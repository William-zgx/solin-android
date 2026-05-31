package com.bytedance.zgx.pocketmind.memory

import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.MessageRole
import com.bytedance.zgx.pocketmind.data.MemoryRecordDao
import com.bytedance.zgx.pocketmind.data.MemoryRecordEntity
import kotlin.math.sqrt

data class MemoryHit(
    val id: String,
    val text: String,
    val score: Float,
)

enum class MemoryRecordType {
    Conversation,
    Preference,
    TaskState,
}

interface EmbeddingRuntime {
    fun embed(text: String): FloatArray
}

interface MemoryIndex {
    var enabled: Boolean
    fun rebuild(messages: List<ChatMessage>)
    fun index(id: String, text: String)
    fun search(query: String, topK: Int = 3): List<MemoryHit>
    fun buildContext(hits: List<MemoryHit>): String
}

interface LongTermMemoryControls {
    fun savedRecords(): List<PersistedMemoryRecord>
    fun indexPreference(id: String, text: String)
    fun indexTaskState(id: String, text: String)
    fun forget(id: String): Boolean
    fun clear()
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
    private val recordStore: MemoryRecordStore = NoOpMemoryRecordStore,
) : MemoryIndex, LongTermMemoryControls {
    private val entries = linkedMapOf<String, MemoryEntry>()
    override var enabled: Boolean = true

    override fun rebuild(messages: List<ChatMessage>) {
        entries.clear()
        recordStore.records().forEach { record ->
            indexRecord(
                id = record.id,
                text = record.text,
                type = record.type,
                persist = false,
            )
        }
        messages.forEach { message ->
            val rolePrefix = when (message.role) {
                MessageRole.User -> "用户"
                MessageRole.Assistant -> "助手"
            }
            index(
                id = message.id.toString(),
                text = "$rolePrefix：${message.text}",
            )
            if (message.role == MessageRole.User) {
                extractPreference(message.text)?.let { preference ->
                    indexRecord(
                        id = "preference-${message.id}",
                        text = "用户偏好：$preference",
                        type = MemoryRecordType.Preference,
                        persist = false,
                    )
                }
            }
        }
    }

    override fun index(id: String, text: String) {
        indexRecord(id, text, MemoryRecordType.Conversation, persist = false)
    }

    override fun savedRecords(): List<PersistedMemoryRecord> =
        recordStore.records()

    override fun indexPreference(id: String, text: String) {
        indexRecord(id, "用户偏好：$text", MemoryRecordType.Preference, persist = true)
    }

    override fun indexTaskState(id: String, text: String) {
        indexRecord(id, "任务状态：$text", MemoryRecordType.TaskState, persist = true)
    }

    override fun forget(id: String): Boolean {
        val removedInMemory = entries.remove(id) != null
        val removedPersisted = recordStore.delete(id)
        return removedInMemory || removedPersisted
    }

    override fun clear() {
        entries.clear()
        recordStore.clear()
    }

    private fun indexRecord(
        id: String,
        text: String,
        type: MemoryRecordType,
        persist: Boolean,
    ) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        val tokens = tokenize(normalized).toSet()
        if (tokens.isEmpty()) return
        entries[id] = MemoryEntry(
            id = id,
            text = normalized,
            type = type,
            tokens = tokens,
            embedding = embeddingRuntime.embed(normalized),
        )
        if (persist && type != MemoryRecordType.Conversation) {
            recordStore.upsert(
                PersistedMemoryRecord(
                    id = id,
                    type = type,
                    text = normalized,
                ),
            )
        }
    }

    private fun extractPreference(text: String): String? {
        val trimmed = text.trim()
        val chinesePrefix = Regex("""^(请)?记住[:：]?\s*(.+)$""")
        chinesePrefix.find(trimmed)?.groupValues?.getOrNull(2)?.trim()?.let { preference ->
            if (preference.isNotBlank()) return preference
        }

        val englishPrefix = Regex(
            pattern = """^(please\s+)?remember\s+(that\s+)?(.+)$""",
            option = RegexOption.IGNORE_CASE,
        )
        return englishPrefix.find(trimmed)
            ?.groupValues
            ?.getOrNull(3)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    override fun search(query: String, topK: Int): List<MemoryHit> {
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

    override fun buildContext(hits: List<MemoryHit>): String =
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

data class PersistedMemoryRecord(
    val id: String,
    val type: MemoryRecordType,
    val text: String,
)

interface MemoryRecordStore {
    fun records(): List<PersistedMemoryRecord>
    fun upsert(record: PersistedMemoryRecord)
    fun delete(id: String): Boolean
    fun clear()
}

object NoOpMemoryRecordStore : MemoryRecordStore {
    override fun records(): List<PersistedMemoryRecord> = emptyList()
    override fun upsert(record: PersistedMemoryRecord) = Unit
    override fun delete(id: String): Boolean = false
    override fun clear() = Unit
}

class RoomMemoryRecordStore(
    private val dao: MemoryRecordDao,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : MemoryRecordStore {
    override fun records(): List<PersistedMemoryRecord> =
        dao.records().mapNotNull { entity ->
            val type = runCatching { MemoryRecordType.valueOf(entity.type) }.getOrNull()
                ?: return@mapNotNull null
            PersistedMemoryRecord(
                id = entity.id,
                type = type,
                text = entity.text,
            )
        }

    override fun upsert(record: PersistedMemoryRecord) {
        val now = clockMillis()
        val existing = dao.record(record.id)
        dao.upsert(
            MemoryRecordEntity(
                id = record.id,
                type = record.type.name,
                text = record.text,
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
            ),
        )
    }

    override fun delete(id: String): Boolean =
        dao.delete(id) > 0

    override fun clear() {
        dao.deleteAll()
    }
}

private data class MemoryEntry(
    val id: String,
    val text: String,
    val type: MemoryRecordType,
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
