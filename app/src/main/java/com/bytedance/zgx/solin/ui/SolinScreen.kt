package com.bytedance.zgx.solin.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ui.components.ActionDraftSheet
import com.bytedance.zgx.solin.ui.components.BackgroundTaskSheet
import com.bytedance.zgx.solin.ui.components.MODEL_MANAGER_CURRENT_TAB_INDEX
import com.bytedance.zgx.solin.ui.components.MODEL_MANAGER_PRIVACY_TAB_INDEX
import com.bytedance.zgx.solin.ui.components.MODEL_MANAGER_REMOTE_TAB_INDEX
import com.bytedance.zgx.solin.ui.components.ModelManagerSheet
import com.bytedance.zgx.solin.ui.components.PanelSurface
import com.bytedance.zgx.solin.ui.components.SessionManagerSheet
import com.bytedance.zgx.solin.ui.components.ExternalOutcomeSheet
import com.bytedance.zgx.solin.ui.components.RemoteModeDisclosureSheet
import com.bytedance.zgx.solin.ui.components.RemoteSendDisclosureSheet
import com.bytedance.zgx.solin.ui.components.SolinGlyph
import com.bytedance.zgx.solin.ui.components.SolinGlyphKind
import com.bytedance.zgx.solin.ui.components.ChatEmptyState
import com.bytedance.zgx.solin.ui.components.ChatTopBar
import com.bytedance.zgx.solin.ui.components.MemoryContextStrip
import com.bytedance.zgx.solin.ui.components.MessageBubble
import com.bytedance.zgx.solin.ui.components.RecoveryActionEntry
import com.bytedance.zgx.solin.ui.components.RunTimelineStrip
import com.bytedance.zgx.solin.ui.components.SourcesStrip
import com.bytedance.zgx.solin.ui.components.publicWebEvidenceDisplayRows
import com.bytedance.zgx.solin.ui.components.solinTechBackdrop
import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.LocalModelTokenLimits
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.PendingExternalOutcomeConfirmation
import com.bytedance.zgx.solin.PendingRemoteModeDisclosure
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteSendDisclosurePolicy
import com.bytedance.zgx.solin.RunTimelineItemUiSummary
import com.bytedance.zgx.solin.RunDataReceiptUiSummary
import com.bytedance.zgx.solin.R
import com.bytedance.zgx.solin.SpecialAccessRequirement
import com.bytedance.zgx.solin.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.orchestration.AgentExternalOutcome
import com.bytedance.zgx.solin.orchestration.AgentRecoveryAction
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.resource.SystemResourceSnapshot
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors
import java.util.Locale

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SolinScreen(
    state: ChatUiState,
    onImportModel: (Uri) -> Unit,
    onDownloadModel: () -> Unit,
    onDownloadRecommendedModel: (String) -> Unit,
    onDownloadCustomModel: (String) -> Unit,
    onSaveHuggingFaceAccessToken: (String) -> Unit,
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
    onCreateSession: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: () -> Unit,
    onOpenModelPage: (String) -> Unit,
    onSetupModelToggled: (String, Boolean) -> Unit,
    onDownloadSetupModels: () -> Unit,
    onSkipFirstRunSetup: () -> Unit,
    onMemoryEnabledChanged: (Boolean) -> Unit,
    onForgetLongTermMemory: (String) -> Unit,
    onClearLongTermMemory: () -> Unit,
    onReduceDeviceActionConfirmationsChanged: (Boolean) -> Unit,
    onRefreshBackgroundTasks: () -> Unit,
    onRefreshAuditEvents: () -> Unit,
    onCancelBackgroundTask: (String) -> Unit,
    onSetPeriodicCheckPolicy: (PeriodicCheckScheduleRequest) -> Unit,
    onDisablePeriodicCheckPolicy: () -> Unit,
    onOpenSpecialAccessSettings: (SpecialAccessRequirement) -> Unit,
    onConfirmAgentConfirmation: (PendingAgentConfirmation) -> Unit,
    onDismissAgentConfirmation: (PendingAgentConfirmation?) -> Unit,
    onRecordExternalOutcome: (PendingExternalOutcomeConfirmation, AgentExternalOutcome) -> Unit,
    onOpenRecoveryAction: (AgentRecoveryAction) -> Unit,
    onDismissRemoteModeDisclosure: () -> Unit,
    onConfirmRemoteModeDisclosure: (PendingRemoteModeDisclosure) -> Unit = {
        onDismissRemoteModeDisclosure()
    },
    onConfirmRemoteSendDisclosure: (Boolean) -> Unit,
    onConfirmRemoteSendWithMasking: () -> Unit,
    onConfirmRemoteSendDespiteSensitive: () -> Unit,
    onDismissRemoteSendDisclosure: () -> Unit,
    onRemoteSendDisclosurePolicySelected: (RemoteSendDisclosurePolicy) -> Unit,
    onSendMessage: (String) -> Unit,
    onSendPendingSharedInput: (String) -> Unit,
    onClearPendingSharedInput: (Long) -> Unit,
    onStartVoiceInput: () -> Unit,
    onCancelVoiceInput: () -> Unit,
    onFinishVoiceInput: () -> Unit,
    onPickSharedAttachment: () -> Unit,
    onVoiceInputConsumed: (Long) -> Unit,
    onStopGeneration: () -> Unit,
    resourceSampler: (suspend () -> SystemResourceSnapshot?)? = null,
    activeRunPlacement: RunPlacement? = null,
    activeRunPlacementReason: PlacementReasonCode? = null,
) {
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportModel)
    }
    val listState = rememberLazyListState()
    var input by rememberSaveable(state.activeSessionId) { mutableStateOf("") }
    var customModelUrl by rememberSaveable { mutableStateOf("") }
    var huggingFaceAccessTokenInput by remember { mutableStateOf("") }
    var showModelManager by rememberSaveable { mutableStateOf(false) }
    var modelManagerInitialTab by rememberSaveable { mutableStateOf(MODEL_MANAGER_CURRENT_TAB_INDEX) }
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
                .solinTechBackdrop(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
            ) {
                ChatTopBar(
                    state = state,
                    resourceSampler = resourceSampler,
                    onOpenModelManager = {
                        modelManagerInitialTab = MODEL_MANAGER_CURRENT_TAB_INDEX
                        showModelManager = true
                    },
                    onOpenPrivacyNotice = {
                        modelManagerInitialTab = MODEL_MANAGER_PRIVACY_TAB_INDEX
                        showModelManager = true
                    },
                    onOpenSessions = { showSessions = true },
                    onOpenBackgroundTasks = {
                        onRefreshBackgroundTasks()
                        onRefreshAuditEvents()
                        showBackgroundTasks = true
                    },
                    onCreateSession = onCreateSession,
                    activeRunPlacement = activeRunPlacement,
                    activeRunPlacementReason = activeRunPlacementReason,
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (state.messages.isEmpty()) {
                        ChatEmptyState(
                            state = state,
                            onOpenModelManager = {
                                modelManagerInitialTab = MODEL_MANAGER_CURRENT_TAB_INDEX
                                showModelManager = true
                            },
                            onOpenPrivacyNotice = {
                                modelManagerInitialTab = MODEL_MANAGER_PRIVACY_TAB_INDEX
                                showModelManager = true
                            },
                            onOpenRemoteModelConfig = {
                                modelManagerInitialTab = MODEL_MANAGER_REMOTE_TAB_INDEX
                                showModelManager = true
                            },
                            onPickModel = { pickModel.launch(arrayOf("*/*")) },
                            onDownloadModel = onDownloadModel,
                            onCancelDownload = onCancelDownload,
                            onSendPrompt = onSendMessage,
                            onSetupModelToggled = onSetupModelToggled,
                            onDownloadSetupModels = onDownloadSetupModels,
                            onSkipFirstRunSetup = onSkipFirstRunSetup,
                            activeRunPlacement = activeRunPlacement,
                            activeRunPlacementReason = activeRunPlacementReason,
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

                val publicSourceRows = publicWebEvidenceDisplayRows(state.activePublicWebEvidence)
                if (publicSourceRows.isNotEmpty()) {
                    SourcesStrip(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("public_sources_strip"),
                        rows = publicSourceRows,
                    )
                }

                if (state.activeRunTimeline.isNotEmpty()) {
                    RunTimelineStrip(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("run_timeline_strip"),
                        items = state.activeRunTimeline,
                    )
                }

                if (state.activeMemoryEvidence.isNotEmpty()) {
                    MemoryContextStrip(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("memory_context_strip"),
                        evidence = state.activeMemoryEvidence,
                    )
                }

                state.latestRecoveryAction
                    ?.takeIf { state.pendingConfirmation == null && state.pendingExternalOutcome == null }
                    ?.let { recoveryAction ->
                    RecoveryActionEntry(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("latest_recovery_action_entry"),
                        action = recoveryAction,
                        enabled = !state.isBusy,
                        onClick = { onOpenRecoveryAction(recoveryAction) },
                    )
                }

                Composer(
                    state = state,
                    input = input,
                    onInputChanged = { input = it },
                    onOpenModelManager = {
                        modelManagerInitialTab = MODEL_MANAGER_CURRENT_TAB_INDEX
                        showModelManager = true
                    },
                    onStartVoiceInput = onStartVoiceInput,
                    onCancelVoiceInput = onCancelVoiceInput,
                    onFinishVoiceInput = onFinishVoiceInput,
                    onPickSharedAttachment = onPickSharedAttachment,
                    onClearPendingSharedInput = onClearPendingSharedInput,
                    onSend = {
                        val message = input
                        if (state.pendingSharedInputDraft != null) {
                            input = ""
                            onSendPendingSharedInput(message)
                        } else {
                            onSendMessage(message)
                            if (state.isReady) input = ""
                        }
                    },
                    onStopGeneration = onStopGeneration,
                    activeRunPlacement = activeRunPlacement,
                    activeRunPlacementReason = activeRunPlacementReason,
                )
            }

            if (showModelManager) {
                ModalBottomSheet(
                    sheetState = sheetState,
                    onDismissRequest = { showModelManager = false },
                ) {
                    ModelManagerSheet(
                        state = state,
                        initialSelectedTab = modelManagerInitialTab,
                        customModelUrl = customModelUrl,
                        onCustomModelUrlChanged = { customModelUrl = it },
                        onPickModel = { pickModel.launch(arrayOf("*/*")) },
                        onDownloadModel = onDownloadModel,
                        onDownloadRecommendedModel = onDownloadRecommendedModel,
                        onDownloadCustomModel = onDownloadCustomModel,
                        huggingFaceAccessTokenInput = huggingFaceAccessTokenInput,
                        onHuggingFaceAccessTokenInputChanged = { huggingFaceAccessTokenInput = it },
                        onSaveHuggingFaceAccessToken = {
                            onSaveHuggingFaceAccessToken(huggingFaceAccessTokenInput)
                            huggingFaceAccessTokenInput = ""
                        },
                        onClearHuggingFaceAccessToken = onClearHuggingFaceAccessToken,
                        onCancelDownload = onCancelDownload,
                        onLoadModel = onLoadModel,
                        onRecommendedModelSelected = onRecommendedModelSelected,
                        onInstalledModelSelected = onInstalledModelSelected,
                        onDeleteInstalledModel = onDeleteInstalledModel,
                        onInferenceModeSelected = { mode ->
                            onInferenceModeSelected(mode)
                            if (mode == InferenceMode.Auto) {
                                showModelManager = false
                            }
                        },
                        onRemoteModelConfigChanged = onRemoteModelConfigChanged,
                        onTestRemoteModelConnectivity = onTestRemoteModelConnectivity,
                        onBackendSelected = onBackendSelected,
                        onGenerationParametersChanged = onGenerationParametersChanged,
                        onResetGenerationParameters = onResetGenerationParameters,
                        onMemoryEnabledChanged = onMemoryEnabledChanged,
                        onForgetLongTermMemory = onForgetLongTermMemory,
                        onClearLongTermMemory = onClearLongTermMemory,
                        onReduceDeviceActionConfirmationsChanged = onReduceDeviceActionConfirmationsChanged,
                        onRemoteSendDisclosurePolicySelected = onRemoteSendDisclosurePolicySelected,
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
                        onDismiss = { showSessions = false },
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

            state.pendingRemoteModeDisclosure?.let { disclosure ->
                ModalBottomSheet(
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismissRequest = onDismissRemoteModeDisclosure,
                ) {
                    RemoteModeDisclosureSheet(
                        disclosure = disclosure,
                        onConfirm = { onConfirmRemoteModeDisclosure(disclosure) },
                        onDismiss = onDismissRemoteModeDisclosure,
                    )
                }
            }

            state.pendingRemoteSendDisclosure?.let { disclosure ->
                ModalBottomSheet(
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismissRequest = onDismissRemoteSendDisclosure,
                ) {
                    RemoteSendDisclosureSheet(
                        disclosure = disclosure,
                        disclosurePolicy = state.remoteSendDisclosurePolicy,
                        onConfirm = onConfirmRemoteSendDisclosure,
                        onMaskAndSend = onConfirmRemoteSendWithMasking,
                        onSendAnyway = onConfirmRemoteSendDespiteSensitive,
                        onDismiss = onDismissRemoteSendDisclosure,
                    )
                }
            }

            state.pendingConfirmation?.let { confirmation ->
                ModalBottomSheet(
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismissRequest = { onDismissAgentConfirmation(confirmation) },
                ) {
                    ActionDraftSheet(
                        confirmation = confirmation,
                        grantedSpecialAccessIds = state.grantedSpecialAccessIds,
                        onOpenSpecialAccessSettings = onOpenSpecialAccessSettings,
                        onConfirm = { onConfirmAgentConfirmation(confirmation) },
                        onDismiss = { onDismissAgentConfirmation(confirmation) },
                    )
                }
            }

            state.pendingExternalOutcome?.let { pendingOutcome ->
                ModalBottomSheet(
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismissRequest = {},
                ) {
                    ExternalOutcomeSheet(
                        pending = pendingOutcome,
                        onRecord = { outcome -> onRecordExternalOutcome(pendingOutcome, outcome) },
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


internal fun runDataReceiptDisplayText(receipt: RunDataReceiptUiSummary): String {
    val destination = when (receipt.destination) {
        "Remote" -> "远端"
        "Local" -> "本机"
        else -> receipt.destination.ifBlank { "未知" }
    }
    val protectedTypes = receipt.protectedContentTypes
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "、")
        ?: "无额外保护项"
    val deletableRecords = receipt.deletableRecordTypes
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "、")
        ?: "无"
    val rawPersisted = if (receipt.rawContentPersisted) "是" else "否"
    val memoryUsage = if (receipt.memoryContextIncluded) "已使用" else "未带出"
    val memoryRecall = if (receipt.memoryHitCount > 0) {
        "Semantic ${receipt.semanticMemoryHitCount} / Lexical ${receipt.lexicalMemoryHitCount}"
    } else {
        "未使用"
    }
    val outputQuality = if (receipt.outputQualityGuardTriggered) {
        val issue = receipt.outputQualityIssue ?: "已触发"
        val rule = receipt.outputQualityRule?.let { value -> "/$value" }.orEmpty()
        val action = receipt.outputQualityAction?.let { value -> "，动作=$value" }.orEmpty()
        val prefix = if (receipt.outputQualityKeptPrefix) "，已保留前缀" else ""
        "触发 $issue$rule$action$prefix"
    } else {
        "未触发"
    }
    val deviceUsage = if (receipt.deviceContextIncluded) "已使用" else "未带出"
    val evidenceSources = receipt.evidenceSourceTypes
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "/")
        ?: "无"
    val evidenceUsage = if (receipt.evidenceCardCount > 0) {
        "${receipt.evidenceCardCount} 条（仅本机 ${receipt.localOnlyEvidenceCardCount}，截断 ${receipt.truncatedEvidenceCardCount}，低质量 ${receipt.lowQualityEvidenceCardCount}，来源 $evidenceSources）"
    } else {
        "未使用"
    }
    return "去向：$destination；隐私：${receipt.currentPromptPrivacy.userFacingPrivacyLabel()}；远端历史：${receipt.remoteHistoryCount}；" +
        "过滤仅本机历史：${receipt.localOnlyHistoryFilteredCount}；记忆：$memoryUsage/${receipt.memoryHitCount}；" +
        "召回：$memoryRecall；设备上下文：$deviceUsage；图片：${receipt.imageAttachmentCount}；受保护源：${receipt.protectedSourceCount}；" +
        "证据：$evidenceUsage；输出保护：$outputQuality；保护：${protectedTypes.userFacingPrivacyLabel()}；可删除：$deletableRecords；原文持久化：$rawPersisted"
}

private fun String.userFacingPrivacyLabel(): String =
    replace("RemoteEligible 对话上下文", "可远程发送的对话上下文")
        .replace("LocalOnly 历史", "仅本机历史")
        .replace("RemoteEligible", "可远程发送")
        .replace("LocalOnly", "仅本机")

internal fun inferencePreferenceDisplayText(preference: InferenceMode): String =
    "偏好：${preference.label()}"

internal fun activePlacementDisplayText(
    placement: RunPlacement?,
    reason: PlacementReasonCode?,
): String? = when (placement) {
    RunPlacement.Local -> when (reason) {
        PlacementReasonCode.USER_FORCED_LOCAL -> "本次使用本地模型：你选择了本地偏好。"
        PlacementReasonCode.PRIVACY_REQUIRES_LOCAL -> "本次使用本地模型：内容仅限本地处理。"
        PlacementReasonCode.REMOTE_NOT_AUTHORIZED -> "本次使用本地模型：自动模式尚未获得远程授权。"
        PlacementReasonCode.REMOTE_NOT_CONFIGURED -> "本次使用本地模型：远程模型尚未配置。"
        PlacementReasonCode.REMOTE_CONNECTIVITY_UNAVAILABLE -> "本次使用本地模型：远程连接当前不可用。"
        PlacementReasonCode.REMOTE_STATUS_STALE -> "本次使用本地模型：远程连接状态需要重新验证。"
        PlacementReasonCode.REMOTE_CAPABILITY_MISMATCH -> "本次使用本地模型：远程模型能力不满足本次请求。"
        PlacementReasonCode.REMOTE_OVERLOADED -> "本次使用本地模型：远程模型当前繁忙。"
        PlacementReasonCode.AUTO_SIMPLE_LOCAL -> "本次使用本地模型：任务较轻，手机状态正常。"
        PlacementReasonCode.AUTO_IMAGE_LOCAL -> "本次使用本地模型：图片可在本机处理，避免发送到远程。"
        PlacementReasonCode.PLACEMENT_LOCAL_CONTINUATION_REQUIRED ->
            "本次使用本地模型：后续内容需要留在本机。"
        PlacementReasonCode.MODEL_EXECUTION_FAILED -> "本地模型执行失败。"
        else -> "本次使用本地模型。"
    }

    RunPlacement.Remote -> when (reason) {
        PlacementReasonCode.USER_FORCED_REMOTE -> "本次使用远程模型：你选择了远程偏好。"
        PlacementReasonCode.LOCAL_MODEL_UNAVAILABLE -> "本次使用远程模型：本地模型当前不可用。"
        PlacementReasonCode.LOCAL_RESOURCE_BLOCKED -> "本次使用远程模型：手机资源暂不适合本地运行。"
        PlacementReasonCode.LOCAL_CAPABILITY_MISMATCH -> "本次使用远程模型：本地模型能力不满足本次请求。"
        PlacementReasonCode.AUTO_COMPLEX_REMOTE -> "本次使用远程模型：任务较复杂，且远程连接已验证。"
        PlacementReasonCode.AUTO_RESOURCE_REMOTE -> "本次使用远程模型：手机资源压力较高，且远程连接已验证。"
        PlacementReasonCode.MODEL_EXECUTION_FAILED -> "远程模型执行失败。"
        else -> "本次使用远程模型。"
    }

    null -> when (reason) {
        null -> null
        PlacementReasonCode.PRIVACY_REQUIRES_LOCAL ->
            "无法执行：内容只能在本地处理，但本地模型当前不可用。"
        PlacementReasonCode.LOCAL_MODEL_UNAVAILABLE -> "无法执行：本地模型当前不可用。"
        PlacementReasonCode.LOCAL_RESOURCE_BLOCKED -> "无法执行：手机资源暂不适合本地运行。"
        PlacementReasonCode.LOCAL_CAPABILITY_MISMATCH -> "无法执行：本地模型能力不满足本次请求。"
        PlacementReasonCode.REMOTE_NOT_AUTHORIZED -> "无法执行：尚未获得远程模型授权。"
        PlacementReasonCode.REMOTE_NOT_CONFIGURED -> "无法执行：远程模型尚未配置。"
        PlacementReasonCode.REMOTE_CONNECTIVITY_UNAVAILABLE -> "无法执行：远程连接当前不可用。"
        PlacementReasonCode.REMOTE_STATUS_STALE -> "无法执行：远程连接状态需要重新验证。"
        PlacementReasonCode.REMOTE_CAPABILITY_MISMATCH -> "无法执行：远程模型能力不满足本次请求。"
        PlacementReasonCode.REMOTE_OVERLOADED -> "无法执行：远程模型当前繁忙。"
        PlacementReasonCode.NO_ELIGIBLE_TARGET ->
            "无法执行：没有同时满足隐私、能力和可用性要求的模型。"
        PlacementReasonCode.PLACEMENT_DECISION_MISSING -> "无法执行：本次运行位置尚未确定。"
        PlacementReasonCode.PLACEMENT_NOT_RESTORABLE -> "无法继续：本次运行位置无法安全恢复。"
        PlacementReasonCode.PLACEMENT_LOCAL_CONTINUATION_REQUIRED ->
            "无法继续远程生成：后续内容需要留在本机。"
        PlacementReasonCode.MODEL_EXECUTION_FAILED -> "模型执行失败。"
        else -> "无法执行：没有可用的运行位置。"
    }
}

internal fun currentModelStatus(state: ChatUiState): String {
    val ready = if (state.inferenceMode == InferenceMode.Remote) {
        when {
            state.isBusy -> state.statusText
            state.isReady -> "已就绪"
            else -> state.statusText
        }
    } else {
        when {
            state.isDownloading -> state.downloadProgressPercent?.let { "下载 $it%" } ?: "下载中"
            state.isBusy -> state.statusText
            state.isReady -> "已就绪"
            state.modelPath != null -> "待加载"
            else -> state.statusText
        }
    }
    return when (state.inferenceMode) {
        InferenceMode.Local -> {
            val modelName = state.installedModels
                .firstOrNull { it.id == state.activeInstalledModelId }
                ?.displayName
                ?: state.selectedRecommendedModel.shortName
            "${inferencePreferenceDisplayText(state.inferenceMode)} · 本地候选：$modelName · " +
                "${state.backend.name} · ${LocalModelTokenLimits.compactDisplayText(state.localMaxTotalTokens)} · $ready"
        }

        InferenceMode.Auto ->
            "${inferencePreferenceDisplayText(state.inferenceMode)} · 本地与远程候选按请求评估 · $ready"

        InferenceMode.Remote -> {
            val modelName = state.remoteModelConfig.modelName.ifBlank { "远程模型" }
            "${inferencePreferenceDisplayText(state.inferenceMode)} · 远程候选：$modelName · $ready"
        }
    }
}

internal fun modelHealthDisplayText(state: ChatUiState): String {
    val health = state.modelHealth
    val parts = mutableListOf("健康：${health.state.label()}")
    health.backend?.let { backend -> parts += "backend=${backend.label()}" }
    health.fallbackBackend?.let { fallback -> parts += "fallback=${fallback.label()}" }
    health.loadMs?.let { loadMs -> parts += "load=${loadMs}ms" }
    health.firstTokenMs?.let { firstTokenMs -> parts += "first=${firstTokenMs}ms" }
    health.tokenCount?.let { tokens -> parts += "tokens=$tokens" }
    health.tokensPerSecond?.takeIf { it > 0.0 }?.let { value ->
        parts += "speed=${String.format(Locale.US, "%.1f", value)} tok/s"
    }
    health.failureReason
        ?.takeIf { it.isNotBlank() }
        ?.let { reason -> parts += "reason=${reason.take(80)}" }
    health.lastOutputQualityIssue
        ?.takeIf { it.isNotBlank() }
        ?.let { issue ->
            val rule = health.lastOutputQualityRule
                ?.takeIf { value -> value.isNotBlank() }
                ?.let { value -> "/$value" }
                .orEmpty()
            parts += "quality=$issue$rule"
        }
    return parts.joinToString(separator = " · ")
}

internal fun ModelHealthState.label(): String =
    when (this) {
        ModelHealthState.NotInstalled -> "未安装"
        ModelHealthState.InstalledUnverified -> "待校验"
        ModelHealthState.Verified -> "已校验"
        ModelHealthState.Loading -> "加载中"
        ModelHealthState.Loaded -> "已加载"
        ModelHealthState.LoadFailed -> "加载失败"
        ModelHealthState.FallbackActive -> "Fallback"
    }

internal fun capabilityLabel(capability: ModelCapability): String =
    when (capability) {
        ModelCapability.Chat -> "对话"
        ModelCapability.MemoryEmbedding -> "记忆"
        ModelCapability.MobileAction -> "动作"
    }

internal fun ChatUiState.pendingSelectedChatDownloadBytes(): Long =
    if (isModelInstalled(selectedRecommendedModel.id)) {
        0L
    } else {
        selectedRecommendedModel.byteSize
    }

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

internal const val REMOTE_ATTACHMENT_PROTECTION_NOTICE =
    "远程模式：图片发送前会确认；非图片附件、分享文本和 OCR 不会自动发送。"

internal const val AUTO_ATTACHMENT_PROTECTION_NOTICE =
    "自动偏好：本次位置会在发送时决定；如使用远程，图片发送前会确认，其他附件不会自动发送。"

internal const val ACTUAL_REMOTE_ATTACHMENT_PROTECTION_NOTICE =
    "本次使用远程模型：图片发送前会确认；非图片附件、分享文本和 OCR 不会自动发送。"

internal const val PRODUCT_POSITIONING_TEXT =
    "隐私优先的随身 AI 助手：可下载或导入本地模型，远程多模态可选，设备动作必须确认执行；能力与信任中心会集中说明数据边界和权限。"

internal const val PRODUCT_POSITIONING_SHORT_TEXT =
    "隐私优先的随身 AI 助手"

internal const val PRODUCT_HOME_TITLE_TEXT =
    "隐私优先的随身 AI 助手"

internal const val PRODUCT_HOME_DESCRIPTION_TEXT =
    "本地模型可下载或导入，远程多模态可选，设备动作必须确认执行。没有模型时只展示启动选项，不读取本地数据，也不会自动发送远程请求。"

internal enum class HomeValueKind {
    Local,
    Remote,
    Action,
}

internal data class HomeValueProposition(
    val kind: HomeValueKind,
    val title: String,
    val body: String,
)

internal val HOME_VALUE_PROPOSITIONS = listOf(
    HomeValueProposition(
        kind = HomeValueKind.Local,
        title = "本地可用",
        body = "基础问答、图片输入、会话和显式记忆优先留在本机；离线模型可稍后下载或导入。",
    ),
    HomeValueProposition(
        kind = HomeValueKind.Remote,
        title = "远程多模态可选",
        body = "远程模型只在你配置并切换后使用；切换时提醒一次，疑似敏感内容仍会单独确认。",
    ),
    HomeValueProposition(
        kind = HomeValueKind.Action,
        title = "动作确认执行",
        body = "联系人、日历、分享、提醒和系统相关动作先说明权限与风险，再由你确认。",
    ),
)

internal const val MODEL_STARTUP_BANNER_TITLE =
    "模型未就绪"

internal const val MODEL_STARTUP_BANNER_DESCRIPTION =
    "配置远程模型可立即试用；下载或导入本地模型后可离线问答。切换远程会提醒，设备动作仍会先让你确认。"

internal val HOME_CAPABILITY_PILLS = listOf(
    "离线问答",
    "显式记忆",
    "图片/文件",
    "确认动作",
)

internal const val LOCAL_SETUP_PANEL_TITLE =
    "离线基础问答可选下载"

internal const val LOCAL_SETUP_PANEL_DESCRIPTION =
    "下载后基础问答和历史默认留在本机；也可以先跳过，稍后配置远程模型或导入可信 .litertlm。"

internal const val MODEL_MANAGER_POSITIONING_TEXT =
    "可下载或导入本地模型离线使用；远程多模态可选。切换远程会提醒，设备动作仍会先确认。"

internal val PRODUCT_PROMPT_SUGGESTIONS = listOf(
    "哪些内容留在本机？",
    "把想法整理成本机待办",
    "切换远程模型前提醒什么？",
)

internal const val PRODUCT_LOCAL_VALUE_TEXT =
    "下载或导入已验证本地模型后，基础问答可在手机上运行；支持图片的本地模型可处理主动选择的图片。会话、记忆和本地工具结果默认留在本机。"

internal const val PRODUCT_REMOTE_VALUE_TEXT =
    "远程模型只在你配置并切换后使用；主动选择的图片只在逐次确认后发送给远程视觉模型，不支持图片时直接提示不支持。"

internal const val PRODUCT_ACTION_VALUE_TEXT =
    "联系人、日历、系统页面、分享、提醒和屏幕相关能力都先展示用途、权限和风险，再由你确认或取消。"

internal const val PRIVACY_POLICY_ENTRY_TEXT =
    "说明哪些内容留在本机、什么时候会发送到远程，以及哪些设备能力必须你确认后才执行。"

internal const val REMOTE_MODE_DISCLOSURE_TEXT =
    "OpenAI 兼容地址和模型名；API Key 加密保存在本机。图片需开启并逐次确认后才会发送。"

internal const val MODEL_DOWNLOAD_RATIONALE_TEXT =
    "本地模型让基础问答离线可用；基础对话模型约 2.4 GB，缺少、下载失败或文件不完整时可在这里补装，也可以先配置远程模型。"

internal const val VOICE_INPUT_PRIVACY_DESCRIPTION =
    "语音输入；使用系统语音转写，结果只进入输入框，不自动发送，不读取本地音频文件；开启前会先确认"

internal const val VOICE_INPUT_PERMISSION_DISCLOSURE_TITLE =
    "开启语音输入"

internal const val VOICE_INPUT_PERMISSION_DISCLOSURE_BODY =
    "语音会交由 Android 系统语音识别服务处理；Solin 不保存音频文件，转写结果只进入输入框，不会自动发送。确认后才会请求麦克风权限或开始收音。"

internal const val TRUST_LOCAL_BOUNDARY_TEXT =
    "会话、长期记忆、设备上下文和本地工具结果默认留在本机；切到远程模型时，标记为仅本机的隐私消息和工具结果不会进入远程历史。"

internal const val TRUST_REMOTE_BOUNDARY_TEXT =
    "远程模型会收到当前可远程发送的对话上下文；你主动附加的图片会在逐次确认后随请求发送，非图片附件、分享文本、OCR 摘录和本地工具私密结果不会自动发送。"

internal const val TRUST_PERMISSION_BOUNDARY_TEXT =
    "联系人、日历、媒体、通知、当前屏幕、Accessibility 文本和截图 OCR 都需要运行时权限、系统特殊授权或前台一次性确认；动作工具仍需用户确认后执行。"

internal const val SENSITIVE_CAPABILITY_DISCLOSURE_TEXT =
    "麦克风、媒体、联系人/日历、Usage Stats、Accessibility、截图、远程图片发送和设备动作都有数据范围、同意边界，以及取消、撤销或清除路径。"



@Composable
private fun Composer(
    state: ChatUiState,
    input: String,
    onInputChanged: (String) -> Unit,
    onOpenModelManager: () -> Unit,
    onStartVoiceInput: () -> Unit,
    onCancelVoiceInput: () -> Unit,
    onFinishVoiceInput: () -> Unit,
    onPickSharedAttachment: () -> Unit,
    onClearPendingSharedInput: (Long) -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
    activeRunPlacement: RunPlacement?,
    activeRunPlacementReason: PlacementReasonCode?,
) {
    val inputEnabled = !state.isBusy
    val attachmentEnabled = !state.isBusy
    val voiceEnabled = !state.isBusy && !state.voiceCapture.isActive
    var showVoicePermissionDisclosure by rememberSaveable { mutableStateOf(false) }
    val actionIsStop = state.isGenerating
    val composerEdge = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
    val hasPendingSharedInput = state.pendingSharedInputDraft != null
    val canSend = inputEnabled && (input.isNotBlank() || hasPendingSharedInput)
    val placeholder = when {
        state.isBusy -> state.statusText
        !state.isReady -> "配置远程或下载本地后提问"
        hasPendingSharedInput -> "补充说明"
        else -> "输入问题"
    }
    val showCompactStatus = state.isBusy ||
        state.statusText.isVoiceStatusText() ||
        state.statusText.isAgentExecutionOutcomeStatusText()
    val attachmentNotice = attachmentProtectionNotice(
        preference = state.inferenceMode,
        actualPlacement = activeRunPlacement,
        actualReason = activeRunPlacementReason,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.86f))
            .drawBehind {
                drawLine(
                    color = composerEdge,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.voiceCapture.isActive) {
            VoiceCaptureBar(
                isTranscribing = state.voiceCapture.isTranscribing,
                level = state.voiceCapture.level,
                waveformLevels = state.voiceCapture.waveformLevels,
                partialText = state.voiceCapture.partialText,
                onCancel = onCancelVoiceInput,
                onFinish = onFinishVoiceInput,
            )
            VoiceInputPrivacyNotice()
        }
        if (attachmentNotice != null) {
            AttachmentProtectionNotice(attachmentNotice)
        }
        state.pendingSharedInputDraft?.let { draft ->
            PendingSharedInputStrip(
                summary = draft.summary,
                onRemove = { onClearPendingSharedInput(draft.id) },
            )
        }
        if (showCompactStatus) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_status_text"),
                text = state.statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compactControls = maxWidth < 360.dp
            if (compactControls) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    ComposerAttachmentButton(
                        contentDescription = attachmentContentDescription(
                            preference = state.inferenceMode,
                            actualPlacement = activeRunPlacement,
                            actualReason = activeRunPlacementReason,
                        ),
                        enabled = attachmentEnabled,
                        onClick = onPickSharedAttachment,
                    )
                    ComposerTextInput(
                        modifier = Modifier.weight(1f),
                        input = input,
                        onInputChanged = onInputChanged,
                        inputEnabled = inputEnabled,
                        placeholder = placeholder,
                    )
                    ComposerVoiceButton(
                        enabled = voiceEnabled,
                        onClick = { showVoicePermissionDisclosure = true },
                    )
                    ComposerSendButton(
                        actionIsStop = actionIsStop,
                        canSend = canSend,
                        onStopGeneration = onStopGeneration,
                        onSend = onSend,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    ComposerAttachmentButton(
                        contentDescription = attachmentContentDescription(
                            preference = state.inferenceMode,
                            actualPlacement = activeRunPlacement,
                            actualReason = activeRunPlacementReason,
                        ),
                        enabled = attachmentEnabled,
                        onClick = onPickSharedAttachment,
                    )
                    ComposerTextInput(
                        modifier = Modifier.weight(1f),
                        input = input,
                        onInputChanged = onInputChanged,
                        inputEnabled = inputEnabled,
                        placeholder = placeholder,
                    )
                    ComposerVoiceButton(
                        enabled = voiceEnabled,
                        onClick = { showVoicePermissionDisclosure = true },
                    )
                    ComposerModelButton(
                        enabled = !state.isBusy,
                        onClick = onOpenModelManager,
                    )
                    ComposerSendButton(
                        actionIsStop = actionIsStop,
                        canSend = canSend,
                        onStopGeneration = onStopGeneration,
                        onSend = onSend,
                    )
                }
            }
        }
    }
    if (showVoicePermissionDisclosure) {
        VoiceInputPermissionDisclosureDialog(
            enabled = voiceEnabled,
            onConfirm = {
                showVoicePermissionDisclosure = false
                onStartVoiceInput()
            },
            onDismiss = {
                showVoicePermissionDisclosure = false
            },
        )
    }
}

@Composable
private fun VoiceInputPermissionDisclosureDialog(
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("voice_permission_disclosure_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(VOICE_INPUT_PERMISSION_DISCLOSURE_TITLE) },
        text = { Text(VOICE_INPUT_PERMISSION_DISCLOSURE_BODY) },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("voice_permission_consent_button"),
                enabled = enabled,
                onClick = onConfirm,
            ) {
                Text("同意并开启语音输入")
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("voice_permission_cancel_button"),
                onClick = onDismiss,
            ) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun ComposerAttachmentButton(
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ComposerIconButton(
        modifier = Modifier
            .testTag("composer_attachment_button")
            .semantics {
                this.contentDescription = contentDescription
            },
        enabled = enabled,
        onClick = onClick,
    ) {
        SolinGlyph(
            kind = SolinGlyphKind.Add,
            tint = LocalContentColor.current,
        )
    }
}

@Composable
private fun ComposerTextInput(
    input: String,
    onInputChanged: (String) -> Unit,
    inputEnabled: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        modifier = modifier
            .heightIn(min = 50.dp, max = 132.dp)
            .testTag("composer_input"),
        value = input,
        onValueChange = onInputChanged,
        enabled = inputEnabled,
        minLines = 1,
        maxLines = 5,
        placeholder = { Text(placeholder) },
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
            disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.96f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.70f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.32f),
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun ComposerVoiceButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ComposerIconButton(
        modifier = Modifier
            .testTag("composer_voice_button")
            .semantics {
                contentDescription = VOICE_INPUT_PRIVACY_DESCRIPTION
            },
        onClick = onClick,
        enabled = enabled,
    ) {
        SolinGlyph(
            kind = SolinGlyphKind.Voice,
            tint = LocalContentColor.current,
        )
    }
}

@Composable
private fun ComposerModelButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ComposerIconButton(
        modifier = Modifier
            .testTag("composer_model_button")
            .semantics {
                contentDescription = "模型管理"
            },
        onClick = onClick,
        enabled = enabled,
    ) {
        SolinGlyph(
            kind = SolinGlyphKind.Spark,
            tint = LocalContentColor.current,
        )
    }
}

@Composable
private fun ComposerSendButton(
    actionIsStop: Boolean,
    canSend: Boolean,
    onStopGeneration: () -> Unit,
    onSend: () -> Unit,
) {
    val semanticColors = LocalSolinColors.current
    IconButton(
        modifier = Modifier
            .height(46.dp)
            .width(46.dp)
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = 1.dp,
                color = if (canSend || actionIsStop) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.26f)
                },
                shape = MaterialTheme.shapes.medium,
            )
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
            else -> canSend
        },
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = when {
                actionIsStop -> semanticColors.busy
                canSend -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = when {
                actionIsStop -> semanticColors.onBusy
                canSend -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f)
            },
        ),
    ) {
        SolinGlyph(
            kind = if (actionIsStop) SolinGlyphKind.Stop else SolinGlyphKind.Send,
            tint = LocalContentColor.current,
        )
    }
}

