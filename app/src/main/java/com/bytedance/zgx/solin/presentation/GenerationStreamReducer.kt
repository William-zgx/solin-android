package com.bytedance.zgx.solin.presentation

data class GenerationStreamKey(
    val sessionId: String,
    val runId: String?,
    val generationToken: Long,
)

sealed interface GenerationStreamEvent {
    val key: GenerationStreamKey

    data class Started(override val key: GenerationStreamKey) : GenerationStreamEvent
    data class TextDelta(override val key: GenerationStreamKey, val text: String) : GenerationStreamEvent
    data class Completed(override val key: GenerationStreamKey) : GenerationStreamEvent
    data class Failed(override val key: GenerationStreamKey) : GenerationStreamEvent
    data class Cancelled(override val key: GenerationStreamKey) : GenerationStreamEvent
}

data class GenerationStreamState(
    val activeKey: GenerationStreamKey? = null,
    val text: String = "",
    val terminal: Boolean = false,
)

data class GenerationStreamReduction(
    val state: GenerationStreamState,
    val accepted: Boolean,
)

class GenerationStreamReducer {
    fun reduce(
        state: GenerationStreamState,
        event: GenerationStreamEvent,
    ): GenerationStreamReduction = when (event) {
        is GenerationStreamEvent.Started -> {
            val current = state.activeKey
            if (current != null && event.key.generationToken <= current.generationToken) {
                GenerationStreamReduction(state, accepted = false)
            } else {
                GenerationStreamReduction(
                    GenerationStreamState(activeKey = event.key),
                    accepted = true,
                )
            }
        }

        is GenerationStreamEvent.TextDelta -> state.acceptForActive(event.key) {
            copy(text = text + event.text)
        }

        is GenerationStreamEvent.Completed -> state.acceptForActive(event.key) { copy(terminal = true) }
        is GenerationStreamEvent.Failed -> state.acceptForActive(event.key) { copy(terminal = true) }
        is GenerationStreamEvent.Cancelled -> state.acceptForActive(event.key) { copy(terminal = true) }
    }

    private inline fun GenerationStreamState.acceptForActive(
        key: GenerationStreamKey,
        transform: GenerationStreamState.() -> GenerationStreamState,
    ): GenerationStreamReduction {
        if (activeKey != key || terminal) return GenerationStreamReduction(this, accepted = false)
        return GenerationStreamReduction(transform(), accepted = true)
    }
}

class GenerationStreamCoordinator(
    private val reducer: GenerationStreamReducer = GenerationStreamReducer(),
) {
    private var state = GenerationStreamState()
    private var nextToken = 0L

    @Synchronized
    fun start(sessionId: String, runId: String?): GenerationStreamKey {
        val key = GenerationStreamKey(sessionId, runId, ++nextToken)
        state = reducer.reduce(state, GenerationStreamEvent.Started(key)).state
        return key
    }

    @Synchronized
    fun acceptDelta(key: GenerationStreamKey, text: String): Boolean {
        val reduction = reducer.reduce(state, GenerationStreamEvent.TextDelta(key, text))
        state = reduction.state
        return reduction.accepted
    }

    @Synchronized
    fun complete(key: GenerationStreamKey): Boolean = terminate(GenerationStreamEvent.Completed(key))

    @Synchronized
    fun fail(key: GenerationStreamKey): Boolean = terminate(GenerationStreamEvent.Failed(key))

    @Synchronized
    fun cancel(key: GenerationStreamKey): Boolean = terminate(GenerationStreamEvent.Cancelled(key))

    @Synchronized
    fun isActive(key: GenerationStreamKey): Boolean =
        state.activeKey == key && !state.terminal

    @Synchronized
    fun snapshot(): GenerationStreamState = state

    private fun terminate(event: GenerationStreamEvent): Boolean {
        val reduction = reducer.reduce(state, event)
        state = reduction.state
        return reduction.accepted
    }
}
