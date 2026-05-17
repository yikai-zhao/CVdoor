package com.cvdoor.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.data.OptimizationRecord
import com.cvdoor.app.ui.theme.NightNavy
import com.cvdoor.app.ui.theme.TextPrimary
import com.cvdoor.app.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onOpenDetails: (OptimizationRecord) -> Unit,
    onReOptimize: (OptimizationRecord) -> Unit,
    onDelete: (OptimizationRecord) -> Unit,   // ✅ 調用 MainAppVM.deleteRecord
    onClearAll: () -> Unit,                   // ✅ 調用 MainAppVM.clearHistory
    records: List<OptimizationRecord> = emptyList()
) {
    Surface(color = NightNavy) {
        var toDelete by remember { mutableStateOf<OptimizationRecord?>(null) }
        var askClearAll by remember { mutableStateOf(false) }

        Scaffold(
            containerColor = NightNavy,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) { Text("←  Back", color = TextSecondary) }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { askClearAll = true },
                        modifier = Modifier.padding(end = 8.dp)
                    ) { Text("Clear All") }
                    OutlinedButton(onClick = onLogout) { Text("Logout") }
                }
            },
            content = { inner ->
                LazyColumn(
                    modifier = Modifier
                        .padding(inner)
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            "Optimization History",
                            color = TextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (records.isEmpty()) {
                        item {
                            Text("No records yet.", color = TextSecondary)
                        }
                    } else {
                        items(
                            items = records,
                            key = { it.id }  // ✅ 用 id 作爲穩定 key
                        ) { rec ->
                            HistoryItem(
                                rec = rec,
                                onDetails = { onOpenDetails(rec) },
                                onReOptimize = { onReOptimize(rec) },
                                onAskDelete = { toDelete = rec }
                            )
                        }
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        )

        /* 單條刪除確認 */
        if (toDelete != null) {
            AlertDialog(
                onDismissRequest = { toDelete = null },
                title = { Text("Delete this record?") },
                text = { Text("This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        val r = toDelete!!; toDelete = null
                        onDelete(r)   // ✅ 調用 VM.deleteRecord
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { toDelete = null }) { Text("Cancel") }
                }
            )
        }

        /* 清空全部確認 */
        if (askClearAll) {
            AlertDialog(
                onDismissRequest = { askClearAll = false },
                title = { Text("Clear all history?") },
                text = { Text("All records for the current user will be permanently deleted.") },
                confirmButton = {
                    TextButton(onClick = {
                        askClearAll = false
                        onClearAll()  // ✅ 調用 VM.clearHistory
                    }) { Text("Clear All") }
                },
                dismissButton = {
                    TextButton(onClick = { askClearAll = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun HistoryItem(
    rec: OptimizationRecord,
    onDetails: () -> Unit,
    onReOptimize: () -> Unit,
    onAskDelete: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 頂部：公司/職位 + 時間 + 分數
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = rec.titleLine(),
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = rec.createdAt.formatAsTime(),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                val delta = rec.afterTotal - rec.beforeTotal
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${rec.afterTotal}%",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (delta >= 0) "+$delta" else "$delta",
                        color = MaterialTheme.colorScheme.tertiary,
                        fontSize = 12.sp
                    )
                }
            }

            // 進度條
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Match Rate (Overall)", color = TextSecondary, fontSize = 12.sp)
                LinearProgressIndicator(
                    progress = (rec.afterTotal / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Before: ${rec.beforeTotal}%    After: ${rec.afterTotal}%",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            // 操作按鈕
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDetails) { Text("Details") }
                Button(onClick = onReOptimize) { Text("Re-Optimize") }
                OutlinedButton(
                    onClick = onAskDelete,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
            }
        }
    }
}

/* ---------- 工具函數 ---------- */
private fun OptimizationRecord.titleLine(): String {
    val first = jdText.lineSequence().firstOrNull()?.trim().orEmpty()
    if (first.isNotBlank()) return first.take(40)
    val fallback = optimizedText.lineSequence().firstOrNull()?.trim()
        ?: resumeText.lineSequence().firstOrNull()?.trim().orEmpty()
    return if (fallback.isBlank()) "Unknown Job" else fallback.take(40)
}

private fun Long.formatAsTime(): String =
    runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(this))
    }.getOrElse { "N/A" }
