package com.bytedance.zgx.solin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.bytedance.zgx.solin.data.ModelRepository
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Verifies that a locally installed LiteRT model can conduct interactive dialogue
 * on the real device. Uses the active chat model (E2B or E4B) if available.
 */
class LocalModelChatDialogueDeviceTest {
    private val targetContext: Context = ApplicationProvider.getApplicationContext()

    private fun findChatModelPath(): String? =
        ModelRepository(targetContext).currentState()
            .installedModels
            .firstOrNull { model ->
                val recommended = model.recommendedModelId
                    ?.let(ModelCatalog::recommendedModelOrNull)
                model.verificationStatus == com.bytedance.zgx.solin.data.ModelVerificationStatus.VerifiedRecommended &&
                    recommended != null &&
                    ModelCatalog.profileFor(recommended).supportsChatGeneration
            }
            ?.path

    @Test
    fun installedChatModelRespondsToSimpleDialogue() {
        val chatModelPath = findChatModelPath()
        assumeTrue(
            "Skipping chat dialogue test because no verified local model is available.",
            chatModelPath != null,
        )
        val modelFile = File(requireNotNull(chatModelPath))
        assertTrue("Model file must exist: ${modelFile.absolutePath}", modelFile.isFile)
        assertTrue("Model file must be readable: ${modelFile.absolutePath}", modelFile.canRead())

        val engine = Engine(
            EngineConfig(
                modelPath = requireNotNull(chatModelPath),
                backend = Backend.CPU(),
                cacheDir = targetContext.cacheDir.absolutePath,
            ),
        )
        engine.initialize()
        try {
            val conversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "你是 Solin，一个运行在 Android 设备上的本地 AI 助手。用中文简洁回答。",
                    ),
                    samplerConfig = SamplerConfig(
                        topK = 64,
                        topP = 0.95,
                        temperature = 0.7,
                    ),
                ),
            )
            try {
                // Test 1: Simple greeting
                val greetingResponse = sendMessage(conversation, "你好，请用一句话介绍你自己。")
                assertNotNull("Greeting should produce a response", greetingResponse)
                assertTrue(
                    "Greeting response should be non-empty. Got: '$greetingResponse'",
                    greetingResponse.isNotBlank(),
                )

                // Test 2: Multi-turn dialogue (context should be preserved)
                val followUpResponse = sendMessage(conversation, "我刚才问了你什么？")
                assertNotNull("Follow-up should produce a response", followUpResponse)
                assertTrue(
                    "Follow-up response should be non-empty. Got: '$followUpResponse'",
                    followUpResponse.isNotBlank(),
                )
                assertTrue(
                    "Follow-up should reference the earlier self-introduction question. Got: '$followUpResponse'",
                    followUpResponse.lowercase(Locale.ROOT).let { response ->
                        listOf("介绍", "自己", "你是谁", "who", "introduce").any(response::contains)
                    },
                )
            } finally {
                conversation.close()
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun installedChatModelHandlesToolLikeQuery() {
        val chatModelPath = findChatModelPath()
        assumeTrue(
            "Skipping tool-query test because no verified local model is available.",
            chatModelPath != null,
        )

        val engine = Engine(
            EngineConfig(
                modelPath = requireNotNull(chatModelPath),
                backend = Backend.CPU(),
                cacheDir = targetContext.cacheDir.absolutePath,
            ),
        )
        engine.initialize()
        try {
            val conversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "你是一个手机助手。当用户要求操作手机时，简洁说明你会怎么做。",
                    ),
                    samplerConfig = SamplerConfig(
                        topK = 32,
                        topP = 0.9,
                        temperature = 0.5,
                    ),
                ),
            )
            try {
                val response = sendMessage(conversation, "帮我打开设置")
                assertNotNull("Tool query should produce a response", response)
                assertTrue(
                    "Tool query response should be non-empty. Got: '$response'",
                    response.isNotBlank(),
                )
            } finally {
                conversation.close()
            }
        } finally {
            engine.close()
        }
    }

    private fun sendMessage(
        conversation: com.google.ai.edge.litertlm.Conversation,
        text: String,
    ): String {
        val output = StringBuilder()
        runBlocking {
            conversation.sendMessageAsync(text).collect { message ->
                output.append(message.textContent())
            }
        }
        return output.toString()
    }
}

private fun com.google.ai.edge.litertlm.Message.textContent(): String =
    contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString(separator = "") { it.text }
