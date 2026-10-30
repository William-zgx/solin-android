package com.bytedance.zgx.solin.runtime

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.GenerationStats
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.testing.FakeLiteRtRuntime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FakeLiteRtRuntimeTest {
    @Test
    fun fakeRuntimeSupportsLoadRecreateStreamStopAndClose() = runBlocking {
        val runtime = FakeLiteRtRuntime(
            localChunks = listOf("你", "好"),
            generationStats = GenerationStats(tokenCount = 2, tokensPerSecond = 12.0),
        )

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
        assertEquals(listOf(BackendChoice.GPU), runtime.loadCalls)
        assertEquals(1, runtime.recreatedHistories.single().size)
        assertEquals(0.3f, runtime.loadedParameters?.temperature)
        assertEquals(2, stats?.tokenCount)
        assertEquals(12.0, stats?.tokensPerSecond ?: 0.0, 0.01)
        assertEquals(1, runtime.stopCallCount)
        assertFalse(runtime.isLoaded)
    }
}
