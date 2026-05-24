package com.bytedance.zgx.gemmalocalqa.runtime

import com.bytedance.zgx.gemmalocalqa.BackendChoice
import com.bytedance.zgx.gemmalocalqa.ChatMessage
import com.bytedance.zgx.gemmalocalqa.GenerationParameters
import com.bytedance.zgx.gemmalocalqa.GenerationStats
import com.bytedance.zgx.gemmalocalqa.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeLiteRtRuntimeTest {
    @Test
    fun fakeRuntimeSupportsLoadRecreateStreamStopAndClose() = runBlocking {
        val runtime = FakeLiteRtRuntime(listOf("你", "好"))

        runtime.load(
            modelPath = "/tmp/model.litertlm",
            backend = BackendChoice.GPU,
            history = listOf(ChatMessage(MessageRole.User, "hi")),
            parameters = GenerationParameters(),
        )
        runtime.recreateConversation(
            history = listOf(ChatMessage(MessageRole.Assistant, "hello")),
            parameters = GenerationParameters(temperature = 0.3f),
        )
        val chunks = runtime.send("你好").toList()
        val stats = runtime.lastGenerationStats()
        runtime.stop()
        runtime.close()

        assertEquals(listOf("你", "好"), chunks)
        assertEquals("/tmp/model.litertlm", runtime.loadedModelPath)
        assertEquals(BackendChoice.GPU, runtime.loadedBackend)
        assertEquals(1, runtime.recreatedHistory.size)
        assertEquals(2, stats?.tokenCount)
        assertEquals(12.0, stats?.tokensPerSecond ?: 0.0, 0.01)
        assertTrue(runtime.stopCalled)
        assertFalse(runtime.isLoaded)
    }
}

private class FakeLiteRtRuntime(
    private val chunks: List<String>,
) : LiteRtRuntime {
    var loadedModelPath: String? = null
        private set
    var loadedBackend: BackendChoice? = null
        private set
    var recreatedHistory: List<ChatMessage> = emptyList()
        private set
    var loadedParameters: GenerationParameters? = null
        private set
    var stopCalled: Boolean = false
        private set

    override var isLoaded: Boolean = false
        private set

    override fun load(
        modelPath: String,
        backend: BackendChoice,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    ) {
        loadedModelPath = modelPath
        loadedBackend = backend
        recreatedHistory = history
        loadedParameters = parameters
        isLoaded = true
    }

    override fun recreateConversation(
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    ) {
        check(isLoaded)
        recreatedHistory = history
        loadedParameters = parameters
    }

    override fun send(prompt: String): Flow<String> {
        check(isLoaded)
        return chunks.asFlow()
    }

    override fun lastGenerationStats(): GenerationStats? =
        GenerationStats(
            tokenCount = chunks.size,
            tokensPerSecond = 12.0,
        )

    override fun stop() {
        stopCalled = true
    }

    override fun close() {
        isLoaded = false
    }
}
