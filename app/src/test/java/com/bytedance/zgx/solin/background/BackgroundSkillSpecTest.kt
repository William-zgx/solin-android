package com.bytedance.zgx.solin.background

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolCapabilityTag
import com.bytedance.zgx.solin.tool.ToolPermission
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSkillSpecTest {
    @Test
    fun registeredPeriodicLocalReminderPatrolSpecIsValidAgainstProductionTools() {
        val validation = RegisteredBackgroundSkillSpecs.PeriodicLocalReminderPatrol.validate(
            ToolRegistry().specs().associateBy { spec -> spec.name },
        )

        assertTrue(validation.errors.joinToString(), validation.isValid)
    }

    @Test
    fun registeredPeriodicLocalReminderPatrolSpecIsDerivedFromSkillManifest() {
        val manifest = BuiltInSkillRuntime()
            .manifests()
            .first { skillManifest -> skillManifest.id == BuiltInSkillRuntime.PERIODIC_CHECK_SKILL }
        val backgroundExecution = requireNotNull(manifest.backgroundExecution)
        val spec = RegisteredBackgroundSkillSpecs.PeriodicLocalReminderPatrol

        assertTrue(spec.id == manifest.id)
        assertTrue(spec.requiredTools == backgroundExecution.requiredTools)
        assertTrue(spec.allowedWork.map { work -> work.name }.toSet() == backgroundExecution.allowedWork.map { work -> work.name }.toSet())
    }

    @Test
    fun acceptsUserConfiguredBoundedLocalReadOnlyOrNotificationSkill() {
        val validation = BackgroundSkillSpec(
            id = "periodic_local_reminder_patrol",
            requiredTools = listOf(
                MobileActionFunctions.QUERY_BACKGROUND_TASKS,
                MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
            ),
            userConfigured = true,
            minimumIntervalMinutes = BackgroundSkillSpec.MIN_INTERVAL_MINUTES,
            localOnly = true,
            allowedWork = setOf(
                BackgroundSkillWork.ReadOnlyLocalState,
                BackgroundSkillWork.PostLocalNotification,
            ),
        ).validate(
            mapOf(
                MobileActionFunctions.QUERY_BACKGROUND_TASKS to toolSpec(
                    riskLevel = RiskLevel.LowReadOnly,
                    permissions = setOf(ToolPermission.ReadsDeviceContext),
                    tags = setOf(ToolCapabilityTag.BackgroundSkillAllowed),
                ),
                MobileActionFunctions.CONFIGURE_PERIODIC_CHECK to toolSpec(
                    riskLevel = RiskLevel.MediumDraftOrNavigation,
                    permissions = setOf(ToolPermission.SchedulesBackgroundWork, ToolPermission.PostsNotification),
                    tags = setOf(ToolCapabilityTag.BackgroundSkillAllowed),
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

    @Test
    fun rejectsPrivateDeviceReadToolEvenWhenLowRiskAndConfirmed() {
        val validation = BackgroundSkillSpec(
            id = "background_contacts",
            requiredTools = listOf("query_contacts"),
            userConfigured = true,
            minimumIntervalMinutes = 60L,
            localOnly = true,
            allowedWork = setOf(BackgroundSkillWork.ReadOnlyLocalState),
        ).validate(
            mapOf(
                "query_contacts" to toolSpec(
                    riskLevel = RiskLevel.LowReadOnly,
                    permissions = setOf(ToolPermission.ReadsDeviceContext, ToolPermission.ReadsContacts),
                ),
            ),
        )

        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { it.contains("approved background tool allowlist") })
    }

    private fun toolSpec(
        riskLevel: RiskLevel,
        permissions: Set<ToolPermission>,
        confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.Required,
        tags: Set<ToolCapabilityTag> = emptySet(),
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
            tags = tags,
        )
}
