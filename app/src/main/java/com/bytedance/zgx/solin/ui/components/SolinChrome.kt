package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextOverflow
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
    Menu,
    More,
    Spark,
    Shield,
    Stop,
    Undo,
    Voice,
    Send,
}

/** Button content: an 18dp glyph in the button's content colour, then [text]. */
@Composable
internal fun RowScope.GlyphLabel(kind: SolinGlyphKind, text: String) {
    SolinGlyph(
        kind = kind,
        modifier = Modifier.size(18.dp),
        tint = LocalContentColor.current,
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(text)
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

            SolinGlyphKind.Menu -> {
                drawLine(tint, p(0.20f, 0.30f), p(0.80f, 0.30f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.20f, 0.50f), p(0.64f, 0.50f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.20f, 0.70f), p(0.80f, 0.70f), strokeWidth = stroke.width, cap = StrokeCap.Round)
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

/**
 * The placement mark: Solin's one visual signature. A filled dot means the work runs on this
 * phone; a ring with a dashed outer ring means content leaves the phone. Use it wherever the
 * user needs to know where their data goes (top bar, composer, reply footers, setup choices).
 */
@Composable
internal fun PlacementMark(
    leavesPhone: Boolean,
    modifier: Modifier = Modifier.size(10.dp),
    tint: Color = if (leavesPhone) LocalSolinColors.current.remote else LocalSolinColors.current.local,
) {
    Canvas(modifier = modifier) {
        val unit = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        if (!leavesPhone) {
            drawCircle(tint, radius = unit * 0.5f, center = center)
        } else {
            drawCircle(tint, radius = unit * 0.34f, center = center, style = Stroke(width = unit * 0.14f))
            drawCircle(
                tint.copy(alpha = 0.7f),
                radius = unit * 0.5f - unit * 0.04f,
                center = center,
                style = Stroke(
                    width = unit * 0.08f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(unit * 0.12f, unit * 0.10f)),
                ),
            )
        }
    }
}

/** A rounded pill with a [PlacementMark] and a short label, tinted by placement. */
@Composable
internal fun PlacementPill(
    label: String,
    leavesPhone: Boolean?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalSolinColors.current
    val container = when (leavesPhone) {
        true -> colors.remoteContainer
        false -> colors.localContainer
        null -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when (leavesPhone) {
        true -> colors.onRemoteContainer
        false -> colors.onLocalContainer
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .padding(start = 10.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlacementMark(
                leavesPhone = leavesPhone == true,
                tint = if (leavesPhone == null) content else (if (leavesPhone) colors.remote else colors.local),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    // A clickable M3 Surface pads its touch target to 48dp but draws at the pill's size.
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = CircleShape, color = container) { body() }
    } else {
        Surface(modifier = modifier, shape = CircleShape, color = container) { body() }
    }
}
