# SolinViewModel Split Plan

> **Status: COMPLETED** (Wave 1–6, see `docs/optimization_plan_weaknesses.md` §20–§22)
>
> **Result**: `SolinViewModel.kt` reduced from ~6,500 lines to ~1,500 lines. 19 focused
> controllers now live under `presentation/`; `SolinViewModel` is a thin facade that
> delegates to controllers and assembles composite `ChatUiState`.
>
> **Original target below** (kept for historical reference):
>
> **Target**: `app/src/main/java/com/bytedance/zgx/solin/SolinViewModel.kt` (6500 lines, 27 constructor deps, ~110+ methods, ~25 private fields)
>
> **Strategy**: Extract 8 focused controllers from the monolithic ViewModel, then introduce a `SolinViewModelCoordinator` that holds all controllers and assembles the composite `ChatUiState`. Migration proceeds in 8 incremental steps; each step is independently shippable.

---

## Section 1 — Complete Method & Field Mapping

### 1.1 ChatController

| Method | Visibility |
|---|---|
| `sendMessage(text, media)` | public |
| `sendMessageInternal(text, media)` | private |
| `sendLocalMessage(text, media)` | private |
| `sendRemoteMessage(text, media)` | private |
| `stopGeneration()` | public |
| `createNewSession()` | public |
| `selectSession(sessionId)` | public |
| `deleteActiveSession()` | public |
| `recreateConversationFromMessages(messages)` | public |
| `recreateConversationFromMessagesInternal(messages)` | private |
| `collectLocalRuntimeResponse(...)` | private |
| `appendGuardedGenerationChunk(...)` | private |
| `applyOutputQualityDecisionToAssistant(...)` | private |
| `finishOutputQualityGuardedGeneration(...)` | private |
| `sendRemoteWithOverflowRetry(...)` | private |
| `continueAfterToolObservation(...)` | public |
| `cancelActiveGenerationRun()` | private |
| `finishStoppedGeneration(...)` | private |
| `handleNotReadySendAttempt()` | private |
| `rejectUnsupportedLocalVisionInput(...)` | private |
| `rejectUnsupportedRemoteVisionInput(...)` | private |
| `protectLocalSharedInput(...)` | private |
| `protectRemoteSharedInput(...)` | private |
| `remoteHistoryForRemoteSend(...)` | private |
| `remoteRouteBoundaryFailure(...)` | private |
| `persistMessagesAndRebuildMemory(...)` | private |
| `replaceActiveSessionMessages(...)` | private |
| `persistActiveSessionFromUi()` | public |
| `ingestSharedInput(...)` | public |
| `stageSharedInputForLocalSend(...)` | private |
| `stageSharedInputForRemoteSend(...)` | private |
| `sendPendingSharedInput()` | public |
| `clearPendingSharedInputDraft(...)` | public |

**Managed fields:** `generationJob`, `activeGenerationRunId`, `pendingRemoteContinuation`, `pendingSharedInputRemoteSendRestore`, `nextVoiceInputDraftId`, `nextSharedInputDraftId`

### 1.2 ModelLoadController

| Method | Visibility |
|---|---|
| `startModelDownload(modelId)` | public |
| `startRecommendedModelDownload(model)` | public |
| `startCustomModelDownload(source)` | public |
| `cancelModelDownload()` | public |
| `importModel(uri)` | public |
| `selectBackend(backend)` | public |
| `selectInferenceMode(mode)` | public |
| `selectRecommendedModel(modelId)` | public |
| `selectInstalledModel(modelId)` | public |
| `deleteInstalledModel(modelId)` | public |
| `loadModel(modelId)` | public |
| `beginModelDownload(source)` | private |
| `beginRecommendedModelDownload(model)` | private |
| `monitorDownload(downloadId)` | private |
| `verifyAndRegisterDownloadedModel(...)` | private |
| `continueSetupDownloadOrLoad()` | private |
| `blockDownloadForMissingHuggingFaceAuthorization(...)` | private |
| `verifyLegacyModelsOnStartup()` | private |
| `startBundledModelInstallOnStartup()` | private |
| `updateRemoteReadiness()` | private |
| `buildRemoteModeDisclosure()` | private |
| `updateRemoteModelConfig(config)` | public |
| `testRemoteModelConnectivity()` | public |
| `configureDebugRemoteModelForScreenshotEvidence()` | public |
| `saveHuggingFaceAccessToken(token)` | public |
| `clearHuggingFaceAccessToken()` | public |
| `toggleSetupModel(modelId)` | public |
| `startSetupModelDownload()` | public |
| `skipFirstRunSetup()` | public |
| `updateModelState(...)` | private |

**Managed fields:** `runtimeLock`, `downloadMonitorJob`, `downloadPreflightJob`, `activeDownloadId`, `setupDownloadQueue`, `setupDownloadInProgress`, `bundledModelInstallJob`, `startupRestored`

### 1.3 ToolExecutionController

| Method | Visibility |
|---|---|
| `confirmAgentConfirmation()` | public |
| `dismissAgentConfirmation()` | public |
| `confirmActionDraft()` | public |
| `dismissActionDraft()` | public |
| `rejectAgentConfirmationForPolicyDenial()` | public |
| `rejectAgentConfirmationForCapabilityDenial()` | public |
| `requestRecoveryActionConfirmation(...)` | public |
| `recordExternalOutcome(...)` | public |
| `executeToolWithBoundary(...)` | private |
| `executeToolRequestAfterRunIsExecuting(...)` | private |
| `launchToolExecutionAfterRunIsExecuting(...)` | private |
| `executePublicEvidenceToolBatchAfterRunIsExecuting(...)` | private |
| `executePublicEvidenceToolPlans(...)` | private |
| `handleNextToolPlan(...)` | private |
| `restorePendingAgentConfirmationIfAny()` | private |
| `restorePendingExternalOutcomeIfAny()` | private |
| `failStaleAgentRunsOnStartup()` | private |

**Managed fields:** `toolRegistry`, `outboundSafetyPolicy`, `toolExecutionBoundary`

### 1.4 MemoryController (already partially extracted)

