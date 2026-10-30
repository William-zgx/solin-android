package com.bytedance.zgx.solin.presentation

/**
 * De-duplicates shared-intent ingestion by consumption key.
 *
 * A key is either in flight (bound to a generation number) or completed. A new ingestion for a
 * key that is in flight is rejected; a completed key is rejected unless the caller explicitly
 * allows re-consumption (a fresh `onNewIntent`). Only the generation that started an ingestion
 * may complete or clear it, so a stale reader can never mark a newer ingestion done. Thread-safe.
 */
internal class SharedIntentIngestionGate {
    private val lock = Any()
    private val completedKeys = mutableSetOf<String>()
    private val inFlightGenerations = mutableMapOf<String, Long>()
    private var nextGeneration = 0L

    /** Returns the generation for a newly started ingestion, or null when it must be skipped. */
    fun tryStart(consumptionKey: String, allowPreviouslyConsumed: Boolean): Long? =
        synchronized(lock) {
            if (consumptionKey in inFlightGenerations) return null
            if (!allowPreviouslyConsumed && consumptionKey in completedKeys) return null
            if (allowPreviouslyConsumed) completedKeys.remove(consumptionKey)
            (++nextGeneration).also { generation -> inFlightGenerations[consumptionKey] = generation }
        }

    fun isCurrent(consumptionKey: String, generation: Long): Boolean =
        synchronized(lock) { inFlightGenerations[consumptionKey] == generation }

    fun markCompleted(consumptionKey: String, generation: Long) {
        synchronized(lock) {
            if (inFlightGenerations[consumptionKey] != generation) return
            inFlightGenerations.remove(consumptionKey)
            completedKeys += consumptionKey
        }
    }

    fun clear(consumptionKey: String, generation: Long) {
        synchronized(lock) {
            if (inFlightGenerations[consumptionKey] == generation) {
                inFlightGenerations.remove(consumptionKey)
            }
        }
    }
}
