package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.common.appendLengthPrefixed
import com.bytedance.zgx.solin.common.keysSet
import com.bytedance.zgx.solin.common.optDoubleOrNull
import com.bytedance.zgx.solin.common.optStringOrNull
import com.bytedance.zgx.solin.common.optStringSet
import com.bytedance.zgx.solin.common.optStringSetOrNull
import com.bytedance.zgx.solin.common.sha256Hex
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import org.json.JSONArray
import org.json.JSONObject
import com.bytedance.zgx.solin.logging.cleanMessage

data class SkillManifest(
    val id: String,
    val version: Int,
    val title: String,
    val description: String,
    val triggerExamples: List<String>,
    val requiredTools: List<String>,
    val inputSchemaJson: String,
    val riskLevel: RiskLevel,
    val lowRiskAppControlEligible: Boolean = false,
    val continuesAfterUnverifiedOpenAppLaunch: Boolean = false,
    val backgroundExecution: SkillBackgroundExecution? = null,
    /**
     * Optional natural-language "when to use this skill" hint surfaced to the model in the
     * advisory `<available_skills>` catalog. Display/selection metadata only — deliberately NOT
     * part of [authorizationContractHash]: changing it must never invalidate an existing skill's
     * authorization, and it can never widen tools, risk, or execution. Dispatch stays deterministic
     * and rule-first; this only helps the model phrase/route a request, never authorizes execution.
     */
    val whenToUse: String = "",
)

data class SkillBackgroundExecution(
    val requiredTools: List<String> = emptyList(),
    val userConfigured: Boolean = true,
    val minimumIntervalMinutes: Long = 60L,
    val localOnly: Boolean = true,
    val allowedWork: Set<SkillBackgroundWork>,
    val foregroundConfirmationForOutboundOrExecution: Boolean = true,
)

enum class SkillBackgroundWork {
    ReadOnlyLocalState,
    PostLocalNotification,
    OutboundNetwork,
    ExecuteExternalAction,
}

data class SkillDefinition(
    val manifest: SkillManifest,
    val directToolNames: List<String> = manifest.requiredTools,
    val triggerExamples: List<String> = manifest.triggerExamples,
) {
    init {
        val undeclaredDirectTools = directToolNames - manifest.requiredTools.toSet()
        require(undeclaredDirectTools.isEmpty()) {
            "Skill ${manifest.id} maps undeclared direct tool(s): ${undeclaredDirectTools.sorted().joinToString()}"
        }
    }
}

class SkillCatalog(
    definitions: List<SkillDefinition>,
) {
    val definitions: List<SkillDefinition> = definitions.toList()
    init {
        val duplicateManifestIds = definitions.map { definition -> definition.manifest.id }.duplicates()
        require(duplicateManifestIds.isEmpty()) {
            "Duplicate skill manifest id(s): ${duplicateManifestIds.joinToString()}"
        }
        val duplicateDirectToolNames = definitions
            .flatMap { definition -> definition.directToolNames }
            .duplicates()
        require(duplicateDirectToolNames.isEmpty()) {
            "Duplicate skill direct tool name(s): ${duplicateDirectToolNames.joinToString()}"
        }
    }

    val manifestsById: Map<String, SkillManifest> = definitions
        .map { definition -> definition.manifest }
        .associateBy { manifest -> manifest.id }
    val skillIdByToolName: Map<String, String> = definitions
        .flatMap { definition ->
            definition.directToolNames.map { toolName -> toolName to definition.manifest.id }
        }
        .toMap()

    fun manifests(): List<SkillManifest> =
        definitions.map { definition -> definition.manifest }
}

private fun List<String>.duplicates(): List<String> =
    groupingBy { value -> value }
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys
        .sorted()

