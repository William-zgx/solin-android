package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.AppDeepTargets
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.SystemSettingsTargets
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrContract
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
            validateRuntimePermissionDescriptorContract(definition.spec)
        }
    }

    constructor() : this(BuiltInToolProvider)

    constructor(vararg providers: ToolProvider) : this(definitionsFor(providers.toList()))

    fun specs(): List<ToolSpec> = orderedSpecs

    fun specFor(toolName: String): ToolSpec? = definitionsByName[toolName]?.spec

    fun privateOutputKeysFor(toolName: String): Set<String> =
        specFor(toolName)?.privateOutputKeys.orEmpty()

    fun pendingArgumentAllowlistFor(toolName: String): Set<String> =
        specFor(toolName)?.pendingArgumentAllowlist.orEmpty()

    fun androidRuntimePermissionSpecsFor(toolName: String): List<AndroidRuntimePermissionSpec> =
        specFor(toolName)?.androidRuntimePermissions.orEmpty()

    fun redactedResultSummaryFor(toolName: String): String? =
        specFor(toolName)?.redactedResultSummary

    fun toolNamesWithTag(tag: ToolCapabilityTag): Set<String> =
        orderedSpecs
            .filter { spec -> tag in spec.tags }
            .mapTo(linkedSetOf()) { spec -> spec.name }

    fun hasTag(toolName: String, tag: ToolCapabilityTag): Boolean =
        specFor(toolName)?.tags?.contains(tag) == true

    fun startsDeviceControlSession(toolName: String): Boolean =
        hasTag(toolName, ToolCapabilityTag.DeviceControlSession)

    fun isOpenAppLaunchTool(toolName: String): Boolean =
        hasTag(toolName, ToolCapabilityTag.OpenAppLaunch)

    fun requiresSequentialLocalModelBeforeTail(toolName: String): Boolean {
        val spec = specFor(toolName) ?: return false
        return ToolCapabilityTag.SequentialLocalContinuation in spec.tags ||
            spec.resultContinuationPolicy == ToolResultContinuationPolicy.LocalEvidence ||
            spec.privateOutputKeys.isNotEmpty()
    }

    fun isLowRiskDeviceActionConfirmationSkippable(request: ToolRequest): Boolean =
        when {
            hasTag(request.toolName, ToolCapabilityTag.LowRiskDeviceAction) -> true
            !hasTag(request.toolName, ToolCapabilityTag.ConditionalLowRiskDeviceAction) -> false
            request.toolName == MobileActionFunctions.UI_TAP ->
                !request.arguments["target"].orEmpty().containsHighRiskUiActionTarget()
            request.toolName == MobileActionFunctions.OPEN_SYSTEM_SETTINGS ->
                request.arguments["target"].orEmpty() in SystemSettingsTargets.confirmationBypassEligible
            else -> false
        }

    fun isLowRiskAppControlContinuationTool(request: ToolRequest): Boolean =
        isLowRiskDeviceActionConfirmationSkippable(request) &&
            hasTag(request.toolName, ToolCapabilityTag.LowRiskAppControlContinuation)

    fun isCheckpointedUiActionTool(toolName: String): Boolean =
        hasTag(toolName, ToolCapabilityTag.CheckpointedUiAction)

    fun isBackgroundSkillAllowedTool(toolName: String): Boolean =
        hasTag(toolName, ToolCapabilityTag.BackgroundSkillAllowed)

    fun specialAccessTagsFor(toolName: String): Set<ToolCapabilityTag> =
        specFor(toolName)
            ?.tags
            ?.filterTo(linkedSetOf()) { tag ->
                tag == ToolCapabilityTag.UsageStatsSpecialAccess ||
                    tag == ToolCapabilityTag.AccessibilityScreenTextSpecialAccess ||
                    tag == ToolCapabilityTag.AccessibilityDeviceControlSpecialAccess
            }
            .orEmpty()

    fun isLowRiskRestoredExternalOutcomePopupSkippable(
        toolName: String,
        result: ToolResult,
    ): Boolean =
        when {
            hasTag(toolName, ToolCapabilityTag.RestoredExternalOutcomePopupSkippable) -> true
            !hasTag(toolName, ToolCapabilityTag.ConditionalRestoredExternalOutcomePopupSkippable) -> false
            toolName == MobileActionFunctions.OPEN_SYSTEM_SETTINGS ->
                result.data["targetId"].orEmpty() in SystemSettingsTargets.confirmationBypassEligible
            else -> false
        }

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
        toolSpecificArgumentInvariant(request)?.let { reason ->
            return request.rejected(reason)
                .sanitizedPrivateNonSucceededResult(
                    request = request,
                    spec = definition.spec,
                    preserveSummary = true,
                )
        }

        return null
    }

    fun validatePublicEvidenceBatchRequest(request: ToolRequest): ToolResult? {
        validate(request)?.let { return it }
        val spec = specFor(request.toolName)
            ?: return request.rejected("Unknown tool: ${request.toolName}")
        if (!spec.isPublicEvidenceBatchEligible()) {
            return request.rejected(
                "Tool ${request.toolName} is not eligible for parallel public evidence execution.",
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
            return request.invalidResultFailure(summary, definitionsByName[request.toolName]?.spec)
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
            return request.invalidResultFailure(summary, definition.spec)
        }
        privateOutputResultInvariant(definition.spec, result)?.let { reason ->
            val summary = "Tool ${request.toolName} returned invalid result: $reason"
            return request.invalidResultFailure(summary, definition.spec)
        }
        externalActivityResultInvariant(definition.spec, request, result)?.let { reason ->
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
        privateNonSucceededAllowedDiagnosticKeys.forEach { key ->
            data[key]
                ?.takeIf { value -> privateNonSucceededDiagnosticValueAllowed(key, value) }
                ?.let { value -> sanitizedData[key] = value }
        }
        if (spec.capability == ToolCapability.DeviceControl) {
            privateNonSucceededDeviceControlObservationKeys.forEach { key ->
                data[key]
                    ?.takeIf { key in spec.privateOutputKeys }
                    ?.takeIf { value -> privateNonSucceededDeviceControlObservationValueAllowed(key, value) }
                    ?.let { value -> sanitizedData[key] = value }
            }
        }
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

    private fun ToolRequest.invalidResultFailure(summary: String, spec: ToolSpec?): ToolResult {
        val data = mapOf("toolName" to toolName) +
            if (spec?.privateOutputKeys?.isNotEmpty() == true) {
                mapOf(
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to true.toString(),
                )
            } else {
                emptyMap()
            }
        return ToolResult(
            requestId = id,
            status = ToolStatus.Failed,
            summary = summary,
            data = data,
            error = ToolError(ToolErrorCode.InvalidResult, summary),
            retryable = false,
        )
    }

    companion object {
        fun fromSupportedActions(supportedActions: Set<String> = builtInToolNames()): ToolRegistry =
            ToolRegistry(definitionsFor(supportedActions))
    }
}

private fun validateRuntimePermissionDescriptorContract(spec: ToolSpec) {
    val hasMarker = ToolPermission.RequiresAndroidRuntimePermission in spec.permissions
    val hasDescriptors = spec.androidRuntimePermissions.isNotEmpty()
    require(hasMarker == hasDescriptors) {
        "Tool ${spec.name} Android runtime permission marker and descriptor must both be present or absent."
    }
    val argumentNames = spec.inputSchemaJson.schemaPropertyNames()
    spec.androidRuntimePermissions.forEach { runtimePermission ->
        val argumentName = runtimePermission.argumentName ?: return@forEach
        require(argumentName in argumentNames) {
            "Tool ${spec.name} Android runtime permission argument $argumentName is not declared in input schema."
        }
    }
}


private fun toolSpecificArgumentInvariant(request: ToolRequest): String? =
    when (request.toolName) {
        MobileActionFunctions.SCHEDULE_REMINDER -> {
            val hasDelay = !request.arguments["delayMinutes"].isNullOrBlank()
            val hasTriggerAt = !request.arguments["triggerAtMillis"].isNullOrBlank()
            if (hasDelay == hasTriggerAt) {
                "Tool ${request.toolName} requires exactly one of delayMinutes or triggerAtMillis"
            } else {
                null
            }
        }

        else -> null
    }

private fun privateOutputResultInvariant(
    spec: ToolSpec,
    result: ToolResult,
): String? {
    if (spec.privateOutputKeys.isEmpty()) return null
    if (result.data["privacy"] != MessagePrivacy.LocalOnly.name) {
        return "private output result requires privacy=LocalOnly"
    }
    if (result.data["requiresLocalModel"]?.toBooleanStrictOrNull() != true) {
        return "private output result requires requiresLocalModel=true"
    }
    return null
}

private fun externalActivityResultInvariant(
    spec: ToolSpec,
    request: ToolRequest,
    result: ToolResult,
): String? {
    if (ToolPermission.StartsExternalActivity !in spec.permissions) return null
    val completionState = result.data["completionState"] ?: return null
    if (completionState != "ExternalActivityOpened") return null
    val completionVerified = result.data["completionVerified"]?.toBooleanStrictOrNull()
        ?: return "Tool ${request.toolName} result completionVerified must be true or false"
    val externalOutcome = result.data["externalOutcome"]
    val externalOutcomeSource = result.data["externalOutcomeSource"]
    return when {
        externalOutcomeSource == "Unknown" && externalOutcome != "Unknown" ->
            "external outcome source Unknown requires externalOutcome=Unknown"

        externalOutcomeSource == "UserConfirmed" && externalOutcome == "Unknown" ->
            "user-confirmed external outcome cannot be Unknown"

        completionVerified && externalOutcome != "Completed" ->
            "completionVerified=true requires externalOutcome=Completed"

        !completionVerified && externalOutcome == "Completed" ->
            "externalOutcome=Completed requires completionVerified=true"

        completionVerified && externalOutcomeSource != "UserConfirmed" ->
            "completionVerified=true requires externalOutcomeSource=UserConfirmed"

        else -> null
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
    "accessibility_device_control",
    CurrentScreenshotOcrContract.CONSENT_REASON,
)

private val privateNonSucceededAllowedSettingsActions = setOf(
    "android.settings.SETTINGS",
    "android.settings.BLUETOOTH_SETTINGS",
    "android.settings.LOCATION_SOURCE_SETTINGS",
    "android.settings.NOTIFICATION_SETTINGS",
    "android.settings.DISPLAY_SETTINGS",
    "android.settings.SOUND_SETTINGS",
    "android.settings.BATTERY_SAVER_SETTINGS",
    "android.settings.WIRELESS_SETTINGS",
    "android.settings.AIRPLANE_MODE_SETTINGS",
    "android.settings.INPUT_METHOD_SETTINGS",
    "android.settings.USAGE_ACCESS_SETTINGS",
    "android.settings.ACCESSIBILITY_SETTINGS",
)

private val privateNonSucceededAllowedRecoveryTools = setOf(
    MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
)

private val privateNonSucceededAllowedDiagnosticKeys = setOf(
    "actionType",
    "status",
    "retryable",
    "failureKind",
    "searchVerificationStatus",
    "searchVerificationEvidence",
)

private val privateNonSucceededDeviceControlObservationKeys = setOf(
    "beforeObservationId",
    "afterObservationId",
    "verificationSummary",
    "screenObservationDiffSummary",
    "beforeScreenObservationJson",
    "afterScreenObservationJson",
)

private val privateNonSucceededAllowedFailureKinds = setOf(
    "node_not_found",
    "page_changed",
    "permission_missing",
    "keyboard_obscured",
    "timeout",
    "app_not_foreground",
    "search_entry_not_found",
    "editable_not_found",
    "submit_not_found",
    "result_not_verified",
    "dangerous_action",
    "unknown",
)

private fun privateNonSucceededDiagnosticValueAllowed(key: String, value: String): Boolean {
    if (value.length > 64 || !value.matches(Regex("""[A-Za-z0-9_-]+"""))) return false
    return key != "failureKind" || value in privateNonSucceededAllowedFailureKinds
}

private fun privateNonSucceededDeviceControlObservationValueAllowed(
    key: String,
    value: String,
): Boolean =
    when (key) {
        "beforeObservationId",
        "afterObservationId" ->
            value.length <= 96 && value.matches(Regex("""[A-Za-z0-9_.:-]+"""))

        "verificationSummary" -> value.length <= 320
        "screenObservationDiffSummary" -> value.length <= 2_048
        "beforeScreenObservationJson",
        "afterScreenObservationJson" -> value.isLocalOnlyScreenObservationJson()

        else -> false
    }

private fun String.isLocalOnlyScreenObservationJson(): Boolean {
    if (isBlank() || length > 80_000) return false
    return runCatching {
        val json = JSONObject(this)
        json.optString("privacyLevel") == MessagePrivacy.LocalOnly.name &&
            json.optJSONArray("elements") != null
    }.getOrDefault(false)
}


private fun definitionsFor(supportedActions: Set<String>): List<ToolDefinition> {
    val missingDefinitions = supportedActions - toolDefinitionsByName.keys
    require(missingDefinitions.isEmpty()) {
        "Missing tool definition(s): ${missingDefinitions.sorted().joinToString()}"
    }
    return supportedActions.map { toolDefinitionsByName.getValue(it) }
}

private fun builtInToolNames(): Set<String> =
    builtInToolDefinitions.mapTo(linkedSetOf()) { definition -> definition.spec.name }

private fun definitionsFor(providers: List<ToolProvider>): List<ToolDefinition> =
    providers.flatMap { provider ->
        provider.specs().map(::ToolDefinition)
    }

private fun String.containsHighRiskUiActionTarget(): Boolean {
    val normalized = lowercase()
    return listOf(
        "发送",
        "删除",
        "支付",
        "付款",
        "转账",
        "下单",
        "提交",
        "发布",
        "购买",
        "确认",
        "send",
        "delete",
        "pay",
        "transfer",
        "submit",
        "post",
        "publish",
        "buy",
        "order",
        "confirm",
    ).any { normalized.contains(it) }
}

private object BuiltInToolProvider : ToolProvider {
    override fun specs(): List<ToolSpec> =
        builtInToolDefinitions.map { definition -> definition.spec }
}

private val emptyObjectSchemaJson = """
    {
      "type": "object",
      "properties": {},
      "additionalProperties": false
    }
""".trimIndent()

private val systemSettingsSchemaJson = """
    {
      "type": "object",
      "required": ["target"],
      "properties": {
        "target": {
          "type": "string",
          "enum": [
            "${SystemSettingsTargets.GENERAL}",
            "${SystemSettingsTargets.BLUETOOTH}",
            "${SystemSettingsTargets.LOCATION}",
            "${SystemSettingsTargets.NOTIFICATION}",
            "${SystemSettingsTargets.DISPLAY}",
            "${SystemSettingsTargets.SOUND}",
            "${SystemSettingsTargets.BATTERY_SAVER}",
            "${SystemSettingsTargets.NETWORK}",
            "${SystemSettingsTargets.AIRPLANE_MODE}",
            "${SystemSettingsTargets.INPUT_METHOD}",
            "${SystemSettingsTargets.ACCESSIBILITY}"
          ]
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private const val CURRENT_SCREEN_TEXT_SOURCE = "accessibility_active_window"
private const val CURRENT_SCREEN_TEXT_METADATA_POLICY =
    "accessibility_text_local_only_no_node_ids_bounds_or_hierarchy_persisted"
private const val DEVICE_CONTROL_SOURCE = "accessibility_active_window"
private const val DEVICE_CONTROL_METADATA_POLICY =
    "accessibility_control_local_only_transient_node_ids_no_pixels_persisted"
private val currentScreenshotOcrSchemaJson = CurrentScreenshotOcrContract.INPUT_SCHEMA_JSON.trimIndent()

private val querySchemaJson = """
    {
      "type": "object",
      "required": ["query"],
      "properties": {
        "query": {
          "type": "string",
          "description": "搜索关键词，不要直接复制用户原文；保留实体、主题、限定词，去掉“请帮我/是什么/有哪些”等寒暄和疑问词。",
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

private val webSearchInputSchemaJson = """
    {
      "type": "object",
      "required": ["query"],
      "properties": {
        "query": {
          "type": "string",
          "description": "模型理解后的搜索关键词，不要直接复制用户原文；保留实体、主题、限定词和必要时效词，去掉“请帮我/是什么/有哪些”等寒暄和疑问词；比较或多主体问题优先拆成多次独立 web_search。",
          "minLength": 1
        },
        "searchMode": {
          "type": "string",
          "enum": ["general", "weather_current"]
        },
        "freshness": {
          "type": "string",
          "description": "搜索时效。查询含最新/目前/当前/现在/今日/热门/排行/latest/current/recent/trending/hottest 或当前年份等当前性语义时应使用 current；缺省时宿主也会按 query 推断。",
          "enum": ["any_time", "current"]
        },
        "maxResults": {
          "type": "integer",
          "minimum": 1,
          "maximum": 5
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val webSearchOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "privacy", "requiresLocalModel", "query", "source", "summaryText", "resultsJson"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["RemoteEligible"]},
        "requiresLocalModel": {"type": "boolean"},
        "query": {"type": "string", "minLength": 1},
        "source": {
          "type": "string",
          "enum": ["open_meteo", "duckduckgo", "duckduckgo_html", "duckduckgo_lite"]
        },
        "searchMode": {
          "type": "string",
          "enum": ["general", "weather_current"]
        },
        "retrievedAt": {
          "type": "string",
          "minLength": 1
        },
        "freshness": {
          "type": "string",
          "enum": ["any_time", "current"]
        },
        "maxResults": {
          "type": "integer",
          "minimum": 1,
          "maximum": 5
        },
        "summaryText": {
          "type": "string",
          "minLength": 1,
          "maxLength": 1203
        },
        "resultsJson": {
          "type": "string",
          "contentMediaType": "application/json",
          "minLength": 1,
          "maxLength": 4003
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
      "required": ["title"],
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
        },
        "triggerAtMillis": {
          "type": "integer",
          "minimum": 0
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val systemAlarmSchemaJson = """
    {
      "type": "object",
      "required": ["hour", "minutes"],
      "properties": {
        "hour": {
          "type": "integer",
          "minimum": 0,
          "maximum": 23
        },
        "minutes": {
          "type": "integer",
          "minimum": 0,
          "maximum": 59
        },
        "message": {
          "type": "string",
          "maxLength": 120
        },
        "recurrence": {
          "type": "string",
          "enum": ["once", "daily"]
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val systemTimerSchemaJson = """
    {
      "type": "object",
      "required": ["lengthSeconds"],
      "properties": {
        "lengthSeconds": {
          "type": "integer",
          "minimum": 1,
          "maximum": 86400
        },
        "message": {
          "type": "string",
          "maxLength": 120
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val periodicCheckSchemaJson = """
    {
      "type": "object",
      "required": ["enabled"],
      "properties": {
        "enabled": {
          "type": "boolean",
          "description": "true to enable the local reminder periodic check, false to disable it."
        },
        "intervalMinutes": {
          "type": "integer",
          "minimum": 60,
          "maximum": 1440
        },
        "minNotificationSpacingMinutes": {
          "type": "integer",
          "minimum": 60,
          "maximum": 1440
        },
        "overdueGraceMinutes": {
          "type": "integer",
          "minimum": 5,
          "maximum": 10080
        },
        "requiresBatteryNotLow": {
          "type": "boolean"
        },
        "requiresCharging": {
          "type": "boolean"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val backgroundTasksQuerySchemaJson = """
    {
      "type": "object",
      "properties": {
        "scope": {
          "type": "string",
          "description": "查询范围：active=已安排/运行中的后台任务，history=最近完成/取消/失败历史，policy=周期检查策略，all=同时返回任务摘要与周期检查策略。默认 active。",
          "enum": ["active", "history", "policy", "all"]
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
          "description": "文件类别。该工具只直接查询已授权媒体；Android 13 及以上不提供 documents/downloads/others 的可执行直接读取路径，非媒体文件应由用户通过系统文件选择器或分享入口主动提供。",
          "enum": ["all", "screenshots", "images", "videos", "audio"]
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
          "description": "Maximum characters returned from the active-window Accessibility 可访问文本快照；不是截图、OCR、视觉/VLM 或语义屏幕理解。",
          "minimum": 1,
          "maximum": 4000
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val observeCurrentScreenSchemaJson = """
    {
      "type": "object",
      "properties": {
        "maxTextChars": {
          "type": "integer",
          "description": "Maximum characters returned from the active-window Accessibility text summary.",
          "minimum": 1,
          "maximum": 4000
        },
        "maxNodes": {
          "type": "integer",
          "description": "Maximum visible Accessibility nodes returned with transient node ids and bounds.",
          "minimum": 1,
          "maximum": 120
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val uiTapSchemaJson = """
    {
      "type": "object",
      "required": ["target"],
      "properties": {
        "target": {
          "type": "string",
          "description": "Transient node id from observe_current_screen, or visible text/contentDescription to match.",
          "minLength": 1,
          "maxLength": 200
        },
        "timeoutMillis": {
          "type": "integer",
          "minimum": 100,
          "maximum": 10000
        },
        "expectedPackageName": {
          "type": "string",
          "description": "Optional foreground package that must still be active before executing this UI action.",
          "minLength": 1,
          "maxLength": 200
        },
        "targetPackageName": {
          "type": "string",
          "description": "Optional alias for expectedPackageName, used by external planners to bind the foreground app.",
          "minLength": 1,
          "maxLength": 200
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val uiTypeTextSchemaJson = """
    {
      "type": "object",
      "required": ["text"],
      "properties": {
        "text": {
          "type": "string",
          "minLength": 1,
          "maxLength": 2000
        },
        "target": {
          "type": "string",
          "description": "Optional transient node id or visible label for the editable field.",
          "minLength": 1,
          "maxLength": 200
        },
        "timeoutMillis": {
          "type": "integer",
          "minimum": 100,
          "maximum": 10000
        },
        "allowClipboardPasteFallback": {
          "type": "boolean",
          "description": "When true, allows falling back to temporary clipboard paste if direct Accessibility text setting fails. Defaults to false."
        },
        "expectedPackageName": {
          "type": "string",
          "description": "Optional foreground package that must still be active before executing this UI action.",
          "minLength": 1,
          "maxLength": 200
        },
        "targetPackageName": {
          "type": "string",
          "description": "Optional alias for expectedPackageName, used by external planners to bind the foreground app.",
          "minLength": 1,
          "maxLength": 200
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val uiSubmitSearchSchemaJson = """
    {
      "type": "object",
      "properties": {
        "timeoutMillis": {
          "type": "integer",
          "minimum": 100,
          "maximum": 10000
        },
        "expectedPackageName": {
          "type": "string",
          "description": "Optional foreground package that must still be active before executing this UI action.",
          "minLength": 1,
          "maxLength": 200
        },
        "targetPackageName": {
          "type": "string",
          "description": "Optional alias for expectedPackageName, used by external planners to bind the foreground app.",
          "minLength": 1,
          "maxLength": 200
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val uiScrollSchemaJson = """
    {
      "type": "object",
      "required": ["direction"],
      "properties": {
        "direction": {
          "type": "string",
          "enum": ["up", "down", "left", "right", "forward", "backward"]
        },
        "target": {
          "type": "string",
          "description": "Optional transient node id or visible label for the scroll container.",
          "minLength": 1,
          "maxLength": 200
        },
        "timeoutMillis": {
          "type": "integer",
          "minimum": 100,
          "maximum": 10000
        },
        "expectedPackageName": {
          "type": "string",
          "description": "Optional foreground package that must still be active before executing this UI action.",
          "minLength": 1,
          "maxLength": 200
        },
        "targetPackageName": {
          "type": "string",
          "description": "Optional alias for expectedPackageName, used by external planners to bind the foreground app.",
          "minLength": 1,
          "maxLength": 200
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val uiBackOrWaitSchemaJson = """
    {
      "type": "object",
      "properties": {
        "timeoutMillis": {
          "type": "integer",
          "minimum": 100,
          "maximum": 10000
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val uiWaitSchemaJson = """
    {
      "type": "object",
      "properties": {
        "timeoutMillis": {
          "type": "integer",
          "minimum": 100,
          "maximum": 10000
        },
        "verifySearchQuery": {
          "type": "string",
          "description": "Optional low-risk search query that must be visible or produce recognizable result evidence after waiting.",
          "minLength": 1,
          "maxLength": 200
        },
        "expectedPackageName": {
          "type": "string",
          "description": "Optional foreground package that must still be active before waiting and while verifying search results.",
          "minLength": 1,
          "maxLength": 200
        },
        "targetPackageName": {
          "type": "string",
          "description": "Optional alias for expectedPackageName, used by external planners to bind the foreground app.",
          "minLength": 1,
          "maxLength": 200
        },
        "expectedAppName": {
          "type": "string",
          "description": "Optional app name alias used only for local profile-based result verification.",
          "minLength": 1,
          "maxLength": 80
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
          "minLength": 1,
          "maxLength": $MAX_SHARE_TEXT_CHARS
        },
        "title": {
          "type": "string",
          "maxLength": $MAX_SHARE_TITLE_CHARS
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

private val openAppByNameSchemaJson = """
    {
      "type": "object",
      "required": ["appName"],
      "properties": {
        "appName": {
          "type": "string",
          "minLength": 1,
          "maxLength": 80,
          "description": "用户可见的应用名，例如淘宝、拼多多、Chrome 或系统桌面显示的 App label；不能是 URI、Intent action、Activity 名或任意 extras。"
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
        "externalOutcomeSource",
        "targetKind",
        "intentAction",
        "metadataPolicy",
        "rawPayloadIncluded"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "completionState": {"type": "string", "enum": ["ExternalActivityOpened"]},
        "completionVerified": {"type": "boolean"},
        "externalOutcome": {"type": "string", "enum": ["Unknown", "Completed", "NotCompleted", "OpenedOnly"]},
        "externalOutcomeSource": {"type": "string", "enum": ["Unknown", "UserConfirmed"]},
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

private val periodicCheckOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "enabled",
        "taskStatus",
        "intervalMinutes",
        "minNotificationSpacingMinutes",
        "overdueGraceMinutes",
        "requiresBatteryNotLow",
        "requiresCharging"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "enabled": {"type": "boolean"},
        "taskStatus": {"type": "string", "enum": ["Scheduled", "Cancelled", "Failed"]},
        "intervalMinutes": {"type": "integer", "minimum": 60, "maximum": 1440},
        "minNotificationSpacingMinutes": {"type": "integer", "minimum": 60, "maximum": 1440},
        "overdueGraceMinutes": {"type": "integer", "minimum": 5, "maximum": 10080},
        "requiresBatteryNotLow": {"type": "boolean"},
        "requiresCharging": {"type": "boolean"},
        "nextAllowedRunAtMillis": {"type": "integer", "minimum": 0},
        "updatedAtMillis": {"type": "integer", "minimum": 0},
        "recoveryToolName": {"type": "string", "enum": ["configure_periodic_check"]},
        "recoveryEnabled": {"type": "boolean"}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val backgroundTasksOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "scope",
        "source",
        "metadataPolicy",
        "rawPayloadIncluded"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "scope": {"type": "string", "enum": ["active", "history", "policy", "all"]},
        "source": {"type": "string", "enum": ["local_store"]},
        "maxCount": {"type": "integer", "minimum": 1, "maximum": 50},
        "activeTaskCount": {"type": "integer", "minimum": 0},
        "historyTaskCount": {"type": "integer", "minimum": 0},
        "tasksJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"},
        "policyJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"},
        "metadataPolicy": {"type": "string", "enum": ["background_tasks_local_only_no_reminder_body"]},
        "rawPayloadIncluded": {"type": "boolean"}
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
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "source",
        "confidence",
        "packageName",
        "appLabel",
        "lastTimeUsedMillis"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "source": {
          "type": "string",
          "description": "How the current app estimate was derived.",
          "enum": ["usage_stats_estimate"]
        },
        "confidence": {
          "type": "string",
          "description": "UsageStats can only approximate the current foreground app.",
          "enum": ["estimate"]
        },
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
        "contactsJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"}
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
        "notificationsJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val recentFilesOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "kind",
        "maxCount",
        "mediaAccessScope",
        "fileCount",
        "filesJson"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "kind": {"type": "string", "minLength": 1},
        "maxCount": {"type": "integer", "minimum": 1, "maximum": 50},
        "mediaAccessScope": {
          "type": "string",
          "description": "Whether MediaStore was queried through legacy storage, full visual media, user-selected visual media, or currently granted media-only access.",
          "enum": ["legacy_storage", "full_visual_media", "user_selected_visual_media", "granted_media_only"]
        },
        "fileCount": {"type": "integer", "minimum": 0},
        "filesJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val recentScreenshotOcrOutputSchemaJson =
    recentOcrOutputSchemaJson(maxCountMaximum = 1)

private val recentImageOcrOutputSchemaJson =
    recentOcrOutputSchemaJson(maxCountMaximum = 3)

private fun recentOcrOutputSchemaJson(maxCountMaximum: Int): String = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "source",
        "maxCount",
        "scannedCount",
        "mediaAccessScope",
        "ocrTextIncluded",
        "rawPayloadIncluded",
        "metadataPolicy"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "source": {"type": "string", "minLength": 1},
        "maxCount": {"type": "integer", "minimum": 1, "maximum": $maxCountMaximum},
        "scannedCount": {"type": "integer", "minimum": 0},
        "mediaAccessScope": {
          "type": "string",
          "description": "Whether OCR image candidates came from legacy storage, full visual media, user-selected visual media, or currently granted media-only access.",
          "enum": ["legacy_storage", "full_visual_media", "user_selected_visual_media", "granted_media_only"]
        },
        "ocrText": {"type": "string", "minLength": 1},
        "truncated": {"type": "boolean"},
        "ocrTextIncluded": {"type": "boolean"},
        "rawPayloadIncluded": {"type": "boolean"},
        "metadataPolicy": {"type": "string", "minLength": 1}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val recentImageOcrPrivateOutputKeys = setOf("ocrText")

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
        "structureSummaryIncluded",
        "rawTreeIncluded",
        "metadataPolicy"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "source": {
          "type": "string",
          "description": "Fixed source for current active-window Accessibility 可访问文本快照；never screenshot, OCR, visual/VLM, or semantic screen understanding.",
          "enum": ["$CURRENT_SCREEN_TEXT_SOURCE"]
        },
        "maxChars": {"type": "integer", "minimum": 1, "maximum": 4000},
        "capturedAtMillis": {"type": "integer"},
        "nodeCount": {"type": "integer", "minimum": 0},
        "screenText": {
          "type": "string",
          "description": "Text exposed by Accessibility from the active window; not screenshot pixels, OCR output, visual/VLM output, or inferred screen semantics.",
          "minLength": 1
        },
        "packageName": {"type": "string"},
        "truncated": {"type": "boolean"},
        "screenTextIncluded": {"type": "boolean"},
        "structureSummary": {
          "type": "string",
          "description": "Coarse Accessibility node/text-item metadata only; no node ids, bounds, hierarchy, screenshots, OCR, or inferred visual semantics.",
          "minLength": 1
        },
        "structureSummaryIncluded": {"type": "boolean"},
        "rawTreeIncluded": {"type": "boolean"},
        "metadataPolicy": {
          "type": "string",
          "enum": ["$CURRENT_SCREEN_TEXT_METADATA_POLICY"]
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val currentScreenshotOcrOutputSchemaJson = CurrentScreenshotOcrContract.OUTPUT_SCHEMA_JSON.trimIndent()

private val observeCurrentScreenOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "source",
        "metadataPolicy",
        "observationId",
        "capturedAtMillis",
        "nodeCount",
        "actionableNodeCount",
        "textSummary",
        "truncated",
        "nodesJson",
        "screenObservationJson",
        "maxTextChars",
        "maxNodes"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "source": {"type": "string", "enum": ["$DEVICE_CONTROL_SOURCE"]},
        "metadataPolicy": {"type": "string", "enum": ["$DEVICE_CONTROL_METADATA_POLICY"]},
        "observationId": {"type": "string", "minLength": 1},
        "packageName": {"type": "string"},
        "capturedAtMillis": {"type": "integer", "minimum": 0},
        "nodeCount": {"type": "integer", "minimum": 0},
        "actionableNodeCount": {"type": "integer", "minimum": 0},
        "textSummary": {"type": "string"},
        "truncated": {"type": "boolean"},
        "nodesJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"},
        "screenObservationJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"},
        "maxTextChars": {"type": "integer", "minimum": 1, "maximum": 4000},
        "maxNodes": {"type": "integer", "minimum": 1, "maximum": 120}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val uiActionOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "source",
        "metadataPolicy",
        "actionType",
        "status",
        "retryable",
        "summary",
        "beforeObservationId",
        "afterObservationId",
        "verificationSummary"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "source": {"type": "string", "enum": ["$DEVICE_CONTROL_SOURCE"]},
        "metadataPolicy": {"type": "string", "enum": ["$DEVICE_CONTROL_METADATA_POLICY"]},
        "actionType": {"type": "string", "enum": ["tap", "type_text", "submit_search", "scroll", "press_back", "wait"]},
        "target": {"type": "string"},
        "direction": {"type": "string", "enum": ["up", "down", "left", "right", "forward", "backward"]},
        "status": {"type": "string", "enum": ["succeeded", "failed"]},
        "retryable": {"type": "boolean"},
        "summary": {"type": "string", "minLength": 1},
        "failureKind": {
          "type": "string",
          "enum": [
            "node_not_found",
            "page_changed",
            "permission_missing",
            "keyboard_obscured",
            "timeout",
            "app_not_foreground",
            "search_entry_not_found",
            "editable_not_found",
            "submit_not_found",
            "result_not_verified",
            "dangerous_action",
            "unknown"
          ]
        },
        "beforeObservationId": {"type": "string"},
        "afterObservationId": {"type": "string"},
        "verificationSummary": {"type": "string", "minLength": 1},
        "screenObservationDiffSummary": {
          "type": "string",
          "description": "Bounded LocalOnly before/after Accessibility observation diff summary for local action replanning.",
          "minLength": 1
        },
        "searchVerificationStatus": {"type": "string", "enum": ["verified", "not_verified"]},
        "searchVerificationEvidence": {"type": "string", "maxLength": 80},
        "beforePackageName": {"type": "string"},
        "beforeCapturedAtMillis": {"type": "integer", "minimum": 0},
        "beforeNodeCount": {"type": "integer", "minimum": 0},
        "beforeActionableNodeCount": {"type": "integer", "minimum": 0},
        "beforeTextSummary": {"type": "string"},
        "beforeTruncated": {"type": "boolean"},
        "beforeNodesJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"},
        "beforeScreenObservationJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"},
        "afterPackageName": {"type": "string"},
        "afterCapturedAtMillis": {"type": "integer", "minimum": 0},
        "afterNodeCount": {"type": "integer", "minimum": 0},
        "afterActionableNodeCount": {"type": "integer", "minimum": 0},
        "afterTextSummary": {"type": "string"},
        "afterTruncated": {"type": "boolean"},
        "afterNodesJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"},
        "afterScreenObservationJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"}
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
        "blocksJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"}
      },
      "additionalProperties": false
    }
""".trimIndent()

private val observeCurrentScreenPrivateOutputKeys = setOf(
    "observationId",
    "packageName",
    "capturedAtMillis",
    "nodeCount",
    "actionableNodeCount",
    "textSummary",
    "nodesJson",
    "screenObservationJson",
)

private val uiActionPrivateOutputKeys = setOf(
    "target",
    "summary",
    "beforeObservationId",
    "afterObservationId",
    "verificationSummary",
    "screenObservationDiffSummary",
    "searchVerificationStatus",
    "searchVerificationEvidence",
    "beforePackageName",
    "beforeCapturedAtMillis",
    "beforeNodeCount",
    "beforeActionableNodeCount",
    "beforeTextSummary",
    "beforeTruncated",
    "beforeNodesJson",
    "beforeScreenObservationJson",
    "afterPackageName",
    "afterCapturedAtMillis",
    "afterNodeCount",
    "afterActionableNodeCount",
    "afterTextSummary",
    "afterTruncated",
    "afterNodesJson",
    "afterScreenObservationJson",
)

private val deviceControlSessionTags = setOf(
    ToolCapabilityTag.DeviceControlSession,
)

private val lowRiskDeviceControlSessionTags = setOf(
    ToolCapabilityTag.DeviceControlSession,
    ToolCapabilityTag.LowRiskDeviceAction,
    ToolCapabilityTag.RestoredExternalOutcomePopupSkippable,
)

private val restoredPopupDeviceControlSessionTags = setOf(
    ToolCapabilityTag.DeviceControlSession,
    ToolCapabilityTag.RestoredExternalOutcomePopupSkippable,
)

private val conditionalLowRiskDeviceControlSessionTags = setOf(
    ToolCapabilityTag.DeviceControlSession,
    ToolCapabilityTag.ConditionalLowRiskDeviceAction,
    ToolCapabilityTag.ConditionalRestoredExternalOutcomePopupSkippable,
)

private val sequentialLocalContinuationTags = setOf(
    ToolCapabilityTag.SequentialLocalContinuation,
)

private val backgroundSkillAllowedTags = setOf(
    ToolCapabilityTag.BackgroundSkillAllowed,
)

private val backgroundSequentialLocalContinuationTags = setOf(
    ToolCapabilityTag.BackgroundSkillAllowed,
    ToolCapabilityTag.SequentialLocalContinuation,
)

private val usageStatsSpecialAccessTags = setOf(
    ToolCapabilityTag.UsageStatsSpecialAccess,
)

private val accessibilityScreenTextSequentialTags = setOf(
    ToolCapabilityTag.SequentialLocalContinuation,
    ToolCapabilityTag.AccessibilityScreenTextSpecialAccess,
)

private val lowRiskSequentialContinuationTags = setOf(
    ToolCapabilityTag.SequentialLocalContinuation,
    ToolCapabilityTag.LowRiskDeviceAction,
    ToolCapabilityTag.LowRiskAppControlContinuation,
    ToolCapabilityTag.AccessibilityDeviceControlSpecialAccess,
)

private val conditionalLowRiskSequentialContinuationTags = setOf(
    ToolCapabilityTag.SequentialLocalContinuation,
    ToolCapabilityTag.ConditionalLowRiskDeviceAction,
    ToolCapabilityTag.LowRiskAppControlContinuation,
    ToolCapabilityTag.CheckpointedUiAction,
    ToolCapabilityTag.AccessibilityDeviceControlSpecialAccess,
)

private val checkpointedLowRiskSequentialContinuationTags = setOf(
    ToolCapabilityTag.SequentialLocalContinuation,
    ToolCapabilityTag.LowRiskDeviceAction,
    ToolCapabilityTag.LowRiskAppControlContinuation,
    ToolCapabilityTag.CheckpointedUiAction,
    ToolCapabilityTag.AccessibilityDeviceControlSpecialAccess,
)

private val checkpointedLowRiskContinuationTags = setOf(
    ToolCapabilityTag.LowRiskDeviceAction,
    ToolCapabilityTag.LowRiskAppControlContinuation,
    ToolCapabilityTag.CheckpointedUiAction,
    ToolCapabilityTag.AccessibilityDeviceControlSpecialAccess,
)

private val builtInToolDefinitions: List<ToolDefinition> = listOf(
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            description = "打开系统 Wi-Fi 设置页，由用户在系统页面内继续操作。",
            inputSchemaJson = emptyObjectSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.DeviceSettings,
            permissions = setOf(ToolPermission.StartsExternalActivity),
            tags = lowRiskDeviceControlSessionTags,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
            title = "打开使用情况访问权限设置",
            description = "打开 Android 使用情况访问权限设置页，由用户手动为栖知授权。",
            inputSchemaJson = emptyObjectSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.DeviceSettings,
            permissions = setOf(ToolPermission.StartsExternalActivity),
            tags = deviceControlSessionTags,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OPEN_SYSTEM_SETTINGS,
            title = "打开系统设置页",
            description = "打开 allowlisted Android 系统设置页，例如蓝牙、定位、通知、显示、声音、省电、网络、飞行模式、输入法或无障碍；不会静默修改系统开关。",
            inputSchemaJson = systemSettingsSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.DeviceSettings,
            permissions = setOf(ToolPermission.StartsExternalActivity),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("target"),
            tags = conditionalLowRiskDeviceControlSessionTags,
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
            tags = restoredPopupDeviceControlSessionTags,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.WEB_SEARCH,
            title = "Web 搜索",
            description = "执行只读网络信息查询并返回摘要和结构化结果，不打开浏览器；query 必须是模型理解后的搜索关键词，不要直接复制用户原文；查询含最新/目前/当前/现在/今日/热门/排行/latest/current/recent/trending/hottest 或当前年份等当前性语义时以 freshness=current 执行；多主体比较、差值、汇总或交叉核验问题可对每个主体发起独立 web_search 工具调用，由宿主并发执行公开只读批次后再综合；疑似个人信息或密钥查询需要用户确认后才联网。",
            inputSchemaJson = webSearchInputSchemaJson,
            outputSchemaJson = webSearchOutputSchemaJson,
            capability = ToolCapability.WebSearch,
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.NotRequired,
            resultContinuationPolicy = ToolResultContinuationPolicy.PublicEvidence,
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
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            androidRuntimePermissions = listOf(
                AndroidRuntimePermissionSpec(AndroidRuntimePermissionKind.ReadContacts),
            ),
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
            description = "创建一个本地后台提醒，到点后通过系统通知提示用户；支持相对延迟或一次性绝对触发时间，不支持重复提醒。",
            inputSchemaJson = reminderSchemaJson,
            outputSchemaJson = reminderOutputSchemaJson,
            capability = ToolCapability.BackgroundTask,
            permissions = setOf(
                ToolPermission.SchedulesBackgroundWork,
                ToolPermission.PostsNotification,
                ToolPermission.RequiresAndroidRuntimePermission,
            ),
            planningPromptHint = "必须恰好填写 delayMinutes 或 triggerAtMillis 之一；不支持重复提醒。",
            androidRuntimePermissions = listOf(
                AndroidRuntimePermissionSpec(AndroidRuntimePermissionKind.PostNotifications),
            ),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.SET_SYSTEM_ALARM,
            title = "系统闹钟",
            description = "打开 Android 系统时钟应用的闹钟设置界面并填入小时、分钟和可选标签；不跳过系统 UI，不验证外部应用内最终保存结果。",
            inputSchemaJson = systemAlarmSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.ExternalDraft,
            permissions = setOf(ToolPermission.StartsExternalActivity),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("hour", "minutes", "message", "recurrence"),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.SET_SYSTEM_TIMER,
            title = "系统倒计时",
            description = "打开 Android 系统时钟应用的倒计时设置界面并填入时长和可选标签；不跳过系统 UI，不验证外部应用内最终启动结果。",
            inputSchemaJson = systemTimerSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.ExternalDraft,
            permissions = setOf(ToolPermission.StartsExternalActivity),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("lengthSeconds", "message"),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
            title = "配置周期检查",
            description = "开启或关闭本地提醒周期检查；该后台任务只巡检本地提醒，不执行后台聊天、屏幕扫描或文件内容扫描。",
            inputSchemaJson = periodicCheckSchemaJson,
            outputSchemaJson = periodicCheckOutputSchemaJson,
            capability = ToolCapability.BackgroundTask,
            permissions = setOf(
                ToolPermission.SchedulesBackgroundWork,
                ToolPermission.PostsNotification,
                ToolPermission.RequiresAndroidRuntimePermission,
            ),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf(
                "enabled",
                "intervalMinutes",
                "minNotificationSpacingMinutes",
                "overdueGraceMinutes",
                "requiresBatteryNotLow",
                "requiresCharging",
            ),
            tags = backgroundSkillAllowedTags,
            androidRuntimePermissions = listOf(
                AndroidRuntimePermissionSpec(
                    kind = AndroidRuntimePermissionKind.PostNotifications,
                    rationale = "用于周期检查发现过期本地提醒时发送通知。",
                ),
            ),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.QUERY_BACKGROUND_TASKS,
            title = "查询后台任务",
            description = "只读查询本地后台提醒、周期检查任务状态与周期检查策略；不会返回提醒正文，任务标题仅作为本地私有输出。",
            inputSchemaJson = backgroundTasksQuerySchemaJson,
            outputSchemaJson = backgroundTasksOutputSchemaJson,
            capability = ToolCapability.BackgroundTask,
            permissions = setOf(ToolPermission.ReadsDeviceContext),
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("scope", "maxCount"),
            privateOutputKeys = setOf("activeTaskCount", "historyTaskCount", "tasksJson", "policyJson"),
            redactedResultSummary = "已读取后台任务摘要",
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            tags = backgroundSequentialLocalContinuationTags,
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
            tags = sequentialLocalContinuationTags,
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
            name = MobileActionFunctions.OPEN_CAMERA,
            title = "打开相机",
            description = "打开系统相机应用；不拍照、不录像、不读取照片或相册。",
            inputSchemaJson = emptyObjectSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.ExternalNavigation,
            permissions = setOf(ToolPermission.StartsExternalActivity),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            tags = lowRiskDeviceControlSessionTags,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OPEN_APP_BY_NAME,
            title = "按名称打开应用",
            description = "按本机 launcher 中的用户可见应用名解析可启动应用并打开启动页；不接受任意 Intent action、URI、Activity 或 extras。",
            inputSchemaJson = openAppByNameSchemaJson,
            outputSchemaJson = externalActivityOutputSchemaJson,
            capability = ToolCapability.ExternalNavigation,
            permissions = setOf(ToolPermission.StartsExternalActivity),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("appName"),
            tags = lowRiskDeviceControlSessionTags + ToolCapabilityTag.OpenAppLaunch,
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
            tags = lowRiskDeviceControlSessionTags + ToolCapabilityTag.OpenAppLaunch,
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
            tags = deviceControlSessionTags,
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
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            androidRuntimePermissions = listOf(
                AndroidRuntimePermissionSpec(AndroidRuntimePermissionKind.ReadCalendar),
            ),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.QUERY_FOREGROUND_APP,
            title = "查询当前前台应用",
            description = "通过 Android UsageStats 读取当前前台应用的应用名与包名估计值；不是窗口管理器真值，也不读取屏幕内容。",
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
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            tags = usageStatsSpecialAccessTags,
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
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.QUERY_RECENT_FILES,
            title = "查询最近文件",
            description = "读取本机最近媒体文件摘要，仅返回文件名与文件类型等最小信息。Android 13 及以上没有 documents/downloads/others 的可执行直接读取授权路径；非媒体文件应由用户通过系统文件选择器或分享入口主动提供。",
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
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            androidRuntimePermissions = listOf(
                AndroidRuntimePermissionSpec(
                    kind = AndroidRuntimePermissionKind.RecentFiles,
                    argumentName = "kind",
                ),
            ),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            title = "读取最近截图 OCR",
            description = "在用户确认后读取最近 1 张截图像素并在本地提取 OCR 文本；不保存 URI、路径、原图或像素。",
            inputSchemaJson = recentScreenshotOcrSchemaJson,
            outputSchemaJson = recentScreenshotOcrOutputSchemaJson,
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
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            tags = sequentialLocalContinuationTags,
            androidRuntimePermissions = listOf(
                AndroidRuntimePermissionSpec(
                    kind = AndroidRuntimePermissionKind.RecentImages,
                    rationale = "用于在你确认后读取最近 1 张截图像素，并在本地提取 OCR 文本。",
                ),
            ),
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
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            tags = sequentialLocalContinuationTags,
            androidRuntimePermissions = listOf(
                AndroidRuntimePermissionSpec(
                    kind = AndroidRuntimePermissionKind.RecentImages,
                    rationale = "用于在你确认后最多扫描最近 3 张图片像素，并在本地提取第一条 OCR 文本。",
                ),
            ),
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            title = "读取当前屏幕 Accessibility 可访问文本快照",
            description = "在用户确认后读取当前 active window 暴露的 Accessibility 可访问文本快照和粗粒度结构摘要；不是截图、OCR、视觉/VLM 或语义屏幕理解，不读取像素、坐标、节点 ID 或完整节点树。",
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
            privateOutputKeys = setOf("capturedAtMillis", "nodeCount", "screenText", "packageName", "structureSummary"),
            redactedResultSummary = "已读取当前屏幕可访问文本快照",
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            tags = accessibilityScreenTextSequentialTags,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            title = "截取当前屏幕 OCR",
            description = "在用户确认并完成 Android MediaProjection 前台同意后，单次截取当前可见屏幕并本地提取有界 OCR 文本；可融合临时 Accessibility 节点形成本地结构化观测；不保存图片、像素、URI、路径或窗口标题，不做视觉语义理解。",
            inputSchemaJson = currentScreenshotOcrSchemaJson,
            outputSchemaJson = currentScreenshotOcrOutputSchemaJson,
            capability = ToolCapability.DeviceContext,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsAccessibilityText,
                ToolPermission.RequiresMediaProjectionConsent,
            ),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("captureMode"),
            privateOutputKeys = setOf("ocrText", "ocrBlocksJson", "screenObservationJson"),
            redactedResultSummary = "已读取当前屏幕截图 OCR 摘录",
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            tags = sequentialLocalContinuationTags,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            title = "观察当前屏幕",
            description = "在用户确认后通过 Accessibility 读取当前 active window 的本地屏幕状态快照，包括可见文本摘要、可交互节点、短期节点 id 和 bounds；不读取截图像素，不默认发送远程。",
            inputSchemaJson = observeCurrentScreenSchemaJson,
            outputSchemaJson = observeCurrentScreenOutputSchemaJson,
            capability = ToolCapability.DeviceControl,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsAccessibilityText,
            ),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("maxTextChars", "maxNodes"),
            privateOutputKeys = observeCurrentScreenPrivateOutputKeys,
            redactedResultSummary = "已观察当前屏幕状态",
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            tags = lowRiskSequentialContinuationTags,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.UI_TAP,
            title = "点击当前屏幕元素",
            description = "在用户确认后通过 Accessibility 点击当前屏幕中匹配的短期节点 id、文本或 contentDescription；每次动作后重新观察屏幕并返回本地验证摘要。",
            inputSchemaJson = uiTapSchemaJson,
            outputSchemaJson = uiActionOutputSchemaJson,
            capability = ToolCapability.DeviceControl,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsAccessibilityText,
                ToolPermission.PerformsAccessibilityGesture,
            ),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf(
                "target",
                "timeoutMillis",
                "expectedPackageName",
                "targetPackageName",
            ),
            privateOutputKeys = uiActionPrivateOutputKeys,
            redactedResultSummary = "已执行屏幕点击动作",
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            tags = conditionalLowRiskSequentialContinuationTags,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.UI_TYPE_TEXT,
            title = "向当前屏幕输入文本",
            description = "在用户确认后通过 Accessibility 向当前或指定输入框写入文本；不直接发送、发布、支付或删除数据，每次动作后重新观察屏幕并返回本地验证摘要。",
            inputSchemaJson = uiTypeTextSchemaJson,
            outputSchemaJson = uiActionOutputSchemaJson,
            capability = ToolCapability.DeviceControl,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsAccessibilityText,
                ToolPermission.PerformsAccessibilityGesture,
            ),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf(
                "text",
                "target",
                "timeoutMillis",
                "allowClipboardPasteFallback",
                "expectedPackageName",
                "targetPackageName",
            ),
            privateOutputKeys = uiActionPrivateOutputKeys,
            redactedResultSummary = "已执行屏幕输入动作",
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            tags = checkpointedLowRiskSequentialContinuationTags,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.UI_SUBMIT_SEARCH,
            title = "提交当前搜索",
            description = "在用户确认后通过 Accessibility 对当前输入框执行搜索提交；优先使用输入法搜索动作，失败时点击可见搜索按钮。不会发送、发布、支付或删除数据。",
            inputSchemaJson = uiSubmitSearchSchemaJson,
            outputSchemaJson = uiActionOutputSchemaJson,
            capability = ToolCapability.DeviceControl,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsAccessibilityText,
                ToolPermission.PerformsAccessibilityGesture,
            ),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf(
                "timeoutMillis",
                "expectedPackageName",
                "targetPackageName",
            ),
            privateOutputKeys = uiActionPrivateOutputKeys,
            redactedResultSummary = "已执行搜索提交动作",
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            tags = checkpointedLowRiskContinuationTags,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.UI_SCROLL,
            title = "滚动当前屏幕",
            description = "在用户确认后通过 Accessibility 滚动当前屏幕或指定滚动容器；每次动作后重新观察屏幕并返回本地验证摘要。",
            inputSchemaJson = uiScrollSchemaJson,
            outputSchemaJson = uiActionOutputSchemaJson,
            capability = ToolCapability.DeviceControl,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsAccessibilityText,
                ToolPermission.PerformsAccessibilityGesture,
            ),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf(
                "direction",
                "target",
                "timeoutMillis",
                "expectedPackageName",
                "targetPackageName",
            ),
            privateOutputKeys = uiActionPrivateOutputKeys,
            redactedResultSummary = "已执行屏幕滚动动作",
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            tags = checkpointedLowRiskSequentialContinuationTags,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.UI_PRESS_BACK,
            title = "执行系统返回",
            description = "在用户确认后通过 Accessibility 执行系统返回；每次动作后重新观察屏幕并返回本地验证摘要。",
            inputSchemaJson = uiBackOrWaitSchemaJson,
            outputSchemaJson = uiActionOutputSchemaJson,
            capability = ToolCapability.DeviceControl,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsAccessibilityText,
                ToolPermission.PerformsAccessibilityGesture,
            ),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("timeoutMillis"),
            privateOutputKeys = uiActionPrivateOutputKeys,
            redactedResultSummary = "已执行系统返回动作",
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            tags = checkpointedLowRiskSequentialContinuationTags,
        ),
    ),
    ToolDefinition(
        spec = ToolSpec(
            name = MobileActionFunctions.UI_WAIT,
            title = "等待屏幕稳定",
            description = "在用户确认后等待当前屏幕稳定并重新观察屏幕；可对低风险搜索任务做本地结果验证，失败时返回可恢复原因。",
            inputSchemaJson = uiWaitSchemaJson,
            outputSchemaJson = uiActionOutputSchemaJson,
            capability = ToolCapability.DeviceControl,
            permissions = setOf(
                ToolPermission.ReadsDeviceContext,
                ToolPermission.ReadsAccessibilityText,
                ToolPermission.PerformsAccessibilityGesture,
            ),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf(
                "timeoutMillis",
                "verifySearchQuery",
                "expectedPackageName",
                "targetPackageName",
                "expectedAppName",
            ),
            privateOutputKeys = uiActionPrivateOutputKeys,
            redactedResultSummary = "已等待并重新观察当前屏幕",
            resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            tags = lowRiskSequentialContinuationTags,
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
            permissions = setOf(ToolPermission.SchedulesBackgroundWork),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
            pendingArgumentAllowlist = setOf("taskId"),
        ),
    ),
)

private val toolDefinitionsByName: Map<String, ToolDefinition> =
    builtInToolDefinitions.associateBy { it.spec.name }
