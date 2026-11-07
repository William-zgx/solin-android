package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.LocalModelTokenLimits
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.ReasoningEffort
import com.bytedance.zgx.solin.orchestration.DECISION_MODEL_MIN_CONFIDENCE
import com.bytedance.zgx.solin.orchestration.DECISION_MODEL_PLACEMENT_CRITERIA
import com.bytedance.zgx.solin.orchestration.DECISION_MODEL_PLACEMENT_INSTRUCTIONS
import com.bytedance.zgx.solin.orchestration.RequestComplexity
import com.bytedance.zgx.solin.orchestration.RequestComplexityAggregator
import com.bytedance.zgx.solin.orchestration.RequestComplexityInput
import com.bytedance.zgx.solin.orchestration.estimateTokensApproximate
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test

/**
 * Suite D: the local model as the cloud/edge router on `docs/decision_routing_eval.json`, asked the
 * same question `CodivDecisionModelRouter` sends, plus the rule baseline the router second-guesses.
 */
class RoutingSuiteTest {
    data class RoutingCase(val id: String, val text: String, val expected: String)

    @Test
    fun routingSuite() {
        requireLabOptIn("D")
        val cases = loadCases().let { all -> LabEnv.caseLimit?.let(all::take) ?: all }
        LabManifest.recordSuite(
            SUITE,
            JSONObject()
                .put("cases", cases.size)
                .put("minConfidence", DECISION_MODEL_MIN_CONFIDENCE)
                .put("system", SYSTEM_PROMPT)
                .put("schema", RESPONSE_SCHEMA),
        )
        runRuleBaseline(cases)
        val client = OllamaClient()
        val models = availableLabModels(client)
        Assume.assumeTrue("no lab models installed", models.isNotEmpty())
        LabManifest.recordModels(client, models)
        models.forEach { model ->
            client.warmUp(model)
            if (LabEnv.modeSelected("json-prompt")) runModel(client, model, "json-prompt", cases, format = null)
            if (LabEnv.modeSelected("json-schema")) runModel(client, model, "json-schema", cases, format = RESPONSE_SCHEMA)
        }
    }

    private fun loadCases(): List<RoutingCase> {
        val json = JSONObject(File(LabEnv.repoRoot, "docs/decision_routing_eval.json").readText())
        val cases = json.getJSONArray("cases")
        return (0 until cases.length()).map(cases::getJSONObject).map { case ->
            RoutingCase(case.getString("id"), case.getString("text"), case.getString("expected"))
        }
    }

    /** The complexity rule exactly as `chatPlacementInputs` feeds it for a first turn with no history. */
    private fun rulePlacement(text: String): Pair<String, RequestComplexity> {
        val complexity = RequestComplexityAggregator.aggregate(
            RequestComplexityInput(
                estimatedInputTokens = estimateTokensApproximate(listOf(ChatMessage(MessageRole.User, text))),
                localContextWindowTokens = LocalModelTokenLimits.MAX_TOTAL_TOKENS,
                reasoningEffort = ReasoningEffort.Off,
                requiresMultiStepPlan = false,
                requiresToolLoop = false,
                requestedOutputTokens = REQUESTED_OUTPUT_TOKENS,
            ),
        )
        return (if (complexity == RequestComplexity.Complex) "remote" else "local") to complexity
    }

    private fun runRuleBaseline(cases: List<RoutingCase>) {
        var correct = 0
        JsonlWriter(SUITE, "rules", "complexity").use { writer ->
            cases.forEach { case ->
                val (placement, complexity) = rulePlacement(case.text)
                val ok = placement == case.expected
                if (ok) correct++
                writer.write(
                    baseRecord("rules", "complexity", case)
                        .put("choice", placement)
                        .put("complexity", complexity.name)
                        .put("valid", true)
                        .put("confidence", JSONObject.NULL)
                        .put("correct", ok)
                        .put("confident", false)
                        .put("effectivePlacement", placement)
                        .put("effectiveCorrect", ok),
                )
            }
        }
        LabLog.info("suite D rules cases=${cases.size} correct=$correct")
    }

