package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContinuationCursorV2Test {
    @Test
    fun valueFreeCursorWithRevalidatedCurrentResultIsRestorable() {
        val cursor = valueFreeCursor().toV2(currentResultRevalidated = true)

        assertEquals(
            AgentContinuationCursorRestoreDecision.Restorable,
            cursor.restoreDecisionFor("source-request"),
        )
    }

    @Test
    fun cursorWithPayloadOrMissingRevalidationIsNotRestorable() {
        val payloadCursor = valueFreeCursor(
            request = ToolRequest(
                id = "next-request",
                toolName = MobileActionFunctions.OPEN_DEEP_LINK,
                arguments = mapOf("url" to "https://example.com/private"),
                reason = "open private URL",
            ),
            draft = ActionDraft(
                functionName = MobileActionFunctions.OPEN_DEEP_LINK,
                title = "Open link",
                summary = "Open private URL",
                parameters = mapOf("url" to "https://example.com/private"),
            ),
        ).toV2(currentResultRevalidated = true)
        val payloadDecision = payloadCursor.restoreDecisionFor("source-request")

        assertTrue(payloadDecision is AgentContinuationCursorRestoreDecision.NotRestorable)
        assertTrue((payloadDecision as AgentContinuationCursorRestoreDecision.NotRestorable).reason.contains("payload"))

        val unvalidatedDecision = valueFreeCursor()
            .toV2(currentResultRevalidated = false)
            .restoreDecisionFor("source-request")

        assertTrue(unvalidatedDecision is AgentContinuationCursorRestoreDecision.NotRestorable)
        assertTrue(
            (unvalidatedDecision as AgentContinuationCursorRestoreDecision.NotRestorable).reason.contains("revalidation"),
        )
    }

    @Test
    fun cursorSourceMismatchIsNotRestorable() {
        val decision = valueFreeCursor()
            .toV2(currentResultRevalidated = true)
            .restoreDecisionFor("other-source")

        assertTrue(decision is AgentContinuationCursorRestoreDecision.NotRestorable)
        assertTrue((decision as AgentContinuationCursorRestoreDecision.NotRestorable).reason.contains("source request"))
    }

    private fun valueFreeCursor(
        request: ToolRequest = ToolRequest(
            id = "next-request",
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = "open settings",
        ),
        draft: ActionDraft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "Open Wi-Fi",
            summary = "Open settings",
            parameters = emptyMap(),
        ),
    ): AgentContinuationCursor =
        AgentContinuationCursor(
            sourceRequestId = "source-request",
            completedSegmentCount = 1,
            request = request,
            draft = draft,
        )
}
