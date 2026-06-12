package com.bytedance.zgx.pocketmind.memory

import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.MessageRole
import com.bytedance.zgx.pocketmind.data.MemoryEmbeddingDao
import com.bytedance.zgx.pocketmind.data.MemoryEmbeddingEntity
import com.bytedance.zgx.pocketmind.data.MemoryRecordDao
import com.bytedance.zgx.pocketmind.data.MemoryRecordEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    UserFact,
    TaskState,
    SuppressedTaskState,
}

enum class SemanticMemoryRuntimeStatus {
    NoVerifiedModel,
    AssetMissing,
    RuntimeUnavailable,
    ProbeFailed,
    BuildingIndex,
    Active,
    DegradedLexical,
}

interface EmbeddingRuntime {
    val runtimeId: String
        get() = this::class.java.name

    val modelId: String
        get() = runtimeId

    val dimension: Int
        get() = -1

    val backend: String
        get() = "unknown"

    val supportsSemanticRecall: Boolean
        get() = false

    val semanticScoreThreshold: Float
        get() = 0.72f

    fun embed(text: String): FloatArray

    fun embedBatch(texts: List<String>): List<FloatArray> =
        texts.map(::embed)

    fun close() = Unit
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
    fun indexUserFact(id: String, text: String)
    fun indexTaskState(id: String, text: String)
    fun suppressAutoManagedTaskState(id: String)
    fun unsuppressAutoManagedTaskState(id: String)
    fun isAutoManagedTaskStateSuppressed(id: String): Boolean
    fun forget(id: String): Boolean
    fun forgetPreference(target: String): Boolean
    fun forgetUserFact(target: String): Boolean
    fun clear()
}

interface SemanticMemoryRuntimeController {
    val activeMemoryModelPath: String?
    val semanticMemoryEnabled: Boolean
    val semanticMemoryRuntimeStatus: SemanticMemoryRuntimeStatus
    val semanticMemoryIndexedRecordCount: Int
    val semanticMemoryLastRebuiltAtMillis: Long?
    val canLoadSemanticMemoryRuntime: Boolean
        get() = true
    fun useMemoryModel(path: String?)
    fun clearSemanticMemoryForModel(modelId: String)
}

