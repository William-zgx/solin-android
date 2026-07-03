package com.bytedance.zgx.solin.safety

import com.bytedance.zgx.solin.tool.RiskLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGatewayTest {

    @Test
    fun autoDenyReturnsDeniedForToolConfirmation() = runTest {
        val req = PermissionRequest.ToolConfirmation(
            runId = "run-1",
            requestId = "req-1",
            toolName = "send_sms",
            actionLabel = "Send SMS",
            riskLevel = RiskLevel.HighExternalSend,
        )
        assertEquals(PermissionResponse.Denied, AutoDenyPermissionGateway.request(req))
    }

    @Test
    fun autoDenyReturnsDeniedForSensitiveRemoteSend() = runTest {
        val req = PermissionRequest.SensitiveRemoteSend(
            runId = "run-1",
            targetUrl = "https://example.com",
            reason = "contacts detected",
        )
        assertEquals(PermissionResponse.Denied, AutoDenyPermissionGateway.request(req))
    }

    @Test
    fun autoDenyReturnsTimedOutForUserQuestion() = runTest {
        val req = PermissionRequest.UserQuestion(
            runId = "run-1",
            questionId = "q-1",
            prompt = "Which contact?",
        )
        assertEquals(PermissionResponse.TimedOut, AutoDenyPermissionGateway.request(req))
    }

    @Test
    fun autoGrantReturnsGrantedForToolConfirmation() = runTest {
        val req = PermissionRequest.ToolConfirmation(
            runId = "run-1",
            requestId = "req-1",
            toolName = "share",
            actionLabel = "Share text",
            riskLevel = RiskLevel.MediumDraftOrNavigation,
        )
        assertEquals(PermissionResponse.Granted, AutoGrantPermissionGateway.request(req))
    }

    @Test
    fun autoGrantReturnsGrantedForSensitiveRemoteSend() = runTest {
        val req = PermissionRequest.SensitiveRemoteSend(
            runId = null,
            targetUrl = "https://example.com",
            reason = "calendar data",
        )
        assertEquals(PermissionResponse.Granted, AutoGrantPermissionGateway.request(req))
    }

    @Test
    fun autoGrantReturnsAnsweredForUserQuestion() = runTest {
        val req = PermissionRequest.UserQuestion(
            runId = "run-1",
            questionId = "q-1",
            prompt = "Pick one",
            choices = listOf("A", "B"),
        )
        val resp = AutoGrantPermissionGateway.request(req)
        assertTrue(resp is PermissionResponse.Answered)
        assertEquals("", (resp as PermissionResponse.Answered).answer)
    }

    @Test
    fun permissionRequestVariantsConstructWithoutError() {
        // ToolConfirmation with all optional fields populated.
        val tool = PermissionRequest.ToolConfirmation(
            runId = "r",
            requestId = "rid",
            toolName = "t",
            actionLabel = "label",
            riskLevel = RiskLevel.LowReadOnly,
            sensitiveCategories = listOf(SafetyCategory.Email),
            requiredRuntimePermissions = listOf("android.permission.POST_NOTIFICATIONS"),
        )
        assertEquals("t", tool.toolName)
        assertEquals(listOf(SafetyCategory.Email), tool.sensitiveCategories)

        // UserQuestion with choices.
        val q = PermissionRequest.UserQuestion(
            runId = "r",
            questionId = "qid",
            prompt = "?",
            choices = listOf("yes", "no"),
        )
        assertEquals(2, q.choices.size)

        // SensitiveRemoteSend with nullable runId.
        val s = PermissionRequest.SensitiveRemoteSend(
            runId = null,
            targetUrl = "https://x.test",
            reason = "phone",
        )
        assertEquals("phone", s.reason)
    }
}
