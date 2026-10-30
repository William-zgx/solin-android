package com.bytedance.zgx.solin.mcp

/**
 * Tracks which MCP servers the user has approved, and which specific tools
 * on those servers have been cleared to be surfaced to the LLM.
 *
 * The call-time store is in-memory; durable approvals live in the
 * [McpCapabilitySettings] catalog and are mirrored here by [McpServerRegistry].
 * Consent is always per-server AND per-tool -- a server being approved does
 * NOT automatically grant all its tools.
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

/** In-memory consent store; thread-safe because tool handlers may run concurrently. */
class InMemoryMcpConsentStore : McpConsentStore {
    private val lock = Any()
    private val approvedServers = mutableSetOf<String>()
    private val approvedTools = mutableMapOf<String, MutableSet<String>>() // serverId -> tools

    override fun isServerApproved(serverId: String): Boolean = synchronized(lock) { serverId in approvedServers }

    override fun approveServer(serverId: String) {
        synchronized(lock) { approvedServers += serverId }
    }

    override fun revokeServer(serverId: String) {
        synchronized(lock) {
            approvedServers -= serverId
            approvedTools.remove(serverId)
        }
    }

    override fun isToolApproved(serverId: String, toolName: String): Boolean = synchronized(lock) {
        serverId in approvedServers && approvedTools[serverId]?.contains(toolName) == true
    }

    override fun approveTool(serverId: String, toolName: String) {
        // Tool approval only takes effect once the server itself is approved.
        // We still record the intent so approvals survive revokeServer/re-approve cycles,
        // but isToolApproved below requires server approval to return true.
        synchronized(lock) { approvedTools.getOrPut(serverId) { mutableSetOf() } += toolName }
    }

    override fun revokeTool(serverId: String, toolName: String) {
        synchronized(lock) { approvedTools[serverId]?.remove(toolName) }
    }

    override fun reset() {
        synchronized(lock) {
            approvedServers.clear()
            approvedTools.clear()
        }
    }
}
