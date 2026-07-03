package com.bytedance.zgx.solin.plan

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class SessionPlanStore {
    private val plans = ConcurrentHashMap<String, PlanSnapshot>()
    private val _updates = MutableSharedFlow<PlanSnapshot>(extraBufferCapacity = 16)
    val updates: SharedFlow<PlanSnapshot> = _updates.asSharedFlow()

    fun get(runId: String): PlanSnapshot? = plans[runId]

    fun list(): List<PlanSnapshot> = plans.values.toList()

    fun replaceAll(runId: String, items: List<PlanItem>): PlanSnapshot {
        val sorted = items.sortedBy { it.position }.mapIndexed { idx, item ->
            if (item.position != idx) {
                item.copy(position = idx, updatedAtMillis = System.currentTimeMillis())
            } else {
                item.copy(updatedAtMillis = System.currentTimeMillis())
            }
        }
        val snap = PlanSnapshot(
            runId = runId,
            items = sorted,
            updatedAtMillis = System.currentTimeMillis(),
        )
        plans[runId] = snap
        _updates.tryEmit(snap)
        return snap
    }

    fun markDone(runId: String, planItemId: String): PlanSnapshot? =
        updateItem(runId, planItemId) { it.copy(status = PlanItemStatus.DONE) }

    fun updateItem(
        runId: String,
        planItemId: String,
        transform: (PlanItem) -> PlanItem,
    ): PlanSnapshot? {
        val current = plans[runId] ?: return null
        val updatedItems = current.items.map { if (it.planItemId == planItemId) transform(it) else it }
        return replaceAll(runId, updatedItems)
    }

    fun clear(runId: String) {
        plans.remove(runId)
    }

    fun clearAll() {
        plans.clear()
    }
}
