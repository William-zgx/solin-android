package com.bytedance.zgx.solin.runtime

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.orchestration.CompactionResult
import com.bytedance.zgx.solin.orchestration.ContextCompactor
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ExperimentalApi
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RealLiteRtRuntimeLifecycleTest {
    @Test
    fun loadFailureWhileCreatingConversationLeavesNoClosedEngineBehind() {
        val factory = FakeEngineFactory()
        factory.nextConversationFailure = IllegalStateException("conversation failed")
        val runtime = RealLiteRtRuntime(File("/tmp/cache"), null, factory)

        val failure = runCatching { load(runtime) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        val engine = factory.engines.single()
        assertEquals(1, engine.closeCount)
        assertFalse(runtime.isLoaded)

        // A later recreate must not reach the closed native engine.
        val recreateFailure = runCatching {
            runtime.recreateConversation(emptyList(), GenerationParameters())
        }.exceptionOrNull()
        assertNotNull("recreate after a failed load must fail fast", recreateFailure)
        assertEquals(0, engine.createConversationCallsAfterClose)

        // And close() must not close the native engine a second time.
        runtime.close()
        assertEquals(1, engine.closeCount)
    }

    @Test
    fun recreateFailureDoesNotKeepClosedConversation() {
        val factory = FakeEngineFactory()
        val runtime = RealLiteRtRuntime(File("/tmp/cache"), null, factory)
        load(runtime)
        val engine = factory.engines.single()
        val original = engine.conversations.single()
        assertTrue(runtime.isLoaded)

        factory.nextConversationFailure = IllegalStateException("recreate failed")
        val failure = runCatching {
            runtime.recreateConversation(emptyList(), GenerationParameters())
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, original.closeCount)
        assertFalse("runtime must not report loaded with a closed conversation", runtime.isLoaded)
        val sendFailure = runCatching { runtime.send("hi") }.exceptionOrNull()
        assertNotNull("send must not reach the closed conversation", sendFailure)
        assertEquals(0, original.useAfterClose)

        runtime.close()
        assertEquals("closed conversation must not be closed twice", 1, original.closeCount)
        assertEquals(1, engine.closeCount)
    }

    @Test
    fun recreateAfterFailedRecreateCanRecoverWithSameEngine() {
        val factory = FakeEngineFactory()
        val runtime = RealLiteRtRuntime(File("/tmp/cache"), null, factory)
        load(runtime)
        factory.nextConversationFailure = IllegalStateException("recreate failed")
        runCatching { runtime.recreateConversation(emptyList(), GenerationParameters()) }

        runtime.recreateConversation(emptyList(), GenerationParameters())

        assertTrue(runtime.isLoaded)
        assertEquals(1, factory.engines.size)
        assertEquals(0, factory.engines.single().closeCount)
    }

    @Test
    fun compactionCancellationIsRethrownInsteadOfSwallowed() {
        val factory = FakeEngineFactory()
        val compactor = object : ContextCompactor {
            override suspend fun compact(
                messages: List<ChatMessage>,
                tokenBudget: Int,
                estimatedTokens: (List<ChatMessage>) -> Int,
            ): CompactionResult = throw CancellationException("send cancelled")
        }
        val runtime = RealLiteRtRuntime(File("/tmp/cache"), compactor, factory)
        load(runtime)

        try {
            runtime.recreateConversationForSend(
                history = listOf(ChatMessage(role = MessageRole.User, text = "earlier turn")),
                prompt = "next",
                parameters = GenerationParameters(),
                maxInputTokens = 4096,
            )
            fail("cancellation from the compactor must propagate")
        } catch (expected: CancellationException) {
            assertEquals("send cancelled", expected.message)
        }
    }

    private fun load(runtime: RealLiteRtRuntime) {
        runtime.load(
            modelPath = "/tmp/model.litertlm",
            backend = BackendChoice.CPU,
            history = emptyList(),
            parameters = GenerationParameters(),
        )
    }

    private class FakeEngineFactory : LiteRtEngineFactory {
        val engines = mutableListOf<FakeEngine>()
        var nextConversationFailure: Throwable? = null

        override fun create(
            modelPath: String,
            backend: BackendChoice,
            cacheDir: File,
            capabilities: LocalModelRuntimeCapabilities?,
        ): LiteRtEngineHandle = FakeEngine(this).also(engines::add)
    }

    private class FakeEngine(private val factory: FakeEngineFactory) : LiteRtEngineHandle {
        val conversations = mutableListOf<FakeConversation>()
        var closeCount = 0
        var createConversationCallsAfterClose = 0

        override fun initialize() = Unit

        override fun createConversation(config: LiteRtConversationSpec): LiteRtConversationHandle {
            if (closeCount > 0) createConversationCallsAfterClose += 1
            factory.nextConversationFailure?.let { failure ->
                factory.nextConversationFailure = null
                throw failure
            }
            return FakeConversation().also(conversations::add)
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeConversation : LiteRtConversationHandle {
        var closeCount = 0
        var useAfterClose = 0

        override fun sendMessageAsync(request: LocalModelRequest): Flow<String> {
            if (closeCount > 0) useAfterClose += 1
            return emptyFlow()
        }

        @OptIn(ExperimentalApi::class)
        override fun benchmarkInfo(): BenchmarkInfo = error("not used")

        override fun cancelProcess() {
            if (closeCount > 0) useAfterClose += 1
        }

        override fun close() {
            closeCount += 1
        }
    }
}
