package com.bytedance.zgx.solin.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpCapabilitySettingsTest {
    @Test
    fun mcpIsOffByDefaultAndCannotBeEnabledWithoutDisclosure() {
        val settings = InMemoryMcpCapabilitySettings()
        val controller = McpCapabilityController(settings, activeThisSession = false)

        assertFalse(controller.state().enabled)
        assertFalse(controller.setEnabled(true).enabled)
        assertFalse(settings.isDisclosureAcknowledged())
    }

    @Test
    fun acceptingDisclosureEnablesAndRestartIsRequiredForTheFrozenRegistry() {
        val settings = InMemoryMcpCapabilitySettings()
        val controller = McpCapabilityController(settings, activeThisSession = false)

        val state = controller.setEnabled(true, disclosureAccepted = true)

        assertTrue(state.enabled)
        assertTrue(state.disclosureAcknowledged)
        assertTrue(state.restartRequired)
        // Disclosure is only shown the first time.
        controller.setEnabled(false)
        assertTrue(controller.setEnabled(true).enabled)
    }

    @Test
    fun disablingAlwaysSucceeds() {
        val settings = InMemoryMcpCapabilitySettings(enabled = true, disclosureAcknowledged = true)
        val controller = McpCapabilityController(settings, activeThisSession = true)

        assertFalse(controller.setEnabled(false).enabled)
        assertFalse(settings.isEnabled())
    }

    @Test
    fun enabledFlagWithoutDisclosureIsIgnored() {
        assertFalse(InMemoryMcpCapabilitySettings(enabled = true, disclosureAcknowledged = false).isEnabled())
    }

    @Test
    fun catalogCodecRoundTripsAndFailsClosed() {
        val entry = McpServerEntry(
            serverId = "com.example/.S",
            displayName = "Example",
            resultsPublic = true,
            approvedTools = listOf(McpApprovedTool("lookup", "desc", "{\"type\":\"object\"}")),
        )

        assertEquals(listOf(entry), McpServerCatalogCodec.decode(McpServerCatalogCodec.encode(listOf(entry))))
        assertTrue(McpServerCatalogCodec.decode("not json").isEmpty())
        assertTrue(McpServerCatalogCodec.decode(null).isEmpty())

        val tampered = McpServerCatalogCodec.encode(listOf(entry))
            .replace("\"resultsPublic\":true", "\"resultsPublic\":\"true\"")
            .replace("\"name\":\"lookup\"", "\"name\":\"bad name\"")
        val decoded = McpServerCatalogCodec.decode(tampered).single()
        assertFalse("only a JSON true marks results public", decoded.resultsPublic)
        assertTrue(decoded.approvedTools.isEmpty())
    }

    @Test
    fun jsonRpcNotificationsOmitIdAndResponsesAreValidated() {
        val notification = JSONObject(McpJsonRpcCodec.serialize(McpRequest(id = 0, method = "notifications/initialized")))
        val request = JSONObject(
            McpJsonRpcCodec.serialize(
                McpRequest(id = 7, method = "tools/call", params = mapOf("arguments" to mapOf("n" to listOf(1, 2)))),
            ),
        )

        assertFalse(notification.has("id"))
        assertEquals(7, request.getInt("id"))
        assertEquals(2, request.getJSONObject("params").getJSONObject("arguments").getJSONArray("n").length())

        val ok = McpJsonRpcCodec.parseResponse("""{"jsonrpc":"2.0","id":7,"result":{"a":[1,{"b":null}]}}""")
        assertEquals(7L, ok.id)
        assertEquals(listOf(1, mapOf("b" to null)), ok.result!!["a"])
        listOf(
            """{"jsonrpc":"2.0","result":{}}""",
            """{"jsonrpc":"2.0","id":"7","result":{}}""",
            """{"jsonrpc":"2.0","id":7}""",
            """{"jsonrpc":"2.0","id":7,"result":[1]}""",
            """{"jsonrpc":"2.0","id":7,"error":{"message":"no code"}}""",
        ).forEach { raw ->
            assertTrue(raw, runCatching { McpJsonRpcCodec.parseResponse(raw) }.isFailure)
        }
    }
}
