package com.cvdoor.app.flow

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.ui.theme.*

@Composable
fun ResultScreen(
    state: FlowUiState,
    onBack: () -> Unit,
    onReset: () -> Unit,
    onCoverLetter: () -> Unit = {},
    onEdit: () -> Unit = {},
    onFillData: () -> Unit = {},
    onPreSubmit: () -> Unit = {}
) {
    val result = state.result ?: return
    val ctx = LocalContext.current
    val clip: ClipboardManager = LocalClipboardManager.current
    val scroll = rememberScrollState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var scoreExpanded by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Night).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top bar ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = TextSecondary)
                }
                Text("優化結果", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onReset) { Text("重新開始", color = NeonBlueEnd, fontSize = 13.sp) }
            }

            // ── Saved banner ──
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(AccentGreen.copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("已自動保存到歷史記錄", fontSize = 12.sp, color = AccentGreen)
            }

            Column(
                modifier = Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(Modifier.height(4.dp))

                // ── Score Card ──
                ScoreCard(result = result, expanded = scoreExpanded, onToggle = { scoreExpanded = !scoreExpanded })

                // ── Tabs ──
                TabSelector(selectedTab = selectedTab, onSelect = { selectedTab = it })

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCoverLetter) {
                        Text("打開 Cover Letter 頁", color = NeonBlueEnd, fontSize = 12.sp)
                    }
                }

                // ── Tab content ──
                val tabText = if (selectedTab == 0) result.optimizedResume else result.coverLetter
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(NightElevated).padding(16.dp)
                ) {
                    Text(
                        text = tabText.ifBlank { "（暫無內容）" },
                        fontSize = 14.sp,
                        color = if (tabText.isNotBlank()) TextPrimary else TextSecondary,
                        lineHeight = 22.sp
                    )
                }

                // ── Copy + Export ──
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            clip.setText(AnnotatedString(tabText))
                            Toast.makeText(ctx, "已複製", Toast.LENGTH_SHORT).show()
                        },
                        enabled = tabText.isNotBlank(),
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent,
                            disabledContainerColor = NightElevated)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                                .background(if (tabText.isNotBlank())
                                    Brush.horizontalGradient(listOf(NeonBlueStart, NeonBlueEnd))
                                else Brush.linearGradient(listOf(NightElevated, NightElevated))),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = if (tabText.isNotBlank()) Color.White else TextSecondary)
                                Text("複製", fontWeight = FontWeight.Medium,
                                    color = if (tabText.isNotBlank()) Color.White else TextSecondary)
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { showExportDialog = true },
                        enabled = tabText.isNotBlank(),
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp,
                            NeonBlueEnd.copy(if (tabText.isNotBlank()) 0.5f else 0.2f)),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = NightElevated)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(15.dp),
                                tint = if (tabText.isNotBlank()) NeonBlueEnd else TextSecondary)
                            Text("導出", color = if (tabText.isNotBlank()) NeonBlueEnd else TextSecondary)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            // ── Bottom action bar ──
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(NightElevated)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BottomAction(icon = Icons.Outlined.Edit, label = "編輯", onClick = onEdit, modifier = Modifier.weight(1f))
                BottomAction(icon = Icons.Outlined.AddChart, label = "填數據", onClick = onFillData, modifier = Modifier.weight(1f))
                BottomAction(icon = Icons.Outlined.TaskAlt, label = "投遞檢查", onClick = onPreSubmit, modifier = Modifier.weight(1f))
                BottomAction(icon = Icons.Outlined.Share, label = "導出", onClick = { showExportDialog = true }, modifier = Modifier.weight(1f))
            }
        }

        if (showExportDialog) {
            val hasUnfilled = hasUnfilledPlaceholders(result.optimizedResume) || hasUnfilledPlaceholders(result.coverLetter)
            ExportConfirmDialog(
                hasUnfilledData = hasUnfilled,
                onDismiss = { showExportDialog = false },
                onGoFillData = {
                    showExportDialog = false
                    onFillData()
                },
                onConfirm = { format, handling ->
                    if (handling == UnfilledHandling.GO_FILL) {
                        showExportDialog = false
                        onFillData()
                    } else {
                        val resume = when (handling) {
                            UnfilledHandling.REMOVE_AND_EXPORT -> sanitizeUnfilledPlaceholders(result.optimizedResume)
                            else -> result.optimizedResume
                        }
                        val letter = when (handling) {
                            UnfilledHandling.REMOVE_AND_EXPORT -> sanitizeUnfilledPlaceholders(result.coverLetter)
                            else -> result.coverLetter
                        }
                        exportPackage(ctx, ExportPackage(resume = resume, coverLetter = letter, title = "cvdoor_result"), format)
                        showExportDialog = false
                    }
                }
            )
        }
    }
}

