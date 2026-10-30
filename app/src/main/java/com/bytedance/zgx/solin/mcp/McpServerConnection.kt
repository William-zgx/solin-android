package com.bytedance.zgx.solin.mcp

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps one MCP transport: connect + initialize handshake, `tools/list`, and `tools/call`.
 * Does not decide what is exposed to the model — [McpClient] maps approved catalog entries to
 * ToolSpecs/handlers and re-checks consent, opt-in state, and schema snapshots before calling.
 */
class McpServerConnection(
    val transport: McpTransport,
    val consentStore: McpConsentStore,
    val displayName: String = transport.serverId,
) : AutoCloseable {
    val serverId: String get() = transport.serverId

    private val mutex = Mutex()
    private val nextRequestId = AtomicLong(1L)

    @Volatile
    private var initResult: McpInitializeResult? = null

    @Volatile
    private var cachedTools: List<McpTool> = emptyList()

    /** Live snapshot of this connection's state, suitable for UI / registry listings. */
    val descriptor: McpServerInfo get() = McpServerInfo(
        serverId = serverId,
        displayName = displayName,
        isConnected = transport.isConnected(),
        isApproved = consentStore.isServerApproved(serverId),
        toolCount = cachedTools.size,
        approvedToolCount = cachedTools.count { consentStore.isToolApproved(serverId, it.name) },
    )

    fun tools(): List<McpTool> = cachedTools

    /** Connects (verifying the peer) and performs the MCP handshake once; refreshes tools. */
    suspend fun ensureInitialized(): McpInitializeResult = mutex.withLock {
        check(transport.connect()) { "MCP server $displayName is not reachable or not trusted" }
        initResult?.takeIf { transport.isConnected() }?.let { return@withLock it }
        val resp = transport.request(
            McpRequest(
                id = nextRequestId.getAndIncrement(),
                method = McpProtocol.METHOD_INITIALIZE,
                params = mapOf(
                    "protocolVersion" to McpProtocol.PROTOCOL_VERSION,
                    "clientInfo" to mapOf("name" to "Solin", "version" to "0.1"),
                    "capabilities" to emptyMap<String, Any?>(),
                ),
            ),
        )
        resp.error?.let { error -> throw IllegalStateException("MCP initialize failed: ${error.code}") }
        val result = resp.result ?: throw IllegalStateException("MCP initialize returned no result")
        val init = parseInitialize(result)
        transport.notify(McpProtocol.METHOD_INITIALIZED_NOTIF)
        refreshToolsLocked()
        initResult = init
        init
    }

    /** Backwards-compatible alias for [ensureInitialized]. */
    suspend fun initialize(): McpInitializeResult = ensureInitialized()

    suspend fun refreshTools(): List<McpTool> = mutex.withLock {
        refreshToolsLocked()
        cachedTools
    }

    private suspend fun refreshToolsLocked() {
        val resp = transport.request(
            McpRequest(id = nextRequestId.getAndIncrement(), method = McpProtocol.METHOD_TOOLS_LIST),
        )
        if (resp.error != null) {
            cachedTools = emptyList()
            return
        }
        val toolsJson = resp.result?.get("tools") as? List<*> ?: emptyList<Any?>()
        cachedTools = toolsJson
            .mapNotNull { item -> item.asStringKeyMap()?.let(::parseTool) }
            .take(MAX_TOOLS_PER_SERVER)
    }

    /**
     * Sends `tools/call` with already-typed [arguments]. Re-checks consent at call time; the
     * returned map is the raw MCP result (callers map it to a privacy-stamped ToolResult).
     */
    suspend fun callTool(toolName: String, arguments: Map<String, Any?>): McpCallOutcome {
        if (!consentStore.isServerApproved(serverId) || !consentStore.isToolApproved(serverId, toolName)) {
            return McpCallOutcome.NotApproved
        }
        val resp = transport.request(
            McpRequest(
                id = nextRequestId.getAndIncrement(),
                method = McpProtocol.METHOD_TOOLS_CALL,
                params = mapOf("name" to toolName, "arguments" to arguments),
            ),
        )
        resp.error?.let { error -> return McpCallOutcome.ProtocolError(error.code) }
        return McpCallOutcome.Result(resp.result ?: emptyMap())
    }

    override fun close() {
        initResult = null
        runCatching { transport.close() }
    }

    private fun parseInitialize(m: Map<String, Any?>): McpInitializeResult {
        val info = m["serverInfo"].asStringKeyMap().orEmpty()
        return McpInitializeResult(
            protocolVersion = m["protocolVersion"] as? String ?: McpProtocol.PROTOCOL_VERSION,
            serverInfo = McpServerDescriptor(
                name = info["name"] as? String ?: displayName,
                version = info["version"] as? String ?: "0",
            ),
            capabilities = m["capabilities"].asStringKeyMap().orEmpty(),
        )
    }

    private fun parseTool(m: Map<String, Any?>): McpTool? {
        val name = m["name"] as? String ?: return null
        return McpTool(
            name = name,
            description = m["description"] as? String,
            inputSchema = m["inputSchema"].asStringKeyMap() ?: mapOf("type" to "object"),
            annotations = m["annotations"].asStringKeyMap(),
        )
    }

    private companion object {
        const val MAX_TOOLS_PER_SERVER = 64
    }
}

sealed interface McpCallOutcome {
    data class Result(val result: Map<String, Any?>) : McpCallOutcome

    /** JSON-RPC error; only the numeric code is kept (server messages are untrusted text). */
    data class ProtocolError(val code: Int) : McpCallOutcome

    data object NotApproved : McpCallOutcome
}
