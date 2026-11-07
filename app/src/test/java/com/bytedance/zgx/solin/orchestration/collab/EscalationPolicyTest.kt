package com.bytedance.zgx.solin.orchestration.collab

import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.orchestration.PromptSegmentSource
import com.bytedance.zgx.solin.orchestration.RequestComplexity
import com.bytedance.zgx.solin.orchestration.RunPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalationPolicyTest {
    @Test
    fun ordinaryContentNeedsApprovalUnlessTheUserPreapprovedTheMechanism() {
        assertEquals(
            EscalationDecision.RequireApproval(EscalationApprovalReason.NotPreapproved),
            EscalationPolicy.decide(escalation()),
        )
        assertEquals(EscalationDecision.Preapproved, EscalationPolicy.decide(escalation(preapproved = true)))
    }

    @Test
    fun sensitivePayloadAlwaysNeedsApprovalEvenWhenPreapproved() {
        listOf(false, true).forEach { preapproved ->
            assertEquals(
                EscalationDecision.RequireApproval(EscalationApprovalReason.SensitiveContent),
                EscalationPolicy.decide(escalation(payloadSensitive = true, preapproved = preapproved)),
            )
        }
    }

    @Test
    fun localOnlyUnknownOrUndeclaredInputsAreNeverEscalated() {
        val restricted = listOf(
            listOf(segment(), segment(PromptSegmentSource.History, MessagePrivacy.LocalOnly, true)),
            listOf(segment(privacy = null)),
            listOf(segment(requiresLocalModel = true)),
            listOf(segment(requiresLocalModel = null)),
            listOf(segment(), segment(PromptSegmentSource.ScreenOcr, MessagePrivacy.LocalOnly, true)),
            emptyList(),
            null,
        )
        restricted.forEach { inputs ->
            listOf(false, true).forEach { sensitive ->
                listOf(false, true).forEach { preapproved ->
                    assertEquals(
                        "$inputs",
                        EscalationDecision.Forbidden(EscalationForbiddenReason.InputsMustStayLocal),
                        EscalationPolicy.decide(
                            escalation(
                                retry = roleInput(CollaborationRole.Answerer, inputs = inputs),
                                payloadSensitive = sensitive,
                                preapproved = preapproved,
                            ),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun deviceBoundRolesAndPlannersCarryingObservationsAreNeverEscalated() {
        listOf(CollaborationRole.Perceiver, CollaborationRole.Executor, CollaborationRole.Verifier).forEach { role ->
            assertEquals(
                EscalationDecision.Forbidden(EscalationForbiddenReason.InputsMustStayLocal),
                EscalationPolicy.decide(escalation(retry = roleInput(role), preapproved = true)),
            )
        }
        val plannerWithScreen = roleInput(
            CollaborationRole.Planner,
            inputs = listOf(segment(), segment(PromptSegmentSource.DeviceContext)),
        )
        assertEquals(
            EscalationDecision.Forbidden(EscalationForbiddenReason.InputsMustStayLocal),
            EscalationPolicy.decide(escalation(retry = plannerWithScreen, preapproved = true)),
        )
        assertEquals(
            EscalationDecision.Preapproved,
            EscalationPolicy.decide(escalation(retry = roleInput(CollaborationRole.Planner), preapproved = true)),
        )
    }

    @Test
    fun onlyAutoMayEscalateAndOnlyFromALocalCallOncePerRun() {
        listOf(InferenceMode.Local, InferenceMode.Remote).forEach { preference ->
            assertForbidden(
                EscalationForbiddenReason.PreferenceNotAuto,
                escalation(retry = roleInput(CollaborationRole.Answerer, preference = preference)),
            )
        }
        assertForbidden(
            EscalationForbiddenReason.FailedCallNotLocal,
            escalation(failedPlacement = RunPlacement.Remote),
        )
        listOf(-1, 1, 2).forEach { count ->
            assertForbidden(EscalationForbiddenReason.EscalationAlreadyUsed, escalation(escalationsSoFar = count))
        }
    }

    @Test
    fun uncalibratedSignalsNeverEscalate() {
        assertForbidden(EscalationForbiddenReason.NoCalibratedSignal, escalation(signals = emptySet()))
        assertForbidden(EscalationForbiddenReason.NoCalibratedSignal, escalation(calibratedSignals = emptySet()))
        assertForbidden(
            EscalationForbiddenReason.NoCalibratedSignal,
            escalation(
                signals = setOf(EscalationSignal.ComplexityExceeded),
                calibratedSignals = setOf(EscalationSignal.QualityGuardTripped),
            ),
        )
        assertEquals(
            EscalationDecision.Preapproved,
            EscalationPolicy.decide(
                escalation(
                    signals = EscalationSignal.entries.toSet(),
                    calibratedSignals = setOf(EscalationSignal.ValidatorRejected),
                    preapproved = true,
                ),
            ),
        )
    }

    @Test
    fun remoteMustBeEligibleUnderTheExistingAutoRules() {
        listOf(
            remoteCandidate(configured = false),
            remoteCandidate(authorized = false),
            remoteCandidate(connectivityFresh = false),
            remoteCandidate(connectivityStatus = RemoteModelConnectivityStatus.AuthenticationFailed),
            remoteCandidate(connectivityStatus = RemoteModelConnectivityStatus.Unreachable),
            remoteCandidate(overloaded = true),
        ).forEach { remote ->
            assertForbidden(
                EscalationForbiddenReason.RemoteNotEligible,
                escalation(retry = roleInput(CollaborationRole.Answerer, remote = remote)),
            )
        }
    }

    @Test
    fun aSimpleRequestThatAutoWouldKeepLocalCanStillEscalateAfterALocalFailure() {
        val retry = roleInput(CollaborationRole.Answerer, complexity = RequestComplexity.Simple)
        assertEquals(RunPlacement.Local, RolePlacementPolicy.decide(retry).placement)
        assertEquals(EscalationDecision.Preapproved, EscalationPolicy.decide(escalation(retry = retry, preapproved = true)))
    }

    @Test
    fun preapprovalOnlyEverLiftsTheNotPreapprovedTier() {
        val retries = listOf(
            roleInput(CollaborationRole.Answerer),
            roleInput(CollaborationRole.Answerer, preference = InferenceMode.Local),
            roleInput(CollaborationRole.Answerer, inputs = listOf(segment(privacy = MessagePrivacy.LocalOnly))),
            roleInput(CollaborationRole.Answerer, remote = remoteCandidate(authorized = false)),
            roleInput(CollaborationRole.Planner),
            roleInput(CollaborationRole.Executor),
        )
        var lifted = 0
        retries.forEach { retry ->
            RunPlacement.entries.forEach { failedPlacement ->
                listOf(0, 1).forEach { count ->
                    listOf(emptySet(), setOf(EscalationSignal.QualityGuardTripped)).forEach { signals ->
                        listOf(false, true).forEach { sensitive ->
                            val base = escalation(
                                retry = retry,
                                failedPlacement = failedPlacement,
                                escalationsSoFar = count,
                                signals = signals,
                                payloadSensitive = sensitive,
                            )
                            val withoutOptIn = EscalationPolicy.decide(base)
                            val withOptIn = EscalationPolicy.decide(base.copy(preapproved = true))
                            if (withoutOptIn == EscalationDecision.RequireApproval(EscalationApprovalReason.NotPreapproved)) {
                                lifted += 1
                                assertEquals(EscalationDecision.Preapproved, withOptIn)
                            } else {
                                assertEquals("$base", withoutOptIn, withOptIn)
                            }
                        }
                    }
                }
            }
        }
        assertTrue("the sweep must reach the liftable tier", lifted > 0)
    }

    private fun assertForbidden(reason: EscalationForbiddenReason, input: EscalationInput) {
        listOf(false, true).forEach { preapproved ->
            assertEquals(
                EscalationDecision.Forbidden(reason),
                EscalationPolicy.decide(input.copy(preapproved = preapproved)),
            )
        }
    }

    private fun escalation(
        retry: RolePlacementInput = roleInput(CollaborationRole.Answerer),
        failedPlacement: RunPlacement = RunPlacement.Local,
        signals: Set<EscalationSignal> = setOf(EscalationSignal.QualityGuardTripped),
        calibratedSignals: Set<EscalationSignal> = setOf(EscalationSignal.QualityGuardTripped),
        escalationsSoFar: Int = 0,
        payloadSensitive: Boolean = false,
        preapproved: Boolean = false,
    ): EscalationInput = EscalationInput(
        retry = retry,
        failedPlacement = failedPlacement,
        signals = signals,
        calibratedSignals = calibratedSignals,
        escalationsSoFar = escalationsSoFar,
        payloadSensitive = payloadSensitive,
        preapproved = preapproved,
    )
}
