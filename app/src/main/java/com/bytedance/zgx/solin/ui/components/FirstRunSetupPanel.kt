package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.ui.LOCAL_SETUP_PANEL_DESCRIPTION
import com.bytedance.zgx.solin.ui.LOCAL_SETUP_PANEL_TITLE
import com.bytedance.zgx.solin.ui.MODEL_STARTUP_BANNER_DESCRIPTION
import com.bytedance.zgx.solin.ui.MODEL_STARTUP_BANNER_TITLE
import com.bytedance.zgx.solin.ui.ProgressBlock
import com.bytedance.zgx.solin.ui.capabilityLabel

@Composable
internal fun FirstRunSetupPanel(
    state: ChatUiState,
    onSetupModelToggled: (String, Boolean) -> Unit,
    onDownloadSetupModels: () -> Unit,
    onSkip: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(
                text = LOCAL_SETUP_PANEL_TITLE,
                subtitle = LOCAL_SETUP_PANEL_DESCRIPTION,
            )
            state.basicSetupModels.forEach { model ->
                val selected = model.id in state.setupSelectedModelIds
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        modifier = Modifier.testTag("first_run_model_${model.id}"),
                        checked = selected,
                        enabled = !state.isBusy && !state.isModelInstalled(model.id),
                        onCheckedChange = { onSetupModelToggled(model.id, it) },
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = model.shortName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${capabilityLabel(model.capability)} · ${ModelCatalog.formatBytes(model.byteSize)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.isModelInstalled(model.id)) {
                        Text(
                            text = "已安装",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onSkip,
                    enabled = !state.isBusy,
                ) {
                    Text("先跳过")
                }
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("first_run_download_button"),
                    onClick = onDownloadSetupModels,
                    enabled = !state.isBusy && state.setupSelectedModelIds.isNotEmpty(),
                ) {
                    Text("下载选中的模型")
                }
            }
        }
    }
}

@Composable
internal fun StatusSummaryRow(state: ChatUiState) {
    val modelName = if (state.inferenceMode == InferenceMode.Remote) {
        state.remoteModelConfig.modelName.ifBlank { "远程模型" }
    } else {
        state.installedModels.firstOrNull { it.id == state.activeInstalledModelId }?.displayName
            ?: state.selectedRecommendedModel.shortName
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RuntimeStatusBadge(state)
        Text(
            modifier = Modifier.weight(1f),
            text = modelName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun QuickModelSetup(
    state: ChatUiState,
    onOpenModelManager: () -> Unit,
    onOpenRemoteModelConfig: () -> Unit,
    onPickModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val localModelIsLoading =
        state.inferenceMode == InferenceMode.Local && state.modelPath != null && !state.isReady
    val startupBannerTitle = if (localModelIsLoading) {
        "正在校验并加载本地模型"
    } else {
        MODEL_STARTUP_BANNER_TITLE
    }
    val startupBannerDescription = if (localModelIsLoading) {
        "模型文件已找到，正在进行完整性校验和端侧初始化；完成后可离线问答。"
    } else {
        MODEL_STARTUP_BANNER_DESCRIPTION
    }
    val stackModelActionsForTextScale = LocalDensity.current.fontScale >= 1.25f
    @Composable
    fun DownloadModelButton(modifier: Modifier = Modifier) {
        Button(
            modifier = modifier,
            onClick = onDownloadModel,
            enabled = !state.isBusy && !state.isDownloading,
        ) {
            SolinGlyph(
                kind = SolinGlyphKind.Download,
                modifier = Modifier.size(18.dp),
                tint = LocalContentColor.current,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "下载模型",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    fun ImportModelButton(modifier: Modifier = Modifier) {
        OutlinedButton(
            modifier = modifier,
            onClick = onPickModel,
            enabled = !state.isBusy,
        ) {
            SolinGlyph(
                kind = SolinGlyphKind.Add,
                modifier = Modifier.size(18.dp),
                tint = LocalContentColor.current,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "导入模型",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_startup_banner"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.26f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RuntimeStatusBadge(state)
                Text(
                    modifier = Modifier.weight(1f),
                    text = startupBannerTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = startupBannerDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "本地推荐：${state.selectedRecommendedModel.shortName} · " +
                    ModelCatalog.formatBytes(state.selectedRecommendedModel.byteSize),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (
                state.isPreparingDownload ||
                state.isDownloading ||
                state.downloadProgressPercent != null ||
                state.totalBytes > 0L
            ) {
                ProgressBlock(state)
            }
            FilledTonalButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quick_remote_config_button"),
                onClick = onOpenRemoteModelConfig,
                enabled = !state.isBusy,
            ) {
                SolinGlyph(
                    kind = SolinGlyphKind.Spark,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("配置远程模型")
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val stackModelActions = stackModelActionsForTextScale || maxWidth < 280.dp
                if (stackModelActions) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DownloadModelButton(modifier = Modifier.fillMaxWidth())
                        ImportModelButton(modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DownloadModelButton(modifier = Modifier.weight(1f))
                        ImportModelButton(modifier = Modifier.weight(1f))
                    }
                }
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenModelManager,
                enabled = !state.isBusy,
            ) {
                SolinGlyph(
                    kind = SolinGlyphKind.Spark,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current,
                )
                Text(" 模型管理")
            }
            if (state.isPreparingDownload || state.isDownloading) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCancelDownload,
                ) {
                    Text("取消下载")
                }
            }
        }
    }
}

