package com.bytedance.zgx.pocketmind

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bytedance.zgx.pocketmind.action.ActionExecutor
import com.bytedance.zgx.pocketmind.action.HybridActionPlanningRuntime
import com.bytedance.zgx.pocketmind.data.EncryptedSecretStore
import com.bytedance.zgx.pocketmind.data.FirstRunSetupRepository
import com.bytedance.zgx.pocketmind.data.GenerationParametersRepository
import com.bytedance.zgx.pocketmind.data.LegacyPrefsMigrator
import com.bytedance.zgx.pocketmind.data.ModelRepository
import com.bytedance.zgx.pocketmind.data.PocketMindDatabase
import com.bytedance.zgx.pocketmind.data.PreferenceSettingsStore
import com.bytedance.zgx.pocketmind.data.RemoteModelRepository
import com.bytedance.zgx.pocketmind.data.SessionRepository
import com.bytedance.zgx.pocketmind.download.ModelDownloadService
import com.bytedance.zgx.pocketmind.memory.MemoryRepository
import com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestrator
import com.bytedance.zgx.pocketmind.runtime.OkHttpRemoteChatRuntime
import com.bytedance.zgx.pocketmind.runtime.RealLiteRtRuntime

class PocketMindAppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = PocketMindDatabase.get(appContext)
    private val settingsStore = PreferenceSettingsStore(appContext)
    private val secretStore = EncryptedSecretStore(appContext)

    private val modelRepository: ModelRepository
    private val sessionRepository: SessionRepository
    private val generationParametersRepository: GenerationParametersRepository
    private val remoteModelRepository: RemoteModelRepository
    private val firstRunSetupRepository: FirstRunSetupRepository
    private val downloadService: ModelDownloadService
    private val localRuntime: RealLiteRtRuntime
    private val remoteRuntime: OkHttpRemoteChatRuntime
    private val memoryRepository: MemoryRepository
    private val actionPlanningRuntime: HybridActionPlanningRuntime
    private val actionExecutor: ActionExecutor
    private val assistantOrchestrator: AssistantOrchestrator

    init {
        LegacyPrefsMigrator(appContext, database, settingsStore, secretStore).migrateIfNeeded()

        modelRepository = ModelRepository(
            appContext = appContext,
            modelDao = database.modelDao(),
            downloadRecordDao = database.downloadRecordDao(),
            settingsStore = settingsStore,
        )
        sessionRepository = SessionRepository(database.sessionDao(), settingsStore)
        generationParametersRepository = GenerationParametersRepository(settingsStore)
        remoteModelRepository = RemoteModelRepository(settingsStore, secretStore, appContext)
        firstRunSetupRepository = FirstRunSetupRepository(settingsStore)
        downloadService = ModelDownloadService(appContext)
        localRuntime = RealLiteRtRuntime(appContext.cacheDir)
        remoteRuntime = OkHttpRemoteChatRuntime()
        memoryRepository = MemoryRepository()
        actionPlanningRuntime = HybridActionPlanningRuntime(appContext.cacheDir)
        actionExecutor = ActionExecutor(appContext)
        assistantOrchestrator = AssistantOrchestrator(memoryRepository, actionPlanningRuntime)
    }

    val viewModelFactory: ViewModelProvider.Factory =
        PocketMindViewModelFactory(
            modelRepository = modelRepository,
            sessionRepository = sessionRepository,
            generationParametersRepository = generationParametersRepository,
            remoteModelRepository = remoteModelRepository,
            firstRunSetupRepository = firstRunSetupRepository,
            downloadService = downloadService,
            runtime = localRuntime,
            remoteRuntime = remoteRuntime,
            memoryRepository = memoryRepository,
            actionExecutor = actionExecutor,
            assistantOrchestrator = assistantOrchestrator,
            isArm64DeviceProvider = {
                Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }
            },
        )
}

private class PocketMindViewModelFactory(
    private val modelRepository: ModelRepository,
    private val sessionRepository: SessionRepository,
    private val generationParametersRepository: GenerationParametersRepository,
    private val remoteModelRepository: RemoteModelRepository,
    private val firstRunSetupRepository: FirstRunSetupRepository,
    private val downloadService: ModelDownloadService,
    private val runtime: RealLiteRtRuntime,
    private val remoteRuntime: OkHttpRemoteChatRuntime,
    private val memoryRepository: MemoryRepository,
    private val actionExecutor: ActionExecutor,
    private val assistantOrchestrator: AssistantOrchestrator,
    private val isArm64DeviceProvider: () -> Boolean,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PocketMindViewModel::class.java)) {
            "Unknown ViewModel class ${modelClass.name}"
        }
        return PocketMindViewModel(
            modelRepository = modelRepository,
            sessionRepository = sessionRepository,
            generationParametersRepository = generationParametersRepository,
            remoteModelRepository = remoteModelRepository,
            firstRunSetupRepository = firstRunSetupRepository,
            downloadService = downloadService,
            runtime = runtime,
            remoteRuntime = remoteRuntime,
            memoryRepository = memoryRepository,
            actionExecutor = actionExecutor,
            assistantOrchestrator = assistantOrchestrator,
            isArm64DeviceProvider = isArm64DeviceProvider,
        ) as T
    }
}
