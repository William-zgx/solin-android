package com.bytedance.zgx.solin.contracts

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ModelBackendKind
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.ModelFeature
import com.bytedance.zgx.solin.ModelInputModality
import com.bytedance.zgx.solin.action.ActionIntentConfidence
import com.bytedance.zgx.solin.action.IntentRoutingDecision
import com.bytedance.zgx.solin.action.IntentRoutingPath
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.eval.AgentBehaviorEvalCase
import com.bytedance.zgx.solin.eval.AgentEvalConfirmationExpectation
import com.bytedance.zgx.solin.eval.AgentEvalRiskLevel
import com.bytedance.zgx.solin.eval.AgentBehaviorActualTrace
import com.bytedance.zgx.solin.eval.AgentBehaviorTraceDiffStatus
import com.bytedance.zgx.solin.eval.diffAgainst
import com.bytedance.zgx.solin.evidence.DeviceVerificationArtifact
import com.bytedance.zgx.solin.evidence.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseOneContractModelsTest {
    @Test
    fun routingDecisionCapturesSelectedPathAndRejectionReasons() {
        val decision = IntentRoutingDecision(
            input = "打开WiFi",
            selectedPath = IntentRoutingPath.SkillFirst,
            selectedToolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            selectedSkillId = "device_settings_skill",
            priority = 100,
            accepted = true,
            confidence = ActionIntentConfidence.High,
            rejectionReasons = listOf("app_navigation_lower_priority"),
            requiresConfirmation = true,
        )

        assertEquals(IntentRoutingPath.SkillFirst, decision.selectedPath)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, decision.selectedToolName)
        assertTrue(decision.rejectionReasons.contains("app_navigation_lower_priority"))
    }

    @Test
    fun evalCaseCapturesPlanningTraceExpectationsAndPrivacyBoundary() {
        val evalCase = AgentBehaviorEvalCase(
            id = "wifi-routing-001",
            input = "打开 Wi-Fi",
            expectedTools = listOf(MobileActionFunctions.OPEN_WIFI_SETTINGS),
            expectedConfirmation = AgentEvalConfirmationExpectation.ToolConfirmation,
            expectedRiskLevel = AgentEvalRiskLevel.Low,
            privacy = MessagePrivacy.LocalOnly,
            localOnly = true,
            remoteEligible = false,
            allowedFailureModes = listOf("permission_missing"),
        )

        assertEquals("wifi-routing-001", evalCase.id)
        assertEquals(listOf(MobileActionFunctions.OPEN_WIFI_SETTINGS), evalCase.expectedTools)
        assertEquals(MessagePrivacy.LocalOnly, evalCase.privacy)
    }

    @Test
    fun planningTraceDiffComparesActualToolsAndSafetyBoundary() {
        val evalCase = AgentBehaviorEvalCase(
            id = "remote-public-weather",
            input = "比较北京和上海天气",
            expectedTools = listOf(MobileActionFunctions.WEB_SEARCH),
            expectedConfirmation = AgentEvalConfirmationExpectation.None,
            expectedRiskLevel = AgentEvalRiskLevel.PublicEvidence,
            privacy = MessagePrivacy.RemoteEligible,
            localOnly = false,
            remoteEligible = true,
        )
        val actualTrace = AgentBehaviorActualTrace(
            caseId = evalCase.id,
            input = evalCase.input,
            actualTools = listOf(MobileActionFunctions.WEB_SEARCH),
            actualConfirmation = AgentEvalConfirmationExpectation.None,
            actualRiskLevel = AgentEvalRiskLevel.PublicEvidence,
            privacy = MessagePrivacy.RemoteEligible,
            localOnly = false,
            remoteEligible = true,
        )

        val diff = evalCase.diffAgainst(actualTrace)

        assertEquals(AgentBehaviorTraceDiffStatus.Matched, diff.status)
        assertEquals(true, diff.toolsMatch)
        assertEquals(true, diff.privacyMatches)
    }

    @Test
    fun planningTraceDiffTreatsAllowedFailureAsNonRegression() {
        val evalCase = AgentBehaviorEvalCase(
            id = "private-mixed-batch",
            input = "远程 tool_calls 返回混合公开搜索和联系人读取",
            expectedTools = listOf(MobileActionFunctions.WEB_SEARCH, MobileActionFunctions.QUERY_CONTACTS),
            expectedConfirmation = AgentEvalConfirmationExpectation.FailClosed,
            expectedRiskLevel = AgentEvalRiskLevel.Sensitive,
            privacy = MessagePrivacy.LocalOnly,
            localOnly = true,
            remoteEligible = false,
            allowedFailureModes = listOf("mixed_batch_rejected_before_execution"),
        )
        val actualTrace = AgentBehaviorActualTrace(
            caseId = evalCase.id,
            input = evalCase.input,
            actualTools = emptyList(),
            actualConfirmation = AgentEvalConfirmationExpectation.FailClosed,
            actualRiskLevel = AgentEvalRiskLevel.Sensitive,
            privacy = MessagePrivacy.LocalOnly,
            localOnly = true,
            remoteEligible = false,
            failureMode = "mixed_batch_rejected_before_execution",
        )
        val diff = evalCase.diffAgainst(actualTrace)

        assertEquals(AgentBehaviorTraceDiffStatus.AllowedFailure, diff.status)
        assertEquals(AgentBehaviorTraceDiffStatus.MissingActual, evalCase.diffAgainst(null).status)
    }

    @Test
    fun deviceVerificationArtifactRequiresFailureContextAndShaBinding() {
        val artifact = DeviceVerificationArtifact(
            id = "physical-api36-smoke",
            status = VerificationStatus.Failed,
            serial = "fb6272c",
            apiLevel = 36,
            abi = "arm64-v8a",
            testCount = 56,
            failedTarget = "instrumentation",
            reason = "instrumentation-timeout",
            instrumentationOutputPath = "build/verification/device/instrumentation.txt",
            logcatPath = "build/verification/device/logcat.txt",
            artifactSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )

        assertEquals(VerificationStatus.Failed, artifact.status)
        assertEquals("instrumentation", artifact.failedTarget)
        assertEquals("arm64-v8a", artifact.abi)
    }

    @Test
    fun modelCapabilityProfileKeepsVisionOutOfTextOnlyModels() {
        val profile = ModelCapabilityProfile(
            id = "text-chat",
            displayName = "Text Chat",
            capability = ModelCapability.Chat,
            backendKind = ModelBackendKind.LocalLiteRt,
            inputModalities = setOf(ModelInputModality.Text),
            features = setOf(ModelFeature.TextGeneration),
            tokenBudget = 4096,
            preferredLocalBackends = setOf(BackendChoice.GPU, BackendChoice.CPU),
        )

        assertEquals(ModelCapability.Chat, profile.capability)
        assertEquals(true, profile.supportsChatGeneration)
        assertEquals(false, profile.supportsVisionInput)
        assertEquals(false, profile.supportsMemoryEmbedding)
        assertEquals(false, profile.supportsMobileActionPlanning)
        assertEquals(4096, profile.contextWindowTokens)
        assertEquals(setOf(BackendChoice.GPU, BackendChoice.CPU), profile.preferredLocalBackends)
    }
}
