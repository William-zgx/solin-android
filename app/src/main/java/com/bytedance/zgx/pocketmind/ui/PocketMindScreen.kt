package com.bytedance.zgx.pocketmind.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.AuditEventSummary
import com.bytedance.zgx.pocketmind.BackgroundTaskSummary
import com.bytedance.zgx.pocketmind.AgentTraceRunUiSummary
import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.ChatUiState
import com.bytedance.zgx.pocketmind.ModelCatalog
import com.bytedance.zgx.pocketmind.GenerationParameters
import com.bytedance.zgx.pocketmind.GenerationStats
import com.bytedance.zgx.pocketmind.InferenceMode
import com.bytedance.zgx.pocketmind.InstalledModelSummary
import com.bytedance.zgx.pocketmind.LongTermMemorySummary
import com.bytedance.zgx.pocketmind.MessageRole
import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.PendingAgentConfirmation
import com.bytedance.zgx.pocketmind.RecommendedModel
import com.bytedance.zgx.pocketmind.RemoteModelConfig
import com.bytedance.zgx.pocketmind.SetupTier
import com.bytedance.zgx.pocketmind.runtimePermissionRequirementsFor
import com.bytedance.zgx.pocketmind.background.PeriodicCheckConstraints
import com.bytedance.zgx.pocketmind.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.pocketmind.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.pocketmind.background.ScheduledTaskStatus
import com.bytedance.zgx.pocketmind.background.ScheduledTaskType
import com.bytedance.zgx.pocketmind.data.ModelVerificationStatus
import com.bytedance.zgx.pocketmind.isUsable
import com.bytedance.zgx.pocketmind.label
import com.bytedance.zgx.pocketmind.memory.MemoryRecordType
import com.bytedance.zgx.pocketmind.orchestration.AgentRunState
import com.bytedance.zgx.pocketmind.ui.theme.LocalPocketMindColors
import java.text.SimpleDateFormat
import java.util.Date
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
    onForgetLongTermMemory: (String) -> Unit,
    onClearLongTermMemory: () -> Unit,
    onRefreshBackgroundTasks: () -> Unit,
    onRefreshAuditEvents: () -> Unit,
    onCancelBackgroundTask: (String) -> Unit,
    onSetPeriodicCheckPolicy: (PeriodicCheckScheduleRequest) -> Unit,
    onDisablePeriodicCheckPolicy: () -> Unit,
    onConfirmAgentConfirmation: (PendingAgentConfirmation) -> Unit,
    onDismissAgentConfirmation: () -> Unit,
    onSendMessage: (String) -> Unit,
    onStartVoiceInput: () -> Unit,
    onPickSharedAttachment: () -> Unit,
    onVoiceInputConsumed: (Long) -> Unit,
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
    var showBackgroundTasks by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lastMessageId = state.messages.lastOrNull()?.id

    LaunchedEffect(state.activeSessionId, lastMessageId) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }
    LaunchedEffect(state.voiceInputDraft?.id) {
        val draft = state.voiceInputDraft ?: return@LaunchedEffect
        input = appendComposerInput(input, draft.text)
        onVoiceInputConsumed(draft.id)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PocketMindBackgroundBrush()),
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
                    onOpenBackgroundTasks = {
                        onRefreshBackgroundTasks()
                        onRefreshAuditEvents()
                        showBackgroundTasks = true
                    },
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

                if (state.memoryHits.isNotEmpty()) {
                    MemoryContextStrip(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("memory_context_strip"),
                        hitCount = state.memoryHits.size,
                    )
                }

                Composer(
                    state = state,
                    input = input,
                    onInputChanged = { input = it },
                    onOpenModelManager = { showModelManager = true },
                    onStartVoiceInput = onStartVoiceInput,
                    onPickSharedAttachment = onPickSharedAttachment,
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
                        onForgetLongTermMemory = onForgetLongTermMemory,
                        onClearLongTermMemory = onClearLongTermMemory,
                        onOpenModelPage = onOpenModelPage,
                        onDismiss = { showModelManager = false },
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

            if (showBackgroundTasks) {
                ModalBottomSheet(
                    sheetState = sheetState,
                    onDismissRequest = { showBackgroundTasks = false },
                ) {
                    BackgroundTaskSheet(
                        state = state,
                        onRefreshBackgroundTasks = onRefreshBackgroundTasks,
                        onRefreshAuditEvents = onRefreshAuditEvents,
                        onCancelBackgroundTask = onCancelBackgroundTask,
                        onSetPeriodicCheckPolicy = onSetPeriodicCheckPolicy,
                        onDisablePeriodicCheckPolicy = onDisablePeriodicCheckPolicy,
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

            state.pendingConfirmation?.let { confirmation ->
                ModalBottomSheet(
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismissRequest = onDismissAgentConfirmation,
                ) {
                    ActionDraftSheet(
                        confirmation = confirmation,
                        onConfirm = { onConfirmAgentConfirmation(confirmation) },
                        onDismiss = onDismissAgentConfirmation,
                    )
                }
            }
        }
    }
}

private fun appendComposerInput(current: String, addition: String): String {
    val cleaned = addition.trim()
    if (cleaned.isBlank()) return current
    return if (current.isBlank()) {
        cleaned
    } else {
        current.trimEnd() + "\n" + cleaned
    }
}

@Composable
private fun PocketMindBackgroundBrush(): Brush =
    Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceContainerLowest,
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    )

