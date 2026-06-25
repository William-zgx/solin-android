package com.bytedance.zgx.pocketmind.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.pocketmind.ModelCatalog
import com.bytedance.zgx.pocketmind.resource.ResourcePressure
import com.bytedance.zgx.pocketmind.resource.SystemResourceSnapshot

@Composable
fun ResourcePressureEntry(
    snapshot: SystemResourceSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val accent = pressureColor(snapshot.pressure)
    val normal = snapshot.pressure == ResourcePressure.Normal
    val iconColor = if (normal) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        accent
    }
    val dotColor = if (normal) {
        MaterialTheme.colorScheme.outlineVariant
    } else {
        accent
    }

    IconButton(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .testTag("resource_pressure_entry")
            .semantics {
                contentDescription = "设备资源"
                stateDescription = snapshot.pressure.label
            },
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = iconColor,
        ),
    ) {
        Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Memory,
                contentDescription = null,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(if (normal) 6.dp else 8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
fun ResourcePressureDetailSheet(
    snapshot: SystemResourceSnapshot,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("resource_pressure_panel")
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = "设备资源 · ${snapshot.pressure.label}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "App ${ModelCatalog.formatBytes(snapshot.appPssBytes)} · 可用 ${
                    ModelCatalog.formatBytes(snapshot.availableRamBytes)
                }",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ResourceRow("状态", snapshot.pressure.label)
                ResourceRow("App 内存", "${ModelCatalog.formatBytes(snapshot.appPssBytes)} PSS")
                ResourceRow("可用 RAM", availableRamText(snapshot))
                ResourceRow("App CPU", snapshot.appCpuPercent?.let { "$it%" } ?: "采样中")
                ResourceRow("温度", snapshot.thermalPressure.label)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "高级指标",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider()
            ResourceRow(
                "Heap",
                "Java ${ModelCatalog.formatBytes(snapshot.javaHeapBytes)} · Native ${
                    ModelCatalog.formatBytes(snapshot.nativeHeapBytes)
                }",
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ResourceRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(0.34f),
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            modifier = Modifier.weight(0.66f),
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
    }
}

private fun availableRamText(snapshot: SystemResourceSnapshot): String =
    buildString {
        append(ModelCatalog.formatBytes(snapshot.availableRamBytes))
        if (snapshot.lowMemory) append(" · 低内存")
    }

private fun pressureColor(pressure: ResourcePressure) = when (pressure) {
    ResourcePressure.Normal -> Color(0xFF2E7D32)
    ResourcePressure.Warm -> Color(0xFFF9A825)
    ResourcePressure.Hot -> Color(0xFFC62828)
}
