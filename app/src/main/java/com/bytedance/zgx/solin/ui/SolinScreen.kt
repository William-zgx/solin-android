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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.AuditEventSummary
import com.bytedance.zgx.solin.BackgroundTaskSummary
import com.bytedance.zgx.solin.AgentTraceRunUiSummary
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.HUGGING_FACE_TOKEN_SETTINGS_URL
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.GenerationStats
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.InstalledModelSummary
import com.bytedance.zgx.solin.LocalModelTokenLimits
import com.bytedance.zgx.solin.LongTermMemorySummary
import com.bytedance.zgx.solin.MemoryEvidenceUiSummary
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.PendingExternalOutcomeConfirmation
import com.bytedance.zgx.solin.PendingRemoteModeDisclosure
import com.bytedance.zgx.solin.PendingRemoteSendDisclosure
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.RecommendedModel
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.RemoteSendAuditSummary
import com.bytedance.zgx.solin.RemoteSendDisclosureKind
import com.bytedance.zgx.solin.RemoteSendDisclosurePolicy
import com.bytedance.zgx.solin.RunTimelineItemUiSummary
import com.bytedance.zgx.solin.RunDataReceiptUiSummary
import com.bytedance.zgx.solin.R
import com.bytedance.zgx.solin.SetupTier
import com.bytedance.zgx.solin.SpecialAccessRequirement
import com.bytedance.zgx.solin.runtimePermissionRequirementsFor
import com.bytedance.zgx.solin.specialAccessRequirementsFor
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.background.PeriodicCheckConstraints
import com.bytedance.zgx.solin.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.solin.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.solin.background.ScheduledTaskStatus
import com.bytedance.zgx.solin.background.ScheduledTaskType
import com.bytedance.zgx.solin.data.ModelVerificationStatus
import com.bytedance.zgx.solin.memory.SemanticMemoryRuntimeStatus
import com.bytedance.zgx.solin.isUsable
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.memory.MemoryRecordType
import com.bytedance.zgx.solin.orchestration.AgentExternalOutcome
import com.bytedance.zgx.solin.orchestration.AgentRecoveryAction
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.resource.SystemResourceSnapshot
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MODEL_MANAGER_CURRENT_TAB_INDEX = 0
private const val MODEL_MANAGER_REMOTE_TAB_INDEX = 2
private const val MODEL_MANAGER_PRIVACY_TAB_INDEX = 4

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
    var input by rememberSaveable { mutableStateOf("") }
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
                        onInferenceModeSelected = onInferenceModeSelected,
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

private enum class SolinGlyphKind {
    Add,
    Bell,
    Chat,
    Check,
    Close,
    Delete,
    Download,
    Memory,
    More,
    Spark,
    Shield,
    Stop,
    Undo,
    Voice,
    Send,
}

