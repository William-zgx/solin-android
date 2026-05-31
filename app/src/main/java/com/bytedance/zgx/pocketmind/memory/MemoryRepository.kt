package com.bytedance.zgx.pocketmind.memory

import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.MessageRole
import com.bytedance.zgx.pocketmind.data.MemoryRecordDao
import com.bytedance.zgx.pocketmind.data.MemoryRecordEntity
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt

enum class MemoryRecallMode {
    Lexical,
    Semantic,
}

data class MemoryHit(
    val id: String,
    val text: String,
    val score: Float,
    val recallMode: MemoryRecallMode = MemoryRecallMode.Lexical,
)

enum class MemoryRecordType {
    Conversation,
    Preference,
    TaskState,
}

interface EmbeddingRuntime {
    val supportsSemanticRecall: Boolean
        get() = false

    val semanticScoreThreshold: Float
        get() = 0.72f

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

interface SemanticMemoryRuntimeController {
    val activeMemoryModelPath: String?
    val semanticMemoryEnabled: Boolean
    fun useMemoryModel(path: String?)
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
    embeddingRuntime: EmbeddingRuntime = HashingEmbeddingRuntime(),
    private val semanticRuntimeFactory: (String) -> EmbeddingRuntime? = { null },
    private val recordStore: MemoryRecordStore = NoOpMemoryRecordStore,
) : MemoryIndex, LongTermMemoryControls, SemanticMemoryRuntimeController {
    private val entries = linkedMapOf<String, MemoryEntry>()
    private val defaultEmbeddingRuntime = embeddingRuntime
    private var activeEmbeddingRuntime: EmbeddingRuntime = embeddingRuntime
    override var activeMemoryModelPath: String? = null
        private set
    override val semanticMemoryEnabled: Boolean
        get() = activeMemoryModelPath != null && activeEmbeddingRuntime.supportsSemanticRecall
    override var enabled: Boolean = true

    override fun useMemoryModel(path: String?) {
        val normalizedPath = path?.trim()?.takeIf { it.isNotBlank() }
        val semanticRuntime = normalizedPath
            ?.let { modelPath -> runCatching { semanticRuntimeFactory(modelPath) }.getOrNull() }
            ?.takeIf { runtime -> runtime.supportsSemanticRecall }
        if (semanticRuntime == null) {
            activeMemoryModelPath = null
            activeEmbeddingRuntime = defaultEmbeddingRuntime
        } else {
            activeMemoryModelPath = checkNotNull(normalizedPath)
            activeEmbeddingRuntime = semanticRuntime
        }
        reembedEntries()
    }

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
            if (message.role == MessageRole.User && explicitUserPreferenceFrom(message.text) != null) {
                return@forEach
            }
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

    override fun index(id: String, text: String) {
        indexRecord(id, text, MemoryRecordType.Conversation, persist = false)
    }

    override fun savedRecords(): List<PersistedMemoryRecord> =
        recordStore.records()

    override fun indexPreference(id: String, text: String) {
        if (text.isBlank()) return
        forgetConflictingPreferences(id, text)
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
            embedding = activeEmbeddingRuntime.embed(normalized),
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

    override fun search(query: String, topK: Int): List<MemoryHit> {
        if (!enabled || query.isBlank() || topK <= 0 || entries.isEmpty()) return emptyList()
        val queryTokens = tokenize(query).toSet()
        if (queryTokens.isEmpty()) return emptyList()
        val requiredOverlapTokens = queryTokens.filter { it.length > 1 }.ifEmpty { queryTokens }
        val queryEmbedding = activeEmbeddingRuntime.embed(query)
        val supportsSemanticRecall = activeEmbeddingRuntime.supportsSemanticRecall
        val semanticScoreThreshold = activeEmbeddingRuntime.semanticScoreThreshold
        return entries.values
            .mapNotNull { entry ->
                val hasLexicalOverlap = entry.tokens.any { it in requiredOverlapTokens }
                if (!hasLexicalOverlap && !supportsSemanticRecall) return@mapNotNull null
                val score = cosine(queryEmbedding, entry.embedding)
                if (score <= 0f) return@mapNotNull null
                val recallMode = if (hasLexicalOverlap) {
                    MemoryRecallMode.Lexical
                } else {
                    MemoryRecallMode.Semantic
                }
                if (recallMode == MemoryRecallMode.Semantic && score < semanticScoreThreshold) {
                    return@mapNotNull null
                }
                MemoryHit(
                    id = entry.id,
                    text = entry.text,
                    score = score,
                    recallMode = recallMode,
                )
            }
            .sortedByDescending { it.score }
            .take(topK)
    }

    override fun buildContext(hits: List<MemoryHit>): String =
        hits.joinToString(separator = "\n") { "- ${it.text}" }

    private fun reembedEntries() {
        entries.keys.toList().forEach { id ->
            entries[id]?.let { entry ->
                entries[id] = entry.copy(embedding = activeEmbeddingRuntime.embed(entry.text))
            }
        }
    }

    private fun forgetConflictingPreferences(id: String, text: String) {
        val conflictKeys = explicitPreferenceConflictKeys(text)
        if (conflictKeys.isEmpty()) return
        val inMemoryConflictIds = entries.values
            .filter { entry ->
                entry.type == MemoryRecordType.Preference &&
                    entry.id != id &&
                    explicitPreferenceConflictKeys(entry.text).any { key -> key in conflictKeys }
            }
            .map { entry -> entry.id }
        val persistedConflictIds = recordStore.records()
            .filter { record ->
                record.type == MemoryRecordType.Preference &&
                    record.id != id &&
                    explicitPreferenceConflictKeys(record.text).any { key -> key in conflictKeys }
            }
            .map { record -> record.id }
        (inMemoryConflictIds + persistedConflictIds)
            .distinct()
            .forEach { conflictId ->
                entries.remove(conflictId)
                recordStore.delete(conflictId)
            }
    }

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        val size = minOf(left.size, right.size)
        var dot = 0f
        for (index in 0 until size) {
            dot += left[index] * right[index]
        }
        return dot
    }
}

internal fun explicitUserPreferenceFrom(text: String): String? {
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

internal fun explicitUserPreferenceRecordId(preference: String): String {
    val key = preference.trim().replace(Regex("""\s+"""), " ").lowercase(Locale.ROOT)
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    return "preference-${hash.take(16)}"
}

internal fun explicitPreferenceConflictKey(preference: String): String? {
    return explicitPreferenceConflictKeys(preference).firstOrNull()
}

private fun explicitPreferenceConflictKeys(preference: String): Set<String> {
    val normalized = preference
        .trim()
        .removePrefix("用户偏好：")
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("""\s+"""), " ")
    if (normalized.isBlank() || !normalized.containsAny(RESPONSE_PREFERENCE_TERMS)) {
        return emptySet()
    }
    return buildSet {
        if (normalized.containsAny(RESPONSE_LENGTH_TERMS)) add("response-length")
        if (normalized.containsAny(RESPONSE_LANGUAGE_TERMS)) add("response-language")
    }
}

internal const val TASK_STATE_MEMORY_RECORD_PREFIX = "task-state-background:"

internal fun taskStateMemoryRecordId(taskId: String): String {
    val normalized = taskId
        .trim()
        .replace(Regex("""[^A-Za-z0-9_.:-]+"""), "-")
        .trim('-')
        .take(64)
        .takeIf { it.isNotBlank() }
        ?: "unknown"
    return "$TASK_STATE_MEMORY_RECORD_PREFIX$normalized"
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

private fun String.containsAny(terms: Set<String>): Boolean =
    terms.any { term -> contains(term) }

private val RESPONSE_PREFERENCE_TERMS = setOf(
    "answer",
    "answers",
    "reply",
    "replies",
    "respond",
    "response",
    "responses",
    "答案",
    "回答",
    "回复",
)

private val RESPONSE_LENGTH_TERMS = setOf(
    "brief",
    "concise",
    "detailed",
    "long",
    "short",
    "succinct",
    "terse",
    "verbose",
    "展开",
    "简洁",
    "简短",
    "精炼",
    "详细",
    "详尽",
)

private val RESPONSE_LANGUAGE_TERMS = setOf(
    "chinese",
    "english",
    "mandarin",
    "中文",
    "汉语",
    "英文",
    "英语",
)