| Method | Visibility | Status |
|---|---|---|
| `rebuildMemoryIndex()` | public | Already in controller |
| `loadLongTermMemories()` | public | Already in controller |
| `syncTaskStateMemories()` | public | Already in controller |
| `handleExplicitPreferenceCommand()` | public | Already in controller |
| `handleExplicitUserFactCommand()` | public | Already in controller |
| `handleExplicitMemoryCommand()` | public | Already in controller |
| `handleExplicitMemoryForgetCommand()` | public | Already in controller |
| `updateMemoryEnabled(enabled)` | public | **Still in ViewModel** |
| `forgetLongTermMemory(id)` | public | **Still in ViewModel** |
| `clearLongTermMemory()` | public | **Still in ViewModel** |
| `persistExplicitPreferenceMemory(...)` | private | Already in controller |
| `persistExplicitUserFactMemory(...)` | private | Already in controller |
| `syncSemanticMemoryRuntime(...)` | private | Already in controller |
| `currentSemanticMemoryEnabled()` | private | Already in controller |
| `currentSemanticMemoryRuntimeStatus()` | private | Already in controller |
| `currentSemanticMemoryIndexedRecordCount()` | private | Already in controller |
| `currentSemanticMemoryLastRebuiltAtMillis()` | private | Already in controller |

**Managed fields:** (none — all state returned via `MemoryControllerResult`)

### 1.5 DeviceContextController

| Method | Visibility |
|---|---|
| `updateDeviceContextAuthorizationSnapshot(...)` | public |
| `refreshDeviceStatus()` | public |
| `reportSystemSettingsUnavailable()` | public |
| `reportSpecialAccessResult(...)` | public |
| `toDeviceContextSnapshot()` | private |
| `deviceContextToolReadiness()` | private |
| `runtimePermissionReadiness()` | private |
| `specialAccessReadiness()` | private |
| `recentFilesReadiness()` | private |
| `visualMediaReadiness()` | private |
| `isArm64Device()` | private |

**Managed fields:** `deviceContextAuthorizationSnapshot`, `isArm64DeviceProvider`

### 1.6 BackgroundTaskController

| Method | Visibility |
|---|---|
| `refreshBackgroundTasks()` | public |
| `cancelBackgroundTask(taskId)` | public |
| `setPeriodicCheckPolicy(policy)` | public |
| `disablePeriodicCheckPolicy()` | public |
| `recoverBackgroundTasksOnStartup()` | private |
| `loadBackgroundTasks()` | private |
| `loadBackgroundTaskHistory()` | private |
| `loadPeriodicCheckPolicy()` | private |

**Managed fields:** `backgroundTaskUseCases`

### 1.7 AuditController

| Method | Visibility |
|---|---|
| `refreshAuditEvents()` | public |
| `refreshRemoteSendAuditEvents()` | public |
| `loadAuditEvents()` | private |
| `loadAgentTraceRuns()` | private |
| `activeRunTimelineFor(runId)` | private |
| `activePublicWebEvidenceFor(runId)` | private |
| `activeMemoryEvidenceFor(runId)` | private |
| `toUiSummary(...)` | private |
| `recordRemoteSendAuditEvent(...)` | private |
| `recordRemoteSendDecision(...)` | private |
| `loadRemoteSendAuditEvents()` | private |
| `appendRemoteSendAuditNote(...)` | private |
| `failClosedPendingRemoteSendOnStartup()` | private |
| `savePendingRemoteSendMarker(...)` | private |

**Managed fields:** `remoteSendAuditSink`, `remoteSendAuditLog`, `remoteSendPendingStore`

### 1.8 SettingsController

| Method | Visibility |
|---|---|
| `updateGenerationParameters(params)` | public |
| `resetGenerationParameters()` | public |
| `updateReduceDeviceActionConfirmations(value)` | public |
| `setRemoteSendDisclosurePolicy(policy)` | public |
| `shouldRequireRemoteSendDisclosure()` | private |
| `resetRemoteSendDisclosureSuppression()` | public |
| `buildPendingRemoteSendDisclosure(...)` | private |
| `buildSensitiveRemoteSendDisclosure(...)` | private |
| `toRemoteSendPromptPreview(...)` | private |
| `confirmRemoteSendDisclosure()` | public |
| `confirmRemoteSendWithMasking()` | public |
| `confirmRemoteSendDespiteSensitive()` | public |
| `dismissRemoteSendDisclosure()` | public |
| `dismissRemoteModeDisclosure()` | public |
| `acceptVoiceTranscript(text)` | public |
| `consumeVoiceInputDraft(id)` | public |
| `reportVoiceInputUnavailable()` | public |
| `startVoiceInputCapture()` | public |
| `updateVoiceInputLevel(rms)` | public |
| `updateVoiceInputPartialTranscript(text)` | public |
| `finishVoiceInputCapture()` | public |
| `voiceWaveformSamples(...)` | private |
| `voiceWaveformLevel(...)` | private |

**Managed fields:** `remoteSendDisclosureSuppressedForSession`, `requireRemoteSendDisclosure`, `sessionRestoreJob`, `sessionRestoreGeneration`

### 1.9 Coordinator (SolinViewModelCoordinator) — composite methods

| Method | Notes |
|---|---|
| `init { }` / startup orchestration | Calls controller startup hooks in order |
| `restoreStartupState()` | Restores sessions, models, tasks, audit state |
| `createInitialState()` | Builds initial `ChatUiState` |
| `onCleared()` | Cancels all jobs, delegates cleanup |
| `applyControllerResult(result)` | Merges per-controller results into `_uiState` |
| `handleRemoteSendDisclosureFlow(...)` | Orchestrates Settings + Chat + Audit |
| `handleToolExecutionFlow(...)` | Orchestrates Tool + Chat + Audit |

---

## Section 2 — Per-Controller Details

### 2.1 ChatController

```kotlin
class ChatController(
    private val sessionRepository: SessionRepository,
    private val runtime: LiteRtRuntime,
    private val remoteRuntime: RemoteChatRuntime,
    private val assistantOrchestrator: AssistantRouter,
    private val outputQualityGuard: OutputQualityGuard,
    private val ioDispatcher: CoroutineDispatcher,
    private val applicationScope: CoroutineScope,
)
```

