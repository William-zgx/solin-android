package com.bytedance.zgx.solin

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

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
    // Always save via RemoteModelRepository so SecretStore API key, revision, and integrity stay aligned.
    RemoteModelRepository(context.applicationContext).saveConfig(remoteModelConfig).getOrThrow()
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
    RemoteModelRepository(context.applicationContext).saveConfig(RemoteModelConfig()).getOrThrow()
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
    "agent_run_placement_bindings",
    "agent_runs",
    "memory_embeddings",
    "memory_records",
    "memory_deletion_events",
    "remote_send_audit_events",
    "tool_audit_events",
    "scheduled_tasks",
    "chat_messages",
    "chat_sessions",
    // Model registry and download rows must not leak across instrumentation cases: a leftover
    // active model path with skip-startup makes empty-state/banner copy switch to
    // the "loading local model" branch and breaks smoke/first-run assertions.
    "download_records",
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

internal fun launchReadyRemoteMainActivity(
    context: Context,
    remoteModelConfig: RemoteModelConfig = ReadyRemoteModelConfig
): ActivityScenario<MainActivity> {
    resetMainActivityPersistentState(
        context = context,
        inferenceMode = InferenceMode.Remote,
        remoteModelConfig = remoteModelConfig
    )
    return ActivityScenario.launch(
        mainActivitySkipStartupIntent(
            context = context,
            debugRemoteModelConfig = remoteModelConfig
        )
    )
}

/**
 * Runs [block] against [MainActivity], captures its [SolinViewModel] job before closing the scenario,
 * and joins the cancelled scope so background IO finishes before the next test closes Room.
 */
internal inline fun <R> ActivityScenario<MainActivity>.useWithViewModelTeardown(
    block: (ActivityScenario<MainActivity>) -> R
): R {
    var viewModelJob: Job? = null
    var primaryFailure: Throwable? = null
    try {
        return block(this)
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        runCatching {
            onActivity { activity ->
                viewModelJob = ViewModelProvider(activity)[SolinViewModel::class.java]
                    .viewModelScope.coroutineContext[Job]
            }
        }
        val closeFailure = runCatching { close() }.exceptionOrNull()
        val joinFailure = runCatching { awaitViewModelTeardown(viewModelJob) }.exceptionOrNull()
        val teardownFailure = combineFailures(closeFailure, joinFailure)
        rethrowOrSuppress(primaryFailure, teardownFailure)
    }
}

/**
 * Coordinates [SolinViewModel] coroutine teardown around an [ActivityScenarioRule]-backed test rule:
 * place [awaitRule] outside the Compose rule and [captureRule] inside it.
 */
internal class MainActivityTeardownRule(
    private val activityProvider: () -> MainActivity?,
    private val afterTeardown: () -> Unit = {}
) {
    @Volatile
    private var viewModelJob: Job? = null

    val captureRule: TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } finally {
                    captureViewModelJob()
                }
            }
        }
    }

    val awaitRule: TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                var primaryFailure: Throwable? = null
                try {
                    base.evaluate()
                } catch (error: Throwable) {
                    primaryFailure = error
                    throw error
                } finally {
                    val joinFailure = runCatching { awaitViewModelTeardown(viewModelJob) }.exceptionOrNull()
                    viewModelJob = null
                    val postFailure = runCatching { afterTeardown() }.exceptionOrNull()
                    rethrowOrSuppress(primaryFailure, combineFailures(joinFailure, postFailure))
                }
            }
        }
    }

    private fun captureViewModelJob() {
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = activityProvider() ?: return@runOnMainSync
                viewModelJob = ViewModelProvider(activity)[SolinViewModel::class.java]
                    .viewModelScope.coroutineContext[Job]
            }
        }
    }
}

@PublishedApi
internal fun awaitViewModelTeardown(viewModelJob: Job?) {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    viewModelJob?.let { job ->
        runBlocking {
            withTimeout(10_000) {
                job.join()
            }
        }
    }
}

