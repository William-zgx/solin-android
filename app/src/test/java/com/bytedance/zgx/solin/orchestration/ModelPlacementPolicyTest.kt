package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.resource.StableResourceBand
import com.bytedance.zgx.solin.resource.StableResourceState
import com.bytedance.zgx.solin.resource.ThermalPressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPlacementPolicyTest {
    @Test
    fun policyVersionAndReasonCodesAreStable() {
        assertEquals(1, ModelPlacementPolicy.POLICY_VERSION)
        assertEquals(
            setOf(
                "USER_FORCED_LOCAL",
                "USER_FORCED_REMOTE",
                "PRIVACY_REQUIRES_LOCAL",
                "LOCAL_MODEL_UNAVAILABLE",
                "LOCAL_RESOURCE_BLOCKED",
                "LOCAL_CAPABILITY_MISMATCH",
                "REMOTE_NOT_AUTHORIZED",
                "REMOTE_NOT_CONFIGURED",
                "REMOTE_CONNECTIVITY_UNAVAILABLE",
                "REMOTE_STATUS_STALE",
                "REMOTE_CAPABILITY_MISMATCH",
                "REMOTE_OVERLOADED",
                "AUTO_SIMPLE_LOCAL",
                "AUTO_IMAGE_LOCAL",
                "AUTO_COMPLEX_REMOTE",
                "AUTO_RESOURCE_REMOTE",
                "NO_ELIGIBLE_TARGET",
                "PLACEMENT_DECISION_MISSING",
                "PLACEMENT_NOT_RESTORABLE",
                "PLACEMENT_LOCAL_CONTINUATION_REQUIRED",
                "MODEL_EXECUTION_FAILED",
            ),
            PlacementReasonCode.values().map { it.name }.toSet(),
        )
    }

    @Test
    fun manualLocalKeepsForcedReasonWhenPrivacyRequiresLocal() {
        listOf<MessagePrivacy?>(null, MessagePrivacy.LocalOnly).forEach { privacy ->
            assertChosen(
                RunPlacement.Local,
                PlacementReasonCode.USER_FORCED_LOCAL,
                input(preference = InferenceMode.Local, privacy = privacy),
            )
        }
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.USER_FORCED_LOCAL,
            input(preference = InferenceMode.Local, requiresLocalModel = true),
        )
    }

    @Test
    fun manualPreferencesNeverFallbackAcrossTargets() {
        assertBlocked(
            PlacementReasonCode.LOCAL_MODEL_UNAVAILABLE,
            input(
                preference = InferenceMode.Local,
                local = local(available = false),
                remote = remote(),
            ),
        )
        assertBlocked(
            PlacementReasonCode.PRIVACY_REQUIRES_LOCAL,
            input(preference = InferenceMode.Remote, privacy = MessagePrivacy.LocalOnly),
        )
        assertBlocked(
            PlacementReasonCode.PRIVACY_REQUIRES_LOCAL,
            input(preference = InferenceMode.Remote, privacy = null),
        )
        assertBlocked(
            PlacementReasonCode.REMOTE_CONNECTIVITY_UNAVAILABLE,
            input(
                preference = InferenceMode.Remote,
                local = local(),
                remote = remote(connectivityStatus = RemoteModelConnectivityStatus.Unreachable),
            ),
        )
    }

    @Test
    fun autoTreatsUnknownPrivacyAndLocalRequirementAsRemotePruningOnly() {
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.PRIVACY_REQUIRES_LOCAL,
            input(privacy = null, complexity = RequestComplexity.Complex),
        )
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.PRIVACY_REQUIRES_LOCAL,
            input(privacy = MessagePrivacy.LocalOnly, complexity = RequestComplexity.Complex),
        )
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.PRIVACY_REQUIRES_LOCAL,
            input(requiresLocalModel = true, complexity = RequestComplexity.Complex),
        )
    }

    @Test
    fun manualRemoteAllowsUnknownAndCheckingButRejectsKnownFailures() {
        listOf(
            RemoteModelConnectivityStatus.Unknown,
            RemoteModelConnectivityStatus.Checking,
            RemoteModelConnectivityStatus.Reachable,
        ).forEach { status ->
            assertChosen(
                RunPlacement.Remote,
                PlacementReasonCode.USER_FORCED_REMOTE,
                input(
                    preference = InferenceMode.Remote,
                    remote = remote(
                        authorized = false,
                        contextWindowTokens = null,
                        connectivityStatus = status,
                        connectivityFresh = false,
                        profileRevisionMatches = false,
                    ),
                ),
            )
        }
        assertBlocked(
            PlacementReasonCode.REMOTE_NOT_AUTHORIZED,
            input(
                preference = InferenceMode.Remote,
                remote = remote(connectivityStatus = RemoteModelConnectivityStatus.AuthenticationFailed),
            ),
        )
        assertBlocked(
            PlacementReasonCode.REMOTE_NOT_CONFIGURED,
            input(preference = InferenceMode.Remote, remote = remote(configured = false)),
        )
        assertChosen(
            RunPlacement.Remote,
            PlacementReasonCode.USER_FORCED_REMOTE,
            input(preference = InferenceMode.Remote, remote = remote(overloaded = true)),
        )
        assertBlocked(
            PlacementReasonCode.REMOTE_CAPABILITY_MISMATCH,
            input(
                preference = InferenceMode.Remote,
                requirements = requirements(
                    estimatedInputTokens = 7_000,
                    requestedOutputTokens = 2_000,
                ),
                remote = remote(contextWindowTokens = 8_192),
            ),
        )
    }

    @Test
    fun autoRequiresAuthorizationAndFreshReachableMatchingRevision() {
        assertChosen(
            RunPlacement.Remote,
            PlacementReasonCode.AUTO_COMPLEX_REMOTE,
            input(complexity = RequestComplexity.Complex),
        )
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.REMOTE_NOT_AUTHORIZED,
            input(remote = remote(authorized = false), complexity = RequestComplexity.Complex),
        )
        listOf(
            RemoteModelConnectivityStatus.Unknown,
            RemoteModelConnectivityStatus.Checking,
        ).forEach { status ->
            assertChosen(
                RunPlacement.Local,
                PlacementReasonCode.REMOTE_STATUS_STALE,
                input(remote = remote(connectivityStatus = status), complexity = RequestComplexity.Complex),
            )
        }
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.REMOTE_STATUS_STALE,
            input(remote = remote(connectivityFresh = false), complexity = RequestComplexity.Complex),
        )
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.REMOTE_STATUS_STALE,
            input(remote = remote(profileRevisionMatches = false), complexity = RequestComplexity.Complex),
        )
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.REMOTE_CONNECTIVITY_UNAVAILABLE,
            input(
                remote = remote(connectivityStatus = RemoteModelConnectivityStatus.Unreachable),
                complexity = RequestComplexity.Complex,
            ),
        )
    }

    @Test
    fun textVisionToolsAndContextActuallyClipCandidates() {
        val text = requirements()
        assertBlocked(
            PlacementReasonCode.LOCAL_CAPABILITY_MISMATCH,
            input(
                preference = InferenceMode.Local,
                requirements = text,
                local = local(supportsText = false),
            ),
        )
        val capabilityBlocked = assertBlocked(
            PlacementReasonCode.LOCAL_CAPABILITY_MISMATCH,
            input(
                requirements = text,
                local = local(supportsText = false),
                remote = remote(supportsText = false),
            ),
        )
        assertEquals(
            listOf(PlacementReasonCode.REMOTE_CAPABILITY_MISMATCH),
            capabilityBlocked.diagnostics.secondaryReasons,
        )

        val vision = requirements(requiresVision = true)
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.AUTO_IMAGE_LOCAL,
            input(
                requirements = vision,
                local = local(supportsVision = true),
                remote = remote(supportsVision = true),
            ),
        )
        assertChosen(
            RunPlacement.Remote,
            PlacementReasonCode.LOCAL_CAPABILITY_MISMATCH,
            input(
                requirements = vision,
                local = local(supportsVision = false),
                remote = remote(supportsVision = true),
            ),
        )

        val tools = requirements(requiresTools = true)
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.REMOTE_CAPABILITY_MISMATCH,
            input(
                requirements = tools,
                local = local(supportsTools = true),
                remote = remote(supportsTools = false),
                complexity = RequestComplexity.Complex,
            ),
        )
        assertChosen(
            RunPlacement.Remote,
            PlacementReasonCode.LOCAL_CAPABILITY_MISMATCH,
            input(
                requirements = tools,
                local = local(supportsTools = false),
                remote = remote(supportsTools = true),
            ),
        )

        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.REMOTE_CAPABILITY_MISMATCH,
            input(
                remote = remote(contextWindowTokens = null),
                complexity = RequestComplexity.Complex,
            ),
        )
        assertChosen(
            RunPlacement.Remote,
            PlacementReasonCode.AUTO_COMPLEX_REMOTE,
            input(
                local = local(contextWindowTokens = null),
                complexity = RequestComplexity.Complex,
            ),
        )
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.REMOTE_CAPABILITY_MISMATCH,
            input(
                requirements = requirements(estimatedInputTokens = 7_000, requestedOutputTokens = 2_000),
                local = local(contextWindowTokens = 16_384),
                remote = remote(contextWindowTokens = 8_192),
                complexity = RequestComplexity.Complex,
            ),
        )
        assertChosen(
            RunPlacement.Remote,
            PlacementReasonCode.AUTO_COMPLEX_REMOTE,
            input(
                requirements = requirements(estimatedInputTokens = 6_000, requestedOutputTokens = 2_192),
                remote = remote(contextWindowTokens = 8_192),
                complexity = RequestComplexity.Complex,
            ),
        )

        val unknownContextRequirement = requirements(
            estimatedInputTokens = null,
            requestedOutputTokens = null,
        )
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.REMOTE_CAPABILITY_MISMATCH,
            input(requirements = unknownContextRequirement, complexity = RequestComplexity.Unknown),
        )
        assertBlocked(
            PlacementReasonCode.NO_ELIGIBLE_TARGET,
            input(
                requirements = unknownContextRequirement,
                local = local(available = false),
                complexity = RequestComplexity.Unknown,
            ),
        )
    }

    @Test
    fun autoUsesResourceComplexityImageAndSimplePriority() {
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.AUTO_SIMPLE_LOCAL,
            input(complexity = RequestComplexity.Simple),
        )
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.AUTO_SIMPLE_LOCAL,
            input(complexity = RequestComplexity.Unknown, resources = resources(band = StableResourceBand.Warm)),
        )
        assertChosen(
            RunPlacement.Remote,
            PlacementReasonCode.AUTO_COMPLEX_REMOTE,
            input(complexity = RequestComplexity.Complex),
        )
        assertChosen(
            RunPlacement.Remote,
            PlacementReasonCode.AUTO_RESOURCE_REMOTE,
            input(resources = resources(band = StableResourceBand.Hot)),
        )
        assertChosen(
            RunPlacement.Remote,
            PlacementReasonCode.AUTO_RESOURCE_REMOTE,
            input(resources = resources(latestLowMemory = true)),
        )
        listOf(ThermalPressure.Severe, ThermalPressure.Critical).forEach { thermal ->
            val decision = assertChosen(
                RunPlacement.Remote,
                PlacementReasonCode.AUTO_RESOURCE_REMOTE,
                input(resources = resources(thermal = thermal)),
            )
            assertEquals(CandidateState.Eligible, decision.diagnostics.localState)
        }
    }

    @Test
    fun softResourceSignalDoesNotEliminateLocalButHardBlockDoes() {
        val softDecision = assertChosen(
            RunPlacement.Remote,
            PlacementReasonCode.AUTO_RESOURCE_REMOTE,
            input(resources = resources(latestLowMemory = true)),
        )
        assertEquals(CandidateState.Eligible, softDecision.diagnostics.localState)

        listOf(ThermalPressure.Emergency, ThermalPressure.Shutdown).forEach { thermal ->
            val hardResources = resources(localHardBlocked = true, thermal = thermal)
            assertChosen(
                RunPlacement.Remote,
                PlacementReasonCode.LOCAL_RESOURCE_BLOCKED,
                input(resources = hardResources),
            )
            val blocked = assertBlocked(
                PlacementReasonCode.NO_ELIGIBLE_TARGET,
                input(privacy = MessagePrivacy.LocalOnly, resources = hardResources),
            )
            assertEquals(CandidateState.ResourceBlocked, blocked.diagnostics.localState)
            assertEquals(CandidateState.PrivacyBlocked, blocked.diagnostics.remoteState)
        }

        assertChosen(
            RunPlacement.Remote,
            PlacementReasonCode.LOCAL_RESOURCE_BLOCKED,
            input(local = local(runtimeAdmissionFailed = true)),
        )
    }

    @Test
    fun autoSelectsOnlyEligibleCandidateAndNeverUsesUnauthorizedRemote() {
        assertChosen(
            RunPlacement.Remote,
            PlacementReasonCode.LOCAL_MODEL_UNAVAILABLE,
            input(local = local(available = false)),
        )
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.REMOTE_CONNECTIVITY_UNAVAILABLE,
            input(remote = remote(available = false), complexity = RequestComplexity.Complex),
        )
        assertBlocked(
            PlacementReasonCode.NO_ELIGIBLE_TARGET,
            input(local = local(available = false), remote = remote(authorized = false)),
        )
        assertChosen(
            RunPlacement.Local,
            PlacementReasonCode.REMOTE_OVERLOADED,
            input(remote = remote(overloaded = true), complexity = RequestComplexity.Complex),
        )
        assertBlocked(
            PlacementReasonCode.NO_ELIGIBLE_TARGET,
            input(local = local(available = false), remote = remote(overloaded = true)),
        )
    }

    @Test
    fun diagnosticsAndDecisionsCannotCarryFreeTextOrConnectionSecrets() {
        val resourceState = resources(
            band = StableResourceBand.Hot,
            stableLowMemory = true,
            latestLowMemory = true,
            localHardBlocked = false,
            thermal = ThermalPressure.Critical,
        )
        val diagnostics = assertChosen(
            RunPlacement.Remote,
            PlacementReasonCode.AUTO_RESOURCE_REMOTE,
            input(resources = resourceState),
        ).diagnostics
        assertEquals(resourceState.band, diagnostics.resourceBand)
        assertEquals(resourceState.stableLowMemory, diagnostics.stableLowMemory)
        assertEquals(resourceState.latestLowMemory, diagnostics.latestLowMemory)
        assertEquals(resourceState.localHardBlocked, diagnostics.localHardBlocked)
        assertEquals(resourceState.thermalPressure, diagnostics.thermalPressure)
        assertTrue(diagnostics.secondaryReasons.isEmpty())

        val forbidden = String::class.java
        assertFalse(PlacementDiagnostics::class.java.declaredFields.any { it.type == forbidden })
        assertFalse(PlacementDecision.Chosen::class.java.declaredFields.any { it.type == forbidden })
        assertFalse(PlacementDecision.Blocked::class.java.declaredFields.any { it.type == forbidden })
        assertFalse(ModelPlacementInput::class.java.declaredFields.any { it.type == forbidden })
    }

    private fun assertChosen(
        placement: RunPlacement,
        reason: PlacementReasonCode,
        input: ModelPlacementInput,
    ): PlacementDecision.Chosen {
        val decision = ModelPlacementPolicy.decide(input)
        assertTrue("Expected Chosen but was $decision", decision is PlacementDecision.Chosen)
        return (decision as PlacementDecision.Chosen).also {
            assertEquals(placement, it.placement)
            assertEquals(reason, it.primaryReason)
            assertEquals(ModelPlacementPolicy.POLICY_VERSION, it.policyVersion)
            assertEquals(input.preference, it.preference)
        }
    }

    private fun assertBlocked(
        reason: PlacementReasonCode,
        input: ModelPlacementInput,
    ): PlacementDecision.Blocked {
        val decision = ModelPlacementPolicy.decide(input)
        assertTrue("Expected Blocked but was $decision", decision is PlacementDecision.Blocked)
        return (decision as PlacementDecision.Blocked).also {
            assertEquals(reason, it.primaryReason)
            assertEquals(ModelPlacementPolicy.POLICY_VERSION, it.policyVersion)
            assertEquals(input.preference, it.preference)
        }
    }

    private fun input(
        preference: InferenceMode = InferenceMode.Auto,
        privacy: MessagePrivacy? = MessagePrivacy.RemoteEligible,
        requiresLocalModel: Boolean = false,
        requirements: ModelRequirements = requirements(),
        local: ModelCandidateSnapshot = local(),
        remote: ModelCandidateSnapshot = remote(),
        resources: StableResourceState = resources(),
        complexity: RequestComplexity = RequestComplexity.Simple,
    ): ModelPlacementInput = ModelPlacementInput(
        preference = preference,
        privacy = privacy,
        requiresLocalModel = requiresLocalModel,
        requirements = requirements,
        local = local,
        remote = remote,
        resources = resources,
        complexity = complexity,
    )

    private fun requirements(
        requiresText: Boolean = true,
        requiresVision: Boolean = false,
        requiresTools: Boolean = false,
        estimatedInputTokens: Int? = 100,
        requestedOutputTokens: Int? = 512,
    ): ModelRequirements = ModelRequirements(
        requiresText = requiresText,
        requiresVision = requiresVision,
        requiresTools = requiresTools,
        estimatedInputTokens = estimatedInputTokens,
        requestedOutputTokens = requestedOutputTokens,
    )

    private fun local(
        available: Boolean = true,
        supportsText: Boolean = true,
        supportsVision: Boolean = false,
        supportsTools: Boolean = true,
        contextWindowTokens: Int? = 8_192,
        runtimeAdmissionFailed: Boolean = false,
    ): ModelCandidateSnapshot = ModelCandidateSnapshot(
        available = available,
        supportsText = supportsText,
        supportsVision = supportsVision,
        supportsTools = supportsTools,
        contextWindowTokens = contextWindowTokens,
        runtimeAdmissionFailed = runtimeAdmissionFailed,
    )

    private fun remote(
        available: Boolean = true,
        configured: Boolean = true,
        authorized: Boolean = true,
        supportsText: Boolean = true,
        supportsVision: Boolean = false,
        supportsTools: Boolean = true,
        contextWindowTokens: Int? = 32_768,
        connectivityStatus: RemoteModelConnectivityStatus = RemoteModelConnectivityStatus.Reachable,
        connectivityFresh: Boolean = true,
        profileRevisionMatches: Boolean = true,
        overloaded: Boolean = false,
    ): ModelCandidateSnapshot = ModelCandidateSnapshot(
        available = available,
        configured = configured,
        authorized = authorized,
        supportsText = supportsText,
        supportsVision = supportsVision,
        supportsTools = supportsTools,
        contextWindowTokens = contextWindowTokens,
        connectivityStatus = connectivityStatus,
        connectivityFresh = connectivityFresh,
        profileRevisionMatches = profileRevisionMatches,
        overloaded = overloaded,
    )

    private fun resources(
        band: StableResourceBand = StableResourceBand.Normal,
        stableLowMemory: Boolean = false,
        latestLowMemory: Boolean = false,
        localHardBlocked: Boolean = false,
        thermal: ThermalPressure = ThermalPressure.Normal,
    ): StableResourceState = StableResourceState(
        band = band,
        stableLowMemory = stableLowMemory,
        latestLowMemory = latestLowMemory,
        localHardBlocked = localHardBlocked,
        thermalPressure = thermal,
    )
}
