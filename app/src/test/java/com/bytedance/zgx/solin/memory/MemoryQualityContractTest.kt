package com.bytedance.zgx.solin.memory

import com.bytedance.zgx.solin.MessagePrivacy
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

    @Test
    fun contractRejectsRemoteRunsWithAnyMemoryHits() {
        val sensitiveFact = MemoryHit(
            id = "fact-1",
            text = "用户事实：my rcode is xb83",
            score = 0.9f,
            recordType = MemoryRecordType.UserFact,
            sensitivity = MemoryRecordSensitivity.Sensitive,
            privacy = MessagePrivacy.LocalOnly,
        )

        assertFalse(
            MemoryQualityContract.validateMemoryBoundary(
                remoteMode = true,
                memoryHits = listOf(sensitiveFact),
                memoryContextIncluded = false,
            ),
        )
        assertFalse(
            MemoryQualityContract.validateMemoryBoundary(
                remoteMode = true,
                memoryHits = emptyList(),
                memoryContextIncluded = true,
            ),
        )
        assertTrue(
            MemoryQualityContract.validateMemoryBoundary(
                remoteMode = true,
                memoryHits = emptyList(),
                memoryContextIncluded = false,
            ),
        )
    }

    @Test
    fun contractKeepsLocalMemoryHitsLocalOnly() {
        assertTrue(
            MemoryQualityContract.validateMemoryBoundary(
                remoteMode = false,
                memoryHits = listOf(
                    MemoryHit(
                        id = "pref-1",
                        text = "用户偏好：中文",
                        score = 0.9f,
                        privacy = MessagePrivacy.LocalOnly,
                    ),
                ),
                memoryContextIncluded = true,
            ),
        )
        assertFalse(
            MemoryQualityContract.validateMemoryBoundary(
                remoteMode = false,
                memoryHits = listOf(
                    MemoryHit(
                        id = "bad-memory",
                        text = "不应远程合格的记忆",
                        score = 0.9f,
                        privacy = MessagePrivacy.RemoteEligible,
                    ),
                ),
                memoryContextIncluded = true,
            ),
        )
    }
}
