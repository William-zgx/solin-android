package com.bytedance.zgx.solin.lab.memory

import com.bytedance.zgx.solin.memory.LocalModelMemoryExtractionConfig
import com.bytedance.zgx.solin.memory.LocalModelMemoryFactExtractor
import com.bytedance.zgx.solin.memory.LocalMemoryModelFallback
import com.bytedance.zgx.solin.memory.MemoryFactSuggestionDetector
import com.bytedance.zgx.solin.memory.isGroundedIn
import com.bytedance.zgx.solin.memory.modelOutputBody
import com.bytedance.zgx.solin.memory.parseMemoryFactModelOutput
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Fact-suggestion bench (opt-in, see [MemoryLab]). Per model it runs the production
 * [LocalModelMemoryFactExtractor] over `fact_extraction.jsonl` and writes `facts__<model>.jsonl`
 * (per case) and `facts__<model>.metrics.json`. With `SOLIN_LAB_MEMORY_REPLAY` it replays the saved
 * answers instead of calling Ollama.
 *
 * Metrics: precision/recall of the regex detector alone, of the raw model facts (parsed, before any
 * guard), of the guarded model facts and of the merged suggestions the user would see; hallucination
 * rate (raw facts not grounded in the message); parse-failure rate (model answers that broke the JSON
 * contract or were empty); negative false-positive rate (negative messages with any suggestion);
 * kind accuracy; latency of the model calls.
 */
class MemoryFactExtractionBenchTest {
    @Test
    fun factExtraction() {
        requireMemoryLabOptIn()
        val cases = MemoryLabDatasets.factCases().limitedForLab()
        MemoryLab.models.forEach { model -> runModel(model, cases) }
    }

    private fun runModel(modelName: String, cases: List<FactCase>) = runBlocking {
        val model = benchModelFor("facts", modelName, textField = "rawOutput")
        model.warmUp()
        val extractor = LocalModelMemoryFactExtractor(
            model,
            LocalModelMemoryExtractionConfig(enabled = true, timeoutMillis = MemoryLab.CALL_TIMEOUT_MILLIS),
        )
        val regex = PrecisionRecall()
        val raw = PrecisionRecall()
        val guarded = PrecisionRecall()
        val merged = PrecisionRecall()
        val mergedByGroup = mapOf("regex" to PrecisionRecall(), "model" to PrecisionRecall())
        val fallbacks = sortedMapOf<String, Int>()
        val latencies = mutableListOf<Long>()
        val falsePositiveCases = mutableListOf<String>()
        var rawFacts = 0
        var ungroundedRaw = 0
        var parseFailures = 0
        var negatives = 0
        var regexNegativeHits = 0
        var kindMatched = 0
        var kindCorrect = 0
        val records = cases.map { case ->
            val callsBefore = model.calls.size
            model.beginCase(case.id)
            val extraction = extractor.extract(case.message)
            val call = model.calls.drop(callsBefore).singleOrNull()
            val golds = case.gold.map { it.fact }
            val regexFacts = MemoryFactSuggestionDetector.detect(case.message).map { it.fact }
            val body = call?.text?.let(::modelOutputBody)
            val rawItems = body?.let(::parseMemoryFactModelOutput).orEmpty()
            val rawGrounded = rawItems.map { isGroundedIn(case.message.trim(), it.fact) }

            regex.add(regexFacts, golds)
            raw.add(rawItems.map { it.fact }, golds)
            guarded.add(extraction.modelSuggestions.map { it.fact }, golds)
            merged.add(extraction.suggestions.map { it.fact }, golds)
            case.tags.firstOrNull { it in mergedByGroup }?.let { group ->
                mergedByGroup.getValue(group).add(extraction.suggestions.map { it.fact }, golds)
            }
            rawFacts += rawItems.size
            ungroundedRaw += rawGrounded.count { !it }
            extraction.fallback?.let { reason -> fallbacks.merge(reason.name, 1, Int::plus) }
            if (extraction.fallback == LocalMemoryModelFallback.Unparseable || extraction.fallback == LocalMemoryModelFallback.EmptyOutput) {
                parseFailures++
            }
            call?.let { latencies += it.wallMillis }
            if (case.gold.isEmpty()) {
                negatives++
                if (regexFacts.isNotEmpty()) regexNegativeHits++
                if (extraction.suggestions.isNotEmpty()) falsePositiveCases += case.id
            }
            extraction.modelSuggestions.forEach { suggestion ->
                val gold = case.gold.firstOrNull { factMatches(suggestion.fact, it.fact) } ?: return@forEach
                kindMatched++
                if (gold.kind == suggestion.kind.name) kindCorrect++
            }

            JSONObject()
                .put("id", case.id)
                .put("tags", JSONArray(case.tags))
                .put("message", case.message)
                .put("gold", JSONArray(case.gold.map { JSONObject().put("kind", it.kind).put("fact", it.fact) }))
                .put("regex", JSONArray(regexFacts))
                .put("rawOutput", call?.text ?: JSONObject.NULL)
                .put(
                    "rawFacts",
                    JSONArray(
                        rawItems.mapIndexed { i, item ->
                            JSONObject().put("kind", item.kindName).put("fact", item.fact).put("grounded", rawGrounded[i])
                        },
                    ),
                )
                .put("modelFacts", JSONArray(extraction.modelSuggestions.map { JSONObject().put("kind", it.kind.name).put("fact", it.fact) }))
                .put("suggestions", JSONArray(extraction.suggestions.map { it.fact }))
                .put("path", extraction.path.name)
                .put("fallback", extraction.fallback?.name ?: JSONObject.NULL)
                .put("dropped", extraction.droppedFacts)
                .put("wallMs", call?.wallMillis ?: JSONObject.NULL)
                .put("promptTokens", call?.promptTokens ?: JSONObject.NULL)
                .put("evalTokens", call?.evalTokens ?: JSONObject.NULL)
                .put("error", call?.error ?: JSONObject.NULL)
        }
        val modelCalls = latencies.size
        val metrics = JSONObject()
            .put("suite", "facts")
            .put("model", model.describe())
            .put("cases", cases.size)
            .put("modelCalls", modelCalls)
            .put("negatives", negatives)
            .put("regexOnly", regex.toJson())
            .put("rawModel", raw.toJson())
            .put("guardedModel", guarded.toJson())
            .put("merged", merged.toJson())
            .put("mergedRegexGroup", mergedByGroup.getValue("regex").toJson())
            .put("mergedModelGroup", mergedByGroup.getValue("model").toJson())
            .put("rawFacts", rawFacts)
            .put("hallucinationRate", ratio(ungroundedRaw, rawFacts))
            .put("parseFailureRate", ratio(parseFailures, modelCalls))
            .put("fallbacks", JSONObject(fallbacks.toMap()))
            .put("regexNegativeFalsePositiveRate", ratio(regexNegativeHits, negatives))
            .put("mergedNegativeFalsePositiveRate", ratio(falsePositiveCases.size, negatives))
            .put("falsePositiveCases", JSONArray(falsePositiveCases))
            .put("kindAccuracy", ratio(kindCorrect, kindMatched))
            .put("latencyMs", latencyJson(latencies))
        val base = "facts__${modelName.fileSafe()}"
        writeJsonl(outputFile("$base.jsonl"), records)
        outputFile("$base.metrics.json").writeText(metrics.toString(2) + "\n", Charsets.UTF_8)
        println("memory-lab facts $modelName: ${metrics.toString(2)}")
    }
}
