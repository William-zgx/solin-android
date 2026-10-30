package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionIntentConfidence
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.ToolRegistry
import java.util.concurrent.atomic.AtomicReference

// Replan budgets live in SolinConstants.AgentLoop so the worst-case cost of one run (tool steps x
// observation decisions x sequential tail x model replans) can be audited from one place. These
// aliases keep the local call sites readable without reintroducing a second source of truth.
private const val DEFAULT_MAX_SEQUENTIAL_ACTIONS = SolinConstants.AgentLoop.MAX_SEQUENTIAL_ACTIONS
private const val DEFAULT_MAX_MODEL_OBSERVATION_REPLANS =
    SolinConstants.AgentLoop.MAX_MODEL_OBSERVATION_REPLANS
private const val SEQUENTIAL_REPLAN_REQUEST_REASON = "Explicit sequential action step planned."

/**
 * Trace-visible marker that a request came from an observation-driven model replan.
 *
 * **This string is load-bearing, not cosmetic.** [modelObservationReplanCount] counts prior requests
 * carrying exactly this reason to enforce the model-replan budget, and the reason is persisted in the
 * agent trace — so the count survives process restarts, and *editing this text silently resets every
 * in-flight run's replan budget to zero* (the anti-loop gate reopens). [RemoteVisionObservationReplanner]
 * intentionally reuses the same constant: local and remote-vision replans share ONE budget, because
 * both drive the same observe → act loop and alternating between backends must not double it.
 *
 * If this ever needs to become a structured field, the migration must keep reading the legacy reason
 * string for already-persisted traces; a straight replacement is a silent budget reset.
 */
internal const val MODEL_OBSERVATION_REPLAN_REQUEST_REASON = "Observation model step planned."
private const val MODEL_OBSERVATION_REPLAN_FALLBACK_REASON = "observation model replan"
/** Fixed retention window for [ModelObservationReplanner]'s diagnostics ring buffer. */
private const val MAX_RETAINED_DIAGNOSTICS = 32

internal val MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES = setOf(
    MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
    MobileActionFunctions.UI_TAP,
    MobileActionFunctions.UI_TYPE_TEXT,
    MobileActionFunctions.UI_SUBMIT_SEARCH,
    MobileActionFunctions.UI_SCROLL,
    MobileActionFunctions.UI_SWIPE,
    MobileActionFunctions.UI_LONG_PRESS,
    MobileActionFunctions.UI_PRESS_KEY,
    MobileActionFunctions.UI_WAIT,
    MobileActionFunctions.UI_PRESS_BACK,
)

/**
 * UI actions whose executor enforces the expected-foreground-package preflight. When a replan
 * inherits an expected package from a post-launch continuation observe, only these actions should
 * carry it forward — observe/wait/back operate on no content and have no foreground gate.
 */
private val FOREGROUND_GATED_UI_ACTION_TOOL_NAMES = setOf(
    MobileActionFunctions.UI_TAP,
    MobileActionFunctions.UI_TYPE_TEXT,
    MobileActionFunctions.UI_SUBMIT_SEARCH,
    MobileActionFunctions.UI_SCROLL,
    MobileActionFunctions.UI_SWIPE,
    MobileActionFunctions.UI_LONG_PRESS,
    MobileActionFunctions.UI_PRESS_KEY,
)

private fun String.isForegroundGatedUiAction(): Boolean =
    this in FOREGROUND_GATED_UI_ACTION_TOOL_NAMES

/** The expected-foreground-package this request carries, using the same keys the executor reads. */
private fun ToolRequest.inheritableExpectedForegroundPackage(): String? =
    arguments["expectedPackageName"]?.trim()?.takeIf { it.isNotBlank() }
        ?: arguments["targetPackageName"]?.trim()?.takeIf { it.isNotBlank() }

private val defaultSequentialToolRegistry = ToolRegistry()

fun interface AgentObservationReplanner {
    fun planNext(context: AgentObservationReplanContext): AgentObservationReplan?
}

