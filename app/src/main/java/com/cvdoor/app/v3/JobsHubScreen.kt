package com.cvdoor.app.v3

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cvdoor.app.data.JobEntry
import com.cvdoor.app.data.JobStage
import com.cvdoor.app.jobs.JobsViewModel
import com.cvdoor.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun JobsHubScreen(
    onOptimizeWithJd: (String) -> Unit = {},
    vm: JobsViewModel = viewModel()
) {
    val jobs by vm.allJobs.collectAsState()
    var jdInput by remember { mutableStateOf("") }
    var titleInput by remember { mutableStateOf("") }
    var companyInput by remember { mutableStateOf("") }
    var selectedStage by remember { mutableStateOf<JobStage?>(null) }
    var showAddForm by remember { mutableStateOf(false) }

    val filteredJobs = if (selectedStage == null) jobs
    else jobs.filter { it.stage == selectedStage!!.name }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Night)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Jobs", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            IconButton(onClick = { showAddForm = !showAddForm }) {
                Icon(
                    if (showAddForm) Icons.Outlined.Close else Icons.Outlined.Add,
                    contentDescription = null, tint = NeonBlueEnd
                )
            }
        }

        if (showAddForm) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(NightElevated)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Save New Job", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                OutlinedTextField(
                    value = titleInput, onValueChange = { titleInput = it },
                    label = { Text("Job Title") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonBlueEnd, unfocusedBorderColor = Stroke,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        focusedLabelColor = NeonBlueEnd, unfocusedLabelColor = TextSecondary
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = companyInput, onValueChange = { companyInput = it },
                    label = { Text("Company") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonBlueEnd, unfocusedBorderColor = Stroke,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        focusedLabelColor = NeonBlueEnd, unfocusedLabelColor = TextSecondary
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = jdInput, onValueChange = { jdInput = it },
                    label = { Text("Paste Job Description") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    maxLines = 8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonBlueEnd, unfocusedBorderColor = Stroke,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        focusedLabelColor = NeonBlueEnd, unfocusedLabelColor = TextSecondary
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (jdInput.isNotBlank()) {
                                vm.saveJob(titleInput, companyInput, jdInput)
                                titleInput = ""
                                companyInput = ""
                                jdInput = ""
                                showAddForm = false
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        enabled = jdInput.isNotBlank()
                    ) { Text("Save JD", fontSize = 13.sp) }
                    Button(
                        onClick = {
                            if (jdInput.isNotBlank()) onOptimizeWithJd(jdInput)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(0.dp),
                        enabled = jdInput.isNotBlank()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (jdInput.isNotBlank())
                                        Brush.horizontalGradient(listOf(NeonBlueStart, NeonBlueEnd))
                                    else
                                        Brush.linearGradient(listOf(NightElevated, NightElevated)),
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) { Text("Optimize →", fontSize = 13.sp, color = Color.White) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Stage filter tabs
        val stages = listOf(null, JobStage.SAVED, JobStage.APPLIED, JobStage.INTERVIEW, JobStage.OFFER)
        val stageLabels = listOf("All", "Saved", "Applied", "Interview", "Offer")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            stages.zip(stageLabels).forEach { (stage, label) ->
                val active = selectedStage == stage
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (active) Brush.horizontalGradient(listOf(NeonBlueStart, NeonBlueEnd))
                            else Brush.linearGradient(listOf(NightElevated, NightElevated))
                        )
                        .border(1.dp, if (active) Color.Transparent else Stroke, RoundedCornerShape(20.dp))
                        .clickable { selectedStage = stage }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        label, fontSize = 12.sp,
                        color = if (active) Color.White else TextSecondary,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        if (filteredJobs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("💼", fontSize = 40.sp)
                    Text("No jobs yet", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text("Tap + to save a job description", fontSize = 13.sp, color = TextSecondary)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filteredJobs, key = { it.id }) { job ->
                    JobEntryCard(job = job, vm = vm, onOptimize = { onOptimizeWithJd(job.jdText) })
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun JobEntryCard(job: JobEntry, vm: JobsViewModel, onOptimize: () -> Unit) {
    val stages = JobStage.values()
    var showMenu by remember { mutableStateOf(false) }
    val stageColor = when (job.stage) {
        JobStage.OFFER.name -> AccentGreen
        JobStage.INTERVIEW.name -> AccentYellow
        JobStage.APPLIED.name -> NeonBlueEnd
        else -> TextSecondary
    }
    val df = remember { SimpleDateFormat("MMM d", Locale.ENGLISH) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NightElevated)
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(job.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    if (job.company.isNotBlank()) {
                        Text(job.company, fontSize = 13.sp, color = TextSecondary)
                    }
                }
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(stageColor.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(job.stage, fontSize = 11.sp, color = stageColor, fontWeight = FontWeight.Medium)
                        }
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Outlined.MoreVert, contentDescription = null,
                                tint = TextSecondary, modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        stages.forEach { stage ->
                            if (stage.name != job.stage) {
                                DropdownMenuItem(
                                    text = { Text("Move to ${stage.name.lowercase().replaceFirstChar { it.uppercase() }}") },
                                    onClick = { vm.updateStage(job, stage); showMenu = false }
                                )
                            }
                        }
                        DropdownMenuItem(
                            text = { Text("Re-optimize", color = NeonBlueEnd) },
                            onClick = { onOptimize(); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = NeonPink) },
                            onClick = { vm.deleteJob(job.id); showMenu = false }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (job.atsScore > 0) {
                    Text("ATS ${job.atsScore}", fontSize = 12.sp, color = AccentGreen, fontWeight = FontWeight.Medium)
                } else {
                    Text("Not optimized yet", fontSize = 12.sp, color = TextSecondary)
                }
                Text(df.format(Date(job.createdAt)), fontSize = 11.sp, color = TextSecondary)
            }
        }
    }
}