@PublishedApi
internal fun combineFailures(first: Throwable?, second: Throwable?): Throwable? = when {
    first == null -> second
    second == null -> first
    else -> first.apply { addSuppressed(second) }
}

@PublishedApi
internal fun rethrowOrSuppress(primaryFailure: Throwable?, secondaryFailure: Throwable?) {
    if (secondaryFailure == null) return
    if (primaryFailure != null) {
        primaryFailure.addSuppressed(secondaryFailure)
    } else {
        throw secondaryFailure
    }
}

internal fun ComposeTestRule.waitForTag(tag: String, timeoutMillis: Long = 5_000) {
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}

internal fun ComposeTestRule.waitForTagGone(tag: String, timeoutMillis: Long = 5_000) {
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    }
}

internal fun ComposeTestRule.waitForText(
    text: String,
    timeoutMillis: Long = 10_000,
    substring: Boolean = false
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }
}

internal fun ComposeTestRule.waitForTextGone(
    text: String,
    timeoutMillis: Long = 10_000,
    substring: Boolean = false
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty()
    }
}

internal fun ComposeTestRule.waitForAnyText(
    texts: List<String>,
    timeoutMillis: Long = 10_000,
    substring: Boolean = false
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        texts.any { text ->
            onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

internal fun ComposeTestRule.waitForOptionalTag(tag: String, timeoutMillis: Long): Boolean = runCatching {
    waitForTag(tag, timeoutMillis = timeoutMillis)
    true
}.getOrDefault(false)

internal fun ComposeTestRule.assertTagAbsent(tag: String, timeoutMillis: Long = 5_000) {
    waitForTagGone(tag, timeoutMillis = timeoutMillis)
}

internal fun ComposeTestRule.assertTextAbsent(
    text: String,
    timeoutMillis: Long = 5_000,
    substring: Boolean = false
) {
    waitForTextGone(text, timeoutMillis = timeoutMillis, substring = substring)
}

internal fun ComposeTestRule.waitForReadyComposer(timeoutMillis: Long = 10_000) {
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodesWithText("输入问题").fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag("composer_input").assertIsEnabled()
}

internal fun ComposeTestRule.confirmRemoteSendIfPresent(timeoutMillis: Long = 1_500) {
    val needsConfirmation = waitForOptionalTag("remote_send_disclosure_sheet", timeoutMillis = timeoutMillis)
    if (!needsConfirmation) return
    onNodeWithTag("remote_send_confirm_button").performClick()
    waitForTagGone("remote_send_disclosure_sheet")
}

internal fun ComposeTestRule.sendRemotePrompt(
    prompt: String,
    expectedBaseUrl: String = ReadyRemoteModelConfig.baseUrl,
    confirmDisclosureTimeoutMillis: Long = 1_500
) {
    waitForRemoteModelReady(expectedBaseUrl)
    waitForReadyComposer()
    onNodeWithTag("composer_input").performTextClearance()
    onNodeWithTag("composer_input").performTextInput(prompt)
    onNodeWithTag("composer_send_button").performClick()
    confirmRemoteSendIfPresent(timeoutMillis = confirmDisclosureTimeoutMillis)
}

internal fun hasTestTagPrefix(prefix: String): SemanticsMatcher = SemanticsMatcher("has test tag prefix $prefix") { node ->
    node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
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
class LocalModelProbeServerRule : TestRule {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val activeClients = CopyOnWriteArrayList<Socket>()
    private val clientThreads = CopyOnWriteArrayList<Thread>()
    private val unexpectedRequests = CopyOnWriteArrayList<String>()

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            start()
            var primaryFailure: Throwable? = null
            try {
                base.evaluate()
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                val teardownFailure = stop()
                rethrowOrSuppress(primaryFailure, teardownFailure)
            }
        }
    }

    private fun start() {
        unexpectedRequests.clear()
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PROBE_PORT), 8)
        }
        serverSocket = socket
        acceptThread = thread(start = true, isDaemon = true, name = "solin-probe-server") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                activeClients += client
                lateinit var worker: Thread
                worker = thread(start = false, isDaemon = true, name = "solin-probe-connection") {
                    try {
                        serve(client)
                    } finally {
                        activeClients.remove(client)
                        clientThreads.remove(worker)
                    }
                }
                clientThreads += worker
                worker.start()
            }
        }
    }

    private fun stop(): Throwable? {
        runCatching { serverSocket?.close() }
        activeClients.forEach { socket -> runCatching { socket.close() } }
        runCatching { acceptThread?.join(2_000) }
        clientThreads.toList().forEach { worker -> runCatching { worker.join(2_000) } }
        activeClients.clear()
        clientThreads.clear()
        serverSocket = null
        acceptThread = null
        return if (unexpectedRequests.isEmpty()) {
            null
        } else {
            IllegalStateException("Connectivity probe server received non-probe requests: $unexpectedRequests")
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

/**
 * Live view-model facts for failure diagnostics: what placement and the disclosure gate saw.
 * No prompt text, keys or run ids.
 */
internal fun liveRemoteUiSummary(): String = resumedMainActivityUiState()?.let {
    "liveConnectivity=${it.remoteModelConfig.connectivityStatus} " +
        "activeRunPlacementReason=${it.activeRunPlacementReason} " +
        "pendingRemoteSendDisclosure=${it.pendingRemoteSendDisclosure != null} " +
        "isBusy=${it.isBusy} disclosurePolicy=${it.remoteSendDisclosurePolicy} mode=${it.inferenceMode}"
} ?: "live state unavailable"

/** Sends [liveRemoteUiSummary] and [placementStateSummary] to the instrumentation output; never throws. */
internal fun emitRouteDiagnostics(label: String, context: Context) {
    runCatching {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString("SolinLiveState-$label", liveRemoteUiSummary())
                putString("SolinPlacementState-$label", placementStateSummary(context))
            }
        )
    }
}

