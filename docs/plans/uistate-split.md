# ChatUiState 拆分实施计划

> **目标**: 将单体 `ChatUiState`（60+ 字段）拆分为多个逻辑子状态，通过独立 `StateFlow` 暴露，减少不必要的 Compose 重组，提升 UI 响应性能。

---

## 1. 当前状态分析

### 1.1 ChatUiState 字段清单（57 个存储字段 + 9 个派生属性）

`ChatUiState` 定义在 `app/src/main/java/com/bytedance/zgx/solin/ChatModels.kt` 第 393 行。

| # | 字段名 | 类型 | 默认值 |
|---|--------|------|--------|
| 1 | `modelPath` | `String?` | `null` |
| 2 | `activeInstalledModelId` | `String?` | `null` |
| 3 | `installedModels` | `List<InstalledModelSummary>` | `emptyList()` |
| 4 | `selectedModelId` | `String` | `DEFAULT_CHAT_MODEL_ID` |
| 5 | `recommendedModels` | `List<RecommendedModel>` | `RECOMMENDED_MODELS` |
| 6 | `setupSelectedModelIds` | `Set<String>` | `ModelCatalog.defaultSetupModelIds()` |
| 7 | `showFirstRunSetup` | `Boolean` | `false` |
| 8 | `memoryEnabled` | `Boolean` | `true` |
| 9 | `reduceDeviceActionConfirmations` | `Boolean` | `false` |
| 10 | `semanticMemoryEnabled` | `Boolean` | `false` |
| 11 | `semanticMemoryRuntimeStatus` | `SemanticMemoryRuntimeStatus` | `NoVerifiedModel` |
| 12 | `semanticMemoryIndexedRecordCount` | `Int` | `0` |
| 13 | `semanticMemoryLastRebuiltAtMillis` | `Long?` | `null` |
| 14 | `huggingFaceAccessTokenConfigured` | `Boolean` | `false` |
| 15 | `pendingHuggingFaceAuthorizationModelId` | `String?` | `null` |
| 16 | `memoryHits` | `List<MemoryHit>` | `emptyList()` |
| 17 | `activeRunTimeline` | `List<RunTimelineItemUiSummary>` | `emptyList()` |
| 18 | `activeMemoryEvidence` | `List<MemoryEvidenceUiSummary>` | `emptyList()` |
| 19 | `activePublicWebEvidence` | `List<PublicWebEvidencePack>` | `emptyList()` |
| 20 | `longTermMemories` | `List<LongTermMemorySummary>` | `emptyList()` |
| 21 | `backgroundTasks` | `List<BackgroundTaskSummary>` | `emptyList()` |
| 22 | `backgroundTaskHistory` | `List<BackgroundTaskSummary>` | `emptyList()` |
| 23 | `periodicCheckPolicy` | `PeriodicCheckPolicySummary` | `disabled()` |
| 24 | `auditEvents` | `List<AuditEventSummary>` | `emptyList()` |
| 25 | `remoteSendAuditEvents` | `List<RemoteSendAuditSummary>` | `emptyList()` |
| 26 | `agentTraceRuns` | `List<AgentTraceRunUiSummary>` | `emptyList()` |
| 27 | `grantedSpecialAccessIds` | `Set<String>` | `emptySet()` |
| 28 | `pendingConfirmation` | `PendingAgentConfirmation?` | `null` |
| 29 | `pendingRemoteModeDisclosure` | `PendingRemoteModeDisclosure?` | `null` |
| 30 | `pendingRemoteSendDisclosure` | `PendingRemoteSendDisclosure?` | `null` |
| 31 | `remoteSendDisclosurePolicy` | `RemoteSendDisclosurePolicy` | `OnRemoteModeSwitch` |
| 32 | `pendingExternalOutcome` | `PendingExternalOutcomeConfirmation?` | `null` |
| 33 | `latestRecoveryAction` | `AgentRecoveryAction?` | `null` |
| 34 | `inferenceMode` | `InferenceMode` | `Local` |
| 35 | `remoteModelConfig` | `RemoteModelConfig` | `RemoteModelConfig()` |
| 36 | `backend` | `BackendChoice` | `GPU` |
| 37 | `generationParameters` | `GenerationParameters` | `GenerationParameters()` |
| 38 | `localMaxTotalTokens` | `Int` | `MAX_TOTAL_TOKENS` |
| 39 | `localPreferredBackends` | `Set<BackendChoice>` | `emptySet()` |
| 40 | `modelHealth` | `ModelHealth` | `NotInstalled` |
| 41 | `statusText` | `String` | `"未加载模型"` |
| 42 | `isArm64Supported` | `Boolean` | `true` |
| 43 | `availableModelStorageBytes` | `Long` | `0L` |
| 44 | `isBusy` | `Boolean` | `false` |
| 45 | `isGenerating` | `Boolean` | `false` |
| 46 | `isPreparingDownload` | `Boolean` | `false` |
| 47 | `isDownloading` | `Boolean` | `false` |
| 48 | `downloadProgressPercent` | `Int?` | `null` |
| 49 | `downloadedBytes` | `Long` | `0L` |
| 50 | `totalBytes` | `Long` | `0L` |
| 51 | `isReady` | `Boolean` | `false` |
| 52 | `sessions` | `List<ChatSessionSummary>` | `emptyList()` |
| 53 | `activeSessionId` | `String?` | `null` |
| 54 | `messages` | `List<ChatMessage>` | `emptyList()` |
| 55 | `voiceInputDraft` | `VoiceInputDraft?` | `null` |
| 56 | `voiceCapture` | `VoiceCaptureUiState` | `VoiceCaptureUiState()` |
| 57 | `pendingSharedInputDraft` | `SharedInputDraft?` | `null` |

