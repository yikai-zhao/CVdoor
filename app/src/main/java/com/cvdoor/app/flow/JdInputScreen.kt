package com.cvdoor.app.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.ui.theme.*

@Composable
fun JdInputScreen(
    state: FlowUiState,
    vm: FlowViewModel,
    onBack: () -> Unit,
    onOptimize: () -> Unit,
    onChangeResume: () -> Unit = {},
    onChangeIndustry: () -> Unit = {}
) {
    val scroll = rememberScrollState()
    val jdOk = state.jdText.trim().length >= 20
    val jdShort = state.jdText.isNotBlank() && !jdOk

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
                    "粘貼崗位描述",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(NightElevated)
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "當前簡歷：${state.resumeName.ifBlank { state.resumeFileName.ifBlank { "未命名簡歷" } }}",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Text(
                        "目標行業：${state.industry.ifBlank { "未選擇" }}",
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Text(
                        "目標地區：${state.region.ifBlank { "未選擇" }}",
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Text(
                        "目標崗位：${state.targetRole.ifBlank { "未填寫" }}",
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = onChangeResume) { Text("更換簡歷", color = NeonBlueEnd, fontSize = 12.sp) }
                        TextButton(onClick = onChangeIndustry) { Text("修改行業", color = NeonBlueEnd, fontSize = 12.sp) }
                    }
                }
            }

            Text(
                "目標崗位名稱",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary
            )
            OutlinedTextField(
                value = state.targetRole,
                onValueChange = { vm.setTargetRole(it) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonBlueStart,
                    unfocusedBorderColor = NightElevated,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = NeonBlueEnd,
                    focusedContainerColor = NightElevated,
                    unfocusedContainerColor = NightElevated
                ),
                shape = RoundedCornerShape(16.dp),
                singleLine = true
            )

            // JD text input
            OutlinedTextField(
                value = state.jdText,
                onValueChange = { vm.setJdText(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp),
                placeholder = {
                    Text(
                        "粘貼職位描述內容…",
                        color = TextSecondary.copy(0.5f),
                        fontSize = 14.sp
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonBlueStart,
                    unfocusedBorderColor = NightElevated,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = NeonBlueEnd,
                    focusedContainerColor = NightElevated,
                    unfocusedContainerColor = NightElevated
                ),
                shape = RoundedCornerShape(16.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 22.sp),
                isError = jdShort,
                supportingText = if (jdShort) {
                    { Text("建議提供更完整的崗位描述，以獲得更準確的優化結果", color = AccentYellow, fontSize = 12.sp) }
                } else null
            )

            // Job link (optional)
            Text(
                "崗位鏈接（可選）",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary
            )
            OutlinedTextField(
                value = state.jdLink,
                onValueChange = { vm.setJdLink(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("https://...", color = TextSecondary.copy(0.4f), fontSize = 13.sp)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonBlueStart,
                    unfocusedBorderColor = NightElevated,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = NeonBlueEnd,
                    focusedContainerColor = NightElevated,
                    unfocusedContainerColor = NightElevated
                ),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(NightElevated)
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("本次將優化：", color = TextPrimary, fontSize = 13.sp)
                    listOf(
                        "ATS 關鍵詞匹配", "ATS 友好格式", "職責轉成果表達", "真實數據補充引導",
                        "簡歷結構重排", "Cover Letter 一致性", "投遞前檢查"
                    ).forEach {
                        Text("[✓] $it", color = TextSecondary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("本次完整優化：活動價 HK$4.9（原價 HK$9.9）", color = AccentGreen, fontSize = 12.sp)
                    Text("下一步將展示示例優化效果。支付後纔會生成你的專屬結果。", color = TextSecondary, fontSize = 12.sp)
                }
            }

            // Optimize button
            Button(
                onClick = {
                    onOptimize()
                },
                enabled = jdOk,
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
                if (jdOk) {
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
                            "查看 ATS 優化效果預覽",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                } else {
                    Text(
                        "查看 ATS 優化效果預覽",
                        fontSize = 16.sp,
                        color = TextSecondary.copy(0.4f)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
