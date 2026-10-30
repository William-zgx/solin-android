package com.bytedance.zgx.solin.memory

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
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
    val recordType: MemoryRecordType? = null,
    val lexicalScore: Float? = if (recallMode == MemoryRecallMode.Lexical) score else null,
    val semanticScore: Float? = if (recallMode == MemoryRecallMode.Semantic) score else null,
    val finalScore: Float = score,
    val rankingReason: String = recallMode.name.lowercase(Locale.ROOT),
    val source: MemoryRecordSource = MemoryRecordSource.LegacyImport,
    val sensitivity: MemoryRecordSensitivity = MemoryRecordSensitivity.Normal,
    val privacy: MessagePrivacy = MessagePrivacy.LocalOnly,
    val expiresAtMillis: Long? = null,
    val conflictKey: String? = null,
)

internal fun MemoryHit.isAvailableForLocalContext(nowMillis: Long = System.currentTimeMillis()): Boolean =
    privacy == MessagePrivacy.LocalOnly && !isExpired(nowMillis)

enum class MemoryRecordType {
    Conversation,
    Preference,
    UserFact,
    TaskState,
    SuppressedTaskState,
}

enum class MemoryRecordSource {
    LegacyImport,
    ConversationHistory,
    ExplicitUser,
    AutoTaskState,
}

