package com.cvdoor.app.flow

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.ui.theme.*

// 靜態 Demo 內容（不呼叫 AI，僅展示已準備好的示例）
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DemoPreviewScreen(
    state: FlowUiState,
    onBack: () -> Unit,
    onPayNow: () -> Unit    // → PaymentScreen
) {
    val industryDef = IndustryData.findById(state.industryId)

    Box(modifier = Modifier.fillMaxSize().background(NightNavy)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top bar
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                    Text("ATS 優化效果預覽", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        color = TextPrimary, modifier = Modifier.weight(1f).padding(start = 4.dp))
                }
            }

            // Notice banner
            item {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AccentYellow.copy(alpha = 0.12f))
                        .border(1.dp, AccentYellow.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        "以下爲示例展示。支付後，系統將根據你的簡歷、行業和 JD 生成專屬結果。",
                        fontSize = 13.sp, color = AccentYellow, lineHeight = 20.sp
                    )
                }
            }

            // Price card
            item {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF1A2550), Color(0xFF0D1E45))))
                        .border(1.dp, AccentBlue.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(20.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("HK$4.9", fontSize = 32.sp, fontWeight = FontWeight.Bold,
                                color = AccentGreen)
                            Spacer(Modifier.width(8.dp))
                            Text("/ 次", fontSize = 14.sp, color = TextSecondary,
                                modifier = Modifier.padding(bottom = 4.dp))
                            Spacer(Modifier.weight(1f))
                            Text("原價 HK$9.9", fontSize = 13.sp, color = TextSecondary,
                                style = LocalTextStyle.current.copy(
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                                ))
                        }
                        Text("包含：ATS 優化 + 完整簡歷 + Cover Letter",
                            fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            // ATS Score demo
            item {
                DemoCard("示例 ATS 匹配評分") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(50))
                                .background(AccentGreen.copy(alpha = 0.12f))
                                .border(2.dp, AccentGreen, RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("86", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("/ 100", fontSize = 14.sp, color = TextSecondary)
                            Text("ATS 友好度：High", fontSize = 14.sp, color = AccentGreen,
                                fontWeight = FontWeight.SemiBold)
                            Text("示例崗位：${state.targetRole.ifBlank { "Marketing Assistant" }}",
                                fontSize = 13.sp, color = TextSecondary)
                        }
                    }
                }
            }

            // Keyword match demo
            item {
                DemoCard("示例行業關鍵詞匹配") {
                    val demoMatched = industryDef?.sampleKeywords?.take(3)
                        ?: listOf("Social Media", "Content Writing", "Customer Service")
                    val demoPartial = listOf("SEO", "Campaign Support")
                    val demoMissing = listOf("CRM", "Google Analytics")

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        KeywordGroup("✅ 已明確體現", demoMatched, AccentGreen.copy(alpha = 0.15f), AccentGreen)
                        KeywordGroup("⚠ 建議加強", demoPartial, AccentYellow.copy(alpha = 0.12f), AccentYellow)
                        KeywordGroup("❌ 尚未體現", demoMissing, Color.Red.copy(alpha = 0.1f), Color(0xFFFF6B6B))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("僅展示部分關鍵詞，完整分析將在支付後根據你的 JD 生成。",
                        fontSize = 12.sp, color = TextSecondary)
                }
            }

            // Before/After demo
            item {
                DemoCard("職責轉成果示例") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BeforeAfterRow(
                            before = "Responsible for posting social media content.",
                            after = "Supported social media content planning and scheduling across 3 platforms, increasing post consistency by 40%."
                        )
                    }
                }
            }

            // Real data prompts demo
            item {
                DemoCard("真實數據補充引導示例") {
                    Text("不編造數字，只引導你填寫真實數據：",
                        fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DataPromptRow("每週發佈內容數量：")
                        DataPromptRow("平均互動量：")
                        DataPromptRow("粉絲增長：", suffix = "%")
                    }
                }
            }

            // ATS format demo
            item {
                DemoCard("ATS 友好格式檢查示例") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CheckItem("✓", "使用標準 Section 標題", AccentGreen)
                        CheckItem("✓", "無複雜表格", AccentGreen)
                        CheckItem("✓", "關鍵詞可被系統讀取", AccentGreen)
                        CheckItem("✓", "Bullet 長度適合 ATS 篩選", AccentGreen)
                    }
                }
            }

            // Resume structure preview
            item {
                DemoCard("示例優化簡歷結構") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Professional Summary", "Key Skills", "Work Experience",
                            "Education", "Certifications").forEach { section ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null,
                                    tint = AccentBlue, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(section, fontSize = 13.sp, color = TextPrimary)
                            }
                        }
                    }
                }
            }

            // Cover letter preview
            item {
                DemoCard("示例 Cover Letter 預覽") {
                    Text(
                        "Dear Hiring Manager,\n\nI am writing to express my interest in the ${state.targetRole.ifBlank { "position" }} role at your esteemed organization. With my background in ${state.industry.ifBlank { "the field" }}, I am confident I can contribute meaningfully to your team...\n\n[完整內容將在支付後生成]",
                        fontSize = 13.sp, color = TextSecondary, lineHeight = 20.sp
                    )
                }
            }

            // Pre-submit check demo
            item {
                DemoCard("投遞前檢查示例") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CheckItem("✓", "聯繫方式完整", AccentGreen)
                        CheckItem("✓", "無未填寫佔位符", AccentGreen)
                        CheckItem("✓", "Cover Letter 與 JD 一致", AccentGreen)
                    }
                }
            }

            // What you'll get
            item {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(NightElevated)
                        .padding(16.dp)
                ) {
                    Column {
                        Text("支付後你將獲得：", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = TextPrimary, modifier = Modifier.padding(bottom = 12.dp))
                        val items = listOf(
                            "專屬 ATS 匹配評分", "完整 JD 關鍵詞分析", "ATS 友好格式優化",
                            "職責轉成果表達", "真實數據補充引導", "完整優化簡歷",
                            "Cover Letter", "投遞前檢查", "Word 導出", "自動保存歷史記錄"
                        )
                        items.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 3.dp)) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null,
                                    tint = AccentGreen, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(item, fontSize = 13.sp, color = TextPrimary)
                            }
                        }
                    }
                }
            }
        }

        // Fixed bottom CTA
        Box(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, NightNavy.copy(alpha = 0.95f), NightNavy)
                    )
                )
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text("活動價 ", fontSize = 13.sp, color = TextSecondary)
                    Text("HK$4.9", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                    Text("  原價 HK$9.9", fontSize = 13.sp, color = TextSecondary)
                }
                GradientCta("立即生成我的專屬 ATS 結果", onClick = onPayNow)
            }
        }
    }
}

