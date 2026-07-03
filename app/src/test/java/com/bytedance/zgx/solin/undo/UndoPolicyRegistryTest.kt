package com.bytedance.zgx.solin.undo

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class UndoPolicyRegistryTest {
    @Test
    fun defaultPoliciesAreRegistered() {
        val registry = ToolRegistry()
        val dummyReq = ToolRequest(id = "x", toolName = "x")
        val dummyRes = ToolResult(requestId = "x", status = ToolStatus.Succeeded, summary = "ok")

        val scheduleReminderPolicy = registry.undoPolicyFor(MobileActionFunctions.SCHEDULE_REMINDER)
        assertTrue(scheduleReminderPolicy != null)
        val scheduleReminderPlan = scheduleReminderPolicy!!.planUndoAfter(dummyReq, dummyRes)
        assertTrue(scheduleReminderPlan is UndoPlan.ExternalHandoff)

        val queryContactsPolicy = registry.undoPolicyFor(MobileActionFunctions.QUERY_CONTACTS)
        assertTrue(queryContactsPolicy != null)
        assertTrue(queryContactsPolicy!!.planUndoAfter(dummyReq, dummyRes) is UndoPlan.NotApplicable)

        val uiTapPolicy = registry.undoPolicyFor(MobileActionFunctions.UI_TAP)
        assertTrue(uiTapPolicy != null)
        assertTrue(uiTapPolicy!!.planUndoAfter(dummyReq, dummyRes) is UndoPlan.NotUndoable)

        val planReadPolicy = registry.undoPolicyFor("plan_read")
        assertTrue(planReadPolicy != null)
        assertTrue(planReadPolicy!!.planUndoAfter(dummyReq, dummyRes) is UndoPlan.NotApplicable)

        assertNull(registry.undoPolicyFor("nonexistent_tool_xyz"))
    }
}
