package com.bytedance.zgx.solin

import android.Manifest
import android.app.DownloadManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionExecutor
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.audit.RemoteSendAuditEvent
import com.bytedance.zgx.solin.audit.RemoteSendAuditLog
import com.bytedance.zgx.solin.audit.RemoteSendAuditSink
import com.bytedance.zgx.solin.audit.RemoteSendDecision
import com.bytedance.zgx.solin.audit.ToolAuditLog
import com.bytedance.zgx.solin.background.BackgroundTaskScheduler
import com.bytedance.zgx.solin.background.BackgroundTaskUseCases
import com.bytedance.zgx.solin.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.solin.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.solin.background.ScheduledTask
import com.bytedance.zgx.solin.background.ScheduledTaskStatus
import com.bytedance.zgx.solin.background.isActivePeriodicCheck
import com.bytedance.zgx.solin.data.BundledModelInstaller
import com.bytedance.zgx.solin.data.FirstRunSetupRepository
import com.bytedance.zgx.solin.data.FirstRunSetupStore
import com.bytedance.zgx.solin.data.GenerationParametersStore
import com.bytedance.zgx.solin.data.HuggingFaceAuthStore
import com.bytedance.zgx.solin.data.ModelDownloadSource
import com.bytedance.zgx.solin.data.ModelRepository
import com.bytedance.zgx.solin.data.ModelRepositoryFacade
import com.bytedance.zgx.solin.data.ModelSelectionState
import com.bytedance.zgx.solin.data.ModelVerificationStatus
import com.bytedance.zgx.solin.data.RemoteModelRepository
import com.bytedance.zgx.solin.data.RemoteModelStore
import com.bytedance.zgx.solin.data.RemoteSendPendingStore
import com.bytedance.zgx.solin.data.SessionStore
import com.bytedance.zgx.solin.device.DeviceContextAuthorizationSnapshot
import com.bytedance.zgx.solin.device.DeviceContextSnapshot
import com.bytedance.zgx.solin.device.DeviceContextToolReadiness
import com.bytedance.zgx.solin.device.DeviceContextToolReadinessState
import com.bytedance.zgx.solin.download.ModelDownloadClient
import com.bytedance.zgx.solin.download.ModelDownloadService
import com.bytedance.zgx.solin.evidence.EvidenceCard
import com.bytedance.zgx.solin.evidence.EvidenceQuality
import com.bytedance.zgx.solin.evidence.EvidenceQualityLevel
import com.bytedance.zgx.solin.evidence.EvidenceReceiptSummary
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
import com.bytedance.zgx.solin.evidence.toEvidenceReceiptSummary
import com.bytedance.zgx.solin.memory.LongTermMemoryControls
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.memory.MemoryIndex
import com.bytedance.zgx.solin.memory.MemoryRecallMode
import com.bytedance.zgx.solin.memory.MemoryRecordType
import com.bytedance.zgx.solin.memory.TASK_STATE_MEMORY_RECORD_PREFIX
import com.bytedance.zgx.solin.memory.SemanticMemoryRuntimeController
import com.bytedance.zgx.solin.memory.SemanticMemoryRuntimeStatus
import com.bytedance.zgx.solin.memory.explicitUserFactFrom
import com.bytedance.zgx.solin.memory.explicitUserFactRecordId
import com.bytedance.zgx.solin.memory.explicitUserPreferenceFrom
import com.bytedance.zgx.solin.memory.explicitUserPreferenceForgetFrom
import com.bytedance.zgx.solin.memory.explicitUserPreferenceRecordId
import com.bytedance.zgx.solin.memory.taskStateMemoryRecordId
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrContract
import com.bytedance.zgx.solin.multimodal.SharedAttachment
import com.bytedance.zgx.solin.multimodal.SharedAttachmentKind
import com.bytedance.zgx.solin.multimodal.SharedInput
import com.bytedance.zgx.solin.multimodal.toSharedEvidenceReceiptSummary
import com.bytedance.zgx.solin.orchestration.AgentModelObservationResult
import com.bytedance.zgx.solin.orchestration.AgentExternalOutcome
import com.bytedance.zgx.solin.orchestration.AgentObservationDecision
import com.bytedance.zgx.solin.orchestration.AgentObservationResult
import com.bytedance.zgx.solin.orchestration.AgentPlan
import com.bytedance.zgx.solin.orchestration.AgentRecoveryAction
import com.bytedance.zgx.solin.orchestration.AgentRunOptions
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.AgentTraceRunSummary
import com.bytedance.zgx.solin.orchestration.AssistantOrchestrator
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.orchestration.AssistantRoute
import com.bytedance.zgx.solin.orchestration.InitialPlanningMode
import com.bytedance.zgx.solin.orchestration.ModelOutputQualityTrace
import com.bytedance.zgx.solin.orchestration.PendingExternalOutcomeSnapshot
import com.bytedance.zgx.solin.orchestration.RemoteToolScope
import com.bytedance.zgx.solin.orchestration.RunDataDestination
import com.bytedance.zgx.solin.orchestration.RunDataReceipt
import com.bytedance.zgx.solin.runtime.GenerationQualityDecision
import com.bytedance.zgx.solin.runtime.GenerationQualityIssue
import com.bytedance.zgx.solin.runtime.GenerationQualityReport
import com.bytedance.zgx.solin.runtime.GenerationRuntimeKind
import com.bytedance.zgx.solin.runtime.AdaptiveGenerationPolicy
import com.bytedance.zgx.solin.runtime.AdaptiveGenerationPolicyInput
import com.bytedance.zgx.solin.runtime.LocalModelRequest
import com.bytedance.zgx.solin.runtime.LocalModelRuntimeCapabilities
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import com.bytedance.zgx.solin.runtime.ModelOutputQualityGuard
import com.bytedance.zgx.solin.runtime.OkHttpRemoteModelConnectivityProbe
import com.bytedance.zgx.solin.runtime.RemoteChatEvent
import com.bytedance.zgx.solin.runtime.RemoteChatRuntime
import com.bytedance.zgx.solin.runtime.RemoteModelConnectivityProbe
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.tool.TimeoutToolExecutionBoundary
import com.bytedance.zgx.solin.tool.ToolExecutor
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.failed
import com.bytedance.zgx.solin.tool.isEligibleForParallelBatch
import com.bytedance.zgx.solin.tool.isUnverifiedExternalLaunch
import com.bytedance.zgx.solin.tool.unverifiedExternalLaunchSummary
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_VOICE_TRANSCRIPT_CHARS = 2_000
private const val VOICE_WAVEFORM_SAMPLE_COUNT = 9
private const val STALE_AGENT_RUN_STARTUP_REASON =
    "App restarted before this Agent step completed."
private const val USER_STOPPED_AGENT_RUN_REASON =
    "User stopped this Agent run."
private const val PUBLIC_EVIDENCE_BATCH_TOOL_NAME = "public_evidence_batch"
private const val REMOTE_SEND_PROMPT_PREVIEW_MAX_CHARS = 240
private const val REMOTE_SEND_RESTART_DISCARDED_TEXT =
    "上次远程发送确认因应用重启已失效，内容没有发送。请重新发起请求。"
private const val REMOTE_TOOL_CONTINUATION_RESTART_DISCARDED_TEXT =
    "上次远程工具结果续写确认因应用重启已失效，工具结果没有发送到远程模型。请重新发起请求。"
private const val REMOTE_COMPACTION_BUDGET_RATIO = 0.85
private val CONTEXT_OVERFLOW_MARKERS = listOf(
    "context_length",
    "context length",
    "maximum context",
    "prompt is too long",
    "prompt too long",
    "context window",
    "exceeded",
    "上下文过长",
    "context_overflow",
)
internal const val NO_MODEL_READY_STATUS_TEXT =
    "选择远程模型或下载本地模型后即可开始"
private val VOICE_WAVEFORM_MULTIPLIERS =
    listOf(0.42f, 0.74f, 1f, 0.56f, 0.88f, 0.63f, 0.95f, 0.5f, 0.8f)

private data class PendingRemoteContinuation(
    val runId: String?,
    val promptForModel: String,
    val responsePrivacy: MessagePrivacy,
    val remoteToolScope: RemoteToolScope,
)

private data class PendingSharedInputRemoteSendRestore(
    val draft: SharedInputDraft,
    val userInstruction: String,
    val combinedPrompt: String,
) {
    fun matches(pending: PendingRemoteSendDisclosure): Boolean =
        pending.kind == RemoteSendDisclosureKind.CurrentInput &&
            pending.prompt == combinedPrompt &&
            pending.imageAttachments == draft.imageAttachments
}

