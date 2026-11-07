package com.bytedance.zgx.solin.orchestration.collab

import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.orchestration.CandidateState
import com.bytedance.zgx.solin.orchestration.ModelPlacementInput
import com.bytedance.zgx.solin.orchestration.ModelPlacementPolicy
import com.bytedance.zgx.solin.orchestration.PlacementDecision
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.PromptPrivacyPlanner
import com.bytedance.zgx.solin.orchestration.PromptPrivacySegment
import com.bytedance.zgx.solin.orchestration.PromptSegmentSource
import com.bytedance.zgx.solin.orchestration.RequestComplexity
import com.bytedance.zgx.solin.orchestration.RunPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RolePlacementPolicyTest {
    @Test
    fun plannerWithOnlyARemoteEligibleRequestFollowsTheExistingAutoRules() {
        val complex = RolePlacementPolicy.decide(roleInput(CollaborationRole.Planner))
        assertEquals(RunPlacement.Remote, complex.placement)
        assertEquals(PlacementReasonCode.AUTO_COMPLEX_REMOTE, complex.decision.primaryReason)
        assertEquals(RoleConstraint.None, complex.constraint)

        val simple = RolePlacementPolicy.decide(
            roleInput(CollaborationRole.Planner, complexity = RequestComplexity.Simple),
        )
        assertEquals(RunPlacement.Local, simple.placement)
        assertEquals(PlacementReasonCode.AUTO_SIMPLE_LOCAL, simple.decision.primaryReason)
    }

    @Test
    fun anyLocalOnlyOrUnknownSegmentKeepsTheRoleLocal() {
        val restrictedSegments = listOf(
            segment(privacy = MessagePrivacy.LocalOnly, requiresLocalModel = true),
            segment(privacy = MessagePrivacy.LocalOnly, requiresLocalModel = false),
            segment(privacy = null, requiresLocalModel = false),
            segment(privacy = MessagePrivacy.RemoteEligible, requiresLocalModel = null),
            segment(privacy = MessagePrivacy.RemoteEligible, requiresLocalModel = true),
        )
        listOf(CollaborationRole.Planner, CollaborationRole.Answerer).forEach { role ->
            restrictedSegments.forEach { restricted ->
                assertKeptLocalByPrivacy(role, listOf(segment(), restricted))
            }
        }
        assertKeptLocalByPrivacy(
            CollaborationRole.Answerer,
            listOf(
                segment(),
                segment(PromptSegmentSource.History),
                segment(PromptSegmentSource.ToolObservation, MessagePrivacy.LocalOnly, requiresLocalModel = true),
            ),
        )
    }

    @Test
    fun plannerNeverCarriesObservationsEvenWhenTheyAreMislabelledRemoteEligible() {
        PromptSegmentSource.entries
            .filterNot { source -> source == PromptSegmentSource.CurrentInput }
            .forEach { source ->
                val decision = RolePlacementPolicy.decide(
                    roleInput(CollaborationRole.Planner, inputs = listOf(segment(), segment(source))),
                )
                assertEquals("$source", RoleConstraint.SourceOutsideRoleContract, decision.constraint)
                assertEquals("$source", RunPlacement.Local, decision.placement)
                assertEquals(MessagePrivacy.LocalOnly, decision.privacy.aggregatePrivacy)
                assertTrue(decision.privacy.requiresLocalModel)
            }
    }

    @Test
    fun deviceBoundRolesStayLocalUnderEveryPreference() {
        listOf(CollaborationRole.Perceiver, CollaborationRole.Executor, CollaborationRole.Verifier).forEach { role ->
            assertTrue(role.deviceBound)
            InferenceMode.entries.forEach { preference ->
                val decision = RolePlacementPolicy.decide(roleInput(role, preference = preference))
                assertEquals("$role/$preference", RoleConstraint.DeviceBound, decision.constraint)
                assertEquals("$role/$preference", RunPlacement.Local, decision.placement)
                assertEquals(CandidateState.PrivacyBlocked, decision.decision.diagnostics.remoteState)
            }
        }
        assertFalse(CollaborationRole.Planner.deviceBound)
        assertFalse(CollaborationRole.Answerer.deviceBound)
    }

    @Test
    fun deviceBoundRoleBlocksInsteadOfUsingRemoteWhenTheLocalModelIsMissing() {
        InferenceMode.entries.forEach { preference ->
            val decision = RolePlacementPolicy.decide(
                roleInput(
                    CollaborationRole.Executor,
                    preference = preference,
                    local = localCandidate(available = false),
                ),
            )
            assertTrue("$preference: $decision", decision.decision is PlacementDecision.Blocked)
            assertNull(decision.placement)
        }
    }

    @Test
    fun deviceBoundDecisionsAreMadeUnderAutoButReportTheUsersPreference() {
        listOf(CollaborationRole.Perceiver, CollaborationRole.Executor, CollaborationRole.Verifier).forEach { role ->
            listOf(localCandidate(), localCandidate(available = false)).forEach { local ->
                val auto = RolePlacementPolicy.decide(roleInput(role, preference = InferenceMode.Auto, local = local))
                InferenceMode.entries.forEach { preference ->
                    val decision = RolePlacementPolicy.decide(roleInput(role, preference = preference, local = local))
                    assertEquals("$role/$preference", preference, decision.decision.preference)
                    assertEquals(auto.placement, decision.placement)
                    assertEquals(auto.decision.primaryReason, decision.decision.primaryReason)
                    assertEquals(auto.decision.diagnostics, decision.decision.diagnostics)
                }
            }
        }
        CollaborationRole.entries.forEach { role ->
            InferenceMode.entries.forEach { preference ->
                val undeclared = RolePlacementPolicy.decide(roleInput(role, inputs = null, preference = preference))
                assertEquals("$role/$preference", preference, undeclared.decision.preference)
            }
        }
    }

    @Test
    fun undeclaredInputsBlockEveryRoleUnderEveryPreference() {
        CollaborationRole.entries.forEach { role ->
            listOf<List<PromptPrivacySegment>?>(null, emptyList()).forEach { inputs ->
                InferenceMode.entries.forEach { preference ->
                    val decision = RolePlacementPolicy.decide(
                        roleInput(role, inputs = inputs, preference = preference),
                    )
                    assertEquals(RoleConstraint.UndeclaredInputs, decision.constraint)
                    assertTrue(decision.decision is PlacementDecision.Blocked)
                    assertEquals(PlacementReasonCode.PLACEMENT_DECISION_MISSING, decision.decision.primaryReason)
                    assertEquals(MessagePrivacy.LocalOnly, decision.privacy.aggregatePrivacy)
                    assertNull(decision.placement)
                }
            }
        }
    }

    @Test
    fun remoteRolesStillNeedARemoteCandidateTheExistingRulesAccept() {
        listOf(
            remoteCandidate(configured = false),
            remoteCandidate(authorized = false),
            remoteCandidate(connectivityFresh = false),
            remoteCandidate(connectivityStatus = RemoteModelConnectivityStatus.Unknown),
            remoteCandidate(connectivityStatus = RemoteModelConnectivityStatus.Unreachable),
            remoteCandidate(overloaded = true),
        ).forEach { remote ->
            val decision = RolePlacementPolicy.decide(roleInput(CollaborationRole.Planner, remote = remote))
            assertEquals("$remote", RunPlacement.Local, decision.placement)
        }

        val forced = RolePlacementPolicy.decide(
            roleInput(CollaborationRole.Planner, preference = InferenceMode.Remote, complexity = RequestComplexity.Simple),
        )
        assertEquals(RunPlacement.Remote, forced.placement)
        assertEquals(PlacementReasonCode.USER_FORCED_REMOTE, forced.decision.primaryReason)

        val forcedUnreachable = RolePlacementPolicy.decide(
            roleInput(
                CollaborationRole.Planner,
                preference = InferenceMode.Remote,
                remote = remoteCandidate(connectivityStatus = RemoteModelConnectivityStatus.Unreachable),
            ),
        )
        assertTrue(forcedUnreachable.decision is PlacementDecision.Blocked)
        assertEquals(PlacementReasonCode.REMOTE_CONNECTIVITY_UNAVAILABLE, forcedUnreachable.decision.primaryReason)

        val forcedLocalOnly = RolePlacementPolicy.decide(
            roleInput(
                CollaborationRole.Planner,
                inputs = listOf(segment(privacy = MessagePrivacy.LocalOnly, requiresLocalModel = true)),
                preference = InferenceMode.Remote,
            ),
        )
        assertTrue(forcedLocalOnly.decision is PlacementDecision.Blocked)
        assertEquals(PlacementReasonCode.PRIVACY_REQUIRES_LOCAL, forcedLocalOnly.decision.primaryReason)
    }

    @Test
    fun answererDecidesExactlyLikeTheRunLevelPolicy() {
        val cases = listOf(
            listOf(segment()),
            listOf(
                segment(),
                segment(
                    PromptSegmentSource.History,
                    MessagePrivacy.LocalOnly,
                    requiresLocalModel = true,
                    optionalHistory = true,
                ),
            ),
            listOf(segment(), segment(PromptSegmentSource.DeviceContext, MessagePrivacy.LocalOnly, true)),
            listOf(segment(), segment(PromptSegmentSource.Memory, privacy = null)),
        )
        cases.forEach { inputs ->
            InferenceMode.entries.forEach { preference ->
                RequestComplexity.entries.forEach { complexity ->
                    val input = roleInput(CollaborationRole.Answerer, inputs, preference, complexity = complexity)
                    val plan = PromptPrivacyPlanner.build(inputs)
                    val runLevel = ModelPlacementPolicy.decide(
                        ModelPlacementInput(
                            preference = preference,
                            privacy = plan.aggregatePrivacy,
                            requiresLocalModel = plan.requiresLocalModel,
                            requirements = input.requirements,
                            local = input.local,
                            remote = input.remote,
                            resources = input.resources,
                            complexity = complexity,
                        ),
                    )
                    assertEquals(runLevel, RolePlacementPolicy.decide(input).decision)
                }
            }
        }
    }

    @Test
    fun remotePlacementImpliesEveryInputIsRemoteEligibleAndAllowedForTheRole() {
        val privacies = listOf(null, MessagePrivacy.LocalOnly, MessagePrivacy.RemoteEligible)
        val localRequirements = listOf(null, true, false)
        var remoteDecisions = 0
        CollaborationRole.entries.forEach { role ->
            InferenceMode.entries.forEach { preference ->
                PromptSegmentSource.entries.forEach { source ->
                    privacies.forEach { privacy ->
                        localRequirements.forEach { requiresLocalModel ->
                            val inputs = listOf(segment(), segment(source, privacy, requiresLocalModel))
                            val decision = RolePlacementPolicy.decide(roleInput(role, inputs, preference))
                            if (decision.placement == RunPlacement.Remote) {
                                remoteDecisions += 1
                                assertFalse(role.deviceBound)
                                inputs.forEach { input ->
                                    assertEquals(MessagePrivacy.RemoteEligible, input.privacy)
                                    assertEquals(false, input.requiresLocalModel)
                                    assertTrue("$role/${input.source}", input.source in role.remoteSources)
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("the sweep must reach Remote at least once", remoteDecisions > 0)
    }

    private fun assertKeptLocalByPrivacy(role: CollaborationRole, inputs: List<PromptPrivacySegment>) {
        val decision = RolePlacementPolicy.decide(roleInput(role, inputs))
        assertEquals("$role $inputs", RunPlacement.Local, decision.placement)
        assertEquals(PlacementReasonCode.PRIVACY_REQUIRES_LOCAL, decision.decision.primaryReason)
        assertEquals(CandidateState.PrivacyBlocked, decision.decision.diagnostics.remoteState)
    }
}
