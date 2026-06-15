package com.bytedance.zgx.pocketmind.background

import com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntime
import com.bytedance.zgx.pocketmind.skill.SkillBackgroundWork
import com.bytedance.zgx.pocketmind.skill.SkillManifest
import com.bytedance.zgx.pocketmind.tool.ConfirmationPolicy
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolCapabilityTag
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.ToolSpec

data class BackgroundSkillSpec(
    val id: String,
    val requiredTools: List<String>,
    val userConfigured: Boolean,
    val minimumIntervalMinutes: Long,
    val localOnly: Boolean,
    val allowedWork: Set<BackgroundSkillWork>,
    val foregroundConfirmationForOutboundOrExecution: Boolean = true,
) {
    fun validate(toolSpecs: Map<String, ToolSpec>): BackgroundSkillSpecValidation {
        val errors = mutableListOf<String>()
        if (!userConfigured) {
            errors += "background skill must be explicitly configured by the user"
        }
        if (minimumIntervalMinutes < MIN_INTERVAL_MINUTES) {
            errors += "background skill frequency must be bounded to at least $MIN_INTERVAL_MINUTES minutes"
        }
        if (!localOnly) {
            errors += "background skill must remain local-only"
        }
        if (allowedWork.isEmpty()) {
            errors += "background skill must declare read-only or notification work"
        }
        val disallowedWork = allowedWork - BACKGROUND_ALLOWED_WORK
        if (disallowedWork.isNotEmpty()) {
            errors += "background skill cannot perform outbound or execution work: $disallowedWork"
        }
        if (!foregroundConfirmationForOutboundOrExecution) {
            errors += "outbound or execution follow-up must return to foreground confirmation"
        }
        requiredTools.forEach { toolName ->
            val spec = toolSpecs[toolName]
            if (spec == null) {
                errors += "unknown required tool $toolName"
                return@forEach
            }
            errors += validateToolBoundary(toolName, spec)
        }
        return BackgroundSkillSpecValidation(errors)
    }

    private fun validateToolBoundary(toolName: String, spec: ToolSpec): List<String> {
        val errors = mutableListOf<String>()
        if (ToolCapabilityTag.BackgroundSkillAllowed !in spec.tags) {
            errors += "background tool $toolName is not in the approved background tool allowlist"
        }
        if (spec.riskLevel.ordinal > RiskLevel.MediumDraftOrNavigation.ordinal) {
            errors += "background tool $toolName cannot exceed medium risk"
        }
        if (spec.confirmationPolicy != ConfirmationPolicy.Required) {
            errors += "background tool $toolName must keep explicit confirmation"
        }
        val forbiddenPermissions = spec.permissions.intersect(FOREGROUND_ONLY_PERMISSIONS)
        if (forbiddenPermissions.isNotEmpty()) {
            errors += "background tool $toolName contains foreground-only permissions: $forbiddenPermissions"
        }
        val isAllowedEffect =
            ToolPermission.SchedulesBackgroundWork in spec.permissions ||
                ToolPermission.PostsNotification in spec.permissions ||
                spec.riskLevel == RiskLevel.LowReadOnly
        if (!isAllowedEffect) {
            errors += "background tool $toolName must be read-only or notification/schedule policy only"
        }
        return errors
    }

    companion object {
        const val MIN_INTERVAL_MINUTES = PeriodicCheckScheduleRequest.MIN_INTERVAL_MINUTES
        val BACKGROUND_ALLOWED_WORK: Set<BackgroundSkillWork> =
            setOf(BackgroundSkillWork.ReadOnlyLocalState, BackgroundSkillWork.PostLocalNotification)
        private val FOREGROUND_ONLY_PERMISSIONS = setOf(
            ToolPermission.StartsExternalActivity,
            ToolPermission.SendsTextToExternalApp,
        )

        fun fromManifest(manifest: SkillManifest): BackgroundSkillSpec {
            val backgroundExecution = requireNotNull(manifest.backgroundExecution) {
                "Skill ${manifest.id} does not declare background execution metadata"
            }
            return BackgroundSkillSpec(
                id = manifest.id,
                requiredTools = backgroundExecution.requiredTools.ifEmpty { manifest.requiredTools },
                userConfigured = backgroundExecution.userConfigured,
                minimumIntervalMinutes = backgroundExecution.minimumIntervalMinutes,
                localOnly = backgroundExecution.localOnly,
                allowedWork = backgroundExecution.allowedWork.mapTo(linkedSetOf(), SkillBackgroundWork::toBackgroundWork),
                foregroundConfirmationForOutboundOrExecution =
                    backgroundExecution.foregroundConfirmationForOutboundOrExecution,
            )
        }
    }
}

private fun SkillBackgroundWork.toBackgroundWork(): BackgroundSkillWork =
    when (this) {
        SkillBackgroundWork.ReadOnlyLocalState -> BackgroundSkillWork.ReadOnlyLocalState
        SkillBackgroundWork.PostLocalNotification -> BackgroundSkillWork.PostLocalNotification
        SkillBackgroundWork.OutboundNetwork -> BackgroundSkillWork.OutboundNetwork
        SkillBackgroundWork.ExecuteExternalAction -> BackgroundSkillWork.ExecuteExternalAction
    }

data class BackgroundSkillSpecValidation(
    val errors: List<String>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

enum class BackgroundSkillWork {
    ReadOnlyLocalState,
    PostLocalNotification,
    OutboundNetwork,
    ExecuteExternalAction,
}

object RegisteredBackgroundSkillSpecs {
    val PeriodicLocalReminderPatrol: BackgroundSkillSpec =
        BuiltInSkillRuntime()
            .manifests()
            .first { manifest -> manifest.id == BuiltInSkillRuntime.PERIODIC_CHECK_SKILL }
            .let(BackgroundSkillSpec::fromManifest)

    val all: List<BackgroundSkillSpec> = listOf(PeriodicLocalReminderPatrol)
}
