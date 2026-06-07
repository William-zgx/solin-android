package com.bytedance.zgx.pocketmind.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryQualityContractTest {
    @Test
    fun contractRejectsRemoteRunsWithMemoryContextOrLocalOnlyHistory() {
        assertTrue(
            MemoryQualityContract.validateRunBoundary(
                remoteMode = true,
                memoryContextIncluded = false,
                remoteHistoryContainsLocalOnly = false,
            ),
        )
        assertFalse(
            MemoryQualityContract.validateRunBoundary(
                remoteMode = true,
                memoryContextIncluded = true,
                remoteHistoryContainsLocalOnly = false,
            ),
        )
        assertFalse(
            MemoryQualityContract.validateRunBoundary(
                remoteMode = true,
                memoryContextIncluded = false,
                remoteHistoryContainsLocalOnly = true,
            ),
        )
    }

    @Test
    fun contractLocksForgetToRemovedRecordId() {
        val removedId = "pref-1"

        assertTrue(
            MemoryQualityContract.validateForgetResult(
                removedRecordId = removedId,
                hitsAfterForget = listOf(
                    MemoryHit(id = "pref-2", text = "用户偏好：中文", score = 0.9f),
                ),
            ),
        )
        assertFalse(
            MemoryQualityContract.validateForgetResult(
                removedRecordId = removedId,
                hitsAfterForget = listOf(
                    MemoryHit(id = removedId, text = "用户偏好：简洁", score = 0.9f),
                ),
            ),
        )
    }
}
