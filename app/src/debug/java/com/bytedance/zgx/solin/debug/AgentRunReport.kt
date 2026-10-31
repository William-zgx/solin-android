package com.bytedance.zgx.solin.debug

import com.bytedance.zgx.solin.orchestration.AgentRun
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.AgentStep
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only summary of one agent run for the phone task benchmark
 * (`scripts/run_phone_task_benchmark.py`). Carries counts, tool names, statuses and failure kinds;
 * never the prompt, tool arguments or result text.
 */
internal fun agentRunReportJson(run: AgentRun?, steps: List<AgentStep>): JSONObject {
    if (run == null) return JSONObject().put("runId", JSONObject.NULL)
    val requested = steps.filterIsInstance<AgentStep.ToolRequested>()
    val resultsByRequestId = steps.mapNotNull { step ->
        when (step) {
            is AgentStep.ToolObserved -> step.result
            is AgentStep.ToolRejected -> step.result
            else -> null
        }
    }.associateBy { result -> result.requestId }
    val toolCalls = JSONArray()
    requested.forEach { step ->
        val result = resultsByRequestId[step.request.id]
        toolCalls.put(
            JSONObject()
                .put("tool", step.request.toolName)
                .put("status", result?.status?.name ?: "Pending")
                .put("failureKind", result?.data?.get("failureKind") ?: JSONObject.NULL),
        )
    }
    val placement = steps.filterIsInstance<AgentStep.PlacementSelected>().lastOrNull()?.binding
    return JSONObject()
        .put("runId", run.id)
        .put("state", run.state.name)
        .put("terminal", run.state in REPORT_SETTLED_STATES)
        .put("durationMillis", run.updatedAtMillis - run.createdAtMillis)
        .put("toolCalls", toolCalls)
        .put("toolCallCount", requested.size)
        .put("confirmationsRequested", steps.count { it is AgentStep.UserConfirmationRequested })
        .put("questionsAsked", steps.count { it is AgentStep.UserQuestionAsked })
        .put(
            "criticalControlStops",
            resultsByRequestId.values.count { result -> result.data["failureKind"] == "dangerous_action" },
        )
        .put("placement", placement?.placement?.name ?: JSONObject.NULL)
        .put("failed", steps.any { it is AgentStep.Failed })
}

/** States a benchmark run can stop in: finished, or waiting on the user. */
internal val REPORT_SETTLED_STATES = setOf(
    AgentRunState.Completed,
    AgentRunState.Cancelled,
    AgentRunState.Failed,
    AgentRunState.AwaitingUserConfirmation,
    AgentRunState.AwaitingUserAnswer,
    AgentRunState.AwaitingExternalOutcome,
)
