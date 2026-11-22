package com.bytedance.zgx.solin

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.bytedance.zgx.solin.data.FirstRunSetupRepository
import com.bytedance.zgx.solin.data.PreferenceSettingsStore
import com.bytedance.zgx.solin.data.RemoteModelRepository
import com.bytedance.zgx.solin.data.SolinDatabase
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.rules.ExternalResource

internal val ReadyRemoteModelConfig = RemoteModelConfig(
    baseUrl = "http://127.0.0.1:16543/v1",
    modelName = "android-test-model",
    supportsVisionInput = true
)

/**
 * Snapshot of the persistent remote config + inference mode a test found on the device, so an
 * on-device run can restore whatever real (API-key-bearing) config the user had. On a throwaway CI
 * emulator this is a no-op restore; on a real device it prevents [resetMainActivityPersistentState]
 * from permanently clobbering the user's configured endpoint. Captured via [RemoteModelRepository]
 * because the API key lives in the SecretStore, not in [PreferenceSettingsStore] alone.
 */
internal class MainActivityRemoteConfigBackup private constructor(
    private val inferenceMode: InferenceMode,
    private val remoteConfig: RemoteModelConfig
) {
    fun restore(context: Context) {
        val repository = RemoteModelRepository(context.applicationContext)
        runCatching { repository.saveConfig(remoteConfig) }
        runCatching { repository.saveMode(inferenceMode) }
        (context.applicationContext as? SolinApplication)?.clearContainersForInstrumentation()
    }

    companion object {
        fun capture(context: Context): MainActivityRemoteConfigBackup {
            val repository = RemoteModelRepository(context.applicationContext)
            val mode = runCatching { repository.loadMode() }.getOrDefault(InferenceMode.Local)
            val config = runCatching { repository.loadConfig() }.getOrDefault(RemoteModelConfig())
            return MainActivityRemoteConfigBackup(mode, config)
        }
    }
}

internal fun resetMainActivityPersistentState(
    context: Context,
    inferenceMode: InferenceMode,
    remoteModelConfig: RemoteModelConfig = RemoteModelConfig()
) {
    (context.applicationContext as? SolinApplication)?.clearContainersForInstrumentation()
    val settingsStore = PreferenceSettingsStore(context)
    settingsStore.saveInferenceMode(inferenceMode)
    if (remoteModelConfig.isConfigured) {
        // Persist revision and integrity together; direct prefs writes leave Remote placement unconfigured.
        RemoteModelRepository(context.applicationContext).saveConfig(remoteModelConfig).getOrThrow()
    } else {
        settingsStore.saveRemoteConfig(remoteModelConfig)
    }
    settingsStore.saveActiveSessionId("")
    // Clear model selection so later UI tests do not inherit a half-loaded local path
    // (skip-startup instrumentation would leave isReady=false and change empty-state copy).
    settingsStore.saveActiveInstalledModelId(null)
    settingsStore.saveSelectedModelId(DEFAULT_CHAT_MODEL_ID)
    FirstRunSetupRepository(settingsStore).apply {
        markSetupDismissed()
        setMemoryEnabled(true)
        setReduceDeviceActionConfirmations(false)
    }
    resetTransientDatabaseState(context)
}

internal fun resetMainActivityFreshInstallState(context: Context) {
    (context.applicationContext as? SolinApplication)?.clearContainersForInstrumentation()
    val settingsStore = PreferenceSettingsStore(context)
    settingsStore.saveInferenceMode(InferenceMode.Local)
    settingsStore.saveRemoteConfig(RemoteModelConfig())
    settingsStore.saveActiveSessionId("")
    settingsStore.saveActiveInstalledModelId(null)
    settingsStore.saveSelectedModelId(DEFAULT_CHAT_MODEL_ID)
    settingsStore.clearSetupDismissed()
    FirstRunSetupRepository(settingsStore).setMemoryEnabled(true)
    resetTransientDatabaseState(context)
}

private fun resetTransientDatabaseState(context: Context) {
    val sqliteDatabase = SolinDatabase.get(context).openHelper.writableDatabase
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
    // Model registry rows must not leak across instrumentation cases: a leftover
    // active model path with skip-startup makes empty-state/banner copy switch to
    // the "loading local model" branch and breaks smoke/first-run assertions.
    "installed_models"
)

internal fun mainActivitySkipStartupIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
    putExtra(MainActivity.EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, true)
}

