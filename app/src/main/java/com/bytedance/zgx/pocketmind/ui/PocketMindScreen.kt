package com.bytedance.zgx.pocketmind.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.ChatUiState
import com.bytedance.zgx.pocketmind.ModelCatalog
import com.bytedance.zgx.pocketmind.GenerationParameters
import com.bytedance.zgx.pocketmind.GenerationStats
import com.bytedance.zgx.pocketmind.InferenceMode
import com.bytedance.zgx.pocketmind.MessageRole
import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.RecommendedModel
import com.bytedance.zgx.pocketmind.RemoteModelConfig
import com.bytedance.zgx.pocketmind.SetupTier
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.isUsable
import com.bytedance.zgx.pocketmind.label
import java.util.Locale

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PocketMindScreen(
    state: ChatUiState,
    onImportModel: (Uri) -> Unit,
    onDownloadModel: () -> Unit,
    onDownloadRecommendedModel: (String) -> Unit,
    onDownloadCustomModel: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onLoadModel: () -> Unit,
    onRecommendedModelSelected: (String) -> Unit,
    onInstalledModelSelected: (String) -> Unit,
    onInferenceModeSelected: (InferenceMode) -> Unit,
    onRemoteModelConfigChanged: (RemoteModelConfig) -> Unit,
    onBackendSelected: (BackendChoice) -> Unit,
    onGenerationParametersChanged: (GenerationParameters) -> Unit,
    onResetGenerationParameters: () -> Unit,
    onCreateSession: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: () -> Unit,
    onOpenModelPage: () -> Unit,
    onSetupModelToggled: (String, Boolean) -> Unit,
    onDownloadSetupModels: () -> Unit,
    onSkipFirstRunSetup: () -> Unit,
    onMemoryEnabledChanged: (Boolean) -> Unit,
    onConfirmActionDraft: (ActionDraft) -> Unit,
    onDismissActionDraft: () -> Unit,
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
) {
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportModel)
    }
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    var customModelUrl by rememberSaveable { mutableStateOf("") }
    var showModelManager by rememberSaveable { mutableStateOf(false) }
    var showSessions by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lastMessageId = state.messages.lastOrNull()?.id

    LaunchedEffect(state.activeSessionId, lastMessageId) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            ChatTopBar(
                state = state,
                onOpenModelManager = { showModelManager = true },
                onOpenSessions = { showSessions = true },
                onCreateSession = onCreateSession,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (state.messages.isEmpty()) {
                    ChatEmptyState(
                        state = state,
                        onOpenModelManager = { showModelManager = true },
                        onPickModel = { pickModel.launch(arrayOf("*/*")) },
                        onDownloadModel = onDownloadModel,
                        onCancelDownload = onCancelDownload,
                        onRecommendedModelSelected = onRecommendedModelSelected,
                        onSendPrompt = onSendMessage,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.memoryHits.isNotEmpty()) {
                            item(key = "memory_context") {
                                MemoryContextStrip(state.memoryHits.size)
                            }
                        }
                        items(
                            items = state.messages,
                            key = { it.id },
                        ) { message ->
                            MessageBubble(
                                message = message,
                                isStreaming = state.isGenerating &&
                                    message.role == MessageRole.Assistant &&
                                    message.id == state.messages.lastOrNull()?.id,
                            )
                        }
                    }
                }
            }

            Composer(
                state = state,
                input = input,
                onInputChanged = { input = it },
                onOpenModelManager = { showModelManager = true },
                onSend = {
                    val message = input
                    input = ""
                    onSendMessage(message)
                },
                onStopGeneration = onStopGeneration,
            )
        }

        if (showModelManager) {
            ModalBottomSheet(
                sheetState = sheetState,
                onDismissRequest = { showModelManager = false },
            ) {
                ModelManagerSheet(
                    state = state,
                    customModelUrl = customModelUrl,
                    onCustomModelUrlChanged = { customModelUrl = it },
                    onPickModel = { pickModel.launch(arrayOf("*/*")) },
                    onDownloadModel = onDownloadModel,
                    onDownloadRecommendedModel = onDownloadRecommendedModel,
                    onDownloadCustomModel = onDownloadCustomModel,
                    onCancelDownload = onCancelDownload,
                    onLoadModel = onLoadModel,
                    onRecommendedModelSelected = onRecommendedModelSelected,
                    onInstalledModelSelected = onInstalledModelSelected,
                    onInferenceModeSelected = onInferenceModeSelected,
                    onRemoteModelConfigChanged = onRemoteModelConfigChanged,
                    onBackendSelected = onBackendSelected,
                    onGenerationParametersChanged = onGenerationParametersChanged,
                    onResetGenerationParameters = onResetGenerationParameters,
                    onMemoryEnabledChanged = onMemoryEnabledChanged,
                    onOpenModelPage = onOpenModelPage,
                )
            }
        }

        if (showSessions) {
            ModalBottomSheet(
                sheetState = sheetState,
                onDismissRequest = { showSessions = false },
            ) {
                SessionManagerSheet(
                    state = state,
                    onCreateSession = {
                        onCreateSession()
                        showSessions = false
                    },
                    onSessionSelected = {
                        onSessionSelected(it)
                        showSessions = false
                    },
                    onDeleteSession = onDeleteSession,
                )
            }
        }

        if (state.showFirstRunSetup) {
            ModalBottomSheet(
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                onDismissRequest = onSkipFirstRunSetup,
            ) {
                FirstRunSetupSheet(
                    state = state,
                    onSetupModelToggled = onSetupModelToggled,
                    onDownloadSetupModels = onDownloadSetupModels,
                    onSkip = onSkipFirstRunSetup,
                )
            }
        }

        state.pendingActionDraft?.let { draft ->
            ModalBottomSheet(
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                onDismissRequest = onDismissActionDraft,
            ) {
                ActionDraftSheet(
                    draft = draft,
                    onConfirm = { onConfirmActionDraft(draft) },
                    onDismiss = onDismissActionDraft,
                )
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    state: ChatUiState,
    onOpenModelManager: () -> Unit,
    onOpenSessions: () -> Unit,
    onCreateSession: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        modifier = Modifier.testTag("app_title"),
                        text = "PocketMind",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = currentModelStatus(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    modifier = Modifier.testTag("top_create_session_button"),
                    onClick = onCreateSession,
                    enabled = !state.isBusy,
                ) {
                    Text("新建")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RuntimeStatusBadge(state)
                TextButton(
                    modifier = Modifier.testTag("top_model_button"),
                    onClick = onOpenModelManager,
                ) {
                    Text("模型")
                }
                TextButton(
                    modifier = Modifier.testTag("top_session_button"),
                    onClick = onOpenSessions,
                ) {
                    Text("会话")
                }
            }
        }
    }
}

