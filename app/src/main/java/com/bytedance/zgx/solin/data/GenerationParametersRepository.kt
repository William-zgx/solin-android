package com.bytedance.zgx.solin.data

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.GenerationParameters
import kotlin.math.roundToInt

class GenerationParametersRepository(
    private val settingsStore: SettingsStore,
) : GenerationParametersStore {
    override fun load(): GenerationParameters =
        settingsStore.loadGenerationParameters()

    override fun save(parameters: GenerationParameters): GenerationParameters =
        settingsStore.saveGenerationParameters(parameters)

    override fun reset(): GenerationParameters =
        save(GenerationParameters())

    override fun loadBackend(): BackendChoice =
        settingsStore.loadBackend()

    override fun saveBackend(backend: BackendChoice) {
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
