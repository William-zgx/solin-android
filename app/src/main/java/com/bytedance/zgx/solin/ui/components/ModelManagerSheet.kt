package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.LocalModelTokenLimits
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.RemoteSendDisclosurePolicy
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.ui.MODEL_MANAGER_POSITIONING_TEXT
import com.bytedance.zgx.solin.ui.REMOTE_MODE_DISCLOSURE_TEXT
import com.bytedance.zgx.solin.ui.modelHealthDisplayText
import com.bytedance.zgx.solin.ui.ProgressBlock
import java.util.Locale

internal const val MODEL_MANAGER_CURRENT_TAB_INDEX = 0
internal const val MODEL_MANAGER_REMOTE_TAB_INDEX = 2
internal const val MODEL_MANAGER_PRIVACY_TAB_INDEX = 4

@Composable
internal fun ModelManagerSheet(
    state: ChatUiState,
    initialSelectedTab: Int,
    customModelUrl: String,
    onCustomModelUrlChanged: (String) -> Unit,
    onPickModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onDownloadRecommendedModel: (String) -> Unit,
    onDownloadCustomModel: (String) -> Unit,
    huggingFaceAccessTokenInput: String,
    onHuggingFaceAccessTokenInputChanged: (String) -> Unit,
    onSaveHuggingFaceAccessToken: () -> Unit,
    onClearHuggingFaceAccessToken: () -> Unit,
    onCancelDownload: () -> Unit,
    onLoadModel: () -> Unit,
    onRecommendedModelSelected: (String) -> Unit,
    onInstalledModelSelected: (String) -> Unit,
    onDeleteInstalledModel: (String) -> Unit,
    onInferenceModeSelected: (InferenceMode) -> Unit,
    onRemoteModelConfigChanged: (RemoteModelConfig) -> Unit,
    onTestRemoteModelConnectivity: () -> Unit,
    onBackendSelected: (BackendChoice) -> Unit,
    onGenerationParametersChanged: (GenerationParameters) -> Unit,
    onResetGenerationParameters: () -> Unit,
    onMemoryEnabledChanged: (Boolean) -> Unit,
    onForgetLongTermMemory: (String) -> Unit,
    onClearLongTermMemory: () -> Unit,
    onReduceDeviceActionConfirmationsChanged: (Boolean) -> Unit,
    onRemoteSendDisclosurePolicySelected: (RemoteSendDisclosurePolicy) -> Unit,
    onOpenModelPage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by rememberSaveable(initialSelectedTab) { mutableStateOf(initialSelectedTab) }
    val tabs = listOf("当前", "模型", "远程", "高级", "隐私")
    val sheetTitle = if (selectedTab == MODEL_MANAGER_PRIVACY_TAB_INDEX) "隐私说明" else "模型管理"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_manager_sheet")
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = sheetTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = MODEL_MANAGER_POSITIONING_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                modifier = Modifier
                    .testTag("model_manager_close_button")
                    .semantics { contentDescription = "关闭$sheetTitle" },
                onClick = onDismiss,
                enabled = !state.isBusy,
            ) {
                SolinGlyph(
                    kind = SolinGlyphKind.Close,
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        PrimaryTabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            tabs.forEachIndexed { index, label ->
                Tab(
                    modifier = Modifier.testTag("model_tab_${labelToTabTag(label)}"),
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(label, maxLines = 1) },
                )
            }
        }

        when (selectedTab) {
            0 -> CurrentModelPanel(
                state = state,
                onLoadModel = onLoadModel,
                onInferenceModeSelected = onInferenceModeSelected,
                onBackendSelected = onBackendSelected,
            )

            1 -> ModelInventoryPanel(
                state = state,
                customModelUrl = customModelUrl,
                onCustomModelUrlChanged = onCustomModelUrlChanged,
                onPickModel = onPickModel,
                onDownloadCustomModel = onDownloadCustomModel,
                onRecommendedModelSelected = onRecommendedModelSelected,
                onInstalledModelSelected = onInstalledModelSelected,
                onDeleteInstalledModel = onDeleteInstalledModel,
                onDownloadRecommendedModel = onDownloadRecommendedModel,
                huggingFaceAccessTokenInput = huggingFaceAccessTokenInput,
                onHuggingFaceAccessTokenInputChanged = onHuggingFaceAccessTokenInputChanged,
                onSaveHuggingFaceAccessToken = onSaveHuggingFaceAccessToken,
                onClearHuggingFaceAccessToken = onClearHuggingFaceAccessToken,
                onOpenModelPage = onOpenModelPage,
            )

            2 -> RemoteModelPanel(
                state = state,
                onInferenceModeSelected = onInferenceModeSelected,
                onRemoteModelConfigChanged = onRemoteModelConfigChanged,
                onTestRemoteModelConnectivity = onTestRemoteModelConnectivity,
            )

            3 -> AdvancedModelPanel(
                state = state,
                onGenerationParametersChanged = onGenerationParametersChanged,
                onResetGenerationParameters = onResetGenerationParameters,
                onMemoryEnabledChanged = onMemoryEnabledChanged,
                onForgetLongTermMemory = onForgetLongTermMemory,
                onClearLongTermMemory = onClearLongTermMemory,
            )

            else -> TrustBoundaryPanel(
                state = state,
                onRemoteSendDisclosurePolicySelected = onRemoteSendDisclosurePolicySelected,
                onReduceDeviceActionConfirmationsChanged = onReduceDeviceActionConfirmationsChanged,
            )
        }

        if (
            state.isPreparingDownload ||
            state.isDownloading ||
            state.downloadProgressPercent != null ||
            state.totalBytes > 0L
        ) {
            ProgressBlock(state)
            if (state.isPreparingDownload || state.isDownloading) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCancelDownload,
                ) {
                    Text("取消下载")
                }
            }
        }
    }
}