class SolinViewModel(
    private val modelRepository: ModelRepositoryFacade,
    private val sessionRepository: SessionStore,
    private val generationParametersRepository: GenerationParametersStore,
    private val remoteModelRepository: RemoteModelStore,
    private val huggingFaceAuthStore: HuggingFaceAuthStore,
    private val firstRunSetupRepository: FirstRunSetupStore,
    private val downloadService: ModelDownloadClient,
    private val runtime: LiteRtRuntime,
    private val remoteRuntime: RemoteChatRuntime,
    private val memoryRepository: MemoryIndex,
    private val longTermMemoryControls: LongTermMemoryControls,
    private val semanticMemoryRuntimeController: SemanticMemoryRuntimeController? =
        memoryRepository as? SemanticMemoryRuntimeController,
    private val backgroundTaskScheduler: BackgroundTaskScheduler,
    private val toolAuditLog: ToolAuditLog,
    private val actionExecutor: ToolExecutor,
    private val assistantOrchestrator: AssistantRouter,
    private val isArm64DeviceProvider: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requireRemoteSendDisclosure: Boolean = true,
    private val remoteConnectivityProbe: RemoteModelConnectivityProbe =
        OkHttpRemoteModelConnectivityProbe(ioDispatcher = ioDispatcher),
    private val outputQualityGuard: ModelOutputQualityGuard = ModelOutputQualityGuard(),
    private val remoteSendAuditSink: RemoteSendAuditSink,
    private val remoteSendAuditLog: RemoteSendAuditLog,
    private val remoteSendPendingStore: RemoteSendPendingStore,
    private val bundledModelInstaller: BundledModelInstaller,
    private val skipStartupModelRuntimeWork: Boolean = false,
) : ViewModel() {
    private val runtimeLock = Mutex()
    private var generationJob: Job? = null
    private var bundledModelInstallJob: Job? = null
    private var sessionRestoreJob: Job? = null
    private var sessionRestoreGeneration: Long = 0L
    private var activeGenerationRunId: String? = null
    private var downloadMonitorJob: Job? = null
    private var downloadPreflightJob: Job? = null
    private var activeDownloadId: Long? = null
    private val setupDownloadQueue = ArrayDeque<ModelDownloadSource>()
    private var setupDownloadInProgress = false
    private var startupRestored = false
    private var nextVoiceInputDraftId = 0L
    private var nextSharedInputDraftId = 0L
    private var pendingRemoteContinuation: PendingRemoteContinuation? = null
    private var pendingSharedInputRemoteSendRestore: PendingSharedInputRemoteSendRestore? = null
    private var remoteConnectivityProbeJob: Job? = null
    /**
     * Session-scoped suppression of the remote-send disclosure sheet. Set when the user picks
     * "don't ask again this session" on a quiet, non-sensitive confirmation. Reset whenever the
     * trust boundary changes: session switch, inference-mode change, or remote config change.
     * A sensitive payload always re-forces confirmation regardless of this flag (fail-closed).
     */
    private var remoteSendDisclosureSuppressedForSession: Boolean = false
    private var deviceContextAuthorizationSnapshot = DeviceContextAuthorizationSnapshot()
    private val toolRegistry = ToolRegistry()
    private val outboundSafetyPolicy = SafetyPolicy()
    private val toolExecutionBoundary = TimeoutToolExecutionBoundary(
        executor = actionExecutor,
        dispatcher = ioDispatcher,
        publicEvidenceBatchRequestValidator = toolRegistry::validatePublicEvidenceBatchRequest,
    )
    private val backgroundTaskUseCases = BackgroundTaskUseCases(backgroundTaskScheduler)

    private val _uiState = MutableStateFlow(createInitialState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun restoreStartupState(skipModelRuntimeWork: Boolean = false) {
        if (startupRestored) return
        startupRestored = true

        failClosedPendingRemoteSendOnStartup()
        recoverBackgroundTasksOnStartup()
        syncTaskStateMemories()
        refreshDeviceStatus()
        rebuildMemoryIndex()
        _uiState.update { it.copy(longTermMemories = loadLongTermMemories()) }
        verifyLegacyModelsOnStartup(skipModelRuntimeWork)
        failStaleAgentRunsOnStartup()
        if (startBundledModelInstallOnStartup(skipModelRuntimeWork)) {
            restorePendingAgentConfirmationIfAny()
            restorePendingExternalOutcomeIfAny()
            return
        }

        if (skipModelRuntimeWork) {
            if (_uiState.value.inferenceMode == InferenceMode.Remote) {
                updateRemoteReadiness("远程模型")
            } else if (_uiState.value.modelPath != null) {
                _uiState.update {
                    it.copy(statusText = "已找到模型，点击加载模型")
                }
            }
            restorePendingAgentConfirmationIfAny()
            restorePendingExternalOutcomeIfAny()
            return
        }

        val pendingDownloadId = modelRepository.pendingDownloadId()
        val pendingDownloadSource = modelRepository.loadPendingDownloadSource()
        if (pendingDownloadId > 0L) {
            val source = pendingDownloadSource ?: ModelDownloadSource.recommended(modelRepository.selectedRecommendedModel())
            source.modelId?.let {
                updateModelState(modelRepository.selectRecommendedModel(it).state)
            }
            val target = modelRepository.downloadedModelFile(source.fileName)
            if (target == null) {
                modelRepository.clearPendingDownload()
                _uiState.update {
                    it.copy(statusText = "下载目录不可用，请导入已有模型")
                }
            } else {
                monitorDownload(pendingDownloadId, target, source)
            }
        } else if (_uiState.value.inferenceMode == InferenceMode.Remote) {
            updateRemoteReadiness("远程模型")
        } else if (!isArm64Device()) {
            _uiState.update {
                it.copy(statusText = "当前设备不是 64 位 ARM，无法运行此模型")
            }
        } else {
            val activePath = modelRepository.currentState().activeModelPath
            if (activePath != null) {
                _uiState.update {
                    it.copy(statusText = "已找到模型，正在加载")
                }
                loadModel()
            }
        }
        restorePendingAgentConfirmationIfAny()
        restorePendingExternalOutcomeIfAny()
    }

    private fun startBundledModelInstallOnStartup(skipModelRuntimeWork: Boolean): Boolean {
        if (skipModelRuntimeWork || !bundledModelInstaller.isEnabled || bundledModelInstallJob != null) {
            return false
        }
        bundledModelInstallJob = viewModelScope.launch(ioDispatcher) {
            _uiState.update {
                it.copy(
                    showFirstRunSetup = false,
                    statusText = "正在准备内置模型",
                )
            }
            val result = runCatching { bundledModelInstaller.install() }
                .getOrElse { error ->
                    _uiState.update {
                        it.copy(statusText = "内置模型准备失败：${error.cleanMessage()}")
                    }
                    return@launch
                }
            if (!result.available) {
                return@launch
            }

            if (result.installedModelCount > 0 || modelRepository.currentState().activeModelPath != null) {
                firstRunSetupRepository.markSetupDismissed()
            }
            val modelState = modelRepository.currentState()
            updateModelState(modelState)
            syncSemanticMemoryRuntime()
            _uiState.update {
                it.copy(
                    showFirstRunSetup = false,
                    semanticMemoryEnabled = currentSemanticMemoryEnabled(),
                    semanticMemoryRuntimeStatus = currentSemanticMemoryRuntimeStatus(),
                    semanticMemoryIndexedRecordCount = currentSemanticMemoryIndexedRecordCount(),
                    semanticMemoryLastRebuiltAtMillis = currentSemanticMemoryLastRebuiltAtMillis(),
                    statusText = when {
                        result.hasFailures ->
                            "内置模型部分准备失败：${result.failedModelIds.joinToString()}"
                        modelState.activeModelPath != null ->
                            "内置模型已准备好，正在加载"
                        else ->
                            "内置模型已准备好"
                    },
                )
            }
            if (
                _uiState.value.inferenceMode == InferenceMode.Local &&
                modelState.activeModelPath != null &&
                !skipStartupModelRuntimeWork
            ) {
                loadModel()
            }
        }
        return true
    }

    fun configureDebugRemoteModelForScreenshotEvidence(
        baseUrl: String,
        modelName: String,
        supportsVisionInput: Boolean = false,
    ) {
        val config = RemoteModelConfig(
            baseUrl = baseUrl,
            modelName = modelName,
            apiKey = "",
            supportsVisionInput = supportsVisionInput,
        ).normalized()
        if (!config.isConfigured) {
            _uiState.update { it.copy(statusText = "截图验证远程配置无效") }
            return
        }
        remoteModelRepository.saveConfigWithoutApiKey(config)
            .fold(
                onSuccess = { normalized ->
                    firstRunSetupRepository.markSetupDismissed()
                    remoteModelRepository.saveMode(InferenceMode.Remote)
                    _uiState.update {
                        it.copy(
                            remoteModelConfig = normalized,
                            inferenceMode = InferenceMode.Remote,
                            showFirstRunSetup = false,
                        )
                    }
                    updateRemoteReadiness("远程模型")
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(statusText = "截图验证远程配置保存失败：${throwable.cleanMessage()}")
                    }
                },
            )
    }

    private fun recoverBackgroundTasksOnStartup() {
        backgroundTaskScheduler.rescheduleScheduledReminders()
        backgroundTaskScheduler.reconcilePeriodicCheckOnStartup()
        _uiState.update {
            it.copy(
                backgroundTasks = loadBackgroundTasks(),
                backgroundTaskHistory = loadBackgroundTaskHistory(),
                periodicCheckPolicy = loadPeriodicCheckPolicy(),
            )
        }
    }

    fun startModelDownload() {
        beginRecommendedModelDownload(modelRepository.selectedRecommendedModel())
    }

    fun startRecommendedModelDownload(modelId: String) {
        val model = ModelCatalog.recommendedModelById(modelId)
        if (model.capability == ModelCapability.Chat) {
            val result = modelRepository.selectRecommendedModel(model.id)
            updateModelState(result.state)
        }
        beginRecommendedModelDownload(model)
    }

    fun saveHuggingFaceAccessToken(token: String) {
        huggingFaceAuthStore.saveAccessToken(token)
            .onSuccess {
                _uiState.update {
                    it.copy(
                        huggingFaceAccessTokenConfigured = huggingFaceAuthStore.hasAccessToken(),
                        pendingHuggingFaceAuthorizationModelId = null,
                        statusText = "Hugging Face 授权已保存，可以下载原始记忆模型",
                    )
                }
            }
            .onFailure { throwable ->
                _uiState.update {
                    it.copy(statusText = "Hugging Face 授权保存失败：${throwable.cleanMessage()}")
                }
            }
    }

    fun clearHuggingFaceAccessToken() {
        huggingFaceAuthStore.clearAccessToken()
            .onSuccess {
                _uiState.update {
                    it.copy(
                        huggingFaceAccessTokenConfigured = false,
                        statusText = "Hugging Face 授权已清除",
                    )
                }
            }
            .onFailure { throwable ->
                _uiState.update {
                    it.copy(statusText = "Hugging Face 授权清除失败：${throwable.cleanMessage()}")
                }
            }
    }

    fun toggleSetupModel(modelId: String, selected: Boolean) {
        if (_uiState.value.isBusy) return
        _uiState.update { state ->
            val next = if (selected) {
                state.setupSelectedModelIds + modelId
            } else {
                state.setupSelectedModelIds - modelId
            }
            state.copy(setupSelectedModelIds = next)
        }
    }

    fun startSetupModelDownload() {
        if (_uiState.value.isBusy || _uiState.value.isDownloading) return
        val selectedModels = _uiState.value.basicSetupModels
            .filter { it.id in _uiState.value.setupSelectedModelIds && !_uiState.value.isModelInstalled(it.id) }
        if (selectedModels.isEmpty()) {
            firstRunSetupRepository.markSetupDismissed()
            _uiState.update {
                it.copy(
                    showFirstRunSetup = false,
                    statusText = if (it.modelPath == null) "可稍后在模型管理安装模型" else it.statusText,
                )
            }
            return
        }
        val selectedSources = selectedModels.flatMap { ModelDownloadSource.recommendedBundle(it) }
        selectedSources.firstOrNull { source ->
            source.requiresHuggingFaceAuthorization && !huggingFaceAuthStore.hasAccessToken()
        }?.let { missingAuthSource ->
            blockDownloadForMissingHuggingFaceAuthorization(missingAuthSource)
            return
        }
        setupDownloadQueue.clear()
        selectedSources.drop(1).forEach(setupDownloadQueue::add)
        setupDownloadInProgress = true
        if (!beginModelDownload(selectedSources.first())) {
            setupDownloadQueue.clear()
            setupDownloadInProgress = false
        }
    }

    private fun beginRecommendedModelDownload(model: RecommendedModel): Boolean {
        val sources = ModelDownloadSource.recommendedBundle(model)
        setupDownloadQueue.clear()
        sources.drop(1).forEach(setupDownloadQueue::add)
        return beginModelDownload(sources.first())
    }

    fun skipFirstRunSetup() {
        if (_uiState.value.isBusy) return
        firstRunSetupRepository.markSetupDismissed()
        _uiState.update {
            it.copy(
                showFirstRunSetup = false,
                statusText = if (it.modelPath == null) "已跳过模型准备，可稍后在模型管理安装" else it.statusText,
            )
        }
    }

    fun updateMemoryEnabled(enabled: Boolean) {
        firstRunSetupRepository.setMemoryEnabled(enabled)
        memoryRepository.enabled = enabled
        syncTaskStateMemories(memoryEnabled = enabled)
        _uiState.update {
            it.copy(
                memoryEnabled = enabled,
                memoryHits = if (enabled) it.memoryHits else emptyList(),
                longTermMemories = loadLongTermMemories(),
                statusText = if (enabled) "本地记忆已开启" else "本地记忆已关闭",
            )
        }
        rebuildMemoryIndex()
    }

    fun updateReduceDeviceActionConfirmations(enabled: Boolean) {
        firstRunSetupRepository.setReduceDeviceActionConfirmations(enabled)
        _uiState.update {
            it.copy(
                reduceDeviceActionConfirmations = enabled,
                statusText = if (enabled) {
                    "低风险手机操作将减少确认"
                } else {
                    "手机操作确认已恢复为保守模式"
                },
            )
        }
    }

    fun forgetLongTermMemory(memoryId: String) {
        if (_uiState.value.isBusy) return
        val removed = longTermMemoryControls.forget(memoryId)
        if (memoryId.startsWith(TASK_STATE_MEMORY_RECORD_PREFIX)) {
            longTermMemoryControls.suppressAutoManagedTaskState(memoryId)
        }
        rebuildMemoryIndex()
        val records = loadLongTermMemories()
        _uiState.update {
            it.copy(
                longTermMemories = records,
                memoryHits = it.memoryHits.filterNot { hit -> hit.id == memoryId },
                statusText = if (removed) "已遗忘这条记忆" else "未找到这条记忆",
            )
        }
    }

    fun clearLongTermMemory() {
        if (_uiState.value.isBusy) return
        val activeTaskMemoryIds = activeTaskStateMemoryIds()
        longTermMemoryControls.clear()
        activeTaskMemoryIds.forEach { memoryId ->
            longTermMemoryControls.suppressAutoManagedTaskState(memoryId)
        }
        rebuildMemoryIndex()
        _uiState.update {
            it.copy(
                longTermMemories = emptyList(),
                memoryHits = emptyList(),
                statusText = "长期记忆已清空",
            )
        }
    }

    fun refreshBackgroundTasks() {
        syncTaskStateMemories()
        val snapshot = backgroundTaskUseCases.snapshot()
        _uiState.update {
            it.copy(
                backgroundTasks = snapshot.activeTasks,
                backgroundTaskHistory = snapshot.history,
                periodicCheckPolicy = snapshot.periodicCheckPolicy,
                longTermMemories = loadLongTermMemories(),
            )
        }
    }

    fun updateDeviceContextAuthorizationSnapshot(snapshot: DeviceContextAuthorizationSnapshot) {
        deviceContextAuthorizationSnapshot = snapshot
        _uiState.update { it.copy(grantedSpecialAccessIds = snapshot.grantedSpecialAccessIds) }
    }

    fun cancelBackgroundTask(taskId: String) {
        if (_uiState.value.isBusy) return
        val result = backgroundTaskUseCases.cancelScheduledTask(taskId)
        syncTaskStateMemories()
        _uiState.update { state ->
            state.copy(
                backgroundTasks = result.snapshot.activeTasks,
                backgroundTaskHistory = result.snapshot.history,
                periodicCheckPolicy = result.snapshot.periodicCheckPolicy,
                longTermMemories = loadLongTermMemories(),
                statusText = result.statusText,
            )
        }
    }

    fun setPeriodicCheckPolicy(request: PeriodicCheckScheduleRequest) {
        if (_uiState.value.isBusy) return
        val previousPolicy = loadPeriodicCheckPolicy()
        val result = backgroundTaskUseCases.setPeriodicCheckPolicy(request)
        if (
            result.succeeded &&
            result.snapshot.periodicCheckPolicy.request.enabled &&
            !previousPolicy.isActivePeriodicCheck()
        ) {
            longTermMemoryControls.unsuppressAutoManagedTaskState(
                taskStateMemoryRecordId(PeriodicCheckScheduleRequest.TASK_ID),
            )
        }
        syncTaskStateMemories()
        _uiState.update { state ->
            state.copy(
                backgroundTasks = result.snapshot.activeTasks,
                backgroundTaskHistory = result.snapshot.history,
                periodicCheckPolicy = result.snapshot.periodicCheckPolicy,
                longTermMemories = loadLongTermMemories(),
                statusText = result.statusText,
            )
        }
    }

    fun disablePeriodicCheckPolicy() {
        if (_uiState.value.isBusy) return
        val result = backgroundTaskUseCases.disablePeriodicCheckPolicy()
        syncTaskStateMemories()
        _uiState.update { state ->
            state.copy(
                backgroundTasks = result.snapshot.activeTasks,
                backgroundTaskHistory = result.snapshot.history,
                periodicCheckPolicy = result.snapshot.periodicCheckPolicy,
                longTermMemories = loadLongTermMemories(),
                statusText = result.statusText,
            )
        }
    }

    fun refreshAuditEvents() {
        _uiState.update {
            it.copy(
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
            )
        }
    }

    fun acceptVoiceTranscript(transcript: String) {
        val cleaned = transcript
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(MAX_VOICE_TRANSCRIPT_CHARS)
        if (cleaned.isBlank()) {
            _uiState.update { it.copy(voiceCapture = VoiceCaptureUiState()) }
            return
        }
        _uiState.update {
            it.copy(
                voiceInputDraft = VoiceInputDraft(
                    id = ++nextVoiceInputDraftId,
                    text = cleaned,
                ),
                voiceCapture = VoiceCaptureUiState(),
                statusText = "语音已转写",
            )
        }
    }

    fun consumeVoiceInputDraft(draftId: Long) {
        _uiState.update {
            if (it.voiceInputDraft?.id == draftId) {
                it.copy(voiceInputDraft = null)
            } else {
                it
            }
        }
    }

    fun reportVoiceInputUnavailable(message: String = "语音输入不可用") {
        _uiState.update {
            it.copy(
                voiceCapture = VoiceCaptureUiState(),
                statusText = message,
            )
        }
    }

    fun startVoiceInputCapture() {
        _uiState.update {
            it.copy(
                voiceCapture = VoiceCaptureUiState(
                    isListening = true,
                    isTranscribing = false,
                    level = 0.18f,
                    waveformLevels = seedVoiceWaveformLevels(level = 0.18f),
                ),
                statusText = "正在收音",
            )
        }
    }

    fun updateVoiceInputLevel(rmsDb: Float) {
        val normalizedLevel = rmsDb.normalizedVoiceInputLevel()
        _uiState.update {
            if (!it.voiceCapture.isListening) {
                it
            } else {
                val nextFrame = it.voiceCapture.waveformFrame + 1
                it.copy(
                    voiceCapture = it.voiceCapture.copy(
                        level = normalizedLevel,
                        waveformFrame = nextFrame,
                        waveformLevels = it.voiceCapture.waveformLevels.nextVoiceWaveformLevels(
                            level = normalizedLevel,
                            frame = nextFrame,
                        ),
                    ),
                )
            }
        }
    }

    fun updateVoiceInputPartialTranscript(transcript: String) {
        val cleaned = transcript
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(MAX_VOICE_TRANSCRIPT_CHARS)
        _uiState.update {
            if (!it.voiceCapture.isActive) {
                it
            } else {
                it.copy(voiceCapture = it.voiceCapture.copy(partialText = cleaned))
            }
        }
    }

    fun finishVoiceInputCapture(message: String = "正在转写") {
        _uiState.update {
            if (!it.voiceCapture.isActive) {
                it
            } else {
                it.copy(
                    voiceCapture = it.voiceCapture.copy(
                        isListening = false,
                        isTranscribing = true,
                        level = 0.12f,
                    ),
                    statusText = message,
                )
            }
        }
    }

    fun reportSystemSettingsUnavailable(message: String = "系统设置不可用") {
        _uiState.update {
            it.copy(statusText = message)
        }
    }

    fun reportSpecialAccessResult(
        requirement: SpecialAccessRequirement,
        granted: Boolean,
    ) {
        _uiState.update {
            it.copy(
                statusText = if (granted) {
                    "${requirement.title}已开启"
                } else {
                    "返回后仍未开启${requirement.title}"
                },
            )
        }
    }

    fun startCustomModelDownload(downloadUrl: String) {
        val source = modelRepository.createCustomDownloadSource(downloadUrl)
        if (source == null) {
            _uiState.update {
                it.copy(statusText = "请输入有效的 HTTPS .litertlm 模型下载链接；HTTP 仅支持本地调试地址")
            }
            return
        }
        beginModelDownload(source)
    }

    fun cancelModelDownload() {
        val downloadId = activeDownloadId ?: modelRepository.pendingDownloadId()
        downloadPreflightJob?.cancel()
        downloadPreflightJob = null
        downloadMonitorJob?.cancel()
        downloadMonitorJob = null
        activeDownloadId = null
        setupDownloadQueue.clear()
        setupDownloadInProgress = false
        downloadService.cancelPreflight()
        downloadService.cancel(downloadId)
        modelRepository.clearPendingDownload()
        _uiState.update {
            it.copy(
                isBusy = false,
                isPreparingDownload = false,
                isDownloading = false,
                downloadProgressPercent = null,
                downloadedBytes = 0L,
                totalBytes = 0L,
                statusText = "下载已取消",
            )
        }
    }

    fun importModel(uri: Uri) {
        refreshDeviceStatus()
        if (_uiState.value.isBusy) return
        if (!isArm64Device()) {
            _uiState.update {
                it.copy(statusText = "当前设备不是 64 位 ARM，无法运行此模型")
            }
            return
        }

        _uiState.update {
            it.copy(
                isBusy = true,
                isReady = false,
                statusText = "正在导入模型",
            )
        }

        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) {
                    runtimeLock.withLock {
                        runtime.close()
                        modelRepository.importModel(uri) { progress ->
                            _uiState.update {
                                it.copy(
                                    downloadProgressPercent = progress.percent,
                                    downloadedBytes = progress.transferredBytes,
                                    totalBytes = progress.totalBytes,
                                    statusText = "正在导入模型",
                                )
                            }
                        }
                    }
                }
            }

            result.fold(
                onSuccess = { path ->
                    firstRunSetupRepository.markSetupDismissed()
                    updateModelState(modelRepository.currentState())
                    _uiState.update {
                        it.copy(
                            modelPath = path,
                            isBusy = false,
                            isDownloading = false,
                            downloadProgressPercent = null,
                            downloadedBytes = 0L,
                            totalBytes = 0L,
                            statusText = "模型已导入",
                            showFirstRunSetup = false,
                        )
                    }
                    loadModel()
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isDownloading = false,
                            downloadProgressPercent = null,
                            downloadedBytes = 0L,
                            totalBytes = 0L,
                            statusText = "导入失败：${throwable.cleanMessage()}",
                        )
                    }
                },
            )
        }
    }

    fun selectBackend(choice: BackendChoice) {
        if (_uiState.value.isBusy || _uiState.value.backend == choice) return
        if (!backendAllowedForActiveModel(_uiState.value, choice)) {
            _uiState.update {
                it.copy(statusText = "当前模型不支持 ${choice.label()}，请使用可用后端")
            }
            return
        }
        generationParametersRepository.saveBackend(choice)
        _uiState.update {
            it.copy(
                backend = choice,
                isReady = false,
                statusText = "已切换到 ${choice.label()}，点击加载模型",
            )
        }
    }

    fun updateGenerationParameters(parameters: GenerationParameters) {
        if (_uiState.value.isBusy) return
        val normalized = generationParametersRepository.save(parameters)
        _uiState.update {
            it.copy(
                generationParameters = normalized,
                statusText = if (runtime.isLoaded) "参数已更新，正在应用" else "参数已更新",
            )
        }
        if (runtime.isLoaded) {
            recreateConversationForActiveSession("参数已生效")
        }
    }

    fun resetGenerationParameters() {
        updateGenerationParameters(GenerationParameters())
    }

    fun selectInferenceMode(mode: InferenceMode) {
        if (_uiState.value.isBusy || _uiState.value.inferenceMode == mode) return
        pendingRemoteContinuation = null
        pendingSharedInputRemoteSendRestore = null
        remoteSendPendingStore.clearPendingRemoteSend()
        // Trust boundary changed (inference mode switch): a prior session-scoped
        // "don't ask again" must not silently carry into the new destination.
        resetRemoteSendDisclosureSuppression()
        remoteModelRepository.saveMode(mode)
        if (mode == InferenceMode.Remote) {
            runtime.close()
            _uiState.update { it.copy(pendingSharedInputDraft = null) }
            updateRemoteReadiness(
                prefix = "已切换到远程模型",
                showModeDisclosure = requireRemoteSendDisclosure,
            )
            return
        }

        _uiState.update {
            it.copy(
                inferenceMode = InferenceMode.Local,
                pendingSharedInputDraft = null,
                pendingRemoteModeDisclosure = null,
                pendingRemoteSendDisclosure = null,
                isReady = runtime.isLoaded,
                statusText = if (runtime.isLoaded) {
                    "就绪 · ${it.backend.label()}"
                } else if (it.modelPath != null) {
                    "已切换到本地模型，点击加载模型"
                } else {
                    "请先下载或导入本地模型"
                },
                modelHealth = ModelHealth(
                    profileId = it.activeModelProfileId(),
                    state = if (runtime.isLoaded) {
                        ModelHealthState.Loaded
                    } else if (it.modelPath != null) {
                        it.modelHealth.state.takeIf { state ->
                            state == ModelHealthState.Verified || state == ModelHealthState.InstalledUnverified
                        } ?: ModelHealthState.Verified
                    } else {
                        ModelHealthState.NotInstalled
                    },
                    backend = it.backend.takeIf { _ -> it.modelPath != null },
                ),
            )
        }
    }

    fun updateRemoteModelConfig(config: RemoteModelConfig) {
        if (_uiState.value.isBusy) return
        pendingRemoteContinuation = null
        pendingSharedInputRemoteSendRestore = null
        remoteSendPendingStore.clearPendingRemoteSend()
        remoteConnectivityProbeJob?.cancel()
        // Trust boundary changed (remote destination/credential): never let a prior
        // session-scoped "don't ask again" carry over to a new remote endpoint.
        resetRemoteSendDisclosureSuppression()
        val previousConfig = _uiState.value.remoteModelConfig
        val requestedConfig = config.normalized()
        val configToSave = requestedConfig.copy(
            connectivityStatus = if (requestedConfig.hasSameConnectivityTarget(previousConfig)) {
                requestedConfig.connectivityStatus
            } else {
                RemoteModelConnectivityStatus.Unknown
            },
        )
        remoteModelRepository.saveConfig(configToSave)
            .fold(
                onSuccess = { normalized ->
                    _uiState.update {
                        it.copy(
                            remoteModelConfig = normalized,
                            pendingRemoteModeDisclosure = null,
                            pendingRemoteSendDisclosure = null,
                            showFirstRunSetup = if (normalized.isConfigured) false else it.showFirstRunSetup,
                        )
                    }
                    if (normalized.isConfigured) {
                        firstRunSetupRepository.markSetupDismissed()
                    }
                    if (_uiState.value.inferenceMode == InferenceMode.Remote) {
                        updateRemoteReadiness("远程模型")
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            remoteModelConfig = configToSave,
                            statusText = "远程配置保存失败（已临时生效）：${throwable.cleanMessage()}",
                        )
                    }
                    if (_uiState.value.inferenceMode == InferenceMode.Remote) {
                        updateRemoteReadiness("远程模型")
                    }
                },
            )
    }

    fun testRemoteModelConnectivity() {
        if (_uiState.value.isBusy) return
        val config = _uiState.value.remoteModelConfig.normalized()
        if (!config.isConfigured) {
            _uiState.update {
                it.copy(
                    remoteModelConfig = config.copy(connectivityStatus = RemoteModelConnectivityStatus.Unknown),
                    statusText = "请先填写有效远程配置",
                )
            }
            return
        }
        remoteConnectivityProbeJob?.cancel()
        val checkingConfig = config.copy(connectivityStatus = RemoteModelConnectivityStatus.Checking)
        _uiState.update {
            it.copy(
                remoteModelConfig = checkingConfig,
                statusText = "正在测试远程连接",
            )
        }
        remoteConnectivityProbeJob = viewModelScope.launch(ioDispatcher) {
            val status = remoteConnectivityProbe.check(config)
            val currentConfig = _uiState.value.remoteModelConfig
            if (!currentConfig.hasSameConnectivityTarget(config)) return@launch
            val updatedConfig = currentConfig.copy(connectivityStatus = status)
            remoteModelRepository.saveConfig(updatedConfig)
                .fold(
                    onSuccess = { normalized ->
                        _uiState.update {
                            it.copy(
                                remoteModelConfig = normalized,
                                statusText = "远程连接${status.label}",
                            )
                        }
                    },
                    onFailure = { throwable ->
                        _uiState.update {
                            it.copy(statusText = "远程连接状态保存失败：${throwable.cleanMessage()}")
                        }
                    },
                )
        }
    }

    fun selectRecommendedModel(modelId: String) {
        if (_uiState.value.isBusy || _uiState.value.selectedModelId == modelId) return
        val result = modelRepository.selectRecommendedModel(modelId)
        updateModelState(result.state)
        val activated = result.activatedModel
        _uiState.update {
            it.copy(
                isReady = if (activated == null) it.isReady else false,
                statusText = activated
                    ?.let { model -> "已切换到 ${model.displayName}，点击加载模型" }
                    ?: "已选择 ${modelRepository.selectedRecommendedModel().shortName}",
            )
        }
        refreshDeviceStatus()
    }

    fun selectInstalledModel(modelId: String) {
        if (_uiState.value.isBusy || _uiState.value.activeInstalledModelId == modelId) return
        val installed = modelRepository.selectInstalledModel(modelId) ?: return
        remoteModelRepository.saveMode(InferenceMode.Local)
        pendingRemoteContinuation = null
        pendingSharedInputRemoteSendRestore = null
        remoteSendPendingStore.clearPendingRemoteSend()
        resetRemoteSendDisclosureSuppression()
        updateModelState(modelRepository.currentState())
        _uiState.update {
            it.copy(
                inferenceMode = InferenceMode.Local,
                pendingRemoteModeDisclosure = null,
                pendingRemoteSendDisclosure = null,
                isReady = false,
                statusText = "已切换到 ${installed.displayName}，点击加载模型",
            )
        }
    }

    fun deleteInstalledModel(modelId: String) {
        val stateBeforeDelete = _uiState.value
        if (stateBeforeDelete.isBusy || stateBeforeDelete.isDownloading || stateBeforeDelete.isGenerating) return
        val target = stateBeforeDelete.installedModels.firstOrNull { it.id == modelId } ?: return
        val wasActive = target.id == stateBeforeDelete.activeInstalledModelId
        _uiState.update {
            it.copy(
                isBusy = true,
                statusText = "正在删除 ${target.displayName}",
            )
        }
        viewModelScope.launch(ioDispatcher) {
            if (wasActive) {
                runtimeLock.withLock {
                    runtime.close()
                }
            }
            val deleted = modelRepository.deleteInstalledModel(modelId)
            if (deleted && target.capability == ModelCapability.MemoryEmbedding) {
                target.recommendedModelId?.let { memoryModelId ->
                    semanticMemoryRuntimeController?.clearSemanticMemoryForModel(memoryModelId)
                }
            }
            val modelState = modelRepository.currentState()
            updateModelState(modelState)
            _uiState.update { current ->
                if (deleted) {
                    val fallback = modelState.installedModels.firstOrNull {
                        it.id == modelState.activeInstalledModelId
                    }
                    current.copy(
                        isReady = if (wasActive) false else current.isReady,
                        isBusy = false,
                        isGenerating = false,
                        statusText = if (wasActive) {
                            fallback?.let { "已删除 ${target.displayName}，已切换到 ${it.displayName}，点击加载模型" }
                                ?: "已删除 ${target.displayName}，请下载或导入本地模型"
                        } else {
                            "已删除 ${target.displayName}"
                        },
                    )
                } else {
                    current.copy(
                        isBusy = false,
                        isReady = if (wasActive) false else current.isReady,
                        isGenerating = if (wasActive) false else current.isGenerating,
                        statusText = "删除 ${target.displayName} 失败",
                    )
                }
            }
            refreshDeviceStatus()
        }
    }

    fun loadModel() {
        val path = _uiState.value.modelPath ?: return
        if (_uiState.value.isBusy) return
        val backendChoice = preferredBackendForActiveModel(_uiState.value, _uiState.value.backend)
        remoteModelRepository.saveMode(InferenceMode.Local)
        pendingRemoteContinuation = null
        pendingSharedInputRemoteSendRestore = null
        remoteSendPendingStore.clearPendingRemoteSend()
        if (backendChoice != _uiState.value.backend) {
            generationParametersRepository.saveBackend(backendChoice)
        }

        _uiState.update {
            it.copy(
                inferenceMode = InferenceMode.Local,
                backend = backendChoice,
                isBusy = true,
                isDownloading = false,
                isReady = false,
                downloadProgressPercent = null,
                downloadedBytes = 0L,
                totalBytes = 0L,
                statusText = "正在初始化 ${backendChoice.label()}",
                modelHealth = ModelHealth(
                    profileId = it.activeModelProfileId(),
                    state = ModelHealthState.Loading,
                    backend = backendChoice,
                ),
            )
        }

        viewModelScope.launch(ioDispatcher) {
            val runtimeCapabilities = localModelRuntimeCapabilitiesFor(_uiState.value)
            val result = runCatching {
                runtimeLock.withLock {
                    runtime.configureModelCapabilities(runtimeCapabilities)
                    runtime.load(
                        modelPath = path,
                        backend = backendChoice,
                        history = sessionRepository.activeMessages(),
                        parameters = _uiState.value.generationParameters,
                    )
                }
            }

            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isDownloading = false,
                            isReady = true,
                            downloadProgressPercent = null,
                            downloadedBytes = 0L,
                            totalBytes = 0L,
                            statusText = "就绪 · ${it.backend.label()}",
                            modelHealth = ModelHealth(
                                profileId = it.activeModelProfileId(),
                                state = ModelHealthState.Loaded,
                                backend = backendChoice,
                            ),
                        )
                    }
                },
                onFailure = { throwable ->
                    var fallbackFailure: Throwable? = null
                    if (backendChoice == BackendChoice.GPU &&
                        backendAllowedForActiveModel(_uiState.value, BackendChoice.CPU)
                    ) {
                        val cpuResult = runCatching {
                            runtimeLock.withLock {
                                runtime.configureModelCapabilities(runtimeCapabilities)
                                runtime.load(
                                    modelPath = path,
                                    backend = BackendChoice.CPU,
                                    history = sessionRepository.activeMessages(),
                                    parameters = _uiState.value.generationParameters,
                                )
                            }
                        }
                        if (cpuResult.isSuccess) {
                            _uiState.update {
                                it.copy(
                                    backend = BackendChoice.CPU,
                                    isBusy = false,
                                    isDownloading = false,
                                    isReady = true,
                                    downloadProgressPercent = null,
                                    downloadedBytes = 0L,
                                    totalBytes = 0L,
                                    statusText = "GPU 不可用，已切到 CPU",
                                    modelHealth = ModelHealth(
                                        profileId = it.activeModelProfileId(),
                                        state = ModelHealthState.FallbackActive,
                                        backend = BackendChoice.CPU,
                                        fallbackBackend = BackendChoice.CPU,
                                        failureReason = "GPU 初始化失败：${throwable.cleanMessage()}",
                                    ),
                                )
                            }
                            return@launch
                        }
                        fallbackFailure = cpuResult.exceptionOrNull()
                    }
                    val failureReason = fallbackFailure?.let { cpuThrowable ->
                        "GPU: ${throwable.cleanMessage()}；CPU: ${cpuThrowable.cleanMessage()}"
                    } ?: throwable.cleanMessage()
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isDownloading = false,
                            isReady = false,
                            downloadProgressPercent = null,
                            downloadedBytes = 0L,
                            totalBytes = 0L,
                            statusText = "初始化失败：$failureReason",
                            modelHealth = ModelHealth(
                                profileId = it.activeModelProfileId(),
                                state = ModelHealthState.LoadFailed,
                                backend = backendChoice,
                                failureReason = failureReason,
                            ),
                        )
                    }
                },
            )
        }
    }

    fun resetConversation() {
        createNewSession()
    }

    fun createNewSession() {
        if (_uiState.value.isBusy) return
        pendingRemoteContinuation = null
        pendingSharedInputRemoteSendRestore = null
        remoteSendPendingStore.clearPendingRemoteSend()
        // New session is a fresh trust context; drop any session-scoped disclosure suppression.
        resetRemoteSendDisclosureSuppression()
        val messages = sessionRepository.createNewSession()
        val activeSessionId = sessionRepository.activeSessionId
        val restoreGeneration = nextSessionRestoreGeneration()
        _uiState.update {
            it.copy(
                sessions = sessionRepository.summaries(),
                activeSessionId = activeSessionId,
                messages = messages,
                pendingConfirmation = null,
                pendingRemoteSendDisclosure = null,
                pendingExternalOutcome = null,
                pendingSharedInputDraft = null,
                latestRecoveryAction = null,
                agentTraceRuns = loadAgentTraceRuns(),
                isReady = if (runtime.isLoaded) false else it.isReady,
                statusText = if (!runtime.isLoaded) "新会话" else "正在开启新会话",
            )
        }
        restorePendingAgentConfirmationIfAny(clearMissing = true)
        restorePendingExternalOutcomeIfAny(clearMissing = true)
        if (runtime.isLoaded) {
            recreateConversationForMessages(
                successPrefix = "新会话",
                sessionId = activeSessionId,
                messages = messages,
                restoreGeneration = restoreGeneration,
            )
        }
    }

    fun selectSession(sessionId: String) {
        if (_uiState.value.isBusy || _uiState.value.activeSessionId == sessionId) return
        pendingRemoteContinuation = null
        pendingSharedInputRemoteSendRestore = null
        remoteSendPendingStore.clearPendingRemoteSend()
        // Switching sessions is a trust-context change; drop session-scoped disclosure suppression.
        resetRemoteSendDisclosureSuppression()
        val messages = sessionRepository.selectSession(sessionId) ?: return
        val activeSessionId = sessionRepository.activeSessionId
        val restoreGeneration = nextSessionRestoreGeneration()
        _uiState.update {
            it.copy(
                activeSessionId = activeSessionId,
                messages = messages,
                pendingConfirmation = null,
                pendingRemoteSendDisclosure = null,
                pendingExternalOutcome = null,
                pendingSharedInputDraft = null,
                latestRecoveryAction = null,
                agentTraceRuns = loadAgentTraceRuns(),
                isReady = if (runtime.isLoaded) false else it.isReady,
                statusText = if (!runtime.isLoaded) "已切换会话" else "正在恢复会话",
            )
        }
        restorePendingAgentConfirmationIfAny(clearMissing = true)
        restorePendingExternalOutcomeIfAny(clearMissing = true)
        if (runtime.isLoaded) {
            recreateConversationForMessages(
                successPrefix = "已恢复会话",
                sessionId = activeSessionId,
                messages = messages,
                restoreGeneration = restoreGeneration,
            )
        }
    }

    fun deleteActiveSession() {
        if (_uiState.value.isBusy) return
        pendingRemoteContinuation = null
        pendingSharedInputRemoteSendRestore = null
        remoteSendPendingStore.clearPendingRemoteSend()
        // Deleting the current session changes the remote trust context even when a replacement
        // empty session is created immediately.
        resetRemoteSendDisclosureSuppression()
        val deletedSessionId = sessionRepository.activeSessionId
        val messages = sessionRepository.deleteActiveSession() ?: return
        val activeSessionId = sessionRepository.activeSessionId
        val restoreGeneration = nextSessionRestoreGeneration()
        assistantOrchestrator.deleteRunsForSession(deletedSessionId)
        _uiState.update {
            it.copy(
                sessions = sessionRepository.summaries(),
                activeSessionId = activeSessionId,
                messages = messages,
                pendingConfirmation = null,
                pendingRemoteSendDisclosure = null,
                pendingExternalOutcome = null,
                pendingSharedInputDraft = null,
                latestRecoveryAction = null,
                agentTraceRuns = loadAgentTraceRuns(),
                isReady = if (runtime.isLoaded) false else it.isReady,
                statusText = if (!runtime.isLoaded) "已删除会话" else "正在恢复会话",
            )
        }
        restorePendingAgentConfirmationIfAny(clearMissing = true)
        restorePendingExternalOutcomeIfAny(clearMissing = true)
        if (runtime.isLoaded) {
            recreateConversationForMessages(
                successPrefix = "已删除会话",
                sessionId = activeSessionId,
                messages = messages,
                restoreGeneration = restoreGeneration,
            )
        }
    }

    fun sendMessage(prompt: String) {
        sendMessageInternal(prompt = prompt, explicitMessagePrivacy = null)
    }

    fun sendMessage(prompt: String, messagePrivacy: MessagePrivacy) {
        sendMessageInternal(prompt = prompt, explicitMessagePrivacy = messagePrivacy)
    }

    private fun sendMessageInternal(
        prompt: String,
        explicitMessagePrivacy: MessagePrivacy?,
        imageAttachments: List<ChatImageAttachment> = emptyList(),
        localImageAttachments: List<LocalImageAttachment> = emptyList(),
        remoteSendConfirmed: Boolean = false,
        currentPromptEvidenceSummary: EvidenceReceiptSummary? = null,
    ) {
        val trimmed = prompt.trim()
        if (trimmed.isNotEmpty() && _uiState.value.pendingConfirmation != null) {
            _uiState.update {
                it.copy(statusText = "请先确认或取消待执行动作")
            }
            return
        }
        if (trimmed.isNotEmpty() &&
            _uiState.value.pendingRemoteModeDisclosure != null &&
            !remoteSendConfirmed
        ) {
            _uiState.update {
                it.copy(statusText = "请先确认远程模式提醒")
            }
            return
        }
        if (trimmed.isNotEmpty() &&
            _uiState.value.pendingRemoteSendDisclosure != null &&
            !remoteSendConfirmed
        ) {
            _uiState.update {
                it.copy(statusText = "请先确认或取消远程发送")
            }
            return
        }
        if (trimmed.isNotEmpty() && _uiState.value.pendingExternalOutcome != null) {
            _uiState.update {
                it.copy(statusText = "请先确认外部动作结果")
            }
            return
        }
        if (trimmed.isEmpty() || _uiState.value.isBusy || generationJob?.isActive == true) {
            return
        }
        if (explicitUserPreferenceForgetFrom(trimmed) != null) {
            handleExplicitMemoryForgetCommand(trimmed)
            return
        }
        if (explicitUserFactFrom(trimmed) != null) {
            handleExplicitUserFactCommand(trimmed)
            return
        }
        if (explicitUserPreferenceFrom(trimmed) != null) {
            handleExplicitPreferenceCommand(trimmed)
            return
        }
        if (!_uiState.value.isReady) {
            handleNotReadySendAttempt()
            return
        }

        syncTaskStateMemories()
        rebuildMemoryIndex()
        _uiState.update { it.copy(longTermMemories = loadLongTermMemories()) }
        val stateBeforeSend = _uiState.value
        val useRemoteModel = stateBeforeSend.inferenceMode == InferenceMode.Remote
        val effectiveMessagePrivacy =
            explicitMessagePrivacy ?: if (useRemoteModel) {
                MessagePrivacy.RemoteEligible
            } else {
                MessagePrivacy.LocalOnly
            }
        val remoteConfig = stateBeforeSend.remoteModelConfig
        var remoteHistory: List<ChatMessage> = remoteHistoryForRemoteSend(stateBeforeSend.messages)
        val localImageAttachmentCount = if (useRemoteModel) 0 else localImageAttachments.size
        if (!useRemoteModel &&
            localImageAttachments.isNotEmpty() &&
            !stateBeforeSend.activeLocalModelSupportsVisionInput
        ) {
            rejectUnsupportedLocalVisionInput()
            return
        }
        val includePrivateLocalContext = !useRemoteModel
        val agentRunOptions = if (useRemoteModel) {
            AgentRunOptions(
                initialPlanningMode = InitialPlanningMode.ModelFirstRemoteTools,
                remoteToolScope = RemoteToolScope.ModelPlanning,
                reduceDeviceActionConfirmations = stateBeforeSend.reduceDeviceActionConfirmations,
            )
        } else {
            AgentRunOptions(
                reduceDeviceActionConfirmations = stateBeforeSend.reduceDeviceActionConfirmations,
            )
        }
        if (useRemoteModel && effectiveMessagePrivacy == MessagePrivacy.LocalOnly) {
            val userMessage = ChatMessage(
                role = MessageRole.User,
                text = trimmed,
                privacy = MessagePrivacy.LocalOnly,
            )
            replaceActiveSessionMessages(
                stateBeforeSend.messages + userMessage + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "这条内容已标记为仅本地使用。当前为远程模型模式，我不会把它发送到远程模型。",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                persistNow = true,
            )
            persistExplicitPreferenceMemory(userMessage)
            rebuildMemoryIndex()
            _uiState.update {
                it.copy(statusText = "已保护本地内容")
            }
            return
        }
        if (useRemoteModel &&
            effectiveMessagePrivacy == MessagePrivacy.RemoteEligible &&
            remoteConfig.isConfigured &&
            remoteConfig.hasKnownConnectivityFailure
        ) {
            val userMessage = ChatMessage(
                role = MessageRole.User,
                text = trimmed,
                privacy = MessagePrivacy.RemoteEligible,
            )
            recordRemoteSendAuditEvent(
                decision = RemoteSendDecision.Blocked,
                modelName = remoteConfig.normalized().modelName,
                prompt = trimmed,
                imageCount = imageAttachments.size,
                remoteHistoryCount = remoteHistory.size,
            )
            replaceActiveSessionMessages(
                stateBeforeSend.messages + userMessage + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "远程连接状态为${remoteConfig.connectivityStatus.label}，本次没有发送。请在模型管理中测试连接或更新远程配置。",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                persistNow = true,
            )
            persistExplicitPreferenceMemory(userMessage)
            rebuildMemoryIndex()
            _uiState.update {
                it.copy(statusText = "远程连接不可用")
            }
            return
        }
        if (useRemoteModel &&
            effectiveMessagePrivacy == MessagePrivacy.RemoteEligible &&
            !remoteSendConfirmed &&
            remoteConfig.isConfigured &&
            outboundSafetyPolicy.containsSensitivePersonalOrSecretContent(trimmed)
        ) {
            // Tiered handling (P1): instead of hard-rejecting sensitive content, surface a
            // forced disclosure that offers graded choices — mask & send, send anyway
            // (audited), or cancel. This is always force-shown (fail-closed) and can never be
            // silenced by the session-suppression flag.
            val disclosure = buildSensitiveRemoteSendDisclosure(
                prompt = trimmed,
                messagePrivacy = effectiveMessagePrivacy,
                remoteConfig = remoteConfig,
                remoteHistory = remoteHistory,
                imageAttachments = imageAttachments,
                stateBeforeSend = stateBeforeSend,
            )
            savePendingRemoteSendMarker(disclosure)
            _uiState.update {
                it.copy(
                    pendingRemoteSendDisclosure = disclosure,
                    pendingExternalOutcome = null,
                    latestRecoveryAction = null,
                    statusText = "敏感内容待确认",
                )
            }
            return
        }
        if (useRemoteModel &&
            effectiveMessagePrivacy == MessagePrivacy.RemoteEligible &&
            !remoteConfig.isConfigured &&
            outboundSafetyPolicy.containsSensitivePersonalOrSecretContent(trimmed)
        ) {
            // Remote not configured: keep the original protect-and-explain behavior since there
            // is no destination to send to anyway.
            val userMessage = ChatMessage(
                role = MessageRole.User,
                text = trimmed,
                privacy = MessagePrivacy.LocalOnly,
            )
            replaceActiveSessionMessages(
                stateBeforeSend.messages + userMessage + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "这条内容疑似包含个人信息或密钥。当前为远程模型模式，我不会把它发送到远程模型；请切换到本地模型，或删去敏感内容后再发送。",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                persistNow = true,
            )
            rebuildMemoryIndex()
            _uiState.update {
                it.copy(statusText = "已保护敏感内容")
            }
            return
        }
        if (useRemoteModel &&
            effectiveMessagePrivacy == MessagePrivacy.RemoteEligible &&
            remoteConfig.isConfigured &&
            !remoteSendConfirmed &&
            shouldRequireRemoteSendDisclosure(imageAttachmentCount = imageAttachments.size)
        ) {
            val disclosure = buildPendingRemoteSendDisclosure(
                kind = RemoteSendDisclosureKind.CurrentInput,
                prompt = trimmed,
                messagePrivacy = effectiveMessagePrivacy,
                remoteConfig = remoteConfig,
                remoteHistory = remoteHistory,
                imageAttachments = imageAttachments,
                stateBeforeSend = stateBeforeSend,
            )
            savePendingRemoteSendMarker(disclosure)
            _uiState.update {
                it.copy(
                    pendingRemoteSendDisclosure = disclosure,
                    pendingExternalOutcome = null,
                    latestRecoveryAction = null,
                    statusText = "远程发送待确认",
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                isBusy = true,
                isGenerating = false,
                pendingRemoteSendDisclosure = null,
                latestRecoveryAction = null,
                pendingExternalOutcome = null,
                activeRunTimeline = emptyList(),
                activeMemoryEvidence = emptyList(),
                activePublicWebEvidence = emptyList(),
                statusText = "处理中",
            )
        }

        val job = viewModelScope.launch(ioDispatcher) {
            var activeModelRunId: String? = null
            try {
                val userMessage = ChatMessage(
                    role = MessageRole.User,
                    text = trimmed,
                    privacy = effectiveMessagePrivacy,
                )
                val route = runCatching {
                    assistantOrchestrator.route(
                        input = trimmed,
                        installedCapabilities = stateBeforeSend.installedCapabilities,
                        memoryEnabled = stateBeforeSend.memoryEnabled && includePrivateLocalContext,
                        actionModelPath = modelRepository.verifiedActionModelPath(),
                        deviceContext = stateBeforeSend.toDeviceContextSnapshot().takeIf { includePrivateLocalContext },
                        sessionId = stateBeforeSend.activeSessionId,
                        options = agentRunOptions,
                        installedCapabilityProfiles = stateBeforeSend.installedCapabilityProfiles,
                    )
                }.getOrElse { throwable ->
                    // If routing fails for any unanticipated reason (e.g. trace store
                    // error, skill planner exception, context assembler bug), fall back
                    // to a synthetic Chat route with the raw user input so the remote
                    // send can still proceed. This is critical for remote-mode tests
                    // where any routing exception would silently prevent the HTTP
                    // request from reaching the mock server.
                    android.util.Log.w(
                        "SolinViewModel",
                        "assistantOrchestrator.route failed, using fallback Chat route: ${throwable.message}",
                    )
                    AssistantRoute.Chat(
                        runId = null,
                        promptForModel = trimmed,
                        memoryHits = emptyList(),
                        deviceContext = null,
                    )
                }
                val routeReceipt = route.runDataReceipt(
                    stateBeforeSend = stateBeforeSend,
                    destination = if (useRemoteModel) RunDataDestination.Remote else RunDataDestination.Local,
                    currentPromptPrivacy = effectiveMessagePrivacy,
                    remoteHistoryCount = remoteHistory.size,
                    imageAttachmentCount = imageAttachments.size + localImageAttachmentCount,
                    currentPromptEvidenceSummary = currentPromptEvidenceSummary,
                )
                route.runIdOrNull()?.let { runId ->
                    assistantOrchestrator.recordRunDataReceipt(
                        runId = runId,
                        receipt = routeReceipt,
                    )
                }
                when (route) {
                    is AssistantRoute.Action -> {
                        val localUserMessage = userMessage.copy(privacy = MessagePrivacy.LocalOnly)
                        val planningLabel = if (route.plannedByModel) {
                            "动作规划模型"
                        } else {
                            "规则回退"
                        }
                        if (!route.requiresUserConfirmation) {
                            val request = route.toolRequest ?: ToolRequest(
                                toolName = route.draft.functionName,
                                arguments = route.draft.parameters,
                                reason = route.draft.summary,
                            )
                            val assistantMessage = ChatMessage(
                                role = MessageRole.Assistant,
                                text = "正在使用工具：${route.draft.title}",
                                privacy = MessagePrivacy.LocalOnly,
                            )
                            replaceActiveSessionMessages(
                                stateBeforeSend.messages + localUserMessage + assistantMessage,
                                persistNow = true,
                            )
                            persistExplicitPreferenceMemory(localUserMessage)
                            _uiState.update {
                                it.copy(
                                    isBusy = true,
                                    isGenerating = false,
                                    pendingConfirmation = null,
                                    memoryHits = emptyList(),
                                    activeRunTimeline = activeRunTimelineFor(route.runId),
                                    activeMemoryEvidence = emptyList(),
                                    statusText = "工具执行中",
                                )
                            }
                            executeToolRequestAfterRunIsExecuting(
                                confirmation = PendingAgentConfirmation(
                                    runId = route.runId,
                                    draft = route.draft,
                                    toolRequest = route.toolRequest,
                                    skillId = route.skillId,
                                    plannedByModel = route.plannedByModel,
                                    fallbackReason = route.fallbackReason,
                                ),
                                request = request,
                            )
                            rebuildMemoryIndex()
                            return@launch
                        }
                        val assistantMessage = ChatMessage(
                            role = MessageRole.Assistant,
                            text = "已准备本地动作草稿（$planningLabel）：${route.draft.summary}\n请确认后再执行。",
                            privacy = MessagePrivacy.LocalOnly,
                        )
                        replaceActiveSessionMessages(
                            stateBeforeSend.messages + localUserMessage + assistantMessage,
                            persistNow = true,
                        )
                        persistExplicitPreferenceMemory(localUserMessage)
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                isGenerating = false,
                                pendingConfirmation = PendingAgentConfirmation(
                                    runId = route.runId,
                                    draft = route.draft,
                                    toolRequest = route.toolRequest,
                                    skillId = route.skillId,
                                    plannedByModel = route.plannedByModel,
                                    fallbackReason = route.fallbackReason,
                                ),
                                memoryHits = emptyList(),
                                activeRunTimeline = activeRunTimelineFor(route.runId),
                                activeMemoryEvidence = emptyList(),
                                statusText = "动作草稿待确认 · $planningLabel",
                            )
                        }
                        rebuildMemoryIndex()
                        return@launch
                    }

                    is AssistantRoute.ToolRejected -> {
                        val localUserMessage = userMessage.copy(privacy = MessagePrivacy.LocalOnly)
                        val assistantMessage = ChatMessage(
                            role = MessageRole.Assistant,
                            text = "无法准备这个动作：${route.summary}",
                            privacy = MessagePrivacy.LocalOnly,
                        )
                        replaceActiveSessionMessages(
                            stateBeforeSend.messages + localUserMessage + assistantMessage,
                            persistNow = true,
                        )
                        persistExplicitPreferenceMemory(localUserMessage)
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                isGenerating = false,
                                memoryHits = emptyList(),
                                activeRunTimeline = emptyList(),
                                activeMemoryEvidence = emptyList(),
                                statusText = "动作不可执行",
                            )
                        }
                        rebuildMemoryIndex()
                        return@launch
                    }

                    is AssistantRoute.MissingModel -> {
                        val capabilityName = when (route.capability) {
                            ModelCapability.Chat -> "对话模型"
                            ModelCapability.MemoryEmbedding -> "记忆模型"
                            ModelCapability.MobileAction -> "动作规划模型"
                        }
                        val installHint = if (route.capability == ModelCapability.MobileAction) {
                            "请到模型管理安装本地 Chat 模型，或可选低资源动作模型。"
                        } else {
                            "请到模型管理安装基础能力包。"
                        }
                        val assistantMessage = ChatMessage(
                            role = MessageRole.Assistant,
                            text = "需要先安装$capabilityName，才能完成这个请求。$installHint",
                            privacy = effectiveMessagePrivacy,
                        )
                        replaceActiveSessionMessages(
                            stateBeforeSend.messages + userMessage + assistantMessage,
                            persistNow = true,
                        )
                        persistExplicitPreferenceMemory(userMessage)
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                isGenerating = false,
                                memoryHits = emptyList(),
                                activeRunTimeline = emptyList(),
                                activeMemoryEvidence = emptyList(),
                                statusText = "缺少$capabilityName",
                            )
                        }
                        rebuildMemoryIndex()
                        return@launch
                    }

                    is AssistantRoute.Chat -> {
                        activeModelRunId = route.runId
                        activeGenerationRunId = route.runId
                        val responsePrivacy = if (
                            !useRemoteModel &&
                            (effectiveMessagePrivacy == MessagePrivacy.LocalOnly || route.memoryHits.isNotEmpty())
                        ) {
                            MessagePrivacy.LocalOnly
                        } else {
                            effectiveMessagePrivacy
                        }
                        val chatUserMessage = if (responsePrivacy == MessagePrivacy.LocalOnly) {
                            userMessage.copy(privacy = MessagePrivacy.LocalOnly)
                        } else {
                            userMessage
                        }
                        val assistantPlaceholder = ChatMessage(
                            role = MessageRole.Assistant,
                            text = "",
                            privacy = responsePrivacy,
                        )
                        replaceActiveSessionMessages(
                            stateBeforeSend.messages + chatUserMessage + assistantPlaceholder,
                            persistNow = true,
                        )
                        persistExplicitPreferenceMemory(chatUserMessage)
                        _uiState.update {
                            it.copy(
                                isGenerating = true,
                                memoryHits = route.memoryHits,
                                activeRunTimeline = activeRunTimelineFor(route.runId),
                                activeMemoryEvidence = activeMemoryEvidenceFor(
                                    memoryHits = route.memoryHits,
                                    includePrivateLocalContext = includePrivateLocalContext,
                                ),
                                statusText = "生成中",
                            )
                        }
                        if (!useRemoteModel && !runtime.isLoaded) {
                            activeModelRunId?.let { runId ->
                                assistantOrchestrator.failModelGeneration(runId, "本地模型尚未就绪")
                            }
                            _uiState.updateLastAssistant("模型尚未就绪")
                            persistActiveSessionFromUi()
                            _uiState.update {
                                it.copy(
                                    isBusy = false,
                                    isGenerating = false,
                                    isReady = false,
                                    pendingConfirmation = null,
                                    agentTraceRuns = loadAgentTraceRuns(),
                                    activeRunTimeline = activeRunTimelineFor(route.runId),
                                    statusText = "未加载模型",
                                )
                            }
                            return@launch
                        }
                        if (useRemoteModel && !remoteConfig.isConfigured) {
                            activeModelRunId?.let { runId ->
                                assistantOrchestrator.failModelGeneration(runId, "远程模型未配置")
                            }
                            _uiState.updateLastAssistant("请先在模型管理中配置远程模型地址和模型名")
                            persistActiveSessionFromUi()
                            _uiState.update {
                                it.copy(
                                    isBusy = false,
                                    isGenerating = false,
                                    isReady = false,
                                    pendingConfirmation = null,
                                    agentTraceRuns = loadAgentTraceRuns(),
                                    activeRunTimeline = activeRunTimelineFor(route.runId),
                                    statusText = "请配置远程模型",
                                )
                            }
                            return@launch
                        }
                        if (useRemoteModel) {
                            val boundaryFailure = remoteRouteBoundaryFailure(
                                userInput = trimmed,
                                route = route,
                            )
                            if (boundaryFailure != null) {
                                activeModelRunId?.let { runId ->
                                    assistantOrchestrator.failModelGeneration(runId, boundaryFailure)
                                }
                                recordRemoteSendAuditEvent(
                                    decision = RemoteSendDecision.Blocked,
                                    modelName = remoteConfig.normalized().modelName,
                                    prompt = route.promptForModel,
                                    imageCount = imageAttachments.size,
                                    remoteHistoryCount = remoteHistory.size,
                                )
                                _uiState.updateLastAssistantLocalOnly(boundaryFailure)
                                persistActiveSessionFromUi()
                                _uiState.update {
                                    it.copy(
                                        isBusy = false,
                                        isGenerating = false,
                                        isReady = remoteConfig.isConfigured,
                                        pendingConfirmation = null,
                                        memoryHits = emptyList(),
                                        agentTraceRuns = loadAgentTraceRuns(),
                                        activeRunTimeline = activeRunTimelineFor(route.runId),
                                        activeMemoryEvidence = emptyList(),
                                        statusText = "已阻止远程发送",
                                    )
                                }
                                return@launch
                            }
                        }

                        val partial = StringBuilder()
                        var remoteToolObservation: AgentModelObservationResult? = null
                        var outputQualityDecision: GenerationQualityDecision? = null
                        if (useRemoteModel) {
                            val remoteTools = assistantOrchestrator.availableRemoteToolSpecs(agentRunOptions.remoteToolScope)
                            route.runId?.let { runId ->
                                assistantOrchestrator.recordRemoteToolsExposed(
                                    runId = runId,
                                    scope = agentRunOptions.remoteToolScope,
                                    toolNames = remoteTools.mapTo(linkedSetOf()) { tool -> tool.name },
                                )
                            }
                            if (!remoteSendConfirmed) {
                                recordRemoteSendAuditEvent(
                                    decision = RemoteSendDecision.Confirmed,
                                    modelName = remoteConfig.normalized().modelName,
                                    prompt = route.promptForModel,
                                    imageCount = imageAttachments.size,
                                    remoteHistoryCount = remoteHistory.size,
                                )
                            }
                            val remoteTokenBudget = remoteConfig.modelProfile().contextWindowTokens
                                ?.let { window -> (window * REMOTE_COMPACTION_BUDGET_RATIO).toInt() }
                                ?: Int.MAX_VALUE
                            val result = sendRemoteWithOverflowRetry(
                                runId = route.runId,
                                history = remoteHistory,
                                tokenBudget = remoteTokenBudget,
                            ) { historyToSend ->
                                remoteRuntime.sendWithTools(
                                    prompt = route.promptForModel,
                                    history = historyToSend,
                                    parameters = _uiState.value.generationParameters,
                                    config = remoteConfig,
                                    tools = remoteTools,
                                    imageAttachments = imageAttachments,
                                ).collect { event ->
                                    if (outputQualityDecision != null) return@collect
                                    when (event) {
                                        is RemoteChatEvent.TextDelta -> {
                                            if (remoteToolObservation == null) {
                                                val decision = appendGuardedGenerationChunk(
                                                    partial = partial,
                                                    chunk = event.text,
                                                    runtimeKind = GenerationRuntimeKind.Remote,
                                                    modelId = remoteConfig.modelProfile().id,
                                                    backend = null,
                                                    parameters = _uiState.value.generationParameters,
                                                )
                                                if (decision !is GenerationQualityDecision.Continue) {
                                                    outputQualityDecision = decision
                                                    remoteRuntime.stop()
                                                }
                                            }
                                        }

                                        is RemoteChatEvent.ToolCall -> {
                                            val runId = route.runId ?: error("远程工具调用缺少 Agent run")
                                            remoteToolObservation =
                                                assistantOrchestrator.observeModelToolRequest(runId, event.request)
                                                    ?: error("远程工具调用无法进入确认流程")
                                        }

                                        is RemoteChatEvent.ToolCalls -> {
                                            val runId = route.runId ?: error("远程工具调用缺少 Agent run")
                                            remoteToolObservation =
                                                assistantOrchestrator.observeModelToolRequests(runId, event.requests)
                                                    ?: error("远程批量工具调用无法进入执行流程")
                                        }

                                        is RemoteChatEvent.ParseError -> {
                                            val decision = outputQualityGuard.failClosedForFormatViolation(
                                                summary = event.summary,
                                                accumulatedText = partial.toString(),
                                                runtimeKind = GenerationRuntimeKind.Remote,
                                                modelId = remoteConfig.modelProfile().id,
                                                backend = null,
                                            )
                                            applyOutputQualityDecisionToAssistant(partial, decision)
                                            outputQualityDecision = decision
                                            remoteRuntime.stop()
                                        }
                                    }
                                }
                            }
                            remoteHistory = result.first
                        } else {
                            collectLocalRuntimeResponse(
                                promptForModel = route.promptForModel,
                                history = stateBeforeSend.messages,
                                parameters = _uiState.value.generationParameters,
                                imageAttachments = localImageAttachments,
                            ) { chunk ->
                                if (outputQualityDecision == null) {
                                    val decision = appendGuardedGenerationChunk(
                                        partial = partial,
                                        chunk = chunk,
                                        runtimeKind = GenerationRuntimeKind.Local,
                                        modelId = _uiState.value.activeModelProfileId(),
                                        backend = _uiState.value.backend,
                                        parameters = _uiState.value.generationParameters,
                                    )
                                    if (decision !is GenerationQualityDecision.Continue) {
                                        outputQualityDecision = decision
                                        runtime.stop()
                                    }
                                }
                            }
                        }
                        if (outputQualityDecision == null && remoteToolObservation == null) {
                            val finalDecision = outputQualityGuard.evaluateCompleted(
                                output = partial.toString(),
                                runtimeKind = if (useRemoteModel) GenerationRuntimeKind.Remote else GenerationRuntimeKind.Local,
                                modelId = if (useRemoteModel) {
                                    remoteConfig.modelProfile().id
                                } else {
                                    _uiState.value.activeModelProfileId()
                                },
                                backend = if (useRemoteModel) null else _uiState.value.backend,
                            )
                            if (finalDecision !is GenerationQualityDecision.Continue) {
                                applyOutputQualityDecisionToAssistant(partial, finalDecision)
                                outputQualityDecision = finalDecision
                            }
                        }
                        outputQualityDecision?.let { decision ->
                            finishOutputQualityGuardedGeneration(
                                runId = route.runId,
                                decision = decision,
                                receipt = routeReceipt,
                                useRemoteModel = useRemoteModel,
                            )
                            return@launch
                        }
                        if (remoteToolObservation != null) {
                            val toolBatch =
                                (remoteToolObservation?.decision as? AgentObservationDecision.PlanToolBatch)?.plans
                            if (toolBatch != null) {
                                val observedForBatch = remoteToolObservation ?: error("远程批量工具调用缺少 Agent observation")
                                val assistantText = buildString {
                                    if (partial.isNotBlank()) {
                                        append(partial)
                                        append("\n\n")
                                    }
                                    append("正在并行使用工具：${toolBatch.batchToolTitle()}")
                                }
                                _uiState.updateLastAssistantLocalOnly(assistantText)
                                persistActiveSessionFromUi()
                                executePublicEvidenceToolBatchAfterRunIsExecuting(
                                    runId = observedForBatch.run.id,
                                    plans = toolBatch,
                                )
                                return@launch
                            }
                            val nextToolPlan =
                                (remoteToolObservation?.decision as? AgentObservationDecision.PlanNextTool)?.plan
                            if (nextToolPlan != null) {
                                val observedForPlan = remoteToolObservation ?: error("远程工具调用缺少 Agent observation")
                                val assistantText = buildString {
                                    if (partial.isNotBlank()) {
                                        append(partial)
                                        append("\n\n")
                                    }
                                    if (nextToolPlan.requiresUserConfirmation()) {
                                        append("已准备远程动作草稿：${nextToolPlan.draft.title}\n请确认后再执行。")
                                    } else {
                                        append("正在使用工具：${nextToolPlan.draft.title}")
                                    }
                                }
                                _uiState.updateLastAssistantLocalOnly(assistantText)
                                persistActiveSessionFromUi()
                                handleNextToolPlan(
                                    runId = observedForPlan.run.id,
                                    plan = nextToolPlan,
                                    pendingStatusText = "动作草稿待确认 · 远程模型",
                                )
                                return@launch
                            }
                            _uiState.updateLastAssistantLocalOnly(
                                "无法准备这个动作：${(remoteToolObservation?.decision as? AgentObservationDecision.Fail)?.reason.orEmpty()}",
                            )
                            persistActiveSessionFromUi()
                            rebuildMemoryIndex()
                            _uiState.update {
                                it.copy(
                                    isBusy = false,
                                    isGenerating = false,
                                    isReady = true,
                                    auditEvents = loadAuditEvents(),
                                    agentTraceRuns = loadAgentTraceRuns(),
                                    activeRunTimeline = activeRunTimelineFor(route.runId),
                                    statusText = "动作不可执行",
                                )
                            }
                            return@launch
                        } else {
                            if (partial.isBlank()) {
                                _uiState.updateLastAssistant("没有生成内容")
                            } else if (!useRemoteModel) {
                                _uiState.updateLastAssistantStats(
                                    runCatching { runtime.lastGenerationStats() }.getOrNull(),
                                )
                            }
                            route.runId?.let { runId ->
                                val modelObservation = assistantOrchestrator.observeModelResult(
                                    runId = runId,
                                    text = partial.toString(),
                                    allowInlineToolCalls = !useRemoteModel,
                                )
                                val nextToolPlan =
                                    (modelObservation?.decision as? AgentObservationDecision.PlanNextTool)?.plan
                                if (nextToolPlan != null) {
                                    val assistantText = if (nextToolPlan.requiresUserConfirmation()) {
                                        "已准备模型动作草稿：${nextToolPlan.draft.title}\n请确认后再执行。"
                                    } else {
                                        "正在使用工具：${nextToolPlan.draft.title}"
                                    }
                                    _uiState.updateLastAssistantLocalOnly(assistantText)
                                    persistActiveSessionFromUi()
                                    handleNextToolPlan(
                                        runId = modelObservation.run.id,
                                        plan = nextToolPlan,
                                        pendingStatusText = "动作草稿待确认 · 本地模型",
                                    )
                                    return@launch
                                }
                                if (modelObservation?.decision is AgentObservationDecision.Fail) {
                                    _uiState.updateLastAssistantLocalOnly(
                                        "无法准备这个动作：${modelObservation.decision.reason}",
                                    )
                                    persistActiveSessionFromUi()
                                    rebuildMemoryIndex()
                                    _uiState.update {
                                        it.copy(
                                            isBusy = false,
                                            isGenerating = false,
                                            isReady = true,
                                            auditEvents = loadAuditEvents(),
                                            agentTraceRuns = loadAgentTraceRuns(),
                                            activeRunTimeline = activeRunTimelineFor(route.runId),
                                            statusText = "动作不可执行",
                                        )
                                    }
                                    return@launch
                                }
                                val observedForContinuation = modelObservation
                                val continuationPrompt = observedForContinuation?.continuationPromptForModel
                                if (observedForContinuation?.decision is AgentObservationDecision.ContinueWithModel &&
                                    continuationPrompt != null
                                ) {
                                    _uiState.updateLastAssistant("正在根据公开来源补充引用…")
                                    persistActiveSessionFromUi()
                                    continueAfterToolObservation(
                                        runId = observedForContinuation.run.id,
                                        promptForModel = continuationPrompt,
                                        responsePrivacy = MessagePrivacy.RemoteEligible,
                                        remoteToolScope = observedForContinuation.continuationRemoteToolScope,
                                    )
                                    return@launch
                                }
                            }
                        }
                        persistActiveSessionFromUi()
                        rebuildMemoryIndex()
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                isGenerating = false,
                                isReady = true,
                                activeRunTimeline = activeRunTimelineFor(route.runId),
                                activePublicWebEvidence = activePublicWebEvidenceFor(route.runId),
                                activeMemoryEvidence = activeMemoryEvidenceFor(
                                    memoryHits = route.memoryHits,
                                    includePrivateLocalContext = includePrivateLocalContext,
                                ),
                                statusText = if (useRemoteModel) {
                                    "就绪 · 远程"
                                } else {
                                    "就绪 · ${it.backend.label()}"
                                },
                            )
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                cancelActiveGenerationRun(activeModelRunId)
                if (_uiState.value.isGenerating) {
                    finishStoppedGeneration(activeModelRunId)
                } else if (_uiState.value.isBusy) {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isGenerating = false,
                            statusText = "已停止",
                        )
                    }
                }
                throw cancellation
            } catch (throwable: Throwable) {
                val errorMessage = throwable.generationFailureMessage(useRemoteModel)
                activeModelRunId?.let { runId ->
                    assistantOrchestrator.failModelGeneration(runId, errorMessage)
                }
                if (_uiState.value.isGenerating) {
                    _uiState.updateLastAssistant("出错了：$errorMessage")
                    persistActiveSessionFromUi()
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isGenerating = false,
                        isReady = useRemoteModel && remoteConfig.isConfigured,
                        pendingConfirmation = null,
                        agentTraceRuns = loadAgentTraceRuns(),
                        activeRunTimeline = activeRunTimelineFor(activeModelRunId),
                        modelHealth = it.failedGenerationModelHealth(
                            useRemoteModel = useRemoteModel,
                            reason = errorMessage,
                        ),
                        statusText = if (useRemoteModel) {
                            "远程生成失败"
                        } else {
                            "生成失败，建议重新加载"
                        },
                    )
                }
            }
        }
        generationJob = job
        job.invokeOnCompletion {
            if (generationJob == job) {
                generationJob = null
                activeGenerationRunId = null
            }
        }
    }

    fun confirmRemoteSendDisclosure(suppressForSession: Boolean = false) {
        val pending = _uiState.value.pendingRemoteSendDisclosure ?: return
        if (pending.requiresSensitiveConsent) return
        // Only honor "don't ask again this session" for non-forced sends.
        // Sensitive disclosures must never be silenced — they re-prompt every time regardless.
        if (suppressForSession &&
            _uiState.value.remoteSendDisclosurePolicy == RemoteSendDisclosurePolicy.OncePerSession &&
            !pending.forcedBySensitiveContent &&
            pending.imageAttachmentCount == 0
        ) {
            remoteSendDisclosureSuppressedForSession = true
        }
        recordRemoteSendDecision(RemoteSendDecision.Confirmed, pending)
        remoteSendPendingStore.clearPendingRemoteSend()
        val continuation = pendingRemoteContinuation
        val sharedInputRestore = pendingSharedInputRemoteSendRestore
            ?.takeIf { it.matches(pending) }
        pendingSharedInputRemoteSendRestore = null
        _uiState.update {
            if (it.pendingRemoteSendDisclosure == pending) {
                it.clearPendingSharedInputDraftForConfirmedRemoteSend(sharedInputRestore).copy(
                    pendingRemoteSendDisclosure = null,
                    statusText = "处理中",
                )
            } else {
                it
            }
        }
        if (continuation != null) {
            pendingRemoteContinuation = null
            continueAfterToolObservation(
                runId = continuation.runId,
                promptForModel = continuation.promptForModel,
                responsePrivacy = continuation.responsePrivacy,
                remoteToolScope = continuation.remoteToolScope,
                remoteSendConfirmed = true,
            )
            return
        }
        sendMessageInternal(
            prompt = pending.prompt,
            explicitMessagePrivacy = pending.messagePrivacy,
            imageAttachments = pending.imageAttachments,
            remoteSendConfirmed = true,
        )
    }

    /**
     * "Mask & send": redacts the detected sensitive spans and sends the masked prompt to the
     * remote model. Records a LocalOnly audit note of what was masked so the egress is traceable.
     * Only valid for a sensitive disclosure that produced a non-empty masked form.
     */
    fun confirmRemoteSendWithMasking() {
        val pending = _uiState.value.pendingRemoteSendDisclosure ?: return
        if (!pending.allowMaskedSend || pending.maskedPrompt.isBlank()) return
        // Masked sends are never applicable to tool-result continuations (no user prompt).
        if (pendingRemoteContinuation != null) return
        val maskedCategories = pending.sensitiveHitCategories.joinToString("、")
        appendRemoteSendAuditNote(
            "已对疑似敏感内容打码后发送到远程模型" +
                if (maskedCategories.isNotBlank()) "（$maskedCategories）。" else "。",
        )
        recordRemoteSendDecision(RemoteSendDecision.MaskedSend, pending)
        remoteSendPendingStore.clearPendingRemoteSend()
        val sharedInputRestore = pendingSharedInputRemoteSendRestore
            ?.takeIf { it.matches(pending) }
        pendingSharedInputRemoteSendRestore = null
        _uiState.update {
            if (it.pendingRemoteSendDisclosure == pending) {
                it.clearPendingSharedInputDraftForConfirmedRemoteSend(sharedInputRestore)
                    .copy(pendingRemoteSendDisclosure = null, statusText = "处理中")
            } else {
                it
            }
        }
        sendMessageInternal(
            prompt = pending.maskedPrompt,
            explicitMessagePrivacy = pending.messagePrivacy,
            imageAttachments = pending.imageAttachments,
            remoteSendConfirmed = true,
        )
    }

    /**
     * "Send anyway (audited)": sends the raw sensitive prompt unchanged after explicit consent.
     * Records a LocalOnly audit note flagging the override so the decision is traceable.
     */
    fun confirmRemoteSendDespiteSensitive() {
        val pending = _uiState.value.pendingRemoteSendDisclosure ?: return
        if (!pending.requiresSensitiveConsent) return
        if (pendingRemoteContinuation != null) return
        val hitCategories = pending.sensitiveHitCategories.joinToString("、")
        appendRemoteSendAuditNote(
            "用户确认在含疑似敏感内容的情况下仍原样发送到远程模型" +
                if (hitCategories.isNotBlank()) "（$hitCategories）。" else "。",
        )
        recordRemoteSendDecision(RemoteSendDecision.SentAnyway, pending)
        remoteSendPendingStore.clearPendingRemoteSend()
        val sharedInputRestore = pendingSharedInputRemoteSendRestore
            ?.takeIf { it.matches(pending) }
        pendingSharedInputRemoteSendRestore = null
        _uiState.update {
            if (it.pendingRemoteSendDisclosure == pending) {
                it.clearPendingSharedInputDraftForConfirmedRemoteSend(sharedInputRestore)
                    .copy(pendingRemoteSendDisclosure = null, statusText = "处理中")
            } else {
                it
            }
        }
        sendMessageInternal(
            prompt = pending.prompt,
            explicitMessagePrivacy = pending.messagePrivacy,
            imageAttachments = pending.imageAttachments,
            remoteSendConfirmed = true,
        )
    }

    /** Appends a LocalOnly assistant note recording a remote-send privacy decision (never sent). */
    private fun appendRemoteSendAuditNote(note: String) {
        replaceActiveSessionMessages(
            _uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = note,
                privacy = MessagePrivacy.LocalOnly,
            ),
            persistNow = true,
        )
    }

    /**
     * Records a structured, redacted egress event for a remote-send [decision] derived from the
     * [pending] disclosure. Feeds the user-reviewable "远程发送记录" list (P2 egress audit). Pure
     * counts/categories only — no raw prompt text is ever written to the audit store.
     */
    private fun recordRemoteSendDecision(
        decision: RemoteSendDecision,
        pending: PendingRemoteSendDisclosure,
    ) {
        recordRemoteSendAuditEvent(
            decision = decision,
            modelName = pending.remoteModelName,
            prompt = pending.prompt,
            imageCount = pending.imageAttachmentCount,
            remoteHistoryCount = pending.remoteHistoryCount,
        )
    }

    private fun savePendingRemoteSendMarker(
        pending: PendingRemoteSendDisclosure,
        runId: String? = null,
    ) {
        remoteSendPendingStore.savePendingRemoteSend(
            PendingRemoteSendMarker(
                kind = pending.kind,
                remoteModelName = pending.remoteModelName,
                remoteHistoryCount = pending.remoteHistoryCount,
                localOnlyHistoryFilteredCount = pending.localOnlyHistoryFilteredCount,
                imageAttachmentCount = pending.imageAttachmentCount,
                protectedSourceCount = pending.protectedSourceCount,
                runId = runId,
            ),
        )
    }

    private fun failClosedPendingRemoteSendOnStartup() {
        val marker = remoteSendPendingStore.consumePendingRemoteSend() ?: return
        pendingRemoteContinuation = null
        val reason = when (marker.kind) {
            RemoteSendDisclosureKind.CurrentInput -> REMOTE_SEND_RESTART_DISCARDED_TEXT
            RemoteSendDisclosureKind.ToolResultContinuation -> REMOTE_TOOL_CONTINUATION_RESTART_DISCARDED_TEXT
        }
        marker.runId?.let { runId ->
            assistantOrchestrator.failModelGeneration(runId, reason)
        }
        replaceActiveSessionMessages(
            _uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = reason,
                privacy = MessagePrivacy.LocalOnly,
            ),
            persistNow = true,
        )
        _uiState.update {
            it.copy(
                pendingRemoteSendDisclosure = null,
                isBusy = false,
                isGenerating = false,
                agentTraceRuns = loadAgentTraceRuns(),
                statusText = "远程发送确认已失效",
            )
        }
    }

    private fun recordRemoteSendAuditEvent(
        decision: RemoteSendDecision,
        modelName: String?,
        prompt: String,
        imageCount: Int,
        remoteHistoryCount: Int,
    ) {
        val sensitiveCategories = outboundSafetyPolicy.detectSensitiveCategories(prompt)
        val summaryParts = buildList {
            add(decision.label)
            if (imageCount > 0) add("图片 ${imageCount} 张")
            if (remoteHistoryCount > 0) add("历史 ${remoteHistoryCount} 条")
            if (sensitiveCategories.isNotEmpty()) {
                add("敏感类别：" + sensitiveCategories.joinToString("、") { it.label })
            }
        }
        remoteSendAuditSink.record(
            RemoteSendAuditEvent(
                decision = decision,
                modelName = modelName?.takeIf { it.isNotBlank() },
                sensitiveCategories = sensitiveCategories,
                imageCount = imageCount,
                remoteHistoryCount = remoteHistoryCount,
                summary = summaryParts.joinToString("；"),
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        _uiState.update { it.copy(remoteSendAuditEvents = loadRemoteSendAuditEvents()) }
    }

    /** Read-only egress audit view surfaced to the privacy/settings UI (most-recent-first). */
    private fun loadRemoteSendAuditEvents(): List<RemoteSendAuditSummary> =
        remoteSendAuditLog.recentRemoteSends().map { event ->
            RemoteSendAuditSummary(
                id = event.id,
                decisionLabel = event.decision.label,
                modelName = event.modelName,
                summary = event.summary,
                createdAtMillis = event.createdAtMillis,
            )
        }

    /** Refreshes the egress audit list in UI state. Call when the privacy/egress panel opens. */
    fun refreshRemoteSendAuditEvents() {
        _uiState.update { it.copy(remoteSendAuditEvents = loadRemoteSendAuditEvents()) }
    }

    fun dismissRemoteModeDisclosure() {
        _uiState.update {
            if (it.pendingRemoteModeDisclosure == null) {
                it
            } else {
                it.copy(
                    pendingRemoteModeDisclosure = null,
                    statusText = if (it.inferenceMode == InferenceMode.Remote) {
                        if (it.remoteModelConfig.isConfigured) "就绪 · 远程" else "请配置远程模型"
                    } else {
                        it.statusText
                    },
                )
            }
        }
    }

    fun dismissRemoteSendDisclosure() {
        val pending = _uiState.value.pendingRemoteSendDisclosure
        pending?.let { disclosure ->
            recordRemoteSendDecision(RemoteSendDecision.Cancelled, disclosure)
        }
        val continuation = pendingRemoteContinuation
        pendingRemoteContinuation = null
        remoteSendPendingStore.clearPendingRemoteSend()
        if (continuation != null) {
            val reason = "用户取消远程工具结果续写，工具结果未发送到远程模型。"
            continuation.runId?.let { runId ->
                assistantOrchestrator.failModelGeneration(runId, reason)
            }
            _uiState.updateLastAssistantLocalOnly(reason)
            persistActiveSessionFromUi()
        }
        val sharedInputRestore = pendingSharedInputRemoteSendRestore
            ?.takeIf { restore -> pending != null && restore.matches(pending) }
        pendingSharedInputRemoteSendRestore = null
        _uiState.update {
            if (it.pendingRemoteSendDisclosure != null) {
                it.restoreComposerDraftAfterRemoteSendCancel(
                    pending = pending,
                    sharedInputRestore = if (continuation == null) sharedInputRestore else null,
                    restoreOrdinaryPrompt = continuation == null,
                ).copy(
                    pendingRemoteSendDisclosure = null,
                    isBusy = false,
                    isGenerating = false,
                    agentTraceRuns = loadAgentTraceRuns(),
                    statusText = "已取消远程发送",
                )
            } else {
                it
            }
        }
    }

    fun ingestSharedInput(sharedInput: SharedInput) {
        if (sharedInput.isEmpty) return
        if (_uiState.value.inferenceMode == InferenceMode.Local &&
            sharedInput.hasLocalImageAttachment() &&
            !_uiState.value.activeLocalModelSupportsVisionInput
        ) {
            rejectUnsupportedLocalVisionInput()
            return
        }
        if (_uiState.value.inferenceMode == InferenceMode.Remote) {
            val remoteConfig = _uiState.value.remoteModelConfig
            if (!remoteConfig.isConfigured) {
                protectUnconfiguredRemoteSharedInput(alreadyStaged = false)
                return
            }
            val remoteSupportsVisionInput = remoteConfig.modelProfile().supportsVisionInput
            if ((sharedInput.hasRemoteImageAttachment() || sharedInput.hasProtectedImageSource()) &&
                !remoteSupportsVisionInput
            ) {
                rejectUnsupportedRemoteVisionInput(sharedInput.protectedSourceCount)
                return
            }
            if (!sharedInput.isRemoteVisionSendable()) {
                protectRemoteSharedInput()
                return
            }
        }
        stageSharedInputDraft(sharedInput, statusText = "已接收分享内容")
    }

    fun stageSharedInput(sharedInput: SharedInput) {
        if (_uiState.value.inferenceMode == InferenceMode.Local &&
            sharedInput.hasLocalImageAttachment() &&
            !_uiState.value.activeLocalModelSupportsVisionInput
        ) {
            rejectUnsupportedLocalVisionInput()
            return
        }
        if (_uiState.value.inferenceMode == InferenceMode.Remote) {
            val remoteConfig = _uiState.value.remoteModelConfig
            if (!remoteConfig.isConfigured) {
                protectUnconfiguredRemoteSharedInput(alreadyStaged = false)
                return
            }
            val remoteSupportsVisionInput = remoteConfig.modelProfile().supportsVisionInput
            if ((sharedInput.hasRemoteImageAttachment() || sharedInput.hasProtectedImageSource()) &&
                !remoteSupportsVisionInput
            ) {
                rejectUnsupportedRemoteVisionInput(sharedInput.protectedSourceCount)
                return
            }
            if (!sharedInput.isRemoteVisionSendable()) {
                protectRemoteSharedInput()
                return
            }
        }
        stageSharedInputDraft(sharedInput, statusText = "已选择附件")
    }

    private fun stageSharedInputDraft(sharedInput: SharedInput, statusText: String) {
        if (sharedInput.isEmpty) return
        pendingSharedInputRemoteSendRestore = null
        val imageAttachments = if (_uiState.value.inferenceMode == InferenceMode.Remote) {
            sharedInput.remoteImageAttachments()
        } else {
            emptyList()
        }
        val localImageAttachments = if (
            _uiState.value.inferenceMode == InferenceMode.Local &&
            _uiState.value.activeLocalModelSupportsVisionInput
        ) {
            sharedInput.localImageAttachments()
        } else {
            emptyList()
        }
        val prompt = if (imageAttachments.isNotEmpty()) {
            sharedInput.toRemoteVisionPrompt()
        } else if (localImageAttachments.isNotEmpty()) {
            sharedInput.toLocalVisionPrompt()
        } else {
            sharedInput.toPrompt()
        }
        if (prompt.isBlank()) return
        _uiState.update {
            it.copy(
                pendingSharedInputDraft = SharedInputDraft(
                    id = ++nextSharedInputDraftId,
                    prompt = prompt,
                    summary = sharedInput.composerSummary(),
                    imageAttachments = imageAttachments,
                    localImageAttachments = localImageAttachments,
                    privacy = if (imageAttachments.isNotEmpty()) {
                        MessagePrivacy.RemoteEligible
                    } else {
                        MessagePrivacy.LocalOnly
                    },
                    evidenceReceiptSummary = sharedInput.toSharedEvidenceReceiptSummary(),
                ),
                statusText = statusText,
            )
        }
    }

    private fun protectRemoteSharedInput() {
        replaceActiveSessionMessages(
            _uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = "已接收分享内容。当前已切换远程模型，主动选择的图片只会在逐次确认后发送给远程视觉模型，疑似敏感内容也会逐次确认；不会读取或自动发送分享文本、RTF/PDF/Office 文档摘录、JSON/XML/YAML 文本摘录、OCR 摘录或非图片附件元数据。",
                privacy = MessagePrivacy.LocalOnly,
            ),
            persistNow = true,
        )
        _uiState.update {
            it.copy(statusText = "已保护分享内容")
        }
    }

    private fun protectUnconfiguredRemoteSharedInput(alreadyStaged: Boolean) {
        val notice = buildString {
            append("请先在模型管理中配置远程模型地址和模型名；")
            if (alreadyStaged) {
                append("我没有发送这次分享内容，图片不会被自动 OCR，也不会发送到远程模型。")
            } else {
                append("我没有读取、OCR 或发送这次分享内容。")
            }
            append("远程模式只会在远程模型配置完成、切换到远程模型且你确认发送后，把主动选择的图片发送给远程视觉模型。")
        }
        replaceActiveSessionMessages(
            _uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = notice,
                privacy = MessagePrivacy.LocalOnly,
            ),
            persistNow = true,
        )
        _uiState.update {
            it.copy(
                pendingSharedInputDraft = null,
                pendingRemoteSendDisclosure = null,
                statusText = "请配置远程模型",
                modelHealth = ModelHealth(
                    profileId = it.remoteModelConfig.modelProfile().id,
                    state = ModelHealthState.LoadFailed,
                    failureReason = "远程模型未配置",
                ),
            )
        }
    }

    private fun rejectUnsupportedRemoteVisionInput(protectedSourceCount: Int = 0) {
        replaceActiveSessionMessages(
            _uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = buildString {
                    append("当前远程模型未启用图片输入能力，未读取、OCR 或发送图片；请配置并切换支持视觉的远程模型后重新选择图片。")
                    if (protectedSourceCount > 0) {
                        append("本次分享中的其他内容也未读取或发送。")
                    }
                },
                privacy = MessagePrivacy.LocalOnly,
            ),
            persistNow = true,
        )
        _uiState.update {
            it.copy(
                pendingSharedInputDraft = null,
                statusText = "当前远程模型不支持图片输入",
            )
        }
    }

    private fun rejectUnsupportedLocalVisionInput() {
        replaceActiveSessionMessages(
            _uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = "当前本地模型不支持图片输入，未读取、OCR 或发送图片；请切换到已校验且支持视觉的本地模型后重新选择图片。",
                privacy = MessagePrivacy.LocalOnly,
            ),
            persistNow = true,
        )
        _uiState.update {
            it.copy(
                pendingSharedInputDraft = null,
                statusText = "当前本地模型不支持图片输入",
            )
        }
    }

    private fun handleNotReadySendAttempt() {
        val state = _uiState.value
        if (state.inferenceMode == InferenceMode.Remote && !state.remoteModelConfig.isConfigured) {
            val notice = "请先在模型管理中配置远程模型地址和模型名；我还没有发送你的内容。"
            val messages = if (state.messages.lastOrNull()?.text == notice) {
                state.messages
            } else {
                state.messages + ChatMessage(
                    role = MessageRole.Assistant,
                    text = notice,
                    privacy = MessagePrivacy.LocalOnly,
                )
            }
            replaceActiveSessionMessages(messages, persistNow = true)
            _uiState.update {
                it.copy(
                    isReady = false,
                    pendingRemoteSendDisclosure = null,
                    statusText = "请配置远程模型",
                    modelHealth = ModelHealth(
                        profileId = it.remoteModelConfig.modelProfile().id,
                        state = ModelHealthState.LoadFailed,
                        failureReason = "远程模型未配置",
                    ),
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                statusText = if (state.inferenceMode == InferenceMode.Remote) {
                    "远程模型未就绪"
                } else {
                    "请先下载或导入本地模型"
                },
            )
        }
    }

    fun clearPendingSharedInputDraft(draftId: Long) {
        if (pendingSharedInputRemoteSendRestore?.draft?.id == draftId) {
            pendingSharedInputRemoteSendRestore = null
        }
        _uiState.update {
            if (it.pendingSharedInputDraft?.id == draftId) {
                it.copy(
                    pendingSharedInputDraft = null,
                    statusText = "已移除附件",
                )
            } else {
                it
            }
        }
    }

    fun sendPendingSharedInput(userInstruction: String = "") {
        val draft = _uiState.value.pendingSharedInputDraft ?: return
        val message = buildString {
            val cleanedInstruction = userInstruction.trim()
            if (cleanedInstruction.isNotBlank()) {
                append(cleanedInstruction)
                append("\n\n")
            }
            append(draft.prompt)
        }.trim()
        if (message.isBlank()) return
        val state = _uiState.value
        if (state.pendingConfirmation != null) {
            _uiState.update { it.copy(statusText = "请先确认或取消待执行动作") }
            return
        }
        if (state.pendingExternalOutcome != null) {
            _uiState.update { it.copy(statusText = "请先确认外部动作结果") }
            return
        }
        if (state.pendingRemoteModeDisclosure != null) {
            _uiState.update { it.copy(statusText = "请先确认远程模式提醒") }
            return
        }
        if (state.isBusy || generationJob?.isActive == true) return
        // An unconfigured remote model is a "no model" situation, not a
        // "vision unsupported" one. Surface the unconfigured guidance first so the
        // fail-closed supportsVisionInput=false default does not mask it.
        if (draft.imageAttachments.isNotEmpty() &&
            state.inferenceMode == InferenceMode.Remote &&
            state.remoteModelConfig.isConfigured &&
            !state.remoteModelConfig.modelProfile().supportsVisionInput
        ) {
            rejectUnsupportedRemoteVisionInput()
            return
        }
        if (draft.localImageAttachments.isNotEmpty() &&
            state.inferenceMode == InferenceMode.Local &&
            !state.activeLocalModelSupportsVisionInput
        ) {
            rejectUnsupportedLocalVisionInput()
            return
        }

        if (!_uiState.value.isReady) {
            clearPendingSharedInputDraftIfActive(draft.id)
            if (_uiState.value.inferenceMode == InferenceMode.Remote &&
                !_uiState.value.remoteModelConfig.isConfigured
            ) {
                protectUnconfiguredRemoteSharedInput(alreadyStaged = true)
                return
            }
            replaceActiveSessionMessages(
                _uiState.value.messages + ChatMessage(
                    role = MessageRole.User,
                    text = message,
                    privacy = MessagePrivacy.LocalOnly,
                ) + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "已接收分享内容。请先准备模型后再发送；图片不会被自动 OCR，当前只会读取受限文本、JSON/XML/YAML/RTF/PDF/Office 文档摘录、PDF 扫描页 OCR 摘录和附件元数据。",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                persistNow = true,
            )
            _uiState.update { it.copy(statusText = "已接收分享内容") }
            return
        }
        val cleanedInstruction = userInstruction.trim()
        sendMessageInternal(
            prompt = message,
            explicitMessagePrivacy = draft.privacy,
            imageAttachments = draft.imageAttachments,
            localImageAttachments = draft.localImageAttachments,
            currentPromptEvidenceSummary = draft.evidenceReceiptSummary,
        )
        val pending = _uiState.value.pendingRemoteSendDisclosure
        if (pending != null &&
            pending.kind == RemoteSendDisclosureKind.CurrentInput &&
            pending.prompt == message &&
            pending.imageAttachments == draft.imageAttachments
        ) {
            pendingSharedInputRemoteSendRestore = PendingSharedInputRemoteSendRestore(
                draft = draft,
                userInstruction = cleanedInstruction,
                combinedPrompt = message,
            )
        } else {
            pendingSharedInputRemoteSendRestore = null
            clearPendingSharedInputDraftIfActive(draft.id)
        }
    }

    private fun clearPendingSharedInputDraftIfActive(draftId: Long) {
        _uiState.update {
            if (it.pendingSharedInputDraft?.id == draftId) {
                it.copy(pendingSharedInputDraft = null)
            } else {
                it
            }
        }
    }

    private fun ChatUiState.clearPendingSharedInputDraftForConfirmedRemoteSend(
        restore: PendingSharedInputRemoteSendRestore?,
    ): ChatUiState {
        if (restore == null) return this
        return if (pendingSharedInputDraft?.id == restore.draft.id) {
            copy(pendingSharedInputDraft = null)
        } else {
            this
        }
    }

    private fun ChatUiState.restoreComposerDraftAfterRemoteSendCancel(
        pending: PendingRemoteSendDisclosure?,
        sharedInputRestore: PendingSharedInputRemoteSendRestore?,
        restoreOrdinaryPrompt: Boolean,
    ): ChatUiState {
        if (pending == null || pending.kind != RemoteSendDisclosureKind.CurrentInput) return this
        if (sharedInputRestore != null) {
            val restoredSharedDraft = if (pendingSharedInputDraft == null) {
                copy(pendingSharedInputDraft = sharedInputRestore.draft)
            } else {
                this
            }
            return restoredSharedDraft.withRecoveredComposerInputDraft(sharedInputRestore.userInstruction)
        }
        return if (restoreOrdinaryPrompt) {
            withRecoveredComposerInputDraft(pending.prompt)
        } else {
            this
        }
    }

    private fun ChatUiState.withRecoveredComposerInputDraft(text: String): ChatUiState {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return this
        return copy(
            voiceInputDraft = VoiceInputDraft(
                id = ++nextVoiceInputDraftId,
                text = cleaned,
            ),
        )
    }

    private fun cancelActiveGenerationRun(runId: String?) {
        val id = runId ?: return
        if (activeGenerationRunId != id) return
        assistantOrchestrator.cancelRun(id, USER_STOPPED_AGENT_RUN_REASON)
        activeGenerationRunId = null
    }

    fun stopGeneration() {
        val job = generationJob ?: return
        val runId = activeGenerationRunId
        if (_uiState.value.inferenceMode == InferenceMode.Local) {
            runtime.stop()
        } else {
            remoteRuntime.stop()
        }
        cancelActiveGenerationRun(runId)
        job.cancel()
        finishStoppedGeneration(runId)
    }

    fun requestRecoveryActionConfirmation(action: AgentRecoveryAction) {
        val state = _uiState.value
        if (state.pendingConfirmation != null) {
            _uiState.update {
                it.copy(statusText = "请先确认或取消待执行动作")
            }
            return
        }
        if (state.isBusy || generationJob?.isActive == true) {
            _uiState.update {
                it.copy(statusText = "请稍后再撤销提醒")
            }
            return
        }
        if (state.latestRecoveryAction != action) {
            _uiState.update {
                it.copy(statusText = "撤销入口已过期")
            }
            return
        }
        when (val route = assistantOrchestrator.requestRecoveryAction(action, state.activeSessionId)) {
            is AssistantRoute.Action -> {
                val runId = route.runId
                val request = route.toolRequest
                if (runId == null || request == null) {
                    _uiState.update {
                        it.copy(
                            latestRecoveryAction = null,
                            statusText = "撤销动作不可执行",
                        )
                    }
                    return
                }
                _uiState.update {
                    it.copy(
                        pendingConfirmation = PendingAgentConfirmation(
                            runId = runId,
                            draft = route.draft,
                            toolRequest = request,
                            skillId = route.skillId,
                            plannedByModel = route.plannedByModel,
                            fallbackReason = route.fallbackReason,
                        ),
                        latestRecoveryAction = null,
                        auditEvents = loadAuditEvents(),
                        agentTraceRuns = loadAgentTraceRuns(),
                        statusText = "撤销提醒待确认",
                    )
                }
            }

            is AssistantRoute.ToolRejected -> {
                _uiState.update {
                    it.copy(
                        latestRecoveryAction = null,
                        auditEvents = loadAuditEvents(),
                        agentTraceRuns = loadAgentTraceRuns(),
                        statusText = "撤销动作不可执行：${route.summary}",
                    )
                }
            }

            else -> {
                _uiState.update {
                    it.copy(statusText = "撤销动作不可执行")
                }
            }
        }
    }

    fun confirmAgentConfirmation(confirmation: PendingAgentConfirmation) {
        val pendingConfirmation = _uiState.value.pendingConfirmation
        if (pendingConfirmation == null || !pendingConfirmation.matchesExecution(confirmation)) {
            _uiState.update {
                it.copy(statusText = "工具确认已处理")
            }
            return
        }
        val request = pendingConfirmation.toolRequest ?: ToolRequest(
            toolName = pendingConfirmation.draft.functionName,
            arguments = pendingConfirmation.draft.parameters,
            reason = pendingConfirmation.draft.summary,
        )
        val confirmedRun = try {
            confirmation.runId?.let { runId ->
                assistantOrchestrator.confirmToolRequest(runId, request.id)
            }
        } catch (_: Exception) {
            _uiState.update {
                it.copy(
                    pendingConfirmation = pendingConfirmation,
                    isBusy = false,
                    isGenerating = false,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(confirmation.runId),
                    statusText = "工具确认失败，未执行动作。",
                )
            }
            return
        }
        if (confirmation.runId != null && confirmedRun?.state != AgentRunState.ExecutingTool) {
            replaceActiveSessionMessages(
                _uiState.value.messages + ChatMessage(
                    MessageRole.Assistant,
                    "工具确认失败，未执行动作。",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                persistNow = true,
            )
            _uiState.update {
                it.copy(
                    pendingConfirmation = null,
                    isBusy = false,
                    isGenerating = false,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(confirmation.runId),
                    statusText = "工具未执行",
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                isBusy = true,
                isGenerating = false,
                activeRunTimeline = activeRunTimelineFor(confirmation.runId),
                statusText = "工具执行中",
            )
        }
        launchToolExecutionAfterRunIsExecuting(
            confirmation = pendingConfirmation,
            request = request,
        )
    }

    private fun launchToolExecutionAfterRunIsExecuting(
        confirmation: PendingAgentConfirmation,
        request: ToolRequest,
    ) {
        activeGenerationRunId = confirmation.runId
        val job = viewModelScope.launch(ioDispatcher) {
            executeToolRequestAfterRunIsExecuting(
                confirmation = confirmation,
                request = request,
            )
        }
        generationJob = job
        job.invokeOnCompletion {
            if (generationJob == job) {
                generationJob = null
                activeGenerationRunId = null
            }
        }
    }

    private suspend fun executeToolRequestAfterRunIsExecuting(
        confirmation: PendingAgentConfirmation,
        request: ToolRequest,
    ) {
        var result = executeToolWithBoundary(request)
        var observation = confirmation.runId?.let { runId ->
            assistantOrchestrator.observeToolResult(runId, result)
        }
        var assistantText = observation?.assistantMessage ?: result.statusSummaryForUi()
        var observationPrivacy = observation.privacyForObservation(fallbackToolName = request.toolName)
        var messagesWithObservation = _uiState.value.messages + ChatMessage(
            role = MessageRole.Assistant,
            text = assistantText,
            privacy = observationPrivacy,
        )
        replaceActiveSessionMessages(
            messagesWithObservation,
            persistNow = true,
        )
        var retryRequest = observation?.retryRequest
        while (retryRequest != null) {
            _uiState.update {
                it.copy(statusText = "工具重试中")
            }
            result = executeToolWithBoundary(retryRequest)
            observation = confirmation.runId?.let { runId ->
                assistantOrchestrator.observeToolResult(runId, result)
            }
            assistantText = observation?.assistantMessage ?: result.statusSummaryForUi()
            observationPrivacy = observation.privacyForObservation(fallbackToolName = retryRequest.toolName)
            messagesWithObservation = _uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = assistantText,
                privacy = observationPrivacy,
            )
            replaceActiveSessionMessages(
                messagesWithObservation,
                persistNow = true,
            )
            retryRequest = observation?.retryRequest
        }
        val publicWebEvidence = activePublicWebEvidenceFor(confirmation.runId)
        val nextToolPlan = (observation?.decision as? AgentObservationDecision.PlanNextTool)?.plan
        if (nextToolPlan != null) {
            handleNextToolPlan(
                runId = observation.run.id,
                plan = nextToolPlan,
                pendingStatusText = "下一步动作待确认",
            )
            return
        }
        observation?.continuationPromptForModel?.let { continuationPrompt ->
            if (observation.continuationRequiresLocalModel &&
                _uiState.value.inferenceMode == InferenceMode.Remote
            ) {
                val protectedContentName = request.protectedContinuationContentName()
                assistantOrchestrator.failModelGeneration(
                    observation.run.id,
                    "工具结果需要本地模型续写，未发送到远程模型",
                )
                replaceActiveSessionMessages(
                    messagesWithObservation + ChatMessage(
                        role = MessageRole.Assistant,
                        text = "已读取${protectedContentName}。当前为远程模型模式，为保护隐私，我不会自动发送${protectedContentName}到远程模型。请切换到本地模型后重试，或手动粘贴你愿意发送的内容。",
                        privacy = MessagePrivacy.LocalOnly,
                    ),
                    persistNow = true,
                )
                rebuildMemoryIndex()
                _uiState.update {
                    it.copy(
                        pendingConfirmation = null,
                        isBusy = false,
                        isGenerating = false,
                        auditEvents = loadAuditEvents(),
                        agentTraceRuns = loadAgentTraceRuns(),
                        activeRunTimeline = activeRunTimelineFor(confirmation.runId),
                        activePublicWebEvidence = publicWebEvidence,
                        statusText = "已保护${protectedContentName}",
                    )
                }
                return
            }
            replaceActiveSessionMessages(
                messagesWithObservation + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "",
                    privacy = observationPrivacy,
                ),
                persistNow = true,
            )
            _uiState.update {
                it.copy(
                    pendingConfirmation = null,
                    isBusy = true,
                    isGenerating = true,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(confirmation.runId),
                    activePublicWebEvidence = publicWebEvidence,
                    statusText = "生成中",
                )
            }
            continueAfterToolObservation(
                runId = observation.run.id,
                promptForModel = continuationPrompt,
                responsePrivacy = observationPrivacy,
                remoteToolScope = observation.continuationRemoteToolScope,
            )
            return
        }
        syncTaskStateMemories()
        rebuildMemoryIndex()
        val pendingExternalOutcome = observation?.pendingExternalOutcomeFor(confirmation, request)
        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                pendingExternalOutcome = pendingExternalOutcome,
                backgroundTasks = loadBackgroundTasks(),
                backgroundTaskHistory = loadBackgroundTaskHistory(),
                periodicCheckPolicy = loadPeriodicCheckPolicy(),
                longTermMemories = loadLongTermMemories(),
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(confirmation.runId),
                activePublicWebEvidence = publicWebEvidence,
                latestRecoveryAction = if (pendingExternalOutcome == null) observation?.recoveryAction else null,
                isBusy = false,
                isGenerating = false,
                statusText = observation?.assistantMessage ?: result.statusSummaryForUi(),
            )
        }
    }

    private suspend fun executeToolWithBoundary(request: ToolRequest): ToolResult =
        toolExecutionBoundary.execute(request)

    private suspend fun executePublicEvidenceToolBatchAfterRunIsExecuting(
        runId: String,
        plans: List<AgentPlan.UseTool>,
    ) {
        if (plans.isEmpty()) {
            assistantOrchestrator.failModelGeneration(runId, "远程模型返回了空工具批次")
            _uiState.updateLastAssistantLocalOnly("远程模型返回了空工具批次，已停止执行。")
            persistActiveSessionFromUi()
            _uiState.update {
                it.copy(
                    isBusy = false,
                    isGenerating = false,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(runId),
                    statusText = "批量工具不可执行",
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                latestRecoveryAction = null,
                isBusy = true,
                isGenerating = false,
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(runId),
                statusText = "工具并发执行中",
            )
        }
        val results = executePublicEvidenceToolPlans(plans)
        val observation = assistantOrchestrator.observeToolResults(runId, results)
        if (observation == null) {
            assistantOrchestrator.cancelRun(runId, "批量工具结果无法进入观察流程")
            _uiState.updateLastAssistantLocalOnly("批量工具结果无法进入观察流程，已停止执行。")
            persistActiveSessionFromUi()
            _uiState.update {
                it.copy(
                    pendingConfirmation = null,
                    isBusy = false,
                    isGenerating = false,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(runId),
                    statusText = "批量工具不可执行",
                )
            }
            return
        }
        val observationPrivacy = observation.privacyForObservation(fallbackToolName = PUBLIC_EVIDENCE_BATCH_TOOL_NAME)
        val publicWebEvidence = activePublicWebEvidenceFor(runId)
        val messagesWithObservation = _uiState.value.messages + ChatMessage(
            role = MessageRole.Assistant,
            text = observation.assistantMessage,
            privacy = observationPrivacy,
        )
        replaceActiveSessionMessages(
            messagesWithObservation,
            persistNow = true,
        )
        val nextToolPlan = (observation.decision as? AgentObservationDecision.PlanNextTool)?.plan
        if (nextToolPlan != null) {
            handleNextToolPlan(
                runId = observation.run.id,
                plan = nextToolPlan,
                pendingStatusText = "下一步动作待确认",
            )
            return
        }
        observation.continuationPromptForModel?.let { continuationPrompt ->
            if (observation.continuationRequiresLocalModel &&
                _uiState.value.inferenceMode == InferenceMode.Remote
            ) {
                assistantOrchestrator.failModelGeneration(
                    observation.run.id,
                    "批量工具结果需要本地模型续写，未发送到远程模型",
                )
                replaceActiveSessionMessages(
                    messagesWithObservation + ChatMessage(
                        role = MessageRole.Assistant,
                        text = "批量工具结果包含仅本地内容。当前为远程模型模式，我不会把它发送到远程模型。",
                        privacy = MessagePrivacy.LocalOnly,
                    ),
                    persistNow = true,
                )
                rebuildMemoryIndex()
                _uiState.update {
                    it.copy(
                        pendingConfirmation = null,
                        isBusy = false,
                        isGenerating = false,
                        auditEvents = loadAuditEvents(),
                        agentTraceRuns = loadAgentTraceRuns(),
                        activeRunTimeline = activeRunTimelineFor(runId),
                        activePublicWebEvidence = publicWebEvidence,
                        statusText = "已保护批量工具结果",
                    )
                }
                return
            }
            replaceActiveSessionMessages(
                messagesWithObservation + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "",
                    privacy = observationPrivacy,
                ),
                persistNow = true,
            )
            _uiState.update {
                it.copy(
                    pendingConfirmation = null,
                    isBusy = true,
                    isGenerating = true,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(runId),
                    activePublicWebEvidence = publicWebEvidence,
                    statusText = "生成中",
                )
            }
            continueAfterToolObservation(
                runId = observation.run.id,
                promptForModel = continuationPrompt,
                responsePrivacy = observationPrivacy,
                remoteToolScope = observation.continuationRemoteToolScope,
            )
            return
        }
        syncTaskStateMemories()
        rebuildMemoryIndex()
        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                backgroundTasks = loadBackgroundTasks(),
                backgroundTaskHistory = loadBackgroundTaskHistory(),
                periodicCheckPolicy = loadPeriodicCheckPolicy(),
                longTermMemories = loadLongTermMemories(),
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(runId),
                activePublicWebEvidence = publicWebEvidence,
                latestRecoveryAction = observation.recoveryAction,
                isBusy = false,
                isGenerating = false,
                statusText = observation.assistantMessage,
            )
        }
    }

    private suspend fun executePublicEvidenceToolPlans(
        plans: List<AgentPlan.UseTool>,
    ): List<ToolResult> =
        toolExecutionBoundary.executePublicEvidenceBatch(
            requests = plans.map { plan -> plan.request },
        ) {
            _uiState.update {
                it.copy(statusText = "工具批量重试中")
            }
        }

    private fun handleNextToolPlan(
        runId: String,
        plan: AgentPlan.UseTool,
        pendingStatusText: String,
    ) {
        rebuildMemoryIndex()
        val confirmation = PendingAgentConfirmation(
            runId = runId,
            draft = plan.draft,
            toolRequest = plan.request,
            skillId = plan.skillRequest?.skillId,
            plannedByModel = plan.plannedByModel,
            fallbackReason = plan.fallbackReason,
        )
        if (!plan.requiresUserConfirmation()) {
            _uiState.update {
                it.copy(
                    pendingConfirmation = null,
                    latestRecoveryAction = null,
                    isBusy = true,
                    isGenerating = false,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(runId),
                    statusText = "工具执行中",
                )
            }
            launchToolExecutionAfterRunIsExecuting(
                confirmation = confirmation,
                request = plan.request,
            )
            return
        }
        _uiState.update {
            it.copy(
                pendingConfirmation = confirmation,
                latestRecoveryAction = null,
                isBusy = false,
                isGenerating = false,
                isReady = true,
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(runId),
                statusText = pendingStatusText,
            )
        }
    }

    private fun AgentObservationResult.pendingExternalOutcomeFor(
        confirmation: PendingAgentConfirmation,
        request: ToolRequest,
    ): PendingExternalOutcomeConfirmation? {
        if (!result.isUnverifiedExternalLaunch()) return null
        if (run.state != AgentRunState.AwaitingExternalOutcome) return null
        val runId = confirmation.runId ?: return null
        return PendingExternalOutcomeConfirmation(
            runId = runId,
            requestId = result.requestId,
            toolName = request.toolName,
            title = confirmation.draft.title,
            summary = assistantMessage,
        )
    }

    fun recordExternalOutcome(
        pending: PendingExternalOutcomeConfirmation,
        outcome: AgentExternalOutcome,
    ) {
        val current = _uiState.value.pendingExternalOutcome
        if (current == null || current != pending) {
            _uiState.update {
                it.copy(statusText = "外部结果确认已处理")
            }
            return
        }
        val recorded = runCatching {
            assistantOrchestrator.recordExternalOutcome(
                runId = pending.runId,
                requestId = pending.requestId,
                outcome = outcome,
            )
        }.getOrNull()
        if (recorded == null) {
            _uiState.update {
                it.copy(
                    pendingExternalOutcome = null,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(pending.runId),
                    statusText = "外部结果确认已过期",
                )
            }
            return
        }
        replaceActiveSessionMessages(
            _uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = recorded.assistantMessage,
                privacy = MessagePrivacy.LocalOnly,
            ),
            persistNow = true,
        )
        rebuildMemoryIndex()
        val nextToolPlan = (recorded.decision as? AgentObservationDecision.PlanNextTool)?.plan
        if (nextToolPlan != null) {
            _uiState.update {
                it.copy(
                    pendingExternalOutcome = null,
                    latestRecoveryAction = null,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(recorded.run.id),
                )
            }
            handleNextToolPlan(
                runId = recorded.run.id,
                plan = nextToolPlan,
                pendingStatusText = "下一步动作待确认",
            )
            return
        }
        _uiState.update {
            it.copy(
                pendingExternalOutcome = null,
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(recorded.run.id),
                statusText = recorded.assistantMessage,
            )
        }
    }

    private fun ToolResult.statusSummaryForUi(): String =
        if (isUnverifiedExternalLaunch()) unverifiedExternalLaunchSummary() else summary

    fun rejectAgentConfirmationForRuntimePermissionDenial(
        confirmation: PendingAgentConfirmation,
        deniedPermissions: List<String>,
    ) {
        val deniedSummary = runtimePermissionDenialSummary(deniedPermissions)
        val deniedPermissionNames = deniedPermissions.distinct().joinToString()
        rejectAgentConfirmationWithFailure(
            confirmation = confirmation,
            statusText = "权限被拒，工具未执行",
            resultFor = { request ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "用户拒绝了所需权限，工具未执行：$deniedSummary",
                    retryable = false,
                    data = mapOf(
                        "toolName" to request.toolName,
                        "deniedPermissions" to deniedPermissionNames,
                        "deniedPermissionLabels" to deniedSummary,
                    ),
                )
            },
        )
    }

    fun rejectAgentConfirmationForSpecialAccessDenial(
        confirmation: PendingAgentConfirmation,
        deniedRequirements: List<SpecialAccessRequirement>,
    ) {
        val deniedSummary = specialAccessDenialSummary(deniedRequirements)
        rejectAgentConfirmationWithFailure(
            confirmation = confirmation,
            statusText = "特殊权限未开启，工具未执行",
            resultFor = { request ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "未开启所需系统特殊权限，工具未执行：$deniedSummary",
                    retryable = false,
                    data = mapOf(
                        "toolName" to request.toolName,
                        "specialAccess" to deniedRequirements.joinToString { it.id },
                        "specialAccessLabels" to deniedSummary,
                        "settingsAction" to deniedRequirements.joinToString { it.settingsAction },
                    ),
                )
            },
        )
    }

    fun rejectAgentConfirmationForMediaProjectionDenial(
        confirmation: PendingAgentConfirmation,
    ) {
        rejectAgentConfirmationWithFailure(
            confirmation = confirmation,
            statusText = "屏幕截图同意已取消，工具未执行",
            resultFor = { request ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "用户取消了当前屏幕截图 OCR 的 Android MediaProjection 前台同意，工具未执行。",
                    retryable = false,
                    data = mapOf(
                        "toolName" to request.toolName,
                        "privacy" to MessagePrivacy.LocalOnly.name,
                        "requiresLocalModel" to true.toString(),
                        "specialAccess" to CurrentScreenshotOcrContract.CONSENT_REASON,
                    ),
                )
            },
        )
    }

    private fun rejectAgentConfirmationWithFailure(
        confirmation: PendingAgentConfirmation,
        statusText: String,
        resultFor: (ToolRequest) -> ToolResult,
    ) {
        val pendingConfirmation = _uiState.value.pendingConfirmation
        if (pendingConfirmation == null || !pendingConfirmation.matchesExecution(confirmation)) {
            _uiState.update {
                it.copy(statusText = "工具确认已处理")
            }
            return
        }
        val request = pendingConfirmation.toolRequest ?: ToolRequest(
            toolName = pendingConfirmation.draft.functionName,
            arguments = pendingConfirmation.draft.parameters,
            reason = pendingConfirmation.draft.summary,
        )
        val result = resultFor(request)
        val observation = confirmation.runId?.let { runId ->
            assistantOrchestrator.failPendingToolRequest(runId, request.id, result)
        }
        replaceActiveSessionMessages(
            _uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = observation?.assistantMessage ?: "工具执行失败：${result.summary}",
                privacy = MessagePrivacy.LocalOnly,
            ),
            persistNow = true,
        )
        rebuildMemoryIndex()
        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                isBusy = false,
                isGenerating = false,
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(confirmation.runId),
                statusText = statusText,
            )
        }
    }

    private fun continueAfterToolObservation(
        runId: String?,
        promptForModel: String,
        responsePrivacy: MessagePrivacy,
        remoteToolScope: RemoteToolScope,
        remoteSendConfirmed: Boolean = false,
    ) {
        val stateAtStart = _uiState.value
        val useRemoteModel = stateAtStart.inferenceMode == InferenceMode.Remote
        val remoteConfig = stateAtStart.remoteModelConfig
        var remoteHistory: List<ChatMessage> = remoteHistoryForRemoteSend(stateAtStart.messages.dropLast(1))
        if (useRemoteModel &&
            responsePrivacy == MessagePrivacy.RemoteEligible &&
            remoteConfig.isConfigured &&
            remoteConfig.hasKnownConnectivityFailure
        ) {
            runId?.let { id ->
                assistantOrchestrator.failModelGeneration(id, "远程连接状态为${remoteConfig.connectivityStatus.label}")
            }
            recordRemoteSendAuditEvent(
                decision = RemoteSendDecision.Blocked,
                modelName = remoteConfig.normalized().modelName,
                prompt = promptForModel,
                imageCount = 0,
                remoteHistoryCount = remoteHistory.size,
            )
            _uiState.updateLastAssistantLocalOnly(
                "远程连接状态为${remoteConfig.connectivityStatus.label}，工具结果续写没有发送。请在模型管理中测试连接或更新远程配置。",
            )
            persistActiveSessionFromUi()
            _uiState.update {
                it.copy(
                    isBusy = false,
                    isGenerating = false,
                    isReady = true,
                    pendingConfirmation = null,
                    pendingRemoteSendDisclosure = null,
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(runId),
                    statusText = "远程连接不可用",
                )
            }
            return
        }
        if (useRemoteModel &&
            responsePrivacy == MessagePrivacy.RemoteEligible &&
            remoteConfig.isConfigured &&
            !remoteSendConfirmed &&
            shouldRequireRemoteSendDisclosure()
        ) {
            pendingRemoteContinuation = PendingRemoteContinuation(
                runId = runId,
                promptForModel = promptForModel,
                responsePrivacy = responsePrivacy,
                remoteToolScope = remoteToolScope,
            )
            val disclosure = buildPendingRemoteSendDisclosure(
                kind = RemoteSendDisclosureKind.ToolResultContinuation,
                prompt = promptForModel,
                messagePrivacy = responsePrivacy,
                remoteConfig = remoteConfig,
                remoteHistory = remoteHistory,
                imageAttachments = emptyList(),
                stateBeforeSend = stateAtStart,
            )
            savePendingRemoteSendMarker(disclosure, runId = runId)
            _uiState.update {
                it.copy(
                    pendingRemoteSendDisclosure = disclosure,
                    isBusy = false,
                    isGenerating = false,
                    activeRunTimeline = activeRunTimelineFor(runId),
                    statusText = "远程续写待确认",
                )
            }
            return
        }
        activeGenerationRunId = runId
        val job = viewModelScope.launch(ioDispatcher) {
            try {
                if (useRemoteModel && responsePrivacy == MessagePrivacy.LocalOnly) {
                    runId?.let { id ->
                        assistantOrchestrator.failModelGeneration(id, "工具结果包含仅本地内容，未发送到远程模型")
                    }
                    _uiState.updateLastAssistant("工具结果包含仅本地内容。当前为远程模型模式，我不会把它发送到远程模型。")
                    persistActiveSessionFromUi()
                    rebuildMemoryIndex()
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isGenerating = false,
                            isReady = remoteConfig.isConfigured,
                            pendingConfirmation = null,
                            agentTraceRuns = loadAgentTraceRuns(),
                            activeRunTimeline = activeRunTimelineFor(runId),
                            statusText = "已保护工具结果",
                        )
                    }
                    return@launch
                }
                if (!useRemoteModel && !runtime.isLoaded) {
                    runId?.let { id ->
                        assistantOrchestrator.failModelGeneration(id, "本地模型尚未就绪")
                    }
                    _uiState.updateLastAssistant("模型尚未就绪，无法继续处理工具结果")
                    persistActiveSessionFromUi()
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isGenerating = false,
                            isReady = false,
                            pendingConfirmation = null,
                            agentTraceRuns = loadAgentTraceRuns(),
                            activeRunTimeline = activeRunTimelineFor(runId),
                            statusText = "未加载模型",
                        )
                    }
                    return@launch
                }
                if (useRemoteModel && !remoteConfig.isConfigured) {
                    runId?.let { id ->
                        assistantOrchestrator.failModelGeneration(id, "远程模型未配置")
                    }
                    _uiState.updateLastAssistant("请先在模型管理中配置远程模型地址和模型名")
                    persistActiveSessionFromUi()
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isGenerating = false,
                            isReady = false,
                            pendingConfirmation = null,
                            agentTraceRuns = loadAgentTraceRuns(),
                            activeRunTimeline = activeRunTimelineFor(runId),
                            statusText = "请配置远程模型",
                        )
                    }
                    return@launch
                }

                val partial = StringBuilder()
                var modelObservation: AgentModelObservationResult? = null
                var outputQualityDecision: GenerationQualityDecision? = null
                if (useRemoteModel) {
                    val remoteTools = assistantOrchestrator.availableRemoteToolSpecs(remoteToolScope)
                    runId?.let { id ->
                        assistantOrchestrator.recordRemoteToolsExposed(
                            runId = id,
                            scope = remoteToolScope,
                            toolNames = remoteTools.mapTo(linkedSetOf()) { tool -> tool.name },
                        )
                        val continuationReceipt = RunDataReceipt(
                            destination = RunDataDestination.Remote,
                            currentPromptPrivacy = responsePrivacy.name,
                            remoteHistoryCount = remoteHistory.size,
                            localOnlyHistoryFilteredCount = stateAtStart.messages.count { message ->
                                message.privacy == MessagePrivacy.LocalOnly
                            },
                            memoryHitCount = 0,
                            memoryContextIncluded = false,
                            deviceContextIncluded = false,
                            imageAttachmentCount = 0,
                            protectedSourceCount = stateAtStart.messages.count { message ->
                                message.privacy == MessagePrivacy.LocalOnly
                            },
                            rawContentPersisted = false,
                            protectedContentTypes = listOf(
                                "本地记忆",
                                "设备上下文",
                                "LocalOnly 历史",
                                "本地工具结果",
                            ),
                            deletableRecordTypes = listOf("对话消息", "Agent 轨迹"),
                        )
                        assistantOrchestrator.recordRunDataReceipt(
                            runId = id,
                            receipt = continuationReceipt,
                        )
                    }
                    if (!remoteSendConfirmed) {
                        recordRemoteSendAuditEvent(
                            decision = RemoteSendDecision.Confirmed,
                            modelName = remoteConfig.normalized().modelName,
                            prompt = promptForModel,
                            imageCount = 0,
                            remoteHistoryCount = remoteHistory.size,
                        )
                    }
                    val remoteTokenBudget = remoteConfig.modelProfile().contextWindowTokens
                        ?.let { window -> (window * REMOTE_COMPACTION_BUDGET_RATIO).toInt() }
                        ?: Int.MAX_VALUE
                    val result = sendRemoteWithOverflowRetry(
                        runId = runId,
                        history = remoteHistory,
                        tokenBudget = remoteTokenBudget,
                    ) { historyToSend ->
                        remoteRuntime.sendWithTools(
                            prompt = promptForModel,
                            history = historyToSend,
                            parameters = _uiState.value.generationParameters,
                            config = remoteConfig,
                            tools = remoteTools,
                        ).collect { event ->
                            if (outputQualityDecision != null) return@collect
                            when (event) {
                                is RemoteChatEvent.TextDelta -> {
                                    if (modelObservation == null) {
                                        val decision = appendGuardedGenerationChunk(
                                            partial = partial,
                                            chunk = event.text,
                                            runtimeKind = GenerationRuntimeKind.Remote,
                                            modelId = remoteConfig.modelProfile().id,
                                            backend = null,
                                            parameters = _uiState.value.generationParameters,
                                        )
                                        if (decision !is GenerationQualityDecision.Continue) {
                                            outputQualityDecision = decision
                                            remoteRuntime.stop()
                                        }
                                    }
                                }

                                is RemoteChatEvent.ToolCall -> {
                                    val id = runId ?: error("远程工具调用缺少 Agent run")
                                    modelObservation =
                                        assistantOrchestrator.observeModelToolRequest(id, event.request)
                                            ?: error("远程工具调用无法进入确认流程")
                                }

                                is RemoteChatEvent.ToolCalls -> {
                                    val id = runId ?: error("远程工具调用缺少 Agent run")
                                    modelObservation =
                                        assistantOrchestrator.observeModelToolRequests(id, event.requests)
                                            ?: error("远程批量工具调用无法进入执行流程")
                                }

                                is RemoteChatEvent.ParseError -> {
                                    val decision = outputQualityGuard.failClosedForFormatViolation(
                                        summary = event.summary,
                                        accumulatedText = partial.toString(),
                                        runtimeKind = GenerationRuntimeKind.Remote,
                                        modelId = remoteConfig.modelProfile().id,
                                        backend = null,
                                    )
                                    applyOutputQualityDecisionToAssistant(partial, decision)
                                    outputQualityDecision = decision
                                    remoteRuntime.stop()
                                }
                            }
                        }
                    }
                    remoteHistory = result.first
                } else {
                    collectLocalRuntimeResponse(
                        promptForModel = promptForModel,
                        history = stateAtStart.messages.dropLast(1),
                        parameters = _uiState.value.generationParameters,
                    ) { chunk ->
                        if (outputQualityDecision == null) {
                            val decision = appendGuardedGenerationChunk(
                                partial = partial,
                                chunk = chunk,
                                runtimeKind = GenerationRuntimeKind.Local,
                                modelId = _uiState.value.activeModelProfileId(),
                                backend = _uiState.value.backend,
                                parameters = _uiState.value.generationParameters,
                            )
                            if (decision !is GenerationQualityDecision.Continue) {
                                outputQualityDecision = decision
                                runtime.stop()
                            }
                        }
                    }
                }
                if (outputQualityDecision == null && modelObservation == null) {
                    val finalDecision = outputQualityGuard.evaluateCompleted(
                        output = partial.toString(),
                        runtimeKind = if (useRemoteModel) GenerationRuntimeKind.Remote else GenerationRuntimeKind.Local,
                        modelId = if (useRemoteModel) {
                            remoteConfig.modelProfile().id
                        } else {
                            _uiState.value.activeModelProfileId()
                        },
                        backend = if (useRemoteModel) null else _uiState.value.backend,
                    )
                    if (finalDecision !is GenerationQualityDecision.Continue) {
                        applyOutputQualityDecisionToAssistant(partial, finalDecision)
                        outputQualityDecision = finalDecision
                    }
                }
                outputQualityDecision?.let { decision ->
                    val continuationReceipt = if (useRemoteModel) {
                        RunDataReceipt(
                            destination = RunDataDestination.Remote,
                            currentPromptPrivacy = responsePrivacy.name,
                            remoteHistoryCount = remoteHistory.size,
                            localOnlyHistoryFilteredCount = stateAtStart.messages.count { message ->
                                message.privacy == MessagePrivacy.LocalOnly
                            },
                            memoryHitCount = 0,
                            memoryContextIncluded = false,
                            deviceContextIncluded = false,
                            imageAttachmentCount = 0,
                            protectedSourceCount = stateAtStart.messages.count { message ->
                                message.privacy == MessagePrivacy.LocalOnly
                            },
                            rawContentPersisted = false,
                            protectedContentTypes = listOf(
                                "本地记忆",
                                "设备上下文",
                                "LocalOnly 历史",
                                "本地工具结果",
                            ),
                            deletableRecordTypes = listOf("对话消息", "Agent 轨迹"),
                        )
                    } else {
                        RunDataReceipt(
                            destination = RunDataDestination.Local,
                            currentPromptPrivacy = responsePrivacy.name,
                            remoteHistoryCount = 0,
                            localOnlyHistoryFilteredCount = 0,
                            memoryHitCount = 0,
                            memoryContextIncluded = false,
                            deviceContextIncluded = false,
                            imageAttachmentCount = 0,
                            protectedSourceCount = 0,
                            rawContentPersisted = false,
                            protectedContentTypes = emptyList(),
                            deletableRecordTypes = listOf("对话消息", "Agent 轨迹"),
                        )
                    }
                    finishOutputQualityGuardedGeneration(
                        runId = runId,
                        decision = decision,
                        receipt = continuationReceipt,
                        useRemoteModel = useRemoteModel,
                    )
                    return@launch
                }
                if (modelObservation == null) {
                    if (partial.isBlank()) {
                        _uiState.updateLastAssistant("没有生成内容")
                    } else if (!useRemoteModel) {
                        _uiState.updateLastAssistantStats(
                            runCatching { runtime.lastGenerationStats() }.getOrNull(),
                        )
                    }
                    modelObservation = runId?.let { id ->
                        assistantOrchestrator.observeModelResult(
                            runId = id,
                            text = partial.toString(),
                            allowInlineToolCalls = !useRemoteModel,
                        )
                    }
                } else {
                    val remotePlan = (modelObservation?.decision as? AgentObservationDecision.PlanNextTool)?.plan
                    val remoteToolBatch =
                        (modelObservation?.decision as? AgentObservationDecision.PlanToolBatch)?.plans
                    if (remotePlan == null && remoteToolBatch == null) {
                        _uiState.updateLastAssistantLocalOnly(
                            "无法准备这个动作：${(modelObservation?.decision as? AgentObservationDecision.Fail)?.reason.orEmpty()}",
                        )
                    }
                }
                persistActiveSessionFromUi()
                rebuildMemoryIndex()
                val toolBatch = (modelObservation?.decision as? AgentObservationDecision.PlanToolBatch)?.plans
                if (toolBatch != null) {
                    val observedForBatch = modelObservation ?: error("远程批量工具调用缺少 Agent observation")
                    val assistantText = buildString {
                        if (partial.isNotBlank()) {
                            append(partial)
                            append("\n\n")
                        }
                        append("正在并行使用工具：${toolBatch.batchToolTitle()}")
                    }
                    _uiState.updateLastAssistantLocalOnly(assistantText)
                    persistActiveSessionFromUi()
                    executePublicEvidenceToolBatchAfterRunIsExecuting(
                        runId = observedForBatch.run.id,
                        plans = toolBatch,
                    )
                    return@launch
                }
                val nextToolPlan = (modelObservation?.decision as? AgentObservationDecision.PlanNextTool)?.plan
                if (nextToolPlan != null) {
                    val observedForPending = modelObservation ?: error("远程工具调用缺少 Agent observation")
                    val assistantText = buildString {
                        if (partial.isNotBlank()) {
                            append(partial)
                            append("\n\n")
                        }
                        if (nextToolPlan.requiresUserConfirmation()) {
                            val modelLabel = if (useRemoteModel) "远程" else "模型"
                            append("已准备${modelLabel}动作草稿：${nextToolPlan.draft.title}\n请确认后再执行。")
                        } else {
                            append("正在使用工具：${nextToolPlan.draft.title}")
                        }
                    }
                    _uiState.updateLastAssistantLocalOnly(assistantText)
                    persistActiveSessionFromUi()
                    handleNextToolPlan(
                        runId = observedForPending.run.id,
                        plan = nextToolPlan,
                        pendingStatusText = "下一步动作待确认",
                    )
                    return@launch
                }
                val observedForContinuation = modelObservation
                val continuationPrompt = observedForContinuation?.continuationPromptForModel
                if (observedForContinuation?.decision is AgentObservationDecision.ContinueWithModel &&
                    continuationPrompt != null
                ) {
                    _uiState.updateLastAssistant("正在根据公开来源补充引用…")
                    persistActiveSessionFromUi()
                    continueAfterToolObservation(
                        runId = observedForContinuation.run.id,
                        promptForModel = continuationPrompt,
                        responsePrivacy = responsePrivacy,
                        remoteToolScope = observedForContinuation.continuationRemoteToolScope,
                    )
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isGenerating = false,
                        isReady = true,
                        auditEvents = loadAuditEvents(),
                        agentTraceRuns = loadAgentTraceRuns(),
                        activeRunTimeline = activeRunTimelineFor(runId),
                        activePublicWebEvidence = activePublicWebEvidenceFor(runId),
                        statusText = when (modelObservation?.decision) {
                            is AgentObservationDecision.Fail -> "后续动作不可执行"
                            else -> if (useRemoteModel) {
                                "就绪 · 远程"
                            } else {
                                "就绪 · ${it.backend.label()}"
                            }
                        },
                    )
                }
            } catch (cancellation: CancellationException) {
                cancelActiveGenerationRun(runId)
                finishStoppedGeneration(runId)
                throw cancellation
            } catch (throwable: Throwable) {
                val errorMessage = throwable.generationFailureMessage(useRemoteModel)
                runId?.let { id ->
                    assistantOrchestrator.failModelGeneration(id, errorMessage)
                }
                _uiState.updateLastAssistant("出错了：$errorMessage")
                persistActiveSessionFromUi()
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isGenerating = false,
                        isReady = useRemoteModel && remoteConfig.isConfigured,
                        pendingConfirmation = null,
                        agentTraceRuns = loadAgentTraceRuns(),
                        activeRunTimeline = activeRunTimelineFor(runId),
                        modelHealth = it.failedGenerationModelHealth(
                            useRemoteModel = useRemoteModel,
                            reason = errorMessage,
                        ),
                        statusText = if (useRemoteModel) {
                            "远程生成失败"
                        } else {
                            "生成失败，建议重新加载"
                        },
                    )
                }
            }
        }
        generationJob = job
        job.invokeOnCompletion {
            if (generationJob == job) {
                generationJob = null
                activeGenerationRunId = null
            }
        }
    }

    fun dismissAgentConfirmation(confirmation: PendingAgentConfirmation? = _uiState.value.pendingConfirmation) {
        val pendingConfirmation = _uiState.value.pendingConfirmation
        if (confirmation == null || pendingConfirmation == null) {
            _uiState.update {
                it.copy(statusText = "工具确认已处理")
            }
            return
        }
        if (!pendingConfirmation.matchesExecution(confirmation)) {
            _uiState.update {
                it.copy(statusText = "工具确认已处理")
            }
            return
        }
        val request = pendingConfirmation.toolRequest ?: ToolRequest(
            toolName = pendingConfirmation.draft.functionName,
            arguments = pendingConfirmation.draft.parameters,
            reason = pendingConfirmation.draft.summary,
        )
        val observation = if (pendingConfirmation.runId != null) {
            assistantOrchestrator.cancelToolRequest(pendingConfirmation.runId, request.id)
        } else {
            null
        }
        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(pendingConfirmation.runId),
                statusText = observation?.assistantMessage ?: "已取消动作草稿",
            )
        }
    }

    private fun remoteHistoryForRemoteSend(messages: List<ChatMessage>): List<ChatMessage> =
        messages.remoteEligibleMessages()
            .filterNot { message ->
                outboundSafetyPolicy.containsSensitivePersonalOrSecretContent(message.text)
            }

    /**
     * Decides whether the remote-send disclosure sheet must be shown before an ordinary,
     * non-sensitive send. Sensitive payloads are intercepted earlier in [sendMessageInternal]
     * and always require an audited choice.
     */
    private fun shouldRequireRemoteSendDisclosure(imageAttachmentCount: Int = 0): Boolean {
        if (imageAttachmentCount > 0) return true
        if (!requireRemoteSendDisclosure) return false
        return when (_uiState.value.remoteSendDisclosurePolicy) {
            RemoteSendDisclosurePolicy.OnRemoteModeSwitch -> false
            RemoteSendDisclosurePolicy.OnlyWhenSensitive -> false
            RemoteSendDisclosurePolicy.OncePerSession ->
                !remoteSendDisclosureSuppressedForSession
            RemoteSendDisclosurePolicy.EveryMessage ->
                true
        }
    }

    /**
     * Resets the session-scoped disclosure suppression. Called whenever the remote trust
     * boundary changes (session switch, inference-mode change, remote config change) so a
     * previously granted "don't ask again this session" never silently carries over to a
     * different destination or context.
     */
    private fun resetRemoteSendDisclosureSuppression() {
        remoteSendDisclosureSuppressedForSession = false
    }

    /** Updates the remote-send disclosure cadence policy and clears any session suppression. */
    fun setRemoteSendDisclosurePolicy(policy: RemoteSendDisclosurePolicy) {
        resetRemoteSendDisclosureSuppression()
        _uiState.update { it.copy(remoteSendDisclosurePolicy = policy) }
    }

    private fun buildPendingRemoteSendDisclosure(
        kind: RemoteSendDisclosureKind,
        prompt: String,
        messagePrivacy: MessagePrivacy,
        remoteConfig: RemoteModelConfig,
        remoteHistory: List<ChatMessage>,
        imageAttachments: List<ChatImageAttachment>,
        stateBeforeSend: ChatUiState,
    ): PendingRemoteSendDisclosure =
        PendingRemoteSendDisclosure(
            kind = kind,
            forcedBySensitiveContent = false,
            prompt = prompt,
            messagePrivacy = messagePrivacy,
            remoteHost = remoteConfig.destinationHostLabel(),
            remoteModelName = remoteConfig.normalized().modelName.ifBlank { "未命名远程模型" },
            remoteHistoryCount = remoteHistory.size,
            localOnlyHistoryFilteredCount = stateBeforeSend.messages.count { message ->
                message.privacy == MessagePrivacy.LocalOnly
            },
            imageAttachmentCount = imageAttachments.size,
            protectedSourceCount = stateBeforeSend.messages.count { message ->
                message.privacy == MessagePrivacy.LocalOnly
            },
            apiKeyConfigured = remoteConfig.apiKey.isNotBlank(),
            connectivityStatus = remoteConfig.connectivityStatus,
            imageAttachments = imageAttachments,
            promptPreview = prompt.toRemoteSendPromptPreview(),
            sensitiveHitCategories = outboundSafetyPolicy
                .detectSensitiveCategories(prompt)
                .map { it.label },
            sensitiveHitSnippets = outboundSafetyPolicy.detectSensitiveSnippets(prompt),
        )

    /**
     * Builds a forced disclosure for a sensitive send that offers graded handling (mask & send /
     * send anyway). Always [PendingRemoteSendDisclosure.forcedBySensitiveContent] so it can
     * never be silenced, and carries the masked preview so the user sees exactly what
     * "mask & send" would transmit.
     */
    private fun buildSensitiveRemoteSendDisclosure(
        prompt: String,
        messagePrivacy: MessagePrivacy,
        remoteConfig: RemoteModelConfig,
        remoteHistory: List<ChatMessage>,
        imageAttachments: List<ChatImageAttachment>,
        stateBeforeSend: ChatUiState,
    ): PendingRemoteSendDisclosure {
        val base = buildPendingRemoteSendDisclosure(
            kind = RemoteSendDisclosureKind.CurrentInput,
            prompt = prompt,
            messagePrivacy = messagePrivacy,
            remoteConfig = remoteConfig,
            remoteHistory = remoteHistory,
            imageAttachments = imageAttachments,
            stateBeforeSend = stateBeforeSend,
        )
        val maskResult = outboundSafetyPolicy.maskSensitiveContent(prompt)
        return base.copy(
            forcedBySensitiveContent = true,
            allowMaskedSend = maskResult.didMask,
            maskedPrompt = if (maskResult.didMask) maskResult.maskedText else "",
            maskedPromptPreview = if (maskResult.didMask) {
                maskResult.maskedText.toRemoteSendPromptPreview()
            } else {
                ""
            },
        )
    }

    private fun String.toRemoteSendPromptPreview(): String {
        val collapsed = trim().replace(Regex("""\s+"""), " ")
        return if (collapsed.length <= REMOTE_SEND_PROMPT_PREVIEW_MAX_CHARS) {
            collapsed
        } else {
            collapsed.take(REMOTE_SEND_PROMPT_PREVIEW_MAX_CHARS).trimEnd() + "…"
        }
    }

    private fun AgentObservationResult?.privacyForObservation(
        fallbackToolName: String? = null,
    ): MessagePrivacy {
        if (this == null) return privacyForPublicEvidenceToolName(fallbackToolName)
        if (continuationRequiresLocalModel) return MessagePrivacy.LocalOnly

        result.data["privacy"]?.let { declaredPrivacy ->
            return runCatching { MessagePrivacy.valueOf(declaredPrivacy) }
                .getOrDefault(MessagePrivacy.LocalOnly)
        }

        return privacyForPublicEvidenceToolName(result.data["toolName"] ?: fallbackToolName)
    }

    private fun privacyForPublicEvidenceToolName(toolName: String?): MessagePrivacy {
        val isInternalPublicEvidenceBatch = toolName == PUBLIC_EVIDENCE_BATCH_TOOL_NAME
        val isRegisteredPublicEvidence =
            toolName != null && toolRegistry.specFor(toolName)?.isEligibleForParallelBatch() == true
        return if (isInternalPublicEvidenceBatch || isRegisteredPublicEvidence) {
            MessagePrivacy.RemoteEligible
        } else {
            MessagePrivacy.LocalOnly
        }
    }

    private fun ToolRequest.protectedContinuationContentName(): String =
        when (toolName) {
            MobileActionFunctions.READ_CLIPBOARD -> "剪贴板内容"
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR -> "截图 OCR 内容"
            MobileActionFunctions.READ_RECENT_IMAGE_OCR -> "图片 OCR 内容"
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT -> "当前屏幕文本"
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR -> "当前屏幕截图 OCR 内容"
            else -> "本地工具结果"
        }

    fun confirmActionDraft(draft: ActionDraft) {
        confirmAgentConfirmation(
            PendingAgentConfirmation(
                runId = null,
                draft = draft,
                toolRequest = null,
                skillId = null,
                plannedByModel = false,
                fallbackReason = null,
            ),
        )
    }

    fun dismissActionDraft() {
        dismissAgentConfirmation()
    }

    override fun onCleared() {
        downloadMonitorJob?.cancel()
        remoteRuntime.stop()
        runtime.close()
        assistantOrchestrator.close()
        super.onCleared()
    }

    private fun beginModelDownload(source: ModelDownloadSource): Boolean {
        refreshDeviceStatus()
        if (_uiState.value.isBusy || _uiState.value.isDownloading) return false
        if (blockDownloadForMissingHuggingFaceAuthorization(source)) return false
        if (!isArm64Device()) {
            _uiState.update {
                it.copy(statusText = "当前设备不是 64 位 ARM，无法运行此模型")
            }
            return false
        }

        val target = modelRepository.downloadedModelFile(source.fileName)
        if (target == null) {
            _uiState.update {
                it.copy(statusText = "下载目录不可用，请导入已有模型")
            }
            return false
        }
        if (source.expectedBytes != null && target.exists() && source.hasExpectedSize(target.length())) {
            verifyAndRegisterDownloadedModel(target, source)
            return true
        }
        if (target.exists() && !target.delete()) {
            _uiState.update {
                it.copy(statusText = "无法清理未完成的下载")
            }
            return false
        }

        val modelParent = target.parentFile
        if (modelParent == null || (!modelParent.exists() && !modelParent.mkdirs())) {
            _uiState.update {
                it.copy(statusText = "无法创建模型下载目录")
            }
            return false
        }
        val requiredBytes = source.expectedBytes ?: DEFAULT_CHAT_MODEL_BYTES
        if (!ModelCatalog.hasEnoughSpace(modelParent.usableSpace, requiredBytes)) {
            _uiState.update {
                it.copy(statusText = "存储空间不足，至少需要约 ${ModelCatalog.formatBytes(requiredBytes)}")
            }
            return false
        }

        val isFirstRunSetupDownload = setupDownloadInProgress
        if (!isFirstRunSetupDownload) {
            firstRunSetupRepository.markSetupDismissed()
        }
        _uiState.update {
            it.copy(
                isBusy = true,
                isPreparingDownload = true,
                isDownloading = false,
                downloadProgressPercent = null,
                downloadedBytes = 0L,
                totalBytes = 0L,
                statusText = if (source.requiresHuggingFaceAuthorization) {
                    "正在验证 Hugging Face 授权"
                } else {
                    "正在准备模型下载"
                },
                isReady = false,
                showFirstRunSetup = false,
            )
        }
        val preflightJob = viewModelScope.launch(ioDispatcher) {
            val downloadResult = downloadService.enqueue(source, target)
            if (!isActive) {
                downloadResult.getOrNull()?.let(downloadService::cancel)
                return@launch
            }
            downloadPreflightJob = null
            if (downloadResult.isFailure) {
                val throwable = downloadResult.exceptionOrNull()
                setupDownloadQueue.clear()
                setupDownloadInProgress = false
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isPreparingDownload = false,
                        isDownloading = false,
                        downloadProgressPercent = null,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        statusText = "下载启动失败：${throwable?.cleanMessage() ?: "未知错误"}",
                    )
                }
                return@launch
            }
            val downloadId = downloadResult.getOrThrow()
            activeDownloadId = downloadId
            modelRepository.savePendingDownload(downloadId, source)
            _uiState.update {
                it.copy(
                    isBusy = true,
                    isPreparingDownload = false,
                    isDownloading = true,
                    downloadProgressPercent = null,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    statusText = "模型下载中",
                    isReady = false,
                    showFirstRunSetup = false,
                )
            }
            monitorDownload(downloadId, target, source)
        }
        downloadPreflightJob = preflightJob
        preflightJob.invokeOnCompletion {
            if (downloadPreflightJob == preflightJob) {
                downloadPreflightJob = null
            }
        }
        return true
    }

    private fun blockDownloadForMissingHuggingFaceAuthorization(source: ModelDownloadSource): Boolean {
        if (!source.requiresHuggingFaceAuthorization || huggingFaceAuthStore.hasAccessToken()) return false
        setupDownloadQueue.clear()
        setupDownloadInProgress = false
        _uiState.update {
            it.copy(
                pendingHuggingFaceAuthorizationModelId = source.modelId,
                huggingFaceAccessTokenConfigured = false,
                statusText = "需要先登录 Hugging Face、接受模型许可，并保存 read token 后再下载",
            )
        }
        return true
    }

    private fun monitorDownload(
        downloadId: Long,
        targetFile: File,
        source: ModelDownloadSource,
    ) {
        downloadMonitorJob?.cancel()
        activeDownloadId = downloadId
        downloadMonitorJob = viewModelScope.launch(ioDispatcher) {
            while (isActive && activeDownloadId == downloadId) {
                val info = downloadService.query(downloadId)
                if (info == null) {
                    if (activeDownloadId == downloadId) {
                        activeDownloadId = null
                        modelRepository.clearPendingDownload()
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                isDownloading = false,
                                downloadProgressPercent = null,
                                statusText = "下载任务不存在",
                            )
                        }
                    }
                    return@launch
                }
                if (!isActive || activeDownloadId != downloadId) {
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        isBusy = true,
                        isDownloading = true,
                        downloadProgressPercent = info.progressPercent,
                        downloadedBytes = info.downloadedBytes,
                        totalBytes = info.totalBytes,
                        statusText = info.statusText,
                    )
                }

                when (info.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        activeDownloadId = null
                        if (!targetFile.exists() || !source.hasExpectedSize(targetFile.length())) {
                            modelRepository.clearPendingDownload()
                            targetFile.delete()
                            _uiState.update {
                                it.copy(
                                    isBusy = false,
                                    isDownloading = false,
                                    downloadProgressPercent = null,
                                    statusText = "下载文件不可用，请重新下载",
                                )
                            }
                            return@launch
                        }
                        _uiState.update {
                            it.copy(
                                isBusy = true,
                                isDownloading = false,
                                downloadProgressPercent = null,
                                statusText = "正在校验模型文件",
                            )
                        }
                        val verifiedSha256 = source.verifiedSha256(targetFile).getOrElse { throwable ->
                            modelRepository.clearPendingDownload()
                            setupDownloadQueue.clear()
                            setupDownloadInProgress = false
                            targetFile.delete()
                            _uiState.update {
                                it.copy(
                                    isBusy = false,
                                    isDownloading = false,
                                    downloadProgressPercent = null,
                                    downloadedBytes = 0L,
                                    totalBytes = 0L,
                                    statusText = throwable.cleanMessage(),
                                )
                            }
                            return@launch
                        }
                        modelRepository.clearPendingDownload()
                        if (source.registerInstalledModel) {
                            modelRepository.registerInstalledModel(
                                path = targetFile.absolutePath,
                                displayName = source.installedDisplayName(targetFile),
                                recommendedModelId = source.modelId,
                                verifiedSha256 = verifiedSha256,
                                verificationStatus = if (source.modelId == null) {
                                    ModelVerificationStatus.UnverifiedCustom
                                } else {
                                    ModelVerificationStatus.VerifiedRecommended
                                },
                            )
                        }
                        updateModelState(modelRepository.currentState())
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                isDownloading = false,
                                downloadProgressPercent = null,
                                downloadedBytes = 0L,
                                totalBytes = 0L,
                                statusText = "模型下载完成",
                            )
                        }
                        continueSetupDownloadOrLoad(source)
                        return@launch
                    }

                    DownloadManager.STATUS_FAILED -> {
                        val restoreFirstRunSetup = shouldRestoreFirstRunSetupAfterDownloadFailure()
                        activeDownloadId = null
                        setupDownloadQueue.clear()
                        setupDownloadInProgress = false
                        modelRepository.clearPendingDownload()
                        targetFile.delete()
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                isDownloading = false,
                                downloadProgressPercent = null,
                                downloadedBytes = 0L,
                                totalBytes = 0L,
                                statusText = downloadFailureStatusText(info.reasonText, source),
                                showFirstRunSetup = restoreFirstRunSetup,
                            )
                        }
                        return@launch
                    }
                }

                delay(1_000L)
            }
        }
    }

    private fun verifyAndRegisterDownloadedModel(
        targetFile: File,
        source: ModelDownloadSource,
    ) {
        _uiState.update {
            it.copy(
                isBusy = true,
                isDownloading = false,
                downloadProgressPercent = null,
                statusText = "正在校验模型文件",
                isReady = false,
            )
        }
        viewModelScope.launch(ioDispatcher) {
            val verifiedSha256 = source.verifiedSha256(targetFile).getOrElse { throwable ->
                targetFile.delete()
                setupDownloadQueue.clear()
                setupDownloadInProgress = false
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isDownloading = false,
                        downloadProgressPercent = null,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        statusText = throwable.cleanMessage(),
                    )
                }
                return@launch
            }
            if (source.registerInstalledModel) {
                modelRepository.registerInstalledModel(
                    path = targetFile.absolutePath,
                    displayName = source.installedDisplayName(targetFile),
                    recommendedModelId = source.modelId,
                    verifiedSha256 = verifiedSha256,
                    verificationStatus = if (source.modelId == null) {
                        ModelVerificationStatus.UnverifiedCustom
                    } else {
                        ModelVerificationStatus.VerifiedRecommended
                    },
                )
            }
            firstRunSetupRepository.markSetupDismissed()
            updateModelState(modelRepository.currentState())
            _uiState.update {
                it.copy(
                    isBusy = false,
                    isDownloading = false,
                    downloadProgressPercent = null,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    statusText = "模型校验通过",
                    showFirstRunSetup = false,
                )
            }
            continueSetupDownloadOrLoad(source)
        }
    }

    private fun continueSetupDownloadOrLoad(completedSource: ModelDownloadSource) {
        if (setupDownloadQueue.isNotEmpty()) {
            val nextSource = setupDownloadQueue.removeFirst()
            if (!beginModelDownload(nextSource)) {
                setupDownloadQueue.clear()
                setupDownloadInProgress = false
            }
            return
        }

        val completedCapability = completedSource.modelId
            ?.let { ModelCatalog.recommendedModelById(it).capability }
            ?: ModelCapability.Chat
        if (setupDownloadInProgress) {
            setupDownloadInProgress = false
            _uiState.update {
                it.copy(statusText = "基础能力包已准备")
            }
        }
        rebuildMemoryIndex()
        if (
            (completedCapability == ModelCapability.Chat || _uiState.value.modelPath != null) &&
            _uiState.value.modelPath != null &&
            !_uiState.value.isReady
        ) {
            loadModel()
        }
        if (completedCapability == ModelCapability.Chat || _uiState.value.modelPath != null) {
            firstRunSetupRepository.markSetupDismissed()
            _uiState.update {
                it.copy(showFirstRunSetup = false)
            }
        }
    }

    private fun shouldRestoreFirstRunSetupAfterDownloadFailure(): Boolean =
        setupDownloadInProgress && _uiState.value.modelPath == null && !_uiState.value.isReady

    private fun recreateConversationForActiveSession(successPrefix: String) {
        val sessionId = sessionRepository.activeSessionId
        val history = sessionRepository.activeMessages()
        val restoreGeneration = nextSessionRestoreGeneration()
        recreateConversationForMessages(
            successPrefix = successPrefix,
            sessionId = sessionId,
            messages = history,
            restoreGeneration = restoreGeneration,
        )
    }

    private suspend fun collectLocalRuntimeResponse(
        promptForModel: String,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        imageAttachments: List<LocalImageAttachment> = emptyList(),
        onChunk: (String) -> Unit,
    ) {
        val policyDecision = AdaptiveGenerationPolicy.decide(
            AdaptiveGenerationPolicyInput(
                preferredBackend = _uiState.value.backend,
                contextWindowTokens = _uiState.value.localMaxTotalTokens,
                lastGenerationStats = _uiState.value.lastGenerationStatsForAdaptivePolicy(),
                qualityIssue = _uiState.value.lastOutputQualityIssueForAdaptivePolicy(),
                requestedImageCount = imageAttachments.size,
            ),
        )
        val effectiveImageAttachments = imageAttachments.take(policyDecision.maxImages)
        runtimeLock.withLock {
            runtime.recreateConversationForSend(
                history = history,
                prompt = promptForModel,
                parameters = parameters,
                maxInputTokens = policyDecision.maxInputTokens,
            )
            runtime.send(
                LocalModelRequest(
                    prompt = promptForModel,
                    imageAttachments = effectiveImageAttachments,
                ),
            ).collect { chunk ->
                onChunk(chunk)
            }
        }
    }

    private fun appendGuardedGenerationChunk(
        partial: StringBuilder,
        chunk: String,
        runtimeKind: GenerationRuntimeKind,
        modelId: String?,
        backend: BackendChoice?,
        parameters: GenerationParameters,
    ): GenerationQualityDecision {
        val decision = outputQualityGuard.evaluate(
            accumulatedText = partial.toString(),
            latestChunk = chunk,
            runtimeKind = runtimeKind,
            modelId = modelId,
            backend = backend,
            parameters = parameters,
        )
        if (decision is GenerationQualityDecision.Continue) {
            partial.append(chunk)
            _uiState.updateLastAssistant(partial.toString())
        } else {
            applyOutputQualityDecisionToAssistant(partial, decision)
        }
        return decision
    }

    private fun applyOutputQualityDecisionToAssistant(
        partial: StringBuilder,
        decision: GenerationQualityDecision,
    ) {
        val report = decision.reportOrNull() ?: return
        partial.clear()
        partial.append(report.safePrefix)
        _uiState.updateLastAssistantLocalOnly(report.visibleAssistantText())
    }

    private fun finishOutputQualityGuardedGeneration(
        runId: String?,
        decision: GenerationQualityDecision,
        receipt: RunDataReceipt,
        useRemoteModel: Boolean,
    ) {
        val report = decision.reportOrNull() ?: return
        persistActiveSessionFromUi()
        runId?.let { id ->
            recordOutputQualityGuardTriggered(
                runId = id,
                decision = decision,
                receipt = receipt,
            )
            if (decision is GenerationQualityDecision.FailClosed) {
                assistantOrchestrator.failModelGeneration(id, report.visibleNotice)
            } else {
                assistantOrchestrator.cancelRun(id, report.visibleNotice)
            }
        }
        val checkedAtMillis = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                isBusy = false,
                isGenerating = false,
                isReady = if (useRemoteModel) {
                    it.remoteModelConfig.isConfigured
                } else {
                    runtime.isLoaded
                },
                pendingConfirmation = null,
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(runId),
                modelHealth = it.modelHealth.copy(
                    profileId = report.modelId ?: if (useRemoteModel) {
                        it.remoteModelConfig.modelProfile().id
                    } else {
                        it.activeModelProfileId()
                    },
                    backend = report.backend ?: it.modelHealth.backend,
                    lastOutputQualityIssue = report.issue.name,
                    lastOutputQualityRule = report.triggeredRule,
                    lastOutputQualityAtMillis = checkedAtMillis,
                ),
                statusText = when (report.issue) {
                    GenerationQualityIssue.EmptyOutput -> "模型没有生成内容"
                    GenerationQualityIssue.FormatViolation -> "模型输出格式已拦截"
                    else -> "模型输出已停止"
                },
            )
        }
    }

    private fun recordOutputQualityGuardTriggered(
        runId: String,
        decision: GenerationQualityDecision,
        receipt: RunDataReceipt,
    ) {
        val report = decision.reportOrNull() ?: return
        assistantOrchestrator.recordRunDataReceipt(runId, receipt.withOutputQualityDecision(decision))
        assistantOrchestrator.recordModelOutputQualityGuardTriggered(runId, report.toTrace(decision))
    }

    private fun recreateConversationForMessages(
        successPrefix: String,
        sessionId: String,
        messages: List<ChatMessage>,
        restoreGeneration: Long,
    ) {
        sessionRestoreJob?.cancel()
        _uiState.update {
            if (it.activeSessionId == sessionId) {
                it.copy(
                    isReady = false,
                    statusText = "正在恢复会话",
                    modelHealth = it.modelHealth.copy(
                        state = ModelHealthState.Loading,
                        backend = it.backend,
                    ),
                )
            } else {
                it
            }
        }
        sessionRestoreJob = viewModelScope.launch(ioDispatcher) {
            val result = runCatching {
                runtimeLock.withLock {
                    runtime.recreateConversation(
                        history = messages,
                        parameters = _uiState.value.generationParameters,
                    )
                }
            }
            result.fold(
                onSuccess = {
                    _uiState.update { current ->
                        if (current.activeSessionId == sessionId && restoreGeneration == sessionRestoreGeneration) {
                            current.copy(
                                isReady = true,
                                statusText = "$successPrefix · ${current.backend.label()}",
                                modelHealth = current.modelHealth.copy(
                                    state = ModelHealthState.Loaded,
                                    backend = current.backend,
                                    failureReason = null,
                                ),
                            )
                        } else {
                            current
                        }
                    }
                },
                onFailure = { throwable ->
                    _uiState.update { current ->
                        if (current.activeSessionId == sessionId && restoreGeneration == sessionRestoreGeneration) {
                            current.copy(
                                isReady = false,
                                statusText = "恢复会话失败：${throwable.cleanMessage()}",
                                modelHealth = current.modelHealth.copy(
                                    state = ModelHealthState.LoadFailed,
                                    backend = current.backend,
                                    failureReason = throwable.cleanMessage(),
                                ),
                            )
                        } else {
                            current
                        }
                    }
                },
            )
        }
    }

    private fun nextSessionRestoreGeneration(): Long {
        sessionRestoreGeneration += 1
        return sessionRestoreGeneration
    }

    private fun createInitialState(): ChatUiState {
        val modelState = modelRepository.currentState()
        val backend = generationParametersRepository.loadBackend()
        val memoryEnabled = firstRunSetupRepository.isMemoryEnabled()
        val reduceDeviceActionConfirmations = firstRunSetupRepository.reduceDeviceActionConfirmations()
        val inferenceMode = remoteModelRepository.loadMode()
        val remoteConfig = remoteModelRepository.loadConfig()
        val hasUsableEndpoint = hasStartupModelEndpoint(modelState, remoteConfig)
        val showFirstRunSetup = !firstRunSetupRepository.isSetupDismissed() && !hasUsableEndpoint
        memoryRepository.enabled = memoryEnabled
        syncTaskStateMemories(memoryEnabled = memoryEnabled)
        syncSemanticMemoryRuntime()
        return ChatUiState(
            modelPath = modelState.activeModelPath,
            activeInstalledModelId = modelState.activeInstalledModelId,
            installedModels = modelState.installedModels,
            selectedModelId = modelState.selectedModelId,
            inferenceMode = inferenceMode,
            remoteModelConfig = remoteConfig,
            backend = backend,
            localMaxTotalTokens = modelState.localContextWindowTokens(),
            localPreferredBackends = modelState.localPreferredBackends(),
            modelHealth = modelState.modelHealthForCurrentSelection(backend),
            showFirstRunSetup = showFirstRunSetup,
            memoryEnabled = memoryEnabled,
            reduceDeviceActionConfirmations = reduceDeviceActionConfirmations,
            semanticMemoryEnabled = currentSemanticMemoryEnabled(),
            semanticMemoryRuntimeStatus = currentSemanticMemoryRuntimeStatus(),
            semanticMemoryIndexedRecordCount = currentSemanticMemoryIndexedRecordCount(),
            semanticMemoryLastRebuiltAtMillis = currentSemanticMemoryLastRebuiltAtMillis(),
            huggingFaceAccessTokenConfigured = huggingFaceAuthStore.hasAccessToken(),
            longTermMemories = loadLongTermMemories(),
            backgroundTasks = loadBackgroundTasks(),
            backgroundTaskHistory = loadBackgroundTaskHistory(),
            periodicCheckPolicy = loadPeriodicCheckPolicy(),
            auditEvents = loadAuditEvents(),
            remoteSendAuditEvents = loadRemoteSendAuditEvents(),
            agentTraceRuns = loadAgentTraceRuns(),
            generationParameters = generationParametersRepository.load(),
            sessions = sessionRepository.summaries(),
            activeSessionId = sessionRepository.activeSessionId,
            messages = sessionRepository.activeMessages(),
            isArm64Supported = isArm64Device(),
            availableModelStorageBytes = modelRepository.resolveModelStorageBytes(),
            statusText = if (!hasUsableEndpoint) {
                NO_MODEL_READY_STATUS_TEXT
            } else {
                "未加载模型"
            },
        )
    }

    private fun hasStartupModelEndpoint(
        modelState: ModelSelectionState,
        remoteConfig: RemoteModelConfig,
    ): Boolean =
        modelState.activeModelPath != null || remoteConfig.isConfigured

    private fun ModelSelectionState.activeLocalCapabilityProfile(): ModelCapabilityProfile? =
        installedModels
            .firstOrNull { model -> model.id == activeInstalledModelId }
            ?.capabilityProfile

    private fun ModelSelectionState.localContextWindowTokens(): Int =
        activeLocalCapabilityProfile()?.contextWindowTokens
            ?: LocalModelTokenLimits.MAX_TOTAL_TOKENS

    private fun ModelSelectionState.localPreferredBackends(): Set<BackendChoice> =
        activeLocalCapabilityProfile()?.preferredLocalBackends.orEmpty()

    private fun updateModelState(modelState: ModelSelectionState) {
        syncSemanticMemoryRuntime()
        _uiState.update {
            it.copy(
                modelPath = modelState.activeModelPath,
                activeInstalledModelId = modelState.activeInstalledModelId,
                installedModels = modelState.installedModels,
                selectedModelId = modelState.selectedModelId,
                localMaxTotalTokens = modelState.localContextWindowTokens(),
                localPreferredBackends = modelState.localPreferredBackends(),
                modelHealth = modelState.modelHealthForCurrentSelection(it.backend),
                semanticMemoryEnabled = currentSemanticMemoryEnabled(),
                semanticMemoryRuntimeStatus = currentSemanticMemoryRuntimeStatus(),
                semanticMemoryIndexedRecordCount = currentSemanticMemoryIndexedRecordCount(),
                semanticMemoryLastRebuiltAtMillis = currentSemanticMemoryLastRebuiltAtMillis(),
                huggingFaceAccessTokenConfigured = huggingFaceAuthStore.hasAccessToken(),
            )
        }
    }

    private fun downloadFailureStatusText(reasonText: String, source: ModelDownloadSource): String =
        if (source.requiresHuggingFaceAuthorization) {
            "Hugging Face 下载失败：请确认已登录、已接受模型许可，且 read token 有效（$reasonText）"
        } else {
            "下载失败：$reasonText"
        }

    private fun verifyLegacyModelsOnStartup(skipModelRuntimeWork: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val changed = modelRepository.verifyLegacyRecommendedModels()
            if (!changed) return@launch
            val state = modelRepository.currentState()
            updateModelState(state)
            val localMode = _uiState.value.inferenceMode == InferenceMode.Local
            val hasFailedLegacy = state.installedModels.any {
                it.verificationStatus == ModelVerificationStatus.FailedVerification
            }
            when {
                !localMode || skipModelRuntimeWork -> Unit
                state.activeModelPath != null && !_uiState.value.isBusy && !_uiState.value.isReady -> {
                    _uiState.update { it.copy(statusText = "旧模型校验通过，正在加载") }
                    loadModel()
                }
                state.activeModelPath == null && hasFailedLegacy -> {
                    _uiState.update { it.copy(statusText = "旧模型校验失败，请重新下载或重新导入") }
                }
            }
        }
    }

    private fun updateRemoteReadiness(
        prefix: String,
        showModeDisclosure: Boolean = false,
    ) {
        val config = _uiState.value.remoteModelConfig
        pendingRemoteContinuation = null
        pendingSharedInputRemoteSendRestore = null
        remoteSendPendingStore.clearPendingRemoteSend()
        val modeDisclosure = if (showModeDisclosure) {
            buildRemoteModeDisclosure(config)
        } else {
            null
        }
        _uiState.update {
            it.copy(
                inferenceMode = InferenceMode.Remote,
                pendingRemoteModeDisclosure = modeDisclosure,
                pendingRemoteSendDisclosure = null,
                isBusy = false,
                isDownloading = false,
                isReady = config.isConfigured,
                statusText = if (modeDisclosure != null) {
                    "远程模式待确认"
                } else if (config.isConfigured) {
                    if (prefix.endsWith("远程模型")) "${prefix}已就绪" else prefix
                } else {
                    "请配置远程模型"
                },
                modelHealth = ModelHealth(
                    profileId = config.modelProfile().id,
                    state = if (config.isConfigured) ModelHealthState.Loaded else ModelHealthState.LoadFailed,
                    failureReason = if (config.isConfigured) null else "远程模型未配置",
                ),
            )
        }
    }

    private fun buildRemoteModeDisclosure(config: RemoteModelConfig): PendingRemoteModeDisclosure {
        val normalized = config.normalized()
        return PendingRemoteModeDisclosure(
            remoteHost = normalized.destinationHostLabel(),
            remoteModelName = normalized.modelName.ifBlank { "未命名远程模型" },
            apiKeyConfigured = normalized.apiKey.isNotBlank(),
            connectivityStatus = normalized.connectivityStatus,
            supportsVisionInput = normalized.modelProfile().supportsVisionInput,
            isConfigured = normalized.isConfigured,
        )
    }

    private fun refreshDeviceStatus() {
        _uiState.update {
            it.copy(
                isArm64Supported = isArm64Device(),
                availableModelStorageBytes = modelRepository.resolveModelStorageBytes(),
            )
        }
    }

    private fun rebuildMemoryIndex() {
        runCatching {
            syncSemanticMemoryRuntime()
            memoryRepository.enabled = _uiState.value.memoryEnabled
            memoryRepository.rebuild(sessionRepository.allMessages(limit = 500))
            _uiState.update { state ->
                state.copy(
                    semanticMemoryEnabled = currentSemanticMemoryEnabled(),
                    semanticMemoryRuntimeStatus = currentSemanticMemoryRuntimeStatus(),
                    semanticMemoryIndexedRecordCount = currentSemanticMemoryIndexedRecordCount(),
                    semanticMemoryLastRebuiltAtMillis = currentSemanticMemoryLastRebuiltAtMillis(),
                )
            }
        }.onFailure {
            _uiState.update { state ->
                state.copy(
                    semanticMemoryEnabled = currentSemanticMemoryEnabled(),
                    semanticMemoryRuntimeStatus = currentSemanticMemoryRuntimeStatus(),
                    semanticMemoryIndexedRecordCount = currentSemanticMemoryIndexedRecordCount(),
                    semanticMemoryLastRebuiltAtMillis = currentSemanticMemoryLastRebuiltAtMillis(),
                    memoryHits = emptyList(),
                    longTermMemories = emptyList(),
                    statusText = "本地记忆暂不可用",
                )
            }
        }
    }

    private fun syncSemanticMemoryRuntime() {
        val controller = semanticMemoryRuntimeController ?: return
        if (skipStartupModelRuntimeWork) {
            controller.useMemoryModel(null)
            return
        }
        controller.useMemoryModel(modelRepository.verifiedMemoryEmbeddingModelPath())
    }

    private fun currentSemanticMemoryEnabled(): Boolean =
        semanticMemoryRuntimeController?.semanticMemoryEnabled == true

    private fun currentSemanticMemoryRuntimeStatus(): SemanticMemoryRuntimeStatus =
        semanticMemoryRuntimeController?.semanticMemoryRuntimeStatus
            ?: SemanticMemoryRuntimeStatus.RuntimeUnavailable

    private fun currentSemanticMemoryIndexedRecordCount(): Int =
        semanticMemoryRuntimeController?.semanticMemoryIndexedRecordCount ?: 0

    private fun currentSemanticMemoryLastRebuiltAtMillis(): Long? =
        semanticMemoryRuntimeController?.semanticMemoryLastRebuiltAtMillis

    private fun syncTaskStateMemories(memoryEnabled: Boolean = _uiState.value.memoryEnabled) {
        runCatching {
            val activeTasks = backgroundTaskScheduler.scheduledTasks()
                .filter { task ->
                    task.status == ScheduledTaskStatus.Scheduled ||
                        task.status == ScheduledTaskStatus.Running
                }
            val activeMemoryIds = activeTasks
                .mapTo(mutableSetOf()) { task -> taskStateMemoryRecordId(task.id) }
            longTermMemoryControls.savedRecords()
                .filter { record ->
                    record.type == MemoryRecordType.TaskState &&
                        record.id.startsWith(TASK_STATE_MEMORY_RECORD_PREFIX) &&
                        (!memoryEnabled || record.id !in activeMemoryIds)
                }
                .forEach { record -> longTermMemoryControls.forgetAutoManagedTaskState(record.id) }
            if (!memoryEnabled) return@runCatching
            activeTasks.forEach { task ->
                val memoryId = taskStateMemoryRecordId(task.id)
                if (longTermMemoryControls.isAutoManagedTaskStateSuppressed(memoryId)) return@forEach
                longTermMemoryControls.indexTaskState(
                    id = memoryId,
                    text = task.toTaskStateMemoryText(),
                )
            }
        }
    }

    private fun activeTaskStateMemoryIds(): Set<String> =
        runCatching {
            backgroundTaskScheduler.scheduledTasks()
                .filter { task ->
                    task.status == ScheduledTaskStatus.Scheduled ||
                        task.status == ScheduledTaskStatus.Running
                }
                .mapTo(mutableSetOf()) { task -> taskStateMemoryRecordId(task.id) }
        }.getOrDefault(emptySet())

    private fun loadLongTermMemories(): List<LongTermMemorySummary> =
        runCatching {
            longTermMemoryControls.savedRecords().map { record ->
                LongTermMemorySummary(
                    id = record.id,
                    type = record.type,
                    text = record.text,
                    source = record.source,
                    sensitivity = record.sensitivity,
                    privacy = record.privacy,
                    expiresAtMillis = record.expiresAtMillis,
                    conflictKey = record.conflictKey,
                )
            }
        }.getOrDefault(emptyList())

    private fun persistExplicitPreferenceMemory(message: ChatMessage): Boolean =
        persistExplicitMemory(
            message = message,
            parse = ::explicitUserPreferenceFrom,
            persist = { preference ->
                longTermMemoryControls.indexPreference(explicitUserPreferenceRecordId(preference), preference)
            },
        )

    private fun persistExplicitUserFactMemory(message: ChatMessage): Boolean =
        persistExplicitMemory(
            message = message,
            parse = ::explicitUserFactFrom,
            persist = { fact ->
                longTermMemoryControls.indexUserFact(explicitUserFactRecordId(fact), fact)
            },
        )

    private fun persistExplicitMemory(
        message: ChatMessage,
        parse: (String) -> String?,
        persist: (String) -> Unit,
    ): Boolean {
        if (message.role != MessageRole.User) return false
        val text = parse(message.text) ?: return false
        return runCatching {
            persist(text)
            loadLongTermMemories()
        }.onSuccess { records ->
            _uiState.update { state ->
                state.copy(longTermMemories = records)
            }
        }.onFailure {
            _uiState.update { state ->
                state.copy(
                    longTermMemories = emptyList(),
                    statusText = "本地记忆暂不可用",
                )
            }
        }.isSuccess
    }

    private fun handleExplicitPreferenceCommand(trimmed: String) =
        handleExplicitMemoryCommand(
            trimmed = trimmed,
            persist = ::persistExplicitPreferenceMemory,
            savedText = "已记住这条本地偏好。你可以在长期记忆中查看或删除。",
            disabledText = "本地记忆已关闭，未保存这条偏好。",
            failedText = "本地记忆暂不可用，未保存这条偏好。",
        )

    private fun handleExplicitUserFactCommand(trimmed: String) =
        handleExplicitMemoryCommand(
            trimmed = trimmed,
            persist = ::persistExplicitUserFactMemory,
            savedText = "已记住这条本地事实。你可以在长期记忆中查看或删除。",
            disabledText = "本地记忆已关闭，未保存这条事实。",
            failedText = "本地记忆暂不可用，未保存这条事实。",
        )

    private fun handleExplicitMemoryCommand(
        trimmed: String,
        persist: (ChatMessage) -> Boolean,
        savedText: String,
        disabledText: String,
        failedText: String,
    ) {
        val memoryEnabled = _uiState.value.memoryEnabled
        if (memoryEnabled) syncTaskStateMemories()
        val userMessage = ChatMessage(
            role = MessageRole.User,
            text = trimmed,
            privacy = MessagePrivacy.LocalOnly,
        )
        replaceActiveSessionMessages(
            _uiState.value.messages + userMessage,
            persistNow = true,
        )
        val persisted = memoryEnabled && persist(userMessage)
        val assistantText = when {
            persisted -> savedText
            !memoryEnabled -> disabledText
            else -> failedText
        }
        replaceActiveSessionMessages(
            _uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = assistantText,
                privacy = MessagePrivacy.LocalOnly,
            ),
            persistNow = true,
        )
        rebuildMemoryIndex()
        _uiState.update { state ->
            state.copy(
                memoryHits = emptyList(),
                longTermMemories = loadLongTermMemories(),
                statusText = when {
                    persisted -> "长期记忆已更新"
                    !memoryEnabled -> "本地记忆已关闭"
                    else -> "本地记忆暂不可用"
                },
            )
        }
    }

    private fun handleExplicitMemoryForgetCommand(trimmed: String) {
        if (_uiState.value.memoryEnabled) syncTaskStateMemories()
        val userMessage = ChatMessage(
            role = MessageRole.User,
            text = trimmed,
            privacy = MessagePrivacy.LocalOnly,
        )
        replaceActiveSessionMessages(
            _uiState.value.messages + userMessage,
            persistNow = true,
        )
        val target = explicitUserPreferenceForgetFrom(trimmed)
        val removed = target?.let {
            runCatching {
                val removedPreference = longTermMemoryControls.forgetPreference(it)
                val removedFact = longTermMemoryControls.forgetUserFact(it)
                removedPreference || removedFact
            }
                .getOrDefault(false)
        } == true
        val assistantText = if (removed) {
            "已遗忘这条本地记忆。"
        } else {
            "未找到这条本地记忆。"
        }
        replaceActiveSessionMessages(
            _uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = assistantText,
                privacy = MessagePrivacy.LocalOnly,
            ),
            persistNow = true,
        )
        rebuildMemoryIndex()
        _uiState.update { state ->
            state.copy(
                memoryHits = emptyList(),
                longTermMemories = loadLongTermMemories(),
                statusText = if (removed) "长期记忆已更新" else "未找到这条记忆",
            )
        }
    }

    private fun loadBackgroundTasks(): List<BackgroundTaskSummary> =
        backgroundTaskUseCases.snapshot().activeTasks

    private fun loadBackgroundTaskHistory(): List<BackgroundTaskSummary> =
        backgroundTaskUseCases.snapshot().history

    private fun loadPeriodicCheckPolicy(): PeriodicCheckPolicySummary =
        backgroundTaskUseCases.snapshot().periodicCheckPolicy

    private fun ScheduledTask.toTaskStateMemoryText(): String =
        listOf(
            "后台任务=${type.name}",
            "任务记录=${taskStateMemoryRecordId(id)}",
            "状态=${status.name}",
            "触发时间=$triggerAtMillis",
        ).joinToString(separator = "；")

    private fun loadAuditEvents(): List<AuditEventSummary> =
        toolAuditLog.recentAuditEvents().map { event ->
            AuditEventSummary(
                id = event.id,
                toolName = event.toolName,
                eventType = event.eventType,
                status = event.status,
                riskLevel = event.riskLevel,
                permissions = event.permissions,
                summary = event.summary,
                createdAtMillis = event.createdAtMillis,
            )
        }

    private fun loadAgentTraceRuns(): List<AgentTraceRunUiSummary> =
        runCatching {
            assistantOrchestrator.recentTraceRuns(limit = 5, stepLimit = 8)
                .map { run -> run.toUiSummary() }
        }.getOrDefault(emptyList())

    private fun activeRunTimelineFor(runId: String?): List<RunTimelineItemUiSummary> =
        runId
            ?.let { id -> runCatching { assistantOrchestrator.runEvents(id) }.getOrDefault(emptyList()) }
            ?.let(::runTimelineSummariesFor)
            .orEmpty()

    private fun activePublicWebEvidenceFor(runId: String?): List<PublicWebEvidencePack> =
        runId
            ?.let { id -> runCatching { assistantOrchestrator.publicWebEvidence(id) }.getOrDefault(emptyList()) }
            .orEmpty()

    private fun activeMemoryEvidenceFor(
        memoryHits: List<MemoryHit>,
        includePrivateLocalContext: Boolean,
    ): List<MemoryEvidenceUiSummary> =
        if (includePrivateLocalContext) memoryEvidenceSummariesFor(memoryHits) else emptyList()

    private fun AgentTraceRunSummary.toUiSummary(): AgentTraceRunUiSummary =
        AgentTraceRunUiSummary(
            id = run.id,
            state = run.state,
            updatedAtMillis = run.updatedAtMillis,
            steps = steps.map { step ->
                AgentTraceStepUiSummary(
                    type = step.type,
                    summary = step.summary,
                    createdAtMillis = step.createdAtMillis,
                    runDataReceipt = step.runDataReceiptUiSummaryOrNull(),
                )
            },
            runDataReceipt = runDataReceiptStep?.runDataReceiptUiSummaryOrNull()
                ?: steps.lastOrNull { step -> step.type == "RunDataReceiptRecorded" }?.runDataReceiptUiSummaryOrNull(),
        )

    private fun com.bytedance.zgx.solin.orchestration.AgentTraceStepSummary.runDataReceiptUiSummaryOrNull():
        RunDataReceiptUiSummary? {
        if (type != "RunDataReceiptRecorded") return null
        val json = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return RunDataReceiptUiSummary(
            destination = json.optString("destination"),
            currentPromptPrivacy = json.optString("currentPromptPrivacy"),
            remoteHistoryCount = json.optInt("remoteHistoryCount"),
            localOnlyHistoryFilteredCount = json.optInt("localOnlyHistoryFilteredCount"),
            memoryHitCount = json.optInt("memoryHitCount"),
            semanticMemoryHitCount = json.optInt("semanticMemoryHitCount"),
            lexicalMemoryHitCount = json.optInt("lexicalMemoryHitCount"),
            memoryContextIncluded = json.optBoolean("memoryContextIncluded"),
            deviceContextIncluded = json.optBoolean("deviceContextIncluded"),
            imageAttachmentCount = json.optInt("imageAttachmentCount"),
            protectedSourceCount = json.optInt("protectedSourceCount"),
            evidenceCardCount = json.optInt("evidenceCardCount"),
            localOnlyEvidenceCardCount = json.optInt("localOnlyEvidenceCardCount"),
            truncatedEvidenceCardCount = json.optInt("truncatedEvidenceCardCount"),
            lowQualityEvidenceCardCount = json.optInt("lowQualityEvidenceCardCount"),
            evidenceSourceTypes = json.optJSONArray("evidenceSourceTypes").toStringList(),
            rawContentPersisted = json.optBoolean("rawContentPersisted"),
            protectedContentTypes = json.optJSONArray("protectedContentTypes").toStringList(),
            deletableRecordTypes = json.optJSONArray("deletableRecordTypes").toStringList(),
            outputQualityGuardTriggered = json.optBoolean("outputQualityGuardTriggered"),
            outputQualityIssue = json.optString("outputQualityIssue").takeIf { value -> value.isNotBlank() },
            outputQualityRule = json.optString("outputQualityRule").takeIf { value -> value.isNotBlank() },
            outputQualityAction = json.optString("outputQualityAction").takeIf { value -> value.isNotBlank() },
            outputQualityStopped = json.optBoolean("outputQualityStopped"),
            outputQualityKeptPrefix = json.optBoolean("outputQualityKeptPrefix"),
        )
    }

    private fun restorePendingAgentConfirmationIfAny(clearMissing: Boolean = false) {
        val route = assistantOrchestrator.restorePendingAction(sessionRepository.activeSessionId)
        if (route == null) {
            if (clearMissing) {
                _uiState.update {
                    it.copy(pendingConfirmation = null)
                }
            }
            return
        }
        _uiState.update {
            it.copy(
                pendingConfirmation = PendingAgentConfirmation(
                    runId = route.runId,
                    draft = route.draft,
                    toolRequest = route.toolRequest,
                    skillId = route.skillId,
                    plannedByModel = route.plannedByModel,
                    fallbackReason = route.fallbackReason,
                ),
                isBusy = false,
                isGenerating = false,
                statusText = "动作草稿待确认 · 已恢复",
            )
        }
    }

    private fun restorePendingExternalOutcomeIfAny(clearMissing: Boolean = false) {
        if (_uiState.value.pendingConfirmation != null) {
            if (clearMissing) {
                _uiState.update { it.copy(pendingExternalOutcome = null) }
            }
            return
        }
        val pending = assistantOrchestrator.restorePendingExternalOutcome(sessionRepository.activeSessionId)
        if (pending == null) {
            if (clearMissing) {
                _uiState.update { it.copy(pendingExternalOutcome = null) }
            }
            return
        }
        _uiState.update {
            it.copy(
                pendingExternalOutcome = pending.toUiPendingExternalOutcome(),
                latestRecoveryAction = null,
                isBusy = false,
                isGenerating = false,
                statusText = "外部动作结果待确认 · 已恢复",
            )
        }
    }

    private fun PendingExternalOutcomeSnapshot.toUiPendingExternalOutcome(): PendingExternalOutcomeConfirmation =
        PendingExternalOutcomeConfirmation(
            runId = runId,
            requestId = requestId,
            toolName = toolName,
            title = title,
            summary = summary,
        )

    private fun failStaleAgentRunsOnStartup() {
        val failedCount = assistantOrchestrator.failStaleInFlightRuns(STALE_AGENT_RUN_STARTUP_REASON)
        if (failedCount <= 0) return
        _uiState.update {
            it.copy(agentTraceRuns = loadAgentTraceRuns())
        }
    }

    private fun isArm64Device(): Boolean =
        isArm64DeviceProvider()

    private fun replaceActiveSessionMessages(
        messages: List<ChatMessage>,
        persistNow: Boolean,
    ) {
        sessionRepository.replaceActiveSessionMessages(messages, persistNow)
        _uiState.update {
            it.copy(
                sessions = sessionRepository.summaries(),
                activeSessionId = sessionRepository.activeSessionId,
                messages = messages,
            )
        }
    }

    private fun persistActiveSessionFromUi() {
        val messages = _uiState.value.messages
        sessionRepository.persistActiveSessionFrom(messages)
        _uiState.update {
            it.copy(
                sessions = sessionRepository.summaries(),
                activeSessionId = sessionRepository.activeSessionId,
                messages = messages,
            )
        }
    }

    private fun finishStoppedGeneration(runId: String?) {
        val remoteMode = _uiState.value.inferenceMode == InferenceMode.Remote
        val currentMessages = _uiState.value.messages
        val messages = if (
            currentMessages.lastOrNull()?.role == MessageRole.Assistant &&
            currentMessages.last().text.isBlank()
        ) {
            currentMessages.dropLast(1)
        } else {
            currentMessages
        }
        replaceActiveSessionMessages(messages, persistNow = true)
        rebuildMemoryIndex()
        _uiState.update {
            it.copy(
                isBusy = false,
                isGenerating = false,
                isReady = if (remoteMode) it.remoteModelConfig.isConfigured else runtime.isLoaded,
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(runId),
                statusText = if (remoteMode) {
                    "已停止 · 远程"
                } else if (!runtime.isLoaded) {
                    "未加载模型"
                } else {
                    "已停止 · ${it.backend.label()}"
                },
                modelHealth = if (remoteMode) {
                    ModelHealth(
                        profileId = it.remoteModelConfig.modelProfile().id,
                        state = if (it.remoteModelConfig.isConfigured) {
                            ModelHealthState.Loaded
                        } else {
                            ModelHealthState.LoadFailed
                        },
                        failureReason = if (it.remoteModelConfig.isConfigured) null else "远程模型未配置",
                    )
                } else {
                    it.modelHealth.copy(
                        state = if (runtime.isLoaded) {
                            ModelHealthState.Loaded
                        } else if (it.modelPath != null) {
                            ModelHealthState.Verified
                        } else {
                            ModelHealthState.NotInstalled
                        },
                        backend = it.backend,
                    )
                },
            )
        }
    }

    private fun Throwable.cleanMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    private fun Throwable.generationFailureMessage(useRemoteModel: Boolean): String {
        val cleanMessage = cleanMessage()
        if (!useRemoteModel) return cleanMessage
        return when (this) {
            is IOException -> if (cleanMessage == this::class.java.simpleName) {
                "远程模型网络连接失败，请检查网络或远程模型配置后重试"
            } else {
                "远程模型网络连接失败：$cleanMessage"
            }
            else -> cleanMessage
        }
    }

    /**
     * Detect context-length overflow phrases in an exception chain. Matches wording produced by
     * common remote providers (OpenAI "context_length_exceeded", Anthropic "prompt is too long",
     * Gemini "maximum context", LiteLLM/OpenAI-compatible proxies) plus the Chinese-localized
     * surface strings we emit ourselves. We walk the cause chain so wrapped IOExceptions/
     * RemoteChatRuntime failures still match.
     */
    private fun Throwable.isContextOverflowError(): Boolean {
        var current: Throwable? = this
        val visited = HashSet<Throwable>(4)
        while (current != null && current !in visited) {
            visited += current
            val msg = current.message.orEmpty().lowercase()
            if (CONTEXT_OVERFLOW_MARKERS.any { it in msg }) return true
            current = current.cause
        }
        return false
    }

    /**
     * Call [block] streaming remote events; if it throws a context-overflow error, force a
     * compaction pass against [history] (returning the compacted list) and retry exactly once.
     * Second throw propagates. Preflight proactive compaction is performed before the first call.
     *
     * @return Pair(compactedHistory, retried) — caller should use the returned history for any
     *   subsequent work (it may be the original or a compacted replacement).
     */
    private suspend fun sendRemoteWithOverflowRetry(
        runId: String?,
        history: List<ChatMessage>,
        tokenBudget: Int,
        estimatedTokens: ((List<ChatMessage>) -> Int)? = null,
        block: suspend (history: List<ChatMessage>) -> Unit,
    ): Pair<List<ChatMessage>, Boolean> {
        val estimator = estimatedTokens ?: ::estimateHistoryTokensDefault
        // Preflight: proactive compaction under normal budget. No-op for NoOpContextCompactor.
        val preflightBudget = if (tokenBudget > 0) tokenBudget else Int.MAX_VALUE
        var currentHistory: List<ChatMessage> = assistantOrchestrator.compactHistory(
            messages = history,
            tokenBudget = preflightBudget,
            runId = runId,
            force = false,
            estimatedTokens = estimator,
        )
        return try {
            block(currentHistory)
            currentHistory to false
        } catch (first: Throwable) {
            if (!first.isContextOverflowError()) throw first
            // ONE retry with forced aggressive compaction (budget=0 triggers OverBudget).
            currentHistory = assistantOrchestrator.compactHistory(
                messages = currentHistory,
                tokenBudget = 0,
                runId = runId,
                force = true,
                estimatedTokens = estimator,
            )
            try {
                block(currentHistory)
                currentHistory to true
            } catch (second: Throwable) {
                second.addSuppressed(first)
                throw second
            }
        }
    }

    private fun estimateHistoryTokensDefault(messages: List<ChatMessage>): Int =
        com.bytedance.zgx.solin.orchestration.estimateTokensApproximate(messages)

    private fun MutableStateFlow<ChatUiState>.updateLastAssistant(text: String) {
        update { state ->
            val updatedMessages = state.messages.toMutableList()
            val index = updatedMessages.indexOfLast { it.role == MessageRole.Assistant }
            if (index >= 0) {
                updatedMessages[index] = updatedMessages[index].copy(
                    text = text,
                    generationStats = null,
                )
            }
            state.copy(messages = updatedMessages)
        }
    }

    private fun MutableStateFlow<ChatUiState>.updateLastAssistantLocalOnly(text: String) {
        update { state ->
            val updatedMessages = state.messages.toMutableList()
            val index = updatedMessages.indexOfLast { it.role == MessageRole.Assistant }
            if (index >= 0) {
                updatedMessages[index] = updatedMessages[index].copy(
                    text = text,
                    generationStats = null,
                    privacy = MessagePrivacy.LocalOnly,
                )
            }
            state.copy(messages = updatedMessages)
        }
    }

    private fun MutableStateFlow<ChatUiState>.updateLastAssistantStats(stats: GenerationStats?) {
        if (stats == null) return
        update { state ->
            val updatedMessages = state.messages.toMutableList()
            val index = updatedMessages.indexOfLast { it.role == MessageRole.Assistant }
            if (index >= 0) {
                val enrichedStats = stats.copy(
                    modelId = stats.modelId ?: state.activeInstalledModelId ?: state.selectedModelId,
                    backend = stats.backend ?: state.backend,
                )
                updatedMessages[index] = updatedMessages[index].copy(generationStats = enrichedStats)
            }
            state.copy(
                messages = updatedMessages,
                modelHealth = state.modelHealth.copy(
                    state = if (state.modelHealth.state == ModelHealthState.FallbackActive) {
                        ModelHealthState.FallbackActive
                    } else {
                        ModelHealthState.Loaded
                    },
                    backend = stats.backend ?: state.backend,
                    loadMs = stats.loadMs ?: state.modelHealth.loadMs,
                    firstTokenMs = stats.firstTokenMs ?: state.modelHealth.firstTokenMs,
                    tokenCount = stats.tokenCount,
                    tokensPerSecond = stats.tokensPerSecond,
                    fallbackBackend = if (stats.usedFallbackBackend) {
                        stats.backend ?: state.modelHealth.fallbackBackend
                    } else {
                        state.modelHealth.fallbackBackend
                    },
                ),
            )
        }
    }

    private fun ChatUiState.failedGenerationModelHealth(
        useRemoteModel: Boolean,
        reason: String,
    ): ModelHealth =
        modelHealth.copy(
            profileId = if (useRemoteModel) remoteModelConfig.modelProfile().id else activeModelProfileId(),
            state = ModelHealthState.LoadFailed,
            backend = if (useRemoteModel) null else backend,
            failureReason = reason,
        )

    private fun ChatUiState.toDeviceContextSnapshot(): DeviceContextSnapshot =
        DeviceContextSnapshot(
            isArm64Supported = isArm64Supported,
            inferenceMode = inferenceMode.name,
            installedCapabilities = installedCapabilities,
            memoryEnabled = memoryEnabled,
            availableStorageBytes = availableModelStorageBytes,
            activeSessionId = activeSessionId,
            hasPendingConfirmation = pendingConfirmation != null,
            toolReadiness = deviceContextToolReadiness(),
        )

    private fun deviceContextToolReadiness(): List<DeviceContextToolReadiness> {
        val authorization = deviceContextAuthorizationSnapshot
        return listOf(
            DeviceContextToolReadiness(
                toolName = MobileActionFunctions.READ_CLIPBOARD,
                state = DeviceContextToolReadinessState.Available,
                reason = "requires explicit tool confirmation before reading clipboard text",
            ),
            runtimePermissionReadiness(
                toolName = MobileActionFunctions.QUERY_CONTACTS,
                permissions = listOf(Manifest.permission.READ_CONTACTS),
                availableReason = "contacts can be read after explicit tool confirmation",
                missingReason = "contacts are read only after confirmation and Android permission grant",
            ),
            runtimePermissionReadiness(
                toolName = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
                permissions = listOf(Manifest.permission.READ_CALENDAR),
                availableReason = "calendar availability can be read after explicit tool confirmation",
                missingReason = "calendar availability is read only after confirmation and Android permission grant",
            ),
            specialAccessReadiness(
                toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                specialAccessId = SPECIAL_ACCESS_USAGE_STATS,
                availableReason = "foreground app metadata can be estimated after explicit tool confirmation",
                missingReason = "foreground app metadata requires Usage Access special access",
            ),
            DeviceContextToolReadiness(
                toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
                state = DeviceContextToolReadinessState.Available,
                reason = "returns bounded current-app notification summaries after confirmation",
            ),
            recentFilesReadiness(authorization),
            DeviceContextToolReadiness(
                toolName = MobileActionFunctions.QUERY_BACKGROUND_TASKS,
                state = DeviceContextToolReadinessState.Available,
                reason = "reads local scheduled task metadata only after confirmation",
            ),
            visualMediaReadiness(
                toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                availableReason = "reads one recent screenshot for local OCR only after confirmation",
                missingReason = "reads one recent screenshot for local OCR only after confirmation and media permission",
            ),
            visualMediaReadiness(
                toolName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
                availableReason = "scans recent images for local OCR only after confirmation",
                missingReason = "scans recent images for local OCR only after confirmation and media permission",
            ),
            specialAccessReadiness(
                toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                specialAccessId = SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT,
                availableReason = "current screen Accessibility text can be read after explicit tool confirmation",
                missingReason = "current screen text uses Accessibility text nodes, not screenshots or OCR",
            ),
            DeviceContextToolReadiness(
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                state = DeviceContextToolReadinessState.RequiresForegroundConsent,
                reason = "current screenshot OCR requires one-shot foreground MediaProjection consent after tool confirmation",
                specialAccessId = CurrentScreenshotOcrContract.CONSENT_REASON,
            ),
        )
    }

    private fun runtimePermissionReadiness(
        toolName: String,
        permissions: List<String>,
        availableReason: String,
        missingReason: String,
    ): DeviceContextToolReadiness {
        val missingPermissions = permissions
            .filterNot(deviceContextAuthorizationSnapshot::hasRuntimePermission)
        return DeviceContextToolReadiness(
            toolName = toolName,
            state = if (missingPermissions.isEmpty()) {
                DeviceContextToolReadinessState.Available
            } else {
                DeviceContextToolReadinessState.RequiresRuntimePermission
            },
            reason = if (missingPermissions.isEmpty()) availableReason else missingReason,
            runtimePermissions = missingPermissions.map { it.androidPermissionName() },
        )
    }

    private fun specialAccessReadiness(
        toolName: String,
        specialAccessId: String,
        availableReason: String,
        missingReason: String,
    ): DeviceContextToolReadiness {
        val available = deviceContextAuthorizationSnapshot.hasSpecialAccess(specialAccessId)
        return DeviceContextToolReadiness(
            toolName = toolName,
            state = if (available) {
                DeviceContextToolReadinessState.Available
            } else {
                DeviceContextToolReadinessState.RequiresSpecialAccess
            },
            reason = if (available) availableReason else missingReason,
            specialAccessId = if (available) null else specialAccessId,
        )
    }

    private fun recentFilesReadiness(
        authorization: DeviceContextAuthorizationSnapshot,
    ): DeviceContextToolReadiness {
        val available = authorization.hasAnyRecentFileMediaAccess()
        return DeviceContextToolReadiness(
            toolName = MobileActionFunctions.QUERY_RECENT_FILES,
            state = if (available) {
                DeviceContextToolReadinessState.Available
            } else {
                DeviceContextToolReadinessState.RequiresRuntimePermission
            },
            reason = if (available) {
                "recent media metadata can use currently granted media scopes; documents/downloads/other files require the system file picker"
            } else {
                "recent media metadata requires Android media permission; documents/downloads/other files require the system file picker"
            },
            runtimePermissions = if (available) emptyList() else recentFileRuntimePermissionHints(),
        )
    }

    private fun visualMediaReadiness(
        toolName: String,
        availableReason: String,
        missingReason: String,
    ): DeviceContextToolReadiness {
        val available = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            deviceContextAuthorizationSnapshot.hasRuntimePermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            deviceContextAuthorizationSnapshot.hasVisualMediaAccess(Manifest.permission.READ_MEDIA_IMAGES)
        }
        return DeviceContextToolReadiness(
            toolName = toolName,
            state = if (available) {
                DeviceContextToolReadinessState.Available
            } else {
                DeviceContextToolReadinessState.RequiresRuntimePermission
            },
            reason = if (available) availableReason else missingReason,
            runtimePermissions = if (available) emptyList() else visualMediaPermissionHints(),
        )
    }

    private fun DeviceContextAuthorizationSnapshot.hasAnyRecentFileMediaAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return hasRuntimePermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return hasVisualMediaAccess(Manifest.permission.READ_MEDIA_IMAGES) ||
            hasVisualMediaAccess(Manifest.permission.READ_MEDIA_VIDEO) ||
            hasRuntimePermission(Manifest.permission.READ_MEDIA_AUDIO)
    }

    private fun DeviceContextAuthorizationSnapshot.hasVisualMediaAccess(
        fullMediaPermission: String,
    ): Boolean =
        hasRuntimePermission(fullMediaPermission) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                hasRuntimePermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

    private fun recentFileRuntimePermissionHints(): List<String> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE.androidPermissionName())
        } else {
            buildList {
                add(Manifest.permission.READ_MEDIA_IMAGES.androidPermissionName())
                add(Manifest.permission.READ_MEDIA_VIDEO.androidPermissionName())
                add(Manifest.permission.READ_MEDIA_AUDIO.androidPermissionName())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED.androidPermissionName())
                }
            }
        }

    private fun visualMediaPermissionHints(): List<String> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE.androidPermissionName())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES.androidPermissionName(),
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED.androidPermissionName(),
            )
        } else {
            listOf(Manifest.permission.READ_MEDIA_IMAGES.androidPermissionName())
        }

    private fun String.androidPermissionName(): String =
        substringAfterLast('.')

    private fun PendingAgentConfirmation.matchesExecution(other: PendingAgentConfirmation): Boolean =
        runId == other.runId &&
            toolRequest == other.toolRequest &&
            draft.functionName == other.draft.functionName &&
            draft.parameters == other.draft.parameters
}

