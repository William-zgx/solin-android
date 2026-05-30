package com.bytedance.zgx.pocketmind.tool

import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    private val registry = ToolRegistry()

    @Test
    fun rejectsUnknownTool() {
        val rejection = registry.validate(
            ToolRequest(
                id = "request-1",
                toolName = "delete_contact",
                reason = "test",
            ),
        )

        assertNotNull(rejection)
        requireNotNull(rejection)
        assertEquals(ToolStatus.Rejected, rejection.status)
        assertTrue(rejection.summary.contains("Unknown tool"))
        assertEquals("delete_contact", rejection.data["toolName"])
    }

    @Test
    fun exposesSpecsForSupportedActionsWithConfirmationRequired() {
        val specNames = registry.specs().map { it.name }.toSet()

        assertTrue(specNames.containsAll(MobileActionFunctions.supported))

        val wifiSpec = registry.specFor(MobileActionFunctions.OPEN_WIFI_SETTINGS)
        assertNotNull(wifiSpec)
        requireNotNull(wifiSpec)
        assertEquals(ToolCapability.DeviceSettings, wifiSpec.capability)
        assertEquals(RiskLevel.MediumDraftOrNavigation, wifiSpec.riskLevel)
        assertEquals(ConfirmationPolicy.Required, wifiSpec.confirmationPolicy)

        val webSearchSpec = registry.specFor(MobileActionFunctions.WEB_SEARCH)
        assertNotNull(webSearchSpec)
        requireNotNull(webSearchSpec)
        assertEquals(ToolCapability.WebSearch, webSearchSpec.capability)
        assertTrue(webSearchSpec.inputSchemaJson.contains("query"))
    }

    @Test
    fun validatesWebSearchQueryArgument() {
        val missingQuery = registry.validate(
            ToolRequest(
                id = "request-2",
                toolName = MobileActionFunctions.WEB_SEARCH,
                reason = "test",
            ),
        )
        assertNotNull(missingQuery)
        requireNotNull(missingQuery)
        assertEquals(ToolStatus.Rejected, missingQuery.status)
        assertTrue(missingQuery.summary.contains("query"))

        val blankQuery = registry.validate(
            ToolRequest(
                id = "request-3",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to " "),
                reason = "test",
            ),
        )
        assertNotNull(blankQuery)
        requireNotNull(blankQuery)
        assertEquals(ToolStatus.Rejected, blankQuery.status)
        assertTrue(blankQuery.summary.contains("query"))

        val valid = registry.validate(
            ToolRequest(
                id = "request-4",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "Kotlin coroutines Android"),
                reason = "test",
            ),
        )
        assertNull(valid)
    }

    @Test
    fun acceptsOpenWifiSettingsWithoutArguments() {
        val rejection = registry.validate(
            ToolRequest(
                id = "request-5",
                toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                reason = "test",
            ),
        )

        assertNull(rejection)
    }
}
