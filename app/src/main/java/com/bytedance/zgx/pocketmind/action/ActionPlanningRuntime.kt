package com.bytedance.zgx.pocketmind.action

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import com.bytedance.zgx.pocketmind.tool.ToolSpec
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
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
    fun parseModelToolOutput(output: String): ModelToolOutputParseResult = ModelToolOutputParseResult.None
}

data class ActionPlanningResult(
    val plan: ActionPlan,
    val usedModel: Boolean,
    val fallbackReason: String?,
)

sealed class ModelToolOutputParseResult {
    data object None : ModelToolOutputParseResult()
    data class Parsed(val draft: ActionDraft) : ModelToolOutputParseResult()
    data class Rejected(
        val toolName: String?,
        val reason: String,
    ) : ModelToolOutputParseResult()
}

class HybridActionPlanningRuntime(
    cacheDir: File,
    toolRegistry: ToolRegistry = ToolRegistry(),
    private val rulePlanner: MobileActionPlanner = MobileActionPlanner(toolRegistry = toolRegistry),
) : ActionPlanningRuntime, AutoCloseable {
    private val modelPlanner = ModelBackedActionPlanner(cacheDir, rulePlanner, toolRegistry)

    override fun classifyIntent(input: String): IntentCandidate =
        rulePlanner.classifyIntent(input)

    override fun isLikelyAction(input: String): Boolean =
        classifyIntent(input).isAction

    override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
        if (actionModelPath != null) {
            val modelPlan = runCatching { modelPlanner.plan(input, actionModelPath) }
                .getOrNull()
            if (modelPlan?.kind == ActionPlanKind.Draft && modelPlan.draft != null) {
                return ActionPlanningResult(
                    plan = modelPlan,
                    usedModel = true,
                    fallbackReason = null,
                )
            }
        }

        return ActionPlanningResult(
            plan = rulePlanner.plan(input),
            usedModel = false,
            fallbackReason = if (actionModelPath == null) "动作模型未安装或未校验" else "动作模型未产出可执行草稿",
        )
    }

    override fun parseModelToolOutput(output: String): ModelToolOutputParseResult =
        rulePlanner.parseModelToolOutput(output)

    override fun close() {
        modelPlanner.close()
    }
}

private class ModelBackedActionPlanner(
    private val cacheDir: File,
    private val parser: MobileActionPlanner,
    private val toolRegistry: ToolRegistry,
) : AutoCloseable {
    private var loadedPath: String? = null
    private var engine: Engine? = null

    fun plan(input: String, modelPath: String): ActionPlan? {
        val activeEngine = engineFor(modelPath)
        val conversation = activeEngine.createConversation(actionConversationConfig())
        return try {
            val output = StringBuilder()
            runBlocking {
                conversation.sendMessageAsync(actionPrompt(input, toolRegistry.specs())).collect { message ->
                    output.append(message.textContent())
                }
            }
            parser.parseModelOutput(output.toString())
                ?.let { ActionPlan(ActionPlanKind.Draft, it) }
        } finally {
            conversation.close()
        }
    }

    private fun engineFor(modelPath: String): Engine {
        if (loadedPath == modelPath && engine != null) return engine!!
        close()
        val created = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = cacheDir.absolutePath,
            ),
        )
        created.initialize()
        loadedPath = modelPath
        engine = created
        return created
    }

    override fun close() {
        engine?.close()
        engine = null
        loadedPath = null
    }
}

private fun actionConversationConfig(): ConversationConfig =
    ConversationConfig(
        systemInstruction = Contents.of(
            "你是手机动作规划器。只能输出 call:function {\"arg\":\"value\"}，不解释。",
        ),
        samplerConfig = SamplerConfig(
            topK = 1,
            topP = 0.1,
            temperature = 0.0,
        ),
    )

internal fun actionPrompt(
    input: String,
    toolSpecs: List<ToolSpec> = ToolRegistry().specs(),
): String {
    val toolLines = toolSpecs.joinToString(separator = "\n") { spec ->
        spec.toActionPromptLine()
    }
    return """
        将用户请求转换成一个手机动作调用。只能输出 call:function {"arg":"value"}，不解释。
        支持函数来自 ToolRegistry；参数必须符合对应 JSON schema：
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
        ?.joinToString(prefix = " 可选参数：")
        .orEmpty()
    val hintClause = planningPromptHint
        ?.takeIf { hint -> hint.isNotBlank() }
        ?.let { hint -> " $hint" }
        .orEmpty()
    return "- $name $primaryShape$optionalClause。$description$hintClause"
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

private fun com.google.ai.edge.litertlm.Message.textContent(): String =
    contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString(separator = "") { it.text }
