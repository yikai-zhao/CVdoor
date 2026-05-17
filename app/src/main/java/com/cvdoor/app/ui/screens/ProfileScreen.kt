package com.cvdoor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
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
import com.cvdoor.app.data.OptimizationRecord
import com.cvdoor.app.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun ProfileScreen(
    displayName: String?,
    credits: Int,
    history: List<OptimizationRecord>,
    onLogout: () -> Unit,
    onBuyCredits: () -> Unit,
    onBack: () -> Unit
) {
    val avgScore = remember(history) {
        if (history.isEmpty()) 0 else history.map { it.afterTotal }.average().roundToInt()
    }
    val strengthLabel = remember(history) { inferStrengthLabel(history) }
    val isPremium = credits >= 10

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Night)
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("← Back", color = TextSecondary) }
                Spacer(Modifier.weight(1f))
                Text("Profile", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(60.dp))
            }
        }

        item {
            // Avatar + Name
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(NeonBlueStart, NeonBlueEnd))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        (displayName?.firstOrNull()?.uppercaseChar() ?: '?').toString(),
                        color = Night,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    displayName ?: "Guest",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isPremium) AccentGreen.copy(alpha = 0.2f) else NeonBlueEnd.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            if (isPremium) "Premium" else "Free",
                            color = if (isPremium) AccentGreen else NeonBlueEnd,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        // Career Strength card
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = NightElevated),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Career Strength", color = TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "$avgScore%",
                                color = NeonBlueEnd,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("avg ATS", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        LinearProgressIndicator(
                            progress = { avgScore / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = NeonBlueEnd,
                            trackColor = CardStroke
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Strongest Domain: $strengthLabel",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Text(
                            "${history.size} optimizations completed",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        // Credits card
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = NightElevated),
                    modifier = Modifier.fillMaxWidth().clickable { onBuyCredits() }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Career Credits", color = TextSecondary, fontSize = 12.sp)
                            Text(
                                "$credits credits",
                                color = NeonMint,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Tap to buy more", color = TextSecondary, fontSize = 12.sp)
                        }
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = TextSecondary)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        // Settings rows
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Settings", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = NightElevated),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        SettingsRow("Account Sync", onClick = {})
                        HorizontalDivider(color = CardStroke, thickness = 0.5.dp)
                        SettingsRow("Notifications", onClick = {})
                        HorizontalDivider(color = CardStroke, thickness = 0.5.dp)
                        SettingsRow("Privacy", onClick = {})
                        HorizontalDivider(color = CardStroke, thickness = 0.5.dp)
                        SettingsRow("Billing", onClick = onBuyCredits)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }

        // Logout
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonPink)
                ) {
                    Text("Logout", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        // Version
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("CVDoor V3 · Build 300", color = TextSecondary.copy(alpha = 0.5f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SettingsRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary, fontSize = 14.sp)
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
    }
}

private fun inferStrengthLabel(history: List<OptimizationRecord>): String {
    if (history.isEmpty()) return "General"
    val keywords = mapOf(
        "Backend" to listOf("backend", "server", "api", "database", "java", "python", "go", "node"),
        "Frontend" to listOf("frontend", "react", "vue", "angular", "css", "html", "ui", "ux"),
        "Data Science" to listOf("data", "machine learning", "ml", "ai", "analytics", "python", "tensorflow"),
        "Product" to listOf("product", "pm", "manager", "roadmap", "agile", "scrum"),
        "DevOps" to listOf("devops", "kubernetes", "docker", "ci/cd", "aws", "gcp", "azure"),
        "Mobile" to listOf("android", "ios", "kotlin", "swift", "mobile", "flutter")
    )
    val allText = history.joinToString(" ") { it.jdText }.lowercase()
    val scores = keywords.mapValues { (_, kws) -> kws.count { allText.contains(it) } }
    return scores.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: "General"
}