enum class MemoryRecordSensitivity {
    Normal,
    Sensitive,
    Internal,
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
    fun forgetAutoManagedTaskState(id: String): Boolean
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
        memoryTokens(text).forEach { token ->
            val index = (token.text.hashCode() and Int.MAX_VALUE) % dimensions
            vector[index] += token.weight
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
    private val deletionEventStore: MemoryDeletionEventStore = NoOpMemoryDeletionEventStore,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : MemoryIndex, LongTermMemoryControls, SemanticMemoryRuntimeController {
    /**
     * Guards every piece of mutable state below (the entry map, the active embedding runtime and
     * the semantic status fields) and serialises all store mutations issued by this repository.
     *
     * Writers (rebuild, model switching, re-embedding, forget/clear) usually run under the
     * presentation-layer memory mutex, but [search] is also called directly by the agent loop. A
     * single monitor is deliberately held across embedding calls: the semantic runtime is closed
     * by [useMemoryModel] / [failActiveSemanticRuntime], so releasing the lock around a native
     * `embed` would allow a use-after-close. Correctness wins over search throughput here; a
     * search simply waits for an in-progress rebuild. The monitor is reentrant, so public methods
     * may call each other.
     */
    private val lock = Any()
    private val entries = linkedMapOf<String, MemoryEntry>()

    /** Monotonic insertion counter; a higher value means more recently indexed (recency tie-break). */
    private var nextEntrySequence = 0L
    private val defaultEmbeddingRuntime = embeddingRuntime
    private var activeEmbeddingRuntime: EmbeddingRuntime = embeddingRuntime

    @Volatile
    override var activeMemoryModelPath: String? = null
        private set

    @Volatile
    override var semanticMemoryRuntimeStatus: SemanticMemoryRuntimeStatus =
        SemanticMemoryRuntimeStatus.NoVerifiedModel
        private set

    @Volatile
    override var semanticMemoryIndexedRecordCount: Int = 0
        private set

    @Volatile
    override var semanticMemoryLastRebuiltAtMillis: Long? = null
        private set
    override val semanticMemoryEnabled: Boolean
        get() = semanticMemoryRuntimeStatus == SemanticMemoryRuntimeStatus.Active
    override val canLoadSemanticMemoryRuntime: Boolean
        get() = semanticRuntimeFactory != null

    override fun clearSemanticMemoryForModel(modelId: String): Unit = synchronized(lock) {
        embeddingStore.deleteForModel(modelId)
        if (activeEmbeddingRuntime.modelId == modelId) {
            activeEmbeddingRuntime.close()
            activeMemoryModelPath = null
            activeEmbeddingRuntime = defaultEmbeddingRuntime
            semanticMemoryRuntimeStatus = SemanticMemoryRuntimeStatus.NoVerifiedModel
            reembedEntries()
        }
    }
    @Volatile
    override var enabled: Boolean = true

    override fun useMemoryModel(path: String?): Unit = synchronized(lock) {
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

    override fun rebuild(messages: List<ChatMessage>): Unit = synchronized(lock) {
        entries.clear()
        visibleRecords().forEach { record ->
            indexRecord(
                id = record.id,
                text = record.text,
                type = record.type,
                persist = false,
                source = record.source,
                sensitivity = record.sensitivity,
                privacy = record.privacy,
                expiresAtMillis = record.expiresAtMillis,
                conflictKey = record.conflictKey,
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

    override fun index(id: String, text: String): Unit = synchronized(lock) {
        indexRecord(
            id = id,
            text = text,
            type = MemoryRecordType.Conversation,
            persist = false,
            source = MemoryRecordSource.ConversationHistory,
            privacy = MessagePrivacy.LocalOnly,
        )
    }

    override fun savedRecords(): List<PersistedMemoryRecord> = synchronized(lock) {
        visibleRecords()
    }

    override fun indexPreference(id: String, text: String): Unit = synchronized(lock) {
        if (text.isBlank()) return
        forgetConflictingPreferences(id, text)
        indexRecord(
            id = id,
            text = "用户偏好：$text",
            type = MemoryRecordType.Preference,
            persist = true,
            source = MemoryRecordSource.ExplicitUser,
            sensitivity = MemoryRecordSensitivity.Normal,
            privacy = MessagePrivacy.LocalOnly,
            conflictKey = explicitPreferenceConflictKeys(text).sorted().joinToString(",").takeIf { it.isNotBlank() },
        )
    }

    override fun indexUserFact(id: String, text: String): Unit = synchronized(lock) {
        if (text.isBlank()) return
        forgetConflictingUserFacts(id, text)
        indexRecord(
            id = id,
            text = "用户事实：$text",
            type = MemoryRecordType.UserFact,
            persist = true,
            source = MemoryRecordSource.ExplicitUser,
            sensitivity = MemoryRecordSensitivity.Sensitive,
            privacy = MessagePrivacy.LocalOnly,
            conflictKey = userFactKeyFrom(text),
        )
    }

    override fun indexTaskState(id: String, text: String): Unit = synchronized(lock) {
        if (isAutoManagedTaskStateSuppressed(id)) return
        indexRecord(
            id = id,
            text = "任务状态：$text",
            type = MemoryRecordType.TaskState,
            persist = true,
            source = MemoryRecordSource.AutoTaskState,
            sensitivity = MemoryRecordSensitivity.Internal,
            privacy = MessagePrivacy.LocalOnly,
            conflictKey = id,
        )
    }

    override fun suppressAutoManagedTaskState(id: String): Unit = synchronized(lock) {
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
                source = MemoryRecordSource.AutoTaskState,
                sensitivity = MemoryRecordSensitivity.Internal,
                privacy = MessagePrivacy.LocalOnly,
                conflictKey = id,
            ),
        )
    }

    override fun unsuppressAutoManagedTaskState(id: String): Unit = synchronized(lock) {
        recordStore.delete(suppressedTaskStateMemoryRecordId(id))
    }

    override fun isAutoManagedTaskStateSuppressed(id: String): Boolean = synchronized(lock) {
        recordStore.records().any { record ->
            record.type == MemoryRecordType.SuppressedTaskState &&
                record.text == id
        }
    }

    override fun forget(id: String): Boolean = synchronized(lock) {
        return forget(id, MemoryDeletionOperation.Forget)
    }

    override fun forgetAutoManagedTaskState(id: String): Boolean = synchronized(lock) {
        if (!id.startsWith(TASK_STATE_MEMORY_RECORD_PREFIX)) return false
        return forgetWithoutDeletionAudit(id)
    }

    override fun forgetPreference(target: String): Boolean = synchronized(lock) {
        val normalizedTarget = target.trim()
        if (normalizedTarget.isBlank()) return false
        val exactRemoved = forget(explicitUserPreferenceRecordId(normalizedTarget))
        val conflictKeys = explicitPreferenceForgetConflictKeys(normalizedTarget)
        if (conflictKeys.isEmpty()) return exactRemoved
        val conflictIds = (entries.values.map { entry ->
            entry.toPersistedRecord()
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

    override fun forgetUserFact(target: String): Boolean = synchronized(lock) {
        val normalizedTarget = target.trim()
        if (normalizedTarget.isBlank()) return false
        val exactRemoved = forget(explicitUserFactRecordId(normalizedTarget))
        val targetKey = userFactKeyFrom(normalizedTarget)
        if (targetKey == null) return exactRemoved
        val matchingIds = (entries.values.map { entry ->
            entry.toPersistedRecord()
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

    override fun clear(): Unit = synchronized(lock) {
        val recordsForDeletionAudit = deletionAuditableRecords()
        val deletedAtMillis = clockMillis()
        val events = recordsForDeletionAudit.map { record ->
            record.toDeletionEvent(
                operation = MemoryDeletionOperation.Clear,
                deletedAtMillis = deletedAtMillis,
            )
        }
        deletionEventStore.clearRecordsAndAppendEvents(recordStore, events)
        entries.clear()
        embeddingStore.clear()
        refreshSemanticIndexStats()
    }

    private fun visibleRecords(): List<PersistedMemoryRecord> =
        recordStore.records().filter { record ->
            !record.isExpired(clockMillis()) &&
                (
                    record.type == MemoryRecordType.Preference ||
                        record.type == MemoryRecordType.UserFact ||
                        record.type == MemoryRecordType.TaskState
                    )
        }

    private fun indexRecord(
        id: String,
        text: String,
        type: MemoryRecordType,
        persist: Boolean,
        source: MemoryRecordSource = MemoryRecordSource.LegacyImport,
        sensitivity: MemoryRecordSensitivity = MemoryRecordSensitivity.Normal,
        privacy: MessagePrivacy = MessagePrivacy.LocalOnly,
        expiresAtMillis: Long? = null,
        conflictKey: String? = null,
    ) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        if (expiresAtMillis != null && expiresAtMillis <= clockMillis()) return
        val searchText = searchTextFor(type, normalized)
        val lexicalVector = MemoryTermVector.of(withoutMemoryRecordLabel(searchText))
        // Conversation snippets with no searchable content are not worth an entry. Explicit
        // records are always kept (and persisted) even if every token is a stop word.
        if (lexicalVector.tokens.isEmpty() && type == MemoryRecordType.Conversation) return
        entries[id] = MemoryEntry(
            id = id,
            text = normalized,
            type = type,
            lexicalVector = lexicalVector,
            sequence = entries[id]?.sequence ?: nextEntrySequence++,
            semanticEmbedding = semanticEmbeddingFor(
                id = id,
                text = normalized,
                type = type,
            ),
            source = source,
            sensitivity = sensitivity,
            privacy = privacy,
            expiresAtMillis = expiresAtMillis,
            conflictKey = conflictKey,
        )
        if (persist && type != MemoryRecordType.Conversation) {
            recordStore.upsert(
                PersistedMemoryRecord(
                    id = id,
                    type = type,
                    text = normalized,
                    source = source,
                    sensitivity = sensitivity,
                    privacy = privacy,
                    expiresAtMillis = expiresAtMillis,
                    conflictKey = conflictKey,
                ),
            )
        }
        refreshSemanticIndexStats()
    }

    /**
     * Ranks local memory for [query].
     *
     * 1. The lexical score blends query coverage with exact term-vector cosine. Candidates must pass the overlap gate in [MemoryLexicalMatch] and cover at least
     *    [MIN_LEXICAL_QUERY_COVERAGE] of the query's token weight, so unrelated queries that only
     *    share a stray character or a generic word return nothing.
     * 2. Semantic candidates must clear the runtime's own score threshold (unchanged).
     * 3. Each mode is ranked by its score times a record-type weight that favours explicit
     *    UserFact / Preference records, and the two rankings are combined with reciprocal-rank
     *    fusion. Ties fall back to the raw score, semantic-first, then the most recently indexed.
     *
     * [MemoryHit.score] / [MemoryHit.finalScore] stay the raw relevance (max of the two modes) so
     * user-facing score labels keep their meaning; only the order uses the fused rank.
     */
    override fun search(query: String, topK: Int): List<MemoryHit> = synchronized(lock) {
        if (!enabled || query.isBlank() || topK <= 0 || entries.isEmpty()) return emptyList()
        val queryTokens = MemoryQueryTokens(query)
        if (queryTokens.isEmpty) return emptyList()
        val normalizedQuery = query.lowercase(Locale.ROOT)
        val supportsSemanticRecall = activeEmbeddingRuntime.supportsSemanticRecall
        val semanticScoreThreshold = activeEmbeddingRuntime.semanticScoreThreshold
        val semanticQueryEmbedding = if (supportsSemanticRecall) embedSemanticQuery(query) else null
        val nowMillis = clockMillis()
        val candidates = entries.values.mapNotNull { entry ->
            if (entry.isExpired(nowMillis)) return@mapNotNull null
            if (entry.shouldSuppressTaskStateHitFor(normalizedQuery)) return@mapNotNull null
            val lexicalMatch = queryTokens.match(entry.lexicalVector.tokens)
            val lexicalScore = if (lexicalMatch.qualifies && lexicalMatch.coverage >= MIN_LEXICAL_QUERY_COVERAGE) {
                // Coverage (how much of the query the record answers) dominates; cosine breaks ties
                // in favour of tighter records without penalising long addresses too much.
                (
                    LEXICAL_COVERAGE_WEIGHT * lexicalMatch.coverage +
                        (1f - LEXICAL_COVERAGE_WEIGHT) * queryTokens.vector.cosine(entry.lexicalVector)
                    ).takeIf { score -> score > 0f }
            } else {
                null
            }
            val semanticEmbedding = entry.semanticEmbedding
            val queryEmbedding = semanticQueryEmbedding
            val semanticScore = if (
                semanticEmbedding == null ||
                queryEmbedding == null ||
                !entry.type.isSemanticRecallEligible()
            ) {
                null
            } else {
                cosine(queryEmbedding, semanticEmbedding)
                    .takeIf { score -> score > 0f && score >= semanticScoreThreshold }
            }
            if (lexicalScore == null && semanticScore == null) return@mapNotNull null
            RankingCandidate(entry, lexicalScore, semanticScore)
        }
        val fusedScores = reciprocalRankFusion(candidates)
        return candidates
            .sortedWith(
                compareByDescending<RankingCandidate> { fusedScores.getValue(it.entry.id) }
                    .thenByDescending { it.relevance }
                    .thenBy { if (it.recallMode == MemoryRecallMode.Semantic) 0 else 1 }
                    .thenByDescending { it.entry.sequence },
            )
            .take(topK)
            .map { candidate -> candidate.toHit() }
    }

    override fun buildContext(hits: List<MemoryHit>): String = synchronized(lock) {
        hits
            .filter { hit -> hit.isAvailableForLocalContext(clockMillis()) }
            .joinToString(separator = "\n") { "- ${it.text}" }
    }

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
        if (!activeEmbeddingRuntime.supportsSemanticRecall) {
            semanticMemoryIndexedRecordCount = 0
            semanticMemoryLastRebuiltAtMillis = null
            return
        }
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
                forget(conflictId, MemoryDeletionOperation.ConflictReplace)
            }
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
                forget(conflictId, MemoryDeletionOperation.ConflictReplace)
            }
    }

    private fun forget(id: String, operation: MemoryDeletionOperation): Boolean {
        val recordForDeletionAudit = recordForDeletionAudit(id)
        val event = recordForDeletionAudit?.toDeletionEvent(
            operation = operation,
            deletedAtMillis = clockMillis(),
        )
        val removedInMemory = entries.remove(id) != null
        val removedPersisted = if (event == null) {
            recordStore.delete(id)
        } else {
            deletionEventStore.deleteRecordAndAppendEvent(
                recordStore = recordStore,
                id = id,
                event = event,
            )
        }
        if (removedInMemory && !removedPersisted && event != null) {
            deletionEventStore.append(event)
        }
        embeddingStore.delete(id)
        refreshSemanticIndexStats()
        return removedInMemory || removedPersisted
    }

    private fun forgetWithoutDeletionAudit(id: String): Boolean {
        val removedInMemory = entries.remove(id) != null
        val removedPersisted = recordStore.delete(id)
        embeddingStore.delete(id)
        refreshSemanticIndexStats()
        return removedInMemory || removedPersisted
    }

    private fun recordForDeletionAudit(id: String): PersistedMemoryRecord? =
        (
            entries[id]?.toPersistedRecord()?.let(::listOf).orEmpty() +
                recordStore.records().filter { record -> record.id == id }
            )
            .firstOrNull { record -> record.isDeletionAuditable(clockMillis()) }

    private fun deletionAuditableRecords(): List<PersistedMemoryRecord> =
        (
            entries.values.map { entry -> entry.toPersistedRecord() } +
                recordStore.records()
            )
            .distinctBy { record -> record.id }
            .filter { record -> record.isDeletionAuditable(clockMillis()) }

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
    val type: MemoryRecordType,
    /** Exact term vector of the search text (record text plus local aliases), label prefix removed. */
    val lexicalVector: MemoryTermVector,
    /** Monotonic index order; higher is more recent. */
    val sequence: Long,
    val semanticEmbedding: FloatArray?,
    val source: MemoryRecordSource,
    val sensitivity: MemoryRecordSensitivity,
    val privacy: MessagePrivacy,
    val expiresAtMillis: Long?,
    val conflictKey: String?,
)

private class RankingCandidate(
    val entry: MemoryEntry,
    val lexicalScore: Float?,
    val semanticScore: Float?,
) {
    val relevance: Float = maxOf(lexicalScore ?: 0f, semanticScore ?: 0f)
    val recallMode: MemoryRecallMode =
        if ((semanticScore ?: -1f) >= (lexicalScore ?: -1f)) MemoryRecallMode.Semantic else MemoryRecallMode.Lexical

    fun toHit(): MemoryHit =
        MemoryHit(
            id = entry.id,
            text = entry.text,
            score = relevance,
            recallMode = recallMode,
            recordType = entry.type,
            lexicalScore = lexicalScore,
            semanticScore = semanticScore,
            finalScore = relevance,
            rankingReason = when {
                lexicalScore != null && semanticScore != null -> "lexical token overlap + semantic vector similarity"
                semanticScore != null -> "semantic vector similarity"
                else -> "lexical token overlap"
            },
            source = entry.source,
            sensitivity = entry.sensitivity,
            privacy = entry.privacy,
            expiresAtMillis = entry.expiresAtMillis,
            conflictKey = entry.conflictKey,
        )
}

/**
 * Reciprocal-rank fusion of the lexical and semantic rankings. Within each mode candidates are
 * ordered by score times [memoryTypeRankWeight]; ties share the better rank.
 */
private fun reciprocalRankFusion(candidates: List<RankingCandidate>): Map<String, Double> {
    val fused = candidates.associate { candidate -> candidate.entry.id to 0.0 }.toMutableMap()
    fun addRanks(score: (RankingCandidate) -> Float?) {
        val ranked = candidates
            .mapNotNull { candidate -> score(candidate)?.let { candidate to it * candidate.entry.type.memoryTypeRankWeight() } }
            .sortedByDescending { (_, weighted) -> weighted }
        var previous: Float? = null
        var rank = 0
        ranked.forEachIndexed { index, (candidate, weighted) ->
            if (weighted != previous) rank = index + 1
            previous = weighted
            fused[candidate.entry.id] = fused.getValue(candidate.entry.id) + 1.0 / (RRF_K + rank)
        }
    }
    addRanks { it.lexicalScore }
    addRanks { it.semanticScore }
    return fused
}

/** Explicit, user-stated records outrank incidental conversation snippets and task bookkeeping. */
internal fun MemoryRecordType.memoryTypeRankWeight(): Float =
    when (this) {
        MemoryRecordType.UserFact,
        MemoryRecordType.Preference -> EXPLICIT_RECORD_RANK_WEIGHT
        MemoryRecordType.Conversation,
        MemoryRecordType.TaskState,
        MemoryRecordType.SuppressedTaskState -> 1f
    }

/** RRF damping constant; 60 is the value from the original RRF paper. */
private const val RRF_K = 60.0
private const val EXPLICIT_RECORD_RANK_WEIGHT = 1.25f

/** Share of the lexical score taken from query coverage; the rest is exact term-vector cosine. */
private const val LEXICAL_COVERAGE_WEIGHT = 0.6f

/** Minimum share of the query's token weight a lexical hit must cover. */
internal const val MIN_LEXICAL_QUERY_COVERAGE = 0.2f

private fun MemoryEntry.toPersistedRecord(): PersistedMemoryRecord =
    PersistedMemoryRecord(
        id = id,
        type = type,
        text = text,
        source = source,
        sensitivity = sensitivity,
        privacy = privacy,
        expiresAtMillis = expiresAtMillis,
        conflictKey = conflictKey,
    )

private fun PersistedMemoryRecord.isExpired(nowMillis: Long): Boolean =
    expiresAtMillis?.let { expiresAt -> expiresAt <= nowMillis } == true

private fun PersistedMemoryRecord.isDeletionAuditable(nowMillis: Long): Boolean =
    !isExpired(nowMillis) &&
        (
            type == MemoryRecordType.Preference ||
                type == MemoryRecordType.UserFact ||
                type == MemoryRecordType.TaskState
            )

private fun PersistedMemoryRecord.toDeletionEvent(
    operation: MemoryDeletionOperation,
    deletedAtMillis: Long,
): MemoryDeletionEvent {
    val recordTextHash = memoryRecordTextHash(text)
    return MemoryDeletionEvent(
        id = deterministicMemoryRecordId(
            prefix = "memory-deletion",
            key = listOf(
                id,
                type.name,
                operation.name,
                deletedAtMillis.toString(),
                recordTextHash,
            ).joinToString(separator = "|"),
        ),
        recordId = id,
        recordType = type,
        operation = operation,
        recordTextHash = recordTextHash,
        recordSource = source,
        recordSensitivity = sensitivity,
        conflictKey = conflictKey,
        deletedAtMillis = deletedAtMillis,
    )
}

private fun MemoryHit.isExpired(nowMillis: Long): Boolean =
    expiresAtMillis?.let { expiresAt -> expiresAt <= nowMillis } == true

private fun MemoryEntry.isExpired(nowMillis: Long): Boolean =
    expiresAtMillis?.let { expiresAt -> expiresAt <= nowMillis } == true

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

private fun MemoryEntry.shouldSuppressTaskStateHitFor(normalizedQuery: String): Boolean {
    if (type != MemoryRecordType.TaskState || !normalizedQuery.containsAny(TASK_STATE_TERMINAL_QUERY_TERMS)) {
        return false
    }
    return !text.lowercase(Locale.ROOT).containsAny(TASK_STATE_TERMINAL_QUERY_TERMS)
}

private const val SEMANTIC_RUNTIME_PROBE_TEXT = "semantic memory runtime probe"
