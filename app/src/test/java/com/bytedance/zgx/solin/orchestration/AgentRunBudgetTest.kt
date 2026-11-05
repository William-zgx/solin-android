package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the anti-runaway gates in [AgentRunBudget].
 *
 * Two properties matter here and are easy to regress silently:
 *  1. a step history that could not be read must FAIL CLOSED (contract C3) — the previous
 *     `runCatching { … }.getOrDefault(emptyList())` in the trace store made a DB hiccup look like
 *     "zero steps so far", i.e. it turned the step-limit gate off;
 *  2. a run must also be bounded in the TIME domain, because a local model generation has no
 *     timeout and step counts alone cannot bound total run duration.
 */
class AgentRunBudgetTest {
    @Test
    fun stepBudgetsAreNotExceededWhileHistoryIsReadableAndUnderTheCap() {
        val budget = budget(toolRequests = listOf(request("a")), observationDecisions = 1)

        assertFalse(budget.toolStepBudgetExceeded(RUN_ID))
        assertFalse(budget.observationDecisionBudgetExceeded(RUN_ID))
    }

    @Test
    fun degradedStepHistoryFailsBothStepDerivedBudgetsClosed() {
        // Zero recorded steps AND zero observation decisions: without the degradation signal this is
        // indistinguishable from a fresh run, so both gates would report "plenty of budget left".
        val budget = budget(
            toolRequests = emptyList(),
            observationDecisions = 0,
            stepHistoryDegraded = true,
        )

        assertTrue(budget.toolStepBudgetExceeded(RUN_ID))
        assertTrue(budget.observationDecisionBudgetExceeded(RUN_ID))
    }

    @Test
    fun degradationProbeFailureItselfFailsClosed() {
        // A probe that throws means the degradation signal is unavailable — exactly the situation
        // this check defends against. Treating it as "not degraded" reopened the runaway gate.
        val budget = AgentRunBudget(
            maxRunToolSteps = 2,
            maxObservationDecisions = 2,
            toolRequestsFor = { emptyList() },
            observationDecidedCount = { 0 },
            sessionPlanStore = null,
            stepHistoryDegraded = { error("probe blew up") },
        )

        assertTrue(
            "an unreadable degradation probe must fail the tool step budget closed",
            budget.toolStepBudgetExceeded(RUN_ID),
        )
        assertTrue(
            "an unreadable degradation probe must fail the observation budget closed",
            budget.observationDecisionBudgetExceeded(RUN_ID),
        )
    }

    @Test
    fun degradedStepHistoryIsScopedToTheAffectedRun() {
        val budget = AgentRunBudget(
            maxRunToolSteps = 10,
            maxObservationDecisions = 10,
            toolRequestsFor = { emptyList() },
            observationDecidedCount = { 0 },
            sessionPlanStore = null,
            stepHistoryDegraded = { runId -> runId == RUN_ID },
        )

        assertTrue(budget.toolStepBudgetExceeded(RUN_ID))
        assertFalse(budget.toolStepBudgetExceeded("run-healthy"))
    }

    @Test
    fun runDeadlineIsNotExceededBeforeTheWallClockBudgetElapses() {
        var now = START_MILLIS
        val budget = budget(
            runStartedAt = START_MILLIS,
            nowMillis = { now },
        )

        now = START_MILLIS + SolinConstants.AgentLoop.MAX_RUN_WALL_CLOCK_MILLIS - 1
        assertFalse(budget.runDeadlineExceeded(RUN_ID))
    }

    @Test
    fun runDeadlineIsExceededOnceTheWallClockBudgetElapses() {
        var now = START_MILLIS
        val budget = budget(
            runStartedAt = START_MILLIS,
            nowMillis = { now },
        )

        now = START_MILLIS + SolinConstants.AgentLoop.MAX_RUN_WALL_CLOCK_MILLIS
        assertTrue(budget.runDeadlineExceeded(RUN_ID))
    }

    @Test
    fun unknownRunStartTimeDoesNotFailTheRunImmediately() {
        // Restored runs (or callers that do not track start times) report a null start. Treating that
        // as an instant expiry would break restore; the step budgets still bound such runs.
        val budget = budget(runStartedAt = null, nowMillis = { Long.MAX_VALUE })

        assertFalse(budget.runDeadlineExceeded(RUN_ID))
    }

    @Test
    fun backwardsClockJumpDoesNotFailTheRun() {
        val budget = budget(
            runStartedAt = START_MILLIS,
            nowMillis = { START_MILLIS - 60_000L },
        )

        assertFalse(budget.runDeadlineExceeded(RUN_ID))
    }

