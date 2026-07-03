package com.bytedance.zgx.solin.orchestration

import java.util.concurrent.ConcurrentHashMap

/**
 * Seam for publishing incremental progress during tool execution. Wave 2 only requires the
 * lifecycle hooks [start]/[complete]/[fail]; the richer [step]/[bytes]/[log]/[intermediate]/[update]
 * methods are forward-compatibility for later waves that wire per-tool intermediate progress.
 *
 * Implementations MUST be thread-safe: tools call these methods from arbitrary coroutine contexts.
 */
interface ToolProgressPublisher {
    fun start(toolCallId: String, toolName: String, description: String? = null)
    fun update(update: ProgressUpdate)
    fun step(
        toolCallId: String,
        toolName: String,
        completed: Int,
        total: Int? = null,
        description: String? = null,
    )
    fun bytes(
        toolCallId: String,
        toolName: String,
        bytesRead: Long,
        totalBytes: Long? = null,
    )
    fun log(toolCallId: String, toolName: String, line: String)
    fun intermediate(toolCallId: String, toolName: String, preview: String)
    fun complete(toolCallId: String, toolName: String)
    fun fail(toolCallId: String, toolName: String, error: String)
}

/**
 * Incremental progress publisher for tools executing within a single agent run.
 *
 * Tools call [start]/[step]/[bytes]/[log]/[intermediate]/[complete]/[fail] (or the generic
 * [update]) from any thread or coroutine; this class collapses those calls into throttled
 * [SolinEvent.Agent.ToolProgress] events on [eventBus]. The publish rate is capped at
 * [THROTTLE_WINDOW_MS] per `toolCallId`; when throttled, only the most recent state for
 * that call is retained (coalesced "latest wins"). Terminal events ([ProgressUpdate.Completed],
 * [ProgressUpdate.Failed]) always bypass the throttle and are emitted immediately, as does
 * the initial [start] event.
 *
 * The publisher owns no coroutine scope and does not register as a bus subscriber; it is a
 * publisher-only helper. Throttling is implemented with a plain [ConcurrentHashMap] of
 * per-call slots tracking last-publish wall-clock time plus a coalesced "latest pending"
 * event — no channel / dispatcher loop is used for Wave 2.
 *
 * Mapping from [ProgressUpdate] to [SolinEvent.Agent.ToolProgress] fields:
 *  - [ProgressUpdate.Step]               -> progress = completed/total (0f..1f) when total is
 *                                           known, else -1f (indeterminate); partialText
 *                                           carries a short "completed/total description".
 *  - [ProgressUpdate.BytesTransferred]   -> progress = bytesRead/totalBytes when known;
 *                                           partialText is a human-readable transfer line.
 *  - [ProgressUpdate.LogLine]            -> progress = -1f; partialText = line.
 *  - [ProgressUpdate.IntermediateResult] -> progress = -1f; partialText = preview.
 *  - [ProgressUpdate.Started]            -> progress = 0f; partialText = description.
 *  - [ProgressUpdate.Completed]          -> progress = 1f; partialText = "completed".
 *  - [ProgressUpdate.Failed]             -> progress = -1f; partialText = "error: $error".
 *
 * Construction requires a [runId] so every emitted event can be correlated to the active
 * agent run (this matches the non-null `runId` field on [SolinEvent.Agent.ToolProgress] and
 * [SolinEvent.Agent.ToolStarted]).
 *
 * @property runId     Agent run id stamped onto every emitted event.
 * @property eventBus  Bus to publish [SolinEvent.Agent.ToolStarted] and
 *                     [SolinEvent.Agent.ToolProgress] events to.
 * @property clock     Wall-clock source; injectable for tests. Defaults to
 *                     [System.currentTimeMillis].
 */
