package com.microdose.discipline.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MdGreen = Color(0xFF2E7D32)
private val MdOrange = Color(0xFFFF7043)

private val LightColors = lightColorScheme(primary = MdGreen, secondary = MdOrange)
private val DarkColors = darkColorScheme(primary = MdGreen, secondary = MdOrange)

@Composable
fun MicroDoseTheme(darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
