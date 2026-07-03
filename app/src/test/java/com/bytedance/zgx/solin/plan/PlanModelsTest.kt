package com.bytedance.zgx.solin.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanModelsTest {
    @Test
    fun pendingCountIncludesPendingAndInProgress() {
        val items = listOf(
            PlanItem(planItemId = "1", runId = "r", position = 0, title = "a", status = PlanItemStatus.PENDING),
            PlanItem(planItemId = "2", runId = "r", position = 1, title = "b", status = PlanItemStatus.IN_PROGRESS),
            PlanItem(planItemId = "3", runId = "r", position = 2, title = "c", status = PlanItemStatus.DONE),
            PlanItem(planItemId = "4", runId = "r", position = 3, title = "d", status = PlanItemStatus.BLOCKED),
            PlanItem(planItemId = "5", runId = "r", position = 4, title = "e", status = PlanItemStatus.SKIPPED),
        )
        val snap = PlanSnapshot(runId = "r", items = items, updatedAtMillis = 1L)
        assertEquals(2, snap.pendingCount())
        assertEquals(1, snap.doneCount())
    }

    @Test
    fun planItemDefaultStatusIsPending() {
        val item = PlanItem(planItemId = "id", runId = "r", position = 0, title = "t")
        assertEquals(PlanItemStatus.PENDING, item.status)
        assertEquals(null, item.note)
    }

    @Test
    fun copyAndEqualsWork() {
        val a = PlanItem(planItemId = "id", runId = "r", position = 0, title = "t")
        val b = a.copy(title = "t2", status = PlanItemStatus.DONE)
        assertEquals("id", b.planItemId)
        assertEquals("t2", b.title)
        assertEquals(PlanItemStatus.DONE, b.status)
        assertNotEquals(a, b)
        val c = a.copy()
        assertEquals(a, c)
        assertTrue(a == c)
    }
}