@Composable
private fun DemoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardNavy)
            .border(1.dp, Stroke, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AccentBlue,
            modifier = Modifier.padding(bottom = 12.dp))
        content()
    }
}

@Composable
private fun BeforeAfterRow(before: String, after: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Red.copy(alpha = 0.08f))
                .padding(10.dp)
        ) {
            Column {
                Text("優化前", fontSize = 11.sp, color = Color(0xFFFF6B6B), fontWeight = FontWeight.SemiBold)
                Text(before, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = AccentGreen,
            modifier = Modifier.align(Alignment.CenterHorizontally))
        Box(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(AccentGreen.copy(alpha = 0.08f))
                .padding(10.dp)
        ) {
            Column {
                Text("優化後", fontSize = 11.sp, color = AccentGreen, fontWeight = FontWeight.SemiBold)
                Text(after, fontSize = 13.sp, color = TextPrimary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KeywordGroup(label: String, keywords: List<String>, bg: Color, textColor: Color) {
    Column {
        Text(label, fontSize = 12.sp, color = textColor, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            keywords.forEach { kw ->
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(bg)
                        .border(1.dp, textColor.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) { Text(kw, fontSize = 12.sp, color = textColor) }
            }
        }
    }
}

@Composable
private fun DataPromptRow(label: String, suffix: String = "") {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier.width(80.dp).height(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(NightElevated)
                .border(1.dp, Stroke, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("____", fontSize = 13.sp, color = TextSecondary)
        }
        if (suffix.isNotBlank()) {
            Text(" $suffix", fontSize = 13.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun CheckItem(icon: String, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 14.sp, color = color)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = TextPrimary)
    }
}
