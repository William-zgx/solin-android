package com.bytedance.zgx.solin.ui

import com.bytedance.zgx.solin.mcp.McpCapabilityState
import com.bytedance.zgx.solin.ui.components.MCP_CAPABILITY_DISCLOSURE_BODY
import com.bytedance.zgx.solin.ui.components.MCP_CAPABILITY_TITLE
import com.bytedance.zgx.solin.ui.components.toToggleUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpCapabilityToggleUiTest {
    @Test
    fun toggleStateMirrorsCapabilityStateIncludingRestartHint() {
        val ui = McpCapabilityState(
            enabled = true,
            disclosureAcknowledged = true,
            activeThisSession = false,
            serverCount = 1,
            approvedToolCount = 2,
        ).toToggleUiState()

        assertTrue(ui.enabled)
        assertTrue(ui.disclosureAcknowledged)
        assertTrue(ui.restartRequired)
        assertEquals(2, ui.approvedToolCount)
    }

    @Test
    fun disclosureStatesThatArgumentsLeaveTheDeviceAndEachCallIsConfirmedAndAudited() {
        assertTrue(MCP_CAPABILITY_TITLE.contains("默认关闭"))
        assertTrue(MCP_CAPABILITY_DISCLOSURE_BODY.contains("离开本机"))
        assertTrue(MCP_CAPABILITY_DISCLOSURE_BODY.contains("逐个确认"))
        assertTrue(MCP_CAPABILITY_DISCLOSURE_BODY.contains("远程发送记录"))
        assertTrue(MCP_CAPABILITY_DISCLOSURE_BODY.contains("仅保存在本机"))
    }
}
