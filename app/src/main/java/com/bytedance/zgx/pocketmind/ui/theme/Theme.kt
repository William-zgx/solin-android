package com.bytedance.zgx.pocketmind.ui.theme

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
    local = Color(0xFF2F6DEB),
    onLocal = Color(0xFFFFFFFF),
    localContainer = Color(0xFFE6EDFF),
    onLocalContainer = Color(0xFF081B42),
    remote = Color(0xFF7357E8),
    onRemote = Color(0xFFFFFFFF),
    remoteContainer = Color(0xFFEFEAFF),
    onRemoteContainer = Color(0xFF21105B),
    busy = Color(0xFFB46900),
    onBusy = Color(0xFFFFFFFF),
    busyContainer = Color(0xFFFFEECF),
    onBusyContainer = Color(0xFF2B1B00),
    memory = Color(0xFFC8418C),
    memoryContainer = Color(0xFFFFE4F2),
    onMemoryContainer = Color(0xFF47052A),
    codeSurface = Color(0xFFEEF2F8),
    accentLine = Color(0xFF9DB3D9),
)

private val DarkSemanticColors = PocketMindSemanticColors(
    local = Color(0xFF8FB4FF),
    onLocal = Color(0xFF071A3E),
    localContainer = Color(0xFF182A52),
    onLocalContainer = Color(0xFFE0E9FF),
    remote = Color(0xFFB9A7FF),
    onRemote = Color(0xFF21105B),
    remoteContainer = Color(0xFF2C225A),
    onRemoteContainer = Color(0xFFF0ECFF),
    busy = Color(0xFFFFCC7A),
    onBusy = Color(0xFF2F1B00),
    busyContainer = Color(0xFF3C2B12),
    onBusyContainer = Color(0xFFFFE7B8),
    memory = Color(0xFFFF86BF),
    memoryContainer = Color(0xFF4A1732),
    onMemoryContainer = Color(0xFFFFE0EE),
    codeSurface = Color(0xFF111827),
    accentLine = Color(0xFF445A86),
)

val LocalPocketMindColors = staticCompositionLocalOf { DarkSemanticColors }

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6DEB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6EDFF),
    onPrimaryContainer = Color(0xFF081B42),
    secondary = Color(0xFF7357E8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEFEAFF),
    onSecondaryContainer = Color(0xFF21105B),
    tertiary = Color(0xFFC8418C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE4F2),
    onTertiaryContainer = Color(0xFF47052A),
    background = Color(0xFFF7F4EC),
    onBackground = Color(0xFF171A22),
    surface = Color(0xFFFFFDF7),
    onSurface = Color(0xFF171A22),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFBF2),
    surfaceContainer = Color(0xFFF1EEE6),
    surfaceContainerHigh = Color(0xFFE8E4DA),
    surfaceContainerHighest = Color(0xFFDCD8CD),
    surfaceVariant = Color(0xFFE8E2D7),
    onSurfaceVariant = Color(0xFF5A6270),
    outline = Color(0xFF778194),
    outlineVariant = Color(0xFFC7CFDC),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceTint = Color.Transparent,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB4FF),
    onPrimary = Color(0xFF071A3E),
    primaryContainer = Color(0xFF182A52),
    onPrimaryContainer = Color(0xFFE0E9FF),
    secondary = Color(0xFFB9A7FF),
    onSecondary = Color(0xFF21105B),
    secondaryContainer = Color(0xFF2C225A),
    onSecondaryContainer = Color(0xFFF0ECFF),
    tertiary = Color(0xFFFF86BF),
    onTertiary = Color(0xFF4A1732),
    tertiaryContainer = Color(0xFF4A1732),
    onTertiaryContainer = Color(0xFFF0ECFF),
    background = Color(0xFF090B12),
    onBackground = Color(0xFFEFF3FA),
    surface = Color(0xFF10141D),
    onSurface = Color(0xFFEFF3FA),
    surfaceContainerLowest = Color(0xFF070910),
    surfaceContainerLow = Color(0xFF0E121A),
    surfaceContainer = Color(0xFF141925),
    surfaceContainerHigh = Color(0xFF1C2330),
    surfaceContainerHighest = Color(0xFF263043),
    surfaceVariant = Color(0xFF232C3C),
    onSurfaceVariant = Color(0xFFC7D0DF),
    outline = Color(0xFF7D8CA5),
    outlineVariant = Color(0xFF34425A),
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
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(5.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun PocketMindTheme(
    useDarkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPocketMindColors provides if (useDarkTheme) DarkSemanticColors else LightSemanticColors,
    ) {
        MaterialTheme(
            colorScheme = if (useDarkTheme) DarkColors else LightColors,
            typography = PocketMindTypography,
            shapes = PocketMindShapes,
            content = content,
        )
    }
}
