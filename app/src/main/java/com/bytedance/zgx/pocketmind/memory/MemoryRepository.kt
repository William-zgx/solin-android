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
    SuppressedTaskState,
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
    fun suppressAutoManagedTaskState(id: String)
    fun unsuppressAutoManagedTaskState(id: String)
    fun isAutoManagedTaskStateSuppressed(id: String): Boolean
    fun forget(id: String): Boolean
    fun clear()
}

interface SemanticMemoryRuntimeController {
    val activeMemoryModelPath: String?
    val semanticMemoryEnabled: Boolean
    val canLoadSemanticMemoryRuntime: Boolean
        get() = true
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
    private val semanticRuntimeFactory: ((String) -> EmbeddingRuntime?)? = null,
    private val recordStore: MemoryRecordStore = NoOpMemoryRecordStore,
) : MemoryIndex, LongTermMemoryControls, SemanticMemoryRuntimeController {
    private val entries = linkedMapOf<String, MemoryEntry>()
    private val defaultEmbeddingRuntime = embeddingRuntime
    private var activeEmbeddingRuntime: EmbeddingRuntime = embeddingRuntime
    override var activeMemoryModelPath: String? = null
        private set
    override val semanticMemoryEnabled: Boolean
        get() = activeMemoryModelPath != null && activeEmbeddingRuntime.supportsSemanticRecall
    override val canLoadSemanticMemoryRuntime: Boolean
        get() = semanticRuntimeFactory != null
    override var enabled: Boolean = true

    override fun useMemoryModel(path: String?) {
        val normalizedPath = path?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedPath == activeMemoryModelPath && semanticMemoryEnabled) return
        if (normalizedPath == null && activeMemoryModelPath == null && activeEmbeddingRuntime === defaultEmbeddingRuntime) {
            return
        }
        val semanticRuntime = normalizedPath
            ?.let { modelPath ->
                semanticRuntimeFactory?.let { factory ->
                    runCatching { factory(modelPath) }.getOrNull()
                }
            }
            ?.takeIf { runtime ->
                runtime.supportsSemanticRecall &&
                    runCatching { runtime.embed(SEMANTIC_RUNTIME_PROBE_TEXT) }.isSuccess
            }
        if (semanticRuntime == null) {
            activeMemoryModelPath = null
            activeEmbeddingRuntime = defaultEmbeddingRuntime
        } else {
            activeMemoryModelPath = checkNotNull(normalizedPath)
            activeEmbeddingRuntime = semanticRuntime
        }
        runCatching { reembedEntries() }.onFailure {
            activeMemoryModelPath = null
            activeEmbeddingRuntime = defaultEmbeddingRuntime
            reembedEntries()
        }
    }

    override fun rebuild(messages: List<ChatMessage>) {
        entries.clear()
        visibleRecords().forEach { record ->
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
        visibleRecords()

    override fun indexPreference(id: String, text: String) {
        if (text.isBlank()) return
        forgetConflictingPreferences(id, text)
        indexRecord(id, "用户偏好：$text", MemoryRecordType.Preference, persist = true)
    }

    override fun indexTaskState(id: String, text: String) {
        if (isAutoManagedTaskStateSuppressed(id)) return
        indexRecord(id, "任务状态：$text", MemoryRecordType.TaskState, persist = true)
    }

    override fun suppressAutoManagedTaskState(id: String) {
        if (!id.startsWith(TASK_STATE_MEMORY_RECORD_PREFIX)) return
        entries.remove(id)
        recordStore.delete(id)
        recordStore.upsert(
            PersistedMemoryRecord(
                id = suppressedTaskStateMemoryRecordId(id),
                type = MemoryRecordType.SuppressedTaskState,
                text = id,
            ),
        )
    }

    override fun unsuppressAutoManagedTaskState(id: String) {
        recordStore.delete(suppressedTaskStateMemoryRecordId(id))
    }

    override fun isAutoManagedTaskStateSuppressed(id: String): Boolean =
        recordStore.records().any { record ->
            record.type == MemoryRecordType.SuppressedTaskState &&
                record.text == id
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

    private fun visibleRecords(): List<PersistedMemoryRecord> =
        recordStore.records().filter { record ->
            record.type == MemoryRecordType.Preference ||
                record.type == MemoryRecordType.TaskState
        }

    private fun indexRecord(
        id: String,
        text: String,
        type: MemoryRecordType,
        persist: Boolean,
    ) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        val searchText = searchTextFor(type, normalized)
        val originalTokens = tokenize(normalized).toSet()
        val tokens = tokenize(searchText).toSet()
        if (tokens.isEmpty()) return
        entries[id] = MemoryEntry(
            id = id,
            text = normalized,
            type = type,
            originalTokens = originalTokens,
            tokens = tokens,
            searchText = searchText,
            embedding = activeEmbeddingRuntime.embed(embeddingTextFor(normalized, searchText)),
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
        val normalizedQuery = query.lowercase(Locale.ROOT)
        val requiredOverlapTokens = queryTokens.filter { it.length > 1 }.ifEmpty { queryTokens }
        val queryEmbedding = activeEmbeddingRuntime.embed(query)
        val supportsSemanticRecall = activeEmbeddingRuntime.supportsSemanticRecall
        val semanticScoreThreshold = activeEmbeddingRuntime.semanticScoreThreshold
        return entries.values
            .mapNotNull { entry ->
                if (entry.shouldSuppressTaskStateHitFor(normalizedQuery)) return@mapNotNull null
                val hasLexicalOverlap = entry.tokens.any { it in requiredOverlapTokens }
                if (!hasLexicalOverlap && !supportsSemanticRecall) return@mapNotNull null
                val score = cosine(queryEmbedding, entry.embedding)
                if (score <= 0f) return@mapNotNull null
                val hasOriginalLexicalOverlap = entry.originalTokens.any { it in requiredOverlapTokens }
                val recallMode = if (!supportsSemanticRecall || hasOriginalLexicalOverlap) {
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
                entries[id] = entry.copy(embedding = activeEmbeddingRuntime.embed(embeddingTextFor(entry)))
            }
        }
    }

    private fun embeddingTextFor(entry: MemoryEntry): String =
        embeddingTextFor(entry.text, entry.searchText)

    private fun embeddingTextFor(text: String, searchText: String): String =
        if (activeEmbeddingRuntime.supportsSemanticRecall) text else searchText

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

internal fun suppressedTaskStateMemoryRecordId(taskStateMemoryId: String): String =
    "suppressed-$taskStateMemoryId"

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
    val originalTokens: Set<String>,
    val tokens: Set<String>,
    val searchText: String,
    val embedding: FloatArray,
)

private fun searchTextFor(type: MemoryRecordType, text: String): String {
    val aliases = memorySearchAliases(type, text)
    return if (aliases.isEmpty()) {
        text
    } else {
        "$text\n${aliases.joinToString(separator = " ")}"
    }
}

private fun memorySearchAliases(type: MemoryRecordType, text: String): Set<String> =
    when (type) {
        MemoryRecordType.Preference -> preferenceSearchAliases(text)
        MemoryRecordType.TaskState -> taskStateSearchAliases(text)
        MemoryRecordType.Conversation,
        MemoryRecordType.SuppressedTaskState -> emptySet()
    }

private fun preferenceSearchAliases(text: String): Set<String> {
    val normalized = text
        .trim()
        .removePrefix("用户偏好：")
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("""\s+"""), " ")
    if (normalized.isBlank() || !normalized.containsAny(RESPONSE_PREFERENCE_TERMS)) {
        return emptySet()
    }
    return buildSet {
        if (normalized.containsAny(RESPONSE_CONCISE_TERMS)) addAll(RESPONSE_CONCISE_ALIASES)
        if (normalized.containsAny(RESPONSE_DETAILED_TERMS)) addAll(RESPONSE_DETAILED_ALIASES)
        if (normalized.containsAny(RESPONSE_CHINESE_TERMS)) addAll(RESPONSE_CHINESE_ALIASES)
        if (normalized.containsAny(RESPONSE_ENGLISH_TERMS)) addAll(RESPONSE_ENGLISH_ALIASES)
    }
}

private fun taskStateSearchAliases(text: String): Set<String> {
    val normalized = text.lowercase(Locale.ROOT)
    if (!normalized.hasStructuredTaskStateMemoryShape()) return emptySet()
    return buildSet {
        addAll(TASK_STATE_ALIASES)
        if ("reminder" in normalized) addAll(TASK_STATE_REMINDER_ALIASES)
        if ("periodiccheck" in normalized || "periodic-check" in normalized) {
            addAll(TASK_STATE_PERIODIC_CHECK_ALIASES)
        }
        if ("状态=scheduled" in normalized) addAll(TASK_STATE_SCHEDULED_ALIASES)
        if ("状态=running" in normalized) addAll(TASK_STATE_RUNNING_ALIASES)
    }
}

private fun String.hasStructuredTaskStateMemoryShape(): Boolean =
    startsWith("任务状态：") &&
        "后台任务=" in this &&
        "任务记录=" in this &&
        "状态=" in this &&
        "触发时间=" in this

private fun MemoryEntry.shouldSuppressTaskStateHitFor(normalizedQuery: String): Boolean {
    if (type != MemoryRecordType.TaskState || !normalizedQuery.containsAny(TASK_STATE_TERMINAL_QUERY_TERMS)) {
        return false
    }
    return !text.lowercase(Locale.ROOT).containsAny(TASK_STATE_TERMINAL_QUERY_TERMS)
}

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

private const val SEMANTIC_RUNTIME_PROBE_TEXT = "semantic memory runtime probe"

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

private val RESPONSE_CONCISE_TERMS = setOf(
    "brief",
    "concise",
    "short",
    "succinct",
    "terse",
    "简洁",
    "简短",
    "精炼",
)

private val RESPONSE_DETAILED_TERMS = setOf(
    "detailed",
    "expanded",
    "long",
    "verbose",
    "elaborate",
    "full",
    "thorough",
    "展开",
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

private val RESPONSE_CHINESE_TERMS = setOf(
    "chinese",
    "mandarin",
    "普通话",
    "中文",
    "汉语",
)

private val RESPONSE_ENGLISH_TERMS = setOf(
    "english",
    "英文",
    "英语",
)

private val RESPONSE_CONCISE_ALIASES = setOf(
    "brief",
    "concise",
    "short",
    "succinct",
    "terse",
    "简洁",
    "简短",
    "精炼",
)

private val RESPONSE_DETAILED_ALIASES = setOf(
    "detailed",
    "expanded",
    "long",
    "verbose",
    "elaborate",
    "thorough",
    "展开",
    "详细",
    "详尽",
)

private val RESPONSE_CHINESE_ALIASES = setOf(
    "chinese",
    "mandarin",
    "普通话",
    "中文",
    "汉语",
)

private val RESPONSE_ENGLISH_ALIASES = setOf(
    "english",
    "英文",
    "英语",
)

private val TASK_STATE_ALIASES = setOf(
    "taskstate",
    "待办事项",
    "任务状态",
)

private val TASK_STATE_REMINDER_ALIASES = setOf(
    "reminder",
    "reminders",
    "alarm",
    "alert",
    "提醒",
    "提醒事项",
    "后台提醒",
    "本地提醒",
    "闹钟",
)

private val TASK_STATE_PERIODIC_CHECK_ALIASES = setOf(
    "periodic",
    "periodiccheck",
    "patrol",
    "周期检查",
    "定期检查",
    "后台检查",
    "本地巡检",
)

private val TASK_STATE_SCHEDULED_ALIASES = setOf(
    "scheduled",
    "pending",
    "queued",
    "upcoming",
    "已安排",
    "待执行",
    "已计划",
    "待触发",
)

private val TASK_STATE_RUNNING_ALIASES = setOf(
    "running",
    "executing",
    "inprogress",
    "运行中",
    "进行中",
    "执行中",
)

private val TASK_STATE_TERMINAL_QUERY_TERMS = setOf(
    "cancelled",
    "canceled",
    "deleted",
    "delivered",
    "done",
    "failed",
    "finished",
    "取消",
    "已取消",
    "删除",
    "已删除",
    "失败",
    "已失败",
    "完成",
    "已完成",
    "送达",
    "已送达",
)
