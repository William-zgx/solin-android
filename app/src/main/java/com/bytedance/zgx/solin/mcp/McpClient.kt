package com.bytedance.zgx.solin.mcp

import com.bytedance.zgx.solin.module.ToolHandler
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolSpec
import org.json.JSONObject

/**
 * Top-level facade that owns server registrations and exposes connection lifecycle.
 * Wave 6: no servers are registered by default; construction is cheap.
 *
 * Open for testing so tests can construct a fake client without wiring a full
 * transport/connection/registry stack.
 */
open class McpClient(
    val registry: McpServerRegistry,
) {
    open fun allApprovedToolSpecs(): List<ToolSpec> =
        registry.listServers().flatMap { info ->
            registry.connectionFor(info.serverId)?.approvedToolSpecs() ?: emptyList()
        }

    open fun handlerFor(toolName: String): ToolHandler? {
        // ToolName from McpServerConnection.toToolSpec is "mcp__<serverIdSanitized>__<toolName>"
        if (!toolName.startsWith("mcp__")) return null
        val parts = toolName.removePrefix("mcp__").split("__", limit = 2)
        if (parts.size != 2) return null
        val serverIdSanitized = parts[0]
        val mcpToolName = parts[1]
        // Find connection by sanitized serverId
        val conn = registry.listServers().firstOrNull { info ->
            sanitize(info.serverId) == serverIdSanitized
        }?.let { registry.connectionFor(it.serverId) } ?: return null
        return ToolHandler { request: ToolRequest ->
            val argsJson = if (request.arguments.isEmpty()) {
                "{}"
            } else {
                val obj = JSONObject()
                request.arguments.forEach { (k, v) ->
                    // ToolRequest.arguments is Map<String,String>; attempt to coerce common primitives
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
            conn.callTool(mcpToolName, argsJson)
        }
    }

    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