@Composable
private fun RuntimeStatusBadge(state: ChatUiState) {
    val active = state.isReady && !state.isBusy
    val label = when {
        state.isDownloading -> state.downloadProgressPercent?.let { "下载 $it%" } ?: "下载中"
        state.isBusy -> "处理中"
        state.inferenceMode == InferenceMode.Remote && state.isReady -> "远程可用"
        state.isReady -> "离线可用"
        state.inferenceMode == InferenceMode.Remote -> "待配置"
        state.modelPath != null -> "可加载"
        else -> "待准备"
    }
    val container = when {
        active -> MaterialTheme.colorScheme.primaryContainer
        state.isBusy || state.isDownloading -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        state.isBusy || state.isDownloading -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = CircleShape,
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(content),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = content,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChatEmptyState(
    state: ChatUiState,
    onOpenModelManager: () -> Unit,
    onPickModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onRecommendedModelSelected: (String) -> Unit,
    onSendPrompt: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = if (state.isReady) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surface
            },
            shadowElevation = if (state.isReady) 0.dp else 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RuntimeStatusBadge(state)
                Text(
                    text = if (state.isReady) "可以开始本地问答" else "先把本地模型准备好",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (state.isReady) {
                        "当前会话为空，选择一个开场问题，或在底部直接输入。"
                    } else {
                        "模型下载、导入、切换和后端选择都集中在模型管理里；加载成功后，问答和历史记录都保留在本机。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.isReady) {
                    PromptSuggestionList(
                        enabled = !state.isBusy,
                        onSendPrompt = onSendPrompt,
                    )
                } else {
                    QuickModelSetup(
                        state = state,
                        onOpenModelManager = onOpenModelManager,
                        onPickModel = onPickModel,
                        onDownloadModel = onDownloadModel,
                        onCancelDownload = onCancelDownload,
                        onRecommendedModelSelected = onRecommendedModelSelected,
                    )
                }
            }
        }

        if (!state.isReady) {
            DeviceCheck(
                state = state,
                requiredBytes = state.pendingSelectedChatDownloadBytes(),
            )
        }
    }
}

