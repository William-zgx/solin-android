package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolProvider
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentObservationReplannerTest {
    @Test
    fun sequentialReplannerSkipsRegistryLocalEvidenceToolWhenTailRemains() {
        val replanner = replannerFor(
            specFor(
                toolName = LOCAL_EVIDENCE_TOOL,
                resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            ),
        )

        val replan = replanner.planNext(
            contextFor(
                input = "first search Kotlin then inspect local evidence then open Wi-Fi settings",
            ),
        )

        assertNull(replan)
    }

    @Test
    fun sequentialReplannerSkipsPrivateOutputToolWhenTailRemains() {
        val replanner = replannerFor(
            specFor(
                toolName = PRIVATE_OUTPUT_TOOL,
                privateOutputKeys = setOf("rawText"),
            ),
        )

        val replan = replanner.planNext(
            contextFor(
                input = "first search Kotlin then read private evidence then open Wi-Fi settings",
            ),
        )

        assertNull(replan)
    }

    @Test
    fun sequentialReplannerAllowsRegistryLocalEvidenceToolAsFinalSegment() {
        val replanner = replannerFor(
            specFor(
                toolName = LOCAL_EVIDENCE_TOOL,
                resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            ),
        )

        val replan = replanner.planNext(
            contextFor(
                input = "first search Kotlin then inspect local evidence",
            ),
        )

        assertNotNull(replan)
        requireNotNull(replan)
        assertEquals(LOCAL_EVIDENCE_TOOL, replan.request.toolName)
    }

    private fun replannerFor(spec: ToolSpec): SequentialActionObservationReplanner =
        SequentialActionObservationReplanner(
            actionPlanningRuntime = DraftActionRuntime(spec.name),
            toolRegistry = ToolRegistry(ToolProvider { listOf(spec) }),
        )

    private fun contextFor(input: String): AgentObservationReplanContext {
        val previousRequest = ToolRequest(
            id = "previous-request",
            toolName = MobileActionFunctions.WEB_SEARCH,
            arguments = mapOf("query" to "Kotlin"),
        )
        return AgentObservationReplanContext(
            run = AgentRun(
                id = "run-1",
                input = input,
                state = AgentRunState.Observing,
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
            previousRequest = previousRequest,
            observedResult = ToolResult(
                requestId = previousRequest.id,
                status = ToolStatus.Succeeded,
                summary = "searched",
                data = mapOf("toolName" to previousRequest.toolName),
            ),
            priorRequests = listOf(previousRequest),
        )
    }

    private fun specFor(
        toolName: String,
        privateOutputKeys: Set<String> = emptySet(),
        resultContinuationPolicy: ToolResultContinuationPolicy = ToolResultContinuationPolicy.None,
    ): ToolSpec =
        ToolSpec(
            name = toolName,
            title = "Test tool",
            description = "Test tool",
            inputSchemaJson = EMPTY_OBJECT_SCHEMA_JSON,
            capability = ToolCapability.DeviceContext,
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Required,
            privateOutputKeys = privateOutputKeys,
            resultContinuationPolicy = resultContinuationPolicy,
        )

    private class DraftActionRuntime(
        private val toolName: String,
    ) : ActionPlanningRuntime {
        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
            ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = toolName,
                        title = "Test tool",
                        summary = "Run test tool",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test",
            )
    }

    private companion object {
        private const val LOCAL_EVIDENCE_TOOL = "test_local_evidence_tool"
        private const val PRIVATE_OUTPUT_TOOL = "test_private_output_tool"
        private const val EMPTY_OBJECT_SCHEMA_JSON =
            """{"type":"object","properties":{},"additionalProperties":false}"""
    }
}
