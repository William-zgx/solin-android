package com.bytedance.zgx.solin.logging

/** A throwable's message for user-facing summaries, falling back to its class name. */
internal fun Throwable.cleanMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