@Composable
private fun ChatTopBar(
    state: ChatUiState,
    onOpenModelManager: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenBackgroundTasks: () -> Unit,
    onCreateSession: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
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
                TopActionButton(
                    modifier = Modifier.testTag("top_create_session_button"),
                    icon = Icons.Filled.Add,
                    label = "新建会话",
                    onClick = onCreateSession,
                    enabled = !state.isBusy,
                )
                TopActionButton(
                    modifier = Modifier.testTag("top_model_button"),
                    icon = Icons.Filled.Tune,
                    label = "模型管理",
                    onClick = onOpenModelManager,
                )
                TopActionButton(
                    modifier = Modifier.testTag("top_background_tasks_button"),
                    icon = Icons.Filled.Notifications,
                    label = "后台任务",
                    onClick = onOpenBackgroundTasks,
                )
                TopActionButton(
                    modifier = Modifier.testTag("top_session_button"),
                    icon = Icons.Filled.Hub,
                    label = "会话",
                    onClick = onOpenSessions,
                )
            }

            RuntimeStatusBadge(state)
        }
    }
}

@Composable
private fun TopActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        modifier = modifier
            .size(48.dp)
            .semantics {
                contentDescription = label
            },
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.36f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
        )
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
    val semanticColors = LocalPocketMindColors.current
    val container = when {
        active && state.inferenceMode == InferenceMode.Remote -> semanticColors.remoteContainer
        active -> semanticColors.localContainer
        state.isBusy || state.isDownloading -> semanticColors.busyContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        active && state.inferenceMode == InferenceMode.Remote -> semanticColors.onRemoteContainer
        active -> semanticColors.onLocalContainer
        state.isBusy || state.isDownloading -> semanticColors.onBusyContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = CircleShape,
        color = container,
        border = BorderStroke(1.dp, content.copy(alpha = 0.16f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
    val semanticColors = LocalPocketMindColors.current
    val readyTitle = when {
        state.inferenceMode == InferenceMode.Remote && state.isReady -> "远程模型已就绪"
        state.isReady -> "本机模型已就绪"
        else -> "准备你的随身模型"
    }
    val readyDescription = when {
        state.inferenceMode == InferenceMode.Remote && state.isReady ->
            "当前会话为空，可以直接输入问题；远程模式会发送当前对话上下文。"
        state.isReady ->
            "当前会话为空，选择一个开场问题，或在底部直接输入。问答和历史记录会保留在本机。"
        else ->
            "先下载推荐模型或导入已有 .litertlm 文件；加载成功后即可离线问答。"
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
            border = BorderStroke(
                width = 1.dp,
                color = if (state.isReady) {
                    semanticColors.accentLine.copy(alpha = 0.86f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                },
            ),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = readyTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = readyDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.isReady) {
                    StatusSummaryRow(state)
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
private fun StatusSummaryRow(state: ChatUiState) {
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
private fun QuickModelSetup(
    state: ChatUiState,
    onOpenModelManager: () -> Unit,
    onPickModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onRecommendedModelSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
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
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = null,
            )
            Text(" 下载 ${state.selectedRecommendedModel.shortName}")
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
                Icon(
                    imageVector = Icons.Filled.FolderOpen,
                    contentDescription = null,
                )
                Text(" 导入")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onOpenModelManager,
                enabled = !state.isBusy,
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                )
                Text(" 更多")
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
            subtitle = "默认只安装对话模型；记忆和动作当前是本地轻量助手，可稍后补装实验模型资产。",
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
            Text("下载选中的模型")
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
    confirmation: PendingAgentConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = confirmation.draft
    val runtimePermissionRequirements = confirmation.runtimePermissionRequirementsFor()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitle(
            text = draft.title,
            subtitle = "动作只会在你确认后读取上下文、创建草稿或调起系统能力。",
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
        if (runtimePermissionRequirements.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("runtime_permission_requirements"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "确认后可能请求系统权限",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                runtimePermissionRequirements.forEach { requirement ->
                    Text(
                        text = "${requirement.title}：${requirement.rationale}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("action_confirm_button"),
            onClick = onConfirm,
        ) {
            Text("确认执行")
        }
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("action_dismiss_button"),
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                ),
                enabled = enabled,
                onClick = { onSendPrompt(prompt) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        modifier = Modifier.size(18.dp),
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
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
    onForgetLongTermMemory: (String) -> Unit,
    onClearLongTermMemory: () -> Unit,
    onOpenModelPage: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("当前", "模型", "远程", "高级")
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "模型管理",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RuntimeStatusBadge(state)
                IconButton(
                    modifier = Modifier.testTag("model_manager_close_button"),
                    onClick = onDismiss,
                    enabled = !state.isBusy,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭模型管理",
                    )
                }
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
                onDownloadRecommendedModel = onDownloadRecommendedModel,
                onOpenModelPage = onOpenModelPage,
            )

            2 -> RemoteModelPanel(
                state = state,
                onInferenceModeSelected = onInferenceModeSelected,
                onRemoteModelConfigChanged = onRemoteModelConfigChanged,
            )

            else -> AdvancedModelPanel(
                state = state,
                onGenerationParametersChanged = onGenerationParametersChanged,
                onResetGenerationParameters = onResetGenerationParameters,
                onMemoryEnabledChanged = onMemoryEnabledChanged,
                onForgetLongTermMemory = onForgetLongTermMemory,
                onClearLongTermMemory = onClearLongTermMemory,
            )
        }

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
    }
}

private fun labelToTabTag(label: String): String =
    when (label) {
        "当前" -> "current"
        "模型" -> "models"
        "远程" -> "remote"
        else -> "advanced"
    }

@Composable
private fun ModelInventoryPanel(
    state: ChatUiState,
    customModelUrl: String,
    onCustomModelUrlChanged: (String) -> Unit,
    onPickModel: () -> Unit,
    onDownloadCustomModel: (String) -> Unit,
    onRecommendedModelSelected: (String) -> Unit,
    onInstalledModelSelected: (String) -> Unit,
    onDownloadRecommendedModel: (String) -> Unit,
    onOpenModelPage: () -> Unit,
) {
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
private fun AddModelPanel(
    customModelUrl: String,
    onCustomModelUrlChanged: (String) -> Unit,
    onPickModel: () -> Unit,
    onDownloadCustomModel: (String) -> Unit,
    enabled: Boolean,
) {
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
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = null,
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
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
            )
            Text(" 导入本地文件")
        }
    }
}

@Composable
private fun EmptyPanelText(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
    ) {
        Text(
            modifier = Modifier.padding(14.dp),
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AdvancedModelPanel(
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

@Composable
private fun PanelSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f),
        ),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun CurrentModelPanel(
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendChip(
                        modifier = Modifier.testTag("backend_gpu_chip"),
                        label = "GPU",
                        selected = state.backend == BackendChoice.GPU,
                        enabled = !state.isBusy,
                        onClick = { onBackendSelected(BackendChoice.GPU) },
                    )
                    BackendChip(
                        modifier = Modifier.testTag("backend_cpu_chip"),
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
            if (!usingRemote && state.modelPath != null && !state.isReady && !state.isBusy) {
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onLoadModel,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                    )
                    Text(" 加载模型")
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
    PanelSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(
                    text = "远程模型",
                    subtitle = "兼容 /v1/chat/completions；远程模式会发送当前对话上下文。",
                )
                Switch(
                    modifier = Modifier.testTag("remote_mode_switch"),
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
            Text(
                text = remoteConfigStatusText(config),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun remoteConfigStatusText(config: RemoteModelConfig): String =
    when {
        config.isConfigured && config.usesLocalInsecureTransport ->
            "远程配置已保存；HTTP 仅允许本机调试地址，API Key 会加密保存在本机。"

        config.isConfigured ->
            "远程配置已保存；API Key 会加密保存在本机。"

        config.baseUrl.startsWith("http://") ->
            "非本机 HTTP 地址不可用；请使用 HTTPS 或本机调试地址。"

        else ->
            "填写 HTTPS 服务地址和模型名后可切换远程模型"
    }

@Composable
private fun MemoryTogglePanel(
    state: ChatUiState,
    enabled: Boolean,
    onMemoryEnabledChanged: (Boolean) -> Unit,
    onForgetLongTermMemory: (String) -> Unit,
    onClearLongTermMemory: () -> Unit,
) {
    val memoryModelInstalled = ModelCapability.MemoryEmbedding in state.installedCapabilities
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    PanelSurface {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        text = "本地记忆",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (memoryModelInstalled) {
                            "记忆模型资产已安装；当前使用本地轻量索引召回。"
                        } else {
                            "当前使用本地轻量索引；可补装记忆模型资产。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    modifier = Modifier.testTag("memory_switch"),
                    checked = state.memoryEnabled,
                    onCheckedChange = onMemoryEnabledChanged,
                    enabled = enabled,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    SectionTitle(
                        text = "长期记忆",
                        subtitle = "已保存 ${state.longTermMemories.size} 条偏好或任务状态。",
                    )
                }
                TextButton(
                    modifier = Modifier.testTag("long_term_memory_clear_button"),
                    onClick = { confirmClear = true },
                    enabled = enabled && state.longTermMemories.isNotEmpty(),
                ) {
                    Text("清空")
                }
            }

            if (state.longTermMemories.isEmpty()) {
                EmptyPanelText("还没有已保存的长期记忆。")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.longTermMemories.forEach { memory ->
                        LongTermMemoryRow(
                            memory = memory,
                            enabled = enabled,
                            onForget = { onForgetLongTermMemory(memory.id) },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空长期记忆") },
            text = { Text("只会清空已保存的偏好和任务状态记录，会话历史不会删除。") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("long_term_memory_confirm_clear_button"),
                    enabled = enabled,
                    onClick = {
                        confirmClear = false
                        onClearLongTermMemory()
                    },
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun LongTermMemoryRow(
    memory: LongTermMemorySummary,
    enabled: Boolean,
    onForget: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("long_term_memory_record_${memory.id}"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f),
        ),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = memory.type.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = memory.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                modifier = Modifier.testTag("long_term_memory_forget_${memory.id}"),
                onClick = onForget,
                enabled = enabled,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "遗忘这条记忆",
                )
            }
        }
    }
}

private fun MemoryRecordType.label(): String =
    when (this) {
        MemoryRecordType.Preference -> "偏好"
        MemoryRecordType.TaskState -> "任务状态"
        MemoryRecordType.Conversation -> "会话"
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
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("session_delete_button"),
                onClick = onDeleteSession,
                enabled = !state.isBusy,
            ) {
                Text("删除当前会话")
            }
        }
    }
}

@Composable
private fun BackgroundTaskSheet(
    state: ChatUiState,
    onRefreshBackgroundTasks: () -> Unit,
    onRefreshAuditEvents: () -> Unit,
    onCancelBackgroundTask: (String) -> Unit,
    onSetPeriodicCheckPolicy: (PeriodicCheckScheduleRequest) -> Unit,
    onDisablePeriodicCheckPolicy: () -> Unit,
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier
                    .weight(1f)
                    .testTag("background_task_manager_title"),
                text = "后台任务",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(
                modifier = Modifier.testTag("background_task_refresh_button"),
                onClick = {
                    onRefreshBackgroundTasks()
                    onRefreshAuditEvents()
                },
                enabled = !state.isBusy,
            ) {
                Text("刷新")
            }
        }

        PeriodicCheckPolicySection(
            policy = state.periodicCheckPolicy,
            enabled = !state.isBusy,
            onSetPeriodicCheckPolicy = onSetPeriodicCheckPolicy,
            onDisablePeriodicCheckPolicy = onDisablePeriodicCheckPolicy,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

        if (state.backgroundTasks.isEmpty()) {
            EmptyPanelText("暂无运行中的后台任务")
        } else {
            state.backgroundTasks.forEach { task ->
                BackgroundTaskRow(
                    task = task,
                    enabled = !state.isBusy,
                    onCancel = { onCancelBackgroundTask(task.id) },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

        SectionTitle(
            text = "最近后台任务",
            subtitle = "已结束任务只展示状态，不提供再次执行入口。",
        )

        if (state.backgroundTaskHistory.isEmpty()) {
            EmptyPanelText("暂无后台任务历史")
        } else {
            state.backgroundTaskHistory.forEach { task ->
                BackgroundTaskRow(
                    task = task,
                    enabled = !state.isBusy,
                    onCancel = null,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle(
                modifier = Modifier.weight(1f),
                text = "最近审计日志",
                subtitle = "只显示不含参数的工具活动摘要。",
            )
            TextButton(
                modifier = Modifier.testTag("audit_event_refresh_button"),
                onClick = onRefreshAuditEvents,
                enabled = !state.isBusy,
            ) {
                Text("刷新")
            }
        }

        if (state.auditEvents.isEmpty()) {
            EmptyPanelText("暂无审计记录")
        } else {
            state.auditEvents.forEach { event ->
                AuditEventRow(event)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

        SectionTitle(
            text = "最近 Agent 轨迹",
            subtitle = "只展示持久化摘要，不展示工具参数。",
        )

        if (state.agentTraceRuns.isEmpty()) {
            EmptyPanelText("暂无 Agent 轨迹")
        } else {
            state.agentTraceRuns.forEach { run ->
                AgentTraceRunRow(run)
            }
        }
    }
}

@Composable
private fun AgentTraceRunRow(run: AgentTraceRunUiSummary) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent_trace_run_${run.id}"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f),
        ),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "Run ${run.id.takeLast(8)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = run.state.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            run.steps.takeLast(4).forEach { step ->
                Text(
                    text = "${step.type} · ${step.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PeriodicCheckPolicySection(
    policy: PeriodicCheckPolicySummary,
    enabled: Boolean,
    onSetPeriodicCheckPolicy: (PeriodicCheckScheduleRequest) -> Unit,
    onDisablePeriodicCheckPolicy: () -> Unit,
) {
    val request = policy.request.normalized()
    val policyKey = listOf(
        policy.updatedAtMillis?.toString().orEmpty(),
        policy.taskStatus?.name.orEmpty(),
        request.storageSummary(),
    ).joinToString(separator = "|")
    var policyEnabled by rememberSaveable(policyKey) { mutableStateOf(policy.isSwitchEnabled()) }
    var intervalMinutes by rememberSaveable(policyKey) { mutableStateOf(request.intervalMinutes) }
    var minSpacingMinutes by rememberSaveable(policyKey) { mutableStateOf(request.minNotificationSpacingMinutes) }
    var overdueGraceMinutes by rememberSaveable(policyKey) { mutableStateOf(request.overdueGraceMinutes) }
    var requiresBatteryNotLow by rememberSaveable(policyKey) {
        mutableStateOf(request.constraints.requiresBatteryNotLow)
    }
    var requiresCharging by rememberSaveable(policyKey) {
        mutableStateOf(request.constraints.requiresCharging)
    }
    val controlsEnabled = enabled && policyEnabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("periodic_check_policy_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle(
            text = "周期检查策略",
            subtitle = policy.statusLine(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = "启用本地提醒巡检",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Switch(
                modifier = Modifier.testTag("periodic_check_policy_switch"),
                checked = policyEnabled,
                onCheckedChange = { policyEnabled = it },
                enabled = enabled,
            )
        }
        PeriodicCheckChoiceRow(
            label = "检查间隔",
            options = periodicCheckIntervalOptions,
            selectedValue = intervalMinutes,
            enabled = controlsEnabled,
            onSelected = { intervalMinutes = it },
        )
        PeriodicCheckChoiceRow(
            label = "最小通知间隔",
            options = periodicCheckIntervalOptions,
            selectedValue = minSpacingMinutes,
            enabled = controlsEnabled,
            onSelected = { minSpacingMinutes = it },
        )
        PeriodicCheckChoiceRow(
            label = "过期宽限",
            options = periodicCheckGraceOptions,
            selectedValue = overdueGraceMinutes,
            enabled = controlsEnabled,
            onSelected = { overdueGraceMinutes = it },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = requiresBatteryNotLow,
                onCheckedChange = { requiresBatteryNotLow = it },
                enabled = controlsEnabled,
            )
            Text(
                modifier = Modifier.weight(1f),
                text = "低电量时暂停",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = requiresCharging,
                onCheckedChange = { requiresCharging = it },
                enabled = controlsEnabled,
            )
            Text(
                modifier = Modifier.weight(1f),
                text = "仅充电时运行",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .testTag("periodic_check_disable_button"),
                onClick = onDisablePeriodicCheckPolicy,
                enabled = enabled,
            ) {
                Text("关闭检查")
            }
            Button(
                modifier = Modifier
                    .weight(1f)
                    .testTag("periodic_check_save_button"),
                onClick = {
                    if (policyEnabled) {
                        onSetPeriodicCheckPolicy(
                            PeriodicCheckScheduleRequest(
                                enabled = true,
                                intervalMinutes = intervalMinutes,
                                minNotificationSpacingMinutes = minSpacingMinutes,
                                overdueGraceMinutes = overdueGraceMinutes,
                                constraints = PeriodicCheckConstraints(
                                    requiresBatteryNotLow = requiresBatteryNotLow,
                                    requiresCharging = requiresCharging,
                                ),
                            ),
                        )
                    } else {
                        onDisablePeriodicCheckPolicy()
                    }
                },
                enabled = enabled,
            ) {
                Text("保存策略")
            }
        }
    }
}

@Composable
private fun PeriodicCheckChoiceRow(
    label: String,
    options: List<Pair<Long, String>>,
    selectedValue: Long,
    enabled: Boolean,
    onSelected: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, optionLabel) ->
                FilterChip(
                    selected = selectedValue == value,
                    onClick = { onSelected(value) },
                    label = { Text(optionLabel) },
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun AuditEventRow(event: AuditEventSummary) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("audit_event_${event.id}"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f),
        ),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = event.eventType,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = event.auditTimeLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            val metadata = listOfNotNull(
                event.toolName,
                event.status,
                event.riskLevel,
                event.permissions.takeIf { it.isNotEmpty() }?.joinToString(separator = ","),
            ).joinToString(separator = " · ")
            if (metadata.isNotBlank()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = event.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun AuditEventSummary.auditTimeLabel(): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(createdAtMillis))

private val periodicCheckIntervalOptions = listOf(
    PeriodicCheckScheduleRequest.MIN_INTERVAL_MINUTES to "1 小时",
    PeriodicCheckScheduleRequest.DEFAULT_INTERVAL_MINUTES to "6 小时",
    12L * 60L to "12 小时",
    PeriodicCheckScheduleRequest.MAX_INTERVAL_MINUTES to "24 小时",
)

private val periodicCheckGraceOptions = listOf(
    PeriodicCheckScheduleRequest.MIN_OVERDUE_GRACE_MINUTES to "5 分钟",
    PeriodicCheckScheduleRequest.DEFAULT_OVERDUE_GRACE_MINUTES to "30 分钟",
    60L to "1 小时",
)

private fun PeriodicCheckPolicySummary.statusLine(): String {
    val policyLabel = if (isSwitchEnabled()) "已启用" else "未启用"
    val statusLabel = taskStatus?.label()?.let { "状态 $it" }
    val nextCheckLabel = nextAllowedRunAtMillis?.let {
        "下次允许检查 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))}"
    }
    val lastRunLabel = lastRunSummary?.takeIf { it.isNotBlank() }?.let { "最近 $it" }
    return listOfNotNull(policyLabel, statusLabel, nextCheckLabel, lastRunLabel)
        .joinToString(separator = " · ")
}

private fun PeriodicCheckPolicySummary.isSwitchEnabled(): Boolean =
    request.enabled &&
        taskStatus != ScheduledTaskStatus.Cancelled &&
        taskStatus != ScheduledTaskStatus.Deleted

@Composable
private fun BackgroundTaskRow(
    task: BackgroundTaskSummary,
    enabled: Boolean,
    onCancel: (() -> Unit)?,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("background_task_${task.id}"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f),
        ),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${task.type.label()} · ${task.status.label()} · ${task.triggerLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (task.body.isNotBlank()) {
                    Text(
                        text = task.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onCancel != null) {
                IconButton(
                    modifier = Modifier.testTag("background_task_cancel_${task.id}"),
                    onClick = onCancel,
                    enabled = enabled,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "取消后台任务",
                    )
                }
            }
        }
    }
}

private fun BackgroundTaskSummary.triggerLabel(): String {
    val prefix = when (type) {
        ScheduledTaskType.Reminder -> "触发"
        ScheduledTaskType.PeriodicCheck -> "下次允许检查"
    }
    return "$prefix ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(triggerAtMillis))}"
}

private fun ScheduledTaskType.label(): String =
    when (this) {
        ScheduledTaskType.Reminder -> "提醒"
        ScheduledTaskType.PeriodicCheck -> "周期检查"
    }

private fun ScheduledTaskStatus.label(): String =
    when (this) {
        ScheduledTaskStatus.Scheduled -> "运行中"
        ScheduledTaskStatus.Running -> "执行中"
        ScheduledTaskStatus.Delivered -> "已送达"
        ScheduledTaskStatus.Cancelled -> "已取消"
        ScheduledTaskStatus.Deleted -> "已删除"
        ScheduledTaskStatus.Failed -> "失败"
    }

private fun AgentRunState.label(): String =
    when (this) {
        AgentRunState.Created -> "已创建"
        AgentRunState.LoadingContext -> "加载上下文"
        AgentRunState.Planning -> "规划中"
        AgentRunState.AwaitingUserConfirmation -> "待确认"
        AgentRunState.ExecutingTool -> "执行工具"
        AgentRunState.RetryingTool -> "重试工具"
        AgentRunState.Observing -> "观察结果"
        AgentRunState.GeneratingAnswer -> "生成回答"
        AgentRunState.Completed -> "已完成"
        AgentRunState.Cancelled -> "已取消"
        AgentRunState.Failed -> "失败"
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
    val semanticColors = LocalPocketMindColors.current
    val accent = when (model.capability) {
        ModelCapability.Chat -> MaterialTheme.colorScheme.primary
        ModelCapability.MemoryEmbedding -> semanticColors.memory
        ModelCapability.MobileAction -> semanticColors.busy
    }
    val statusText = when {
        installed -> "已安装"
        model.setupTier == SetupTier.BasicRecommended -> "基础包"
        else -> "可选"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
        tonalElevation = 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelectedChat) {
                accent.copy(alpha = 0.8f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
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
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                    )
                    Text(
                        text = if (installed) " 重新下载" else " 下载",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
        }
    }
}

@Composable
private fun CapabilityMark(
    icon: ImageVector,
    tint: Color,
) {
    Surface(
        shape = CircleShape,
        color = tint.copy(alpha = 0.16f),
    ) {
        Icon(
            modifier = Modifier
                .padding(7.dp)
                .size(18.dp),
            imageVector = icon,
            contentDescription = null,
            tint = tint,
        )
    }
}

private fun capabilityIcon(capability: ModelCapability): ImageVector =
    when (capability) {
        ModelCapability.Chat -> Icons.Filled.Storage
        ModelCapability.MemoryEmbedding -> Icons.Filled.Memory
        ModelCapability.MobileAction -> Icons.Filled.Settings
    }

@Composable
private fun SectionTitle(
    text: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
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

private fun InstalledModelSummary.verificationLabel(): String =
    when (verificationStatus) {
        ModelVerificationStatus.VerifiedRecommended -> "SHA-256 已校验"
        ModelVerificationStatus.UnverifiedCustom -> "自定义未校验"
        ModelVerificationStatus.LegacyUnverified -> "旧文件未校验"
        ModelVerificationStatus.FailedVerification -> "校验失败"
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
        .filter { it.id in setupSelectedModelIds && !isModelInstalled(it.id) }
        .sumOf { it.byteSize }

@Composable
private fun DeviceCheck(
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
                    text = "建议至少预留 ${ModelCatalog.formatBytes(requiredBytes)}，再多留一些空间给加载缓存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
    val semanticColors = LocalPocketMindColors.current
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
private fun MemoryContextStrip(
    modifier: Modifier = Modifier,
    hitCount: Int,
) {
    val semanticColors = LocalPocketMindColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = semanticColors.memoryContainer.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                modifier = Modifier.size(16.dp),
                imageVector = Icons.Filled.Memory,
                contentDescription = null,
                tint = semanticColors.onMemoryContainer,
            )
            Text(
                text = "已引用本地记忆 $hitCount 条",
                style = MaterialTheme.typography.labelMedium,
                color = semanticColors.onMemoryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isStreaming: Boolean,
) {
    val isUser = message.role == MessageRole.User
    val semanticColors = LocalPocketMindColors.current
    val bubbleColor = if (isUser) {
        semanticColors.localContainer.copy(alpha = 0.92f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f)
    }
    val textColor = if (isUser) {
        semanticColors.onLocalContainer
    } else {
        MaterialTheme.colorScheme.onSurface
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
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.84f else 0.96f),
            shape = shape,
            color = bubbleColor,
            border = if (isUser) {
                BorderStroke(1.dp, semanticColors.local.copy(alpha = 0.24f))
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f))
            },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = when {
                        isUser -> "你"
                        isStreaming -> "PocketMind · 生成中"
                        else -> "PocketMind"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.62f),
                    fontWeight = FontWeight.SemiBold,
                )
                if (isStreaming && message.text.isBlank()) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
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
                        color = textColor.copy(alpha = 0.54f),
                    )
                }
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
    val semanticColors = LocalPocketMindColors.current
    val cleaned = code.trim('\n')
    val lines = cleaned.lines()
    val maybeLanguage = lines.firstOrNull().orEmpty()
    val hasLanguage = maybeLanguage.length in 1..24 &&
        maybeLanguage.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '+' || it == '#' }
    val codeText = if (hasLanguage) lines.drop(1).joinToString("\n") else cleaned

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(semanticColors.codeSurface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.medium,
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
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
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
    onStartVoiceInput: () -> Unit,
    onPickSharedAttachment: () -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
) {
    val inputEnabled = state.isReady && !state.isBusy
    val attachmentEnabled = !state.isBusy
    val actionIsStop = state.isGenerating
    val semanticColors = LocalPocketMindColors.current
    val placeholder = when {
        state.isBusy -> state.statusText
        !state.isReady -> "先准备模型，再开始提问"
        else -> "输入问题"
    }
    val modelLabel = if (state.inferenceMode == InferenceMode.Remote) {
        state.remoteModelConfig.modelName.ifBlank { "远程模型" }
    } else {
        state.installedModels.firstOrNull { it.id == state.activeInstalledModelId }?.displayName
            ?: state.selectedRecommendedModel.shortName
    }
    val showCompactStatus = !state.isReady || state.isBusy
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
                        MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.98f),
                    ),
                ),
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = if (showCompactStatus) {
                    Modifier.weight(1f)
                } else {
                    Modifier.weight(1f, fill = false)
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                onClick = onOpenModelManager,
                enabled = !state.isBusy,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        modifier = Modifier.size(15.dp),
                        imageVector = if (state.inferenceMode == InferenceMode.Remote) {
                            Icons.Filled.Cloud
                        } else {
                            Icons.Filled.Storage
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        modifier = Modifier.weight(1f, fill = false),
                        text = modelLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (showCompactStatus) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = state.statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .testTag("composer_input"),
                value = input,
                onValueChange = onInputChanged,
                enabled = inputEnabled,
                minLines = 1,
                maxLines = 5,
                placeholder = { Text(placeholder) },
                leadingIcon = {
                    IconButton(
                        modifier = Modifier
                            .testTag("composer_attachment_button")
                            .semantics {
                                contentDescription = "选择附件"
                            },
                        onClick = onPickSharedAttachment,
                        enabled = attachmentEnabled,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = null,
                        )
                    }
                },
            )

            IconButton(
                modifier = Modifier
                    .height(52.dp)
                    .width(52.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .testTag("composer_voice_button")
                    .semantics {
                        contentDescription = "语音输入"
                    },
                onClick = onStartVoiceInput,
                enabled = inputEnabled,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.58f),
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.34f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                )
            }

            IconButton(
                modifier = Modifier
                    .height(52.dp)
                    .width(52.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .testTag("composer_model_button")
                    .semantics {
                        contentDescription = "模型管理"
                    },
                onClick = onOpenModelManager,
                enabled = !state.isBusy,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.58f),
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.34f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                )
            }

            IconButton(
                modifier = Modifier
                    .height(52.dp)
                    .width(52.dp)
                    .testTag("composer_send_button")
                    .semantics {
                        contentDescription = if (actionIsStop) "停止生成" else "发送"
                    },
                onClick = when {
                    actionIsStop -> onStopGeneration
                    else -> onSend
                },
                enabled = when {
                    actionIsStop -> true
                    else -> inputEnabled && input.isNotBlank()
                },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = when {
                        actionIsStop -> semanticColors.busy
                        inputEnabled && input.isNotBlank() -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = when {
                        actionIsStop -> semanticColors.onBusy
                        inputEnabled && input.isNotBlank() -> MaterialTheme.colorScheme.onPrimary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f)
                    },
                ),
            ) {
                Icon(
                    imageVector = if (actionIsStop) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                )
            }
        }
    }
}
