package com.bytedance.zgx.pocketmind.tool

import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.action.AppDeepTargets
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import org.json.JSONObject

class ToolRegistry private constructor(
    definitions: List<ToolDefinition>,
) {
    private val definitionsByName: Map<String, ToolDefinition> = definitions.associateBy { it.spec.name }
    private val orderedSpecs: List<ToolSpec> = definitions.map { it.spec }

    init {
        require(definitionsByName.size == definitions.size) { "Tool names must be unique." }
        definitions.forEach { definition ->
            definition.argumentValidator
        }
    }

    constructor() : this(definitionsFor(MobileActionFunctions.supported))

    fun specs(): List<ToolSpec> = orderedSpecs

    fun specFor(toolName: String): ToolSpec? = definitionsByName[toolName]?.spec

    fun privateOutputKeysFor(toolName: String): Set<String> =
        specFor(toolName)?.privateOutputKeys.orEmpty()

    fun pendingArgumentAllowlistFor(toolName: String): Set<String> =
        specFor(toolName)?.pendingArgumentAllowlist.orEmpty()

    fun redactedResultSummaryFor(toolName: String): String? =
        specFor(toolName)?.redactedResultSummary

    fun isKnownTool(toolName: String): Boolean = toolName in definitionsByName

    /**
     * Returns a rejection result when the request is invalid, or null when it can proceed to policy.
     */
    fun validate(request: ToolRequest): ToolResult? {
        val definition = definitionsByName[request.toolName]
            ?: return ToolResult(
                requestId = request.id,
                status = ToolStatus.Rejected,
                summary = "Unknown tool: ${request.toolName}",
                data = mapOf("toolName" to request.toolName),
                error = ToolError(ToolErrorCode.UnknownTool, "Unknown tool: ${request.toolName}"),
                retryable = false,
            )

        definition.argumentValidator.validate(request)?.let { reason ->
            return request.rejected(reason)
                .sanitizedPrivateNonSucceededResult(
                    request = request,
                    spec = definition.spec,
                    preserveSummary = true,
                )
        }

        return null
    }

    fun validateResult(request: ToolRequest, result: ToolResult): ToolResult? {
        if (result.status != ToolStatus.Succeeded) {
            val definition = definitionsByName[request.toolName] ?: return null
            val sanitized = result.sanitizedPrivateNonSucceededResult(
                request = request,
                spec = definition.spec,
            )
            return sanitized.takeIf { it != result }
        }
        if (result.requestId != request.id) {
            val summary = "Tool ${request.toolName} returned result for unexpected request id."
            return ToolResult(
                requestId = request.id,
                status = ToolStatus.Failed,
                summary = summary,
                data = mapOf("toolName" to request.toolName),
                error = ToolError(ToolErrorCode.InvalidResult, summary),
                retryable = false,
            )
        }
        val definition = definitionsByName[request.toolName]
            ?: return ToolResult(
                requestId = request.id,
                status = ToolStatus.Failed,
                summary = "Unknown tool while validating result: ${request.toolName}",
                data = mapOf("toolName" to request.toolName),
                error = ToolError(
                    ToolErrorCode.UnknownTool,
                    "Unknown tool while validating result: ${request.toolName}",
                ),
                retryable = false,
            )

        definition.resultValidator.validate(request, result)?.let { reason ->
            val summary = "Tool ${request.toolName} returned invalid result: $reason"
            return ToolResult(
                requestId = request.id,
                status = ToolStatus.Failed,
                summary = summary,
                data = mapOf("toolName" to request.toolName),
                error = ToolError(ToolErrorCode.InvalidResult, summary),
                retryable = false,
            )
        }

        return null
    }

    private fun ToolResult.sanitizedPrivateNonSucceededResult(
        request: ToolRequest,
        spec: ToolSpec,
        preserveSummary: Boolean = false,
    ): ToolResult {
        if (status == ToolStatus.Succeeded || spec.privateOutputKeys.isEmpty()) return this

        val sanitizedData = mutableMapOf<String, String>()
        data["specialAccess"]
            ?.takeIf { it in privateNonSucceededAllowedSpecialAccessValues }
            ?.let { sanitizedData["specialAccess"] = it }
        data["settingsAction"]
            ?.takeIf { it in privateNonSucceededAllowedSettingsActions }
            ?.let { sanitizedData["settingsAction"] = it }
        data["recoveryToolName"]
            ?.takeIf { it in privateNonSucceededAllowedRecoveryTools }
            ?.let { sanitizedData["recoveryToolName"] = it }
        sanitizedData["toolName"] = request.toolName
        sanitizedData["privacy"] = MessagePrivacy.LocalOnly.name
        sanitizedData["requiresLocalModel"] = true.toString()

        val sanitizedSummary = if (preserveSummary) {
            summary
        } else {
            privateNonSucceededResultSummary(
                toolName = request.toolName,
                status = status,
                errorCode = error?.code,
            )
        }

        return copy(
            requestId = request.id,
            summary = sanitizedSummary,
            data = sanitizedData,
            error = error?.copy(message = sanitizedSummary),
        )
    }

    private fun privateNonSucceededResultSummary(
        toolName: String,
        status: ToolStatus,
        errorCode: ToolErrorCode?,
    ): String =
        when (status) {
            ToolStatus.Succeeded -> "Tool $toolName completed."
            ToolStatus.Rejected -> "Tool $toolName was rejected before returning private local data."
            ToolStatus.Cancelled -> "Tool $toolName was cancelled before returning private local data."
            ToolStatus.Failed -> when (errorCode) {
                ToolErrorCode.PermissionDenied ->
                    "Tool $toolName requires local permission or special access."
                else -> "Tool $toolName failed before returning private local data."
            }
        }

    companion object {
        fun fromSupportedActions(supportedActions: Set<String> = MobileActionFunctions.supported): ToolRegistry =
            ToolRegistry(definitionsFor(supportedActions))
    }
}

