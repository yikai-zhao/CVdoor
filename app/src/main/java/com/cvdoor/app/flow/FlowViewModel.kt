package com.cvdoor.app.flow

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.cvdoor.app.api.ApiService
import com.cvdoor.app.api.OptimizeReq
import com.cvdoor.app.api.OptimizeResp
import com.cvdoor.app.data.AppDatabase
import com.cvdoor.app.data.SavedResumeEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

enum class FlowPhase { IDLE, RESUME_READY, INDUSTRY_READY, JD_READY, LOADING, SUCCESS, ERROR }
enum class PaymentPhase { IDLE, PENDING, SUCCESS, FAILED }

data class FlowUiState(
    val phase: FlowPhase = FlowPhase.IDLE,
    val savedResumeId: Long? = null,
    val resumeName: String = "",
    val resumeText: String = "",
    val resumeFileName: String = "",
    val industryId: String = "",
    val industry: String = "",
    val targetRole: String = "",
    val region: String = "香港",
    val jdText: String = "",
    val jdLink: String = "",
    val paymentPhase: PaymentPhase = PaymentPhase.IDLE,
    val progressStep: Int = 0,
    val result: FlowResult? = null,
    val editedCoverLetter: String = "",
    val errorMsg: String = ""
)

data class FlowResult(
    val optimizedResume: String,
    val coverLetter: String,
    val score: Int,
    val scoreLabel: String = "",
    val matchedKeywords: List<String>,
    val partialKeywords: List<String> = emptyList(),
    val missingKeywords: List<String>,
    val suggestions: List<String>,
    val atsIssues: List<String> = emptyList(),
    val atsOk: List<String> = emptyList(),
    val dimScores: List<Pair<String, Int>> = emptyList()
)

const val DEFAULT_COVER_LETTER_STYLE = "professional"
private const val MIN_OPTIMIZED_RESUME_LENGTH = 50

class FlowViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(FlowUiState())
    val state: StateFlow<FlowUiState> = _state.asStateFlow()

    private val db by lazy { AppDatabase.get(app) }
    private val api by lazy { ApiService.create() }
    private var optimizeJob: Job? = null

    val savedResumes = db.savedResumeDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun saveResume(name: String, content: String) {
        viewModelScope.launch {
            val id = db.savedResumeDao().insert(SavedResumeEntity(name = name, content = content))
            _state.update {
                it.copy(savedResumeId = id, resumeName = name, resumeText = content,
                    resumeFileName = "", phase = FlowPhase.RESUME_READY)
            }
        }
    }

    fun selectSavedResume(entity: SavedResumeEntity) {
        _state.update {
            it.copy(savedResumeId = entity.id, resumeName = entity.name,
                resumeText = entity.content, resumeFileName = "", phase = FlowPhase.RESUME_READY)
        }
    }

    fun deleteSavedResume(id: Long) { viewModelScope.launch { db.savedResumeDao().deleteById(id) } }

    fun setResumeText(text: String) {
        _state.update {
            it.copy(resumeText = text, resumeFileName = "", savedResumeId = null, resumeName = "",
                phase = if (text.isNotBlank()) FlowPhase.RESUME_READY else FlowPhase.IDLE)
        }
    }

    fun setResumeFile(uri: Uri, displayName: String) {
        viewModelScope.launch {
            val ctx: Context = getApplication()
            try {
                val fileKind = detectResumeFileKind(ctx, uri, displayName)
                val text: String = when (fileKind) {
                    ResumeFileKind.DOCX -> extractDocxText(ctx, uri)
                    ResumeFileKind.DOC -> extractLegacyDocText(ctx, uri)
                    ResumeFileKind.PDF -> extractPdfText(ctx, uri)
                    ResumeFileKind.TEXT -> ctx.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                }
                if (text.isBlank()) {
                    throw IllegalArgumentException("未能提取文件內容，請確認文件可讀或改為貼上文字")
                }
                _state.update {
                    it.copy(resumeText = text, resumeFileName = displayName, resumeName = displayName,
                        savedResumeId = null, errorMsg = "",
                        phase = if (text.isNotBlank()) FlowPhase.RESUME_READY else FlowPhase.IDLE)
                }
            } catch (e: Throwable) {
                _state.update { it.copy(resumeFileName = "", resumeText = "",
                    errorMsg = "文件讀取失敗：${e.message?.take(60) ?: "未知錯誤"}") }
            }
        }
    }

    private enum class ResumeFileKind { DOC, DOCX, PDF, TEXT }

    private fun detectResumeFileKind(ctx: Context, uri: Uri, displayName: String): ResumeFileKind {
        val mimeType = ctx.contentResolver.getType(uri)?.lowercase().orEmpty()
        val lower = displayName.lowercase()
        val header = readFileHeader(ctx, uri, 8)

        return when {
            hasHeaderPrefix(header, byteArrayOf(0x25, 0x50, 0x44, 0x46)) || lower.endsWith(".pdf") || mimeType == "application/pdf" -> ResumeFileKind.PDF
            hasHeaderPrefix(header, byteArrayOf(0x50, 0x4b, 0x03, 0x04)) || lower.endsWith(".docx") || mimeType.contains("wordprocessingml") || mimeType.contains("officedocument") -> ResumeFileKind.DOCX
            hasHeaderPrefix(header, byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte())) || lower.endsWith(".doc") || mimeType.contains("msword") -> ResumeFileKind.DOC
            else -> ResumeFileKind.TEXT
        }
    }

    private fun hasHeaderPrefix(header: ByteArray, prefix: ByteArray): Boolean {
        if (header.size < prefix.size) return false
        for (index in prefix.indices) {
            if (header[index] != prefix[index]) return false
        }
        return true
    }

    private fun readFileHeader(ctx: Context, uri: Uri, size: Int): ByteArray {
        val buffer = ByteArray(size)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val read = input.read(buffer)
            if (read <= 0) return ByteArray(0)
            return if (read == buffer.size) buffer else buffer.copyOf(read)
        }
        return ByteArray(0)
    }

    private suspend fun extractPdfText(ctx: Context, uri: Uri): String {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { document ->
                val directText = PDFTextStripper().getText(document).trim()
                if (directText.length >= 40) {
                    return directText
                }
                val ocrText = extractPdfTextWithOcr(ctx, uri).trim()
                return if (ocrText.isNotBlank()) ocrText else directText
            }
        }
        return ""
    }

    private suspend fun extractPdfTextWithOcr(ctx: Context, uri: Uri): String {
        val chineseRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val results = linkedSetOf<String>()

        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val pagesToScan = minOf(renderer.pageCount, 3)
                for (pageIndex in 0 until pagesToScan) {
                    renderer.openPage(pageIndex).use { page ->
                        val bitmap = renderPageForOcr(page)
                        try {
                            val image = InputImage.fromBitmap(bitmap, 0)
                            val chinese = chineseRecognizer.process(image).await().text.trim()
                            if (chinese.isNotBlank()) results.add(chinese)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }

        chineseRecognizer.close()

        return results.joinToString("\n\n").trim()
    }

    private fun renderPageForOcr(page: PdfRenderer.Page): Bitmap {
        val maxDimension = 1600
        val width = page.width
        val height = page.height
        val scale = minOf(
            maxDimension.toFloat() / width.toFloat(),
            maxDimension.toFloat() / height.toFloat(),
            2.0f
        ).coerceAtLeast(1.0f)
        val bitmapWidth = (width * scale).toInt().coerceAtLeast(1)
        val bitmapHeight = (height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun extractDocxText(ctx: Context, uri: Uri): String {
        val sb = StringBuilder()
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val zip = java.util.zip.ZipInputStream(input)
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val entryBytes = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var read = zip.read(buffer)
                    while (read > 0) {
                        entryBytes.write(buffer, 0, read)
                        read = zip.read(buffer)
                    }
                    val xml = entryBytes.toString(Charsets.UTF_8.name())
                    val text = xml
                        .replace("</w:p>", "\n")
                        .replace("<w:tab/>", "\t")
                        .replace(Regex("<w:br[^>]*/>"), "\n")
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\"")
                        .replace("&apos;", "'")
                        .replace(Regex("[ \t]{2,}"), " ")
                        .replace(Regex("\n{3,}"), "\n\n")
                        .trim()
                    sb.append(text)
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return sb.toString()
    }

    private fun extractLegacyDocText(ctx: Context, uri: Uri): String {
        val bytes = readAllBytesLimited(ctx, uri, 6 * 1024 * 1024)
        if (bytes.isEmpty()) return ""

        val merged = buildString {
            append(String(bytes, Charsets.UTF_16LE))
            append('\n')
            append(String(bytes, Charsets.ISO_8859_1))
        }.replace('\u0000', ' ')

        val lines = merged.lines()
            .map { it.trim() }
            .filter { it.length >= 3 }
            .filter { line -> line.count { isReadableChar(it) } >= 3 }
            .filterNot { line -> line.startsWith("PK") }
            .distinct()

        return lines.joinToString("\n").take(12000).trim()
    }

    private fun readAllBytesLimited(ctx: Context, uri: Uri, maxBytes: Int): ByteArray {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val canWrite = minOf(read, maxBytes - total)
                if (canWrite <= 0) break
                out.write(buffer, 0, canWrite)
                total += canWrite
                if (total >= maxBytes) break
            }
            return out.toByteArray()
        }
        return ByteArray(0)
    }

    private fun isReadableChar(ch: Char): Boolean {
        return ch.isLetterOrDigit() || ch in "，。！？、；：,.!?;:-_()（）[]{}%+*/#@&$'\" " ||
            ch in '\u4E00'..'\u9FFF'
    }

    // Kept for backward compatibility with legacy ResumeInputScreen.
    fun clearResumeFile() {
        _state.update {
            it.copy(resumeFileName = "", resumeText = "", resumeName = "", savedResumeId = null,
                phase = FlowPhase.IDLE)
        }
    }

    fun clearResumeSelection() {
        _state.update { it.copy(savedResumeId = null, resumeName = "", resumeText = "",
            resumeFileName = "", phase = FlowPhase.IDLE) }
    }

    fun setIndustry(id: String, name: String) {
        _state.update { it.copy(industryId = id, industry = name) }
    }

    fun setTargetRole(role: String) { _state.update { it.copy(targetRole = role) } }
    fun setRegion(region: String) { _state.update { it.copy(region = region) } }

    fun confirmIndustry() {
        _state.update { it.copy(phase = FlowPhase.INDUSTRY_READY) }
    }

    fun setJdText(text: String) {
        _state.update {
            it.copy(jdText = text,
                phase = if (text.isNotBlank()) FlowPhase.JD_READY else FlowPhase.INDUSTRY_READY)
        }
    }

    fun setJdLink(link: String) { _state.update { it.copy(jdLink = link) } }

    fun onPaymentSuccess() { _state.update { it.copy(paymentPhase = PaymentPhase.SUCCESS) } }
    fun onPaymentFailed(msg: String) {
        _state.update { it.copy(paymentPhase = PaymentPhase.FAILED, errorMsg = msg) }
    }
    fun onPaymentPending() { _state.update { it.copy(paymentPhase = PaymentPhase.PENDING) } }
    fun resetPayment() { _state.update { it.copy(paymentPhase = PaymentPhase.IDLE, errorMsg = "") } }

    fun updateCoverLetter(text: String) { _state.update { it.copy(editedCoverLetter = text) } }

    fun updateOptimizedResume(text: String) {
        _state.update { current ->
            val r = current.result ?: return@update current
            current.copy(result = r.copy(optimizedResume = text))
        }
    }

    fun regenerateCoverLetter(style: String = DEFAULT_COVER_LETTER_STYLE) {
        val s = _state.value
        if (s.resumeText.isBlank() || s.jdText.isBlank()) return
        
        viewModelScope.launch {
            _state.update { it.copy(phase = FlowPhase.LOADING, progressStep = 4, errorMsg = "") }
            try {
                val resp = api.generateCoverLetter(OptimizeReq(s.resumeText, s.jdText, null, style))
                if (resp.coverLetter.isBlank()) {
                    _state.update { it.copy(phase = FlowPhase.ERROR, errorMsg = "AI 未能生成求职信") }
                    return@launch
                }
                _state.update { current ->
                    val r = current.result ?: return@update current
                    current.copy(
                        editedCoverLetter = resp.coverLetter,
                        result = r.copy(coverLetter = resp.coverLetter),
                        phase = FlowPhase.SUCCESS,
                        progressStep = 5
                    )
                }
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("timeout", true) == true -> "请求超时，请重试"
                    e.message?.contains("Unable to resolve host", true) == true -> "网络不可用"
                    else -> e.message ?: "求职信生成失败，请重试"
                }
                _state.update { it.copy(phase = FlowPhase.ERROR, errorMsg = msg) }
            }
        }
    }

    fun startOptimize() {
        val s = _state.value
        if (s.resumeText.isBlank() || s.jdText.isBlank()) return
        optimizeJob?.cancel()
        optimizeJob = viewModelScope.launch {
            _state.update { it.copy(phase = FlowPhase.LOADING, progressStep = 0, errorMsg = "") }
            val stepJob = launch {
                for (i in 1..5) { delay(900L); _state.update { it.copy(progressStep = i) } }
            }
            try {
                val resp: OptimizeResp = api.optimize(OptimizeReq(s.resumeText, s.jdText, null))
                stepJob.cancel()
                for (i in 4..5) { _state.update { it.copy(progressStep = i) }; delay(400) }

                // ── Validate AI response ──────────────────────────────────────
                if (resp.optimized.isBlank()) {
                    _state.update { it.copy(phase = FlowPhase.ERROR, errorMsg = "AI 未返回优化简历，请重试") }
                    return@launch
                }
                if (resp.optimized.length < MIN_OPTIMIZED_RESUME_LENGTH) {
                    _state.update { it.copy(phase = FlowPhase.ERROR, errorMsg = "AI 返回内容过短，请重试") }
                    return@launch
                }
                val safeScore = resp.afterTotal.coerceIn(0, 100)

                val analysis = resp.analysis
                val matched = resp.addedKeywords.orEmpty()
                val missing = analysis?.dimensions?.flatMap { it.missingBefore.orEmpty() }
                    ?.distinct()?.take(10) ?: emptyList()
                val suggestions = analysis?.overall?.actions?.take(4)
                    ?: listOf("增加与JD相关的关键词", "强化成果量化表达", "补充岗位要求中的核心技能")
                
                // 使用后端返回的 cover_letter（真实 AI 生成）
                val coverLetter = resp.coverLetter?.takeIf { it.isNotBlank() }
                    ?: "无法生成求职信，请重试"
                
                val dimScores = analysis?.dimensions?.mapNotNull { d ->
                    if (d.name != null && d.after != null) d.name to d.after else null
                } ?: emptyList()
                val atsOk = mutableListOf("使用标准 Section 标题", "无复杂图表/表格", "关键词可被 ATS 读取")
                val atsIssues = mutableListOf<String>()
                val longBullets = resp.optimized.lines().count { it.startsWith("•") && it.length > 120 }
                if (longBullets > 0) atsIssues.add("$longBullets 条 bullet 过长，建议精简")
                if (resp.optimized.contains("[")) atsIssues.add("检测到未填写的占位符，请填写真实数据")

                s.savedResumeId?.let { db.savedResumeDao().incrementAndUpdateMeta(it, s.industry, s.targetRole) }

                val scoreLabel = when {
                    safeScore >= 85 -> "ATS 友好度：High"
                    safeScore >= 70 -> "ATS 友好度：Medium"
                    else -> "ATS 友好度：Low — 需改进"
                }
                _state.update {
                    it.copy(
                        phase = FlowPhase.SUCCESS,
                        result = FlowResult(resp.optimized, coverLetter, safeScore, scoreLabel,
                            matched, emptyList(), missing, suggestions, atsIssues, atsOk, dimScores),
                        editedCoverLetter = coverLetter
                    )
                }
            } catch (e: Exception) {
                stepJob.cancel()
                val msg = when {
                    e.message?.contains("timeout", true) == true -> "请求超时，请重试"
                    e.message?.contains("Unable to resolve host", true) == true -> "网络不可用"
                    else -> e.message ?: "生成失败，请重试"
                }
                _state.update { it.copy(phase = FlowPhase.ERROR, errorMsg = msg) }
            }
        }
    }

    fun retry() { _state.update { it.copy(phase = FlowPhase.JD_READY, errorMsg = "") } }

    /**
     * Replace [placeholder] tokens in the optimized resume (and cover letter) with the
     * real values the user has just entered in DataSupplementScreen.
     * Replacement is positional: the Nth non-blank value fills the Nth [token] found.
     */
    fun applyDataSupplement(values: List<String>) {
        _state.update { current ->
            val r = current.result ?: return@update current
            val nonBlankValues = values.filter { it.isNotBlank() }
            if (nonBlankValues.isEmpty()) return@update current

            val placeholderRegex = Regex("""\[[^\]]+\]""")

            fun applyTo(text: String): String {
                var idx = 0
                return placeholderRegex.replace(text) { match ->
                    val replacement = nonBlankValues.getOrNull(idx)
                    if (replacement != null) { idx++; replacement } else match.value
                }
            }

            val updatedResume = applyTo(r.optimizedResume)
            val updatedCover = applyTo(
                current.editedCoverLetter.ifBlank { r.coverLetter }
            )
            current.copy(
                result = r.copy(optimizedResume = updatedResume, coverLetter = updatedCover),
                editedCoverLetter = updatedCover
            )
        }
    }

    fun reset() { optimizeJob?.cancel(); _state.value = FlowUiState() }
}
