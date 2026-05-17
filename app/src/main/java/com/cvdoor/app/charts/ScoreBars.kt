package com.cvdoor.app.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cvdoor.app.score.ScoreBreakdown

private val dimLabels = listOf("格式", "關鍵詞", "語義", "職位", "可讀性", "最近度")

@Composable
fun ScoreCompareBars(
    before: ScoreBreakdown,
    after: ScoreBreakdown,
    modifier: Modifier = Modifier
) {
    val beforeList = listOf(before.format, before.keywords, before.semantic, before.titleMatch, before.readability, before.recency)
    val afterList  = listOf(after.format,  after.keywords,  after.semantic,  after.titleMatch,  after.readability,  after.recency)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        dimLabels.forEachIndexed { idx, label ->
            MetricRow(label, beforeList[idx], afterList[idx])
        }
    }
}

@Composable
private fun MetricRow(label: String, before: Int, after: Int) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(text = "原始 $before / 優化 $after", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(6.dp))
        // 兩條“並排”柱狀進度（0~100），綠色=優化後，藍色=原始
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(MaterialTheme.shapes.small)
                .background(Color(0xFFE9ECF3))
        ) {
            val totalWidth = 1f
            val beforeWidth = (before / 100f).coerceIn(0f, 1f)
            val afterWidth = (after / 100f).coerceIn(0f, 1f)

            // 原始（左半透明藍）
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(beforeWidth * totalWidth)
                    .background(Color(0xFF7BA7FF).copy(alpha = 0.55f))
            )
            // 在上面疊加“優化後”（綠色），若更高會覆蓋更多寬度
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(afterWidth * totalWidth)
                    .background(Color(0xFF4CD2AF).copy(alpha = 0.7f))
            )
        }
    }
}
