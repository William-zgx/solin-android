package com.bytedance.zgx.solin.memory

import android.content.Context
import com.bytedance.zgx.solin.common.sha256Hex
import com.bytedance.zgx.solin.storage.LocalVectorIndexContract
import com.bytedance.zgx.solin.storage.LocalDocument
import com.bytedance.zgx.solin.storage.SharedPreferencesLocalDocumentStore
import com.bytedance.zgx.solin.storage.ZvecNativeLocalVectorIndex
import java.io.File
import org.json.JSONObject

internal class LegacyMemoryStorageRetirement(
    context: Context,
    private val records: RoomMemoryRecordStore,
    private val embeddings: RoomMemoryEmbeddingStore,
    private val deletionEvents: RoomMemoryDeletionEventStore,
) {
    private val appContext = context.applicationContext
    private val legacyDocuments = SharedPreferencesLocalDocumentStore(appContext)
    private val legacyVectorsRoot = appContext.noBackupFilesDir
        .resolve("solin-zvec")

    fun retireAfterSecureImport() {
        val deletedRecordIds = deletionEvents.events()
            .mapTo(mutableSetOf()) { event -> event.recordId }
        legacyDocuments.validateForSecureMigration()
        val existingRecordIds = records.records()
            .mapTo(mutableSetOf()) { record -> record.id }
        val legacyRecords = legacyDocuments.list()
            .mapNotNull { document -> document.toLegacyMemoryRecordOrNull() }
            .filterNot { record -> record.id in deletedRecordIds }

        val existingById = records.records().associateBy { record -> record.id }
        legacyRecords.forEach { record ->
            val existing = existingById[record.id] ?: return@forEach
            check(record.contentHash() == existing.contentHash()) {
                "Legacy plaintext memory conflicts with encrypted Room record ${record.id}."
            }
        }
        legacyRecords
            .filterNot { record -> record.id in existingRecordIds }
            .forEach(records::upsert)

        val importedRecordIds = records.records()
            .mapTo(mutableSetOf()) { record -> record.id }
        check(legacyRecords.all { record -> record.id in importedRecordIds }) {
            "Failed to import all legacy plaintext memory records into encrypted Room storage."
        }

        importLegacyEmbeddings(deletedRecordIds)
        check(legacyDocuments.clearForSecureMigration()) {
            "Failed to clear legacy plaintext memory documents."
        }
        deleteRecursively(legacyVectorsRoot)
    }

    private fun importLegacyEmbeddings(deletedRecordIds: Set<String>) {
        val vectorRoot = legacyVectorsRoot.resolve("v1").resolve("pm_vectors_v1")
        val embeddingImports = retainedLegacyEmbeddingImports(
            documents = legacyDocuments.list(),
            existingRecordIds = records.records().mapTo(mutableSetOf()) { record -> record.id },
            deletedRecordIds = deletedRecordIds,
        )
        if (embeddingImports.isEmpty()) return
        check(vectorRoot.exists()) {
            "Legacy plaintext embedding metadata exists without a vector index."
        }
        ZvecNativeLocalVectorIndex(vectorRoot).use { vectors ->
            embeddingImports.forEach { embedding ->
                val document = embedding.document
                val vector = requireNotNull(vectors.get(document.id)) {
                    "Legacy plaintext vector is missing for ${document.id}."
                }
                check(vector.dimension == LocalVectorIndexContract.DIMENSIONS) {
                    "Legacy plaintext embedding dimension is invalid for ${document.id}."
                }
                check(vector.vector.all(Float::isFinite)) {
                    "Legacy plaintext embedding contains a non-finite value for ${document.id}."
                }
                embeddings.upsert(
                    PersistedMemoryEmbedding(
                        recordId = embedding.recordId,
                        modelId = embedding.modelId,
                        sourceHash = document.sourceHash,
                        dimension = vector.dimension,
                        vector = vector.vector,
                        updatedAtMillis = document.updatedAtMillis,
                    ),
                )
                val imported = embeddings.embedding(embedding.recordId, embedding.modelId)
                check(
                    imported != null &&
                        imported.sourceHash == document.sourceHash &&
                        imported.dimension == vector.dimension &&
                        imported.vector.contentEquals(vector.vector),
                ) {
                    "Failed to import legacy plaintext embedding for ${document.id}."
                }
            }
        }
    }

    private fun deleteRecursively(root: File) {
        if (!root.exists()) return
        check(root.canonicalFile.path.startsWith(appContext.noBackupFilesDir.canonicalFile.path)) {
            "Refusing to delete a legacy memory path outside noBackupFilesDir."
        }
        check(root.deleteRecursively()) {
            "Failed to remove legacy plaintext vector storage."
        }
    }

    private fun PersistedMemoryRecord.contentHash(): String =
        sha256Hex(
            listOf(
                id,
                type.name,
                text,
                source.name,
                sensitivity.name,
                privacy.name,
                expiresAtMillis?.toString().orEmpty(),
                conflictKey.orEmpty(),
            ).joinToString(separator = "\u0000"),
        )
}

