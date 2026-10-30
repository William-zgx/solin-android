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
 * 3. Same scroll direction repeated without screen progress → force direction change or back
 *
 * Scroll progress is judged from [AgentObservationReplanContext.screenObservationHistory]: a scroll
 * whose post-action fingerprint differs from the fingerprint observed just before it is "making
 * progress" (e.g. walking down a long list) and must not be reversed. Identical fingerprints, or no
 * fingerprint at all for the scroll, keep the original fail-safe behaviour (reverse/back).
 */
class DeadLoopDetectionReplanner(
    private val maxRepeatActions: Int = AgentSurvivalRules.MAX_REPEAT_ACTIONS,
    private val maxSameScreen: Int = AgentSurvivalRules.MAX_SAME_SCREEN_OBSERVATIONS,
) : AgentObservationReplanner {

    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? {
        val priorRequests = context.priorRequests

        // ── Rule 1: Repeated identical STATE-MUTATING actions ───────────────
        // Only tap/type/submit/scroll count. Repeated observe/wait are NOT a dead loop — an agent
        // legitimately re-observes or waits while a screen loads; detecting a frozen screen across
        // such calls is Rule 2's job (screen-fingerprint comparison), which correctly distinguishes
        // an advancing screen from a stuck one. Treating observe/wait as "same action" here would
        // force a spurious back-press mid-load.
        val mutatingRequests = priorRequests.filter { it.toolName in STATE_MUTATING_UI_TOOLS }
        if (mutatingRequests.size >= maxRepeatActions) {
            val recent = mutatingRequests.takeLast(maxRepeatActions)
            val lastRequest = mutatingRequests.last()
            val allSame = recent.all { it.toolName == lastRequest.toolName &&
                it.sameTargetAs(lastRequest) }
            // Scrolls: directional scroll loops are owned by Rule 3 (which reverses direction), and
            // a scroll that is still changing the screen is progress, not a loop.
            val deferScrollToRule3 = lastRequest.toolName == MobileActionFunctions.UI_SCROLL &&
                (lastRequest.arguments["direction"] != null || context.scrollMadeProgress(lastRequest))

            if (allSame && !deferScrollToRule3) {
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
        // The runtime now supplies screen-observation fingerprints via
        // AgentObservationReplanContext. When the last [maxSameScreen] observations describe
        // the same surface (package + text digest + element count) despite intervening
        // actions, the run is stuck on a frozen screen — force a back press to break out.
        val observations = context.screenObservationHistory
        if (observations.size >= maxSameScreen) {
            val recentObs = observations.takeLast(maxSameScreen)
            val newest = recentObs.last()
            val allSameScreen = recentObs.all { it.sameSurfaceAs(newest) }
            if (allSameScreen && context.previousRequest.toolName != MobileActionFunctions.UI_PRESS_BACK) {
                val reason = "dead_loop: same screen x$maxSameScreen"
                val draft = ActionDraft(
                    functionName = MobileActionFunctions.UI_PRESS_BACK,
                    title = "强制返回",
                    summary = "连续 $maxSameScreen 次观测到同一屏幕且无进展，强制返回上一页以打破停滞",
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

        // ── Rule 3: Repeated scrolls in same direction ─────────────────────
        val scrollRequests = priorRequests.filter { it.toolName == MobileActionFunctions.UI_SCROLL }
        if (scrollRequests.size >= AgentSurvivalRules.MAX_SCROLL_SAME_DIRECTION) {
            val recentScrolls = scrollRequests.takeLast(AgentSurvivalRules.MAX_SCROLL_SAME_DIRECTION)
            val direction = recentScrolls.last().arguments["direction"]
            if (
                direction != null &&
                recentScrolls.all { it.arguments["direction"] == direction } &&
                !context.scrollMadeProgress(recentScrolls.last())
            ) {
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

    /**
     * True only when [scroll] has its own fingerprint in the history AND that fingerprint differs from
     * the screen observed immediately before it. Missing evidence is NOT progress (fail-safe: the
     * original reverse/back behaviour applies).
     */
    private fun AgentObservationReplanContext.scrollMadeProgress(scroll: ToolRequest): Boolean {
        val history = screenObservationHistory
        val index = history.indexOfLast { fingerprint -> fingerprint.requestId == scroll.id }
        if (index <= 0) return false
        return !history[index].sameSurfaceAs(history[index - 1])
    }

    private fun ToolRequest.sameTargetAs(other: ToolRequest): Boolean {
        val thisTarget = arguments["target"] ?: arguments["query"] ?: arguments["appName"] ?: ""
        val otherTarget = other.arguments["target"] ?: other.arguments["query"] ?: other.arguments["appName"] ?: ""
        return thisTarget == otherTarget
    }

    companion object {
        /**
         * UI tools that mutate on-screen state. Rule 1 (repeated-identical-action) only considers
         * these; observe/wait/back are excluded so re-observing or waiting during a load is not
         * mistaken for a dead loop (same-screen stalls are Rule 2's responsibility).
         */
        private val STATE_MUTATING_UI_TOOLS = setOf(
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_SCROLL,
        )

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
 * [textSummary] is a stable digest of the screen text, not the text itself.
 */
data class ScreenObservationFingerprint(
    val packageName: String?,
    val textSummary: String?,
    val elementCount: Int,
    val capturedAtMillis: Long,
    /** Id of the tool request whose observed result produced this fingerprint, when known. */
    val requestId: String? = null,
) {
    /**
     * Whether two fingerprints describe the same surface with no progress. Compares package,
     * text digest, and element count; deliberately ignores [capturedAtMillis] so that merely
     * re-observing the same frozen screen counts as "same".
     */
    fun sameSurfaceAs(other: ScreenObservationFingerprint): Boolean =
        packageName == other.packageName &&
            textSummary == other.textSummary &&
            elementCount == other.elementCount
}