**派生属性**（基于字段计算，非构造参数）：
`selectedRecommendedModel`, `basicSetupModels`, `chatModels`, `optionalChatModels`, `optionalModels`,
`activeLocalModelSupportsVisionInput`, `activeLocalCapabilityProfile`, `installedCapabilities`,
`installedCapabilityProfiles`, `isModelInstalled(modelId)`

### 1.2 使用规模统计

| 指标 | 数值 | 来源 |
|------|------|------|
| ViewModel 中 `_uiState.update` / `_uiState.value` 调用 | **372 处** | `SolinViewModel.kt` (6500 行) |
| SolinScreen 中 `state.` 字段访问 | **~237 处** | `ui/SolinScreen.kt` (6324 行) |
| 引用 ChatUiState 的源文件 | **3 个** | `ChatModels.kt`, `SolinViewModel.kt`, `SolinScreen.kt` |
| 引用 ChatUiState 的测试文件 | **5 个** | 1 个 unit test + 4 个 androidTest |

### 1.3 核心问题

每次 `_uiState.update { it.copy(...) }` 都会创建一个全新的 `ChatUiState` 实例，触发所有观察 `uiState` 的 composable 重组。典型问题场景：

- **语音波形更新** (`voiceCapture.level` @ 10Hz): 触发整个 `SolinScreen` 及其 ~91 个 composable 重组
- **流式 token 追加** (`messages` 列表变化): 触发模型状态徽章、后台任务按钮、记忆条等所有组件重组
- **下载进度更新**: 触发不相关的记忆列表、审计事件列表、会话管理组件重组
- **后台任务刷新**: 触发消息列表和输入框重组

---

## 2. 子状态分组定义

按用户指定的 9 个逻辑子状态分组，每个子状态包含数据类定义、管理控制器和观察的 composable。

---

### 2.1 ChatState（对话核心状态）

**职责**: 管理对话消息、生成状态、会话管理。

```kotlin
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val isBusy: Boolean = false,
    val isGenerating: Boolean = false,
    val statusText: String = "未加载模型",
    val isReady: Boolean = false,
    val sessions: List<ChatSessionSummary> = emptyList(),
    val activeSessionId: String? = null,
)
```

**管理控制器**: `SolinViewModel`（消息发送、生成控制、会话管理）

**观察的 Composable**:
| Composable | 使用的字段 |
|------------|-----------|
| `SolinScreen` (消息列表区域) | `messages`, `isGenerating`, `activeSessionId` |
| `ChatEmptyState` | `isReady`, `isBusy` |
| `Composer` | `isBusy`, `isReady`, `isGenerating` |
| `ChatTopBar` / `TopMenuItem` | `isBusy` |
| `MessageBubble` | `isGenerating` (流式指示器) |
| `SessionManagerSheet` | `sessions`, `activeSessionId` |
| `RecoveryActionEntry` | `isBusy` |

**更新频率**: **高**（用户输入、流式 token 到达、生成状态切换）

---

### 2.2 ModelState（模型加载与运行时）

**职责**: 模型文件路径、安装状态、运行时后端选择、下载进度、设备能力。

```kotlin
data class ModelState(
    val modelPath: String? = null,
    val activeInstalledModelId: String? = null,
    val installedModels: List<InstalledModelSummary> = emptyList(),
    val selectedModelId: String = DEFAULT_CHAT_MODEL_ID,
    val recommendedModels: List<RecommendedModel> = RECOMMENDED_MODELS,
    val setupSelectedModelIds: Set<String> = ModelCatalog.defaultSetupModelIds(),
    val showFirstRunSetup: Boolean = false,
    val inferenceMode: InferenceMode = InferenceMode.Local,
    val backend: BackendChoice = BackendChoice.GPU,
    val modelHealth: ModelHealth = ModelHealth(
        profileId = DEFAULT_CHAT_MODEL_ID,
        state = ModelHealthState.NotInstalled,
    ),
    val isPreparingDownload: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgressPercent: Int? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val isArm64Supported: Boolean = true,
    val availableModelStorageBytes: Long = 0L,
    val localMaxTotalTokens: Int = LocalModelTokenLimits.MAX_TOTAL_TOKENS,
    val localPreferredBackends: Set<BackendChoice> = emptySet(),
    val huggingFaceAccessTokenConfigured: Boolean = false,
    val pendingHuggingFaceAuthorizationModelId: String? = null,
) {
    // 派生属性从 ChatUiState 迁移到此处
    val selectedRecommendedModel: RecommendedModel
        get() = ModelCatalog.recommendedChatModelById(selectedModelId)

    val activeLocalCapabilityProfile: ModelCapabilityProfile?
        get() = installedModels
            .firstOrNull { it.id == activeInstalledModelId }
            ?.capabilityProfile

    val activeLocalModelSupportsVisionInput: Boolean
        get() = activeLocalCapabilityProfile?.supportsVisionInput == true

    val installedCapabilities: Set<ModelCapability>
        get() = installedModels.filter { it.isUsable }.map { it.capability }.toSet()

    val installedCapabilityProfiles: List<ModelCapabilityProfile>
        get() = installedModels.mapNotNull { it.capabilityProfile }

    val basicSetupModels: List<RecommendedModel>
        get() = ModelCatalog.basicSetupModels()

    fun isModelInstalled(modelId: String): Boolean =
        installedModels.any { it.recommendedModelId == modelId && it.isUsable }
}
```

**管理控制器**: `SolinViewModel` + `ModelRepository`（模型下载、加载、选择、后端切换）

