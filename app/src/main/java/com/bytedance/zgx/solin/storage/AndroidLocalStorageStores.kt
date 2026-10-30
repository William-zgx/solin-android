package com.bytedance.zgx.solin.storage

import android.content.Context
import android.content.SharedPreferences
import com.bytedance.zgx.solin.MessagePrivacy
import java.io.File
import org.json.JSONObject

/** Reader for the retired plaintext memory documents kept in SharedPreferences. */
class SharedPreferencesLocalDocumentStore(
    context: Context,
    name: String = "solin_local_documents_v1",
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** Every parseable document, newest first; unparseable entries are skipped. */
    fun list(): List<LocalDocument> =
        prefs.all.values
            .asSequence()
            .filterIsInstance<String>()
            .mapNotNull { value -> runCatching { documentFromJsonStrict(value) }.getOrNull() }
            .sortedWith(compareByDescending<LocalDocument> { it.updatedAtMillis }.thenBy { it.id })
            .toList()

    /** Throws if any stored document is malformed, so retirement never silently drops data. */
    internal fun validateForSecureMigration() {
        prefs.all.values
            .filterIsInstance<String>()
            .forEach(::documentFromJsonStrict)
    }

    internal fun clearForSecureMigration(): Boolean =
        prefs.edit().clear().commit() && prefs.all.isEmpty()

    private fun documentFromJsonStrict(value: String): LocalDocument {
        val json = JSONObject(value)
        return LocalDocument(
            domain = json.optString("domain", "default"),
            id = json.getString("id"),
            ownerId = json.optString("ownerId", ""),
            title = json.optString("title", ""),
            text = json.optString("text", ""),
            sensitivity = json.optString("sensitivity", ""),
            type = json.optString("type", ""),
            sourceHash = json.optString("sourceHash", ""),
            metadataJson = json.optString("metadataJson", "{}"),
            privacy = MessagePrivacy.valueOf(json.optString("privacy", MessagePrivacy.LocalOnly.name)),
            updatedAtMillis = json.optLong("updatedAtMillis", 0L),
            expiresAtMillis = json.optNullableLong("expiresAtMillis"),
            deletedAtMillis = json.optNullableLong("deletedAtMillis"),
        )
    }
}

/** Read-only view of the retired zvec vector index under [rootDir]. */
class ZvecNativeLocalVectorIndex(
    rootDir: File,
) : AutoCloseable {
    private val nativeStore: ZvecNativeStore = openNativeStore(rootDir.absolutePath)

    fun get(id: String): LegacyVector? =
        nativeStore.fetch(id)?.let { record ->
            val metadata = runCatching { JSONObject(record.metadataJson) }.getOrNull() ?: JSONObject()
            require(metadata.optString("domain", "default").isNotBlank()) { "Vector domain must not be blank" }
            LegacyVector(
                dimension = metadata.optInt("dimension", LocalVectorIndexContract.DIMENSIONS),
                vector = record.vector.copyOf(),
            )
        }

    override fun close() {
        nativeStore.close()
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
}

private fun JSONObject.optNullableLong(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
