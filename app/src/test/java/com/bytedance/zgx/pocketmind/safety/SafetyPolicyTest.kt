package com.bytedance.zgx.pocketmind.safety

import com.bytedance.zgx.pocketmind.tool.ConfirmationPolicy
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolCapability
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import com.bytedance.zgx.pocketmind.tool.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyTest {
    private val policy = SafetyPolicy()

    @Test
    fun requiredConfirmationToolWaitsUntilUserConfirms() {
        val spec = toolSpec(
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
        )
        val request = ToolRequest(toolName = spec.name)

        val beforeConfirmation = policy.evaluate(
            spec = spec,
            request = request,
            context = SafetyContext(userConfirmed = false),
        )
        val afterConfirmation = policy.evaluate(
            spec = spec,
            request = request,
            context = SafetyContext(userConfirmed = true),
        )

        assertEquals(SafetyOutcome.RequireConfirmation, beforeConfirmation.outcome)
        assertEquals(SafetyOutcome.Allow, afterConfirmation.outcome)
    }

    @Test
    fun highRiskToolsCannotSkipConfirmation() {
        val spec = toolSpec(
            riskLevel = RiskLevel.HighExternalSend,
            confirmationPolicy = ConfirmationPolicy.Optional,
            permissions = setOf(ToolPermission.StartsExternalActivity),
        )

        val decision = policy.evaluate(
            spec = spec,
            request = ToolRequest(toolName = spec.name),
            context = SafetyContext(userConfirmed = true),
        )

        assertEquals(SafetyOutcome.Reject, decision.outcome)
    }

    @Test
    fun externalTextToolsCannotRunWithoutConfirmationPolicy() {
        val spec = toolSpec(
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Optional,
            permissions = setOf(ToolPermission.SendsTextToExternalApp),
        )

        val decision = policy.evaluate(
            spec = spec,
            request = ToolRequest(toolName = spec.name),
            context = SafetyContext(userConfirmed = true),
        )

        assertEquals(SafetyOutcome.Reject, decision.outcome)
    }

    @Test
    fun privateReadToolsCannotSkipConfirmationPolicy() {
        val spec = toolSpec(
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Optional,
            permissions = setOf(ToolPermission.ReadsDeviceContext, ToolPermission.ReadsContacts),
        )

        val decision = policy.evaluate(
            spec = spec,
            request = ToolRequest(toolName = spec.name),
            context = SafetyContext(userConfirmed = true),
        )

        assertEquals(SafetyOutcome.Reject, decision.outcome)
    }

    @Test
    fun boundaryPermissionsCannotSkipConfirmationPolicy() {
        boundaryPermissions.forEach { permission ->
            listOf(ConfirmationPolicy.Optional, ConfirmationPolicy.NotRequired).forEach { confirmationPolicy ->
                val spec = toolSpec(
                    riskLevel = RiskLevel.LowReadOnly,
                    confirmationPolicy = confirmationPolicy,
                    permissions = setOf(permission),
                )

                val decision = policy.evaluate(
                    spec = spec,
                    request = ToolRequest(toolName = spec.name),
                    context = SafetyContext(userConfirmed = true),
                )

                assertEquals("permission=$permission policy=$confirmationPolicy", SafetyOutcome.Reject, decision.outcome)
            }
        }
    }

    @Test
    fun registeredBoundaryToolsRequireConfirmationBeforeExecution() {
        val boundarySpecs = ToolRegistry().specs()
            .filter { spec ->
                spec.riskLevel.requiresHardConfirmationForTest() ||
                    spec.permissions.any { permission -> permission in boundaryPermissions }
            }

        assertTrue(boundarySpecs.isNotEmpty())
        boundarySpecs.forEach { spec ->
            val beforeConfirmation = policy.evaluate(
                spec = spec,
                request = ToolRequest(toolName = spec.name),
                context = SafetyContext(userConfirmed = false),
            )
            val afterConfirmation = policy.evaluate(
                spec = spec,
                request = ToolRequest(toolName = spec.name),
                context = SafetyContext(userConfirmed = true),
            )

            assertEquals(spec.name, ConfirmationPolicy.Required, spec.confirmationPolicy)
            assertEquals(spec.name, SafetyOutcome.RequireConfirmation, beforeConfirmation.outcome)
            assertEquals(spec.name, SafetyOutcome.Allow, afterConfirmation.outcome)
        }
    }

    private fun toolSpec(
        riskLevel: RiskLevel,
        confirmationPolicy: ConfirmationPolicy,
        permissions: Set<ToolPermission> = emptySet(),
    ): ToolSpec =
        ToolSpec(
            name = "test_tool",
            title = "Test Tool",
            description = "A test tool.",
            inputSchemaJson = "{}",
            capability = ToolCapability.ExternalNavigation,
            permissions = permissions,
            riskLevel = riskLevel,
            confirmationPolicy = confirmationPolicy,
        )

    private fun RiskLevel.requiresHardConfirmationForTest(): Boolean =
        this == RiskLevel.HighExternalSend || this == RiskLevel.CriticalDeviceOrPayment

    private companion object {
        val boundaryPermissions = setOf(
            ToolPermission.StartsExternalActivity,
            ToolPermission.SendsTextToExternalApp,
            ToolPermission.RequiresAndroidRuntimePermission,
            ToolPermission.SchedulesBackgroundWork,
            ToolPermission.PostsNotification,
            ToolPermission.ReadsClipboard,
            ToolPermission.ReadsContacts,
            ToolPermission.ReadsFiles,
            ToolPermission.ReadsCalendar,
            ToolPermission.ReadsAccessibilityText,
            ToolPermission.ReadsDeviceContext,
        )
    }
}