private fun SharedInput.composerSummary(): String {
    if (protectedSourceCount > 0 && protectedImageSourceCount <= 0 && attachments.isEmpty() && text.isBlank()) {
        return "受保护分享 ${protectedSourceCount} 项"
    }
    val labels = buildList {
        if (protectedSourceCount > 0) add("受保护 ${protectedSourceCount} 项")
        if (protectedImageSourceCount > 0) add("受保护图片")
        if (text.isNotBlank()) add("文本")
        attachments.take(3).forEach { attachment ->
            add(attachment.composerSummaryLabel())
        }
    }
    val extraCount = attachments.size - 3
    return buildString {
        append(labels.joinToString(separator = "、").ifBlank { "附件" })
        if (extraCount > 0) append(" 等 ${extraCount + 3} 项")
    }
}

private fun SharedAttachment.composerSummaryLabel(): String {
    val base = safeDisplayNameForPrompt() ?: kind.label
    return when {
        kind == SharedAttachmentKind.Image && (imageAttachment != null || localImageAttachment != null) -> "$base · 图片"
        kind == SharedAttachmentKind.Image && textPreview == null -> "$base · 不支持视觉"
        kind == SharedAttachmentKind.Image -> "$base · OCR"
        else -> base
    }
}

private fun SharedInput.remoteImageAttachments(): List<ChatImageAttachment> =
    attachments.mapNotNull { attachment -> attachment.imageAttachment }

