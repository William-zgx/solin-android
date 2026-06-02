package com.bytedance.zgx.pocketmind.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bytedance.zgx.pocketmind.InferenceMode
import com.bytedance.zgx.pocketmind.RemoteModelConfig
import com.bytedance.zgx.pocketmind.data.FirstRunSetupRepository
import com.bytedance.zgx.pocketmind.data.PocketMindDatabase
import com.bytedance.zgx.pocketmind.data.PreferenceSettingsStore
import com.bytedance.zgx.pocketmind.data.RemoteModelRepository

class DebugRemoteConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val config = RemoteModelConfig(
            baseUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty(),
            modelName = intent.getStringExtra(EXTRA_MODEL_NAME).orEmpty(),
            apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty(),
        ).normalized()
        if (!config.isConfigured) {
            resultCode = Activity.RESULT_CANCELED
            resultData = "invalid remote config"
            return
        }

        val settingsStore = PreferenceSettingsStore(appContext)
        if (intent.getBooleanExtra(EXTRA_CLEAR_STATE, false)) {
            settingsStore.saveActiveSessionId("")
            PocketMindDatabase.get(appContext).clearAllTables()
        }
        FirstRunSetupRepository(settingsStore).markSetupDismissed()
        val repository = RemoteModelRepository(appContext)
        repository.saveConfig(config).getOrThrow()
        repository.saveMode(InferenceMode.Remote)

        resultCode = Activity.RESULT_OK
        resultData = "remote config saved"
    }

    private companion object {
        const val EXTRA_BASE_URL = "baseUrl"
        const val EXTRA_MODEL_NAME = "modelName"
        const val EXTRA_API_KEY = "apiKey"
        const val EXTRA_CLEAR_STATE = "clearState"
    }
}
