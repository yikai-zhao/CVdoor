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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.ui.theme.AccentBlue
import com.cvdoor.app.ui.theme.Night
import com.cvdoor.app.ui.theme.Stroke
import com.cvdoor.app.ui.theme.TextPrimary
import com.cvdoor.app.ui.theme.TextSecondary

@Composable
fun DataSupplementScreen(
    industryId: String,
    vm: FlowViewModel,
    onBack: () -> Unit,
    onSaveAndApply: () -> Unit,
    onSkip: () -> Unit
) {
    val fields = remember(industryId) {
        IndustryData.findById(industryId)?.dataFields
            ?: IndustryData.industries.firstOrNull()?.dataFields
            ?: listOf("量化成果 1", "量化成果 2", "量化成果 3", "量化成果 4")
    }
    val values = remember(fields) { mutableStateListOf(*Array(fields.size) { "" }) }

    Column(modifier = Modifier.fillMaxSize().background(Night)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text("补充真实数据", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("请仅填写真实且可验证的数据。", color = TextSecondary, fontSize = 13.sp)

            fields.forEachIndexed { index, label ->
                DataField(label, values[index]) { values[index] = it }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    vm.applyDataSupplement(values.toList())
                    onSaveAndApply()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存并更新简历")
            }
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("暂不填写，保留普通优化版")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DataField(label: String, value: String, onValueChange: (String) -> Unit) {
    Text(label, color = TextPrimary, fontSize = 13.sp)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = AccentBlue,
            unfocusedBorderColor = Stroke
        )
    )
}
