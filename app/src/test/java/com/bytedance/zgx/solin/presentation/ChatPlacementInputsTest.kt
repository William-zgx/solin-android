package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatImageAttachment
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.LocalImageAttachment
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.ReasoningEffort
import com.bytedance.zgx.solin.RemoteConnectivitySnapshot
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.orchestration.CandidateState
import com.bytedance.zgx.solin.orchestration.ModelPlacementInput
import com.bytedance.zgx.solin.orchestration.ModelPlacementPolicy
import com.bytedance.zgx.solin.orchestration.PlacementDecision
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.resource.StableResourceBand
import com.bytedance.zgx.solin.resource.StableResourceState
import com.bytedance.zgx.solin.resource.ThermalPressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPlacementInputsTest {
    @Test
    fun ordinaryImageRepresentationsInheritRemoteEligibleSourcePrivacy() {
        val plan = initialChatPrivacyPlan(
            promptPrivacy = MessagePrivacy.RemoteEligible,
            history = emptyList(),
            remoteImages = listOf(ChatImageAttachment("image/png", "data:image/png;base64,AA==")),
            localImages = listOf(LocalImageAttachment("image/png", byteArrayOf(1))),
            evidence = null,
        )

        assertEquals(MessagePrivacy.RemoteEligible, plan.aggregatePrivacy)
        assertFalse(plan.requiresLocalModel)
    }

    @Test
    fun localOnlyImageSourceStillRequiresLocalPlacement() {
        val plan = initialChatPrivacyPlan(
            promptPrivacy = MessagePrivacy.LocalOnly,
            history = emptyList(),
            remoteImages = listOf(ChatImageAttachment("image/png", "data:image/png;base64,AA==")),
            localImages = listOf(LocalImageAttachment("image/png", byteArrayOf(1))),
            evidence = null,
        )

        assertEquals(MessagePrivacy.LocalOnly, plan.aggregatePrivacy)
        assertTrue(plan.requiresLocalModel)
    }

    @Test
    fun autoUsesFreshReachableSnapshotAndNeverPersistedConnectivityStatus() {
        val inputs = inputs(
            connectivity = RemoteConnectivitySnapshot(
                configRevision = REVISION,
                status = RemoteModelConnectivityStatus.Reachable,
                checkedAtElapsedRealtimeMs = 100L,
                ttlMs = 1_000L,
            ),
            now = 200L,
            reasoningEffort = ReasoningEffort.High,
        )

        val decision = decide(inputs)

        assertEquals(RunPlacement.Remote, (decision as PlacementDecision.Chosen).placement)
        assertEquals(CandidateState.Eligible, decision.diagnostics.remoteState)
    }

    @Test
    fun autoTreatsMissingOrExpiredConnectivitySnapshotAsStale() {
        val missing = decide(inputs(connectivity = null))
        val expired = decide(
            inputs(
                connectivity = RemoteConnectivitySnapshot(
                    configRevision = REVISION,
                    status = RemoteModelConnectivityStatus.Reachable,
                    checkedAtElapsedRealtimeMs = 1L,
                    ttlMs = 10L,
                ),
                now = 100L,
            ),
        )

        assertEquals(CandidateState.Stale, missing.diagnostics.remoteState)
        assertEquals(CandidateState.Stale, expired.diagnostics.remoteState)
        assertEquals(RunPlacement.Local, (missing as PlacementDecision.Chosen).placement)
        assertEquals(RunPlacement.Local, (expired as PlacementDecision.Chosen).placement)
    }

    private fun inputs(
        connectivity: RemoteConnectivitySnapshot?,
        now: Long = 100L,
        reasoningEffort: ReasoningEffort = ReasoningEffort.Off,
    ): ChatPlacementInputs = chatPlacementInputs(
        state = ChatUiState(
            inferenceMode = InferenceMode.Auto,
            remoteModelConfig = RemoteModelConfig(
                baseUrl = "https://api.example.com/v1",
                modelName = "model-a",
                contextWindowTokens = 8_192,
                profileRevision = REVISION,
                connectivityStatus = RemoteModelConnectivityStatus.Unknown,
            ),
            generationParameters = GenerationParameters(reasoningEffort = reasoningEffort),
        ),
        promptForModel = "hello",
        history = emptyList(),
        remoteImageCount = 0,
        localImageCount = 0,
        localRuntimeLoaded = true,
        connectivity = connectivity,
        nowElapsedRealtimeMillis = now,
        autoRemoteAuthorized = true,
    )

    private fun decide(inputs: ChatPlacementInputs) = ModelPlacementPolicy.decide(
        ModelPlacementInput(
            preference = InferenceMode.Auto,
            privacy = MessagePrivacy.RemoteEligible,
            requiresLocalModel = false,
            requirements = inputs.requirements,
            local = inputs.localCandidate,
            remote = inputs.remoteCandidate,
            resources = StableResourceState(
                band = StableResourceBand.Normal,
                stableLowMemory = false,
                latestLowMemory = false,
                localHardBlocked = false,
                thermalPressure = ThermalPressure.Normal,
            ),
            complexity = inputs.complexity,
        ),
    )

    private companion object {
        const val REVISION = "00000000-0000-0000-0000-000000000001"
    }
}