data class AgentObservationReplanContext(
    val run: AgentRun,
    val previousRequest: ToolRequest,
    val observedResult: ToolResult,
    val priorRequests: List<ToolRequest>,
    val nextActionInput: String? = null,
    val completedSegmentCount: Int = priorRequests.size,
    /**
     * Ordered fingerprints of the screen observations seen so far this run (oldest first).
     * Populated by [ToolPlanCoordinator] from the trace store's observed steps. Enables
     * [DeadLoopDetectionReplanner] Rule 2 (same-screen-no-progress) without giving replanners
     * raw screen text — each fingerprint carries only non-sensitive metadata.
     */
    val screenObservationHistory: List<ScreenObservationFingerprint> = emptyList(),
    /**
     * The just-launched target package resolved from the most recent unverified-launch open_app
     * result (targetPackage/packageName), threaded by [ToolPlanCoordinator] so a REMOTE-planned
     * continuation — whose open_app_by_name carries only appName and never sets followUpIntent, so
     * [ToolPlanCoordinator.planPostLaunchInAppContinuation] does not run and no expectedPackageName
     * reaches the replanned request — can still fail-close its foreground-gated tap/type/etc. on the
     * correct package. Null when there is no pending launch to bind to.
     */
    val inheritedExpectedForegroundPackage: String? = null,
    /**
     * True when this run already has at least one user-confirmed tool step. Gates the remote-vision
     * screenshot egress: [RemoteVisionObservationReplanner] only sends a screenshot off-device once
     * the user has confirmed the run's first action (open_app / the initial observe), giving the
     * "first confirm → then auto" behaviour. Recomputed statelessly from the trace each step by
     * [ToolPlanCoordinator], so authorization auto-resets on a new run (new session/mode/config).
     * Local replanners ignore this field. Defaults false → fail closed (no screenshot leaves).
     */
    val runHasUserConfirmedStep: Boolean = false,
    /**
     * Value-free outcome token (`ok` / `no_change` / `failed:<kind>`) per prior request id, derived
     * from the trace by [ToolPlanCoordinator]. Rendered next to each prior request in the local
     * replanner prompt so "avoid the same target if the last action failed" has evidence.
     */
    val priorStepOutcomes: Map<String, String> = emptyMap(),
    /**
     * LocalOnly per-run scratchpad notes (may quote screen content). Only delivered to
     * [LocalOnlyNotesObservationReplanner]s — every other replanner, including remote-vision, gets
     * this stripped via [scopedForReplanner]. Defaults empty (fail closed).
     */
    val localOnlyScratchpadNotes: List<String> = emptyList(),
)

data class AgentObservationReplan(
    val request: ToolRequest,
    val draft: ActionDraft,
    val plannedByModel: Boolean = false,
    val fallbackReason: String? = null,
    val input: String? = null,
) {
    init {
        require(request.toolName == draft.functionName) {
            "Replanned request and draft must target the same tool."
        }
    }
}

object NoOpAgentObservationReplanner : AgentObservationReplanner {
    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? = null
}

class CompositeAgentObservationReplanner(
    vararg delegates: AgentObservationReplanner,
) : AgentObservationReplanner {
    private val delegates = delegates.toList()

    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? =
        delegates.firstNotNullOfOrNull { delegate -> delegate.planNext(context.scopedForReplanner(delegate)) }
}

