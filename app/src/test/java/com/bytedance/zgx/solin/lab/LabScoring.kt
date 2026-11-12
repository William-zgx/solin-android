package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.action.MobileActionPlanner
import org.json.JSONArray
import org.json.JSONObject

/** A single planned step: `tool == null` means the model chose to stop / answer in text. */
data class LabAnswer(
    val tool: String?,
    val args: Map<String, String> = emptyMap(),
    val invalid: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put(
            "kind",
            if (invalid) {
                "invalid"
            } else if (tool == null) {
                "stop"
            } else {
                "tool"
            }
        )
        .put("tool", tool ?: JSONObject.NULL)
        .put("args", args.toJson())

    companion object {
        val STOP = LabAnswer(tool = null)
        val INVALID = LabAnswer(tool = null, invalid = true)
    }
}

object LabScoring {
    private val whitespace = Regex("\\s+")

    fun normalized(value: String?): String = value.orEmpty().lowercase().replace(whitespace, "")

    /**
     * Suite A gold matching (see lab/README.md "Labeling rules"). [nodeLabels] maps a node id of the
     * current screen to the visible strings that name it.
     */
    fun observationAnswerMatches(
        answer: LabAnswer,
        accepts: List<JSONObject>,
        nodeLabels: Map<String, List<String>>
    ): Boolean = !answer.invalid && accepts.any { accept -> observationAcceptMatches(answer, accept, nodeLabels) }

    private fun observationAcceptMatches(
        answer: LabAnswer,
        accept: JSONObject,
        nodeLabels: Map<String, List<String>>
    ): Boolean {
        if (accept.optBoolean("stop")) return answer.tool == null
        if (answer.tool == null || answer.tool != accept.optString("tool")) return false
        val targetNodes = accept.optJSONArray("targetNodes").stringList()
        val literalTargets = accept.optJSONArray("targets").stringList()
        if (targetNodes.isNotEmpty() || literalTargets.isNotEmpty()) {
            val target = normalized(answer.args["target"])
            val targetOk = when {
                target.isEmpty() -> accept.optBoolean("targetOptional")
                else -> {
                    val names = targetNodes.flatMap { nodeId -> listOf(nodeId) + nodeLabels[nodeId].orEmpty() } +
                        literalTargets
                    names.map(::normalized).filter(String::isNotEmpty).any { name -> name == target }
                }
            }
            if (!targetOk) return false
        }
        if (accept.has("text") && normalized(answer.args["text"]) != normalized(accept.optString("text"))) {
            return false
        }
        accept.optJSONObject("args")?.let { expected ->
            val ok = expected.keys().asSequence().all { key ->
                normalized(answer.args[key]) == normalized(expected.optString(key))
            }
            if (!ok) return false
        }
        return true
    }

    /**
     * Suite B/C argument value matching: plain values compare after trim+lowercase (numbers
     * numerically), `~x` means contains, `a|b` means either alternative, phone numbers compare digits.
     */
    fun argValueMatches(key: String, expected: String, actual: String?): Boolean {
        if (actual == null) return false
        return expected.split('|').any { alternative -> argAlternativeMatches(key, alternative, actual) }
    }

    private fun argAlternativeMatches(key: String, expected: String, actual: String): Boolean {
        val actualValue = actual.trim().lowercase()
        if (expected.startsWith("~")) {
            return actualValue.contains(expected.removePrefix("~").trim().lowercase())
        }
        if (key == "phoneNumber" || key == "phone") {
            val digits = expected.filter(Char::isDigit)
            return digits.isNotEmpty() && digits == actual.filter(Char::isDigit)
        }
        val expectedValue = expected.trim().lowercase()
        val expectedNumber = expectedValue.toBigDecimalOrNull()
        val actualNumber = actualValue.toBigDecimalOrNull()
        if (expectedNumber != null && actualNumber != null) return expectedNumber.compareTo(actualNumber) == 0
        return expectedValue == actualValue
    }

