package com.cvdoor.app.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.ui.theme.*

@Composable
fun IndustrySelectScreen(
    state: FlowUiState,
    vm: FlowViewModel,
    onBack: () -> Unit,
    onNext: () -> Unit   // → JdInputScreen
) {
    var selectedIndustryId by remember { mutableStateOf(state.industryId.ifBlank { IndustryData.industries[0].id }) }
    var roleInput by remember { mutableStateOf(state.targetRole) }
    var selectedRegion by remember { mutableStateOf(state.region) }

    val selectedDef = IndustryData.industries.find { it.id == selectedIndustryId }
        ?: IndustryData.industries[0]

    val canProceed = selectedIndustryId.isNotBlank() && roleInput.isNotBlank()

    Column(
        modifier = Modifier.fillMaxSize().background(NightNavy)
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text("行業與目標崗位", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                modifier = Modifier.weight(1f).padding(start = 4.dp))
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Industry selector
            item {
                SectionLabel("請選擇目標行業")
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IndustryData.industries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { def ->
                                IndustryChip(
                                    label = def.displayName,
                                    selected = def.id == selectedIndustryId,
                                    modifier = Modifier.weight(1f),
                                    onClick = { selectedIndustryId = def.id }
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // Target role
            item {
                SectionLabel("目標崗位名稱")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = roleInput,
                    onValueChange = { roleInput = it },
                    placeholder = { Text("例：幼稚園教學助理", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue, unfocusedBorderColor = Stroke,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        cursorColor = AccentBlue
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                // Suggested roles
                if (selectedDef.suggestedRoles.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        selectedDef.suggestedRoles.forEach { role ->
                            SuggestionChip(
                                onClick = { roleInput = role },
                                label = { Text(role, fontSize = 12.sp, color = AccentBlue) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = NightElevated
                                ),
                                border = SuggestionChipDefaults.suggestionChipBorder(
                                    enabled = true,
                                    borderColor = Stroke
                                )
                            )
                        }
                    }
                }
            }

            // Region selector
            item {
                SectionLabel("目標地區")
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IndustryData.regions.forEach { region ->
                        RegionChip(
                            label = region,
                            selected = region == selectedRegion,
                            onClick = { selectedRegion = region }
                        )
                    }
                }
            }

            // ATS keyword preview
            item {
                SectionLabel("ATS 關鍵詞庫預覽")
                Spacer(Modifier.height(4.dp))
                Text("以下僅展示部分常見關鍵詞，支付後將生成完整匹配分析",
                    fontSize = 12.sp, color = TextSecondary, lineHeight = 18.sp)
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedDef.sampleKeywords.forEach { kw ->
                        KeywordBadge(kw)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("🔒 更多關鍵詞將在支付後完整展示",
                    fontSize = 12.sp, color = AccentYellow)
            }

            item { Spacer(Modifier.height(8.dp)) }
        }

        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            GradientCta(
                text = "下一步：輸入 JD",
                enabled = canProceed,
                onClick = {
                    vm.setIndustry(selectedIndustryId, selectedDef.displayName)
                    vm.setTargetRole(roleInput)
                    vm.setRegion(selectedRegion)
                    vm.confirmIndustry()
                    onNext()
                }
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
}

@Composable
private fun IndustryChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AccentBlue.copy(alpha = 0.18f) else NightElevated)
            .border(1.dp, if (selected) AccentBlue else Stroke, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) AccentBlue else TextSecondary,
            maxLines = 2,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun RegionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) AccentGreen.copy(alpha = 0.2f) else NightElevated)
            .border(1.dp, if (selected) AccentGreen else Stroke, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 13.sp,
            color = if (selected) AccentGreen else TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun KeywordBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(AccentBlue.copy(alpha = 0.12f))
            .border(1.dp, AccentBlue.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 12.sp, color = AccentBlue)
    }
}
