package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.multimodal.SharedInput
import com.bytedance.zgx.solin.multimodal.SharedInputSource
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionIntentConfidence
import com.bytedance.zgx.solin.action.IntentRoutingDecision
import com.bytedance.zgx.solin.action.IntentRoutingPath
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.DeviceContextSnapshot
import com.bytedance.zgx.solin.evidence.EvidenceCard
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.runtime.estimateLocalRuntimeTokens
import com.bytedance.zgx.solin.safety.SafetyDecision
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolStatus
import org.json.JSONArray

fun AgentLoopResult.toAssistantRoute(): AssistantRoute =
    when (val planned = plan) {
        is AgentPlan.Answer -> AssistantRoute.Chat(
            runId = run.id,
            promptForModel = planned.promptForModel,
            memoryHits = planned.memoryHits,
            deviceContext = planned.deviceContext,
        )

        is AgentPlan.UseTool -> AssistantRoute.Action(
            runId = run.id,
            toolRequest = planned.request,
            draft = planned.draft,
            plannedByModel = planned.plannedByModel,
            fallbackReason = planned.fallbackReason,
            skillId = planned.skillRequest?.skillId,
            requiresUserConfirmation = planned.requiresUserConfirmation(),
        )

        is AgentPlan.RejectedTool -> AssistantRoute.ToolRejected(planned.result.summary)

        is AgentPlan.MissingModel -> AssistantRoute.MissingModel(planned.capability)
    }

internal fun AgentPlan.UseTool.requiresUserConfirmation(): Boolean =
    safetyDecision.outcome == SafetyOutcome.RequireConfirmation

internal fun AgentPlan.UseTool.toIntentRoutingDecision(input: String): IntentRoutingDecision {
    val path = routingPath()
    return IntentRoutingDecision(
        input = input,
        selectedPath = path,
        selectedToolName = request.toolName,
        selectedSkillId = skillRequest?.skillId,
        priority = path.routingPriority(),
        accepted = true,
        confidence = ActionIntentConfidence.High,
        rejectionReasons = emptyList(),
        requiresConfirmation = requiresUserConfirmation(),
    )
}

internal fun AgentPlan.UseTool.routingPath(): IntentRoutingPath =
    when (fallbackReason) {
        "skill-first",
        "skill model step" -> IntentRoutingPath.SkillFirst
        "remote tool call",
        "remote tool batch" -> IntentRoutingPath.RemoteToolPlanning
        "local model tool call" -> IntentRoutingPath.ModelToolCall
        else -> IntentRoutingPath.ActionPlanner
    }

internal fun AgentTraceStore.appendRejectedRoutingDecision(
    runId: String,
    path: IntentRoutingPath,
    toolName: String?,
    reason: String,
) {
    val run = run(runId) ?: return
    appendStep(
        runId,
        AgentStep.IntentRouted(
            IntentRoutingDecision(
                input = run.input,
                selectedPath = path,
                selectedToolName = toolName?.takeIf { it.isNotBlank() },
                selectedSkillId = null,
                priority = path.routingPriority(),
                accepted = false,
                confidence = ActionIntentConfidence.None,
                rejectionReasons = listOf(reason.toRoutingReasonSlug()),
                requiresConfirmation = null,
            ),
        ),
    )
}

internal fun IntentRoutingPath.routingPriority(): Int =
    when (this) {
        IntentRoutingPath.SkillFirst -> 100
        IntentRoutingPath.ActionPlanner -> 80
        IntentRoutingPath.RemoteToolPlanning -> 70
        IntentRoutingPath.ModelToolCall -> 60
        IntentRoutingPath.NoAction -> 0
    }

internal fun String.toRoutingReasonSlug(): String =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "tool_rejected" }

internal fun ActionDraft.withSafetyDecision(safetyDecision: SafetyDecision): ActionDraft =
    if (safetyDecision.outcome == SafetyOutcome.RequireConfirmation && !requiresConfirmation) {
        copy(requiresConfirmation = true)
    } else {
        this
    }

internal fun AgentPlan.UseTool.nextExecutionState(): AgentRunState =
    when {
        request.toolName == MobileActionFunctions.ASK_USER -> AgentRunState.AwaitingUserAnswer
        requiresUserConfirmation() -> AgentRunState.AwaitingUserConfirmation
        else -> AgentRunState.ExecutingTool
    }

/**
 * Parse the `choices` argument from a ToolRequest whose arguments map is `Map<String, String>`.
 * Accepts either a JSON-array string (the typical shape produced by model tool calls that
 * serialize structured args as JSON strings) or a single non-empty string treated as a single
 * choice. Returns an empty list when the argument is absent or unparseable.
 */
internal fun parseAskUserChoices(arguments: Map<String, String>): List<String> {
    val raw = arguments["choices"]?.takeIf { it.isNotBlank() } ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { idx ->
            arr.optString(idx).takeIf { !it.isNullOrBlank() }
        }
    }.getOrElse {
        // Fall back to treating a single non-JSON string as a singleton choice.
        listOf(raw)
    }
}

/**
 * Rough token estimator used by compaction call sites that don't supply a model-specific one.
 * Reuses the LiteRt chars/4 heuristic (already imported) — accurate enough for preflight
 * compaction gating even against remote models (both over- and under-estimates are safe: the
 * compactor is conservative, and the post-send retry path catches actual overflows).
 */
internal fun estimateTokensApproximate(messages: List<ChatMessage>): Int =
    messages.sumOf { estimateLocalRuntimeTokens(it.text) } + messages.size * 4

