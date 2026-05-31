package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import com.bytedance.zgx.pocketmind.tool.ToolResult

class SkillRunProgressor(
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val toolRegistry: ToolRegistry = ToolRegistry(),
) {
    fun validateForExecution(plan: SkillPlan): String? {
        val validation = plan.validateStructure()
        if (!validation.isValid) return validation.errors.joinToString()
        if (plan.steps.size > maxSteps) return "skill step limit exceeded"
        return null
    }

    fun initialOutputs(plan: SkillPlan): MutableMap<String, Map<String, String>> =
        linkedMapOf(INPUT_OUTPUT_KEY to plan.request.arguments)

    fun bindToolStep(
        step: SkillStep.ToolStep,
        outputs: Map<String, Map<String, String>>,
        privateOutputRefs: Set<String>,
    ): SkillToolStepBinding =
        rejectPrivateToolArgumentBindings(step.argumentBindings, privateOutputRefs)
            ?.let { reason -> SkillToolStepBinding.Rejected(reason) }
            ?: bindArguments(step.argumentBindings, outputs)?.let { boundArguments ->
                SkillToolStepBinding.Bound(
                    request = step.request.copy(arguments = step.request.arguments + boundArguments),
                    draft = step.draft.copy(parameters = step.draft.parameters + boundArguments),
                )
            }
            ?: SkillToolStepBinding.Missing("missing tool argument binding")

    fun bindModelStep(
        step: SkillStep.ModelStep,
        outputs: Map<String, Map<String, String>>,
    ): SkillModelStepBinding =
        bindArguments(step.inputBindings, outputs)?.let { inputs ->
            SkillModelStepBinding.Bound(inputs)
        } ?: SkillModelStepBinding.Missing("missing model input binding")

    fun nextToolAfterModelOutput(
        skillPlan: SkillPlan,
        requestedRequestIds: Set<String>,
        modelOutput: String,
    ): SkillModelOutputProgression {
        validateForExecution(skillPlan)?.let { reason ->
            return SkillModelOutputProgression.Rejected("Invalid skill plan: $reason")
        }

        val privateOutputRefs = privateOutputRefsForRequestedTools(skillPlan, requestedRequestIds)
        val modelSteps = skillPlan.steps.filterIsInstance<SkillStep.ModelStep>()
        skillPlan.steps
            .filterIsInstance<SkillStep.ToolStep>()
            .forEach { toolStep ->
                if (toolStep.request.id in requestedRequestIds) return@forEach
                val modelStep = modelSteps.lastOrNull { step -> step.id in toolStep.dependsOn }
                    ?: return@forEach
                val outputs = initialOutputs(skillPlan).apply {
                    put(modelStep.id, mapOf(modelStep.outputKey to modelOutput))
                }
                return when (val binding = bindToolStep(toolStep, outputs, privateOutputRefs)) {
                    is SkillToolStepBinding.Bound ->
                        SkillModelOutputProgression.BoundTool(
                            modelStep = modelStep,
                            toolStep = toolStep,
                            request = binding.request,
                            draft = binding.draft,
                        )

                    is SkillToolStepBinding.Missing ->
                        SkillModelOutputProgression.Rejected("Missing model output binding for skill step.")

                    is SkillToolStepBinding.Rejected ->
                        SkillModelOutputProgression.Rejected(binding.reason)
                }
            }
        return SkillModelOutputProgression.None
    }

    fun outputForToolResult(
        result: ToolResult,
        draft: ActionDraft,
    ): Map<String, String> =
        buildMap {
            putAll(result.data)
            put("summary", result.summary)
            draft.parameters.forEach { (key, value) ->
                putIfAbsent(key, value)
            }
        }

    fun privateOutputRefsFor(stepId: String, toolName: String): Set<String> =
        toolRegistry.privateOutputKeysFor(toolName)
            .mapTo(mutableSetOf()) { key -> "$stepId.$key" }

    fun publicOutputs(
        outputs: Map<String, Map<String, String>>,
        privateOutputRefs: Set<String>,
    ): Map<String, Map<String, String>> =
        outputs
            .filterKeys { it != INPUT_OUTPUT_KEY }
            .mapValues { (stepId, values) ->
                values.filterKeys { key -> "$stepId.$key" !in privateOutputRefs }
            }

    fun privateOutputRefsForRequestedTools(
        skillPlan: SkillPlan,
        requestedRequestIds: Set<String>,
    ): Set<String> =
        skillPlan.steps
            .filterIsInstance<SkillStep.ToolStep>()
            .filter { step -> step.request.id in requestedRequestIds }
            .flatMapTo(mutableSetOf()) { step ->
                privateOutputRefsFor(step.id, step.request.toolName)
            }

    private fun bindArguments(
        bindings: Map<String, String>,
        outputs: Map<String, Map<String, String>>,
    ): Map<String, String>? {
        val resolved = linkedMapOf<String, String>()
        bindings.forEach { (targetName, sourceRef) ->
            val sourceStepId = sourceRef.substringBefore('.', missingDelimiterValue = "")
            val sourceKey = sourceRef.substringAfter('.', missingDelimiterValue = "")
            if (sourceStepId.isBlank() || sourceKey.isBlank()) return null
            val value = outputs[sourceStepId]?.get(sourceKey) ?: return null
            resolved[targetName] = value
        }
        return resolved
    }

    private fun rejectPrivateToolArgumentBindings(
        bindings: Map<String, String>,
        privateOutputRefs: Set<String>,
    ): String? {
        val privateBindings = bindings.values
            .filter { sourceRef -> sourceRef in privateOutputRefs }
            .sorted()
        return privateBindings
            .takeIf { it.isNotEmpty() }
            ?.joinToString(
                prefix = "private tool output cannot be bound directly to tool argument: ",
            )
    }

    private companion object {
        const val DEFAULT_MAX_STEPS = 8
        const val INPUT_OUTPUT_KEY = "input"
    }
}

sealed class SkillToolStepBinding {
    data class Bound(
        val request: ToolRequest,
        val draft: ActionDraft,
    ) : SkillToolStepBinding()

    data class Missing(
        val reason: String,
    ) : SkillToolStepBinding()

    data class Rejected(
        val reason: String,
    ) : SkillToolStepBinding()
}

sealed class SkillModelStepBinding {
    data class Bound(
        val inputs: Map<String, String>,
    ) : SkillModelStepBinding()

    data class Missing(
        val reason: String,
    ) : SkillModelStepBinding()
}

sealed class SkillModelOutputProgression {
    data object None : SkillModelOutputProgression()

    data class BoundTool(
        val modelStep: SkillStep.ModelStep,
        val toolStep: SkillStep.ToolStep,
        val request: ToolRequest,
        val draft: ActionDraft,
    ) : SkillModelOutputProgression()

    data class Rejected(
        val reason: String,
    ) : SkillModelOutputProgression()
}
