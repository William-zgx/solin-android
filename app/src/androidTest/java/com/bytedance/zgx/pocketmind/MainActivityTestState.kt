package com.bytedance.zgx.pocketmind

import android.content.Context
import com.bytedance.zgx.pocketmind.data.FirstRunSetupRepository
import com.bytedance.zgx.pocketmind.data.PocketMindDatabase
import com.bytedance.zgx.pocketmind.data.PreferenceSettingsStore

internal val ReadyRemoteModelConfig = RemoteModelConfig(
    baseUrl = "http://127.0.0.1:1/v1",
    modelName = "android-test-model",
)

internal fun resetMainActivityPersistentState(
    context: Context,
    inferenceMode: InferenceMode,
    remoteModelConfig: RemoteModelConfig = RemoteModelConfig(),
) {
    val settingsStore = PreferenceSettingsStore(context)
    settingsStore.saveInferenceMode(inferenceMode)
    settingsStore.saveRemoteConfig(remoteModelConfig)
    settingsStore.saveActiveSessionId("")
    FirstRunSetupRepository(settingsStore).apply {
        markSetupDismissed()
        setMemoryEnabled(true)
    }
    PocketMindDatabase.get(context).clearAllTables()
}
