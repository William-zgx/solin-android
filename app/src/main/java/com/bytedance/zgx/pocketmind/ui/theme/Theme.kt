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
    local = Color(0xFF007665),
    onLocal = Color.White,
    localContainer = Color(0xFFD7F4ED),
    onLocalContainer = Color(0xFF06231F),
    remote = Color(0xFF2969B0),
    onRemote = Color.White,
    remoteContainer = Color(0xFFDCEAFF),
    onRemoteContainer = Color(0xFF081D36),
    busy = Color(0xFF9A6400),
    onBusy = Color.White,
    busyContainer = Color(0xFFFFE3B0),
    onBusyContainer = Color(0xFF2E1B00),
    memory = Color(0xFF7658B9),
    memoryContainer = Color(0xFFE9DDFF),
    onMemoryContainer = Color(0xFF24133F),
    codeSurface = Color(0xFFF2F5F4),
    accentLine = Color(0xFF9CE8DA),
)

private val DarkSemanticColors = PocketMindSemanticColors(
    local = Color(0xFF7CE0CF),
    onLocal = Color(0xFF052421),
    localContainer = Color(0xFF173B36),
    onLocalContainer = Color(0xFFD9FFF7),
    remote = Color(0xFF8DBDFF),
    onRemote = Color(0xFF081E3B),
    remoteContainer = Color(0xFF182E48),
    onRemoteContainer = Color(0xFFDCEAFF),
    busy = Color(0xFFFFCA74),
    onBusy = Color(0xFF2C1A00),
    busyContainer = Color(0xFF3D2D16),
    onBusyContainer = Color(0xFFFFE4B7),
    memory = Color(0xFFC9B7FF),
    memoryContainer = Color(0xFF332A45),
    onMemoryContainer = Color(0xFFF0E8FF),
    codeSurface = Color(0xFF0B0E0D),
    accentLine = Color(0xFF45645E),
)

val LocalPocketMindColors = staticCompositionLocalOf { DarkSemanticColors }

private val LightColors = lightColorScheme(
    primary = Color(0xFF007665),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F4ED),
    onPrimaryContainer = Color(0xFF06231F),
    secondary = Color(0xFF54615E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E9E6),
    onSecondaryContainer = Color(0xFF101F1C),
    tertiary = Color(0xFF2969B0),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCEAFF),
    onTertiaryContainer = Color(0xFF081D36),
    background = Color(0xFFF7F9F8),
    onBackground = Color(0xFF171B1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171B1A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F7F5),
    surfaceContainer = Color(0xFFF0F4F2),
    surfaceContainerHigh = Color(0xFFE9EFEC),
    surfaceContainerHighest = Color(0xFFE3E9E6),
    surfaceVariant = Color(0xFFE3E8E6),
    onSurfaceVariant = Color(0xFF4B5652),
    outline = Color(0xFF76827E),
    outlineVariant = Color(0xFFC5CFCA),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CE0CF),
    onPrimary = Color(0xFF052421),
    primaryContainer = Color(0xFF173B36),
    onPrimaryContainer = Color(0xFFD9FFF7),
    secondary = Color(0xFFAEB9B5),
    onSecondary = Color(0xFF1B2421),
    secondaryContainer = Color(0xFF26312E),
    onSecondaryContainer = Color(0xFFE2E9E6),
    tertiary = Color(0xFF8DBDFF),
    onTertiary = Color(0xFF081E3B),
    tertiaryContainer = Color(0xFF182E48),
    onTertiaryContainer = Color(0xFFDCEAFF),
    background = Color(0xFF0E1110),
    onBackground = Color(0xFFE6ECE9),
    surface = Color(0xFF171B1A),
    onSurface = Color(0xFFE6ECE9),
    surfaceContainerLowest = Color(0xFF0A0D0C),
    surfaceContainerLow = Color(0xFF121615),
    surfaceContainer = Color(0xFF171B1A),
    surfaceContainerHigh = Color(0xFF1D2421),
    surfaceContainerHighest = Color(0xFF25302C),
    surfaceVariant = Color(0xFF242B29),
    onSurfaceVariant = Color(0xFFBBC7C2),
    outline = Color(0xFF687570),
    outlineVariant = Color(0xFF35413D),
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
