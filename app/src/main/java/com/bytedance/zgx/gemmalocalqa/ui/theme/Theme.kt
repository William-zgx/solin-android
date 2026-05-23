package com.bytedance.zgx.gemmalocalqa.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBDEFE4),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8DF),
    onSecondaryContainer = Color(0xFF06201B),
    tertiary = Color(0xFF445E7C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7E3FF),
    onTertiaryContainer = Color(0xFF001B35),
    background = Color(0xFFF8FAF9),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDFE5E2),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7975),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7EDCCB),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFFBDEFE4),
    secondary = Color(0xFFB0CCC4),
    onSecondary = Color(0xFF1B3530),
    secondaryContainer = Color(0xFF334B46),
    onSecondaryContainer = Color(0xFFCCE8DF),
    tertiary = Color(0xFFADC7E8),
    onTertiary = Color(0xFF15324B),
    tertiaryContainer = Color(0xFF2C4863),
    onTertiaryContainer = Color(0xFFD7E3FF),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE0E3E1),
    surface = Color(0xFF181C1B),
    onSurface = Color(0xFFE0E3E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = Color(0xFF89938F),
)

@Composable
fun GemmaLocalQATheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
