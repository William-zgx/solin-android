package com.bytedance.zgx.solin.logging

/**
 * Standard log tag constants used across the Solin app.
 *
 * Usage:
 * ```
 * solinD(TAG_MODEL, "Loading model catalog…")
 * solinE(TAG_REMOTE, "Failed to reach remote endpoint", throwable)
 * ```
 */
object SolinLogTags {
    const val TAG_MODEL = "Solin/Model"
    const val TAG_TOOL = "Solin/Tool"
    const val TAG_MEMORY = "Solin/Memory"
    const val TAG_REMOTE = "Solin/Remote"
    const val TAG_LIFECYCLE = "Solin/Lifecycle"
}