**观察的 Composable**:
| Composable | 使用的字段 |
|------------|-----------|
| `CompactModelStatusChip` | `isReady`, `isBusy`, `inferenceMode`, `isDownloading` |
| `RuntimeStatusBadge` | `isReady`, `isBusy`, `inferenceMode`, `modelPath`, `isDownloading`, `downloadProgressPercent` |
| `QuickModelSetup` | `isBusy`, `isDownloading`, `isPreparingDownload`, `downloadProgressPercent`, `totalBytes`, `selectedRecommendedModel` |
| `ModelManagerSheet` (全部标签页) | 几乎所有字段 |
| `FirstRunSetupPanel` | `setupSelectedModelIds`, `showFirstRunSetup`, `isBusy`, `basicSetupModels`, `isModelInstalled()` |
| `StatusSummaryRow` | `inferenceMode`, `installedModels`, `activeInstalledModelId`, `selectedRecommendedModel` |
| `DeviceCheck` | `isArm64Supported`, `availableModelStorageBytes` |
| `ProgressBlock` | `isPreparingDownload`, `isDownloading`, `downloadProgressPercent`, `downloadedBytes`, `totalBytes` |

**更新频率**: **中高**（下载进度高频更新，模型选择/加载低频）

---

### 2.3 ToolState（工具执行与确认）

**职责**: 代理工具执行的待确认项、时间线、外部结果、恢复操作、特殊权限。

```kotlin
data class ToolState(
    val pendingConfirmation: PendingAgentConfirmation? = null,
    val pendingExternalOutcome: PendingExternalOutcomeConfirmation? = null,
    val latestRecoveryAction: AgentRecoveryAction? = null,
    val activeRunTimeline: List<RunTimelineItemUiSummary> = emptyList(),
    val activePublicWebEvidence: List<PublicWebEvidencePack> = emptyList(),
    val grantedSpecialAccessIds: Set<String> = emptySet(),
)
```

**管理控制器**: `SolinViewModel` + `AgentOrchestrator`（工具执行、确认流程、权限管理）

**观察的 Composable**:
| Composable | 使用的字段 |
|------------|-----------|
| `ActionDraftSheet` | `pendingConfirmation`, `grantedSpecialAccessIds` |
| `ExternalOutcomeSheet` | `pendingExternalOutcome` |
| `RecoveryActionEntry` | `latestRecoveryAction`, `pendingConfirmation`, `pendingExternalOutcome` |
| `RunTimelineStrip` | `activeRunTimeline` |
| `SourcesStrip` | `activePublicWebEvidence` |

**更新频率**: **中低**（仅在工具执行期间活跃，每次工具结果到达时更新）

---

### 2.4 MemoryState（记忆系统）

**职责**: 长期记忆、记忆命中、语义记忆运行时状态、记忆开关。

```kotlin
data class MemoryState(
    val memoryEnabled: Boolean = true,
    val semanticMemoryEnabled: Boolean = false,
    val semanticMemoryRuntimeStatus: SemanticMemoryRuntimeStatus =
        SemanticMemoryRuntimeStatus.NoVerifiedModel,
    val semanticMemoryIndexedRecordCount: Int = 0,
    val semanticMemoryLastRebuiltAtMillis: Long? = null,
    val memoryHits: List<MemoryHit> = emptyList(),
    val activeMemoryEvidence: List<MemoryEvidenceUiSummary> = emptyList(),
    val longTermMemories: List<LongTermMemorySummary> = emptyList(),
)
```

**管理控制器**: `SolinViewModel` + `MemoryRepository`（记忆检索、索引重建、长期记忆管理）

**观察的 Composable**:
| Composable | 使用的字段 |
|------------|-----------|
| `MemoryContextStrip` | `activeMemoryEvidence` |
| `ModelManagerSheet` 记忆标签页 | `memoryEnabled`, `longTermMemories`, `semanticMemoryEnabled`, `semanticMemoryRuntimeStatus` |

**更新频率**: **低**（记忆命中在生成时更新，设置变更手动触发）

---

### 2.5 DeviceState（设备上下文与权限）

**职责**: 设备能力和可访问性相关设置。

```kotlin
data class DeviceState(
    val reduceDeviceActionConfirmations: Boolean = false,
    val isArm64Supported: Boolean = true,
)
```

> **注意**: `isArm64Supported` 也被 `ModelState` 的派生逻辑使用（在 `DeviceCheck` composable 中）。它属于设备静态能力，在模型选择逻辑中被引用。建议保留在 `DeviceState` 中，在需要的 composable 中同时观察两个子状态。如果 `ModelState` 的派生属性需要它，可以在 ViewModel 中组合读取。

**管理控制器**: `SolinViewModel` + `DeviceContextProvider`

**观察的 Composable**:
| Composable | 使用的字段 |
|------------|-----------|
| `ModelManagerSheet` 设备确认设置 | `reduceDeviceActionConfirmations` |
| `DeviceCheck` (通过 `ModelState` 间接) | `isArm64Supported` |

**更新频率**: **极低**（设置变更时才更新，`isArm64Supported` 在应用生命周期内不变）

---

### 2.6 BackgroundTaskState（后台任务）

**职责**: 后台定时任务列表、历史记录、周期检查策略。

```kotlin
data class BackgroundTaskState(
    val backgroundTasks: List<BackgroundTaskSummary> = emptyList(),
    val backgroundTaskHistory: List<BackgroundTaskSummary> = emptyList(),
    val periodicCheckPolicy: PeriodicCheckPolicySummary = PeriodicCheckPolicySummary.disabled(),
)
```

**管理控制器**: `SolinViewModel` + `BackgroundTaskUseCases`

**观察的 Composable**:
| Composable | 使用的字段 |
|------------|-----------|
| `BackgroundTaskSheet` | `backgroundTasks`, `backgroundTaskHistory`, `periodicCheckPolicy` |
| `ChatTopBar` (间接) | 后台任务按钮可见性（通过 `state.backgroundTasks.isNotEmpty()`） |

**更新频率**: **低**（任务状态变化时更新，非实时 UI 驱动）

---

### 2.7 AuditState（审计与追踪）

**职责**: 审计事件、远程发送审计、代理追踪运行记录。

```kotlin
data class AuditState(
    val auditEvents: List<AuditEventSummary> = emptyList(),
    val remoteSendAuditEvents: List<RemoteSendAuditSummary> = emptyList(),
    val agentTraceRuns: List<AgentTraceRunUiSummary> = emptyList(),
)
```

