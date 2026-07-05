package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRequest

/**
 * Hard dead-loop detection replanner that forces the agent to take a different approach
 * when it gets stuck in repetitive patterns.
 *
 * This is the HARD enforcement layer complementing [AgentSurvivalRules]'s SOFT guidance
 * in the system prompt. While the model is instructed to avoid loops, it doesn't always
 * comply — this replanner catches violations and forces corrective action.
 *
 * Detection rules:
 * 1. Same action repeated [maxRepeatActions] times with same target → force back
 * 2. Same screen observed [maxSameScreen] times without progress → (TODO: needs runtime support)
 * 3. Same scroll direction repeated → force direction change or back
 */
class DeadLoopDetectionReplanner(
    private val maxRepeatActions: Int = AgentSurvivalRules.MAX_REPEAT_ACTIONS,
    private val maxSameScreen: Int = AgentSurvivalRules.MAX_SAME_SCREEN_OBSERVATIONS,
) : AgentObservationReplanner {

    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? {
        val priorRequests = context.priorRequests

        // ── Rule 1: Repeated identical actions ──────────────────────────────
        if (priorRequests.size >= maxRepeatActions) {
            val recent = priorRequests.takeLast(maxRepeatActions)
            val lastRequest = priorRequests.last()
            val allSame = recent.all { it.toolName == lastRequest.toolName &&
                it.sameTargetAs(lastRequest) }

            if (allSame) {
                val lastTool = lastRequest.toolName

                // If repeating back press, let normal flow fail — we're at the root
                if (lastTool == MobileActionFunctions.UI_PRESS_BACK) {
                    return null
                }

                // Force a back press to break the loop
                val reason = "dead_loop: repeated $lastTool"
                val draft = ActionDraft(
                    functionName = MobileActionFunctions.UI_PRESS_BACK,
                    title = "强制返回",
                    summary = "检测到重复操作死循环（连续 $maxRepeatActions 次相同动作：$lastTool），强制返回上一页",
                    parameters = emptyMap(),
                )
                val request = ToolRequest(
                    toolName = MobileActionFunctions.UI_PRESS_BACK,
                    arguments = emptyMap(),
                    reason = reason,
                )
                return AgentObservationReplan(
                    request = request,
                    draft = draft,
                    plannedByModel = false,
                    fallbackReason = reason,
                )
            }
        }

        // ── Rule 2: Same screen observations ───────────────────────────────
        // TODO: Need priorScreenObservations from runtime — currently not
        // available in AgentObservationReplanContext. When the runtime provides
        // screen observation history, re-enable this rule:
        //   val observations = ... // runtime-provided List<ScreenObservationFingerprint>
        //   if (observations.size >= maxSameScreen) {
        //       val recentObs = observations.takeLast(maxSameScreen)
        //       val allSameScreen = recentObs.all { it.packageName == observations.last().packageName &&
        //           (it.textSummary ?: "").hashCode() == (observations.last().textSummary ?: "").hashCode() }
        //       if (allSameScreen) { /* force back */ }
        //   }

        // ── Rule 3: Repeated scrolls in same direction ─────────────────────
        val scrollRequests = priorRequests.filter { it.toolName == MobileActionFunctions.UI_SCROLL }
        if (scrollRequests.size >= AgentSurvivalRules.MAX_SCROLL_SAME_DIRECTION) {
            val recentScrolls = scrollRequests.takeLast(AgentSurvivalRules.MAX_SCROLL_SAME_DIRECTION)
            val direction = recentScrolls.last().arguments["direction"]
            if (direction != null && recentScrolls.all { it.arguments["direction"] == direction }) {
                val oppositeDirection = OPPOSITE_SCROLL_DIRECTIONS[direction]
                val reason = "dead_loop: repeated scroll $direction"

                return if (oppositeDirection != null) {
                    val draft = ActionDraft(
                        functionName = MobileActionFunctions.UI_SCROLL,
                        title = "反向滚动",
                        summary = "连续 ${AgentSurvivalRules.MAX_SCROLL_SAME_DIRECTION} 次向 $direction 方向滚动未找到新内容，换 $oppositeDirection 方向",
                        parameters = mapOf("direction" to oppositeDirection),
                    )
                    val request = ToolRequest(
                        toolName = MobileActionFunctions.UI_SCROLL,
                        arguments = mapOf("direction" to oppositeDirection),
                        reason = reason,
                    )
                    AgentObservationReplan(
                        request = request,
                        draft = draft,
                        plannedByModel = false,
                        fallbackReason = reason,
                    )
                } else {
                    val draft = ActionDraft(
                        functionName = MobileActionFunctions.UI_PRESS_BACK,
                        title = "强制返回",
                        summary = "连续 ${AgentSurvivalRules.MAX_SCROLL_SAME_DIRECTION} 次向 $direction 方向滚动未找到新内容，强制返回",
                        parameters = emptyMap(),
                    )
                    val request = ToolRequest(
                        toolName = MobileActionFunctions.UI_PRESS_BACK,
                        arguments = emptyMap(),
                        reason = reason,
                    )
                    AgentObservationReplan(
                        request = request,
                        draft = draft,
                        plannedByModel = false,
                        fallbackReason = reason,
                    )
                }
            }
        }

        return null // No dead loop detected
    }

    private fun ToolRequest.sameTargetAs(other: ToolRequest): Boolean {
        val thisTarget = arguments["target"] ?: arguments["query"] ?: arguments["appName"] ?: ""
        val otherTarget = other.arguments["target"] ?: other.arguments["query"] ?: other.arguments["appName"] ?: ""
        return thisTarget == otherTarget
    }

    companion object {
        private val OPPOSITE_SCROLL_DIRECTIONS = mapOf(
            "down" to "up",
            "up" to "down",
            "forward" to "backward",
            "backward" to "forward",
        )
    }
}

/**
 * Fingerprint of a screen observation used for dead-loop detection.
 * Carries only non-sensitive metadata — never raw text or element details.
 */
data class ScreenObservationFingerprint(
    val packageName: String?,
    val textSummary: String?,
    val elementCount: Int,
    val capturedAtMillis: Long,
)
