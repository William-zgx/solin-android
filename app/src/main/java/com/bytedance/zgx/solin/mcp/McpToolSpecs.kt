package com.bytedance.zgx.solin.mcp

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.MAX_SHARE_TEXT_CHARS
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolArgumentValidator
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolCapabilityTag
import com.bytedance.zgx.solin.tool.ToolExecutionMode
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.solin.tool.ToolResultDataValidator
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolStatus
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure mapping between MCP tool descriptors and Solin [ToolSpec]s / [ToolResult]s.
 *
 * Fail-closed rules (the MCP server is an untrusted, separately installed app):
 * - Every MCP tool is at least [RiskLevel.MediumDraftOrNavigation]; Solin maps them to
 *   [RiskLevel.HighExternalSend] with [ConfirmationPolicy.Required] because arguments leave
 *   Solin for another app, which SafetyPolicy hard-requires to be confirmed every time.
 * - Results are `LocalOnly` + `requiresLocalModel=true` unless the user explicitly marked the
 *   server as public ([McpServerEntry.resultsPublic]).
 * - Tool names, descriptions, and schemas are validated/bounded; a schema outside Solin's closed
 *   schema dialect, an invalid name, or an oversized schema drops the tool instead of crashing
 *   ToolRegistry construction or truncating JSON into an invalid schema.
 */
object McpToolSpecs {
    const val TOOL_NAME_PREFIX = "mcp__"
    const val MAX_TOOL_NAME_CHARS = 48
    const val MAX_DESCRIPTION_CHARS = 400
    const val MAX_TITLE_CHARS = 80
    const val MAX_SCHEMA_CHARS = 8_000
    const val DATA_TOOL_NAME = "toolName"
    const val DATA_PRIVACY = "privacy"
    const val DATA_REQUIRES_LOCAL_MODEL = "requiresLocalModel"
    const val DATA_TEXT = "mcpText"
    const val DATA_TRUNCATED = "contentTruncated"
    const val DATA_OMITTED_CONTENT_COUNT = "omittedContentCount"
    const val LOCAL_ONLY_REDACTED_SUMMARY = "已调用 MCP 工具，结果仅保存在本机"

    private val toolNamePattern = Regex("^[A-Za-z0-9_-]{1,$MAX_TOOL_NAME_CHARS}$")
    private val controlCharacters = Regex("[\\p{Cntrl}&&[^\\n\\t]]")

    val OUTPUT_SCHEMA_JSON: String = """
        {
          "type": "object",
          "required": ["toolName", "privacy", "requiresLocalModel"],
          "properties": {
            "toolName": {"type": "string", "minLength": 1},
            "privacy": {"type": "string", "enum": ["LocalOnly", "RemoteEligible"]},
            "requiresLocalModel": {"type": "boolean"},
            "mcpText": {"type": "string"},
            "contentTruncated": {"type": "boolean"},
            "omittedContentCount": {"type": "integer", "minimum": 0}
          },
          "additionalProperties": false
        }
    """.trimIndent()

    fun isValidMcpToolName(name: String): Boolean = toolNamePattern.matches(name)