@Composable
private fun QuickModelSetup(
    state: ChatUiState,
    onOpenModelManager: () -> Unit,
    onPickModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onRecommendedModelSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.recommendedModels.forEach { model ->
                FilterChip(
                    selected = model.id == state.selectedModelId,
                    enabled = !state.isBusy,
                    onClick = { onRecommendedModelSelected(model.id) },
                    label = { Text(model.shortName) },
                )
            }
        }
        Text(
            text = "${state.selectedRecommendedModel.shortName} · " +
                "${ModelCatalog.formatBytes(state.selectedRecommendedModel.byteSize)} · " +
                state.selectedRecommendedModel.deviceHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.isDownloading || state.downloadProgressPercent != null || state.totalBytes > 0L) {
            ProgressBlock(state)
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onDownloadModel,
            enabled = !state.isBusy && !state.isDownloading,
        ) {
            Text("下载 ${state.selectedRecommendedModel.shortName}")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onPickModel,
                enabled = !state.isBusy,
            ) {
                Text("导入")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onOpenModelManager,
                enabled = !state.isBusy,
            ) {
                Text("更多")
            }
        }
        if (state.isDownloading) {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancelDownload,
            ) {
                Text("取消下载")
            }
        }
    }
}

@Composable
private fun FirstRunSetupSheet(
    state: ChatUiState,
    onSetupModelToggled: (String, Boolean) -> Unit,
    onDownloadSetupModels: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitle(
            text = "准备基础能力包",
            subtitle = "默认安装对话、记忆和动作模型；也可以取消任意项，之后再补装。",
        )
        state.basicSetupModels.forEach { model ->
            val selected = model.id in state.setupSelectedModelIds
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (selected) 0.72f else 0.34f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
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
        }
        DeviceCheck(
            state = state,
            requiredBytes = state.pendingSetupDownloadBytes(),
        )
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("first_run_download_button"),
            onClick = onDownloadSetupModels,
            enabled = !state.isBusy && state.setupSelectedModelIds.isNotEmpty(),
        ) {
            Text("下载选中的基础包")
        }
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onSkip,
            enabled = !state.isBusy,
        ) {
            Text("先跳过")
        }
    }
}

