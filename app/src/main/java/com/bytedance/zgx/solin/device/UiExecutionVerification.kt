/*
 * Adapted from Mika (Copyright 2026 wangzan101), Apache License 2.0.
 * Source: wangzan101/Mika @ 0091d2d67b541a417df913a23fefbb8e3a04e4a7
 * core/.../uistate/Accuracy.kt; app/.../cap/phone/a11y/ChangeWait.kt.
 * Changes: read-only verification after a single authorized write, exact text comparison,
 * monotonic bounded polling, and unknown for unavailable/incomplete screen evidence.
 */
package com.bytedance.zgx.solin.device

/** Verification describes evidence, never permission to repeat an input operation (T2/T5). */
enum class UiInputVerification(val schemaValue: String) {
    Match("match"), Mismatch("mismatch"), Unknown("unknown"),
}

/** Stable refers only to the observed accessibility tree, never visual or task completion. */
enum class UiScreenStability(val schemaValue: String) {
    Stable("stable"), TimedOut("timed_out"), Unknown("unknown"),
}

/**
 * Read after an already authorized write. No trimming, clear, write retry, or password read (T8).
 * A null read means the original safe field can no longer be identified. Do not search for a
 * replacement field or reuse an earlier matching read after the field becomes unavailable.
 */
internal fun verifyWrittenText(
    expected: String,
    timeoutMillis: Long,
    now: () -> Long,
    pause: (Long) -> Unit,
    read: () -> String?,
    requireActive: () -> Unit = {},
): UiInputVerification {
    val started = now()
    val budget = timeoutMillis.coerceIn(0L, 500L)
    while (true) {
        requireActive()
        val actual = read() ?: return UiInputVerification.Unknown
        requireActive()
        // A slow read crossing the deadline cannot establish timely completion.
        if (now() - started > budget) return UiInputVerification.Unknown
        if (actual == expected) return UiInputVerification.Match
        val left = budget - (now() - started)
        if (left <= 0L) return UiInputVerification.Mismatch
        pause(minOf(50L, left))
    }
}

/**
 * Polls existing authorized tree evidence only; this never captures pixels or starts OCR (T6).
 * Missing evidence resets stability. A steady tree must span the quiet interval and events must
 * also be quiet. Continuous animation/events time out rather than falsely claiming success.
 * Sampling is read-only; a timeout or cancellation must never replay the preceding action (T2).
 */
internal fun <T : Any> awaitStableUiEvidence(
    timeoutMillis: Long,
    now: () -> Long,
    pause: (Long) -> Unit,
    sample: () -> T?,
    quietForMillis: () -> Long,
    requireActive: () -> Unit = {},
): UiScreenStability {
    val started = now()
    val budget = timeoutMillis.coerceIn(0L, MAX_UI_ACTION_TIMEOUT_MILLIS)
    var previous: T? = null
    var sameSince = started
    var lastReadable = false
    while (true) {
        requireActive()
        val current = sample()
        requireActive()
        val sampledAt = now()
        lastReadable = current != null
        if (sampledAt - started > budget) {
            return if (lastReadable) UiScreenStability.TimedOut else UiScreenStability.Unknown
        }
        if (current == null || current != previous) sameSince = sampledAt
        if (current != null && previous != null && current == previous &&
            sampledAt - sameSince >= UI_EVIDENCE_QUIET_MILLIS &&
            quietForMillis() >= UI_EVIDENCE_QUIET_MILLIS
        ) return UiScreenStability.Stable
        previous = current
        val left = budget - (sampledAt - started)
        if (left <= 0L) return if (lastReadable) UiScreenStability.TimedOut else UiScreenStability.Unknown
        pause(minOf(100L, left))
    }
}

private const val UI_EVIDENCE_QUIET_MILLIS = 400L

/** Null cannot accidentally compare equal to another failed, empty, or truncated read. */
internal fun ScreenStateSnapshot.stabilityEvidence(): ScreenStateSnapshot? =
    takeIf { !packageName.isNullOrBlank() && nodes.isNotEmpty() && !truncated }
        ?.copy(id = "", capturedAtMillis = 0L, nodes = nodes.map { it.copy(id = "") })
