package com.bytedance.zgx.solin

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            .filter { model ->
                val recommended = model.recommendedModelId
                    ?.let(ModelCatalog::recommendedModelOrNull)
                model.verificationStatus == com.bytedance.zgx.solin.data.ModelVerificationStatus.VerifiedRecommended &&
                    recommended != null &&
                    ModelCatalog.profileFor(recommended).supportsChatGeneration
            }
            .minByOrNull { model -> model.fileBytes }
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
        Log.i(TAG, "Using chat model ${modelFile.name} bytes=${modelFile.length()}")

        Log.i(TAG, "Initializing chat engine")
        val engine = Engine(
            EngineConfig(
                modelPath = requireNotNull(chatModelPath),
                backend = Backend.CPU(),
                cacheDir = targetContext.cacheDir.absolutePath,
            ),
        )
        engine.initialize()
        Log.i(TAG, "Chat engine initialized")
        try {
            val conversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "你是 Solin，一个运行在 Android 设备上的本地 AI 助手。用中文简洁回答。",
                    ),
                    samplerConfig = SamplerConfig(
                        topK = 16,
                        topP = 0.8,
                        temperature = 0.2,
                        seed = 1,
                    ),
                ),
            )
            try {
                // Test 1: Simple greeting
                val greetingResponse = sendMessage(conversation, "用不超过10个字介绍你自己。")
                assertNotNull("Greeting should produce a response", greetingResponse)
                assertTrue(
                    "Greeting response should be non-empty. Got: '$greetingResponse'",
                    greetingResponse.isNotBlank(),
                )

                // Test 2: Multi-turn dialogue (context should be preserved)
                val followUpResponse = sendMessage(conversation, "刚才的问题主题？八字内回答。")
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
        Log.i(TAG, "Initializing tool-query engine")
        engine.initialize()
        Log.i(TAG, "Tool-query engine initialized")
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
                        seed = 1,
                    ),
                ),
            )
            try {
                val response = sendMessage(conversation, "打开设置")
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
        Log.i(TAG, "sendMessage start: ${text.take(40)}")
        try {
            runBlocking {
                withTimeout(RESPONSE_TIMEOUT_MILLIS) {
                    conversation.sendMessageAsync(text).collect { message ->
                        output.append(message.textContent())
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            runCatching { conversation.cancelProcess() }
            Log.e(TAG, "sendMessage timeout after ${RESPONSE_TIMEOUT_MILLIS}ms: ${text.take(40)}")
            throw AssertionError(
                "Local model response timed out after ${RESPONSE_TIMEOUT_MILLIS}ms for prompt: '${text.take(40)}'",
                timeout,
            )
        }
        Log.i(TAG, "sendMessage finished chars=${output.length}: ${text.take(40)}")
        return output.toString()
    }

    private companion object {
        const val TAG = "LocalModelChatTest"
        const val RESPONSE_TIMEOUT_MILLIS = 240_000L
    }
}

private fun com.google.ai.edge.litertlm.Message.textContent(): String =
    contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString(separator = "") { it.text }
