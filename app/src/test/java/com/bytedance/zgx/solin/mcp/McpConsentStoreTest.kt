package com.bytedance.zgx.solin.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpConsentStoreTest {
    private lateinit var store: InMemoryMcpConsentStore

    @Before
    fun setUp() {
        store = InMemoryMcpConsentStore()
    }

    @Test
    fun isServerApproved_defaultsToFalse() {
        assertFalse(store.isServerApproved("srv"))
    }

    @Test
    fun approveServer_thenIsServerApprovedTrue() {
        store.approveServer("srv")
        assertTrue(store.isServerApproved("srv"))
    }

    @Test
    fun revokeServer_resetsServerAndTools() {
        store.approveServer("srv")
        store.approveTool("srv", "tool_a")
        assertTrue(store.isToolApproved("srv", "tool_a"))
        store.revokeServer("srv")
        assertFalse(store.isServerApproved("srv"))
        // revokeServer also removes the tool set for the server
        assertFalse(store.isToolApproved("srv", "tool_a"))
    }

    @Test
    fun approveTool_requiresServerApproval_stillReturnsFalseWhenServerNotApproved() {
        // Semantic: approving a tool while the server is not approved records the
        // intent, but isToolApproved must still return false until the server is
        // approved (defense-in-depth so a tool can never fire through an unapproved server).
        store.approveTool("srv", "tool_a")
        assertFalse("tool approval is gated by server approval",
            store.isToolApproved("srv", "tool_a"))
        assertFalse(store.isServerApproved("srv"))

        // Once the server is approved the previously-approved tool becomes visible
        store.approveServer("srv")
        assertTrue(store.isToolApproved("srv", "tool_a"))
    }

    @Test
    fun reset_clearsEverything() {
        store.approveServer("srv")
        store.approveTool("srv", "tool_a")
        store.approveServer("other")
        store.reset()
        assertFalse(store.isServerApproved("srv"))
        assertFalse(store.isServerApproved("other"))
        assertFalse(store.isToolApproved("srv", "tool_a"))
    }
}
