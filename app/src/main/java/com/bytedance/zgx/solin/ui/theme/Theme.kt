package com.bytedance.zgx.solin.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
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
data class SolinSemanticColors(
    val local: Color,
    val onLocal: Color,
    val localContainer: Color,
    val onLocalContainer: Color,
    val remote: Color,
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
)

// Solin palette: one colour per meaning. Pine = runs on this phone, indigo = leaves the
// phone, amber = waits for the user. Memory is local data, so it shares the local colours.
private val Pine = Color(0xFF2E6A4F)
private val Indigo = Color(0xFF4651A0)
private val Amber = Color(0xFF96560A)

private val LightSemanticColors = SolinSemanticColors(
    local = Pine,
    onLocal = Color(0xFFFFFFFF),
    localContainer = Color(0xFFDCEBE2),
    onLocalContainer = Color(0xFF0F3B27),
    remote = Indigo,
    remoteContainer = Color(0xFFE2E5F6),
    onRemoteContainer = Color(0xFF1C2466),
    busy = Amber,
    onBusy = Color(0xFFFFFFFF),
    busyContainer = Color(0xFFF8EBD6),
    onBusyContainer = Color(0xFF5A3300),
    memory = Pine,
    memoryContainer = Color(0xFFDCEBE2),
    onMemoryContainer = Color(0xFF0F3B27),
    codeSurface = Color(0xFFECEFEA),
)

private val DarkSemanticColors = SolinSemanticColors(
    local = Color(0xFF86CBA6),
    onLocal = Color(0xFF0B3322),
    localContainer = Color(0xFF1F3B2E),
    onLocalContainer = Color(0xFFC6EBD5),
    remote = Color(0xFFAAB2F2),
    remoteContainer = Color(0xFF272C52),
    onRemoteContainer = Color(0xFFDDE0FF),
    busy = Color(0xFFF0B866),
    onBusy = Color(0xFF3F2A00),
    busyContainer = Color(0xFF3B2C14),
    onBusyContainer = Color(0xFFFFDDB0),
    memory = Color(0xFF86CBA6),
    memoryContainer = Color(0xFF1F3B2E),
    onMemoryContainer = Color(0xFFC6EBD5),
    codeSurface = Color(0xFF1A201D),
)

val LocalSolinColors = staticCompositionLocalOf { DarkSemanticColors }

private val LightColors = lightColorScheme(
    primary = Pine,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCEBE2),
    onPrimaryContainer = Color(0xFF0F3B27),
    secondary = Indigo,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E5F6),
    onSecondaryContainer = Color(0xFF1C2466),
    tertiary = Amber,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF8EBD6),
    onTertiaryContainer = Color(0xFF5A3300),
    background = Color(0xFFF5F6F3),
    onBackground = Color(0xFF1A2320),
    surface = Color(0xFFF5F6F3),
    onSurface = Color(0xFF1A2320),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F2EE),
    surfaceContainer = Color(0xFFECEFEA),
    surfaceContainerHigh = Color(0xFFE4E9E3),
    surfaceContainerHighest = Color(0xFFDCE1DA),
    surfaceVariant = Color(0xFFE4E9E3),
    onSurfaceVariant = Color(0xFF5B6661),
    outline = Color(0xFF7A857F),
    outlineVariant = Color(0xFFDCE1DA),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    surfaceTint = Color.Transparent,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF86CBA6),
    onPrimary = Color(0xFF0B3322),
    primaryContainer = Color(0xFF1F3B2E),
    onPrimaryContainer = Color(0xFFC6EBD5),
    secondary = Color(0xFFAAB2F2),
    onSecondary = Color(0xFF1A2266),
    secondaryContainer = Color(0xFF272C52),
    onSecondaryContainer = Color(0xFFDDE0FF),
    tertiary = Color(0xFFF0B866),
    onTertiary = Color(0xFF3F2A00),
    tertiaryContainer = Color(0xFF3B2C14),
    onTertiaryContainer = Color(0xFFFFDDB0),
    background = Color(0xFF121715),
    onBackground = Color(0xFFE7ECE8),
    surface = Color(0xFF121715),
    onSurface = Color(0xFFE7ECE8),
    surfaceContainerLowest = Color(0xFF0D1110),
    surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1A201D),
    surfaceContainerHigh = Color(0xFF222A26),
    surfaceContainerHighest = Color(0xFF2C3531),
    surfaceVariant = Color(0xFF222A26),
    onSurfaceVariant = Color(0xFF9CA8A2),
    outline = Color(0xFF6F7B75),
    outlineVariant = Color(0xFF2C3531),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    surfaceTint = Color.Transparent,
)

// Chinese text needs more leading than Latin; body styles run at about 1.6x.
private val SolinTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
)

// Radius grows with the size of the surface: chips < cards < sheets.
private val SolinShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SolinTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSolinColors provides if (useDarkTheme) DarkSemanticColors else LightSemanticColors,
    ) {
        MaterialTheme(
            colorScheme = if (useDarkTheme) DarkColors else LightColors,
            typography = SolinTypography,
            shapes = SolinShapes,
            content = content,
        )
    }
}
