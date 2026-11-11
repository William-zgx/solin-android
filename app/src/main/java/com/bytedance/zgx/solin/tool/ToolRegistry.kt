package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.SystemSettingsTargets
import com.bytedance.zgx.solin.device.hasDangerousTapTargetText
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrContract
import org.json.JSONObject

class ToolRegistry private constructor(
    definitions: List<ToolDefinition>,
    @Suppress("UNUSED_PARAMETER") internalTag: InternalTag
) {
    private enum class InternalTag { Definitions }

    private val definitionsByName: Map<String, ToolDefinition> = definitions.associateBy { it.spec.name }
    private val orderedSpecs: List<ToolSpec> = definitions.map { it.spec }

    init {
        require(definitionsByName.size == definitions.size) { "Tool names must be unique." }
        definitions.forEach { definition ->
            validateRuntimePermissionDescriptorContract(definition.spec)
            validateExactlyOneOfContract(definition.spec)
            validateEgressTextArgumentContract(definition.spec)
        }
    }

    constructor() : this(BuiltInToolProvider)

    constructor(providers: List<ToolProvider>) : this(*providers.toTypedArray())

    constructor(vararg providers: ToolProvider) : this(definitionsFor(providers.toList()), InternalTag.Definitions)

    fun specs(): List<ToolSpec> = orderedSpecs

    fun specFor(toolName: String): ToolSpec? = definitionsByName[toolName]?.spec

    fun privateOutputKeysFor(toolName: String): Set<String> = specFor(toolName)?.privateOutputKeys.orEmpty()

    fun pendingArgumentAllowlistFor(toolName: String): Set<String> = specFor(toolName)?.pendingArgumentAllowlist.orEmpty()

    fun androidRuntimePermissionSpecsFor(toolName: String): List<AndroidRuntimePermissionSpec> = specFor(toolName)?.androidRuntimePermissions.orEmpty()

    fun redactedResultSummaryFor(toolName: String): String? = specFor(toolName)?.redactedResultSummary

    fun hasTag(toolName: String, tag: ToolCapabilityTag): Boolean = specFor(toolName)?.tags?.contains(tag) == true

    fun startsDeviceControlSession(toolName: String): Boolean = hasTag(toolName, ToolCapabilityTag.DeviceControlSession)

    fun isOpenAppLaunchTool(toolName: String): Boolean = hasTag(toolName, ToolCapabilityTag.OpenAppLaunch)

    fun requiresSequentialLocalModelBeforeTail(toolName: String): Boolean {
        val spec = specFor(toolName) ?: return false
        return ToolCapabilityTag.SequentialLocalContinuation in spec.tags ||
            spec.resultContinuationPolicy == ToolResultContinuationPolicy.LocalEvidence ||
            spec.privateOutputKeys.isNotEmpty()
    }

    fun isLowRiskDeviceActionConfirmationSkippable(request: ToolRequest): Boolean = when {
        hasTag(request.toolName, ToolCapabilityTag.LowRiskDeviceAction) -> true
        !hasTag(request.toolName, ToolCapabilityTag.ConditionalLowRiskDeviceAction) -> false
        request.toolName == MobileActionFunctions.UI_TAP ->
            !request.arguments["target"].hasDangerousTapTargetText()
        request.toolName == MobileActionFunctions.OPEN_SYSTEM_SETTINGS ->
            request.arguments["target"].orEmpty() in SystemSettingsTargets.confirmationBypassEligible
        else -> false
    }

    fun isLowRiskAppControlContinuationTool(request: ToolRequest): Boolean = isLowRiskDeviceActionConfirmationSkippable(request) &&
        hasTag(request.toolName, ToolCapabilityTag.LowRiskAppControlContinuation)

    fun isCheckpointedUiActionTool(toolName: String): Boolean = hasTag(toolName, ToolCapabilityTag.CheckpointedUiAction)

    fun specialAccessTagsFor(toolName: String): Set<ToolCapabilityTag> = specFor(toolName)
        ?.tags
        ?.filterTo(linkedSetOf()) { tag ->
            tag == ToolCapabilityTag.UsageStatsSpecialAccess ||
                tag == ToolCapabilityTag.AccessibilityScreenTextSpecialAccess ||
                tag == ToolCapabilityTag.AccessibilityDeviceControlSpecialAccess
        }
        .orEmpty()

    fun isLowRiskRestoredExternalOutcomePopupSkippable(
        toolName: String,
        result: ToolResult
    ): Boolean = when {
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
                retryable = false
            )

        definition.argumentValidator.validate(request)?.let { reason ->
            return request.rejected(reason)
                .sanitizedPrivateNonSucceededResult(
                    request = request,
                    spec = definition.spec,
                    preserveSummary = true
                )
        }
        toolSpecificArgumentInvariant(definition.spec, request)?.let { reason ->
            return request.rejected(reason)
                .sanitizedPrivateNonSucceededResult(
                    request = request,
                    spec = definition.spec,
                    preserveSummary = true
                )
        }

        return null
    }

    fun validatePublicEvidenceBatchRequest(request: ToolRequest): ToolResult? {
        validate(request)?.let { return it }
        val spec = specFor(request.toolName)
            ?: return request.rejected("Unknown tool: ${request.toolName}")
        if (!spec.isEligibleForParallelBatch()) {
            return request.rejected(
                "Tool ${request.toolName} is not eligible for parallel public evidence execution."
            )
        }
        return null
    }

    fun validateResult(request: ToolRequest, result: ToolResult): ToolResult? {
        if (result.status != ToolStatus.Succeeded) {
            val definition = definitionsByName[request.toolName] ?: return null
            val sanitized = result.sanitizedPrivateNonSucceededResult(
                request = request,
                spec = definition.spec
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
                    "Unknown tool while validating result: ${request.toolName}"
                ),
                retryable = false
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
                retryable = false
            )
        }

        return null
    }

    private fun ToolResult.sanitizedPrivateNonSucceededResult(
        request: ToolRequest,
        spec: ToolSpec,
        preserveSummary: Boolean = false
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
        if (spec.capability == ToolCapability.DeviceControl) {
            data["inputVerification"]?.takeIf { it in setOf("match", "mismatch", "unknown") }
                ?.let { sanitizedData["inputVerification"] = it }
            data["screenStability"]?.takeIf { it in setOf("stable", "timed_out", "unknown") }
                ?.let { sanitizedData["screenStability"] = it }
        }
        if (request.toolName in PhoneCompositeOperations.TOOL_NAMES) {
            // T7: retain bounded progress diagnostics, never partial screen text in shared audit data.
            for (key in listOf("completed", "partial")) {
                data[key]?.takeIf { it == "true" || it == "false" }?.let { sanitizedData[key] = it }
            }
            for (key in listOf("pagesInspected", "writesAttempted")) {
                data[key]?.toIntOrNull()?.takeIf { it in 0..6 }?.let { sanitizedData[key] = it.toString() }
            }
            data["stopReason"]?.takeIf {
                it in setOf(
                    "unknown", "timeout", "authorization_required", "read_failed",
                    "invalid_observation", "app_changed", "write_limit", "scroll_failed", "item_limit", "no_new_content",
                    "page_limit", "ambiguous_target", "no_change", "invalid_range", "wheel_not_unique", "wheel_unreadable",
                    "wheel_not_converging", "input_failed", "wheel_changed", "execution_interrupted"
                )
            }?.let { sanitizedData["stopReason"] = it }
            data["remaining"]?.takeIf { it == "observe_before_continuing" }?.let { sanitizedData["remaining"] = it }
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
                errorCode = error?.code
            )
        }

        return copy(
            requestId = request.id,
            summary = sanitizedSummary,
            data = sanitizedData,
            error = error?.copy(message = sanitizedSummary)
        )
    }

    private fun privateNonSucceededResultSummary(
        toolName: String,
        status: ToolStatus,
        errorCode: ToolErrorCode?
    ): String = when (status) {
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
                    "requiresLocalModel" to true.toString()
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
            retryable = false
        )
    }

    companion object {
        fun fromSupportedActions(supportedActions: Set<String> = builtInToolNames()): ToolRegistry = ToolRegistry(definitionsFor(supportedActions), InternalTag.Definitions)
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

/** A misspelled [ToolSpec.egressTextArguments] name would silently drop an egress check. */
private fun validateEgressTextArgumentContract(spec: ToolSpec) {
    val undeclared = spec.egressTextArguments - spec.inputSchemaJson.schemaPropertyNames()
    require(undeclared.isEmpty()) {
        "Tool ${spec.name} egress text argument(s) not declared in input schema: " + undeclared.sorted().joinToString()
    }
}

/**
 * Fails registry construction when a [ToolSpec.exactlyOneOf] group cannot possibly be enforced.
 *
 * WHY this is a construction-time `require` and not a lenient runtime skip: the whole point of
 * moving the XOR rule out of a hard-coded `when` is that the declaration is now the only place
 * the invariant lives. A typo'd or renamed argument name would make the group vacuous — every
 * request would report "supplied 0 of 1" and the tool would become permanently uncallable, or
 * (if we skipped unknown names) the mutual-exclusion gate would silently disappear. Both are
 * bad; failing loudly at construction keeps this fail-closed and catches the mistake in tests
 * rather than in front of the model.
 */
private fun validateExactlyOneOfContract(spec: ToolSpec) {
    if (spec.exactlyOneOf.isEmpty()) return
    val argumentNames = spec.inputSchemaJson.schemaPropertyNames()
    val requiredNames = spec.inputSchemaJson.schemaRequiredPropertyNames()
    spec.exactlyOneOf.forEach { group ->
        require(group.size >= 2) {
            "Tool ${spec.name} exactlyOneOf group must name at least two mutually exclusive arguments: " +
                group.sorted().joinToString()
        }
        val undeclared = group - argumentNames
        require(undeclared.isEmpty()) {
            "Tool ${spec.name} exactlyOneOf argument(s) not declared in input schema: " +
                undeclared.sorted().joinToString()
        }
        // A schema-required member would force itself to always be present, so no sibling could
        // ever be the "exactly one" — the group would degrade into "only this argument".
        val alsoRequired = group.intersect(requiredNames)
        require(alsoRequired.isEmpty()) {
            "Tool ${spec.name} exactlyOneOf argument(s) must not be schema-required: " +
                alsoRequired.sorted().joinToString()
        }
    }
}

/**
 * Enforces declarative argument invariants that the closed JSON Schema dialect cannot express.
 *
 * [ToolSpec.exactlyOneOf], plus `ui_wait`'s conditional timeout ceiling ([uiWaitArgumentInvariant]).
 * Keep it table-driven so a new mutual-exclusion rule is a spec declaration, not an edit to shared
 * validation code.
 */
private fun toolSpecificArgumentInvariant(spec: ToolSpec, request: ToolRequest): String? {
    spec.exactlyOneOf.forEach { group ->
        val supplied = group.count { name -> !request.arguments[name].isNullOrBlank() }
        if (supplied != 1) {
            return "Tool ${request.toolName} requires exactly one of ${group.humanReadableAlternatives()}"
        }
    }
    if (request.toolName == MobileActionFunctions.UI_WAIT) return uiWaitArgumentInvariant(request.arguments)
    return null
}

/**
 * Renders a mutually exclusive argument group the way the previous hard-coded message did:
 * "delayMinutes or triggerAtMillis". Sorted so the rejection reason is stable regardless of
 * declaration order — this string is surfaced to the model, which retries against it.
 */
private fun Set<String>.humanReadableAlternatives(): String {
    val names = sorted()
    // Groups are guaranteed to hold >= 2 names by validateExactlyOneOfContract; the smaller
    // cases only exist so message rendering can never itself throw.
    return when (names.size) {
        0 -> "(no alternatives declared)"
        1 -> names.single()
        else -> names.dropLast(1).joinToString(", ") + " or " + names.last()
    }
}

private fun privateOutputResultInvariant(
    spec: ToolSpec,
    result: ToolResult
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
    result: ToolResult
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
    val spec: ToolSpec
) {
    val argumentValidator: ToolArgumentValidator = ToolArgumentValidator.fromSchema(spec)
    val resultValidator: ToolResultDataValidator = ToolResultDataValidator.fromSchema(spec)
}

private val privateNonSucceededAllowedSpecialAccessValues = setOf(
    "usage_stats",
    "accessibility_screen_text",
    "accessibility_device_control",
    CurrentScreenshotOcrContract.CONSENT_REASON
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
    "android.settings.ACCESSIBILITY_SETTINGS"
)

private val privateNonSucceededAllowedRecoveryTools = setOf(
    MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS
)

private val privateNonSucceededAllowedDiagnosticKeys = setOf(
    "actionType",
    "status",
    "retryable",
    "failureKind",
    "searchVerificationStatus",
    "searchVerificationEvidence",
    "uiActionOutcome",
    "uiActionOutcomeReason",
    "appSearchProgressStage",
    // ui_wait's true/false: a failed condition wait must still say whether its condition held.
    "conditionMet"
)

private val privateNonSucceededDeviceControlObservationKeys = setOf(
    "beforeObservationId",
    "afterObservationId",
    "verificationSummary",
    "screenObservationDiffSummary",
    "beforeScreenObservationJson",
    "afterScreenObservationJson"
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
    "unknown"
)

private fun privateNonSucceededDiagnosticValueAllowed(key: String, value: String): Boolean {
    if (value.length > 64 || !value.matches(Regex("""[A-Za-z0-9_-]+"""))) return false
    return key != "failureKind" || value in privateNonSucceededAllowedFailureKinds
}

private fun privateNonSucceededDeviceControlObservationValueAllowed(
    key: String,
    value: String
): Boolean = when (key) {
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

private fun builtInToolNames(): Set<String> = builtInToolSpecs.mapTo(linkedSetOf()) { spec -> spec.name }

private fun definitionsFor(providers: List<ToolProvider>): List<ToolDefinition> = providers.flatMap { provider ->
    provider.specs().map(::ToolDefinition)
}

internal object BuiltInToolProvider : ToolProvider {
    override fun specs(): List<ToolSpec> = builtInToolSpecs
}

/**
 * The single canonical built-in-only [ToolRegistry] instance.
 *
 * WHY this exists: constructing a `ToolRegistry()` is NOT cheap — the constructor parses
 * ~90 JSON input/output schemas and compiles their `pattern` regexes
 * (`ToolArgumentValidator.fromSchema` / `ToolResultDataValidator.fromSchema`). Several
 * call sites used `= ToolRegistry()` as a default parameter value, so every invocation
 * rebuilt the whole table. On the confirmation-sheet path that ran per recomposition, on
 * the main thread. A [ToolRegistry] is immutable after construction, so one shared
 * instance is safe to publish and read from any thread.
 *
 * WHAT THIS IS NOT: it contains ONLY built-in tools. It does NOT know about tools
 * contributed by extension modules (plan tools, MCP tools, …). Any caller whose decision
 * depends on module tools — permission gating, special access gating, consent gating,
 * risk/confirmation policy — MUST be handed the module-aware registry built in
 * `SolinAppContainer` instead of falling back here. Falling back silently yields "unknown
 * tool", and an unknown tool produces NO permission or consent requirement, which is
 * fail-OPEN for that gate.
 */
val defaultBuiltInToolRegistry: ToolRegistry by lazy { ToolRegistry() }

/** Built-in specs in registration order (pinned by BuiltInToolSpecSnapshotTest). */
private val builtInToolSpecs: List<ToolSpec> =
    externalAndDraftToolSpecs +
        deviceQueryToolSpecs +
        uiControlToolSpecs +
        orchestrationToolSpecs +
        assistantIntentToolSpecs

private val toolDefinitionsByName: Map<String, ToolDefinition> =
    builtInToolSpecs.map(::ToolDefinition).associateBy { it.spec.name }
