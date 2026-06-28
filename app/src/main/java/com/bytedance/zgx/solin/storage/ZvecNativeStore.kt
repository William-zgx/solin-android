package com.bytedance.zgx.solin.storage

class ZvecNativeStore private constructor(
    private var handle: Long,
) : AutoCloseable {
    private val lock = Any()

    val isOpen: Boolean
        get() = synchronized(lock) { handle != 0L }

    data class Record(
        val id: String,
        val documentId: String,
        val text: String,
        val vector: FloatArray,
        val metadataJson: String,
    ) {
        init {
            require(id.isNotBlank()) { "Zvec record id must not be blank" }
            require(documentId.isNotBlank()) { "Zvec record document id must not be blank" }
            require(vector.size == LocalVectorIndexContract.DIMENSIONS) {
                "Zvec native vector must have ${LocalVectorIndexContract.DIMENSIONS} dimensions"
            }
            require(metadataJson.isNotBlank()) { "Zvec record metadata must not be blank" }
        }
    }

    data class Hit(
        val record: Record,
        val score: Float,
    )

    fun upsert(record: Record): Boolean =
        withOpenHandle { nativeUpsert(it, record.id, record.documentId, record.text, record.vector, record.metadataJson) }

    fun fetch(id: String): Record? =
        withOpenHandle { nativeFetch(it, id) }

    fun query(vector: FloatArray, topK: Int): List<Hit> {
        require(vector.size == LocalVectorIndexContract.DIMENSIONS) {
            "Zvec native query vector must have ${LocalVectorIndexContract.DIMENSIONS} dimensions"
        }
        require(topK > 0) { "Zvec native query topK must be positive" }
        return withOpenHandle { nativeQuery(it, vector, topK).toList() }
    }

    fun delete(id: String): Boolean =
        withOpenHandle { nativeDelete(it, id) }

    fun flush(): Boolean =
        withOpenHandle { nativeFlush(it) }

    fun lastErrorCode(): Int =
        synchronized(lock) { nativeLastErrorCode() }

    override fun close() {
        synchronized(lock) {
            val current = handle
            if (current == 0L) return
            handle = 0L
            nativeClose(current)
        }
    }

    private inline fun <T> withOpenHandle(block: (Long) -> T): T {
        synchronized(lock) {
            val current = handle
            check(current != 0L) { "ZvecNativeStore is closed" }
            return block(current)
        }
    }

    companion object {
        private const val LAST_ERROR_CLOSED = 5

        init {
            System.loadLibrary("solin_zvec_bridge")
        }

        fun create(rootPath: String): ZvecNativeStore =
            ZvecNativeStore(nativeCreate(rootPath, LocalVectorIndexContract.DIMENSIONS))

        fun open(rootPath: String): ZvecNativeStore =
            ZvecNativeStore(nativeOpen(rootPath))

        @JvmStatic
        private external fun nativeCreate(rootPath: String, dimensions: Int): Long

        @JvmStatic
        private external fun nativeOpen(rootPath: String): Long

        @JvmStatic
        private external fun nativeUpsert(
            handle: Long,
            id: String,
            documentId: String,
            text: String,
            vector: FloatArray,
            metadataJson: String,
        ): Boolean

        @JvmStatic
        private external fun nativeFetch(handle: Long, id: String): Record?

        @JvmStatic
        private external fun nativeQuery(handle: Long, vector: FloatArray, topK: Int): Array<Hit>

        @JvmStatic
        private external fun nativeDelete(handle: Long, id: String): Boolean

        @JvmStatic
        private external fun nativeFlush(handle: Long): Boolean

        @JvmStatic
        private external fun nativeClose(handle: Long): Int

        @JvmStatic
        private external fun nativeLastErrorCode(): Int
    }
}
