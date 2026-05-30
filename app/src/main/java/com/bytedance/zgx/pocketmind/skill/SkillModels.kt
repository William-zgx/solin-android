package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolRequest

data class SkillManifest(
    val id: String,
    val version: Int,
    val title: String,
    val description: String,
    val triggerExamples: List<String>,
    val requiredTools: List<String>,
    val inputSchemaJson: String,
    val riskLevel: RiskLevel,
)

data class SkillRequest(
    val id: String,
    val skillId: String,
    val arguments: Map<String, String>,
    val reason: String,
)

data class SkillPlan(
    val request: SkillRequest,
    val manifest: SkillManifest,
    val steps: List<SkillStep>,
)

sealed class SkillStep {
    abstract val id: String
    abstract val dependsOn: List<String>

    data class ToolStep(
        val request: ToolRequest,
        val draft: ActionDraft,
        override val id: String = "tool:${request.id}",
        override val dependsOn: List<String> = emptyList(),
        val argumentBindings: Map<String, String> = emptyMap(),
    ) : SkillStep()

    data class ModelStep(
        override val id: String,
        override val dependsOn: List<String>,
        val title: String,
        val instruction: String,
        val inputBindings: Map<String, String>,
        val outputKey: String,
        val keepsSensitiveInputLocal: Boolean = true,
    ) : SkillStep()
}

data class SkillPlanValidation(
    val errors: List<String>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

fun SkillPlan.validateStructure(): SkillPlanValidation {
    val errors = mutableListOf<String>()
    val seenStepIds = mutableSetOf<String>()

    if (steps.isEmpty()) {
        errors += "skill plan must contain at least one step"
    }

    steps.forEachIndexed { index, step ->
        val priorStepIds = seenStepIds.toSet()
        if (step.id.isBlank()) {
            errors += "step[$index] id must not be blank"
        }
        if (!seenStepIds.add(step.id)) {
            errors += "duplicate step id: ${step.id}"
        }
        step.dependsOn.forEach { dependency ->
            if (dependency !in priorStepIds) {
                errors += "step ${step.id} depends on missing or later step: $dependency"
            }
        }

        when (step) {
            is SkillStep.ToolStep -> {
                if (step.request.toolName !in manifest.requiredTools) {
                    errors += "tool ${step.request.toolName} is not declared by skill ${manifest.id}"
                }
                if (step.draft.functionName != step.request.toolName) {
                    errors += "step ${step.id} draft function does not match tool request"
                }
                step.argumentBindings.values.validateSourceRefs(
                    currentStepId = step.id,
                    priorStepIds = priorStepIds,
                    errors = errors,
                )
            }

            is SkillStep.ModelStep -> {
                if (step.outputKey.isBlank()) {
                    errors += "model step ${step.id} outputKey must not be blank"
                }
                step.inputBindings.values.validateSourceRefs(
                    currentStepId = step.id,
                    priorStepIds = priorStepIds,
                    errors = errors,
                )
            }
        }
    }

    return SkillPlanValidation(errors)
}

private fun Collection<String>.validateSourceRefs(
    currentStepId: String,
    priorStepIds: Set<String>,
    errors: MutableList<String>,
) {
    forEach { sourceRef ->
        val sourceStepId = sourceRef.substringBefore('.', missingDelimiterValue = "")
        if (sourceStepId.isNotBlank() && sourceStepId !in priorStepIds && sourceStepId != "input") {
            errors += "step $currentStepId reads from missing or later step: $sourceRef"
        }
    }
}