    @Test
    fun nonPositiveWallClockBudgetDisablesTheDeadline() {
        val budget = AgentRunBudget(
            maxRunToolSteps = 10,
            maxObservationDecisions = 10,
            toolRequestsFor = { emptyList() },
            observationDecidedCount = { 0 },
            sessionPlanStore = null,
            runStartedAtMillis = { START_MILLIS },
            maxRunWallClockMillis = 0L,
            nowMillis = { START_MILLIS + 10 * 60_000L },
        )

        assertFalse(budget.runDeadlineExceeded(RUN_ID))
    }

    @Test
    fun wallClockDeadlineConstantIsFiveMinutes() {
        // Guards the audited worst-case figure documented in SolinConstants.AgentLoop.
        assertEquals(5 * 60_000L, SolinConstants.AgentLoop.MAX_RUN_WALL_CLOCK_MILLIS)
    }

    private fun budget(
        toolRequests: List<ToolRequest> = emptyList(),
        observationDecisions: Int = 0,
        stepHistoryDegraded: Boolean = false,
        runStartedAt: Long? = null,
        parkedMillis: Long = 0L,
        nowMillis: () -> Long = { START_MILLIS },
        stepHistoryDegradedProbe: ((String) -> Boolean)? = null,
        onHistoryRead: () -> Unit = {},
    ): AgentRunBudget = AgentRunBudget(
        maxRunToolSteps = 2,
        maxObservationDecisions = 2,
        toolRequestsFor = {
            onHistoryRead()
            toolRequests
        },
        observationDecidedCount = { observationDecisions },
        sessionPlanStore = null,
        stepHistoryDegraded = stepHistoryDegradedProbe ?: { stepHistoryDegraded },
        runStartedAtMillis = { runStartedAt },
        runParkedMillis = { parkedMillis },
        nowMillis = nowMillis,
    )

    @Test
    fun theDeadlineDoesNotBillTimeSpentParkedWaitingForTheUser() {
        // The failure this guards: a user who takes six minutes to read a confirmation had their run
        // killed by the first step after they tapped it, blamed on elapsed runtime. take_over is the
        // extreme case — its whole point is "go log in and come back".
        val deadline = SolinConstants.AgentLoop.MAX_RUN_WALL_CLOCK_MILLIS
        val budget = budget(
            runStartedAt = START_MILLIS,
            parkedMillis = deadline,
            nowMillis = { START_MILLIS + deadline + 10_000L },
        )

        assertFalse(
            "time parked awaiting the user must not count toward the run's working deadline",
            budget.runDeadlineExceeded(RUN_ID),
        )
    }

    @Test
    fun theDeadlineStillFiresOnWorkingTimeEvenWhenSomeTimeWasParked() {
        // The other half: pausing the clock while parked must not make the deadline unenforceable.
        val deadline = SolinConstants.AgentLoop.MAX_RUN_WALL_CLOCK_MILLIS
        val budget = budget(
            runStartedAt = START_MILLIS,
            parkedMillis = 60_000L,
            nowMillis = { START_MILLIS + deadline + 60_000L },
        )

        assertTrue(
            "working time at the deadline must still fail closed",
            budget.runDeadlineExceeded(RUN_ID),
        )
    }

    @Test
    fun aDegradedHistoryThatRecoversOnReProbeDoesNotFailTheRun() {
        // The one-way trap this guards: the latch is cleared by a SUCCESSFUL history read, but the
        // budget used to return early on a raised latch and never perform that read — so a single
        // transient SQLiteDatabaseLockedException failed every later check for the run's whole life.
        var probeCalls = 0
        var historyReads = 0
        val budget = budget(
            toolRequests = emptyList(),
            stepHistoryDegradedProbe = {
                probeCalls++
                // Degraded on the first look, readable once the forced read has run.
                historyReads == 0
            },
            onHistoryRead = { historyReads++ },
        )

        assertFalse(
            "a latch that clears on re-probe must not fail the budget closed",
            budget.toolStepBudgetExceeded(RUN_ID),
        )
        assertTrue("the budget must force a history read to give the store a chance", historyReads > 0)
        assertTrue("the latch must be re-checked after that read", probeCalls >= 2)
    }

    @Test
    fun aHistoryThatStaysDegradedAfterReProbeStillFailsClosed() {
        val budget = budget(
            toolRequests = emptyList(),
            stepHistoryDegraded = true,
        )

        assertTrue(
            "a persistently unreadable history must still fail closed",
            budget.toolStepBudgetExceeded(RUN_ID),
        )
    }

    private fun request(id: String): ToolRequest =
        ToolRequest(
            id = id,
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            reason = "budget test",
        )

    private companion object {
        const val RUN_ID = "run-budget"
        const val START_MILLIS = 1_000_000L
    }
}
