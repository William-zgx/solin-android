package com.bytedance.zgx.pocketmind

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bytedance.zgx.pocketmind.action.ActionExecutor
import com.bytedance.zgx.pocketmind.action.HybridActionPlanningRuntime
import com.bytedance.zgx.pocketmind.audit.RemoteSendAuditRepository
import com.bytedance.zgx.pocketmind.audit.ToolAuditLog
import com.bytedance.zgx.pocketmind.audit.ToolAuditRepository
import com.bytedance.zgx.pocketmind.background.AndroidBackgroundTaskScheduler
import com.bytedance.zgx.pocketmind.background.ReminderNotificationHelper
import com.bytedance.zgx.pocketmind.background.ScheduledTaskRepository
import com.bytedance.zgx.pocketmind.data.AssetBundledModelInstaller
import com.bytedance.zgx.pocketmind.data.BundledModelInstaller
import com.bytedance.zgx.pocketmind.data.EncryptedSecretStore
import com.bytedance.zgx.pocketmind.data.FirstRunSetupRepository
import com.bytedance.zgx.pocketmind.data.GenerationParametersRepository
import com.bytedance.zgx.pocketmind.data.HuggingFaceAuthRepository
import com.bytedance.zgx.pocketmind.data.LegacyPrefsMigrator
import com.bytedance.zgx.pocketmind.data.LocalStorageMigrationStateDao
import com.bytedance.zgx.pocketmind.data.LocalStorageMigrationStateEntity
import com.bytedance.zgx.pocketmind.data.ModelRepository
import com.bytedance.zgx.pocketmind.data.PocketMindDatabase
import com.bytedance.zgx.pocketmind.data.PreferenceSettingsStore
import com.bytedance.zgx.pocketmind.data.RemoteModelRepository
import com.bytedance.zgx.pocketmind.data.RemoteSendPendingStore
import com.bytedance.zgx.pocketmind.data.SessionRepository
import com.bytedance.zgx.pocketmind.device.AndroidCalendarAvailabilityProvider
import com.bytedance.zgx.pocketmind.device.AndroidContactSummaryProvider
import com.bytedance.zgx.pocketmind.device.AndroidCurrentScreenControlProvider
import com.bytedance.zgx.pocketmind.device.AndroidCurrentScreenTextProvider
import com.bytedance.zgx.pocketmind.device.AndroidForegroundAppProvider
import com.bytedance.zgx.pocketmind.device.AndroidNotificationSummaryProvider
import com.bytedance.zgx.pocketmind.device.AndroidRecentFileProvider
import com.bytedance.zgx.pocketmind.device.AndroidRecentImageTextProvider
import com.bytedance.zgx.pocketmind.device.DeviceControlSessionService
import com.bytedance.zgx.pocketmind.download.ModelDownloadService
import com.bytedance.zgx.pocketmind.memory.LongTermMemoryControls
import com.bytedance.zgx.pocketmind.memory.MemoryDeletionEventStore
import com.bytedance.zgx.pocketmind.memory.MemoryEmbeddingStore
import com.bytedance.zgx.pocketmind.memory.MemoryRecordStore
import com.bytedance.zgx.pocketmind.memory.MemoryRepository
import com.bytedance.zgx.pocketmind.memory.RoomMemoryDeletionEventStore
import com.bytedance.zgx.pocketmind.memory.RoomMemoryEmbeddingStore
import com.bytedance.zgx.pocketmind.memory.RoomMemoryRecordStore
import com.bytedance.zgx.pocketmind.memory.ZvecMemoryEmbeddingStore
import com.bytedance.zgx.pocketmind.memory.ZvecMemoryRecordStore
import com.bytedance.zgx.pocketmind.multimodal.AndroidCurrentScreenshotOcrProvider
import com.bytedance.zgx.pocketmind.multimodal.CurrentScreenshotOcrProvider
import com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestrator
import com.bytedance.zgx.pocketmind.orchestration.CompositeAgentObservationReplanner
import com.bytedance.zgx.pocketmind.orchestration.ModelObservationReplanner
import com.bytedance.zgx.pocketmind.orchestration.RoomAgentTraceStore
import com.bytedance.zgx.pocketmind.orchestration.SequentialActionObservationReplanner
import com.bytedance.zgx.pocketmind.runtime.OkHttpRemoteChatRuntime
import com.bytedance.zgx.pocketmind.runtime.RealLiteRtRuntime
import com.bytedance.zgx.pocketmind.runtime.TfliteTextEmbeddingRuntimeFactory
import com.bytedance.zgx.pocketmind.storage.SharedPreferencesLocalDocumentStore
import com.bytedance.zgx.pocketmind.storage.ZvecNativeLocalVectorIndex
import com.bytedance.zgx.pocketmind.tool.ValidatingToolExecutor
import com.bytedance.zgx.pocketmind.tool.RoutingToolExecutor
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import com.bytedance.zgx.pocketmind.tool.ToolExecutor
import com.bytedance.zgx.pocketmind.tool.OkHttpWebSearchProvider
import org.json.JSONObject

class PocketMindAppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = PocketMindDatabase.get(appContext)
    private val settingsStore = PreferenceSettingsStore(appContext)
    private val secretStore = EncryptedSecretStore(appContext)

    private val modelRepository: ModelRepository
    private val bundledModelInstaller: BundledModelInstaller
    private val sessionRepository: SessionRepository
    private val generationParametersRepository: GenerationParametersRepository
    private val remoteModelRepository: RemoteModelRepository
    private val huggingFaceAuthRepository: HuggingFaceAuthRepository
    private val firstRunSetupRepository: FirstRunSetupRepository
    private val downloadService: ModelDownloadService
    private val localRuntime: RealLiteRtRuntime
    private val remoteRuntime: OkHttpRemoteChatRuntime
    private val memoryRepository: MemoryRepository
    private val toolAuditRepository: ToolAuditRepository
    private val remoteSendAuditRepository: RemoteSendAuditRepository
    private val scheduledTaskRepository: ScheduledTaskRepository
    private val backgroundTaskSchedulerInternal: AndroidBackgroundTaskScheduler
    private val reminderNotificationHelper: ReminderNotificationHelper
    private val currentScreenshotOcrProviderInternal: AndroidCurrentScreenshotOcrProvider
    private val actionPlanningRuntime: HybridActionPlanningRuntime
    private val actionExecutor: ToolExecutor
    private val assistantOrchestrator: AssistantOrchestrator

    init {
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
        RealLiteRtRuntime.configureNativeLogging()
        localRuntime = RealLiteRtRuntime(appContext.cacheDir)
        remoteRuntime = OkHttpRemoteChatRuntime()
        val roomMemoryRecordStore = RoomMemoryRecordStore(database.memoryRecordDao())
        val roomMemoryEmbeddingStore = RoomMemoryEmbeddingStore(database.memoryEmbeddingDao())
        val memoryStores = createMemoryStores(roomMemoryRecordStore, roomMemoryEmbeddingStore)
        memoryRepository = MemoryRepository(
            semanticRuntimeFactory = { modelPath ->
                TfliteTextEmbeddingRuntimeFactory.create(appContext, modelPath)
            },
            recordStore = memoryStores.recordStore,
            embeddingStore = memoryStores.embeddingStore,
            deletionEventStore = memoryStores.deletionEventStore,
        )
        toolAuditRepository = ToolAuditRepository(database.toolAuditDao())
        remoteSendAuditRepository = RemoteSendAuditRepository(database.remoteSendAuditDao())
        scheduledTaskRepository = ScheduledTaskRepository(database.scheduledTaskDao())
        backgroundTaskSchedulerInternal = AndroidBackgroundTaskScheduler(appContext, scheduledTaskRepository)
        reminderNotificationHelper = ReminderNotificationHelper(appContext)
        currentScreenshotOcrProviderInternal = AndroidCurrentScreenshotOcrProvider(appContext)
        val toolRegistry = ToolRegistry()
        actionPlanningRuntime = HybridActionPlanningRuntime(
            cacheDir = appContext.cacheDir,
            toolRegistry = toolRegistry,
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
                    backgroundTaskScheduler = backgroundTaskSchedulerInternal,
                    canPostReminderNotifications = reminderNotificationHelper::canPostNotifications,
                    toolRegistry = toolRegistry,
                ),
                backgroundTaskScheduler = backgroundTaskSchedulerInternal,
                recentImageTextProvider = AndroidRecentImageTextProvider(appContext),
                currentScreenTextProvider = AndroidCurrentScreenTextProvider(),
                currentScreenshotOcrProvider = currentScreenshotOcrProviderInternal,
                currentScreenControlProvider = AndroidCurrentScreenControlProvider(),
                toolRegistry = toolRegistry,
            ),
            registry = toolRegistry,
        )
        assistantOrchestrator = AssistantOrchestrator(
            memoryIndex = memoryRepository,
            actionPlanningRuntime = actionPlanningRuntime,
            toolRegistry = toolRegistry,
            toolAuditSink = toolAuditRepository,
            traceStore = RoomAgentTraceStore(database.agentTraceDao()),
            observationReplanner = CompositeAgentObservationReplanner(
                ModelObservationReplanner(
                    actionPlanningRuntime = actionPlanningRuntime,
                    actionModelPathProvider = modelRepository::verifiedActionModelPath,
                    toolRegistry = toolRegistry,
                ),
                SequentialActionObservationReplanner(
                    actionPlanningRuntime = actionPlanningRuntime,
                    toolRegistry = toolRegistry,
                ),
            ),
            deviceControlSessionFinisher = {
                DeviceControlSessionService.stop(appContext)
            },
        )
    }

    fun viewModelFactory(skipStartupModelRuntimeWork: Boolean = false): ViewModelProvider.Factory =
        PocketMindViewModelFactory(
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
            backgroundTaskScheduler = backgroundTaskSchedulerInternal,
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

    val backgroundTaskScheduler: AndroidBackgroundTaskScheduler
        get() = backgroundTaskSchedulerInternal

    val currentScreenshotOcrProvider: CurrentScreenshotOcrProvider
        get() = currentScreenshotOcrProviderInternal

    private fun createMemoryStores(
        roomMemoryRecordStore: RoomMemoryRecordStore,
        roomMemoryEmbeddingStore: RoomMemoryEmbeddingStore,
    ): MemoryStores {
        val migrationDao = database.localStorageMigrationStateDao()
        return runCatching {
            val documents = SharedPreferencesLocalDocumentStore(appContext)
            val recordStore = ZvecMemoryRecordStore(
                documents = documents,
                mirrorStore = roomMemoryRecordStore,
            )
            val vectors = ZvecNativeLocalVectorIndex(
                rootDir = appContext.noBackupFilesDir
                    .resolve("pocketmind-zvec")
                    .resolve("v1")
                    .resolve("pm_vectors_v1"),
            )
            backfillRoomMemoryRecords(
                source = roomMemoryRecordStore,
                target = recordStore,
                migrationDao = migrationDao,
            )
            MemoryStores(
                recordStore = recordStore,
                embeddingStore = ZvecMemoryEmbeddingStore(
                    documents = documents,
                    vectors = vectors,
                    mirrorStore = roomMemoryEmbeddingStore,
                ),
                deletionEventStore = RoomMemoryDeletionEventStore(
                    dao = database.memoryDeletionEventDao(),
                ),
            )
        }.getOrElse { error ->
            recordMemoryMigrationFailure(migrationDao, error)
            MemoryStores(
                recordStore = roomMemoryRecordStore,
                embeddingStore = roomMemoryEmbeddingStore,
                deletionEventStore = RoomMemoryDeletionEventStore(
                    dao = database.memoryDeletionEventDao(),
                    transactionDao = database.memoryDeletionTransactionDao(),
                ),
            )
        }
    }

    private fun backfillRoomMemoryRecords(
        source: RoomMemoryRecordStore,
        target: ZvecMemoryRecordStore,
        migrationDao: LocalStorageMigrationStateDao,
    ) {
        val stateId = "room-memory-to-zvec"
        val existing = migrationDao.state(stateId)
        val sourceRecords = source.records().sortedBy { record -> record.id }
        if (existing?.phase == "Completed" && (sourceRecords.isEmpty() || target.records().isNotEmpty())) {
            return
        }

        val startedAtMillis = existing?.startedAtMillis ?: System.currentTimeMillis()
        val checkpointId = existing?.lastId?.takeIf { existing.phase == "Running" }
        migrationDao.upsert(
            LocalStorageMigrationStateEntity(
                id = stateId,
                phase = "Running",
                lastDomain = existing?.lastDomain,
                lastId = checkpointId,
                startedAtMillis = startedAtMillis,
                completedAtMillis = null,
                errorJson = null,
                schemaVersion = 1,
            ),
        )

        var lastId = checkpointId
        sourceRecords
            .filter { record -> checkpointId == null || record.id > checkpointId }
            .forEach { record ->
                target.upsert(record)
                lastId = record.id
                migrationDao.upsert(
                    LocalStorageMigrationStateEntity(
                        id = stateId,
                        phase = "Running",
                        lastDomain = "memory",
                        lastId = record.id,
                        startedAtMillis = startedAtMillis,
                        completedAtMillis = null,
                        errorJson = null,
                        schemaVersion = 1,
                    ),
                )
            }

        migrationDao.upsert(
            LocalStorageMigrationStateEntity(
                id = stateId,
                phase = "Completed",
                lastDomain = "memory",
                lastId = lastId,
                startedAtMillis = startedAtMillis,
                completedAtMillis = System.currentTimeMillis(),
                errorJson = null,
                schemaVersion = 1,
            ),
        )
    }

    private fun recordMemoryMigrationFailure(
        migrationDao: LocalStorageMigrationStateDao,
        error: Throwable,
    ) {
        migrationDao.upsert(
            LocalStorageMigrationStateEntity(
                id = "room-memory-to-zvec",
                phase = "Failed",
                lastDomain = null,
                lastId = null,
                startedAtMillis = System.currentTimeMillis(),
                completedAtMillis = System.currentTimeMillis(),
                errorJson = JSONObject()
                    .put("message", error.message ?: error::class.java.name)
                    .toString(),
                schemaVersion = 1,
            ),
        )
    }
}

private data class MemoryStores(
    val recordStore: MemoryRecordStore,
    val embeddingStore: MemoryEmbeddingStore,
    val deletionEventStore: MemoryDeletionEventStore,
)

private class PocketMindViewModelFactory(
    private val modelRepository: ModelRepository,
    private val bundledModelInstaller: BundledModelInstaller,
    private val sessionRepository: SessionRepository,
    private val generationParametersRepository: GenerationParametersRepository,
    private val remoteModelRepository: RemoteModelRepository,
    private val huggingFaceAuthRepository: HuggingFaceAuthRepository,
    private val firstRunSetupRepository: FirstRunSetupRepository,
    private val downloadService: ModelDownloadService,
    private val runtime: RealLiteRtRuntime,
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
        require(modelClass.isAssignableFrom(PocketMindViewModel::class.java)) {
            "Unknown ViewModel class ${modelClass.name}"
        }
        return PocketMindViewModel(
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
        ) as T
    }
}
