package com.bytedance.zgx.pocketmind.memory

object MemoryQualityContract {
    const val EXPLICIT_RECALL_TARGET_PERCENT = 90
    const val FALSE_POSITIVE_MAX_PERCENT = 5

    val longTermRecordTypes: Set<MemoryRecordType> =
        setOf(
            MemoryRecordType.Preference,
            MemoryRecordType.UserFact,
            MemoryRecordType.TaskState,
        )

    fun validateRunBoundary(
        remoteMode: Boolean,
        memoryContextIncluded: Boolean,
        remoteHistoryContainsLocalOnly: Boolean,
    ): Boolean =
        if (remoteMode) {
            !memoryContextIncluded && !remoteHistoryContainsLocalOnly
        } else {
            true
        }

    fun validateForgetResult(
        removedRecordId: String,
        hitsAfterForget: List<MemoryHit>,
    ): Boolean =
        hitsAfterForget.none { hit -> hit.id == removedRecordId }
}