@Composable
private fun SolinGlyph(
    kind: SolinGlyphKind,
    modifier: Modifier = Modifier.size(20.dp),
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val unit = w.coerceAtMost(h)
        val stroke = Stroke(
            width = unit * 0.11f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        fun p(x: Float, y: Float) = Offset(w * x, h * y)

        when (kind) {
            SolinGlyphKind.Add -> {
                drawCircle(tint, radius = unit * 0.42f, center = p(0.5f, 0.5f), style = stroke)
                drawLine(tint, p(0.34f, 0.5f), p(0.66f, 0.5f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.5f, 0.34f), p(0.5f, 0.66f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Bell -> {
                val bell = Path().apply {
                    moveTo(w * 0.28f, h * 0.58f)
                    lineTo(w * 0.32f, h * 0.42f)
                    quadraticTo(w * 0.34f, h * 0.27f, w * 0.50f, h * 0.27f)
                    quadraticTo(w * 0.66f, h * 0.27f, w * 0.68f, h * 0.42f)
                    lineTo(w * 0.72f, h * 0.58f)
                    lineTo(w * 0.80f, h * 0.68f)
                    lineTo(w * 0.20f, h * 0.68f)
                    close()
                }
                drawPath(bell, tint, style = stroke)
                drawLine(tint, p(0.43f, 0.80f), p(0.57f, 0.80f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Chat -> {
                val bubble = Path().apply {
                    moveTo(w * 0.26f, h * 0.24f)
                    lineTo(w * 0.76f, h * 0.24f)
                    quadraticTo(w * 0.86f, h * 0.24f, w * 0.86f, h * 0.35f)
                    lineTo(w * 0.86f, h * 0.58f)
                    quadraticTo(w * 0.86f, h * 0.69f, w * 0.74f, h * 0.69f)
                    lineTo(w * 0.50f, h * 0.69f)
                    lineTo(w * 0.31f, h * 0.82f)
                    lineTo(w * 0.34f, h * 0.69f)
                    lineTo(w * 0.26f, h * 0.69f)
                    quadraticTo(w * 0.14f, h * 0.69f, w * 0.14f, h * 0.58f)
                    lineTo(w * 0.14f, h * 0.35f)
                    quadraticTo(w * 0.14f, h * 0.24f, w * 0.26f, h * 0.24f)
                }
                drawPath(bubble, tint, style = stroke)
                drawLine(tint, p(0.32f, 0.43f), p(0.68f, 0.43f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.32f, 0.55f), p(0.55f, 0.55f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Check -> {
                drawCircle(tint, radius = unit * 0.40f, center = p(0.5f, 0.5f), style = stroke)
                drawLine(tint, p(0.34f, 0.52f), p(0.46f, 0.64f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.46f, 0.64f), p(0.68f, 0.38f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Close -> {
                drawCircle(tint, radius = unit * 0.38f, center = p(0.5f, 0.5f), style = stroke)
                drawLine(tint, p(0.38f, 0.38f), p(0.62f, 0.62f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.62f, 0.38f), p(0.38f, 0.62f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Delete -> {
                drawLine(tint, p(0.30f, 0.34f), p(0.70f, 0.34f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.42f, 0.22f), p(0.58f, 0.22f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.46f, 0.22f), p(0.42f, 0.34f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.54f, 0.22f), p(0.58f, 0.34f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                val bin = Path().apply {
                    moveTo(w * 0.34f, h * 0.40f)
                    lineTo(w * 0.66f, h * 0.40f)
                    lineTo(w * 0.62f, h * 0.80f)
                    lineTo(w * 0.38f, h * 0.80f)
                    close()
                }
                drawPath(bin, tint, style = stroke)
            }

            SolinGlyphKind.Download -> {
                drawLine(tint, p(0.50f, 0.20f), p(0.50f, 0.58f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.34f, 0.44f), p(0.50f, 0.60f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.66f, 0.44f), p(0.50f, 0.60f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.26f, 0.76f), p(0.74f, 0.76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.26f, 0.64f), p(0.26f, 0.76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.74f, 0.64f), p(0.74f, 0.76f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Memory -> {
                drawCircle(tint, radius = unit * 0.09f, center = p(0.30f, 0.32f), style = stroke)
                drawCircle(tint, radius = unit * 0.09f, center = p(0.70f, 0.32f), style = stroke)
                drawCircle(tint, radius = unit * 0.09f, center = p(0.50f, 0.72f), style = stroke)
                drawLine(tint, p(0.36f, 0.38f), p(0.46f, 0.64f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.64f, 0.38f), p(0.54f, 0.64f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.39f, 0.32f), p(0.61f, 0.32f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.More -> {
                drawCircle(tint, radius = unit * 0.07f, center = p(0.5f, 0.26f))
                drawCircle(tint, radius = unit * 0.07f, center = p(0.5f, 0.5f))
                drawCircle(tint, radius = unit * 0.07f, center = p(0.5f, 0.74f))
            }

            SolinGlyphKind.Send -> {
                val arrow = Path().apply {
                    moveTo(w * 0.24f, h * 0.22f)
                    lineTo(w * 0.82f, h * 0.50f)
                    lineTo(w * 0.24f, h * 0.78f)
                    lineTo(w * 0.36f, h * 0.53f)
                    lineTo(w * 0.82f, h * 0.50f)
                    lineTo(w * 0.36f, h * 0.47f)
                    close()
                }
                drawPath(arrow, tint)
            }

            SolinGlyphKind.Shield -> {
                val shield = Path().apply {
                    moveTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.80f, h * 0.28f)
                    lineTo(w * 0.74f, h * 0.60f)
                    quadraticTo(w * 0.70f, h * 0.76f, w * 0.50f, h * 0.86f)
                    quadraticTo(w * 0.30f, h * 0.76f, w * 0.26f, h * 0.60f)
                    lineTo(w * 0.20f, h * 0.28f)
                    close()
                }
                drawPath(shield, tint, style = stroke)
                drawLine(tint, p(0.38f, 0.50f), p(0.48f, 0.60f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.48f, 0.60f), p(0.66f, 0.40f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Spark -> {
                drawLine(tint, p(0.50f, 0.12f), p(0.50f, 0.38f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.50f, 0.62f), p(0.50f, 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.12f, 0.50f), p(0.38f, 0.50f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.62f, 0.50f), p(0.88f, 0.50f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.28f, 0.28f), p(0.39f, 0.39f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.61f, 0.61f), p(0.72f, 0.72f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.72f, 0.28f), p(0.61f, 0.39f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.39f, 0.61f), p(0.28f, 0.72f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawCircle(tint, radius = unit * 0.055f, center = p(0.76f, 0.24f))
            }

            SolinGlyphKind.Stop -> {
                drawCircle(tint, radius = unit * 0.40f, center = p(0.5f, 0.5f), style = stroke)
                drawLine(tint, p(0.36f, 0.36f), p(0.64f, 0.64f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.64f, 0.36f), p(0.36f, 0.64f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            SolinGlyphKind.Undo -> {
                val curve = Path().apply {
                    moveTo(w * 0.32f, h * 0.34f)
                    lineTo(w * 0.18f, h * 0.50f)
                    lineTo(w * 0.32f, h * 0.66f)
                    moveTo(w * 0.20f, h * 0.50f)
                    lineTo(w * 0.62f, h * 0.50f)
                    quadraticTo(w * 0.80f, h * 0.50f, w * 0.80f, h * 0.68f)
                }
                drawPath(curve, tint, style = stroke)
            }

            SolinGlyphKind.Voice -> {
                drawLine(tint, p(0.18f, 0.55f), p(0.18f, 0.45f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.34f, 0.68f), p(0.34f, 0.32f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.50f, 0.78f), p(0.50f, 0.22f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.66f, 0.68f), p(0.66f, 0.32f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, p(0.82f, 0.55f), p(0.82f, 0.45f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun Modifier.solinTechBackdrop(): Modifier {
    val base = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val lift = MaterialTheme.colorScheme.surfaceContainerLow
    return background(base).drawBehind {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    primary.copy(alpha = 0.12f),
                    secondary.copy(alpha = 0.08f),
                    lift.copy(alpha = 0.50f),
                    base,
                ),
            ),
        )
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    tertiary.copy(alpha = 0.07f),
                    primary.copy(alpha = 0.05f),
                    secondary.copy(alpha = 0.06f),
                    Color.Transparent,
                ),
            ),
        )
    }
}

@Composable
private fun ChatTopBar(
    state: ChatUiState,
    resourceSampler: (suspend () -> SystemResourceSnapshot?)?,
    onOpenModelManager: () -> Unit,
    onOpenPrivacyNotice: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenBackgroundTasks: () -> Unit,
    onCreateSession: () -> Unit,
) {
    val topEdgeColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = topEdgeColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.88f),
        shadowElevation = 0.dp,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compactTopBar = maxWidth < 430.dp
            val actionButtonSize = if (compactTopBar) 30.dp else 36.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(
                        horizontal = if (compactTopBar) 8.dp else 14.dp,
                        vertical = if (compactTopBar) 4.dp else 5.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(if (compactTopBar) 4.dp else 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    modifier = Modifier
                        .size(if (compactTopBar) 32.dp else 38.dp),
                    painter = painterResource(R.drawable.solin_brand_mark),
                    contentDescription = null,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = if (compactTopBar) 56.dp else 132.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    Text(
                        modifier = Modifier.testTag("app_title"),
                        text = "Solin",
                        style = if (compactTopBar) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        modifier = Modifier.testTag("app_positioning_subtitle"),
                        text = if (compactTopBar) "隐私优先" else PRODUCT_POSITIONING_SHORT_TEXT,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (resourceSampler != null) {
                    ResourcePressureOverlay(
                        resourceSampler = resourceSampler,
                        modifier = Modifier.size(24.dp),
                    )
                }
                CompactModelStatusChip(
                    modifier = Modifier.widthIn(
                        min = if (compactTopBar) 58.dp else 96.dp,
                        max = if (compactTopBar) 78.dp else 148.dp,
                    ),
                    state = state,
                    compact = compactTopBar,
                    onClick = onOpenModelManager,
                )
                TopActionButton(
                    modifier = Modifier.testTag("top_session_button"),
                    glyph = SolinGlyphKind.Chat,
                    label = "会话",
                    size = actionButtonSize,
                    onClick = onOpenSessions,
                )
                Box {
                    TopActionButton(
                        modifier = Modifier.testTag("top_more_button"),
                        glyph = SolinGlyphKind.More,
                        label = "更多",
                        size = actionButtonSize,
                        onClick = { menuExpanded = true },
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.widthIn(min = 152.dp, max = 164.dp),
                    ) {
                        TopMenuItem(
                            modifier = Modifier.testTag("top_create_session_button"),
                            glyph = SolinGlyphKind.Add,
                            label = "新建会话",
                            enabled = !state.isBusy,
                            onClick = {
                                menuExpanded = false
                                onCreateSession()
                            },
                        )
                        TopMenuItem(
                            modifier = Modifier.testTag("top_model_menu_button"),
                            glyph = SolinGlyphKind.Spark,
                            label = "模型管理",
                            onClick = {
                                menuExpanded = false
                                onOpenModelManager()
                            },
                        )
                        TopMenuItem(
                            modifier = Modifier.testTag("top_privacy_button"),
                            glyph = SolinGlyphKind.Shield,
                            label = "隐私说明",
                            onClick = {
                                menuExpanded = false
                                onOpenPrivacyNotice()
                            },
                        )
                        TopMenuItem(
                            modifier = Modifier.testTag("top_background_tasks_button"),
                            glyph = SolinGlyphKind.Bell,
                            label = "后台任务",
                            onClick = {
                                menuExpanded = false
                                onOpenBackgroundTasks()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactModelStatusChip(
    state: ChatUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val active = state.isReady && !state.isBusy
    val semanticColors = LocalSolinColors.current
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
        onClick = onClick,
        modifier = modifier
            .testTag("top_model_button")
            .semantics {
                contentDescription = "模型管理"
                stateDescription = compactModelStatus(state)
            },
        shape = CircleShape,
        color = container,
        border = BorderStroke(1.dp, content.copy(alpha = 0.18f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(content),
            )
            Text(
                text = if (compact) compactModelStatusShort(state) else compactModelStatus(state),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TopMenuItem(
    glyph: SolinGlyphKind,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SolinGlyph(
            modifier = Modifier.size(20.dp),
            kind = glyph,
            tint = contentColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TopActionButton(
    glyph: SolinGlyphKind,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
) {
    IconButton(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .semantics {
                contentDescription = label
            },
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
        ),
    ) {
        SolinGlyph(
            kind = glyph,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.56f),
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
    val semanticColors = LocalSolinColors.current
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
    onOpenPrivacyNotice: () -> Unit,
    onOpenRemoteModelConfig: () -> Unit,
    onPickModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onSendPrompt: (String) -> Unit,
) {
    val readyTitle = when {
        state.isReady -> "想让 Solin 做什么？"
        else -> PRODUCT_HOME_TITLE_TEXT
    }
    val readyDescription = when {
        state.inferenceMode == InferenceMode.Remote && state.isReady ->
            "直接输入问题、整理线索，或先看看哪些内容会发送到远程。"
        state.isReady ->
            "直接输入问题、整理想法，或先看看哪些内容会留在本机。"
        else ->
            "先配置远程模型或下载本地模型；就绪前不会读取本地数据，也不会自动发送远程请求。"
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = if (state.isReady) {
                MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.86f)
            },
            border = BorderStroke(
                width = 1.dp,
                color = if (state.isReady) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
                },
            ),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Text(
                    text = readyTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = readyDescription,
                    style = MaterialTheme.typography.bodySmall,
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
                        onOpenRemoteModelConfig = onOpenRemoteModelConfig,
                        onPickModel = onPickModel,
                        onDownloadModel = onDownloadModel,
                        onCancelDownload = onCancelDownload,
                    )
                    HomeCapabilityPills()
                }
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_privacy_notice_button"),
                    onClick = onOpenPrivacyNotice,
                    enabled = !state.isBusy,
                ) {
                    SolinGlyph(
                        kind = SolinGlyphKind.Shield,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(" 隐私说明")
                }
                HomePositioningPanel()
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
private fun HomePositioningPanel() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_positioning_panel"),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        SectionTitle(
            text = "放心用的三件事",
            subtitle = PRODUCT_POSITIONING_TEXT,
        )
        HOME_VALUE_PROPOSITIONS.forEach { proposition ->
            TrustBoundaryRow(
                icon = homeValueIcon(proposition.kind),
                title = proposition.title,
                body = proposition.body,
            )
        }
    }
}

private fun homeValueIcon(kind: HomeValueKind): SolinGlyphKind =
    when (kind) {
        HomeValueKind.Local -> SolinGlyphKind.Shield
        HomeValueKind.Remote -> SolinGlyphKind.Spark
        HomeValueKind.Action -> SolinGlyphKind.Check
    }

@Composable
private fun HomeCapabilityPills() {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_capability_pills"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HOME_CAPABILITY_PILLS.forEach { label ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f)),
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FirstRunSetupPanel(
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
    onOpenRemoteModelConfig: () -> Unit,
    onPickModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
) {
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
                    text = MODEL_STARTUP_BANNER_TITLE,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = MODEL_STARTUP_BANNER_DESCRIPTION,
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

@Composable
private fun TrustSheetSurface(
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    val edgeColor = accentColor.copy(alpha = 0.24f)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .drawBehind {
                drawLine(
                    color = edgeColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f),
        ),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun TrustSheetGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.78f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.44f),
        ),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun RemoteModeDisclosureSheet(
    disclosure: PendingRemoteModeDisclosure,
    onDismiss: () -> Unit,
) {
    TrustSheetSurface(
        modifier = Modifier.testTag("remote_mode_disclosure_sheet"),
        accentColor = LocalSolinColors.current.remote,
    ) {
        SectionTitle(
            text = "已切换到远程模型",
            subtitle = "远程模式提醒只在切换时展示；普通发送不会逐条弹窗，疑似敏感内容仍会单独确认。",
        )
        RemoteModeDisclosureRows(disclosure)
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("remote_mode_confirm_button"),
            onClick = onDismiss,
        ) {
            SolinGlyph(
                kind = SolinGlyphKind.Spark,
                modifier = Modifier.size(18.dp),
                tint = LocalContentColor.current,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("知道了")
        }
    }
}

@Composable
private fun RemoteModeDisclosureRows(disclosure: PendingRemoteModeDisclosure) {
    val rows = remoteModeDisclosureDisplayRows(disclosure)
    TrustSheetGroup(
        modifier = Modifier.testTag("remote_mode_disclosure_rows"),
    ) {
        rows.forEach { row ->
            Text(
                text = row,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun remoteModeDisclosureDisplayRows(disclosure: PendingRemoteModeDisclosure): List<String> {
    val imagePolicy = if (disclosure.supportsVisionInput) {
        "主动选择的图片会在每次发送前弹出远程发送预览确认；请只选择你愿意交给该服务处理的图片。"
    } else {
        "当前远程模型未启用图片输入；选择图片时会直接提示不支持，不会读取或发送。"
    }
    val destinationSummary = if (disclosure.isConfigured) {
        "远程地址：${disclosure.remoteHost}"
    } else {
        "远程地址：尚未配置，配置完成前不会发送远程请求"
    }
    return listOf(
        destinationSummary,
        "模型：${disclosure.remoteModelName}",
        "发送范围：仅发送可远程发送的对话上下文、当前输入，以及确认后的主动选择图片。",
        "不会发送：仅本机历史、本地记忆、设备上下文、非图片附件正文或 OCR 摘录。",
        "图片规则：$imagePolicy",
        "远程服务方可能按其政策记录或保留请求和响应。",
        "凭据状态：${if (disclosure.apiKeyConfigured) "已配置 API Key" else "未配置 API Key"}",
        "连接状态：${disclosure.connectivityStatus.label}",
    )
}

@Composable
private fun RemoteSendDisclosureSheet(
    disclosure: PendingRemoteSendDisclosure,
    disclosurePolicy: RemoteSendDisclosurePolicy,
    onConfirm: (Boolean) -> Unit,
    onMaskAndSend: () -> Unit,
    onSendAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canSuppressForSession = remoteSendDisclosureCanSuppressForSession(
        policy = disclosurePolicy,
        disclosure = disclosure,
    )
    val requiresSensitiveConsent = disclosure.requiresSensitiveConsent
    var suppressForSession by rememberSaveable(disclosure) { mutableStateOf(false) }
    TrustSheetSurface(
        modifier = Modifier.testTag("remote_send_disclosure_sheet"),
        accentColor = if (requiresSensitiveConsent) {
            MaterialTheme.colorScheme.error
        } else {
            LocalSolinColors.current.remote
        },
    ) {
        SectionTitle(
            text = "即将发送到远程模型",
            subtitle = "确认后才会把本次内容交给远程模型；API Key 只作为请求凭据使用，不在界面显示。",
        )
        RemoteSendDisclosureRows(disclosure)
        if (canSuppressForSession) {
            TrustSheetGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { suppressForSession = !suppressForSession }
                        .testTag("remote_send_suppress_session_row"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = suppressForSession,
                        onCheckedChange = { suppressForSession = it },
                        modifier = Modifier.testTag("remote_send_suppress_session_checkbox"),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "本次会话不再提示（含敏感内容或图片时仍会提示）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!requiresSensitiveConsent) {
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("remote_send_confirm_button"),
                onClick = { onConfirm(canSuppressForSession && suppressForSession) },
            ) {
                SolinGlyph(
                    kind = SolinGlyphKind.Spark,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("确认发送")
            }
        }
        if (requiresSensitiveConsent) {
            if (disclosure.maskedPromptPreview.isNotBlank()) {
                TrustSheetGroup {
                    Text(
                        modifier = Modifier.testTag("remote_send_masked_preview"),
                        text = "打码后将发送：${disclosure.maskedPromptPreview}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (disclosure.allowMaskedSend) {
                FilledTonalButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("remote_send_mask_button"),
                    onClick = onMaskAndSend,
                ) {
                    SolinGlyph(
                        kind = SolinGlyphKind.Shield,
                        modifier = Modifier.size(18.dp),
                        tint = LocalContentColor.current,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("打码后发送")
                }
            }
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("remote_send_anyway_button"),
                onClick = onSendAnyway,
            ) {
                SolinGlyph(
                    kind = SolinGlyphKind.Spark,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("仍然发送（已记录）")
            }
        }
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("remote_send_dismiss_button"),
            onClick = onDismiss,
        ) {
            SolinGlyph(
                kind = SolinGlyphKind.Close,
                modifier = Modifier.size(18.dp),
                tint = LocalContentColor.current,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("取消")
        }
    }
}

internal fun remoteSendDisclosureCanSuppressForSession(
    policy: RemoteSendDisclosurePolicy,
    disclosure: PendingRemoteSendDisclosure,
): Boolean =
    policy == RemoteSendDisclosurePolicy.OncePerSession &&
        !disclosure.forcedBySensitiveContent &&
        !disclosure.requiresSensitiveConsent &&
        disclosure.imageAttachmentCount == 0

@Composable
private fun RemoteSendDisclosureRows(disclosure: PendingRemoteSendDisclosure) {
    val rows = remoteSendDisclosureDisplayRows(disclosure)
    TrustSheetGroup(
        modifier = Modifier.testTag("remote_send_disclosure_rows"),
    ) {
        rows.forEach { row ->
            Text(
                text = row,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun remoteSendDisclosureDisplayRows(disclosure: PendingRemoteSendDisclosure): List<String> {
    val sendSummary = when (disclosure.kind) {
        RemoteSendDisclosureKind.CurrentInput ->
            "会发送：当前输入、可远程发送历史 ${disclosure.remoteHistoryCount} 条"

        RemoteSendDisclosureKind.ToolResultContinuation ->
            "会发送：工具结果续写提示、可远程发送历史 ${disclosure.remoteHistoryCount} 条"
    }.let { summary ->
        if (disclosure.imageAttachmentCount > 0) {
            "$summary、图片 ${disclosure.imageAttachmentCount} 张；图片字节会发往该远程地址"
        } else {
            "$summary；本次没有图片字节发送"
        }
    }
    val retentionNotice = if (disclosure.imageAttachmentCount > 0) {
        "远程服务方可能按其政策记录或保留请求、图片和响应；请只发送你愿意交给该服务处理的内容。"
    } else {
        "远程服务方可能按其政策记录或保留请求和响应；请只发送你愿意交给该服务处理的内容。"
    }
    return listOf(
        "发送到：${disclosure.remoteHost} · ${disclosure.remoteModelName}",
        sendSummary,
        "不会发送：仅本机历史 ${disclosure.localOnlyHistoryFilteredCount} 条、本地记忆、设备上下文、非图片附件",
        retentionNotice,
    ) + buildList {
        if (disclosure.promptPreview.isNotBlank()) {
            add("将发送内容预览：${disclosure.promptPreview}")
        }
        if (disclosure.sensitiveHitCategories.isNotEmpty()) {
            add("⚠ 检测到疑似敏感内容（${disclosure.sensitiveHitCategories.joinToString("、")}）；请确认是否仍要发送")
        }
        if (disclosure.sensitiveHitSnippets.isNotEmpty()) {
            add("命中片段：${disclosure.sensitiveHitSnippets.joinToString("、")}")
        }
    }
}

@Composable
private fun ActionDraftSheet(
    confirmation: PendingAgentConfirmation,
    grantedSpecialAccessIds: Set<String>,
    onOpenSpecialAccessSettings: (SpecialAccessRequirement) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = confirmation.draft
    val runtimePermissionRequirements = confirmation.runtimePermissionRequirementsFor()
    val specialAccessRequirements = confirmation.specialAccessRequirementsFor()
    val missingSpecialAccessRequirements = specialAccessRequirements
        .filterNot { requirement -> requirement.id in grantedSpecialAccessIds }
    TrustSheetSurface(
        accentColor = LocalSolinColors.current.busy,
    ) {
        SectionTitle(
            text = draft.title,
            subtitle = "动作只会在你确认后读取上下文、创建草稿或调起系统能力。",
        )
        TrustSheetGroup {
            Text(
                text = "参数只用于本次确认动作。链接优先显示域名，长文本会折叠显示长度。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExpandableActionText(
                text = draft.summary,
                collapsedMaxChars = ACTION_SUMMARY_COLLAPSE_CHARS,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                testTag = "action_summary_text",
            )
            ActionDataBoundary(functionName = draft.functionName)
        }
        if (draft.parameters.isNotEmpty()) {
            TrustSheetGroup(
                modifier = Modifier.testTag("action_parameters"),
            ) {
                draft.parameters.forEach { (key, value) ->
                    ActionParameterRows(key = key, value = value)
                }
            }
        }
        if (runtimePermissionRequirements.isNotEmpty()) {
            TrustSheetGroup(
                modifier = Modifier.testTag("runtime_permission_requirements"),
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
        if (missingSpecialAccessRequirements.isNotEmpty()) {
            TrustSheetGroup(
                modifier = Modifier.testTag("special_access_requirements"),
            ) {
                Text(
                    text = "可能需要系统特殊授权",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                missingSpecialAccessRequirements.forEach { requirement ->
                    Text(
                        text = "${requirement.title}：${requirement.rationale}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_special_access_${requirement.id}"),
                        onClick = { onOpenSpecialAccessSettings(requirement) },
                    ) {
                        SolinGlyph(
                            kind = SolinGlyphKind.Check,
                            modifier = Modifier.size(18.dp),
                            tint = LocalContentColor.current,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("打开系统设置")
                    }
                }
            }
        }
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("action_confirm_button"),
            onClick = {
                missingSpecialAccessRequirements.firstOrNull()
                    ?.let(onOpenSpecialAccessSettings)
                    ?: onConfirm()
            },
        ) {
            Text(if (missingSpecialAccessRequirements.isEmpty()) "确认执行" else "打开系统设置")
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
private fun ActionDataBoundary(functionName: String) {
    val rows = remember(functionName) { actionDataBoundaryDisplayRows(functionName) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("action_data_boundary"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "数据去向",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        rows.forEach { row ->
            Text(
                text = row,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionParameterRows(
    key: String,
    value: String,
) {
    val rows = remember(key, value) { actionParameterDisplayRows(key, value) }
    rows.forEachIndexed { index, row ->
        ExpandableActionText(
            label = row.label,
            text = row.value,
            collapsedMaxChars = if (row.preferCompact) {
                ACTION_PARAMETER_COMPACT_CHARS
            } else {
                ACTION_PARAMETER_COLLAPSE_CHARS
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            testTag = "action_parameter_${key.safeTestTagPart()}_$index",
        )
    }
}

@Composable
private fun ExpandableActionText(
    text: String,
    collapsedMaxChars: Int,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    testTag: String,
    label: String? = null,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val display = remember(text, collapsedMaxChars, expanded) {
        actionTextDisplay(
            text = text,
            collapsedMaxChars = collapsedMaxChars,
            expanded = expanded,
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            modifier = Modifier.testTag(testTag),
            text = display.text,
            style = style,
            color = color,
        )
        if (display.canToggle) {
            TextButton(
                modifier = Modifier.testTag("${testTag}_toggle"),
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Text(
                    text = if (expanded) {
                        "收起"
                    } else {
                        "显示全部（${display.totalChars} 字）"
                    },
                )
            }
        }
    }
}

@Composable
private fun ExternalOutcomeSheet(
    pending: PendingExternalOutcomeConfirmation,
    onRecord: (AgentExternalOutcome) -> Unit,
) {
    TrustSheetSurface(
        modifier = Modifier.testTag("external_outcome_sheet"),
        accentColor = LocalSolinColors.current.busy,
    ) {
        SectionTitle(
            text = "外部操作完成了吗？",
            subtitle = pending.title,
        )
        TrustSheetGroup {
            Text(
                text = pending.summary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("external_outcome_completed_button"),
            onClick = { onRecord(AgentExternalOutcome.Completed) },
        ) {
            SolinGlyph(
                kind = SolinGlyphKind.Check,
                modifier = Modifier.size(18.dp),
                tint = LocalContentColor.current,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("已完成")
        }
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("external_outcome_not_completed_button"),
            onClick = { onRecord(AgentExternalOutcome.NotCompleted) },
        ) {
            SolinGlyph(
                kind = SolinGlyphKind.Close,
                modifier = Modifier.size(18.dp),
                tint = LocalContentColor.current,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("未完成")
        }
        TextButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("external_outcome_opened_only_button"),
            onClick = { onRecord(AgentExternalOutcome.OpenedOnly) },
        ) {
            Text("只是打开了")
        }
    }
}

@Composable
private fun PromptSuggestionList(
    enabled: Boolean,
    onSendPrompt: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PRODUCT_PROMPT_SUGGESTIONS.forEach { prompt ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.78f),
                enabled = enabled,
                onClick = { onSendPrompt(prompt) },
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, top = 9.dp, end = 8.dp, bottom = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        SolinGlyph(
                            kind = SolinGlyphKind.Spark,
                            modifier = Modifier
                                .padding(6.dp)
                                .size(15.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelManagerSheet(
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

private fun labelToTabTag(label: String): String =
    when (label) {
        "当前" -> "current"
        "模型" -> "models"
        "远程" -> "remote"
        "高级" -> "advanced"
        else -> "privacy"
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
private fun HuggingFaceAuthorizationPanel(
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
private fun EmptyPanelText(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)),
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
private fun ModelPathGuidance(
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
    val panelEdge = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = panelEdge,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
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
private fun RemoteModelPanel(
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

private fun RemoteModelConfig.hasAnySavedValue(): Boolean =
    baseUrl.isNotBlank() ||
        modelName.isNotBlank() ||
        apiKey.isNotBlank() ||
        supportsVisionInput ||
        connectivityStatus != RemoteModelConnectivityStatus.Unknown

private fun remoteConfigStatusText(config: RemoteModelConfig): String =
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
private fun TrustBoundaryPanel(
    state: ChatUiState,
    onRemoteSendDisclosurePolicySelected: (RemoteSendDisclosurePolicy) -> Unit,
    onReduceDeviceActionConfirmationsChanged: (Boolean) -> Unit,
) {
    val sensitiveDisclosureRows = sensitiveCapabilityDisclosureDisplayRows()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(
                    text = "为什么装它",
                    subtitle = PRODUCT_POSITIONING_TEXT,
                )
                TrustBoundaryRow(
                    icon = SolinGlyphKind.Shield,
                    title = "本地可用",
                    body = PRODUCT_LOCAL_VALUE_TEXT,
                )
                TrustBoundaryRow(
                    icon = SolinGlyphKind.Spark,
                    title = "远程多模态可选",
                    body = PRODUCT_REMOTE_VALUE_TEXT,
                )
                TrustBoundaryRow(
                    icon = SolinGlyphKind.Check,
                    title = "动作确认执行",
                    body = PRODUCT_ACTION_VALUE_TEXT,
                )
            }
        }
        PanelSurface {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(
                    text = "能力与信任中心",
                    subtitle = TRUST_CENTER_CAPABILITY_TEXT,
                )
                trustCenterCapabilityDisplayRows().forEach { row ->
                    TrustBoundaryRow(
                        icon = SolinGlyphKind.Shield,
                        title = row.title,
                        body = row.body,
                    )
                }
            }
        }
        PanelSurface {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(
                    text = "隐私说明",
                    subtitle = PRIVACY_POLICY_ENTRY_TEXT,
                )
                TrustBoundaryRow(
                    icon = SolinGlyphKind.Shield,
                    title = "本地优先",
                    body = TRUST_LOCAL_BOUNDARY_TEXT,
                )
                TrustBoundaryRow(
                    icon = SolinGlyphKind.Spark,
                    title = "远程模型",
                    body = TRUST_REMOTE_BOUNDARY_TEXT,
                )
                TrustBoundaryRow(
                    icon = SolinGlyphKind.Check,
                    title = "敏感权限",
                    body = TRUST_PERMISSION_BOUNDARY_TEXT,
                )
                TrustBoundaryRow(
                    icon = SolinGlyphKind.Delete,
                    title = "用户控制",
                    body = trustDeletionBoundaryText(state),
                )
            }
        }
        PanelSurface {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(
                    text = "远程模式提醒",
                    subtitle = "默认切换到远程模型时提醒一次；疑似敏感内容发送仍会单独确认。",
                )
                RemoteSendDisclosurePolicySelector(
                    selectedPolicy = state.remoteSendDisclosurePolicy,
                    enabled = !state.isBusy,
                    onSelected = onRemoteSendDisclosurePolicySelected,
                )
            }
        }
        PanelSurface {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(
                    modifier = Modifier.weight(1f),
                    text = "手机操作确认",
                    subtitle = "开启后，低风险系统页、应用导航和屏幕点击滚动会减少弹窗；发送、删除、支付、发布、敏感输入和权限授权仍会确认。",
                )
                Switch(
                    modifier = Modifier.testTag("reduce_device_action_confirmations_switch"),
                    checked = state.reduceDeviceActionConfirmations,
                    enabled = !state.isBusy,
                    onCheckedChange = onReduceDeviceActionConfirmationsChanged,
                )
            }
        }
        PanelSurface {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(
                    text = "远程发送记录",
                    subtitle = "只记录决策、模型和类别，不保存原始 prompt。",
                )
                RemoteSendAuditList(events = state.remoteSendAuditEvents)
            }
        }
        PanelSurface {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(
                    text = "敏感能力披露",
                    subtitle = SENSITIVE_CAPABILITY_DISCLOSURE_TEXT,
                )
                sensitiveDisclosureRows.forEach { row ->
                    TrustBoundaryRow(
                        icon = SolinGlyphKind.Shield,
                        title = row.title,
                        body = row.body,
                    )
                }
            }
        }
        PanelSurface {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(
                    text = "发布前仍需人工完成",
                    subtitle = "这些不是 App 代码能自动替代的事项。",
                )
                listOf(
                    "使用生产签名或 Play App Signing，不使用本地 debug keystore 发布。",
                    "准备公开隐私政策 URL，并与 Play Console Data safety 表单保持一致。",
                    "接入 crash/ANR 监控，并完成真机矩阵、无障碍和大字体验收。",
                ).forEach { item ->
                    Text(
                        text = "• $item",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteSendDisclosurePolicySelector(
    selectedPolicy: RemoteSendDisclosurePolicy,
    enabled: Boolean,
    onSelected: (RemoteSendDisclosurePolicy) -> Unit,
) {
    Column(
        modifier = Modifier.testTag("remote_send_disclosure_policy_selector"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RemoteSendDisclosurePolicy.values().forEach { policy ->
            FilterChip(
                modifier = Modifier.testTag("remote_send_policy_${policy.name}"),
                selected = selectedPolicy == policy,
                onClick = { onSelected(policy) },
                label = { Text(policy.remoteSendPolicyLabel()) },
                enabled = enabled,
            )
            if (selectedPolicy == policy) {
                Text(
                    text = policy.remoteSendPolicyDescription(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RemoteSendAuditList(events: List<RemoteSendAuditSummary>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote_send_audit_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (events.isEmpty()) {
            Text(
                text = "暂无远程发送记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val visibleEvents = events.take(12)
        visibleEvents.forEachIndexed { index, event ->
            RemoteSendAuditRow(event)
            if (index < visibleEvents.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun RemoteSendAuditRow(event: RemoteSendAuditSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote_send_audit_${event.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = event.decisionLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = event.remoteSendAuditTimeLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = listOfNotNull(event.modelName?.takeIf { it.isNotBlank() }, event.summary)
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun RemoteSendDisclosurePolicy.remoteSendPolicyLabel(): String =
    when (this) {
        RemoteSendDisclosurePolicy.OnRemoteModeSwitch -> "切换时提醒"
        RemoteSendDisclosurePolicy.EveryMessage -> "每次发送"
        RemoteSendDisclosurePolicy.OncePerSession -> "每会话一次"
        RemoteSendDisclosurePolicy.OnlyWhenSensitive -> "仅敏感内容"
    }

private fun RemoteSendDisclosurePolicy.remoteSendPolicyDescription(): String =
    when (this) {
        RemoteSendDisclosurePolicy.OnRemoteModeSwitch ->
            "切换到远程模型时弹一次提醒；普通文本随后不再逐条弹窗，图片仍每次确认。"
        RemoteSendDisclosurePolicy.EveryMessage -> "所有远程发送都会弹出确认。"
        RemoteSendDisclosurePolicy.OncePerSession -> "普通文本确认一次后本会话内静默，疑似敏感内容和图片仍会弹窗。"
        RemoteSendDisclosurePolicy.OnlyWhenSensitive -> "普通文本直接发送；疑似敏感内容和图片仍会强制确认。"
    }

private fun RemoteSendAuditSummary.remoteSendAuditTimeLabel(): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(createdAtMillis))

@Composable
private fun TrustBoundaryRow(
    icon: SolinGlyphKind,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SolinGlyph(
            modifier = Modifier.size(20.dp),
            kind = icon,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun trustDeletionBoundaryText(state: ChatUiState): String =
    "可清空长期记忆、删除当前会话、取消后台任务，并可一键清除远程服务地址、模型名和 API Key。当前已保存长期记忆 ${state.longTermMemories.size} 条。"

internal fun semanticMemoryIndexStatusText(state: ChatUiState): String {
    val mode = if (state.semanticMemoryRuntimeStatus == SemanticMemoryRuntimeStatus.Active) {
        "语义记忆"
    } else {
        "轻量索引"
    }
    val rebuilt = state.semanticMemoryLastRebuiltAtMillis
        ?.let { millis -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis)) }
        ?: "尚未建立"
    return "召回模式：$mode；索引：${state.semanticMemoryIndexedRecordCount} 条长期记忆；最近重建：$rebuilt"
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
                        text = when {
                            !state.memoryEnabled ->
                                "本地记忆已关闭；已有记录仍可查看和清除，不会参与召回，也不会自动发送到远程模型。"

                            state.semanticMemoryRuntimeStatus == SemanticMemoryRuntimeStatus.Active ->
                                "语义记忆运行时已启用；记忆仍只在本机检索，不会自动发送到远程模型。"

                            state.semanticMemoryRuntimeStatus == SemanticMemoryRuntimeStatus.BuildingIndex ->
                                "正在建立语义记忆索引；完成前仍可使用轻量索引。"

                            state.semanticMemoryRuntimeStatus == SemanticMemoryRuntimeStatus.ProbeFailed ||
                                state.semanticMemoryRuntimeStatus == SemanticMemoryRuntimeStatus.DegradedLexical ->
                                "记忆模型已安装但语义运行时未通过探测，已回退轻量索引。"

                            state.semanticMemoryRuntimeStatus == SemanticMemoryRuntimeStatus.RuntimeUnavailable &&
                                memoryModelInstalled ->
                                "记忆模型资产已安装；当前没有可用 embedding runtime，语义运行时未启用。"

                            else ->
                                "当前使用本地轻量索引；可补装记忆模型资产。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = semanticMemoryIndexStatusText(state),
                        style = MaterialTheme.typography.labelMedium,
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
                modifier = Modifier
                    .testTag("long_term_memory_forget_${memory.id}")
                    .semantics { contentDescription = "遗忘这条记忆：${memory.text}" },
                onClick = onForget,
                enabled = enabled,
            ) {
                SolinGlyph(
                    kind = SolinGlyphKind.Delete,
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun MemoryRecordType.label(): String =
    when (this) {
        MemoryRecordType.Preference -> "偏好"
        MemoryRecordType.UserFact -> "事实"
        MemoryRecordType.TaskState -> "任务状态"
        MemoryRecordType.SuppressedTaskState -> "已隐藏任务状态"
        MemoryRecordType.Conversation -> "会话"
    }

@Composable
private fun SessionManagerSheet(
    state: ChatUiState,
    onCreateSession: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: () -> Unit,
    onDismiss: () -> Unit,
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
                    .testTag("session_manager_title"),
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
            IconButton(
                modifier = Modifier
                    .testTag("session_manager_close_button")
                    .semantics { contentDescription = "关闭会话管理" },
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
        state.sessions.forEach { session ->
            ModelRow(
                title = session.title,
                subtitle = if (session.messageCount == 0) "空会话" else "${session.messageCount} 条消息",
                selected = session.id == state.activeSessionId,
                enabled = !state.isBusy,
                onClick = { onSessionSelected(session.id) },
            )
        }
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
                    onCancel = if (task.status == ScheduledTaskStatus.Scheduled) {
                        { onCancelBackgroundTask(task.id) }
                    } else {
                        null
                    },
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
            run.runDataReceipt?.let { receipt ->
                RunDataReceiptSummary(receipt)
            }
            run.steps
                .filter { step -> step.runDataReceipt == null }
                .takeLast(4)
                .forEach { step ->
                AgentTraceStepRow(step)
            }
        }
    }
}

@Composable
private fun AgentTraceStepRow(step: com.bytedance.zgx.solin.AgentTraceStepUiSummary) {
    val receipt = step.runDataReceipt
    if (receipt == null) {
        Text(
            text = "${step.type} · ${step.summary}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    RunDataReceiptSummary(receipt)
}

@Composable
private fun RunDataReceiptSummary(receipt: RunDataReceiptUiSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("run_data_receipt_summary"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "本次数据回执",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = runDataReceiptDisplayText(receipt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

internal const val REMOTE_ATTACHMENT_PROTECTION_NOTICE =
    "远程模式：图片发送前会确认；非图片附件、分享文本和 OCR 不会自动发送。"

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

internal const val ACTION_SUMMARY_COLLAPSE_CHARS = 160
internal const val ACTION_PARAMETER_COLLAPSE_CHARS = 120
internal const val ACTION_PARAMETER_COMPACT_CHARS = 80

internal data class ActionTextDisplay(
    val text: String,
    val totalChars: Int,
    val canToggle: Boolean,
)

internal data class ActionParameterDisplayRow(
    val label: String,
    val value: String,
    val preferCompact: Boolean = false,
)

internal fun actionTextDisplay(
    text: String,
    collapsedMaxChars: Int,
    expanded: Boolean,
): ActionTextDisplay {
    val normalized = text.trim()
    val canToggle = normalized.length > collapsedMaxChars
    return ActionTextDisplay(
        text = if (!canToggle || expanded) {
            normalized
        } else {
            normalized.take((collapsedMaxChars - 3).coerceAtLeast(1)).trimEnd() + "..."
        },
        totalChars = normalized.length,
        canToggle = canToggle,
    )
}

internal fun actionParameterDisplayRows(
    key: String,
    value: String,
): List<ActionParameterDisplayRow> {
    val trimmedValue = value.trim()
    val normalizedKey = key.trim()
    val keyForMatching = normalizedKey.lowercase(Locale.US)
    val domain = if (keyForMatching in linkParameterKeys) {
        actionLinkDomain(trimmedValue)
    } else {
        null
    }
    return when {
        domain != null -> listOf(
            ActionParameterDisplayRow(
                label = "链接域名",
                value = domain,
                preferCompact = true,
            ),
            ActionParameterDisplayRow(
                label = "完整链接",
                value = trimmedValue,
            ),
        )

        keyForMatching == "packagename" -> listOf(
            ActionParameterDisplayRow(
                label = "目标包",
                value = trimmedValue,
                preferCompact = true,
            ),
        )

        keyForMatching == "targetid" -> listOf(
            ActionParameterDisplayRow(
                label = "目标类型",
                value = trimmedValue,
                preferCompact = true,
            ),
        )

        else -> listOf(
            ActionParameterDisplayRow(
                label = normalizedKey.ifBlank { "参数" },
                value = trimmedValue,
            ),
        )
    }
}

internal fun actionDataBoundaryDisplayRows(functionName: String): List<String> =
    when (functionName) {
        MobileActionFunctions.COMPOSE_EMAIL,
        MobileActionFunctions.CREATE_CALENDAR_EVENT,
        MobileActionFunctions.CREATE_CONTACT_DRAFT,
        MobileActionFunctions.SHARE_TEXT,
        -> listOf(
            "确认后会把草稿或分享内容交给外部 App；目标 App 的后续处理由你继续确认。",
            "Solin 只能记录外部界面已打开，不能在未确认结果前宣称已完成。",
        )

        MobileActionFunctions.OPEN_WIFI_SETTINGS,
        MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
        MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
        MobileActionFunctions.OPEN_DEEP_LINK,
        MobileActionFunctions.OPEN_APP_BY_NAME,
        MobileActionFunctions.OPEN_APP_INTENT,
        MobileActionFunctions.OPEN_APP_DEEP_TARGET,
        MobileActionFunctions.SEARCH_MAPS,
        -> listOf(
            "确认后会打开系统设置、链接、地图或目标 App；不会自动完成外部页面里的操作。",
            "如果外部界面需要继续处理，返回后由你确认结果。",
        )

        MobileActionFunctions.QUERY_CONTACTS,
        MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
        MobileActionFunctions.QUERY_RECENT_FILES,
        MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
        MobileActionFunctions.READ_RECENT_IMAGE_OCR,
        MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
        MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
        MobileActionFunctions.QUERY_FOREGROUND_APP,
        MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
        MobileActionFunctions.READ_CLIPBOARD,
        -> listOf(
            "确认后只读取本次动作需要的本机内容或权限范围内摘要。",
            "读取结果默认仅留在本机，不会自动发送给远程模型。",
        )

        MobileActionFunctions.SCHEDULE_REMINDER,
        MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
        MobileActionFunctions.CANCEL_REMINDER,
        MobileActionFunctions.QUERY_BACKGROUND_TASKS,
        -> listOf(
            "确认后只在本机创建、修改、取消或查询提醒与后台任务。",
            "提醒正文和后台任务状态默认留在本机。",
        )

        else -> listOf(
            "确认后只按本次动作使用必要参数；取消不会执行。",
            "如需外部 App 或系统继续处理，会在结果页让你确认完成状态。",
        )
    }

internal fun actionLinkDomain(rawValue: String): String? {
    val host = runCatching { URI(rawValue.trim()).host }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return host.lowercase(Locale.US)
}

private val linkParameterKeys = setOf("uri", "url", "link")

private fun String.safeTestTagPart(): String =
    replace(Regex("""[^A-Za-z0-9_]+"""), "_")
        .trim('_')
        .ifBlank { "parameter" }

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
                    modifier = Modifier
                        .testTag("background_task_cancel_${task.id}")
                        .semantics { contentDescription = "取消后台任务" },
                    onClick = onCancel,
                    enabled = enabled,
                ) {
                    SolinGlyph(
                        kind = SolinGlyphKind.Close,
                        tint = LocalContentColor.current,
                        modifier = Modifier.size(20.dp),
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
        AgentRunState.AwaitingUserAnswer -> "待用户回答"
        AgentRunState.ExecutingTool -> "执行工具"
        AgentRunState.RetryingTool -> "重试工具"
        AgentRunState.Observing -> "观察结果"
        AgentRunState.GeneratingAnswer -> "生成回答"
        AgentRunState.AwaitingExternalOutcome -> "待确认外部结果"
        AgentRunState.Completed -> "已完成"
        AgentRunState.Cancelled -> "已取消"
        AgentRunState.Failed -> "失败"
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

@Composable
private fun RecommendedModelCard(
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

private fun RecommendedModel.requiresHuggingFaceAccessToken(): Boolean =
    requiresHuggingFaceAuthorization ||
        companionFiles.any { companion -> companion.requiresHuggingFaceAuthorization }

private fun memoryModelStatusText(
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
private fun ModelRow(
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
private fun CapabilityMark(
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

private fun capabilityIcon(capability: ModelCapability): SolinGlyphKind =
    when (capability) {
        ModelCapability.Chat -> SolinGlyphKind.Chat
        ModelCapability.MemoryEmbedding -> SolinGlyphKind.Memory
        ModelCapability.MobileAction -> SolinGlyphKind.Check
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

internal fun currentModelStatus(state: ChatUiState): String {
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
    return "$modelName · ${state.backend.name} · " +
        "${LocalModelTokenLimits.compactDisplayText(state.localMaxTotalTokens)} · $ready"
}

private fun compactModelStatus(state: ChatUiState): String {
    val ready = when {
        state.isDownloading -> state.downloadProgressPercent?.let { "下载 $it%" } ?: "下载中"
        state.isBusy -> state.statusText
        state.isReady -> "可用"
        state.inferenceMode == InferenceMode.Remote -> "待配置"
        state.modelPath != null -> "待加载"
        else -> "待准备"
    }
    if (state.inferenceMode == InferenceMode.Remote) {
        val modelName = state.remoteModelConfig.modelName.ifBlank { "远程模型" }
        return "$modelName · $ready"
    }
    val modelName = state.installedModels.firstOrNull { it.id == state.activeInstalledModelId }?.displayName
        ?: state.selectedRecommendedModel.shortName
    return "$modelName · ${state.backend.name} · $ready"
}

private fun compactModelStatusShort(state: ChatUiState): String =
    when {
        state.isDownloading -> state.downloadProgressPercent?.let { "下载 $it%" } ?: "下载中"
        state.isBusy -> "处理中"
        state.inferenceMode == InferenceMode.Remote && state.isReady -> "远程"
        state.inferenceMode == InferenceMode.Remote -> "待配置"
        state.isReady -> "本地"
        state.modelPath != null -> "待加载"
        else -> "待准备"
    }

private fun ChatUiState.backendChoiceEnabled(choice: BackendChoice): Boolean =
    localPreferredBackends.isEmpty() || choice in localPreferredBackends

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

private fun ModelHealthState.label(): String =
    when (this) {
        ModelHealthState.NotInstalled -> "未安装"
        ModelHealthState.InstalledUnverified -> "待校验"
        ModelHealthState.Verified -> "已校验"
        ModelHealthState.Loading -> "加载中"
        ModelHealthState.Loaded -> "已加载"
        ModelHealthState.LoadFailed -> "加载失败"
        ModelHealthState.FallbackActive -> "Fallback"
    }

@Composable
private fun LocalTokenLimitBlock(maxTotalTokens: Int) {
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
                    text = "建议至少预留 ${ModelCatalog.formatBytes(requiredBytes)}，再多留一些空间给加载缓存；也可以先配置远程模型，或导入你信任的更小 .litertlm。",
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
    evidence: List<MemoryEvidenceUiSummary>,
) {
    val semanticColors = LocalSolinColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = semanticColors.memoryContainer.copy(alpha = 0.72f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SolinGlyph(
                    modifier = Modifier.size(16.dp),
                    kind = SolinGlyphKind.Memory,
                    tint = semanticColors.onMemoryContainer,
                )
                Text(
                    text = "已引用本地记忆 ${evidence.size} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = semanticColors.onMemoryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            evidence.take(3).forEach { item ->
                Text(
                    text = memoryEvidenceDisplayText(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = semanticColors.onMemoryContainer.copy(alpha = 0.86f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SourcesStrip(
    modifier: Modifier = Modifier,
    rows: List<PublicWebSourceDisplayRow>,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SolinGlyph(
                modifier = Modifier.size(16.dp),
                kind = SolinGlyphKind.Spark,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "公开来源 ${rows.size} 条",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        rows.take(4).forEach { row ->
            SourceCard(row = row)
        }
    }
}

@Composable
private fun SourceCard(row: PublicWebSourceDisplayRow) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = publicWebSourceMetaText(row),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.snippet.isNotBlank()) {
                Text(
                    text = row.snippet,
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
private fun RunTimelineStrip(
    modifier: Modifier = Modifier,
    items: List<RunTimelineItemUiSummary>,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SolinGlyph(
                    modifier = Modifier.size(16.dp),
                    kind = SolinGlyphKind.Spark,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "当前进度",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items.takeLast(4).forEach { item ->
                Text(
                    text = runTimelineItemDisplayText(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun runTimelineItemDisplayText(item: RunTimelineItemUiSummary): String =
    listOf(item.label, item.detail, item.privacyLabel, item.riskLabel)
        .mapNotNull { value -> value?.safeStripDisplayText()?.takeIf { it.isNotBlank() } }
        .joinToString(" · ")

internal fun memoryEvidenceDisplayText(item: MemoryEvidenceUiSummary): String =
    listOf(item.typeLabel, item.recallLabel, item.reasonLabel, item.scoreLabel)
        .map { value -> value.safeStripDisplayText() }
        .filter { value -> value.isNotBlank() }
        .joinToString(" · ")

internal data class PublicWebSourceDisplayRow(
    val sourceId: String,
    val title: String,
    val source: String,
    val retrievedAt: String,
    val qualityLabel: String,
    val snippet: String,
)

internal fun publicWebEvidenceDisplayRows(packs: List<PublicWebEvidencePack>): List<PublicWebSourceDisplayRow> =
    packs
        .flatMap { pack ->
            val retrievedAt = pack.retrievedAt
            val packQuality = pack.quality
            pack.items.map { item ->
                val url = item.url
                PublicWebSourceDisplayRow(
                    sourceId = "",
                    title = item.title.safeStripDisplayText().ifBlank { "公开来源" },
                    source = item.sourceName.safeStripDisplayText()
                        .ifBlank { actionLinkDomain(url).orEmpty() }
                        .safeStripDisplayText()
                        .ifBlank { "公开网页" },
                    retrievedAt = retrievedAt.safeStripDisplayText(),
                    qualityLabel = item.qualityLabel
                        .ifBlank { packQuality }
                        .safeStripDisplayText(),
                    snippet = item.snippet.safeStripDisplayText(),
                )
            }
        }
        .distinctBy { row -> row.source to row.title }
        .take(8)
        .mapIndexed { index, row -> row.copy(sourceId = "S${index + 1}") }

internal fun publicWebSourceDisplayText(row: PublicWebSourceDisplayRow): String =
    listOf(row.title, publicWebSourceMetaText(row), row.snippet)
        .filter { value -> value.isNotBlank() }
        .joinToString("\n")

private fun publicWebSourceMetaText(row: PublicWebSourceDisplayRow): String =
    listOf(row.source, row.retrievedAt, row.qualityLabel)
        .filter { value -> value.isNotBlank() }
        .joinToString(" · ")

private fun String.safeStripDisplayText(): String {
    val normalized = trim().replace(Regex("\\s+"), " ")
    return if (PRIVATE_STRIP_TEXT_PATTERNS.any { pattern -> pattern.containsMatchIn(normalized) }) {
        "[redacted]"
    } else {
        normalized.take(80)
    }
}

private val PRIVATE_STRIP_TEXT_PATTERNS = listOf(
    Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
    Regex("(?i)\\b(password|passwd|secret|token|api[_ -]?key|credential|private)\\b\\s*[:=]?\\s*\\S*"),
    Regex("(?i)\\bresultsJson\\b"),
    Regex("^\\s*[\\[{].*[\\]}]\\s*$"),
)

@Composable
private fun RecoveryActionEntry(
    action: AgentRecoveryAction,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics {
                    contentDescription = action.draft.title
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SolinGlyph(
                modifier = Modifier.size(18.dp),
                kind = SolinGlyphKind.Undo,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = action.draft.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = action.draft.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "确认",
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f)
                },
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
    val semanticColors = LocalSolinColors.current
    val bubbleColor = if (isUser) {
        semanticColors.local
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.94f)
    }
    val textColor = if (isUser) {
        semanticColors.onLocal
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (isUser) {
        RoundedCornerShape(20.dp, 8.dp, 20.dp, 20.dp)
    } else {
        RoundedCornerShape(8.dp, 20.dp, 20.dp, 20.dp)
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
                BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f))
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f))
            },
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = when {
                        isUser -> "你"
                        isStreaming -> "Solin · 生成中"
                        else -> "Solin"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = if (isUser) 0.82f else 0.64f),
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
    buildList {
        stats.backend?.let { backend -> add(backend.label()) }
        stats.firstTokenMs?.let { firstTokenMs -> add("首 token ${firstTokenMs}ms") }
        stats.loadMs?.let { loadMs -> add("加载 ${loadMs}ms") }
        add("${stats.tokenCount} tokens")
        add("${String.format(Locale.US, "%.1f", stats.tokensPerSecond)} tokens/s")
    }.joinToString(separator = " · ")

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
    val semanticColors = LocalSolinColors.current
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
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
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
    onCancelVoiceInput: () -> Unit,
    onFinishVoiceInput: () -> Unit,
    onPickSharedAttachment: () -> Unit,
    onClearPendingSharedInput: (Long) -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
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
        if (state.inferenceMode == InferenceMode.Remote) {
            RemoteAttachmentProtectionNotice()
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
                        remoteMode = state.inferenceMode == InferenceMode.Remote,
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
                        remoteMode = state.inferenceMode == InferenceMode.Remote,
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
    remoteMode: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ComposerIconButton(
        modifier = Modifier
            .testTag("composer_attachment_button")
            .semantics {
                contentDescription = if (remoteMode) {
                    "选择附件；远程模式逐次确认后发送图片，其他附件不读取正文或 OCR"
                } else {
                    "选择附件"
                }
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
private fun RemoteAttachmentProtectionNotice() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote_attachment_protection_notice"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.24f),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            text = REMOTE_ATTACHMENT_PROTECTION_NOTICE,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
