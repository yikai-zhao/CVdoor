package com.cvdoor.app.flow

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.data.SavedResumeEntity
import com.cvdoor.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// 03: Resume Select Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ResumeSelectScreen(
    savedResumes: List<SavedResumeEntity>,
    onSelectResume: (SavedResumeEntity) -> Unit,
    onDeleteResume: (Long) -> Unit,
    onUploadNew: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightNavy)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text(
                "选择简历",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
        }

        if (savedResumes.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("暂无已保存简历", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "上传一次后，之后可直接复用，\n无需反复上传。",
                    fontSize = 14.sp, color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    lineHeight = 22.sp
                )
                Spacer(Modifier.height(32.dp))
                GradientCta("上传新简历", onClick = onUploadNew)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                item {
                    Text(
                        "选择一份简历开始 ATS 优化",
                        fontSize = 14.sp, color = TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                items(savedResumes, key = { it.id }) { resume ->
                    ResumeCard(
                        resume = resume,
                        onSelect = { onSelectResume(resume) },
                        onDelete = { onDeleteResume(resume.id) }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                OutlinedButton(
                    onClick = onUploadNew,
                    modifier = Modifier.fillMaxWidth(),
                    border = ButtonDefaults.outlinedButtonBorder,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("上传新简历", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun ResumeCard(
    resume: SavedResumeEntity,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val dateStr = fmt.format(Date(resume.uploadDate))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardNavy)
            .border(1.dp, Stroke, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Description,
            contentDescription = null,
            tint = AccentBlue,
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(resume.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text("最近使用：$dateStr", fontSize = 12.sp, color = TextSecondary)
            if (resume.optimizationCount > 0) {
                Text("已优化 ${resume.optimizationCount} 次", fontSize = 12.sp, color = AccentGreen)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = onSelect,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("使用", fontSize = 13.sp, color = Color.White)
            }
            Spacer(Modifier.height(4.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete",
                    tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 03A: Upload New Resume Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ResumeUploadScreen(
    state: FlowUiState,
    vm: FlowViewModel,
    onBack: () -> Unit,
    onNext: () -> Unit   // → IndustrySelectScreen
) {
    var resumeNameInput by remember { mutableStateOf("我的简历") }
    var pasteText by remember { mutableStateOf(state.resumeText) }
    val ctx = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "简历文件"
        resumeNameInput = name.substringBeforeLast('.')
        vm.setResumeFile(uri, name)
    }

    val hasContent = state.resumeText.isNotBlank() || pasteText.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightNavy)
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text("上传简历", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                modifier = Modifier.weight(1f).padding(start = 4.dp))
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f)
        ) {
            item {
                Text("上传你的简历", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text("支持 PDF / DOCX / 文本", fontSize = 13.sp, color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp))
            }

            // File picker area
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(NightElevated)
                        .border(1.dp, if (state.resumeFileName.isNotBlank()) AccentGreen else Stroke,
                            RoundedCornerShape(14.dp))
                        .clickable { filePicker.launch("*/*") }
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (state.resumeFileName.isNotBlank()) Icons.Default.CheckCircle else Icons.Default.CloudUpload,
                            contentDescription = null,
                            tint = if (state.resumeFileName.isNotBlank()) AccentGreen else AccentBlue,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        if (state.resumeFileName.isNotBlank()) {
                            Text(state.resumeFileName, fontSize = 14.sp, color = AccentGreen,
                                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { vm.clearResumeSelection() }) {
                                Text("重新选择", fontSize = 13.sp, color = TextSecondary)
                            }
                        } else {
                            Text("点击选择文件", fontSize = 14.sp, color = AccentBlue, fontWeight = FontWeight.Medium)
                            Text("或拖拽到此处", fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                }
            }

            // Paste text
            item {
                Text("或直接粘贴简历文本", fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = if (state.resumeFileName.isNotBlank()) state.resumeText else pasteText,
                    onValueChange = { txt -> pasteText = txt; vm.setResumeText(txt) },
                    enabled = state.resumeFileName.isBlank(),
                    placeholder = { Text("粘贴完整简历内容...", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = Stroke,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        disabledBorderColor = Stroke,
                        disabledTextColor = TextSecondary,
                        cursorColor = AccentBlue
                    ),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 12
                )
            }

            // Name input
            item {
                Text("简历名称", fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = resumeNameInput,
                    onValueChange = { resumeNameInput = it },
                    placeholder = { Text("例：Lisa 幼稚园助理简历", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue, unfocusedBorderColor = Stroke,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        cursorColor = AccentBlue
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
            }

            item { Spacer(Modifier.height(8.dp)) }
        }

        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            GradientCta(
                text = "保存并继续",
                enabled = hasContent && resumeNameInput.isNotBlank(),
                onClick = {
                    val content = if (state.resumeFileName.isNotBlank()) state.resumeText else pasteText
                    vm.saveResume(resumeNameInput, content)
                    onNext()
                }
            )
        }
    }
}

// ── Shared gradient CTA button ────────────────────────────────────────────────
@Composable
fun GradientCta(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled)
                    Brush.horizontalGradient(listOf(GradStart, GradEnd))
                else
                    Brush.horizontalGradient(listOf(Color(0xFF3A4060), Color(0xFF3A4060)))
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (enabled) Color.White else TextSecondary)
    }
}
