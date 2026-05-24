package com.bytedance.zgx.pocketmind.memory

import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.MessageRole
import kotlin.math.sqrt

data class MemoryHit(
    val id: String,
    val text: String,
    val score: Float,
)

interface EmbeddingRuntime {
    fun embed(text: String): FloatArray
}

class HashingEmbeddingRuntime(
    private val dimensions: Int = 256,
) : EmbeddingRuntime {
    override fun embed(text: String): FloatArray {
        val vector = FloatArray(dimensions)
        tokenize(text).forEach { token ->
            val index = (token.hashCode() and Int.MAX_VALUE) % dimensions
            vector[index] += 1f
        }
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) {
            for (index in vector.indices) {
                vector[index] /= norm
            }
        }
        return vector
    }
}

class MemoryRepository(
    private val embeddingRuntime: EmbeddingRuntime = HashingEmbeddingRuntime(),
) {
    private val entries = linkedMapOf<String, MemoryEntry>()
    var enabled: Boolean = true

    fun rebuild(messages: List<ChatMessage>) {
        entries.clear()
        messages.forEach { message ->
            val rolePrefix = when (message.role) {
                MessageRole.User -> "用户"
                MessageRole.Assistant -> "助手"
            }
            index(
                id = message.id.toString(),
                text = "$rolePrefix：${message.text}",
            )
        }
    }

    fun index(id: String, text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        val tokens = tokenize(normalized).toSet()
        if (tokens.isEmpty()) return
        entries[id] = MemoryEntry(
            id = id,
            text = normalized,
            tokens = tokens,
            embedding = embeddingRuntime.embed(normalized),
        )
    }

    fun search(query: String, topK: Int = 3): List<MemoryHit> {
        if (!enabled || query.isBlank() || topK <= 0 || entries.isEmpty()) return emptyList()
        val queryTokens = tokenize(query).toSet()
        if (queryTokens.isEmpty()) return emptyList()
        val queryEmbedding = embeddingRuntime.embed(query)
        return entries.values
            .filter { entry -> entry.tokens.any { it in queryTokens } }
            .map { entry ->
                MemoryHit(
                    id = entry.id,
                    text = entry.text,
                    score = cosine(queryEmbedding, entry.embedding),
                )
            }
            .filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(topK)
    }

    fun buildContext(hits: List<MemoryHit>): String =
        hits.joinToString(separator = "\n") { "- ${it.text}" }

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        val size = minOf(left.size, right.size)
        var dot = 0f
        for (index in 0 until size) {
            dot += left[index] * right[index]
        }
        return dot
    }
}

private data class MemoryEntry(
    val id: String,
    val text: String,
    val tokens: Set<String>,
    val embedding: FloatArray,
)

private fun tokenize(text: String): List<String> {
    val normalized = text.lowercase()
    val latinTokens = normalized
        .split(Regex("""[^\p{L}\p{N}]+"""))
        .map { it.trim() }
        .filter { it.length >= 2 && it !in LATIN_STOP_WORDS }
    val cjkChars = normalized
        .filter { it.isCjk() }
        .map { it.toString() }
    val cjkBigrams = cjkChars.zipWithNext { left, right -> left + right }
    return latinTokens + cjkChars + cjkBigrams
}

private fun Char.isCjk(): Boolean =
    this in '\u4E00'..'\u9FFF' ||
        this in '\u3400'..'\u4DBF' ||
        this in '\u3040'..'\u30FF' ||
        this in '\uAC00'..'\uD7AF'

private val LATIN_STOP_WORDS = setOf(
    "a",
    "an",
    "and",
    "are",
    "for",
    "in",
    "is",
    "it",
    "my",
    "of",
    "ok",
    "on",
    "only",
    "or",
    "please",
    "remember",
    "reply",
    "the",
    "to",
    "what",
    "you",
    "your",
)
