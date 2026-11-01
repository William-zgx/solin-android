package com.bytedance.zgx.solin.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.resource.ResourcePressure
import com.bytedance.zgx.solin.resource.SystemResourceSnapshot
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors

/**
 * The device-resource indicator. It only exists while the phone is under pressure: a quiet dot
 * that expands into the numbers. When everything is fine the top bar shows nothing, because a
 * permanently visible PSS/heap badge was noise, not information.
 *
 * The badge keeps a 48dp touch target; the glyph is a small "gauge", and every number lives in the
 * dropdown panel.
 */
@Composable
fun ResourcePressureBadge(
    snapshot: SystemResourceSnapshot,
    modifier: Modifier = Modifier,
) {
    if (!snapshot.pressure.isElevated()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val palette = pressurePalette(snapshot.pressure)
    Box(
        modifier = modifier
            .size(48.dp)
            .testTag("resource_pressure_badge")
            .semantics {
                role = Role.Button
                contentDescription = "设备资源压力：${snapshot.pressure.label}"
            }
            .clickable { expanded = !expanded },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = palette.container,
            border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.30f)),
            tonalElevation = 0.dp,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                PressureGauge(accent = palette.accent)
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ResourcePressurePanel(
                snapshot = snapshot,
                modifier = Modifier
                    .widthIn(min = 196.dp, max = 244.dp)
                    .testTag("resource_pressure_panel"),
            )
        }
    }
}

/** Memory pressure is "elevated" once the sample crosses Warm; Normal stays hidden. */
internal fun ResourcePressure.isElevated(): Boolean = this != ResourcePressure.Normal

/**
 * A three-bar gauge: the bar heights rise together. Deliberately no text, so no fixed tiny font
 * size is needed at any scale.
 */
@Composable
private fun PressureGauge(
    modifier: Modifier = Modifier,
    accent: Color,
) {
    Canvas(modifier = modifier.size(16.dp)) {
        val unit = size.minDimension
        val barWidth = unit * 0.18f
        val gap = unit * 0.14f
        val totalWidth = barWidth * 3 + gap * 2
        val startX = (size.width - totalWidth) / 2f
        val heights = listOf(0.45f, 0.70f, 1f)
        heights.forEachIndexed { index, fraction ->
            val barHeight = unit * fraction
            val x = startX + index * (barWidth + gap)
            drawRoundRect(
                color = accent,
                topLeft = Offset(x, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
private fun ResourcePressurePanel(
    snapshot: SystemResourceSnapshot,
    modifier: Modifier = Modifier,
) {
    val palette = pressurePalette(snapshot.pressure)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ResourceRow("状态", snapshot.pressure.label, valueColor = palette.accent)
            ResourceRow("App 内存", "${ModelCatalog.formatBytes(snapshot.appPssBytes)} PSS")
            ResourceRow(
                "Heap",
                "Java ${ModelCatalog.formatBytes(snapshot.javaHeapBytes)} · Native ${
                    ModelCatalog.formatBytes(snapshot.nativeHeapBytes)
                }",
            )
            ResourceRow("App CPU", snapshot.appCpuPercent?.let { "$it%" } ?: "采样中")
            ResourceRow("温度", snapshot.thermalPressure.label)
            ResourceRow(
                "可用 RAM",
                buildString {
                    append(ModelCatalog.formatBytes(snapshot.availableRamBytes))
                    if (snapshot.lowMemory) append(" · 低内存")
                },
            )
        }
    }
}

@Composable
private fun ResourceRow(
    label: String,
    value: String,
    valueColor: Color? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(0.42f),
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            modifier = Modifier.weight(0.58f),
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
        )
    }
}

@Composable
private fun pressurePalette(pressure: ResourcePressure): PressurePalette {
    val semanticColors = LocalSolinColors.current
    val colorScheme = MaterialTheme.colorScheme
    return when (pressure) {
        ResourcePressure.Normal -> PressurePalette(
            accent = semanticColors.local,
            container = semanticColors.localContainer,
            content = semanticColors.onLocalContainer,
        )
        ResourcePressure.Warm -> PressurePalette(
            accent = semanticColors.busy,
            container = semanticColors.busyContainer,
            content = semanticColors.onBusyContainer,
        )
        ResourcePressure.Hot -> PressurePalette(
            accent = colorScheme.error,
            container = colorScheme.errorContainer,
            content = colorScheme.onErrorContainer,
        )
    }
}

private data class PressurePalette(
    val accent: Color,
    val container: Color,
    val content: Color,
)
