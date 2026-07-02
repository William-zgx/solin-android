package com.bytedance.zgx.solin.storage

internal interface ZvecNativeAdapter {
    val documents: LocalDocumentStore
    val keyValues: LocalKeyValueStore
    val vectors: LocalVectorIndex

    fun open()
    fun flush()
    fun close()
}

internal class ZvecLocalStorageKernel(
    private val adapter: ZvecNativeAdapter,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
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
        adapter.open()
        opened = true
        return LocalStorageSession(
            documents = adapter.documents,
            keyValues = adapter.keyValues,
            vectors = adapter.vectors,
        )
    }

    override fun flush() {
        adapter.flush()
        lastFlushedAtMillis = clockMillis()
    }

    override fun close() {
        adapter.close()
        opened = false
    }
}