internal fun mainActivitySkipStartupIntent(
    context: Context,
    debugRemoteModelConfig: RemoteModelConfig
): Intent = mainActivitySkipStartupIntent(context).apply {
    putExtra(MainActivity.EXTRA_DEBUG_SCREENSHOT_REMOTE_BASE_URL, debugRemoteModelConfig.baseUrl)
    putExtra(MainActivity.EXTRA_DEBUG_SCREENSHOT_REMOTE_MODEL_NAME, debugRemoteModelConfig.modelName)
    putExtra(
        MainActivity.EXTRA_DEBUG_SCREENSHOT_REMOTE_SUPPORTS_VISION_INPUT,
        debugRemoteModelConfig.supportsVisionInput
    )
}

internal fun <A : Activity> activityFromScenarioRule(rule: ActivityScenarioRule<A>): A {
    val activityRef = AtomicReference<A>()
    rule.scenario.onActivity { activity -> activityRef.set(activity) }
    return checkNotNull(activityRef.get()) { "ActivityScenarioRule did not provide an activity." }
}

/**
 * Waits until the resumed [MainActivity]'s view model has committed the remote configuration:
 * remote mode, the expected endpoint (so a stale persisted config cannot pass early) and `isReady`. MainActivity shows its content before the
 * (debug-intent) remote config is applied on IO, and the composer placeholder alone does not prove
 * it, so a send issued in that window reads an unconfigured `stateBeforeSend` and is blocked by
 * placement before any dispatch. `updateRemoteReadiness` publishes `远程模型已就绪` from this same
 * state update, but that status is not rendered once the home is ready and later runs overwrite it,
 * so the underlying state is the observable proof.
 */
internal fun ComposeTestRule.waitForRemoteModelReady(expectedBaseUrl: String, timeoutMillis: Long = 10_000) {
    waitUntil(timeoutMillis = timeoutMillis) { isRemoteModelReadyNow(expectedBaseUrl) }
}

private fun isRemoteModelReadyNow(expectedBaseUrl: String): Boolean {
    val state = resumedMainActivityUiState() ?: return false
    return state.isReady &&
        state.inferenceMode == InferenceMode.Remote &&
        state.remoteModelConfig.isConfigured &&
        state.remoteModelConfig.baseUrl == expectedBaseUrl
}

/** The live view-model state of the resumed [MainActivity], or null when none is resumed. */
internal fun resumedMainActivityUiState(): ChatUiState? {
    var state: ChatUiState? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val activity = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>()
            .firstOrNull() ?: return@runOnMainSync
        state = ViewModelProvider(activity)[SolinViewModel::class.java].uiState.value
    }
    return state
}

/**
 * Connectivity-only endpoint for [ReadyRemoteModelConfig] (127.0.0.1:16543). Connectivity probing
 * marks an unreachable remote endpoint Unreachable and placement then blocks remote sends, so tests
 * that pre-configure that endpoint but run no chat mock need something real answering `/v1/models`.
 * It deliberately cannot answer chat: any other request gets 404 and is recorded, and [after] fails
 * the test if one arrived, so a test that must not make a remote chat request still cannot.
 * Start it ahead of the Activity (outer rule / plain rule with an Activity launched in the body).
 */
class LocalModelProbeServerRule : ExternalResource() {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val unexpectedRequests = CopyOnWriteArrayList<String>()

    override fun before() {
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PROBE_PORT), 8)
        }
        serverSocket = socket
        acceptThread = thread(start = true, isDaemon = true, name = "solin-probe-server") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(start = true, isDaemon = true, name = "solin-probe-connection") { serve(client) }
            }
        }
    }

    override fun after() {
        serverSocket?.close()
        acceptThread?.join(2_000)
        serverSocket = null
        acceptThread = null
        check(unexpectedRequests.isEmpty()) {
            "Connectivity probe server received non-probe requests: $unexpectedRequests"
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return
                while (reader.readLine()?.isNotEmpty() == true) Unit
                val parts = requestLine.split(" ")
                val isProbe = parts.getOrNull(0) == "GET" && parts.getOrNull(1)?.substringBefore('?') == "/v1/models"
                val body = if (isProbe) PROBE_MODELS_BODY else "{}"
                if (!isProbe) unexpectedRequests += "${parts.getOrNull(0)} ${parts.getOrNull(1)}"
                val status = if (isProbe) "200 OK" else "404 Not Found"
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            )
                            .toByteArray(StandardCharsets.ISO_8859_1)
                    )
                    write(bytes)
                    flush()
                }
            }
        } catch (_: java.io.IOException) {
            // A client that disconnects or times out ends only this connection; it is not a server
            // failure, and the owning test's assertions (and [after]) still decide pass or fail.
        }
    }

    private companion object {
        const val PROBE_PORT = 16543
        const val PROBE_MODELS_BODY = "{\"object\":\"list\",\"data\":[{\"id\":\"android-test-model\",\"object\":\"model\"}]}"
    }
}
