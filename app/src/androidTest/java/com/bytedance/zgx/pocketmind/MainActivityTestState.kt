package com.bytedance.zgx.pocketmind

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.bytedance.zgx.pocketmind.data.FirstRunSetupRepository
import com.bytedance.zgx.pocketmind.data.PocketMindDatabase
import com.bytedance.zgx.pocketmind.data.PreferenceSettingsStore
import java.util.concurrent.atomic.AtomicReference
import androidx.test.ext.junit.rules.ActivityScenarioRule

internal val ReadyRemoteModelConfig = RemoteModelConfig(
    baseUrl = "http://127.0.0.1:1/v1",
    modelName = "android-test-model",
    supportsVisionInput = true,
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
        setReduceDeviceActionConfirmations(false)
    }
    resetTransientDatabaseState(context)
}

internal fun resetMainActivityFreshInstallState(context: Context) {
    val settingsStore = PreferenceSettingsStore(context)
    settingsStore.saveInferenceMode(InferenceMode.Local)
    settingsStore.saveRemoteConfig(RemoteModelConfig())
    settingsStore.saveActiveSessionId("")
    settingsStore.setSetupDismissedForTesting(false)
    FirstRunSetupRepository(settingsStore).setMemoryEnabled(true)
    resetTransientDatabaseState(context)
}

private fun resetTransientDatabaseState(context: Context) {
    val sqliteDatabase = PocketMindDatabase.get(context).openHelper.writableDatabase
    sqliteDatabase.beginTransaction()
    try {
        transientStateTables.forEach { table ->
            sqliteDatabase.execSQL("DELETE FROM $table")
        }
        sqliteDatabase.setTransactionSuccessful()
    } finally {
        sqliteDatabase.endTransaction()
    }
}

private val transientStateTables = listOf(
    "agent_skill_run_checkpoints",
    "pending_agent_confirmations",
    "agent_steps",
    "agent_runs",
    "memory_embeddings",
    "memory_records",
    "memory_deletion_events",
    "remote_send_audit_events",
    "tool_audit_events",
    "scheduled_tasks",
    "chat_messages",
    "chat_sessions",
)

internal fun mainActivitySkipStartupIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        putExtra(MainActivity.EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, true)
    }

internal fun mainActivitySkipStartupIntent(
    context: Context,
    debugRemoteModelConfig: RemoteModelConfig,
): Intent =
    mainActivitySkipStartupIntent(context).apply {
        putExtra(MainActivity.EXTRA_DEBUG_SCREENSHOT_REMOTE_BASE_URL, debugRemoteModelConfig.baseUrl)
        putExtra(MainActivity.EXTRA_DEBUG_SCREENSHOT_REMOTE_MODEL_NAME, debugRemoteModelConfig.modelName)
        putExtra(
            MainActivity.EXTRA_DEBUG_SCREENSHOT_REMOTE_SUPPORTS_VISION_INPUT,
            debugRemoteModelConfig.supportsVisionInput,
        )
    }

internal fun <A : Activity> activityFromScenarioRule(rule: ActivityScenarioRule<A>): A {
    val activityRef = AtomicReference<A>()
    rule.scenario.onActivity { activity -> activityRef.set(activity) }
    return checkNotNull(activityRef.get()) { "ActivityScenarioRule did not provide an activity." }
}
