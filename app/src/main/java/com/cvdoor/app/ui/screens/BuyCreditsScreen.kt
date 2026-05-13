// File: app/src/main/java/com/cvdoor/app/ui/screens/BuyCreditsScreen.kt
package com.cvdoor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cvdoor.app.ui.theme.NightElevated
import com.cvdoor.app.ui.theme.NightNavy
import com.cvdoor.app.ui.theme.TextPrimary
import com.cvdoor.app.ui.theme.TextSecondary
import com.cvdoor.app.util.MainAppVM

private data class Plan(
    val credits: Int,
    val price: Double,
    val tag: String = "",
    val discountPct: Int = 0
)

@Composable
fun BuyCreditsScreen(
    onBack: () -> Unit,
    vm: MainAppVM = viewModel()
) {
    val plans = remember {
        listOf(
            Plan(credits = 1,   price = 0.99,  tag = "Trial"),
            Plan(credits = 10,  price = 5.99,  tag = "Starter"),
            Plan(credits = 50,  price = 24.99, tag = "Pro",   discountPct = 20),
            Plan(credits = 100, price = 39.99, tag = "Max",   discountPct = 33),
        )
    }
    var pending by remember { mutableStateOf<Plan?>(null) }

    Surface(color = NightNavy) {
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
                    TextButton(onClick = onBack) { Text("<  Back", color = TextSecondary) }
                    Spacer(Modifier.weight(1f))
                    Text("Buy Credits", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(12.dp))
                }
            }
        ) { inner ->
            Column(
                modifier = Modifier
                    .padding(inner)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())  // ✅ 可滚动，最后一项不会被裁掉
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Choose a package", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

                plans.forEach { p ->
                    PlanCard(
                        plan = p,
                        onBuy = { pending = p }
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Prices are examples. This screen currently adds credits directly. " +
                            "Integrate Google Play Billing to charge real payments.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(50.dp)) // ✅ 额外底部空间，避免被导航栏挡住
            }
        }
    }

    pending?.let { plan ->
        val priceText = "$" + "%.2f".format(plan.price)
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Confirm Purchase") },
            text  = { Text("Buy ${plan.credits} credits for $priceText?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        vm.addCredits(plan.credits)  // ✅ 支付成功后加次数
                        onBack()
                    }
                ) { Text("Buy Now") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PlanCard(
    plan: Plan,
    onBuy: () -> Unit
) {
    val unit = plan.price / plan.credits
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NightElevated),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brushes.cardGrad)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${plan.credits} Credits",
                    color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                if (plan.tag.isNotBlank()) {
                    AssistChip(onClick = {}, enabled = false, label = { Text(plan.tag) })
                }
                Spacer(Modifier.weight(1f))
                Text("$" + "%.2f".format(plan.price), color = Color(0xFF7CC4FF), fontSize = 18.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("≈ $" + "%.2f".format(unit) + "/credit", color = TextSecondary, fontSize = 12.sp)
                if (plan.discountPct > 0) {
                    Spacer(Modifier.width(12.dp))
                    AssistChip(onClick = {}, enabled = false, label = { Text("${plan.discountPct}% OFF") })
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onBuy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) { Text("Buy Now") }
        }
    }
}

private object Brushes {
    val cardGrad: Brush = Brush.verticalGradient(
        colors = listOf(Color(0x2229ABE2), Color(0x1129ABE2))
    )
}
