package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.tool.ToolSpec
import kotlin.math.ln

/**
 * Deterministic lexical tool retriever for the `*-topk` modes. Each spec is indexed by its name (split
 * on `_`), title, description and planning hint. Chinese text contributes character bigrams (weight 1)
 * and unigrams (weight 0.3); ASCII contributes lowercase words. Scoring is BM25 over the registry, ties
 * break by registry order. No embeddings, no model: this is the cheapest retriever a phone could run.
 */
class LabToolRetriever(private val specs: List<ToolSpec>) {
    private data class Doc(val spec: ToolSpec, val terms: Map<String, Double>, val length: Double)

    private val docs: List<Doc> = specs.map { spec ->
        val text = listOf(spec.name.replace('_', ' '), spec.title, spec.description, spec.planningPromptHint.orEmpty())
            .joinToString(" ")
        val terms = weightedTerms(text)
        Doc(spec, terms, terms.values.sum())
    }
    private val averageLength = docs.map(Doc::length).average().takeIf { it > 0 } ?: 1.0
    private val documentFrequency: Map<String, Int> =
        docs.flatMap { it.terms.keys }.groupingBy { it }.eachCount()

    fun topK(query: String, k: Int = 8): List<ToolSpec> =
        scores(query).take(k).map { it.first }

    fun scores(query: String): List<Pair<ToolSpec, Double>> {
        val queryTerms = weightedTerms(query)
        return docs.mapIndexed { index, doc -> Triple(doc.spec, score(queryTerms, doc), index) }
            .sortedWith(compareByDescending<Triple<ToolSpec, Double, Int>> { it.second }.thenBy { it.third })
            .map { it.first to it.second }
    }

    private fun score(queryTerms: Map<String, Double>, doc: Doc): Double {
        val n = docs.size.toDouble()
        return queryTerms.entries.sumOf { (term, queryWeight) ->
            val tf = doc.terms[term] ?: return@sumOf 0.0
            val df = documentFrequency[term]?.toDouble() ?: 0.0
            val idf = ln(1.0 + (n - df + 0.5) / (df + 0.5))
            val norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * doc.length / averageLength))
            queryWeight * idf * norm
        }
    }

    companion object {
        private const val K1 = 1.2
        private const val B = 0.75
        private val asciiWord = Regex("[a-z0-9]+")

        fun weightedTerms(text: String): Map<String, Double> {
            val terms = linkedMapOf<String, Double>()
            fun add(term: String, weight: Double) {
                terms[term] = (terms[term] ?: 0.0) + weight
            }
            val lower = text.lowercase()
            asciiWord.findAll(lower).forEach { add(it.value, 1.0) }
            lower.split(Regex("[^\\p{IsHan}]+")).filter(String::isNotEmpty).forEach { run ->
                run.forEach { add(it.toString(), 0.3) }
                run.windowed(2).forEach { add(it, 1.0) }
            }
            return terms
        }
    }
}