**管理控制器**: `SolinViewModel` + `AuditLogRepository`

**观察的 Composable**:
| Composable | 使用的字段 |
|------------|-----------|
| `BackgroundTaskSheet` 审计标签页 | `auditEvents`, `agentTraceRuns` |
| `ModelManagerSheet` 隐私标签页 | `remoteSendAuditEvents`, `auditEvents` |

**更新频率**: **低**（审计事件追加，非实时 UI 驱动；用户打开审计面板时才刷新）

---

### 2.8 UiDisplayState（UI 显示与输入）

**职责**: 语音捕获状态、共享输入草稿、远程发送披露弹窗状态。

```kotlin
data class UiDisplayState(
    val voiceInputDraft: VoiceInputDraft? = null,
    val voiceCapture: VoiceCaptureUiState = VoiceCaptureUiState(),
    val pendingSharedInputDraft: SharedInputDraft? = null,
    val pendingRemoteModeDisclosure: PendingRemoteModeDisclosure? = null,
    val pendingRemoteSendDisclosure: PendingRemoteSendDisclosure? = null,
    val remoteSendDisclosurePolicy: RemoteSendDisclosurePolicy =
        RemoteSendDisclosurePolicy.OnRemoteModeSwitch,
)
```

**管理控制器**: `SolinViewModel`（语音输入管理、披露弹窗控制）

**观察的 Composable**:
| Composable | 使用的字段 |
|------------|-----------|
| `Composer` | `voiceCapture`, `pendingSharedInputDraft` |
| `SolinScreen` (LaunchedEffect) | `voiceInputDraft` |
| `RemoteModeDisclosureSheet` | `pendingRemoteModeDisclosure` |
| `RemoteSendDisclosureSheet` | `pendingRemoteSendDisclosure`, `remoteSendDisclosurePolicy` |

**更新频率**: **极高**（语音波形 `voiceCapture.level` 和 `waveformLevels` 每 100ms 更新）

---

### 2.9 SettingsState（生成参数与远程模型配置）

**职责**: 生成温度/topP/topK/推理努力、远程模型连接配置。

```kotlin
data class SettingsState(
    val generationParameters: GenerationParameters = GenerationParameters(),
    val remoteModelConfig: RemoteModelConfig = RemoteModelConfig(),
)
```

**管理控制器**: `SolinViewModel` + `GenerationParametersRepository` + `RemoteModelRepository`

**观察的 Composable**:
| Composable | 使用的字段 |
|------------|-----------|
| `ModelManagerSheet` 生成参数面板 | `generationParameters` |
| `ModelManagerSheet` 远程模型配置面板 | `remoteModelConfig` |
| `StatusSummaryRow` | `remoteModelConfig.modelName` |

**更新频率**: **低**（用户手动调整参数或配置远程模型时）

---

## 3. 组合 AppState 设计

### 3.1 设计方案：独立 StateFlow + 派生组合

采用**独立 StateFlow 方案**，每个子状态有自己的 `MutableStateFlow`，同时提供一个组合的 `AppState` 用于需要跨子状态访问的场景。

```kotlin
class SolinViewModel : ViewModel() {
    // ===== 独立子状态 MutableStateFlow =====
    private val _chatState = MutableStateFlow(ChatState())
    private val _modelState = MutableStateFlow(ModelState())
    private val _toolState = MutableStateFlow(ToolState())
    private val _memoryState = MutableStateFlow(MemoryState())
    private val _deviceState = MutableStateFlow(DeviceState())
    private val _backgroundTaskState = MutableStateFlow(BackgroundTaskState())
    private val _auditState = MutableStateFlow(AuditState())
    private val _uiDisplayState = MutableStateFlow(UiDisplayState())
    private val _settingsState = MutableStateFlow(SettingsState())

    // ===== 公开只读 StateFlow =====
    val chatState: StateFlow<ChatState> = _chatState.asStateFlow()
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()
    val toolState: StateFlow<ToolState> = _toolState.asStateFlow()
    val memoryState: StateFlow<MemoryState> = _memoryState.asStateFlow()
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()
    val backgroundTaskState: StateFlow<BackgroundTaskState> = _backgroundTaskState.asStateFlow()
    val auditState: StateFlow<AuditState> = _auditState.asStateFlow()
    val uiDisplayState: StateFlow<UiDisplayState> = _uiDisplayState.asStateFlow()
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()
```

### 3.2 组合 AppState（用于跨子状态操作和向后兼容）

```kotlin
    // ===== 组合状态（用于需要同时访问多个子状态的场景） =====
    data class AppState(
        val chat: ChatState = ChatState(),
        val model: ModelState = ModelState(),
        val tool: ToolState = ToolState(),
        val memory: MemoryState = MemoryState(),
        val device: DeviceState = DeviceState(),
        val backgroundTask: BackgroundTaskState = BackgroundTaskState(),
        val audit: AuditState = AuditState(),
        val uiDisplay: UiDisplayState = UiDisplayState(),
        val settings: SettingsState = SettingsState(),
    )

    val appState: StateFlow<AppState> = combine(
        chatState, modelState, toolState, memoryState,
        deviceState, backgroundTaskState, auditState, uiDisplayState, settingsState
    ) { values ->
        val (chat, model, tool, memory, device, bg, audit, ui, settings) = values
        AppState(
            chat = chat, model = model, tool = tool, memory = memory,
            device = device, backgroundTask = bg, audit = audit,
            uiDisplay = ui, settings = settings
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppState()
    )
```

### 3.3 向后兼容：从 AppState 派生 ChatUiState

```kotlin
    // ===== 向后兼容：从子状态组合派生旧的 ChatUiState =====
    val uiState: StateFlow<ChatUiState> = appState
        .map { it.toChatUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())
```

并在 `AppState` 上添加扩展函数：

