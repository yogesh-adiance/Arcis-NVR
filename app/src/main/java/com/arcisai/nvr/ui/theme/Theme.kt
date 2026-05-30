package com.arcisai.nvr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary  = Color(0xFF7D6BE8),
    secondary = Color(0xFF03DAC6),
    background = Color(0xFF0B0B14),
    surface    = Color(0xFF16162A),
)
private val LightColors = lightColorScheme(
    primary  = Color(0xFF5E4FBA),
    secondary = Color(0xFF018786),
)

@Composable
fun ArcisNvrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
