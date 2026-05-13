package com.cvdoor.app.flow

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
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

private val STEPS = listOf(
    "正在解析简历",
    "正在分析岗位需求",
    "正在匹配你的经验",
    "正在优化简历",
    "正在生成求职信"
)

@Composable
fun ProcessingScreen(
    state: FlowUiState,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Night)
            .statusBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        if (state.phase == FlowPhase.ERROR) {
            // Error state
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(32.dp)
            ) {
                Text("😕", fontSize = 48.sp)
                Text(
                    "出现错误，请重试",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )
                if (state.errorMsg.isNotBlank()) {
                    Text(
                        state.errorMsg,
                        fontSize = 14.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
                ) {
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
                            "重新尝试",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        } else {
            // Loading state
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                Text(
                    "正在优化你的简历",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )

                // Spinner
                PulsingRing()

                // Steps
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    STEPS.forEachIndexed { index, label ->
                        val done = state.progressStep > index
                        val current = state.progressStep == index
                        StepRow(label = label, done = done, current = current)
                    }
                }

                Text(
                    "预计需要30秒左右",
                    fontSize = 13.sp,
                    color = TextSecondary.copy(0.6f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PulsingRing() {
    val infiniteAnim = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteAnim.animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "scale"
    )
    val alpha by infiniteAnim.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(contentAlignment = Alignment.Center) {
        // Outer glow ring
        Box(
            modifier = Modifier
                .size((56 * scale).dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            NeonBlueEnd.copy(alpha = alpha * 0.3f),
                            Color.Transparent
                        )
                    )
                )
        )
        // Inner ring
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = NeonBlueEnd,
            trackColor = NightElevated,
            strokeWidth = 3.dp
        )
    }
}

@Composable
private fun StepRow(label: String, done: Boolean, current: Boolean) {
    val dotColor = when {
        done -> AccentGreen
        current -> NeonBlueEnd
        else -> TextSecondary.copy(0.3f)
    }
    val textColor = when {
        done -> AccentGreen
        current -> TextPrimary
        else -> TextSecondary.copy(0.4f)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (done) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = AccentGreen,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(dotColor),
                contentAlignment = Alignment.Center
            ) {
                if (current) {
                    val pulse = rememberInfiniteTransition(label = "dot")
                    val a by pulse.animateFloat(
                        0.4f, 1f,
                        infiniteRepeatable(tween(600), RepeatMode.Reverse),
                        label = "da"
                    )
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(a))
                    )
                }
            }
        }

        Text(
            label,
            fontSize = 15.sp,
            color = textColor,
            fontWeight = if (current) FontWeight.Medium else FontWeight.Normal
        )
    }
}
