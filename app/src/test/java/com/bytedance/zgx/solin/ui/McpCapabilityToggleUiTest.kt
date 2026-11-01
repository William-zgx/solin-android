package com.bytedance.zgx.solin.ui

import com.bytedance.zgx.solin.mcp.McpCapabilityState
import com.bytedance.zgx.solin.ui.components.MCP_CAPABILITY_DISCLOSURE_BODY
import com.bytedance.zgx.solin.ui.components.MCP_CAPABILITY_TITLE
import com.bytedance.zgx.solin.ui.components.REMOTE_GUI_AUTOMATION_DISCLOSURE_BODY
import com.bytedance.zgx.solin.ui.components.REMOTE_GUI_AUTOMATION_DISCLOSURE_TITLE
import com.bytedance.zgx.solin.ui.components.REMOTE_GUI_AUTOMATION_DETAIL
import com.bytedance.zgx.solin.ui.components.remoteGuiAutomationRequiresDisclosure
import com.bytedance.zgx.solin.ui.components.toToggleUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun remoteGuiAutomationDisclosurePrecedesTurningItOn() {
        // Turning the switch on widens egress, so it needs the disclosure first.
        assertTrue(remoteGuiAutomationRequiresDisclosure(next = true, current = false))
        // Turning it off narrows egress and applies immediately.
        assertFalse(remoteGuiAutomationRequiresDisclosure(next = false, current = true))
        // No transition, no dialog.
        assertFalse(remoteGuiAutomationRequiresDisclosure(next = true, current = true))
        assertFalse(remoteGuiAutomationRequiresDisclosure(next = false, current = false))
    }

    @Test
    fun remoteGuiAutomationDisclosureNamesWhatLeavesThePhoneAndWhatStaysWithTheUser() {
        assertEquals("允许把屏幕发给远程模型？", REMOTE_GUI_AUTOMATION_DISCLOSURE_TITLE)
        assertTrue(REMOTE_GUI_AUTOMATION_DISCLOSURE_BODY.contains("截图"))
        assertTrue(REMOTE_GUI_AUTOMATION_DISCLOSURE_BODY.contains("屏幕上的文字"))
        assertTrue(REMOTE_GUI_AUTOMATION_DISCLOSURE_BODY.contains("不含密码框"))
        assertTrue(REMOTE_GUI_AUTOMATION_DISCLOSURE_BODY.contains("远程视觉模型"))
        assertTrue(REMOTE_GUI_AUTOMATION_DISCLOSURE_BODY.contains("由你亲手点击"))
        assertTrue(REMOTE_GUI_AUTOMATION_DISCLOSURE_BODY.contains("空白"))
        assertTrue(REMOTE_GUI_AUTOMATION_DISCLOSURE_BODY.contains("不外发"))
        // The expanded detail keeps the same facts available after the switch is on.
        assertTrue(REMOTE_GUI_AUTOMATION_DETAIL.contains("支付、发送、删除、发布"))
    }
}