private fun SharedInput.localImageAttachments(): List<LocalImageAttachment> =
    attachments.mapNotNull { attachment -> attachment.localImageAttachment }

private fun SharedInput.hasRemoteImageAttachment(): Boolean =
    attachments.any { attachment -> attachment.imageAttachment != null }

private fun SharedInput.hasLocalImageAttachment(): Boolean =
    attachments.any { attachment -> attachment.localImageAttachment != null }

private fun SharedInput.hasProtectedImageSource(): Boolean =
    protectedImageSourceCount > 0

private fun SharedInput.isRemoteVisionSendable(): Boolean =
    text.isBlank() &&
        attachments.isNotEmpty() &&
        attachments.all { attachment ->
            attachment.kind == SharedAttachmentKind.Image &&
                attachment.imageAttachment != null &&
                attachment.textPreview == null
        }

private fun remoteRouteBoundaryFailure(userInput: String, route: AssistantRoute.Chat): String? =
    when {
        route.memoryHits.isNotEmpty() ->
            "远程发送已阻止：本次路由包含本地记忆上下文。请切换到本地模型，或重试不带本地记忆的请求。"

        route.deviceContext != null ->
            "远程发送已阻止：本次路由包含设备上下文。请切换到本地模型，或重试不带设备上下文的请求。"

        route.promptForModel != userInput ->
            "远程发送已阻止：本次路由修改了远程 prompt。请切换到本地模型，或重新发送原始请求。"

        else -> null
    }

