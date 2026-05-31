package com.bytedance.zgx.pocketmind.tool

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
            val minimalValidArguments = requiredProperties.associateWith { propertyName ->
                validValueFor(properties.getJSONObject(propertyName))
            }

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
    fun stringPatternsDeclaredInSchemasAreEnforcedByRegistry() {
        registry.specs().forEach { spec ->
            val schema = JSONObject(spec.inputSchemaJson)
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val requiredProperties = schema.optStringSet("required")
            val minimalValidArguments = requiredProperties.associateWith { propertyName ->
                validValueFor(properties.getJSONObject(propertyName))
            }

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

    private fun validValueFor(property: JSONObject): String {
        property.optStringSet("enum").firstOrNull()?.let { return it }

        val pattern = property.optStringOrNull("pattern")
        if (pattern != null) {
            return listOf("1", "10", "abc", "value", "https://example.com", "com.example.app")
                .firstOrNull { Regex(pattern).matches(it) }
                ?: error("No test fixture value matches pattern $pattern")
        }

        if (property.optStringOrNull("type") == "integer") {
            return (property.optIntOrNull("minimum") ?: 1).coerceAtLeast(1).toString()
        }

        val minLength = property.optIntOrNull("minLength") ?: 1
        return "x".repeat(minLength.coerceAtLeast(1))
    }

    private fun firstInvalidValueFor(pattern: String): String =
        listOf("", " ", "0", "-1", "1.5", "abc")
            .firstOrNull { !Regex(pattern).matches(it) }
            ?: error("No invalid fixture value for pattern $pattern")

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
}