**Public API (suspend + result-snapshot):**
- `suspend fun sendMessage(text: String, media: List<InputMedia>, state: ChatUiState): ChatSendResult`
- `fun stopGeneration()`
- `suspend fun createNewSession(): ChatSessionResult`
- `suspend fun selectSession(sessionId: String): ChatSessionResult`
- `suspend fun deleteActiveSession(): ChatSessionResult`
- `suspend fun recreateConversation(messages: List<ChatMessage>): ChatSendResult`
- `suspend fun continueAfterToolObservation(runId: String, observation: String): ChatSendResult`
- `suspend fun persistActiveSessionFromUi(messages: List<ChatMessage>)`
- `suspend fun ingestSharedInput(input: SharedInput): ChatSendResult`
- `fun sendPendingSharedInput()`
- `fun clearPendingSharedInputDraft(draftId: Long)`

**Managed state (internal, not exposed):**
- `generationJob: Job?`
- `activeGenerationRunId: String?`
- `pendingRemoteContinuation: PendingRemoteContinuation?`
- `pendingSharedInputRemoteSendRestore: PendingSharedInputRemoteSendRestore?`
- `nextVoiceInputDraftId: Long`
- `nextSharedInputDraftId: Long`

**Emitted result types:**
```kotlin
data class ChatSendResult(
    val messages: List<ChatMessage>,
    val isGenerating: Boolean,
    val isBusy: Boolean,
    val activeRunId: String?,
    val statusText: String?,
    val memoryHits: List<MemoryHit>?,
    val error: String? = null,
)

data class ChatSessionResult(
    val sessions: List<ChatSessionSummary>,
    val activeSessionId: String?,
    val messages: List<ChatMessage>,
    val statusText: String?,
)
```

### 2.2 ModelLoadController

```kotlin
class ModelLoadController(
    private val modelRepository: ModelRepositoryFacade,
    private val downloadService: ModelDownloadService,
    private val runtime: LiteRtRuntime,
    private val remoteRuntime: RemoteChatRuntime,
    private val remoteConnectivityProbe: RemoteConnectivityProbe,
    private val firstRunSetupRepository: FirstRunSetupRepository,
    private val huggingFaceAuthStore: HuggingFaceAuthStore,
    private val bundledModelInstaller: BundledModelInstaller,
    private val isArm64DeviceProvider: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher,
    private val applicationScope: CoroutineScope,
    private val skipStartupModelRuntimeWork: Boolean = false,
)
```

**Public API:**
- `suspend fun startModelDownload(modelId: String): ModelDownloadResult`
- `suspend fun startRecommendedModelDownload(model: RecommendedModel): ModelDownloadResult`
- `suspend fun startCustomModelDownload(source: ModelDownloadSource): ModelDownloadResult`
- `fun cancelModelDownload()`
- `suspend fun importModel(uri: Uri): ModelImportResult`
- `suspend fun selectBackend(backend: BackendChoice): ModelStateResult`
- `suspend fun selectInferenceMode(mode: InferenceMode): ModelStateResult`
- `suspend fun selectRecommendedModel(modelId: String): ModelStateResult`
- `suspend fun selectInstalledModel(modelId: String): ModelStateResult`
- `suspend fun deleteInstalledModel(modelId: String): ModelStateResult`
- `suspend fun loadModel(modelId: String): ModelStateResult`
- `suspend fun updateRemoteModelConfig(config: RemoteModelConfig): ModelStateResult`
- `suspend fun testRemoteModelConnectivity(): RemoteConnectivityResult`
- `suspend fun configureDebugRemoteModelForScreenshotEvidence()`
- `suspend fun saveHuggingFaceAccessToken(token: String): AuthResult`
- `suspend fun clearHuggingFaceAccessToken(): AuthResult`
- `suspend fun toggleSetupModel(modelId: String): SetupStateResult`
- `suspend fun startSetupModelDownload(): SetupDownloadResult`
- `suspend fun skipFirstRunSetup(): SetupStateResult`
- `suspend fun verifyLegacyModelsOnStartup(): ModelStateResult`
- `suspend fun startBundledModelInstallOnStartup(): ModelStateResult`
- `suspend fun updateRemoteReadiness(state: ChatUiState): ModelStateResult`

**Managed state:**
- `runtimeLock: Mutex`
- `downloadMonitorJob: Job?`
- `downloadPreflightJob: Job?`
- `activeDownloadId: Long?`
- `setupDownloadQueue: ArrayDeque<ModelDownloadSource>`
- `setupDownloadInProgress: Boolean`
- `bundledModelInstallJob: Job?`
- `startupRestored: Boolean`

**Emitted result types:**
```kotlin
data class ModelStateResult(
    val modelPath: String?,
    val activeInstalledModelId: String?,
    val installedModels: List<InstalledModelSummary>,
    val selectedModelId: String,
    val inferenceMode: InferenceMode,
    val remoteModelConfig: RemoteModelConfig,
    val backend: BackendChoice,
    val localMaxTotalTokens: Int,
    val localPreferredBackends: Set<BackendChoice>,
    val modelHealth: ModelHealth,
    val statusText: String,
    val isReady: Boolean,
    val isBusy: Boolean,
    val isArm64Supported: Boolean,
    val availableModelStorageBytes: Long,
    val pendingRemoteModeDisclosure: PendingRemoteModeDisclosure?,
)

data class ModelDownloadResult(
    val isDownloading: Boolean,
    val isPreparingDownload: Boolean,
    val downloadProgressPercent: Int?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val statusText: String?,
    val pendingHuggingFaceAuthorizationModelId: String?,
)

data class RemoteConnectivityResult(
    val isReady: Boolean,
    val statusText: String,
)
```

### 2.3 ToolExecutionController

```kotlin
class ToolExecutionController(
    private val actionExecutor: ToolExecutor,
    private val assistantOrchestrator: AssistantRouter,
    private val toolAuditLog: ToolAuditLog,
    private val ioDispatcher: CoroutineDispatcher,
    private val applicationScope: CoroutineScope,
)
```