private fun RemoteModelConfig.destinationHostLabel(): String {
    val normalized = normalized()
    val uri = runCatching { URI(normalized.baseUrl) }.getOrNull()
    val host = uri?.host?.takeIf { it.isNotBlank() }
        ?: normalized.baseUrl.ifBlank { "未配置" }
    val port = uri?.port?.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
    return "$host$port"
}

private fun GenerationQualityDecision.reportOrNull(): GenerationQualityReport? =
    when (this) {
        GenerationQualityDecision.Continue -> null
        is GenerationQualityDecision.StopAndKeepPrefix -> report
        is GenerationQualityDecision.StopAndReplaceWithNotice -> report
        is GenerationQualityDecision.RetrySuggested -> report
        is GenerationQualityDecision.FailClosed -> report
    }

private fun GenerationQualityDecision.actionName(): String =
    when (this) {
        GenerationQualityDecision.Continue -> "Continue"
        is GenerationQualityDecision.StopAndKeepPrefix -> "StopAndKeepPrefix"
        is GenerationQualityDecision.StopAndReplaceWithNotice -> "StopAndReplaceWithNotice"
        is GenerationQualityDecision.RetrySuggested -> "RetrySuggested"
        is GenerationQualityDecision.FailClosed -> "FailClosed"
    }

