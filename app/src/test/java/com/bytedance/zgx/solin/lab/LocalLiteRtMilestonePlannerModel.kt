package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.orchestration.collab.MilestonePlannerModel
import java.io.IOException

/** Devbox-only adapter for the production LiteRT files through the Ollama-compatible shim. */
class LocalLiteRtMilestonePlannerModel(
    private val client: OllamaClient,
    private val model: String
) : MilestonePlannerModel {
    var lastRawOutput: String? = null
        private set

    override fun complete(instruction: String, renderedRequest: String): String {
        val result = client.chat(
            model = model,
            messages = listOf(
                OllamaClient.Message(role = "system", content = instruction),
                OllamaClient.Message(role = "user", content = renderedRequest)
            ),
            options = LocalLiteRtMilestonePlannerModel.PLANNING_OPTIONS
        )
        if (result.error != null) throw IOException(result.error)
        lastRawOutput = result.text
        return result.text
    }

    internal companion object {
        val PLANNING_OPTIONS = OllamaClient.Options(numPredict = 1_024)
    }
}
