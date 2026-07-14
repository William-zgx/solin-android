package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.VoiceCaptureUiState
import com.bytedance.zgx.solin.VoiceInputDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns voice-input capture state and transcript draft handoff into the composer.
 *
 * Extracted from [com.bytedance.zgx.solin.SolinViewModel] (Wave 6 Track C6b). Draft IDs come from
 * [ChatController.allocateVoiceInputDraftId] so shared-input and voice drafts stay on one counter.
 */
class VoiceInputController(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val allocateVoiceInputDraftId: () -> Long,
) {
    fun acceptVoiceTranscript(transcript: String) {
        val cleaned = transcript
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(SolinConstants.Ui.MAX_VOICE_TRANSCRIPT_CHARS)
        if (cleaned.isBlank()) {
            uiState.update { it.copy(voiceCapture = VoiceCaptureUiState()) }
            return
        }
        uiState.update {
            it.copy(
                voiceInputDraft = VoiceInputDraft(
                    id = allocateVoiceInputDraftId(),
                    text = cleaned,
                ),
                voiceCapture = VoiceCaptureUiState(),
                statusText = "语音已转写",
            )
        }
    }

    fun consumeVoiceInputDraft(draftId: Long) {
        uiState.update {
            if (it.voiceInputDraft?.id == draftId) {
                it.copy(voiceInputDraft = null)
            } else {
                it
            }
        }
    }

    fun reportVoiceInputUnavailable(message: String = "语音输入不可用") {
        uiState.update {
            it.copy(
                voiceCapture = VoiceCaptureUiState(),
                statusText = message,
            )
        }
    }

    fun startVoiceInputCapture() {
        uiState.update {
            it.copy(
                voiceCapture = VoiceCaptureUiState(
                    isListening = true,
                    isTranscribing = false,
                    level = 0.18f,
                    waveformLevels = seedVoiceWaveformLevels(level = 0.18f),
                ),
                statusText = "正在收音",
            )
        }
    }

    fun updateVoiceInputLevel(rmsDb: Float) {
        val normalizedLevel = rmsDb.normalizedVoiceInputLevel()
        uiState.update {
            if (!it.voiceCapture.isListening) {
                it
            } else {
                val nextFrame = it.voiceCapture.waveformFrame + 1
                it.copy(
                    voiceCapture = it.voiceCapture.copy(
                        level = normalizedLevel,
                        waveformFrame = nextFrame,
                        waveformLevels = it.voiceCapture.waveformLevels.nextVoiceWaveformLevels(
                            level = normalizedLevel,
                            frame = nextFrame,
                        ),
                    ),
                )
            }
        }
    }

    fun updateVoiceInputPartialTranscript(transcript: String) {
        val cleaned = transcript
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(SolinConstants.Ui.MAX_VOICE_TRANSCRIPT_CHARS)
        uiState.update {
            if (!it.voiceCapture.isActive) {
                it
            } else {
                it.copy(voiceCapture = it.voiceCapture.copy(partialText = cleaned))
            }
        }
    }

    fun finishVoiceInputCapture(message: String = "正在转写") {
        uiState.update {
            if (!it.voiceCapture.isActive) {
                it
            } else {
                it.copy(
                    voiceCapture = it.voiceCapture.copy(
                        isListening = false,
                        isTranscribing = true,
                        level = 0.12f,
                    ),
                    statusText = message,
                )
            }
        }
    }
}
