package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.UiScrollDirection
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_REMOTE
import com.bytedance.zgx.solin.logging.solinD
import com.bytedance.zgx.solin.tool.ToolRequest
import kotlin.math.abs

private const val REMOTE_VISION_REPLAN_REQUEST_REASON = MODEL_OBSERVATION_REPLAN_REQUEST_REASON
private const val REMOTE_VISION_REPLAN_FALLBACK_REASON = "remote vision observation replan"
private const val DEFAULT_MAX_REMOTE_VISION_REPLANS = 5

/**
 * Two taps whose normalized coordinates differ by no more than this on both axes are the "same target".
 * A vision model re-deciding on an unchanged screenshot jitters by a few units; ~1.5% of the screen is
 * well inside a single control and well below the distance between two neighbouring list rows.
 */
internal const val REMOTE_VISION_SAME_TAP_TOLERANCE = 15

/** The scroll directions the remote vision model may ask for (forward/backward are local-only). */
internal val REMOTE_VISION_SCROLL_DIRECTIONS = setOf(
    UiScrollDirection.Up,
    UiScrollDirection.Down,
    UiScrollDirection.Left,
    UiScrollDirection.Right,
)

/**
 * A single decision the remote vision model returns after "seeing" the current screenshot.
 *
 * The model's output is an UNTRUSTED suggestion — the same trust posture as the local action model,
 * just a different backend. It is parsed into a closed vocabulary (tap at normalized 0-1000
 * coordinates, system back, scroll up/down/left/right, stop) and turned into a LOCAL `ui_tap` /
 * `ui_press_back` / `ui_scroll` draft that re-enters the normal plan→confirm→execute path with all
 * device-control preflights intact (dangerous-action, foreground-package). The model never receives a
 * tool schema and never emits a tool_call.
 */
sealed interface RemoteVisionDecision {
    /** Tap the given normalized 0-1000 coordinate. */
    data class Tap(val normalizedX: Int, val normalizedY: Int) : RemoteVisionDecision

    /** Press system back (leave a wrong page, close a sheet). */
    data object Back : RemoteVisionDecision

    /**
     * Scroll the current screen. [direction] is always one of [REMOTE_VISION_SCROLL_DIRECTIONS]; the
     * parser rejects anything else. `down` reveals content further down, as for `ui_scroll`.
     */
    data class Scroll(val direction: UiScrollDirection) : RemoteVisionDecision

    /** No further action — the task is complete, or the model declined (e.g. a dangerous control). */
    data object Stop : RemoteVisionDecision

    /**
     * The decision could not be produced this step (no consent, capture failed, vision send failed,
     * unparseable output). Fail closed: the loop stops rather than guessing. [reason] is a
     * non-sensitive diagnostic label for logs/audit only.
     */
    data class Unavailable(val reason: String) : RemoteVisionDecision
}

/**
 * Captures the current screen, sends the screenshot to the remote vision model, and parses its
 * decision. This is the boundary-crossing, coroutine-blocking, Android-dependent half of
 * the remote-vision loop — isolated behind an interface so [RemoteVisionObservationReplanner]'s
 * gating and ordering logic stays pure and unit-testable with a fake.
 *
 * Implementations MUST fail closed: return [RemoteVisionDecision.Unavailable] (never throw, never
 * a default Tap) when consent is missing, capture fails, the send fails, or output is unparseable.
 */
fun interface RemoteVisionDecider {
    /**
     * @param intent the user's request preview (for the decision prompt)
     * @param requestId a correlation/log id for this capture (the accessibility capture path needs
     *   no per-request consent; egress is gated by the opt-in + first-confirm, not by requestId)
     */
    fun decide(intent: String, requestId: String): RemoteVisionDecision
}

