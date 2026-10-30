package com.bytedance.zgx.solin.mcp

import android.content.ComponentName
import android.content.Context

/**
 * Owns MCP server registrations: the persisted catalog in [settings] (servers + approved tool
 * snapshots) and live [McpServerConnection]s created on demand.
 *
 * Registration and tool approval are explicit user actions (a server-management UI or debug
 * tooling calls [registerServer] / [approveTools]); nothing is discovered or approved
 * automatically. Connections are created lazily through [connectionFactory], which for the
 * Binder transport verifies that the server holds Solin's MCP_SERVER signature permission.
 */
class McpServerRegistry(
    context: Context?,
    private val consentStore: McpConsentStore = InMemoryMcpConsentStore(),
    private val settings: McpCapabilitySettings = InMemoryMcpCapabilitySettings(),
    private val connectionFactory: (McpServerEntry) -> McpServerConnection? =
        context?.applicationContext?.let { appContext -> binderConnectionFactory(appContext, consentStore) }
            ?: { _: McpServerEntry -> null },
) {
    /** Test/internal convenience: construct without providing an Android Context. */
    internal constructor() : this(context = null)

    private val lock = Any()
    private val servers = LinkedHashMap<String, McpServerConnection>()

    init {
        // Mirror persisted approvals into the call-time consent store.
        settings.servers().forEach(::syncConsent)
    }

    fun listServers(): List<McpServerInfo> =
        synchronized(lock) { servers.values.map { it.descriptor } }

    fun consentStore(): McpConsentStore = consentStore

    /** Persisted catalog (servers and approved tool snapshots). */
    fun catalog(): List<McpServerEntry> = settings.servers()

    fun catalogEntry(serverId: String): McpServerEntry? =
        settings.servers().firstOrNull { it.serverId == serverId }

    /** Returns the live connection for [serverId], creating it from the catalog when needed. */
    fun connectionFor(serverId: String): McpServerConnection? = synchronized(lock) {
        servers[serverId]?.let { return it }
        val entry = catalogEntry(serverId) ?: return null
        val created = connectionFactory(entry) ?: return null
        servers[serverId] = created
        created
    }

    /**
     * Registers a server component chosen by the user. Returns null (fail closed) when the
     * component cannot be verified as a trusted MCP server. Registration approves the server but
     * no tools; results stay LocalOnly unless [resultsPublic] is explicitly true.
     */
    fun registerServer(
        component: ComponentName,
        displayName: String = component.flattenToShortString(),
        resultsPublic: Boolean = false,
    ): McpServerConnection? = registerServerId(component.flattenToString(), displayName, resultsPublic)

    internal fun registerServerId(
        serverId: String,
        displayName: String,
        resultsPublic: Boolean = false,
    ): McpServerConnection? {
        val existing = catalogEntry(serverId)
        val entry = McpServerEntry(
            serverId = serverId,
            displayName = McpToolSpecs.sanitizeText(displayName, McpToolSpecs.MAX_TITLE_CHARS).ifBlank { serverId },
            resultsPublic = resultsPublic,
            approvedTools = existing?.approvedTools.orEmpty(),
        )
        val connection = connectionFactory(entry) ?: return null
        synchronized(lock) {
            servers.remove(serverId)?.close()
            servers[serverId] = connection
        }
        settings.putServer(entry)
        syncConsent(entry)
        return connection
    }

    /**
     * Approves [toolNames] on [serverId] using the tools last listed by the live server, storing
     * a schema snapshot per tool. Tools that are invalid for Solin (bad name, oversized or
     * unsupported schema) are skipped. Returns the approved snapshots.
     */
    suspend fun approveTools(serverId: String, toolNames: Set<String>): List<McpApprovedTool> {
        val entry = catalogEntry(serverId) ?: return emptyList()
        val connection = connectionFor(serverId) ?: return emptyList()
        connection.ensureInitialized()
        val snapshots = connection.tools()
            .filter { it.name in toolNames }
            .mapNotNull(McpToolSpecs::approvedSnapshotOrNull)
            .filter { snapshot -> McpToolSpecs.toToolSpecOrNull(entry, snapshot) != null }
        val merged = (entry.approvedTools.filterNot { old -> snapshots.any { it.name == old.name } } + snapshots)
        val updated = entry.copy(approvedTools = merged)
        settings.putServer(updated)
        syncConsent(updated)
        return snapshots
    }

    fun revokeTool(serverId: String, toolName: String) {
        val entry = catalogEntry(serverId) ?: return
        settings.putServer(entry.copy(approvedTools = entry.approvedTools.filterNot { it.name == toolName }))
        consentStore.revokeTool(serverId, toolName)
    }

    /** Test/internal helper: insert an already-constructed connection directly. */
    internal fun putConnection(conn: McpServerConnection) {
        synchronized(lock) { servers[conn.serverId] = conn }
    }

    fun unregisterServer(serverId: String) {
        synchronized(lock) { servers.remove(serverId) }?.close()
        settings.removeServer(serverId)
        consentStore.revokeServer(serverId)
    }

    /** Closes live connections; the persisted catalog is kept. */
    fun clear() {
        val closing = synchronized(lock) { servers.values.toList().also { servers.clear() } }
        closing.forEach { runCatching { it.close() } }
    }

    private fun syncConsent(entry: McpServerEntry) {
        consentStore.approveServer(entry.serverId)
        entry.approvedTools.forEach { tool -> consentStore.approveTool(entry.serverId, tool.name) }
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

private fun binderConnectionFactory(
    context: Context,
    consentStore: McpConsentStore,
): (McpServerEntry) -> McpServerConnection? = { entry ->
    ComponentName.unflattenFromString(entry.serverId)
        ?.takeIf { component -> McpServerVerifier.isTrustedServer(context, component) }
        ?.let { component ->
            McpServerConnection(
                transport = BinderMcpTransport(context, component),
                consentStore = consentStore,
                displayName = entry.displayName,
            )
        }
}
