package com.bytedance.zgx.solin

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bytedance.zgx.solin.ui.VOICE_INPUT_PERMISSION_DISCLOSURE_BODY
import com.bytedance.zgx.solin.ui.VOICE_INPUT_PERMISSION_DISCLOSURE_TITLE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the platform [SpeechRecognizer] for composer voice input and forwards its callbacks into
 * [SolinViewModel]. Each start opens a new [VoiceRecognitionSession] generation so late callbacks
 * from a cancelled, timed-out or replaced generation are dropped. Destroys the recognizer with the
 * activity and cancels any active generation when the activity stops.
 *
 * On-device first: when the platform reports an on-device recognizer (API 31+) it is used
 * directly; otherwise the system recognizer is asked to prefer offline recognition. Because that
 * preference is only a hint, the system-recognizer route may use the system's online service, so
 * the voice consent is shown again with an explicit online-recognition note before the first such
 * session (see [selectVoiceRecognizerRoute]).
 *
 * Must be constructed while the activity is being initialized so the microphone permission
 * launcher registers before the activity reaches STARTED.
 */
internal class VoiceRecognitionCoordinator(
    private val activity: ComponentActivity,
    private val viewModel: () -> SolinViewModel,
    private val isViewModelReady: () -> Boolean,
) : DefaultLifecycleObserver {
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechRecognizerRoute: VoiceRecognizerRoute? = null
    private val session = VoiceRecognitionSession(
        scope = activity.lifecycleScope,
        sink = object : VoiceCaptureSink {
            override fun captureStarted() = viewModel().startVoiceInputCapture()
            override fun captureFinished() = viewModel().finishVoiceInputCapture()
            override fun transcript(transcript: String) = viewModel().acceptVoiceTranscript(transcript)
            override fun unavailable(message: String) = viewModel().reportVoiceInputUnavailable(message)
        },
        cancelRecognizer = { speechRecognizer?.cancel() },
    )
    private val audioPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            start()
        } else {
            viewModel().reportVoiceInputUnavailable("未授权麦克风权限")
        }
    }

    init {
        activity.lifecycle.addObserver(this)
    }

    fun start() {
        // Early UI guard: a queued click after destroy or during an active generation must not
        // reach the permission prompt or recognizer. A new generation needs a terminal state plus
        // another explicit user start.
        if (activity.lifecycle.currentState == Lifecycle.State.DESTROYED || session.isActive) return
        if (!activity.hasRuntimePermission(Manifest.permission.RECORD_AUDIO)) {
            viewModel().reportVoiceInputUnavailable("需要授权麦克风权限")
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val route = selectVoiceRecognizerRoute(
            sdkInt = Build.VERSION.SDK_INT,
            onDeviceRecognitionAvailable = isOnDeviceRecognitionAvailable(),
            systemRecognitionAvailable = SpeechRecognizer.isRecognitionAvailable(activity),
        )
        if (route == VoiceRecognizerRoute.Unavailable) {
            viewModel().reportVoiceInputUnavailable("当前设备没有可用语音识别服务")
            return
        }
        if (route.mayUseOnlineService && !onlineRecognitionAcknowledged) {
            showOnlineRecognitionConsent()
            return
        }
        val recognizer = recognizerFor(route) ?: run {
            viewModel().reportVoiceInputUnavailable("语音输入启动失败")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        voiceRecognitionIntentExtras().forEach { (key, value) ->
            when (value) {
                is Boolean -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
            }
        }
        session.start(
            installListener = { generation -> recognizer.setRecognitionListener(recognitionListener(generation)) },
            startListening = { recognizer.startListening(intent) },
        )
    }

    fun finish() {
        val recognizer = speechRecognizer
        session.finish(stopListening = recognizer?.let { r -> { r.stopListening() } })
    }

    fun cancel() {
        session.cancel()
    }

    private fun isOnDeviceRecognitionAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(activity) }.getOrDefault(false)

    private fun recognizerFor(route: VoiceRecognizerRoute): SpeechRecognizer? {
        speechRecognizer?.takeIf { speechRecognizerRoute == route }?.let { return it }
        speechRecognizer?.destroy()
        speechRecognizer = null
        val created = runCatching {
            if (route == VoiceRecognizerRoute.OnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            } else {
                SpeechRecognizer.createSpeechRecognizer(activity)
            }
        }.getOrNull() ?: return null
        speechRecognizer = created
        speechRecognizerRoute = route
        return created
    }

    private fun showOnlineRecognitionConsent() {
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle(VOICE_INPUT_PERMISSION_DISCLOSURE_TITLE)
                .setMessage("$VOICE_INPUT_PERMISSION_DISCLOSURE_BODY\n\n$VOICE_INPUT_ONLINE_RECOGNITION_NOTE")
                .setPositiveButton("同意并开启语音输入") { _, _ ->
                    onlineRecognitionAcknowledged = true
                    start()
                }
                .setNegativeButton("取消") { _, _ ->
                    viewModel().reportVoiceInputUnavailable("语音输入已取消")
                }
                .setOnCancelListener {
                    viewModel().reportVoiceInputUnavailable("语音输入已取消")
                }
                .show()
        }.onFailure {
            // Fail closed: without the online-recognition notice, do not start recognition.
            viewModel().reportVoiceInputUnavailable("语音输入不可用")
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        // Never keep the microphone request open while the UI is not visible; onResume does not reopen it.
        session.stopForLifecycle("语音输入已因离开界面而停止")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        session.destroy(
            releaseRecognizer = {
                speechRecognizer?.destroy()
                speechRecognizer = null
                speechRecognizerRoute = null
            },
            canReport = isViewModelReady,
        )
    }

    private fun recognitionListener(generation: Long): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (session.isActive(generation)) viewModel().startVoiceInputCapture()
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                if (session.isActive(generation)) viewModel().updateVoiceInputLevel(rmsDb = rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (session.isActive(generation)) viewModel().finishVoiceInputCapture()
            }

            override fun onError(error: Int) = session.onError(generation, error.voiceInputErrorMessage())

            override fun onResults(results: Bundle?) = session.onResults(generation, results?.recognizedSpeechText())

            override fun onPartialResults(partialResults: Bundle?) {
                if (!session.isActive(generation)) return
                partialResults?.recognizedSpeechText()?.let(viewModel()::updateVoiceInputPartialTranscript)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
}

/** Capture state sink; the coordinator forwards it to [SolinViewModel]. */
internal interface VoiceCaptureSink {
    fun captureStarted()

    fun captureFinished()

    fun transcript(transcript: String)

    fun unavailable(message: String)
}

/** Conservative absolute bound for listening plus post-stop transcription of one generation. */
internal const val VOICE_RECOGNITION_DEADLINE_MS = 60_000L

/**
 * Main-thread state machine for one explicit voice generation. Threat: a recognizer that never
 * reports a terminal callback would leave the microphone request and UI capture state open
 * indefinitely. Results, error, cancel, timeout and lifecycle stop are competing terminal
 * transitions; the first to clear the generation wins and later ones are ignored. The single
 * deadline is absolute: RMS, partials and [finish] never extend it, and there is no auto restart.
 * Never logs transcripts, partials or audio.
 */
internal class VoiceRecognitionSession(
    private val scope: CoroutineScope,
    private val sink: VoiceCaptureSink,
    private val cancelRecognizer: () -> Unit,
    private val deadlineMs: Long = VOICE_RECOGNITION_DEADLINE_MS,
) {
    private val gate = VoiceInputSessionGate()
    private var watchdog: Job? = null

    val isActive: Boolean get() = gate.activeSessionId != 0L

    /** No-op while a generation is already active. */
    fun start(installListener: (Long) -> Unit, startListening: () -> Unit) {
        if (isActive) return
        val generation = gate.startSession()
        installListener(generation)
        sink.captureStarted()
        watchdog = scope.launch {
            delay(deadlineMs)
            onDeadline(generation)
        }
        runCatching(startListening).onFailure {
            if (claim(generation)) sink.unavailable("语音输入启动失败")
        }
    }

    /** Non-terminal: the generation keeps waiting for results under the same deadline. */
    fun finish(stopListening: (() -> Unit)?) {
        val generation = gate.activeSessionId
        if (generation == 0L || stopListening == null) {
            claim(generation)
            sink.unavailable("语音输入已中断")
            return
        }
        runCatching(stopListening)
            .onSuccess { if (gate.isActive(generation)) sink.captureFinished() }
            .onFailure { if (claim(generation)) sink.unavailable("当前设备没有可用语音识别服务") }
    }

    fun cancel() {
        claim(gate.activeSessionId)
        runCatching(cancelRecognizer)
        sink.unavailable("语音输入已取消")
    }

    fun stopForLifecycle(message: String) {
        if (claim(gate.activeSessionId)) {
            runCatching(cancelRecognizer)
            sink.unavailable(message)
        }
    }

    fun destroy(releaseRecognizer: () -> Unit, canReport: () -> Boolean) {
        val hadActive = claim(gate.activeSessionId)
        runCatching(releaseRecognizer)
        if (hadActive && canReport()) sink.unavailable("语音输入已因界面重建或关闭而停止")
    }

    /** Whether [generation] is still the live one; stale listener callbacks are dropped. */
    fun isActive(generation: Long): Boolean = gate.isActive(generation)

    fun onError(generation: Long, message: String) {
        if (claim(generation)) sink.unavailable(message)
    }

    fun onResults(generation: Long, transcript: String?) {
        if (!claim(generation)) return
        if (transcript.isNullOrBlank()) sink.unavailable("未识别到语音") else sink.transcript(transcript)
    }

    private fun onDeadline(generation: Long) {
        // Re-check the generation: a queued deadline must not fire after another terminal won.
        if (!claim(generation)) return
        runCatching(cancelRecognizer)
        sink.unavailable("语音输入超时")
    }

    /** Claims the terminal transition for [generation]; true for exactly one caller. */
    private fun claim(generation: Long): Boolean {
        if (!gate.isActive(generation)) return false
        gate.clearIfActive(generation)
        watchdog?.cancel()
        watchdog = null
        return true
    }
}

/** Which platform recognizer composer voice input uses. */
internal enum class VoiceRecognizerRoute(val mayUseOnlineService: Boolean) {
    /** `SpeechRecognizer.createOnDeviceSpeechRecognizer` (API 31+); audio stays on the device. */
    OnDevice(mayUseOnlineService = false),

    /**
     * Default system recognizer with `EXTRA_PREFER_OFFLINE=true`. The preference is a hint the
     * recognition service may ignore, so this route may use the system's online service.
     */
    SystemPreferOffline(mayUseOnlineService = true),

    Unavailable(mayUseOnlineService = false),
}

internal const val VOICE_INPUT_ONLINE_RECOGNITION_NOTE =
    "注意：本机没有可用的离线语音识别。Solin会请求系统优先离线识别，但系统语音识别服务仍可能使用其在线服务处理你的语音。"

/** Prefers the on-device recognizer, then the offline-preferring system recognizer. */
internal fun selectVoiceRecognizerRoute(
    sdkInt: Int,
    onDeviceRecognitionAvailable: Boolean,
    systemRecognitionAvailable: Boolean,
): VoiceRecognizerRoute =
    when {
        sdkInt >= Build.VERSION_CODES.S && onDeviceRecognitionAvailable -> VoiceRecognizerRoute.OnDevice
        systemRecognitionAvailable -> VoiceRecognizerRoute.SystemPreferOffline
        else -> VoiceRecognizerRoute.Unavailable
    }

/**
 * Recognizer intent extras. `EXTRA_PREFER_OFFLINE` is always requested; it is harmless for the
 * on-device recognizer and steers the system recognizer away from its online service.
 */
internal fun voiceRecognitionIntentExtras(): Map<String, Any> =
    linkedMapOf(
        RecognizerIntent.EXTRA_LANGUAGE_MODEL to RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        RecognizerIntent.EXTRA_PARTIAL_RESULTS to true,
        RecognizerIntent.EXTRA_MAX_RESULTS to 1,
        RecognizerIntent.EXTRA_PREFER_OFFLINE to true,
    )

/** Process-wide so a configuration change does not re-ask; cleared only by process death. */
@Volatile
private var onlineRecognitionAcknowledged: Boolean = false

internal class VoiceInputSessionGate {
    var activeSessionId: Long = 0L
        private set
    private var nextSessionId: Long = 0L

    fun startSession(): Long {
        activeSessionId = ++nextSessionId
        return activeSessionId
    }

    fun cancelActiveSession() {
        activeSessionId = 0L
    }

    fun clearIfActive(sessionId: Long) {
        if (isActive(sessionId)) {
            activeSessionId = 0L
        }
    }

    fun isActive(sessionId: Long): Boolean =
        sessionId != 0L && sessionId == activeSessionId
}

private fun Bundle.recognizedSpeechText(): String? =
    getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun Int.voiceInputErrorMessage(): String =
    when (this) {
        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到声音"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "未授权麦克风权限"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别服务忙碌"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> "语音识别网络不可用"

        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> "本机语音识别暂不支持当前语言"

        else -> "语音输入不可用"
    }
