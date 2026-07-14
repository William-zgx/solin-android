package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.SolinConstants

private val VOICE_WAVEFORM_MULTIPLIERS =
    listOf(0.42f, 0.74f, 1f, 0.56f, 0.88f, 0.63f, 0.95f, 0.5f, 0.8f)

internal fun Float.normalizedVoiceInputLevel(): Float =
    ((this + 2f) / 12f).coerceIn(0.08f, 1f)

internal fun seedVoiceWaveformLevels(level: Float): List<Float> =
    List(SolinConstants.Ui.VOICE_WAVEFORM_SAMPLE_COUNT) { frame ->
        voiceWaveformSample(level = level, frame = frame)
    }

internal fun List<Float>.nextVoiceWaveformLevels(level: Float, frame: Int): List<Float> {
    val nextSample = voiceWaveformSample(level = level, frame = frame)
    val samples = (this + nextSample).takeLast(SolinConstants.Ui.VOICE_WAVEFORM_SAMPLE_COUNT)
    return if (samples.size == SolinConstants.Ui.VOICE_WAVEFORM_SAMPLE_COUNT) {
        samples
    } else {
        seedVoiceWaveformLevels(level).take(SolinConstants.Ui.VOICE_WAVEFORM_SAMPLE_COUNT - samples.size) + samples
    }
}

internal fun voiceWaveformSample(level: Float, frame: Int): Float {
    val index = frame.floorMod(VOICE_WAVEFORM_MULTIPLIERS.size)
    return (level * VOICE_WAVEFORM_MULTIPLIERS[index]).coerceIn(0.08f, 1f)
}

internal fun Int.floorMod(divisor: Int): Int =
    ((this % divisor) + divisor) % divisor