@Composable
private fun ActionDraftSheet(
    draft: ActionDraft,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitle(
            text = draft.title,
            subtitle = "动作只会在你确认后打开系统页面或草稿页。",
        )
        Text(
            text = draft.summary,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (draft.parameters.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                draft.parameters.forEach { (key, value) ->
                    Text(
                        text = "$key: $value",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onConfirm,
        ) {
            Text("确认并打开")
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onDismiss,
        ) {
            Text("取消")
        }
    }
}

@Composable
private fun PromptSuggestionList(
    enabled: Boolean,
    onSendPrompt: (String) -> Unit,
) {
    val prompts = listOf(
        "用三句话解释端侧大模型",
        "帮我整理一个今日待办清单",
        "写一段简洁的中文自我介绍",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        prompts.forEach { prompt ->
            AssistChip(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                onClick = { onSendPrompt(prompt) },
                label = {
                    Text(
                        text = prompt,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun ModelManagerSheet(
    state: ChatUiState,
    customModelUrl: String,
    onCustomModelUrlChanged: (String) -> Unit,
    onPickModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onDownloadRecommendedModel: (String) -> Unit,
    onDownloadCustomModel: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onLoadModel: () -> Unit,
    onRecommendedModelSelected: (String) -> Unit,
    onInstalledModelSelected: (String) -> Unit,
    onInferenceModeSelected: (InferenceMode) -> Unit,
    onRemoteModelConfigChanged: (RemoteModelConfig) -> Unit,
    onBackendSelected: (BackendChoice) -> Unit,
    onGenerationParametersChanged: (GenerationParameters) -> Unit,
    onResetGenerationParameters: () -> Unit,
    onMemoryEnabledChanged: (Boolean) -> Unit,
    onOpenModelPage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_manager_sheet")
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "模型管理",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            RuntimeStatusBadge(state)
        }

        CurrentModelPanel(
            state = state,
            onLoadModel = onLoadModel,
            onInferenceModeSelected = onInferenceModeSelected,
            onBackendSelected = onBackendSelected,
            onGenerationParametersChanged = onGenerationParametersChanged,
            onResetGenerationParameters = onResetGenerationParameters,
            onMemoryEnabledChanged = onMemoryEnabledChanged,
        )

        RemoteModelPanel(
            state = state,
            onInferenceModeSelected = onInferenceModeSelected,
            onRemoteModelConfigChanged = onRemoteModelConfigChanged,
        )

        if (state.isDownloading || state.downloadProgressPercent != null || state.totalBytes > 0L) {
            ProgressBlock(state)
            if (state.isDownloading) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCancelDownload,
                ) {
                    Text("取消下载")
                }
            }
        }

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
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Text(
                        modifier = Modifier.padding(14.dp),
                        text = "还没有本地模型。先下载推荐模型，或导入已有 .litertlm 文件。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                state.installedModels.forEach { model ->
                    ModelRow(
                        title = model.displayName,
                        subtitle = "${capabilityLabel(model.capability)} · ${model.fileName} · ${ModelCatalog.formatBytes(model.fileBytes)}",
                        selected = model.id == state.activeInstalledModelId,
                        enabled = !state.isBusy && model.capability == ModelCapability.Chat,
                        onClick = { onInstalledModelSelected(model.id) },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(
                text = "推荐模型",
                subtitle = "缺少或文件不完整时，可在这里单独补装对应能力。",
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
                onClick = onOpenModelPage,
                enabled = !state.isBusy,
            ) {
                Text("查看推荐模型来源")
            }
        }

        if (state.optionalChatModels.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(
                    text = "可选对话模型",
                    subtitle = "用于高质量或备用对话，不参与首装默认下载。",
                )
                state.optionalChatModels.forEach { model ->
                    RecommendedModelCard(
                        model = model,
                        state = state,
                        onSelect = { onRecommendedModelSelected(model.id) },
                        onDownload = { onDownloadRecommendedModel(model.id) },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(
                text = "添加模型",
                subtitle = "自定义链接或本地文件都必须是 .litertlm 模型。",
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_model_url_input"),
                value = customModelUrl,
                onValueChange = onCustomModelUrlChanged,
                enabled = !state.isBusy,
                maxLines = 2,
                placeholder = { Text("粘贴 .litertlm 模型下载链接") },
            )
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_model_download_button"),
                onClick = { onDownloadCustomModel(customModelUrl) },
                enabled = !state.isBusy && customModelUrl.isNotBlank(),
            ) {
                Text("从链接下载")
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onPickModel,
                enabled = !state.isBusy,
            ) {
                Text("导入本地文件")
            }
        }
    }
}

@Composable
private fun CurrentModelPanel(
    state: ChatUiState,
    onLoadModel: () -> Unit,
    onInferenceModeSelected: (InferenceMode) -> Unit,
    onBackendSelected: (BackendChoice) -> Unit,
    onGenerationParametersChanged: (GenerationParameters) -> Unit,
    onResetGenerationParameters: () -> Unit,
    onMemoryEnabledChanged: (Boolean) -> Unit,
) {
    val activeModel = state.installedModels.firstOrNull { it.id == state.activeInstalledModelId }
    val usingRemote = state.inferenceMode == InferenceMode.Remote
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackendChip(
                    label = InferenceMode.Local.label(),
                    selected = state.inferenceMode == InferenceMode.Local,
                    enabled = !state.isBusy,
                    onClick = { onInferenceModeSelected(InferenceMode.Local) },
                )
                BackendChip(
                    label = InferenceMode.Remote.label(),
                    selected = usingRemote,
                    enabled = !state.isBusy,
                    onClick = { onInferenceModeSelected(InferenceMode.Remote) },
                )
            }
            if (!usingRemote) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendChip(
                        label = "GPU",
                        selected = state.backend == BackendChoice.GPU,
                        enabled = !state.isBusy,
                        onClick = { onBackendSelected(BackendChoice.GPU) },
                    )
                    BackendChip(
                        label = "CPU",
                        selected = state.backend == BackendChoice.CPU,
                        enabled = !state.isBusy,
                        onClick = { onBackendSelected(BackendChoice.CPU) },
                    )
                }
                Text(
                    text = "GPU 通常更快，适合设备驱动和内存条件较好的手机；CPU 更稳但更慢。GPU 初始化失败时会自动切到 CPU。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GenerationParametersPanel(
                parameters = state.generationParameters,
                enabled = !state.isBusy,
                onParametersChanged = onGenerationParametersChanged,
                onReset = onResetGenerationParameters,
            )
            MemoryTogglePanel(
                state = state,
                enabled = !state.isBusy,
                onMemoryEnabledChanged = onMemoryEnabledChanged,
            )
            if (!usingRemote && state.modelPath != null && !state.isReady && !state.isBusy) {
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onLoadModel,
                ) {
                    Text("加载模型")
                }
            }
        }
    }
}

@Composable
private fun RemoteModelPanel(
    state: ChatUiState,
    onInferenceModeSelected: (InferenceMode) -> Unit,
    onRemoteModelConfigChanged: (RemoteModelConfig) -> Unit,
) {
    val config = state.remoteModelConfig
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(
                    text = "远程模型",
                    subtitle = "兼容 /v1/chat/completions 的服务地址。",
                )
                Switch(
                    checked = state.inferenceMode == InferenceMode.Remote,
                    enabled = !state.isBusy,
                    onCheckedChange = {
                        onInferenceModeSelected(
                            if (it) InferenceMode.Remote else InferenceMode.Local,
                        )
                    },
                )
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
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
            Text(
                text = if (config.isConfigured) {
                    "远程配置已保存"
                } else {
                    "填写服务地址和模型名后可切换远程模型"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MemoryTogglePanel(
    state: ChatUiState,
    enabled: Boolean,
    onMemoryEnabledChanged: (Boolean) -> Unit,
) {
    val memoryModelInstalled = ModelCapability.MemoryEmbedding in state.installedCapabilities
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "本地记忆",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (memoryModelInstalled) {
                        "启用后会从本机会话中召回相关片段。"
                    } else {
                        "安装本地记忆模型后可开启语义召回。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.memoryEnabled && memoryModelInstalled,
                onCheckedChange = onMemoryEnabledChanged,
                enabled = enabled && memoryModelInstalled,
            )
        }
    }
}

@Composable
private fun SessionManagerSheet(
    state: ChatUiState,
    onCreateSession: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.testTag("session_manager_title"),
                text = "会话",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(
                modifier = Modifier.testTag("session_create_button"),
                onClick = onCreateSession,
                enabled = !state.isBusy,
            ) {
                Text("新建")
            }
        }
        state.sessions.forEach { session ->
            ModelRow(
                title = session.title,
                subtitle = if (session.messageCount == 0) "空会话" else "${session.messageCount} 条消息",
                selected = session.id == state.activeSessionId,
                enabled = !state.isBusy,
                onClick = { onSessionSelected(session.id) },
            )
        }
        if (state.sessions.size > 1) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onDeleteSession,
                enabled = !state.isBusy,
            ) {
                Text("删除当前会话")
            }
        }
    }
}