    /**
     * Scores one initial-planning answer against gold tools/args (Suites B and C). An invalid answer
     * (a call that failed to parse or was rejected) executes nothing, so it is never a correct tool,
     * but it is also not a false positive; `callAttempted` records the intent.
     */
    fun scoreInitial(case: InitialCase, answer: LabAnswer, schemaValid: Boolean? = null, rawKind: String? = null): JSONObject {
        val expectsTool = case.goldTools.isNotEmpty()
        val executedTool = answer.tool?.takeUnless { answer.invalid }
        val toolCorrect = if (expectsTool) {
            executedTool != null && executedTool in case.goldTools
        } else {
            rawKind !in setOf("error", "missing_model") && (executedTool == null || schemaValid == false || answer.invalid)
        }
        val validityAllowsScore = schemaValid != false
        val perKey = JSONObject()
        val constraintResults = JSONObject()
        var allArgs = toolCorrect
        if (expectsTool && toolCorrect) {
            case.goldArgs.forEach { (key, expected) ->
                val ok = argValueMatches(key, expected, answer.args[key])
                perKey.put(key, ok)
                allArgs = allArgs && ok
            }
            case.forbiddenArgs.forEach { key -> constraintResults.put(key, !answer.args.containsKey(key)) }
            case.forbiddenNonEmptyArgs.forEach { key ->
                val allowed = answer.args[key].isNullOrBlank()
                constraintResults.put(key, constraintResults.optBoolean(key, true) && allowed)
            }
            case.forbiddenArgValues.forEach { (key, forbidden) ->
                val actual = answer.args[key]
                val allowed = actual == null || !argValueMatches(key, forbidden, actual)
                constraintResults.put(key, constraintResults.optBoolean(key, true) && allowed)
            }
            allArgs = allArgs && constraintResults.keys().asSequence().all { constraintResults.getBoolean(it) }
        }
        return JSONObject()
            .put("expectsTool", expectsTool)
            .put("toolCorrect", toolCorrect)
            .put("argsExact", expectsTool && toolCorrect && allArgs && validityAllowsScore)
            .put("argKeys", perKey)
            .put("constraintResults", constraintResults)
            .put("falsePositive", !expectsTool && executedTool != null && validityAllowsScore)
            .put("callAttempted", answer.tool != null || answer.invalid)
            .put("missedTool", expectsTool && answer.tool == null)
            .put("correct", if (expectsTool) toolCorrect && allArgs && validityAllowsScore else toolCorrect)
    }

    /**
     * Classifies one raw `call:` model output exactly as the app's parser sees it: `call` (a draft the
     * parser accepts), `invalid` (a call was attempted but rejected), `text` (plain text, i.e. the model
     * chose not to call a tool) or `blank`.
     *
     * With [noToolChoice] set, the mode offered production's `answer` choice. Production reads a
     * rejected `call:answer` as a deliberate no-tool decision (`InitialToolPlanner.kt:144` and
     * `choseNoTool()`), not as a hallucinated tool, so that is what this returns (`noToolChoice`).
     */
    fun answerFromCallOutput(
        raw: String,
        parser: MobileActionPlanner,
        noToolChoice: Boolean = false
    ): Pair<LabAnswer, String> {
        parser.parseModelOutput(raw)?.let { draft -> return LabAnswer(draft.functionName, draft.parameters) to "call" }
        if (noToolChoice && Regex("call:").findAll(raw).count() == 1 && normalizedChoice(raw) == LOCAL_MODEL_NO_TOOL_CHOICE) {
            return LabAnswer.STOP to "no_tool_choice"
        }
        return when {
            raw.isBlank() -> LabAnswer.STOP to "blank"
            raw.contains("call:") -> LabAnswer(tool = rejectedToolName(raw), invalid = true) to "invalid"
            else -> LabAnswer.STOP to "text"
        }
    }

    /** The `answer` choice in a raw output, or null when the output does not name it. */
    fun normalizedChoice(raw: String): String? = callName.find(raw)?.groupValues?.get(1)

    private val callName = Regex("""call:\s*([A-Za-z0-9_]+)""")

    private fun rejectedToolName(raw: String): String? = callName.find(raw)?.groupValues?.get(1)
}

fun List<JSONObject>.toJsonArray(): JSONArray = JSONArray().also { array -> forEach(array::put) }
