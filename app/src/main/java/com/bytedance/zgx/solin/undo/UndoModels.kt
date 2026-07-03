package com.bytedance.zgx.solin.undo

import com.bytedance.zgx.solin.tool.ToolRequest

sealed class UndoPlan {
    data class CompensatingTool(
        val request: ToolRequest,
        val summary: String,
        val ttlMillis: Long = 60_000L,
    ) : UndoPlan()

    data class ExternalHandoff(val reason: String) : UndoPlan()

    data object NotApplicable : UndoPlan()

    data class NotUndoable(val reason: String) : UndoPlan()
}

data class UndoEntry(
    val sourceRunId: String,
    val sourceRequestId: String,
    val toolName: String,
    val plan: UndoPlan.CompensatingTool,
    val availableUntilMillis: Long,
    val createdAtMillis: Long = System.currentTimeMillis(),
)
