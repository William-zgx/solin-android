package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors

@Composable
internal fun DeviceCheck(
    state: ChatUiState,
    requiredBytes: Long,
) {
    val hasPendingDownload = requiredBytes > 0L
    val hasEnoughSpace = !hasPendingDownload ||
        ModelCatalog.hasEnoughSpace(state.availableModelStorageBytes, requiredBytes)
    PanelSurface {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "设备检查",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DeviceMetric(
                    modifier = Modifier.weight(1f),
                    label = "架构",
                    value = if (state.isArm64Supported) "arm64" else "不支持",
                    good = state.isArm64Supported,
                )
                DeviceMetric(
                    modifier = Modifier.weight(1f),
                    label = "空间",
                    value = ModelCatalog.formatBytes(state.availableModelStorageBytes),
                    good = hasEnoughSpace,
                )
                DeviceMetric(
                    modifier = Modifier.weight(1f),
                    label = "待下载",
                    value = if (hasPendingDownload) {
                        ModelCatalog.formatBytes(requiredBytes)
                    } else {
                        "已就绪"
                    },
                    good = true,
                )
            }
            if (!state.isArm64Supported) {
                Text(
                    text = "此模型需要 64 位 ARM 安卓设备。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!hasEnoughSpace) {
                Text(
                    text = "建议至少预留 ${ModelCatalog.formatBytes(requiredBytes)}，再多留一些空间给加载缓存；也可以先配置远程模型，或导入你信任的更小 .litertlm。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun DeviceMetric(
    modifier: Modifier,
    label: String,
    value: String,
    good: Boolean,
) {
    val semanticColors = LocalSolinColors.current
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (good) {
                    semanticColors.localContainer.copy(alpha = 0.52f)
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (good) {
                semanticColors.onLocalContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ProgressBlock(state: ChatUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val progress = state.downloadProgressPercent
        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when {
                    progress != null -> "$progress%"
                    state.isPreparingDownload -> "准备中"
                    else -> ModelCatalog.formatBytes(state.downloadedBytes)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.totalBytes > 0L) {
            Text(
                text = "${ModelCatalog.formatBytes(state.downloadedBytes)} / " +
                    ModelCatalog.formatBytes(state.totalBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