private data class ToolDefinition(
    val spec: ToolSpec,
) {
    val argumentValidator: ToolArgumentValidator = ToolArgumentValidator.fromSchema(spec)
    val resultValidator: ToolResultDataValidator = ToolResultDataValidator.fromSchema(spec)
}

private val privateNonSucceededAllowedSpecialAccessValues = setOf(
    "usage_stats",
    "accessibility_screen_text",
)

private val privateNonSucceededAllowedSettingsActions = setOf(
    "android.settings.USAGE_ACCESS_SETTINGS",
    "android.settings.ACCESSIBILITY_SETTINGS",
)

private val privateNonSucceededAllowedRecoveryTools = setOf(
    MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
)

private data class ToolArgumentValidator(
    private val requiredArguments: Set<String>,
    private val properties: Map<String, PropertyRule>,
    private val additionalProperties: Boolean,
) {
    fun validate(request: ToolRequest): String? {
        if (!additionalProperties) {
            val unknownArguments = request.arguments.keys - properties.keys
            if (unknownArguments.isNotEmpty()) {
                return "Tool ${request.toolName} does not accept argument(s): " +
                    unknownArguments.sorted().joinToString()
            }
        }

        val missingArguments = requiredArguments
            .filter { argumentName ->
                val argumentValue = request.arguments[argumentName]
                val expectedType = properties[argumentName]?.type
                if (expectedType == "boolean") {
                    argumentValue == null
                } else {
                    argumentValue.isNullOrBlank()
                }
            }
        if (missingArguments.isNotEmpty()) {
            return "Tool ${request.toolName} requires argument(s): ${missingArguments.sorted().joinToString()}"
        }

        request.arguments.forEach { (name, value) ->
            val rule = properties[name] ?: return@forEach
            rule.validate(request.toolName, "argument", name, value)?.let { return it }
        }

        return null
    }

    companion object {
        fun fromSchema(spec: ToolSpec): ToolArgumentValidator {
            val schema = JSONObject(spec.inputSchemaJson)
            require(schema.optString("type") == "object") {
                "Tool ${spec.name} schema must declare type=object"
            }
            val propertiesJson = schema.optJSONObject("properties") ?: JSONObject()
            val properties = propertiesJson.keysSet().associateWith { propertyName ->
                val propertyJson = propertiesJson.optJSONObject(propertyName) ?: JSONObject()
                PropertyRule(
                    type = propertyJson.optStringOrNull("type"),
                    minLength = propertyJson.optIntOrNull("minLength"),
                    maxLength = propertyJson.optIntOrNull("maxLength"),
                    pattern = propertyJson.optStringOrNull("pattern")?.let(::Regex),
                    enum = propertyJson.optStringSetOrNull("enum")?.toSet(),
                    minimum = propertyJson.optDoubleOrNull("minimum"),
                    maximum = propertyJson.optDoubleOrNull("maximum"),
                    exclusiveMinimum = propertyJson.optDoubleOrNull("exclusiveMinimum"),
                    exclusiveMaximum = propertyJson.optDoubleOrNull("exclusiveMaximum"),
                )
            }
            val requiredArguments = schema.optStringSet("required")
            val unknownRequired = requiredArguments - properties.keys
            require(unknownRequired.isEmpty()) {
                "Tool ${spec.name} schema requires undeclared properties: ${unknownRequired.sorted().joinToString()}"
            }
            return ToolArgumentValidator(
                requiredArguments = requiredArguments,
                properties = properties,
                additionalProperties = schema.optBoolean("additionalProperties", true),
            )
        }
    }
}

