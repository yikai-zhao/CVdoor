package com.cvdoor.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.data.OptimizationRecord
import com.cvdoor.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

@Composable
fun AtsTrendScreen(
    records: List<OptimizationRecord>,
    onBack: () -> Unit
) {
    val sorted = remember(records) { records.sortedBy { it.createdAt } }
    val highest = remember(sorted) { sorted.maxByOrNull { it.afterTotal } }
    val lowest  = remember(sorted) { sorted.minByOrNull { it.afterTotal } }
    val avgAfter = remember(sorted) { if (sorted.isEmpty()) 0 else sorted.map { it.afterTotal }.average().roundToInt() }
    val avgBefore = remember(sorted) { if (sorted.isEmpty()) 0 else sorted.map { it.beforeTotal }.average().roundToInt() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Night)
            .statusBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Back", color = TextSecondary) }
            Spacer(Modifier.weight(1f))
            Text("ATS Trend", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(60.dp))
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                // Chart
                AtsLineChart(records = sorted)
            }

            item {
                // Stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(Modifier.weight(1f), "Avg Before", "$avgBefore%", AccentBlue)
                    StatCard(Modifier.weight(1f), "Avg After", "$avgAfter%", AccentGreen)
                    StatCard(Modifier.weight(1f), "Total Scans", "${sorted.size}", NeonBlueEnd)
                }
            }

            if (highest != null) {
                item {
                    RecordHighlightCard(
                        label = "🏆 Highest Match",
                        score = highest.afterTotal,
                        jdText = highest.jdText,
                        date = highest.createdAt,
                        scoreColor = AccentGreen
                    )
                }
            }

            if (lowest != null && lowest.id != highest?.id) {
                item {
                    RecordHighlightCard(
                        label = "📉 Lowest Match",
                        score = lowest.afterTotal,
                        jdText = lowest.jdText,
                        date = lowest.createdAt,
                        scoreColor = NeonPink
                    )
                }
            }

            item {
                AiInsightsCard(records = sorted, avgAfter = avgAfter)
            }
        }
    }
}

@Composable
private fun AtsLineChart(records: List<OptimizationRecord>) {
    val beforeColor = AccentBlue
    val afterColor  = AccentGreen

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NightElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Score Over Time", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(beforeColor, "Before")
                LegendDot(afterColor, "After")
            }
            Spacer(Modifier.height(12.dp))

            if (records.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No data yet", color = TextSecondary, fontSize = 14.sp)
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val padH = 16f
                    val n = records.size

                    fun xOf(i: Int) = if (n == 1) w / 2f else i.toFloat() / (n - 1) * w
                    fun yOf(score: Int) = h - padH - (score / 100f) * (h - 2 * padH)

                    // Grid lines at 25, 50, 75
                    listOf(25, 50, 75).forEach { line ->
                        val y = yOf(line)
                        drawLine(
                            color = Color.White.copy(alpha = 0.07f),
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1f
                        )
                    }

                    if (n >= 2) {
                        // Before path
                        val beforePath = Path().apply {
                            records.forEachIndexed { i, r ->
                                val x = xOf(i); val y = yOf(r.beforeTotal)
                                if (i == 0) moveTo(x, y) else lineTo(x, y)
                            }
                        }
                        drawPath(beforePath, color = beforeColor.copy(alpha = 0.6f), style = Stroke(width = 2f, cap = StrokeCap.Round))

                        // After path
                        val afterPath = Path().apply {
                            records.forEachIndexed { i, r ->
                                val x = xOf(i); val y = yOf(r.afterTotal)
                                if (i == 0) moveTo(x, y) else lineTo(x, y)
                            }
                        }
                        drawPath(afterPath, color = afterColor.copy(alpha = 0.9f), style = Stroke(width = 2.5f, cap = StrokeCap.Round))
                    }

                    // Dots
                    records.forEachIndexed { i, r ->
                        drawCircle(beforeColor, radius = 4f, center = Offset(xOf(i), yOf(r.beforeTotal)))
                        drawCircle(afterColor, radius = 4f, center = Offset(xOf(i), yOf(r.afterTotal)))
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Text(label, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun StatCard(modifier: Modifier, label: String, value: String, accent: Color) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NightElevated),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, color = accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(label, color = TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RecordHighlightCard(
    label: String,
    score: Int,
    jdText: String,
    date: Long,
    scoreColor: Color
) {
    val title = jdText.lineSequence().firstOrNull()?.trim()?.take(40).orEmpty().ifBlank { "Optimization" }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NightElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(date)),
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
            Text("$score%", color = scoreColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AiInsightsCard(records: List<OptimizationRecord>, avgAfter: Int) {
    val insights = buildInsights(records, avgAfter)
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NightElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("✨ AI Insights", color = NeonBlueEnd, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            insights.forEach { line ->
                Text("• $line", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

private fun buildInsights(records: List<OptimizationRecord>, avgAfter: Int): List<String> {
    if (records.isEmpty()) return listOf("No optimization history yet. Start your first scan!")
    val list = mutableListOf<String>()
    list += "Average ATS score after optimization: $avgAfter%"
    val avgGain = records.map { it.afterTotal - it.beforeTotal }.average().roundToInt()
    list += "Average score improvement per optimization: +$avgGain%"
    val best = records.maxByOrNull { it.afterTotal }
    if (best != null) {
        val title = best.jdText.lineSequence().firstOrNull()?.trim()?.take(30).orEmpty().ifBlank { "a role" }
        list += "Best match was for: $title (${best.afterTotal}%)"
    }
    if (avgAfter >= 80) list += "Great performance! You're consistently hitting strong ATS scores."
    else if (avgAfter >= 60) list += "Good progress. Try adding more keywords from job descriptions."
    else list += "Consider tailoring your resume more closely to each job description."
    return list
}
