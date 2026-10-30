package com.bytedance.zgx.solin.runtime

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.MessageRole
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ExperimentalApi
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLocalEngineTest {
    private val request = SharedEngineOneShotRequest(
        modelPath = MODEL_PATH,
        systemInstruction = "你是手机动作规划器。",
        prompt = "打开设置",
    )

    @Test
    fun unavailableWithoutLoadedEngineOrForDifferentModel() {
        val factory = FakeFactory()
        val runtime = RealLiteRtRuntime(File("/tmp"), contextCompactor = null, engineFactory = factory)

        assertEquals(
            SharedEngineOneShotResult.Unavailable(SharedEngineUnavailableReason.NoEngineLoaded),
            runtime.generateOneShot(request),
        )
        runtime.load(MODEL_PATH, BackendChoice.GPU, emptyList(), GenerationParameters())
        assertEquals(
            SharedEngineOneShotResult.Unavailable(SharedEngineUnavailableReason.DifferentModel),
            runtime.generateOneShot(request.copy(modelPath = "/tmp/other.litertlm")),
        )
        runtime.close()
        assertEquals(
            SharedEngineOneShotResult.Unavailable(SharedEngineUnavailableReason.NoEngineLoaded),
            runtime.generateOneShot(request),
        )
    }

    @Test
    fun oneShotReusesLoadedEngineAndRestoresChatConversation() {
        val factory = FakeFactory(oneShotOutput = listOf("call:", "open_settings {}"))
        val runtime = RealLiteRtRuntime(File("/tmp"), contextCompactor = null, engineFactory = factory)
        val history = listOf(ChatMessage(MessageRole.User, "历史"))
        runtime.load(MODEL_PATH, BackendChoice.GPU, emptyList(), GenerationParameters())
        runtime.recreateConversationForSend(history, "当前", GenerationParameters(), maxInputTokens = 4_000)

        val result = runtime.generateOneShot(request)

        assertEquals(SharedEngineOneShotResult.Generated("call:open_settings {}"), result)
        assertEquals("only the chat load created an engine", 1, factory.engines.size)
        val specs = factory.engines.single().specs
        val oneShotSpec = specs[2]
        assertEquals("你是手机动作规划器。", oneShotSpec.systemInstruction)
        assertEquals(1, oneShotSpec.parameters.topK)
        assertEquals(0f, oneShotSpec.parameters.temperature)
        assertTrue(oneShotSpec.history.isEmpty())
        // The chat conversation is recreated from its last spec after the one-shot.
        assertEquals(specs[1], specs[3])
        assertNull(specs[1].systemInstruction)
        assertTrue(runtime.isLoaded)
        val conversations = factory.engines.single().conversations
        assertTrue(conversations.take(3).all { it.closed })
        assertTrue(!conversations[3].closed)
    }

    @Test
    fun oneShotDoesNotPreemptChatGenerationInFlight() = runTest {
        val release = CompletableDeferred<Unit>()
        val factory = FakeFactory(
            chatOutput = flow {
                emit("部分")
                release.await()
            },
        )
        val runtime = RealLiteRtRuntime(File("/tmp"), contextCompactor = null, engineFactory = factory)
        runtime.load(MODEL_PATH, BackendChoice.GPU, emptyList(), GenerationParameters())
        val firstChunk = CompletableDeferred<Unit>()
        val chat = launch {
            runtime.send("你好").collect { firstChunk.complete(Unit) }
        }
        firstChunk.await()

        assertEquals(
            SharedEngineOneShotResult.Unavailable(SharedEngineUnavailableReason.Busy),
            runtime.generateOneShot(request),
        )
        release.complete(Unit)
        chat.join()
        yield()
        assertTrue(runtime.generateOneShot(request) is SharedEngineOneShotResult.Generated)
    }

    @Test
    fun failedOneShotStillRestoresChatConversation() {
        val factory = FakeFactory(failOneShot = true)
        val runtime = RealLiteRtRuntime(File("/tmp"), contextCompactor = null, engineFactory = factory)
        runtime.load(MODEL_PATH, BackendChoice.GPU, emptyList(), GenerationParameters())

        val result = runtime.generateOneShot(request)

        assertEquals(SharedEngineUnavailableReason.Failed, (result as SharedEngineOneShotResult.Unavailable).reason)
        assertTrue(runtime.isLoaded)
    }

    private class FakeFactory(
        val oneShotOutput: List<String> = listOf("ok"),
        val chatOutput: Flow<String> = flowOf("chat"),
        val failOneShot: Boolean = false,
    ) : LiteRtEngineFactory {
        val engines = mutableListOf<FakeEngine>()

        override fun create(
            modelPath: String,
            backend: BackendChoice,
            cacheDir: File,
            capabilities: LocalModelRuntimeCapabilities,
        ): LiteRtEngineHandle = FakeEngine(this).also(engines::add)
    }

    private class FakeEngine(private val factory: FakeFactory) : LiteRtEngineHandle {
        val specs = mutableListOf<LiteRtConversationSpec>()
        val conversations = mutableListOf<FakeConversation>()

        override fun initialize() = Unit

        override fun createConversation(config: LiteRtConversationSpec): LiteRtConversationHandle {
            specs += config
            val isOneShot = config.systemInstruction != null
            if (isOneShot && factory.failOneShot) error("native failure")
            val output = if (isOneShot) flowOf(*factory.oneShotOutput.toTypedArray()) else factory.chatOutput
            return FakeConversation(output).also(conversations::add)
        }

        override fun close() = Unit
    }

    private class FakeConversation(private val output: Flow<String>) : LiteRtConversationHandle {
        var closed = false

        override fun sendMessageAsync(request: LocalModelRequest): Flow<String> = output

        @OptIn(ExperimentalApi::class)
        override fun benchmarkInfo(): BenchmarkInfo = error("not used")

        override fun cancelProcess() = Unit

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val MODEL_PATH = "/tmp/chat.litertlm"
    }
}