@Composable
private fun AttachmentProtectionNotice(notice: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote_attachment_protection_notice"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.24f),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            text = notice,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun attachmentProtectionNotice(
    preference: InferenceMode,
    actualPlacement: RunPlacement?,
    actualReason: PlacementReasonCode?,
): String? = when {
    actualPlacement == RunPlacement.Remote -> ACTUAL_REMOTE_ATTACHMENT_PROTECTION_NOTICE
    actualPlacement == RunPlacement.Local || actualReason != null -> null
    preference == InferenceMode.Remote -> REMOTE_ATTACHMENT_PROTECTION_NOTICE
    preference == InferenceMode.Auto -> AUTO_ATTACHMENT_PROTECTION_NOTICE
    else -> null
}

private fun attachmentContentDescription(
    preference: InferenceMode,
    actualPlacement: RunPlacement?,
    actualReason: PlacementReasonCode?,
): String = when {
    actualPlacement == RunPlacement.Remote ->
        "选择附件；本次使用远程模型，逐次确认后发送图片，其他附件不读取正文或 OCR"
    actualPlacement == RunPlacement.Local || actualReason != null -> "选择附件"
    preference == InferenceMode.Remote ->
        "选择附件；远程模式逐次确认后发送图片，其他附件不读取正文或 OCR"
    preference == InferenceMode.Auto ->
        "选择附件；自动偏好会在发送时决定位置，使用远程时逐次确认图片"
    else -> "选择附件"
}

