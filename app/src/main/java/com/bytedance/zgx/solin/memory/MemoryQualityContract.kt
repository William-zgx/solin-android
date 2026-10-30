package com.bytedance.zgx.solin.memory

import com.bytedance.zgx.solin.MessagePrivacy

object MemoryQualityContract {
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

    fun validateMemoryBoundary(
        remoteMode: Boolean,
        memoryHits: List<MemoryHit>,
        memoryContextIncluded: Boolean,
    ): Boolean =
        if (remoteMode) {
            !memoryContextIncluded && memoryHits.isEmpty()
        } else {
            memoryHits.all { hit -> hit.privacy == MessagePrivacy.LocalOnly }
        }

    fun validateForgetResult(
        removedRecordId: String,
        hitsAfterForget: List<MemoryHit>,
    ): Boolean =
        hitsAfterForget.none { hit -> hit.id == removedRecordId }
}
