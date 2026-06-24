package com.bytedance.zgx.pocketmind.storage

import java.io.File
import java.util.Locale
import kotlin.math.sqrt

class InMemoryLocalStorageKernel(
    override val rootDir: File = File("in-memory-zvec"),
) : LocalStorageKernel {
    val keyValueStore = InMemoryLocalKeyValueStore()
    val documentStore = InMemoryLocalDocumentStore()
    val vectorIndex = InMemoryLocalVectorIndex()
    private var opened = false

    override fun open(): LocalStorageStatus {
        opened = true
        return status()
    }

    override fun healthCheck(): LocalStorageStatus = status()

    override fun flush() = Unit

    override fun close() {
        opened = false
    }

    private fun status(): LocalStorageStatus =
        LocalStorageStatus(
            health = if (opened) LocalStorageHealth.Ready else LocalStorageHealth.Unavailable,
            rootDir = rootDir,
            detail = if (opened) "in-memory local storage ready" else "local storage closed",
        )
}

class InMemoryLocalKeyValueStore : LocalKeyValueStore {
    private val entries = linkedMapOf<Pair<String, String>, LocalKeyValueEntry>()

    override fun get(namespace: String, key: String): LocalKeyValueEntry? =
        synchronized(entries) { entries[namespace to key] }

    override fun put(entry: LocalKeyValueEntry) {
        synchronized(entries) { entries[entry.namespace to entry.key] = entry }
    }

    override fun delete(namespace: String, key: String): Boolean =
        synchronized(entries) { entries.remove(namespace to key) != null }

    override fun entries(namespace: String): List<LocalKeyValueEntry> =
        synchronized(entries) {
            entries.values
                .filter { it.namespace == namespace }
                .sortedBy { it.key }
        }
}

class InMemoryLocalDocumentStore : LocalDocumentStore {
    private val documents = linkedMapOf<Pair<String, String>, LocalDocument>()

    override fun upsert(document: LocalDocument) {
        synchronized(documents) { documents[document.domain to document.id] = document }
    }

    override fun fetch(domain: String, id: String): LocalDocument? =
        synchronized(documents) { documents[domain to id]?.takeIf { it.deletedAtMillis == null } }

    override fun query(query: LocalDocumentQuery): List<LocalDocument> =
        synchronized(documents) {
            documents.values
                .asSequence()
                .filter { query.includeDeleted || it.deletedAtMillis == null }
                .filter { query.domain == null || it.domain == query.domain }
                .filter { query.ownerId == null || it.ownerId == query.ownerId }
                .filter { query.type == null || it.type == query.type }
                .filter { query.matchesText(it) }
                .sortedWith(compareByDescending<LocalDocument> { it.updatedAtMillis }.thenBy { it.id })
                .take(query.limit.coerceAtLeast(0))
                .toList()
        }

    override fun delete(domain: String, id: String, deletedAtMillis: Long): Boolean =
        synchronized(documents) {
            val current = documents[domain to id] ?: return@synchronized false
            documents[domain to id] = current.copy(
                deletedAtMillis = deletedAtMillis,
                updatedAtMillis = maxOf(current.updatedAtMillis, deletedAtMillis),
            )
            true
        }

    private fun LocalDocumentQuery.matchesText(document: LocalDocument): Boolean {
        val tokens = textQuery.tokens()
        if (tokens.isEmpty()) return true
        val haystack = "${document.searchText}\n${document.payloadJson}".lowercase(Locale.ROOT)
        return tokens.all { it in haystack }
    }
}

class InMemoryLocalVectorIndex : LocalVectorIndex {
    private val records = linkedMapOf<VectorKey, LocalVectorRecord>()

    override fun upsert(record: LocalVectorRecord) {
        synchronized(records) {
            records[VectorKey(record.domain, record.id, record.modelId)] = record.defensiveCopy()
        }
    }

    override fun fetch(domain: String, id: String, modelId: String): LocalVectorRecord? =
        synchronized(records) { records[VectorKey(domain, id, modelId)]?.defensiveCopy() }

    override fun query(query: LocalVectorQuery): List<LocalVectorHit> =
        synchronized(records) {
            // ponytail: linear scan, replace with zvec-backed ANN/FTS when native bridge is available.
            records.values
                .asSequence()
                .filter { query.domain == null || it.domain == query.domain }
                .filter { query.modelId == null || it.modelId == query.modelId }
                .filter { query.type == null || it.type == query.type }
                .map { LocalVectorHit(it.defensiveCopy(), cosine(query.embedding, it.embedding)) }
                .filter { it.score > 0f }
                .sortedByDescending { it.score }
                .take(query.topK.coerceAtLeast(0))
                .toList()
        }

    override fun delete(domain: String, id: String, modelId: String?): Boolean =
        synchronized(records) {
            if (modelId != null) {
                return@synchronized records.remove(VectorKey(domain, id, modelId)) != null
            }
            val keys = records.keys.filter { it.domain == domain && it.id == id }
            keys.forEach(records::remove)
            keys.isNotEmpty()
        }

    override fun deleteForModel(modelId: String, domain: String?): Int =
        synchronized(records) {
            val keys = records.keys.filter { it.modelId == modelId && (domain == null || it.domain == domain) }
            keys.forEach(records::remove)
            keys.size
        }

    override fun clear(domain: String?): Int =
        synchronized(records) {
            val keys = records.keys.filter { domain == null || it.domain == domain }
            keys.forEach(records::remove)
            keys.size
        }

    private data class VectorKey(
        val domain: String,
        val id: String,
        val modelId: String,
    )

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        var dot = 0f
        var leftNorm = 0f
        var rightNorm = 0f
        for (index in 0 until minOf(left.size, right.size)) {
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        val denom = sqrt(leftNorm.toDouble()).toFloat() * sqrt(rightNorm.toDouble()).toFloat()
        return if (denom > 0f) dot / denom else 0f
    }

    private fun LocalVectorRecord.defensiveCopy(): LocalVectorRecord =
        copy(embedding = embedding.copyOf())
}

private fun String?.tokens(): List<String> =
    orEmpty()
        .lowercase(Locale.ROOT)
        .split(Regex("\\s+"))
        .mapNotNull { it.trim().takeIf(String::isNotBlank) }