**Public API:**
- `suspend fun confirmAgentConfirmation(state: ChatUiState): ToolExecutionResult`
- `suspend fun dismissAgentConfirmation(state: ChatUiState): ToolExecutionResult`
- `suspend fun confirmActionDraft(state: ChatUiState): ToolExecutionResult`
- `suspend fun dismissActionDraft(state: ChatUiState): ToolExecutionResult`
- `suspend fun rejectAgentConfirmationForPolicyDenial(state: ChatUiState): ToolExecutionResult`
- `suspend fun rejectAgentConfirmationForCapabilityDenial(state: ChatUiState): ToolExecutionResult`
- `suspend fun requestRecoveryActionConfirmation(action: AgentRecoveryAction): ToolExecutionResult`
- `suspend fun recordExternalOutcome(outcome: ExternalOutcome): ToolExecutionResult`
- `suspend fun restorePendingAgentConfirmationIfAny(): ToolExecutionResult`
- `suspend fun restorePendingExternalOutcomeIfAny(): ToolExecutionResult`
- `suspend fun failStaleAgentRunsOnStartup()`

**Managed state:**
- `toolRegistry: ToolRegistry`
- `outboundSafetyPolicy: SafetyPolicy`
- `toolExecutionBoundary: TimeoutToolExecutionBoundary`

**Emitted result types:**
```kotlin
data class ToolExecutionResult(
    val pendingConfirmation: PendingAgentConfirmation?,
    val pendingExternalOutcome: PendingExternalOutcomeConfirmation?,
    val latestRecoveryAction: AgentRecoveryAction?,
    val statusText: String?,
    val shouldContinueGeneration: Boolean = false,
    val continueRunId: String? = null,
    val continueObservation: String? = null,
)
```

### 2.4 MemoryController (already exists, needs wiring)

```kotlin
// Already at: app/src/main/java/com/bytedance/zgx/solin/memory/MemoryController.kt
class MemoryController(
    private val memoryIndex: MemoryIndex,
    private val longTermMemoryControls: LongTermMemoryControls,
    private val sessionStore: SessionStore,
    private val modelRepository: ModelRepositoryFacade,
    private val backgroundTaskScheduler: BackgroundTaskScheduler,
    private val semanticMemoryRuntimeController: SemanticMemoryRuntimeController?,
    private val ioDispatcher: CoroutineDispatcher,
)
```

**Additional methods to add (currently still in SolinViewModel):**
- `suspend fun updateMemoryEnabled(enabled: Boolean): MemoryControllerResult`
- `suspend fun forgetLongTermMemory(id: String): MemoryControllerResult`
- `suspend fun clearLongTermMemory(): MemoryControllerResult`

**Existing result types (already defined):**
- `MemoryControllerResult` — semantic memory status + long-term memories
- `MemoryCommandResult` — result of explicit memory commands

### 2.5 DeviceContextController

```kotlin
class DeviceContextController(
    private val isArm64DeviceProvider: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher,
)
```

**Public API:**
- `fun updateDeviceContextAuthorizationSnapshot(snapshot: DeviceContextAuthorizationSnapshot)`
- `suspend fun refreshDeviceStatus(): DeviceContextResult`
- `fun reportSystemSettingsUnavailable()`
- `fun reportSpecialAccessResult(result: SpecialAccessResult)`
- `fun toDeviceContextSnapshot(): DeviceContextSnapshot`
- `fun deviceContextToolReadiness(): DeviceContextToolReadiness`
- `fun runtimePermissionReadiness(): PermissionReadiness`
- `fun specialAccessReadiness(): SpecialAccessReadiness`
- `fun recentFilesReadiness(): RecentFilesReadiness`
- `fun visualMediaReadiness(): VisualMediaReadiness`
- `fun isArm64Device(): Boolean`

**Managed state:**
- `deviceContextAuthorizationSnapshot: DeviceContextAuthorizationSnapshot`

**Emitted result types:**
```kotlin
data class DeviceContextResult(
    val isArm64Supported: Boolean,
    val grantedSpecialAccessIds: Set<String>,
    val statusText: String?,
)
```

### 2.6 BackgroundTaskController

```kotlin
class BackgroundTaskController(
    private val backgroundTaskScheduler: BackgroundTaskScheduler,
    private val backgroundTaskUseCases: BackgroundTaskUseCases,
    private val ioDispatcher: CoroutineDispatcher,
)
```

**Public API:**
- `suspend fun refreshBackgroundTasks(): BackgroundTaskResult`
- `suspend fun cancelBackgroundTask(taskId: String): BackgroundTaskResult`
- `suspend fun setPeriodicCheckPolicy(policy: PeriodicCheckPolicy): BackgroundTaskResult`
- `suspend fun disablePeriodicCheckPolicy(): BackgroundTaskResult`
- `suspend fun recoverBackgroundTasksOnStartup()`
- `suspend fun loadBackgroundTasks(): List<BackgroundTaskSummary>`
- `suspend fun loadBackgroundTaskHistory(): List<BackgroundTaskSummary>`
- `suspend fun loadPeriodicCheckPolicy(): PeriodicCheckPolicySummary`

**Managed state:**
- `backgroundTaskUseCases: BackgroundTaskUseCases` (wraps scheduler + store)

**Emitted result types:**
```kotlin
data class BackgroundTaskResult(
    val backgroundTasks: List<BackgroundTaskSummary>,
    val backgroundTaskHistory: List<BackgroundTaskSummary>,
    val periodicCheckPolicy: PeriodicCheckPolicySummary,
    val statusText: String?,
)
```

### 2.7 AuditController

```kotlin
class AuditController(
    private val toolAuditLog: ToolAuditLog,
    private val remoteSendAuditSink: RemoteSendAuditSink,
    private val remoteSendAuditLog: RemoteSendAuditLog,
    private val remoteSendPendingStore: RemoteSendPendingStore,
    private val ioDispatcher: CoroutineDispatcher,
)
```

