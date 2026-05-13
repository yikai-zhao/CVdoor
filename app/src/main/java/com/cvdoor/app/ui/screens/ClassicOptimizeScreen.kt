package com.cvdoor.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.cvdoor.app.ui.theme.NightNavy
import com.cvdoor.app.ui.theme.TextPrimary
import com.cvdoor.app.ui.theme.TextSecondary
import com.cvdoor.app.util.MainAppVM

@Composable
fun ClassicOptimizeScreen(
    vm: MainAppVM,
    prefillResume: String? = null,
    prefillJD: String? = null,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit
) {
    var resume by remember { mutableStateOf(prefillResume.orEmpty()) }
    var jd by remember { mutableStateOf(prefillJD.orEmpty()) }
    var working by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    var progress by remember { mutableFloatStateOf(0f) }
    var phaseIndex by remember { mutableIntStateOf(0) }
    val phases = remember {
        listOf(
            "Scoring resume vs JD…",
            "Generating optimized draft…",
            "Aligning keywords & titles…",
            "Finalizing and saving…"
        )
    }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "optimizingProgress"
    )

    LaunchedEffect(working) {
        if (working) {
            progress = 0.05f
            phaseIndex = 0
            while (progress < 0.92f && working) {
                delay(120)
                progress += when {
                    progress < 0.20f -> 0.012f
                    progress < 0.50f -> 0.009f
                    progress < 0.75f -> 0.007f
                    else             -> 0.004f
                }
                phaseIndex = when {
                    progress < 0.20f -> 0
                    progress < 0.50f -> 1
                    progress < 0.75f -> 2
                    else             -> 3
                }
            }
        } else {
            progress = 0f
            phaseIndex = 0
        }
    }

    Surface(color = NightNavy, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            val scroll = rememberScrollState()

            Scaffold(
                containerColor = NightNavy,
                topBar = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { if (!working) onBack() }, enabled = !working) {
                            Text("<  Back", color = if (working) TextSecondary.copy(0.5f) else TextSecondary)
                        }
                        Spacer(Modifier.weight(1f))
                    }
                },
                content = { inner ->
                    Column(
                        modifier = Modifier
                            .padding(inner)
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(16.dp)
                            .alpha(if (working) 0.6f else 1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {

                        Text("Resume", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp)
                                .padding(2.dp)
                                .border(
                                    width = 1.dp,
                                    color = TextSecondary.copy(alpha = 0.25f),
                                    shape = MaterialTheme.shapes.medium
                                )
                                .padding(12.dp)
                        ) {
                            BasicTextField(
                                value = resume,
                                onValueChange = { resume = it },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                                decorationBox = { innerField ->
                                    if (resume.isBlank()) {
                                        Text("Paste or type your resume text...", color = TextSecondary)
                                    }
                                    innerField()
                                }
                            )
                        }

                        Text("Job Description", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp)
                                .padding(2.dp)
                                .border(
                                    width = 1.dp,
                                    color = TextSecondary.copy(alpha = 0.25f),
                                    shape = MaterialTheme.shapes.medium
                                )
                                .padding(12.dp)
                        ) {
                            BasicTextField(
                                value = jd,
                                onValueChange = { jd = it },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                                decorationBox = { innerField ->
                                    if (jd.isBlank()) {
                                        Text("Paste JD here...", color = TextSecondary)
                                    }
                                    innerField()
                                }
                            )
                        }

                        if (err != null) {
                            Text(err!!, color = MaterialTheme.colorScheme.error)
                        }

                        Button(
                            onClick = {
                                err = null
                                working = true
                                vm.optimizeAndSave(
                                    resumeText = resume,
                                    jdText = jd,
                                    onDone = { rec ->
                                        progress = 1f
                                        phaseIndex = phases.lastIndex
                                        working = false
                                        onSaved(rec.id)     // ✅ 直接跳详情
                                    },
                                    onError = { e ->
                                        working = false
                                        err = e.message ?: "Unknown error"
                                    }
                                )
                            },
                            enabled = !working && resume.isNotBlank() && jd.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Text(if (working) "Optimizing..." else "Run Optimize")
                        }

                        Spacer(Modifier.height(24.dp))
                    }
                }
            )

            // 优化中遮罩
            AnimatedVisibility(
                visible = working,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                OptimizingOverlay(
                    progress = animatedProgress,
                    phase = phases[phaseIndex],
                )
            }
        }
    }
}

@Composable
private fun OptimizingOverlay(
    progress: Float,
    phase: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .alpha(0.65f)
                .background(Color.Black)
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121826)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.padding(24.dp).fillMaxWidth()
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Optimizing your resume",
                    color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                )
                Text(phase, color = TextSecondary, fontSize = 14.sp)

                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                // ✅ Material3 新签名：progress 传 lambda
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    trackColor = Color(0x3328A0FF)
                )

                val pct = (progress * 100).toInt().coerceIn(0, 100)
                Text("$pct%", color = TextSecondary, fontSize = 13.sp)
            }
        }
    }
}