/** Parses a plaintext record written by the retired zvec/SharedPreferences memory store. */
internal fun LocalDocument.toLegacyMemoryRecordOrNull(): PersistedMemoryRecord? {
    if (domain != LEGACY_MEMORY_DOMAIN || deletedAtMillis != null) return null
    val recordType = runCatching { MemoryRecordType.valueOf(type) }.getOrNull() ?: return null
    val metadata = runCatching { JSONObject(metadataJson) }.getOrNull()
    return PersistedMemoryRecord(
        id = id,
        type = recordType,
        text = text,
        source = metadata?.optString(LEGACY_MEMORY_SOURCE_KEY)
            ?.let { source -> runCatching { MemoryRecordSource.valueOf(source) }.getOrNull() }
            ?: MemoryRecordSource.LegacyImport,
        sensitivity = runCatching { MemoryRecordSensitivity.valueOf(sensitivity) }
            .getOrDefault(MemoryRecordSensitivity.Normal),
        privacy = privacy,
        expiresAtMillis = expiresAtMillis,
        conflictKey = metadata?.optString(LEGACY_MEMORY_CONFLICT_KEY)?.takeIf { it.isNotBlank() },
    )
}

internal fun retainedLegacyEmbeddingImports(
    documents: List<LocalDocument>,
    existingRecordIds: Set<String>,
    deletedRecordIds: Set<String>,
): List<LegacyEmbeddingImport> =
    documents.mapNotNull { document ->
        document.toLegacyEmbeddingImportOrNull(
            existingRecordIds = existingRecordIds,
            deletedRecordIds = deletedRecordIds,
        )
    }

internal data class LegacyEmbeddingImport(
    val document: LocalDocument,
    val recordId: String,
    val modelId: String,
)

private fun LocalDocument.toLegacyEmbeddingImportOrNull(
    existingRecordIds: Set<String>,
    deletedRecordIds: Set<String>,
): LegacyEmbeddingImport? {
    if (
        domain != LEGACY_MEMORY_DOMAIN ||
        type != LEGACY_MEMORY_EMBEDDING_TYPE ||
        deletedAtMillis != null
    ) {
        return null
    }
    val metadata = metadataJson.takeIf { ownerId.isBlank() || title.isBlank() }
        ?.let { value -> runCatching { JSONObject(value) }.getOrNull() }
    val recordId = ownerId.ifBlank {
        metadata?.optString(LEGACY_MEMORY_EMBEDDING_RECORD_ID_KEY).orEmpty()
    }
    if (recordId !in existingRecordIds || recordId in deletedRecordIds) {
        return null
    }
    val modelId = title.ifBlank {
        metadata?.optString(LEGACY_MEMORY_EMBEDDING_MODEL_ID_KEY).orEmpty()
    }
    check(modelId.isNotBlank()) {
        "Legacy plaintext embedding metadata is invalid for $id."
    }
    return LegacyEmbeddingImport(
        document = this,
        recordId = recordId,
        modelId = modelId,
    )
}

private const val LEGACY_MEMORY_DOMAIN = "memory"
private const val LEGACY_MEMORY_EMBEDDING_TYPE = "MemoryEmbedding"
private const val LEGACY_MEMORY_EMBEDDING_RECORD_ID_KEY = "recordId"
private const val LEGACY_MEMORY_EMBEDDING_MODEL_ID_KEY = "modelId"
private const val LEGACY_MEMORY_SOURCE_KEY = "source"
private const val LEGACY_MEMORY_CONFLICT_KEY = "conflictKey"