**Public API:**
- `suspend fun refreshAuditEvents(): AuditResult`
- `suspend fun refreshRemoteSendAuditEvents(): AuditResult`
- `suspend fun loadAuditEvents(): List<AuditEventSummary>`
- `suspend fun loadAgentTraceRuns(): List<AgentTraceRunUiSummary>`
- `suspend fun activeRunTimelineFor(runId: String): List<RunTimelineItemUiSummary>`
- `suspend fun activePublicWebEvidenceFor(runId: String): List<PublicWebEvidencePack>`
- `suspend fun activeMemoryEvidenceFor(runId: String): List<MemoryEvidenceUiSummary>`
- `suspend fun recordRemoteSendAuditEvent(event: RemoteSendAuditEvent)`
- `suspend fun recordRemoteSendDecision(decision: RemoteSendDecision)`
- `suspend fun loadRemoteSendAuditEvents(): List<RemoteSendAuditSummary>`
- `suspend fun appendRemoteSendAuditNote(runId: String, note: String)`
- `suspend fun failClosedPendingRemoteSendOnStartup()`
- `suspend fun savePendingRemoteSendMarker(marker: PendingRemoteSendMarker)`

**Managed state:** (none — delegates to injected stores/logs)

**Emitted result types:**
```kotlin
data class AuditResult(
    val auditEvents: List<AuditEventSummary>,
    val remoteSendAuditEvents: List<RemoteSendAuditSummary>,
    val agentTraceRuns: List<AgentTraceRunUiSummary>,
    val activeRunTimeline: List<RunTimelineItemUiSummary>,
    val activePublicWebEvidence: List<PublicWebEvidencePack>,
    val activeMemoryEvidence: List<MemoryEvidenceUiSummary>,
)
```

### 2.8 SettingsController

```kotlin
class SettingsController(
    private val generationParametersRepository: GenerationParametersRepository,
    private val requireRemoteSendDisclosure: Boolean,
    private val ioDispatcher: CoroutineDispatcher,
    private val applicationScope: CoroutineScope,
)
```

**Public API:**
- `suspend fun updateGenerationParameters(params: GenerationParameters): SettingsResult`
- `suspend fun resetGenerationParameters(): SettingsResult`
- `suspend fun updateReduceDeviceActionConfirmations(value: Boolean): SettingsResult`
- `suspend fun setRemoteSendDisclosurePolicy(policy: RemoteSendDisclosurePolicy): SettingsResult`
- `fun shouldRequireRemoteSendDisclosure(): Boolean`
- `fun resetRemoteSendDisclosureSuppression()`
- `fun buildPendingRemoteSendDisclosure(state: ChatUiState): PendingRemoteSendDisclosure?`
- `fun buildSensitiveRemoteSendDisclosure(state: ChatUiState): PendingRemoteSendDisclosure?`
- `fun toRemoteSendPromptPreview(state: ChatUiState): RemoteSendPromptPreview`
- `suspend fun confirmRemoteSendDisclosure(state: ChatUiState): SettingsResult`
- `suspend fun confirmRemoteSendWithMasking(state: ChatUiState): SettingsResult`
- `suspend fun confirmRemoteSendDespiteSensitive(state: ChatUiState): SettingsResult`
- `suspend fun dismissRemoteSendDisclosure(): SettingsResult`
- `suspend fun dismissRemoteModeDisclosure(): SettingsResult`
- `fun acceptVoiceTranscript(text: String): VoiceInputResult`
- `fun consumeVoiceInputDraft(draftId: Long): VoiceInputResult`
- `fun reportVoiceInputUnavailable(): VoiceInputResult`
- `fun startVoiceInputCapture(): VoiceInputResult`
- `fun updateVoiceInputLevel(rms: Float): VoiceInputResult`
- `fun updateVoiceInputPartialTranscript(text: String): VoiceInputResult`
- `fun finishVoiceInputCapture(): VoiceInputResult`

**Managed state:**
- `remoteSendDisclosureSuppressedForSession: Boolean`
- `sessionRestoreJob: Job?`
- `sessionRestoreGeneration: Long`
- `nextVoiceInputDraftId: Long`

**Emitted result types:**
```kotlin
data class SettingsResult(
    val generationParameters: GenerationParameters,
    val reduceDeviceActionConfirmations: Boolean,
    val remoteSendDisclosurePolicy: RemoteSendDisclosurePolicy,
    val pendingRemoteSendDisclosure: PendingRemoteSendDisclosure?,
    val pendingRemoteModeDisclosure: PendingRemoteModeDisclosure?,
    val memoryEnabled: Boolean,
    val statusText: String?,
)

data class VoiceInputResult(
    val voiceInputDraft: VoiceInputDraft?,
    val voiceCapture: VoiceCaptureUiState,
    val statusText: String?,
)
```

---

## Section 3 — SolinViewModelCoordinator Pattern

### 3.1 Architecture

```
SolinViewModel (becomes thin ViewModel, ~300 lines)
  └─ holds: SolinViewModelCoordinator
       ├─ ChatController
       ├─ ModelLoadController
       ├─ ToolExecutionController
       ├─ MemoryController
       ├─ DeviceContextController
       ├─ BackgroundTaskController
       ├─ AuditController
       └─ SettingsController
```

The **Coordinator** is not a controller itself. It:
1. Owns the composite `_uiState: MutableStateFlow<ChatUiState>`
2. Holds references to all 8 controllers
3. Routes UI intents to the correct controller
4. Applies controller result snapshots to `_uiState`
5. Orchestrates cross-controller flows (e.g., send message triggers Chat + Audit + Memory)
6. Manages startup ordering and `onCleared` cleanup

### 3.2 Coordinator Skeleton

