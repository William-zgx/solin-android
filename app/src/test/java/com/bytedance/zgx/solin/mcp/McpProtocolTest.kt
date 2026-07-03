package com.bytedance.zgx.solin.mcp

import com.bytedance.zgx.solin.module.SolinModuleRegistryImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {
    @Test
    fun constants_areCorrect() {
        assertEquals("2.0", McpProtocol.JSONRPC_VERSION)
        assertEquals("2024-11-05", McpProtocol.PROTOCOL_VERSION)
    }

    @Test
    fun mcpRequest_defaultsJsonrpcTo20() {
        val req = McpRequest(id = 1L, method = "ping")
        assertEquals("2.0", req.jsonrpc)
        assertEquals(1L, req.id)
        assertEquals("ping", req.method)
    }

    @Test
    fun disabledModule_registersNothing() {
        val reg = SolinModuleRegistryImpl()
        McpModule.disabled().register(reg)
        assertTrue("disabled module should not add tool providers", reg.toolProviders.isEmpty())
        assertTrue("disabled module should not add tool handlers", reg.toolHandlers.isEmpty())
        assertTrue("disabled module should not add skill sources", reg.skillSources.isEmpty())
    }
}
