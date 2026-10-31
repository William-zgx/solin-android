package com.bytedance.zgx.solin.orchestration

import android.util.Log
import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.plan.PlanItemStatus
import com.bytedance.zgx.solin.plan.SessionPlanStore
import com.bytedance.zgx.solin.tool.ToolRequest
import java.util.concurrent.ConcurrentHashMap

internal const val TOOL_STEP_BUDGET_EXCEEDED_REASON = "Agent run tool step budget exceeded."
internal const val OBSERVATION_DECISION_BUDGET_EXCEEDED_REASON =
    "Agent run observation decision budget exceeded."
internal const val RUN_WALL_CLOCK_DEADLINE_EXCEEDED_REASON =
    "Agent run wall-clock deadline exceeded."

private const val TAG = "AgentRunBudget"

/**
 * Per-run tool-step and observation-decision budgets for [AgentLoopRuntime].
 *
 * Pure budget checks and step-budget failure-message augmentation live here so the
 * runtime facade stays thin. Fail/CAS state transitions remain on the runtime.
 *
 * New collaborators are appended with defaults so existing construction sites and tests keep
 * compiling (Kotlin data/constructor evolution rule).
 */
internal class AgentRunBudget(
    private val maxRunToolSteps: Int,
    private val maxObservationDecisions: Int,
    private val profilesByRunId: ConcurrentHashMap<String, AgentProfile>,
    private val toolRequestsFor: (runId: String) -> List<ToolRequest>,
    private val observationDecidedCount: (runId: String) -> Int,
    private val sessionPlanStore: SessionPlanStore?,
    /**
     * True when the trace store could not read the run's persisted step history. Both step-derived
     * budgets below are computed FROM that history, so a degraded read would otherwise look like
     * "zero steps so far" and silently reopen the runaway gate. See
     * [AgentTraceStore.stepHistoryDegraded].
     */
    private val stepHistoryDegraded: (runId: String) -> Boolean = { false },
    /**
     * Wall-clock start time of a run, or null when unknown (e.g. a run restored in another process,
     * or a caller that does not track start times). Null is treated as "no deadline to enforce"
     * rather than as an immediate expiry: the step budgets still bound such runs, and failing an
     * unknown-age run instantly would break restore paths.
     */
    private val runStartedAtMillis: (runId: String) -> Long? = { null },
    /**
     * Milliseconds this run has spent parked waiting on someone outside the agent — a confirmation,
     * an answer, or an external app's outcome — which the deadline must not bill.
     *
     * Without this the deadline measures the user's response time, not the agent's work: a user who
     * takes six minutes to read a confirmation would have their run killed by the first step after
     * they tap it, blamed on elapsed runtime. `take_over` is the extreme case, since its whole
     * purpose is "go log in and come back".
     */
    private val runParkedMillis: (runId: String) -> Long = { 0L },
    private val maxRunWallClockMillis: Long = SolinConstants.AgentLoop.MAX_RUN_WALL_CLOCK_MILLIS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    fun effectiveMaxToolSteps(runId: String): Int =
        profilesByRunId[runId]?.effectiveMaxToolSteps() ?: maxRunToolSteps

    fun toolStepBudgetExceeded(runId: String): Boolean {
        if (stepHistoryDegradedFailClosed(runId, budgetName = "tool step")) return true
        return toolRequestsFor(runId).size >= effectiveMaxToolSteps(runId)
    }

    fun observationDecisionBudgetExceeded(runId: String): Boolean {
        if (stepHistoryDegradedFailClosed(runId, budgetName = "observation decision")) return true
        return observationDecidedCount(runId) >= maxObservationDecisions
    }

    /**
     * True once the run has spent longer than [maxRunWallClockMillis] *working*.
     *
     * This is the time-domain counterpart of the step budgets: step counts bound how many
     * iterations a run may take, not how long each blocking model generation or tool timeout may
     * last, so without a deadline a run's total duration is unbounded. Checked alongside the
     * existing budget checkpoints so an over-long run fails closed at the next safe boundary rather
     * than being hard-killed mid-tool.
     *
     * Time parked awaiting a user or an external app is subtracted — see [runParkedMillis]. What is
     * left is the agent's own elapsed work, which is what the budget is meant to cap.
     *
     * Both clock-jump directions are handled: a backwards jump yields a negative elapsed value that
     * reads as not-expired, and a forwards jump is only as trustworthy as the clock that reported it.
     */
    fun runDeadlineExceeded(runId: String): Boolean {
        if (maxRunWallClockMillis <= 0L) return false
        val startedAt = runStartedAtMillis(runId) ?: return false
        val elapsed = nowMillis() - startedAt
        if (elapsed < 0L) return false
        val working = elapsed - runParkedMillis(runId).coerceAtLeast(0L)
        return working >= maxRunWallClockMillis
    }

    /**
     * Fail-closed bridge for an unreadable step history: log once per check and report the budget as
     * exhausted. Preferring an early termination over a possibly-uncapped loop is the deliberate
     * trade — the run surfaces a budget failure the user can retry, instead of spinning invisibly.
     */
    /**
     * True when the run's persisted step history is unreadable, after giving it one chance to
     * recover.
     *
     * The re-probe is what keeps this from being a one-way trap. The latch is cleared by a
     * *successful* history read, and that read happens inside the store's merge path — but this
     * check runs before the budget touches the history, so returning early on a raised latch means
     * the clearing read never happens and one transient `SQLiteDatabaseLockedException` would fail
     * every subsequent check for the life of the run. Forcing a read here gives the store the chance
     * to clear its own latch; only a second failure fails closed.
     */
    private fun stepHistoryDegradedFailClosed(runId: String, budgetName: String): Boolean {
        // Fail closed on a throwing probe: an unreadable degradation signal is exactly the case this
        // check exists for, and treating it as "not degraded" would reopen the runaway gate.
        val degraded = runCatching { stepHistoryDegraded(runId) }.getOrDefault(true)
        if (!degraded) return false
        // Force a history read so a store whose backing DB has recovered can lower its own latch.
        // The value is deliberately discarded: the caller re-reads the history itself once this
        // returns false, and the point here is the side effect on the latch.
        runCatching { toolRequestsFor(runId) }
        val stillDegraded = runCatching { stepHistoryDegraded(runId) }.getOrDefault(true)
        if (!stillDegraded) return false
        // Log is wrapped because android.util.Log is not mocked on the JVM unit-test path; an
        // unavailable logger must never turn a fail-closed budget check into a thrown exception.
        runCatching {
            Log.e(
                TAG,
                "Step history degraded for run $runId; treating $budgetName budget as exceeded (fail-closed)",
            )
        }
        return true
    }

    /**
     * Step-budget hint: when the tool step budget is exhausted, look up the current session plan
     * (if present) and append up to 5 still-pending/in-progress items as a numbered hint so the
     * assistant-facing failure message carries context about outstanding work. The base [reason]
     * is returned unchanged when there is no plan or no pending items.
     */
    fun augmentReasonWithStepBudgetHint(runId: String, reason: String): String {
        if (reason != TOOL_STEP_BUDGET_EXCEEDED_REASON) return reason
        return runCatching {
            val store = sessionPlanStore ?: return@runCatching reason
            val snap = store.get(runId) ?: return@runCatching reason
            val pending = snap.items.filter { item ->
                item.status == PlanItemStatus.PENDING || item.status == PlanItemStatus.IN_PROGRESS
            }.take(5)
            if (pending.isEmpty()) return@runCatching reason
            val sb = StringBuilder(reason)
            sb.append('\n')
            sb.append('\n')
            sb.append("Remaining plan (up to 5 pending steps):")
            sb.append('\n')
            pending.forEachIndexed { index, item ->
                val marker = when (item.status) {
                    PlanItemStatus.PENDING -> "[P]"
                    PlanItemStatus.IN_PROGRESS -> "[>]"
                    PlanItemStatus.DONE -> "[D]"
                    PlanItemStatus.BLOCKED -> "[B]"
                    PlanItemStatus.SKIPPED -> "[S]"
                }
                val lineNumber = index + 1
                val note = item.note
                sb.append(lineNumber).append(". ")
                sb.append(marker).append(' ')
                sb.append(item.title)
                if (!note.isNullOrBlank()) {
                    sb.append(" - ").append(note)
                }
                sb.append('\n')
            }
            sb.append("Use plan_write to mark completed items before continuing.")
            sb.toString()
        }.getOrElse { throwable ->
            // android.util.Log is not mocked on the JVM unit-test path; a logger failure must not
            // turn a budget message into a thrown exception.
            runCatching { Log.e(TAG, "Failed to build step-budget plan hint", throwable) }
            reason
        }
    }
}