```kotlin
fun AppState.toChatUiState(): ChatUiState = ChatUiState(
    // ChatState
    messages = chat.messages,
    isBusy = chat.isBusy,
    isGenerating = chat.isGenerating,
    statusText = chat.statusText,
    isReady = chat.isReady,
    sessions = chat.sessions,
    activeSessionId = chat.activeSessionId,
    // ModelState
    modelPath = model.modelPath,
    activeInstalledModelId = model.activeInstalledModelId,
    installedModels = model.installedModels,
    selectedModelId = model.selectedModelId,
    recommendedModels = model.recommendedModels,
    setupSelectedModelIds = model.setupSelectedModelIds,
    showFirstRunSetup = model.showFirstRunSetup,
    inferenceMode = model.inferenceMode,
    backend = model.backend,
    modelHealth = model.modelHealth,
    isPreparingDownload = model.isPreparingDownload,
    isDownloading = model.isDownloading,
    downloadProgressPercent = model.downloadProgressPercent,
    downloadedBytes = model.downloadedBytes,
    totalBytes = model.totalBytes,
    isArm64Supported = model.isArm64Supported,
    availableModelStorageBytes = model.availableModelStorageBytes,
    localMaxTotalTokens = model.localMaxTotalTokens,
    localPreferredBackends = model.localPreferredBackends,
    huggingFaceAccessTokenConfigured = model.huggingFaceAccessTokenConfigured,
    pendingHuggingFaceAuthorizationModelId = model.pendingHuggingFaceAuthorizationModelId,
    // ToolState
    pendingConfirmation = tool.pendingConfirmation,
    pendingExternalOutcome = tool.pendingExternalOutcome,
    latestRecoveryAction = tool.latestRecoveryAction,
    activeRunTimeline = tool.activeRunTimeline,
    activePublicWebEvidence = tool.activePublicWebEvidence,
    grantedSpecialAccessIds = tool.grantedSpecialAccessIds,
    // MemoryState
    memoryEnabled = memory.memoryEnabled,
    semanticMemoryEnabled = memory.semanticMemoryEnabled,
    semanticMemoryRuntimeStatus = memory.semanticMemoryRuntimeStatus,
    semanticMemoryIndexedRecordCount = memory.semanticMemoryIndexedRecordCount,
    semanticMemoryLastRebuiltAtMillis = memory.semanticMemoryLastRebuiltAtMillis,
    memoryHits = memory.memoryHits,
    activeMemoryEvidence = memory.activeMemoryEvidence,
    longTermMemories = memory.longTermMemories,
    // DeviceState
    reduceDeviceActionConfirmations = device.reduceDeviceActionConfirmations,
    // BackgroundTaskState
    backgroundTasks = backgroundTask.backgroundTasks,
    backgroundTaskHistory = backgroundTask.backgroundTaskHistory,
    periodicCheckPolicy = backgroundTask.periodicCheckPolicy,
    // AuditState
    auditEvents = audit.auditEvents,
    remoteSendAuditEvents = audit.remoteSendAuditEvents,
    agentTraceRuns = audit.agentTraceRuns,
    // UiDisplayState
    voiceInputDraft = uiDisplay.voiceInputDraft,
    voiceCapture = uiDisplay.voiceCapture,
    pendingSharedInputDraft = uiDisplay.pendingSharedInputDraft,
    pendingRemoteModeDisclosure = uiDisplay.pendingRemoteModeDisclosure,
    pendingRemoteSendDisclosure = uiDisplay.pendingRemoteSendDisclosure,
    remoteSendDisclosurePolicy = uiDisplay.remoteSendDisclosurePolicy,
    // SettingsState
    generationParameters = settings.generationParameters,
    remoteModelConfig = settings.remoteModelConfig,
)
```

### 3.4 Composable 观察模式

**当前模式**（全量重组）:
```kotlin
@Composable
fun SolinScreen(state: ChatUiState, ...) {
    // 任何字段变化都触发整个 SolinScreen 重组
}
```

**新模式**（精准观察，推荐用于 Activity/Fragment 入口）:
```kotlin
// 在 MainActivity 中收集各子状态
@Composable
fun SolinApp(viewModel: SolinViewModel) {
    val chatState by viewModel.chatState.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val toolState by viewModel.toolState.collectAsStateWithLifecycle()
    val memoryState by viewModel.memoryState.collectAsStateWithLifecycle()
    val deviceState by viewModel.deviceState.collectAsStateWithLifecycle()
    val backgroundTaskState by viewModel.backgroundTaskState.collectAsStateWithLifecycle()
    val auditState by viewModel.auditState.collectAsStateWithLifecycle()
    val uiDisplayState by viewModel.uiDisplayState.collectAsStateWithLifecycle()
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle()

    SolinScreen(
        chatState = chatState,
        modelState = modelState,
        toolState = toolState,
        memoryState = memoryState,
        deviceState = deviceState,
        backgroundTaskState = backgroundTaskState,
        auditState = auditState,
        uiDisplayState = uiDisplayState,
        settingsState = settingsState,
        // ... callbacks
    )
}
```

**对于深层子组件**，只传递它们实际需要的子状态：
```kotlin
// Composer 只需要 chatState 和 uiDisplayState
Composer(
    chatState = chatState,
    uiDisplayState = uiDisplayState,
    ...
)

// CompactModelStatusChip 只需要 modelState 的几个字段
CompactModelStatusChip(
    modelState = modelState,
    ...
)
```

---

## 4. 迁移策略（5 步走）

### 步骤 A: 创建子状态数据类

**工作内容**:
1. 创建新文件 `app/src/main/java/com/bytedance/zgx/solin/AppState.kt`
2. 在其中定义 9 个子状态 `data class`（见第 2 节），每个都有合理的默认值
3. 定义 `AppState` 组合数据类
4. 添加 `AppState.toChatUiState()` 扩展函数
5. 添加 `ChatUiState` 到各子状态的映射扩展函数（`toChatState()`, `toModelState()` 等），用于从现有 `_uiState` 初始化子状态
6. **不修改** 现有 `ChatUiState`

