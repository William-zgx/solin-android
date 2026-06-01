package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import org.json.JSONObject

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

fun SkillPlan.validateStructure(): SkillPlanValidation {
    val errors = mutableListOf<String>()
    val seenStepIds = mutableSetOf<String>()

    if (request.skillId != manifest.id) {
        errors += "skill request ${request.skillId} does not match manifest ${manifest.id}"
    }
    manifest.validateRequestArguments(request.arguments, errors)

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
                if (step.request.toolName !in manifest.requiredTools) {
                    errors += "tool ${step.request.toolName} is not declared by skill ${manifest.id}"
                }
                if (step.draft.functionName != step.request.toolName) {
                    errors += "step ${step.id} draft function does not match tool request"
                }
                step.argumentBindings.values.validateSourceRefs(
                    currentStepId = step.id,
                    priorStepIds = priorStepIds,
                    inputKeys = request.arguments.keys,
                    errors = errors,
                )
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
                    errors = errors,
                )
            }
        }
    }

    return SkillPlanValidation(errors)
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
    errors: MutableList<String>,
) {
    forEach { sourceRef ->
        val sourceStepId = sourceRef.substringBefore('.', missingDelimiterValue = "")
        val sourceKey = sourceRef.substringAfter('.', missingDelimiterValue = "")
        when {
            sourceStepId == "input" && sourceKey !in inputKeys ->
                errors += "step $currentStepId reads from missing skill input: $sourceRef"

            sourceStepId.isNotBlank() && sourceStepId !in priorStepIds && sourceStepId != "input" ->
                errors += "step $currentStepId reads from missing or later step: $sourceRef"
        }
    }
}

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
