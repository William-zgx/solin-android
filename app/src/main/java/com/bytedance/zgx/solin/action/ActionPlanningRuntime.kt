package com.bytedance.zgx.solin.action

import com.bytedance.zgx.solin.orchestration.collab.StrictJsonScan
import com.bytedance.zgx.solin.runtime.DedicatedLiteRtEngine
import com.bytedance.zgx.solin.runtime.LocalCallMetrics
import com.bytedance.zgx.solin.runtime.LocalEngine
import com.bytedance.zgx.solin.runtime.LocalOneShotCancellation
import com.bytedance.zgx.solin.runtime.LocalOneShotRequest
import com.bytedance.zgx.solin.runtime.LocalOneShotResult
import com.bytedance.zgx.solin.runtime.LocalOneShotUnavailableReason
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import com.bytedance.zgx.solin.tool.ToolSpec
import java.io.File
import org.json.JSONObject

interface ActionPlanningRuntime {
    fun classifyIntent(input: String): IntentCandidate =
        if (isLikelyAction(input)) {
            IntentCandidate(
                toolName = null,
                confidence = ActionIntentConfidence.High,
                reason = "runtime likely action",
            )
        } else {
            IntentCandidate(
                toolName = null,
                confidence = ActionIntentConfidence.None,
                reason = "runtime rejected action intent",
            )
        }

    fun isLikelyAction(input: String): Boolean
    fun plan(input: String, actionModelPath: String?): ActionPlanningResult

    /**
     * Same as [plan], but the model prompt advertises only [toolSpecs] (e.g. the observation
     * replanner's scoped tool list). Parsing and acceptance are unchanged; runtimes without a model
     * prompt ignore the scope.
     */
    fun plan(input: String, actionModelPath: String?, toolSpecs: List<ToolSpec>): ActionPlanningResult =
        plan(input, actionModelPath)

    /** Initial-route variant whose model one-shot shares the run's cooperative Stop signal. */
    fun plan(
        input: String,
        actionModelPath: String?,
        toolSpecs: List<ToolSpec>,
        cancellation: LocalOneShotCancellation,
    ): ActionPlanningResult = plan(input, actionModelPath, toolSpecs)

    fun parseModelToolOutput(output: String): ModelToolOutputParseResult = ModelToolOutputParseResult.None

    /**
     * Local-only action-intent gate for the debug RuleFirstGatedModel strategy. This is not an
     * authorization decision: it can only decline model planning, never approve a tool execution.
     */
    fun gateActionIntent(input: String, actionModelPath: String?): ActionIntentGateResult =
        ActionIntentGateResult(open = false, reason = "local_model_action_gate_unsupported")

    /** Initial-route variant whose gate one-shot shares the run's cooperative Stop signal. */
    fun gateActionIntent(
        input: String,
        actionModelPath: String?,
        cancellation: LocalOneShotCancellation,
    ): ActionIntentGateResult = gateActionIntent(input, actionModelPath)
}

data class ActionPlanningResult(
    val plan: ActionPlan,
    val usedModel: Boolean,
    val fallbackReason: String?,
    val modelAttempted: Boolean = usedModel,
    val modelOutputPreview: String? = null,
    val modelFailureReason: String? = null,
    /** Text-free timing of the model call, when one ran; for the local device bench only. */
    val modelMetrics: LocalCallMetrics? = null,
)

/** A stable, text-free planner-gate verdict. [reason] is safe to include in a redacted run trace. */
data class ActionIntentGateResult(
    val open: Boolean,
    val reason: String,
)

sealed class ModelToolOutputParseResult {
    data object None : ModelToolOutputParseResult()
    data class Parsed(val draft: ActionDraft) : ModelToolOutputParseResult()
    data class Rejected(
        val toolName: String?,
        val reason: String,
    ) : ModelToolOutputParseResult()
}

/**
 * Model planning runs on [sharedEngine] (the process's loaded chat engine) first. Only when that
 * engine does not hold the requested model does the planner use [fallbackEngine]; a busy or
 * failing shared engine falls back to the rule planner instead, so a phone never maps the same
 * multi-GB model twice. Planners in one process should share a single [fallbackEngine].
 */