**文件变更**:
- 新增: `app/src/main/java/com/bytedance/zgx/solin/AppState.kt`

**验证**:
- 编译通过
- 现有测试不受影响（纯添加，不修改现有代码）

**工作量**: **0.5 天**

---

### 步骤 B: 在 ViewModel 中添加独立 StateFlow

**工作内容**:
1. 在 `SolinViewModel` 中创建 9 个 `MutableStateFlow` 和对应的公开 `StateFlow`
2. 修改 `createInitialState()` 同时初始化各子状态的初始值
3. 采用**渐进式方案**：保留 `_uiState` 作为单一事实来源，用 `map` + `distinctUntilChanged` 从 `_uiState` 派生子状态 flow

```kotlin
// 渐进式：从 _uiState 派生子状态
val chatState: StateFlow<ChatState> = _uiState
    .map { it.toChatState() }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, ChatState())

val modelState: StateFlow<ModelState> = _uiState
    .map { it.toModelState() }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, ModelState())

// ... 其他子状态类似
```

4. 添加 `toChatState()`, `toModelState()` 等扩展到 `ChatUiState` 上

**为什么选这个方案**:
- 不需要修改 372 处 `_uiState.update` 调用
- 子状态 flow 自动与 `_uiState` 保持同步
- `distinctUntilChanged()` 确保只有子状态真正变化时才发射
- 为步骤 C 的 UI 迁移做好准备

**文件变更**:
- 修改: `app/src/main/java/com/bytedance/zgx/solin/SolinViewModel.kt`
- 修改: `app/src/main/java/com/bytedance/zgx/solin/AppState.kt`（添加 `ChatUiState.toXxxState()` 扩展）

**验证**:
- 编译通过
- 所有现有测试通过（`_uiState` 仍是事实来源，行为不变）
- 新增测试：验证子状态 flow 在 `_uiState` 更新后发射正确的值

**工作量**: **1 天**

---

### 步骤 C: 更新 Composable 观察独立子状态 flow

**工作内容**:
1. 修改 `SolinScreen` 签名，接受各子状态而非单体 `ChatUiState`
2. 在 `MainActivity`（或调用 `SolinScreen` 的入口）中，从 ViewModel 收集各子状态并传入
3. 对于深层子组件，只传递它们实际需要的子状态

**SolinScreen 重构映射**:

| Composable | 需要的子状态 |
|------------|-------------|
| `ChatTopBar` | `modelState` (isReady, inferenceMode, isBusy, isDownloading) + `chatState` (isBusy) |
| `CompactModelStatusChip` | `modelState` |
| `ChatEmptyState` | `modelState` + `chatState` (isReady, isBusy) |
| `QuickModelSetup` | `modelState` (下载相关字段) |
| `MessageBubble` 列表 | `chatState` (messages, isGenerating) |
| `Composer` | `chatState` (isBusy, isReady, isGenerating) + `uiDisplayState` (voiceCapture, pendingSharedInputDraft) |
| `RunTimelineStrip` | `toolState` (activeRunTimeline) |
| `MemoryContextStrip` | `memoryState` (activeMemoryEvidence) |
| `SourcesStrip` | `toolState` (activePublicWebEvidence) |
| `RecoveryActionEntry` | `toolState` + `chatState` (isBusy) |
| `ActionDraftSheet` | `toolState` |
| `ExternalOutcomeSheet` | `toolState` |
| `RemoteModeDisclosureSheet` | `uiDisplayState` |
| `RemoteSendDisclosureSheet` | `uiDisplayState` |
| `ModelManagerSheet` | `modelState` + `memoryState` + `settingsState` + `deviceState` |
| `SessionManagerSheet` | `chatState` (sessions, activeSessionId) |
| `BackgroundTaskSheet` | `backgroundTaskState` + `auditState` |

**文件变更**:
- 修改: `app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt`
- 修改: `app/src/main/java/com/bytedance/zgx/solin/MainActivity.kt`（或 SolinScreen 调用入口）

**验证**:
- 编译通过
- 所有 UI 测试通过
- 手动测试：所有功能正常工作
- 使用 Layout Inspector / Compose 调试工具验证重组范围缩小

**工作量**: **2 天**

---

### 步骤 D: 保持 ChatUiState 作为派生组合状态用于向后兼容

**工作内容**:
1. 确保 `uiState: StateFlow<ChatUiState>` 仍然可用（从 `appState` 或 `_uiState` 派生）
2. 在 `ChatUiState` 上添加 `@Deprecated` 注解，提示迁移到子状态 API
3. 更新所有仍直接使用 `ChatUiState` 的测试文件，使其可以同时使用旧 API 和新 API

**测试文件影响**:
- `ChatUiStateModelVerificationTest.kt`: 保持不变（直接构造 `ChatUiState` 仍有效，测试派生属性）
- `SolinScreenDisplayTest.kt`: 保持不变（测试辅助函数可以继续使用 `ChatUiState` 构造测试数据）
- 5 个 `androidTest` 文件: 保持不变（通过 Activity 场景测试，不直接构造 `ChatUiState`）

**文件变更**:
- 修改: `app/src/main/java/com/bytedance/zgx/solin/ChatModels.kt`（添加 `@Deprecated` 到 `ChatUiState`）
- 修改: `app/src/main/java/com/bytedance/zgx/solin/SolinViewModel.kt`（确保 `uiState` 正确派生）

**验证**:
- 所有测试通过
- 废弃警告正确显示
- 代码扫描：`grep -r "ChatUiState" app/src/main/` 确认只有必要的引用

**工作量**: **0.5 天**

---

### 步骤 E: 逐步移除 ChatUiState

**工作内容**（长期任务，按子状态分批进行）:

