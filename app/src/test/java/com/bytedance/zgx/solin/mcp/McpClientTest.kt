package com.bytedance.zgx.solin.mcp

import com.bytedance.zgx.solin.audit.RemoteSendAuditEvent
import com.bytedance.zgx.solin.audit.RemoteSendAuditSink
import com.bytedance.zgx.solin.audit.RemoteSendDecision
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.module.SolinModuleRegistryImpl
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientTest {
    private val serverId = "com.example.mcp/com.example.mcp.Server"

    @Test
    fun disabledByDefaultExposesNoToolsAndRefusesCalls() = runTest {
        val fixture = fixture(enabled = false)

        assertTrue(fixture.client.allApprovedToolSpecs().isEmpty())
        val reg = SolinModuleRegistryImpl()
        McpModule(fixture.client).register(reg)
        assertTrue(reg.toolProviders.flatMap { it.specs() }.isEmpty())

        val handler = assertNotNullHandler(fixture)
        val result = handler.execute(ToolRequest(toolName = toolName(), arguments = mapOf("query" to "x")))!!

        assertEquals(ToolStatus.Rejected, result.status)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data[McpToolSpecs.DATA_PRIVACY])
        assertTrue(fixture.transport.calls.isEmpty())
        assertEquals(RemoteSendDecision.Blocked, fixture.audit.events.single().decision)
    }

    @Test
    fun enabledApprovedCallSendsTypedArgumentsAndAuditsWithoutArgumentValues() = runTest {
        val fixture = fixture(enabled = true)
        val reg = SolinModuleRegistryImpl()
        McpModule(fixture.client).register(reg)
        val frozen = reg.freeze()
        assertEquals(listOf(toolName()), frozen.toolProviders.flatMap { it.specs() }.map { it.name })

        val result = frozen.toolHandlers.getValue(toolName())
            .execute(ToolRequest(toolName = toolName(), arguments = mapOf("query" to "我的私人问题", "limit" to "3")))!!

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("pong", result.data[McpToolSpecs.DATA_TEXT])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data[McpToolSpecs.DATA_PRIVACY])
        val call = fixture.transport.calls.single()
        assertEquals("lookup", call["name"])
        assertEquals(mapOf("query" to "我的私人问题", "limit" to 3L), call["arguments"])
        val event = fixture.audit.events.single()
        assertEquals(RemoteSendDecision.Confirmed, event.decision)
        assertEquals("MCP:Example", event.modelName)
        assertTrue(event.summary.contains("lookup"))
        assertFalse("audit must not contain argument values", event.summary.contains("我的私人问题"))
    }

    @Test
    fun changedServerSchemaIsRefusedUntilReapproved() = runTest {
        val fixture = fixture(enabled = true)
        fixture.transport.liveSchema = schema(extraProperty = "injected")

        val result = assertNotNullHandler(fixture)
            .execute(ToolRequest(toolName = toolName(), arguments = mapOf("query" to "x")))!!

        assertEquals(ToolStatus.Rejected, result.status)
        assertTrue(fixture.transport.calls.isEmpty())
        assertEquals(RemoteSendDecision.Blocked, fixture.audit.events.single().decision)
    }

    @Test
    fun missingAuditSinkOrUnverifiedServerFailsClosed() = runTest {
        val noAudit = fixture(enabled = true, withAudit = false)
        val refused = assertNotNullHandler(noAudit)
            .execute(ToolRequest(toolName = toolName(), arguments = mapOf("query" to "x")))!!
        assertEquals(ToolStatus.Rejected, refused.status)
        assertTrue(noAudit.transport.calls.isEmpty())

        val untrusted = fixture(enabled = true, trusted = false)
        val blocked = assertNotNullHandler(untrusted)
            .execute(ToolRequest(toolName = toolName(), arguments = mapOf("query" to "x")))!!
        assertEquals(ToolStatus.Rejected, blocked.status)
        assertTrue(untrusted.transport.calls.isEmpty())
    }

    @Test
    fun togglingOffTakesEffectImmediatelyForAlreadyRegisteredHandlers() = runTest {
        val fixture = fixture(enabled = true)
        val handler = assertNotNullHandler(fixture)
        fixture.settings.setEnabled(false)

        val result = handler.execute(ToolRequest(toolName = toolName(), arguments = mapOf("query" to "x")))!!

        assertEquals(ToolStatus.Rejected, result.status)
        assertTrue(fixture.transport.calls.isEmpty())
    }

    @Test
    fun protocolErrorsAndTransportFailuresAreFailuresWithPrivacyMetadata() = runTest {
        val fixture = fixture(enabled = true)
        fixture.transport.callError = McpError(code = -32000, message = "secret server text")

        val result = assertNotNullHandler(fixture)
            .execute(ToolRequest(toolName = toolName(), arguments = mapOf("query" to "x")))!!

        assertEquals(ToolStatus.Failed, result.status)
        assertFalse(result.summary.contains("secret server text"))
        assertEquals(MessagePrivacy.LocalOnly.name, result.data[McpToolSpecs.DATA_PRIVACY])
    }

    @Test
    fun revokedToolHasNoHandlerAndIsRefusedAtCallTime() = runTest {
        val fixture = fixture(enabled = true)
        val handler = assertNotNullHandler(fixture)
        fixture.registry.revokeTool(serverId, "lookup")

        val result = handler.execute(ToolRequest(toolName = toolName(), arguments = mapOf("query" to "x")))!!

        assertEquals(ToolStatus.Rejected, result.status)
        assertTrue(fixture.client.handlerFor(toolName()) == null)
    }

    private fun assertNotNullHandler(fixture: Fixture) =
        requireNotNull(fixture.client.handlerFor(toolName())).also { assertNotNull(it) }

    private fun toolName(): String = McpToolSpecs.solinToolName(serverId, "lookup")

    private fun fixture(enabled: Boolean, withAudit: Boolean = true, trusted: Boolean = true): Fixture {
        val transport = FakeTransport(serverId, schema())
        val snapshot = requireNotNull(McpToolSpecs.approvedSnapshotOrNull(McpTool("lookup", "Lookup", schema())))
        val settings = InMemoryMcpCapabilitySettings(
            enabled = enabled,
            disclosureAcknowledged = true,
            servers = listOf(McpServerEntry(serverId, "Example", approvedTools = listOf(snapshot))),
        )
        val consent = InMemoryMcpConsentStore()
        val registry = McpServerRegistry(
            context = null,
            consentStore = consent,
            settings = settings,
            connectionFactory = { entry ->
                if (trusted) McpServerConnection(transport, consent, entry.displayName) else null
            },
        )
        val audit = RecordingAudit()
        return Fixture(
            client = McpClient(registry, settings, if (withAudit) audit else null),
            registry = registry,
            settings = settings,
            transport = transport,
            audit = audit,
        )
    }

    private data class Fixture(
        val client: McpClient,
        val registry: McpServerRegistry,
        val settings: InMemoryMcpCapabilitySettings,
        val transport: FakeTransport,
        val audit: RecordingAudit,
    )

    private class RecordingAudit : RemoteSendAuditSink {
        val events = mutableListOf<RemoteSendAuditEvent>()

        override fun record(event: RemoteSendAuditEvent) {
            events += event
        }
    }

    private class FakeTransport(
        override val serverId: String,
        var liveSchema: Map<String, Any?>,
    ) : McpTransport {
        val calls = mutableListOf<Map<String, Any?>>()
        var callError: McpError? = null
        private var connected = false

        override suspend fun connect(): Boolean {
            connected = true
            return true
        }

        override suspend fun request(request: McpRequest): McpResponse =
            when (request.method) {
                McpProtocol.METHOD_INITIALIZE -> McpResponse(
                    id = request.id,
                    result = mapOf("protocolVersion" to McpProtocol.PROTOCOL_VERSION, "serverInfo" to mapOf("name" to "fake")),
                )

                McpProtocol.METHOD_TOOLS_LIST -> McpResponse(
                    id = request.id,
                    result = mapOf(
                        "tools" to listOf(mapOf("name" to "lookup", "description" to "Lookup", "inputSchema" to liveSchema)),
                    ),
                )

                McpProtocol.METHOD_TOOLS_CALL -> {
                    calls += request.params.orEmpty()
                    callError?.let { McpResponse(id = request.id, error = it) }
                        ?: McpResponse(
                            id = request.id,
                            result = mapOf("content" to listOf(mapOf("type" to "text", "text" to "pong"))),
                        )
                }

                else -> McpResponse(id = request.id, error = McpError(-32601, "unknown"))
            }

        override suspend fun notify(method: String, params: Map<String, Any?>?) = Unit

        override fun isConnected(): Boolean = connected

        override fun close() {
            connected = false
        }
    }

    private companion object {
        fun schema(extraProperty: String? = null): Map<String, Any?> =
            mapOf(
                "type" to "object",
                "required" to listOf("query"),
                "properties" to buildMap {
                    put("query", mapOf("type" to "string"))
                    put("limit", mapOf("type" to "integer"))
                    extraProperty?.let { put(it, mapOf("type" to "string")) }
                },
                "additionalProperties" to false,
            )
    }
}
