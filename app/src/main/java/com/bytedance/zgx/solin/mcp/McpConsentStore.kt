package com.bytedance.zgx.solin.mcp

/**
 * Tracks which MCP servers the user has approved, and which specific tools
 * on those servers have been cleared to be surfaced to the LLM.
 *
 * Wave 6 implementation is in-memory only. A future wave may persist
 * consent via SettingsStore; consent is always per-server AND per-tool --
 * a server being approved does NOT automatically grant all its tools.
 */
interface McpConsentStore {
    fun isServerApproved(serverId: String): Boolean
    fun approveServer(serverId: String)
    fun revokeServer(serverId: String)
    fun isToolApproved(serverId: String, toolName: String): Boolean
    fun approveTool(serverId: String, toolName: String)
    fun revokeTool(serverId: String, toolName: String)
    fun reset()
}

/** In-memory consent store; tests/production default. */
class InMemoryMcpConsentStore : McpConsentStore {
    private val approvedServers = mutableSetOf<String>()
    private val approvedTools = mutableMapOf<String, MutableSet<String>>() // serverId -> tools

    override fun isServerApproved(serverId: String): Boolean = serverId in approvedServers

    override fun approveServer(serverId: String) {
        approvedServers += serverId
    }

    override fun revokeServer(serverId: String) {
        approvedServers -= serverId
        approvedTools.remove(serverId)
    }

    override fun isToolApproved(serverId: String, toolName: String): Boolean =
        serverId in approvedServers && approvedTools[serverId]?.contains(toolName) == true

    override fun approveTool(serverId: String, toolName: String) {
        // Tool approval only takes effect once the server itself is approved.
        // We still record the intent so approvals survive revokeServer/re-approve cycles,
        // but isToolApproved below requires server approval to return true.
        approvedTools.getOrPut(serverId) { mutableSetOf() } += toolName
    }

    override fun revokeTool(serverId: String, toolName: String) {
        approvedTools[serverId]?.remove(toolName)
    }

    override fun reset() {
        approvedServers.clear()
        approvedTools.clear()
    }
}