class ModelObservationReplanner(
    private val actionPlanningRuntime: ActionPlanningRuntime,
    private val actionModelPathProvider: () -> String? = { null },
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    maxModelReplans: Int = DEFAULT_MAX_MODEL_OBSERVATION_REPLANS,
) : LocalOnlyNotesObservationReplanner {
    private val modelReplanLimit = maxModelReplans.coerceAtLeast(0)
    private val lastDiagnostic = AtomicReference<ModelObservationReplanDiagnostic?>(null)

    /**
     * Bounded ring buffer of the most recent diagnostics.
     *
     * A replanner instance is process-scoped and shared across every run, and [planNext] records at
     * least one diagnostic per invocation. The previous unbounded `CopyOnWriteArrayList` therefore
     * grew for the lifetime of the process (a memory leak across a long session) AND degraded
     * quadratically, because each COW `add` copies the whole backing array — exactly the wrong shape
     * for an append-mostly diagnostic log. A fixed-capacity ArrayDeque under a private lock keeps
     * memory flat and appends O(1); [diagnosticSnapshots] keeps its existing contract (oldest-first
     * list copy), just truncated to the newest [MAX_RETAINED_DIAGNOSTICS] entries.
     */
    private val diagnostics = ArrayDeque<ModelObservationReplanDiagnostic>(MAX_RETAINED_DIAGNOSTICS)
    private val diagnosticsLock = Any()

    fun lastDiagnosticSnapshot(): ModelObservationReplanDiagnostic? = lastDiagnostic.get()

    /** Oldest-first copy of the retained diagnostics (at most [MAX_RETAINED_DIAGNOSTICS]). */
    fun diagnosticSnapshots(): List<ModelObservationReplanDiagnostic> =
        synchronized(diagnosticsLock) { diagnostics.toList() }

    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? {
        if (!context.observedResult.isModelReplannableObservation()) {
            recordDiagnostic(ModelObservationReplanDiagnostic.notAttempted("not_model_replannable_observation"))
            return null
        }
        if (modelReplanLimit == 0) {
            recordDiagnostic(ModelObservationReplanDiagnostic.notAttempted("model_replan_disabled"))
            return null
        }
        if (context.modelObservationReplanCount() >= modelReplanLimit) {
            recordDiagnostic(ModelObservationReplanDiagnostic.notAttempted("model_replan_limit_reached"))
            return null
        }
        val actionModelPath = actionModelPathProvider()?.takeIf { path -> path.isNotBlank() }
        if (actionModelPath == null) {
            recordDiagnostic(ModelObservationReplanDiagnostic.notAttempted("missing_action_model"))
            return null
        }
        // Invocation-local parse cache: the current observation JSON is read repeatedly across the
        // two prompt builds and every acceptance guard. One cache instance per planNext call keeps
        // parsing to once-per-shape; it is never shared beyond this invocation.
        val parseCache = ObservationJsonParseCache()
        // At most TWO model calls per invocation (unchanged worst case): the full prompt, then
        // EITHER the minimal prompt (the model produced no draft) OR one corrective re-prompt (the
        // model produced a draft that a guard rejected). The corrective draft re-runs every guard;
        // a second rejection fails closed — there is no third call and no loop.
        val fullPrompt = context.observationModelPrompt(toolRegistry, parseCache)
        val first = attempt(context, actionModelPath, fullPrompt, promptIndex = 0, parseCache)
        first.replan?.let { replan -> return replan }
        val secondPrompt: String
        val secondPromptIndex: Int
        if (first.producedModelDraft) {
            val rejectedDraft = first.rejectedDraft
            val reason = first.rejectionReason
            if (rejectedDraft == null || reason == null || !reason.isCorrectableGuardRejection()) return null
            secondPrompt = context.correctiveObservationModelPrompt(
                toolRegistry = toolRegistry,
                rejectedDraft = rejectedDraft,
                rejectionReason = reason,
                parseCache = parseCache,
            )
            secondPromptIndex = CORRECTIVE_OBSERVATION_PROMPT_INDEX
        } else {
            secondPrompt = context.minimalObservationModelPrompt(toolRegistry, parseCache)
            if (secondPrompt == fullPrompt) return null
            secondPromptIndex = 1
        }
        return attempt(context, actionModelPath, secondPrompt, secondPromptIndex, parseCache).replan
    }

    private fun attempt(
        context: AgentObservationReplanContext,
        actionModelPath: String,
        prompt: String,
        promptIndex: Int,
        parseCache: ObservationJsonParseCache,
    ): ModelObservationReplanAttempt {
        val planningResult = actionPlanningRuntime.plan(
            input = prompt,
            actionModelPath = actionModelPath,
        )
        val acceptance = context.acceptModelObservationReplan(planningResult, parseCache)
        recordDiagnostic(
            planningResult.toObservationReplanDiagnostic(
                promptIndex = promptIndex,
                reason = if (acceptance.replan != null) {
                    "accepted"
                } else {
                    acceptance.rejectionReason ?: "model_replan_not_accepted"
                },
            ),
        )
        return ModelObservationReplanAttempt(
            replan = acceptance.replan,
            producedModelDraft = planningResult.usedModel &&
                planningResult.plan.kind == ActionPlanKind.Draft &&
                planningResult.plan.draft != null,
            rejectedDraft = acceptance.rejectedDraft,
            rejectionReason = acceptance.rejectionReason,
        )
    }

    private fun recordDiagnostic(diagnostic: ModelObservationReplanDiagnostic) {
        lastDiagnostic.set(diagnostic)
        synchronized(diagnosticsLock) {
            diagnostics.addLast(diagnostic)
            // Evict from the front so the buffer always holds the newest window; a while-loop (not a
            // single removeFirst) keeps the invariant even if the cap is ever lowered at runtime.
            while (diagnostics.size > MAX_RETAINED_DIAGNOSTICS) {
                diagnostics.removeFirst()
            }
        }
    }

    private fun AgentObservationReplanContext.acceptModelObservationReplan(
        planningResult: ActionPlanningResult,
        parseCache: ObservationJsonParseCache,
    ): ModelObservationReplanAcceptance {
        if (!planningResult.usedModel) {
            return ModelObservationReplanAcceptance(
                rejectionReason = planningResult.modelFailureReason
                    ?: planningResult.fallbackReason
                    ?: "model_not_used",
            )
        }
        if (planningResult.plan.kind != ActionPlanKind.Draft) {
            return ModelObservationReplanAcceptance(
                rejectionReason = "model_plan_${planningResult.plan.kind.name.lowercase()}",
            )
        }
        val draft = planningResult.plan.draft
            ?.normalizedUiTargetDraft()
            ?.tapFirstForSearchTypingTarget(this, parseCache)
            ?: return ModelObservationReplanAcceptance(rejectionReason = "missing_model_draft")
        val rejectionReason = when {
            shouldRejectNonLocalObservationTool(draft, toolRegistry) -> "non_local_observation_tool"
            draft.hasMissingRequiredUiTarget() -> "missing_required_ui_target"
            shouldRejectDangerousObservationAction(draft, parseCache) -> "dangerous_observation_action"
            shouldRejectTextOnlyUiControl(draft) -> "text_only_ui_control_without_structured_target"
            shouldRejectUnsupportedSubmitSearch(draft, parseCache) -> "unsupported_submit_search"
            shouldRejectUnsupportedTargetlessTyping(draft, parseCache) -> "unsupported_targetless_typing"
            shouldRejectUnsupportedObservedTarget(draft, parseCache) -> "unsupported_observed_target"
            shouldRejectUnsupportedRepeatTarget(draft, parseCache) -> "unsupported_repeat_target"
            else -> null
        }
        if (rejectionReason != null) {
            return ModelObservationReplanAcceptance(rejectionReason = rejectionReason, rejectedDraft = draft)
        }
        // Propagate the expected foreground package from the triggering observe request onto the
        // replanned UI action so the executor's foreground-package preflight actually fires. Without
        // this the fail-closed binding threaded onto the post-launch continuation observe (see
        // ToolPlanCoordinator.planPostLaunchInAppContinuation) would be dropped here and never reach
        // the tap/type/scroll that follows. Prefer the triggering request's own value; fall back to
        // the launched package threaded via context (the REMOTE path, where open_app_by_name carried
        // only appName and no request-level package reached here). The model's own value still wins.
        val inheritedExpectedPackage = previousRequest.inheritableExpectedForegroundPackage()
            ?: inheritedExpectedForegroundPackage
        val replanArguments =
            if (inheritedExpectedPackage != null &&
                draft.functionName.isForegroundGatedUiAction() &&
                !draft.parameters.containsKey("expectedPackageName") &&
                !draft.parameters.containsKey("targetPackageName")
            ) {
                draft.parameters + mapOf("expectedPackageName" to inheritedExpectedPackage)
            } else {
                draft.parameters
            }
        return ModelObservationReplanAcceptance(
            replan = AgentObservationReplan(
                request = ToolRequest(
                    toolName = draft.functionName,
                    arguments = replanArguments,
                    reason = MODEL_OBSERVATION_REPLAN_REQUEST_REASON,
                ),
                draft = draft,
                plannedByModel = true,
                fallbackReason = MODEL_OBSERVATION_REPLAN_FALLBACK_REASON,
            ),
        )
    }
}

