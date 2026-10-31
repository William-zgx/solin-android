package com.bytedance.zgx.solin.debug

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.orchestration.AgentRun
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.AgentStep
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunReportTest {
    @Test
    fun reportsToolCallsStopsAndConfirmationsWithoutPromptOrArguments() {
        val tap = ToolRequest(id = "tap", toolName = "ui_tap", arguments = mapOf("target" to "SECRET-TARGET"))
        val type = ToolRequest(id = "type", toolName = "ui_type_text", arguments = mapOf("text" to "SECRET-TEXT"))
        val run = AgentRun(
            id = "run-1",
            input = "SECRET-PROMPT",
            state = AgentRunState.Completed,
            createdAtMillis = 1_000L,
            updatedAtMillis = 4_500L,
        )
        val steps = listOf(
            AgentStep.ToolRequested(type, draft(type)),
            AgentStep.ToolObserved(ToolResult(requestId = "type", status = ToolStatus.Succeeded, summary = "ok")),
            AgentStep.ToolRequested(tap, draft(tap)),
            AgentStep.UserConfirmationRequested(tap, draft(tap)),
            AgentStep.ToolObserved(
                ToolResult(
                    requestId = "tap",
                    status = ToolStatus.Failed,
                    summary = "需要你亲自操作",
                    data = mapOf("failureKind" to "dangerous_action"),
                ),
            ),
        )

        val json = agentRunReportJson(run, steps)

        assertEquals("Completed", json.getString("state"))
        assertTrue(json.getBoolean("terminal"))
        assertEquals(3_500L, json.getLong("durationMillis"))
        assertEquals(2, json.getInt("toolCallCount"))
        assertEquals(1, json.getInt("confirmationsRequested"))
        assertEquals(1, json.getInt("criticalControlStops"))
        assertEquals("dangerous_action", json.getJSONArray("toolCalls").getJSONObject(1).getString("failureKind"))
        assertFalse(json.toString().contains("SECRET"))
    }

    @Test
    fun noRunYieldsNullRunId() {
        assertTrue(agentRunReportJson(null, emptyList()).isNull("runId"))
    }

    private fun draft(request: ToolRequest) = ActionDraft(
        functionName = request.toolName,
        title = request.toolName,
        summary = request.toolName,
        parameters = request.arguments,
    )
}
