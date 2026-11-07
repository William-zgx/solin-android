package com.bytedance.zgx.solin.orchestration.collab

import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.orchestration.CandidateState
import com.bytedance.zgx.solin.orchestration.ModelCandidateSnapshot
import com.bytedance.zgx.solin.orchestration.ModelPlacementInput
import com.bytedance.zgx.solin.orchestration.ModelPlacementPolicy
import com.bytedance.zgx.solin.orchestration.ModelRequirements
import com.bytedance.zgx.solin.orchestration.PlacementDecision
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.PromptPrivacyPlan
import com.bytedance.zgx.solin.orchestration.PromptPrivacyPlanner
import com.bytedance.zgx.solin.orchestration.PromptPrivacySegment
import com.bytedance.zgx.solin.orchestration.PromptSegmentSource
import com.bytedance.zgx.solin.orchestration.RequestComplexity
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.resource.StableResourceState

/**
 * A model role inside one agent run. Placement is decided per role call, not per run; the contract
 * is docs/specs/20261107-cloud-edge-collaboration/spec.md.
 *
 * [remoteSources] lists the prompt sources a role may ever send off the device. A role with none is
 * device-bound: it reads or acts on the live device, so it stays local under every preference.
 */
enum class CollaborationRole(internal val remoteSources: Set<PromptSegmentSource>) {
    /** Turns the user's request into a closed-vocabulary [MilestonePlan]. */
    Planner(setOf(PromptSegmentSource.CurrentInput)),

    /**
     * Reads the live screen and device state. The opt-in remote-vision loop that sends screenshots is
     * an existing, separately consented exception that this policy does not model.
     */
    Perceiver(emptySet()),

    /** Grounds one milestone into one tool call on the live screen. */
    Executor(emptySet()),

    /** Checks a milestone against the observation taken after the action. */
    Verifier(emptySet()),

    /** Writes the user-facing reply. The only role `RunPlacementBinding` places today. */
    Answerer(PromptSegmentSource.entries.toSet()),
    ;

    val deviceBound: Boolean
        get() = remoteSources.isEmpty()
}

/** Why a role was kept on the device (or blocked) before privacy and candidate rules ran. */
enum class RoleConstraint {
    None,

    /** The caller did not declare what the prompt carries, so nothing can be checked: blocked. */
    UndeclaredInputs,
    DeviceBound,
    SourceOutsideRoleContract,
}

data class RolePlacementInput(
    val role: CollaborationRole,
    /** Every segment the role's prompt will carry. Null or empty blocks the call. */
    val inputs: List<PromptPrivacySegment>?,
    val preference: InferenceMode,
    val requirements: ModelRequirements,
    val local: ModelCandidateSnapshot,
    val remote: ModelCandidateSnapshot,
    val resources: StableResourceState,
    val complexity: RequestComplexity,
)

/**
 * Only [RolePlacementPolicy] constructs one, so [RunProgressGate] and [EscalationPolicy] can trust
 * it. The constructor is internal so tests can forge inconsistent decisions; a source scan in the
 * collab tests fails if any other production file calls it.
 */
@ConsistentCopyVisibility
data class RolePlacementDecision internal constructor(
    val role: CollaborationRole,
    val constraint: RoleConstraint,
    /** Effective privacy of the role's prompt after [constraint] is applied. */
    val privacy: PromptPrivacyPlan,
    /** Its [PlacementDecision.preference] is the user's, even for a device-bound role decided under Auto. */
    val decision: PlacementDecision,
) {
    val placement: RunPlacement?
        get() = (decision as? PlacementDecision.Chosen)?.placement
}

/**
 * Places one role call. Order: role contract, then [PromptPrivacyPlanner], then the unchanged
 * [ModelPlacementPolicy] candidate rules. A role is Remote only when it is not device-bound, every
 * input comes from a source the role may send, every input is RemoteEligible without a local-model
 * requirement, and the remote candidate is eligible under the existing rules.
 */
object RolePlacementPolicy {
    fun decide(input: RolePlacementInput): RolePlacementDecision {
        val inputs = input.inputs.orEmpty()
        val constraint = when {
            inputs.isEmpty() -> RoleConstraint.UndeclaredInputs
            input.role.deviceBound -> RoleConstraint.DeviceBound
            inputs.any { segment -> segment.source !in input.role.remoteSources } ->
                RoleConstraint.SourceOutsideRoleContract
            else -> RoleConstraint.None
        }
        val planned = PromptPrivacyPlanner.build(input.inputs)
        val privacy = if (constraint == RoleConstraint.None) {
            planned
        } else {
            planned.copy(aggregatePrivacy = MessagePrivacy.LocalOnly, requiresLocalModel = true)
        }
        val evaluated = ModelPlacementPolicy.decide(
            ModelPlacementInput(
                // The preference picks the serving target of model-serving roles. Device-bound roles
                // stay on the phone under every preference, like today's helper models, and an
                // unavailable local model blocks them instead of falling back to Remote. The returned
                // decision still reports the user's preference, so audit never shows a false Auto.
                preference = if (input.role.deviceBound) InferenceMode.Auto else input.preference,
                privacy = privacy.aggregatePrivacy,
                requiresLocalModel = privacy.requiresLocalModel,
                requirements = input.requirements,
                local = input.local,
                remote = input.remote,
                resources = input.resources,
                complexity = input.complexity,
            ),
        )
        val decision = if (constraint == RoleConstraint.UndeclaredInputs) {
            PlacementDecision.Blocked(
                policyVersion = evaluated.policyVersion,
                preference = input.preference,
                primaryReason = PlacementReasonCode.PLACEMENT_DECISION_MISSING,
                diagnostics = evaluated.diagnostics,
            )
        } else {
            when (evaluated) {
                is PlacementDecision.Chosen -> evaluated.copy(preference = input.preference)
                is PlacementDecision.Blocked -> evaluated.copy(preference = input.preference)
            }
        }
        return RolePlacementDecision(input.role, constraint, privacy, decision)
    }
}

