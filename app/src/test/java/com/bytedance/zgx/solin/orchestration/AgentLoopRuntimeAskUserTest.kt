package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.ModelToolOutputParseResult
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ask_user` parking: every path that moves a run into [AgentRunState.AwaitingUserAnswer] must also
 * register the pending question, otherwise answer/cancel can never correlate a reply and the run is
 * stuck forever.
 */
class AgentLoopRuntimeAskUserTest {
    private val eventBus = RecordingSolinEventBus()
    private val actionRuntime = ScriptedActionRuntime()
    private val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
    private val runtime = AgentLoopRuntime(
        memoryIndex = MemoryRepository(),
        actionPlanningRuntime = actionRuntime,
        traceStore = traceStore,
        eventBus = eventBus,
    )

    private fun parkedRun(): Pair<String, String> {
        val started = runtime.runOnce(
            input = "帮我查一下天气",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            // Inline device-style tool calls need an in-app driver; the remote-vision opt-in satisfies
            // the gate without wiring a local mobile-action model profile into the test.
            options = AgentRunOptions(remoteGuiDrivingEnabled = true),
        )
        assertEquals(AgentRunState.GeneratingAnswer, started.run.state)
        actionRuntime.modelOutputResult = ModelToolOutputParseResult.Parsed(askUserDraft())

        val observed = runtime.observeModelResult(started.run.id, """call:ask_user{"prompt":"你想查询哪个城市？"}""")

        requireNotNull(observed)
        assertEquals(AgentRunState.AwaitingUserAnswer, observed.run.state)
        val asked = observed.steps.filterIsInstance<AgentStep.UserQuestionAsked>().singleOrNull()
        assertNotNull("ask_user from a local model text tool call must register the question", asked)
        return started.run.id to asked!!.questionId
    }

    @Test
    fun localModelAskUserToolCallParksQuestionAndPublishesEvent() {
        val (runId, questionId) = parkedRun()

        val event = eventBus.ofType<SolinEvent.Agent.UserQuestionAsked>().single()
        assertEquals(runId, event.runId)
        assertEquals(questionId, event.questionId)
        assertEquals("你想查询哪个城市？", event.prompt)
        assertNull("ask_user must not persist a pending tool confirmation", runtime.latestPendingConfirmation())
    }

    @Test
    fun answerUserQuestionResumesParkedRunWithLocalOnlyContinuation() {
        val (runId, questionId) = parkedRun()

        val answered = runtime.answerUserQuestion(runId, questionId, "  北京 ")

        requireNotNull(answered)
        assertEquals(AgentRunState.GeneratingAnswer, answered.run.state)
        assertTrue(answered.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(answered.continuationRequiresLocalModel)
        assertTrue(answered.continuationPromptForModel.orEmpty().contains("北京"))
        assertTrue(answered.steps.any { step -> step is AgentStep.UserQuestionAnswered && step.answer == "北京" })
        // The pending question is consumed: a second answer is rejected.
        assertNull(runtime.answerUserQuestion(runId, questionId, "上海"))
    }

    @Test
    fun answerWithWrongQuestionIdIsRejectedAndKeepsRunParked() {
        val (runId, _) = parkedRun()

        assertNull(runtime.answerUserQuestion(runId, "not-the-question", "北京"))
        assertEquals(AgentRunState.AwaitingUserAnswer, traceStore.run(runId)?.state)
    }

    @Test
    fun cancelUserQuestionCancelsParkedRun() {
        val (runId, questionId) = parkedRun()

        val cancelled = runtime.cancelUserQuestion(runId, questionId)

        requireNotNull(cancelled)
        assertEquals(AgentRunState.Cancelled, cancelled.run.state)
        assertEquals(AgentObservationDecision.Cancel, cancelled.decision)
    }

    @Test
    fun cancelRunWhileAwaitingUserAnswerCancelsTheRun() {
        val (runId, _) = parkedRun()

        val cancelled = runtime.cancelRun(runId)

        assertNotNull("cancelRun must not orphan a run parked on ask_user", cancelled)
        assertEquals(AgentRunState.Cancelled, cancelled!!.run.state)
        assertEquals(AgentRunState.Cancelled, traceStore.run(runId)?.state)
    }

    @Test
    fun externalOutcomeConfirmationThatReplansAskUserParksQuestion() {
        val askUserDraft = askUserDraft("要继续打开哪个页面？")
        val outcomeRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = ScriptedActionRuntime(planningResult = externalDeepLinkPlanningResult()),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            eventBus = eventBus,
            observationReplanner = AgentObservationReplanner {
                AgentObservationReplan(
                    request = ToolRequest(
                        toolName = MobileActionFunctions.ASK_USER,
                        arguments = askUserDraft.parameters,
                        reason = "clarify",
                    ),
                    draft = askUserDraft,
                    fallbackReason = "after external completion",
                )
            },
        )
        val planned = outcomeRuntime.runOnce(
            input = "先打开网页，然后问我下一步",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        outcomeRuntime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val opened = outcomeRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开深链",
                data = unverifiedExternalActivityResultData(),
            ),
        )
        assertEquals(AgentRunState.AwaitingExternalOutcome, opened?.run?.state)

        val confirmed = outcomeRuntime.recordExternalOutcome(
            runId = planned.run.id,
            requestId = planned.plan.request.id,
            outcome = AgentExternalOutcome.Completed,
        )

        requireNotNull(confirmed)
        assertEquals(AgentRunState.AwaitingUserAnswer, confirmed.run.state)
        val asked = confirmed.steps.filterIsInstance<AgentStep.UserQuestionAsked>().single()
        val answered = outcomeRuntime.answerUserQuestion(planned.run.id, asked.questionId, "设置页")
        requireNotNull(answered)
        assertEquals(AgentRunState.GeneratingAnswer, answered.run.state)
    }
}
