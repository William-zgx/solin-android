package com.bytedance.zgx.solin.debug

import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.data.ChatMessageEntity
import com.bytedance.zgx.solin.orchestration.AgentRun
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.AgentStep
import com.bytedance.zgx.solin.orchestration.shortTraceText
import org.json.JSONArray
import org.json.JSONObject

/** Longest answer text the report carries; the benchmark only needs enough to check facts. */
internal const val REPORT_ANSWER_MAX_CHARS = 2_000

/**
 * Debug-only summary of one agent run for the phone task benchmark
 * (`scripts/run_phone_task_benchmark.py`). Carries counts, tool names, statuses, failure kinds and
 * the last confirmation's tool name and redacted draft title; never the prompt or tool arguments.
 *
 * For the benchmark it also carries `answer`: the final assistant reply from the chat transcript
 * ([transcriptAnswer]), capped at [REPORT_ANSWER_MAX_CHARS]. This debug report is only readable
 * through adb on the device and never leaves the device any other way; the runner checks the
 * answer on the host and does not write it to its results.
 *
 * Steps read back from Room arrive as [AgentStep.RestoredSummary], so both those and live typed
 * steps are folded into the same events before counting.
 */
internal fun agentRunReportJson(
    run: AgentRun?,
    steps: List<AgentStep>,
    transcriptAnswer: String? = null,
): JSONObject {
    if (run == null) return JSONObject().put("runId", JSONObject.NULL)
    val events = steps.mapNotNull { step -> step.toReportEvent() }
    val requested = events.filterIsInstance<ReportEvent.ToolRequested>()
    val resultsByRequestId = events.filterIsInstance<ReportEvent.ToolResult>()
        .associateBy { result -> result.requestId }
    val toolCalls = JSONArray()
    requested.forEach { call ->
        val result = resultsByRequestId[call.requestId]
        toolCalls.put(
            JSONObject()
                .put("tool", call.toolName)
                .put("status", result?.status ?: "Pending")
                .put("failureKind", result?.failureKind ?: JSONObject.NULL),
        )
    }
    val statuses = requested.map { call -> resultsByRequestId[call.requestId]?.status }
    val lastFailed = statuses.indexOfLast { status -> status == "Failed" || status == "Rejected" }
    val gaveUp = run.state == AgentRunState.Completed &&
        lastFailed >= 0 &&
        statuses.drop(lastFailed + 1).none { status -> status == "Succeeded" }
    val confirmation = events.filterIsInstance<ReportEvent.ConfirmationRequested>().lastOrNull()
    val traceAnswer = events.filterIsInstance<ReportEvent.Answered>().lastOrNull()?.text
    val (answer, answerSource) = when {
        !transcriptAnswer.isNullOrBlank() -> transcriptAnswer to "transcript"
        !traceAnswer.isNullOrBlank() -> traceAnswer to "trace"
        else -> null to null
    }
    return JSONObject()
        .put("runId", run.id)
        .put("state", run.state.name)
        .put("terminal", run.state in REPORT_SETTLED_STATES)
        .put("durationMillis", run.updatedAtMillis - run.createdAtMillis)
        .put("toolCalls", toolCalls)
        .put("toolCallCount", requested.size)
        .put("confirmationsRequested", events.count { it is ReportEvent.ConfirmationRequested })
        .put("questionsAsked", events.count { it is ReportEvent.QuestionAsked })
        .put(
            "criticalControlStops",
            resultsByRequestId.values.count { result -> result.failureKind == "dangerous_action" },
        )
        .put(
            "confirmation",
            confirmation?.let { JSONObject().put("functionName", it.toolName).put("title", it.title) }
                ?: JSONObject.NULL,
        )
        .put("gaveUp", gaveUp)
        .put("answer", answer?.take(REPORT_ANSWER_MAX_CHARS) ?: JSONObject.NULL)
        .put("answerSource", answerSource ?: JSONObject.NULL)
        .put("placement", events.filterIsInstance<ReportEvent.Placement>().lastOrNull()?.name ?: JSONObject.NULL)
        .put("failed", events.any { it is ReportEvent.Failed })
}

/**
 * The reply to the latest user turn: the last assistant message after the last user message, or
 * null while that turn has no reply yet (so an earlier task's answer is never reused).
 */
internal fun transcriptAnswer(messages: List<ChatMessageEntity>): String? {
    val ordered = messages.sortedBy { message -> message.position }
    val lastUser = ordered.indexOfLast { message -> message.role == MessageRole.User.name }
    return ordered.drop(lastUser + 1)
        .lastOrNull { message -> message.role == MessageRole.Assistant.name }
        ?.text
        ?.takeIf { text -> text.isNotBlank() }
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

private sealed interface ReportEvent {
    data class ToolRequested(val requestId: String, val toolName: String) : ReportEvent
    data class ToolResult(val requestId: String, val status: String, val failureKind: String?) : ReportEvent
    data class ConfirmationRequested(val toolName: String, val title: String) : ReportEvent
    data object QuestionAsked : ReportEvent
    data class Answered(val text: String) : ReportEvent
    data class Placement(val name: String) : ReportEvent
    data object Failed : ReportEvent
}

private fun AgentStep.toReportEvent(): ReportEvent? = when (this) {
    is AgentStep.ToolRequested -> ReportEvent.ToolRequested(request.id, request.toolName)
    is AgentStep.ToolObserved -> ReportEvent.ToolResult(result.requestId, result.status.name, result.data["failureKind"])
    is AgentStep.ToolRejected -> ReportEvent.ToolResult(result.requestId, result.status.name, result.data["failureKind"])
    is AgentStep.UserConfirmationRequested -> ReportEvent.ConfirmationRequested(
        toolName = request.toolName,
        title = draft.title.shortTraceText(),
    )
    is AgentStep.UserQuestionAsked -> ReportEvent.QuestionAsked
    is AgentStep.AssistantResponded -> ReportEvent.Answered(text)
    is AgentStep.PlacementSelected -> ReportEvent.Placement(binding.placement.name)
    is AgentStep.Failed -> ReportEvent.Failed
    is AgentStep.RestoredSummary -> restoredReportEvent()
    else -> null
}

/** Reads the allowlisted trace JSON a persisted step was saved with (see `AgentTraceStore.kt`). */
private fun AgentStep.RestoredSummary.restoredReportEvent(): ReportEvent? {
    val json = runCatching { JSONObject(json) }.getOrNull() ?: return null
    return when (persistedType) {
        "ToolRequested" -> ReportEvent.ToolRequested(json.optString("requestId"), json.optString("toolName"))
        "ToolObserved", "ToolRejected" -> ReportEvent.ToolResult(
            requestId = json.optString("requestId"),
            status = json.optString("status"),
            failureKind = json.optJSONObject("completionMetadata")
                ?.optString("failureKind")
                ?.takeIf { kind -> kind.isNotBlank() },
        )
        "UserConfirmationRequested" -> ReportEvent.ConfirmationRequested(
            toolName = json.optString("toolName"),
            title = json.optString("draftTitle"),
        )
        "UserQuestionAsked" -> ReportEvent.QuestionAsked
        "AssistantResponded" -> json.optString("textPreview").takeIf { it.isNotBlank() }?.let(ReportEvent::Answered)
        "PlacementSelected" -> json.optString("placement").takeIf { it.isNotBlank() }?.let(ReportEvent::Placement)
        "Failed" -> ReportEvent.Failed
        else -> null
    }
}