internal fun placementStateSummary(targetContext: Context): String = runCatching {
    val database = SolinDatabase.get(targetContext)
    val sqlite = database.openHelper.writableDatabase
    val dao = database.runPlacementBindingDao()
    // Recent runs with their binding (if any); run ids stay out of the output.
    val recent = sqlite.query(
        """
        SELECT r.id, r.state, b.placement, b.dispatchState, b.attempt, b.bootCount
        FROM agent_runs r
        LEFT JOIN agent_run_placement_bindings b ON b.runId = r.id
        ORDER BY r.updatedAtMillis DESC LIMIT 5
        """.trimIndent()
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    Pair(
                        cursor.getString(0),
                        "runState=${cursor.getString(1)} binding=${!cursor.isNull(2)} " +
                            "placement=${cursor.getStringOrNull(2)} dispatch=${cursor.getStringOrNull(3)} " +
                            "attempt=${if (cursor.isNull(4)) null else cursor.getInt(4)} " +
                            "boot=${if (cursor.isNull(5)) null else cursor.getLong(5)}"
                    )
                )
            }
        }
    }
    val runs = if (recent.isEmpty()) {
        "no runs"
    } else {
        recent.mapIndexed { index, (runId, row) ->
            val steps = dao.steps(runId).takeLast(8).joinToString(",") { it.type }
            "run$index[$row steps=$steps]"
        }.joinToString(" ")
    }
    val latestBinding = sqlite.query(
        """
        SELECT placement, dispatchState, attempt, bootCount
        FROM agent_run_placement_bindings
        ORDER BY boundAtElapsedRealtimeMillis DESC LIMIT 1
        """.trimIndent()
    ).use { cursor ->
        if (cursor.moveToFirst()) {
            "exists placement=${cursor.getString(0)} dispatch=${cursor.getString(1)} " +
                "attempt=${cursor.getInt(2)} boot=${cursor.getLong(3)}"
        } else {
            "none"
        }
    }
    "$runs latestBinding=$latestBinding"
}.getOrElse { "placement state failed: ${it.javaClass.simpleName}" }

private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
