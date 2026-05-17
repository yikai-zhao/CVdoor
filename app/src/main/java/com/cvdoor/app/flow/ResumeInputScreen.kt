package com.cvdoor.app.flow

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.ui.theme.*

@Composable
fun ResumeInputScreen(
    state: FlowUiState,
    vm: FlowViewModel,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()

    // File picker launcher – restricted to PDF / DOCX / DOC
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = resolveFileName(ctx, uri)
            vm.setResumeFile(uri, name)
        }
    }

    val hasContent = state.resumeText.isNotBlank() || state.resumeFileName.isNotBlank()

    Box(
        Modifier
            .fillMaxSize()
            .background(Night)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint = TextSecondary
                    )
                }
                Text(
                    "添加你的簡歷",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            // Upload area
            if (state.resumeFileName.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            1.dp,
                            Brush.horizontalGradient(
                                listOf(NeonBlueStart.copy(0.5f), NeonBlueEnd.copy(0.5f))
                            ),
                            RoundedCornerShape(16.dp)
                        )
                        .background(NightElevated)
                        .clickable { launcher.launch(RESUME_MIME_TYPES) },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Outlined.AttachFile,
                            contentDescription = null,
                            tint = NeonBlueEnd,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            "上傳 PDF / DOCX",
                            color = NeonBlueEnd,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp
                        )
                    }
                }
            } else {
                // File uploaded state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(NightElevated)
                        .border(1.dp, NeonBlueEnd.copy(0.4f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                "已上傳文件",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Text(
                                state.resumeFileName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = AccentGreen
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { launcher.launch(RESUME_MIME_TYPES) }) {
                                Text("重新上傳", color = NeonBlueEnd, fontSize = 13.sp)
                            }
                            IconButton(onClick = { vm.clearResumeFile() }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "清除",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // File read error banner
            val showFileErrorBanner = state.errorMsg.isNotBlank()
                && state.resumeFileName.isBlank()
                && state.resumeText.isBlank()
            if (showFileErrorBanner) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF3A1A1A))
                        .border(1.dp, Color(0xFFE57373).copy(0.5f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = Color(0xFFE57373),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        state.errorMsg,
                        fontSize = 13.sp,
                        color = Color(0xFFE57373),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Divider
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Divider(Modifier.weight(1f), color = NightElevated)
                Text("或", color = TextSecondary, fontSize = 13.sp)
                Divider(Modifier.weight(1f), color = NightElevated)
            }

            // Text input
            OutlinedTextField(
                value = if (state.resumeFileName.isNotBlank()) "" else state.resumeText,
                onValueChange = { vm.setResumeText(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp),
                placeholder = {
                    Text(
                        "請粘貼你的簡歷內容…",
                        color = TextSecondary.copy(0.5f),
                        fontSize = 14.sp
                    )
                },
                enabled = state.resumeFileName.isBlank(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonBlueStart,
                    unfocusedBorderColor = NightElevated,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    disabledBorderColor = NightElevated,
                    disabledTextColor = TextSecondary,
                    cursorColor = NeonBlueEnd,
                    focusedContainerColor = NightElevated,
                    unfocusedContainerColor = NightElevated,
                    disabledContainerColor = NightElevated
                ),
                shape = RoundedCornerShape(16.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 22.sp)
            )

            // Privacy notice
            Text(
                "🔒 你的數據不會被存儲",
                fontSize = 12.sp,
                color = TextSecondary.copy(0.6f),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // Next button
            Button(
                onClick = onNext,
                enabled = hasContent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    disabledContainerColor = NightElevated
                )
            ) {
                if (hasContent) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.horizontalGradient(listOf(NeonBlueStart, NeonBlueEnd))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "下一步：添加崗位JD",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                } else {
                    Text(
                        "下一步：添加崗位JD",
                        fontSize = 16.sp,
                        color = TextSecondary.copy(0.4f)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

private val RESUME_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
)

private fun resolveFileName(ctx: android.content.Context, uri: Uri): String {
    return try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (idx >= 0) cursor.getString(idx) else "document"
        } ?: "document"
    } catch (_: Exception) {
        "document"
    }
}