class HybridActionPlanningRuntime(
    fallbackEngine: DedicatedLiteRtEngine,
    toolRegistry: ToolRegistry = defaultBuiltInToolRegistry,
    private val rulePlanner: MobileActionPlanner = MobileActionPlanner(toolRegistry = toolRegistry),
    sharedEngine: LocalEngine? = null,
) : ActionPlanningRuntime, AutoCloseable {
    constructor(
        cacheDir: File,
        toolRegistry: ToolRegistry = defaultBuiltInToolRegistry,
        rulePlanner: MobileActionPlanner = MobileActionPlanner(toolRegistry = toolRegistry),
        sharedEngine: LocalEngine? = null,
    ) : this(DedicatedLiteRtEngine(cacheDir), toolRegistry, rulePlanner, sharedEngine)

    private val modelPlanner = ModelBackedActionPlanner(sharedEngine, fallbackEngine, rulePlanner, toolRegistry)

    override fun classifyIntent(input: String): IntentCandidate =
        rulePlanner.classifyIntent(input)

    override fun isLikelyAction(input: String): Boolean =
        classifyIntent(input).isAction

    override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
        planWithToolSpecs(input, actionModelPath, toolSpecs = null, LocalOneShotCancellation())

    override fun plan(input: String, actionModelPath: String?, toolSpecs: List<ToolSpec>): ActionPlanningResult =
        planWithToolSpecs(input, actionModelPath, toolSpecs, LocalOneShotCancellation())

    override fun plan(
        input: String,
        actionModelPath: String?,
        toolSpecs: List<ToolSpec>,
        cancellation: LocalOneShotCancellation,
    ): ActionPlanningResult = planWithToolSpecs(input, actionModelPath, toolSpecs, cancellation)

    private fun planWithToolSpecs(
        input: String,
        actionModelPath: String?,
        toolSpecs: List<ToolSpec>?,
        cancellation: LocalOneShotCancellation,
    ): ActionPlanningResult {
        if (actionModelPath != null) {
            val modelAttemptResult = runCatching {
                modelPlanner.plan(input, actionModelPath, toolSpecs, cancellation)
            }
            val modelAttempt = modelAttemptResult.getOrNull()
            val modelPlan = modelAttempt?.plan
            if (modelPlan?.kind == ActionPlanKind.Draft && modelPlan.draft != null) {
                return ActionPlanningResult(
                    plan = modelPlan,
                    usedModel = true,
                    fallbackReason = null,
                    modelAttempted = true,
                    modelOutputPreview = modelAttempt.output.previewForDiagnostics(),
                    modelMetrics = modelAttempt.metrics,
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
                modelMetrics = modelAttempt?.metrics,
            )
        }

        return ActionPlanningResult(
            plan = rulePlanner.plan(input),
            usedModel = false,
            fallbackReason = "动作规划模型未安装或未校验",
            modelAttempted = false,
        )
    }

    override fun parseModelToolOutput(output: String): ModelToolOutputParseResult =
        rulePlanner.parseModelToolOutput(output)

    override fun gateActionIntent(input: String, actionModelPath: String?): ActionIntentGateResult =
        gateActionIntent(input, actionModelPath, LocalOneShotCancellation())

    override fun gateActionIntent(
        input: String,
        actionModelPath: String?,
        cancellation: LocalOneShotCancellation,
    ): ActionIntentGateResult {
        if (actionModelPath == null) {
            return ActionIntentGateResult(open = false, reason = "local_model_action_gate_no_model")
        }
        return runCatching { modelPlanner.gateActionIntent(input, actionModelPath, cancellation) }
            .getOrElse { ActionIntentGateResult(open = false, reason = "local_model_action_gate_error") }
    }

    override fun close() {
        modelPlanner.close()
    }
}

private class ModelBackedActionPlanner(
    private val sharedEngine: LocalEngine?,
    private val ownEngine: DedicatedLiteRtEngine,
    private val parser: MobileActionPlanner,
    private val toolRegistry: ToolRegistry,
) : AutoCloseable {
    fun plan(
        input: String,
        modelPath: String,
        toolSpecs: List<ToolSpec>?,
        cancellation: LocalOneShotCancellation = LocalOneShotCancellation(),
    ): ModelBackedPlanningAttempt {
        val request = LocalOneShotRequest(
            modelPath = modelPath,
            systemInstruction = ACTION_PLANNER_SYSTEM_INSTRUCTION,
            prompt = actionPrompt(input, toolSpecs?.takeIf { it.isNotEmpty() } ?: toolRegistry.specs()),
            cancellation = cancellation,
        )
        return when (val result = generate(request)) {
            is LocalOneShotResult.Generated -> {
                val draft = (parser.parseModelToolOutput(result.text) as? ModelToolOutputParseResult.Parsed)?.draft
                ModelBackedPlanningAttempt(
                    plan = draft?.let { ActionPlan(ActionPlanKind.Draft, it) },
                    output = result.text,
                    failureReason = if (draft == null) parser.modelOutputFailureReason(result.text) else null,
                    metrics = result.metrics,
                )
            }

            is LocalOneShotResult.Unavailable -> ModelBackedPlanningAttempt(
                plan = null,
                output = "",
                failureReason = result.diagnosticReason(),
                metrics = null,
            )
        }
    }

    fun gateActionIntent(
        input: String,
        modelPath: String,
        cancellation: LocalOneShotCancellation = LocalOneShotCancellation(),
    ): ActionIntentGateResult {
        val request = LocalOneShotRequest(
            modelPath = modelPath,
            systemInstruction = ACTION_INTENT_GATE_SYSTEM_INSTRUCTION,
            prompt = JSONObject().put("request", input).toString(),
            maxOutputTokens = ACTION_INTENT_GATE_MAX_OUTPUT_TOKENS,
            cancellation = cancellation,
        )
        return when (val result = generate(request)) {
            is LocalOneShotResult.Generated -> parseActionIntentGateResult(result.text)
            is LocalOneShotResult.Unavailable -> ActionIntentGateResult(
                open = false,
                reason = "local_model_action_gate_${result.reason.name.lowercase()}",
            )
        }
    }

    private fun generate(request: LocalOneShotRequest): LocalOneShotResult {
        val shared = sharedEngine?.generateOneShot(request)
        return when {
            shared is LocalOneShotResult.Generated -> shared.also { ownEngine.releaseIfHolding(request.modelPath) }
            shared == null || shared.mayUseOwnEngine() -> ownEngine.generateOneShot(request)
            else -> shared
        }
    }

    override fun close() {
        ownEngine.close()
    }
}

/**
 * Only a shared engine that does not hold this model may hand off to the planner's own engine;
 * Busy and Failed mean the model is already mapped, so loading a second copy is never the answer.
 */
private fun LocalOneShotResult.mayUseOwnEngine(): Boolean =
    this is LocalOneShotResult.Unavailable &&
        (reason == LocalOneShotUnavailableReason.ModelNotLoaded || reason == LocalOneShotUnavailableReason.Unsupported)

private fun LocalOneShotResult.Unavailable.diagnosticReason(): String =
    listOfNotNull("local_engine_${reason.name.lowercase()}", detail?.takeIf { it.isNotBlank() })
        .joinToString(separator = ":")
        .take(240)

private data class ModelBackedPlanningAttempt(
    val plan: ActionPlan?,
    val output: String,
    val failureReason: String?,
    val metrics: LocalCallMetrics?,
)

private const val ACTION_PLANNER_SYSTEM_INSTRUCTION =
    "你是手机动作规划器。只能输出 call:函数名 {\"参数名\":\"值\"}，不解释。"

private const val ACTION_INTENT_GATE_MAX_OUTPUT_TOKENS = 64
private const val ACTION_INTENT_GATE_MIN_CONFIDENCE = 0.9

/** Suite E `json-prompt`, byte-for-byte; the user prompt is one JSON object with a `request` field. */
private const val ACTION_INTENT_GATE_SYSTEM_INSTRUCTION =
    "You decide whether a phone assistant must perform a phone action for the user's request. " +
        "action = true: the user asks the assistant to do something on the phone now, such as opening an app, " +
        "setting an alarm, timer or reminder, drafting a message, email, contact or calendar event, " +
        "calling, searching the web or a map, or changing a setting. " +
        "action = false: chat, a question the assistant can answer in text, an explanation, a translation, " +
        "or a request that tells the assistant not to do something.\n\n" +
        "The user message is a JSON object whose \"request\" field is the request. " +
        "Reply with one JSON object only: {\"action\": true or false, \"confidence\": a number from 0 to 1}."

/** Strictly one object with exactly a Boolean action and a numeric confidence in 0..1. */
private fun parseActionIntentGateResult(raw: String): ActionIntentGateResult {
    if (StrictJsonScan.firstViolation(raw) != null) {
        return ActionIntentGateResult(open = false, reason = "local_model_action_gate_invalid_output")
    }
    val json = runCatching { JSONObject(raw) }.getOrNull()
        ?: return ActionIntentGateResult(open = false, reason = "local_model_action_gate_invalid_output")
    if (json.length() != 2 || !json.has("action") || !json.has("confidence")) {
        return ActionIntentGateResult(open = false, reason = "local_model_action_gate_invalid_output")
    }
    val action = json.opt("action") as? Boolean
        ?: return ActionIntentGateResult(open = false, reason = "local_model_action_gate_invalid_output")
    val confidence = (json.opt("confidence") as? Number)?.toDouble()
        ?.takeIf { value -> value.isFinite() && value in 0.0..1.0 }
        ?: return ActionIntentGateResult(open = false, reason = "local_model_action_gate_invalid_output")
    return when {
        !action -> ActionIntentGateResult(open = false, reason = "local_model_action_gate_no_action")
        confidence < ACTION_INTENT_GATE_MIN_CONFIDENCE ->
            ActionIntentGateResult(open = false, reason = "local_model_action_gate_low_confidence")
        else -> ActionIntentGateResult(open = true, reason = "local_model_action_gate_open")
    }
}

private fun MobileActionPlanner.modelOutputFailureReason(output: String): String =
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

internal fun actionPrompt(
    input: String,
    toolSpecs: List<ToolSpec> = defaultBuiltInToolRegistry.specs(),
): String {
    val toolLines = toolSpecs.joinToString(separator = "\n") { spec ->
        spec.toActionPromptLine()
    }
    return """
        将用户请求转换成一个手机动作调用。只能输出 call:函数名 {"参数名":"值"}（函数名换成下面列表里的一个函数），不解释。
        支持函数如下；参数必须符合对应 JSON schema：
        $toolLines

        用户请求：$input
    """.trimIndent()
}

private fun ToolSpec.toActionPromptLine(): String {
    val schema = runCatching { JSONObject(inputSchemaJson) }.getOrNull()
    val properties = schema?.optJSONObject("properties") ?: JSONObject()
    val requiredNames = schema?.optStringList("required").orEmpty()
    val propertyNames = properties.keysList()
    val primaryNames = requiredNames.ifEmpty { emptyList() }
    val optionalNames = propertyNames.filterNot { name -> name in primaryNames }
    val primaryShape = primaryNames.joinToString(prefix = "{", postfix = "}") { name ->
        val property = properties.optJSONObject(name) ?: JSONObject()
        """"$name":${property.placeholderFor(name)}"""
    }
    val optionalClause = optionalNames
        .takeIf { names -> names.isNotEmpty() }
        ?.joinToString(prefix = " 可选：")
        .orEmpty()
    val hintClause = planningPromptHint
        ?.takeIf { hint -> hint.isNotBlank() }
        ?.let { hint -> " ${hint.compactActionPromptText(maxLength = 64)}" }
        .orEmpty()
    val descriptionClause = description
        .takeIf { value -> value.isNotBlank() }
        ?.compactActionPromptText(maxLength = 64)
        ?.let { value -> "。$value" }
        .orEmpty()
    return "- $name $primaryShape$optionalClause$descriptionClause$hintClause"
}

private fun String.compactActionPromptText(maxLength: Int): String =
    replace(Regex("\\s+"), " ")
        .trim()
        .let { value ->
            if (value.length <= maxLength) value else value.take(maxLength).trimEnd() + "..."
        }

private fun JSONObject.placeholderFor(name: String): String {
    val enumValues = optStringList("enum")
    if (enumValues.isNotEmpty()) return enumValues.joinToString(prefix = "\"", postfix = "\"", separator = "|")
    return when (optString("type")) {
        "integer", "number" -> {
            val minimum = optNumberAsString("minimum")
            val maximum = optNumberAsString("maximum")
            if (minimum != null && maximum != null) {
                "\"$minimum..$maximum\""
            } else {
                "\"...\""
            }
        }

        "boolean" -> "\"true|false\""
        else -> when {
            name.equals("uri", ignoreCase = true) -> "\"https://...\""
            name.endsWith("Millis", ignoreCase = true) -> "\"...\""
            else -> "\"...\""
        }
    }
}

private fun JSONObject.optStringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optString(index).takeIf { value -> value.isNotBlank() }
    }
}

private fun JSONObject.keysList(): List<String> {
    val names = mutableListOf<String>()
    val iterator = keys()
    while (iterator.hasNext()) {
        names += iterator.next()
    }
    return names
}

private fun JSONObject.optNumberAsString(name: String): String? =
    opt(name)?.let { value ->
        when (value) {
            is Number -> value.toLong().takeIf { value.toDouble() == it.toDouble() }?.toString()
                ?: value.toString()

            else -> value.toString().takeIf { it.isNotBlank() }
        }
    }
