package com.bytedance.zgx.solin.storage

import java.util.Locale
import kotlin.math.sqrt

class FakeLocalStorageKernel(
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val documents: LocalDocumentStore = FakeLocalDocumentStore(),
    private val keyValues: LocalKeyValueStore = FakeLocalKeyValueStore(),
    private val vectors: LocalVectorIndex = FakeLocalVectorIndex(),
) : LocalStorageKernel {
    private var opened = false
    private var lastFlushedAtMillis: Long? = null

    override fun health(): LocalStorageHealth =
        LocalStorageHealth(
            opened = opened,
            healthy = true,
            collectionNames = LocalStorageCollections.all,
            lastFlushedAtMillis = lastFlushedAtMillis,
        )

    override fun open(): LocalStorageSession {
        opened = true
        return LocalStorageSession(
            documents = documents,
            keyValues = keyValues,
            vectors = vectors,
        )
    }

    override fun flush() {
        lastFlushedAtMillis = clockMillis()
    }

    override fun close() {
        opened = false
    }
}

class FakeLocalDocumentStore : LocalDocumentStore {
    private val documents = linkedMapOf<String, LocalDocument>()

    override fun upsert(document: LocalDocument): LocalDocument {
        documents[document.id] = document
        return document
    }

    override fun get(id: String, readScope: LocalStorageReadScope): LocalDocument? =
        documents[id]?.takeIf { it.privacy.isReadableFor(readScope) }

    override fun list(readScope: LocalStorageReadScope): List<LocalDocument> =
        documents.values.filter { it.privacy.isReadableFor(readScope) }

    override fun delete(id: String): Boolean =
        documents.remove(id) != null
}

class FakeLocalKeyValueStore : LocalKeyValueStore {
    private val entries = linkedMapOf<Key, LocalKeyValueEntry>()

    override fun upsert(entry: LocalKeyValueEntry): LocalKeyValueEntry {
        entries[Key(entry.namespace, entry.key)] = entry
        return entry
    }

    override fun get(namespace: String, key: String, readScope: LocalStorageReadScope): LocalKeyValueEntry? =
        entries[Key(namespace, key)]?.takeIf { it.privacy.isReadableFor(readScope) }

    override fun list(namespace: String, readScope: LocalStorageReadScope): List<LocalKeyValueEntry> =
        entries.values.filter { it.namespace == namespace && it.privacy.isReadableFor(readScope) }

    override fun delete(namespace: String, key: String): Boolean =
        entries.remove(Key(namespace, key)) != null

    private data class Key(val namespace: String, val key: String)
}

class FakeLocalVectorIndex : LocalVectorIndex {
    private val records = linkedMapOf<String, LocalVectorRecord>()

    override fun upsert(record: LocalVectorRecord): LocalVectorRecord {
        requireExpectedDimensions(record.vector, "Vector record ${record.id}")
        val stored = record.copy(vector = record.vector.copyOf())
        records[record.id] = stored
        return stored.copy(vector = stored.vector.copyOf())
    }

    override fun get(id: String, readScope: LocalStorageReadScope): LocalVectorRecord? =
        records[id]
            ?.takeIf { it.privacy.isReadableFor(readScope) }
            ?.copyVector()

    override fun delete(id: String): Boolean =
        records.remove(id) != null

    override fun search(query: LocalVectorQuery): List<LocalVectorHit> {
        requireExpectedDimensions(query.vector, "Vector query")
        val queryTokens = tokenize(query.text)
        return records.values
            .asSequence()
            .filter { it.privacy.isReadableFor(query.readScope) }
            .map { record ->
                val cosineScore = cosine(query.vector, record.vector)
                val lexicalScore = lexicalScore(queryTokens, tokenize(record.text))
                LocalVectorHit(
                    record = record.copyVector(),
                    score = (cosineScore * COSINE_WEIGHT) + (lexicalScore * LEXICAL_WEIGHT),
                    cosineScore = cosineScore,
                    lexicalScore = lexicalScore,
                )
            }
            .filter { it.score > 0f }
            .sortedWith(
                compareByDescending<LocalVectorHit> { it.score }
                    .thenByDescending { it.cosineScore }
                    .thenBy { it.record.id },
            )
            .take(query.topK)
            .toList()
    }

    private fun LocalVectorRecord.copyVector(): LocalVectorRecord =
        copy(vector = vector.copyOf())

    private fun requireExpectedDimensions(vector: FloatArray, label: String) {
        require(vector.size == dimensions) {
            "$label must have $dimensions dimensions but had ${vector.size}"
        }
    }

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            val leftValue = left[index].toDouble()
            val rightValue = right[index].toDouble()
            dot += leftValue * rightValue
            leftNorm += leftValue * leftValue
            rightNorm += rightValue * rightValue
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0f
        return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).toFloat()
    }

    private fun lexicalScore(queryTokens: Set<String>, recordTokens: Set<String>): Float {
        if (queryTokens.isEmpty() || recordTokens.isEmpty()) return 0f
        return queryTokens.count { it in recordTokens }.toFloat() / queryTokens.size.toFloat()
    }

    private fun tokenize(text: String): Set<String> =
        TOKEN_REGEX.findAll(text.lowercase(Locale.ROOT))
            .map { it.value }
            .toSet()

    private companion object {
        private const val COSINE_WEIGHT = 0.7f
        private const val LEXICAL_WEIGHT = 0.3f
        private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}_]+")
    }
}