private fun GenerationQualityReport.visibleAssistantText(): String =
    if (safePrefix.isBlank()) {
        visibleNotice
    } else {
        "$safePrefix\n\n（$visibleNotice）"
    }

private fun GenerationQualityReport.toTrace(decision: GenerationQualityDecision): ModelOutputQualityTrace =
    ModelOutputQualityTrace(
        issue = issue.name,
        severity = severity.name,
        triggeredRule = triggeredRule,
        action = decision.actionName(),
        rawOutputLength = rawOutputLength,
        keptPrefix = safePrefix.isNotBlank(),
        modelId = modelId,
        backend = backend?.name,
        runtimeKind = runtimeKind.name,
    )

private fun RunDataReceipt.withOutputQualityDecision(decision: GenerationQualityDecision): RunDataReceipt {
    val report = decision.reportOrNull() ?: return this
    return copy(
        outputQualityGuardTriggered = true,
        outputQualityIssue = report.issue.name,
        outputQualityRule = report.triggeredRule,
        outputQualityAction = decision.actionName(),
        outputQualityStopped = decision !is GenerationQualityDecision.Continue,
        outputQualityKeptPrefix = report.safePrefix.isNotBlank(),
    )
}

private fun ChatUiState.lastGenerationStatsForAdaptivePolicy(): GenerationStats? =
    messages
        .asReversed()
        .firstNotNullOfOrNull { message -> message.generationStats?.takeIf { it.isUsable() } }
        ?: modelHealth.takeIf { health ->
            health.tokenCount != null && health.tokensPerSecond != null
        }?.let { health ->
            GenerationStats(
                tokenCount = health.tokenCount ?: 0,
                tokensPerSecond = health.tokensPerSecond ?: 0.0,
                backend = health.backend ?: backend,
                loadMs = health.loadMs,
                firstTokenMs = health.firstTokenMs,
                usedFallbackBackend = health.fallbackBackend != null,
            )
        }

