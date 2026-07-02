package com.bytedance.zgx.solin.storage

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
