package com.bytedance.zgx.solin.undo

import com.bytedance.zgx.solin.tool.ToolRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UndoModelsTest {
    @Test
    fun subtypesCanBeInstantiated() {
        val req = ToolRequest(id = "r1", toolName = "some_tool", arguments = mapOf("k" to "v"))
        val ct = UndoPlan.CompensatingTool(request = req, summary = "undo summary")
        assertEquals("undo summary", ct.summary)
        assertEquals("r1", ct.request.id)

        val eh = UndoPlan.ExternalHandoff(reason = "hand off")
        assertEquals("hand off", eh.reason)

        assertSame(UndoPlan.NotApplicable, UndoPlan.NotApplicable)

        val nu = UndoPlan.NotUndoable(reason = "irreversible")
        assertEquals("irreversible", nu.reason)
    }
}
