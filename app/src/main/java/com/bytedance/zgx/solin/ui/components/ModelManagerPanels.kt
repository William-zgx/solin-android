package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.HUGGING_FACE_TOKEN_SETTINGS_URL
import com.bytedance.zgx.solin.InstalledModelSummary
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.RecommendedModel
import com.bytedance.zgx.solin.SetupTier
import com.bytedance.zgx.solin.data.ModelVerificationStatus
import com.bytedance.zgx.solin.isUsable
import com.bytedance.zgx.solin.memory.SemanticMemoryRuntimeStatus
import com.bytedance.zgx.solin.ui.DeviceCheck
import com.bytedance.zgx.solin.ui.MODEL_DOWNLOAD_RATIONALE_TEXT
import com.bytedance.zgx.solin.ui.capabilityLabel
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors

@Composable
internal fun ModelInventoryPanel(
    state: ChatUiState,
    customModelUrl: String,
    onCustomModelUrlChanged: (String) -> Unit,
    onPickModel: () -> Unit,
    onDownloadCustomModel: (String) -> Unit,
    onRecommendedModelSelected: (String) -> Unit,
    onInstalledModelSelected: (String) -> Unit,
    onDeleteInstalledModel: (String) -> Unit,
    onDownloadRecommendedModel: (String) -> Unit,
    huggingFaceAccessTokenInput: String,
    onHuggingFaceAccessTokenInputChanged: (String) -> Unit,
    onSaveHuggingFaceAccessToken: () -> Unit,
    onClearHuggingFaceAccessToken: () -> Unit,
    onOpenModelPage: (String) -> Unit,
) {
    var pendingDeleteModel by remember { mutableStateOf<InstalledModelSummary?>(null) }
    val firstHuggingFaceModel = state.recommendedModels.firstOrNull { it.requiresHuggingFaceAccessToken() }
    pendingDeleteModel?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDeleteModel = null },
            title = { Text("删除本地模型") },
            text = {
                Text(
                    if (model.capability == ModelCapability.MemoryEmbedding) {
                        "将删除 ${model.displayName} 的模型文件，并清除该模型生成的语义向量缓存；不会删除长期记忆文本，已有记忆仍可用轻量索引检索。"
                    } else {
                        "将从设备删除 ${model.displayName} 的模型文件，并从模型列表移除。这个操作不可撤销。"
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteModel = null
                        onDeleteInstalledModel(model.id)
                    },
                    enabled = !state.isBusy,
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteModel = null }) {
                    Text("取消")
                }
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DeviceCheck(
            state = state,
            requiredBytes = state.pendingBasicDownloadBytes(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(
                text = "本地模型",
                subtitle = "已下载或已导入的模型会出现在这里。",
            )
            if (state.installedModels.isEmpty()) {
                EmptyPanelText("还没有本地模型。先下载推荐模型，或导入已有 .litertlm 文件。")
            } else {
                state.installedModels.forEach { model ->
                    ModelRow(
                        title = model.displayName,
                        subtitle = "${capabilityLabel(model.capability)} · ${model.fileName} · " +
                            "${ModelCatalog.formatBytes(model.fileBytes)} · ${model.verificationLabel()}",
                        selected = model.id == state.activeInstalledModelId,
                        enabled = !state.isBusy && model.capability == ModelCapability.Chat && model.isUsable,
                        onClick = { onInstalledModelSelected(model.id) },
                        onDelete = { pendingDeleteModel = model },
                        deleteEnabled = !state.isBusy && !state.isDownloading && !state.isGenerating,
                        deleteButtonTag = "delete_installed_model_${model.id}",
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(
                text = "推荐模型",
                subtitle = MODEL_DOWNLOAD_RATIONALE_TEXT,
            )
            if (firstHuggingFaceModel != null) {
                HuggingFaceAuthorizationPanel(
                    tokenConfigured = state.huggingFaceAccessTokenConfigured,
                    pendingAuthorization = state.pendingHuggingFaceAuthorizationModelId != null,
                    tokenInput = huggingFaceAccessTokenInput,
                    onTokenInputChanged = onHuggingFaceAccessTokenInputChanged,
                    onSaveToken = onSaveHuggingFaceAccessToken,
                    onClearToken = onClearHuggingFaceAccessToken,
                    onOpenModelAuthorization = {
                        onOpenModelPage(firstHuggingFaceModel.repositoryUrl)
                    },
                    onOpenTokenSettings = {
                        onOpenModelPage(HUGGING_FACE_TOKEN_SETTINGS_URL)
                    },
                )
            }
            ModelPathGuidance(
                selectedModel = state.selectedRecommendedModel,
            )
            state.basicSetupModels.forEach { model ->
                RecommendedModelCard(
                    model = model,
                    state = state,
                    onSelect = { onRecommendedModelSelected(model.id) },
                    onDownload = { onDownloadRecommendedModel(model.id) },
                )
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenModelPage(state.selectedRecommendedModel.repositoryUrl) },
                enabled = !state.isBusy,
            ) {
                Text("查看推荐模型来源")
            }
        }

        if (state.optionalModels.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(
                    text = "可选模型",
                    subtitle = "用于高质量对话或低资源实验动作规划，不参与首装默认下载。",
                )
                state.optionalModels.forEach { model ->
                    RecommendedModelCard(
                        model = model,
                        state = state,
                        onSelect = { onRecommendedModelSelected(model.id) },
                        onDownload = { onDownloadRecommendedModel(model.id) },
                    )
                }
            }
        }

        AddModelPanel(
            customModelUrl = customModelUrl,
            onCustomModelUrlChanged = onCustomModelUrlChanged,
            onPickModel = onPickModel,
            onDownloadCustomModel = onDownloadCustomModel,
            enabled = !state.isBusy,
        )
    }
}

