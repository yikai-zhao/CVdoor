package com.cvdoor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cvdoor.app.ui.theme.NeonBlueEnd
import com.cvdoor.app.ui.theme.NeonBlueStart
import com.cvdoor.app.ui.theme.Track

@Composable
fun GradientProgress(progress: Float, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .clip(shape)
            .background(Track)
            .height(12.dp)
            .fillMaxWidth()
    ) {
        Box(
            Modifier
                .clip(shape)
                .background(Brush.horizontalGradient(listOf(NeonBlueStart, NeonBlueEnd)))
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(12.dp)
        )
    }
}
