package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal enum class SolinGlyphKind {
    Add,
    Bell,
    Chat,
    Check,
    Close,
    Delete,
    Download,
    Memory,
    More,
    Spark,
    Shield,
    Stop,
    Undo,
    Voice,
    Send,
}

@Composable
internal fun SolinGlyph(
    kind: SolinGlyphKind,
    modifier: Modifier = Modifier.size(20.dp),
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val unit = w.coerceAtMost(h)
        val stroke = Stroke(
            width = unit * 0.11f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        fun p(x: Float, y: Float) = Offset(w * x, h * y)

        when (kind) {
            SolinGlyphKind.Add -> {
                drawCircle(tint, radius = unit * 0.42f, center = p(0.5f, 0.5f), style = stroke)
                drawLine(tint, p(0.34f, 0.5f), p(0.66f, 0.5f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.5f, 0.34f), p(0.5f, 0.66f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Bell -> {
                val bell = Path().apply {
                    moveTo(w * 0.28f, h * 0.58f)
                    lineTo(w * 0.32f, h * 0.42f)
                    quadraticTo(w * 0.34f, h * 0.27f, w * 0.50f, h * 0.27f)
                    quadraticTo(w * 0.66f, h * 0.27f, w * 0.68f, h * 0.42f)
                    lineTo(w * 0.72f, h * 0.58f)
                    lineTo(w * 0.80f, h * 0.68f)
                    lineTo(w * 0.20f, h * 0.68f)
                    close()
                }
                drawPath(bell, tint, style = stroke)
                drawLine(tint, p(0.43f, 0.80f), p(0.57f, 0.80f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Chat -> {
                val bubble = Path().apply {
                    moveTo(w * 0.26f, h * 0.24f)
                    lineTo(w * 0.76f, h * 0.24f)
                    quadraticTo(w * 0.86f, h * 0.24f, w * 0.86f, h * 0.35f)
                    lineTo(w * 0.86f, h * 0.58f)
                    quadraticTo(w * 0.86f, h * 0.69f, w * 0.74f, h * 0.69f)
                    lineTo(w * 0.50f, h * 0.69f)
                    lineTo(w * 0.31f, h * 0.82f)
                    lineTo(w * 0.34f, h * 0.69f)
                    lineTo(w * 0.26f, h * 0.69f)
                    quadraticTo(w * 0.14f, h * 0.69f, w * 0.14f, h * 0.58f)
                    lineTo(w * 0.14f, h * 0.35f)
                    quadraticTo(w * 0.14f, h * 0.24f, w * 0.26f, h * 0.24f)
                }
                drawPath(bubble, tint, style = stroke)
                drawLine(tint, p(0.32f, 0.43f), p(0.68f, 0.43f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.32f, 0.55f), p(0.55f, 0.55f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Check -> {
                drawCircle(tint, radius = unit * 0.40f, center = p(0.5f, 0.5f), style = stroke)
                drawLine(tint, p(0.34f, 0.52f), p(0.46f, 0.64f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.46f, 0.64f), p(0.68f, 0.38f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Close -> {
                drawCircle(tint, radius = unit * 0.38f, center = p(0.5f, 0.5f), style = stroke)
                drawLine(tint, p(0.38f, 0.38f), p(0.62f, 0.62f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.62f, 0.38f), p(0.38f, 0.62f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Delete -> {
                drawLine(tint, p(0.30f, 0.34f), p(0.70f, 0.34f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.42f, 0.22f), p(0.58f, 0.22f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.46f, 0.22f), p(0.42f, 0.34f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.54f, 0.22f), p(0.58f, 0.34f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                val bin = Path().apply {
                    moveTo(w * 0.34f, h * 0.40f)
                    lineTo(w * 0.66f, h * 0.40f)
                    lineTo(w * 0.62f, h * 0.80f)
                    lineTo(w * 0.38f, h * 0.80f)
                    close()
                }
                drawPath(bin, tint, style = stroke)
            }

            SolinGlyphKind.Download -> {
                drawLine(tint, p(0.50f, 0.20f), p(0.50f, 0.58f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.34f, 0.44f), p(0.50f, 0.60f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.66f, 0.44f), p(0.50f, 0.60f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.26f, 0.76f), p(0.74f, 0.76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.26f, 0.64f), p(0.26f, 0.76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.74f, 0.64f), p(0.74f, 0.76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Memory -> {
                drawCircle(tint, radius = unit * 0.09f, center = p(0.30f, 0.32f), style = stroke)
                drawCircle(tint, radius = unit * 0.09f, center = p(0.70f, 0.32f), style = stroke)
                drawCircle(tint, radius = unit * 0.09f, center = p(0.50f, 0.72f), style = stroke)
                drawLine(tint, p(0.36f, 0.38f), p(0.46f, 0.64f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.64f, 0.38f), p(0.54f, 0.64f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.39f, 0.32f), p(0.61f, 0.32f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.More -> {
                drawCircle(tint, radius = unit * 0.07f, center = p(0.5f, 0.26f))
                drawCircle(tint, radius = unit * 0.07f, center = p(0.5f, 0.5f))
                drawCircle(tint, radius = unit * 0.07f, center = p(0.5f, 0.74f))
            }

            SolinGlyphKind.Send -> {
                val arrow = Path().apply {
                    moveTo(w * 0.24f, h * 0.22f)
                    lineTo(w * 0.82f, h * 0.50f)
                    lineTo(w * 0.24f, h * 0.78f)
                    lineTo(w * 0.36f, h * 0.53f)
                    lineTo(w * 0.82f, h * 0.50f)
                    lineTo(w * 0.36f, h * 0.47f)
                    close()
                }
                drawPath(arrow, tint)
            }

            SolinGlyphKind.Shield -> {
                val shield = Path().apply {
                    moveTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.80f, h * 0.28f)
                    lineTo(w * 0.74f, h * 0.60f)
                    quadraticTo(w * 0.70f, h * 0.76f, w * 0.50f, h * 0.86f)
                    quadraticTo(w * 0.30f, h * 0.76f, w * 0.26f, h * 0.60f)
                    lineTo(w * 0.20f, h * 0.28f)
                    close()
                }
                drawPath(shield, tint, style = stroke)
                drawLine(tint, p(0.38f, 0.50f), p(0.48f, 0.60f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.48f, 0.60f), p(0.66f, 0.40f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Spark -> {
                drawLine(tint, p(0.50f, 0.12f), p(0.50f, 0.38f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.50f, 0.62f), p(0.50f, 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.12f, 0.50f), p(0.38f, 0.50f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.62f, 0.50f), p(0.88f, 0.50f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.28f, 0.28f), p(0.39f, 0.39f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.61f, 0.61f), p(0.72f, 0.72f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.72f, 0.28f), p(0.61f, 0.39f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.39f, 0.61f), p(0.28f, 0.72f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawCircle(tint, radius = unit * 0.055f, center = p(0.76f, 0.24f))
            }

            SolinGlyphKind.Stop -> {
                drawCircle(tint, radius = unit * 0.40f, center = p(0.5f, 0.5f), style = stroke)
                drawLine(tint, p(0.36f, 0.36f), p(0.64f, 0.64f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.64f, 0.36f), p(0.36f, 0.64f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Undo -> {
                val curve = Path().apply {
                    moveTo(w * 0.32f, h * 0.34f)
                    lineTo(w * 0.18f, h * 0.50f)
                    lineTo(w * 0.32f, h * 0.66f)
                    moveTo(w * 0.20f, h * 0.50f)
                    lineTo(w * 0.62f, h * 0.50f)
                    quadraticTo(w * 0.80f, h * 0.50f, w * 0.80f, h * 0.68f)
                }
                drawPath(curve, tint, style = stroke)
            }

            SolinGlyphKind.Voice -> {
                drawLine(tint, p(0.18f, 0.55f), p(0.18f, 0.45f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.34f, 0.68f), p(0.34f, 0.32f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.50f, 0.78f), p(0.50f, 0.22f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.66f, 0.68f), p(0.66f, 0.32f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.82f, 0.55f), p(0.82f, 0.45f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
internal fun Modifier.solinTechBackdrop(): Modifier {
    val base = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val lift = MaterialTheme.colorScheme.surfaceContainerLow
    return background(base).drawBehind {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    primary.copy(alpha = 0.12f),
                    secondary.copy(alpha = 0.08f),
                    lift.copy(alpha = 0.50f),
                    base,
                ),
            ),
        )
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    tertiary.copy(alpha = 0.07f),
                    primary.copy(alpha = 0.05f),
                    secondary.copy(alpha = 0.06f),
                    Color.Transparent,
                ),
            ),
        )
    }
}
