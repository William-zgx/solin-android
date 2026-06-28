package com.bytedance.zgx.solin.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.resource.ResourcePressure
import com.bytedance.zgx.solin.resource.SystemResourceSnapshot

@Composable
fun ResourcePressureBadge(
    snapshot: SystemResourceSnapshot,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val accent = pressureColor(snapshot.pressure)
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .size(28.dp)
                .testTag("resource_pressure_badge")
                .semantics {
                    role = Role.Button
                    contentDescription = "设备资源压力：${snapshot.pressurePercent}%，${snapshot.pressure.label}"
                }
                .clickable { expanded = !expanded },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
            shadowElevation = 1.dp,
        ) {
            Box(
                modifier = Modifier.background(accent.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${snapshot.pressurePercent}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                    textAlign = TextAlign.Center,
                    color = accent,
                )
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

@Composable
private fun ResourcePressurePanel(
    snapshot: SystemResourceSnapshot,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ResourceRow("状态", snapshot.pressure.label)
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
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
    }
}

@Composable
private fun pressureColor(pressure: ResourcePressure) = when (pressure) {
    ResourcePressure.Normal -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
    ResourcePressure.Warm -> androidx.compose.ui.graphics.Color(0xFFF9A825)
    ResourcePressure.Hot -> androidx.compose.ui.graphics.Color(0xFFC62828)
}