@Composable
private fun RecommendedModelCard(
    model: RecommendedModel,
    state: ChatUiState,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
) {
    val installed = state.isModelInstalled(model.id)
    val isSelectedChat = model.capability == ModelCapability.Chat && model.id == state.selectedModelId
    val statusText = when {
        installed -> "已安装"
        model.setupTier == SetupTier.BasicRecommended -> "基础包"
        else -> "可选"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (installed) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.46f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (installed) 1.dp else 0.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (isSelectedChat) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.54f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
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
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (model.capability == ModelCapability.Chat) {
                    OutlinedButton(
                        onClick = onSelect,
                        enabled = !state.isBusy && !isSelectedChat,
                    ) {
                        Text(if (isSelectedChat) "当前" else "选择")
                    }
                }
                FilledTonalButton(
                    onClick = onDownload,
                    enabled = !state.isBusy && !state.isDownloading,
                ) {
                    Text(if (installed) "重新下载" else "下载")
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                },
                shape = shape,
            ),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (selected) 2.dp else 0.dp,
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
        }
    }
}

@Composable
private fun SectionTitle(
    text: String,
    subtitle: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun currentModelStatus(state: ChatUiState): String {
    if (state.inferenceMode == InferenceMode.Remote) {
        val modelName = state.remoteModelConfig.modelName.ifBlank { "远程模型" }
        val ready = when {
            state.isBusy -> state.statusText
            state.isReady -> "已就绪"
            else -> state.statusText
        }
        return "$modelName · 远程 · $ready"
    }
    val modelName = state.installedModels.firstOrNull { it.id == state.activeInstalledModelId }?.displayName
        ?: state.selectedRecommendedModel.shortName
    val ready = when {
        state.isDownloading -> state.downloadProgressPercent?.let { "下载 $it%" } ?: "下载中"
        state.isBusy -> state.statusText
        state.isReady -> "已就绪"
        state.modelPath != null -> "待加载"
        else -> state.statusText
    }
    return "$modelName · ${state.backend.name} · $ready"
}

private fun capabilityLabel(capability: ModelCapability): String =
    when (capability) {
        ModelCapability.Chat -> "对话"
        ModelCapability.MemoryEmbedding -> "记忆"
        ModelCapability.MobileAction -> "动作"
    }

@Composable
private fun ChatUiState.pendingSelectedChatDownloadBytes(): Long =
    if (isModelInstalled(selectedRecommendedModel.id)) {
        0L
    } else {
        selectedRecommendedModel.byteSize
    }

private fun ChatUiState.pendingSetupDownloadBytes(): Long =
    basicSetupModels
        .filter { it.id in setupSelectedModelIds && !isModelInstalled(it.id) }
        .sumOf { it.byteSize }

private fun ChatUiState.pendingBasicDownloadBytes(): Long =
    basicSetupModels
        .filterNot { isModelInstalled(it.id) }
        .sumOf { it.byteSize }

@Composable
private fun DeviceCheck(
    state: ChatUiState,
    requiredBytes: Long,
) {
    val hasPendingDownload = requiredBytes > 0L
    val hasEnoughSpace = !hasPendingDownload ||
        ModelCatalog.hasEnoughSpace(state.availableModelStorageBytes, requiredBytes)
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
                text = "建议至少预留 ${ModelCatalog.formatBytes(requiredBytes)}，再多留一些空间给加载缓存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DeviceMetric(
    modifier: Modifier,
    label: String,
    value: String,
    good: Boolean,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (good) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
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
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProgressBlock(state: ChatUiState) {
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
                text = progress?.let { "$it%" } ?: ModelCatalog.formatBytes(state.downloadedBytes),
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

@Composable
private fun BackendChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun GenerationParametersPanel(
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
private fun ParameterSlider(
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

@Composable
private fun MemoryContextStrip(hitCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.58f),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            text = "已引用本地记忆 $hitCount 条",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isStreaming: Boolean,
) {
    val isUser = message.role == MessageRole.User
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (isUser) {
        RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp)
    } else {
        RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.86f else 0.94f)
                .clip(shape)
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when {
                    isUser -> "你"
                    isStreaming -> "PocketMind · 生成中"
                    else -> "PocketMind"
                },
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.72f),
                fontWeight = FontWeight.SemiBold,
            )
            if (isStreaming && message.text.isBlank()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface,
                )
            }
            SelectionContainer {
                MessageContent(
                    text = message.text.ifBlank { if (isStreaming) "正在思考..." else "..." },
                    isUser = isUser,
                    color = textColor,
                )
            }
            if (!isUser && !isStreaming && message.generationStats?.isUsable() == true) {
                Text(
                    text = formatGenerationStats(message.generationStats),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.68f),
                )
            }
        }
    }
}

