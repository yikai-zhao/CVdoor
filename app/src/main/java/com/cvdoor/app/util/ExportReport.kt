package com.cvdoor.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.cvdoor.app.data.OptimizationRecord
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/** 将“最近一次优化”导出为 PDF 并唤起系统分享面板 */
fun exportOptimizationReportPdf(context: Context, rec: OptimizationRecord) {
    // A4: 595 x 842 pt（72dpi），简单排版
    val doc = PdfDocument()
    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
    val page = doc.startPage(pageInfo)
    val c = page.canvas

    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        isFakeBoldText = true
        textSize = 18f
    }
    val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 12f
    }

    var y = 40f
    c.drawText("CVDoor Optimization Report", 40f, y, title); y += 26f
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
    c.drawText("Generated: $time", 40f, y, body); y += 18f
    c.drawText("Before: ${rec.beforeTotal}%   After: ${rec.afterTotal}%", 40f, y, body); y += 20f

    val labels = listOf("Fmt", "KW", "Sem", "Title", "Read", "Rec")
    labels.forEachIndexed { i, lab ->
        val b = rec.dimsBefore.getOrNull(i) ?: 0
        val a = rec.dimsAfter.getOrNull(i) ?: 0
        c.drawText("$lab :  $b%  →  $a%", 40f, y, body)
        y += 16f
    }
    y += 10f

    // 摘要（避免把整篇大文本写进 PDF）
    val resumeSnippet = rec.resumeText.replace("\n", " ").take(200)
    val jdSnippet = rec.jdText.replace("\n", " ").take(200)

    c.drawText("Resume snippet: $resumeSnippet", 40f, y, body); y += 36f
    c.drawText("JD snippet: $jdSnippet", 40f, y, body)

    doc.finishPage(page)

    val out = File(context.cacheDir, "cvdoor_report_${System.currentTimeMillis()}.pdf")
    doc.writeTo(FileOutputStream(out))
    doc.close()

    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(share, "Share optimization report"))
}
