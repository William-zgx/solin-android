package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.skill.SkillRequest
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunEventsTest {
    @Test
    fun summaryPreservesEventOrderingAndCounts() {
        val events = listOf(
            AgentRunEvent.InputReceived(
                eventId = "event-1",
                runId = "run-order",
                inputId = "input-1",
                sourceLabel = "voice",
                privacyMarkers = setOf(AgentRunPrivacyMarker.LocalOnly),
            ),
            AgentRunEvent.ContextLoaded(
                eventId = "event-2",
                runId = "run-order",
                contextId = "context-1",
                memoryHitCount = 2,
                deviceContextIncluded = true,
                sourceLabels = listOf("memory", "device"),
                privacyMarkers = setOf(AgentRunPrivacyMarker.ContainsLocalContext),
            ),
            AgentRunEvent.PlanCreated(
                eventId = "event-3",
                runId = "run-order",
                planId = "plan-1",
                stepCount = 3,
                toolLabels = listOf("calendar", "notes"),
                riskMarkers = setOf(AgentRunRiskMarker.ExternalSideEffect),
            ),
            AgentRunEvent.ToolExecuted(
                eventId = "event-4",
                runId = "run-order",
                toolCallId = "tool-1",
                toolLabel = "calendar",
                status = AgentRunToolStatus.Succeeded,
                riskMarkers = setOf(AgentRunRiskMarker.ExternalSideEffect),
            ),
            AgentRunEvent.ObservationRecorded(
                eventId = "event-5",
                runId = "run-order",
                observationId = "observation-1",
                toolCallId = "tool-1",
                observationLabel = "calendar result observed",
                sourceCount = 1,
                privacyMarkers = setOf(AgentRunPrivacyMarker.ThirdPartyOutput),
            ),
            AgentRunEvent.AnswerGenerated(
                eventId = "event-6",
                runId = "run-order",
                answerId = "answer-1",
                safeAnswerPreview = "Calendar checked.",
                outputLabel = "final answer",
            ),
        )

        val summary = AgentRunEventProjector.summarize(events)

        assertEquals("run-order", summary.runId)
        assertEquals("event-1", summary.firstEventId)
        assertEquals("event-6", summary.lastEventId)
        assertEquals(
            listOf(
                AgentRunEventKind.InputReceived,
                AgentRunEventKind.ContextLoaded,
                AgentRunEventKind.PlanCreated,
                AgentRunEventKind.ToolExecuted,
                AgentRunEventKind.ObservationRecorded,
                AgentRunEventKind.AnswerGenerated,
            ),
            summary.orderedEventKinds,
        )
        assertEquals(6, summary.counts.total)
        assertEquals(1, summary.counts.toolExecuted)
        assertEquals(1, summary.counts.observationRecorded)
        assertEquals(2, summary.counts.memoryHits)
        assertEquals(1, summary.counts.deviceContextLoads)
        assertEquals(3, summary.counts.plannedSteps)
        assertEquals(AgentRunTerminalState.AnswerGenerated, summary.terminalState)
        assertNull(summary.pendingConfirmation)
        assertTrue(summary.labels.contains("calendar"))
        assertTrue(summary.privacyMarkers.contains(AgentRunPrivacyMarker.LocalOnly))
        assertTrue(summary.riskMarkers.contains(AgentRunRiskMarker.ExternalSideEffect))
    }

    @Test
    fun summaryReportsPendingConfirmationUntilMatchingToolExecutes() {
        val events = listOf(
            AgentRunEvent.InputReceived(
                eventId = "event-1",
                runId = "run-confirm",
                inputId = "input-1",
                sourceLabel = "typed",
            ),
            AgentRunEvent.PlanCreated(
                eventId = "event-2",
                runId = "run-confirm",
                planId = "plan-1",
                stepCount = 1,
                toolLabels = listOf("send message"),
                riskMarkers = setOf(AgentRunRiskMarker.ExternalSideEffect),
            ),
            AgentRunEvent.ConfirmationRequested(
                eventId = "event-3",
                runId = "run-confirm",
                confirmationId = "confirmation-1",
                toolCallId = "tool-send",
                actionLabel = "send message",
                riskMarkers = setOf(AgentRunRiskMarker.ExternalSideEffect),
                privacyMarkers = setOf(AgentRunPrivacyMarker.UserMediated),
            ),
        )

        val pendingSummary = AgentRunEventProjector.summarize(events)

        assertEquals(AgentRunEventState.AwaitingConfirmation, pendingSummary.currentState)
        assertEquals(
            AgentRunPendingConfirmationSummary(
                confirmationId = "confirmation-1",
                toolCallId = "tool-send",
                actionLabel = "send message",
                riskMarkers = setOf(AgentRunRiskMarker.ExternalSideEffect),
                privacyMarkers = setOf(AgentRunPrivacyMarker.UserMediated),
            ),
            pendingSummary.pendingConfirmation,
        )

        val executedSummary = AgentRunEventProjector.summarize(
            events + AgentRunEvent.ToolExecuted(
                eventId = "event-4",
                runId = "run-confirm",
                toolCallId = "tool-send",
                toolLabel = "send message",
                status = AgentRunToolStatus.Succeeded,
                riskMarkers = setOf(AgentRunRiskMarker.ExternalSideEffect),
            ),
        )

        assertNull(executedSummary.pendingConfirmation)
        assertEquals(AgentRunEventState.Executing, executedSummary.currentState)
    }

    @Test
    fun summaryUsesFailedAndCancelledTerminalStates() {
        val failedSummary = AgentRunEventProjector.summarize(
            listOf(
                AgentRunEvent.InputReceived(
                    eventId = "event-1",
                    runId = "run-failed",
                    inputId = "input-1",
                    sourceLabel = "typed",
                ),
                AgentRunEvent.RunFailed(
                    eventId = "event-2",
                    runId = "run-failed",
                    failureId = "failure-1",
                    reasonLabel = "tool unavailable",
                ),
            ),
        )
        val cancelledSummary = AgentRunEventProjector.summarize(
            listOf(
                AgentRunEvent.InputReceived(
                    eventId = "event-1",
                    runId = "run-cancelled",
                    inputId = "input-1",
                    sourceLabel = "typed",
                ),
                AgentRunEvent.RunCancelled(
                    eventId = "event-2",
                    runId = "run-cancelled",
                    cancellationId = "cancel-1",
                    reasonLabel = "user cancelled",
                ),
            ),
        )

        assertEquals(AgentRunTerminalState.Failed, failedSummary.terminalState)
        assertEquals(AgentRunEventState.Failed, failedSummary.currentState)
        assertEquals("failure-1", failedSummary.terminalEventId)
        assertEquals(1, failedSummary.counts.runFailed)

        assertEquals(AgentRunTerminalState.Cancelled, cancelledSummary.terminalState)
        assertEquals(AgentRunEventState.Cancelled, cancelledSummary.currentState)
        assertEquals("cancel-1", cancelledSummary.terminalEventId)
        assertEquals(1, cancelledSummary.counts.runCancelled)
    }

    @Test
    fun summaryRedactsPrivateAndExecutableLookingStrings() {
        val privateEmail = "secret@example.com"
        val executableCommand = "rm -rf /tmp/solin"
        val rawPayload = "{\"arguments\":{\"text\":\"$privateEmail\"}}"
        val summary = AgentRunEventProjector.summarize(
            listOf(
                AgentRunEvent.InputReceived(
                    eventId = "event-$privateEmail",
                    runId = "run-redaction",
                    inputId = "input-$privateEmail",
                    sourceLabel = "typed $privateEmail",
                    privacyMarkers = setOf(AgentRunPrivacyMarker.LocalOnly),
                ),
                AgentRunEvent.PlanCreated(
                    eventId = "event-2",
                    runId = "run-redaction",
                    planId = "plan-1",
                    stepCount = 1,
                    toolLabels = listOf(rawPayload),
                    riskMarkers = setOf(AgentRunRiskMarker.ExternalSideEffect),
                ),
                AgentRunEvent.ConfirmationRequested(
                    eventId = "event-3",
                    runId = "run-redaction",
                    confirmationId = "confirm-$privateEmail",
                    toolCallId = "tool-$privateEmail",
                    actionLabel = executableCommand,
                    riskMarkers = setOf(AgentRunRiskMarker.ExternalSideEffect),
                ),
                AgentRunEvent.AnswerGenerated(
                    eventId = "event-4",
                    runId = "run-redaction",
                    answerId = "answer-1",
                    safeAnswerPreview = "Done for $privateEmail by running $executableCommand",
                    outputLabel = rawPayload,
                ),
            ),
        )

        val renderedSummary = summary.toString()
        assertFalse(renderedSummary.contains(privateEmail))
        assertFalse(renderedSummary.contains(executableCommand))
        assertFalse(renderedSummary.contains(rawPayload))
        assertTrue(renderedSummary.contains("[redacted"))
        assertTrue(summary.privacyMarkers.contains(AgentRunPrivacyMarker.LocalOnly))
        assertTrue(summary.riskMarkers.contains(AgentRunRiskMarker.ExternalSideEffect))
    }

    @Test
    fun adapterDerivesSafeTimelineEventsFromAgentSteps() {
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

        val summary = AgentRunEventProjector.summarize(events)

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
            summary.orderedEventKinds,
        )
        assertEquals(1, summary.counts.memoryHits)
        assertEquals(1, summary.counts.confirmationRequested)
        assertEquals(1, summary.counts.toolExecuted)
        assertEquals(1, summary.counts.observationRecorded)
        assertEquals(AgentRunEventState.Failed, summary.currentState)
        assertFalse(summary.toString().contains(privateEmail))
    }
}
