package com.cvdoor.app.v3

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.cvdoor.app.data.OptimizationRecord
import com.cvdoor.app.jobs.JobsViewModel
import com.cvdoor.app.ui.theme.*
import com.cvdoor.app.util.MainAppVM
import java.util.Calendar

@Composable
fun HomeDashboardScreen(
    onStartOptimize: () -> Unit,
    onViewHistory: () -> Unit,
    onViewJobs: () -> Unit,
    appVm: MainAppVM = viewModel(),
    jobsVm: JobsViewModel = viewModel()
) {
    val history by appVm.history.collectAsState()
    val credits by appVm.credits.collectAsState()
    val displayName by appVm.displayName.collectAsState()
    val jobs by jobsVm.allJobs.collectAsState()
    val scroll = rememberScrollState()

    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good Morning"
        in 12..17 -> "Good Afternoon"
        else -> "Good Evening"
    }
    val name = displayName?.takeIf { it.isNotBlank() } ?: "there"
    val recentRecord = history.firstOrNull()
    val atsTrend = history.takeLast(5).map { it.afterTotal }

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

        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("$greeting, $name", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("AI Career Operating System", fontSize = 13.sp, color = TextSecondary)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(NeonBlueStart.copy(alpha = 0.2f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("⚡ $credits credits", fontSize = 12.sp, color = NeonBlueEnd, fontWeight = FontWeight.SemiBold)
            }
        }

        if (atsTrend.size >= 2) {
            AtsTrendMiniCard(scores = atsTrend, onClick = onViewHistory)
        }

        recentRecord?.let { rec ->
            CareerStrengthCard(record = rec, onReOptimize = onStartOptimize)
        } ?: run {
            StartOptimizeCard(onStartOptimize = onStartOptimize)
        }

        JobTrackerSummaryCard(jobs = jobs, onViewJobs = onViewJobs)

        recentRecord?.let { rec ->
            AiSuggestionsCard(record = rec)
        }

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun AtsTrendMiniCard(scores: List<Int>, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NightElevated)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("ATS Trend", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text("View all →", fontSize = 12.sp, color = NeonBlueEnd)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                scores.forEachIndexed { idx, score ->
                    if (idx > 0) Text("→", fontSize = 12.sp, color = TextSecondary)
                    Text(
                        text = "$score",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            score >= 85 -> AccentGreen
                            score >= 70 -> NeonBlueEnd
                            else -> AccentYellow
                        }
                    )
                }
                Spacer(Modifier.weight(1f))
                val delta = scores.last() - scores.first()
                if (delta > 0) {
                    Text("+$delta pts", fontSize = 12.sp, color = AccentGreen)
                }
            }
            Text("Last ${scores.size} optimizations", fontSize = 11.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun CareerStrengthCard(record: OptimizationRecord, onReOptimize: () -> Unit) {
    val jdTitle = record.jdText.lineSequence().firstOrNull()?.trim()?.take(50).orEmpty()
        .ifBlank { "Recent Job" }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(NightElevated, NightElevated.copy(0.9f))))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Continue Optimization", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(jdTitle, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("ATS ${record.afterTotal}", fontSize = 13.sp, color = AccentGreen)
                        Text("·", color = TextSecondary, fontSize = 13.sp)
                        Text("Before: ${record.beforeTotal}", fontSize = 13.sp, color = TextSecondary)
                    }
                }
                Button(
                    onClick = onReOptimize,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                Brush.horizontalGradient(listOf(NeonBlueStart, NeonBlueEnd)),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Re-optimize", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StartOptimizeCard(onStartOptimize: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NightElevated)
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("🎯", fontSize = 32.sp)
            Text("Start Your First ATS Scan", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(
                "Upload your resume and job description to get your ATS score and optimize for interviews.",
                fontSize = 13.sp, color = TextSecondary
            )
            Button(
                onClick = onStartOptimize,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(listOf(NeonBlueStart, NeonBlueEnd)),
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Start Free ATS Scan", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun JobTrackerSummaryCard(jobs: List<JobEntry>, onViewJobs: () -> Unit) {
    val saved = jobs.count { it.stage == JobStage.SAVED.name }
    val applied = jobs.count { it.stage == JobStage.APPLIED.name }
    val interview = jobs.count { it.stage == JobStage.INTERVIEW.name }
    val offer = jobs.count { it.stage == JobStage.OFFER.name }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NightElevated)
            .clickable(onClick = onViewJobs)
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Job Tracker", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text("View all →", fontSize = 12.sp, color = NeonBlueEnd)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TrackerStat("Saved", saved, TextSecondary)
                TrackerStat("Applied", applied, NeonBlueEnd)
                TrackerStat("Interview", interview, AccentYellow)
                TrackerStat("Offer", offer, AccentGreen)
            }
        }
    }
}

@Composable
private fun TrackerStat(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$count", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 11.sp, color = TextSecondary)
    }
}

@Composable
private fun AiSuggestionsCard(record: OptimizationRecord) {
    val suggestions = buildList {
        if (record.afterTotal < 90) add("Add more quantified metrics to your experience bullets")
        if (record.dimsBefore.getOrElse(0) { 0 } < 75) add("Strengthen keyword density to match job requirements")
        add(
            "Your profile is strongest for roles similar to: ${
                record.jdText.lineSequence().firstOrNull()?.trim()?.take(40).orEmpty().ifBlank { "your target role" }
            }"
        )
    }.take(3)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NightElevated)
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🤖", fontSize = 16.sp)
                Text("AI Career Suggestions", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            }
            suggestions.forEach { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", color = NeonBlueEnd, fontSize = 14.sp)
                    Text(s, fontSize = 13.sp, color = TextSecondary, lineHeight = 19.sp)
                }
            }
        }
    }
}
