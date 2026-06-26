package com.bytedance.zgx.pocketmind.orchestration

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
        val executableCommand = "rm -rf /tmp/pocketmind"
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
}