internal fun SharedInput.toPromptPrivacySegments(): List<PromptPrivacySegment> =
    sourcePrivacy.map { metadata ->
        PromptPrivacySegment(
            source = when (metadata.source) {
                SharedInputSource.Text -> PromptSegmentSource.CurrentInput
                SharedInputSource.Image -> PromptSegmentSource.Image
                SharedInputSource.File -> PromptSegmentSource.File
                SharedInputSource.ScreenOcr -> PromptSegmentSource.ScreenOcr
            },
            privacy = metadata.privacy,
            requiresLocalModel = metadata.requiresLocalModel,
        )
    }

internal fun ChatMessage.toPromptPrivacySegment(
    source: PromptSegmentSource,
    optionalHistory: Boolean = false,
): PromptPrivacySegment = PromptPrivacySegment(
    source = source,
    privacy = privacy,
    requiresLocalModel = privacy == MessagePrivacy.LocalOnly,
    optionalHistory = optionalHistory,
)

internal fun Iterable<ChatMessage>.toPromptPrivacySegments(
    source: PromptSegmentSource,
    optionalHistory: Boolean = false,
): List<PromptPrivacySegment> =
    map { message -> message.toPromptPrivacySegment(source, optionalHistory) }

internal fun PendingMessagesDrain.toPromptPrivacySegments(): List<PromptPrivacySegment> =
    steer.toPromptPrivacySegments(PromptSegmentSource.Steer) +
        queued.toPromptPrivacySegments(PromptSegmentSource.QueuedInput)

internal fun MemoryHit.toPromptPrivacySegment(): PromptPrivacySegment = PromptPrivacySegment(
    source = PromptSegmentSource.Memory,
    privacy = privacy,
    requiresLocalModel = privacy == MessagePrivacy.LocalOnly,
)

internal fun DeviceContextSnapshot.toPromptPrivacySegment(): PromptPrivacySegment = PromptPrivacySegment(
    source = PromptSegmentSource.DeviceContext,
    privacy = MessagePrivacy.LocalOnly,
    requiresLocalModel = true,
)

internal fun EvidenceCard.toPromptPrivacySegment(): PromptPrivacySegment = PromptPrivacySegment(
    source = when (sourceType) {
        EvidenceSourceType.UserPrompt -> PromptSegmentSource.CurrentInput
        EvidenceSourceType.Memory -> PromptSegmentSource.Memory
        EvidenceSourceType.DeviceContext -> PromptSegmentSource.DeviceContext
        EvidenceSourceType.ToolResult -> PromptSegmentSource.ToolObservation
        EvidenceSourceType.OcrText -> PromptSegmentSource.ScreenOcr
        EvidenceSourceType.FilePreview -> PromptSegmentSource.File
        EvidenceSourceType.ImageAttachment -> PromptSegmentSource.Image
        EvidenceSourceType.PublicWeb,
        EvidenceSourceType.ProtectedSource -> PromptSegmentSource.Evidence
    },
    privacy = privacy,
    requiresLocalModel = requiresLocalModel,
)

internal fun ToolResult.toPromptPrivacySegment(spec: ToolSpec?): PromptPrivacySegment {
    val isRemoteEligible =
        status == ToolStatus.Succeeded &&
            spec?.resultContinuationPolicy == ToolResultContinuationPolicy.PublicEvidence &&
            spec.privateOutputKeys.isEmpty() &&
            data["privacy"] == MessagePrivacy.RemoteEligible.name &&
            data["requiresLocalModel"] == false.toString() &&
            !containsProtectedObservationPayload() &&
            overflowRefs.all { ref -> ref.privacy == MessagePrivacy.RemoteEligible }
    return PromptPrivacySegment(
        source = PromptSegmentSource.ToolObservation,
        privacy = if (isRemoteEligible) MessagePrivacy.RemoteEligible else MessagePrivacy.LocalOnly,
        requiresLocalModel = !isRemoteEligible,
    )
}

private fun ToolResult.containsProtectedObservationPayload(): Boolean =
    data.any { (key, value) ->
        key.isProtectedObservationKey() || PROTECTED_OBSERVATION_FIELD_PATTERN.containsMatchIn(value)
    }

private fun String.isProtectedObservationKey(): Boolean {
    val normalized = lowercase()
    return normalized.contains("screen") ||
        normalized.startsWith("ocr") ||
        normalized.startsWith("beforeobservation") ||
        normalized.startsWith("afterobservation") ||
        normalized.startsWith("beforenode") ||
        normalized.startsWith("afternode") ||
        normalized.startsWith("beforeactionable") ||
        normalized.startsWith("afteractionable") ||
        normalized.startsWith("beforetext") ||
        normalized.startsWith("aftertext") ||
        normalized in PROTECTED_OBSERVATION_KEYS
}

private val PROTECTED_OBSERVATION_KEYS = setOf(
    "verificationSummary",
    "searchVerificationStatus",
    "searchVerificationEvidence",
    "uiActionOutcome",
    "uiActionOutcomeReason",
    "appSearchProgressStage",
).mapTo(mutableSetOf()) { it.lowercase() }

private val PROTECTED_OBSERVATION_FIELD_PATTERN = Regex(
    pattern = """(?i)[\"']?(?:screen\w*|ocr\w*|before(?:observation|node|actionable|text)\w*|after(?:observation|node|actionable|text)\w*|verificationSummary|searchVerification\w*|uiActionOutcome\w*|appSearchProgressStage)[\"']?\s*[:=]""",
)

internal fun AssistantRoute.Chat.toContextPrivacySegments(): List<PromptPrivacySegment> = buildList {
    memoryHits.mapTo(this) { hit -> hit.toPromptPrivacySegment() }
    deviceContext?.let { context -> add(context.toPromptPrivacySegment()) }
}