fun SkillManifest.authorizationContractHash(): String {
    val identity = buildString {
        appendLengthPrefixed(id)
        appendLengthPrefixed(version.toString())
        appendLengthPrefixed(riskLevel.name)
        appendLengthPrefixed(lowRiskAppControlEligible.toString())
        appendLengthPrefixed(continuesAfterUnverifiedOpenAppLaunch.toString())
        appendLengthPrefixed(backgroundExecution?.authorizationIdentity().orEmpty())
        requiredTools.sorted().forEach(::appendLengthPrefixed)
        appendLengthPrefixed(inputSchemaJson.normalizedJsonForContract())
    }
    return sha256Hex(identity)
}

private fun SkillBackgroundExecution.authorizationIdentity(): String =
    buildString {
        requiredTools.sorted().forEach(::appendLengthPrefixed)
        appendLengthPrefixed(userConfigured.toString())
        appendLengthPrefixed(minimumIntervalMinutes.toString())
        appendLengthPrefixed(localOnly.toString())
        allowedWork.map { work -> work.name }.sorted().forEach(::appendLengthPrefixed)
        appendLengthPrefixed(foregroundConfirmationForOutboundOrExecution.toString())
    }

data class SkillRequest(
    val id: String,
    val skillId: String,
    val arguments: Map<String, String>,
    val reason: String,
)

data class SkillPlan(
    val request: SkillRequest,
    val manifest: SkillManifest,
    val steps: List<SkillStep>,
)

sealed class SkillStep {
    abstract val id: String
    abstract val dependsOn: List<String>

    data class ToolStep(
        val request: ToolRequest,
        val draft: ActionDraft,
        override val id: String = "tool:${request.id}",
        override val dependsOn: List<String> = emptyList(),
        val argumentBindings: Map<String, String> = emptyMap(),
    ) : SkillStep()

    data class ModelStep(
        override val id: String,
        override val dependsOn: List<String>,
        val title: String,
        val instruction: String,
        val inputBindings: Map<String, String>,
        val outputKey: String,
        val keepsSensitiveInputLocal: Boolean = true,
    ) : SkillStep()

    /**
     * Conditional control-flow step. After the source step ([dependsOn].last) completes, its result
     * is evaluated against [condition]; execution jumps to [onMatchStepId] on a match, otherwise to
     * [onElseStepId] (or completes the run when [onElseStepId] is null).
     *
     * Fail-closed / termination guarantees enforced by [validateStructure]:
     * - Branch targets must be *forward* steps (strictly later than this branch), so control flow is
     *   a DAG with no cycles — a branch can skip fallback steps on success or fall into them on
     *   failure, but can never loop. This keeps the index-based confirmation-restore path valid
     *   (branches are never a tool-confirmation boundary).
     * - A branch has exactly one incoming source step ([dependsOn] has a single entry).
     */
    data class BranchStep(
        override val id: String,
        override val dependsOn: List<String>,
        val condition: StepOutcomeCondition,
        val onMatchStepId: String,
        val onElseStepId: String? = null,
    ) : SkillStep()
}

/**
 * A predicate over a completed tool step's result, used by [SkillStep.BranchStep] to choose the next
 * step. Kept intentionally small and declarative so branch decisions are deterministic, auditable,
 * and injection-resistant (no free-form model expression).
 */
sealed class StepOutcomeCondition {
    /** Matches when the source step's app-search outcome equals [outcome] (advanced/no_change/…). */
    data class AppSearchOutcomeEquals(val outcome: String) : StepOutcomeCondition()

    /** Matches when the source step verified a search result (searchVerificationStatus == verified). */
    data object SearchVerified : StepOutcomeCondition()

    /** Matches when a specific result-data key equals [value]. */
    data class ResultDataEquals(val key: String, val value: String) : StepOutcomeCondition()
}

/**
 * Evaluate a branch condition against a completed source step's output map. Unknown/missing data
 * fails the match (fail-closed): a branch never "matches" on absent evidence.
 */
internal fun StepOutcomeCondition.matches(sourceOutput: Map<String, String>): Boolean =
    when (this) {
        is StepOutcomeCondition.AppSearchOutcomeEquals ->
            sourceOutput["uiActionOutcome"] == outcome
        is StepOutcomeCondition.SearchVerified ->
            sourceOutput["searchVerificationStatus"] == "verified"
        is StepOutcomeCondition.ResultDataEquals ->
            sourceOutput[key] == value
    }

