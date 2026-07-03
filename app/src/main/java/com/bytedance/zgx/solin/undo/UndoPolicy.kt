package com.bytedance.zgx.solin.undo

import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult

fun interface UndoPolicy {
    fun planUndoAfter(request: ToolRequest, result: ToolResult): UndoPlan?
}