    /**
     * Stable, collision-resistant server key used in tool names. Derived from the full server id
     * (flattened component) so two servers can never alias each other after sanitization.
     */
    fun serverKey(serverId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(serverId.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    fun solinToolName(serverId: String, mcpToolName: String): String =
        "$TOOL_NAME_PREFIX${serverKey(serverId)}__$mcpToolName"

    /** Builds an approval snapshot for [tool], or null when the tool must not be exposed. */
    fun approvedSnapshotOrNull(tool: McpTool): McpApprovedTool? {
        if (!isValidMcpToolName(tool.name)) return null
        val schemaJson = runCatching { canonicalJson(tool.inputSchema) }.getOrNull() ?: return null
        if (schemaJson.length > MAX_SCHEMA_CHARS) return null
        return McpApprovedTool(
            name = tool.name,
            description = sanitizeText(tool.description.orEmpty(), MAX_DESCRIPTION_CHARS),
            inputSchemaJson = schemaJson,
        )
    }

    /** Maps an approved tool to a [ToolSpec], or null when Solin's schema dialect rejects it. */
    fun toToolSpecOrNull(entry: McpServerEntry, tool: McpApprovedTool): ToolSpec? {
        if (!isValidMcpToolName(tool.name)) return null
        if (tool.inputSchemaJson.length > MAX_SCHEMA_CHARS) return null
        val serverName = sanitizeText(entry.displayName, MAX_TITLE_CHARS).ifBlank { "MCP" }
        val localOnly = !entry.resultsPublic
        val spec = ToolSpec(
            name = solinToolName(entry.serverId, tool.name),
            title = sanitizeText("${tool.name}（$serverName）", MAX_TITLE_CHARS),
            description = tool.description.ifBlank { "MCP tool from $serverName" } +
                " [MCP 外部扩展：参数会发送给其他应用]",
            inputSchemaJson = tool.inputSchemaJson,
            outputSchemaJson = OUTPUT_SCHEMA_JSON,
            capability = ToolCapability.Extension,
            permissions = emptySet(),
            riskLevel = RiskLevel.HighExternalSend,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = emptySet(),
            privateOutputKeys = if (localOnly) setOf(DATA_TEXT) else emptySet(),
            redactedResultSummary = if (localOnly) LOCAL_ONLY_REDACTED_SUMMARY else null,
            resultContinuationPolicy = if (localOnly) {
                ToolResultContinuationPolicy.LocalEvidence
            } else {
                ToolResultContinuationPolicy.None
            },
            tags = setOf(ToolCapabilityTag.McpTool),
            executionMode = ToolExecutionMode.Sequential,
        )
        val acceptedBySolinSchemaDialect = runCatching {
            ToolArgumentValidator.fromSchema(spec)
            ToolResultDataValidator.fromSchema(spec)
        }.isSuccess
        return spec.takeIf { acceptedBySolinSchemaDialect }
    }

    /**
     * Converts string tool arguments to the JSON types declared by the approved schema. Unknown
     * or unparsable values stay strings (the server validates its own schema); `array`/`object`
     * values must be JSON text of that shape.
     */
    fun typedArguments(inputSchemaJson: String, arguments: Map<String, String>): Map<String, Any?> {
        val properties = runCatching { JSONObject(inputSchemaJson).optJSONObject("properties") }.getOrNull()
        return arguments.mapValues { (name, value) ->
            when (properties?.optJSONObject(name)?.optString("type")) {
                "integer" -> value.trim().toLongOrNull() ?: value
                "number" -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: value
                "boolean" -> value.trim().toBooleanStrictOrNull() ?: value
                "array" -> runCatching { jsonArrayToList(JSONArray(value)) }.getOrDefault(value)
                "object" -> runCatching { jsonObjectToMap(JSONObject(value)) }.getOrDefault(value)
                else -> value
            }
        }
    }

    /** Maps an MCP `tools/call` result to a privacy-stamped [ToolResult]. */
    fun toToolResult(
        request: ToolRequest,
        entry: McpServerEntry,
        result: Map<String, Any?>,
    ): ToolResult {
        val isError = result["isError"] as? Boolean ?: false
        val content = result["content"] as? List<*> ?: emptyList<Any?>()
        val text = StringBuilder()
        var omitted = 0
        content.forEach { item ->
            val entryMap = item.asStringKeyMap() ?: run {
                omitted += 1
                return@forEach
            }
            when (entryMap["type"] as? String) {
                "text" -> (entryMap["text"] as? String)?.let { value ->
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(value)
                } ?: run { omitted += 1 }

                // Binary content (images, audio, embedded blobs) is never forwarded into prompts.
                else -> omitted += 1
            }
        }
        val sanitized = sanitizeText(text.toString(), Int.MAX_VALUE)
        val truncated = sanitized.length > MAX_SHARE_TEXT_CHARS
        val boundedText = sanitized.take(MAX_SHARE_TEXT_CHARS)
        val data = buildMap {
            putAll(privacyData(request, entry))
            if (boundedText.isNotBlank()) put(DATA_TEXT, boundedText)
            put(DATA_TRUNCATED, truncated.toString())
            put(DATA_OMITTED_CONTENT_COUNT, omitted.toString())
        }
        val localOnly = !entry.resultsPublic
        val summary = when {
            isError -> "MCP 工具 ${request.toolName} 返回错误"
            localOnly -> LOCAL_ONLY_REDACTED_SUMMARY
            boundedText.isNotBlank() -> boundedText
            else -> "MCP 工具 ${request.toolName} 已完成"
        }
        return ToolResult(
            requestId = request.id,
            status = if (isError) ToolStatus.Failed else ToolStatus.Succeeded,
            summary = summary,
            data = data,
            retryable = false,
        )
    }

    /** Required privacy metadata stamped on every MCP result, including failures. */
    fun privacyData(request: ToolRequest, entry: McpServerEntry): Map<String, String> {
        val localOnly = !entry.resultsPublic
        return mapOf(
            DATA_TOOL_NAME to request.toolName,
            DATA_PRIVACY to if (localOnly) MessagePrivacy.LocalOnly.name else MessagePrivacy.RemoteEligible.name,
            DATA_REQUIRES_LOCAL_MODEL to localOnly.toString(),
        )
    }

    fun sanitizeText(text: String, maxChars: Int): String =
        text.replace(controlCharacters, " ").trim().take(maxChars)
}

/** Deterministic JSON text (sorted keys) used to detect server-side schema changes. */
internal fun canonicalJson(value: Any?): String =
    when (value) {
        null, JSONObject.NULL -> "null"
        is Map<*, *> -> value.entries
            .map { (key, item) -> requireNotNull(key as? String) { "non-string JSON key" } to item }
            .sortedBy { (key, _) -> key }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, item) ->
                JSONObject.quote(key) + ":" + canonicalJson(item)
            }