private fun formatGenerationStats(stats: GenerationStats): String =
    "${stats.tokenCount} tokens · ${String.format(Locale.US, "%.1f", stats.tokensPerSecond)} tokens/s"

@Composable
private fun MessageContent(
    text: String,
    isUser: Boolean,
    color: Color,
) {
    if (isUser) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        splitMessageSegments(text).forEach { segment ->
            if (segment.isCode) {
                CodeBlock(segment.text)
            } else if (segment.text.isNotBlank()) {
                Text(
                    text = segment.text.toMessageAnnotatedString(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    val cleaned = code.trim('\n')
    val lines = cleaned.lines()
    val maybeLanguage = lines.firstOrNull().orEmpty()
    val hasLanguage = maybeLanguage.length in 1..24 &&
        maybeLanguage.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '+' || it == '#' }
    val codeText = if (hasLanguage) lines.drop(1).joinToString("\n") else cleaned

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            ),
    ) {
        if (hasLanguage) {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                text = maybeLanguage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider()
        }
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
            text = codeText.ifBlank { "..." },
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start,
        )
    }
}

private fun String.toMessageAnnotatedString(): AnnotatedString =
    buildAnnotatedString {
        lines().forEachIndexed { index, rawLine ->
            if (index > 0) append('\n')
            appendMarkdownLine(rawLine)
        }
    }

