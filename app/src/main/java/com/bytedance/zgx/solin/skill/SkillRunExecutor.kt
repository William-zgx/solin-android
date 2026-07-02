package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.safety.SafetyContext
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.tool.ToolExecutor
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus

class SkillRunExecutor(
    private val toolExecutor: ToolExecutor,
    private val modelExecutor: SkillModelStepExecutor,
    private val toolGate: SkillToolGate = RegistrySkillToolGate(),
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    maxSteps: Int = DEFAULT_MAX_STEPS,
) {
    private val progressor = SkillRunProgressor(maxSteps = maxSteps, toolRegistry = toolRegistry)

    fun execute(plan: SkillPlan): SkillRunResult {
        validatePlanForExecution(plan)?.let { return it }
        return runSteps(
            plan = plan,
            startIndex = 0,
            outputs = progressor.initialOutputs(plan),
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
                outputs = progressor.publicOutputs(continuation.outputs, continuation.privateOutputRefs),
                trace = continuation.trace + SkillRunTrace.Failed("pending skill step is missing"),
                error = "pending skill step is missing",
            )
        if (step.id != continuation.pendingStepId) {
            return SkillRunResult(
                state = SkillRunState.Failed,
                outputs = progressor.publicOutputs(continuation.outputs, continuation.privateOutputRefs),
                trace = continuation.trace + SkillRunTrace.Failed("pending skill step changed"),
                error = "pending skill step changed",
            )
        }
        if (result.requestId != continuation.pendingToolRequest.id) {
            return SkillRunResult(
                state = SkillRunState.Failed,
                outputs = progressor.publicOutputs(continuation.outputs, continuation.privateOutputRefs),
                trace = continuation.trace + SkillRunTrace.Failed("tool result does not match pending skill step"),
                error = "tool result does not match pending skill step",
            )
        }

        val outputs = continuation.outputs.deepMutableCopy()
        val privateOutputRefs = continuation.privateOutputRefs.toMutableSet()
        val trace = continuation.trace.toMutableList()
        val validatedResult = validatedResult(continuation.pendingToolRequest, result)
        trace += SkillRunTrace.ToolFinished(
            stepId = step.id,
            toolName = continuation.pendingToolRequest.toolName,
            status = validatedResult.status,
            summary = validatedResult.summary,
        )
        if (validatedResult.status != ToolStatus.Succeeded) {
            return SkillRunResult(
                state = SkillRunState.Failed,
                outputs = progressor.publicOutputs(outputs, privateOutputRefs),
                trace = trace,
                error = validatedResult.summary,
            )
        }

        outputs[step.id] = progressor.outputForToolResult(validatedResult, continuation.pendingDraft)
        privateOutputRefs += progressor.privateOutputRefsFor(step.id, continuation.pendingToolRequest.toolName)
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
                outputs = progressor.publicOutputs(continuation.outputs, continuation.privateOutputRefs),
                trace = continuation.trace + SkillRunTrace.Failed("pending skill step is missing"),
                error = "pending skill step is missing",
            )
        if (step.id != continuation.pendingStepId) {
            return SkillRunResult(
                state = SkillRunState.Failed,
                outputs = progressor.publicOutputs(continuation.outputs, continuation.privateOutputRefs),
                trace = continuation.trace + SkillRunTrace.Failed("pending skill step changed"),
                error = "pending skill step changed",
            )
        }

        return SkillRunResult(
            state = SkillRunState.Cancelled,
            outputs = progressor.publicOutputs(continuation.outputs, continuation.privateOutputRefs),
            trace = continuation.trace + SkillRunTrace.Cancelled(
                stepId = step.id,
                toolName = continuation.pendingToolRequest.toolName,
                reason = reason,
            ),
            error = reason,
        )
    }

    private fun validatePlanForExecution(plan: SkillPlan): SkillRunResult? {
        val validationError = progressor.validateForExecution(plan)
        if (validationError != null) {
            val traceReason = if (validationError == "skill step limit exceeded") {
                validationError
            } else {
                "invalid plan: $validationError"
            }
            return SkillRunResult(
                state = SkillRunState.Failed,
                outputs = emptyMap(),
                trace = listOf(SkillRunTrace.Failed(traceReason)),
                error = validationError,
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
                    val binding = when (val result = progressor.bindToolStep(step, outputs, privateOutputRefs)) {
                        is SkillToolStepBinding.Bound -> result
                        is SkillToolStepBinding.Missing ->
                            return failed(step.id, result.reason, outputs, privateOutputRefs, trace)
                        is SkillToolStepBinding.Rejected ->
                            return failed(step.id, result.reason, outputs, privateOutputRefs, trace)
                    }
                    val request = binding.request
                    val draft = binding.draft
                    when (val gateDecision = toolGate.evaluate(step, request)) {
                        SkillToolGateDecision.Allow -> Unit
                        is SkillToolGateDecision.AwaitingConfirmation -> {
                            trace += SkillRunTrace.AwaitingConfirmation(step.id, request.toolName, gateDecision.reason)
                            return SkillRunResult(
                                state = SkillRunState.AwaitingConfirmation,
                                outputs = progressor.publicOutputs(outputs, privateOutputRefs),
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
                    val result = validatedResult(request, toolExecutor.execute(request))
                    trace += SkillRunTrace.ToolFinished(
                        stepId = step.id,
                        toolName = request.toolName,
                        status = result.status,
                        summary = result.summary,
                    )
                    if (result.status != ToolStatus.Succeeded) {
                        return SkillRunResult(
                            state = SkillRunState.Failed,
                            outputs = progressor.publicOutputs(outputs, privateOutputRefs),
                            trace = trace,
                            error = result.summary,
                        )
                    }
                    outputs[step.id] = progressor.outputForToolResult(result, draft)
                    privateOutputRefs += progressor.privateOutputRefsFor(step.id, request.toolName)
                }

                is SkillStep.ModelStep -> {
                    val inputs = when (val binding = progressor.bindModelStep(step, outputs)) {
                        is SkillModelStepBinding.Bound -> binding.inputs
                        is SkillModelStepBinding.Missing ->
                            return failed(step.id, binding.reason, outputs, privateOutputRefs, trace)
                    }
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
            outputs = progressor.publicOutputs(outputs, privateOutputRefs),
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
            outputs = progressor.publicOutputs(outputs, privateOutputRefs),
            trace = trace,
            error = message,
        )
    }

    private fun validatedResult(request: ToolRequest, result: ToolResult): ToolResult =
        toolRegistry.validateResult(request, result) ?: result

    private companion object {
        const val DEFAULT_MAX_STEPS = 8
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
