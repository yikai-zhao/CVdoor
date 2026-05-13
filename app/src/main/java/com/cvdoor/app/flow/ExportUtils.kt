package com.cvdoor.app.flow

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat { WORD, PDF }

data class ExportPackage(
    val resume: String,
    val coverLetter: String,
    val title: String = "CVDoor_Export"
)

fun hasUnfilledPlaceholders(text: String): Boolean {
    if (text.isBlank()) return false
    return text.contains("[") || text.contains("__")
}

fun sanitizeUnfilledPlaceholders(text: String): String {
    if (text.isBlank()) return text
    return text
        .replace(Regex("\\[[^\\]]*\\]"), "")
        .replace(Regex("_{2,}"), "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

fun exportPackage(context: Context, pkg: ExportPackage, format: ExportFormat) {
    when (format) {
        ExportFormat.PDF -> exportPdf(context, pkg)
        ExportFormat.WORD -> exportWord(context, pkg)
    }
}

private fun exportWord(context: Context, pkg: ExportPackage) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val outFile = File(dir, "${pkg.title}_$ts.doc")

    val content = buildString {
        appendLine("CVDoor ATS Optimization")
        appendLine()
        appendLine("===== Optimized Resume =====")
        appendLine(pkg.resume.ifBlank { "(empty)" })
        appendLine()
        appendLine("===== Cover Letter =====")
        appendLine(pkg.coverLetter.ifBlank { "(empty)" })
    }
    outFile.writeText(content)

    shareFile(
        context = context,
        file = outFile,
        mimeType = "application/msword",
        chooserTitle = "导出 Word"
    )
}

private fun exportPdf(context: Context, pkg: ExportPackage) {
    val doc = PdfDocument()
    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
    val page = doc.startPage(pageInfo)
    val canvas = page.canvas

    val titlePaint = Paint().apply {
        isAntiAlias = true
        textSize = 17f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = 0xFF111111.toInt()
    }
    val bodyPaint = Paint().apply {
        isAntiAlias = true
        textSize = 11f
        color = 0xFF222222.toInt()
    }

    var y = 40f
    fun line(text: String, paint: Paint = bodyPaint) {
        canvas.drawText(text, 36f, y, paint)
        y += 18f
    }

    canvas.drawColor(0xFFFFFFFF.toInt())
    line("CVDoor ATS Optimization", titlePaint)
    line(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
    y += 8f

    fun block(header: String, text: String) {
        line(header, titlePaint)
        text.ifBlank { "(empty)" }.wrap(75).forEach { line(it) }
        y += 8f
    }

    block("Optimized Resume", pkg.resume)
    block("Cover Letter", pkg.coverLetter)

    doc.finishPage(page)

    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val outFile = File(dir, "${pkg.title}_$ts.pdf")
    FileOutputStream(outFile).use { doc.writeTo(it) }
    doc.close()

    shareFile(
        context = context,
        file = outFile,
        mimeType = "application/pdf",
        chooserTitle = "导出 PDF"
    )
}

private fun shareFile(context: Context, file: File, mimeType: String, chooserTitle: String) {
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

private fun String.wrap(maxCols: Int): List<String> {
    if (isBlank()) return listOf("")
    val out = mutableListOf<String>()
    var i = 0
    while (i < length) {
        val end = (i + maxCols).coerceAtMost(length)
        out += substring(i, end)
        i = end
    }
    return out
}