private data class ToolResultDataValidator(
    private val requiredDataKeys: Set<String>,
    private val properties: Map<String, PropertyRule>,
    private val additionalProperties: Boolean,
) {
    fun validate(request: ToolRequest, result: ToolResult): String? {
        if (!additionalProperties) {
            val unknownKeys = result.data.keys - properties.keys
            if (unknownKeys.isNotEmpty()) {
                return "Tool ${request.toolName} result does not accept data key(s): " +
                    unknownKeys.sorted().joinToString()
            }
        }

        val missingKeys = requiredDataKeys
            .filter { key ->
                val value = result.data[key]
                val expectedType = properties[key]?.type
                if (expectedType == "boolean") {
                    value == null
                } else {
                    value.isNullOrBlank()
                }
            }
        if (missingKeys.isNotEmpty()) {
            return "Tool ${request.toolName} result requires data key(s): ${missingKeys.sorted().joinToString()}"
        }

        result.data.forEach { (name, value) ->
            val rule = properties[name] ?: return@forEach
            rule.validate(request.toolName, "result data", name, value)?.let { return it }
        }

        return null
    }

    companion object {
        fun fromSchema(spec: ToolSpec): ToolResultDataValidator {
            val schema = JSONObject(spec.outputSchemaJson)
            require(schema.optString("type") == "object") {
                "Tool ${spec.name} output schema must declare type=object"
            }
            val propertiesJson = schema.optJSONObject("properties") ?: JSONObject()
            val properties = propertiesJson.keysSet().associateWith { propertyName ->
                val propertyJson = propertiesJson.optJSONObject(propertyName) ?: JSONObject()
                PropertyRule(
                    type = propertyJson.optStringOrNull("type"),
                    minLength = propertyJson.optIntOrNull("minLength"),
                    maxLength = propertyJson.optIntOrNull("maxLength"),
                    pattern = propertyJson.optStringOrNull("pattern")?.let(::Regex),
                    enum = propertyJson.optStringSetOrNull("enum")?.toSet(),
                    minimum = propertyJson.optDoubleOrNull("minimum"),
                    maximum = propertyJson.optDoubleOrNull("maximum"),
                    exclusiveMinimum = propertyJson.optDoubleOrNull("exclusiveMinimum"),
                    exclusiveMaximum = propertyJson.optDoubleOrNull("exclusiveMaximum"),
                )
            }
            val requiredDataKeys = schema.optStringSet("required")
            val unknownRequired = requiredDataKeys - properties.keys
            require(unknownRequired.isEmpty()) {
                "Tool ${spec.name} output schema requires undeclared properties: " +
                    unknownRequired.sorted().joinToString()
            }
            return ToolResultDataValidator(
                requiredDataKeys = requiredDataKeys,
                properties = properties,
                additionalProperties = schema.optBoolean("additionalProperties", true),
            )
        }
    }
}

