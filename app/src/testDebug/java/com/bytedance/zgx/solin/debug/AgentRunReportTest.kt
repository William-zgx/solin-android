package com.bytedance.zgx.solin.debug

import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.data.ChatMessageEntity
import com.bytedance.zgx.solin.orchestration.AgentRun
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.AgentStep
import com.bytedance.zgx.solin.orchestration.RoomAgentTraceStore
import com.bytedance.zgx.solin.testing.FakeAgentTraceDao
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunReportTest {
    private val type = ToolRequest(id = "type", toolName = "ui_type_text", arguments = mapOf("text" to "SECRET-TEXT"))
    private val tap = ToolRequest(id = "tap", toolName = "ui_tap", arguments = mapOf("target" to "SECRET-TARGET"))
    private val criticalStopSteps = listOf(
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

    @Test
    fun reportsToolCallsStopsAndConfirmationsWithoutPromptOrArguments() {
        val run = completedRun()

        val json = agentRunReportJson(run, criticalStopSteps)

        assertEquals("Completed", json.getString("state"))
        assertTrue(json.getBoolean("terminal"))
        assertEquals(3_500L, json.getLong("durationMillis"))
        assertEquals(2, json.getInt("toolCallCount"))
        assertEquals(1, json.getInt("confirmationsRequested"))
        assertEquals(1, json.getInt("criticalControlStops"))
        assertEquals("dangerous_action", json.getJSONArray("toolCalls").getJSONObject(1).getString("failureKind"))
        val confirmation = json.getJSONObject("confirmation")
        assertEquals("ui_tap", confirmation.getString("functionName"))
        assertEquals("点击 ui_tap", confirmation.getString("title"))
        assertEquals(setOf("functionName", "title"), confirmation.keys().asSequence().toSet())
        assertTrue(json.getBoolean("gaveUp"))
        assertFalse(json.toString().contains("SECRET"))
    }

    @Test
    fun persistedStepsFromAFreshStoreYieldTheSameCounts() {
        // The device receiver builds a fresh RoomAgentTraceStore, so every step comes back as a
        // RestoredSummary. Counting only typed steps reported zero tool calls on a real device.
        val dao = FakeAgentTraceDao()
        val liveStore = RoomAgentTraceStore(traceDao = dao, runIdFactory = { "run-persisted" })
        val run = liveStore.createRun("SECRET-PROMPT")
        criticalStopSteps.forEach { step -> liveStore.appendStep(run.id, step) }
        liveStore.updateState(run.id, AgentRunState.Completed)

        val freshStore = RoomAgentTraceStore(traceDao = dao)
        val restoredSteps = freshStore.steps(run.id)
        val json = agentRunReportJson(freshStore.run(run.id), restoredSteps)

        assertTrue(restoredSteps.all { step -> step is AgentStep.RestoredSummary })
        assertEquals(2, json.getInt("toolCallCount"))
        assertEquals(
            listOf("ui_type_text" to "Succeeded", "ui_tap" to "Failed"),
            (0 until 2).map { index ->
                json.getJSONArray("toolCalls").getJSONObject(index).let { it.getString("tool") to it.getString("status") }
            },
        )
        assertEquals(1, json.getInt("confirmationsRequested"))
        assertEquals(1, json.getInt("criticalControlStops"))
        assertEquals("ui_tap", json.getJSONObject("confirmation").getString("functionName"))
        assertEquals("点击 ui_tap", json.getJSONObject("confirmation").getString("title"))
        assertTrue(json.getBoolean("gaveUp"))
        assertFalse(json.toString().contains("SECRET"))
    }

    @Test
    fun answerComesFromTheTranscriptReplyToTheLatestUserTurnAndIsCapped() {
        val long = "答".repeat(REPORT_ANSWER_MAX_CHARS + 50)
        val answer = transcriptAnswer(
            listOf(
                message(0, MessageRole.User, "上一个任务"),
                message(1, MessageRole.Assistant, "上一个回答"),
                message(2, MessageRole.User, "这个任务"),
                message(3, MessageRole.Assistant, long),
            ),
        )

        val json = agentRunReportJson(completedRun(), emptyList(), answer)

        assertEquals(REPORT_ANSWER_MAX_CHARS, json.getString("answer").length)
        assertEquals("transcript", json.getString("answerSource"))
    }

    @Test
    fun noReplyYetToTheLatestTurnNeverReusesAnEarlierAnswer() {
        val answer = transcriptAnswer(
            listOf(
                message(0, MessageRole.User, "上一个任务"),
                message(1, MessageRole.Assistant, "上一个回答"),
                message(2, MessageRole.User, "这个任务"),
            ),
        )

        assertNull(answer)
        val json = agentRunReportJson(completedRun(), emptyList(), answer)
        assertTrue(json.isNull("answer"))
        assertTrue(json.isNull("answerSource"))
    }

    @Test
    fun gaveUpIsFalseWhenALaterToolCallSucceeded() {
        val retry = ToolRequest(id = "retry", toolName = "ui_tap", arguments = emptyMap())
        val steps = criticalStopSteps + listOf(
            AgentStep.ToolRequested(retry, draft(retry)),
            AgentStep.ToolObserved(ToolResult(requestId = "retry", status = ToolStatus.Succeeded, summary = "ok")),
        )

        assertFalse(agentRunReportJson(completedRun(), steps).getBoolean("gaveUp"))
        assertFalse(agentRunReportJson(completedRun(), criticalStopSteps.take(2)).getBoolean("gaveUp"))
    }

    @Test
    fun noRunYieldsNullRunId() {
        assertTrue(agentRunReportJson(null, emptyList()).isNull("runId"))
    }

    private fun completedRun() = AgentRun(
        id = "run-1",
        input = "SECRET-PROMPT",
        state = AgentRunState.Completed,
        createdAtMillis = 1_000L,
        updatedAtMillis = 4_500L,
    )

    private fun message(position: Int, role: MessageRole, text: String) = ChatMessageEntity(
        sessionId = "session",
        position = position,
        role = role.name,
        text = text,
        tokenCount = null,
        tokensPerSecond = null,
    )

    private fun draft(request: ToolRequest) = ActionDraft(
        functionName = request.toolName,
        title = "点击 ${request.toolName}",
        summary = request.toolName,
        parameters = request.arguments,
    )
}
