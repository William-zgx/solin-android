package com.bytedance.zgx.solin.lab.memory

import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.memory.LocalMemoryModel
import com.bytedance.zgx.solin.memory.LocalModelMemoryExtractionConfig
import com.bytedance.zgx.solin.memory.LocalModelMemoryFactExtractor
import com.bytedance.zgx.solin.memory.MemoryFactSuggestionKind
import com.bytedance.zgx.solin.memory.MemoryTaskPath
import com.bytedance.zgx.solin.memory.groundingFold
import com.bytedance.zgx.solin.memory.isGroundedIn
import com.bytedance.zgx.solin.orchestration.DefaultContextCompactor
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Always-on checks of the bench datasets against the production code, with oracle models instead of
 * Ollama: a perfect model must score 100%, so a bench miss is the model's and not the dataset's.
 */
class MemoryLabDatasetTest {
    private val factCases = MemoryLabDatasets.factCases()
    private val compactionCases = MemoryLabDatasets.compactionCases()

    @Test
    fun `fact cases are well formed and every gold fact is grounded in its message`() {
        assertTrue(factCases.size >= 100)
        assertEquals(factCases.size, factCases.map { it.id }.toSet().size)
        assertTrue("negatives are needed for the false-positive rate", factCases.count { it.gold.isEmpty() } >= 20)
        val kinds = MemoryFactSuggestionKind.entries.map { it.name }.toSet()
        factCases.forEach { case ->
            case.gold.forEach { gold ->
                assertTrue("${case.id}: unknown kind ${gold.kind}", gold.kind in kinds)
                assertTrue("${case.id}: gold fact not in the message", isGroundedIn(case.message, gold.fact))
            }
        }
    }

    @Test
    fun `an oracle model passes every gold fact through the production guards`() = runTest {
        factCases.forEach { case ->
            val oracle = LocalMemoryModel { _, _ ->
                JSONObject().put("facts", JSONArray(case.gold.map { JSONObject().put("kind", it.kind).put("fact", it.fact) })).toString()
            }
            val extraction = LocalModelMemoryFactExtractor(oracle, LocalModelMemoryExtractionConfig(enabled = true))
                .extract(case.message)
            if (case.gold.isNotEmpty()) {
                assertEquals("${case.id}: path", MemoryTaskPath.Model, extraction.path)
                assertEquals("${case.id}: guards dropped a gold fact", 0, extraction.droppedFacts)
            }
            case.gold.forEach { gold ->
                assertTrue(
                    "${case.id}: gold fact '${gold.fact}' missing from ${extraction.suggestions.map { it.fact }}",
                    extraction.suggestions.any { factMatches(it.fact, gold.fact) },
                )
            }
            extraction.modelSuggestions.forEach { suggestion ->
                val gold = case.gold.first { factMatches(suggestion.fact, it.fact) }
                assertEquals("${case.id}: kind", gold.kind, suggestion.kind.name)
            }
        }
    }

    @Test
    fun `compaction cases summarize and keep every must-keep string out of user turns`() = runTest {
        assertTrue(compactionCases.size >= 10)
        val compactor = DefaultContextCompactor(CompactionBench.config)
        compactionCases.forEach { case ->
            val userText = case.messages.filter { it.role == MessageRole.User }.joinToString("\n") { it.text }
            assertEquals("${case.id}: must-keep in a user turn", emptyList<String>(), CompactionBench.retained(case.mustKeep, userText))
            val sourceText = case.messages.joinToString("\n") { it.text }
            assertEquals("${case.id}: must-keep not in the case", case.mustKeep, CompactionBench.retained(case.mustKeep, sourceText))

            val result = compactor.compact(case.messages, CompactionBench.budgetFor(case.messages), CompactionBench.estimator)
            assertTrue("${case.id}: did not compact", result.tokensAfter < result.tokensBefore)
            assertTrue(
                "${case.id}: no summary, got ${result.messages.first().text.take(40)}",
                result.messages.first().text.startsWith("Previous conversation summary: "),
            )
        }
    }

    @Test
    fun `an oracle summary passes the production checks and keeps every must-keep string`() = runTest {
        compactionCases.forEach { case ->
            // A perfect summarizer echoes only what it was shown: the must-keep strings in the prompt, each
            // on a line with a source label that was sent (threat T1). Must-keep strings in the verbatim tail
            // stay in the result by themselves.
            val oracle = LocalMemoryModel { _, prompt ->
                val label = prompt.lines().drop(1).first().substringBefore(": ")
                val folded = prompt.groundingFold()
                val shown = case.mustKeep.filter { it.groundingFold() in folded }
                if (shown.isEmpty()) "$label: 更早的对话已压缩" else shown.joinToString("\n") { "$label: $it" }
            }
            val compactor = DefaultContextCompactor(CompactionBench.config.copy(modelSummaryEnabled = true), oracle)
            val result = compactor.compact(case.messages, CompactionBench.budgetFor(case.messages), CompactionBench.estimator)
            assertEquals("${case.id}: fallback ${result.summaryFallback}", MemoryTaskPath.Model, result.summaryPath)
            assertEquals(case.mustKeep, CompactionBench.retained(case.mustKeep, result.messages.joinToString("\n") { it.text }))
        }
    }
}
