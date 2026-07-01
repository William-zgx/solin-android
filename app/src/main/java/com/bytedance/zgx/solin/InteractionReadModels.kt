package com.bytedance.zgx.solin

import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.memory.MemoryRecallMode
import com.bytedance.zgx.solin.memory.MemoryRecordType
import com.bytedance.zgx.solin.orchestration.AgentRunEvent
import com.bytedance.zgx.solin.orchestration.AgentRunEventState

data class RunTimelineItemUiSummary(
    val id: String,
    val label: String,
    val state: String,
    val detail: String,
    val privacyLabel: String? = null,
    val riskLabel: String? = null,
)

data class MemoryEvidenceUiSummary(
    val id: String,
    val typeLabel: String,
    val recallLabel: String,
    val reasonLabel: String,
    val scoreLabel: String,
)

internal fun runTimelineSummariesFor(events: List<AgentRunEvent>): List<RunTimelineItemUiSummary> =
    events.mapIndexed { index, event ->
        RunTimelineItemUiSummary(
            id = "timeline-$index",
            label = event.userFacingLabel(),
            state = event.userFacingState().name,
            detail = event.userFacingDetail(),
            privacyLabel = event.privacyMarkers().joinToLabel(),
            riskLabel = event.riskMarkers().joinToLabel(),
        )
    }

internal fun memoryEvidenceSummariesFor(hits: List<MemoryHit>): List<MemoryEvidenceUiSummary> =
    hits.mapIndexed { index, hit ->
        MemoryEvidenceUiSummary(
            id = "memory-$index",
            typeLabel = hit.recordType.userFacingLabel(),
            recallLabel = when (hit.recallMode) {
                MemoryRecallMode.Semantic -> "语义召回"
                MemoryRecallMode.Lexical -> "轻量召回"
            },
            reasonLabel = when (hit.recallMode) {
                MemoryRecallMode.Semantic -> "语义相似"
                MemoryRecallMode.Lexical -> "关键词重合"
            },
            scoreLabel = hit.finalScore.userFacingScoreLabel(),
        )
    }

private fun AgentRunEvent.userFacingLabel(): String =
    when (this) {
        is AgentRunEvent.InputReceived -> "收到输入"
        is AgentRunEvent.ContextLoaded -> "加载上下文"
        is AgentRunEvent.PlanCreated -> "规划下一步"
        is AgentRunEvent.ConfirmationRequested -> "等待确认"
        is AgentRunEvent.ToolExecuted -> "执行工具"
        is AgentRunEvent.ObservationRecorded -> "观察结果"
        is AgentRunEvent.AnswerGenerated -> "生成回答"
        is AgentRunEvent.RunFailed -> "失败"
        is AgentRunEvent.RunCancelled -> "已取消"
    }

private fun AgentRunEvent.userFacingState(): AgentRunEventState =
    when (this) {
        is AgentRunEvent.InputReceived -> AgentRunEventState.InputReceived
        is AgentRunEvent.ContextLoaded -> AgentRunEventState.ContextLoaded
        is AgentRunEvent.PlanCreated -> AgentRunEventState.Planning
        is AgentRunEvent.ConfirmationRequested -> AgentRunEventState.AwaitingConfirmation
        is AgentRunEvent.ToolExecuted -> AgentRunEventState.Executing
        is AgentRunEvent.ObservationRecorded -> AgentRunEventState.Observing
        is AgentRunEvent.AnswerGenerated -> AgentRunEventState.Completed
        is AgentRunEvent.RunFailed -> AgentRunEventState.Failed
        is AgentRunEvent.RunCancelled -> AgentRunEventState.Cancelled
    }

