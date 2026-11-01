package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.skill.SkillRequest
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentRunEventsTest {
    @Test
    fun adapterDerivesTimelineEventsFromAgentSteps() {
        val privateEmail = "secret@example.com"
        val run = AgentRun(
            id = "run-adapter",
            input = "总结后分享给 $privateEmail",
            state = AgentRunState.Failed,
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
        )
        val request = ToolRequest(
            id = "tool-share",
            toolName = "share_text",
            reason = "share summarized text",
        )
        val draft = ActionDraft(
            functionName = "share_text",
            title = "分享摘要",
            summary = "分享给用户确认的目标",
            parameters = mapOf("text" to privateEmail),
        )
        val events = AgentStepRunEventAdapter.adapt(
            run = run,
            steps = listOf(
                AgentStep.ContextLoaded(
                    memoryHits = listOf(MemoryHit(id = "memory-1", text = "用户邮箱：$privateEmail", score = 0.9f)),
                ),
                AgentStep.SkillPlanned(
                    request = SkillRequest(
                        id = "skill-1",
                        skillId = "clipboard_summary_share",
                        arguments = emptyMap(),
                        reason = "summarize and share",
                    ),
                    plan = null,
                ),
                AgentStep.ToolRequested(request, draft),
                AgentStep.UserConfirmationRequested(request, draft),
                AgentStep.ToolObserved(
                    ToolResult(
                        requestId = request.id,
                        status = ToolStatus.Failed,
                        summary = "Permission missing for share target",
                    ),
                ),
                AgentStep.AssistantResponded("无法分享给 $privateEmail"),
                AgentStep.Failed("permission denied"),
            ),
        )

        assertEquals(
            listOf(
                AgentRunEventKind.InputReceived,
                AgentRunEventKind.ContextLoaded,
                AgentRunEventKind.PlanCreated,
                AgentRunEventKind.PlanCreated,
                AgentRunEventKind.ConfirmationRequested,
                AgentRunEventKind.ToolExecuted,
                AgentRunEventKind.ObservationRecorded,
                AgentRunEventKind.AnswerGenerated,
                AgentRunEventKind.RunFailed,
            ),
            events.map { it.kind },
        )

        // ContextLoaded carries the memory-hit count and device-context flag.
        val contextLoaded = events.filterIsInstance<AgentRunEvent.ContextLoaded>().single()
        assertEquals(1, contextLoaded.memoryHitCount)
        assertEquals(false, contextLoaded.deviceContextIncluded)

        // ConfirmationRequested is user-mediated.
        val confirmation = events.filterIsInstance<AgentRunEvent.ConfirmationRequested>().single()
        assertEquals(setOf(AgentRunPrivacyMarker.UserMediated), confirmation.privacyMarkers)

        // ToolExecuted from a failed observation is reported as Failed.
        val executed = events.filterIsInstance<AgentRunEvent.ToolExecuted>().single()
        assertEquals(AgentRunToolStatus.Failed, executed.status)

        // Terminal failure is reported.
        val failed = events.filterIsInstance<AgentRunEvent.RunFailed>().single()
        assertEquals("permission denied", failed.reasonLabel)
    }

    @Test
    fun answerEventKeepsNoRawAnswerText() {
        val privateAnswer = "无法分享给 secret@example.com"
        val events = AgentStepRunEventAdapter.adapt(
            run = AgentRun(
                id = "run-answer",
                input = "总结后分享",
                state = AgentRunState.Completed,
                createdAtMillis = 1L,
                updatedAtMillis = 2L,
            ),
            steps = listOf(AgentStep.AssistantResponded(privateAnswer)),
        )

        val answer = events.filterIsInstance<AgentRunEvent.AnswerGenerated>().single()
        assertEquals("answer", answer.outputLabel)
        // The event is an audit/timeline marker: no field may hold the answer text itself.
        assertFalse("raw answer text leaked: $answer", answer.toString().contains(privateAnswer))
        assertEquals(
            setOf("eventId", "runId", "answerId", "outputLabel", "privacyMarkers", "kind"),
            generateSequence(AgentRunEvent.AnswerGenerated::class.java as Class<*>?) { type -> type.superclass }
                .flatMap { type -> type.declaredFields.asSequence() }
                .filterNot { field -> java.lang.reflect.Modifier.isStatic(field.modifiers) }
                .map { field -> field.name }
                .toSet(),
        )
    }
}
