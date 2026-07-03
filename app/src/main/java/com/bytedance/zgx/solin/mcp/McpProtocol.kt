package com.bytedance.zgx.solin.mcp

/** MCP JSON-RPC 2.0 constants. */
object McpProtocol {
    const val JSONRPC_VERSION = "2.0"
    const val METHOD_INITIALIZE = "initialize"
    const val METHOD_INITIALIZED_NOTIF = "notifications/initialized"
    const val METHOD_TOOLS_LIST = "tools/list"
    const val METHOD_TOOLS_CALL = "tools/call"
    const val METHOD_PING = "ping"
    const val PROTOCOL_VERSION = "2024-11-05"
    const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
}

data class McpRequest(
    val jsonrpc: String = McpProtocol.JSONRPC_VERSION,
    val id: Long,
    val method: String,
    val params: Map<String, Any?>? = null,
)

data class McpResponse(
    val jsonrpc: String = McpProtocol.JSONRPC_VERSION,
    val id: Long,
    val result: Map<String, Any?>? = null,
    val error: McpError? = null,
)

data class McpError(val code: Int, val message: String, val data: Any? = null)

data class McpTool(
    val name: String,
    val description: String?,
    val inputSchema: Map<String, Any?>,
    val annotations: Map<String, Any?>? = null,
)

data class McpCallResult(
    val content: List<McpContent>,
    val isError: Boolean = false,
)

sealed class McpContent {
    data class Text(val text: String) : McpContent()
    data class Image(val data: String, val mimeType: String) : McpContent()
    data class Resource(
        val uri: String,
        val text: String? = null,
        val mimeType: String? = null,
    ) : McpContent()
}

data class McpServerDescriptor(val name: String, val version: String)

data class McpInitializeResult(
    val protocolVersion: String,
    val serverInfo: McpServerDescriptor,
    val capabilities: Map<String, Any?>,
)
