package com.bytedance.zgx.solin.mcp

import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerRegistryTest {
    @Test
    fun emptyByDefault_listServersReturnsEmpty() {
        val reg = McpServerRegistry()
        assertTrue(reg.listServers().isEmpty())
    }

    @Test
    fun clear_isIdempotentOnEmpty() {
        val reg = McpServerRegistry()
        reg.clear()
        reg.clear()
        assertTrue(reg.listServers().isEmpty())
    }
}