private data class PropertyRule(
    val type: String?,
    val minLength: Int?,
    val maxLength: Int?,
    val pattern: Regex?,
    val enum: Set<String>?,
    val minimum: Double?,
    val maximum: Double?,
    val exclusiveMinimum: Double?,
    val exclusiveMaximum: Double?,
) {
    fun validate(toolName: String, valueKind: String, valueName: String, value: String): String? {
        if (enum != null && !enum.contains(value)) {
            return "Tool ${toolName} $valueKind $valueName has invalid value"
        }

        return when (type?.lowercase()) {
            "string" -> {
                val minLength = this.minLength
                if (minLength != null && value.trim().length < minLength) {
                    "Tool $toolName $valueKind $valueName must have at least $minLength character(s)"
                } else {
                    val maxLength = this.maxLength
                    if (maxLength != null && value.length > maxLength) {
                        "Tool $toolName $valueKind $valueName must have at most $maxLength character(s)"
                    } else {
                        val pattern = this.pattern
                        if (pattern != null && !pattern.matches(value)) {
                            "Tool $toolName $valueKind $valueName does not match required pattern"
                        } else {
                            null
                        }
                    }
                }
            }

            "integer" -> {
                val numeric = value.toLongOrNull()
                if (numeric == null) {
                    "Tool $toolName $valueKind $valueName must be an integer"
                } else {
                    validateNumericRange(toolName, valueKind, valueName, numeric.toDouble())
                }
            }

            "number" -> {
                val numeric = value.toDoubleOrNull()
                if (numeric == null) {
                    "Tool $toolName $valueKind $valueName must be a number"
                } else {
                    validateNumericRange(toolName, valueKind, valueName, numeric)
                }
            }

            "boolean" -> {
                if (value.toBooleanStrictOrNull() == null) {
                    "Tool $toolName $valueKind $valueName must be true or false"
                } else {
                    null
                }
            }

            "array" -> {
                try {
                    org.json.JSONArray(value)
                    null
                } catch (_: Exception) {
                    "Tool $toolName $valueKind $valueName must be an array"
                }
            }

            "object" -> {
                try {
                    JSONObject(value)
                    null
                } catch (_: Exception) {
                    "Tool $toolName $valueKind $valueName must be an object"
                }
            }

            null -> null
            else -> null
        }
    }

    private fun validateNumericRange(
        toolName: String,
        valueKind: String,
        valueName: String,
        numeric: Double,
    ): String? {
        minimum?.let { min ->
            if (numeric < min) {
                return "Tool $toolName $valueKind $valueName must be at least $min"
            }
        }
        maximum?.let { max ->
            if (numeric > max) {
                return "Tool $toolName $valueKind $valueName must be at most $max"
            }
        }
        exclusiveMinimum?.let { min ->
            if (numeric <= min) {
                return "Tool $toolName $valueKind $valueName must be greater than $min"
            }
        }
        exclusiveMaximum?.let { max ->
            if (numeric >= max) {
                return "Tool $toolName $valueKind $valueName must be less than $max"
            }
        }
        return null
    }
}

private fun JSONObject.keysSet(): Set<String> {
    val result = linkedSetOf<String>()
    val iterator = keys()
    while (iterator.hasNext()) {
        result += iterator.next()
    }
    return result
}

private fun JSONObject.optStringSet(name: String): Set<String> {
    val array = optJSONArray(name) ?: return emptySet()
    return buildSet {
        for (index in 0 until array.length()) {
            add(array.getString(index))
        }
    }
}

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name)) optInt(name) else null

private fun JSONObject.optStringOrNull(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name)

private fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return optDouble(name)
}

private fun JSONObject.optStringSetOrNull(name: String): Set<String>? {
    val array = optJSONArray(name) ?: return null
    return buildSet {
        for (index in 0 until array.length()) {
            add(array.getString(index))
        }
    }
}

private fun definitionsFor(supportedActions: Set<String>): List<ToolDefinition> {
    val missingDefinitions = supportedActions - toolDefinitionsByName.keys
    require(missingDefinitions.isEmpty()) {
        "Missing tool definition(s): ${missingDefinitions.sorted().joinToString()}"
    }
    return supportedActions.map { toolDefinitionsByName.getValue(it) }
}

private val emptyObjectSchemaJson = """
    {
      "type": "object",
      "properties": {},
      "additionalProperties": false
    }
""".trimIndent()

