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
import androidx.lifecycle.LifecycleOwner
import com.bytedance.zgx.solin.ui.VOICE_INPUT_PERMISSION_DISCLOSURE_BODY
import com.bytedance.zgx.solin.ui.VOICE_INPUT_PERMISSION_DISCLOSURE_TITLE

/**
 * Owns the platform [SpeechRecognizer] for composer voice input and forwards its callbacks into
 * [SolinViewModel]. Each start opens a new [VoiceInputSessionGate] session so late callbacks from a
 * cancelled or replaced session are dropped. Destroys the recognizer with the activity.
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
    private val sessions = VoiceInputSessionGate()
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
        val sessionId = sessions.startSession()
        recognizer.setRecognitionListener(recognitionListener(sessionId))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        voiceRecognitionIntentExtras().forEach { (key, value) ->
            when (value) {
                is Boolean -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
            }
        }
        viewModel().startVoiceInputCapture()
        runCatching { recognizer.startListening(intent) }
            .onFailure {
                if (sessions.isActive(sessionId)) {
                    sessions.clearIfActive(sessionId)
                    viewModel().reportVoiceInputUnavailable("语音输入启动失败")
                }
            }
    }

    fun finish() {
        val sessionId = sessions.activeSessionId
        val recognizer = speechRecognizer
        if (recognizer == null || !sessions.isActive(sessionId)) {
            sessions.clearIfActive(sessionId)
            viewModel().reportVoiceInputUnavailable("语音输入已中断")
            return
        }
        runCatching { recognizer.stopListening() }
            .onSuccess {
                if (sessions.isActive(sessionId)) {
                    viewModel().finishVoiceInputCapture()
                }
            }
            .onFailure {
                if (sessions.isActive(sessionId)) {
                    sessions.clearIfActive(sessionId)
                    viewModel().reportVoiceInputUnavailable("当前设备没有可用语音识别服务")
                }
            }
    }

    fun cancel() {
        sessions.cancelActiveSession()
        runCatching { speechRecognizer?.cancel() }
        viewModel().reportVoiceInputUnavailable("语音输入已取消")
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

    override fun onDestroy(owner: LifecycleOwner) {
        val hadActiveVoiceSession = sessions.activeSessionId != 0L
        sessions.cancelActiveSession()
        speechRecognizer?.destroy()
        speechRecognizer = null
        speechRecognizerRoute = null
        if (hadActiveVoiceSession && isViewModelReady()) {
            viewModel().reportVoiceInputUnavailable("语音输入已因界面重建或关闭而停止")
        }
    }

    private fun recognitionListener(sessionId: Long): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (sessions.isActive(sessionId)) {
                    viewModel().startVoiceInputCapture()
                }
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                if (sessions.isActive(sessionId)) {
                    viewModel().updateVoiceInputLevel(rmsDb = rmsdB)
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (sessions.isActive(sessionId)) {
                    viewModel().finishVoiceInputCapture()
                }
            }

            override fun onError(error: Int) {
                if (!sessions.isActive(sessionId)) return
                sessions.clearIfActive(sessionId)
                viewModel().reportVoiceInputUnavailable(error.voiceInputErrorMessage())
            }

            override fun onResults(results: Bundle?) {
                if (!sessions.isActive(sessionId)) return
                sessions.clearIfActive(sessionId)
                val transcript = results?.recognizedSpeechText()
                if (transcript.isNullOrBlank()) {
                    viewModel().reportVoiceInputUnavailable("未识别到语音")
                } else {
                    viewModel().acceptVoiceTranscript(transcript)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (sessions.isActive(sessionId)) {
                    partialResults?.recognizedSpeechText()?.let(viewModel()::updateVoiceInputPartialTranscript)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
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
