package com.bytedance.zgx.pocketmind.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class PocketMindSemanticColors(
    val local: Color,
    val onLocal: Color,
    val localContainer: Color,
    val onLocalContainer: Color,
    val remote: Color,
    val onRemote: Color,
    val remoteContainer: Color,
    val onRemoteContainer: Color,
    val busy: Color,
    val onBusy: Color,
    val busyContainer: Color,
    val onBusyContainer: Color,
    val memory: Color,
    val memoryContainer: Color,
    val onMemoryContainer: Color,
    val codeSurface: Color,
    val accentLine: Color,
)

private val LightSemanticColors = PocketMindSemanticColors(
    local = Color(0xFF006C7A),
    onLocal = Color.White,
    localContainer = Color(0xFFD5F7F5),
    onLocalContainer = Color(0xFF042225),
    remote = Color(0xFF2463D8),
    onRemote = Color.White,
    remoteContainer = Color(0xFFDBE7FF),
    onRemoteContainer = Color(0xFF061E4F),
    busy = Color(0xFFA55F00),
    onBusy = Color(0xFFFFFFFF),
    busyContainer = Color(0xFFFFE4B5),
    onBusyContainer = Color(0xFF321A00),
    memory = Color(0xFF6A5ACD),
    memoryContainer = Color(0xFFE8E2FF),
    onMemoryContainer = Color(0xFF201645),
    codeSurface = Color(0xFFEFF5F8),
    accentLine = Color(0xFF6FE7D7),
)

private val DarkSemanticColors = PocketMindSemanticColors(
    local = Color(0xFF63F2D6),
    onLocal = Color(0xFF002421),
    localContainer = Color(0xFF0A3B42),
    onLocalContainer = Color(0xFFD0FFF8),
    remote = Color(0xFF82B6FF),
    onRemote = Color(0xFF041D3F),
    remoteContainer = Color(0xFF122E55),
    onRemoteContainer = Color(0xFFD9E8FF),
    busy = Color(0xFFF4C36A),
    onBusy = Color(0xFF2F1B00),
    busyContainer = Color(0xFF3B2B12),
    onBusyContainer = Color(0xFFFFE4B3),
    memory = Color(0xFFC4B5FF),
    memoryContainer = Color(0xFF302554),
    onMemoryContainer = Color(0xFFF0EAFF),
    codeSurface = Color(0xFF071019),
    accentLine = Color(0xFF225C68),
)

val LocalPocketMindColors = staticCompositionLocalOf { DarkSemanticColors }

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C7A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5F7F5),
    onPrimaryContainer = Color(0xFF042225),
    secondary = Color(0xFF4C5F6D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2ECF2),
    onSecondaryContainer = Color(0xFF101F29),
    tertiary = Color(0xFF2463D8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDBE7FF),
    onTertiaryContainer = Color(0xFF061E4F),
    background = Color(0xFFF4F8FB),
    onBackground = Color(0xFF111820),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111820),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FBFD),
    surfaceContainer = Color(0xFFF0F5F8),
    surfaceContainerHigh = Color(0xFFE8F0F5),
    surfaceContainerHighest = Color(0xFFDDE8EF),
    surfaceVariant = Color(0xFFE1EAF0),
    onSurfaceVariant = Color(0xFF50606B),
    outline = Color(0xFF6D7D87),
    outlineVariant = Color(0xFFC2D0D8),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceTint = Color.Transparent,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF63F2D6),
    onPrimary = Color(0xFF002421),
    primaryContainer = Color(0xFF0A3B42),
    onPrimaryContainer = Color(0xFFD0FFF8),
    secondary = Color(0xFFB7C8D6),
    onSecondary = Color(0xFF14212B),
    secondaryContainer = Color(0xFF22313D),
    onSecondaryContainer = Color(0xFFD7E7F3),
    tertiary = Color(0xFF82B6FF),
    onTertiary = Color(0xFF041D3F),
    tertiaryContainer = Color(0xFF122E55),
    onTertiaryContainer = Color(0xFFD9E8FF),
    background = Color(0xFF060A0F),
    onBackground = Color(0xFFE7EEF4),
    surface = Color(0xFF0C1218),
    onSurface = Color(0xFFE7EEF4),
    surfaceContainerLowest = Color(0xFF04070B),
    surfaceContainerLow = Color(0xFF0A1118),
    surfaceContainer = Color(0xFF0F1821),
    surfaceContainerHigh = Color(0xFF16222D),
    surfaceContainerHighest = Color(0xFF1F2E3A),
    surfaceVariant = Color(0xFF1A2934),
    onSurfaceVariant = Color(0xFFB9C7D1),
    outline = Color(0xFF6F828D),
    outlineVariant = Color(0xFF263946),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceTint = Color.Transparent,
)

private val PocketMindTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp,
    ),
)

private val PocketMindShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

@Composable
fun PocketMindTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalPocketMindColors provides if (dark) DarkSemanticColors else LightSemanticColors,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = PocketMindTypography,
            shapes = PocketMindShapes,
            content = content,
        )
    }
}
