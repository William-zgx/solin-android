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
    const val TAG_DEVICE = "Solin/Device"
    const val TAG_AUDIT = "Solin/Audit"
    const val TAG_SAFETY = "Solin/Safety"
    const val TAG_LIFECYCLE = "Solin/Lifecycle"
    const val TAG_BACKGROUND = "Solin/Background"
    const val TAG_MCP = "Solin/MCP"
    const val TAG_UI = "Solin/UI"
    const val TAG_EVIDENCE = "Solin/Evidence"
}
