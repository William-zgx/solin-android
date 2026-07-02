package com.bytedance.zgx.solin.storage

import com.bytedance.zgx.solin.MessagePrivacy
import org.json.JSONObject

object LocalStorageMigrationStateContract {
    const val NAMESPACE = "local_storage_migration_state"
    const val ROOM_MEMORY_TO_ZVEC_KEY = "room_memory_to_zvec"
}

enum class LocalStorageBackfillStatus {
    NotStarted,
    InProgress,
    Completed,
}

data class LocalStorageBackfillCursor(
    val domain: String,
    val id: String,
) : Comparable<LocalStorageBackfillCursor> {
    init {
        require(domain.isNotBlank()) { "Backfill cursor domain must not be blank" }
        require(id.isNotBlank()) { "Backfill cursor id must not be blank" }
    }

    override fun compareTo(other: LocalStorageBackfillCursor): Int {
        val domainCompare = domain.compareTo(other.domain)
        if (domainCompare != 0) return domainCompare
        return id.compareTo(other.id)
    }
}

data class LocalStorageBackfillState(
    val status: LocalStorageBackfillStatus = LocalStorageBackfillStatus.NotStarted,
    val lastDomain: String? = null,
    val lastId: String? = null,
    val updatedAtMillis: Long = 0L,
) {
    init {
        require(lastDomain == null || lastDomain.isNotBlank()) { "Backfill lastDomain must not be blank" }
        require(lastId == null || lastId.isNotBlank()) { "Backfill lastId must not be blank" }
        require((lastDomain == null) == (lastId == null)) {
            "Backfill cursor must include both lastDomain and lastId"
        }
    }

    val cursor: LocalStorageBackfillCursor?
        get() = if (lastDomain == null || lastId == null) {
            null
        } else {
            LocalStorageBackfillCursor(lastDomain, lastId)
        }
}

class LocalStorageMigrationStateStore(
    private val keyValues: LocalKeyValueStore,
    private val stateKey: String = LocalStorageMigrationStateContract.ROOM_MEMORY_TO_ZVEC_KEY,
) {
    fun load(): LocalStorageBackfillState {
        val entry = keyValues.get(
            namespace = LocalStorageMigrationStateContract.NAMESPACE,
            key = stateKey,
        ) ?: return LocalStorageBackfillState()
        return parseState(entry.valueJson)
    }

    fun save(state: LocalStorageBackfillState) {
        keyValues.upsert(
            LocalKeyValueEntry(
                namespace = LocalStorageMigrationStateContract.NAMESPACE,
                key = stateKey,
                valueJson = state.toJson(),
                privacy = MessagePrivacy.LocalOnly,
                updatedAtMillis = state.updatedAtMillis,
            ),
        )
    }

    private fun parseState(valueJson: String): LocalStorageBackfillState {
        val json = JSONObject(valueJson)
        val status = runCatching {
            LocalStorageBackfillStatus.valueOf(json.optString("status"))
        }.getOrElse {
            throw IllegalStateException("Invalid local storage migration status", it)
        }
        return LocalStorageBackfillState(
            status = status,
            lastDomain = json.optString("lastDomain").takeIf { it.isNotBlank() },
            lastId = json.optString("lastId").takeIf { it.isNotBlank() },
            updatedAtMillis = json.optLong("updatedAtMillis", 0L),
        )
    }

    private fun LocalStorageBackfillState.toJson(): String {
        val json = JSONObject()
            .put("status", status.name)
            .put("updatedAtMillis", updatedAtMillis)
        lastDomain?.let { json.put("lastDomain", it) }
        lastId?.let { json.put("lastId", it) }
        return json.toString()
    }
}

data class LocalStorageBackfillRecord(
    val domain: String,
    val id: String,
    val modelId: String = "",
    val sourceHash: String = "",
    val dimension: Int = LocalVectorIndexContract.DIMENSIONS,
    val type: String = "memory",
    val documentId: String,
    val text: String,
    val vector: FloatArray,
    val metadataJson: String = "{}",
    val privacy: MessagePrivacy = MessagePrivacy.LocalOnly,
    val updatedAtMillis: Long = 0L,
) {
    init {
        require(domain.isNotBlank()) { "Backfill record domain must not be blank" }
        require(id.isNotBlank()) { "Backfill record id must not be blank" }
        require(documentId.isNotBlank()) { "Backfill record document id must not be blank" }
        require(text.isNotBlank()) { "Backfill record text must not be blank" }
        require(dimension == LocalVectorIndexContract.DIMENSIONS) {
            "Backfill vector dimension must be ${LocalVectorIndexContract.DIMENSIONS}"
        }
        require(vector.size == dimension) {
            "Backfill vector must have $dimension dimensions but had ${vector.size}"
        }
    }

    val cursor: LocalStorageBackfillCursor
        get() = LocalStorageBackfillCursor(domain, id)

    fun toVectorRecord(): LocalVectorRecord =
        LocalVectorRecord(
            domain = domain,
            id = id,
            modelId = modelId,
            sourceHash = sourceHash,
            dimension = dimension,
            type = type,
            documentId = documentId,
            text = text,
            vector = vector.copyOf(),
            metadataJson = metadataJson,
            privacy = privacy,
            updatedAtMillis = updatedAtMillis,
        )
}

data class LocalStorageBackfillResult(
    val processed: Int,
    val state: LocalStorageBackfillState,
)

class LocalStorageRoomMemoryBackfill(
    records: List<LocalStorageBackfillRecord>,
    private val vectors: LocalVectorIndex,
    private val stateStore: LocalStorageMigrationStateStore,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val sortedRecords = records.sortedBy { it.cursor }

    fun runBatch(limit: Int): LocalStorageBackfillResult {
        require(limit > 0) { "Backfill limit must be positive" }
        val loadedState = stateStore.load()
        if (loadedState.status == LocalStorageBackfillStatus.Completed) {
            return LocalStorageBackfillResult(processed = 0, state = loadedState)
        }

        var state = loadedState
        val cursor = loadedState.cursor
        val records = sortedRecords
            .asSequence()
            .filter { record -> cursor == null || record.cursor > cursor }
            .take(limit)
            .toList()
        if (records.isEmpty()) {
            val completed = state.copy(
                status = LocalStorageBackfillStatus.Completed,
                updatedAtMillis = clockMillis(),
            )
            stateStore.save(completed)
            return LocalStorageBackfillResult(processed = 0, state = completed)
        }

        records.forEach { record ->
            vectors.upsert(record.toVectorRecord())
            state = LocalStorageBackfillState(
                status = LocalStorageBackfillStatus.InProgress,
                lastDomain = record.domain,
                lastId = record.id,
                updatedAtMillis = clockMillis(),
            )
            stateStore.save(state)
        }

        if (records.size < limit) {
            state = state.copy(
                status = LocalStorageBackfillStatus.Completed,
                updatedAtMillis = clockMillis(),
            )
            stateStore.save(state)
        }
        return LocalStorageBackfillResult(processed = records.size, state = state)
    }
}
