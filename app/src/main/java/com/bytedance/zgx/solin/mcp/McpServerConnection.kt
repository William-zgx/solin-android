package com.bytedance.zgx.solin.mcp

import com.bytedance.zgx.solin.module.ToolHandler
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolCapabilityTag
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.MAX_SHARE_TEXT_CHARS
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wraps one connected MCP transport; performs initialize handshake and exposes
 * tools/list + tools/call. ToolSpec/ToolHandler adapters are produced by
 * [approvedToolSpecs] and [toolHandler] and registered into Solin via [McpModule].
 */
class McpServerConnection(
    val transport: McpTransport,
    val consentStore: McpConsentStore,
    val displayName: String = transport.serverId,
) : AutoCloseable {
    val serverId: String get() = transport.serverId

    private val mutex = Mutex()
    private var initResult: McpInitializeResult? = null
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

    /** Perform MCP initialize handshake and cache tools list. */
    suspend fun initialize(): McpInitializeResult = mutex.withLock {
        val id = 1L
        val resp = transport.request(McpRequest(id = id, method = McpProtocol.METHOD_INITIALIZE, params = mapOf(
            "protocolVersion" to McpProtocol.PROTOCOL_VERSION,
            "clientInfo" to mapOf("name" to "Solin", "version" to "0.1"),
            "capabilities" to mapOf("tools" to mapOf<String, Any>()),
        )))
        if (resp.error != null) throw IllegalStateException("MCP initialize failed: ${resp.error.message}")
        val result = resp.result ?: throw IllegalStateException("MCP initialize returned no result")
        val init = parseInitialize(result)
        initResult = init
        transport.notify(McpProtocol.METHOD_INITIALIZED_NOTIF)
        refreshToolsLocked()
        init
    }

    private suspend fun refreshToolsLocked() {
        val resp = transport.request(McpRequest(id = 2L, method = McpProtocol.METHOD_TOOLS_LIST))
        if (resp.error != null) return
        val toolsJson = resp.result?.get("tools") as? List<*> ?: return
        cachedTools = toolsJson.mapNotNull { t ->
            (t as? Map<*, *>)?.let { parseTool(it as Map<String, Any?>) }
        }
    }

    /** Returns ToolSpecs for all approved tools (those approved by user consent). */
    fun approvedToolSpecs(): List<ToolSpec> {
        if (!consentStore.isServerApproved(serverId)) return emptyList()
        return cachedTools
            .filter { consentStore.isToolApproved(serverId, it.name) }
            .map { tool -> toToolSpec(tool) }
    }

    /**
     * Returns a [ToolHandler] bound to this connection that performs tools/call and
     * maps MCP content to [ToolResult].
     */
    fun toolHandler(): ToolHandler = ToolHandler { request ->
        val argsJson = if (request.arguments.isEmpty()) {
            "{}"
        } else {
            val obj = JSONObject()
            request.arguments.forEach { (k, v) ->
                val longVal = v.toLongOrNull()
                val boolVal = v.toBooleanStrictOrNull()
                when {
                    boolVal != null -> obj.put(k, boolVal)
                    longVal != null && !v.contains('.') -> obj.put(k, longVal)
                    else -> obj.put(k, v)
                }
            }
            obj.toString()
        }
        callTool(request.toolName, argsJson)
    }

    suspend fun callTool(toolName: String, argsJson: String): ToolResult {
        // Double-check consent at call time
        if (!consentStore.isServerApproved(serverId) || !consentStore.isToolApproved(serverId, toolName)) {
            return ToolResult(
                requestId = "",
                status = ToolStatus.Rejected,
                summary = "MCP tool $serverId/$toolName is not approved by the user.",
            )
        }
        val argsObj = if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
        val argsMap = jsonObjectToMap(argsObj)
        val resp = transport.request(McpRequest(
            id = System.nanoTime(),
            method = McpProtocol.METHOD_TOOLS_CALL,
            params = mapOf("name" to toolName, "arguments" to argsMap),
        ))
        if (resp.error != null) {
            return ToolResult(
                requestId = "",
                status = ToolStatus.Failed,
                summary = "MCP tool $serverId/$toolName error: ${resp.error.message}",
                retryable = true,
            )
        }
        return parseCallResult(toolName, resp.result ?: emptyMap())
    }

    private fun toToolSpec(t: McpTool): ToolSpec {
        // Map MCP tool to a Solin ToolSpec with conservative defaults:
        // risk=HighExternalSend (MCP servers can drive external side effects),
        // confirmation=Required, capability=Extension.
        val desc = (t.description ?: "MCP tool from $displayName").take(400)
        val schemaJson = mapToJsonString(t.inputSchema).take(8_000)
        return ToolSpec(
            name = "mcp__${sanitize(serverId)}__${t.name}",
            title = "${t.name} (${displayName})",
            description = desc,
            inputSchemaJson = schemaJson,
            capability = ToolCapability.Extension,
            permissions = emptySet(),
            riskLevel = RiskLevel.HighExternalSend,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = emptySet(),
            privateOutputKeys = emptySet(),
            tags = setOf(ToolCapabilityTag.McpTool),
        )
    }

    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    override fun close() {
        runCatching { transport.close() }
    }

    private fun parseInitialize(m: Map<String, Any?>): McpInitializeResult {
        val ver = m["protocolVersion"] as? String ?: McpProtocol.PROTOCOL_VERSION
        val info = m["serverInfo"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val caps = m["capabilities"] as? Map<String, Any?> ?: emptyMap()
        return McpInitializeResult(
            protocolVersion = ver,
            serverInfo = McpServerDescriptor(
                name = info["name"] as? String ?: displayName,
                version = info["version"] as? String ?: "0",
            ),
            capabilities = caps,
        )
    }

    private fun parseTool(m: Map<String, Any?>): McpTool? {
        val name = m["name"] as? String ?: return null
        val desc = m["description"] as? String
        val schema = m["inputSchema"] as? Map<String, Any?> ?: mapOf("type" to "object")
        val annotations = m["annotations"] as? Map<String, Any?>
        return McpTool(name, desc, schema, annotations)
    }

    private fun parseCallResult(toolName: String, m: Map<String, Any?>): ToolResult {
        val isError = m["isError"] as? Boolean ?: false
        val content = m["content"] as? List<*> ?: emptyList<Any>()
        val sb = StringBuilder()
        val data = mutableMapOf<String, String>()
        content.forEachIndexed { i, c ->
            (c as? Map<*, *>)?.let { entry ->
                val em = entry as Map<String, Any?>
                when (em["type"] as? String) {
                    "text" -> sb.append(em["text"] as? String ?: "")
                    "image" -> data["mcp_image_$i"] = (em["mimeType"] as? String ?: "") + "|" + ((em["data"] as? String)?.take(100) ?: "")
                    "resource" -> sb.append("[resource: ${em["uri"]}]")
                }
            }
        }
        val summary = sb.toString().take(MAX_SHARE_TEXT_CHARS)
        val status = if (isError) ToolStatus.Failed else ToolStatus.Succeeded
        return ToolResult(
            requestId = "",
            status = status,
            summary = summary.ifBlank {
                if (isError) "MCP tool $serverId/$toolName returned an error."
                else "MCP tool $serverId/$toolName completed."
            },
            data = data,
            retryable = isError,
        )
    }

    private fun jsonObjectToMap(o: JSONObject): Map<String, Any?> {
        val out = HashMap<String, Any?>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.get(k)
            out[k] = when (v) {
                is JSONObject -> jsonObjectToMap(v)
                is JSONArray -> {
                    val list = mutableListOf<Any?>()
                    for (i in 0 until v.length()) {
                        val item = v.get(i)
                        list.add(
                            when (item) {
                                is JSONObject -> jsonObjectToMap(item)
                                is JSONArray -> jsonArrayToList(item)
                                else -> item
                            }
                        )
                    }
                    list
                }
                else -> v
            }
        }
        return out
    }

    private fun jsonArrayToList(a: JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until a.length()) {
            val item = a.get(i)
            list.add(
                when (item) {
                    is JSONObject -> jsonObjectToMap(item)
                    is JSONArray -> jsonArrayToList(item)
                    else -> item
                }
            )
        }
        return list
    }

    private fun mapToJsonString(m: Map<String, Any?>): String = JSONObject(m).toString()
}
