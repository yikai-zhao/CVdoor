package com.cvdoor.app.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.ui.theme.AccentBlue
import com.cvdoor.app.ui.theme.CardNavy
import com.cvdoor.app.ui.theme.Stroke
import com.cvdoor.app.ui.theme.TextPrimary
import com.cvdoor.app.ui.theme.TextSecondary

enum class UnfilledHandling { GO_FILL, REMOVE_AND_EXPORT, EXPORT_AS_IS }

@Composable
fun ExportConfirmDialog(
    hasUnfilledData: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (ExportFormat, UnfilledHandling) -> Unit,
    onGoFillData: (() -> Unit)? = null
) {
    var selectedFormat by remember { mutableStateOf(ExportFormat.PDF) }
    var selectedHandling by remember { mutableStateOf(UnfilledHandling.EXPORT_AS_IS) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasUnfilledData) "導出前確認" else "導出確認", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (hasUnfilledData) "檢測到未填寫真實數據或佔位符。"
                    else "確認導出當前版本？",
                    fontSize = 13.sp,
                    color = TextSecondary
                )

                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(CardNavy).border(1.dp, Stroke, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("格式", fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    ExportFormat.entries.forEach { format ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedFormat == format, onClick = { selectedFormat = format })
                            Text(if (format == ExportFormat.PDF) "PDF" else "Word", color = TextPrimary)
                        }
                    }
                }

                if (hasUnfilledData) {
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(CardNavy).border(1.dp, Stroke, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("未填寫項處理", fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedHandling == UnfilledHandling.GO_FILL,
                                onClick = { selectedHandling = UnfilledHandling.GO_FILL })
                            Text("去填寫後導出", color = TextPrimary)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedHandling == UnfilledHandling.REMOVE_AND_EXPORT,
                                onClick = { selectedHandling = UnfilledHandling.REMOVE_AND_EXPORT })
                            Text("刪除未填寫建議並導出", color = TextPrimary)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedHandling == UnfilledHandling.EXPORT_AS_IS,
                                onClick = { selectedHandling = UnfilledHandling.EXPORT_AS_IS })
                            Text("導出普通優化版", color = TextPrimary)
                        }
                    }

                    if (selectedHandling == UnfilledHandling.GO_FILL && onGoFillData != null) {
                        OutlinedButton(onClick = onGoFillData, modifier = Modifier.fillMaxWidth()) {
                            Text("去填寫數據", color = AccentBlue)
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedFormat, selectedHandling) }) {
                Text("確認導出")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
