package com.bytedance.zgx.pocketmind.ui.theme

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
    local = Color(0xFF3157FF),
    onLocal = Color(0xFFFFFFFF),
    localContainer = Color(0xFFE3E9FF),
    onLocalContainer = Color(0xFF071B6F),
    remote = Color(0xFF00A89D),
    onRemote = Color(0xFFFFFFFF),
    remoteContainer = Color(0xFFCFF8F0),
    onRemoteContainer = Color(0xFF003B38),
    busy = Color(0xFFFF8A00),
    onBusy = Color(0xFFFFFFFF),
    busyContainer = Color(0xFFFFE2B8),
    onBusyContainer = Color(0xFF4A2600),
    memory = Color(0xFFFF4D7D),
    memoryContainer = Color(0xFFFFE1EA),
    onMemoryContainer = Color(0xFF5D0B29),
    codeSurface = Color(0xFFF1F6FF),
    accentLine = Color(0xFF9AA9FF),
)

private val DarkSemanticColors = PocketMindSemanticColors(
    local = Color(0xFFA9B8FF),
    onLocal = Color(0xFF071642),
    localContainer = Color(0xFF25306E),
    onLocalContainer = Color(0xFFE8EBFF),
    remote = Color(0xFF65E2D3),
    onRemote = Color(0xFF003A37),
    remoteContainer = Color(0xFF0A4B49),
    onRemoteContainer = Color(0xFFD5FFF8),
    busy = Color(0xFFFFBE6B),
    onBusy = Color(0xFF3B1D00),
    busyContainer = Color(0xFF53330A),
    onBusyContainer = Color(0xFFFFE7B2),
    memory = Color(0xFFFF8FAE),
    memoryContainer = Color(0xFF642039),
    onMemoryContainer = Color(0xFFFFDDE8),
    codeSurface = Color(0xFF111827),
    accentLine = Color(0xFF7D8DFF),
)

val LocalPocketMindColors = staticCompositionLocalOf { DarkSemanticColors }

private val LightColors = lightColorScheme(
    primary = Color(0xFF3157FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3E9FF),
    onPrimaryContainer = Color(0xFF071B6F),
    secondary = Color(0xFF00A89D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFF8F0),
    onSecondaryContainer = Color(0xFF003B38),
    tertiary = Color(0xFFFF4D7D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE1EA),
    onTertiaryContainer = Color(0xFF5D0B29),
    background = Color(0xFFF7F9FF),
    onBackground = Color(0xFF101521),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101521),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFF),
    surfaceContainer = Color(0xFFEFF4FF),
    surfaceContainerHigh = Color(0xFFE5ECFB),
    surfaceContainerHighest = Color(0xFFD8E2F3),
    surfaceVariant = Color(0xFFE8EEFA),
    onSurfaceVariant = Color(0xFF536070),
    outline = Color(0xFF68788E),
    outlineVariant = Color(0xFFC5D0E1),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceTint = Color.Transparent,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9B8FF),
    onPrimary = Color(0xFF071642),
    primaryContainer = Color(0xFF25306E),
    onPrimaryContainer = Color(0xFFE8EBFF),
    secondary = Color(0xFF65E2D3),
    onSecondary = Color(0xFF003A37),
    secondaryContainer = Color(0xFF0A4B49),
    onSecondaryContainer = Color(0xFFD5FFF8),
    tertiary = Color(0xFFFF8FAE),
    onTertiary = Color(0xFF642039),
    tertiaryContainer = Color(0xFF642039),
    onTertiaryContainer = Color(0xFFFFDDE8),
    background = Color(0xFF080A12),
    onBackground = Color(0xFFEFF3FF),
    surface = Color(0xFF0E1220),
    onSurface = Color(0xFFEFF3FF),
    surfaceContainerLowest = Color(0xFF060811),
    surfaceContainerLow = Color(0xFF121827),
    surfaceContainer = Color(0xFF182032),
    surfaceContainerHigh = Color(0xFF232C42),
    surfaceContainerHighest = Color(0xFF2E3851),
    surfaceVariant = Color(0xFF29334B),
    onSurfaceVariant = Color(0xFFC5CEE0),
    outline = Color(0xFF8592AA),
    outlineVariant = Color(0xFF3A465F),
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
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
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
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun PocketMindTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
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
