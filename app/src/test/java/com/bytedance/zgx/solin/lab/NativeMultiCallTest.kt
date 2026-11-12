package com.bytedance.zgx.solin.lab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeMultiCallTest {
    @Test
    fun answerFollowedByExecutableCallDoesNotBecomeNoTool() {
        val result = chatResult(
            OllamaClient.ToolCall(LOCAL_MODEL_NO_TOOL_CHOICE, JSONObject()),
            OllamaClient.ToolCall("web_search", JSONObject().put("query", "x"))
        )

        val (answer, kind) = NativeToolsSuiteTest().answerFromNative(result, noToolChoice = true)

        assertEquals("web_search", answer.tool)
        assertEquals("multi_call", kind)
    }

    @Test
    fun answerOnlyReturnsDeliberateNoToolChoice() {
        val result = chatResult(OllamaClient.ToolCall(LOCAL_MODEL_NO_TOOL_CHOICE, JSONObject()))

        val (answer, kind) = NativeToolsSuiteTest().answerFromNative(result, noToolChoice = true)

        assertEquals(null, answer.tool)
        assertEquals("no_tool_choice", kind)
    }

    private fun chatResult(vararg calls: OllamaClient.ToolCall): OllamaClient.ChatResult = OllamaClient.ChatResult(
        text = "",
        toolCalls = calls.toList(),
        wallMillis = 0,
        totalDurationNs = 0,
        loadDurationNs = 0,
        promptEvalCount = 0,
        promptEvalCachedCount = 0,
        promptEvalDurationNs = 0,
        evalCount = 0,
        evalDurationNs = 0,
        doneReason = null
    )
}