private fun ChatUiState.lastOutputQualityIssueForAdaptivePolicy(): GenerationQualityIssue? =
    modelHealth.lastOutputQualityIssue
        ?.let { value -> runCatching { GenerationQualityIssue.valueOf(value) }.getOrNull() }

private fun RemoteModelConfig.hasSameConnectivityTarget(other: RemoteModelConfig): Boolean {
    val left = normalized()
    val right = other.normalized()
    return left.baseUrl == right.baseUrl &&
        left.modelName == right.modelName &&
        left.apiKey == right.apiKey
}

private fun ModelSelectionState.modelHealthForCurrentSelection(backend: BackendChoice): ModelHealth {
    val activeModel = installedModels.firstOrNull { model -> model.id == activeInstalledModelId }
    val profileId = activeModel?.capabilityProfile?.id
        ?: activeModel?.recommendedModelId
        ?: selectedModelId
    val healthState = when {
        activeModel == null && activeModelPath == null -> ModelHealthState.NotInstalled
        activeModel?.isUsable == true &&
            activeModel.verificationStatus == ModelVerificationStatus.VerifiedRecommended -> ModelHealthState.Verified
        activeModel?.recommendedModelId == null && activeModelPath != null -> ModelHealthState.InstalledUnverified
        activeModelPath != null -> ModelHealthState.InstalledUnverified
        else -> ModelHealthState.NotInstalled
    }
    return ModelHealth(
        profileId = profileId,
        state = healthState,
        backend = backend.takeIf { activeModelPath != null },
    )
}

