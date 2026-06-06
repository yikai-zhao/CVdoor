package com.cvdoor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.data.SavedJobEntity
import com.cvdoor.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun JobsHubScreen(
    savedJobs: List<SavedJobEntity>,
    onStartScan: (jdText: String) -> Unit,
    onRedoJob: (SavedJobEntity) -> Unit,
    onDeleteJob: (SavedJobEntity) -> Unit,
    onBack: () -> Unit
) {
    var jdInput by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("All Jobs", "Saved JDs")

    val displayedJobs = when (selectedTab) {
        1 -> savedJobs.filter { it.jdText.isNotBlank() }
        else -> savedJobs
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
            Text("Jobs Hub", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(60.dp))
        }

        // JD paste area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NightElevated)
                .padding(16.dp)
        ) {
            Text("Paste New Job Description", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = jdInput,
                onValueChange = { jdInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                placeholder = { Text("Paste job description here...", color = TextSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = NeonBlueEnd,
                    unfocusedBorderColor = CardStroke
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { if (jdInput.isNotBlank()) onStartScan(jdInput) },
                enabled = jdInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonBlueEnd),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Start ATS Scan", color = Night, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Tabs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = NightElevated,
            contentColor = NeonBlueEnd,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEachIndexed { i, label ->
                Tab(
                    selected = selectedTab == i,
                    onClick = { selectedTab = i },
                    text = {
                        Text(
                            label,
                            color = if (selectedTab == i) NeonBlueEnd else TextSecondary,
                            fontWeight = if (selectedTab == i) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Jobs list
        if (displayedJobs.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                Text("No saved jobs yet", color = TextSecondary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(displayedJobs, key = { it.id }) { job ->
                    SavedJobCard(
                        job = job,
                        onRedo = { onRedoJob(job) },
                        onDelete = { onDeleteJob(job) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedJobCard(
    job: SavedJobEntity,
    onRedo: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NightElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        job.title.ifBlank { "Untitled Job" },
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    if (job.company.isNotBlank()) {
                        Text(job.company, color = TextSecondary, fontSize = 13.sp)
                    }
                    Text(
                        job.savedAt.fmtDate(),
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.horizontalGradient(listOf(NeonBlueStart, NeonBlueEnd))
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "${job.atsScore}%",
                            color = Night,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Text("ATS Score", color = TextSecondary, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRedo,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Redo", color = NeonBlueEnd, fontSize = 13.sp)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun Long.fmtDate(): String =
    runCatching {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(this))
    }.getOrElse { "N/A" }
