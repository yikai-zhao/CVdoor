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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.billing.BillingManager
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
    val ctx = LocalContext.current
    val billing = remember { BillingManager.get(ctx) }
    val billingState by billing.state.collectAsState()

    // Pre-connect billing so price is ready by the time user reaches PaymentScreen
    LaunchedEffect(Unit) { billing.connect() }

    val displayPrice = when (val bs = billingState) {
        is BillingManager.BillingState.PriceLoaded -> bs.price
        else -> BillingManager.PRICE_DISPLAY
    }

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
                    "粘贴岗位描述",
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
                        "当前简历：${state.resumeName.ifBlank { state.resumeFileName.ifBlank { "未命名简历" } }}",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Text(
                        "目标行业：${state.industry.ifBlank { "未选择" }}",
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Text(
                        "目标地区：${state.region.ifBlank { "未选择" }}",
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Text(
                        "目标岗位：${state.targetRole.ifBlank { "未填写" }}",
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = onChangeResume) { Text("更换简历", color = NeonBlueEnd, fontSize = 12.sp) }
                        TextButton(onClick = onChangeIndustry) { Text("修改行业", color = NeonBlueEnd, fontSize = 12.sp) }
                    }
                }
            }

            Text(
                "目标岗位名称",
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
                        "粘贴职位描述内容…",
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
                    { Text("建议提供更完整的岗位描述，以获得更准确的优化结果", color = AccentYellow, fontSize = 12.sp) }
                } else null
            )

            // Job link (optional)
            Text(
                "岗位链接（可选）",
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
                    Text("本次将优化：", color = TextPrimary, fontSize = 13.sp)
                    listOf(
                        "ATS 关键词匹配", "ATS 友好格式", "职责转成果表达", "真实数据补充引导",
                        "简历结构重排", "Cover Letter 一致性", "投递前检查"
                    ).forEach {
                        Text("[✓] $it", color = TextSecondary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("本次完整优化：活动价 $displayPrice（原价 ${BillingManager.PRICE_ORIGINAL}）", color = AccentGreen, fontSize = 12.sp)
                    Text("下一步将展示示例优化效果。支付后才会生成你的专属结果。", color = TextSecondary, fontSize = 12.sp)
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
                            "查看 ATS 优化效果预览",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                } else {
                    Text(
                        "查看 ATS 优化效果预览",
                        fontSize = 16.sp,
                        color = TextSecondary.copy(0.4f)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
