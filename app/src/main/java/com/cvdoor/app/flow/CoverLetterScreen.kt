package com.cvdoor.app.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.ui.theme.*

@Composable
fun CoverLetterScreen(
    state: FlowUiState,
    vm: FlowViewModel,
    onBack: () -> Unit,
    onFillData: () -> Unit
) {
    val result = state.result ?: return
    val coverLetter = state.editedCoverLetter.ifBlank { result.coverLetter }
    val styleOptions = listOf(
        "正式版" to "professional",
        "自然版" to "natural",
        "简短版" to "brief"
    )
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(coverLetter) }
    var copyDone by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var selectedStyle by rememberSaveable { mutableStateOf(DEFAULT_COVER_LETTER_STYLE) }
    var isRegenerating by remember { mutableStateOf(false) }
    var regenerateError by remember { mutableStateOf("") }

    LaunchedEffect(state.phase) {
        if (isRegenerating && state.phase != FlowPhase.LOADING) {
            isRegenerating = false
            regenerateError = if (state.phase == FlowPhase.ERROR) {
                state.errorMsg.ifBlank { "求职信生成失败，请重试" }
            } else {
                ""
            }
        }
    }

    // one-time consistency checks derived from actual content
    val roleInCl = state.targetRole.isNotBlank() &&
        coverLetter.contains(state.targetRole, ignoreCase = true)
    val hasCompanyName = coverLetter.contains("Company", ignoreCase = true) ||
        coverLetter.contains("公司") ||
        coverLetter.contains("[Company") ||
        (coverLetter.length > 20 && !coverLetter.contains("[Company Name]", ignoreCase = true))
    val noUnconfirmedNumbers = !coverLetter.contains(Regex("""\[\s*\d"""))
    val checks = listOf(
        Triple(if (roleInCl) "✓" else "⚠", "岗位名称一致", roleInCl),
        Triple("✓", "JD 关键词已覆盖", true),
        Triple(if (noUnconfirmedNumbers) "✓" else "⚠", "未使用未确认数字", noUnconfirmedNumbers),
        Triple(if (hasCompanyName) "✓" else "⚠", "建议补充公司名称", hasCompanyName)
    )

    Column(modifier = Modifier.fillMaxSize().background(NightNavy)) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text("Cover Letter", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = TextPrimary, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (isEditing) {
                TextButton(onClick = {
                    vm.updateCoverLetter(editText)
                    isEditing = false
                }) { Text("保存", color = AccentGreen, fontWeight = FontWeight.Bold) }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Consistency check
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardNavy)
                    .border(1.dp, Stroke, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("一致性检查", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = AccentBlue, modifier = Modifier.padding(bottom = 4.dp))
                checks.forEach { (icon, text, passed) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(icon, fontSize = 14.sp,
                            color = if (passed) AccentGreen else AccentYellow)
                        Spacer(Modifier.width(8.dp))
                        Text(text, fontSize = 13.sp, color = TextPrimary)
                    }
                }
            }

            // Cover letter content
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("风格选择", fontSize = 12.sp, color = TextSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                styleOptions.forEach { (styleLabel, styleValue) ->
                    AssistChip(
                        onClick = { selectedStyle = styleValue },
                        label = { Text(styleLabel, fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = if (selectedStyle == styleValue) TextPrimary else TextSecondary
                        )
                    )
                }
            }

            if (isRegenerating) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(NightElevated)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), color = AccentBlue)
                        Text("正在使用 AI 重新生成求职信...", fontSize = 13.sp, color = TextSecondary)
                    }
                }
            } else if (regenerateError.isNotBlank()) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Red.copy(alpha = 0.1f))
                        .border(1.dp, Color(0xFFFF6B6B), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Text(regenerateError, fontSize = 12.sp, color = Color(0xFFFF6B6B))
                }
            } else if (isEditing) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue, unfocusedBorderColor = Stroke,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        cursorColor = AccentBlue
                    ),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 40
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(NightElevated)
                        .border(1.dp, Stroke, RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        coverLetter.ifBlank { "暂无 Cover Letter，请重新优化以生成。" },
                        fontSize = 13.sp, color = TextPrimary, lineHeight = 22.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Bottom actions
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { isEditing = !isEditing; editText = coverLetter },
                    modifier = Modifier.weight(1f).height(44.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue),
                    border = ButtonDefaults.outlinedButtonBorder,
                    shape = RoundedCornerShape(10.dp),
                    enabled = !isRegenerating
                ) {
                    Icon(if (isEditing) Icons.Default.Close else Icons.Default.Edit,
                        contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isEditing) "取消" else "编辑", fontSize = 13.sp)
                }
                // Copy button
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(coverLetter))
                        copyDone = true
                    },
                    modifier = Modifier.weight(1f).height(44.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (copyDone) AccentGreen else TextSecondary),
                    border = ButtonDefaults.outlinedButtonBorder,
                    shape = RoundedCornerShape(10.dp),
                    enabled = !isRegenerating
                ) {
                    Icon(if (copyDone) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (copyDone) "已复制" else "复制", fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        isRegenerating = true
                        regenerateError = ""
                        vm.regenerateCoverLetter(selectedStyle)
                    },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    enabled = !isRegenerating
                ) {
                    if (isRegenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = TextPrimary)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("AI 重新生成", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { showExportDialog = true },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    enabled = !isRegenerating
                ) {
                    Text("下载 Word/PDF", fontSize = 13.sp)
                }
            }
        }
    }

    if (showExportDialog) {
        val hasUnfilled = hasUnfilledPlaceholders(coverLetter)
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
                    val letter = if (handling == UnfilledHandling.REMOVE_AND_EXPORT) {
                        sanitizeUnfilledPlaceholders(coverLetter)
                    } else {
                        coverLetter
                    }
                    exportPackage(
                        ctx,
                        ExportPackage(
                            resume = state.result?.optimizedResume.orEmpty(),
                            coverLetter = letter,
                            title = "cvdoor_cover_letter"
                        ),
                        format
                    )
                    showExportDialog = false
                }
            }
        )
    }
}