data class SkillPlanValidation(
    val errors: List<String>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

fun SkillPlan.validateStructure(
    toolRegistry: ToolRegistry = defaultBuiltInToolRegistry,
): SkillPlanValidation {
    val errors = mutableListOf<String>()
    val seenStepIds = mutableSetOf<String>()
    val seenToolRequestIds = mutableSetOf<String>()
    val outputKeysByStepId = linkedMapOf<String, Set<String>>()
    val privateOutputRefs = mutableSetOf<String>()

    if (request.skillId != manifest.id) {
        errors += "skill request ${request.skillId} does not match manifest ${manifest.id}"
    }
    manifest.validateRequestArguments(request.arguments, errors)
    manifest.validateRequiredToolContract(toolRegistry, errors)

    if (steps.isEmpty()) {
        errors += "skill plan must contain at least one step"
    }

    // Index every step id up front so BranchStep targets (which are forward references) can be
    // validated for existence and forward-only direction.
    val stepIndexById: Map<String, Int> = steps
        .mapIndexedNotNull { index, step -> step.id.takeIf { it.isNotBlank() }?.let { it to index } }
        .toMap()

    steps.forEachIndexed { index, step ->
        val priorStepIds = seenStepIds.toSet()
        if (step.id.isBlank()) {
            errors += "step[$index] id must not be blank"
        }
        if (step.id.isInvalidSourceRefComponent()) {
            errors += "step[$index] id must not contain source-ref delimiter: ${step.id}"
        }
        if (!seenStepIds.add(step.id)) {
            errors += "duplicate step id: ${step.id}"
        }
        step.dependsOn.forEach { dependency ->
            if (dependency !in priorStepIds) {
                errors += "step ${step.id} depends on missing or later step: $dependency"
            }
        }

        when (step) {
            is SkillStep.ToolStep -> {
                if (step.request.id.isBlank()) {
                    errors += "tool step ${step.id} request id must not be blank"
                }
                if (!seenToolRequestIds.add(step.request.id)) {
                    errors += "duplicate tool request id: ${step.request.id}"
                }
                if (step.request.toolName !in manifest.requiredTools) {
                    errors += "tool ${step.request.toolName} is not declared by skill ${manifest.id}"
                }
                val toolSpec = toolRegistry.specFor(step.request.toolName)
                if (toolSpec == null) {
                    errors += "tool ${step.request.toolName} is not registered"
                }
                if (step.draft.functionName != step.request.toolName) {
                    errors += "step ${step.id} draft function does not match tool request"
                }
                toolSpec?.inputSchemaJson?.schemaContract()?.let { schema ->
                    val unknownRequestArguments = step.request.arguments.keys - schema.propertyNames
                    if (unknownRequestArguments.isNotEmpty()) {
                        errors += "step ${step.id} request uses undeclared tool argument(s): " +
                            unknownRequestArguments.sorted().joinToString()
                    }
                    val unknownBindingTargets = step.argumentBindings.keys - schema.propertyNames
                    if (unknownBindingTargets.isNotEmpty()) {
                        errors += "step ${step.id} binds undeclared tool argument(s): " +
                            unknownBindingTargets.sorted().joinToString()
                    }
                    val missingRequiredArguments = schema.requiredNames -
                        (step.request.arguments.keys + step.argumentBindings.keys)
                    if (missingRequiredArguments.isNotEmpty()) {
                        errors += "step ${step.id} does not provide required tool argument(s): " +
                            missingRequiredArguments.sorted().joinToString()
                    }
                }
                val literalBindingCollisions = step.argumentBindings.keys
                    .intersect(step.request.arguments.keys + step.draft.parameters.keys)
                if (literalBindingCollisions.isNotEmpty()) {
                    errors += "step ${step.id} binds already-declared tool argument(s): " +
                        literalBindingCollisions.sorted().joinToString()
                }
                val mismatchedLiteralArguments = step.request.arguments.keys
                    .intersect(step.draft.parameters.keys)
                    .filter { key -> step.request.arguments[key] != step.draft.parameters[key] }
                if (mismatchedLiteralArguments.isNotEmpty()) {
                    errors += "step ${step.id} request and draft disagree on argument(s): " +
                        mismatchedLiteralArguments.sorted().joinToString()
                }
                step.argumentBindings.values.validateSourceRefs(
                    currentStepId = step.id,
                    priorStepIds = priorStepIds,
                    inputKeys = request.arguments.keys,
                    outputKeysByStepId = outputKeysByStepId,
                    privateOutputRefs = privateOutputRefs,
                    rejectPrivateOutputRefs = true,
                    errors = errors,
                )
                outputKeysByStepId[step.id] = step.availableOutputKeys(toolRegistry)
                privateOutputRefs += toolRegistry.privateOutputKeysFor(step.request.toolName)
                    .map { key -> "${step.id}.$key" }
            }

            is SkillStep.ModelStep -> {
                if (step.outputKey.isBlank()) {
                    errors += "model step ${step.id} outputKey must not be blank"
                }
                if (step.outputKey.isInvalidSourceRefComponent()) {
                    errors += "model step ${step.id} outputKey must not contain source-ref delimiter"
                }
                step.inputBindings.values.validateSourceRefs(
                    currentStepId = step.id,
                    priorStepIds = priorStepIds,
                    inputKeys = request.arguments.keys,
                    outputKeysByStepId = outputKeysByStepId,
                    privateOutputRefs = privateOutputRefs,
                    rejectPrivateOutputRefs = false,
                    errors = errors,
                )
                outputKeysByStepId[step.id] = setOf(step.outputKey)
            }

            is SkillStep.BranchStep -> {
                if (step.dependsOn.size != 1) {
                    errors += "branch step ${step.id} must depend on exactly one source step"
                }
                // Branch targets are forward references: must exist and be strictly later than the
                // branch itself, guaranteeing acyclic (terminating) control flow.
                val targets = listOfNotNull(step.onMatchStepId, step.onElseStepId)
                targets.forEach { targetId ->
                    val targetIndex = stepIndexById[targetId]
                    when {
                        targetIndex == null ->
                            errors += "branch step ${step.id} targets unknown step: $targetId"
                        targetIndex <= index ->
                            errors += "branch step ${step.id} must target a later step (forward-only): $targetId"
                    }
                }
                if (step.onMatchStepId == step.id || step.onElseStepId == step.id) {
                    errors += "branch step ${step.id} must not target itself"
                }
                // A branch produces no bindable output.
                outputKeysByStepId[step.id] = emptySet()
            }
        }
    }

    return SkillPlanValidation(errors)
}

private fun SkillManifest.validateRequiredToolContract(
    toolRegistry: ToolRegistry,
    errors: MutableList<String>,
) {
    val duplicateTools = requiredTools
        .groupingBy { toolName -> toolName }
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys
    if (duplicateTools.isNotEmpty()) {
        errors += "skill $id declares duplicate required tool(s): ${duplicateTools.sorted().joinToString()}"
    }

    val requiredSpecs = requiredTools.mapNotNull { toolName ->
        toolRegistry.specFor(toolName)
            ?: run {
                errors += "skill $id requires unknown tool: $toolName"
                null
            }
    }
    val highestRisk = requiredSpecs.maxByOrNull { spec -> spec.riskLevel.ordinal }?.riskLevel
    if (highestRisk != null && riskLevel.ordinal < highestRisk.ordinal) {
        errors += "skill $id risk level $riskLevel is below required tool risk $highestRisk"
    }
}

private fun SkillManifest.validateRequestArguments(
    arguments: Map<String, String>,
    errors: MutableList<String>,
) {
    val schema = runCatching { JSONObject(inputSchemaJson) }
        .getOrElse { throwable ->
            errors += "skill $id input schema is invalid JSON: ${throwable.cleanMessage()}"
            return
        }
    if (schema.optString("type") != "object") {
        errors += "skill $id input schema must declare type=object"
        return
    }

    val propertiesJson = schema.optJSONObject("properties") ?: JSONObject()
    val propertyNames = propertiesJson.keysSet()
    if (!schema.optBoolean("additionalProperties", true)) {
        val unknownArguments = arguments.keys - propertyNames
        if (unknownArguments.isNotEmpty()) {
            errors += "skill $id does not accept argument(s): ${unknownArguments.sorted().joinToString()}"
        }
    }

    val requiredArguments = schema.optStringSet("required")
    val unknownRequired = requiredArguments - propertyNames
    if (unknownRequired.isNotEmpty()) {
        errors += "skill $id input schema requires undeclared properties: ${unknownRequired.sorted().joinToString()}"
    }
    val missingArguments = requiredArguments
        .filter { argumentName ->
            val value = arguments[argumentName]
            val expectedType = propertiesJson.optJSONObject(argumentName)?.optStringOrNull("type")
            if (expectedType == "boolean") {
                value == null
            } else {
                value.isNullOrBlank()
            }
        }
    if (missingArguments.isNotEmpty()) {
        errors += "skill $id requires argument(s): ${missingArguments.sorted().joinToString()}"
    }

    arguments.forEach { (argumentName, value) ->
        val property = propertiesJson.optJSONObject(argumentName) ?: return@forEach
        property.validateValue(
            skillId = id,
            argumentName = argumentName,
            value = value,
            errors = errors,
        )
    }
}

private fun JSONObject.validateValue(
    skillId: String,
    argumentName: String,
    value: String,
    errors: MutableList<String>,
) {
    optStringSetOrNull("enum")?.let { allowed ->
        if (value !in allowed) {
            errors += "skill $skillId argument $argumentName has invalid value"
            return
        }
    }

    when (optStringOrNull("type")?.lowercase()) {
        "string" -> {
            optIntOrNull("minLength")?.let { minLength ->
                if (value.trim().length < minLength) {
                    errors += "skill $skillId argument $argumentName must have at least $minLength character(s)"
                    return
                }
            }
            optIntOrNull("maxLength")?.let { maxLength ->
                if (value.length > maxLength) {
                    errors += "skill $skillId argument $argumentName must have at most $maxLength character(s)"
                    return
                }
            }
            optStringOrNull("pattern")?.let { pattern ->
                if (!Regex(pattern).matches(value)) {
                    errors += "skill $skillId argument $argumentName does not match required pattern"
                }
            }
        }

        "integer" -> {
            val numeric = value.toLongOrNull()
            if (numeric == null) {
                errors += "skill $skillId argument $argumentName must be an integer"
            } else {
                validateNumericRange(skillId, argumentName, numeric.toDouble(), errors)
            }
        }

        "number" -> {
            val numeric = value.toDoubleOrNull()
            if (numeric == null) {
                errors += "skill $skillId argument $argumentName must be a number"
            } else {
                validateNumericRange(skillId, argumentName, numeric, errors)
            }
        }

        "boolean" -> {
            if (value.toBooleanStrictOrNull() == null) {
                errors += "skill $skillId argument $argumentName must be true or false"
            }
        }

        "array" -> {
            runCatching { org.json.JSONArray(value) }
                .onFailure { errors += "skill $skillId argument $argumentName must be an array" }
        }

        "object" -> {
            runCatching { JSONObject(value) }
                .onFailure { errors += "skill $skillId argument $argumentName must be an object" }
        }
    }
}

private fun JSONObject.validateNumericRange(
    skillId: String,
    argumentName: String,
    numeric: Double,
    errors: MutableList<String>,
) {
    optDoubleOrNull("minimum")?.let { min ->
        if (numeric < min) {
            errors += "skill $skillId argument $argumentName must be at least $min"
            return
        }
    }
    optDoubleOrNull("maximum")?.let { max ->
        if (numeric > max) {
            errors += "skill $skillId argument $argumentName must be at most $max"
            return
        }
    }
    optDoubleOrNull("exclusiveMinimum")?.let { min ->
        if (numeric <= min) {
            errors += "skill $skillId argument $argumentName must be greater than $min"
            return
        }
    }
    optDoubleOrNull("exclusiveMaximum")?.let { max ->
        if (numeric >= max) {
            errors += "skill $skillId argument $argumentName must be less than $max"
        }
    }
}

private fun Collection<String>.validateSourceRefs(
    currentStepId: String,
    priorStepIds: Set<String>,
    inputKeys: Set<String>,
    outputKeysByStepId: Map<String, Set<String>>,
    privateOutputRefs: Set<String>,
    rejectPrivateOutputRefs: Boolean,
    errors: MutableList<String>,
) {
    forEach { sourceRef ->
        val parsed = sourceRef.parseSourceRef()
        if (parsed == null) {
            errors += "step $currentStepId source reference must be <stepId>.<outputKey>: $sourceRef"
            return@forEach
        }
        val sourceStepId = parsed.stepId
        val sourceKey = parsed.outputKey
        when {
            sourceStepId == "input" && sourceKey !in inputKeys ->
                errors += "step $currentStepId reads from missing skill input: $sourceRef"

            sourceStepId.isNotBlank() && sourceStepId !in priorStepIds && sourceStepId != "input" ->
                errors += "step $currentStepId reads from missing or later step: $sourceRef"

            sourceStepId != "input" && sourceKey !in outputKeysByStepId.getValue(sourceStepId) ->
                errors += "step $currentStepId reads from missing output key: $sourceRef"

            rejectPrivateOutputRefs && sourceRef in privateOutputRefs ->
                errors += "step $currentStepId private tool output cannot be bound directly to tool argument: $sourceRef"
        }
    }
}

private fun SkillStep.ToolStep.availableOutputKeys(toolRegistry: ToolRegistry): Set<String> =
    buildSet {
        toolRegistry.specFor(request.toolName)
            ?.outputSchemaJson
            ?.schemaContract()
            ?.propertyNames
            ?.let { keys -> addAll(keys) }
        add("summary")
        addAll(draft.parameters.keys)
    }

private data class SourceRef(
    val stepId: String,
    val outputKey: String,
)

private fun String.parseSourceRef(): SourceRef? {
    val firstDelimiter = indexOf('.')
    if (firstDelimiter <= 0 || firstDelimiter == lastIndex) return null
    if (indexOf('.', startIndex = firstDelimiter + 1) >= 0) return null
    return SourceRef(
        stepId = substring(0, firstDelimiter),
        outputKey = substring(firstDelimiter + 1),
    )
}

private data class SchemaContract(
    val propertyNames: Set<String>,
    val requiredNames: Set<String>,
)

private fun String.schemaContract(): SchemaContract =
    runCatching {
        val json = JSONObject(this)
        SchemaContract(
            propertyNames = json.optJSONObject("properties")?.keysSet().orEmpty(),
            requiredNames = json.optStringSet("required"),
        )
    }.getOrDefault(SchemaContract(emptySet(), emptySet()))

private fun String.isInvalidSourceRefComponent(): Boolean =
    contains('.')

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name)) optInt(name) else null



private fun String.normalizedJsonForContract(): String =
    runCatching { JSONObject(this).canonicalJsonString() }
        .getOrDefault(trim())

private fun Any?.canonicalJsonString(): String =
    when {
        this == null || this == JSONObject.NULL -> "null"
        this is JSONObject -> keysSet()
            .sorted()
            .joinToString(prefix = "{", postfix = "}") { key ->
                "${JSONObject.quote(key)}:${opt(key).canonicalJsonString()}"
            }

        this is JSONArray -> (0 until length())
            .joinToString(prefix = "[", postfix = "]") { index ->
                opt(index).canonicalJsonString()
            }

        this is Number || this is Boolean -> toString()
        else -> JSONObject.quote(toString())
    }
