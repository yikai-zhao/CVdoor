package com.cvdoor.app.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.ui.theme.AccentBlue
import com.cvdoor.app.ui.theme.Night
import com.cvdoor.app.ui.theme.Stroke
import com.cvdoor.app.ui.theme.TextPrimary
import com.cvdoor.app.ui.theme.TextSecondary

@Composable
fun ResumeEditScreen(
    state: FlowUiState,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onSaveAndReoptimize: () -> Unit,
    onFillData: () -> Unit
) {
    var edited by remember(state.result?.optimizedResume) {
        mutableStateOf(state.result?.optimizedResume.orEmpty())
    }

    Column(modifier = Modifier.fillMaxSize().background(Night)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text("编辑简历", color = TextPrimary, fontSize = 18.sp)
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("ATS 辅助工具", color = TextSecondary, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("增强关键词", "更ATS友好", "职责转成果", "强动词替换").forEach {
                    AssistChip(
                        onClick = { },
                        label = { Text(it, fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = AccentBlue)
                    )
                }
            }

            OutlinedTextField(
                value = edited,
                onValueChange = { edited = it },
                modifier = Modifier.fillMaxWidth().height(420.dp),
                maxLines = 200,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = Stroke
                )
            )

            Button(onClick = { onFillData() }, modifier = Modifier.fillMaxWidth()) {
                Text("添加真实数据")
            }
            Button(onClick = { onSave(edited) }, modifier = Modifier.fillMaxWidth()) {
                Text("保存")
            }
            Button(onClick = onSaveAndReoptimize, modifier = Modifier.fillMaxWidth()) {
                Text("保存并重新优化")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
