package com.cvdoor.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.cvdoor.app.ui.theme.*

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.28f)
        ),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CardStroke),
        content = { Column(Modifier.padding(16.dp), content = content) }
    )
}

@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.clip(RoundedCornerShape(24.dp)),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            Modifier
                .background(Brush.horizontalGradient(listOf(NeonBlueStart, NeonBlueEnd)))
                .padding(vertical = 14.dp)
                .fillMaxWidth(),
        ) { Text(text, modifier = Modifier.padding(horizontal = 8.dp)) }
    }
}

@Composable
fun GradientProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: androidx.compose.ui.graphics.Color = Track
) {
    val barShape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .height(10.dp)
            .clip(barShape)
            .background(trackColor)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(Brush.horizontalGradient(listOf(NeonBlueStart, NeonBlueEnd)))
        )
    }
}
