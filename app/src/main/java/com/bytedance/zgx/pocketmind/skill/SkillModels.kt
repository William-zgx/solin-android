package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class SkillManifest(
    val id: String,
    val version: Int,
    val title: String,
    val description: String,
    val triggerExamples: List<String>,
    val requiredTools: List<String>,
    val inputSchemaJson: String,
    val riskLevel: RiskLevel,
)

data class ParsedSkillDraft(
    val draft: ActionDraft,
    val request: ToolRequest,
)

fun interface SkillPlanAdapter {
    fun parse(input: String): ParsedSkillDraft?
}

data class SkillDefinition(
    val manifest: SkillManifest,
    val planAdapter: SkillPlanAdapter? = null,
    val planPriority: Int = Int.MAX_VALUE,
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
    val manifestsById: Map<String, SkillManifest> = definitions
        .map { definition -> definition.manifest }
        .associateBy { manifest -> manifest.id }
    val skillIdByToolName: Map<String, String> = definitions
        .flatMap { definition ->
            definition.directToolNames.map { toolName -> toolName to definition.manifest.id }
        }
        .toMap()
    val plannableDefinitions: List<SkillDefinition> = definitions
        .filter { definition -> definition.planAdapter != null }
        .sortedBy { definition -> definition.planPriority }

    fun manifests(): List<SkillManifest> =
        definitions.map { definition -> definition.manifest }
}

fun SkillManifest.authorizationContractHash(): String {
    val identity = buildString {
        appendLengthPrefixed(id)
        appendLengthPrefixed(version.toString())
        appendLengthPrefixed(riskLevel.name)
        requiredTools.sorted().forEach(::appendLengthPrefixed)
        appendLengthPrefixed(inputSchemaJson.normalizedJsonForContract())
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
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
}

data class SkillPlanValidation(
    val errors: List<String>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

fun SkillPlan.validateStructure(
    toolRegistry: ToolRegistry = ToolRegistry(),
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

private fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return optDouble(name)
}

private fun JSONObject.optStringOrNull(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name)

private fun JSONObject.optStringSetOrNull(name: String): Set<String>? {
    val array = optJSONArray(name) ?: return null
    return buildSet {
        for (index in 0 until array.length()) {
            add(array.getString(index))
        }
    }
}

private fun Throwable.cleanMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

private fun StringBuilder.appendLengthPrefixed(value: String) {
    append(value.length)
    append(':')
    append(value)
    append(';')
}

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
