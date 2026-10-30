package com.bytedance.zgx.solin.mcp

import org.json.JSONObject

/**
 * Pure JSON-RPC 2.0 codec for MCP messages. Requests carry a positive id; notifications (id 0 in
 * [McpRequest]) are serialized WITHOUT an `id` member, as JSON-RPC requires. Responses without a
 * valid positive numeric id, with a malformed error object, or with a non-object result are
 * rejected.
 */
internal object McpJsonRpcCodec {
    fun serialize(request: McpRequest): String =
        JSONObject().apply {
            put("jsonrpc", request.jsonrpc)
            if (request.id != 0L) put("id", request.id)
            put("method", request.method)
            request.params?.let { put("params", wrapJsonValue(it)) }
        }.toString()

    fun parseResponse(json: String): McpResponse {
        val obj = JSONObject(json)
        val id = obj.opt("id")
        require(id is Number && id.toLong() > 0L) { "MCP response without a valid id" }
        val error = if (obj.has("error")) {
            val e = obj.optJSONObject("error") ?: throw IllegalArgumentException("MCP error must be an object")
            val code = e.opt("code")
            require(code is Number) { "MCP error without numeric code" }
            McpError(code = code.toInt(), message = e.optString("message"), data = null)
        } else {
            null
        }
        val result = if (obj.has("result")) {
            obj.optJSONObject("result")?.let(::jsonObjectToMap)
                ?: throw IllegalArgumentException("MCP result must be an object")
        } else {
            null
        }
        require(error != null || result != null) { "MCP response without result or error" }
        return McpResponse(id = id.toLong(), result = result, error = error)
    }
}