/**
 * Drives in-app GUI automation with a remote vision model (e.g. kimi-k2.5) instead of the local
 * action model. Occupies the same [AgentObservationReplanner] slot as [ModelObservationReplanner]
 * in the [CompositeAgentObservationReplanner], inserted AFTER dead-loop detection and BEFORE the
 * local model replanner: when remote-GUI driving is active it outranks the local 270M/e2b model;
 * when the gate is off it returns null so the local model still runs in Local/Auto mode.
 *
 * Unlike the RemoteToolScope path, the remote model here is a pure perception+decision oracle: it
 * sees a screenshot and returns a tap / back / scroll / stop decision, which becomes a LOCAL
 * `ui_tap` / `ui_press_back` / `ui_scroll` executed through the unchanged device-control executor.
 * See [com.bytedance.zgx.solin.multimodal.RemoteVisionScreenshotContract].
 *
 * Step history stays LOCAL. The remote model is never sent prior steps (that would be new egress the
 * user has not approved); instead the run's local trace — the previous request and the screen
 * fingerprints [AgentObservationReplanContext.screenObservationHistory] — is used here to refuse a
 * decision that merely repeats the last action on a screen that action did not change (a stuck loop
 * the stateless model cannot see).
 *
 * @param decider the capture→send→parse seam (Android/coroutine/network isolated behind it)
 * @param gateProvider true only when inferenceMode==Remote AND the remote-GUI opt-in is on AND the
 *   remote config declares vision support. Returns null (local model takes over) when false.
 * @param executeDecisions when false (Phase 2, read-only), a parsed action is logged but NOT turned
 *   into a replan (returns null) — used to validate perception + gate ordering without touching the
 *   device. When true (Phase 3+), an action becomes a real local UI-action replan.
 */
class RemoteVisionObservationReplanner(
    private val decider: RemoteVisionDecider,
    private val gateProvider: () -> Boolean,
    private val executeDecisions: Boolean = false,
    maxReplans: Int = DEFAULT_MAX_REMOTE_VISION_REPLANS,
    private val logger: (String) -> Unit = { message -> solinD(TAG_REMOTE, message) },
) : AgentObservationReplanner {
    private val replanLimit = maxReplans.coerceAtLeast(0)

    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? {
        if (!gateProvider()) return null
        if (replanLimit == 0) return null
        if (!context.observedResult.isModelReplannableObservation()) return null
        // First-confirm-then-auto: the screenshot must not leave the device until the user has
        // confirmed this run's first action. The decider performs the capture + remote send, so we
        // gate BEFORE calling it. Once confirmed, subsequent captures in the same run auto-proceed
        // (bounded by replanLimit); a new run/session starts unconfirmed again (fail closed).
        if (!context.runHasUserConfirmedStep) {
            logger("remote-vision: run not yet user-confirmed — no screenshot sent (fail closed)")
            return null
        }
        if (context.modelObservationReplanCount() >= replanLimit) {
            logger("remote-vision: replan limit ($replanLimit) reached")
            return null
        }
        val intent = (context.nextActionInput?.immediateSequentialActionText() ?: context.run.input)
        val replan = when (val decision = decider.decide(intent = intent, requestId = context.previousRequest.id)) {
            is RemoteVisionDecision.Tap -> {
                logger("remote-vision: decision tap (${decision.normalizedX},${decision.normalizedY})")
                context.toTapReplan(decision)
            }

            RemoteVisionDecision.Back -> {
                logger("remote-vision: decision back")
                toBackReplan()
            }

            is RemoteVisionDecision.Scroll -> {
                logger("remote-vision: decision scroll (${decision.direction.schemaValue})")
                context.toScrollReplan(decision)
            }

            RemoteVisionDecision.Stop -> {
                logger("remote-vision: decision stop")
                return null
            }

            is RemoteVisionDecision.Unavailable -> {
                logger("remote-vision: unavailable (${decision.reason}) — fail closed, loop stops")
                return null
            }
        }
        if (!executeDecisions) {
            logger("remote-vision: read-only mode — ${replan.request.toolName} not executed")
            return null
        }
        if (context.repeatsLastActionWithoutScreenProgress(replan.request)) {
            logger(
                "remote-vision: repeated ${replan.request.toolName} on an unchanged screen — " +
                    "fail closed, loop stops",
            )
            return null
        }
        return replan
    }

    /**
     * Builds a LOCAL `ui_tap` request from the remote model's normalized coordinates. Propagates the
     * inherited expected-foreground-package exactly as [ModelObservationReplanner] does, so the tap's
     * foreground-package preflight fires on a REMOTE-planned continuation where open_app_by_name
     * carried only appName. The model's decision is untrusted; the local executor still runs the
     * dangerous-action and foreground preflights before the tap.
     */
    private fun AgentObservationReplanContext.toTapReplan(
        decision: RemoteVisionDecision.Tap,
    ): AgentObservationReplan {
        val arguments = buildMap {
            put("targetX", decision.normalizedX.toString())
            put("targetY", decision.normalizedY.toString())
            inheritedForegroundPackage()?.let { put("expectedPackageName", it) }
        }
        return remoteVisionReplan(
            toolName = MobileActionFunctions.UI_TAP,
            title = "点击坐标",
            summary = "远程视觉模型规划点击坐标 (${decision.normalizedX}, ${decision.normalizedY})。",
            arguments = arguments,
        )
    }

    /**
     * Builds a LOCAL `ui_scroll`. Scroll is foreground-gated like tap, so it inherits the expected
     * package the same way; the executor's dangerous-action preflight and (for the gesture fallback)
     * fail-closed screen check still run.
     */
    private fun AgentObservationReplanContext.toScrollReplan(
        decision: RemoteVisionDecision.Scroll,
    ): AgentObservationReplan {
        val arguments = buildMap {
            put("direction", decision.direction.schemaValue)
            inheritedForegroundPackage()?.let { put("expectedPackageName", it) }
        }
        return remoteVisionReplan(
            toolName = MobileActionFunctions.UI_SCROLL,
            title = "滚动屏幕",
            summary = "远程视觉模型规划滚动屏幕：${decision.direction.schemaValue}。",
            arguments = arguments,
        )
    }

    /**
     * Builds a LOCAL `ui_press_back`. Back operates on no content and has no foreground gate (its
     * schema accepts no package binding), matching how [ModelObservationReplanner] treats it.
     */
    private fun toBackReplan(): AgentObservationReplan =
        remoteVisionReplan(
            toolName = MobileActionFunctions.UI_PRESS_BACK,
            title = "系统返回",
            summary = "远程视觉模型规划执行系统返回。",
            arguments = emptyMap(),
        )

    private fun AgentObservationReplanContext.inheritedForegroundPackage(): String? =
        previousRequest.arguments["expectedPackageName"]?.trim()?.takeIf { it.isNotBlank() }
            ?: previousRequest.arguments["targetPackageName"]?.trim()?.takeIf { it.isNotBlank() }
            ?: inheritedExpectedForegroundPackage

    private fun remoteVisionReplan(
        toolName: String,
        title: String,
        summary: String,
        arguments: Map<String, String>,
    ): AgentObservationReplan =
        AgentObservationReplan(
            request = ToolRequest(
                toolName = toolName,
                arguments = arguments,
                reason = REMOTE_VISION_REPLAN_REQUEST_REASON,
            ),
            draft = ActionDraft(
                functionName = toolName,
                title = title,
                summary = summary,
                parameters = arguments,
            ),
            plannedByModel = true,
            fallbackReason = REMOTE_VISION_REPLAN_FALLBACK_REASON,
        )
}