1. **E1 - 迁移 ChatState 更新**: 将所有 `_uiState.update { it.copy(messages = ..., isBusy = ...) }` 替换为 `_chatState.update { it.copy(...) }`
2. **E2 - 迁移 ModelState 更新**: 将模型相关的 `_uiState.update` 替换为 `_modelState.update`
3. **E3 - 迁移 ToolState + UiDisplayState 更新**: 替换工具和 UI 显示相关的更新
4. **E4 - 迁移剩余子状态**: MemoryState, DeviceState, BackgroundTaskState, AuditState, SettingsState
5. **E5 - 最终清理**: 删除 `_uiState`、`ChatUiState` 数据类、`toChatUiState()` 扩展

**关键**: 在 E1-E4 期间，将 `uiState` 从 `_uiState` 直接持有改为从 `combine` 各子状态 flow 派生：

```kotlin
// 迁移完成后的 uiState 实现
val uiState: StateFlow<ChatUiState> = combine(
    _chatState, _modelState, _toolState, _memoryState,
    _deviceState, _backgroundTaskState, _auditState, _uiDisplayState, _settingsState
) { values ->
    AppState(/* ... */).toChatUiState()
}.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())
```

**文件变更**:
- 修改: `SolinViewModel.kt`（所有 ~200 处 `_uiState.update` 调用）
- 修改: `ChatModels.kt`（最终删除 `ChatUiState`）
- 修改: 所有测试文件

**验证**:
- 完整测试套件通过
- 手动回归测试全部功能
- 性能基准测试对比前后数据

**工作量**: **3-4 天**（建议分散在多个 PR 中，每个 PR 迁移 1-2 个子状态）

---

## 5. 预期性能收益

### 5.1 重组减少分析

| 场景 | 当前行为 | 拆分后行为 | 重组减少 |
|------|----------|-----------|---------|
| 语音波形更新 (`voiceCapture.level` @ 10Hz) | 整个 SolinScreen + ~91 composable | 仅 `Composer` 内的语音指示器 (~3 composable) | **95%+** |
| 流式 token 追加 (`messages`) | 整个屏幕重组 | 仅消息列表 LazyColumn | **80%+** |
| 下载进度更新 | 整个屏幕重组 | 仅 `QuickModelSetup`/`ProgressBlock` | **85%+** |
| 模型加载完成 (`isReady` 变化) | 整个屏幕重组 | `ChatTopBar` 徽章 + `Composer` + 空状态 | **60%+** |
| 后台任务刷新 | 整个屏幕重组 | 仅 `BackgroundTaskSheet` 内容 | **90%+** |
| 审计事件追加 | 整个屏幕重组 | 仅 `BackgroundTaskSheet` 审计标签 | **95%+** |
| 记忆开关切换 | 整个屏幕重组 | 仅 `ModelManagerSheet` 记忆标签 | **90%+** |

### 5.2 量化估算

- **语音输入时**: 每秒 10 次全屏幕重组 -> 每秒 10 次局部重组，减少约 **90% 重组 CPU 开销**
- **生成回复时**: 每次 token 到达触发全量重组 -> 仅触发消息列表重组，减少约 **70% 重组开销**
- **模型下载时**: 进度更新触发全量重组 -> 仅进度条区域重组，减少约 **85% 重组开销**

### 5.3 distinctUntilChanged 效率

每个子状态是 `data class`，`distinctUntilChanged()` 使用结构相等性。如果 `_uiState.update` 只改变了 `ModelState` 的字段，那么 `chatState`, `toolState`, `memoryState` 等 8 个子状态 flow 都不会发射新值——它们的 `distinctUntilChanged()` 会过滤掉这些变化。

### 5.4 可衡量目标

1. **下载期间帧丢失**: 从 ~15-20 帧丢失（进度条 + 消息列表可见卡顿）降至 0-2 帧丢失
2. **生成期间滚动流畅度**: 即使 `memoryHits` 或 `activeRunTimeline` 更新到达，消息列表 `LazyColumn` 滚动应保持 60fps
3. **Compose Compiler 指标**: 拆分后 `@Composable` 函数应显示 `skippable: true` 和 `restartable: true`，参数类型为 stable

---

## 6. 风险评估与测试策略

### 6.1 风险矩阵

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| 子状态边界不正确，导致跨状态更新困难 | 中 | 高 | 仔细分析现有 `update` 调用中的跨字段 copy；保留 `uiState` 作为安全网，必要时可同时更新多个子状态 |
| `distinctUntilChanged` 对复杂对象失效 | 中 | 中 | 确保所有子状态是 `data class`（有正确的 `equals`）；对 `List` 字段确保内容变化时真正产生新的 List 实例（使用 `.toList()` 或 `mutableStateListOf`） |
| 迁移过程中引入回归 bug | 高 | 中 | 分步迁移，每步都有完整测试覆盖；保留旧 API 直到新 API 验证稳定；使用 feature flag 控制新旧路径 |
| `combine` 9 个 flow 的性能开销 | 低 | 低 | `combine` 有 `SharingStarted.Eagerly` 且各子流有 `distinctUntilChanged`；9 个 flow 的 combine 开销可忽略（每个 flow 大部分时间不发射） |
| 测试代码需要大量修改 | 中 | 低 | 测试可以继续使用 `ChatUiState()` 构造测试数据；只有直接观察 `uiState` 的测试需要考虑更新 |
| 步骤 B 的派生方案引入额外 map 开销 | 低 | 低 | 每次 `_uiState` 更新需要 9 次 `map` 操作，但每次只是字段拷贝，开销远小于触发的重组 |
| 跨子状态事务一致性 | 低 | 高 | 如果一个操作需要同时更新 `chatState` 和 `modelState`（如"加载模型"），使用 `update` 原子性保证。在渐进式方案中（从 `_uiState` 派生），事务一致性由 `_uiState.update` 的原子性自动保证 |

### 6.2 测试策略

