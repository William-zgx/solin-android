package com.bytedance.zgx.solin.storage

internal interface ZvecNativeAdapter {
    val documents: LocalDocumentStore
    val keyValues: LocalKeyValueStore
    val vectors: LocalVectorIndex

    fun open()
    fun flush()
    fun close()
}

@ExperimentalLocalStorageFake
internal class FakeZvecNativeAdapter(
    override val documents: LocalDocumentStore = FakeLocalDocumentStore(),
    override val keyValues: LocalKeyValueStore = FakeLocalKeyValueStore(),
    override val vectors: LocalVectorIndex = FakeLocalVectorIndex(),
) : ZvecNativeAdapter {
    var opened: Boolean = false
        private set
    var flushed: Boolean = false
        private set

    override fun open() {
        opened = true
    }

    override fun flush() {
        flushed = true
    }

    override fun close() {
        opened = false
    }
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

@ExperimentalLocalStorageFake
fun fakeZvecLocalStorageKernel(
    clockMillis: () -> Long = { System.currentTimeMillis() },
): LocalStorageKernel =
    ZvecLocalStorageKernel(
        adapter = FakeZvecNativeAdapter(),
        clockMillis = clockMillis,
    )
