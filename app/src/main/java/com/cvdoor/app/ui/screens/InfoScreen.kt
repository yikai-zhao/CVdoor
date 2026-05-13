package com.cvdoor.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About CVDoor") },
                navigationIcon = {
                    Text(
                        text = "< Back",
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .clickable { onBack() }
                    )
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Product Value", style = MaterialTheme.typography.titleMedium)
            Text(
                "CVDoor is built on current ATS (Applicant Tracking System) models. " +
                        "Most resumes fail in mass applications because ATS filters them out. " +
                        "Our app helps candidates overcome this barrier by optimizing language, " +
                        "aligning keywords, and improving structure."
            )

            Text("Model & Scoring", style = MaterialTheme.typography.titleMedium)
            Text(
                "• Keyword alignment with job descriptions\n" +
                        "• Semantic matching using advanced embeddings\n" +
                        "• LLM-powered rewriting with concise, action-driven phrasing\n" +
                        "• Scoring: Recall × Relevance × ATS-parse readiness"
            )

            Text("Privacy & Security", style = MaterialTheme.typography.titleMedium)
            Text("• Your text is used only for the current optimization. " +
                    "• All history can be deleted anytime.")

            Text("Usage Tips", style = MaterialTheme.typography.titleMedium)
            Text("• Paste the key requirements from the JD first, then upload your resume.\n" +
                    "• You can re-optimize anytime from the History page.")

            Text("Contact Us", style = MaterialTheme.typography.titleMedium)
            Text("support@cvdoor.com")
        }
    }
}
