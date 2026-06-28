package com.bytedance.zgx.solin.storage

import android.content.Context
import android.content.SharedPreferences
import com.bytedance.zgx.solin.MessagePrivacy
import java.io.File
import org.json.JSONObject

class SharedPreferencesLocalDocumentStore(
    context: Context,
    name: String = "solin_local_documents_v1",
) : LocalDocumentStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun upsert(document: LocalDocument): LocalDocument {
        prefs.edit()
            .putString(document.id, document.toJson().toString())
            .apply()
        return document
    }

    override fun get(id: String, readScope: LocalStorageReadScope): LocalDocument? =
        prefs.getString(id, null)
            ?.let(::documentFromJson)
            ?.takeIf { document -> document.privacy.isReadableFor(readScope) }

    override fun list(readScope: LocalStorageReadScope): List<LocalDocument> =
        prefs.all.values
            .asSequence()
            .filterIsInstance<String>()
            .mapNotNull(::documentFromJson)
            .filter { document -> document.privacy.isReadableFor(readScope) }
            .sortedWith(compareByDescending<LocalDocument> { it.updatedAtMillis }.thenBy { it.id })
            .toList()

    override fun delete(id: String): Boolean {
        val existed = prefs.contains(id)
        prefs.edit().remove(id).apply()
        return existed
    }

    private fun documentFromJson(value: String): LocalDocument? =
        runCatching {
            val json = JSONObject(value)
            LocalDocument(
                domain = json.optString("domain", "default"),
                id = json.getString("id"),
                ownerId = json.optString("ownerId", ""),
                title = json.optString("title", ""),
                text = json.optString("text", ""),
                sensitivity = json.optString("sensitivity", ""),
                type = json.optString("type", ""),
                sourceHash = json.optString("sourceHash", ""),
                payloadJson = json.optString("payloadJson", "{}"),
                searchText = json.optString("searchText", json.optString("text", "")),
                createdAtMillis = json.optLong("createdAtMillis", 0L),
                metadataJson = json.optString("metadataJson", "{}"),
                privacy = runCatching {
                    MessagePrivacy.valueOf(json.optString("privacy", MessagePrivacy.LocalOnly.name))
                }.getOrDefault(MessagePrivacy.LocalOnly),
                updatedAtMillis = json.optLong("updatedAtMillis", 0L),
                expiresAtMillis = json.optNullableLong("expiresAtMillis"),
                deletedAtMillis = json.optNullableLong("deletedAtMillis"),
                schemaVersion = json.optInt("schemaVersion", LocalStorageSchemaVersions.CURRENT),
            )
        }.getOrNull()

    private fun LocalDocument.toJson(): JSONObject =
        JSONObject()
            .put("domain", domain)
            .put("id", id)
            .put("ownerId", ownerId)
            .put("title", title)
            .put("text", text)
            .put("sensitivity", sensitivity)
            .put("type", type)
            .put("sourceHash", sourceHash)
            .put("payloadJson", payloadJson)
            .put("searchText", searchText)
            .put("createdAtMillis", createdAtMillis)
            .put("metadataJson", metadataJson)
            .put("privacy", privacy.name)
            .put("updatedAtMillis", updatedAtMillis)
            .putNullable("expiresAtMillis", expiresAtMillis)
            .putNullable("deletedAtMillis", deletedAtMillis)
            .put("schemaVersion", schemaVersion)
}

class ZvecNativeLocalVectorIndex(
    rootDir: File,
) : LocalVectorIndex {
    private val rootPath = rootDir.absolutePath
    private val nativeStore: ZvecNativeStore = openNativeStore(rootPath)

    override fun upsert(record: LocalVectorRecord): LocalVectorRecord {
        check(nativeStore.upsert(record.toNativeRecord())) {
            "zvec native upsert failed with code ${nativeStore.lastErrorCode()}"
        }
        return record.copy(vector = record.vector.copyOf())
    }

    override fun get(id: String, readScope: LocalStorageReadScope): LocalVectorRecord? =
        nativeStore
            .fetch(id)
            ?.toLocalVectorRecord()
            ?.takeIf { record -> record.privacy.isReadableFor(readScope) }

    override fun delete(id: String): Boolean =
        nativeStore.delete(id)

    override fun search(query: LocalVectorQuery): List<LocalVectorHit> =
        nativeStore
            .query(query.vector, query.topK)
            .mapNotNull { hit ->
                val record = hit.record.toLocalVectorRecord()
                    .takeIf { record -> record.privacy.isReadableFor(query.readScope) }
                    ?: return@mapNotNull null
                LocalVectorHit(
                    record = record,
                    score = hit.score,
                    cosineScore = hit.score,
                    lexicalScore = 0f,
                )
            }

    private fun openNativeStore(path: String): ZvecNativeStore {
        val opened = runCatching { ZvecNativeStore.open(path) }.getOrNull()
        if (opened != null && opened.isOpen) return opened
        opened?.close()
        val created = ZvecNativeStore.create(path)
        check(created.isOpen) {
            "zvec native create failed with code ${created.lastErrorCode()}"
        }
        return created
    }

    private fun LocalVectorRecord.toNativeRecord(): ZvecNativeStore.Record =
        ZvecNativeStore.Record(
            id = id,
            documentId = documentId,
            text = text,
            vector = vector.copyOf(),
            metadataJson = JSONObject(metadataJson)
                .put("domain", domain)
                .put("modelId", modelId)
                .put("sourceHash", sourceHash)
                .put("dimension", dimension)
                .put("type", type)
                .put("privacy", privacy.name)
                .put("updatedAtMillis", updatedAtMillis)
                .toString(),
        )

    private fun ZvecNativeStore.Record.toLocalVectorRecord(): LocalVectorRecord {
        val metadata = runCatching { JSONObject(metadataJson) }.getOrNull() ?: JSONObject()
        return LocalVectorRecord(
            domain = metadata.optString("domain", "default"),
            id = id,
            modelId = metadata.optString("modelId", ""),
            sourceHash = metadata.optString("sourceHash", ""),
            dimension = metadata.optInt("dimension", LocalVectorIndexContract.DIMENSIONS),
            type = metadata.optString("type", ""),
            documentId = documentId,
            text = text,
            vector = vector.copyOf(),
            metadataJson = metadataJson,
            privacy = runCatching {
                MessagePrivacy.valueOf(metadata.optString("privacy", MessagePrivacy.LocalOnly.name))
            }.getOrDefault(MessagePrivacy.LocalOnly),
            updatedAtMillis = metadata.optLong("updatedAtMillis", 0L),
        )
    }
}

private fun JSONObject.putNullable(key: String, value: Long?): JSONObject =
    put(key, value ?: JSONObject.NULL)

private fun JSONObject.optNullableLong(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
