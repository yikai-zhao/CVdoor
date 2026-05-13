package com.cvdoor.app.flow

import android.app.Activity
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.billing.BillingManager
import com.cvdoor.app.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// 07: Payment Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun PaymentScreen(
    state: FlowUiState,
    vm: FlowViewModel,
    onBack: () -> Unit,
    onPaymentStarted: () -> Unit   // → PaymentProcessingScreen
) {
    val ctx = LocalContext.current
    val billing = remember { BillingManager.get(ctx) }
    val billingState by billing.state.collectAsState()

    // Connect billing when screen appears
    LaunchedEffect(Unit) { billing.connect() }

    // React to billing state changes
    LaunchedEffect(billingState) {
        when (billingState) {
            is BillingManager.BillingState.Pending -> {
                vm.onPaymentPending()
                onPaymentStarted()
            }
            is BillingManager.BillingState.Success -> {
                vm.onPaymentSuccess()
                onPaymentStarted()
            }
            is BillingManager.BillingState.Failed -> {
                val msg = (billingState as BillingManager.BillingState.Failed).msg
                if (msg == "USER_CANCELED") {
                    vm.resetPayment()
                } else {
                    vm.onPaymentFailed(msg)
                    onPaymentStarted()
                }
            }
            else -> {}
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(NightNavy)
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text("确认支付", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                modifier = Modifier.weight(1f).padding(start = 4.dp))
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Title
            Text("解锁 1 次完整 ATS 简历优化",
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

            // Price highlight
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF1A2550), NightElevated)))
                    .border(1.dp, AccentGreen.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("活动价", fontSize = 13.sp, color = TextSecondary)
                    Text("HK$4.9", fontSize = 42.sp, fontWeight = FontWeight.Black, color = AccentGreen)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("原价 HK$9.9", fontSize = 14.sp, color = TextSecondary,
                            style = LocalTextStyle.current.copy(
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                            ))
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFFF6B35))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("50% OFF", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // What's included
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardNavy)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("你将获得：", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                listOf(
                    "专属 ATS 匹配评分",
                    "JD 关键词三分类分析",
                    "ATS 友好格式优化",
                    "职责转成果表达",
                    "真实数据补充引导",
                    "优化后的完整简历",
                    "Cover Letter",
                    "投递前检查 & Word 导出",
                    "保存到历史记录"
                ).forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null,
                            tint = AccentGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(item, fontSize = 13.sp, color = TextPrimary)
                    }
                }
            }

            Text("支付成功后将自动开始生成你的专属 ATS 优化结果。",
                fontSize = 12.sp, color = TextSecondary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }

        // Payment buttons
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val isConnecting = billingState is BillingManager.BillingState.Connecting

            Button(
                onClick = {
                    val activity = ctx as? Activity ?: return@Button
                    billing.launchBillingFlow(activity)
                },
                enabled = !isConnecting,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34A853)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Payment, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isConnecting) "正在连接..." else "使用 Google Play 支付",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White
                )
            }

            Text("支付安全由 Google Play 保障",
                fontSize = 11.sp, color = TextSecondary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 08 + 09: Payment Processing + AI Generation Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun PaymentProcessingScreen(
    state: FlowUiState,
    vm: FlowViewModel,
    onSuccess: () -> Unit,      // → ResultScreen
    onBackToPayment: () -> Unit,
    onBackToHome: () -> Unit
) {
    val paymentPhase = state.paymentPhase
    val aiPhase = state.phase

    // When payment succeeds, auto-start generation
    LaunchedEffect(paymentPhase) {
        if (paymentPhase == PaymentPhase.SUCCESS && aiPhase != FlowPhase.LOADING && aiPhase != FlowPhase.SUCCESS) {
            vm.startOptimize()
        }
    }

    // When AI generation succeeds, navigate
    LaunchedEffect(aiPhase) {
        if (aiPhase == FlowPhase.SUCCESS) onSuccess()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(NightNavy),
        contentAlignment = Alignment.Center
    ) {
        when {
            paymentPhase == PaymentPhase.PENDING || paymentPhase == PaymentPhase.IDLE -> {
                // Payment processing
                PaymentWaitingContent()
            }
            paymentPhase == PaymentPhase.FAILED -> {
                // Payment failed
                PaymentFailedContent(
                    msg = state.errorMsg,
                    onRetry = onBackToPayment,
                    onBack = onBackToHome
                )
            }
            aiPhase == FlowPhase.LOADING || paymentPhase == PaymentPhase.SUCCESS -> {
                // AI generating
                AiGeneratingContent(step = state.progressStep)
            }
            aiPhase == FlowPhase.ERROR -> {
                // Generation failed (payment was ok, AI failed)
                GenerationFailedContent(
                    msg = state.errorMsg,
                    onRetry = { vm.startOptimize() },
                    onBack = onBackToHome
                )
            }
            else -> {
                PaymentWaitingContent()
            }
        }
    }
}

@Composable
private fun PaymentWaitingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp)
    ) {
        CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(56.dp))
        Text("正在处理支付...", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Text("请不要关闭 App\n支付完成后将自动开始生成结果",
            fontSize = 14.sp, color = TextSecondary, textAlign = TextAlign.Center, lineHeight = 22.sp)
    }
}

@Composable
private fun AiGeneratingContent(step: Int) {
    val steps = listOf(
        "解析简历内容",
        "提取 JD 和行业关键词",
        "计算 ATS 匹配评分",
        "优化格式与成果表达",
        "生成 Cover Letter",
        "执行投递前检查"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        CircularProgressIndicator(
            color = AccentGreen,
            modifier = Modifier.size(56.dp),
            strokeWidth = 3.dp
        )
        Spacer(Modifier.height(24.dp))
        Text("正在生成 ATS 优化结果...", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text("我们正在根据你的简历和 JD：", fontSize = 13.sp, color = TextSecondary)
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            steps.forEachIndexed { i, label ->
                val done = i < step
                val current = i == step
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        when {
                            done -> Icon(Icons.Default.CheckCircle, contentDescription = null,
                                tint = AccentGreen, modifier = Modifier.size(20.dp))
                            current -> CircularProgressIndicator(
                                modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentBlue)
                            else -> Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null,
                                tint = TextSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        label, fontSize = 13.sp,
                        color = when { done -> AccentGreen; current -> TextPrimary; else -> TextSecondary },
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("预计需要 10–30 秒", fontSize = 12.sp, color = TextSecondary)
    }
}

@Composable
private fun PaymentFailedContent(msg: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(Icons.Default.ErrorOutline, contentDescription = null,
            tint = Color(0xFFFF6B6B), modifier = Modifier.size(64.dp))
        Text("支付未完成", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text("你的支付没有完成，暂未生成结果。", fontSize = 14.sp, color = TextSecondary)
        GradientCta("重新支付", onClick = onRetry)
        TextButton(onClick = onBack) {
            Text("返回预览页", color = TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun GenerationFailedContent(msg: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(Icons.Default.CloudOff, contentDescription = null,
            tint = AccentYellow, modifier = Modifier.size(64.dp))
        Text("生成暂时失败", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text("支付已成功，但生成结果暂时失败。\n你可以重新生成，无需再次付费。",
            fontSize = 14.sp, color = TextSecondary, textAlign = TextAlign.Center, lineHeight = 22.sp)
        if (msg.isNotBlank()) {
            Text(msg, fontSize = 12.sp, color = Color(0xFFFF6B6B))
        }
        GradientCta("重新生成", onClick = onRetry)
        TextButton(onClick = onBack) {
            Text("返回首页", color = TextSecondary, fontSize = 14.sp)
        }
    }
}
