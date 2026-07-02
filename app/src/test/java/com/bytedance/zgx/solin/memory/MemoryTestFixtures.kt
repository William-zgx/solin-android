package com.bytedance.zgx.solin.memory

class FakeMemoryRecordStore(
    private val failure: Throwable? = null,
) : MemoryRecordStore {
    private val records = linkedMapOf<String, PersistedMemoryRecord>()

    override fun records(): List<PersistedMemoryRecord> {
        failure?.let { throw it }
        return records.values.toList()
    }

    override fun upsert(record: PersistedMemoryRecord) {
        failure?.let { throw it }
        records[record.id] = record
    }

    override fun delete(id: String): Boolean {
        failure?.let { throw it }
        return records.remove(id) != null
    }

    override fun clear() {
        failure?.let { throw it }
        records.clear()
    }
}

class FakeMemoryDeletionEventStore : MemoryDeletionEventStore {
    val events = mutableListOf<MemoryDeletionEvent>()

    override fun events(): List<MemoryDeletionEvent> =
        events.toList()

    override fun append(event: MemoryDeletionEvent) {
        events += event
    }
}
