package com.bytedance.zgx.solin.lab.memory

import com.bytedance.zgx.solin.memory.LocalMemoryModelFallback
import com.bytedance.zgx.solin.memory.MemoryTaskPath
import com.bytedance.zgx.solin.memory.modelOutputBody
import com.bytedance.zgx.solin.orchestration.DefaultContextCompactor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Compaction-summary bench (opt-in, see [MemoryLab]). Per case it compacts the history twice with
 * the production [DefaultContextCompactor]: heuristic only, and with the model summary enabled. It
 * writes `compaction__<model>.jsonl` and `compaction__<model>.metrics.json`.
 *
 * Metrics: must-keep retention of the heuristic result, of the final result with the flag on (model
 * summary or its fallback) and of the raw model summary alone; how often the model summary was used
 * and why not; hallucination rate (non-empty summaries with a number missing from their sources,
 * the first check after a non-empty answer); compression; latency of the model calls.
 */
class MemoryCompactionBenchTest {
    @Test
    fun compactionSummary() {
        requireMemoryLabOptIn()
        val cases = MemoryLabDatasets.compactionCases().limitedForLab()
        MemoryLab.models.forEach { model -> runModel(model, cases) }
    }

    private fun runModel(modelName: String, cases: List<CompactionCase>) = runBlocking {
        val model = OllamaMemoryModel(modelName)
        model.warmUp()
        val heuristic = DefaultContextCompactor(CompactionBench.config)
        val withModel = DefaultContextCompactor(
            CompactionBench.config.copy(modelSummaryEnabled = true, modelSummaryTimeoutMillis = MemoryLab.CALL_TIMEOUT_MILLIS),
            model,
        )
        var mustKeep = 0
        var keptHeuristic = 0
        var keptFinal = 0
        var keptRaw = 0
        var keptWhenModelUsed = 0
        var mustKeepWhenModelUsed = 0
        var modelUsed = 0
        var answered = 0
        var ungrounded = 0
        val fallbacks = sortedMapOf<String, Int>()
        val latencies = mutableListOf<Long>()
        val ratioHeuristic = mutableListOf<Double>()
        val ratioFinal = mutableListOf<Double>()
        val records = cases.map { case ->
            val budget = CompactionBench.budgetFor(case.messages)
            val base = heuristic.compact(case.messages, budget, CompactionBench.estimator)
            val callsBefore = model.calls.size
            val result = withModel.compact(case.messages, budget, CompactionBench.estimator)
            val call = model.calls.drop(callsBefore).singleOrNull()
            val rawSummary = call?.text?.let(::modelOutputBody).orEmpty()
            val retainedHeuristic = CompactionBench.retained(case.mustKeep, base.messages.joinToString("\n") { it.text })
            val retainedFinal = CompactionBench.retained(case.mustKeep, result.messages.joinToString("\n") { it.text })
            val retainedRaw = CompactionBench.retained(case.mustKeep, rawSummary)

            mustKeep += case.mustKeep.size
            keptHeuristic += retainedHeuristic.size
            keptFinal += retainedFinal.size
            keptRaw += retainedRaw.size
            if (result.summaryPath == MemoryTaskPath.Model) {
                modelUsed++
                mustKeepWhenModelUsed += case.mustKeep.size
                keptWhenModelUsed += retainedFinal.size
            }
            result.summaryFallback?.let { reason -> fallbacks.merge(reason.name, 1, Int::plus) }
            if (rawSummary.isNotEmpty()) answered++
            if (result.summaryFallback == LocalMemoryModelFallback.Ungrounded) ungrounded++
            call?.let { latencies += it.wallMillis }
            ratioHeuristic += base.tokensAfter.toDouble() / base.tokensBefore
            ratioFinal += result.tokensAfter.toDouble() / result.tokensBefore

            JSONObject()
                .put("id", case.id)
                .put("budget", budget)
                .put("mustKeep", JSONArray(case.mustKeep))
                .put("heuristicSummary", base.messages.first().text)
                .put("rawSummary", call?.text ?: JSONObject.NULL)
                .put("finalSummary", result.messages.first().text)
                .put("path", result.summaryPath.name)
                .put("fallback", result.summaryFallback?.name ?: JSONObject.NULL)
                .put("retainedHeuristic", JSONArray(retainedHeuristic))
                .put("retainedFinal", JSONArray(retainedFinal))
                .put("retainedRaw", JSONArray(retainedRaw))
                .put("tokensBefore", base.tokensBefore)
                .put("tokensAfterHeuristic", base.tokensAfter)
                .put("tokensAfterFinal", result.tokensAfter)
                .put("wallMs", call?.wallMillis ?: JSONObject.NULL)
                .put("promptTokens", call?.promptTokens ?: JSONObject.NULL)
                .put("evalTokens", call?.evalTokens ?: JSONObject.NULL)
                .put("error", call?.error ?: JSONObject.NULL)
        }
        val metrics = JSONObject()
            .put("suite", "compaction")
            .put("model", model.describe())
            .put("cases", cases.size)
            .put("modelCalls", latencies.size)
            .put("mustKeep", mustKeep)
            .put("retentionHeuristic", ratio(keptHeuristic, mustKeep))
            .put("retentionFinal", ratio(keptFinal, mustKeep))
            .put("retentionRawSummary", ratio(keptRaw, mustKeep))
            .put("retentionWhenModelUsed", ratio(keptWhenModelUsed, mustKeepWhenModelUsed))
            .put("modelSummaryUsedRate", ratio(modelUsed, cases.size))
            .put("fallbacks", JSONObject(fallbacks.toMap()))
            .put("hallucinationRate", ratio(ungrounded, answered))
            .put("emptyOutputRate", ratio(latencies.size - answered, latencies.size))
            .put("tokensAfterOverBeforeHeuristic", Math.round(ratioHeuristic.average() * 1000) / 1000.0)
            .put("tokensAfterOverBeforeFinal", Math.round(ratioFinal.average() * 1000) / 1000.0)
            .put("latencyMs", latencyJson(latencies))
        val base = "compaction__${modelName.fileSafe()}"
        writeJsonl(File(MemoryLab.resultsDir, "$base.jsonl"), records)
        File(MemoryLab.resultsDir, "$base.metrics.json").writeText(metrics.toString(2) + "\n", Charsets.UTF_8)
        println("memory-lab compaction $modelName: ${metrics.toString(2)}")
    }
}