    private fun runModel(client: OllamaClient, model: String, mode: String, cases: List<RoutingCase>, format: Any?) {
        var correct = 0
        JsonlWriter(SUITE, model, mode).use { writer ->
            cases.forEach { case ->
                val result = client.chat(
                    model = model,
                    messages = listOf(
                        OllamaClient.Message("system", SYSTEM_PROMPT),
                        OllamaClient.Message("user", JSONObject().put("request", case.text.take(MAX_TEXT_CHARS)).toString()),
                    ),
                    options = OPTIONS,
                    format = format,
                )
                val verdict = if (result.error == null) parseVerdict(result.text) else Verdict(null, null, "error")
                val (rulePlacement, _) = rulePlacement(case.text)
                val confident = verdict.choice != null && (verdict.confidence ?: 0.0) >= DECISION_MODEL_MIN_CONFIDENCE
                val effective = if (confident) verdict.choice else rulePlacement
                val ok = verdict.choice == case.expected
                if (ok) correct++
                writer.write(
                    baseRecord(model, mode, case)
                        .put("rawPreview", result.text.previewForLab())
                        .put("choice", verdict.choice ?: JSONObject.NULL)
                        .put("confidence", verdict.confidence ?: JSONObject.NULL)
                        .put("valid", verdict.choice != null)
                        .put("invalidReason", verdict.invalidReason ?: JSONObject.NULL)
                        .put("correct", ok)
                        .put("confident", confident)
                        .put("rulePlacement", rulePlacement)
                        .put("effectivePlacement", effective)
                        .put("effectiveCorrect", effective == case.expected)
                        .put("metrics", result.metricsJson()),
                )
            }
        }
        LabLog.info("suite D model=$model mode=$mode cases=${cases.size} correct=$correct")
    }

    private fun baseRecord(model: String, mode: String, case: RoutingCase): JSONObject =
        JSONObject()
            .put("suite", SUITE)
            .put("model", model)
            .put("mode", mode)
            .put("caseId", case.id)
            .put("input", case.text)
            .put("gold", case.expected)

    data class Verdict(val choice: String?, val confidence: Double?, val invalidReason: String?)

    /**
     * The same acceptance contract as `parseDecisionResponse`: a declared choice and a confidence in
     * 0..1, otherwise the verdict is unavailable and the rule decision stands.
     */
    private fun parseVerdict(raw: String): Verdict {
        val body = raw.trim().removeSurrounding("```json", "```").removeSurrounding("```", "```").trim()
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return Verdict(null, null, "not_json")
        val json = runCatching { JSONObject(body.substring(start, end + 1)) }.getOrNull()
            ?: return Verdict(null, null, "not_json")
        val choice = json.optString("choice").trim().lowercase()
        if (choice !in DECISION_MODEL_PLACEMENT_CRITERIA.keys) return Verdict(null, null, "choice_outside_criteria")
        val confidence = json.optDouble("confidence", Double.NaN)
        if (confidence.isNaN() || confidence < 0.0 || confidence > 1.0) return Verdict(null, null, "confidence_invalid")
        return Verdict(choice, confidence, null)
    }

    private companion object {
        const val SUITE = "routing"
        const val MAX_TEXT_CHARS = 2_000
        const val REQUESTED_OUTPUT_TOKENS = 1_024
        val OPTIONS = OllamaClient.Options(numPredict = 128)

        val SYSTEM_PROMPT: String =
            DECISION_MODEL_PLACEMENT_INSTRUCTIONS + "\n\nChoices:\n" +
                DECISION_MODEL_PLACEMENT_CRITERIA.entries.joinToString("\n") { (key, value) -> "- $key: $value" } +
                "\n\nThe user message is a JSON object whose \"request\" field is the chat request. " +
                "Reply with one JSON object only: {\"choice\": \"local\" or \"remote\", " +
                "\"confidence\": a number from 0 to 1}."

        val RESPONSE_SCHEMA: JSONObject = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("choice", JSONObject().put("type", "string").put("enum", JSONArray(DECISION_MODEL_PLACEMENT_CRITERIA.keys.toList())))
                    .put("confidence", JSONObject().put("type", "number").put("minimum", 0).put("maximum", 1)),
            )
            .put("required", JSONArray(listOf("choice", "confidence")))
    }
}
