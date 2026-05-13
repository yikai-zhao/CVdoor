package com.cvdoor.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary        = NeonBlueStart,
    secondary      = NeonBlueEnd,
    background     = Night,
    surface        = NightElevated,
    onBackground   = TextPrimary,
    onSurface      = TextPrimary,
    onPrimary      = Color.White,
    onSecondary    = Color.Black
)

@Composable
fun CVDoorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography  = Typography(),
        content     = content
    )
}