@Composable
private fun VoiceInputPrivacyNotice() {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_privacy_notice"),
        text = VOICE_INPUT_PRIVACY_DESCRIPTION,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ComposerIconButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    IconButton(
        modifier = modifier
            .height(46.dp)
            .width(42.dp)
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
                shape = MaterialTheme.shapes.medium,
            ),
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.34f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        ),
    ) {
        content()
    }
}

@Composable
private fun PendingSharedInputStrip(
    summary: String,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pending_shared_input_strip"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.66f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SolinGlyph(
                modifier = Modifier.size(16.dp),
                kind = SolinGlyphKind.Add,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                modifier = Modifier.weight(1f),
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                modifier = Modifier
                    .size(36.dp)
                    .semantics { contentDescription = "移除附件" },
                onClick = onRemove,
            ) {
                SolinGlyph(
                    kind = SolinGlyphKind.Close,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current,
                )
            }
        }
    }
}

@Composable
private fun VoiceCaptureBar(
    isTranscribing: Boolean,
    level: Float,
    waveformLevels: List<Float>,
    partialText: String,
    onCancel: () -> Unit,
    onFinish: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "voice_wave")
    val pulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice_wave_pulse",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_capture_bar"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.66f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoiceWaveform(
                level = level,
                waveformLevels = waveformLevels,
                pulse = pulse,
            )
            Text(
                modifier = Modifier.weight(1f),
                text = partialText.ifBlank { if (isTranscribing) "正在转写" else "正在收音" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                modifier = Modifier
                    .size(38.dp)
                    .semantics { contentDescription = "取消语音输入" },
                onClick = onCancel,
            ) {
                SolinGlyph(
                    kind = SolinGlyphKind.Close,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current,
                )
            }
            if (!isTranscribing) {
                IconButton(
                    modifier = Modifier
                        .size(38.dp)
                    .semantics { contentDescription = "结束语音输入" },
                    onClick = onFinish,
                ) {
                    SolinGlyph(
                        kind = SolinGlyphKind.Check,
                        modifier = Modifier.size(18.dp),
                        tint = LocalContentColor.current,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceWaveform(
    level: Float,
    waveformLevels: List<Float>,
    pulse: Float,
) {
    val normalized = level.coerceIn(0.08f, 1f)
    val bars = waveformLevels.takeIf { it.isNotEmpty() } ?: VOICE_WAVEFORM_FALLBACK_BARS
    val activity = ((normalized - 0.08f) / 0.92f).coerceIn(0f, 1f)
    val pulseGain = 0.86f + pulse * (0.12f + activity * 0.18f)
    Row(
        modifier = Modifier.width(56.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bars.forEachIndexed { index, sample ->
            val sampleLevel = sample.coerceIn(0.08f, 1f)
            val animated = (sampleLevel * pulseGain * (0.94f + index * 0.015f)).coerceIn(0.12f, 1f)
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((7f + animated * 30f).dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private fun String.isVoiceStatusText(): Boolean =
    listOf("语音", "麦克风", "收音", "转写", "识别", "声音")
        .any { marker -> marker in this }

private fun String.isAgentExecutionOutcomeStatusText(): Boolean =
    listOf("工具未执行", "权限被拒", "特殊权限未开启", "屏幕截图同意已取消")
        .any { marker -> marker in this }

private val VOICE_WAVEFORM_FALLBACK_BARS =
    listOf(0.08f, 0.14f, 0.18f, 0.11f, 0.16f, 0.12f, 0.17f, 0.1f, 0.15f)
