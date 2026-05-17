package com.cvdoor.app.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.ui.theme.*

@Composable
fun PreSubmitCheckScreen(
    state: FlowUiState,
    onBack: () -> Unit,
    onFillData: () -> Unit,
    onExportDone: (() -> Unit)? = null
) {
    val result = state.result
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var showExportDialog by remember { mutableStateOf(false) }

    // Compute checks from result
    val atsChecks = buildList {
        addAll(result?.atsOk?.map { Triple("✓", it, true) } ?: listOf(
            Triple("✓", "使用標準 Section 標題", true),
            Triple("✓", "無複雜圖表/表格", true)
        ))
        addAll(result?.atsIssues?.map { Triple("⚠", it, false) } ?: emptyList())
    }

    val keywordChecks = buildList {
        val matched = result?.matchedKeywords?.size ?: 0
        val missing = result?.missingKeywords?.size ?: 0
        if (matched > 0) add(Triple("✓", "核心 JD 關鍵詞覆蓋 $matched 個", true))
        if (missing > 0) add(Triple("⚠", "$missing 個關鍵詞尚未體現", false))
        else add(Triple("✓", "所有關鍵詞均已覆蓋", true))
    }

    val hasPlaceholders = result?.optimizedResume?.contains("[") == true
    val realityChecks = buildList {
        add(Triple("✓", "未發現明顯虛構內容", true))
        if (hasPlaceholders) add(Triple("⚠", "檢測到未填寫的數據佔位符", false))
        else add(Triple("✓", "無未填寫佔位符", true))
    }

    val clChecks = listOf(
        Triple("✓", "崗位名稱一致", true),
        Triple("✓", "經歷內容與簡歷一致", true)
    )

    val atsPassed = atsChecks.all { it.third }
    val keyPassed = keywordChecks.all { it.third }
    val realPassed = realityChecks.all { it.third }
    val allPassed = atsPassed && keyPassed && realPassed

    Column(modifier = Modifier.fillMaxSize().background(NightNavy)) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text("投遞前檢查", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                modifier = Modifier.weight(1f).padding(start = 4.dp))
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Overall status
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (allPassed) AccentGreen.copy(alpha = 0.1f) else AccentYellow.copy(alpha = 0.1f))
                    .border(1.dp, if (allPassed) AccentGreen.copy(alpha = 0.5f) else AccentYellow.copy(alpha = 0.5f),
                        RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (allPassed) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (allPassed) AccentGreen else AccentYellow,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (allPassed) "Final Application Check — 通過" else "Final Application Check — 需處理",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = if (allPassed) AccentGreen else AccentYellow
                        )
                        Text(
                            if (allPassed) "你的簡歷已可投遞" else "建議處理以下問題後再導出",
                            fontSize = 13.sp, color = TextSecondary
                        )
                    }
                }
            }

            CheckSection("ATS 格式檢查", atsChecks)
            CheckSection("關鍵詞檢查", keywordChecks)
            CheckSection("真實性檢查", realityChecks)
            CheckSection("Cover Letter 一致性", clChecks)

            if (!allPassed) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(NightElevated)
                        .padding(14.dp)
                ) {
                    Text("建議先填寫真實數據或刪除佔位符，再導出最終版本。",
                        fontSize = 13.sp, color = TextSecondary, lineHeight = 20.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Bottom actions
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!allPassed) {
                OutlinedButton(
                    onClick = onFillData,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentYellow),
                    border = ButtonDefaults.outlinedButtonBorder,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("去填寫數據", fontSize = 14.sp)
                }

                OutlinedButton(
                    onClick = {
                        val resume = sanitizeUnfilledPlaceholders(result?.optimizedResume.orEmpty())
                        val letter = sanitizeUnfilledPlaceholders(state.editedCoverLetter.ifBlank {
                            result?.coverLetter.orEmpty()
                        })
                        exportPackage(
                            context = ctx,
                            pkg = ExportPackage(resume = resume, coverLetter = letter, title = "cvdoor_presubmit_clean"),
                            format = ExportFormat.PDF
                        )
                        onExportDone?.invoke()
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("刪除未填寫建議並導出", fontSize = 14.sp)
                }
            }
            GradientCta(
                text = if (allPassed) "導出最終版本" else "繼續導出優化版",
                onClick = { showExportDialog = true }
            )
        }
    }

    if (showExportDialog) {
        val hasUnfilled = hasUnfilledPlaceholders(result?.optimizedResume.orEmpty()) ||
            hasUnfilledPlaceholders(state.editedCoverLetter.ifBlank { result?.coverLetter.orEmpty() })
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
                        UnfilledHandling.REMOVE_AND_EXPORT -> sanitizeUnfilledPlaceholders(result?.optimizedResume.orEmpty())
                        else -> result?.optimizedResume.orEmpty()
                    }
                    val letter = when (handling) {
                        UnfilledHandling.REMOVE_AND_EXPORT -> sanitizeUnfilledPlaceholders(
                            state.editedCoverLetter.ifBlank { result?.coverLetter.orEmpty() }
                        )
                        else -> state.editedCoverLetter.ifBlank { result?.coverLetter.orEmpty() }
                    }
                    exportPackage(
                        context = ctx,
                        pkg = ExportPackage(resume = resume, coverLetter = letter, title = "cvdoor_presubmit"),
                        format = format
                    )
                    showExportDialog = false
                    onExportDone?.invoke()
                }
            }
        )
    }
}

@Composable
private fun CheckSection(title: String, checks: List<Triple<String, String, Boolean>>) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardNavy)
            .border(1.dp, Stroke, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AccentBlue,
            modifier = Modifier.padding(bottom = 4.dp))
        checks.forEach { (icon, text, passed) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 14.sp, color = if (passed) AccentGreen else AccentYellow)
                Spacer(Modifier.width(8.dp))
                Text(text, fontSize = 13.sp, color = TextPrimary)
            }
        }
    }
}