@Composable
private fun BottomAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = NeonBlueEnd, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center)
    }
}

// ── Score Card ─────────────────────────────────────────────────────────────

@Composable
private fun ScoreCard(result: FlowResult, expanded: Boolean, onToggle: () -> Unit) {
    val score = result.score
    val scoreColor = when {
        score >= 90 -> NeonBlueEnd
        score >= 75 -> AccentGreen
        score >= 60 -> AccentYellow
        else -> NeonPink
    }
    val scoreLabel = result.scoreLabel.ifBlank {
        when {
            score >= 90 -> "ATS 友好度：High"
            score >= 75 -> "ATS 友好度：Medium"
            score >= 60 -> "ATS 友好度：Medium — 可改進"
            else -> "ATS 友好度：Low — 需改進"
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(NightElevated, NightElevated.copy(0.95f))))
            .border(1.dp, scoreColor.copy(0.25f), RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("匹配評分", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null, tint = TextSecondary)
            }

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$score", fontSize = 48.sp, fontWeight = FontWeight.Black, color = scoreColor)
                Text("/ 100", fontSize = 18.sp, color = TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
            }

            Box(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Track)) {
                Box(modifier = Modifier.fillMaxWidth(score / 100f).fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.horizontalGradient(listOf(NeonBlueStart, scoreColor))))
            }

            Text(scoreLabel, fontSize = 13.sp, color = scoreColor)

            // ATS format summary row
            if (result.atsOk.isNotEmpty() || result.atsIssues.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (result.atsOk.isNotEmpty()) {
                        Box(modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(AccentGreen.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("✓ ${result.atsOk.size} 項通過", fontSize = 11.sp, color = AccentGreen)
                        }
                    }
                    if (result.atsIssues.isNotEmpty()) {
                        Box(modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(AccentYellow.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("⚠ ${result.atsIssues.size} 項需注意", fontSize = 11.sp, color = AccentYellow)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (expanded) "收起詳情  ∧" else "展開查看詳情  ∨", fontSize = 13.sp, color = NeonBlueEnd)
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (result.matchedKeywords.isNotEmpty()) {
                        KeywordSection("已匹配關鍵詞", result.matchedKeywords,
                            AccentGreen.copy(0.15f), AccentGreen)
                    }
                    if (result.partialKeywords.isNotEmpty()) {
                        KeywordSection("部分匹配", result.partialKeywords,
                            AccentYellow.copy(0.15f), AccentYellow)
                    }
                    if (result.missingKeywords.isNotEmpty()) {
                        KeywordSection("缺失關鍵詞", result.missingKeywords,
                            NeonPink.copy(0.12f), NeonPink)
                    }
                    if (result.dimScores.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("維度得分", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            result.dimScores.forEach { (dim, s) ->
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(dim, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(90.dp))
                                    Box(modifier = Modifier.weight(1f).height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)).background(Track)) {
                                        Box(modifier = Modifier.fillMaxWidth(s / 100f).fillMaxHeight()
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(Brush.horizontalGradient(listOf(NeonBlueStart, NeonBlueEnd))))
                                    }
                                    Text("$s", fontSize = 12.sp, color = NeonBlueEnd,
                                        modifier = Modifier.padding(start = 8.dp).width(30.dp), textAlign = TextAlign.End)
                                }
                            }
                        }
                    }
                    if (result.suggestions.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("優化建議", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            result.suggestions.forEach { s ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    Text("•", color = NeonBlueEnd, fontSize = 14.sp)
                                    Text(s, fontSize = 13.sp, color = TextSecondary, lineHeight = 20.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Tab selector ──────────────────────────────────────────────────────────

@Composable
private fun TabSelector(selectedTab: Int, onSelect: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NightElevated).padding(4.dp)) {
        listOf("優化簡歷", "Cover Letter").forEachIndexed { idx, label ->
            val active = idx == selectedTab
            Box(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (active) Brush.horizontalGradient(listOf(NeonBlueStart, NeonBlueEnd))
                    else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onSelect(idx) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) Color.White else TextSecondary)
            }
        }
    }
}

// ── Keyword chips ─────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordSection(title: String, keywords: List<String>, chipColor: Color, textColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            keywords.forEach { kw ->
                Box(modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(chipColor)
                    .padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(kw, fontSize = 12.sp, color = textColor)
                }
            }
        }
    }
}
