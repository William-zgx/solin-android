package com.bytedance.zgx.solin.tool

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

private val SUPPORTED_ROOT_SCHEMA_KEYS = setOf(
    "type",
    "required",
    "properties",
    "additionalProperties",
)

internal data class ToolArgumentValidator(
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
            validateRootSchema(spec.name, "input schema", schema)
            val propertiesJson = schema.optJSONObject("properties") ?: JSONObject()
            val properties = propertiesJson.keysSet().associateWith { propertyName ->
                val propertyJson = propertiesJson.optJSONObject(propertyName) ?: JSONObject()
                validatePropertySchema(spec.name, "input schema", propertyName, propertyJson)
                PropertyRule(
                    type = propertyJson.optStringOrNull("type"),
                    constValue = propertyJson.optConstStringOrNull(),
                    minLength = propertyJson.optIntOrNull("minLength"),
                    maxLength = propertyJson.optIntOrNull("maxLength"),
                    pattern = propertyJson.optStringOrNull("pattern")?.let(::Regex),
                    enum = propertyJson.optStringSetOrNull("enum")?.toSet(),
                    format = propertyJson.optStringOrNull("format"),
                    contentMediaType = propertyJson.optStringOrNull("contentMediaType"),
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

internal data class ToolResultDataValidator(
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
            validateRootSchema(spec.name, "output schema", schema)
            val propertiesJson = schema.optJSONObject("properties") ?: JSONObject()
            val properties = propertiesJson.keysSet().associateWith { propertyName ->
                val propertyJson = propertiesJson.optJSONObject(propertyName) ?: JSONObject()
                validatePropertySchema(spec.name, "output schema", propertyName, propertyJson)
                PropertyRule(
                    type = propertyJson.optStringOrNull("type"),
                    constValue = propertyJson.optConstStringOrNull(),
                    minLength = propertyJson.optIntOrNull("minLength"),
                    maxLength = propertyJson.optIntOrNull("maxLength"),
                    pattern = propertyJson.optStringOrNull("pattern")?.let(::Regex),
                    enum = propertyJson.optStringSetOrNull("enum")?.toSet(),
                    format = propertyJson.optStringOrNull("format"),
                    contentMediaType = propertyJson.optStringOrNull("contentMediaType"),
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

private fun validateRootSchema(
    toolName: String,
    schemaKind: String,
    schema: JSONObject,
) {
    val unsupportedKeys = schema.keysSet() - SUPPORTED_ROOT_SCHEMA_KEYS
    require(unsupportedKeys.isEmpty()) {
        "Tool $toolName $schemaKind declares unsupported schema keyword(s): " +
            unsupportedKeys.sorted().joinToString()
    }
    require(schema.has("type") && !schema.isNull("type")) {
        "Tool $toolName $schemaKind must declare type=object"
    }
    require(schema.get("type") == "object") {
        "Tool $toolName $schemaKind must declare type=object"
    }
    if (schema.has("properties") && !schema.isNull("properties")) {
        require(schema.get("properties") is JSONObject) {
            "Tool $toolName $schemaKind properties must be an object"
        }
    }
    if (schema.has("required") && !schema.isNull("required")) {
        require(schema.get("required") is JSONArray) {
            "Tool $toolName $schemaKind required must be an array"
        }
        val required = schema.getJSONArray("required")
        val seenRequired = linkedSetOf<String>()
        for (index in 0 until required.length()) {
            require(required.get(index) is String) {
                "Tool $toolName $schemaKind required values must be strings"
            }
            val requiredName = required.getString(index)
            require(requiredName.isNotBlank()) {
                "Tool $toolName $schemaKind required values must be non-blank"
            }
            require(seenRequired.add(requiredName)) {
                "Tool $toolName $schemaKind required values must be unique"
            }
        }
    }
    require(schema.has("additionalProperties") && !schema.isNull("additionalProperties")) {
        "Tool $toolName $schemaKind must declare additionalProperties=false"
    }
    require(schema.get("additionalProperties") == false) {
        "Tool $toolName $schemaKind must declare additionalProperties=false"
    }
}

private val SUPPORTED_PROPERTY_SCHEMA_KEYS = setOf(
    "type",
    "description",
    "const",
    "minLength",
    "maxLength",
    "pattern",
    "enum",
    "format",
    "contentMediaType",
    "minimum",
    "maximum",
    "exclusiveMinimum",
    "exclusiveMaximum",
)

private const val MAX_SUPPORTED_STRING_LENGTH_BOUND = Int.MAX_VALUE.toLong()

private val SUPPORTED_PROPERTY_SCHEMA_TYPES = setOf(
    "string",
    "integer",
    "number",
    "boolean",
    "array",
    "object",
)

private val SUPPORTED_STRING_FORMATS = setOf("date-time")
private val SUPPORTED_STRING_CONTENT_MEDIA_TYPES = setOf("application/json")

private fun validatePropertySchema(
    toolName: String,
    schemaKind: String,
    propertyName: String,
    propertyJson: JSONObject,
) {
    require(propertyName.isNotBlank()) {
        "Tool $toolName $schemaKind property names must be non-blank"
    }
    val unsupportedKeys = propertyJson.keysSet() - SUPPORTED_PROPERTY_SCHEMA_KEYS
    require(unsupportedKeys.isEmpty()) {
        "Tool $toolName $schemaKind property $propertyName declares unsupported schema keyword(s): " +
            unsupportedKeys.sorted().joinToString()
    }
    require(propertyJson.has("type") && !propertyJson.isNull("type")) {
        "Tool $toolName $schemaKind property $propertyName must declare type"
    }
    require(propertyJson.get("type") is String) {
        "Tool $toolName $schemaKind property $propertyName type must be a string"
    }
    val type = propertyJson.getString("type").lowercase()
    require(type in SUPPORTED_PROPERTY_SCHEMA_TYPES) {
        "Tool $toolName $schemaKind property $propertyName declares unsupported schema type '$type'"
    }
    requireCompatiblePropertyKeywords(toolName, schemaKind, propertyName, propertyJson, type)
    requireOptionalConst(propertyJson, toolName, schemaKind, propertyName, type)
    requireOptionalString(propertyJson, "description", toolName, schemaKind, propertyName)
    val format = requireOptionalString(propertyJson, "format", toolName, schemaKind, propertyName)
    require(format == null || format in SUPPORTED_STRING_FORMATS) {
        "Tool $toolName $schemaKind property $propertyName declares unsupported format '$format'"
    }
    val contentMediaType = requireOptionalString(propertyJson, "contentMediaType", toolName, schemaKind, propertyName)
    require(contentMediaType == null || contentMediaType in SUPPORTED_STRING_CONTENT_MEDIA_TYPES) {
        "Tool $toolName $schemaKind property $propertyName declares unsupported contentMediaType '$contentMediaType'"
    }
    requireOptionalString(propertyJson, "pattern", toolName, schemaKind, propertyName)
    val minLength = optionalIntegerValue(propertyJson, "minLength", toolName, schemaKind, propertyName)
    val maxLength = optionalIntegerValue(propertyJson, "maxLength", toolName, schemaKind, propertyName)
    require(minLength == null || minLength >= 0) {
        "Tool $toolName $schemaKind property $propertyName minLength must be non-negative"
    }
    require(maxLength == null || maxLength >= 0) {
        "Tool $toolName $schemaKind property $propertyName maxLength must be non-negative"
    }
    require(minLength == null || minLength <= MAX_SUPPORTED_STRING_LENGTH_BOUND) {
        "Tool $toolName $schemaKind property $propertyName minLength must not exceed supported length bound"
    }
    require(maxLength == null || maxLength <= MAX_SUPPORTED_STRING_LENGTH_BOUND) {
        "Tool $toolName $schemaKind property $propertyName maxLength must not exceed supported length bound"
    }
    require(minLength == null || maxLength == null || minLength <= maxLength) {
        "Tool $toolName $schemaKind property $propertyName minLength must not exceed maxLength"
    }
    val minimum = optionalNumberValue(propertyJson, "minimum", toolName, schemaKind, propertyName)
    val maximum = optionalNumberValue(propertyJson, "maximum", toolName, schemaKind, propertyName)
    val exclusiveMinimum = optionalNumberValue(propertyJson, "exclusiveMinimum", toolName, schemaKind, propertyName)
    val exclusiveMaximum = optionalNumberValue(propertyJson, "exclusiveMaximum", toolName, schemaKind, propertyName)
    require(minimum == null || maximum == null || minimum <= maximum) {
        "Tool $toolName $schemaKind property $propertyName minimum must not exceed maximum"
    }
    require(exclusiveMinimum == null || exclusiveMaximum == null || exclusiveMinimum < exclusiveMaximum) {
        "Tool $toolName $schemaKind property $propertyName exclusiveMinimum must be less than exclusiveMaximum"
    }
    requireOptionalEnum(propertyJson, toolName, schemaKind, propertyName, type)
}

private fun requireCompatiblePropertyKeywords(
    toolName: String,
    schemaKind: String,
    propertyName: String,
    propertyJson: JSONObject,
    type: String,
) {
    val incompatibleKeys = buildList {
        if (type != "string") {
            listOf("minLength", "maxLength", "pattern", "format", "contentMediaType").forEach { keyword ->
                if (propertyJson.has(keyword)) add(keyword)
            }
        }
        if (type !in setOf("integer", "number")) {
            listOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum").forEach { keyword ->
                if (propertyJson.has(keyword)) add(keyword)
            }
        }
    }
    require(incompatibleKeys.isEmpty()) {
        "Tool $toolName $schemaKind property $propertyName declares incompatible schema keyword(s) " +
            "for type '$type': ${incompatibleKeys.sorted().joinToString()}"
    }
}

private fun requireOptionalConst(
    propertyJson: JSONObject,
    toolName: String,
    schemaKind: String,
    propertyName: String,
    type: String,
) {
    if (!propertyJson.has("const") || propertyJson.isNull("const")) return
    val constValue = propertyJson.get("const")
    require(constValue !is JSONObject && constValue !is JSONArray) {
        "Tool $toolName $schemaKind property $propertyName const must be a scalar value"
    }
    require(
        when (type) {
            "string" -> constValue is String
            "boolean" -> constValue is Boolean
            "integer" -> constValue is Int || constValue is Long
            "number" -> constValue is Number
            else -> true
        },
    ) {
        "Tool $toolName $schemaKind property $propertyName const must match type '$type'"
    }
}

private fun requireOptionalString(
    propertyJson: JSONObject,
    keyword: String,
    toolName: String,
    schemaKind: String,
    propertyName: String,
): String? {
    if (!propertyJson.has(keyword) || propertyJson.isNull(keyword)) return null
    require(propertyJson.get(keyword) is String) {
        "Tool $toolName $schemaKind property $propertyName $keyword must be a string"
    }
    return propertyJson.getString(keyword)
}

private fun optionalIntegerValue(
    propertyJson: JSONObject,
    keyword: String,
    toolName: String,
    schemaKind: String,
    propertyName: String,
): Long? {
    if (!propertyJson.has(keyword) || propertyJson.isNull(keyword)) return null
    val value = propertyJson.get(keyword)
    require(value is Int || value is Long) {
        "Tool $toolName $schemaKind property $propertyName $keyword must be an integer"
    }
    return (value as Number).toLong()
}

private fun optionalNumberValue(
    propertyJson: JSONObject,
    keyword: String,
    toolName: String,
    schemaKind: String,
    propertyName: String,
): Double? {
    if (!propertyJson.has(keyword) || propertyJson.isNull(keyword)) return null
    val value = propertyJson.get(keyword)
    require(value is Number) {
        "Tool $toolName $schemaKind property $propertyName $keyword must be a number"
    }
    val numeric = value.toDouble()
    require(numeric.isFinite()) {
        "Tool $toolName $schemaKind property $propertyName $keyword must be finite"
    }
    return numeric
}

private fun requireOptionalEnum(
    propertyJson: JSONObject,
    toolName: String,
    schemaKind: String,
    propertyName: String,
    type: String,
) {
    if (!propertyJson.has("enum") || propertyJson.isNull("enum")) return
    val value = propertyJson.get("enum")
    require(value is JSONArray) {
        "Tool $toolName $schemaKind property $propertyName enum must be an array"
    }
    val seenValues = linkedSetOf<String>()
    for (index in 0 until value.length()) {
        val enumValue = value.get(index)
        require(enumValue is String) {
            "Tool $toolName $schemaKind property $propertyName enum values must be strings"
        }
        require(seenValues.add(enumValue)) {
            "Tool $toolName $schemaKind property $propertyName enum values must be unique"
        }
        require(type in setOf("string", "array", "object") || enumValue.canParseAs(type)) {
            "Tool $toolName $schemaKind property $propertyName enum value must match type '$type'"
        }
    }
}

private fun String.canParseAs(type: String): Boolean =
    when (type) {
        "boolean" -> toBooleanStrictOrNull() != null
        "integer" -> toLongOrNull() != null
        "number" -> toDoubleOrNull()?.isFinite() == true
        else -> true
    }

internal data class PropertyRule(
    val type: String?,
    val constValue: String?,
    val minLength: Int?,
    val maxLength: Int?,
    val pattern: Regex?,
    val enum: Set<String>?,
    val format: String?,
    val contentMediaType: String?,
    val minimum: Double?,
    val maximum: Double?,
    val exclusiveMinimum: Double?,
    val exclusiveMaximum: Double?,
) {
    fun validate(toolName: String, valueKind: String, valueName: String, value: String): String? {
        if (constValue != null && value != constValue) {
            return "Tool ${toolName} $valueKind $valueName has invalid constant value"
        }
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
                            validateStringContentMediaType(toolName, valueKind, valueName, value)
                                ?: validateStringFormat(toolName, valueKind, valueName, value)
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
                if (numeric == null || !numeric.isFinite()) {
                    "Tool $toolName $valueKind $valueName must be a finite number"
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
            else -> "Tool $toolName $valueKind $valueName declares unsupported schema type '$type'"
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

    private fun validateStringFormat(
        toolName: String,
        valueKind: String,
        valueName: String,
        value: String,
    ): String? =
        when (format) {
            null -> null
            "date-time" -> runCatching { Instant.parse(value) }
                .fold(
                    onSuccess = { null },
                    onFailure = { "Tool $toolName $valueKind $valueName must be an ISO-8601 date-time" },
                )

            else -> "Tool $toolName $valueKind $valueName declares unsupported format '$format'"
        }

    private fun validateStringContentMediaType(
        toolName: String,
        valueKind: String,
        valueName: String,
        value: String,
    ): String? =
        when (contentMediaType) {
            null -> null
            "application/json" -> runCatching {
                val tokener = org.json.JSONTokener(value)
                tokener.nextValue()
                require(tokener.nextClean() == 0.toChar())
            }.fold(
                onSuccess = { null },
                onFailure = { "Tool $toolName $valueKind $valueName must contain valid JSON" },
            )

            // Fail-closed: an unrecognized contentMediaType must NOT be treated as validated.
            // Returning null here would silently pass unvalidated content; reject instead so that
            // adding a new contentMediaType to a schema without a matching validator is caught.
            else -> "Tool $toolName $valueKind $valueName declares unsupported contentMediaType " +
                "'$contentMediaType'"
        }
}

internal fun String.schemaPropertyNames(): Set<String> {
    val schema = JSONObject(this)
    return schema.optJSONObject("properties")
        ?.keysSet()
        .orEmpty()
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

private fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    val value = get(name)
    require(value is Int || value is Long) {
        "Schema keyword $name must be an integer"
    }
    require(value.toLong() in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "Schema keyword $name must fit in Int range"
    }
    return (value as Number).toInt()
}

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

private fun JSONObject.optConstStringOrNull(): String? {
    if (!has("const") || isNull("const")) return null
    return when (val value = get("const")) {
        is String -> value
        is Boolean -> value.toString()
        is Number -> value.toString()
        else -> null
    }
}