```kotlin
class SolinViewModelCoordinator(
    val chat: ChatController,
    val modelLoad: ModelLoadController,
    val toolExecution: ToolExecutionController,
    val memory: MemoryController,
    val deviceContext: DeviceContextController,
    val backgroundTasks: BackgroundTaskController,
    val audit: AuditController,
    val settings: SettingsController,
    private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _uiState = MutableStateFlow(createInitialState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // ---- Intent routing (called by the thin ViewModel) ----

    fun sendMessage(text: String, media: List<InputMedia>) {
        applicationScope.launch {
            val current = _uiState.value
            // Cross-controller: check remote send disclosure first
            val disclosure = settings.buildPendingRemoteSendDisclosure(current)
            if (disclosure != null) {
                _uiState.update { it.copy(pendingRemoteSendDisclosure = disclosure) }
                return@launch
            }
            // Delegate to ChatController
            val result = chat.sendMessage(text, media, current)
            applyChatSendResult(result)
            // Cross-controller: rebuild memory after message persist
            val memoryResult = memory.rebuildMemoryIndex(current.memoryEnabled)
            applyMemoryResult(memoryResult)
        }
    }

    fun stopGeneration() = chat.stopGeneration()

    fun selectBackend(backend: BackendChoice) {
        applicationScope.launch {
            val result = modelLoad.selectBackend(backend)
            applyModelStateResult(result)
        }
    }

    // ... similar routing for every public intent ...

    // ---- Result application ----

    private fun applyChatSendResult(result: ChatSendResult) {
        _uiState.update { state ->
            state.copy(
                messages = result.messages,
                isGenerating = result.isGenerating,
                isBusy = result.isBusy,
                statusText = result.statusText ?: state.statusText,
                memoryHits = result.memoryHits ?: state.memoryHits,
            )
        }
    }

    private fun applyModelStateResult(result: ModelStateResult) {
        _uiState.update { state ->
            state.copy(
                modelPath = result.modelPath,
                activeInstalledModelId = result.activeInstalledModelId,
                installedModels = result.installedModels,
                selectedModelId = result.selectedModelId,
                inferenceMode = result.inferenceMode,
                remoteModelConfig = result.remoteModelConfig,
                backend = result.backend,
                localMaxTotalTokens = result.localMaxTotalTokens,
                localPreferredBackends = result.localPreferredBackends,
                modelHealth = result.modelHealth,
                statusText = result.statusText,
                isReady = result.isReady,
                isBusy = result.isBusy,
                isArm64Supported = result.isArm64Supported,
                availableModelStorageBytes = result.availableModelStorageBytes,
                pendingRemoteModeDisclosure = result.pendingRemoteModeDisclosure,
            )
        }
    }

    private fun applyMemoryResult(result: MemoryControllerResult) {
        _uiState.update { state ->
            state.copy(
                semanticMemoryEnabled = result.semanticMemoryEnabled,
                semanticMemoryRuntimeStatus = result.semanticMemoryRuntimeStatus,
                semanticMemoryIndexedRecordCount = result.semanticMemoryIndexedRecordCount,
                semanticMemoryLastRebuiltAtMillis = result.semanticMemoryLastRebuiltAtMillis,
                longTermMemories = result.longTermMemories.ifEmpty { state.longTermMemories },
                memoryHits = if (result.memoryHitsCleared) emptyList() else state.memoryHits,
                statusText = result.statusText ?: state.statusText,
            )
        }
    }

    // applyToolResult, applyBackgroundTaskResult, applyAuditResult,
    // applySettingsResult, applyDeviceContextResult ...

    // ---- Startup orchestration ----

    suspend fun restoreStartupState() {
        // 1. Model state
        val modelResult = modelLoad.verifyLegacyModelsOnStartup()
        applyModelStateResult(modelResult)
        modelLoad.startBundledModelInstallOnStartup()

        // 2. Memory
        val memoryResult = memory.rebuildMemoryIndex(
            memoryEnabled = _uiState.value.memoryEnabled,
            skipModelRuntimeWork = true,
        )
        applyMemoryResult(memoryResult)
        memory.syncTaskStateMemories(_uiState.value.memoryEnabled)

        // 3. Background tasks
        backgroundTasks.recoverBackgroundTasksOnStartup()
        val taskResult = backgroundTasks.refreshBackgroundTasks()
        applyBackgroundTaskResult(taskResult)

        // 4. Audit
        audit.failClosedPendingRemoteSendOnStartup()
        val auditResult = audit.refreshAuditEvents()
        applyAuditResult(auditResult)

        // 5. Tool state
        toolExecution.failStaleAgentRunsOnStartup()
        val toolRestore = toolExecution.restorePendingAgentConfirmationIfAny()
        applyToolResult(toolRestore)

        // 6. Sessions (ChatController)
        // ... restore active session messages ...
    }

    // ---- Cleanup ----

    fun onCleared() {
        chat.stopGeneration()
        modelLoad.cancelModelDownload()
    }
}
```

### 3.3 Thin ViewModel

```kotlin
class SolinViewModel(
    private val coordinator: SolinViewModelCoordinator,
) : ViewModel() {
    val uiState: StateFlow<ChatUiState> = coordinator.uiState

    // Delegate every intent to coordinator
    fun sendMessage(text: String, media: List<InputMedia>) =
        coordinator.sendMessage(text, media)
    fun stopGeneration() = coordinator.stopGeneration()
    fun selectBackend(backend: BackendChoice) = coordinator.selectBackend(backend)
    // ... one-liner delegates for all ~50 public methods ...

    override fun onCleared() {
        coordinator.onCleared()
        super.onCleared()
    }
}
```

---

## Section 4 — Migration Strategy (8 Incremental Steps)

Each step is independently mergeable and shippable. No big-bang rewrite.

| Step | Controller | Lines added | Lines removed from VM | Risk | Effort |
|---|---|---|---|---|---|
| 1 | MemoryController (wire up) | ~50 | ~80 | Low | 0.5d |
| 2 | ModelLoadController | ~600 | ~700 | Medium | 2d |
| 3 | ToolExecutionController | ~400 | ~500 | Medium | 1.5d |
| 4 | DeviceContextController | ~200 | ~200 | Low | 0.5d |
| 5 | BackgroundTaskController | ~250 | ~250 | Low | 0.5d |
| 6 | ChatController | ~800 | ~900 | High | 3d |
| 7 | Create Coordinator | ~400 | ~200 | Medium | 1d |
| 8 | Audit + Settings + cleanup | ~500 | ~600 | Medium | 1.5d |

**Total estimated effort:** ~10.5 engineering days

### Step 1 — Wire up MemoryController (already partially extracted)

**Goal:** SolinViewModel delegates memory operations to the existing `MemoryController`.

**Methods to extract from VM and delegate to controller:**
- `updateMemoryEnabled(enabled)` — add to MemoryController
- `forgetLongTermMemory(id)` — add to MemoryController
- `clearLongTermMemory()` — add to MemoryController

**Fields to move:** None (MemoryController is stateless, returns result snapshots)

**Deps to pass:** `memoryIndex`, `longTermMemoryControls`, `sessionStore`, `modelRepository`, `backgroundTaskScheduler`, `semanticMemoryRuntimeController`, `ioDispatcher`

