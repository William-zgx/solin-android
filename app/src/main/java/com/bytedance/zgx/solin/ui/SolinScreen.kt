package com.bytedance.zgx.solin.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ui.components.BackgroundTaskSheet
import com.bytedance.zgx.solin.ui.components.MODEL_MANAGER_CURRENT_TAB_INDEX
import com.bytedance.zgx.solin.ui.components.McpCapabilityToggleUiState
import com.bytedance.zgx.solin.presentation.MemoryPanelState
import com.bytedance.zgx.solin.ui.components.MODEL_MANAGER_PRIVACY_TAB_INDEX
import com.bytedance.zgx.solin.ui.components.MODEL_MANAGER_REMOTE_TAB_INDEX
import com.bytedance.zgx.solin.ui.components.ModelManagerSheet
import com.bytedance.zgx.solin.ui.components.SessionManagerSheet
import com.bytedance.zgx.solin.ui.components.SolinSheetHost
import com.bytedance.zgx.solin.ui.components.ChatEmptyState
import com.bytedance.zgx.solin.ui.components.ChatTopBar
import com.bytedance.zgx.solin.ui.components.Composer
import com.bytedance.zgx.solin.ui.components.MemoryContextStrip
import com.bytedance.zgx.solin.ui.components.MessageBubble
import com.bytedance.zgx.solin.ui.components.RecoveryActionEntry
import com.bytedance.zgx.solin.ui.components.RunTimelineStrip
import com.bytedance.zgx.solin.ui.components.SourcesStrip
import com.bytedance.zgx.solin.ui.components.publicWebEvidenceDisplayRows
import com.bytedance.zgx.solin.ui.components.solinTechBackdrop
import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.PendingExternalOutcomeConfirmation
import com.bytedance.zgx.solin.PendingRemoteModeDisclosure
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteSendDisclosurePolicy
import com.bytedance.zgx.solin.RuntimePermissionRequirement
import com.bytedance.zgx.solin.SpecialAccessRequirement
import com.bytedance.zgx.solin.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.solin.orchestration.AgentExternalOutcome
import com.bytedance.zgx.solin.orchestration.AgentRecoveryAction
import com.bytedance.zgx.solin.resource.SystemResourceSnapshot
import com.bytedance.zgx.solin.orchestration.DecisionModelRouting
import com.bytedance.zgx.solin.data.DecisionModelRoutingState

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
    onRemoteGuiAutomationEnabledChanged: (Boolean) -> Unit,
    mcpCapability: McpCapabilityToggleUiState? = null,
    onMcpCapabilityEnabledChanged: (Boolean, Boolean) -> Unit = { _, _ -> },
    decisionModelRouting: DecisionModelRoutingState? = null,
    onDecisionModelRoutingModeSelected: (DecisionModelRouting) -> Unit = {},
    onDecisionModelRoutingKeySaved: (String) -> Unit = {},
    memoryPanel: MemoryPanelState = MemoryPanelState(),
    onConfirmMemorySuggestion: (String) -> Unit = {},
    onDismissMemorySuggestion: (String) -> Unit = {},
    onRefreshBackgroundTasks: () -> Unit,
    onRefreshAuditEvents: () -> Unit,
    onCancelBackgroundTask: (String) -> Unit,
    onSetPeriodicCheckPolicy: (PeriodicCheckScheduleRequest) -> Unit,
    onDisablePeriodicCheckPolicy: () -> Unit,
    onOpenSpecialAccessSettings: (SpecialAccessRequirement) -> Unit,
    // Resolved by the caller, which owns the module-aware tool registry: a registry that does not
    // know a tool answers "nothing required", so resolving these inside the UI layer risks a
    // confirmation sheet that understates what the action will do. See ActionDraftSheet's KDoc.
    resolveRuntimePermissionRequirements: (PendingAgentConfirmation) -> List<RuntimePermissionRequirement>,
    resolveSpecialAccessRequirements: (PendingAgentConfirmation) -> List<SpecialAccessRequirement>,
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

                val publicSourceRows = remember(state.activePublicWebEvidence) {
                    publicWebEvidenceDisplayRows(state.activePublicWebEvidence)
                }
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
                        onRemoteGuiAutomationEnabledChanged = onRemoteGuiAutomationEnabledChanged,
                        mcpCapability = mcpCapability,
                        onMcpCapabilityEnabledChanged = onMcpCapabilityEnabledChanged,
                        decisionModelRouting = decisionModelRouting,
                        onDecisionModelRoutingModeSelected = onDecisionModelRoutingModeSelected,
                        onDecisionModelRoutingKeySaved = onDecisionModelRoutingKeySaved,
                        memoryPanel = memoryPanel,
                        onConfirmMemorySuggestion = onConfirmMemorySuggestion,
                        onDismissMemorySuggestion = onDismissMemorySuggestion,
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

            SolinSheetHost(
                state = state,
                onDismissRemoteModeDisclosure = onDismissRemoteModeDisclosure,
                onConfirmRemoteModeDisclosure = onConfirmRemoteModeDisclosure,
                onConfirmRemoteSendDisclosure = onConfirmRemoteSendDisclosure,
                onConfirmRemoteSendWithMasking = onConfirmRemoteSendWithMasking,
                onConfirmRemoteSendDespiteSensitive = onConfirmRemoteSendDespiteSensitive,
                onDismissRemoteSendDisclosure = onDismissRemoteSendDisclosure,
                resolveRuntimePermissionRequirements = resolveRuntimePermissionRequirements,
                resolveSpecialAccessRequirements = resolveSpecialAccessRequirements,
                onOpenSpecialAccessSettings = onOpenSpecialAccessSettings,
                onConfirmAgentConfirmation = onConfirmAgentConfirmation,
                onDismissAgentConfirmation = onDismissAgentConfirmation,
                onRecordExternalOutcome = onRecordExternalOutcome,
            )
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
