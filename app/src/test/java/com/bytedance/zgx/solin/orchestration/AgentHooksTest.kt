package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHooksTest {

    private fun beforeCtx(toolName: String = "read_calendar") = BeforeToolCallContext(
        runId = "run-1",
        toolCallId = "tc-1",
        toolName = toolName,
        args = emptyMap(),
    )

    private fun afterCtx(toolName: String = "read_calendar", status: ToolStatus = ToolStatus.Succeeded) = AfterToolCallContext(
        runId = "run-1",
        toolCallId = "tc-1",
        toolName = toolName,
        result = ToolResult(
            requestId = "tc-1",
            status = status,
            summary = "ok",
        ),
        durationMs = 42L,
    )

    private fun turnCtx() = TurnContext(
        runId = "run-1",
        turnIndex = 1,
        messageCount = 3,
        pendingToolCalls = 0,
    )

    @Test
    fun noOpHooksReturnDefaults() = runTest {
        val hooks: AgentHooks = NoOpAgentHooks

        assertEquals(BeforeToolCallResult.Proceed, hooks.beforeToolCall(beforeCtx()))
        assertEquals(AfterToolCallResult.Keep, hooks.afterToolCall(afterCtx()))

        val messages = listOf(ChatMessage(role = MessageRole.User, text = "hi"))
        assertSame(messages, hooks.transformContext(messages))

        assertNull(hooks.prepareNextTurn(turnCtx()))
        assertFalse(hooks.shouldStopAfterTurn(turnCtx()))
        assertTrue(hooks.getSteeringMessages().isEmpty())
    }

    @Test
    fun blockingHookReturnsBlocked() = runTest {
        val hooks = object : AgentHooks {
            override suspend fun beforeToolCall(ctx: BeforeToolCallContext): BeforeToolCallResult =
                BeforeToolCallResult.Blocked(reason = "policy denies ${ctx.toolName}")
        }

        val result = hooks.beforeToolCall(beforeCtx(toolName = "send_sms"))
        assertTrue(result is BeforeToolCallResult.Blocked)
        assertEquals("policy denies send_sms", (result as BeforeToolCallResult.Blocked).reason)
    }

    @Test
    fun replaceContentHookReturnsReplacement() = runTest {
        val hooks = object : AgentHooks {
            override suspend fun afterToolCall(ctx: AfterToolCallContext): AfterToolCallResult =
                AfterToolCallResult.ReplaceContent(
                    content = "redacted:${ctx.result.summary}",
                    isError = false,
                )
        }

        val result = hooks.afterToolCall(afterCtx())
        assertTrue(result is AfterToolCallResult.ReplaceContent)
        val replaced = result as AfterToolCallResult.ReplaceContent
        assertEquals("redacted:ok", replaced.content)
        assertFalse(replaced.isError)
    }
}
