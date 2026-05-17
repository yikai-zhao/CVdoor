// File: app/src/main/java/com/cvdoor/app/ui/screens/SampleDetailsScreen.kt
package com.cvdoor.app.ui.screens

import androidx.compose.runtime.Composable
import com.cvdoor.app.data.AnalysisOut
import com.cvdoor.app.data.DimAnalysis
import com.cvdoor.app.data.OverallAnalysis

@Composable
fun SampleDetailsScreen(onBack: () -> Unit) {
    val sampleAnalysis = AnalysisOut(
        overall = OverallAnalysis(
            summary = "Resume improved with stronger keyword coverage and clearer structure.",
            strengths = listOf("Good formatting", "Relevant experience"),
            issues = listOf("Keywords coverage was low before optimization"),
            actions = listOf("Add more job-specific keywords", "Highlight measurable results")
        ),
        dimensions = listOf(
            DimAnalysis(
                name = "Formatting",
                before = 6,
                after = 8,
                reasons = listOf("Optimized section layout"),
                problems = listOf("Inconsistent headings"),
                suggestions = listOf("Use consistent title style"),
                missingBefore = listOf("Clear section titles"),
                addedAfter = listOf("Bold section headers")
            ),
            DimAnalysis(
                name = "Keywords",
                before = 6,
                after = 9,
                reasons = listOf("Added job-specific keywords"),
                problems = listOf("Lacked analytics tools keywords"),
                suggestions = listOf("Include SQL, Google Analytics"),
                missingBefore = listOf("SQL", "Google Analytics"),
                addedAfter = listOf("Agile", "Scrum", "KPI-driven")
            )
        )
    )

    // ✅ 直接調用 RecordDetailsScreen，保持 UI 與真實結果完全一致
    RecordDetailsScreen(
        resume = "Sample resume content here...",
        jd = "Sample job description here...",
        optimized = "Sample optimized resume content here...",
        before = 65,
        after = 82,
        dimsBefore = listOf(6, 6, 5, 7, 6, 5),
        dimsAfter = listOf(8, 9, 7, 8, 9, 8),
        analysis = sampleAnalysis,
        onBack = onBack
    )
}
