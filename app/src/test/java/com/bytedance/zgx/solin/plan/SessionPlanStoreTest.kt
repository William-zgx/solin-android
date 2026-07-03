package com.bytedance.zgx.solin.plan

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlanStoreTest {
    @Test
    fun replaceAllSortsByPositionAndRenumbers() {
        val store = SessionPlanStore()
        val items = listOf(
            PlanItem(planItemId = "a", runId = "r", position = 5, title = "A"),
            PlanItem(planItemId = "b", runId = "r", position = 2, title = "B"),
            PlanItem(planItemId = "c", runId = "r", position = 9, title = "C"),
        )
        val snap = store.replaceAll("r", items)
        assertEquals(3, snap.items.size)
        assertEquals("B", snap.items[0].title)
        assertEquals("A", snap.items[1].title)
        assertEquals("C", snap.items[2].title)
        assertEquals(0, snap.items[0].position)
        assertEquals(1, snap.items[1].position)
        assertEquals(2, snap.items[2].position)
    }

    @Test
    fun getListClearWork() {
        val store = SessionPlanStore()
        assertNull(store.get("r"))
        val items = listOf(PlanItem(planItemId = "a", runId = "r", position = 0, title = "A"))
        store.replaceAll("r1", items)
        store.replaceAll("r2", items)
        assertEquals(2, store.list().size)
        assertNotNull(store.get("r1"))
        store.clear("r1")
        assertNull(store.get("r1"))
        assertNotNull(store.get("r2"))
        store.clearAll()
        assertEquals(0, store.list().size)
    }

    @Test
    fun markDoneSetsStatusToDone() {
        val store = SessionPlanStore()
        val before = System.currentTimeMillis() - 1
        val items = listOf(PlanItem(planItemId = "a", runId = "r", position = 0, title = "A"))
        store.replaceAll("r", items)
        val updated = store.markDone("r", "a")
        assertNotNull(updated)
        val item = updated!!.items.first { it.planItemId == "a" }
        assertEquals(PlanItemStatus.DONE, item.status)
        assertTrue(item.updatedAtMillis >= before)
    }

    @Test
    fun replaceAllEmitsToUpdatesFlow() = runTest {
        val store = SessionPlanStore()
        val items = listOf(PlanItem(planItemId = "a", runId = "r", position = 0, title = "A"))
        val emitted = coroutineScope {
            val job = async { store.updates.first() }
            delay(10)
            store.replaceAll("r", items)
            job.await()
        }
        assertEquals("r", emitted.runId)
        assertEquals(1, emitted.items.size)
    }
}
