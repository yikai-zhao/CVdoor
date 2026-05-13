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
                    Text("ATS 优化效果预览", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
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
                        "以下为示例展示。支付后，系统将根据你的简历、行业和 JD 生成专属结果。",
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
                            Text("原价 HK$9.9", fontSize = 13.sp, color = TextSecondary,
                                style = LocalTextStyle.current.copy(
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                                ))
                        }
                        Text("包含：ATS 优化 + 完整简历 + Cover Letter",
                            fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            // ATS Score demo
            item {
                DemoCard("示例 ATS 匹配评分") {
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
                            Text("示例岗位：${state.targetRole.ifBlank { "Marketing Assistant" }}",
                                fontSize = 13.sp, color = TextSecondary)
                        }
                    }
                }
            }

            // Keyword match demo
            item {
                DemoCard("示例行业关键词匹配") {
                    val demoMatched = industryDef?.sampleKeywords?.take(3)
                        ?: listOf("Social Media", "Content Writing", "Customer Service")
                    val demoPartial = listOf("SEO", "Campaign Support")
                    val demoMissing = listOf("CRM", "Google Analytics")

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        KeywordGroup("✅ 已明确体现", demoMatched, AccentGreen.copy(alpha = 0.15f), AccentGreen)
                        KeywordGroup("⚠ 建议加强", demoPartial, AccentYellow.copy(alpha = 0.12f), AccentYellow)
                        KeywordGroup("❌ 尚未体现", demoMissing, Color.Red.copy(alpha = 0.1f), Color(0xFFFF6B6B))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("仅展示部分关键词，完整分析将在支付后根据你的 JD 生成。",
                        fontSize = 12.sp, color = TextSecondary)
                }
            }

            // Before/After demo
            item {
                DemoCard("职责转成果示例") {
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
                DemoCard("真实数据补充引导示例") {
                    Text("不编造数字，只引导你填写真实数据：",
                        fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DataPromptRow("每周发布内容数量：")
                        DataPromptRow("平均互动量：")
                        DataPromptRow("粉丝增长：", suffix = "%")
                    }
                }
            }

            // ATS format demo
            item {
                DemoCard("ATS 友好格式检查示例") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CheckItem("✓", "使用标准 Section 标题", AccentGreen)
                        CheckItem("✓", "无复杂表格", AccentGreen)
                        CheckItem("✓", "关键词可被系统读取", AccentGreen)
                        CheckItem("✓", "Bullet 长度适合 ATS 筛选", AccentGreen)
                    }
                }
            }

            // Resume structure preview
            item {
                DemoCard("示例优化简历结构") {
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
                DemoCard("示例 Cover Letter 预览") {
                    Text(
                        "Dear Hiring Manager,\n\nI am writing to express my interest in the ${state.targetRole.ifBlank { "position" }} role at your esteemed organization. With my background in ${state.industry.ifBlank { "the field" }}, I am confident I can contribute meaningfully to your team...\n\n[完整内容将在支付后生成]",
                        fontSize = 13.sp, color = TextSecondary, lineHeight = 20.sp
                    )
                }
            }

            // Pre-submit check demo
            item {
                DemoCard("投递前检查示例") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CheckItem("✓", "联系方式完整", AccentGreen)
                        CheckItem("✓", "无未填写占位符", AccentGreen)
                        CheckItem("✓", "Cover Letter 与 JD 一致", AccentGreen)
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
                        Text("支付后你将获得：", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = TextPrimary, modifier = Modifier.padding(bottom = 12.dp))
                        val items = listOf(
                            "专属 ATS 匹配评分", "完整 JD 关键词分析", "ATS 友好格式优化",
                            "职责转成果表达", "真实数据补充引导", "完整优化简历",
                            "Cover Letter", "投递前检查", "Word 导出", "自动保存历史记录"
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
                    Text("活动价 ", fontSize = 13.sp, color = TextSecondary)
                    Text("HK$4.9", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                    Text("  原价 HK$9.9", fontSize = 13.sp, color = TextSecondary)
                }
                GradientCta("立即生成我的专属 ATS 结果", onClick = onPayNow)
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
                Text("优化前", fontSize = 11.sp, color = Color(0xFFFF6B6B), fontWeight = FontWeight.SemiBold)
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
                Text("优化后", fontSize = 11.sp, color = AccentGreen, fontWeight = FontWeight.SemiBold)
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