data class ModelObservationReplanDiagnostic(
    val attempted: Boolean,
    val reason: String,
    val promptIndex: Int? = null,
    val usedModel: Boolean = false,
    val modelAttempted: Boolean = false,
    val modelPlanKind: String? = null,
    val modelFailureReason: String? = null,
    val modelOutputPreview: String? = null,
) {
    companion object {
        fun notAttempted(reason: String): ModelObservationReplanDiagnostic =
            ModelObservationReplanDiagnostic(
                attempted = false,
                reason = reason,
            )
    }
}

private data class ModelObservationReplanAcceptance(
    val replan: AgentObservationReplan? = null,
    val rejectionReason: String? = null,
    /** The normalized model draft a guard rejected, for the single corrective re-prompt. */
    val rejectedDraft: ActionDraft? = null,
)

private data class ModelObservationReplanAttempt(
    val replan: AgentObservationReplan? = null,
    val producedModelDraft: Boolean,
    val rejectedDraft: ActionDraft? = null,
    val rejectionReason: String? = null,
)

private fun ActionPlanningResult.toObservationReplanDiagnostic(
    promptIndex: Int,
    reason: String,
): ModelObservationReplanDiagnostic =
    ModelObservationReplanDiagnostic(
        attempted = true,
        reason = reason,
        promptIndex = promptIndex,
        usedModel = usedModel,
        modelAttempted = modelAttempted,
        modelPlanKind = plan.kind.name,
        modelFailureReason = modelFailureReason ?: fallbackReason,
        modelOutputPreview = modelOutputPreview,
    )

