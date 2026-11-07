package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.IntentCandidate
import com.bytedance.zgx.solin.action.MobileActionPlanner
import com.bytedance.zgx.solin.action.ModelToolOutputParseResult
import com.bytedance.zgx.solin.action.actionPrompt
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import java.io.IOException

/**
 * Must stay identical to the system instruction in `actionConversationConfig()`
 * (action/ActionPlanningRuntime.kt), which is private there.
 */
const val ACTION_PLANNER_SYSTEM_INSTRUCTION = "你是手机动作规划器。只能输出 call:function {\"arg\":\"value\"}，不解释。"

/**
 * Lab twin of `HybridActionPlanningRuntime` + its private `ModelBackedActionPlanner`: same prompt
 * (`actionPrompt`), same system instruction, greedy sampling, same parser, and the same fallback to
 * the rule planner. The only difference is the backend (Ollama instead of LiteRT-LM), plus capture of
 * every raw output and its timings in [calls].
 *
 * A non-null `actionModelPath` means "a model is installed"; its value is ignored.
 */
class OllamaActionPlanningRuntime(
    private val client: OllamaClient,
    val model: String,
    private val toolRegistry: ToolRegistry = defaultBuiltInToolRegistry,
    private val rulePlanner: MobileActionPlanner = MobileActionPlanner(toolRegistry = toolRegistry),
    private val options: OllamaClient.Options = OllamaClient.Options(),
    private val responseCache: MutableMap<String, OllamaClient.ChatResult>? = null,
) : ActionPlanningRuntime {
    data class Call(
        val prompt: String,
        val toolNames: List<String>,
        val rawOutput: String,
        val parsedDraft: ActionDraft?,
        val failureReason: String?,
        val result: OllamaClient.ChatResult,
        val cached: Boolean,
    )

    private val recorded = mutableListOf<Call>()
    val calls: List<Call> get() = recorded.toList()

    fun clearCalls() = recorded.clear()

    override fun classifyIntent(input: String): IntentCandidate = rulePlanner.classifyIntent(input)

    override fun isLikelyAction(input: String): Boolean = classifyIntent(input).isAction

    override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
        planWithToolSpecs(input, actionModelPath, toolSpecs = null)

    override fun plan(input: String, actionModelPath: String?, toolSpecs: List<ToolSpec>): ActionPlanningResult =
        planWithToolSpecs(input, actionModelPath, toolSpecs)

    override fun parseModelToolOutput(output: String): ModelToolOutputParseResult =
        rulePlanner.parseModelToolOutput(output)

    private fun planWithToolSpecs(
        input: String,
        actionModelPath: String?,
        toolSpecs: List<ToolSpec>?,
    ): ActionPlanningResult {
        if (actionModelPath != null) {
            val modelAttemptResult = runCatching { modelAttempt(input, toolSpecs) }
            val modelAttempt = modelAttemptResult.getOrNull()
            val modelPlan = modelAttempt?.plan
            if (modelPlan?.kind == ActionPlanKind.Draft && modelPlan.draft != null) {
                return ActionPlanningResult(
                    plan = modelPlan,
                    usedModel = true,
                    fallbackReason = null,
                    modelAttempted = true,
                    modelOutputPreview = modelAttempt.output.previewForDiagnostics(),
                )
            }
            val modelFailureReason = modelAttempt?.failureReason
                ?: modelAttemptResult.exceptionOrNull()?.diagnosticReason()
                ?: "动作规划模型未产出可执行草稿"
            return ActionPlanningResult(
                plan = rulePlanner.plan(input),
                usedModel = false,
                fallbackReason = "动作规划模型未产出可执行草稿",
                modelAttempted = true,
                modelOutputPreview = modelAttempt?.output?.previewForDiagnostics(),
                modelFailureReason = modelFailureReason,
            )
        }
        return ActionPlanningResult(
            plan = rulePlanner.plan(input),
            usedModel = false,
            fallbackReason = "动作规划模型未安装或未校验",
            modelAttempted = false,
        )
    }

    private data class ModelAttempt(val plan: ActionPlan?, val output: String, val failureReason: String?)

    private fun modelAttempt(input: String, toolSpecs: List<ToolSpec>?): ModelAttempt {
        val specs = toolSpecs?.takeIf { it.isNotEmpty() } ?: toolRegistry.specs()
        val prompt = actionPrompt(input, specs)
        val cacheKey = "$model\u0000$prompt"
        val cachedResult = responseCache?.get(cacheKey)
        val result = cachedResult ?: client.chat(
            model = model,
            messages = listOf(
                OllamaClient.Message("system", ACTION_PLANNER_SYSTEM_INSTRUCTION),
                OllamaClient.Message("user", prompt),
            ),
            options = options,
        )
        if (cachedResult == null && result.error == null) responseCache?.put(cacheKey, result)
        val rawOutput = result.text
        val draft = if (result.error == null) rulePlanner.parseModelOutput(rawOutput) else null
        val failureReason = when {
            result.error != null -> null
            draft == null -> rulePlanner.modelOutputFailureReason(rawOutput)
            else -> null
        }
        recorded += Call(
            prompt = prompt,
            toolNames = specs.map { it.name },
            rawOutput = rawOutput,
            parsedDraft = draft,
            failureReason = failureReason ?: result.error,
            result = result,
            cached = cachedResult != null,
        )
        if (result.error != null) throw IOException(result.error)
        return ModelAttempt(
            plan = draft?.let { ActionPlan(ActionPlanKind.Draft, it) },
            output = rawOutput,
            failureReason = failureReason,
        )
    }
}

/** Mirrors the private `modelOutputFailureReason` in action/ActionPlanningRuntime.kt. */
fun MobileActionPlanner.modelOutputFailureReason(output: String): String =
    when (val parseResult = parseModelToolOutput(output)) {
        ModelToolOutputParseResult.None ->
            if (output.isBlank()) "blank_model_output" else "missing_call_tool_output"

        is ModelToolOutputParseResult.Parsed -> "model_tool_call_not_accepted"
        is ModelToolOutputParseResult.Rejected -> parseResult.reason
    }

private fun String.previewForDiagnostics(maxChars: Int = 240): String? =
    replace(Regex("""\s+"""), " ")
        .trim()
        .takeIf { value -> value.isNotBlank() }
        ?.take(maxChars)

private fun Throwable.diagnosticReason(): String =
    "${javaClass.simpleName}:${message.orEmpty()}".take(240)