class HashingEmbeddingRuntime(
    private val dimensions: Int = 256,
) : EmbeddingRuntime {
    override val runtimeId: String = "hashing-lexical"
    override val modelId: String = "hashing-lexical"
    override val dimension: Int = dimensions
    override val backend: String = "hash"

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
    private val embeddingStore: MemoryEmbeddingStore = NoOpMemoryEmbeddingStore,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : MemoryIndex, LongTermMemoryControls, SemanticMemoryRuntimeController {
    private val entries = linkedMapOf<String, MemoryEntry>()
    private val defaultEmbeddingRuntime = embeddingRuntime
    private var activeEmbeddingRuntime: EmbeddingRuntime = embeddingRuntime
    override var activeMemoryModelPath: String? = null
        private set
    override var semanticMemoryRuntimeStatus: SemanticMemoryRuntimeStatus =
        SemanticMemoryRuntimeStatus.NoVerifiedModel
        private set
    override var semanticMemoryIndexedRecordCount: Int = 0
        private set
    override var semanticMemoryLastRebuiltAtMillis: Long? = null
        private set
    override val semanticMemoryEnabled: Boolean
        get() = semanticMemoryRuntimeStatus == SemanticMemoryRuntimeStatus.Active
    override val canLoadSemanticMemoryRuntime: Boolean
        get() = semanticRuntimeFactory != null

    override fun clearSemanticMemoryForModel(modelId: String) {
        embeddingStore.deleteForModel(modelId)
        if (activeEmbeddingRuntime.modelId == modelId) {
            activeEmbeddingRuntime.close()
            activeMemoryModelPath = null
            activeEmbeddingRuntime = defaultEmbeddingRuntime
            semanticMemoryRuntimeStatus = SemanticMemoryRuntimeStatus.NoVerifiedModel
            reembedEntries()
        }
    }
    override var enabled: Boolean = true

    override fun useMemoryModel(path: String?) {
        val normalizedPath = path?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedPath == activeMemoryModelPath && semanticMemoryEnabled) return
        if (normalizedPath == null && activeMemoryModelPath == null && activeEmbeddingRuntime === defaultEmbeddingRuntime) {
            semanticMemoryRuntimeStatus = SemanticMemoryRuntimeStatus.NoVerifiedModel
            refreshSemanticIndexStats()
            return
        }
        if (normalizedPath == null) {
            activeMemoryModelPath = null
            activeEmbeddingRuntime.close()
            activeEmbeddingRuntime = defaultEmbeddingRuntime
            semanticMemoryRuntimeStatus = SemanticMemoryRuntimeStatus.NoVerifiedModel
            reembedEntries()
            return
        }
        val runtimeFactory = semanticRuntimeFactory
        if (runtimeFactory == null) {
            activeMemoryModelPath = null
            activeEmbeddingRuntime.close()
            activeEmbeddingRuntime = defaultEmbeddingRuntime
            semanticMemoryRuntimeStatus = SemanticMemoryRuntimeStatus.RuntimeUnavailable
            reembedEntries()
            return
        }
        val semanticRuntime = runCatching { runtimeFactory(normalizedPath) }.getOrNull()
        if (semanticRuntime == null) {
            activeMemoryModelPath = null
            activeEmbeddingRuntime.close()
            activeEmbeddingRuntime = defaultEmbeddingRuntime
            semanticMemoryRuntimeStatus = SemanticMemoryRuntimeStatus.RuntimeUnavailable
        } else {
            val probeSucceeded = semanticRuntime.supportsSemanticRecall &&
                runCatching { semanticRuntime.probeSemanticVector() }.getOrDefault(false)
            if (!probeSucceeded) {
                semanticRuntime.close()
                activeMemoryModelPath = null
                activeEmbeddingRuntime.close()
                activeEmbeddingRuntime = defaultEmbeddingRuntime
                semanticMemoryRuntimeStatus = SemanticMemoryRuntimeStatus.ProbeFailed
            } else {
                activeMemoryModelPath = normalizedPath
                activeEmbeddingRuntime.close()
                activeEmbeddingRuntime = semanticRuntime
                semanticMemoryRuntimeStatus = SemanticMemoryRuntimeStatus.BuildingIndex
            }
        }
        runCatching { reembedEntries() }.onFailure {
            activeMemoryModelPath = null
            activeEmbeddingRuntime.close()
            activeEmbeddingRuntime = defaultEmbeddingRuntime
            semanticMemoryRuntimeStatus = SemanticMemoryRuntimeStatus.DegradedLexical
            reembedEntries()
        }
        if (semanticMemoryRuntimeStatus == SemanticMemoryRuntimeStatus.BuildingIndex) {
            semanticMemoryRuntimeStatus = SemanticMemoryRuntimeStatus.Active
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
            if (message.role != MessageRole.User) return@forEach
            if (
                explicitUserPreferenceFrom(message.text) != null ||
                    explicitUserFactFrom(message.text) != null ||
                    explicitUserPreferenceForgetFrom(message.text) != null
            ) {
                return@forEach
            }
            if (message.privacy == MessagePrivacy.LocalOnly) return@forEach
            index(
                id = message.id.toString(),
                text = "用户：${message.text}",
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

    override fun indexUserFact(id: String, text: String) {
        if (text.isBlank()) return
        forgetConflictingUserFacts(id, text)
        indexRecord(id, "用户事实：$text", MemoryRecordType.UserFact, persist = true)
    }

    override fun indexTaskState(id: String, text: String) {
        if (isAutoManagedTaskStateSuppressed(id)) return
        indexRecord(id, "任务状态：$text", MemoryRecordType.TaskState, persist = true)
    }

    override fun suppressAutoManagedTaskState(id: String) {
        if (!id.startsWith(TASK_STATE_MEMORY_RECORD_PREFIX)) return
        entries.remove(id)
        recordStore.delete(id)
        embeddingStore.delete(id)
        refreshSemanticIndexStats()
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
        embeddingStore.delete(id)
        refreshSemanticIndexStats()
        return removedInMemory || removedPersisted
    }

    override fun forgetPreference(target: String): Boolean {
        val normalizedTarget = target.trim()
        if (normalizedTarget.isBlank()) return false
        val exactRemoved = forget(explicitUserPreferenceRecordId(normalizedTarget))
        val conflictKeys = explicitPreferenceForgetConflictKeys(normalizedTarget)
        if (conflictKeys.isEmpty()) return exactRemoved
        val conflictIds = (entries.values.map { entry ->
            PersistedMemoryRecord(entry.id, entry.type, entry.text)
        } + recordStore.records())
            .filter { record ->
                record.type == MemoryRecordType.Preference &&
                    explicitPreferenceConflictKeys(record.text).any { key -> key in conflictKeys }
            }
            .map { record -> record.id }
            .distinct()
        var removedConflict = false
        conflictIds.forEach { conflictId ->
            removedConflict = forget(conflictId) || removedConflict
        }
        return exactRemoved || removedConflict
    }

    override fun forgetUserFact(target: String): Boolean {
        val normalizedTarget = target.trim()
        if (normalizedTarget.isBlank()) return false
        val exactRemoved = forget(explicitUserFactRecordId(normalizedTarget))
        val targetKey = userFactKeyFrom(normalizedTarget)
        if (targetKey == null) return exactRemoved
        val matchingIds = (entries.values.map { entry ->
            PersistedMemoryRecord(entry.id, entry.type, entry.text)
        } + recordStore.records())
            .filter { record ->
                record.type == MemoryRecordType.UserFact &&
                    userFactKeyFrom(record.text) == targetKey
            }
            .map { record -> record.id }
            .distinct()
        var removedMatching = false
        matchingIds.forEach { matchingId ->
            removedMatching = forget(matchingId) || removedMatching
        }
        return exactRemoved || removedMatching
    }

    override fun clear() {
        entries.clear()
        recordStore.clear()
        embeddingStore.clear()
        refreshSemanticIndexStats()
    }

    private fun visibleRecords(): List<PersistedMemoryRecord> =
        recordStore.records().filter { record ->
            record.type == MemoryRecordType.Preference ||
                record.type == MemoryRecordType.UserFact ||
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
            lexicalEmbedding = defaultEmbeddingRuntime.embed(searchText),
            semanticEmbedding = semanticEmbeddingFor(
                id = id,
                text = normalized,
                type = type,
            ),
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
        refreshSemanticIndexStats()
    }

    override fun search(query: String, topK: Int): List<MemoryHit> {
        if (!enabled || query.isBlank() || topK <= 0 || entries.isEmpty()) return emptyList()
        val queryTokens = tokenize(query).toSet()
        if (queryTokens.isEmpty()) return emptyList()
        val normalizedQuery = query.lowercase(Locale.ROOT)
        val requiredOverlapTokens = queryTokens.filter { it.length > 1 }.ifEmpty { queryTokens }
        val supportsSemanticRecall = activeEmbeddingRuntime.supportsSemanticRecall
        val semanticScoreThreshold = activeEmbeddingRuntime.semanticScoreThreshold
        val lexicalQueryEmbedding = defaultEmbeddingRuntime.embed(query)
        val semanticQueryEmbedding = if (supportsSemanticRecall) embedSemanticQuery(query) else null
        return entries.values
            .mapNotNull { entry ->
                if (entry.shouldSuppressTaskStateHitFor(normalizedQuery)) return@mapNotNull null
                val hasLexicalOverlap = entry.tokens.any { it in requiredOverlapTokens }
                if (hasLexicalOverlap) {
                    val score = cosine(lexicalQueryEmbedding, entry.lexicalEmbedding)
                    if (score <= 0f) return@mapNotNull null
                    return@mapNotNull MemoryHit(
                        id = entry.id,
                        text = entry.text,
                        score = score,
                        recallMode = MemoryRecallMode.Lexical,
                    )
                }
                val semanticEmbedding = entry.semanticEmbedding
                val queryEmbedding = semanticQueryEmbedding
                if (
                    semanticEmbedding == null ||
                    queryEmbedding == null ||
                    !entry.type.isSemanticRecallEligible()
                ) {
                    return@mapNotNull null
                }
                val score = cosine(queryEmbedding, semanticEmbedding)
                if (score <= 0f || score < semanticScoreThreshold) {
                    return@mapNotNull null
                }
                MemoryHit(
                    id = entry.id,
                    text = entry.text,
                    score = score,
                    recallMode = MemoryRecallMode.Semantic,
                )
            }
            .sortedWith(
                compareByDescending<MemoryHit> { it.score }
                    .thenBy { if (it.recallMode == MemoryRecallMode.Semantic) 0 else 1 },
            )
            .take(topK)
    }

    override fun buildContext(hits: List<MemoryHit>): String =
        hits.joinToString(separator = "\n") { "- ${it.text}" }

    private fun semanticEmbeddingFor(
        id: String,
        text: String,
        type: MemoryRecordType,
    ): FloatArray? {
        val runtime = activeEmbeddingRuntime
        if (!runtime.supportsSemanticRecall || !type.isSemanticRecallEligible()) return null
        val modelId = runtime.modelId
        val dimension = runtime.dimension
        if (modelId.isBlank() || dimension <= 0) return null
        val sourceHash = semanticSourceHash(text)
        val cached = embeddingStore.embedding(id, modelId)
        if (
            cached != null &&
            cached.sourceHash == sourceHash &&
            cached.dimension == dimension &&
            cached.vector.size == dimension
        ) {
            return cached.vector
        }
        val vector = runCatching { runtime.embed(text).normalizedVector(dimension) }.getOrElse {
            failActiveSemanticRuntime()
            return null
        }
        embeddingStore.upsert(
            PersistedMemoryEmbedding(
                recordId = id,
                modelId = modelId,
                sourceHash = sourceHash,
                dimension = dimension,
                vector = vector,
                updatedAtMillis = clockMillis(),
            ),
        )
        return vector
    }

    private fun embedSemanticQuery(query: String): FloatArray? {
        val runtime = activeEmbeddingRuntime
        if (!runtime.supportsSemanticRecall || runtime.dimension <= 0) return null
        return runCatching { runtime.embed(query).normalizedVector(runtime.dimension) }.getOrElse {
            failActiveSemanticRuntime()
            null
        }
    }

    private fun reembedEntries() {
        entries.keys.toList().forEach { id ->
            entries[id]?.let { entry ->
                entries[id] = entry.copy(
                    lexicalEmbedding = defaultEmbeddingRuntime.embed(entry.searchText),
                    semanticEmbedding = semanticEmbeddingFor(
                        id = entry.id,
                        text = entry.text,
                        type = entry.type,
                    ),
                )
            }
        }
        refreshSemanticIndexStats()
    }

    private fun refreshSemanticIndexStats() {
        semanticMemoryIndexedRecordCount = entries.values.count { entry ->
            entry.type.isSemanticRecallEligible() && entry.semanticEmbedding != null
        }
        semanticMemoryLastRebuiltAtMillis = if (semanticMemoryIndexedRecordCount > 0) {
            clockMillis()
        } else {
            null
        }
    }

    private fun failActiveSemanticRuntime() {
        activeMemoryModelPath = null
        activeEmbeddingRuntime.close()
        activeEmbeddingRuntime = defaultEmbeddingRuntime
        semanticMemoryRuntimeStatus = SemanticMemoryRuntimeStatus.DegradedLexical
        reembedEntries()
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
                embeddingStore.delete(conflictId)
            }
        refreshSemanticIndexStats()
    }

    private fun forgetConflictingUserFacts(id: String, text: String) {
        val factKey = userFactKeyFrom(text) ?: return
        val inMemoryConflictIds = entries.values
            .filter { entry ->
                entry.type == MemoryRecordType.UserFact &&
                    entry.id != id &&
                    userFactKeyFrom(entry.text) == factKey
            }
            .map { entry -> entry.id }
        val persistedConflictIds = recordStore.records()
            .filter { record ->
                record.type == MemoryRecordType.UserFact &&
                    record.id != id &&
                    userFactKeyFrom(record.text) == factKey
            }
            .map { record -> record.id }
        (inMemoryConflictIds + persistedConflictIds)
            .distinct()
            .forEach { conflictId ->
                entries.remove(conflictId)
                recordStore.delete(conflictId)
                embeddingStore.delete(conflictId)
            }
        refreshSemanticIndexStats()
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
    val remembered = explicitRememberPayloadFrom(text) ?: return null
    return remembered
        .takeUnless(::isExplicitUserFactPayload)
        ?.takeIf { it.isNotBlank() }
}

internal fun explicitUserFactFrom(text: String): String? {
    val remembered = explicitRememberPayloadFrom(text) ?: return null
    return remembered.takeIf(::isExplicitUserFactPayload)
}

internal fun explicitUserPreferenceForgetFrom(text: String): String? {
    val trimmed = text.trim()
    val chinesePrefix = Regex("""^(?:请)?(?:忘记|遗忘|删除|移除|不要再记住|别再记住)[:：]?\s*(.+)$""")
    chinesePrefix.find(trimmed)?.groupValues?.getOrNull(1)?.toPreferenceForgetTarget()?.let { preference ->
        if (preference.isNotBlank()) return preference
    }

    val englishPrefix = Regex(
        pattern = """^(?:please\s+)?(?:forget|delete|remove)\s+(?:that\s+)?(.+)$""",
        option = RegexOption.IGNORE_CASE,
    )
    return englishPrefix.find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.toPreferenceForgetTarget()
        ?.takeIf { it.isNotBlank() }
}

internal fun explicitUserPreferenceRecordId(preference: String): String {
    val key = normalizedMemoryText(preference)
    return deterministicMemoryRecordId("preference", key)
}

internal fun explicitUserFactRecordId(fact: String): String {
    val key = userFactKeyFrom(fact) ?: normalizedMemoryText(fact)
    return deterministicMemoryRecordId("user-fact", key)
}

private fun deterministicMemoryRecordId(prefix: String, key: String): String {
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    return "$prefix-${hash.take(16)}"
}

private fun String.toPreferenceForgetTarget(): String =
    trim()
        .removePrefix("用户偏好：")
        .removePrefix("用户事实：")
        .removePrefix("user preference:")
        .removePrefix("user fact:")
        .trim()

private fun explicitRememberPayloadFrom(text: String): String? {
    val trimmed = text.trim()
    val chinesePrefix = Regex("""^(请)?记住[:：]?\s*(.+)$""")
    chinesePrefix.find(trimmed)?.groupValues?.getOrNull(2)?.trim()?.let { payload ->
        if (payload.isNotBlank()) return payload
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

internal fun explicitPreferenceConflictKey(preference: String): String? {
    return explicitPreferenceConflictKeys(preference).firstOrNull()
}

internal fun explicitPreferenceForgetConflictKeys(target: String): Set<String> {
    val conflictKeys = explicitPreferenceConflictKeys(target)
    if (conflictKeys.isNotEmpty()) return conflictKeys
    val normalized = normalizedPreferenceText(target)
    return if (
        normalized.containsAny(RESPONSE_PREFERENCE_TERMS) &&
        normalized.containsAny(PREFERENCE_CONTROL_TERMS)
    ) {
        setOf("response-length", "response-language")
    } else {
        emptySet()
    }
}

private fun explicitPreferenceConflictKeys(preference: String): Set<String> {
    val normalized = normalizedPreferenceText(preference)
    if (normalized.isBlank() || !normalized.containsAny(RESPONSE_PREFERENCE_TERMS)) {
        return emptySet()
    }
    return buildSet {
        if (normalized.containsAny(RESPONSE_LENGTH_TERMS)) add("response-length")
        if (normalized.containsAny(RESPONSE_LANGUAGE_TERMS)) add("response-language")
    }
}

private fun normalizedPreferenceText(text: String): String =
    text
        .trim()
        .removePrefix("用户偏好：")
        .removePrefix("user preference:")
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("""\s+"""), " ")

private fun normalizedMemoryText(text: String): String =
    text.trim().replace(Regex("""\s+"""), " ").lowercase(Locale.ROOT)

private fun userFactKeyFrom(text: String): String? {
    val normalized = normalizedUserFactText(text)
    val chineseMatch = Regex("""^(?:我(?:的)?)(.+?)(?:是|为)(.+)$""")
        .find(normalized)
    if (chineseMatch != null) {
        return chineseMatch.groupValues.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { "zh:$it" }
    }
    val englishMatch = Regex("""^my\s+(.+?)\s+(?:is|are)\s+(.+)$""")
        .find(normalized)
    return englishMatch
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { key -> "en:$key" }
}

private fun isExplicitUserFactPayload(text: String): Boolean =
    userFactKeyFrom(text) != null && explicitPreferenceConflictKeys(text).isEmpty()

private fun normalizedUserFactText(text: String): String =
    text.trim()
        .removePrefix("用户事实：")
        .removePrefix("user fact:")
        .lowercase(Locale.ROOT)
        .replace(Regex("""\s+"""), " ")

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

data class PersistedMemoryEmbedding(
    val recordId: String,
    val modelId: String,
    val sourceHash: String,
    val dimension: Int,
    val vector: FloatArray,
    val updatedAtMillis: Long,
)

interface MemoryEmbeddingStore {
    fun embedding(recordId: String, modelId: String): PersistedMemoryEmbedding?
    fun upsert(embedding: PersistedMemoryEmbedding)
    fun delete(recordId: String)
    fun deleteForModel(modelId: String)
    fun clear()
}

object NoOpMemoryRecordStore : MemoryRecordStore {
    override fun records(): List<PersistedMemoryRecord> = emptyList()
    override fun upsert(record: PersistedMemoryRecord) = Unit
    override fun delete(id: String): Boolean = false
    override fun clear() = Unit
}

object NoOpMemoryEmbeddingStore : MemoryEmbeddingStore {
    override fun embedding(recordId: String, modelId: String): PersistedMemoryEmbedding? = null
    override fun upsert(embedding: PersistedMemoryEmbedding) = Unit
    override fun delete(recordId: String) = Unit
    override fun deleteForModel(modelId: String) = Unit
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

class RoomMemoryEmbeddingStore(
    private val dao: MemoryEmbeddingDao,
) : MemoryEmbeddingStore {
    override fun embedding(recordId: String, modelId: String): PersistedMemoryEmbedding? =
        dao.embedding(recordId, modelId)?.toPersisted()

    override fun upsert(embedding: PersistedMemoryEmbedding) {
        dao.upsert(embedding.toEntity())
    }

    override fun delete(recordId: String) {
        dao.delete(recordId)
    }

    override fun deleteForModel(modelId: String) {
        dao.deleteForModel(modelId)
    }

    override fun clear() {
        dao.deleteAll()
    }

    private fun MemoryEmbeddingEntity.toPersisted(): PersistedMemoryEmbedding? {
        val vector = vectorBlob.decodeFloatVector(dimension) ?: return null
        return PersistedMemoryEmbedding(
            recordId = recordId,
            modelId = modelId,
            sourceHash = sourceHash,
            dimension = dimension,
            vector = vector,
            updatedAtMillis = updatedAtMillis,
        )
    }

    private fun PersistedMemoryEmbedding.toEntity(): MemoryEmbeddingEntity =
        MemoryEmbeddingEntity(
            recordId = recordId,
            modelId = modelId,
            sourceHash = sourceHash,
            dimension = dimension,
            vectorBlob = vector.encodeFloatVector(),
            updatedAtMillis = updatedAtMillis,
        )

    private fun FloatArray.encodeFloatVector(): ByteArray {
        val buffer = ByteBuffer.allocate(size * java.lang.Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
        forEach(buffer::putFloat)
        return buffer.array()
    }

    private fun ByteArray.decodeFloatVector(dimension: Int): FloatArray? {
        if (dimension <= 0 || size != dimension * java.lang.Float.BYTES) return null
        val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimension) { buffer.float }
    }
}

private data class MemoryEntry(
    val id: String,
    val text: String,
    val type: MemoryRecordType,
    val originalTokens: Set<String>,
    val tokens: Set<String>,
    val searchText: String,
    val lexicalEmbedding: FloatArray,
    val semanticEmbedding: FloatArray?,
)

private fun MemoryRecordType.isSemanticRecallEligible(): Boolean =
    this == MemoryRecordType.Preference ||
        this == MemoryRecordType.UserFact ||
        this == MemoryRecordType.TaskState

private fun EmbeddingRuntime.probeSemanticVector(): Boolean {
    val expectedDimension = dimension
    if (expectedDimension <= 0) return false
    val vector = embed(SEMANTIC_RUNTIME_PROBE_TEXT)
    if (vector.size != expectedDimension) return false
    if (vector.any { value -> value.isNaN() || value.isInfinite() }) return false
    val normSquared = vector.sumOf { value -> (value * value).toDouble() }
    return normSquared > 0.0 && kotlin.math.abs(kotlin.math.sqrt(normSquared) - 1.0) <= 0.05
}

private fun FloatArray.normalizedVector(expectedDimension: Int): FloatArray {
    require(size == expectedDimension) {
        "Embedding dimension mismatch: expected $expectedDimension, got $size"
    }
    val norm = sqrt(sumOf { value -> (value * value).toDouble() }).toFloat()
    require(norm > 0f && !norm.isNaN() && !norm.isInfinite()) {
        "Embedding vector has invalid norm"
    }
    return FloatArray(size) { index -> this[index] / norm }
}

private fun semanticSourceHash(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val normalized = text.trim().replace(Regex("""\s+"""), " ")
    digest.update(normalized.toByteArray(Charsets.UTF_8))
    return digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

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
        MemoryRecordType.UserFact,
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
    terms.any { term -> containsTerm(term) }

private fun String.containsTerm(term: String): Boolean =
    if (term.all { char -> char.isAsciiLetterOrDigit() }) {
        Regex("""(?<![a-z0-9])${Regex.escape(term)}(?![a-z0-9])""")
            .containsMatchIn(this)
    } else {
        contains(term)
    }

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in '0'..'9'

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
    "length",
    "long",
    "short",
    "succinct",
    "terse",
    "verbose",
    "展开",
    "长度",
    "长短",
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
    "language",
    "mandarin",
    "中文",
    "汉语",
    "英文",
    "英语",
    "语言",
    "语种",
)

private val PREFERENCE_CONTROL_TERMS = setOf(
    "preference",
    "preferences",
    "setting",
    "settings",
    "偏好",
    "设置",
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