class SequentialActionObservationReplanner(
    private val actionPlanningRuntime: ActionPlanningRuntime,
    maxSequentialActions: Int = DEFAULT_MAX_SEQUENTIAL_ACTIONS,
    private val toolRegistry: ToolRegistry = defaultSequentialToolRegistry,
) : AgentObservationReplanner {
    private val sequentialActionLimit = maxSequentialActions.coerceAtLeast(1)

    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? {
        if (context.observedResult.status != ToolStatus.Succeeded) return null
        val completedSegmentCount = context.completedSegmentCount
        if (completedSegmentCount <= 0 || completedSegmentCount >= sequentialActionLimit) return null
        val nextInput = context.nextActionInput?.immediateSequentialActionText()
            ?: context.run.input.explicitSequentialActionTextAt(completedSegmentCount)
            ?: return null
        val intent = actionPlanningRuntime.classifyIntent(nextInput)
        if (!intent.isAction || !intent.confidence.isActionableForSequentialReplan()) return null
        val planningResult = actionPlanningRuntime.plan(nextInput, actionModelPath = null)
        val draft = planningResult.plan.draft ?: return null
        if (planningResult.plan.kind != ActionPlanKind.Draft) return null
        if (
            draft.functionName.requiresLocalModelBeforeSequentialTail(toolRegistry) &&
            context.run.input.explicitSequentialActionTextAt(completedSegmentCount + 1) != null
        ) {
            return null
        }
        return AgentObservationReplan(
            request = ToolRequest(
                toolName = draft.functionName,
                arguments = draft.parameters,
                reason = SEQUENTIAL_REPLAN_REQUEST_REASON,
            ),
            draft = draft,
            plannedByModel = planningResult.usedModel,
            fallbackReason = planningResult.fallbackReason,
            input = nextInput,
        )
    }

}

