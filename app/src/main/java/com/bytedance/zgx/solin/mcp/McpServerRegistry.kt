package com.bytedance.zgx.solin.mcp

import android.content.ComponentName
import android.content.Context

/**
 * Discovers and manages MCP server connections. Wave 6 implementation is a
 * no-op registry (no servers by default). Future waves will add user-managed
 * server entries via a settings UI that binds [BinderMcpTransport] instances
 * for approved servers and constructs [McpServerConnection] wrappers.
 */
class McpServerRegistry(
    @Suppress("unused") private val context: Context?,
    private val consentStore: McpConsentStore = InMemoryMcpConsentStore(),
) {
    /** Test/internal convenience: construct without providing an Android Context. */
    internal constructor() : this(context = null)
    private val servers = mutableMapOf<String, McpServerConnection>()

    fun listServers(): List<McpServerInfo> =
        servers.values.map { it.descriptor }.toList()

    fun connectionFor(serverId: String): McpServerConnection? = servers[serverId]

    fun consentStore(): McpConsentStore = consentStore

    /**
     * Register a server component (e.g. from user settings). Wire-up of the
     * actual Binder connection is deferred to a future wave that has a proper
     * management UI. For Wave 6 this returns null and the registry stays empty.
     */
    fun registerServer(component: ComponentName): McpServerConnection? {
        // Wire-up deferred to a future wave that has a proper UI.
        // For Wave 6, servers are empty (registry returns empty list).
        return null
    }

    /** Test/internal helper: insert an already-constructed connection directly. */
    internal fun putConnection(conn: McpServerConnection) {
        servers[conn.serverId] = conn
    }

    fun unregisterServer(serverId: String) {
        servers.remove(serverId)?.close()
    }

    fun clear() {
        servers.values.forEach { runCatching { it.close() } }
        servers.clear()
    }
}

/** Small descriptor DTO for a bound/configured server. */
data class McpServerInfo(
    val serverId: String,
    val displayName: String,
    val isConnected: Boolean,
    val isApproved: Boolean,
    val toolCount: Int,
    val approvedToolCount: Int,
)
