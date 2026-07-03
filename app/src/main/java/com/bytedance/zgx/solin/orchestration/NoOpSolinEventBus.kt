package com.bytedance.zgx.solin.orchestration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.reflect.KClass

/**
 * No-op implementation of [SolinEventBus]. publish() is a no-op; subscribe() returns
 * [emptyFlow] (never emits, never completes); recent() returns [emptyList].
 *
 * For use in tests / previews where event wiring is irrelevant and we want to avoid
 * bringing up a real coroutine-bus.
 */
object NoOpSolinEventBus : SolinEventBus {
    override fun publish(event: SolinEvent) = Unit

    override fun <E : SolinEvent> subscribe(type: KClass<E>): Flow<E> = emptyFlow()

    override fun <E : SolinEvent> recent(type: KClass<out E>, limit: Int): List<E> = emptyList()
}
