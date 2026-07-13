package com.bytedance.zgx.solin

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bytedance.zgx.solin.action.ActionExecutor
import com.bytedance.zgx.solin.action.HybridActionPlanningRuntime
import com.bytedance.zgx.solin.audit.RemoteSendAuditEvent
import com.bytedance.zgx.solin.audit.RemoteSendAuditRepository
import com.bytedance.zgx.solin.audit.RemoteSendDecision
import com.bytedance.zgx.solin.audit.ToolAuditEvent
import com.bytedance.zgx.solin.audit.ToolAuditEventType
import com.bytedance.zgx.solin.audit.ToolAuditLog
import com.bytedance.zgx.solin.audit.ToolAuditRepository
import com.bytedance.zgx.solin.safety.SafetyCategory
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolPermission
import com.bytedance.zgx.solin.tool.ToolStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.bytedance.zgx.solin.background.AndroidBackgroundTaskScheduler
import com.bytedance.zgx.solin.background.ReminderNotificationHelper
import com.bytedance.zgx.solin.background.ScheduledTaskRepository
import com.bytedance.zgx.solin.data.AssetBundledModelInstaller
import com.bytedance.zgx.solin.data.BundledModelInstaller
import com.bytedance.zgx.solin.data.EncryptedSecretStore
import com.bytedance.zgx.solin.data.FirstRunSetupRepository
import com.bytedance.zgx.solin.data.GenerationParametersRepository
import com.bytedance.zgx.solin.data.HuggingFaceAuthRepository
import com.bytedance.zgx.solin.data.LegacyPrefsMigrator
import com.bytedance.zgx.solin.data.ModelRepository
import com.bytedance.zgx.solin.data.SolinDatabase
import com.bytedance.zgx.solin.data.PreferenceSettingsStore
import com.bytedance.zgx.solin.data.RemoteModelRepository
import com.bytedance.zgx.solin.data.RemoteSendPendingStore
import com.bytedance.zgx.solin.data.SessionRepository
import com.bytedance.zgx.solin.device.AndroidCalendarAvailabilityProvider
import com.bytedance.zgx.solin.device.AndroidContactSummaryProvider
import com.bytedance.zgx.solin.device.AndroidCurrentScreenControlProvider
import com.bytedance.zgx.solin.device.AndroidCurrentScreenTextProvider
import com.bytedance.zgx.solin.device.AndroidForegroundAppProvider
import com.bytedance.zgx.solin.device.AndroidNotificationSummaryProvider
import com.bytedance.zgx.solin.device.AndroidRecentFileProvider
import com.bytedance.zgx.solin.device.AndroidRecentImageTextProvider
import com.bytedance.zgx.solin.device.DeviceControlSessionService
import com.bytedance.zgx.solin.download.ModelDownloadService
import com.bytedance.zgx.solin.evidence.EvidenceBlobStore
import com.bytedance.zgx.solin.evidence.NoOpEvidenceBlobStore
import com.bytedance.zgx.solin.evidence.OnDeviceEvidenceBlobStore
import com.bytedance.zgx.solin.memory.LongTermMemoryControls
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.memory.RoomMemoryDeletionEventStore
import com.bytedance.zgx.solin.memory.RoomMemoryEmbeddingStore
import com.bytedance.zgx.solin.memory.RoomMemoryRecordStore
import com.bytedance.zgx.solin.memory.LegacyMemoryStorageRetirement
import com.bytedance.zgx.solin.multimodal.AndroidCurrentScreenshotOcrProvider
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrProvider
import com.bytedance.zgx.solin.orchestration.AgentHooks
import com.bytedance.zgx.solin.orchestration.AssistantOrchestrator
import com.bytedance.zgx.solin.orchestration.CompositeAgentObservationReplanner
import com.bytedance.zgx.solin.orchestration.DefaultContextCompactor
import com.bytedance.zgx.solin.orchestration.DefaultSolinEventBus
import com.bytedance.zgx.solin.orchestration.DefaultToolProgressPublisher
import com.bytedance.zgx.solin.orchestration.InMemoryTelemetrySink
import com.bytedance.zgx.solin.orchestration.MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES
import com.bytedance.zgx.solin.orchestration.ModelObservationReplanner
import com.bytedance.zgx.solin.orchestration.NoOpAgentHooks
import com.bytedance.zgx.solin.orchestration.RoomAgentTraceStore
import com.bytedance.zgx.solin.orchestration.SequentialActionObservationReplanner
import com.bytedance.zgx.solin.orchestration.SolinEvent
import com.bytedance.zgx.solin.orchestration.SolinEventBus
import com.bytedance.zgx.solin.orchestration.ToolProgressPublisher
import com.bytedance.zgx.solin.orchestration.SystemContextContributor
import com.bytedance.zgx.solin.orchestration.SystemPromptBuilder
import com.bytedance.zgx.solin.orchestration.TelemetrySink
import com.bytedance.zgx.solin.orchestration.attachTo
import com.bytedance.zgx.solin.runtime.OkHttpRemoteChatRuntime
import com.bytedance.zgx.solin.runtime.DisabledLiteRtRuntime
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import com.bytedance.zgx.solin.runtime.RealLiteRtRuntime
import com.bytedance.zgx.solin.runtime.TfliteTextEmbeddingRuntimeFactory
import com.bytedance.zgx.solin.mcp.McpClient
import com.bytedance.zgx.solin.mcp.McpModule
import com.bytedance.zgx.solin.mcp.McpServerRegistry
import com.bytedance.zgx.solin.skill.AppSearchPlanningMode
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.skill.BuiltInSkillsModule
import com.bytedance.zgx.solin.module.SolinModule
import com.bytedance.zgx.solin.module.SolinModuleRegistryImpl
import com.bytedance.zgx.solin.tool.ValidatingToolExecutor
import com.bytedance.zgx.solin.tool.RoutingToolExecutor
import com.bytedance.zgx.solin.tool.BuiltInToolsModule
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolExecutor
import com.bytedance.zgx.solin.tool.OkHttpWebSearchProvider
import com.bytedance.zgx.solin.plan.PlanToolHandler
import com.bytedance.zgx.solin.plan.PlanToolsModule
import com.bytedance.zgx.solin.plan.SessionPlanStore
import org.json.JSONObject

