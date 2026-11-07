package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.orchestration.DECISION_MODEL_MIN_CONFIDENCE
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test

/**
 * Suite E: the local model as an action-intent gate. One short call per Suite B request asks only
 * whether the request needs a phone action, in the JSON-prompt style of Suite D. The gate opens on
 * `action = true` with confidence >= [DECISION_MODEL_MIN_CONFIDENCE]; anything else (false, low
 * confidence, invalid output) keeps it closed, so a broken gate fails closed to no tool.
 *
 * Lab-only experiment: the app has no such gate today.
 */
class ActionGateSuiteTest {
    @Test
    fun actionGateSuite() {
        requireLabOptIn("E")
        Assume.assumeTrue("no Suite E mode selected by SOLIN_LAB_MODES", LabEnv.modeSelected(MODE))
        val client = OllamaClient()
        val models = availableLabModels(client)
        Assume.assumeTrue("no lab models installed", models.isNotEmpty())
        LabManifest.recordModels(client, models)
        val cases = InitialPlanningCases.benchCases()
        LabManifest.recordSuite(
            SUITE,
            JSONObject()
                .put("cases", cases.size)
                .put("minConfidence", DECISION_MODEL_MIN_CONFIDENCE)
                .put("system", SYSTEM_PROMPT),
        )
        models.forEach { model ->
            client.warmUp(model)
            runModel(client, model, cases)
        }
    }

    private fun runModel(client: OllamaClient, model: String, cases: List<InitialCase>) {
        var correct = 0
        var gatedCorrect = 0
        JsonlWriter(SUITE, model, MODE).use { writer ->
            cases.forEach { case ->
                val result = client.chat(
                    model = model,
                    messages = listOf(
                        OllamaClient.Message("system", SYSTEM_PROMPT),
                        OllamaClient.Message("user", JSONObject().put("request", case.input).toString()),
                    ),
                    options = OPTIONS,
                )
                val verdict = if (result.error == null) parseVerdict(result.text) else Verdict(null, null, "error")
                val expectsAction = case.goldTools.isNotEmpty()
                val gateOpen = verdict.action == true && (verdict.confidence ?: 0.0) >= DECISION_MODEL_MIN_CONFIDENCE
                val ok = verdict.action == expectsAction
                if (ok) correct++
                if (gateOpen == expectsAction) gatedCorrect++
                writer.write(
                    JSONObject()
                        .put("suite", SUITE)
                        .put("model", model)
                        .put("mode", MODE)
                        .put("caseId", case.id)
                        .put("source", case.source)
                        .put("category", case.category ?: JSONObject.NULL)
                        .put("input", case.input)
                        .put("gold", case.goldJson())
                        .put("expectsAction", expectsAction)
                        .put("rawPreview", result.text.previewForLab())
                        .put("action", verdict.action ?: JSONObject.NULL)
                        .put("confidence", verdict.confidence ?: JSONObject.NULL)
                        .put("valid", verdict.action != null)
                        .put("invalidReason", verdict.invalidReason ?: JSONObject.NULL)
                        .put("correct", ok)
                        .put("gateOpen", gateOpen)
                        .put("gatedCorrect", gateOpen == expectsAction)
                        .put("metrics", result.metricsJson()),
                )
            }
        }
        LabLog.info("suite E model=$model mode=$MODE cases=${cases.size} correct=$correct gatedCorrect=$gatedCorrect")
    }

    data class Verdict(val action: Boolean?, val confidence: Double?, val invalidReason: String?)

    /** Same acceptance shape as Suite D: one JSON object, a boolean verdict and a confidence in 0..1. */
    private fun parseVerdict(raw: String): Verdict {
        val body = raw.trim().removeSurrounding("```json", "```").removeSurrounding("```", "```").trim()
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return Verdict(null, null, "not_json")
        val json = runCatching { JSONObject(body.substring(start, end + 1)) }.getOrNull()
            ?: return Verdict(null, null, "not_json")
        val action = when (val value = json.opt("action")) {
            is Boolean -> value
            "true" -> true
            "false" -> false
            else -> return Verdict(null, null, "action_not_boolean")
        }
        val confidence = json.optDouble("confidence", Double.NaN)
        if (confidence.isNaN() || confidence < 0.0 || confidence > 1.0) return Verdict(null, null, "confidence_invalid")
        return Verdict(action, confidence, null)
    }

    private companion object {
        const val SUITE = "action-gate"
        const val MODE = "json-prompt"
        val OPTIONS = OllamaClient.Options(numPredict = 64)

        val SYSTEM_PROMPT: String =
            "You decide whether a phone assistant must perform a phone action for the user's request. " +
                "action = true: the user asks the assistant to do something on the phone now, such as opening an app, " +
                "setting an alarm, timer or reminder, drafting a message, email, contact or calendar event, " +
                "calling, searching the web or a map, or changing a setting. " +
                "action = false: chat, a question the assistant can answer in text, an explanation, a translation, " +
                "or a request that tells the assistant not to do something.\n\n" +
                "The user message is a JSON object whose \"request\" field is the request. " +
                "Reply with one JSON object only: {\"action\": true or false, \"confidence\": a number from 0 to 1}."
    }
}
