package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test

/**
 * Suite C: the Suite B cases with tools passed through Ollama's native `tools` field. Tool-call
 * arguments become strings the way `RemoteChatRuntime` converts remote tool calls (nested values are
 * rejected), and the call must name a registry tool.
 */
class NativeToolsSuiteTest {
    private val registry: ToolRegistry = defaultBuiltInToolRegistry
    private val retriever = LabToolRetriever(registry.specs())

    @Test
    fun nativeToolsSuite() {
        requireLabOptIn("C")
        val client = OllamaClient()
        val models = availableLabModels(client)
        Assume.assumeTrue("no lab models installed", models.isNotEmpty())
        LabManifest.recordModels(client, models)
        val cases = InitialPlanningCases.benchCases()
        LabManifest.recordSuite(
            SUITE,
            JSONObject()
                .put("cases", cases.size)
                .put("registryTools", registry.specs().size)
                .put("topK", TOP_K)
                .put("strictSystem", STRICT_SYSTEM)
                .put("freeSystem", FREE_SYSTEM)
                .put("numCtxFull", FULL_OPTIONS.numCtx)
                .put("numCtxTopK", TOPK_OPTIONS.numCtx),
        )
        models.forEach { model ->
            if (OllamaClient.CAPABILITY_TOOLS !in client.capabilities(model)) {
                LabManifest.recordUnsupported(SUITE, model, "Ollama declares no tools capability")
                LabLog.info("suite C model=$model unsupported: no tools capability")
                return@forEach
            }
            client.warmUp(model)
            if (LabEnv.modeSelected("N-full")) {
                run(client, model, "N-full", cases, STRICT_SYSTEM, FULL_OPTIONS) { registry.specs() }
            }
            if (LabEnv.modeSelected("N-topk")) {
                run(client, model, "N-topk", cases, STRICT_SYSTEM, TOPK_OPTIONS) { case -> retriever.topK(case.input, TOP_K) }
            }
            // Production-faithful LocalModelFirst native shape: the retrieved shortlist plus the
            // `answer` no-tool choice (orchestration/InitialToolPlanner.kt:52-60,136). `answer` is
            // offered as a declaration like any other, and choosing it scores as no tool.
            if (LabEnv.modeSelected("N-topk-lmf")) {
                run(
                    client, model, "N-topk-lmf", cases, STRICT_SYSTEM, TOPK_OPTIONS,
                    specsFor = { case -> retriever.topK(case.input, TOP_K) + NO_TOOL_CHOICE_SPEC },
                )
            }
            if (LabEnv.modeSelected("N-topk-free")) {
                run(client, model, "N-topk-free", cases, FREE_SYSTEM, TOPK_OPTIONS) { case ->
                    retriever.topK(case.input, TOP_K)
                }
            }
        }
    }

    private fun run(
        client: OllamaClient,
        model: String,
        mode: String,
        cases: List<InitialCase>,
        system: String,
        options: OllamaClient.Options,
        specsFor: (InitialCase) -> List<ToolSpec>,
    ) {
        var correct = 0
        JsonlWriter(SUITE, model, mode).use { writer ->
            cases.forEach { case ->
                val specs = specsFor(case)
                val result = client.chat(
                    model = model,
                    messages = listOf(OllamaClient.Message("system", system), OllamaClient.Message("user", case.input)),
                    options = options,
                    tools = JSONArray(specs.map(::nativeTool)),
                )
                val noToolChoice = mode.endsWith("-lmf")
                val (answer, kind) = answerFromNative(result, noToolChoice)
                val advertised = specs.map(ToolSpec::name)
                val record = InitialPlanningCases.record(SUITE, model, mode, case, answer, kind, rawPreview(result), registry)
                    .put("toolCallCount", result.toolCalls.size)
                    .put("modelCalls", 1)
                    .put("advertisedToolCount", advertised.size)
                    .put("metrics", result.metricsJson())
                if (advertised.size < registry.specs().size) {
                    record.put("advertisedTools", JSONArray(advertised))
                    record.put("outsideAdvertised", answer.tool != null && answer.tool !in advertised)
                    if (case.goldTools.isNotEmpty()) {
                        record.put("retrieverHit", case.goldTools.any { it in advertised })
                    }
                }
                if (record.getBoolean("correct")) correct++
                writer.write(record)
            }
        }
        LabLog.info("suite C model=$model mode=$mode cases=${cases.size} correct=$correct")
    }

    private fun nativeTool(spec: ToolSpec): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", spec.name)
                    .put("description", listOf(spec.title, spec.description).filter(String::isNotBlank).joinToString("。"))
                    .put("parameters", JSONObject(spec.inputSchemaJson)),
            )

    private fun answerFromNative(result: OllamaClient.ChatResult, noToolChoice: Boolean): Pair<LabAnswer, String> {
        if (result.error != null) return LabAnswer.INVALID to "error"
        val call = result.toolCalls.firstOrNull()
            ?: return LabAnswer.STOP to if (result.text.isBlank()) "blank" else "text"
        // Production reads `answer` as a deliberate no-tool decision, not an unknown tool
        // (InitialToolPlanner.kt:144). The choice is never a registry tool.
        if (noToolChoice && call.name == LOCAL_MODEL_NO_TOOL_CHOICE) {
            return LabAnswer.STOP to "no_tool_choice"
        }
        if (!registry.isKnownTool(call.name)) return LabAnswer(call.name, invalid = true) to "unknown_tool"
        val arguments = call.arguments.toToolArgumentMapOrNull()
            ?: return LabAnswer(call.name, invalid = true) to "nested_arguments"
        return LabAnswer(call.name, arguments) to if (result.toolCalls.size > 1) "multi_call" else "call"
    }

    /** Same conversion as the private `toToolArgumentMapOrNull` in runtime/RemoteChatRuntime.kt. */
    private fun JSONObject.toToolArgumentMapOrNull(): Map<String, String>? {
        val result = linkedMapOf<String, String>()
        keys().forEach { key ->
            if (isNull(key)) return@forEach
            result[key] = when (val value = get(key)) {
                is String -> value
                is Number -> value.toString()
                is Boolean -> value.toString()
                else -> return null
            }
        }
        return result
    }

    private fun rawPreview(result: OllamaClient.ChatResult): String {
        val calls = result.toolCalls.joinToString(" ") { call -> "tool_call:${call.name}${call.arguments}" }
        return listOf(calls, result.text).filter(String::isNotBlank).joinToString(" | ")
    }

    private companion object {
        const val SUITE = "native-tools"
        const val TOP_K = 8
        const val STRICT_SYSTEM = "你是手机动作规划器。为用户请求调用一个最合适的工具，不解释。"
        const val FREE_SYSTEM =
            "你是手机助手。如果用户请求需要在手机上执行动作，就调用一个最合适的工具；" +
                "如果不需要（闲聊、提问、解释、翻译，或用户明确说不要做某事），直接用一句话回答，不调用工具。"
        val FULL_OPTIONS = OllamaClient.Options(numCtx = 32768)
        val TOPK_OPTIONS = OllamaClient.Options()
    }
}
