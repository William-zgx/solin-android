package com.bytedance.zgx.pocketmind.background

import com.bytedance.zgx.pocketmind.tool.ConfirmationPolicy
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolCapability
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.ToolSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSkillSpecTest {
    @Test
    fun acceptsUserConfiguredBoundedLocalReadOnlyOrNotificationSkill() {
        val validation = BackgroundSkillSpec(
            id = "periodic_local_reminder_patrol",
            requiredTools = listOf("query_background_tasks", "configure_periodic_check"),
            userConfigured = true,
            minimumIntervalMinutes = BackgroundSkillSpec.MIN_INTERVAL_MINUTES,
            localOnly = true,
            allowedWork = setOf(
                BackgroundSkillWork.ReadOnlyLocalState,
                BackgroundSkillWork.PostLocalNotification,
            ),
        ).validate(
            mapOf(
                "query_background_tasks" to toolSpec(
                    riskLevel = RiskLevel.LowReadOnly,
                    permissions = setOf(ToolPermission.ReadsDeviceContext),
                ),
                "configure_periodic_check" to toolSpec(
                    riskLevel = RiskLevel.MediumDraftOrNavigation,
                    permissions = setOf(ToolPermission.SchedulesBackgroundWork, ToolPermission.PostsNotification),
                ),
            ),
        )

        assertTrue(validation.errors.joinToString(), validation.isValid)
    }

    @Test
    fun rejectsImplicitUnboundedRemoteOrOutboundBackgroundSkill() {
        val validation = BackgroundSkillSpec(
            id = "bad_background_sender",
            requiredTools = listOf("share_text"),
            userConfigured = false,
            minimumIntervalMinutes = 1L,
            localOnly = false,
            allowedWork = setOf(BackgroundSkillWork.OutboundNetwork, BackgroundSkillWork.ExecuteExternalAction),
            foregroundConfirmationForOutboundOrExecution = false,
        ).validate(
            mapOf(
                "share_text" to toolSpec(
                    riskLevel = RiskLevel.HighExternalSend,
                    permissions = setOf(
                        ToolPermission.StartsExternalActivity,
                        ToolPermission.SendsTextToExternalApp,
                    ),
                ),
            ),
        )

        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { it.contains("explicitly configured") })
        assertTrue(validation.errors.any { it.contains("at least 60 minutes") })
        assertTrue(validation.errors.any { it.contains("local-only") })
        assertTrue(validation.errors.any { it.contains("outbound or execution work") })
        assertTrue(validation.errors.any { it.contains("foreground confirmation") })
        assertTrue(validation.errors.any { it.contains("cannot exceed medium risk") })
        assertTrue(validation.errors.any { it.contains("foreground-only permissions") })
    }

    @Test
    fun rejectsToolThatCouldRunWithoutExplicitConfirmation() {
        val validation = BackgroundSkillSpec(
            id = "silent_reader",
            requiredTools = listOf("silent_read"),
            userConfigured = true,
            minimumIntervalMinutes = 60L,
            localOnly = true,
            allowedWork = setOf(BackgroundSkillWork.ReadOnlyLocalState),
        ).validate(
            mapOf(
                "silent_read" to toolSpec(
                    riskLevel = RiskLevel.LowReadOnly,
                    permissions = setOf(ToolPermission.ReadsDeviceContext),
                    confirmationPolicy = ConfirmationPolicy.NotRequired,
                ),
            ),
        )

        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { it.contains("explicit confirmation") })
    }

    private fun toolSpec(
        riskLevel: RiskLevel,
        permissions: Set<ToolPermission>,
        confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.Required,
    ): ToolSpec =
        ToolSpec(
            name = "test_tool",
            title = "Test Tool",
            description = "test",
            inputSchemaJson = """{"type":"object","additionalProperties":false}""",
            capability = ToolCapability.BackgroundTask,
            permissions = permissions,
            riskLevel = riskLevel,
            confirmationPolicy = confirmationPolicy,
        )
}