        is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { canonicalJson(it) }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Int, is Long, is Short, is Byte -> value.toString()
        is Number -> {
            val double = value.toDouble()
            require(double.isFinite()) { "non-finite JSON number" }
            if (double == Math.floor(double) && kotlin.math.abs(double) < 1e15) {
                double.toLong().toString()
            } else {
                double.toString()
            }
        }

        is JSONObject -> canonicalJson(jsonObjectToMap(value))
        is JSONArray -> canonicalJson(jsonArrayToList(value))
        else -> throw IllegalArgumentException("unsupported JSON value ${value.javaClass.simpleName}")
    }

/** Safe replacement for unchecked `as Map<String, Any?>` casts: drops non-string keys. */
internal fun Any?.asStringKeyMap(): Map<String, Any?>? {
    val map = this as? Map<*, *> ?: return null
    return buildMap {
        map.forEach { (key, value) -> if (key is String) put(key, value) }
    }
}

internal fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        out[key] = unwrapJsonValue(obj.get(key))
    }
    return out
}

internal fun jsonArrayToList(array: JSONArray): List<Any?> =
    (0 until array.length()).map { index -> unwrapJsonValue(array.get(index)) }

internal fun unwrapJsonValue(value: Any?): Any? =
    when (value) {
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> jsonArrayToList(value)
        JSONObject.NULL -> null
        else -> value
    }

/** Converts plain Kotlin maps/lists back into org.json values for serialization. */
internal fun wrapJsonValue(value: Any?): Any =
    when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { obj ->
            value.forEach { (key, item) -> if (key is String) obj.put(key, wrapJsonValue(item)) }
        }

        is List<*> -> JSONArray().also { array -> value.forEach { array.put(wrapJsonValue(it)) } }
        else -> value
    }
