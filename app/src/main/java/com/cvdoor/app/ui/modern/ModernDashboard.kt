// File: app/src/main/java/com/cvdoor/app/ui/modern/ModernDashboard.kt
package com.cvdoor.app.ui.modern

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.cvdoor.app.data.OptimizationRecord
import com.cvdoor.app.ui.theme.*
import com.cvdoor.app.util.MainAppVM
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/* ========== 用参数调用（MainActivity 正在用） ========== */
@Composable
fun ModernDashboard(
    credits: Int,
    fileName: String,
    matchRate: Int,
    coverage: Int,
    delta: Int,
    onOptimize: () -> Unit,
    onHistory: () -> Unit,
    onLogout: () -> Unit,
    onBuyCredits: () -> Unit,
    accountText: String = "Account Balance",
    onInfo: () -> Unit = {},
    onExportRecent: () -> Unit = {},
    radarBefore: List<Int> = emptyList(),
    radarAfter: List<Int> = emptyList(),
    onOpenRecentDetails: () -> Unit = {},
    recentAvailable: Boolean = false
) {
    ModernDashboardImpl(
        credits = credits,
        titleText = "CVDoor.com",
        accountText = accountText,
        fileName = fileName,
        matchRate = matchRate,
        coverage = coverage,
        delta = delta,
        onOptimize = onOptimize,
        onHistory = onHistory,
        onLogout = onLogout,
        onBuyCredits = onBuyCredits,
        onInfo = onInfo,
        onExportRecent = onExportRecent,
        radarBefore = radarBefore,
        radarAfter = radarAfter,
        onOpenRecentDetails = onOpenRecentDetails,
        recentAvailable = recentAvailable
    )
}

/* ========== 直接传 nav / vm（可选） ========== */
@Composable
fun ModernDashboard(
    nav: NavController,
    vm: MainAppVM = viewModel()
) {
    val credits by vm.credits.collectAsState(initial = 0)
    val history by vm.history.collectAsState(initial = emptyList())

    // ✅ 最新记录在最前
    val latest: OptimizationRecord? = remember(history) { history.firstOrNull() }

    val match = latest?.afterTotal ?: 0
    val cov   = latest?.dimsAfter?.getOrNull(1) ?: 0
    val delta = latest?.let { it.afterTotal - it.beforeTotal } ?: 0
    val label = latest?.let { extractTitle(it.jdText) } ?: "No recent record"

    val beforeDims = latest?.dimsBefore ?: emptyList()
    val afterDims  = latest?.dimsAfter  ?: emptyList()

    ModernDashboard(
        credits = credits,
        fileName = label,
        matchRate = match,
        coverage = cov,
        delta = delta,
        onOptimize = { nav.navigate("optimize") },
        onHistory = { nav.navigate("history") },
        onLogout = { vm.logout() },
        onBuyCredits = { vm.addCredits(10) },
        accountText = "Account Balance",
        onInfo = { nav.navigate("info") },
        onExportRecent = { /* 导出报告：可加 vm.export... */nav.navigate("sample") },
        radarBefore = beforeDims,
        radarAfter = afterDims,
        onOpenRecentDetails = {
            val rec = latest ?: return@ModernDashboard
            nav.navigate("details/${rec.id}")
        },
        recentAvailable = latest != null
    )
}

/* ========== 通用实现（UI） ========== */
@Composable
private fun ModernDashboardImpl(
    credits: Int,
    titleText: String,
    accountText: String,
    fileName: String,
    matchRate: Int,
    coverage: Int,
    delta: Int,
    onOptimize: () -> Unit,
    onHistory: () -> Unit,
    onLogout: () -> Unit,
    onBuyCredits: () -> Unit,
    onInfo: () -> Unit,
    onExportRecent: () -> Unit,
    radarBefore: List<Int>,
    radarAfter: List<Int>,
    onOpenRecentDetails: () -> Unit,
    recentAvailable: Boolean
) {
    fun List<Int>.pad6(): List<Int> = (this + List(6) { 0 }).take(6)

    Scaffold(
        containerColor = NightNavy,
        bottomBar = {
            val mainBrush = Brush.horizontalGradient(listOf(GradStart, GradEnd))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Button(
                    onClick = onOptimize,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(mainBrush, RoundedCornerShape(28.dp)),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(horizontal = 20.dp)
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Optimize")
                }
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(inner)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 顶部标题
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = titleText,
                    color = TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionChip(icon = Icons.Outlined.History, text = "History", onClick = onHistory)
                    ActionChip(icon = Icons.Outlined.Logout,  text = "Logout",  onClick = onLogout)
                }
            }

            // 余额卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NightElevated),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0x2229ABE2), Color(0x1129ABE2))
                            )
                        )
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(accountText, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Text("Credits available", color = TextSecondary, fontSize = 12.sp)
                        }
                        Text("$credits", color = AccentBlue, fontSize = 18.sp)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val buyBrush = Brush.horizontalGradient(listOf(GradStart, GradEnd))
                        Button(
                            onClick = onBuyCredits,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .weight(1f)
                                .background(buyBrush, RoundedCornerShape(24.dp))
                        ) { Text("Buy credits") }

                        OutlinedButton(
                            onClick = onInfo,
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue),
                            border = BorderStroke(1.dp, AccentBlue)
                        ) {
                            Icon(Icons.Outlined.Info, contentDescription = null, tint = AccentBlue)
                            Spacer(Modifier.width(6.dp))
                            Text("Info")
                        }
                    }
                }
            }

            // 六维雷达
            SixDimRadarCard(
                labels = listOf("Fmt","KW","Sem","Title","Read","Rec"),
                before = radarBefore.pad6(),
                after  = radarAfter.pad6(),
                onExport = onExportRecent
            )

            // Latest Result
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NightElevated),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Latest Result", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Text(
                                text = "Company: $fileName",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (delta >= 0) "+$delta" else "$delta", color = AccentLime, fontSize = 14.sp)
                            Text("$matchRate%", color = TextPrimary, fontSize = 18.sp)
                            AssistChip(
                                onClick = onOpenRecentDetails,
                                enabled = recentAvailable,
                                label = { Text("Details") },
                                leadingIcon = { Icon(Icons.Outlined.TrendingUp, contentDescription = null) },
                                border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = CardStroke)
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Match Rate (Overall)", color = TextPrimary)
                        ProgressBar(value = matchRate / 100f, height = 10.dp)
                    }
                }
            }
        }
    }
}

