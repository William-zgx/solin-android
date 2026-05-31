package com.bytedance.zgx.pocketmind.tool

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
        }

        return null
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
}

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
            rule.validate(request.toolName, name, value)?.let { return it }
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
    fun validate(toolName: String, argumentName: String, value: String): String? {
        if (enum != null && !enum.contains(value)) {
            return "Tool ${toolName} argument $argumentName has invalid value"
        }

        return when (type?.lowercase()) {
            "string" -> {
                val minLength = this.minLength
                if (minLength != null && value.trim().length < minLength) {
                    "Tool $toolName argument $argumentName must have at least $minLength character(s)"
                } else {
                    val maxLength = this.maxLength
                    if (maxLength != null && value.length > maxLength) {
                        "Tool $toolName argument $argumentName must have at most $maxLength character(s)"
                    } else {
                        val pattern = this.pattern
                        if (pattern != null && !pattern.matches(value)) {
                            "Tool $toolName argument $argumentName does not match required pattern"
                        } else {
                            null
                        }
                    }
                }
            }

            "integer" -> {
                val numeric = value.toLongOrNull()
                if (numeric == null) {
                    "Tool $toolName argument $argumentName must be an integer"
                } else {
                    validateNumericRange(toolName, argumentName, numeric.toDouble())
                }
            }

            "number" -> {
                val numeric = value.toDoubleOrNull()
                if (numeric == null) {
                    "Tool $toolName argument $argumentName must be a number"
                } else {
                    validateNumericRange(toolName, argumentName, numeric)
                }
            }

            "boolean" -> {
                if (value.toBooleanStrictOrNull() == null) {
                    "Tool $toolName argument $argumentName must be true or false"
                } else {
                    null
                }
            }

            "array" -> {
                try {
                    org.json.JSONArray(value)
                    null
                } catch (_: Exception) {
                    "Tool $toolName argument $argumentName must be an array"
                }
            }

            "object" -> {
                try {
                    JSONObject(value)
                    null
                } catch (_: Exception) {
                    "Tool $toolName argument $argumentName must be an object"
                }
            }

            null -> null
            else -> null
        }
    }

    private fun validateNumericRange(
        toolName: String,
        argumentName: String,
        numeric: Double,
    ): String? {
        minimum?.let { min ->
            if (numeric < min) {
                return "Tool $toolName argument $argumentName must be at least $min"
            }
        }
        maximum?.let { max ->
            if (numeric > max) {
                return "Tool $toolName argument $argumentName must be at most $max"
            }
        }
        exclusiveMinimum?.let { min ->
            if (numeric <= min) {
                return "Tool $toolName argument $argumentName must be greater than $min"
            }
        }
        exclusiveMaximum?.let { max ->
            if (numeric >= max) {
                return "Tool $toolName argument $argumentName must be less than $max"
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
          "minimum": 1
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
          "minimum": 1
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
          "enum": ["all", "images", "videos", "audio", "documents", "downloads", "others"]
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

private val cancelReminderSchemaJson = """
    {
      "type": "object",
      "required": ["taskId"],
      "properties": {
        "taskId": {
          "type": "string",
          "minLength": 1
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
          "pattern": "^(https?|mailto|tel|sms|smsto|geo):.+"
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
          "pattern": "^[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+$"
        },
        "activityClass": {
          "type": "string",
          "minLength": 1
        },
        "action": {
          "type": "string",
          "minLength": 1
        },
        "data": {
          "type": "string",
          "minLength": 1
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

private val toolDefinitionsByName: Map<String, ToolDefinition> = listOf(
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            description = "打开系统 Wi-Fi 设置页，由用户在系统页面内继续操作。",
            inputSchemaJson = emptyObjectSchemaJson,
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
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsContacts,
                ToolPermission.RequiresAndroidRuntimePermission,
            ),
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Required,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
            title = "打开手电筒设置",
            description = "打开系统设置页，由用户手动完成手电筒相关操作。",
            inputSchemaJson = emptyObjectSchemaJson,
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
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsClipboard,
            ),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.SHARE_TEXT,
            title = "系统分享",
            description = "打开 Android 系统分享面板并填入文本，由用户选择目标应用后继续操作。",
            inputSchemaJson = shareTextSchemaJson,
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
            capability = ToolCapability.ExternalNavigation,
            permissions = setOf(ToolPermission.StartsExternalActivity),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OPEN_APP_INTENT,
            title = "打开应用 Intent",
            description = "打开指定应用（可选传入 activityClass、action 或 data Uri）进行更精细化跳转。",
            inputSchemaJson = openAppIntentSchemaJson,
            capability = ToolCapability.ExternalNavigation,
            permissions = setOf(ToolPermission.StartsExternalActivity),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
            title = "查询日历忙闲",
            description = "只读查询本机日历在指定 ISO 时间窗口内的忙闲区间，不读取标题、地点或参与人。",
            inputSchemaJson = calendarAvailabilitySchemaJson,
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsCalendar,
                ToolPermission.RequiresAndroidRuntimePermission,
            ),
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Required,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.QUERY_FOREGROUND_APP,
            title = "查询当前前台应用",
            description = "读取当前前台应用的应用名与包名（用户当前界面可见应用）。",
            inputSchemaJson = emptyObjectSchemaJson,
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.RequiresAndroidRuntimePermission,
            ),
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Required,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
            title = "查询近期通知",
            description = "读取当前应用最近一段时间的通知摘要，默认返回最近 5 条。",
            inputSchemaJson = recentNotificationSchemaJson,
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
            ),
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Required,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.QUERY_RECENT_FILES,
            title = "查询最近文件",
            description = "读取本机最近文件摘要，仅返回文件名与文件类型等最小信息。",
            inputSchemaJson = recentFilesSchemaJson,
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsFiles,
                ToolPermission.RequiresAndroidRuntimePermission,
            ),
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Required,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.CANCEL_REMINDER,
            title = "取消提醒",
            description = "在已安排的提醒列表中取消指定提醒任务，不再触发该提醒。",
            inputSchemaJson = cancelReminderSchemaJson,
            capability = ToolCapability.BackgroundTask,
            permissions = setOf(ToolPermission.RequiresAndroidRuntimePermission),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
        ),
    ),
).associateBy { it.spec.name }
