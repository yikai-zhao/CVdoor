package com.cvdoor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.data.ApplicationStage
import com.cvdoor.app.data.JobApplicationEntity
import com.cvdoor.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

private val VISIBLE_STAGES = listOf(
    ApplicationStage.SAVED,
    ApplicationStage.APPLIED,
    ApplicationStage.INTERVIEW,
    ApplicationStage.OFFER
)

private fun nextStage(current: String): String? {
    val idx = VISIBLE_STAGES.indexOfFirst { it.name == current }
    return if (idx >= 0 && idx < VISIBLE_STAGES.lastIndex) VISIBLE_STAGES[idx + 1].name else null
}

private fun stageColor(stage: String): Color = when (stage) {
    ApplicationStage.SAVED.name     -> AccentBlue
    ApplicationStage.APPLIED.name   -> NeonBlueEnd
    ApplicationStage.INTERVIEW.name -> AccentGreen
    ApplicationStage.OFFER.name     -> NeonMint
    ApplicationStage.REJECTED.name  -> NeonPink
    else                            -> TextSecondary
}

@Composable
fun JobTrackerScreen(
    applications: List<JobApplicationEntity>,
    onAddApplication: (title: String, company: String, atsScore: Int) -> Unit,
    onUpdateStage: (JobApplicationEntity, String) -> Unit,
    onDelete: (JobApplicationEntity) -> Unit,
    onBack: () -> Unit
) {
    var selectedStageIdx by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }

    val visibleApps = applications.filter { app ->
        app.stage != ApplicationStage.REJECTED.name
    }

    val filtered = if (selectedStageIdx == 0) {
        visibleApps
    } else {
        val stage = VISIBLE_STAGES[selectedStageIdx - 1].name
        visibleApps.filter { it.stage == stage }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Night)
            .statusBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Back", color = TextSecondary) }
            Spacer(Modifier.weight(1f))
            Text("Job Tracker", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "Add", tint = NeonBlueEnd)
            }
        }

        // Stage tabs with counts
        val allTabLabels = listOf("All") + VISIBLE_STAGES.map { stage ->
            val count = visibleApps.count { it.stage == stage.name }
            "${stage.name.lowercase().replaceFirstChar { it.uppercase() }} ($count)"
        }

        ScrollableTabRow(
            selectedTabIndex = selectedStageIdx,
            containerColor = NightElevated,
            contentColor = NeonBlueEnd,
            edgePadding = 8.dp
        ) {
            allTabLabels.forEachIndexed { i, label ->
                Tab(
                    selected = selectedStageIdx == i,
                    onClick = { selectedStageIdx = i },
                    text = {
                        Text(
                            label,
                            color = if (selectedStageIdx == i) NeonBlueEnd else TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = if (selectedStageIdx == i) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No applications here", color = TextSecondary, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap + to add one", color = TextSecondary, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(filtered, key = { it.id }) { app ->
                    ApplicationCard(
                        app = app,
                        onAdvance = {
                            val next = nextStage(app.stage)
                            if (next != null) onUpdateStage(app, next)
                        },
                        onDelete = { onDelete(app) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddApplicationDialog(
            onConfirm = { title, company, score ->
                onAddApplication(title, company, score)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
private fun ApplicationCard(
    app: JobApplicationEntity,
    onAdvance: () -> Unit,
    onDelete: () -> Unit
) {
    val color = stageColor(app.stage)
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NightElevated),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAdvance() }
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            // Stage indicator bar
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.title.ifBlank { "Untitled" },
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                if (app.company.isNotBlank()) {
                    Text(app.company, color = TextSecondary, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Stage chip
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(color.copy(alpha = 0.18f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            app.stage.lowercase().replaceFirstChar { it.uppercase() },
                            color = color,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text("ATS: ${app.atsScore}%", color = TextSecondary, fontSize = 12.sp)
                    Text(app.appliedAt.fmtJobDate(), color = TextSecondary, fontSize = 11.sp)
                }
                if (nextStage(app.stage) != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("Tap to advance stage →", color = NeonBlueEnd, fontSize = 11.sp)
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AddApplicationDialog(
    onConfirm: (title: String, company: String, atsScore: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var scoreText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NightElevated,
        title = { Text("Add Application", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Job Title *", color = TextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = NeonBlueEnd,
                        unfocusedBorderColor = CardStroke
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = company,
                    onValueChange = { company = it },
                    label = { Text("Company", color = TextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = NeonBlueEnd,
                        unfocusedBorderColor = CardStroke
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = scoreText,
                    onValueChange = { scoreText = it.filter { c -> c.isDigit() } },
                    label = { Text("ATS Score (0-100)", color = TextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = NeonBlueEnd,
                        unfocusedBorderColor = CardStroke
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        onConfirm(title.trim(), company.trim(), scoreText.toIntOrNull()?.coerceIn(0, 100) ?: 0)
                    }
                },
                enabled = title.isNotBlank()
            ) { Text("Add", color = NeonBlueEnd) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

private fun Long.fmtJobDate(): String =
    runCatching {
        SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(this))
    }.getOrElse { "" }
