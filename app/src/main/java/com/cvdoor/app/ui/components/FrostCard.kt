package com.cvdoor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cvdoor.app.ui.theme.CardStroke
import com.cvdoor.app.ui.theme.NightElevated

/**
 * 與原 GlassCard 功能相同，只是換了名字防止重載衝突
 */
@Composable
fun FrostCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .shadow(8.dp, shape, ambientColor = Color.Black.copy(.35f), spotColor = Color.Black.copy(.35f))
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    listOf(
                        NightElevated.copy(alpha = .96f),
                        NightElevated.copy(alpha = .90f)
                    )
                )
            )
            .padding(16.dp)
            .background(Brush.linearGradient(listOf(CardStroke, Color.Transparent)), shape)
    ) {
        content()
    }
}