private fun AnnotatedString.Builder.appendMarkdownLine(rawLine: String) {
    val trimmedStart = rawLine.trimStart()
    val normalized = when {
        trimmedStart.startsWith("### ") -> trimmedStart.removePrefix("### ").trim()
        trimmedStart.startsWith("## ") -> trimmedStart.removePrefix("## ").trim()
        trimmedStart.startsWith("# ") -> trimmedStart.removePrefix("# ").trim()
        trimmedStart.startsWith("- ") -> "• ${trimmedStart.removePrefix("- ").trim()}"
        trimmedStart.startsWith("* ") -> "• ${trimmedStart.removePrefix("* ").trim()}"
        else -> rawLine
    }
    val boldRegex = Regex("""\*\*(.+?)\*\*""")
    var cursor = 0
    boldRegex.findAll(normalized).forEach { match ->
        append(normalized.substring(cursor, match.range.first))
        pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
        append(match.groupValues[1])
        pop()
        cursor = match.range.last + 1
    }
    append(normalized.substring(cursor))
}

@Composable
private fun Composer(
    state: ChatUiState,
    input: String,
    onInputChanged: (String) -> Unit,
    onOpenModelManager: () -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
) {
    val inputEnabled = state.isReady && !state.isBusy
    val actionIsStop = state.isGenerating
    val placeholder = when {
        state.isBusy -> state.statusText
        !state.isReady -> "先准备模型，再开始提问"
        else -> "输入问题"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!state.isReady || state.isBusy) {
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
                value = input,
                onValueChange = onInputChanged,
                enabled = inputEnabled,
                minLines = 1,
                maxLines = 5,
                placeholder = { Text(placeholder) },
            )

            OutlinedButton(
                modifier = Modifier
                    .height(56.dp)
                    .width(64.dp),
                onClick = onOpenModelManager,
                enabled = !state.isBusy,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("模型")
            }

            Button(
                modifier = Modifier
                    .height(56.dp)
                    .width(56.dp),
                onClick = when {
                    actionIsStop -> onStopGeneration
                    else -> onSend
                },
                enabled = when {
                    actionIsStop -> true
                    else -> inputEnabled && input.isNotBlank()
                },
                contentPadding = PaddingValues(0.dp),
            ) {
                when {
                    actionIsStop -> Text("停止")
                    else -> Text("发送")
                }
            }
        }
    }
}
