package com.bytedance.zgx.solin.orchestration

import android.util.Log
import com.bytedance.zgx.solin.plan.PlanItemStatus
import com.bytedance.zgx.solin.plan.SessionPlanStore
import com.bytedance.zgx.solin.tool.ToolRequest
import java.util.concurrent.ConcurrentHashMap

internal const val TOOL_STEP_BUDGET_EXCEEDED_REASON = "Agent run tool step budget exceeded."
internal const val OBSERVATION_DECISION_BUDGET_EXCEEDED_REASON =
    "Agent run observation decision budget exceeded."

private const val TAG = "AgentRunBudget"

/**
 * Per-run tool-step and observation-decision budgets for [AgentLoopRuntime].
 *
 * Pure budget checks and step-budget failure-message augmentation live here so the
 * runtime facade stays thin. Fail/CAS state transitions remain on the runtime.
 */
internal class AgentRunBudget(
    private val maxRunToolSteps: Int,
    private val maxObservationDecisions: Int,
    private val profilesByRunId: ConcurrentHashMap<String, AgentProfile>,
    private val toolRequestsFor: (runId: String) -> List<ToolRequest>,
    private val observationDecidedCount: (runId: String) -> Int,
    private val sessionPlanStore: SessionPlanStore?,
) {
    fun effectiveMaxToolSteps(runId: String): Int =
        profilesByRunId[runId]?.effectiveMaxToolSteps() ?: maxRunToolSteps

    fun toolStepBudgetExceeded(runId: String): Boolean =
        toolRequestsFor(runId).size >= effectiveMaxToolSteps(runId)

    fun observationDecisionBudgetExceeded(runId: String): Boolean =
        observationDecidedCount(runId) >= maxObservationDecisions

    /**
     * Wave 7 step-budget hint: when the tool step budget is exhausted, look up the current
     * session plan (if present) and append up to 5 still-pending/in-progress items as a
     * numbered hint so the assistant-facing failure message carries context about outstanding
     * work. The base [reason] is returned unchanged when there is no plan or no pending items.
     *
     * Implementation uses concatenation with explicit \n characters (no Kotlin string templates
     * at this call site for plan lines) per Wave 6 directive to avoid template pitfalls.
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
            Log.e(TAG, "Failed to build step-budget plan hint", throwable)
            reason
        }
    }
}
