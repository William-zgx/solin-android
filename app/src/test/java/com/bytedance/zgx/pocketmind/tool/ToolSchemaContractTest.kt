package com.bytedance.zgx.pocketmind.tool

import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaContractTest {
    private val registry = ToolRegistry()

    @Test
    fun allToolSchemasDriveRegistryValidation() {
        registry.specs().forEach { spec ->
            val schema = JSONObject(spec.inputSchemaJson)
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val propertyNames = properties.keysSet()
            val requiredProperties = schema.optStringSet("required")
            val minimalValidArguments = minimalValidInputArgumentsFor(spec, schema, properties)

            assertEquals("${spec.name} schema must be an object", "object", schema.getString("type"))
            assertFalse("${spec.name} schema must be closed", schema.optBoolean("additionalProperties", true))
            assertTrue(
                "${spec.name} required properties must be declared in properties",
                propertyNames.containsAll(requiredProperties),
            )

            assertNull(
                "${spec.name} minimal arguments derived from schema should validate",
                registry.validate(
                    ToolRequest(
                        id = "valid-${spec.name}",
                        toolName = spec.name,
                        arguments = minimalValidArguments,
                        reason = "schema contract",
                    ),
                ),
            )

            val extraArgumentRejection = registry.validate(
                ToolRequest(
                    id = "extra-${spec.name}",
                    toolName = spec.name,
                    arguments = minimalValidArguments + ("__unexpected" to "value"),
                    reason = "schema contract",
                ),
            )
            assertNotNull("${spec.name} should reject arguments not declared in schema", extraArgumentRejection)

            requiredProperties.forEach { propertyName ->
                val missingRejection = registry.validate(
                    ToolRequest(
                        id = "missing-${spec.name}-$propertyName",
                        toolName = spec.name,
                        arguments = minimalValidArguments - propertyName,
                        reason = "schema contract",
                    ),
                )
                assertNotNull("${spec.name} should reject missing required $propertyName", missingRejection)

                val blankRejection = registry.validate(
                    ToolRequest(
                        id = "blank-${spec.name}-$propertyName",
                        toolName = spec.name,
                        arguments = minimalValidArguments + (propertyName to " "),
                        reason = "schema contract",
                    ),
                )
                assertNotNull("${spec.name} should reject blank required $propertyName", blankRejection)
            }
        }
    }

    @Test
    fun allToolOutputSchemasDriveResultValidation() {
        registry.specs().forEach { spec ->
            val inputSchema = JSONObject(spec.inputSchemaJson)
            val inputProperties = inputSchema.optJSONObject("properties") ?: JSONObject()
            val minimalValidArguments = minimalValidInputArgumentsFor(spec, inputSchema, inputProperties)
            val outputSchema = JSONObject(spec.outputSchemaJson)
            val outputProperties = outputSchema.optJSONObject("properties") ?: JSONObject()
            val outputPropertyNames = outputProperties.keysSet()
            val requiredOutputs = outputSchema.optStringSet("required")
            val minimalValidData = minimalValidOutputDataFor(spec, requiredOutputs, outputProperties)
            val request = ToolRequest(
                id = "output-${spec.name}",
                toolName = spec.name,
                arguments = minimalValidArguments,
                reason = "schema contract",
            )

            assertEquals("${spec.name} output schema must be an object", "object", outputSchema.getString("type"))
            assertFalse("${spec.name} output schema must be closed", outputSchema.optBoolean("additionalProperties", true))
            assertTrue(
                "${spec.name} required output properties must be declared in properties",
                outputPropertyNames.containsAll(requiredOutputs),
            )

            assertNull(
                "${spec.name} minimal output data derived from schema should validate",
                registry.validateResult(
                    request = request,
                    result = request.succeeded(
                        summary = "schema-valid ${spec.name}",
                        data = minimalValidData,
                    ),
                ),
            )

            val extraOutputRejection = registry.validateResult(
                request = request,
                result = request.succeeded(
                    summary = "schema-extra ${spec.name}",
                    data = minimalValidData + ("__unexpected" to "value"),
                ),
            )
            assertInvalidResult("${spec.name} should reject result data not declared in schema", extraOutputRejection)

            requiredOutputs.forEach { propertyName ->
                val missingOutputRejection = registry.validateResult(
                    request = request,
                    result = request.succeeded(
                        summary = "schema-missing ${spec.name}",
                        data = minimalValidData - propertyName,
                    ),
                )
                assertInvalidResult("${spec.name} should reject missing output $propertyName", missingOutputRejection)

                invalidValueFor(outputProperties.getJSONObject(propertyName))?.let { invalidValue ->
                    val invalidOutputRejection = registry.validateResult(
                        request = request,
                        result = request.succeeded(
                            summary = "schema-invalid ${spec.name}",
                            data = minimalValidData + (propertyName to invalidValue),
                        ),
                    )
                    assertInvalidResult("${spec.name} should reject invalid output $propertyName", invalidOutputRejection)
                }
            }
        }
    }

    @Test
    fun privateOutputToolsRejectNonLocalModelBoundary() {
        registry.specs()
            .filter { spec -> spec.privateOutputKeys.isNotEmpty() }
            .forEach { spec ->
                val inputSchema = JSONObject(spec.inputSchemaJson)
                val inputProperties = inputSchema.optJSONObject("properties") ?: JSONObject()
            val minimalValidArguments = minimalValidInputArgumentsFor(spec, inputSchema, inputProperties)
                val outputSchema = JSONObject(spec.outputSchemaJson)
                val outputProperties = outputSchema.optJSONObject("properties") ?: JSONObject()
                val minimalValidData = minimalValidOutputDataFor(
                    spec = spec,
                    requiredOutputs = outputSchema.optStringSet("required"),
                    outputProperties = outputProperties,
                )
                val request = ToolRequest(
                    id = "private-local-model-${spec.name}",
                    toolName = spec.name,
                    arguments = minimalValidArguments,
                    reason = "schema contract",
                )

                assertNull(
                    "${spec.name} should accept the schema-derived local-only private result",
                    registry.validateResult(
                        request = request,
                        result = request.succeeded(
                            summary = "schema-valid ${spec.name}",
                            data = minimalValidData,
                        ),
                    ),
                )
                assertInvalidResult(
                    "${spec.name} should reject private output without requiresLocalModel=true",
                    registry.validateResult(
                        request = request,
                        result = request.succeeded(
                            summary = "schema-invalid ${spec.name}",
                            data = minimalValidData + ("requiresLocalModel" to "false"),
                        ),
                    ),
                )
            }
    }

    @Test
    fun jsonPayloadOutputFieldsAreStringEncoded() {
        val jsonPayloadFields = registry.specs().flatMap { spec ->
            val outputProperties = JSONObject(spec.outputSchemaJson).optJSONObject("properties") ?: JSONObject()
            outputProperties.keysSet()
                .filter { propertyName -> propertyName.endsWith("Json") }
                .map { propertyName -> spec.name to (propertyName to outputProperties.getJSONObject(propertyName)) }
        }

        assertTrue("expected at least one JSON payload output field", jsonPayloadFields.isNotEmpty())
        jsonPayloadFields.forEach { (toolName, field) ->
            val (propertyName, property) = field
            assertEquals("$toolName.$propertyName must be encoded in ToolResult.data as a string", "string", property.getString("type"))
            assertEquals("$toolName.$propertyName must declare JSON content", "application/json", property.getString("contentMediaType"))
        }
    }

    @Test
    fun numericOutputBoundsCannotBeWiderThanMatchingInputBounds() {
        registry.specs().forEach { spec ->
            val inputProperties = JSONObject(spec.inputSchemaJson).optJSONObject("properties") ?: JSONObject()
            val outputProperties = JSONObject(spec.outputSchemaJson).optJSONObject("properties") ?: JSONObject()
            val sharedProperties = inputProperties.keysSet().intersect(outputProperties.keysSet())

            sharedProperties.forEach { propertyName ->
                val inputProperty = inputProperties.getJSONObject(propertyName)
                val outputProperty = outputProperties.getJSONObject(propertyName)
                val inputType = inputProperty.optStringOrNull("type")
                val outputType = outputProperty.optStringOrNull("type")
                if (inputType !in NUMERIC_SCHEMA_TYPES || outputType !in NUMERIC_SCHEMA_TYPES) return@forEach

                inputProperty.optDoubleOrNull("minimum")?.let { inputMinimum ->
                    val outputMinimum = outputProperty.optDoubleOrNull("minimum")
                    assertNotNull(
                        "${spec.name} output $propertyName must keep the input minimum bound",
                        outputMinimum,
                    )
                    requireNotNull(outputMinimum)
                    assertTrue(
                        "${spec.name} output $propertyName minimum must not be wider than input",
                        outputMinimum >= inputMinimum,
                    )
                }
                inputProperty.optDoubleOrNull("maximum")?.let { inputMaximum ->
                    val outputMaximum = outputProperty.optDoubleOrNull("maximum")
                    assertNotNull(
                        "${spec.name} output $propertyName must keep the input maximum bound",
                        outputMaximum,
                    )
                    requireNotNull(outputMaximum)
                    assertTrue(
                        "${spec.name} output $propertyName maximum must not be wider than input",
                        outputMaximum <= inputMaximum,
                    )
                }
            }
        }
    }

    @Test
    fun recentScreenshotOcrOutputRejectsMultiScreenshotCount() {
        val spec = requireNotNull(registry.specFor(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR))
        val inputMaxCount = JSONObject(spec.inputSchemaJson)
            .getJSONObject("properties")
            .getJSONObject("maxCount")
            .getInt("maximum")
        val outputMaxCount = JSONObject(spec.outputSchemaJson)
            .getJSONObject("properties")
            .getJSONObject("maxCount")
            .getInt("maximum")
        val request = ToolRequest(
            id = "recent-screenshot-ocr-output-contract",
            toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            arguments = mapOf("maxCount" to "1"),
            reason = "schema contract",
        )
        val validData = mapOf(
            "toolName" to MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            "privacy" to MessagePrivacy.LocalOnly.name,
            "requiresLocalModel" to "true",
            "source" to "screenshots",
            "maxCount" to "1",
            "scannedCount" to "1",
            "mediaAccessScope" to "full_visual_media",
            "ocrTextIncluded" to "true",
            "rawPayloadIncluded" to "false",
            "metadataPolicy" to "ocr_text_local_only_no_uri_path_or_pixels_persisted",
        )

        assertEquals(1, inputMaxCount)
        assertEquals(1, outputMaxCount)
        assertNull(
            registry.validateResult(
                request = request,
                result = request.succeeded(summary = "read recent screenshot OCR", data = validData),
            ),
        )
        assertInvalidResult(
            "read_recent_screenshot_ocr should reject output maxCount wider than one screenshot",
            registry.validateResult(
                request = request,
                result = request.succeeded(
                    summary = "invalid multi-screenshot OCR output",
                    data = validData + ("maxCount" to "2"),
                ),
            ),
        )
    }

    @Test
    fun stringPatternsDeclaredInSchemasAreEnforcedByRegistry() {
        registry.specs().forEach { spec ->
            val schema = JSONObject(spec.inputSchemaJson)
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val requiredProperties = schema.optStringSet("required")
            val minimalValidArguments = minimalValidInputArgumentsFor(spec, schema, properties)

            properties.keysSet().forEach { propertyName ->
                val property = properties.optJSONObject(propertyName) ?: return@forEach
                val pattern = property.optStringOrNull("pattern") ?: return@forEach
                val invalidValue = firstInvalidValueFor(pattern)
                val rejection = registry.validate(
                    ToolRequest(
                        id = "pattern-${spec.name}-$propertyName",
                        toolName = spec.name,
                        arguments = minimalValidArguments + (propertyName to invalidValue),
                        reason = "schema contract",
                    ),
                )

                assertNotNull("${spec.name} should enforce pattern for $propertyName", rejection)
            }
        }
    }

    @Test
    fun stringFormatsDeclaredInSchemasAreEnforcedByRegistry() {
        registry.specs().forEach { spec ->
            val schema = JSONObject(spec.inputSchemaJson)
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val requiredProperties = schema.optStringSet("required")
            val minimalValidArguments = minimalValidInputArgumentsFor(spec, schema, properties)

            properties.keysSet().forEach { propertyName ->
                val property = properties.optJSONObject(propertyName) ?: return@forEach
                val invalidValue = invalidFormatValueFor(property.optStringOrNull("format")) ?: return@forEach
                val rejection = registry.validate(
                    ToolRequest(
                        id = "format-${spec.name}-$propertyName",
                        toolName = spec.name,
                        arguments = minimalValidArguments + (propertyName to invalidValue),
                        reason = "schema contract",
                    ),
                )

                assertNotNull("${spec.name} should enforce format for $propertyName", rejection)
            }
        }
    }

    private fun validValueFor(property: JSONObject): String {
        property.optConstStringOrNull()?.let { return it }
        property.optStringSet("enum").firstOrNull()?.let { return it }

        validFormatValueFor(property.optStringOrNull("format"))?.let { return it }

        val pattern = property.optStringOrNull("pattern")
        if (pattern != null) {
            return listOf("1", "10", "abc", "value", "task-1", "https://example.com", "com.example.app")
                .firstOrNull { Regex(pattern).matches(it) }
                ?: error("No test fixture value matches pattern $pattern")
        }

        return when (property.optStringOrNull("type")) {
            "boolean" -> true.toString()
            "integer" -> (property.optIntOrNull("minimum") ?: 1).coerceAtLeast(1).toString()
            "number" -> (property.optDoubleOrNull("minimum") ?: 1.0).coerceAtLeast(1.0).toString()
            "array" -> "[]"
            "object" -> "{}"
            else -> {
                val minLength = property.optIntOrNull("minLength") ?: 1
                "x".repeat(minLength.coerceAtLeast(1))
            }
        }
    }

    private fun minimalValidInputArgumentsFor(
        spec: ToolSpec,
        schema: JSONObject,
        properties: JSONObject,
    ): Map<String, String> {
        val requiredArguments = schema.optStringSet("required").associateWith { propertyName ->
            validValueFor(properties.getJSONObject(propertyName))
        }
        return when (spec.name) {
            MobileActionFunctions.SCHEDULE_REMINDER ->
                requiredArguments + ("delayMinutes" to validValueFor(properties.getJSONObject("delayMinutes")))

            else -> requiredArguments
        }
    }

    private fun minimalValidOutputDataFor(
        spec: ToolSpec,
        requiredOutputs: Set<String>,
        outputProperties: JSONObject,
    ): Map<String, String> {
        val data = requiredOutputs.associateWith { propertyName ->
            if (propertyName == "toolName") spec.name else validValueFor(outputProperties.getJSONObject(propertyName))
        }
        if (ToolPermission.StartsExternalActivity !in spec.permissions) return data
        return data + mapOf(
            "completionVerified" to "false",
            "externalOutcome" to "Unknown",
            "externalOutcomeSource" to "Unknown",
        )
    }

    private fun invalidValueFor(property: JSONObject): String? {
        property.optConstStringOrNull()?.let { constValue ->
            return when (constValue) {
                "true" -> "false"
                "false" -> "true"
                else -> "__invalid_const__"
            }
        }
        val enum = property.optStringSet("enum")
        if (enum.isNotEmpty()) return "__invalid_enum__"

        invalidFormatValueFor(property.optStringOrNull("format"))?.let { return it }

        property.optStringOrNull("pattern")?.let(::firstInvalidValueFor)?.let { return it }

        return when (property.optStringOrNull("type")) {
            "boolean" -> "maybe"
            "integer" -> "not-an-integer"
            "number" -> "not-a-number"
            "array" -> "{}"
            "object" -> "[]"
            "string" -> ""
            else -> null
        }
    }

    private fun assertInvalidResult(message: String, result: ToolResult?) {
        assertNotNull(message, result)
        requireNotNull(result)
        assertEquals(message, ToolStatus.Failed, result.status)
        assertEquals(message, ToolErrorCode.InvalidResult, result.error?.code)
        assertFalse(message, result.retryable)
    }

    private fun firstInvalidValueFor(pattern: String): String =
        listOf("", " ", "0", "-1", "1.5", "abc")
            .firstOrNull { !Regex(pattern).matches(it) }
            ?: error("No invalid fixture value for pattern $pattern")

    private fun validFormatValueFor(format: String?): String? =
        when (format) {
            "date-time" -> "2026-06-02T00:00:00Z"
            else -> null
        }

    private fun invalidFormatValueFor(format: String?): String? =
        when (format) {
            "date-time" -> "tomorrow morning"
            else -> null
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

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name)) optDouble(name) else null

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name)

    private fun JSONObject.optConstStringOrNull(): String? {
        if (!has("const") || isNull("const")) return null
        return when (val value = get("const")) {
            is String -> value
            is Boolean -> value.toString()
            is Number -> value.toString()
            else -> null
        }
    }

    private companion object {
        val NUMERIC_SCHEMA_TYPES = setOf("integer", "number")
    }
}
