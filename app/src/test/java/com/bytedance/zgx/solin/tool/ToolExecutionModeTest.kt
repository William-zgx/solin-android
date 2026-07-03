package com.bytedance.zgx.solin.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionModeTest {

    private fun minimalSpec(
        executionMode: ToolExecutionMode = ToolExecutionMode.Sequential,
        resultContinuationPolicy: ToolResultContinuationPolicy = ToolResultContinuationPolicy.None,
        confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.Required,
        riskLevel: RiskLevel = RiskLevel.MediumDraftOrNavigation,
        permissions: Set<ToolPermission> = emptySet(),
        privateOutputKeys: Set<String> = emptySet(),
    ): ToolSpec = ToolSpec(
        name = "test_tool",
        title = "Test Tool",
        description = "A test tool.",
        inputSchemaJson = "{}",
        capability = ToolCapability.WebSearch,
        executionMode = executionMode,
        resultContinuationPolicy = resultContinuationPolicy,
        confirmationPolicy = confirmationPolicy,
        riskLevel = riskLevel,
        permissions = permissions,
        privateOutputKeys = privateOutputKeys,
    )

    @Test
    fun defaultExecutionModeIsSequential() {
        val spec = minimalSpec()
        assertEquals(ToolExecutionMode.Sequential, spec.executionMode)
    }

    @Test
    fun concurrentWhenIndependentWithPublicEvidenceIsEligibleForParallelBatch() {
        val eligible = minimalSpec(
            executionMode = ToolExecutionMode.ConcurrentWhenIndependent,
            resultContinuationPolicy = ToolResultContinuationPolicy.PublicEvidence,
            confirmationPolicy = ConfirmationPolicy.NotRequired,
            riskLevel = RiskLevel.LowReadOnly,
        )
        assertTrue("expected parallel-batch eligible", eligible.isEligibleForParallelBatch())
    }

    @Test
    fun sequentialSpecIsNotEligibleForParallelBatch() {
        val spec = minimalSpec(
            executionMode = ToolExecutionMode.Sequential,
            resultContinuationPolicy = ToolResultContinuationPolicy.PublicEvidence,
            confirmationPolicy = ConfirmationPolicy.NotRequired,
            riskLevel = RiskLevel.LowReadOnly,
        )
        assertFalse(spec.isEligibleForParallelBatch())
    }
}