private val querySchemaJson = """
    {
      "type": "object",
      "required": ["query"],
      "properties": {
        "query": {
          "type": "string",
          "minLength": 1
        },
        "searchMode": {
          "type": "string",
          "enum": ["general", "local"]
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val emailDraftSchemaJson = """
    {
      "type": "object",
      "required": ["body"],
      "properties": {
        "subject": {
          "type": "string"
        },
        "body": {
          "type": "string",
          "minLength": 1
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val calendarDraftSchemaJson = """
    {
      "type": "object",
      "required": ["title"],
      "properties": {
        "title": {
          "type": "string",
          "minLength": 1
        },
        "description": {
          "type": "string"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val contactDraftSchemaJson = """
    {
      "type": "object",
      "required": ["name"],
      "properties": {
        "name": {
          "type": "string",
          "minLength": 1
        },
        "email": {
          "type": "string"
        },
        "phone": {
          "type": "string"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val contactQuerySchemaJson = """
    {
      "type": "object",
      "required": ["query"],
      "properties": {
        "query": {
          "type": "string",
          "minLength": 1
        },
        "maxCount": {
          "type": "integer",
          "minimum": 1,
          "maximum": 20
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val reminderSchemaJson = """
    {
      "type": "object",
      "required": ["title", "delayMinutes"],
      "properties": {
        "title": {
          "type": "string",
          "minLength": 1
        },
        "body": {
          "type": "string"
        },
        "delayMinutes": {
          "type": "integer",
          "minimum": 1
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val recentNotificationSchemaJson = """
    {
      "type": "object",
      "properties": {
        "maxCount": {
          "type": "integer",
          "minimum": 1,
          "maximum": 20
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val recentFilesSchemaJson = """
    {
      "type": "object",
      "properties": {
        "kind": {
          "type": "string",
          "description": "文件类别。Android 13 及以上的 all 只包含已授权媒体；documents/downloads/others 需要系统文件选择器授权，不能通过宽泛文件权限直接读取。",
          "enum": ["all", "screenshots", "images", "videos", "audio", "documents", "downloads", "others"]
        },
        "maxCount": {
          "type": "integer",
          "minimum": 1,
          "maximum": 50
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val recentScreenshotOcrSchemaJson = """
    {
      "type": "object",
      "properties": {
        "maxCount": {
          "type": "integer",
          "minimum": 1,
          "maximum": 1
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val recentImageOcrSchemaJson = """
    {
      "type": "object",
      "properties": {
        "maxCount": {
          "type": "integer",
          "minimum": 1,
          "maximum": 3
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val currentScreenTextSchemaJson = """
    {
      "type": "object",
      "properties": {
        "maxChars": {
          "type": "integer",
          "minimum": 1,
          "maximum": 4000
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val cancelReminderSchemaJson = """
    {
      "type": "object",
      "required": ["taskId"],
      "properties": {
        "taskId": {
          "type": "string",
          "minLength": 1,
          "pattern": "^task-[A-Za-z0-9_-]+$"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val shareTextSchemaJson = """
    {
      "type": "object",
      "required": ["text"],
      "properties": {
        "text": {
          "type": "string",
          "minLength": 1
        },
        "title": {
          "type": "string"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val openDeepLinkSchemaJson = """
    {
      "type": "object",
      "required": ["uri"],
      "properties": {
        "uri": {
          "type": "string",
          "minLength": 1,
          "maxLength": 2048,
          "pattern": "^https://[^\\s/@]+(?:[:/].*)?$"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val openAppIntentSchemaJson = """
    {
      "type": "object",
      "required": ["packageName"],
      "properties": {
        "packageName": {
          "type": "string",
          "minLength": 3,
          "maxLength": 255,
          "pattern": "^[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+$"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val openAppDeepTargetSchemaJson = """
    {
      "type": "object",
      "required": ["targetId", "packageName"],
      "properties": {
        "targetId": {
          "type": "string",
          "enum": ["${AppDeepTargets.APP_DETAILS_SETTINGS_ID}"]
        },
        "packageName": {
          "type": "string",
          "minLength": 3,
          "maxLength": 255,
          "pattern": "^[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+$"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val calendarAvailabilitySchemaJson = """
    {
      "type": "object",
      "required": ["start", "end"],
      "properties": {
        "start": {
          "type": "string",
          "format": "date-time",
          "description": "Inclusive ISO-8601 start time with timezone."
        },
        "end": {
          "type": "string",
          "format": "date-time",
          "description": "Exclusive ISO-8601 end time with timezone. Window must be at most 31 days."
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val externalActivityOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "completionState",
        "completionVerified",
        "externalOutcome",
        "targetKind",
        "intentAction",
        "metadataPolicy",
        "rawPayloadIncluded"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "completionState": {"type": "string", "enum": ["ExternalActivityOpened"]},
        "completionVerified": {"type": "boolean"},
        "externalOutcome": {"type": "string"},
        "targetKind": {"type": "string", "minLength": 1},
        "intentAction": {"type": "string", "minLength": 1},
        "metadataPolicy": {"type": "string", "minLength": 1},
        "rawPayloadIncluded": {"type": "boolean"},
        "settingsAction": {"type": "string"},
        "specialAccess": {"type": "string"},
        "targetId": {"type": "string"},
        "targetPackage": {"type": "string"},
        "targetUriScheme": {"type": "string"},
        "targetUriHost": {"type": "string"},
        "targetUriPort": {"type": "integer"}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val reminderOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "taskId", "taskStatus", "triggerAtMillis", "recoveryToolName", "recoveryTaskId"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "taskId": {"type": "string", "minLength": 1, "pattern": "^task-[A-Za-z0-9_-]+$"},
        "taskStatus": {"type": "string", "enum": ["Scheduled"]},
        "triggerAtMillis": {"type": "integer", "minimum": 0},
        "recoveryToolName": {"type": "string", "enum": ["cancel_reminder"]},
        "recoveryTaskId": {"type": "string", "minLength": 1, "pattern": "^task-[A-Za-z0-9_-]+$"}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val cancelReminderOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "taskId", "taskStatus"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "taskId": {"type": "string", "minLength": 1, "pattern": "^task-[A-Za-z0-9_-]+$"},
        "taskStatus": {"type": "string", "enum": ["Cancelled"]}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val clipboardOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "privacy", "requiresLocalModel", "text", "truncated"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "text": {"type": "string", "minLength": 1},
        "truncated": {"type": "boolean"}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val foregroundAppOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "privacy", "requiresLocalModel", "packageName", "appLabel", "lastTimeUsedMillis"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "packageName": {"type": "string", "minLength": 1},
        "appLabel": {"type": "string", "minLength": 1},
        "lastTimeUsedMillis": {"type": "integer"}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val contactsOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "privacy", "requiresLocalModel", "query", "maxCount", "contactCount", "contactsJson"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "query": {"type": "string"},
        "maxCount": {"type": "integer", "minimum": 1, "maximum": 20},
        "contactCount": {"type": "integer", "minimum": 0},
        "contactsJson": {"type": "array"}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val notificationsOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "privacy", "requiresLocalModel", "maxCount", "notificationCount", "notificationsJson"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "maxCount": {"type": "integer", "minimum": 1, "maximum": 20},
        "notificationCount": {"type": "integer", "minimum": 0},
        "notificationsJson": {"type": "array"}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val recentFilesOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "privacy", "requiresLocalModel", "kind", "maxCount", "fileCount", "filesJson"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "kind": {"type": "string", "minLength": 1},
        "maxCount": {"type": "integer", "minimum": 1, "maximum": 50},
        "fileCount": {"type": "integer", "minimum": 0},
        "filesJson": {"type": "array"}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val recentImageOcrOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "source",
        "maxCount",
        "scannedCount",
        "ocrTextIncluded",
        "rawPayloadIncluded",
        "metadataPolicy"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "source": {"type": "string", "minLength": 1},
        "maxCount": {"type": "integer", "minimum": 1, "maximum": 3},
        "scannedCount": {"type": "integer", "minimum": 0},
        "name": {"type": "string"},
        "mimeType": {"type": "string"},
        "kind": {"type": "string"},
        "sizeBytes": {"type": "integer", "minimum": 0},
        "lastModifiedMillis": {"type": "integer"},
        "ocrText": {"type": "string", "minLength": 1},
        "truncated": {"type": "boolean"},
        "ocrTextIncluded": {"type": "boolean"},
        "rawPayloadIncluded": {"type": "boolean"},
        "metadataPolicy": {"type": "string", "minLength": 1}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val recentImageOcrPrivateOutputKeys = setOf(
    "name",
    "mimeType",
    "sizeBytes",
    "lastModifiedMillis",
    "ocrText",
)

private val currentScreenTextOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "source",
        "maxChars",
        "capturedAtMillis",
        "nodeCount",
        "truncated",
        "screenTextIncluded",
        "rawTreeIncluded",
        "metadataPolicy"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "source": {"type": "string", "minLength": 1},
        "maxChars": {"type": "integer", "minimum": 1, "maximum": 4000},
        "capturedAtMillis": {"type": "integer"},
        "nodeCount": {"type": "integer", "minimum": 0},
        "screenText": {"type": "string", "minLength": 1},
        "packageName": {"type": "string"},
        "truncated": {"type": "boolean"},
        "screenTextIncluded": {"type": "boolean"},
        "rawTreeIncluded": {"type": "boolean"},
        "metadataPolicy": {"type": "string", "minLength": 1}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val calendarAvailabilityOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "start",
        "end",
        "busyBlockCount",
        "freeBlockCount",
        "blocksJson"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "start": {"type": "string", "minLength": 1},
        "end": {"type": "string", "minLength": 1},
        "busyBlockCount": {"type": "integer", "minimum": 0},
        "freeBlockCount": {"type": "integer", "minimum": 0},
        "blocksJson": {"type": "array"}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val toolDefinitionsByName: Map<String, ToolDefinition> = listOf(
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            description = "打开系统 Wi-Fi 设置页，由用户在系统页面内继续操作。",
            inputSchemaJson = emptyObjectSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.DeviceSettings,
            permissions = setOf(ToolPermission.StartsExternalActivity),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
            title = "打开使用情况访问权限设置",
            description = "打开 Android 使用情况访问权限设置页，由用户手动为 PocketMind 授权。",
            inputSchemaJson = emptyObjectSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.DeviceSettings,
            permissions = setOf(ToolPermission.StartsExternalActivity),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.SEARCH_MAPS,
            title = "地图搜索",
            description = "使用外部地图应用搜索地点或路线关键词。",
            inputSchemaJson = querySchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.ExternalNavigation,
            permissions = setOf(ToolPermission.StartsExternalActivity),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.WEB_SEARCH,
            title = "Web 搜索",
            description = "使用浏览器或系统搜索能力搜索网页内容。",
            inputSchemaJson = querySchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.WebSearch,
            permissions = setOf(ToolPermission.StartsExternalActivity),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.COMPOSE_EMAIL,
            title = "邮件草稿",
            description = "打开邮件应用并填入邮件草稿内容，不直接发送邮件。",
            inputSchemaJson = emailDraftSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.ExternalDraft,
            permissions = setOf(
                ToolPermission.StartsExternalActivity,
                ToolPermission.SendsTextToExternalApp,
            ),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.CREATE_CALENDAR_EVENT,
            title = "日程草稿",
            description = "打开日历应用的新建事件页面并填入草稿内容。",
            inputSchemaJson = calendarDraftSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.ExternalDraft,
            permissions = setOf(
                ToolPermission.StartsExternalActivity,
                ToolPermission.SendsTextToExternalApp,
            ),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.CREATE_CONTACT_DRAFT,
            title = "联系人草稿",
            description = "打开联系人应用的新建联系人页面并填入草稿内容。",
            inputSchemaJson = contactDraftSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.ExternalDraft,
            permissions = setOf(
                ToolPermission.StartsExternalActivity,
                ToolPermission.SendsTextToExternalApp,
            ),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.QUERY_CONTACTS,
            title = "查询联系人",
            description = "读取通讯录中的联系人名称与电话，返回最小字段以辅助用户决策。",
            inputSchemaJson = contactQuerySchemaJson,
            outputSchemaJson = contactsOutputSchemaJson,
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsContacts,
                ToolPermission.RequiresAndroidRuntimePermission,
            ),
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Required,
            privateOutputKeys = setOf("query", "contactCount", "contactsJson"),
            redactedResultSummary = "已读取联系人摘要",
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
            title = "打开手电筒设置",
            description = "打开系统设置页，由用户手动完成手电筒相关操作。",
            inputSchemaJson = emptyObjectSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.DeviceSettings,
            permissions = setOf(ToolPermission.StartsExternalActivity),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.SCHEDULE_REMINDER,
            title = "后台提醒",
            description = "创建一个本地后台提醒，到点后通过系统通知提示用户。",
            inputSchemaJson = reminderSchemaJson,
            outputSchemaJson = reminderOutputSchemaJson,
            capability = ToolCapability.BackgroundTask,
            permissions = setOf(
                ToolPermission.SchedulesBackgroundWork,
                ToolPermission.PostsNotification,
                ToolPermission.RequiresAndroidRuntimePermission,
            ),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            description = "读取当前前台可访问的文本剪贴板内容，用于用户明确要求处理剪贴板时。",
            inputSchemaJson = emptyObjectSchemaJson,
            outputSchemaJson = clipboardOutputSchemaJson,
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsClipboard,
            ),
            privateOutputKeys = setOf("text"),
            redactedResultSummary = "已读取剪贴板文本",
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.SHARE_TEXT,
            title = "系统分享",
            description = "打开 Android 系统分享面板并填入文本，由用户选择目标应用后继续操作。",
            inputSchemaJson = shareTextSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.ExternalShare,
            permissions = setOf(
                ToolPermission.StartsExternalActivity,
                ToolPermission.SendsTextToExternalApp,
            ),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OPEN_DEEP_LINK,
            title = "打开深链",
            description = "打开外部链接或深度链接，用户可在跳转后的应用继续操作。",
            inputSchemaJson = openDeepLinkSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.ExternalNavigation,
            permissions = setOf(ToolPermission.StartsExternalActivity),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OPEN_APP_INTENT,
            title = "打开应用 Intent",
            description = "仅通过 packageName 打开指定应用启动页；不会传入额外 Intent 参数。",
            inputSchemaJson = openAppIntentSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.ExternalNavigation,
            permissions = setOf(ToolPermission.StartsExternalActivity),
            pendingArgumentAllowlist = setOf("packageName"),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OPEN_APP_DEEP_TARGET,
            title = "打开应用深层目标",
            description = "仅通过 allowlisted targetId 打开固定应用目标；不会接受任意 action、URI、activity 或 extras。",
            inputSchemaJson = openAppDeepTargetSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.ExternalNavigation,
            permissions = setOf(ToolPermission.StartsExternalActivity),
            pendingArgumentAllowlist = setOf("targetId", "packageName"),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
            title = "查询日历忙闲",
            description = "只读查询本机日历在指定 ISO 时间窗口内的忙闲区间，不读取标题、地点或参与人。",
            inputSchemaJson = calendarAvailabilitySchemaJson,
            outputSchemaJson = calendarAvailabilityOutputSchemaJson,
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsCalendar,
                ToolPermission.RequiresAndroidRuntimePermission,
            ),
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("start", "end"),
            privateOutputKeys = setOf("start", "end", "busyBlockCount", "freeBlockCount", "blocksJson"),
            redactedResultSummary = "已读取日历忙闲摘要",
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.QUERY_FOREGROUND_APP,
            title = "查询当前前台应用",
            description = "读取当前前台应用的应用名与包名（用户当前界面可见应用）。",
            inputSchemaJson = emptyObjectSchemaJson,
            outputSchemaJson = foregroundAppOutputSchemaJson,
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
            ),
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Required,
            privateOutputKeys = setOf("packageName", "appLabel", "lastTimeUsedMillis"),
            redactedResultSummary = "已读取当前前台应用",
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
            title = "查询近期通知",
            description = "读取当前应用最近一段时间的通知摘要，默认返回最近 5 条。",
            inputSchemaJson = recentNotificationSchemaJson,
            outputSchemaJson = notificationsOutputSchemaJson,
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
            ),
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("maxCount"),
            privateOutputKeys = setOf("notificationCount", "notificationsJson"),
            redactedResultSummary = "已读取近期通知摘要",
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.QUERY_RECENT_FILES,
            title = "查询最近文件",
            description = "读取本机最近文件摘要，仅返回文件名与文件类型等最小信息。Android 13 及以上仅直接支持已授权媒体；文档、下载与其他非媒体文件需要系统文件选择器授权。",
            inputSchemaJson = recentFilesSchemaJson,
            outputSchemaJson = recentFilesOutputSchemaJson,
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsFiles,
                ToolPermission.RequiresAndroidRuntimePermission,
            ),
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("kind", "maxCount"),
            privateOutputKeys = setOf("fileCount", "filesJson"),
            redactedResultSummary = "已读取最近文件摘要",
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            title = "读取最近截图 OCR",
            description = "在用户确认后读取最近 1 张截图像素并在本地提取 OCR 文本；不保存 URI、路径、原图或像素。",
            inputSchemaJson = recentScreenshotOcrSchemaJson,
            outputSchemaJson = recentImageOcrOutputSchemaJson,
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsFiles,
                ToolPermission.RequiresAndroidRuntimePermission,
            ),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("maxCount"),
            privateOutputKeys = recentImageOcrPrivateOutputKeys,
            redactedResultSummary = "已读取最近截图 OCR 摘录",
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            title = "读取最近图片 OCR",
            description = "在用户确认后扫描最近图片像素并在本地提取第一条 OCR 文本；不保存 URI、路径、原图或像素。",
            inputSchemaJson = recentImageOcrSchemaJson,
            outputSchemaJson = recentImageOcrOutputSchemaJson,
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsFiles,
                ToolPermission.RequiresAndroidRuntimePermission,
            ),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("maxCount"),
            privateOutputKeys = recentImageOcrPrivateOutputKeys,
            redactedResultSummary = "已读取最近图片 OCR 摘录",
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            title = "读取当前屏幕文本",
            description = "在用户确认后读取当前屏幕的 Accessibility 可访问文本快照；不读取截图、像素、坐标、节点 ID 或完整节点树。",
            inputSchemaJson = currentScreenTextSchemaJson,
            outputSchemaJson = currentScreenTextOutputSchemaJson,
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsAccessibilityText,
            ),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("maxChars"),
            privateOutputKeys = setOf("capturedAtMillis", "nodeCount", "screenText", "packageName"),
            redactedResultSummary = "已读取当前屏幕可访问文本快照",
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.CANCEL_REMINDER,
            title = "取消提醒",
            description = "在已安排的提醒列表中取消指定提醒任务，不再触发该提醒。",
            inputSchemaJson = cancelReminderSchemaJson,
            outputSchemaJson = cancelReminderOutputSchemaJson,
            capability = ToolCapability.BackgroundTask,
            permissions = emptySet(),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("taskId"),
        ),
    ),
).associateBy { it.spec.name }
