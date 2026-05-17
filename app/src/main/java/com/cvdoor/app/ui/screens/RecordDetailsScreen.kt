package com.cvdoor.app.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.cvdoor.app.data.AnalysisOut
import com.cvdoor.app.ui.theme.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RecordDetailsScreen(
    resume: String,
    jd: String,
    optimized: String,
    before: Int,
    after: Int,
    dimsBefore: List<Int>,
    dimsAfter: List<Int>,
    analysis: AnalysisOut? = null,   // ✅ 新增
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val clip: ClipboardManager = LocalClipboardManager.current
    val scroll = rememberScrollState()

    Surface(color = NightNavy) {
        Scaffold(
            containerColor = NightNavy,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) { Text("←  Back", color = TextSecondary) }
                    Spacer(Modifier.weight(1f))
                }
            }
        ) { inner ->
            Column(
                modifier = Modifier
                    .padding(inner)
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                /* --- Match Rate & Dimensions --- */
                Card(colors = CardDefaults.cardColors(containerColor = NightElevated)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Match Rate", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Before: ${before}%      After: ${after}%", color = TextSecondary, fontSize = 13.sp)
                        LinearProgressIndicator(
                            progress = (after / 100f).coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                            color = NeonBlueEnd, trackColor = Track
                        )
                        Spacer(Modifier.height(6.dp))
                        LegendRow()

                        val names = listOf("Fmt","KW","Sem","Title","Read","Rec")
                        val b = (dimsBefore + List(6){0}).take(6)
                        val a = (dimsAfter  + List(6){0}).take(6)
                        Spacer(Modifier.height(4.dp))
                        names.indices.forEach { i ->
                            Text(names[i], color = TextPrimary, fontSize = 14.sp)
                            DimBar(
                                before = b[i], after = a[i],
                                beforeColor = NeonBlueStart, afterColor = NeonBlueEnd, track = Track
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }

                /* --- Original Resume --- */
                Card(colors = CardDefaults.cardColors(containerColor = NightElevated)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Original Resume", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { clip.setText(AnnotatedString(resume)) }) { Text("Copy") }
                        }
                        Text(resume.ifBlank { "(empty)" }, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }

                /* --- Optimized Resume --- */
                Card(colors = CardDefaults.cardColors(containerColor = NightElevated)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Optimized Resume", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { exportOptimizedToPdf(ctx, optimized) }) { Text("Export") }
                        }
                        Text(optimized.ifBlank { "(backend not returned)" }, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { clip.setText(AnnotatedString(optimized)) },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("Copy Optimized") }
                    }
                }

                /* --- Job Description --- */
                Card(colors = CardDefaults.cardColors(containerColor = NightElevated)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Job Description", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(jd.ifBlank { "(empty)" }, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }

                /* --- Analysis（新增） --- */
                Card(colors = CardDefaults.cardColors(containerColor = NightElevated)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Analysis", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        if (analysis == null) {
                            Text("No analysis available.", color = TextSecondary)
                        } else {
                            analysis.overall?.let { ov ->
                                if (!ov.summary.isNullOrBlank()) Text("• ${ov.summary}", color = TextPrimary)
                                if (ov.strengths.isNotEmpty()) {
                                    Text("Strengths:", color = TextSecondary)
                                    ov.strengths.forEach { Text("• $it", color = TextPrimary) }
                                }
                                if (ov.issues.isNotEmpty()) {
                                    Text("Issues:", color = TextSecondary)
                                    ov.issues.forEach { Text("• $it", color = TextPrimary) }
                                }
                                if (ov.actions.isNotEmpty()) {
                                    Text("Actions:", color = TextSecondary)
                                    ov.actions.forEach { Text("• $it", color = TextPrimary) }
                                }
                                Divider()
                            }

                            if (analysis.dimensions.isNotEmpty()) {
                                Text("By Dimension", color = TextSecondary)
                                analysis.dimensions.forEach { d ->
                                    val n = d.name ?: "Unknown"
                                    Text("$n  ${d.before ?: 0}% → ${d.after ?: 0}%", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                    if (d.reasons.isNotEmpty()) {
                                        Text("Reasons:", color = TextSecondary); d.reasons.forEach { Text("• $it", color = TextPrimary) }
                                    }
                                    if (d.problems.isNotEmpty()) {
                                        Text("Problems:", color = TextSecondary); d.problems.forEach { Text("• $it", color = TextPrimary) }
                                    }
                                    if (d.suggestions.isNotEmpty()) {
                                        Text("Suggestions:", color = TextSecondary); d.suggestions.forEach { Text("• $it", color = TextPrimary) }
                                    }
                                    if (d.missingBefore.isNotEmpty()) {
                                        Text("Missing (before):", color = TextSecondary); d.missingBefore.forEach { Text("• $it", color = TextPrimary) }
                                    }
                                    if (d.addedAfter.isNotEmpty()) {
                                        Text("Added (after):", color = TextSecondary); d.addedAfter.forEach { Text("• $it", color = TextPrimary) }
                                    }
                                    Divider()
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/* ---------- 圖例 & 維度條 ---------- */

@Composable private fun LegendRow() { /* 與你現有一致，省略… */
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendDot(NeonBlueStart); Text("Before", color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        LegendDot(NeonBlueEnd);   Text("After",  color = TextSecondary, fontSize = 12.sp)
    }
}
@Composable private fun LegendDot(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
}
@Composable private fun DimBar(before: Int, after: Int,
                               beforeColor: androidx.compose.ui.graphics.Color,
                               afterColor: androidx.compose.ui.graphics.Color,
                               track: androidx.compose.ui.graphics.Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator((before / 100f).coerceIn(0f, 1f), Modifier.fillMaxWidth(), beforeColor, track)
        LinearProgressIndicator((after / 100f).coerceIn(0f, 1f), Modifier.fillMaxWidth(), afterColor, track)
    }
}

/* ---------- 導出 Optimized 爲 PDF ---------- */
// 與你現有一致，保留
private fun exportOptimizedToPdf(context: Context, optimized: String) {
    val doc = PdfDocument()
    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
    val page = doc.startPage(pageInfo)
    val canvas = page.canvas
    val titlePaint = Paint().apply { isAntiAlias = true; textSize = 18f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = 0xFFFFFFFF.toInt() }
    val bodyPaint = Paint().apply { isAntiAlias = true; textSize = 12f; color = 0xFFFFFFFF.toInt() }
    canvas.drawColor(0xFF0F172A.toInt())
    var y = 40f
    fun drawLine(t: String) { val max = 92; (if (t.length <= max) listOf(t) else t.chunked(max)).forEach { canvas.drawText(it, 40f, y, bodyPaint); y += 18f } }
    canvas.drawText("CVDoor - Optimized Resume", 40f, y, titlePaint); y += 28f
    (optimized.ifBlank { "(backend not returned)" }).lineSequence().forEach { drawLine(it) }
    doc.finishPage(page)
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val outFile = File(dir, "optimized_resume_$ts.pdf")
    FileOutputStream(outFile).use { doc.writeTo(it) }
    doc.close()
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    val share = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    context.startActivity(Intent.createChooser(share, "Share optimized resume"))
}

private fun Float.toPercent() = (this * 100).roundToInt()
