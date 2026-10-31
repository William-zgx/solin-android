package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.resource.StableResourceBand
import com.bytedance.zgx.solin.resource.StableResourceState
import com.bytedance.zgx.solin.resource.ThermalPressure
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionModelPlacementPolicyTest {
    private val sent = mutableListOf<String>()
    private val samples = mutableListOf<DecisionModelRoutingSample>()
    private var mode = DecisionModelRouting.Active
    private var verdict: DecisionModelVerdict = DecisionModelVerdict.Decided(RunPlacement.Remote, 0.95)
    private val policy = DecisionModelPlacementPolicy(
        routing = { mode },
        router = { text -> sent += text; verdict },
        mayLeaveDevice = { text -> "身份证" !in text },
        onSample = { samples += it },
    )

    @Test
    fun confidentVerdictOverridesTheComplexityGuessInActiveMode() {
        val chosen = policy.decide(input(), "写一份三周的学习计划") as PlacementDecision.Chosen
        assertEquals(RunPlacement.Remote, chosen.placement)
        assertEquals(PlacementReasonCode.AUTO_DECISION_MODEL_REMOTE, chosen.primaryReason)
        assertTrue(PlacementReasonCode.AUTO_SIMPLE_LOCAL in chosen.diagnostics.secondaryReasons)
        assertEquals(listOf("写一份三周的学习计划"), sent)

        verdict = DecisionModelVerdict.Decided(RunPlacement.Local, 0.97)
        val local = policy.decide(input(complexity = RequestComplexity.Complex), "你好") as PlacementDecision.Chosen
        assertEquals(RunPlacement.Local, local.placement)
        assertEquals(PlacementReasonCode.AUTO_DECISION_MODEL_LOCAL, local.primaryReason)
    }

    @Test
    fun lowConfidenceOrUnavailableKeepsTheRuleDecision() {
        verdict = DecisionModelVerdict.Decided(RunPlacement.Remote, 0.89)
        assertEquals(PlacementReasonCode.AUTO_SIMPLE_LOCAL, policy.decide(input(), "你好").primaryReason)
        verdict = DecisionModelVerdict.Unavailable("transport")
        assertEquals(PlacementReasonCode.AUTO_SIMPLE_LOCAL, policy.decide(input(), "你好").primaryReason)
        assertEquals(listOf(false, false), samples.map { it.applied })
    }

    @Test
    fun shadowModeCallsAndRecordsButNeverChangesTheDecision() {
        mode = DecisionModelRouting.Shadow
        val chosen = policy.decide(input(), "写一份三周的学习计划") as PlacementDecision.Chosen
        assertEquals(PlacementReasonCode.AUTO_SIMPLE_LOCAL, chosen.primaryReason)
        assertEquals(1, sent.size)
        assertEquals(DecisionModelRouting.Shadow, samples.single().mode)
        assertEquals(false, samples.single().applied)
    }

    @Test
    fun offModeAndNonComplexityDecisionsNeverCallTheRouter() {
        mode = DecisionModelRouting.Off
        policy.decide(input(), "你好")
        mode = DecisionModelRouting.Active
        // Manual preferences, privacy, images and resource pressure are rule-only.
        policy.decide(input(preference = InferenceMode.Local), "你好")
        policy.decide(input(preference = InferenceMode.Remote), "你好")
        policy.decide(input(privacy = MessagePrivacy.LocalOnly), "你好")
        policy.decide(
            input(requiresVision = true),
            "看看这张图",
        )
        policy.decide(input(resources = resources(stableLowMemory = true)), "你好")
        policy.decide(input(remoteConnectivity = RemoteModelConnectivityStatus.Unreachable), "你好")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun sensitiveOrBlankTextIsNeverSent() {
        assertEquals(PlacementReasonCode.AUTO_SIMPLE_LOCAL, policy.decide(input(), "我的身份证号是多少").primaryReason)
        policy.decide(input(), "   ")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aThrowingRouterFailsClosedToTheRuleDecision() {
        val throwing = DecisionModelPlacementPolicy(
            routing = { DecisionModelRouting.Active },
            router = { error("boom") },
            mayLeaveDevice = { true },
        )
        assertEquals(PlacementReasonCode.AUTO_SIMPLE_LOCAL, throwing.decide(input(), "你好").primaryReason)
    }

    @Test
    fun evalScriptAsksTheSameQuestionAsTheApp() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val script = File(root, "scripts/run_decision_routing_eval.py").readText()
        assertTrue("instructions drifted", DECISION_MODEL_PLACEMENT_INSTRUCTIONS in script.replace("\"\n    \"", ""))
        DECISION_MODEL_PLACEMENT_CRITERIA.values.forEach { text -> assertTrue("criteria drifted: $text", text in script) }
    }

    private fun input(
        preference: InferenceMode = InferenceMode.Auto,
        privacy: MessagePrivacy = MessagePrivacy.RemoteEligible,
        requiresVision: Boolean = false,
        complexity: RequestComplexity = RequestComplexity.Simple,
        resources: StableResourceState = resources(),
        remoteConnectivity: RemoteModelConnectivityStatus = RemoteModelConnectivityStatus.Reachable,
    ) = ModelPlacementInput(
        preference = preference,
        privacy = privacy,
        requiresLocalModel = false,
        requirements = ModelRequirements(
            requiresText = true,
            requiresVision = requiresVision,
            requiresTools = false,
            estimatedInputTokens = 100,
            requestedOutputTokens = 512,
        ),
        local = ModelCandidateSnapshot(
            available = true,
            supportsText = true,
            supportsVision = requiresVision,
            supportsTools = true,
            contextWindowTokens = 8_192,
        ),
        remote = ModelCandidateSnapshot(
            available = true,
            supportsText = true,
            supportsVision = true,
            supportsTools = true,
            contextWindowTokens = 32_768,
            configured = true,
            authorized = true,
            connectivityStatus = remoteConnectivity,
            connectivityFresh = true,
            profileRevisionMatches = true,
        ),
        resources = resources,
        complexity = complexity,
    )

    private fun resources(stableLowMemory: Boolean = false) = StableResourceState(
        band = StableResourceBand.Normal,
        stableLowMemory = stableLowMemory,
        latestLowMemory = false,
        localHardBlocked = false,
        thermalPressure = ThermalPressure.Normal,
    )
}
