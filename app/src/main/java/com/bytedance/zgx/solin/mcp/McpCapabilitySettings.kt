package com.bytedance.zgx.solin.mcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted MCP opt-in state. MCP is an opt-in capability: [isEnabled] defaults to `false`, and
 * it can only be turned on after the user acknowledged the "data leaves the device" disclosure.
 *
 * The approved-tool catalog is persisted too so the (frozen-at-startup) tool registry can expose
 * exactly the tools the user approved, with the schema snapshot they approved. A server whose
 * live schema later differs from the snapshot is refused at call time.
 */
interface McpCapabilitySettings {
    fun isEnabled(): Boolean

    /**
     * Turns MCP on or off. Turning it on is refused (returns false) until
     * [acknowledgeDisclosure] has been called. Turning it off always succeeds.
     */
    fun setEnabled(enabled: Boolean): Boolean

    fun isDisclosureAcknowledged(): Boolean

    fun acknowledgeDisclosure()

    fun servers(): List<McpServerEntry>

    fun putServer(entry: McpServerEntry)

    fun removeServer(serverId: String)
}

/** One user-registered MCP server and the tools the user approved on it. */
data class McpServerEntry(
    /** Flattened component name of the bound server service (`package/class`). */
    val serverId: String,
    val displayName: String,
    /**
     * Only when the user explicitly marks a server as public are its results `RemoteEligible`.
     * Defaults to `false`: results are `LocalOnly` and require the local model.
     */
    val resultsPublic: Boolean = false,
    val approvedTools: List<McpApprovedTool> = emptyList(),
)

/** Approval snapshot of one MCP tool: the exact schema the user saw when approving it. */
data class McpApprovedTool(
    val name: String,
    val description: String,
    /** Canonical (sorted-key) JSON of the approved input schema. */
    val inputSchemaJson: String,
)

class InMemoryMcpCapabilitySettings(
    enabled: Boolean = false,
    disclosureAcknowledged: Boolean = false,
    servers: List<McpServerEntry> = emptyList(),
) : McpCapabilitySettings {
    private val lock = Any()
    private var enabled = enabled && disclosureAcknowledged
    private var disclosureAcknowledged = disclosureAcknowledged
    private val servers = servers.associateByTo(LinkedHashMap()) { it.serverId }

    override fun isEnabled(): Boolean = synchronized(lock) { enabled }

    override fun setEnabled(enabled: Boolean): Boolean = synchronized(lock) {
        if (enabled && !disclosureAcknowledged) return false
        this.enabled = enabled
        true
    }

    override fun isDisclosureAcknowledged(): Boolean = synchronized(lock) { disclosureAcknowledged }

    override fun acknowledgeDisclosure() = synchronized(lock) { disclosureAcknowledged = true }

    override fun servers(): List<McpServerEntry> = synchronized(lock) { servers.values.toList() }

    override fun putServer(entry: McpServerEntry) = synchronized(lock) { servers[entry.serverId] = entry }

    override fun removeServer(serverId: String) = synchronized(lock) { servers.remove(serverId); Unit }
}

/**
 * SharedPreferences-backed settings, kept in a dedicated MCP preferences file so the opt-in
 * state cannot be confused with other settings. Malformed persisted catalog data fails closed
 * to an empty catalog.
 */
class SharedPreferencesMcpCapabilitySettings(context: Context) : McpCapabilitySettings {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun isEnabled(): Boolean = synchronized(lock) {
        prefs.getBoolean(KEY_ENABLED, false) && prefs.getBoolean(KEY_DISCLOSURE_ACKNOWLEDGED, false)
    }

    override fun setEnabled(enabled: Boolean): Boolean = synchronized(lock) {
        if (enabled && !prefs.getBoolean(KEY_DISCLOSURE_ACKNOWLEDGED, false)) return false
        prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()
    }

    override fun isDisclosureAcknowledged(): Boolean = synchronized(lock) {
        prefs.getBoolean(KEY_DISCLOSURE_ACKNOWLEDGED, false)
    }

    override fun acknowledgeDisclosure() = synchronized(lock) {
        prefs.edit().putBoolean(KEY_DISCLOSURE_ACKNOWLEDGED, true).commit()
        Unit
    }

    override fun servers(): List<McpServerEntry> = synchronized(lock) {
        McpServerCatalogCodec.decode(prefs.getString(KEY_SERVERS, null))
    }

    override fun putServer(entry: McpServerEntry) = synchronized(lock) {
        val updated = McpServerCatalogCodec.decode(prefs.getString(KEY_SERVERS, null))
            .filterNot { it.serverId == entry.serverId } + entry
        prefs.edit().putString(KEY_SERVERS, McpServerCatalogCodec.encode(updated)).commit()
        Unit
    }

    override fun removeServer(serverId: String) = synchronized(lock) {
        val updated = McpServerCatalogCodec.decode(prefs.getString(KEY_SERVERS, null))
            .filterNot { it.serverId == serverId }
        prefs.edit().putString(KEY_SERVERS, McpServerCatalogCodec.encode(updated)).commit()
        Unit
    }

    private companion object {
        const val PREFS_NAME = "solin_mcp_capability"
        const val KEY_ENABLED = "mcp_enabled"
        const val KEY_DISCLOSURE_ACKNOWLEDGED = "mcp_disclosure_acknowledged"
        const val KEY_SERVERS = "mcp_servers_v1"
    }
}

/** JSON codec for the persisted catalog. Pure so it is JVM-testable. */
internal object McpServerCatalogCodec {
    fun encode(entries: List<McpServerEntry>): String =
        JSONArray().also { array ->
            entries.forEach { entry ->
                array.put(
                    JSONObject()
                        .put("serverId", entry.serverId)
                        .put("displayName", entry.displayName)
                        .put("resultsPublic", entry.resultsPublic)
                        .put(
                            "approvedTools",
                            JSONArray().also { tools ->
                                entry.approvedTools.forEach { tool ->
                                    tools.put(
                                        JSONObject()
                                            .put("name", tool.name)
                                            .put("description", tool.description)
                                            .put("inputSchemaJson", tool.inputSchemaJson),
                                    )
                                }
                            },
                        ),
                )
            }
        }.toString()

    fun decode(raw: String?): List<McpServerEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val serverId = obj.optString("serverId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val tools = obj.optJSONArray("approvedTools") ?: JSONArray()
                McpServerEntry(
                    serverId = serverId,
                    displayName = obj.optString("displayName").ifBlank { serverId },
                    // Fail closed: anything but an explicit `true` keeps results LocalOnly.
                    resultsPublic = obj.opt("resultsPublic") == true,
                    approvedTools = (0 until tools.length()).mapNotNull { toolIndex ->
                        val tool = tools.optJSONObject(toolIndex) ?: return@mapNotNull null
                        val name = tool.optString("name")
                        val schema = tool.optString("inputSchemaJson")
                        if (!McpToolSpecs.isValidMcpToolName(name) || schema.isBlank()) return@mapNotNull null
                        McpApprovedTool(
                            name = name,
                            description = tool.optString("description"),
                            inputSchemaJson = schema,
                        )
                    },
                )
            }
        }.getOrDefault(emptyList())
    }
}