**Tests to add:**
- `MemoryControllerTest` — verify `rebuildMemoryIndex` returns correct result
- Verify `updateMemoryEnabled(false)` returns `memoryHitsCleared = true`
- Verify `handleExplicitPreferenceCommand` persists and returns both messages

**Risk:** Low. Controller already exists; just need delegation wiring.

**Effort:** 0.5 days

### Step 2 — Extract ModelLoadController

**Goal:** All model download/load/select/import logic moves to `ModelLoadController`.

**Methods to extract (~30):**
All methods listed in Section 1.2.

**Fields to move:**
- `runtimeLock: Mutex`
- `downloadMonitorJob: Job?`
- `downloadPreflightJob: Job?`
- `activeDownloadId: Long?`
- `setupDownloadQueue: ArrayDeque<ModelDownloadSource>`
- `setupDownloadInProgress: Boolean`
- `bundledModelInstallJob: Job?`
- `startupRestored: Boolean`

**Deps to pass:**
`modelRepository`, `downloadService`, `runtime`, `remoteRuntime`, `remoteConnectivityProbe`, `firstRunSetupRepository`, `huggingFaceAuthStore`, `bundledModelInstaller`, `isArm64DeviceProvider`, `ioDispatcher`, `applicationScope`

**Result type:** `ModelStateResult`, `ModelDownloadResult`, `RemoteConnectivityResult` (see Section 2.2)

**Tests to add:**
- `ModelLoadControllerTest`
  - `startModelDownload` triggers download service and emits progress
  - `cancelModelDownload` cancels active download job
  - `selectBackend(CPU)` returns `ModelStateResult` with CPU backend
  - `selectInferenceMode(Remote)` triggers remote readiness check
  - `loadModel` acquires `runtimeLock` and loads model into runtime
  - `verifyLegacyModelsOnStartup` populates installed models list
  - `saveHuggingFaceAccessToken` persists to auth store

**Risk:** Medium. Download monitoring involves coroutine jobs and state transitions. Must ensure `runtimeLock` mutual exclusion is preserved.

**Effort:** 2 days

### Step 3 — Extract ToolExecutionController

**Goal:** Agent confirmation, action draft, tool execution boundary logic moves out.

**Methods to extract (~17):**
All methods listed in Section 1.3.

**Fields to move:**
- `toolRegistry: ToolRegistry`
- `outboundSafetyPolicy: SafetyPolicy`
- `toolExecutionBoundary: TimeoutToolExecutionBoundary`

**Deps to pass:**
`actionExecutor`, `assistantOrchestrator`, `toolAuditLog`, `ioDispatcher`, `applicationScope`

**Result type:** `ToolExecutionResult` (see Section 2.3)

**Cross-controller note:** When `confirmAgentConfirmation()` triggers tool execution, the result may include `shouldContinueGeneration = true` with a `continueRunId`. The Coordinator must route this back to `ChatController.continueAfterToolObservation()`.

**Tests to add:**
- `ToolExecutionControllerTest`
  - `confirmAgentConfirmation` executes tool via boundary and returns observation
  - `dismissAgentConfirmation` clears pending confirmation
  - `rejectAgentConfirmationForPolicyDenial` records audit and clears
  - `failStaleAgentRunsOnStartup` marks stale runs as failed

**Risk:** Medium. Tool execution involves timeouts, retries, and audit logging. The boundary pattern must be preserved exactly.

**Effort:** 1.5 days

### Step 4 — Extract DeviceContextController

**Goal:** Device capability checks and authorization snapshot move out.

**Methods to extract (~11):**
All methods listed in Section 1.5.

**Fields to move:**
- `deviceContextAuthorizationSnapshot: DeviceContextAuthorizationSnapshot`

**Deps to pass:** `isArm64DeviceProvider`, `ioDispatcher`

**Result type:** `DeviceContextResult` (see Section 2.5)

**Tests to add:**
- `DeviceContextControllerTest`
  - `isArm64Device()` returns provider value
  - `refreshDeviceStatus` returns correct special access set
  - `deviceContextToolReadiness` reflects authorization snapshot

**Risk:** Low. Pure computation, no side effects beyond reading injected state.

**Effort:** 0.5 days

### Step 5 — Extract BackgroundTaskController

**Goal:** Scheduled task management and periodic check policy move out.

**Methods to extract (~8):**
All methods listed in Section 1.6.

**Fields to move:**
- `backgroundTaskUseCases: BackgroundTaskUseCases`

**Deps to pass:** `backgroundTaskScheduler`, `backgroundTaskUseCases`, `ioDispatcher`

**Result type:** `BackgroundTaskResult` (see Section 2.6)

**Tests to add:**
- `BackgroundTaskControllerTest`
  - `refreshBackgroundTasks` returns tasks from scheduler
  - `cancelBackgroundTask` calls scheduler cancel and refreshes
  - `setPeriodicCheckPolicy` persists and returns updated policy

**Risk:** Low. Straightforward delegation to scheduler.

**Effort:** 0.5 days

### Step 6 — Extract ChatController

**Goal:** All message sending, session management, generation lifecycle moves out.

**Methods to extract (~33):**
All methods listed in Section 1.1.

**Fields to move:**
- `generationJob: Job?`
- `activeGenerationRunId: String?`
- `pendingRemoteContinuation: PendingRemoteContinuation?`
- `pendingSharedInputRemoteSendRestore: PendingSharedInputRemoteSendRestore?`
- `nextVoiceInputDraftId: Long`
- `nextSharedInputDraftId: Long`

**Deps to pass:**
`sessionRepository`, `runtime`, `remoteRuntime`, `assistantOrchestrator`, `outputQualityGuard`, `ioDispatcher`, `applicationScope`

**Result type:** `ChatSendResult`, `ChatSessionResult` (see Section 2.1)

**Cross-controller flows handled by Coordinator:**
1. `sendMessage` checks `SettingsController.buildPendingRemoteSendDisclosure` -- if disclosure needed, show it; else proceed
2. After message persist, call `MemoryController.rebuildMemoryIndex`
3. Tool observation from generation triggers `ToolExecutionController` + `AuditController`