internal fun labelToTabTag(label: String): String =
    when (label) {
        "当前" -> "current"
        "模型" -> "models"
        "远程" -> "remote"
        "高级" -> "advanced"
        else -> "privacy"
    }

@Composable
internal fun CurrentModelPanel(
    state: ChatUiState,
    onLoadModel: () -> Unit,
    onInferenceModeSelected: (InferenceMode) -> Unit,
    onBackendSelected: (BackendChoice) -> Unit,
) {
    val activeModel = state.installedModels.firstOrNull { it.id == state.activeInstalledModelId }
    val usingRemote = state.inferenceMode == InferenceMode.Remote
    PanelSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(
                text = "当前模型",
                subtitle = state.statusText,
            )
            Text(
                text = if (usingRemote) {
                    state.remoteModelConfig.modelName.ifBlank { "未配置远程模型" }
                } else {
                    activeModel?.displayName ?: "未选择"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                modifier = Modifier.testTag("model_health_summary"),
                text = modelHealthDisplayText(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackendChip(
                    modifier = Modifier.testTag("inference_local_chip"),
                    label = InferenceMode.Local.label(),
                    selected = state.inferenceMode == InferenceMode.Local,
                    enabled = !state.isBusy,
                    onClick = { onInferenceModeSelected(InferenceMode.Local) },
                )
                BackendChip(
                    modifier = Modifier.testTag("inference_remote_chip"),
                    label = InferenceMode.Remote.label(),
                    selected = usingRemote,
                    enabled = !state.isBusy,
                    onClick = { onInferenceModeSelected(InferenceMode.Remote) },
                )
            }
            if (!usingRemote) {
                LocalTokenLimitBlock(state.localMaxTotalTokens)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendChip(
                    modifier = Modifier.testTag("backend_gpu_chip"),
                    label = "GPU",
                    selected = state.backend == BackendChoice.GPU,
                    enabled = !state.isBusy && state.backendChoiceEnabled(BackendChoice.GPU),
                    onClick = { onBackendSelected(BackendChoice.GPU) },
                )
                BackendChip(
                    modifier = Modifier.testTag("backend_cpu_chip"),
                    label = "CPU",
                    selected = state.backend == BackendChoice.CPU,
                    enabled = !state.isBusy && state.backendChoiceEnabled(BackendChoice.CPU),
                    onClick = { onBackendSelected(BackendChoice.CPU) },
                )
                }
                Text(
                    text = "GPU 通常更快，适合设备驱动和内存条件较好的手机；CPU 更稳但更慢。GPU 初始化失败时会自动切到 CPU。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!usingRemote && state.modelPath != null && !state.isReady && !state.isBusy) {
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onLoadModel,
                ) {
                    SolinGlyph(
                        kind = SolinGlyphKind.Check,
                        tint = LocalContentColor.current,
                    )
                    Text(" 加载模型")
                }
            }
        }
    }
}