private fun ChatUiState.activeModelProfileId(): String =
    installedModels.firstOrNull { model -> model.id == activeInstalledModelId }?.let { model ->
        model.capabilityProfile?.id ?: model.recommendedModelId
    }
        ?: selectedModelId

private fun localModelRuntimeCapabilitiesFor(state: ChatUiState): LocalModelRuntimeCapabilities =
    LocalModelRuntimeCapabilities.fromProfile(state.activeLocalCapabilityProfile)

private fun backendAllowedForActiveModel(state: ChatUiState, backend: BackendChoice): Boolean =
    state.localPreferredBackends.isEmpty() || backend in state.localPreferredBackends

private fun preferredBackendForActiveModel(state: ChatUiState, current: BackendChoice): BackendChoice =
    if (backendAllowedForActiveModel(state, current)) {
        current
    } else {
        state.localPreferredBackends.firstOrNull() ?: current
    }

private fun AssistantRoute.runIdOrNull(): String? =
    when (this) {
        is AssistantRoute.Chat -> runId
        is AssistantRoute.Action -> runId
        is AssistantRoute.ToolRejected,
        is AssistantRoute.MissingModel -> null
    }

private fun AssistantRoute.runDataReceipt(
    stateBeforeSend: ChatUiState,
    destination: RunDataDestination,
    currentPromptPrivacy: MessagePrivacy,
    remoteHistoryCount: Int,
    imageAttachmentCount: Int,
    protectedSourceCount: Int = 0,
    currentPromptEvidenceSummary: EvidenceReceiptSummary? = null,
): RunDataReceipt {
    val memoryHits = (this as? AssistantRoute.Chat)?.memoryHits.orEmpty()
    val deviceContext = (this as? AssistantRoute.Chat)?.deviceContext
    val isRemote = destination == RunDataDestination.Remote
    val localOnlyHistoryFilteredCount = if (isRemote) {
        stateBeforeSend.messages.count { message -> message.privacy == MessagePrivacy.LocalOnly }
    } else {
        0
    }
    val memoryContextIncluded = !isRemote && memoryHits.isNotEmpty()
    val deviceContextIncluded = !isRemote && deviceContext != null
    val semanticMemoryHitCount = memoryHits.count { hit -> hit.recallMode == MemoryRecallMode.Semantic }
    val lexicalMemoryHitCount = memoryHits.count { hit -> hit.recallMode == MemoryRecallMode.Lexical }
    val evidenceReceiptSummary = buildList<EvidenceCard> {
        if (memoryContextIncluded) {
            memoryHits.forEach { hit ->
                add(
                    EvidenceCard(
                        id = "memory:${hit.id}",
                        sourceType = EvidenceSourceType.Memory,
                        privacy = MessagePrivacy.LocalOnly,
                        requiresLocalModel = true,
                        text = hit.text,
                        quality = EvidenceQuality(EvidenceQualityLevel.High),
                    ),
                )
            }
        }
        if (deviceContextIncluded) {
            add(
                EvidenceCard(
                    id = "device-context",
                    sourceType = EvidenceSourceType.DeviceContext,
                    privacy = MessagePrivacy.LocalOnly,
                    requiresLocalModel = true,
                    text = "",
                    quality = EvidenceQuality(EvidenceQualityLevel.Unknown),
                ),
            )
        }
        if (isRemote && imageAttachmentCount > 0) {
            repeat(imageAttachmentCount) { index ->
                add(
                    EvidenceCard(
                        id = "remote-image:$index",
                        sourceType = EvidenceSourceType.ImageAttachment,
                        privacy = MessagePrivacy.RemoteEligible,
                        requiresLocalModel = false,
                        text = "",
                        quality = EvidenceQuality(EvidenceQualityLevel.Unknown),
                    ),
                )
            }
        }
    }.toEvidenceReceiptSummary()
    val evidenceCardCount = evidenceReceiptSummary.evidenceCardCount +
        (currentPromptEvidenceSummary?.evidenceCardCount ?: 0)
    val localOnlyEvidenceCardCount = evidenceReceiptSummary.localOnlyEvidenceCardCount +
        (currentPromptEvidenceSummary?.localOnlyEvidenceCardCount ?: 0)
    val truncatedEvidenceCardCount = evidenceReceiptSummary.truncatedEvidenceCardCount +
        (currentPromptEvidenceSummary?.truncatedEvidenceCardCount ?: 0)
    val lowQualityEvidenceCardCount = evidenceReceiptSummary.lowQualityEvidenceCardCount +
        (currentPromptEvidenceSummary?.lowQualityEvidenceCardCount ?: 0)
    val evidenceSourceTypes = (
        evidenceReceiptSummary.sourceTypes.map { it.name } +
            currentPromptEvidenceSummary.orEmptyEvidenceSourceTypeNames()
        ).distinct()
    return RunDataReceipt(
        destination = destination,
        currentPromptPrivacy = currentPromptPrivacy.name,
        remoteHistoryCount = if (isRemote) remoteHistoryCount else 0,
        localOnlyHistoryFilteredCount = localOnlyHistoryFilteredCount,
        memoryHitCount = memoryHits.size,
        semanticMemoryHitCount = semanticMemoryHitCount,
        lexicalMemoryHitCount = lexicalMemoryHitCount,
        memoryContextIncluded = memoryContextIncluded,
        deviceContextIncluded = deviceContextIncluded,
        imageAttachmentCount = if (isRemote) imageAttachmentCount else 0,
        protectedSourceCount = protectedSourceCount,
        evidenceCardCount = evidenceCardCount,
        localOnlyEvidenceCardCount = localOnlyEvidenceCardCount,
        truncatedEvidenceCardCount = truncatedEvidenceCardCount,
        lowQualityEvidenceCardCount = lowQualityEvidenceCardCount,
        evidenceSourceTypes = evidenceSourceTypes,
        rawContentPersisted = false,
        protectedContentTypes = buildList {
            if (isRemote) {
                add("本地记忆")
                add("设备上下文")
            }
            if (localOnlyHistoryFilteredCount > 0) add("LocalOnly 历史")
            if (protectedSourceCount > 0) add("受保护分享源")
            if (isRemote && imageAttachmentCount == 0) add("非图片附件")
            if ((currentPromptEvidenceSummary?.localOnlyEvidenceCardCount ?: 0) > 0) {
                add("LocalOnly 输入证据")
            }
            if ((currentPromptEvidenceSummary?.truncatedEvidenceCardCount ?: 0) > 0) {
                add("截断输入证据")
            }
        },
        deletableRecordTypes = buildList {
            add("对话消息")
            add("Agent 轨迹")
            if (memoryHits.isNotEmpty()) add("显式记忆")
        },
    )
}

private fun EvidenceReceiptSummary?.orEmptyEvidenceSourceTypeNames(): List<String> =
    this?.sourceTypes?.map { sourceType -> sourceType.name }.orEmpty()

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val value = optString(index).trim()
            if (value.isNotBlank()) add(value)
        }
    }
}

private fun Float.normalizedVoiceInputLevel(): Float =
    ((this + 2f) / 12f).coerceIn(0.08f, 1f)

private fun seedVoiceWaveformLevels(level: Float): List<Float> =
    List(VOICE_WAVEFORM_SAMPLE_COUNT) { frame ->
        voiceWaveformSample(level = level, frame = frame)
    }

private fun List<Float>.nextVoiceWaveformLevels(level: Float, frame: Int): List<Float> {
    val nextSample = voiceWaveformSample(level = level, frame = frame)
    val samples = (this + nextSample).takeLast(VOICE_WAVEFORM_SAMPLE_COUNT)
    return if (samples.size == VOICE_WAVEFORM_SAMPLE_COUNT) {
        samples
    } else {
        seedVoiceWaveformLevels(level).take(VOICE_WAVEFORM_SAMPLE_COUNT - samples.size) + samples
    }
}

private fun voiceWaveformSample(level: Float, frame: Int): Float {
    val index = frame.floorMod(VOICE_WAVEFORM_MULTIPLIERS.size)
    return (level * VOICE_WAVEFORM_MULTIPLIERS[index]).coerceIn(0.08f, 1f)
}

private fun Int.floorMod(divisor: Int): Int =
    ((this % divisor) + divisor) % divisor

private fun AgentPlan.UseTool.requiresUserConfirmation(): Boolean =
    safetyDecision.outcome == SafetyOutcome.RequireConfirmation

private fun List<AgentPlan.UseTool>.batchToolTitle(): String =
    groupingBy { plan -> plan.draft.title.ifBlank { plan.request.toolName } }
        .eachCount()
        .entries
        .joinToString(separator = "、") { (title, count) ->
            if (count > 1) "$title x$count" else title
        }
