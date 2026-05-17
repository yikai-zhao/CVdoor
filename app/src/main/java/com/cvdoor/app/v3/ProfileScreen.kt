package com.cvdoor.app.v3

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cvdoor.app.ui.theme.*
import com.cvdoor.app.util.MainAppVM

@Composable
fun ProfileScreen(
    onLogout: () -> Unit = {},
    onMembership: () -> Unit = {},
    onAbout: () -> Unit = {},
    appVm: MainAppVM = viewModel()
) {
    val history by appVm.history.collectAsState()
    val credits by appVm.credits.collectAsState()
    val displayName by appVm.displayName.collectAsState()
    val uid by appVm.uid.collectAsState()
    val scroll = rememberScrollState()

    val careerStrength = if (history.isEmpty()) 0
    else history.take(5).map { it.afterTotal }.average().toInt()

    val accountLabel = displayName?.takeIf { it.isNotBlank() }
        ?: uid?.takeIf { it.isNotBlank() }?.take(20)
        ?: "Guest User"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Night)
            .statusBarsPadding()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        Text("Profile", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)

        // Avatar + name
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(NightElevated)
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(NeonBlueStart, NeonBlueEnd))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = accountLabel.first().uppercase(),
                        fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )
                }
                Column {
                    Text(accountLabel, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text(
                        text = if (uid.isNullOrBlank()) "Guest — Tap to sign in" else "Career OS User",
                        fontSize = 13.sp, color = TextSecondary
                    )
                }
            }
        }

        // Stats row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f), label = "Career\nStrength", value = "$careerStrength",
                valueColor = if (careerStrength >= 80) AccentGreen else NeonBlueEnd
            )
            StatCard(modifier = Modifier.weight(1f), label = "Career\nCredits", value = "$credits", valueColor = NeonBlueEnd)
            StatCard(modifier = Modifier.weight(1f), label = "Total\nOptimized", value = "${history.size}", valueColor = AccentYellow)
        }

        // Membership card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(NeonBlueStart.copy(0.15f), NeonBlueEnd.copy(0.15f))))
                .clickable(onClick = onMembership)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Membership", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text("Free Tier — Upgrade for unlimited", fontSize = 12.sp, color = NeonBlueEnd)
                }
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = NeonBlueEnd)
            }
        }

        // Settings items
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(NightElevated)
        ) {
            Column {
                SettingsItem(icon = Icons.Outlined.SyncAlt, label = "Account Sync", onClick = {})
                HorizontalDivider(color = Stroke, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(icon = Icons.Outlined.Notifications, label = "Notifications", onClick = {})
                HorizontalDivider(color = Stroke, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(icon = Icons.Outlined.Lock, label = "Privacy & Security", onClick = {})
                HorizontalDivider(color = Stroke, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(icon = Icons.Outlined.Info, label = "About CVDoor", onClick = onAbout)
            }
        }

        if (!uid.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(NightElevated)
            ) {
                SettingsItem(icon = Icons.Outlined.Logout, label = "Sign Out", onClick = onLogout, tint = NeonPink)
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun StatCard(modifier: Modifier = Modifier, label: String, value: String, valueColor: Color) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(NightElevated)
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = valueColor)
            Text(label, fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = TextPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 15.sp, color = tint, modifier = Modifier.weight(1f))
        Icon(
            Icons.Outlined.ChevronRight, contentDescription = null,
            tint = TextSecondary.copy(alpha = 0.5f), modifier = Modifier.size(16.dp)
        )
    }
}