@Composable
internal fun RemoteModelPanel(
    state: ChatUiState,
    onInferenceModeSelected: (InferenceMode) -> Unit,
    onRemoteModelConfigChanged: (RemoteModelConfig) -> Unit,
    onTestRemoteModelConnectivity: () -> Unit,
) {
    val config = state.remoteModelConfig
    PanelSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(
                    modifier = Modifier.weight(1f),
                    text = "远程模型",
                    subtitle = REMOTE_MODE_DISCLOSURE_TEXT,
                )
                val remoteSwitchEnabled = !state.isBusy &&
                    (config.isConfigured || state.inferenceMode == InferenceMode.Remote)
                Switch(
                    modifier = Modifier.testTag("remote_mode_switch"),
                    checked = state.inferenceMode == InferenceMode.Remote,
                    enabled = remoteSwitchEnabled,
                    onCheckedChange = {
                        onInferenceModeSelected(
                            if (it) InferenceMode.Remote else InferenceMode.Local,
                        )
                    },
                )
            }
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("remote_base_url_input"),
                value = config.baseUrl,
                onValueChange = {
                    onRemoteModelConfigChanged(config.copy(baseUrl = it))
                },
                enabled = !state.isBusy,
                singleLine = true,
                placeholder = { Text("https://api.example.com/v1") },
                label = { Text("服务地址") },
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("remote_model_name_input"),
                value = config.modelName,
                onValueChange = {
                    onRemoteModelConfigChanged(config.copy(modelName = it))
                },
                enabled = !state.isBusy,
                singleLine = true,
                placeholder = { Text("model-name") },
                label = { Text("模型名") },
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("remote_api_key_input"),
                value = config.apiKey,
                onValueChange = {
                    onRemoteModelConfigChanged(config.copy(apiKey = it))
                },
                enabled = !state.isBusy,
                singleLine = true,
                placeholder = { Text("可留空") },
                label = { Text("API Key") },
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("remote_vision_input_row"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "允许远程图片输入",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Switch(
                    checked = config.supportsVisionInput,
                    enabled = !state.isBusy,
                    onCheckedChange = {
                        onRemoteModelConfigChanged(config.copy(supportsVisionInput = it))
                    },
                )
            }
            Text(
                text = remoteConfigStatusText(config),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("remote_connectivity_test_button"),
                onClick = onTestRemoteModelConnectivity,
                enabled = !state.isBusy &&
                    config.isConfigured &&
                    config.connectivityStatus != RemoteModelConnectivityStatus.Checking,
            ) {
                SolinGlyph(
                    kind = SolinGlyphKind.Check,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current,
                )
                Text(
                    if (config.connectivityStatus == RemoteModelConnectivityStatus.Checking) {
                        " 测试中"
                    } else {
                        " 测试连接"
                    },
                )
            }
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("remote_config_clear_button"),
                onClick = { onRemoteModelConfigChanged(RemoteModelConfig()) },
                enabled = !state.isBusy && config.hasAnySavedValue(),
            ) {
                SolinGlyph(
                    kind = SolinGlyphKind.Delete,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current,
                )
                Text(" 清除远程配置")
            }
        }
    }
}

internal fun RemoteModelConfig.hasAnySavedValue(): Boolean =
    baseUrl.isNotBlank() ||
        modelName.isNotBlank() ||
        apiKey.isNotBlank() ||
        supportsVisionInput ||
        connectivityStatus != RemoteModelConnectivityStatus.Unknown

internal fun remoteConfigStatusText(config: RemoteModelConfig): String =
    when {
        config.isConfigured && config.usesLocalInsecureTransport ->
            "远程配置已保存；HTTP 仅允许本机调试地址，API Key 会加密保存在本机；连接状态：${config.connectivityStatus.label}。"

        config.isConfigured ->
            "远程配置已保存；API Key 会加密保存在本机；连接状态：${config.connectivityStatus.label}。"

        config.baseUrl.startsWith("http://") ->
            "非本机 HTTP 地址不可用；请使用 HTTPS 或本机调试地址；连接状态：${config.connectivityStatus.label}。"

        else ->
            "填写 HTTP(S) 服务地址和模型名后可切换远程模型；连接状态：${config.connectivityStatus.label}。"
    }