private fun AgentRunEvent.userFacingDetail(): String =
    when (this) {
        is AgentRunEvent.InputReceived -> sourceLabel.safeUiText()
        is AgentRunEvent.ContextLoaded -> buildList {
            if (memoryHitCount > 0) add("本地记忆 $memoryHitCount 条")
            if (deviceContextIncluded) add("设备上下文")
        }.joinToString("、").ifBlank { "无额外上下文" }
        is AgentRunEvent.PlanCreated -> toolLabels.joinToString("、") { it.safeUiText() }
            .ifBlank { "无工具" }
        is AgentRunEvent.ConfirmationRequested -> actionLabel.safeUiText()
        is AgentRunEvent.ToolExecuted -> "${toolLabel.safeUiText()} · ${status.name}"
        is AgentRunEvent.ObservationRecorded -> observationLabel.safeUiText()
        is AgentRunEvent.AnswerGenerated -> outputLabel?.safeUiText() ?: "回答已生成"
        is AgentRunEvent.RunFailed -> reasonLabel.safeUiText()
        is AgentRunEvent.RunCancelled -> reasonLabel.safeUiText()
    }

private fun AgentRunEvent.privacyMarkers(): Set<*> =
    when (this) {
        is AgentRunEvent.InputReceived -> privacyMarkers
        is AgentRunEvent.ContextLoaded -> privacyMarkers
        is AgentRunEvent.ConfirmationRequested -> privacyMarkers
        is AgentRunEvent.ObservationRecorded -> privacyMarkers
        is AgentRunEvent.AnswerGenerated -> privacyMarkers
        is AgentRunEvent.PlanCreated,
        is AgentRunEvent.ToolExecuted,
        is AgentRunEvent.RunFailed,
        is AgentRunEvent.RunCancelled -> emptySet<Any>()
    }

private fun AgentRunEvent.riskMarkers(): Set<*> =
    when (this) {
        is AgentRunEvent.PlanCreated -> riskMarkers
        is AgentRunEvent.ConfirmationRequested -> riskMarkers
        is AgentRunEvent.ToolExecuted -> riskMarkers
        is AgentRunEvent.RunFailed -> riskMarkers
        is AgentRunEvent.InputReceived,
        is AgentRunEvent.ContextLoaded,
        is AgentRunEvent.ObservationRecorded,
        is AgentRunEvent.AnswerGenerated,
        is AgentRunEvent.RunCancelled -> emptySet<Any>()
    }

private fun Set<*>.joinToLabel(): String? =
    map { marker -> marker.toString().substringAfterLast('.').markerLabel() }
        .takeIf { labels -> labels.isNotEmpty() }
        ?.joinToString("、")

private fun String.markerLabel(): String =
    when (this) {
        "LocalOnly" -> "仅本机"
        "ContainsLocalContext" -> "含本地上下文"
        "ThirdPartyOutput" -> "第三方输出"
        "UserMediated" -> "用户确认"
        "ExternalSideEffect" -> "外部副作用"
        "DestructiveAction" -> "高风险动作"
        "NetworkAccess" -> "网络访问"
        "NeedsHumanReview" -> "需要确认"
        else -> this
    }

private fun MemoryRecordType?.userFacingLabel(): String =
    when (this) {
        MemoryRecordType.Preference -> "偏好"
        MemoryRecordType.UserFact -> "事实"
        MemoryRecordType.TaskState -> "任务状态"
        MemoryRecordType.SuppressedTaskState -> "已隐藏任务状态"
        MemoryRecordType.Conversation -> "会话"
        null -> "记忆"
    }

private fun Float.userFacingScoreLabel(): String =
    when {
        this >= 0.75f -> "高相关"
        this >= 0.4f -> "中相关"
        else -> "低相关"
    }

private fun String.safeUiText(maxLength: Int = 80): String {
    val normalized = trim().replace(Regex("\\s+"), " ")
    if (normalized.isBlank()) return "无"
    if (UNSAFE_UI_TEXT_PATTERNS.any { pattern -> pattern.containsMatchIn(normalized) }) {
        return "[redacted]"
    }
    return normalized.take(maxLength)
}

private val UNSAFE_UI_TEXT_PATTERNS = listOf(
    Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
    Regex("(?i)\\b(password|passwd|secret|token|api[_ -]?key|credential|private)\\b\\s*[:=]?\\s*\\S*"),
    Regex("(?i)\\b(rm\\s+-rf|curl\\s+|wget\\s+|bash\\s+-c|sh\\s+-c|powershell\\s+|sudo\\s+|chmod\\s+|eval\\s*\\(|exec\\s*\\(|adb\\s+shell|am\\s+start)\\b"),
    Regex("(?i)\\bhttps?://\\S+"),
    Regex("[{}]"),
)