/** Evidence that a local role call failed. Only signals the lab has calibrated may trigger escalation. */
enum class EscalationSignal {
    /** `ModelOutputQualityGuard` stopped the local output (repetition, empty, malformed). */
    QualityGuardTripped,

    /** A schema validator rejected the local output (tool arguments, milestone plan). */
    ValidatorRejected,

    /** The local planner produced no usable plan. */
    PlannerFailed,

    /** Structured complexity turned Complex after the local call started. */
    ComplexityExceeded,
}

enum class EscalationForbiddenReason {
    /** Only the Answerer escalates, and only as a retry of its own failed call. */
    RoleCannotEscalate,
    PreferenceNotAuto,
    FailedCallNotLocal,
    EscalationAlreadyUsed,
    NoCalibratedSignal,
    InputsMustStayLocal,
    RemoteNotEligible,
}

enum class EscalationApprovalReason {
    SensitiveContent,
    NotPreapproved,
}

/**
 * Three-tier consent for retrying a failed local Answerer call on the remote model. Pre-approval
 * approves the mechanism, never specific content: it can only turn
 * `RequireApproval(NotPreapproved)` into [Preapproved].
 */
sealed interface EscalationDecision {
    data class Forbidden(val reason: EscalationForbiddenReason) : EscalationDecision

    data class RequireApproval(val reason: EscalationApprovalReason) : EscalationDecision

    /** Still subject to the remote-send preflight, disclosure and audit at dispatch time. */
    data object Preapproved : EscalationDecision
}

data class EscalationInput(
    /** The failed call as [RolePlacementPolicy] placed it, including the privacy of what it read. */
    val failed: RolePlacementDecision,
    /** The retry, described with the exact payload the escalation would send. */
    val retry: RolePlacementInput,
    val signals: Set<EscalationSignal>,
    /** Empty until the lab has measured a signal's precision for this model pair. */
    val calibratedSignals: Set<EscalationSignal>,
    val escalationsSoFar: Int,
    /** Sensitive-content match over the outbound prompt and the history that travels with it. */
    val payloadSensitive: Boolean,
    val preapproved: Boolean,
)

object EscalationPolicy {
    const val MAX_ESCALATIONS_PER_RUN = 1

    fun decide(input: EscalationInput): EscalationDecision {
        val failed = input.failed
        if (failed.role != CollaborationRole.Answerer || input.retry.role != CollaborationRole.Answerer) {
            return forbidden(EscalationForbiddenReason.RoleCannotEscalate)
        }
        if (input.retry.preference != InferenceMode.Auto || failed.decision.preference != InferenceMode.Auto) {
            return forbidden(EscalationForbiddenReason.PreferenceNotAuto)
        }
        if (failed.placement != RunPlacement.Local) {
            return forbidden(EscalationForbiddenReason.FailedCallNotLocal)
        }
        // A call that read LocalOnly content failed because of that content; retrying it remotely sends
        // a request chosen by it, even when the retry payload is trimmed to RemoteEligible (T4).
        if (
            failed.privacy.aggregatePrivacy != MessagePrivacy.RemoteEligible ||
            failed.privacy.requiresLocalModel ||
            failed.decision.diagnostics.remoteState == CandidateState.PrivacyBlocked
        ) {
            return forbidden(EscalationForbiddenReason.InputsMustStayLocal)
        }
        if (input.escalationsSoFar !in 0 until MAX_ESCALATIONS_PER_RUN) {
            return forbidden(EscalationForbiddenReason.EscalationAlreadyUsed)
        }
        if (input.signals.none { signal -> signal in input.calibratedSignals }) {
            return forbidden(EscalationForbiddenReason.NoCalibratedSignal)
        }
        val remoteState = RolePlacementPolicy.decide(input.retry).decision.diagnostics.remoteState
        if (remoteState == CandidateState.PrivacyBlocked) {
            return forbidden(EscalationForbiddenReason.InputsMustStayLocal)
        }
        if (remoteState != CandidateState.Eligible) {
            return forbidden(EscalationForbiddenReason.RemoteNotEligible)
        }
        if (input.payloadSensitive) {
            return EscalationDecision.RequireApproval(EscalationApprovalReason.SensitiveContent)
        }
        return if (input.preapproved) {
            EscalationDecision.Preapproved
        } else {
            EscalationDecision.RequireApproval(EscalationApprovalReason.NotPreapproved)
        }
    }

    private fun forbidden(reason: EscalationForbiddenReason): EscalationDecision =
        EscalationDecision.Forbidden(reason)
}
