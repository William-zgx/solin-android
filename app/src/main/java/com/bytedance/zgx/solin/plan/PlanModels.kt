package com.bytedance.zgx.solin.plan

data class PlanItem(
    val planItemId: String,
    val runId: String,
    val position: Int,
    val title: String,
    val status: PlanItemStatus = PlanItemStatus.PENDING,
    val note: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

enum class PlanItemStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    BLOCKED,
    SKIPPED,
}

data class PlanSnapshot(
    val runId: String,
    val items: List<PlanItem>,
    val updatedAtMillis: Long,
) {
    fun pendingCount(): Int = items.count { it.status == PlanItemStatus.PENDING || it.status == PlanItemStatus.IN_PROGRESS }
    fun doneCount(): Int = items.count { it.status == PlanItemStatus.DONE }
}