/* ---------- 小组件 ---------- */
@Composable
private fun ActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, AccentBlue),
        modifier = Modifier.height(36.dp).clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = AccentBlue)
            Spacer(Modifier.width(6.dp))
            Text(text, color = AccentBlue, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ProgressBar(value: Float, height: Dp = 10.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(Track, RoundedCornerShape(8.dp))
            .drawBehind {
                val w = size.width * value.coerceIn(0f, 1f)
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(NeonBlueStart, NeonBlueEnd)),
                    size = androidx.compose.ui.geometry.Size(w, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx())
                )
            }
    )
}

/* ---------- 六维雷达 ---------- */
@Composable
private fun SixDimRadarCard(labels: List<String>, before: List<Int>, after: List<Int>, onExport: () -> Unit) {
    fun List<Int>.norm6() = (this + List(6) { 0 }).take(6).map { it.coerceIn(0, 100) }

    val b6 = remember(before) { before.norm6() }
    val a6 = remember(after)  { after.norm6() }
    val hasData = remember(b6, a6) { (b6 + a6).any { it > 0 } }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NightElevated),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ATS Six-Dimension Radar", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                AssistChip(
                    onClick = onExport,
                    label = { Text("Sample") },
                    leadingIcon = { Icon(Icons.Outlined.TrendingUp, contentDescription = null) },
                    border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = CardStroke)
                )
            }

            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().height(280.dp)
            ) {
                val density = LocalDensity.current
                val w = with(density) { maxWidth.toPx() }
                val h = with(density) { maxHeight.toPx() }
                val center = Offset(w / 2f, h / 2.2f)
                val radius = min(w, h) * 0.38f
                val n = labels.size.coerceAtMost(6)

                Canvas(modifier = Modifier.matchParentSize()) {
                    val levels = 5
                    repeat(levels) { i ->
                        val r = radius * (i + 1) / levels
                        val grid = regularPolygonPath(n, r, center)
                        drawPath(grid, color = CardStroke, style = Stroke(width = 1.dp.toPx()))
                    }
                    for (i in 0 until n) {
                        val ang = angleAt(i, n)
                        val end = Offset(center.x + radius * cos(ang), center.y + radius * sin(ang))
                        drawLine(color = CardStroke.copy(alpha = 0.6f), start = center, end = end, strokeWidth = 1.dp.toPx())
                    }

                    if (hasData) {
                        val bPath = dataPolygonPath(b6.take(n).map { it / 100f }, n, radius, center)
                        drawPath(bPath, GradStart.copy(alpha = 0.15f))
                        drawPath(bPath, GradStart, style = Stroke(width = 2.dp.toPx()))

                        val aPath = dataPolygonPath(a6.take(n).map { it / 100f }, n, radius, center)
                        drawPath(aPath, NeonBlueEnd.copy(alpha = 0.15f))
                        drawPath(aPath, NeonBlueEnd, style = Stroke(width = 2.dp.toPx()))
                    }
                }

                labels.take(n).forEachIndexed { i, label ->
                    val pad = with(density) { 18.dp.toPx() }
                    val ang = angleAt(i, n)
                    val x = center.x + (radius + pad) * cos(ang)
                    val y = center.y + (radius + pad) * sin(ang)
                    Text(
                        text = label,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.offset(x = with(density) { x.toDp() } - 18.dp, y = with(density) { y.toDp() } - 8.dp)
                    )
                }

                if (!hasData) {
                    Text("no data", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
                }

                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegendDot(GradStart); Text("Before", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.width(12.dp))
                    LegendDot(NeonBlueEnd); Text("After", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable private fun LegendDot(color: Color) {
    Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
}

private fun angleAt(i: Int, n: Int) = (-90f + 360f / n * i) * (PI / 180f).toFloat()
private fun regularPolygonPath(n: Int, r: Float, c: Offset): Path {
    val path = Path()
    for (i in 0 until n) {
        val ang = angleAt(i, n)
        val x = c.x + r * cos(ang)
        val y = c.y + r * sin(ang)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close(); return path
}
private fun dataPolygonPath(values: List<Float>, n: Int, r: Float, c: Offset): Path {
    val path = Path()
    values.forEachIndexed { i, v ->
        val ang = angleAt(i, n)
        val x = c.x + r * v * cos(ang)
        val y = c.y + r * v * sin(ang)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close(); return path
}
private fun extractTitle(jd: String): String {
    val first = jd.lineSequence().firstOrNull()?.trim().orEmpty()
    return if (first.isBlank()) "No recent record" else first.take(40)
}
