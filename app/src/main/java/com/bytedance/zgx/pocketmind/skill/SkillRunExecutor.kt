package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.safety.SafetyContext
import com.bytedance.zgx.pocketmind.safety.SafetyOutcome
import com.bytedance.zgx.pocketmind.safety.SafetyPolicy
import com.bytedance.zgx.pocketmind.tool.ToolExecutor
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus

class SkillRunExecutor(
    private val toolExecutor: ToolExecutor,
    private val modelExecutor: SkillModelStepExecutor,
    private val toolGate: SkillToolGate = RegistrySkillToolGate(),
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
) {
    fun execute(plan: SkillPlan): SkillRunResult {
        validatePlanForExecution(plan)?.let { return it }
        return runSteps(
            plan = plan,
            startIndex = 0,
            outputs = linkedMapOf(INPUT_OUTPUT_KEY to plan.request.arguments),
            privateOutputRefs = mutableSetOf(),
            trace = mutableListOf(),
        )
    }

    fun resume(
        plan: SkillPlan,
        continuation: SkillRunContinuation,
        result: ToolResult,
    ): SkillRunResult {
        validatePlanForExecution(plan)?.let { return it }
        if (continuation.planRequestId != plan.request.id || continuation.skillId != plan.request.skillId) {
            return SkillRunResult(
                state = SkillRunState.Failed,
                outputs = emptyMap(),
                trace = continuation.trace + SkillRunTrace.Failed("skill continuation does not match plan"),
                error = "skill continuation does not match plan",
            )
        }
        val step = plan.steps.getOrNull(continuation.pendingStepIndex) as? SkillStep.ToolStep
            ?: return SkillRunResult(
                state = SkillRunState.Failed,
                outputs = continuation.outputs.withoutInput().publicOnly(continuation.privateOutputRefs),
                trace = continuation.trace + SkillRunTrace.Failed("pending skill step is missing"),
                error = "pending skill step is missing",
            )
        if (step.id != continuation.pendingStepId) {
            return SkillRunResult(
                state = SkillRunState.Failed,
                outputs = continuation.outputs.withoutInput().publicOnly(continuation.privateOutputRefs),
                trace = continuation.trace + SkillRunTrace.Failed("pending skill step changed"),
                error = "pending skill step changed",
            )
        }
        if (result.requestId != continuation.pendingToolRequest.id) {
            return SkillRunResult(
                state = SkillRunState.Failed,
                outputs = continuation.outputs.withoutInput().publicOnly(continuation.privateOutputRefs),
                trace = continuation.trace + SkillRunTrace.Failed("tool result does not match pending skill step"),
                error = "tool result does not match pending skill step",
            )
        }

        val outputs = continuation.outputs.deepMutableCopy()
        val privateOutputRefs = continuation.privateOutputRefs.toMutableSet()
        val trace = continuation.trace.toMutableList()
        trace += SkillRunTrace.ToolFinished(
            stepId = step.id,
            toolName = continuation.pendingToolRequest.toolName,
            status = result.status,
            summary = result.summary,
        )
        if (result.status != ToolStatus.Succeeded) {
            return SkillRunResult(
                state = SkillRunState.Failed,
                outputs = outputs.withoutInput().publicOnly(privateOutputRefs),
                trace = trace,
                error = result.summary,
            )
        }

        outputs[step.id] = outputForToolResult(result, continuation.pendingDraft)
        privateOutputRefs += privateOutputRefsFor(step.id, continuation.pendingToolRequest.toolName)
        return runSteps(
            plan = plan,
            startIndex = continuation.pendingStepIndex + 1,
            outputs = outputs,
            privateOutputRefs = privateOutputRefs,
            trace = trace,
        )
    }

    fun cancel(
        plan: SkillPlan,
        continuation: SkillRunContinuation,
        reason: String = "user cancelled skill run",
    ): SkillRunResult {
        validatePlanForExecution(plan)?.let { return it }
        if (continuation.planRequestId != plan.request.id || continuation.skillId != plan.request.skillId) {
            return SkillRunResult(
                state = SkillRunState.Failed,
                outputs = emptyMap(),
                trace = continuation.trace + SkillRunTrace.Failed("skill continuation does not match plan"),
                error = "skill continuation does not match plan",
            )
        }
        val step = plan.steps.getOrNull(continuation.pendingStepIndex) as? SkillStep.ToolStep
            ?: return SkillRunResult(
                state = SkillRunState.Failed,
                outputs = continuation.outputs.withoutInput().publicOnly(continuation.privateOutputRefs),
                trace = continuation.trace + SkillRunTrace.Failed("pending skill step is missing"),
                error = "pending skill step is missing",
            )
        if (step.id != continuation.pendingStepId) {
            return SkillRunResult(
                state = SkillRunState.Failed,
                outputs = continuation.outputs.withoutInput().publicOnly(continuation.privateOutputRefs),
                trace = continuation.trace + SkillRunTrace.Failed("pending skill step changed"),
                error = "pending skill step changed",
            )
        }

        return SkillRunResult(
            state = SkillRunState.Cancelled,
            outputs = continuation.outputs.withoutInput().publicOnly(continuation.privateOutputRefs),
            trace = continuation.trace + SkillRunTrace.Cancelled(
                stepId = step.id,
                toolName = continuation.pendingToolRequest.toolName,
                reason = reason,
            ),
            error = reason,
        )
    }

    private fun validatePlanForExecution(plan: SkillPlan): SkillRunResult? {
        val validation = plan.validateStructure()
        if (!validation.isValid) {
            return SkillRunResult(
                state = SkillRunState.Failed,
                outputs = emptyMap(),
                trace = listOf(SkillRunTrace.Failed("invalid plan: ${validation.errors.joinToString()}")),
                error = validation.errors.joinToString(),
            )
        }
        if (plan.steps.size > maxSteps) {
            return SkillRunResult(
                state = SkillRunState.Failed,
                outputs = emptyMap(),
                trace = listOf(SkillRunTrace.Failed("skill step limit exceeded")),
                error = "skill step limit exceeded",
            )
        }
        return null
    }

    private fun runSteps(
        plan: SkillPlan,
        startIndex: Int,
        outputs: MutableMap<String, Map<String, String>>,
        privateOutputRefs: MutableSet<String>,
        trace: MutableList<SkillRunTrace>,
    ): SkillRunResult {
        plan.steps.drop(startIndex).forEachIndexed { relativeIndex, step ->
            val stepIndex = startIndex + relativeIndex
            trace += SkillRunTrace.StepStarted(step.id)
            when (step) {
                is SkillStep.ToolStep -> {
                    rejectPrivateToolArgumentBindings(step.argumentBindings, privateOutputRefs)?.let { message ->
                        return failed(step.id, message, outputs, privateOutputRefs, trace)
                    }
                    val boundArguments = resolveBindings(step.argumentBindings, outputs)
                        ?: return failed(step.id, "missing tool argument binding", outputs, privateOutputRefs, trace)
                    val request = step.request.copy(arguments = step.request.arguments + boundArguments)
                    val draft = step.draft.copy(parameters = step.draft.parameters + boundArguments)
                    when (val gateDecision = toolGate.evaluate(step, request)) {
                        SkillToolGateDecision.Allow -> Unit
                        is SkillToolGateDecision.AwaitingConfirmation -> {
                            trace += SkillRunTrace.AwaitingConfirmation(step.id, request.toolName, gateDecision.reason)
                            return SkillRunResult(
                                state = SkillRunState.AwaitingConfirmation,
                                outputs = outputs.withoutInput().publicOnly(privateOutputRefs),
                                trace = trace,
                                error = gateDecision.reason,
                                pendingToolRequest = request,
                                continuation = SkillRunContinuation(
                                    planRequestId = plan.request.id,
                                    skillId = plan.request.skillId,
                                    pendingStepIndex = stepIndex,
                                    pendingStepId = step.id,
                                    pendingToolRequest = request,
                                    pendingDraft = draft,
                                    outputs = outputs.deepCopy(),
                                    privateOutputRefs = privateOutputRefs.toSet(),
                                    trace = trace.toList(),
                                ),
                            )
                        }

                        is SkillToolGateDecision.Reject -> {
                            return failed(step.id, gateDecision.reason, outputs, privateOutputRefs, trace)
                        }
                    }
                    val result = toolExecutor.execute(request)
                    trace += SkillRunTrace.ToolFinished(
                        stepId = step.id,
                        toolName = request.toolName,
                        status = result.status,
                        summary = result.summary,
                    )
                    if (result.status != ToolStatus.Succeeded) {
                        return SkillRunResult(
                            state = SkillRunState.Failed,
                            outputs = outputs.withoutInput().publicOnly(privateOutputRefs),
                            trace = trace,
                            error = result.summary,
                        )
                    }
                    outputs[step.id] = outputForToolResult(result, draft)
                    privateOutputRefs += privateOutputRefsFor(step.id, request.toolName)
                }

                is SkillStep.ModelStep -> {
                    val inputs = resolveBindings(step.inputBindings, outputs)
                        ?: return failed(step.id, "missing model input binding", outputs, privateOutputRefs, trace)
                    val modelResult = modelExecutor.execute(step, inputs)
                    if (modelResult.isFailure) {
                        val message = modelResult.exceptionOrNull()?.message ?: "model step failed"
                        return failed(step.id, message, outputs, privateOutputRefs, trace)
                    }
                    val output = modelResult.getOrThrow()
                    trace += SkillRunTrace.ModelFinished(
                        stepId = step.id,
                        outputKey = step.outputKey,
                    )
                    outputs[step.id] = mapOf(step.outputKey to output)
                }
            }
        }

        return SkillRunResult(
            state = SkillRunState.Succeeded,
            outputs = outputs.withoutInput().publicOnly(privateOutputRefs),
            trace = trace,
            error = null,
        )
    }

    private fun failed(
        stepId: String,
        message: String,
        outputs: Map<String, Map<String, String>>,
        privateOutputRefs: Set<String>,
        trace: MutableList<SkillRunTrace>,
    ): SkillRunResult {
        trace += SkillRunTrace.StepFailed(stepId, message)
        return SkillRunResult(
            state = SkillRunState.Failed,
            outputs = outputs.withoutInput().publicOnly(privateOutputRefs),
            trace = trace,
            error = message,
        )
    }

    private fun resolveBindings(
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

    private fun outputForToolResult(
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

    private fun Map<String, Map<String, String>>.withoutInput(): Map<String, Map<String, String>> =
        filterKeys { it != INPUT_OUTPUT_KEY }

    private fun Map<String, Map<String, String>>.publicOnly(
        privateOutputRefs: Set<String>,
    ): Map<String, Map<String, String>> =
        mapValues { (stepId, values) ->
            values.filterKeys { key -> "$stepId.$key" !in privateOutputRefs }
        }

    private fun privateOutputRefsFor(stepId: String, toolName: String): Set<String> =
        PRIVATE_OUTPUT_KEYS_BY_TOOL[toolName]
            ?.mapTo(mutableSetOf()) { key -> "$stepId.$key" }
            .orEmpty()

    private companion object {
        const val DEFAULT_MAX_STEPS = 8
        const val INPUT_OUTPUT_KEY = "input"
        val PRIVATE_OUTPUT_KEYS_BY_TOOL = mapOf(
            MobileActionFunctions.READ_CLIPBOARD to setOf("text"),
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR to setOf("ocrText"),
        )
    }
}

class SkillRunContinuation internal constructor(
    internal val planRequestId: String,
    internal val skillId: String,
    internal val pendingStepIndex: Int,
    val pendingStepId: String,
    val pendingToolRequest: ToolRequest,
    internal val pendingDraft: ActionDraft,
    internal val outputs: Map<String, Map<String, String>>,
    internal val privateOutputRefs: Set<String>,
    internal val trace: List<SkillRunTrace>,
) {
    override fun toString(): String =
        "SkillRunContinuation(" +
            "skillId=$skillId, " +
            "pendingStepId=$pendingStepId, " +
            "pendingTool=${pendingToolRequest.toolName}, " +
            "outputStepIds=${outputs.keys.sorted()}" +
            ")"
}

fun interface SkillModelStepExecutor {
    fun execute(step: SkillStep.ModelStep, inputs: Map<String, String>): Result<String>
}

fun interface SkillToolGate {
    fun evaluate(step: SkillStep.ToolStep, request: ToolRequest): SkillToolGateDecision

    companion object {
        fun allowAllForTests(): SkillToolGate =
            SkillToolGate { _, _ -> SkillToolGateDecision.Allow }
    }
}

class RegistrySkillToolGate(
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val safetyPolicy: SafetyPolicy = SafetyPolicy(),
) : SkillToolGate {
    override fun evaluate(
        step: SkillStep.ToolStep,
        request: ToolRequest,
    ): SkillToolGateDecision {
        toolRegistry.validate(request)?.let { rejection ->
            return SkillToolGateDecision.Reject(rejection.summary)
        }
        val spec = toolRegistry.specFor(request.toolName)
            ?: return SkillToolGateDecision.Reject("Unknown tool: ${request.toolName}")
        val decision = safetyPolicy.evaluate(spec, request, SafetyContext(userConfirmed = false))
        return when (decision.outcome) {
            SafetyOutcome.Allow -> SkillToolGateDecision.Allow
            SafetyOutcome.RequireConfirmation -> SkillToolGateDecision.AwaitingConfirmation(decision.reason)
            SafetyOutcome.Reject -> SkillToolGateDecision.Reject(decision.reason)
        }
    }
}

sealed class SkillToolGateDecision {
    data object Allow : SkillToolGateDecision()
    data class AwaitingConfirmation(
        val reason: String,
    ) : SkillToolGateDecision()

    data class Reject(
        val reason: String,
    ) : SkillToolGateDecision()
}

enum class SkillRunState {
    Succeeded,
    AwaitingConfirmation,
    Cancelled,
    Failed,
}

data class SkillRunResult(
    val state: SkillRunState,
    val outputs: Map<String, Map<String, String>>,
    val trace: List<SkillRunTrace>,
    val error: String?,
    val pendingToolRequest: ToolRequest? = null,
    val continuation: SkillRunContinuation? = null,
)

sealed class SkillRunTrace {
    data class StepStarted(
        val stepId: String,
    ) : SkillRunTrace()

    data class ToolFinished(
        val stepId: String,
        val toolName: String,
        val status: ToolStatus,
        val summary: String,
    ) : SkillRunTrace()

    data class ModelFinished(
        val stepId: String,
        val outputKey: String,
    ) : SkillRunTrace()

    data class AwaitingConfirmation(
        val stepId: String,
        val toolName: String,
        val reason: String,
    ) : SkillRunTrace()

    data class StepFailed(
        val stepId: String,
        val reason: String,
    ) : SkillRunTrace()

    data class Failed(
        val reason: String,
    ) : SkillRunTrace()

    data class Cancelled(
        val stepId: String,
        val toolName: String,
        val reason: String,
    ) : SkillRunTrace()
}

private fun Map<String, Map<String, String>>.deepCopy(): Map<String, Map<String, String>> =
    mapValues { (_, values) -> values.toMap() }

private fun Map<String, Map<String, String>>.deepMutableCopy(): MutableMap<String, Map<String, String>> =
    mapValuesTo(linkedMapOf()) { (_, values) -> values.toMap() }
