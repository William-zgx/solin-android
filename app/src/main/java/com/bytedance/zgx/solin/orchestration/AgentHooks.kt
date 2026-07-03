package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.tool.ToolResult

data class BeforeToolCallContext(
    val runId: String,
    val toolCallId: String,
    val toolName: String,
    val args: Map<String, Any?>,
)

sealed interface BeforeToolCallResult {
    data object Proceed : BeforeToolCallResult

    data class Blocked(
        val reason: String,
    ) : BeforeToolCallResult
}

data class AfterToolCallContext(
    val runId: String,
    val toolCallId: String,
    val toolName: String,
    val result: ToolResult,
    val durationMs: Long,
)

sealed interface AfterToolCallResult {
    data object Keep : AfterToolCallResult

    data class ReplaceContent(
        val content: String,
        val isError: Boolean = false,
    ) : AfterToolCallResult

    data class Terminate(
        val message: String,
    ) : AfterToolCallResult
}

data class TurnContext(
    val runId: String,
    val turnIndex: Int,
    val messageCount: Int,
    val pendingToolCalls: Int,
)

data class TurnUpdate(
    val prependMessages: List<ChatMessage>? = null,
    val stopAfterTurn: Boolean? = null,
)

interface AgentHooks {
    suspend fun beforeToolCall(ctx: BeforeToolCallContext): BeforeToolCallResult =
        BeforeToolCallResult.Proceed

    suspend fun afterToolCall(ctx: AfterToolCallContext): AfterToolCallResult =
        AfterToolCallResult.Keep

    suspend fun transformContext(messages: List<ChatMessage>): List<ChatMessage> =
        messages

    suspend fun prepareNextTurn(ctx: TurnContext): TurnUpdate? =
        null

    suspend fun shouldStopAfterTurn(ctx: TurnContext): Boolean =
        false

    suspend fun getSteeringMessages(): List<ChatMessage> =
        emptyList()
}
