package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.orchestration.collab.CloudPlannerRequest
import com.bytedance.zgx.solin.runtime.RemoteChatRuntime
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteMilestonePlannerModelTest {
    @Test
    fun sendsExactRequestAndSeparatePlannerInstruction() {
        val runtime = RecordingPlannerRuntime(SEQUENTIAL_PLAN)
        val adapter = RemoteMilestonePlannerModel(runtime, CONFIG)
        val rendered = CloudPlannerRequest.render(REQUEST).text

        val result = adapter.complete("planner instruction", rendered)

        assertEquals(SEQUENTIAL_PLAN, result)
        assertEquals(rendered, runtime.prompt)
        assertEquals(listOf("planner instruction"), runtime.history.map(ChatMessage::text))
    }

    @Test
    fun transportFailureStopsRuntimeAndPropagates() {
        val runtime = RecordingPlannerRuntime(IOException("synthetic transport failure"))
        val adapter = RemoteMilestonePlannerModel(runtime, CONFIG)

        try {
            adapter.complete("planner instruction", CloudPlannerRequest.render(REQUEST).text)
            fail("expected transport failure")
        } catch (_: IOException) {
        }

        assertTrue(runtime.stopped)
    }

    private class RecordingPlannerRuntime(private val result: Any) : RemoteChatRuntime {
        var prompt = ""
        var history = emptyList<ChatMessage>()
        var stopped = false

        override fun send(
            prompt: String,
            history: List<ChatMessage>,
            parameters: GenerationParameters,
            config: RemoteModelConfig,
            imageAttachments: List<com.bytedance.zgx.solin.ChatImageAttachment>
        ): Flow<String> {
            this.prompt = prompt
            this.history = history
            return when (result) {
                is String -> flowOf(result)
                is Throwable -> flow { throw result }
                else -> error("unexpected fixture")
            }
        }

        override fun stop() {
            stopped = true
        }
    }

    private companion object {
        const val REQUEST = "在淘宝搜索牛奶"
        const val SEQUENTIAL_PLAN =
            """{"version":1,"milestones":[{"intent":"search_in_app","app":"com.taobao.taobao","query":"牛奶"}]}"""
        val CONFIG = RemoteModelConfig(baseUrl = "https://api.example.com/v1", modelName = "planner")
    }
}