@Composable
internal fun HuggingFaceAuthorizationPanel(
    tokenConfigured: Boolean,
    pendingAuthorization: Boolean,
    tokenInput: String,
    onTokenInputChanged: (String) -> Unit,
    onSaveToken: () -> Unit,
    onClearToken: () -> Unit,
    onOpenModelAuthorization: () -> Unit,
    onOpenTokenSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hugging_face_authorization_panel"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (pendingAuthorization) 0.9f else 0.58f),
        ),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapabilityMark(
                    icon = SolinGlyphKind.Shield,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Hugging Face 授权",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (tokenConfigured) {
                            "read token 已保存在本机加密区，下载 gated 模型时会临时使用。"
                        } else {
                            "先登录并接受模型许可，再保存 read token 才能下载原始记忆模型。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (tokenConfigured) "已保存" else "待授权",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (tokenConfigured) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onOpenModelAuthorization,
                ) {
                    Text("模型授权页", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onOpenTokenSettings,
                ) {
                    Text("Token 页面", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hugging_face_token_input"),
                value = tokenInput,
                onValueChange = onTokenInputChanged,
                enabled = !tokenConfigured,
                maxLines = 1,
                placeholder = { Text("粘贴 Hugging Face read token") },
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("hugging_face_token_save_button"),
                    onClick = onSaveToken,
                    enabled = !tokenConfigured && tokenInput.isNotBlank(),
                ) {
                    Text("保存授权", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("hugging_face_token_clear_button"),
                    onClick = onClearToken,
                    enabled = tokenConfigured,
                ) {
                    Text("清除", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun AddModelPanel(
    customModelUrl: String,
    onCustomModelUrlChanged: (String) -> Unit,
    onPickModel: () -> Unit,
    onDownloadCustomModel: (String) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(
            text = "添加模型",
            subtitle = "自定义链接必须是 HTTPS；HTTP 仅用于本地调试地址。本地文件必须是 .litertlm 模型。",
        )
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("custom_model_url_input"),
            value = customModelUrl,
            onValueChange = onCustomModelUrlChanged,
            enabled = enabled,
            maxLines = 2,
            placeholder = { Text("粘贴 .litertlm 模型下载链接") },
        )
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("custom_model_download_button"),
            onClick = { onDownloadCustomModel(customModelUrl) },
            enabled = enabled && customModelUrl.isNotBlank(),
        ) {
            SolinGlyph(
                kind = SolinGlyphKind.Download,
                tint = LocalContentColor.current,
            )
            Text(" 从链接下载")
        }
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("import_model_button"),
            onClick = onPickModel,
            enabled = enabled,
        ) {
            SolinGlyph(
                kind = SolinGlyphKind.Add,
                tint = LocalContentColor.current,
            )
            Text(" 导入本地文件")
        }
    }
}

@Composable
internal fun ModelPathGuidance(
    selectedModel: RecommendedModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("model_path_guidance"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        modelPathGuidanceRows(selectedModel).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    modifier = Modifier.weight(1f),
                    text = row.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal data class ModelPathGuidanceRow(
    val label: String,
    val body: String,
)

internal fun modelPathGuidanceRows(selectedModel: RecommendedModel): List<ModelPathGuidanceRow> =
    listOf(
        ModelPathGuidanceRow(
            label = "本地",
            body = "下载或导入 ${selectedModel.shortName}（约 ${ModelCatalog.formatBytes(selectedModel.byteSize)}）后可离线问答；下载中断、校验失败或加载失败都可以重新下载，空间不足时先释放存储。",
        ),
        ModelPathGuidanceRow(
            label = "远程",
            body = "不下载本地模型也能开始；配置 HTTP(S) 兼容接口后，切换到远程模型时会提醒一次，图片只在你主动附加、模型支持且逐次确认后发送。",
        ),
        ModelPathGuidanceRow(
            label = "轻量",
            body = "当前没有更小的官方推荐聊天模型；记忆/动作小模型不是聊天替代。可先用远程模型，或导入你信任的 .litertlm。",
        ),
    )

@Composable
internal fun RecommendedModelCard(
    model: RecommendedModel,
    state: ChatUiState,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
) {
    val installed = state.isModelInstalled(model.id)
    val isSelectedChat = model.capability == ModelCapability.Chat && model.id == state.selectedModelId
    val semanticColors = LocalSolinColors.current
    val accent = when (model.capability) {
        ModelCapability.Chat -> MaterialTheme.colorScheme.primary
        ModelCapability.MemoryEmbedding -> semanticColors.memory
        ModelCapability.MobileAction -> semanticColors.busy
    }
    val statusText = when {
        model.requiresHuggingFaceAccessToken() && !state.huggingFaceAccessTokenConfigured && !installed ->
            "需 HF 授权"

        model.capability == ModelCapability.MemoryEmbedding ->
            memoryModelStatusText(installed, state.semanticMemoryRuntimeStatus)

        installed && model.capability == ModelCapability.MobileAction -> "低资源实验已安装"
        installed -> "已安装"
        model.setupTier == SetupTier.BasicRecommended -> "基础包"
        model.setupTier == SetupTier.OptionalExperimental -> "实验可选"
        else -> "可选"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.86f),
        tonalElevation = 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelectedChat) {
                accent.copy(alpha = 0.9f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.68f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                CapabilityMark(
                    icon = capabilityIcon(model.capability),
                    tint = accent,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = model.shortName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${capabilityLabel(model.capability)} · ${ModelCatalog.formatBytes(model.byteSize)} · ${model.deviceHint}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (installed) {
                        accent
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val hasSelectButton = model.capability == ModelCapability.Chat
                if (model.capability == ModelCapability.Chat) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onSelect,
                        enabled = !state.isBusy && !isSelectedChat,
                    ) {
                        Text(
                            text = if (isSelectedChat) "当前" else "选择",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                FilledTonalButton(
                    modifier = if (hasSelectButton) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                    onClick = onDownload,
                    enabled = !state.isBusy && !state.isDownloading,
                ) {
                    SolinGlyph(
                        kind = SolinGlyphKind.Download,
                        tint = LocalContentColor.current,
                    )
                    Text(
                        text = when {
                            installed -> " 重新下载"
                            model.requiresHuggingFaceAccessToken() && !state.huggingFaceAccessTokenConfigured ->
                                " 授权下载"

                            else -> " 下载"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal fun RecommendedModel.requiresHuggingFaceAccessToken(): Boolean =
    requiresHuggingFaceAuthorization ||
        companionFiles.any { companion -> companion.requiresHuggingFaceAuthorization }

internal fun memoryModelStatusText(
    installed: Boolean,
    status: SemanticMemoryRuntimeStatus,
): String =
    when {
        !installed -> "未安装"
        status == SemanticMemoryRuntimeStatus.BuildingIndex -> "正在建立索引"
        status == SemanticMemoryRuntimeStatus.Active -> "语义记忆可用"
        status == SemanticMemoryRuntimeStatus.ProbeFailed ||
            status == SemanticMemoryRuntimeStatus.DegradedLexical -> "已回退轻量索引"

        else -> "已安装待探测"
    }

@Composable
internal fun ModelRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    deleteEnabled: Boolean = false,
    deleteButtonTag: String = "",
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                },
                shape = shape,
            ),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f)
        },
        tonalElevation = 0.dp,
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Text(
                    text = "已选",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (onDelete != null) {
                IconButton(
                    modifier = Modifier.testTag(deleteButtonTag),
                    onClick = onDelete,
                    enabled = deleteEnabled,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    ),
                ) {
                    SolinGlyph(
                        kind = SolinGlyphKind.Delete,
                        tint = LocalContentColor.current,
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = "删除模型 $title" },
                    )
                }
            }
        }
    }
}

@Composable
internal fun CapabilityMark(
    icon: SolinGlyphKind,
    tint: Color,
) {
    Surface(
        shape = CircleShape,
        color = tint.copy(alpha = 0.16f),
    ) {
        SolinGlyph(
            modifier = Modifier
                .padding(7.dp)
                .size(18.dp),
            kind = icon,
            tint = tint,
        )
    }
}

internal fun capabilityIcon(capability: ModelCapability): SolinGlyphKind =
    when (capability) {
        ModelCapability.Chat -> SolinGlyphKind.Chat
        ModelCapability.MemoryEmbedding -> SolinGlyphKind.Memory
        ModelCapability.MobileAction -> SolinGlyphKind.Check
    }

internal fun InstalledModelSummary.verificationLabel(): String =
    when (verificationStatus) {
        ModelVerificationStatus.VerifiedRecommended -> "SHA-256 已校验"
        ModelVerificationStatus.UnverifiedCustom -> "自定义未校验"
        ModelVerificationStatus.LegacyUnverified -> "旧文件未校验"
        ModelVerificationStatus.FailedVerification -> "校验失败"
    }

internal fun ChatUiState.pendingBasicDownloadBytes(): Long =
    basicSetupModels
        .filter { it.id in setupSelectedModelIds && !isModelInstalled(it.id) }
        .sumOf { it.byteSize }

