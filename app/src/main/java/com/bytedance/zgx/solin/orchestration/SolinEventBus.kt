package com.bytedance.zgx.solin.orchestration

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlin.reflect.KClass

interface SolinEventBus {
    fun publish(event: SolinEvent)
    fun <E : SolinEvent> subscribe(type: KClass<E>): Flow<E>
    fun <E : SolinEvent> recent(type: KClass<out E>, limit: Int = 50): List<E>
}

class DefaultSolinEventBus : SolinEventBus {
    /**
     * Durable bus: keeps a bounded replay cache for lifecycle / metric / audit / safety events.
     * New subscribers immediately receive recent history (up to [REPLAY_CACHE_SIZE] most recent).
     */
    private val durableBus = MutableSharedFlow<SolinEvent>(
        replay = REPLAY_CACHE_SIZE,
        extraBufferCapacity = EXTRA_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * High-frequency bus: zero replay, no backpressure, drops oldest on overflow.
     * Carries [SolinEvent.Agent.TextDelta] (per-token streaming) and any future event
     * declaring [SolinEvent.replay] == [SolinEvent.NO_REPLAY]. New subscribers see
     * only emissions after they subscribe; no history is replayed.
     */
    private val highFrequencyBus = MutableSharedFlow<SolinEvent>(
        replay = 0,
        extraBufferCapacity = HF_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun publish(event: SolinEvent) {
        // tryEmit never suspends; onBufferOverflow=DROP_OLDEST guarantees success under contention.
        if (event.replay == SolinEvent.NO_REPLAY) {
            highFrequencyBus.tryEmit(event)
        } else {
            durableBus.tryEmit(event)
        }
    }

    override fun <E : SolinEvent> subscribe(type: KClass<E>): Flow<E> {
        // Cold filtered view: each collector gets its own downstream; no shareIn, no caching.
        // Subscribers collect in their own coroutine scope (viewModelScope / applicationScope).
        val durable: Flow<SolinEvent> = durableBus
        val highFrequency: Flow<SolinEvent> = highFrequencyBus
        @Suppress("UNCHECKED_CAST")
        return merge(durable, highFrequency)
            .filter { type.isInstance(it) }
            .map { it as E }
    }

    override fun <E : SolinEvent> recent(type: KClass<out E>, limit: Int): List<E> {
        // High-frequency (TextDelta) events are deliberately excluded from history: they
        // have replay=0 and are not cached. Pull only from the durable replay cache.
        val snapshot: List<SolinEvent> = durableBus.replayCache
        val capped = limit.coerceAtLeast(0)
        if (capped == 0) return emptyList()
        @Suppress("UNCHECKED_CAST")
        return snapshot
            .filter { type.isInstance(it) }
            .takeLast(capped) as List<E>
    }

    companion object {
        private const val REPLAY_CACHE_SIZE = 200
        private const val EXTRA_BUFFER_CAPACITY = 512
        private const val HF_BUFFER_CAPACITY = 512
    }
}

/**
 * Marker-typed reified overloads for Kotlin callers.
 */
inline fun <reified E : SolinEvent> SolinEventBus.subscribe(): Flow<E> = subscribe(E::class)

inline fun <reified E : SolinEvent> SolinEventBus.recent(limit: Int = 50): List<E> = recent(E::class, limit)