class SolinAppContainer(
    context: Context,
    private val skipLocalModelRuntime: Boolean = false,
    private val enableMcp: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val database = SolinDatabase.get(appContext)
    private val settingsStore = PreferenceSettingsStore(appContext)
    private val secretStore = EncryptedSecretStore(appContext)
    val sessionPlanStore: SessionPlanStore = SessionPlanStore()
    private val moduleRegistry: SolinModuleRegistryImpl = run {
        val reg = SolinModuleRegistryImpl()
        val mcpClient = if (enableMcp) McpClient(McpServerRegistry(appContext)) else null
        val mcpModule = mcpClient?.let { McpModule(it) }
        val planToolHandler = PlanToolHandler(sessionPlanStore)
        val modules: List<SolinModule> = buildList {
            add(BuiltInToolsModule())
            add(BuiltInSkillsModule())
            add(PlanToolsModule(planToolHandler))
            if (mcpModule != null) add(mcpModule)
        }
        modules.forEach { it.register(reg) }
        android.util.Log.d(
            "SolinAppContainer",
            "Loaded ${modules.size} SolinModules: ${modules.map { it.moduleId }}",
        )
        reg
    }
    val evidenceBlobStore: EvidenceBlobStore = OnDeviceEvidenceBlobStore(appContext)

    private val modelRepository: ModelRepository
    private val bundledModelInstaller: BundledModelInstaller
    private val sessionRepository: SessionRepository
    private val generationParametersRepository: GenerationParametersRepository
    private val remoteModelRepository: RemoteModelRepository
    private val huggingFaceAuthRepository: HuggingFaceAuthRepository
    private val firstRunSetupRepository: FirstRunSetupRepository
    private val downloadService: ModelDownloadService
    private val localRuntime: LiteRtRuntime
    private val remoteRuntime: OkHttpRemoteChatRuntime
    private val memoryRepository: MemoryRepository
    private val toolAuditRepository: ToolAuditRepository
    private val remoteSendAuditRepository: RemoteSendAuditRepository
    private val scheduledTaskRepository: ScheduledTaskRepository
    private val backgroundTaskScheduler: AndroidBackgroundTaskScheduler
    private val reminderNotificationHelper: ReminderNotificationHelper
    val currentScreenshotOcrProvider: CurrentScreenshotOcrProvider
    private val actionPlanningRuntime: HybridActionPlanningRuntime
    private val observationActionPlanningRuntime: HybridActionPlanningRuntime
    private val actionExecutor: ToolExecutor
    private val assistantOrchestrator: AssistantOrchestrator
    private val eventBus: SolinEventBus
    private val telemetrySink: TelemetrySink
    private val agentHooks: AgentHooks
    private val systemContextContributors: List<SystemContextContributor>
    private val systemPromptBuilder: SystemPromptBuilder
    private val toolProgressPublisher: ToolProgressPublisher
    private val containerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        evidenceBlobStore.gc()
        LegacyPrefsMigrator(appContext, database, settingsStore, secretStore).migrateIfNeeded()

        modelRepository = ModelRepository(
            appContext = appContext,
            modelDao = database.modelDao(),
            downloadRecordDao = database.downloadRecordDao(),
            settingsStore = settingsStore,
        )
        bundledModelInstaller = AssetBundledModelInstaller(appContext, modelRepository)
        sessionRepository = SessionRepository(database.sessionDao(), settingsStore)
        generationParametersRepository = GenerationParametersRepository(settingsStore)
        remoteModelRepository = RemoteModelRepository(settingsStore, secretStore, appContext)
        huggingFaceAuthRepository = HuggingFaceAuthRepository(secretStore)
        firstRunSetupRepository = FirstRunSetupRepository(settingsStore)
        downloadService = ModelDownloadService(
            appContext,
            huggingFaceAuthRepository::authorizationHeader,
        )
        localRuntime = if (skipLocalModelRuntime) {
            DisabledLiteRtRuntime
        } else {
            RealLiteRtRuntime.configureNativeLogging()
            RealLiteRtRuntime(
                cacheDir = appContext.cacheDir,
                contextCompactor = DefaultContextCompactor(),
            )
        }
        remoteRuntime = OkHttpRemoteChatRuntime()
        val roomMemoryRecordStore = RoomMemoryRecordStore(database.memoryRecordDao())
        val roomMemoryEmbeddingStore = RoomMemoryEmbeddingStore(database.memoryEmbeddingDao())
        val memoryDeletionEventStore = RoomMemoryDeletionEventStore(
            dao = database.memoryDeletionEventDao(),
            transactionDao = database.memoryDeletionTransactionDao(),
        )
        LegacyMemoryStorageRetirement(
            context = appContext,
            records = roomMemoryRecordStore,
            embeddings = roomMemoryEmbeddingStore,
            deletionEvents = memoryDeletionEventStore,
        ).retireAfterSecureImport()
        memoryRepository = MemoryRepository(
            semanticRuntimeFactory = { modelPath ->
                TfliteTextEmbeddingRuntimeFactory.create(appContext, modelPath)
            },
            recordStore = roomMemoryRecordStore,
            embeddingStore = roomMemoryEmbeddingStore,
            deletionEventStore = memoryDeletionEventStore,
        )
        toolAuditRepository = ToolAuditRepository(database.toolAuditDao())
        remoteSendAuditRepository = RemoteSendAuditRepository(database.remoteSendAuditDao())
        scheduledTaskRepository = ScheduledTaskRepository(database.scheduledTaskDao())
        backgroundTaskScheduler = AndroidBackgroundTaskScheduler(appContext, scheduledTaskRepository)
        reminderNotificationHelper = ReminderNotificationHelper(appContext)
        currentScreenshotOcrProvider = AndroidCurrentScreenshotOcrProvider(appContext)
        // Seams: event bus, telemetry, hooks, and system-context contributors.
        eventBus = DefaultSolinEventBus()
        telemetrySink = InMemoryTelemetrySink()
        agentHooks = NoOpAgentHooks
        systemContextContributors = emptyList()
        systemPromptBuilder = SystemPromptBuilder(
            contributors = systemContextContributors,
            includeDeviceControlSurvivalRules = true,
        )
        toolProgressPublisher = DefaultToolProgressPublisher(eventBus = eventBus)
        telemetrySink.attachTo(eventBus, containerScope)
        containerScope.launch {
            eventBus.subscribe(SolinEvent.Audit.RemoteSendAudited::class).collect { event ->
                event.toRemoteSendAuditEvent()?.let(remoteSendAuditRepository::record)
            }
        }
        val toolRegistry = ToolRegistry(moduleRegistry.toolProviders)
        actionPlanningRuntime = HybridActionPlanningRuntime(
            cacheDir = appContext.cacheDir,
            toolRegistry = toolRegistry,
        )
        val observationActionToolRegistry = ToolRegistry.fromSupportedActions(
            MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES,
        )
        observationActionPlanningRuntime = HybridActionPlanningRuntime(
            cacheDir = appContext.cacheDir,
            toolRegistry = observationActionToolRegistry,
        )
        actionExecutor = ValidatingToolExecutor(
            delegate = RoutingToolExecutor(
                calendarAvailabilityProvider = AndroidCalendarAvailabilityProvider(appContext),
                foregroundAppProvider = AndroidForegroundAppProvider(appContext),
                contactSummaryProvider = AndroidContactSummaryProvider(appContext),
                notificationSummaryProvider = AndroidNotificationSummaryProvider(appContext),
                recentFileProvider = AndroidRecentFileProvider(appContext),
                webSearchProvider = OkHttpWebSearchProvider(),
                delegate = ActionExecutor(
                    context = appContext,
                    backgroundTaskScheduler = backgroundTaskScheduler,
                    canPostReminderNotifications = reminderNotificationHelper::canPostNotifications,
                    toolRegistry = toolRegistry,
                ),
                backgroundTaskScheduler = backgroundTaskScheduler,
                recentImageTextProvider = AndroidRecentImageTextProvider(appContext),
                currentScreenTextProvider = AndroidCurrentScreenTextProvider(),
                currentScreenshotOcrProvider = currentScreenshotOcrProvider,
                currentScreenControlProvider = AndroidCurrentScreenControlProvider(),
                toolRegistry = toolRegistry,
                toolHandlers = moduleRegistry.toolHandlers,
                evidenceBlobStore = evidenceBlobStore,
            ),
            registry = toolRegistry,
            progressPublisher = toolProgressPublisher,
        )
        assistantOrchestrator = AssistantOrchestrator(
            memoryIndex = memoryRepository,
            actionPlanningRuntime = actionPlanningRuntime,
            toolRegistry = toolRegistry,
            toolAuditSink = toolAuditRepository,
            traceStore = RoomAgentTraceStore(database.agentTraceDao()),
            skillRuntime = BuiltInSkillRuntime(
                appSearchPlanningModeProvider = {
                    if (modelRepository.verifiedObservationActionModelPath() != null) {
                        AppSearchPlanningMode.ModelDrivenBootstrap
                    } else {
                        AppSearchPlanningMode.StaticSkill
                    }
                },
            ),
            observationReplanner = CompositeAgentObservationReplanner(
                ModelObservationReplanner(
                    actionPlanningRuntime = observationActionPlanningRuntime,
                    actionModelPathProvider = modelRepository::verifiedObservationActionModelPath,
                    toolRegistry = observationActionToolRegistry,
                    maxModelReplans = 5,
                ),
                SequentialActionObservationReplanner(
                    actionPlanningRuntime = actionPlanningRuntime,
                    toolRegistry = toolRegistry,
                ),
            ),
            deviceControlSessionFinisher = {
                DeviceControlSessionService.stop(appContext)
            },
            eventBus = eventBus,
            hooks = agentHooks,
            telemetrySink = telemetrySink,
            systemContextContributors = systemContextContributors,
            systemPromptBuilder = systemPromptBuilder,
            evidenceBlobStore = evidenceBlobStore,
            sessionPlanStore = sessionPlanStore,
            contextCompactor = DefaultContextCompactor(),
        )
    }

    fun viewModelFactory(skipStartupModelRuntimeWork: Boolean = false): ViewModelProvider.Factory =
        SolinViewModelFactory(
            modelRepository = modelRepository,
            bundledModelInstaller = bundledModelInstaller,
            sessionRepository = sessionRepository,
            generationParametersRepository = generationParametersRepository,
            remoteModelRepository = remoteModelRepository,
            huggingFaceAuthRepository = huggingFaceAuthRepository,
            firstRunSetupRepository = firstRunSetupRepository,
            downloadService = downloadService,
            runtime = localRuntime,
            remoteRuntime = remoteRuntime,
            memoryRepository = memoryRepository,
            longTermMemoryControls = memoryRepository,
            backgroundTaskScheduler = backgroundTaskScheduler,
            toolAuditLog = toolAuditRepository,
            remoteSendAuditRepository = remoteSendAuditRepository,
            remoteSendPendingStore = settingsStore,
            actionExecutor = actionExecutor,
            assistantOrchestrator = assistantOrchestrator,
            isArm64DeviceProvider = {
                Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }
            },
            skipStartupModelRuntimeWork = skipStartupModelRuntimeWork,
        )

    fun close() {
        containerScope.cancel()
        remoteRuntime.stop()
        localRuntime.close()
        assistantOrchestrator.close()
        observationActionPlanningRuntime.close()
    }

    private fun SolinEvent.Audit.ToolAudited.toToolAuditEvent(): ToolAuditEvent =
        ToolAuditEvent(
            id = eventId,
            runId = runId,
            requestId = requestId,
            toolName = toolName,
            skillId = skillId,
            eventType = runCatching { ToolAuditEventType.valueOf(eventType) }
                .getOrDefault(ToolAuditEventType.ToolObserved),
            status = status?.let { name ->
                runCatching { ToolStatus.valueOf(name) }.getOrNull()
            },
            riskLevel = riskLevel?.let { name ->
                runCatching { RiskLevel.valueOf(name) }.getOrNull()
            },
            permissions = permissionsCsv
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { name ->
                    runCatching { ToolPermission.valueOf(name) }.getOrNull()
                }
                .toSet(),
            summary = summary,
            createdAtMillis = occurredAtMillis,
        )

    private fun SolinEvent.Audit.RemoteSendAudited.toRemoteSendAuditEvent(): RemoteSendAuditEvent? {
        val resolvedDecision = runCatching { RemoteSendDecision.valueOf(decision) }
            .getOrElse { return null }
        val categories = sensitiveCategoriesCsv
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { name ->
                runCatching { SafetyCategory.valueOf(name) }.getOrNull()
            }
        return RemoteSendAuditEvent(
            id = eventId,
            decision = resolvedDecision,
            modelName = modelName,
            sensitiveCategories = categories,
            imageCount = imageCount,
            remoteHistoryCount = remoteHistoryCount,
            summary = summary,
            createdAtMillis = occurredAtMillis,
        )
    }
}

