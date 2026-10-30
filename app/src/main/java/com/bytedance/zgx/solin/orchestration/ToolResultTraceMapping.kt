package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.orchestration.ToolErrorCode as OrchestrationToolErrorCode

/** Maps a tool-layer error code onto the orchestration event taxonomy used by SolinEvent.ToolFailed. */
internal fun ToolErrorCode?.toOrchestrationErrorCode(): OrchestrationToolErrorCode = when (this) {
    null -> OrchestrationToolErrorCode.Unknown("unknown")
    ToolErrorCode.PermissionDenied -> OrchestrationToolErrorCode.Unknown("permission_denied")
    ToolErrorCode.UnknownTool -> OrchestrationToolErrorCode.ToolNotFound
    ToolErrorCode.InvalidRequest, ToolErrorCode.InvalidResult, ToolErrorCode.MissingArgument ->
        OrchestrationToolErrorCode.InvalidArgs
    ToolErrorCode.NoActivityFound -> OrchestrationToolErrorCode.Unknown("no_activity_found")
    ToolErrorCode.ExecutionFailed -> OrchestrationToolErrorCode.ExecutionFailed
    ToolErrorCode.UserCancelled -> OrchestrationToolErrorCode.Cancelled
}

/**
 * PRIVACY: replaces every private output key the tool declares with "[redacted]" (and swaps in the
 * tool's redacted summary) before the result is written to the trace or audit. A result without a
 * request, or a tool without private keys, is returned unchanged.
 */
internal fun ToolResult.redactedForTrace(request: ToolRequest?, toolRegistry: ToolRegistry): ToolResult {
    val toolName = request?.toolName ?: return this
    val privateKeys = toolRegistry.privateOutputKeysFor(toolName)
    if (privateKeys.isEmpty()) return this
    val redactedData = privateKeys.fold(data) { currentData, key ->
        if (key in currentData) {
            currentData + (key to "[redacted]")
        } else {
            currentData
        }
    }
    if (redactedData == data) return this
    return copy(
        summary = toolRegistry.redactedResultSummaryFor(toolName) ?: summary,
        data = redactedData,
    )
}
