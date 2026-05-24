package com.bytedance.zgx.gemmalocalqa.data

import android.content.Context
import com.bytedance.zgx.gemmalocalqa.GenerationParameters
import kotlin.math.roundToInt

private const val PREFS_NAME = "gemma_local_qa"
private const val PREF_TEMPERATURE = "generation_temperature"
private const val PREF_TOP_P = "generation_top_p"
private const val PREF_TOP_K = "generation_top_k"

class GenerationParametersRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): GenerationParameters =
        GenerationParameters(
            temperature = prefs.getFloat(PREF_TEMPERATURE, GenerationParameters.DEFAULT_TEMPERATURE)
                .coerceIn(GenerationParameters.MIN_TEMPERATURE, GenerationParameters.MAX_TEMPERATURE),
            topP = prefs.getFloat(PREF_TOP_P, GenerationParameters.DEFAULT_TOP_P)
                .coerceIn(GenerationParameters.MIN_TOP_P, GenerationParameters.MAX_TOP_P),
            topK = prefs.getInt(PREF_TOP_K, GenerationParameters.DEFAULT_TOP_K)
                .coerceIn(GenerationParameters.MIN_TOP_K, GenerationParameters.MAX_TOP_K),
        )

    fun save(parameters: GenerationParameters): GenerationParameters {
        val normalized = parameters.normalized()
        prefs.edit()
            .putFloat(PREF_TEMPERATURE, normalized.temperature)
            .putFloat(PREF_TOP_P, normalized.topP)
            .putInt(PREF_TOP_K, normalized.topK)
            .apply()
        return normalized
    }

    fun reset(): GenerationParameters =
        save(GenerationParameters())
}

fun GenerationParameters.normalized(): GenerationParameters =
    GenerationParameters(
        temperature = temperature
            .roundToStep(0.05f)
            .coerceIn(GenerationParameters.MIN_TEMPERATURE, GenerationParameters.MAX_TEMPERATURE),
        topP = topP
            .roundToStep(0.01f)
            .coerceIn(GenerationParameters.MIN_TOP_P, GenerationParameters.MAX_TOP_P),
        topK = topK.coerceIn(GenerationParameters.MIN_TOP_K, GenerationParameters.MAX_TOP_K),
    )

private fun Float.roundToStep(step: Float): Float =
    (this / step).roundToInt() * step