@Composable
internal fun AdvancedModelPanel(
    state: ChatUiState,
    onGenerationParametersChanged: (GenerationParameters) -> Unit,
    onResetGenerationParameters: () -> Unit,
    onMemoryEnabledChanged: (Boolean) -> Unit,
    onForgetLongTermMemory: (String) -> Unit,
    onClearLongTermMemory: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface {
            GenerationParametersPanel(
                parameters = state.generationParameters,
                enabled = !state.isBusy,
                onParametersChanged = onGenerationParametersChanged,
                onReset = onResetGenerationParameters,
            )
        }
        MemoryTogglePanel(
            state = state,
            enabled = !state.isBusy,
            onMemoryEnabledChanged = onMemoryEnabledChanged,
            onForgetLongTermMemory = onForgetLongTermMemory,
            onClearLongTermMemory = onClearLongTermMemory,
        )
    }
}

internal fun ChatUiState.backendChoiceEnabled(choice: BackendChoice): Boolean =
    localPreferredBackends.isEmpty() || choice in localPreferredBackends

@Composable
internal fun LocalTokenLimitBlock(maxTotalTokens: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = LocalModelTokenLimits.totalDisplayText(maxTotalTokens),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = LocalModelTokenLimits.inputDisplayText() +
                " · ${LocalModelTokenLimits.outputDisplayText()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun BackendChip(
    modifier: Modifier = Modifier,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        modifier = modifier,
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
internal fun GenerationParametersPanel(
    parameters: GenerationParameters,
    enabled: Boolean,
    onParametersChanged: (GenerationParameters) -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(
            text = "生成参数",
            subtitle = "修改后立即应用到当前会话，不会重新下载模型。",
        )
        ParameterSlider(
            title = "Temperature · 创造性",
            value = parameters.temperature,
            valueText = { String.format(Locale.US, "%.1f", it) },
            valueRange = GenerationParameters.MIN_TEMPERATURE..GenerationParameters.MAX_TEMPERATURE,
            steps = 11,
            enabled = enabled,
            helper = "低：更稳定、更像按资料回答；高：更发散、更容易有新表达。",
            onValueCommitted = { value ->
                onParametersChanged(parameters.copy(temperature = value))
            },
        )
        ParameterSlider(
            title = "Top P · 候选范围",
            value = parameters.topP,
            valueText = { String.format(Locale.US, "%.2f", it) },
            valueRange = GenerationParameters.MIN_TOP_P..GenerationParameters.MAX_TOP_P,
            steps = 17,
            enabled = enabled,
            helper = "低：只从最可靠的一小批词里选；高：保留更多可能性。",
            onValueCommitted = { value ->
                onParametersChanged(parameters.copy(topP = value))
            },
        )
        ParameterSlider(
            title = "Top K · 候选数量",
            value = parameters.topK.toFloat(),
            valueText = { it.toInt().toString() },
            valueRange = GenerationParameters.MIN_TOP_K.toFloat()..GenerationParameters.MAX_TOP_K.toFloat(),
            steps = 0,
            enabled = enabled,
            helper = "低：输出更集中；高：每一步可选词更多，回答更可能多样。",
            onValueCommitted = { value ->
                onParametersChanged(parameters.copy(topK = value.toInt().coerceIn(1, 100)))
            },
        )
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onReset,
            enabled = enabled && parameters != GenerationParameters(),
        ) {
            Text("恢复默认参数")
        }
    }
}

@Composable
internal fun ParameterSlider(
    title: String,
    value: Float,
    valueText: (Float) -> String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    helper: String,
    onValueCommitted: (Float) -> Unit,
) {
    var draftValue by remember(value) { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = valueText(draftValue),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = valueText(draftValue)
            },
            value = draftValue,
            onValueChange = { draftValue = it },
            onValueChangeFinished = { onValueCommitted(draftValue) },
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
        )
        Text(
            text = helper,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