internal fun ActionIntentConfidence.isActionableForAgentPlan(): Boolean =
    this == ActionIntentConfidence.Medium || this == ActionIntentConfidence.High

internal fun ActionIntentConfidence.isActionableForSequentialReplan(): Boolean =
    this == ActionIntentConfidence.High

/**
 * Number of prior requests in this run that were produced by an observation-driven model replan.
 *
 * Counted by comparing [ToolRequest.reason] against [MODEL_OBSERVATION_REPLAN_REQUEST_REASON] rather
 * than by a dedicated field, because the reason is what survives in the persisted trace — restoring a
 * run must not hand the model a fresh replan budget. Consequence: the shared constant IS the counter
 * key, so it is intentionally shared with [RemoteVisionObservationReplanner] (one budget for the whole
 * observe→act loop, regardless of which backend planned the step) and must not be reworded casually.
 */
internal fun AgentObservationReplanContext.modelObservationReplanCount(): Int =
    priorRequests.count { request -> request.reason == MODEL_OBSERVATION_REPLAN_REQUEST_REASON }

internal fun ToolResult.isModelReplannableObservation(): Boolean =
    when (status) {
        ToolStatus.Succeeded -> true
        ToolStatus.Failed -> isRecoverableLocalObservationFailure()
        ToolStatus.Cancelled,
        ToolStatus.Rejected -> false
    }

private fun ToolResult.isRecoverableLocalObservationFailure(): Boolean {
    if (!retryable) return false
    if (error?.code != null && error.code != ToolErrorCode.ExecutionFailed) return false
    if (data["failureKind"] == "permission_missing") return false
    return localObservationJsonKeys.any { key -> data[key]?.isNotBlank() == true } ||
        data["screenObservationDiffSummary"]?.isNotBlank() == true ||
        data["ocrBlocksJson"]?.isNotBlank() == true ||
        data["ocrText"]?.isNotBlank() == true ||
        data["screenText"]?.isNotBlank() == true
}

internal fun String.explicitSequentialActionTextAt(index: Int): String? {
    if (index < 0) return null
    return explicitSequentialActionSegments().getOrNull(index)
}

internal fun String.requiresLocalModelBeforeSequentialTail(): Boolean =
    requiresLocalModelBeforeSequentialTail(defaultSequentialToolRegistry)

internal fun String.requiresLocalModelBeforeSequentialTail(toolRegistry: ToolRegistry): Boolean =
    toolRegistry.requiresSequentialLocalModelBeforeTail(this)

internal fun String.immediateSequentialActionText(): String? =
    (explicitSequentialActionTextAt(0) ?: trimActionText()).takeIf { it.isNotBlank() }

private fun String.explicitSequentialActionSegments(): List<String> {
    val matches = sequenceConnector.findAll(this).toList()
    if (matches.isEmpty()) return emptyList()
    val starts = listOf(0) + matches.map { match -> match.range.last + 1 }
    val ends = matches.map { match -> match.range.first } + length
    return starts.zip(ends)
        .mapNotNull { (start, end) ->
            substring(start.coerceAtMost(length), end.coerceAtMost(length)).trimActionText()
                .takeIf { it.isNotBlank() }
        }
}

private fun String.trimActionText(): String {
    val trimmed = trim(' ', '\t', '\n', '\r', '，', '。', ',', '.', ';', '；', ':', '：')
    return trimmed
        .replace(Regex("""^(?:先\s*|first,?\s+)""", RegexOption.IGNORE_CASE), "")
        .trim(' ', '\t', '\n', '\r', '，', '。', ',', '.', ';', '；', ':', '：')
}

private val sequenceConnector = Regex(
    pattern = """(?i)(?:\b(?:and\s+then|then)\b|然后|接着|随后|之后|再)""",
)
