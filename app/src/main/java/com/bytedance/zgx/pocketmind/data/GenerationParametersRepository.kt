package com.bytedance.zgx.pocketmind.data

import android.content.Context
import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.GenerationParameters
import kotlin.math.roundToInt

class GenerationParametersRepository(
    private val settingsStore: SettingsStore,
) {
    constructor(context: Context) : this(PreferenceSettingsStore(context))

    fun load(): GenerationParameters =
        settingsStore.loadGenerationParameters()

    fun save(parameters: GenerationParameters): GenerationParameters =
        settingsStore.saveGenerationParameters(parameters)

    fun reset(): GenerationParameters =
        save(GenerationParameters())

    fun loadBackend(): BackendChoice =
        settingsStore.loadBackend()

    fun saveBackend(backend: BackendChoice) {
        settingsStore.saveBackend(backend)
    }
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