private class SolinViewModelFactory(
    private val modelRepository: ModelRepository,
    private val bundledModelInstaller: BundledModelInstaller,
    private val sessionRepository: SessionRepository,
    private val generationParametersRepository: GenerationParametersRepository,
    private val remoteModelRepository: RemoteModelRepository,
    private val huggingFaceAuthRepository: HuggingFaceAuthRepository,
    private val firstRunSetupRepository: FirstRunSetupRepository,
    private val downloadService: ModelDownloadService,
    private val runtime: LiteRtRuntime,
    private val remoteRuntime: OkHttpRemoteChatRuntime,
    private val memoryRepository: MemoryRepository,
    private val longTermMemoryControls: LongTermMemoryControls,
    private val backgroundTaskScheduler: AndroidBackgroundTaskScheduler,
    private val toolAuditLog: ToolAuditLog,
    private val remoteSendAuditRepository: RemoteSendAuditRepository,
    private val remoteSendPendingStore: RemoteSendPendingStore,
    private val actionExecutor: ToolExecutor,
    private val assistantOrchestrator: AssistantOrchestrator,
    private val isArm64DeviceProvider: () -> Boolean,
    private val skipStartupModelRuntimeWork: Boolean,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SolinViewModel::class.java)) {
            "Unknown ViewModel class ${modelClass.name}"
        }
        return SolinViewModel(
            modelRepository = modelRepository,
            bundledModelInstaller = bundledModelInstaller,
            sessionRepository = sessionRepository,
            generationParametersRepository = generationParametersRepository,
            remoteModelRepository = remoteModelRepository,
            huggingFaceAuthStore = huggingFaceAuthRepository,
            firstRunSetupRepository = firstRunSetupRepository,
            downloadService = downloadService,
            runtime = runtime,
            remoteRuntime = remoteRuntime,
            memoryRepository = memoryRepository,
            longTermMemoryControls = longTermMemoryControls,
            backgroundTaskScheduler = backgroundTaskScheduler,
            toolAuditLog = toolAuditLog,
            remoteSendAuditSink = remoteSendAuditRepository,
            remoteSendAuditLog = remoteSendAuditRepository,
            remoteSendPendingStore = remoteSendPendingStore,
            actionExecutor = actionExecutor,
            assistantOrchestrator = assistantOrchestrator,
            isArm64DeviceProvider = isArm64DeviceProvider,
            skipStartupModelRuntimeWork = skipStartupModelRuntimeWork,
            deferPersistenceInitialization = true,
        ) as T
    }
}
