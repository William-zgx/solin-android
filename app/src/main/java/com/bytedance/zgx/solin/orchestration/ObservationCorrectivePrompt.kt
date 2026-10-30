package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.tool.ToolRegistry

/**
 * The single corrective re-prompt [ModelObservationReplanner] issues when the local action model
 * produced a draft that an acceptance guard rejected. It states the rejection reason and the
 * allowed target shortlist so a small model can fix its own mistake; the corrected draft goes
 * through exactly the same guards. LOCAL model only — this prompt may quote LocalOnly screen targets.
 */
internal const val CORRECTIVE_OBSERVATION_PROMPT_INDEX = 2
internal const val MAX_CORRECTIVE_PROMPT_CHARS = 1_200
private const val MAX_CORRECTIVE_SHORTLIST_VALUES = 8
private const val MAX_CORRECTIVE_TARGET_CHARS = 48

/**
 * Guard rejections a corrected draft could legitimately fix. `dangerous_observation_action` is
 * deliberately absent: a screen with payment/sending/deletion controls fails closed, it is never
 * re-prompted.
 */
private val correctableGuardRejections = mapOf(
    "non_local_observation_tool" to "只能使用下列本机屏幕控制工具",
    "missing_required_ui_target" to "ui_tap 必须带 target",
    "text_only_ui_control_without_structured_target" to "当前只有屏幕文字，没有可执行的结构化目标",
    "unsupported_submit_search" to "当前屏幕没有可提交搜索的证据",
    "unsupported_targetless_typing" to "输入文字必须指定输入框 target",
    "unsupported_observed_target" to "target 不在当前屏幕候选中",
    "unsupported_repeat_target" to "上一步对同一 target 已失败",
)

internal fun String.isCorrectableGuardRejection(): Boolean = this in correctableGuardRejections

internal fun AgentObservationReplanContext.correctiveObservationModelPrompt(
    toolRegistry: ToolRegistry,
    rejectedDraft: ActionDraft,
    rejectionReason: String,
    parseCache: ObservationJsonParseCache? = null,
): String {
    val intentPreview = (nextActionInput?.immediateSequentialActionText() ?: run.input)
        .shortTraceText(maxLength = 120)
    val allowedTools = localOnlyObservationAllowedToolsPrompt(toolRegistry)
    val shortlist = observedResult.localObservationEvidencePrompt(intentPreview, parseCache)
        .targetShortlistTargetValues()
        .take(MAX_CORRECTIVE_SHORTLIST_VALUES)
        .map { value -> value.shortTraceText(maxLength = MAX_CORRECTIVE_TARGET_CHARS) }
    val shortlistText = if (shortlist.isEmpty()) {
        "无；不要输出需要 target 的工具"
    } else {
        shortlist.joinToString(separator = " | ")
    }
    val rejectedCall = rejectedDraft.parameters["target"]
        ?.takeIf { target -> target.isNotBlank() }
        ?.let { target -> "${rejectedDraft.functionName}{target=${target.shortTraceText(MAX_CORRECTIVE_TARGET_CHARS)}}" }
        ?: rejectedDraft.functionName
    val explanation = correctableGuardRejections[rejectionReason] ?: "未通过本机校验"
    return """
        本机已获得用户授权执行手机 UI 自动化。上一个输出被本机校验拒绝；请改正后只输出一个 call，无法改正时输出不含 call 的普通文本。
        被拒绝: $rejectedCall 原因: $explanation ($rejectionReason)
        不要再输出被拒绝的调用。
        允许工具: $allowedTools
        用户请求: $intentPreview
        当前进度: ${observedResult.appSearchProgressPromptText(parseCache)}
        可用 target（只能原样复制其一）: $shortlistText
        格式: call:ui_tap{"target":"候选target"}
    """.trimIndent().let { prompt ->
        if (prompt.length <= MAX_CORRECTIVE_PROMPT_CHARS) prompt else prompt.take(MAX_CORRECTIVE_PROMPT_CHARS)
    }
}
