package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.tool.ToolRequest

/**
 * The tool request a confirmation decision (confirm / dismiss / reject) acts on.
 *
 * A run-less action draft has no orchestrator request, so one is built from the draft. A
 * run-bound confirmation must carry the exact request the run is awaiting: fabricating one would
 * get a fresh random id that the orchestrator cannot match, leaving the run in
 * AwaitingUserConfirmation while the UI clears the confirmation. Returns null in that case so the
 * caller fails closed.
 */
internal fun PendingAgentConfirmation.resolvedToolRequest(): ToolRequest? =
    toolRequest ?: if (runId == null) {
        ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = draft.summary,
        )
    } else {
        null
    }
