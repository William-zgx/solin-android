package com.bytedance.zgx.pocketmind.memory

import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryTest {
    @Test
    fun searchReturnsTopMatchesAndHonorsTopK() {
        val repository = MemoryRepository()
        repository.index("travel", "用户喜欢京都旅行和安静咖啡馆")
        repository.index("work", "助手整理 Android 模型下载方案")
        repository.index("food", "用户喜欢清淡早餐")

        val hits = repository.search("京都咖啡馆旅行", topK = 1)

        assertEquals(1, hits.size)
        assertEquals("travel", hits.first().id)
    }

    @Test
    fun searchReturnsEmptyWhenDisabledOrEmpty() {
        val repository = MemoryRepository()
        repository.index("one", "本地记忆内容")

        repository.enabled = false

        assertTrue(repository.search("记忆").isEmpty())
        assertTrue(MemoryRepository().search("记忆").isEmpty())
    }

    @Test
    fun searchSkipsEntriesWithoutTokenOverlap() {
        val repository = MemoryRepository()
        repository.index("one", "端侧大模型部署在手机本地运行")

        val hits = repository.search("project codename starboat")

        assertTrue(hits.isEmpty())
    }

    @Test
    fun searchIgnoresCommonLatinStopWords() {
        val repository = MemoryRepository()
        repository.index("old", "What is my project")
        repository.index("code", "zcode_is_zeta73")
        repository.index("command", "Remember my project note")

        val hits = repository.search("What_is_my_zcode")

        assertEquals(1, hits.size)
        assertEquals("code", hits.first().id)
    }

    @Test
    fun rebuildIndexesConversationMessagesWithStableIds() {
        val repository = MemoryRepository()
        val message = ChatMessage(
            role = MessageRole.User,
            text = "我正在做端侧动作助手",
            id = 42L,
        )

        repository.rebuild(listOf(message))

        assertEquals("42", repository.search("动作助手").first().id)
    }

    @Test
    fun rebuildExtractsExplicitUserPreferenceMemory() {
        val repository = MemoryRepository()
        val message = ChatMessage(
            role = MessageRole.User,
            text = "记住：我喜欢简洁的中文回答",
            id = 7L,
        )

        repository.rebuild(listOf(message))

        val hits = repository.search("简洁中文回答", topK = 3)
        assertTrue(hits.any { it.id == "preference-7" })
    }

    @Test
    fun rebuildDoesNotPersistExtractedHistoryPreferences() {
        val store = FakeMemoryRecordStore()
        val repository = MemoryRepository(recordStore = store)
        val firstRestore = ChatMessage(
            role = MessageRole.User,
            text = "记住：我喜欢简洁的中文回答",
            id = 7L,
        )
        val secondRestore = firstRestore.copy(id = 8L)

        repository.rebuild(listOf(firstRestore))
        repository.rebuild(listOf(secondRestore))

        assertTrue(repository.search("简洁中文回答", topK = 3).isNotEmpty())
        assertTrue(store.records().isEmpty())
    }

    @Test
    fun canIndexTaskStateAndForgetRecords() {
        val repository = MemoryRepository()
        repository.indexTaskState("task-1", "等待用户确认网页搜索结果")

        assertEquals("task-1", repository.search("网页搜索确认").first().id)

        assertTrue(repository.forget("task-1"))
        assertTrue(repository.search("网页搜索确认").isEmpty())
    }

    @Test
    fun explicitPreferenceAndTaskStateRecordsPersistAcrossRepositoryInstances() {
        val store = FakeMemoryRecordStore()
        val firstRepository = MemoryRepository(recordStore = store)
        firstRepository.indexPreference("pref-1", "回答尽量简洁")
        firstRepository.indexTaskState("task-1", "等待确认分享摘要")

        val restoredRepository = MemoryRepository(recordStore = store)
        restoredRepository.rebuild(emptyList())

        assertEquals("pref-1", restoredRepository.search("简洁回答").first().id)
        assertEquals("task-1", restoredRepository.search("分享摘要确认").first().id)

        assertTrue(restoredRepository.forget("pref-1"))

        val afterForgetRepository = MemoryRepository(recordStore = store)
        afterForgetRepository.rebuild(emptyList())
        assertTrue(afterForgetRepository.search("简洁回答").isEmpty())
        assertEquals("task-1", afterForgetRepository.search("分享摘要确认").first().id)
    }

    private class FakeMemoryRecordStore : MemoryRecordStore {
        private val records = linkedMapOf<String, PersistedMemoryRecord>()

        override fun records(): List<PersistedMemoryRecord> =
            records.values.toList()

        override fun upsert(record: PersistedMemoryRecord) {
            records[record.id] = record
        }

        override fun delete(id: String): Boolean =
            records.remove(id) != null

        override fun clear() {
            records.clear()
        }
    }
}
