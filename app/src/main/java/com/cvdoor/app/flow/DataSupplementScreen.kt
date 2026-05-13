package com.cvdoor.app.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    onBack: () -> Unit,
    onSaveAndApply: () -> Unit,
    onSkip: () -> Unit
) {
    var childrenCount by remember { mutableStateOf("") }
    var classPerWeek by remember { mutableStateOf("") }
    var materials by remember { mutableStateOf("") }
    var parentComm by remember { mutableStateOf("") }

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
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("请仅填写真实且可验证的数据。", color = TextSecondary, fontSize = 13.sp)

            DataField("每日照顾儿童数量", childrenCount) { childrenCount = it }
            DataField("每周课堂活动次数", classPerWeek) { classPerWeek = it }
            DataField("准备教学材料数量", materials) { materials = it }
            DataField("家长沟通频率", parentComm) { parentComm = it }

            Spacer(Modifier.weight(1f))
            Button(onClick = onSaveAndApply, modifier = Modifier.fillMaxWidth()) {
                Text("保存并更新简历")
            }
            Button(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("暂不填写，保留普通优化版")
            }
            Spacer(Modifier.padding(bottom = 12.dp))
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
