package com.bytedance.zgx.pocketmind.tool

import com.bytedance.zgx.pocketmind.action.MobileActionFunctions

class ToolRegistry private constructor(
    definitions: List<ToolDefinition>,
) {
    private val definitionsByName: Map<String, ToolDefinition> = definitions.associateBy { it.spec.name }
    private val orderedSpecs: List<ToolSpec> = definitions.map { it.spec }

    init {
        require(definitionsByName.size == definitions.size) { "Tool names must be unique." }
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
            ?: return request.rejected("Unknown tool: ${request.toolName}")

        val unknownArguments = request.arguments.keys - definition.allowedArguments
        if (unknownArguments.isNotEmpty()) {
            return request.rejected(
                "Tool ${request.toolName} does not accept argument(s): ${unknownArguments.sorted().joinToString()}",
            )
        }

        val missingArguments = definition.requiredArguments
            .filter { request.arguments[it].isNullOrBlank() }
        if (missingArguments.isNotEmpty()) {
            return request.rejected(
                "Tool ${request.toolName} requires argument(s): ${missingArguments.sorted().joinToString()}",
            )
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
    val requiredArguments: Set<String> = emptySet(),
    val optionalArguments: Set<String> = emptySet(),
) {
    val allowedArguments: Set<String> = requiredArguments + optionalArguments
}

private fun ToolRequest.rejected(summary: String): ToolResult =
    ToolResult(
        requestId = id,
        status = ToolStatus.Rejected,
        summary = summary,
        data = mapOf("toolName" to toolName),
    )

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

private val toolDefinitionsByName: Map<String, ToolDefinition> = listOf(
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            description = "打开系统 Wi-Fi 设置页，由用户在系统页面内继续操作。",
            inputSchemaJson = emptyObjectSchemaJson,
            capability = ToolCapability.DeviceSettings,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.SEARCH_MAPS,
            title = "地图搜索",
            description = "使用外部地图应用搜索地点或路线关键词。",
            inputSchemaJson = querySchemaJson,
            capability = ToolCapability.ExternalNavigation,
        ),
        requiredArguments = setOf("query"),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.WEB_SEARCH,
            title = "Web 搜索",
            description = "使用浏览器或系统搜索能力搜索网页内容。",
            inputSchemaJson = querySchemaJson,
            capability = ToolCapability.WebSearch,
        ),
        requiredArguments = setOf("query"),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.COMPOSE_EMAIL,
            title = "邮件草稿",
            description = "打开邮件应用并填入邮件草稿内容，不直接发送邮件。",
            inputSchemaJson = emailDraftSchemaJson,
            capability = ToolCapability.ExternalDraft,
        ),
        requiredArguments = setOf("body"),
        optionalArguments = setOf("subject"),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.CREATE_CALENDAR_EVENT,
            title = "日程草稿",
            description = "打开日历应用的新建事件页面并填入草稿内容。",
            inputSchemaJson = calendarDraftSchemaJson,
            capability = ToolCapability.ExternalDraft,
        ),
        requiredArguments = setOf("title"),
        optionalArguments = setOf("description"),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.CREATE_CONTACT_DRAFT,
            title = "联系人草稿",
            description = "打开联系人应用的新建联系人页面并填入草稿内容。",
            inputSchemaJson = contactDraftSchemaJson,
            capability = ToolCapability.ExternalDraft,
        ),
        requiredArguments = setOf("name"),
        optionalArguments = setOf("email", "phone"),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
            title = "打开手电筒设置",
            description = "打开系统设置页，由用户手动完成手电筒相关操作。",
            inputSchemaJson = emptyObjectSchemaJson,
            capability = ToolCapability.DeviceSettings,
        ),
    ),
).associateBy { it.spec.name }
