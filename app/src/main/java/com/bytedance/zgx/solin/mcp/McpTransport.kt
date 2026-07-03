package com.bytedance.zgx.solin.mcp

/**
 * Bidirectional transport for one MCP server connection. All methods are
 * suspend-safe for calling from coroutine context.
 */
interface McpTransport : AutoCloseable {
    /** Server identifier (package name / component / URL for diagnostics). */
    val serverId: String

    /** Send a JSON-RPC request and await the matching response. */
    suspend fun request(request: McpRequest): McpResponse

    /** Send a one-way notification (no response expected). */
    suspend fun notify(method: String, params: Map<String, Any?>? = null)

    /** True if currently connected to the server. */
    fun isConnected(): Boolean
}
