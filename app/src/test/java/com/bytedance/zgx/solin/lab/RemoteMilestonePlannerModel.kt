package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.orchestration.collab.MilestonePlannerModel
import com.bytedance.zgx.solin.runtime.RemoteChatRuntime
import kotlinx.coroutines.runBlocking

/** Test-only adapter. It keeps the exact rendered request as the current user prompt. */
class RemoteMilestonePlannerModel(
    private val runtime: RemoteChatRuntime,
    private val config: RemoteModelConfig,
    private val parameters: GenerationParameters = GenerationParameters(temperature = 0f, topP = 0.1f, topK = 1)
) : MilestonePlannerModel {
    override fun complete(instruction: String, renderedRequest: String): String = runBlocking {
        val output = StringBuilder()
        try {
            runtime.send(
                prompt = renderedRequest,
                history = listOf(
                    ChatMessage(
                        role = MessageRole.User,
                        text = instruction,
                        privacy = MessagePrivacy.RemoteEligible
                    )
                ),
                parameters = parameters,
                config = config
            ).collect { chunk -> output.append(chunk) }
        } catch (error: Throwable) {
            runtime.stop()
            throw error
        }
        output.toString()
    }
}
