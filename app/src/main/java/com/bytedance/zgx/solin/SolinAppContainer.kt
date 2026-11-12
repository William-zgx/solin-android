package com.bytedance.zgx.solin

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bytedance.zgx.solin.action.ActionExecutor
import com.bytedance.zgx.solin.action.HybridActionPlanningRuntime
import com.bytedance.zgx.solin.audit.RemoteSendAuditEvent
import com.bytedance.zgx.solin.audit.RemoteSendAuditRepository
import com.bytedance.zgx.solin.audit.RemoteSendAuditSink
import com.bytedance.zgx.solin.audit.ToolAuditRepository
import com.bytedance.zgx.solin.background.AndroidBackgroundTaskScheduler
import com.bytedance.zgx.solin.background.ReminderNotificationHelper
import com.bytedance.zgx.solin.background.ScheduledTaskRepository
import com.bytedance.zgx.solin.data.AssetBundledModelInstaller
import com.bytedance.zgx.solin.data.BundledModelInstaller
import com.bytedance.zgx.solin.data.DecisionModelRoutingController
import com.bytedance.zgx.solin.data.EncryptedSecretStore
import com.bytedance.zgx.solin.data.FirstRunSetupRepository
import com.bytedance.zgx.solin.data.GenerationParametersRepository
import com.bytedance.zgx.solin.data.HuggingFaceAuthRepository
import com.bytedance.zgx.solin.data.LegacyPrefsMigrator
import com.bytedance.zgx.solin.data.ModelRepository
import com.bytedance.zgx.solin.data.PreferenceSettingsStore
import com.bytedance.zgx.solin.data.RemoteModelRepository
import com.bytedance.zgx.solin.data.SessionRepository
import com.bytedance.zgx.solin.data.SharedPreferencesDecisionModelRoutingSettings
import com.bytedance.zgx.solin.data.SolinDatabase
import com.bytedance.zgx.solin.device.AndroidCalendarAvailabilityProvider
import com.bytedance.zgx.solin.device.AndroidContactSummaryProvider
import com.bytedance.zgx.solin.device.AndroidCurrentScreenControlProvider
import com.bytedance.zgx.solin.device.AndroidCurrentScreenTextProvider
import com.bytedance.zgx.solin.device.AndroidForegroundAppProvider
import com.bytedance.zgx.solin.device.AndroidNotificationSummaryProvider
import com.bytedance.zgx.solin.device.AndroidRecentFileProvider
import com.bytedance.zgx.solin.device.AndroidRecentImageTextProvider
import com.bytedance.zgx.solin.device.DeviceControlSessionService
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.SolinAccessibilityService
import com.bytedance.zgx.solin.download.ModelDownloadService
import com.bytedance.zgx.solin.evidence.EvidenceBlobStore
import com.bytedance.zgx.solin.evidence.OnDeviceEvidenceBlobStore
import com.bytedance.zgx.solin.logging.solinD
import com.bytedance.zgx.solin.mcp.McpCapabilityController
import com.bytedance.zgx.solin.mcp.McpCapabilitySettings
import com.bytedance.zgx.solin.mcp.McpClient
import com.bytedance.zgx.solin.mcp.McpModule
import com.bytedance.zgx.solin.mcp.McpServerRegistry
import com.bytedance.zgx.solin.mcp.SharedPreferencesMcpCapabilitySettings
import com.bytedance.zgx.solin.memory.LegacyMemoryStorageRetirement
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.memory.RoomMemoryDeletionEventStore
import com.bytedance.zgx.solin.memory.RoomMemoryEmbeddingStore
import com.bytedance.zgx.solin.memory.RoomMemoryRecordStore
import com.bytedance.zgx.solin.module.BuiltInToolsModule
import com.bytedance.zgx.solin.module.FrozenSolinModuleRegistry
import com.bytedance.zgx.solin.module.SolinModule
import com.bytedance.zgx.solin.module.SolinModuleRegistryImpl
import com.bytedance.zgx.solin.multimodal.AndroidCurrentScreenshotOcrProvider
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrProvider
import com.bytedance.zgx.solin.multimodal.RawScreenshotProvider
import com.bytedance.zgx.solin.orchestration.AndroidRemoteVisionDecider
import com.bytedance.zgx.solin.orchestration.AssistantOrchestrator
import com.bytedance.zgx.solin.orchestration.AvailableSkillsContributor
import com.bytedance.zgx.solin.orchestration.CompositeAgentObservationReplanner
import com.bytedance.zgx.solin.orchestration.DeadLoopDetectionReplanner
import com.bytedance.zgx.solin.orchestration.DecisionModelPlacementPolicy
import com.bytedance.zgx.solin.orchestration.DefaultContextCompactor
import com.bytedance.zgx.solin.orchestration.DefaultSolinEventBus
import com.bytedance.zgx.solin.orchestration.DefaultToolProgressPublisher
import com.bytedance.zgx.solin.orchestration.InMemoryTelemetrySink
import com.bytedance.zgx.solin.orchestration.MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES
import com.bytedance.zgx.solin.orchestration.MetricSample
import com.bytedance.zgx.solin.orchestration.ModelObservationReplanner
import com.bytedance.zgx.solin.orchestration.ModelRuntimeDispatcher
import com.bytedance.zgx.solin.orchestration.RemoteVisionObservationReplanner
import com.bytedance.zgx.solin.orchestration.RoomAgentTraceStore
import com.bytedance.zgx.solin.orchestration.RoomRunPlacementBindingStore
import com.bytedance.zgx.solin.orchestration.RunPlacementRecoveryContext
import com.bytedance.zgx.solin.orchestration.SequentialActionObservationReplanner
import com.bytedance.zgx.solin.orchestration.SolinEventBus
import com.bytedance.zgx.solin.orchestration.SystemContextContributor
import com.bytedance.zgx.solin.orchestration.SystemPromptBuilder
import com.bytedance.zgx.solin.orchestration.TelemetryCounter
import com.bytedance.zgx.solin.orchestration.TelemetrySink
import com.bytedance.zgx.solin.orchestration.ToolProgressPublisher
import com.bytedance.zgx.solin.orchestration.attachTo
import com.bytedance.zgx.solin.orchestration.toRemoteVisionScreenContext
import com.bytedance.zgx.solin.plan.PlanToolHandler
import com.bytedance.zgx.solin.plan.PlanToolsModule
import com.bytedance.zgx.solin.plan.SessionPlanStore
import com.bytedance.zgx.solin.presentation.ChatPlacementRuntime
import com.bytedance.zgx.solin.presentation.S3ChatPlacementRuntime
import com.bytedance.zgx.solin.resource.StableResourceSnapshotAggregator
import com.bytedance.zgx.solin.resource.StableResourceState
import com.bytedance.zgx.solin.resource.SystemResourceMonitor
import com.bytedance.zgx.solin.resource.SystemResourceSnapshot
import com.bytedance.zgx.solin.runtime.CodivDecisionModelRouter
import com.bytedance.zgx.solin.runtime.DedicatedLiteRtEngine
import com.bytedance.zgx.solin.runtime.DisabledLiteRtRuntime
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import com.bytedance.zgx.solin.runtime.OkHttpRemoteChatRuntime
import com.bytedance.zgx.solin.runtime.OkHttpRemoteModelConnectivityProbe
import com.bytedance.zgx.solin.runtime.RealLiteRtRuntime
import com.bytedance.zgx.solin.runtime.RemoteConnectivityRefreshCoordinator
import com.bytedance.zgx.solin.runtime.TfliteTextEmbeddingRuntimeFactory
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.AppSearchPlanningMode
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.skill.BuiltInSkillsModule
import com.bytedance.zgx.solin.skill.CompositeSkillRuntime
import com.bytedance.zgx.solin.tool.EvidenceBoundingToolExecutor
import com.bytedance.zgx.solin.tool.OkHttpWebSearchProvider
import com.bytedance.zgx.solin.tool.RoutingToolExecutor
import com.bytedance.zgx.solin.tool.ToolExecutor
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ValidatingToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class SolinAppContainer(
    context: Context,
    private val skipLocalModelRuntime: Boolean = false,
    /**
     * MCP opt-in override for tests/debug launches. `null` (default) follows the persisted Trust
     * Center toggle, which defaults to OFF and requires the MCP disclosure to be accepted.
     */
    private val enableMcp: Boolean? = null
) {
    private val appContext = context.applicationContext
    private val adaptiveInferenceRollout = AdaptiveInferenceRollout.parse(
        BuildConfig.ADAPTIVE_INFERENCE_ROLLOUT_STAGE
    )
    private val stableResourceSnapshotAggregator = StableResourceSnapshotAggregator(
        elapsedRealtimeMillis = SystemClock::elapsedRealtime
    )
    private val systemResourceMonitor = SystemResourceMonitor(appContext)
    private val database = SolinDatabase.get(appContext)
    private val settingsStore = PreferenceSettingsStore(appContext)
    private val secretStore = EncryptedSecretStore(appContext)
    val sessionPlanStore: SessionPlanStore = SessionPlanStore()
    private val mcpSettings: McpCapabilitySettings = SharedPreferencesMcpCapabilitySettings(appContext)
    private val mcpActiveThisSession: Boolean = enableMcp ?: mcpSettings.isEnabled()

    /** Backs the Trust Center cloud/edge router setting (default Off). */
    val decisionModelRoutingController: DecisionModelRoutingController =
        DecisionModelRoutingController(SharedPreferencesDecisionModelRoutingSettings(appContext, secretStore))

    /** Backs the Trust Center MCP toggle (default OFF). */
    val mcpCapabilityController: McpCapabilityController =
        McpCapabilityController(mcpSettings, activeThisSession = mcpActiveThisSession)
    private val moduleRegistry: FrozenSolinModuleRegistry = run {
        val reg = SolinModuleRegistryImpl()
        val mcpClient = if (mcpActiveThisSession) {
            McpClient(
                registry = McpServerRegistry(appContext, settings = mcpSettings),
                settings = mcpSettings,
                // Resolved lazily at call time: the audit repository is created later in init.
                auditSink = object : RemoteSendAuditSink {
                    override fun record(event: RemoteSendAuditEvent) {
                        remoteSendAuditRepository.record(event)
                    }
                }
            )
        } else {
            null
        }
        val mcpModule = mcpClient?.let { McpModule(it) }
        val planToolHandler = PlanToolHandler(sessionPlanStore)
        val modules: List<SolinModule> = buildList {
            add(BuiltInToolsModule())
            add(BuiltInSkillsModule())
            add(PlanToolsModule(planToolHandler))
            if (mcpModule != null) add(mcpModule)
        }
        modules.forEach { it.register(reg) }
        solinD(
            "SolinAppContainer",
            "Loaded ${modules.size} SolinModules: ${modules.map { it.moduleId }}"
        )
        reg.freeze()
    }
    val evidenceBlobStore: EvidenceBlobStore = OnDeviceEvidenceBlobStore(appContext)

    private val modelRepository: ModelRepository
    private val bundledModelInstaller: BundledModelInstaller
    private val sessionRepository: SessionRepository
    private val generationParametersRepository: GenerationParametersRepository
    private val remoteModelRepository: RemoteModelRepository
    private val chatPlacementRuntime: ChatPlacementRuntime
    private val remoteConnectivityRefreshCoordinator: RemoteConnectivityRefreshCoordinator
    private val huggingFaceAuthRepository: HuggingFaceAuthRepository
    private val firstRunSetupRepository: FirstRunSetupRepository
    private val downloadService: ModelDownloadService
    private val actionPlannerFallbackEngine = DedicatedLiteRtEngine(appContext.cacheDir)
    private val localRuntime: LiteRtRuntime
    private val remoteRuntime: OkHttpRemoteChatRuntime
    private val memoryRepository: MemoryRepository
    private val toolAuditRepository: ToolAuditRepository
    private val remoteSendAuditRepository: RemoteSendAuditRepository
    private val scheduledTaskRepository: ScheduledTaskRepository
    private val backgroundTaskScheduler: AndroidBackgroundTaskScheduler
    private val reminderNotificationHelper: ReminderNotificationHelper
    val currentScreenshotOcrProvider: CurrentScreenshotOcrProvider
    val rawScreenshotProvider: RawScreenshotProvider

    /**
     * The module-aware tool registry — the only registry that knows about tools contributed by
     * [SolinModuleRegistry] providers, not just the built-in specs.
     *
     * Published (rather than staying a local in [init]) because the runtime permission, special
     * access, and media-projection consent gates in AgentRuntimePermissionPolicy must be asked
     * about module tools too. A registry built from built-in specs alone answers "no permission
     * required, no consent required" for any tool it does not know, which makes those gates
     * silently fail open. Call sites therefore have to be able to hand the real registry in.
     */
    val toolRegistry: ToolRegistry
    private val actionPlanningRuntime: HybridActionPlanningRuntime
    private val observationActionPlanningRuntime: HybridActionPlanningRuntime
    private val actionExecutor: ToolExecutor
    private val assistantOrchestrator: AssistantOrchestrator
    private val eventBus: SolinEventBus
    private val telemetrySink: TelemetrySink
    private val systemContextContributors: List<SystemContextContributor>
    private val systemPromptBuilder: SystemPromptBuilder
    private val toolProgressPublisher: ToolProgressPublisher
    private val containerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        com.bytedance.zgx.solin.device.background.BackgroundPhoneSession.initialize(appContext)
        evidenceBlobStore.gc()
        LegacyPrefsMigrator(appContext, database, settingsStore, secretStore).migrateIfNeeded()

        modelRepository = ModelRepository(
            appContext = appContext,
            modelDao = database.modelDao(),
            downloadRecordDao = database.downloadRecordDao(),
            settingsStore = settingsStore
        )
        bundledModelInstaller = AssetBundledModelInstaller(appContext, modelRepository)
        sessionRepository = SessionRepository(database.sessionDao(), settingsStore)
        generationParametersRepository = GenerationParametersRepository(settingsStore)
        remoteModelRepository = RemoteModelRepository(settingsStore, secretStore, appContext)
        val placementStore = RoomRunPlacementBindingStore(
            dao = database.runPlacementBindingDao(),
            currentRecoveryContext = {
                RunPlacementRecoveryContext(
                    bootCount = currentBootCount(),
                    elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                    remoteProfileRevision = remoteModelRepository.loadConfig().profileRevision
                )
            }
        )
        chatPlacementRuntime = S3ChatPlacementRuntime(
            bindingStore = placementStore,
            dispatcher = ModelRuntimeDispatcher(placementStore)
        )
        remoteConnectivityRefreshCoordinator = RemoteConnectivityRefreshCoordinator(
            remoteModelStore = remoteModelRepository,
            probe = OkHttpRemoteModelConnectivityProbe(),
            scope = containerScope
        )
        huggingFaceAuthRepository = HuggingFaceAuthRepository(secretStore)
        firstRunSetupRepository = FirstRunSetupRepository(settingsStore)
        downloadService = ModelDownloadService(
            appContext,
            huggingFaceAuthRepository::authorizationHeader
        )
        localRuntime = if (skipLocalModelRuntime) {
            DisabledLiteRtRuntime
        } else {
            RealLiteRtRuntime.configureNativeLogging()
            RealLiteRtRuntime(
                cacheDir = appContext.cacheDir,
                contextCompactor = DefaultContextCompactor(),
                fallbackEngine = actionPlannerFallbackEngine
            )
        }
        remoteRuntime = OkHttpRemoteChatRuntime()
        val roomMemoryRecordStore = RoomMemoryRecordStore(database.memoryRecordDao())
        val roomMemoryEmbeddingStore = RoomMemoryEmbeddingStore(database.memoryEmbeddingDao())
        val memoryDeletionEventStore = RoomMemoryDeletionEventStore(
            dao = database.memoryDeletionEventDao(),
            transactionDao = database.memoryDeletionTransactionDao()
        )
        LegacyMemoryStorageRetirement(
            context = appContext,
            records = roomMemoryRecordStore,
            embeddings = roomMemoryEmbeddingStore,
            deletionEvents = memoryDeletionEventStore
        ).retireAfterSecureImport()
        memoryRepository = MemoryRepository(
            semanticRuntimeFactory = { modelPath ->
                TfliteTextEmbeddingRuntimeFactory.create(appContext, modelPath)
            },
            recordStore = roomMemoryRecordStore,
            embeddingStore = roomMemoryEmbeddingStore,
            deletionEventStore = memoryDeletionEventStore
        )
        toolAuditRepository = ToolAuditRepository(database.toolAuditDao())
        remoteSendAuditRepository = RemoteSendAuditRepository(database.remoteSendAuditDao())
        scheduledTaskRepository = ScheduledTaskRepository(database.scheduledTaskDao())
        backgroundTaskScheduler = AndroidBackgroundTaskScheduler(appContext, scheduledTaskRepository)
        reminderNotificationHelper = ReminderNotificationHelper(appContext)
        currentScreenshotOcrProvider = AndroidCurrentScreenshotOcrProvider(appContext)
        // Remote-vision GUI automation captures the screen via the already-connected accessibility
        // service (AccessibilityService.takeScreenshot) — NO MediaProjection dialog and NO foreground
        // service. Egress is governed by the in-app opt-in + first-confirm gate + per-send audit.
        // The MediaProjection-based OCR provider above stays the LocalOnly OCR tool's capture path.
        rawScreenshotProvider = SolinAccessibilityService::performTakeScreenshotRaw
        // Seams: event bus, telemetry, and system-context contributors.
        eventBus = DefaultSolinEventBus()
        telemetrySink = InMemoryTelemetrySink()
        systemContextContributors = listOf(
            AvailableSkillsContributor(
                manifestsProvider = { moduleRegistry.skillSources.flatMap { source -> source.manifests() } }
            )
        )
        systemPromptBuilder = SystemPromptBuilder(
            contributors = systemContextContributors,
            includeDeviceControlSurvivalRules = true
        )
        toolProgressPublisher = DefaultToolProgressPublisher(eventBus = eventBus)
        telemetrySink.attachTo(eventBus, containerScope)
        toolRegistry = ToolRegistry(moduleRegistry.toolProviders)
        actionPlanningRuntime = HybridActionPlanningRuntime(
            fallbackEngine = actionPlannerFallbackEngine,
            toolRegistry = toolRegistry,
            sharedEngine = localRuntime
        )
        val observationActionToolRegistry = ToolRegistry.fromSupportedActions(
            MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES +
                com.bytedance.zgx.solin.orchestration.MODEL_OBSERVATION_REPLAN_COMPOSITE_TOOL_NAMES
        )
        observationActionPlanningRuntime = HybridActionPlanningRuntime(
            fallbackEngine = actionPlannerFallbackEngine,
            toolRegistry = observationActionToolRegistry,
            sharedEngine = localRuntime
        )
        actionExecutor = ValidatingToolExecutor(
            delegate = EvidenceBoundingToolExecutor(
                delegate = RoutingToolExecutor(
                    calendarAvailabilityProvider = AndroidCalendarAvailabilityProvider(appContext),
                    foregroundAppProvider = AndroidForegroundAppProvider(appContext),
                    contactSummaryProvider = AndroidContactSummaryProvider(appContext),
                    notificationSummaryProvider = AndroidNotificationSummaryProvider(appContext),
                    recentFileProvider = AndroidRecentFileProvider(appContext),
                    webSearchProvider = OkHttpWebSearchProvider(auditSink = remoteSendAuditRepository),
                    delegate = ActionExecutor(
                        context = appContext,
                        backgroundTaskScheduler = backgroundTaskScheduler,
                        canPostReminderNotifications = reminderNotificationHelper::canPostNotifications,
                        toolRegistry = toolRegistry
                    ),
                    backgroundTaskScheduler = backgroundTaskScheduler,
                    recentImageTextProvider = AndroidRecentImageTextProvider(appContext),
                    currentScreenTextProvider = AndroidCurrentScreenTextProvider(),
                    currentScreenshotOcrProvider = currentScreenshotOcrProvider,
                    currentScreenControlProvider = AndroidCurrentScreenControlProvider(),
                    toolRegistry = toolRegistry,
                    toolHandlers = moduleRegistry.toolHandlers
                ),
                evidenceBlobStore = evidenceBlobStore
            ),
            registry = toolRegistry,
            progressPublisher = toolProgressPublisher
        )
        assistantOrchestrator = AssistantOrchestrator(
            memoryIndex = memoryRepository,
            actionPlanningRuntime = actionPlanningRuntime,
            toolRegistry = toolRegistry,
            toolAuditSink = toolAuditRepository,
            traceStore = RoomAgentTraceStore(
                traceDao = database.agentTraceDao(),
                // Bridge the degradation latch to real telemetry: while step history is unreadable
                // the step-derived budgets fail closed, so runs end early for a storage reason that
                // is otherwise invisible. Only transitions are reported, not every failed read.
                onStepHistoryDegradedChanged = { runId, degraded ->
                    // Both directions, not just the onset: the store already fires this only on a
                    // real transition, so counting recoveries too is what lets a 1ms blip be told
                    // apart from a sustained outage. Counting only the onset would leave every
                    // degradation looking permanent.
                    runCatching {
                        telemetrySink.record(
                            MetricSample.CounterInc(
                                name = if (degraded) {
                                    TelemetryCounter.StepHistoryDegraded
                                } else {
                                    TelemetryCounter.StepHistoryRecovered
                                },
                                runId = runId
                            )
                        )
                    }
                }
            ),
            skillRuntime = CompositeSkillRuntime(
                sources = moduleRegistry.skillSources,
                planners = listOf(
                    BuiltInSkillRuntime(
                        appSearchPlanningModeProvider = {
                            if (modelRepository.verifiedObservationActionModelPath() != null) {
                                AppSearchPlanningMode.ModelDrivenBootstrap
                            } else {
                                AppSearchPlanningMode.StaticSkill
                            }
                        }
                    )
                )
            ),
            observationReplanner = com.bytedance.zgx.solin.orchestration.VerifiedPhoneRouteReplanner(
                CompositeAgentObservationReplanner(
                    DeadLoopDetectionReplanner(),
                    // Remote-vision GUI driving: when the user opted in and the remote model supports
                    // vision, kimi sees screenshots and decides taps. Outranks the local action model
                    // (below) when active; returns null when the gate is off so the local model still
                    // runs in Local/Auto mode. executeDecisions=true: a parsed tap becomes a real local
                    // ui_tap that still passes every device-control preflight and confirmation gate.
                    RemoteVisionObservationReplanner(
                        decider = AndroidRemoteVisionDecider(
                            rawScreenshotProvider = rawScreenshotProvider,
                            remoteRuntime = remoteRuntime,
                            configProvider = { remoteModelRepository.loadConfig() },
                            inferenceModeProvider = { remoteModelRepository.loadMode() },
                            generationParametersProvider = { generationParametersRepository.load() },
                            auditSink = remoteSendAuditRepository,
                            screenContextProvider = {
                                (AndroidCurrentScreenControlProvider().observeCurrentScreen() as? ScreenStateReadResult.Available)
                                    ?.snapshot
                                    ?.toRemoteVisionScreenContext()
                            }
                        ),
                        gateProvider = {
                            remoteModelRepository.loadMode() == InferenceMode.Remote &&
                                firstRunSetupRepository.remoteGuiAutomationEnabled() &&
                                remoteModelRepository.loadConfig().supportsVisionInput
                        },
                        executeDecisions = true,
                        // Counts every step, including typed and submit steps that make no remote call.
                        maxReplans = 12
                    ),
                    ModelObservationReplanner(
                        actionPlanningRuntime = observationActionPlanningRuntime,
                        actionModelPathProvider = modelRepository::verifiedObservationActionModelPath,
                        toolRegistry = observationActionToolRegistry,
                        maxModelReplans = 5
                    ),
                    SequentialActionObservationReplanner(
                        actionPlanningRuntime = actionPlanningRuntime,
                        toolRegistry = toolRegistry
                    )
                )
            ),
            deviceControlSessionFinisher = {
                DeviceControlSessionService.stop(appContext)
            },
            eventBus = eventBus,
            telemetrySink = telemetrySink,
            systemContextContributors = systemContextContributors,
            systemPromptBuilder = systemPromptBuilder,
            evidenceBlobStore = evidenceBlobStore,
            sessionPlanStore = sessionPlanStore,
            contextCompactor = DefaultContextCompactor()
        )
    }

    fun viewModelFactory(skipStartupModelRuntimeWork: Boolean = false): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            SolinViewModel(
                modelRepository = modelRepository,
                bundledModelInstaller = bundledModelInstaller,
                sessionRepository = sessionRepository,
                generationParametersRepository = generationParametersRepository,
                remoteModelRepository = remoteModelRepository,
                adaptiveInferenceRollout = adaptiveInferenceRollout,
                decisionModelPlacementPolicy = DecisionModelPlacementPolicy(
                    routing = decisionModelRoutingController::effectiveMode,
                    router = CodivDecisionModelRouter(
                        credential = decisionModelRoutingController::apiKey,
                        auditSink = remoteSendAuditRepository
                    ),
                    mayLeaveDevice = { text -> !SafetyPolicy().containsSensitivePersonalOrSecretContent(text) },
                    onSample = decisionModelRoutingController::record
                ),
                injectedRemoteConnectivityRefreshCoordinator = remoteConnectivityRefreshCoordinator,
                huggingFaceAuthStore = huggingFaceAuthRepository,
                firstRunSetupRepository = firstRunSetupRepository,
                downloadService = downloadService,
                runtime = localRuntime,
                remoteRuntime = remoteRuntime,
                memoryRepository = memoryRepository,
                longTermMemoryControls = memoryRepository,
                backgroundTaskScheduler = backgroundTaskScheduler,
                toolAuditLog = toolAuditRepository,
                remoteSendAuditSink = remoteSendAuditRepository,
                remoteSendAuditLog = remoteSendAuditRepository,
                remoteSendPendingStore = settingsStore,
                actionExecutor = actionExecutor,
                assistantOrchestrator = assistantOrchestrator,
                chatPlacementRuntime = chatPlacementRuntime,
                stableResourceStateProvider = ::currentStableResourceState,
                bootCountProvider = ::currentBootCount,
                elapsedRealtimeMillis = SystemClock::elapsedRealtime,
                isArm64DeviceProvider = {
                    Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }
                },
                skipStartupModelRuntimeWork = skipStartupModelRuntimeWork,
                deferPersistenceInitialization = true,
                // The module-aware registry, not the built-in default: the safety authorizer inside
                // ToolExecutionController rejects any tool its registry does not know, which would
                // make every module-contributed tool unexecutable.
                toolRegistry = toolRegistry
            )
        }
    }

    internal fun sampleSystemResources(): SystemResourceSnapshot? = systemResourceMonitor.sample()?.also { stableResourceSnapshotAggregator.record(it) }

    internal fun currentStableResourceState(): StableResourceState = stableResourceSnapshotAggregator.current()

    private fun currentBootCount(): Long = Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()

    fun close() {
        containerScope.cancel()
        remoteRuntime.stop()
        localRuntime.close()
        assistantOrchestrator.close()
        observationActionPlanningRuntime.close()
    }
}