/**
 * True when [candidate] repeats the model-planned action that just ran ([AgentObservationReplanContext.previousRequest])
 * and that action left the screen unchanged — i.e. issuing it again cannot make progress.
 *
 * Screen progress is read from the run's local fingerprint history: the newest fingerprint is the
 * screen after the previous action, the one before it the screen the previous action was planned on.
 * Fewer than two fingerprints means progress cannot be shown, which counts as NO progress (fail
 * closed): a repeated action is only allowed when the local trace proves the screen moved.
 */
internal fun AgentObservationReplanContext.repeatsLastActionWithoutScreenProgress(candidate: ToolRequest): Boolean {
    val previous = previousRequest
    if (previous.reason != REMOTE_VISION_REPLAN_REQUEST_REASON) return false
    if (!previous.isSameRemoteVisionActionAs(candidate)) return false
    val history = screenObservationHistory
    if (history.size < 2) return true
    return history[history.lastIndex].sameSurfaceAs(history[history.lastIndex - 1])
}

private fun ToolRequest.isSameRemoteVisionActionAs(other: ToolRequest): Boolean {
    if (toolName != other.toolName) return false
    return when (toolName) {
        MobileActionFunctions.UI_TAP -> {
            val x = arguments["targetX"]?.trim()?.toIntOrNull()
            val y = arguments["targetY"]?.trim()?.toIntOrNull()
            val otherX = other.arguments["targetX"]?.trim()?.toIntOrNull()
            val otherY = other.arguments["targetY"]?.trim()?.toIntOrNull()
            x != null && y != null && otherX != null && otherY != null &&
                abs(x - otherX) <= REMOTE_VISION_SAME_TAP_TOLERANCE &&
                abs(y - otherY) <= REMOTE_VISION_SAME_TAP_TOLERANCE
        }

        MobileActionFunctions.UI_SCROLL ->
            arguments["direction"]?.trim()?.lowercase() == other.arguments["direction"]?.trim()?.lowercase()

        MobileActionFunctions.UI_PRESS_BACK -> true
        else -> false
    }
}
