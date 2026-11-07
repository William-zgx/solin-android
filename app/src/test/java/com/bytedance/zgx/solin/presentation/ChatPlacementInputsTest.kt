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
import com.bytedance.zgx.solin.orchestration.AgentRunOptions
import com.bytedance.zgx.solin.orchestration.CandidateState
import com.bytedance.zgx.solin.orchestration.InitialPlanningMode
import com.bytedance.zgx.solin.orchestration.LocalPlanningStrategy
import com.bytedance.zgx.solin.orchestration.ModelPlacementInput
import com.bytedance.zgx.solin.orchestration.ModelPlacementPolicy
import com.bytedance.zgx.solin.orchestration.PlacementDecision
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
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

    @Test
    fun unsetLocalPlanningStrategyBuildsTheDefaultLocalRunOptions() {
        val state = autoState()
        var modelPathReads = 0

        val unset = initialAgentRunOptions(state, useRemoteModel = false, remoteConfig = state.remoteModelConfig)
        val ruleFirst = initialAgentRunOptions(
            state,
            useRemoteModel = false,
            remoteConfig = state.remoteModelConfig,
            localPlanningStrategy = { LocalPlanningStrategy.RuleFirst },
            localPlanningModelPath = { modelPathReads += 1; MODEL_PATH },
        )

        val expected = AgentRunOptions(reduceDeviceActionConfirmations = state.reduceDeviceActionConfirmations)
        assertEquals(expected, unset)
        assertEquals(expected, ruleFirst)
        assertEquals(0, modelPathReads)
    }

    @Test
    fun localPlanningStrategyNeverChangesRemoteRunOptions() {
        val state = autoState()
        val remote = initialAgentRunOptions(state, useRemoteModel = true, remoteConfig = state.remoteModelConfig)

        LocalPlanningStrategy.entries.forEach { strategy ->
            assertEquals(
                remote,
                initialAgentRunOptions(
                    state,
                    useRemoteModel = true,
                    remoteConfig = state.remoteModelConfig,
                    localPlanningStrategy = { strategy },
                    localPlanningModelPath = { MODEL_PATH },
                ),
            )
        }
        assertEquals(InitialPlanningMode.ModelFirstRemoteTools, remote.initialPlanningMode)
    }

    @Test
    // Placement input only: `requiresTools` is the rule classifier's view of the turn, not the planner's choice.
    fun localModelFirstLeavesAutoPlacementOfRuleClassifiedTurnsUnchanged() {
        val state = autoState()
        val options = initialAgentRunOptions(
            state,
            useRemoteModel = false,
            remoteConfig = state.remoteModelConfig,
            localPlanningStrategy = { LocalPlanningStrategy.ModelFirst },
            localPlanningModelPath = { MODEL_PATH },
        )
        assertEquals(InitialPlanningMode.LocalModelFirst, options.initialPlanningMode)
        assertEquals(LocalPlanningStrategy.ModelFirst, options.localPlanningStrategy)
        assertEquals(MODEL_PATH, options.localPlanningModelPath)
        assertEquals(state.reduceDeviceActionConfirmations, options.reduceDeviceActionConfirmations)

        val freshRemote = RemoteConnectivitySnapshot(
            configRevision = REVISION,
            status = RemoteModelConnectivityStatus.Reachable,
            checkedAtElapsedRealtimeMs = 100L,
            ttlMs = 1_000L,
        )
        listOf("定个6点半的闹钟", "在淘宝搜索牛奶", "hello").forEach { prompt ->
            val inputs = inputs(connectivity = freshRemote, now = 200L, prompt = prompt)
            val decision = decide(inputs) as PlacementDecision.Chosen

            assertFalse(prompt, inputs.requirements.requiresTools)
            assertEquals(prompt, CandidateState.Eligible, decision.diagnostics.remoteState)
            assertEquals(prompt, RunPlacement.Local, decision.placement)
            assertEquals(prompt, PlacementReasonCode.AUTO_SIMPLE_LOCAL, decision.primaryReason)
        }
    }

    private fun autoState(reasoningEffort: ReasoningEffort = ReasoningEffort.Off): ChatUiState =
        ChatUiState(
            inferenceMode = InferenceMode.Auto,
            remoteModelConfig = RemoteModelConfig(
                baseUrl = "https://api.example.com/v1",
                modelName = "model-a",
                contextWindowTokens = 8_192,
                profileRevision = REVISION,
                connectivityStatus = RemoteModelConnectivityStatus.Unknown,
            ),
            generationParameters = GenerationParameters(reasoningEffort = reasoningEffort),
        )

    private fun inputs(
        connectivity: RemoteConnectivitySnapshot?,
        now: Long = 100L,
        reasoningEffort: ReasoningEffort = ReasoningEffort.Off,
        prompt: String = "hello",
    ): ChatPlacementInputs = chatPlacementInputs(
        state = autoState(reasoningEffort),
        promptForModel = prompt,
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
        const val MODEL_PATH = "/data/models/gemma.litertlm"
    }
}
