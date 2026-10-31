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
        if (mutatingRequests.size >= maxRepeatActions && !context.deadLoopReplanAlreadyForced()) {
            val recent = mutatingRequests.takeLast(maxRepeatActions)
            val lastRequest = mutatingRequests.last()
            val allSame = recent.all { it.sameActionAs(lastRequest) }
            // Scrolls: directional scroll loops are owned by Rule 3 (which reverses direction), and
            // a scroll that is still changing the screen is progress, not a loop.
            val deferScrollToRule3 = lastRequest.toolName == MobileActionFunctions.UI_SCROLL &&
                (lastRequest.arguments["direction"] != null || context.scrollMadeProgress(lastRequest))

            if (allSame && !deferScrollToRule3) {
                val lastTool = lastRequest.toolName
                // Force a back press to break the loop
                return forcedReplan(
                    toolName = MobileActionFunctions.UI_PRESS_BACK,
                    title = "强制返回",
                    summary = "检测到重复操作死循环（连续 $maxRepeatActions 次相同动作：$lastTool），强制返回上一页",
                    arguments = emptyMap(),
                    reason = "dead_loop: repeated $lastTool",
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
                return forcedReplan(
                    toolName = MobileActionFunctions.UI_PRESS_BACK,
                    title = "强制返回",
                    summary = "连续 $maxSameScreen 次观测到同一屏幕且无进展，强制返回上一页以打破停滞",
                    arguments = emptyMap(),
                    reason = "dead_loop: same screen x$maxSameScreen",
                )
            }
        }

        // ── Rule 3: Repeated scrolls in same direction ─────────────────────
        val scrollRequests = priorRequests.filter { it.toolName == MobileActionFunctions.UI_SCROLL }
        if (
            scrollRequests.size >= AgentSurvivalRules.MAX_SCROLL_SAME_DIRECTION &&
            !context.deadLoopReplanAlreadyForced()
        ) {
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
                    forcedReplan(
                        toolName = MobileActionFunctions.UI_SCROLL,
                        title = "反向滚动",
                        summary = "连续 ${AgentSurvivalRules.MAX_SCROLL_SAME_DIRECTION} 次向 $direction 方向滚动未找到新内容，换 $oppositeDirection 方向",
                        arguments = mapOf("direction" to oppositeDirection),
                        reason = reason,
                    )
                } else {
                    forcedReplan(
                        toolName = MobileActionFunctions.UI_PRESS_BACK,
                        title = "强制返回",
                        summary = "连续 ${AgentSurvivalRules.MAX_SCROLL_SAME_DIRECTION} 次向 $direction 方向滚动未找到新内容，强制返回",
                        arguments = emptyMap(),
                        reason = reason,
                    )
                }
            }
        }

        return null // No dead loop detected
    }

    private fun forcedReplan(
        toolName: String,
        title: String,
        summary: String,
        arguments: Map<String, String>,
        reason: String,
    ): AgentObservationReplan {
        val draft = ActionDraft(
            functionName = toolName,
            title = title,
            summary = summary,
            parameters = arguments,
        )
        val request = ToolRequest(
            toolName = toolName,
            arguments = arguments,
            reason = reason,
        )
        return AgentObservationReplan(
            request = request,
            draft = draft,
            plannedByModel = false,
            fallbackReason = reason,
        )
    }

    /**
     * True when this replanner already forced a corrective step after the last state-mutating
     * request, i.e. the newest request carries a `dead_loop:` reason.
     *
     * The forced back press is itself NOT a state-mutating tool (see [STATE_MUTATING_UI_TOOLS]),
     * so the repeat counts that triggered the rule stay satisfied on the next step. Without this
     * check the replanner would force the same correction again and again instead of handing the
     * step back to the model — the old `lastTool == UI_PRESS_BACK` guard could never fire for that
     * same reason and was dead code.
     */
    private fun AgentObservationReplanContext.deadLoopReplanAlreadyForced(): Boolean {
        val lastMutatingIndex = priorRequests.indexOfLast { it.toolName in STATE_MUTATING_UI_TOOLS }
        return priorRequests.drop(lastMutatingIndex + 1)
            .any { request -> request.reason.startsWith(DEAD_LOOP_REASON_PREFIX) }
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

    /**
     * Two requests are "the same action" only when they target the same tool AND the same target.
     *
     * The key must fall back to the coordinates for remote-vision taps: those carry only
     * `targetX`/`targetY` and no textual `target`, so a key built from text alone made every such
     * tap look like the same target and forced a spurious back press after three unrelated taps.
     * Typed text is part of the key too — three different strings typed into the same field is not
     * a dead loop.
     */
    private fun ToolRequest.sameActionAs(other: ToolRequest): Boolean =
        toolName == other.toolName && actionTargetKey() == other.actionTargetKey()

    private fun ToolRequest.actionTargetKey(): String {
        val target = arguments["target"]
            ?: arguments["query"]
            ?: arguments["appName"]
            ?: "${arguments["targetX"]},${arguments["targetY"]}"
        return if (toolName == MobileActionFunctions.UI_TYPE_TEXT) {
            "$target|text=${arguments["text"]}"
        } else {
            target
        }
    }

    companion object {
        /** Reason prefix carried by a forced corrective step from this replanner. */
        private const val DEAD_LOOP_REASON_PREFIX = "dead_loop:"

        /**
         * UI tools that mutate on-screen state. Rule 1 (repeated-identical-action) only considers
         * these; observe/wait/back are excluded so re-observing or waiting during a load is not
         * mistaken for a dead loop (same-screen stalls are Rule 2's responsibility). Excluding
         * back is also why the "already forced a back press" check keys on the request reason
         * rather than on the last tool name.
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
