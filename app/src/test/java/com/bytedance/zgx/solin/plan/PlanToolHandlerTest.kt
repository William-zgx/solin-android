package com.bytedance.zgx.solin.plan

import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanToolHandlerTest {
    @Test
    fun planWriteStoresItems() {
        val store = SessionPlanStore()
        val handler = PlanToolHandler(store)
        val request = ToolRequest(
            id = "req-1",
            toolName = PlanToolHandler.PLAN_WRITE_TOOL,
            arguments = mapOf(
                "runId" to "test-run",
                "itemsJson" to """[{"title":"Step 1"},{"title":"Step 2"}]""",
            ),
            reason = "",
        )
        val result = runBlocking { handler.handler().execute(request) }
        assertNotNull(result)
        assertEquals(ToolStatus.Succeeded, result!!.status)
        val snap = store.get("test-run")
        assertNotNull(snap)
        assertEquals(2, snap!!.items.size)
        assertEquals("Step 1", snap.items[0].title)
        assertEquals("Step 2", snap.items[1].title)
    }

    @Test
    fun planReadReturnsSummaryContainingTitles() {
        val store = SessionPlanStore()
        val handler = PlanToolHandler(store)
        val writeReq = ToolRequest(
            id = "req-w",
            toolName = PlanToolHandler.PLAN_WRITE_TOOL,
            arguments = mapOf(
                "runId" to "test-run",
                "itemsJson" to """[{"title":"Step 1"},{"title":"Step 2"}]""",
            ),
            reason = "",
        )
        runBlocking { handler.handler().execute(writeReq) }

        val readReq = ToolRequest(
            id = "req-r",
            toolName = PlanToolHandler.PLAN_READ_TOOL,
            arguments = mapOf("runId" to "test-run"),
            reason = "",
        )
        val result = runBlocking { handler.handler().execute(readReq) }
        assertNotNull(result)
        assertEquals(ToolStatus.Succeeded, result!!.status)
        val rendered = result.data["rendered"]!!
        assertTrue(rendered.contains("Step 1"))
        assertTrue(rendered.contains("Step 2"))
    }

    @Test
    fun missingRunIdReturnsFailed() {
        val store = SessionPlanStore()
        val handler = PlanToolHandler(store)
        val request = ToolRequest(
            id = "req-2",
            toolName = PlanToolHandler.PLAN_WRITE_TOOL,
            arguments = mapOf(
                "itemsJson" to """[{"title":"x"}]""",
            ),
            reason = "",
        )
        val result = runBlocking { handler.handler().execute(request) }
        assertNotNull(result)
        assertEquals(ToolStatus.Failed, result!!.status)
    }
}
