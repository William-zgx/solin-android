package com.bytedance.zgx.solin

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Owns the platform [SpeechRecognizer] for composer voice input and forwards its callbacks into
 * [SolinViewModel]. Each start opens a new [VoiceInputSessionGate] session so late callbacks from a
 * cancelled or replaced session are dropped. Destroys the recognizer with the activity.
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
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            viewModel().reportVoiceInputUnavailable("当前设备没有可用语音识别服务")
            return
        }
        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(activity).also {
            speechRecognizer = it
        }
        val sessionId = sessions.startSession()
        recognizer.setRecognitionListener(recognitionListener(sessionId))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
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

    override fun onDestroy(owner: LifecycleOwner) {
        val hadActiveVoiceSession = sessions.activeSessionId != 0L
        sessions.cancelActiveSession()
        speechRecognizer?.destroy()
        speechRecognizer = null
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

        else -> "语音输入不可用"
    }