**单元测试**:
- 为每个 `ChatUiState.toXxxState()` 扩展函数编写测试，验证字段映射正确
- 测试 `distinctUntilChanged` 行为：修改 `ModelState` 字段不应导致 `ChatState` flow 发射
- 测试 `AppState.toChatUiState()` 往返一致性：`uiState -> toChatState() -> toChatUiState()` 应还原
- 现有 `ChatUiStateModelVerificationTest` 保持不变（验证派生属性仍然正确）

**UI 测试**:
- 现有 `SolinScreenDisplayTest` 保持使用 `ChatUiState` 构造测试数据
- 新增测试：验证只传入特定子状态时对应 UI 组件正确渲染
- 5 个 `androidTest` 文件不需要修改（通过 Activity 场景测试）

**集成测试**:
- 端到端测试：发送消息、模型加载、工具确认流程
- 验证 `_uiState`（旧）和子状态 flow（新）在所有操作后保持一致

**性能测试**:
- 使用 Compose Compiler Metrics 验证重组次数
- 在语音输入场景下对比前后帧率（`adb shell dumpsys gfxinfo`）
- 使用 Android Studio Profiler 测量 CPU 使用率差异

**手动回归测试清单**:
- [ ] 模型下载与加载（本地 + 远程）
- [ ] 本地/远程推理模式切换
- [ ] 发送消息与流式回复
- [ ] 语音输入与波形显示
- [ ] 工具调用与确认（含特殊权限）
- [ ] 后台任务与周期检查策略
- [ ] 记忆系统开关与长期记忆管理
- [ ] 远程发送披露与敏感内容确认
- [ ] 会话创建、切换与删除
- [ ] 审计事件与代理追踪查看
- [ ] 生成参数调整（温度、topP 等）
- [ ] 首次运行设置流程

---

## 7. 工作量估算总结

| 步骤 | 内容 | 工作量 | 风险 | 累计 |
|------|------|--------|------|------|
| **A** | 创建子状态数据类 + AppState + 扩展函数 | 0.5 天 | 无 | 0.5 天 |
| **B** | 添加独立 StateFlow（从 `_uiState` 派生） | 1 天 | 低 | 1.5 天 |
| **C** | 更新 Composable 观察独立 flow | 2 天 | 中 | 3.5 天 |
| **D** | 保持向后兼容 + 废弃标记 | 0.5 天 | 低 | 4 天 |
| **E1** | 迁移 ChatState 的 update/value | 1 天 | 中 | 5 天 |
| **E2** | 迁移 ModelState 的 update/value | 1 天 | 中 | 6 天 |
| **E3** | 迁移 ToolState + UiDisplayState | 1 天 | 中 | 7 天 |
| **E4** | 迁移剩余子状态 (Memory, Device, BackgroundTask, Audit, Settings) | 0.5 天 | 低 | 7.5 天 |
| **E5** | 删除 ChatUiState + 最终清理 | 0.5 天 | 低 | 8 天 |

**总计**: 约 **8 个工作日**（可在 2 周内完成，含测试和代码审查）

**建议里程碑**:
- **M1（第 1.5 天）**: 步骤 A+B 完成，子状态 flow 可被读取，旧代码不受影响
- **M2（第 3.5 天）**: 步骤 C 完成，`SolinScreen` 使用子状态，性能收益开始体现
- **M3（第 4 天）**: 步骤 D 完成，废弃警告就位
- **M4（第 8 天）**: 步骤 E 完成，`ChatUiState` 完全移除

---

## 8. 替代方案考虑

### 8.1 使用 `@Stable` + 字段级 `derivedStateOf`

不拆分类，而是在 `ChatUiState` 上添加 `@Stable` 注解，并让 composable 使用 `derivedStateOf { state.specificField }` 来精准观察。

**优点**: 改动最小
**缺点**: 仍然需要每个 composable 声明 `derivedStateOf`；且 `@Stable` 对包含 `List` 的类效果有限（Compose 无法知道 List 内容何时变化，除非使用 `ImmutableList`）

### 8.2 使用 `SnapshotStateList` 替代 `List`

将 `messages` 等列表字段改为 `SnapshotStateList`，实现细粒度列表更新。

**优点**: 列表变化不触发整个 state 的 copy
**缺点**: 只解决了列表问题，其他字段（如 `isBusy`、`statusText`）仍然全量触发；且 `SnapshotStateList` 不是 `data class` 友好的

### 8.3 引入 MVI 框架（如 Mavericks、Orbit）

使用成熟的 MVI 框架来管理状态。

**优点**: 有内置的状态选择和精准观察
**缺点**: 引入新依赖和学习曲线；当前代码已经有清晰的 ViewModel 模式

**结论**: 本计划的方案（拆分子状态 + 独立 StateFlow）是最适合当前代码库的渐进式改进。

---

## 9. 后续优化方向

1. **`ImmutableList`**: 考虑使用 Kotlinx Collections Immutable 的 `ImmutableList` 替代 `List`，确保 `distinctUntilChanged` 能正确检测内容变化（避免因 `mutableListOf` 复用导致的假阴性）

2. **`SharingStarted.WhileSubscribed(5000)`**: 将 `stateIn` 的启动策略从 `Eagerly` 改为 `WhileSubscribed`，在 UI 不可见时节省资源（但需确保后台任务仍能更新状态）

3. **状态保存**: 利用 `SavedStateHandle` 为关键子状态（如 `chatState.activeSessionId`, `modelState.inferenceMode`）实现进程死亡恢复

4. **细粒度收集**: 对特别热的字段（如 `voiceCapture.level`），考虑使用 `Flow<Float>` 直接暴露，避免整个 `VoiceCaptureUiState` 的比较开销

5. **与 ViewModel 拆分计划协同**: 如果后续执行 `SolinViewModel` 拆分为多个 Controller，每个 Controller 可以直接管理对应的子状态 flow，实现更清晰的职责分离
