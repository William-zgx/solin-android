package com.bytedance.zgx.solin.safety

import com.bytedance.zgx.solin.tool.RiskLevel

/**
 * Gateway abstraction for permission / confirmation flows that must be resolved by an external
 * actor (typically the host UI, or a test double). The orchestrator calls [request] and suspends
 * until a [PermissionResponse] is produced.
 */
interface PermissionGateway {
    suspend fun request(request: PermissionRequest): PermissionResponse
}

/**
 * Sealed request hierarchy for the permission gateway. Each variant corresponds to a different
 * kind of user-visible prompt the host must surface.
 */
sealed interface PermissionRequest {
    /**
     * Confirmation prompt before executing a tool that crosses a safety boundary (high-risk tool,
     * sensitive data detected in a remote send, required runtime permission, etc.).
     *
     * @param runId agent run that triggered the tool
     * @param requestId unique id for this confirmation request (used to correlate the response)
     * @param toolName the tool being invoked
     * @param actionLabel short human-readable label describing the action being confirmed
     * @param riskLevel the tool's declared risk level
     * @param sensitiveCategories categories of sensitive content detected in the outgoing payload,
     *        if any. Empty when the confirmation is triggered purely by tool risk/permissions.
     * @param requiredRuntimePermissions Android runtime permissions that must be granted before
     *        the tool can run (e.g. POST_NOTIFICATIONS). Empty when none are required.
     */
    data class ToolConfirmation(
        val runId: String,
        val requestId: String,
        val toolName: String,
        val actionLabel: String,
        val riskLevel: RiskLevel,
        val sensitiveCategories: List<SafetyCategory> = emptyList(),
        val requiredRuntimePermissions: List<String> = emptyList(),
    ) : PermissionRequest

    /**
     * Free-form clarification question from the agent to the user, with optional bounded choices.
     * The host surfaces the prompt and returns the user's selected/typed answer as
     * [PermissionResponse.Answered].
     */
    data class UserQuestion(
        val runId: String,
        val questionId: String,
        val prompt: String,
        val choices: List<String> = emptyList(),
    ) : PermissionRequest

    /**
     * Confirmation before sending potentially sensitive payloads to a remote endpoint. Triggered
     * when the safety policy detects sensitive content in an outbound network request.
     */
    data class SensitiveRemoteSend(
        val runId: String?,
        val targetUrl: String,
        val reason: String,
    ) : PermissionRequest
}

/**
 * Sealed response hierarchy returned by [PermissionGateway.request].
 */
sealed interface PermissionResponse {
    /** The user approved the request; the protected action may proceed. */
    object Granted : PermissionResponse

    /** The user answered a [PermissionRequest.UserQuestion] with [answer]. */
    data class Answered(val answer: String) : PermissionResponse

    /** The user explicitly denied / cancelled the request. */
    object Denied : PermissionResponse

    /** The request timed out or the host could not produce a response (e.g. app in background). */
    object TimedOut : PermissionResponse
}

/**
 * Test / security-fallback gateway that always denies. Useful as a safe default when no UI is
 * attached, or in unit tests that must verify the deny path.
 *
 * [PermissionRequest.UserQuestion] has no meaningful deny answer, so it returns
 * [PermissionResponse.TimedOut]; binary confirmation and remote-send requests return
 * [PermissionResponse.Denied].
 */
object AutoDenyPermissionGateway : PermissionGateway {
    override suspend fun request(request: PermissionRequest): PermissionResponse =
        when (request) {
            is PermissionRequest.UserQuestion -> PermissionResponse.TimedOut
            is PermissionRequest.ToolConfirmation,
            is PermissionRequest.SensitiveRemoteSend,
            -> PermissionResponse.Denied
        }
}

/**
 * Test gateway that always grants. Useful in unit tests and golden-path flows that should not
 * block on user interaction. [PermissionRequest.UserQuestion] returns an empty [PermissionResponse.Answered].
 */
object AutoGrantPermissionGateway : PermissionGateway {
    override suspend fun request(request: PermissionRequest): PermissionResponse =
        when (request) {
            is PermissionRequest.ToolConfirmation,
            is PermissionRequest.SensitiveRemoteSend,
            -> PermissionResponse.Granted
            is PermissionRequest.UserQuestion -> PermissionResponse.Answered(answer = "")
        }
}