**Tests to add:**
- `ChatControllerTest`
  - `sendMessage` in local mode collects from runtime and appends messages
  - `sendMessage` when not ready returns error result
  - `stopGeneration` cancels generationJob
  - `createNewSession` persists new session and returns it
  - `selectSession` switches active session and loads messages
  - `continueAfterToolObservation` resumes generation with observation
  - Vision input rejection for local model without vision support

**Risk:** High. This is the most complex controller. It manages generation jobs, handles both local and remote inference paths, output quality guarding, tool observation loops, and shared input staging. The coroutine scoping and cancellation must be exact.

**Effort:** 3 days

### Step 7 — Create SolinViewModelCoordinator

**Goal:** Introduce the Coordinator that holds all controllers and assembles `ChatUiState`. The existing `SolinViewModel` becomes a thin delegator.

**What changes:**
- Create `SolinViewModelCoordinator` class
- Move `_uiState: MutableStateFlow<ChatUiState>` from SolinViewModel to Coordinator
- Move `restoreStartupState()`, `createInitialState()`, `onCleared()` to Coordinator
- Add intent-routing methods that call controllers and apply results
- SolinViewModel constructor takes `SolinViewModelCoordinator` and delegates all intents

**DI wiring:** The Hilt module (or manual factory) constructs all 8 controllers, then constructs the Coordinator, then constructs the ViewModel with the Coordinator.

**Tests to add:**
- `SolinViewModelCoordinatorTest`
  - Startup: `restoreStartupState()` calls all controller startup hooks in order
  - `sendMessage` with remote mode triggers disclosure check before sending
  - `selectBackend` applies `ModelStateResult` to `_uiState`
  - Cross-controller: confirming tool execution triggers chat continuation

**Risk:** Medium. Mostly structural reorganization. Risk is in missed cross-controller call sites.

**Effort:** 1 day

### Step 8 — Extract AuditController + SettingsController + cleanup

**Goal:** Extract remaining controllers and remove old code from SolinViewModel.

**AuditController methods to extract (~14):**
All methods listed in Section 1.7.

**SettingsController methods to extract (~23):**
All methods listed in Section 1.8.

**Fields to move to SettingsController:**
- `remoteSendDisclosureSuppressedForSession: Boolean`
- `sessionRestoreJob: Job?`
- `sessionRestoreGeneration: Long`

**Final cleanup:**
- Remove all private helper methods from SolinViewModel that now live in controllers
- Verify no dead imports
- Run full test suite

**Tests to add:**
- `AuditControllerTest`
  - `refreshAuditEvents` returns events from audit log
  - `recordRemoteSendDecision` persists to audit sink
  - `failClosedPendingRemoteSendOnStartup` marks pending sends as failed
- `SettingsControllerTest`
  - `updateGenerationParameters` persists to repo and returns result
  - `buildPendingRemoteSendDisclosure` respects policy and suppression
  - Voice input lifecycle: start, update level, finish, accept transcript

**Risk:** Medium. Remote send disclosure flow involves Settings + Chat + Audit coordination.

**Effort:** 1.5 days

---

## Section 5 — Per-Step Detail Summary

### Quick Reference Table

| Step | Controller | Methods | Fields | Key Dependencies | Tests | Risk | Effort |
|---|---|---|---|---|---|---|---|
| 1 | Memory (wire-up) | 3 add + 7 delegate | 0 | memoryIndex, longTermMemoryControls, sessionStore | MemoryControllerTest extensions | Low | 0.5d |
| 2 | ModelLoad | ~30 | 8 | modelRepository, downloadService, runtime, remoteRuntime | ModelLoadControllerTest (8 cases) | Medium | 2d |
| 3 | ToolExecution | ~17 | 3 | actionExecutor, assistantOrchestrator, toolAuditLog | ToolExecutionControllerTest (4 cases) | Medium | 1.5d |
| 4 | DeviceContext | ~11 | 1 | isArm64DeviceProvider | DeviceContextControllerTest (3 cases) | Low | 0.5d |
| 5 | BackgroundTask | ~8 | 1 | backgroundTaskScheduler, backgroundTaskUseCases | BackgroundTaskControllerTest (3 cases) | Low | 0.5d |
| 6 | Chat | ~33 | 6 | sessionRepository, runtime, remoteRuntime, outputQualityGuard | ChatControllerTest (7 cases) | High | 3d |
| 7 | Coordinator | ~50 routing | _uiState | All 8 controllers | CoordinatorTest (4 cases) | Medium | 1d |
| 8 | Audit + Settings | ~37 | 3 | toolAuditLog, remoteSendAudit*, generationParametersRepo | AuditControllerTest (3), SettingsControllerTest (3) | Medium | 1.5d |

### Dependency Graph for Controller Construction

```
MemoryController ──────────────────────────────────────────────┐
BackgroundTaskController ──────────────────────────────────────┤
DeviceContextController ───────────────────────────────────────┤
                                                                ├─► SolinViewModelCoordinator ──► SolinViewModel
AuditController ───────────────────────────────────────────────┤
SettingsController ────────────────────────────────────────────┤
ModelLoadController (needs runtime, remoteRuntime) ────────────┤
ToolExecutionController (needs actionExecutor, orchestrator) ───┤
ChatController (needs runtime, remoteRuntime, orchestrator) ────┘
```

Controllers with no inter-dependencies can be extracted in any order (Memory, DeviceContext, BackgroundTask, Audit, Settings). ModelLoad and Chat both depend on `runtime` and `remoteRuntime` but not on each other. ToolExecution depends on `actionExecutor` and `assistantOrchestrator`.

### Recommended Extraction Order (revised)

The order in Section 4 groups by risk (low first), building confidence:

1. **Memory** (already done, just wiring) -- validates the controller pattern end-to-end
2. **ModelLoad** -- biggest single chunk, establishes result-snapshot convention
3. **ToolExecution** -- validates cross-controller callback pattern (tool to chat continuation)
4. **DeviceContext** -- quick win, pure computation
5. **BackgroundTask** -- quick win, delegation-only
6. **Chat** -- most complex, benefits from all prior controllers being stable
7. **Coordinator** -- structural, all controllers already extracted
8. **Audit + Settings** -- remaining methods, final cleanup

This order ensures that by the time we tackle the high-risk ChatController (Step 6), the result-snapshot and cross-controller patterns are already proven in production by Steps 1-5.
