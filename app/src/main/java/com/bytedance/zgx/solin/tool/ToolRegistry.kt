package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.SystemSettingsTargets
import com.bytedance.zgx.solin.device.hasDangerousActionText
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrContract
import com.bytedance.zgx.solin.undo.UndoPlan
import com.bytedance.zgx.solin.undo.UndoPolicy
import org.json.JSONObject

class ToolRegistry private constructor(
    definitions: List<ToolDefinition>,
    @Suppress("UNUSED_PARAMETER") internalTag: InternalTag,
) {
    private enum class InternalTag { Definitions }

    private val definitionsByName: Map<String, ToolDefinition> = definitions.associateBy { it.spec.name }
    private val orderedSpecs: List<ToolSpec> = definitions.map { it.spec }

    /**
     * Undo policies, built once at construction and never mutated afterwards.
     *
     * WHY immutable: a ToolRegistry instance is shared across threads — the parallel tool
     * batch runs on `Dispatchers.IO` while [undoPolicyFor] is read from the executor and
     * audit paths. The previous `MutableMap` plus a public `registerUndoPolicy` mutator
     * left an unsynchronized write path open on a concurrently-read map. In practice all
     * writes happened during `init` (no caller ever invoked the mutator), so this is a
     * contract fix, not a live-bug fix: the safety property is now enforced by the type
     * instead of by convention. If a SolinModule ever needs to contribute undo policies,
     * add them as a constructor parameter merged here — do not reintroduce a setter.
     */
    private val undoPolicies: Map<String, UndoPolicy> = defaultUndoPolicies()

    init {
        require(definitionsByName.size == definitions.size) { "Tool names must be unique." }
        definitions.forEach { definition ->
            validateRuntimePermissionDescriptorContract(definition.spec)
            validateExactlyOneOfContract(definition.spec)
        }
    }

    constructor() : this(BuiltInToolProvider)

    constructor(providers: List<ToolProvider>) : this(*providers.toTypedArray())

    constructor(vararg providers: ToolProvider) : this(definitionsFor(providers.toList()), InternalTag.Definitions)

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

    fun undoPolicyFor(toolName: String): UndoPolicy? = undoPolicies[toolName]

    private fun defaultUndoPolicies(): Map<String, UndoPolicy> {
        val externalHandoff = UndoPolicy { _, _ ->
            UndoPlan.ExternalHandoff("performed externally; handoff to user")
        }
        val notApplicable = UndoPolicy { _, _ -> UndoPlan.NotApplicable }
        val notUndoableUi = UndoPolicy { _, _ -> UndoPlan.NotUndoable("irreversible UI action") }

        // External handoff tools (user completes action outside Solin), including human takeover.
        val externalHandoffTools = listOf(
            MobileActionFunctions.SHARE_TEXT,
            MobileActionFunctions.COMPOSE_EMAIL,
            MobileActionFunctions.CREATE_CALENDAR_EVENT,
            MobileActionFunctions.CREATE_CONTACT_DRAFT,
            MobileActionFunctions.SET_SYSTEM_ALARM,
            MobileActionFunctions.SET_SYSTEM_TIMER,
            MobileActionFunctions.OPEN_CAMERA,
            MobileActionFunctions.OPEN_DEEP_LINK,
            MobileActionFunctions.OPEN_APP_BY_NAME,
            MobileActionFunctions.OPEN_APP_INTENT,
            MobileActionFunctions.OPEN_APP_DEEP_TARGET,
            MobileActionFunctions.OPEN_WIFI_SETTINGS,
            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
            MobileActionFunctions.OPEN_SYSTEM_SETTINGS,
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
            MobileActionFunctions.SEARCH_MAPS,
            MobileActionFunctions.WEB_SEARCH,
            MobileActionFunctions.SCHEDULE_REMINDER,
            MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
            MobileActionFunctions.CANCEL_REMINDER,
            MobileActionFunctions.TAKE_OVER,
        )

        // Read-only / observation tools — undo not applicable
        val notApplicableTools = listOf(
            MobileActionFunctions.QUERY_CONTACTS,
            MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
            MobileActionFunctions.QUERY_FOREGROUND_APP,
            MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
            MobileActionFunctions.QUERY_RECENT_FILES,
            MobileActionFunctions.QUERY_BACKGROUND_TASKS,
            MobileActionFunctions.READ_CLIPBOARD,
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.NOTE,
            MobileActionFunctions.FINISH,
            // plan_read is not a MobileActionFunctions constant; registered by literal
            "plan_read",
        )

        // Irreversible UI actions
        val notUndoableUiTools = listOf(
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SCROLL,
            MobileActionFunctions.UI_SWIPE,
            MobileActionFunctions.UI_LONG_PRESS,
            MobileActionFunctions.UI_PRESS_KEY,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_PRESS_BACK,
            MobileActionFunctions.UI_WAIT,
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
        )

        return externalHandoffTools.associateWith { externalHandoff } +
            notApplicableTools.associateWith { notApplicable } +
            notUndoableUiTools.associateWith { notUndoableUi }
    }

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
                !request.arguments["target"].hasDangerousActionText()
            request.toolName == MobileActionFunctions.OPEN_SYSTEM_SETTINGS ->
                request.arguments["target"].orEmpty() in SystemSettingsTargets.confirmationBypassEligible
            else -> false
        }

    fun isLowRiskAppControlContinuationTool(request: ToolRequest): Boolean =
        isLowRiskDeviceActionConfirmationSkippable(request) &&
            hasTag(request.toolName, ToolCapabilityTag.LowRiskAppControlContinuation)

    fun isCheckpointedUiActionTool(toolName: String): Boolean =
        hasTag(toolName, ToolCapabilityTag.CheckpointedUiAction)

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
        toolSpecificArgumentInvariant(definition.spec, request)?.let { reason ->
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
        if (!spec.isEligibleForParallelBatch()) {
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
            ToolRegistry(definitionsFor(supportedActions), InternalTag.Definitions)
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
 * Currently only [ToolSpec.exactlyOneOf]. This used to be a `when (request.toolName)` with one
 * hard-coded branch per tool; keep it table-driven so a new mutual-exclusion rule is a spec
 * declaration, not an edit to shared validation code.
 */
private fun toolSpecificArgumentInvariant(spec: ToolSpec, request: ToolRequest): String? {
    spec.exactlyOneOf.forEach { group ->
        val supplied = group.count { name -> !request.arguments[name].isNullOrBlank() }
        if (supplied != 1) {
            return "Tool ${request.toolName} requires exactly one of ${group.humanReadableAlternatives()}"
        }
    }
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
    "uiActionOutcome",
    "uiActionOutcomeReason",
    "appSearchProgressStage",
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
    builtInToolSpecs.mapTo(linkedSetOf()) { spec -> spec.name }

private fun definitionsFor(providers: List<ToolProvider>): List<ToolDefinition> =
    providers.flatMap { provider ->
        provider.specs().map(::ToolDefinition)
    }

private object BuiltInToolProvider : ToolProvider {
    override fun specs(): List<ToolSpec> =
        builtInToolSpecs
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
 * contributed by a [com.bytedance.zgx.solin.module.SolinModule] (plan tools, MCP tools,
 * …). Any caller whose decision depends on module tools — permission gating, special
 * access gating, consent gating, risk/confirmation policy — MUST be handed the
 * module-aware registry built in `SolinAppContainer` instead of falling back here.
 * Falling back silently yields "unknown tool", and an unknown tool produces NO permission
 * or consent requirement, which is fail-OPEN for that gate.
 */
val defaultBuiltInToolRegistry: ToolRegistry by lazy { ToolRegistry() }

/**
 * Built-in tools exposed as a SolinModule. Always registered first; user modules
 * append or override via ToolHandler.
 */
class BuiltInToolsModule : com.bytedance.zgx.solin.module.SolinModule {
    override val moduleId: String get() = "builtin:tools"
    override fun register(registry: com.bytedance.zgx.solin.module.SolinModuleRegistry) {
        registry.addToolProvider(BuiltInToolProvider)
    }
}

private val recentImageOcrPrivateOutputKeys = setOf("ocrText")

private val observeCurrentScreenPrivateOutputKeys = setOf(
    "observationId",
    "packageName",
    "capturedAtMillis",
    "nodeCount",
    "actionableNodeCount",
    "textSummary",
    "nodesJson",
    "screenObservationJson",
    "screenWidthPx",
    "screenHeightPx",
    "screenshotPerception",
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
    "uiActionOutcome",
    "uiActionOutcomeReason",
    "appSearchProgressStage",
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
    "screenWidthPx",
    "screenHeightPx",
    "beforeScreenWidthPx",
    "beforeScreenHeightPx",
    "beforeScreenshotPerception",
    "afterScreenWidthPx",
    "afterScreenHeightPx",
    "afterScreenshotPerception",
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

private val builtInToolSpecs: List<ToolSpec> = listOf(
    ToolSpec(
        name = MobileActionFunctions.OPEN_WIFI_SETTINGS,
        title = "打开 Wi-Fi 设置",
        description = "打开系统 Wi-Fi 设置页，由用户在系统页面内继续操作。",
        inputSchemaJson = emptyObjectSchemaJson,
        outputSchemaJson = externalActivityOutputSchemaJson,
        capability = ToolCapability.DeviceSettings,
        permissions = setOf(ToolPermission.StartsExternalActivity),
        tags = lowRiskDeviceControlSessionTags,
    ),
    ToolSpec(
        name = MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
        title = "打开使用情况访问权限设置",
        description = "打开 Android 使用情况访问权限设置页，由用户手动为Solin授权。",
        inputSchemaJson = emptyObjectSchemaJson,
        outputSchemaJson = externalActivityOutputSchemaJson,
        capability = ToolCapability.DeviceSettings,
        permissions = setOf(ToolPermission.StartsExternalActivity),
        tags = deviceControlSessionTags,
    ),
    ToolSpec(
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
    ToolSpec(
        name = MobileActionFunctions.SEARCH_MAPS,
        title = "地图搜索",
        description = "使用外部地图应用搜索地点或路线关键词。",
        inputSchemaJson = querySchemaJson,
        outputSchemaJson = externalActivityOutputSchemaJson,
        capability = ToolCapability.ExternalNavigation,
        permissions = setOf(ToolPermission.StartsExternalActivity),
        tags = restoredPopupDeviceControlSessionTags,
    ),
    ToolSpec(
        name = MobileActionFunctions.WEB_SEARCH,
        title = "Web 搜索",
        description = "执行只读网络信息查询并返回摘要和结构化结果，不打开浏览器；query 必须是模型理解后的搜索关键词，不要直接复制用户原文；查询含最新/目前/当前/现在/今日/热门/排行/latest/current/recent/trending/hottest 或当前年份等当前性语义时以 freshness=current 执行；多主体比较、差值、汇总或交叉核验问题可对每个主体发起独立 web_search 工具调用，由宿主并发执行公开只读批次后再综合；疑似个人信息或密钥查询需要用户确认后才联网。",
        inputSchemaJson = webSearchInputSchemaJson,
        outputSchemaJson = webSearchOutputSchemaJson,
        capability = ToolCapability.WebSearch,
        riskLevel = RiskLevel.LowReadOnly,
        confirmationPolicy = ConfirmationPolicy.NotRequired,
        resultContinuationPolicy = ToolResultContinuationPolicy.PublicEvidence,
        executionMode = ToolExecutionMode.ConcurrentWhenIndependent,
    ),
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
        name = MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
        title = "打开手电筒设置",
        description = "打开系统设置页，由用户手动完成手电筒相关操作。",
        inputSchemaJson = emptyObjectSchemaJson,
        outputSchemaJson = externalActivityOutputSchemaJson,
        capability = ToolCapability.DeviceSettings,
        permissions = setOf(ToolPermission.StartsExternalActivity),
    ),
    ToolSpec(
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
        // Relative delay vs. absolute trigger time are mutually exclusive and one is mandatory.
        // Neither can be schema-`required` (each is individually optional) and the closed schema
        // dialect has no `oneOf`, so the XOR is declared here and enforced by ToolRegistry.validate.
        exactlyOneOf = setOf(setOf("delayMinutes", "triggerAtMillis")),
    ),
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
        name = MobileActionFunctions.OPEN_DEEP_LINK,
        title = "打开深链",
        description = "打开外部链接或深度链接，用户可在跳转后的应用继续操作。",
        inputSchemaJson = openDeepLinkSchemaJson,
        outputSchemaJson = externalActivityOutputSchemaJson,
        capability = ToolCapability.ExternalNavigation,
        permissions = setOf(ToolPermission.StartsExternalActivity),
    ),
    ToolSpec(
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
    ToolSpec(
        name = MobileActionFunctions.OPEN_APP_BY_NAME,
        title = "按名称打开应用",
        description = "按本机 launcher 中的用户可见应用名解析可启动应用并打开启动页；不接受任意 Intent action、URI、Activity 或 extras。",
        inputSchemaJson = openAppByNameSchemaJson,
        outputSchemaJson = externalActivityOutputSchemaJson,
        capability = ToolCapability.ExternalNavigation,
        permissions = setOf(ToolPermission.StartsExternalActivity),
        riskLevel = RiskLevel.MediumDraftOrNavigation,
        confirmationPolicy = ConfirmationPolicy.Required,
        pendingArgumentAllowlist = setOf("appName", "followUpIntent"),
        tags = lowRiskDeviceControlSessionTags + ToolCapabilityTag.OpenAppLaunch,
    ),
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
            "targetX",
            "targetY",
            "timeoutMillis",
            "expectedPackageName",
            "targetPackageName",
        ),
        privateOutputKeys = uiActionPrivateOutputKeys,
        redactedResultSummary = "已执行屏幕点击动作",
        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
        tags = conditionalLowRiskSequentialContinuationTags,
    ),
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
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
    ToolSpec(
        name = MobileActionFunctions.UI_SWIPE,
        title = "滑动当前屏幕",
        description = "在用户确认后通过 Accessibility 手势在归一化 0-1000 坐标间滑动（轮播、地图拖动、下拉刷新、侧滑等）；每次动作后重新观察屏幕并返回本地验证摘要。",
        inputSchemaJson = uiSwipeSchemaJson,
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
            "startXNorm",
            "startYNorm",
            "endXNorm",
            "endYNorm",
            "durationMillis",
            "timeoutMillis",
            "expectedPackageName",
            "targetPackageName",
        ),
        privateOutputKeys = uiActionPrivateOutputKeys,
        redactedResultSummary = "已执行屏幕滑动动作",
        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
        tags = checkpointedLowRiskSequentialContinuationTags,
    ),
    ToolSpec(
        name = MobileActionFunctions.UI_LONG_PRESS,
        title = "长按当前屏幕",
        description = "在用户确认后通过 Accessibility 手势在归一化 0-1000 坐标处长按（选中、拖拽入口、快捷菜单等）；每次动作后重新观察屏幕并返回本地验证摘要。",
        inputSchemaJson = uiLongPressSchemaJson,
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
            "xNorm",
            "yNorm",
            "holdMillis",
            "timeoutMillis",
            "expectedPackageName",
            "targetPackageName",
        ),
        privateOutputKeys = uiActionPrivateOutputKeys,
        redactedResultSummary = "已执行屏幕长按动作",
        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
        tags = checkpointedLowRiskSequentialContinuationTags,
    ),
    ToolSpec(
        name = MobileActionFunctions.UI_PRESS_KEY,
        title = "执行系统按键",
        description = "在用户确认后通过 Accessibility 执行白名单系统按键（home 回主屏 / recents 最近任务 / enter 回车确认 / delete 删除末字符）；不接受任意 keycode；每次动作后重新观察屏幕并返回本地验证摘要。",
        inputSchemaJson = uiPressKeySchemaJson,
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
            "key",
            "timeoutMillis",
            "expectedPackageName",
            "targetPackageName",
        ),
        privateOutputKeys = uiActionPrivateOutputKeys,
        redactedResultSummary = "已执行系统按键动作",
        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
        tags = checkpointedLowRiskSequentialContinuationTags,
    ),
    ToolSpec(
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
    ToolSpec(
        name = MobileActionFunctions.ASK_USER,
        title = "询问用户",
        description = "当需要额外信息才能继续处理用户请求时，暂停执行并向用户提出澄清问题；可提供可选的简短选项标签供用户点选回答，省略则等待用户自由文本回复。请克制使用——仅在请求确实存在歧义且无法通过已有上下文解决时调用。",
        inputSchemaJson = askUserInputSchemaJson,
        outputSchemaJson = askUserOutputSchemaJson,
        capability = ToolCapability.DeviceContext,
        permissions = emptySet(),
        riskLevel = RiskLevel.LowReadOnly,
        confirmationPolicy = ConfirmationPolicy.NotRequired,
        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
        executionMode = ToolExecutionMode.Sequential,
        tags = setOf(ToolCapabilityTag.UxInteraction),
    ),
    // ── Open-AutoGLM-inspired expanded action vocabulary ──
    ToolSpec(
        name = MobileActionFunctions.NOTE,
        title = "记录笔记",
        description = "向本次运行的暂存笔记中添加一条记录，供后续步骤参考。不会触发任何外部操作，不离开当前页面。对记录搜索结果、页面标题、验证证据等场景特别有用。",
        inputSchemaJson = noteInputSchemaJson,
        outputSchemaJson = noteOutputSchemaJson,
        capability = ToolCapability.Orchestration,
        permissions = emptySet(),
        riskLevel = RiskLevel.LowReadOnly,
        confirmationPolicy = ConfirmationPolicy.NotRequired,
        // Note content can be derived from screen text / OCR / other private local evidence,
        // so it is a private LocalOnly output that only a local model may continue from.
        privateOutputKeys = setOf("content"),
        redactedResultSummary = "已记录笔记",
        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
        tags = setOf(ToolCapabilityTag.Planning),
    ),
    ToolSpec(
        name = MobileActionFunctions.FINISH,
        title = "完成任务",
        description = "显式结束本次运行并附上完成总结。当用户的请求已完成时使用此动作，不要只是停止操作。",
        inputSchemaJson = finishInputSchemaJson,
        outputSchemaJson = finishOutputSchemaJson,
        capability = ToolCapability.Orchestration,
        permissions = emptySet(),
        riskLevel = RiskLevel.LowReadOnly,
        confirmationPolicy = ConfirmationPolicy.NotRequired,
        tags = setOf(ToolCapabilityTag.Planning),
    ),
    ToolSpec(
        name = MobileActionFunctions.TAKE_OVER,
        title = "人工接管",
        description = "将控制权交还给用户处理。当遇到登录页面、验证码、密码输入框或任何 Agent 无法自主处理的界面时使用此动作。用户完成操作后可继续本次运行。",
        inputSchemaJson = takeOverInputSchemaJson,
        outputSchemaJson = takeOverOutputSchemaJson,
        capability = ToolCapability.Orchestration,
        permissions = emptySet(),
        riskLevel = RiskLevel.LowReadOnly,
        confirmationPolicy = ConfirmationPolicy.NotRequired,
        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
        tags = setOf(ToolCapabilityTag.UxInteraction, ToolCapabilityTag.Planning),
    ),
)

private val toolDefinitionsByName: Map<String, ToolDefinition> =
    builtInToolSpecs.map(::ToolDefinition).associateBy { it.spec.name }
