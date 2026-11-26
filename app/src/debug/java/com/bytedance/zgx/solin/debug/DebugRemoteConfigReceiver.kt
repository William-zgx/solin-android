package com.bytedance.zgx.solin.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.data.FirstRunSetupRepository
import com.bytedance.zgx.solin.data.PreferenceSettingsStore
import com.bytedance.zgx.solin.data.RemoteModelRepository
import com.bytedance.zgx.solin.data.SolinDatabase
import com.bytedance.zgx.solin.orchestration.LocalPlanningStrategy

class DebugRemoteConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                handleReceive(appContext, intent, pending)
            } catch (throwable: Throwable) {
                pending.resultCode = Activity.RESULT_CANCELED
                pending.resultData = "debug remote config failed: ${throwable.javaClass.simpleName}"
            } finally {
                pending.finish()
            }
        }, "solin-debug-remote-config").start()
    }

    private fun handleReceive(
        appContext: Context,
        intent: Intent,
        pending: PendingResult
    ) {
        // A strategy broadcast only sets who proposes a local run's first tool (debug builds read it;
        // release builds always use RuleFirst). An unknown value is ignored and nothing is written.
        if (intent.hasExtra(EXTRA_LOCAL_PLANNING_STRATEGY)) {
            val strategy = LocalPlanningStrategy.parse(intent.getStringExtra(EXTRA_LOCAL_PLANNING_STRATEGY))
            strategy?.let(PreferenceSettingsStore(appContext)::setLocalPlanningStrategy)
            pending.resultCode = if (strategy != null) Activity.RESULT_OK else Activity.RESULT_CANCELED
            pending.resultData = strategy?.let { "local planning strategy saved: ${it.name}" } ?: "invalid local planning strategy ignored"
            return
        }
        val repository = RemoteModelRepository(appContext)
        if (intent.getBooleanExtra(EXTRA_CLEAR_REMOTE_CONFIG, false)) {
            repository.saveConfig(RemoteModelConfig()).getOrThrow()
            repository.saveMode(InferenceMode.Local)
            pending.resultCode = Activity.RESULT_OK
            pending.resultData = "remote config cleared"
            return
        }

        val remoteCredential = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
        val config = RemoteModelConfig(
            baseUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty(),
            modelName = intent.getStringExtra(EXTRA_MODEL_NAME).orEmpty(),
            apiKey = remoteCredential,
            supportsVisionInput = intent.getBooleanExtra(EXTRA_SUPPORTS_VISION_INPUT, false),
            supportsToolCalls = intent.getBooleanExtra(EXTRA_SUPPORTS_TOOL_CALLS, false)
        ).normalized()
        if (!config.isConfigured) {
            pending.resultCode = Activity.RESULT_CANCELED
            pending.resultData = "invalid remote config"
            return
        }

        val settingsStore = PreferenceSettingsStore(appContext)
        if (intent.getBooleanExtra(EXTRA_CLEAR_STATE, false)) {
            settingsStore.saveActiveSessionId("")
            SolinDatabase.get(appContext).clearAllTables()
        }
        FirstRunSetupRepository(settingsStore).markSetupDismissed()
        repository.saveConfig(config).getOrThrow()
        repository.saveMode(InferenceMode.Remote)

        pending.resultCode = Activity.RESULT_OK
        pending.resultData = "remote config saved"
    }

    private companion object {
        const val EXTRA_BASE_URL = "baseUrl"
        const val EXTRA_MODEL_NAME = "modelName"
        const val EXTRA_API_KEY = "apiKey"
        const val EXTRA_SUPPORTS_VISION_INPUT = "supportsVisionInput"
        const val EXTRA_SUPPORTS_TOOL_CALLS = "supportsToolCalls"
        const val EXTRA_CLEAR_STATE = "clearState"
        const val EXTRA_CLEAR_REMOTE_CONFIG = "clearRemoteConfig"
        const val EXTRA_LOCAL_PLANNING_STRATEGY = "localPlanningStrategy"
    }
}