class DefaultToolProgressPublisher(
    val runId: String = "",
    private val eventBus: SolinEventBus,
    private val clock: () -> Long = System::currentTimeMillis,
) : ToolProgressPublisher {

    /** Per-tool-call throttle slot. All mutable state is [@Volatile] or synchronized on the slot. */
    private class Slot {
        @Volatile var lastPublishMillis: Long = 0L
        @Volatile var started: Boolean = false
        @Volatile var latest: SolinEvent.Agent.ToolProgress? = null

        /** CAS [lastPublishMillis] from [expected] to [update]; returns true on success. */
        fun casLastPublish(expected: Long, update: Long): Boolean = synchronized(this) {
            if (lastPublishMillis == expected) {
                lastPublishMillis = update
                true
            } else {
                false
            }
        }
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    // -------- Public API (convenience methods) --------

    /**
     * Mark a tool call as started. Publishes [SolinEvent.Agent.ToolStarted] at most once per
     * [toolCallId], then immediately emits an initial [SolinEvent.Agent.ToolProgress] with
     * progress=0. The initial progress event is NOT throttled.
     */
    override fun start(toolCallId: String, toolName: String, description: String?) {
        val slot = slotFor(toolCallId)
        synchronized(slot) {
            if (slot.started) return
            slot.started = true
        }
        eventBus.publish(
            SolinEvent.Agent.ToolStarted(
                runId = runId,
                toolCallId = toolCallId,
                toolName = toolName,
                occurredAtMillis = clock(),
            ),
        )
        emit(
            toolCallId = toolCallId,
            event = SolinEvent.Agent.ToolProgress(
                runId = runId,
                toolCallId = toolCallId,
                partialText = description,
                progress = 0f,
                occurredAtMillis = clock(),
            ),
            force = true,
        )
    }

    /** Generic entry point: dispatches [update] to the appropriate typed method. */
    override fun update(update: ProgressUpdate) {
        when (update) {
            is ProgressUpdate.Started -> start(
                toolCallId = update.toolCallId,
                toolName = update.toolName,
                description = update.description,
            )
            is ProgressUpdate.Step -> step(
                toolCallId = update.toolCallId,
                toolName = update.toolName,
                completed = update.completed,
                total = update.total,
                description = update.description,
            )
            is ProgressUpdate.BytesTransferred -> bytes(
                toolCallId = update.toolCallId,
                toolName = update.toolName,
                bytesRead = update.bytesRead,
                totalBytes = update.totalBytes,
            )
            is ProgressUpdate.LogLine -> log(
                toolCallId = update.toolCallId,
                toolName = update.toolName,
                line = update.line,
            )
            is ProgressUpdate.IntermediateResult -> intermediate(
                toolCallId = update.toolCallId,
                toolName = update.toolName,
                preview = update.preview,
            )
            is ProgressUpdate.Completed -> complete(
                toolCallId = update.toolCallId,
                toolName = update.toolName,
            )
            is ProgressUpdate.Failed -> fail(
                toolCallId = update.toolCallId,
                toolName = update.toolName,
                error = update.error,
            )
        }
    }

    override fun step(
        toolCallId: String,
        toolName: String,
        completed: Int,
        total: Int?,
        description: String?,
    ) {
        val progress: Float = if (total != null && total > 0) {
            (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else {
            INDETERMINATE_PROGRESS
        }
        val partial = buildString {
            append(completed)
            if (total != null) append('/').append(total)
            if (description != null) append(' ').append(description)
        }
        publishThrottled(
            SolinEvent.Agent.ToolProgress(
                runId = runId,
                toolCallId = toolCallId,
                partialText = partial,
                progress = progress,
                occurredAtMillis = clock(),
            ),
        )
    }

    override fun bytes(
        toolCallId: String,
        toolName: String,
        bytesRead: Long,
        totalBytes: Long?,
    ) {
        val progress: Float = if (totalBytes != null && totalBytes > 0L) {
            (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            INDETERMINATE_PROGRESS
        }
        val partial = buildString {
            append(formatBytes(bytesRead))
            if (totalBytes != null) append(" / ").append(formatBytes(totalBytes))
        }
        publishThrottled(
            SolinEvent.Agent.ToolProgress(
                runId = runId,
                toolCallId = toolCallId,
                partialText = partial,
                progress = progress,
                occurredAtMillis = clock(),
            ),
        )
    }

    override fun log(toolCallId: String, toolName: String, line: String) {
        publishThrottled(
            SolinEvent.Agent.ToolProgress(
                runId = runId,
                toolCallId = toolCallId,
                partialText = line,
                progress = INDETERMINATE_PROGRESS,
                occurredAtMillis = clock(),
            ),
        )
    }

    override fun intermediate(toolCallId: String, toolName: String, preview: String) {
        publishThrottled(
            SolinEvent.Agent.ToolProgress(
                runId = runId,
                toolCallId = toolCallId,
                partialText = preview,
                progress = INDETERMINATE_PROGRESS,
                occurredAtMillis = clock(),
            ),
        )
    }

    /**
     * Terminal success. Always emitted immediately (throttle bypassed); the per-call slot is
     * removed afterwards so a later reuse of the same [toolCallId] is treated as a fresh call.
     */
    override fun complete(toolCallId: String, toolName: String) {
        emit(
            toolCallId = toolCallId,
            event = SolinEvent.Agent.ToolProgress(
                runId = runId,
                toolCallId = toolCallId,
                partialText = "completed",
                progress = 1f,
                occurredAtMillis = clock(),
            ),
            force = true,
        )
        slots.remove(toolCallId)
    }

    /**
     * Terminal failure. Always emitted immediately (throttle bypassed); the per-call slot is
     * removed afterwards.
     */
    override fun fail(toolCallId: String, toolName: String, error: String) {
        emit(
            toolCallId = toolCallId,
            event = SolinEvent.Agent.ToolProgress(
                runId = runId,
                toolCallId = toolCallId,
                partialText = "error: $error",
                progress = INDETERMINATE_PROGRESS,
                occurredAtMillis = clock(),
            ),
            force = true,
        )
        slots.remove(toolCallId)
    }

    // -------- Internals --------

    /**
     * Publish [event] to [eventBus] and update the slot's last-published state. Called for
     * forced publishes (start / throttle winner / complete / fail). When [force] is true the
     * slot's [Slot.lastPublishMillis] is refreshed so the throttle window resets after the
     * forced publish.
     */
    private fun emit(
        toolCallId: String,
        event: SolinEvent.Agent.ToolProgress,
        force: Boolean,
    ) {
        eventBus.publish(event)
        val slot = slots[toolCallId] ?: return
        synchronized(slot) {
            if (force) {
                slot.lastPublishMillis = event.occurredAtMillis
            }
            // The event we just published is at least as new as anything coalesced: clear.
            slot.latest = null
        }
    }

    /**
     * Publish a non-terminal progress event subject to the per-call throttle window.
     *
     * If at least [THROTTLE_WINDOW_MS] has elapsed since the last publish for this
     * [toolCallId] (read from the event), the calling thread wins a CAS on the slot and
     * publishes immediately. Otherwise — or if the CAS is lost to a concurrent winner —
     * the event is stored as the latest coalesced state (overwriting any previously-pending
     * update) and publish is deferred. Wave 2 deliberately avoids a scheduled drain loop:
     * the next call arriving outside the window, or the terminal [complete]/[fail], will
     * carry the freshest value forward.
     *
     * Races at the throttle boundary are resolved by [Slot.casLastPublish] so exactly one
     * thread publishes; losers coalesce.
     */
    private fun publishThrottled(event: SolinEvent.Agent.ToolProgress) {
        val slot = slotFor(event.toolCallId)
        val now = event.occurredAtMillis
        val last = slot.lastPublishMillis
        if (now - last >= THROTTLE_WINDOW_MS) {
            if (slot.casLastPublish(last, now)) {
                eventBus.publish(event)
                synchronized(slot) { slot.latest = null }
                return
            }
        }
        // Within throttle window or lost CAS race: record latest coalesced state.
        synchronized(slot) { slot.latest = event }
    }

    private fun slotFor(toolCallId: String): Slot = slots.getOrPut(toolCallId) { Slot() }

    companion object {
        /** Minimum wall-clock interval between non-terminal progress publishes per tool call. */
        const val THROTTLE_WINDOW_MS: Long = 100L

        /** Sentinel used for [SolinEvent.Agent.ToolProgress.progress] when progress is indeterminate. */
        private const val INDETERMINATE_PROGRESS: Float = -1f

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000L -> String.format("%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000L -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000L -> String.format("%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}

// Backwards-compatible type alias: Wave 1 code that referred to the class as `ToolProgressPublisher`
// continues to compile; the interface now owns that name.
@Deprecated(
    message = "ToolProgressPublisher is now an interface; use DefaultToolProgressPublisher for the bus-backed implementation.",
    replaceWith = ReplaceWith("DefaultToolProgressPublisher", "com.bytedance.zgx.solin.orchestration.DefaultToolProgressPublisher"),
    level = DeprecationLevel.HIDDEN,
)
typealias ToolProgressPublisherClass = DefaultToolProgressPublisher

// ---------------------------------------------------------------------------
// Progress update sealed hierarchy
// ---------------------------------------------------------------------------

/**
 * Incremental-progress update shape that tools feed into [ToolProgressPublisher.update].
 * Each subtype represents a distinct kind of progress signal; the publisher maps the richer
 * typed fields onto the compact [SolinEvent.Agent.ToolProgress] event that crosses the bus.
 *
 * All subtypes carry [toolCallId] + [toolName] so the publisher does not need ambient
 * tracking of which tool is "current"; a single publisher instance can multiplex progress
 * for every concurrently executing tool call within a run.
 */
sealed interface ProgressUpdate {
    val toolCallId: String
    val toolName: String

    data class Started(
        override val toolCallId: String,
        override val toolName: String,
        val description: String? = null,
    ) : ProgressUpdate

    data class Step(
        override val toolCallId: String,
        override val toolName: String,
        val completed: Int,
        val total: Int? = null,
        val description: String? = null,
    ) : ProgressUpdate

    data class BytesTransferred(
        override val toolCallId: String,
        override val toolName: String,
        val bytesRead: Long,
        val totalBytes: Long? = null,
    ) : ProgressUpdate

    data class LogLine(
        override val toolCallId: String,
        override val toolName: String,
        val line: String,
    ) : ProgressUpdate

    data class IntermediateResult(
        override val toolCallId: String,
        override val toolName: String,
        val preview: String,
    ) : ProgressUpdate

    data class Completed(
        override val toolCallId: String,
        override val toolName: String,
    ) : ProgressUpdate

    data class Failed(
        override val toolCallId: String,
        override val toolName: String,
        val error: String,
    ) : ProgressUpdate
}

// ---------------------------------------------------------------------------
// No-op implementation (for tests / fallback)
// ---------------------------------------------------------------------------

/**
 * No-op progress publisher. All public methods return immediately and emit no events. Use
 * this in unit tests, preview surfaces, or as a safe fallback when a real event-bus-backed
 * publisher is not available.
 */
object NoOpToolProgressPublisher : ToolProgressPublisher {
    override fun start(toolCallId: String, toolName: String, description: String?) = Unit
    override fun update(update: ProgressUpdate) = Unit
    override fun step(
        toolCallId: String,
        toolName: String,
        completed: Int,
        total: Int?,
        description: String?,
    ) = Unit
    override fun bytes(
        toolCallId: String,
        toolName: String,
        bytesRead: Long,
        totalBytes: Long?,
    ) = Unit
    override fun log(toolCallId: String, toolName: String, line: String) = Unit
    override fun intermediate(toolCallId: String, toolName: String, preview: String) = Unit
    override fun complete(toolCallId: String, toolName: String) = Unit
    override fun fail(toolCallId: String, toolName: String, error: String) = Unit
}
