package com.bytedance.zgx.pocketmind.memory

import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun hashRuntimeStillRequiresTokenOverlapBeforeVectorScoring() {
        val repository = MemoryRepository()
        repository.indexPreference("pref", "I prefer concise answers")

        val hits = repository.search("brief replies")

        assertTrue(hits.isEmpty())
    }

    @Test
    fun semanticRuntimeCanRecallWithoutTokenOverlap() {
        val repository = MemoryRepository(embeddingRuntime = ConceptEmbeddingRuntime())
        repository.indexPreference("pref", "I prefer concise answers")

        val hits = repository.search("brief replies")

        assertEquals(listOf("pref"), hits.map { it.id })
        assertEquals(MemoryRecallMode.Semantic, hits.first().recallMode)
    }

    @Test
    fun semanticRuntimeHonorsScoreThresholdWithoutTokenOverlap() {
        val repository = MemoryRepository(embeddingRuntime = ConceptEmbeddingRuntime())
        repository.indexPreference("pref", "I prefer concise answers")

        val hits = repository.search("nearby replies")

        assertTrue(hits.isEmpty())
    }

    @Test
    fun semanticRuntimeControllerSwitchesBetweenFallbackAndSemanticRuntime() {
        val repository = MemoryRepository(
            semanticRuntimeFactory = { path ->
                check(path == "/verified/memory.litertlm")
                ConceptEmbeddingRuntime()
            },
        )
        repository.indexPreference("pref", "I prefer concise answers")

        assertFalse(repository.semanticMemoryEnabled)
        assertTrue(repository.search("brief replies").isEmpty())

        repository.useMemoryModel("/verified/memory.litertlm")

        assertTrue(repository.semanticMemoryEnabled)
        assertEquals("/verified/memory.litertlm", repository.activeMemoryModelPath)
        val semanticHits = repository.search("brief replies")
        assertEquals(listOf("pref"), semanticHits.map { it.id })
        assertEquals(MemoryRecallMode.Semantic, semanticHits.first().recallMode)

        repository.useMemoryModel(null)

        assertFalse(repository.semanticMemoryEnabled)
        assertNull(repository.activeMemoryModelPath)
        assertTrue(repository.search("brief replies").isEmpty())
    }

    @Test
    fun memoryModelPathDoesNotEnableSemanticRecallWithoutRuntimeSupport() {
        val repository = MemoryRepository()
        repository.indexPreference("pref", "I prefer concise answers")

        repository.useMemoryModel("/verified/memory.litertlm")

        assertFalse(repository.semanticMemoryEnabled)
        assertNull(repository.activeMemoryModelPath)
        assertTrue(repository.search("brief replies").isEmpty())
    }

    @Test
    fun searchRequiresSpecificCjkOverlapWhenQueryHasBigrams() {
        val repository = MemoryRepository()
        repository.index("assistant-reply", "助手：远程回复")

        val hits = repository.search("简洁回答")

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
    fun rebuildSkipsExplicitPreferenceCommandsWithoutPersistedRecords() {
        val repository = MemoryRepository()
        val message = ChatMessage(
            role = MessageRole.User,
            text = "记住：我喜欢简洁的中文回答",
            id = 7L,
        )

        repository.rebuild(listOf(message))

        assertTrue(repository.search("简洁中文回答", topK = 3).isEmpty())
    }

    @Test
    fun explicitPreferenceExtractorSupportsChineseAndEnglishCommands() {
        assertEquals("我喜欢简洁的中文回答", explicitUserPreferenceFrom("请记住：我喜欢简洁的中文回答"))
        assertEquals("I prefer concise answers", explicitUserPreferenceFrom("please remember that I prefer concise answers"))
        assertEquals(null, explicitUserPreferenceFrom("我们讨论一下记忆系统"))
    }

    @Test
    fun explicitPreferenceRecordIdIsStableForNormalizedText() {
        assertEquals(
            explicitUserPreferenceRecordId("I prefer concise answers"),
            explicitUserPreferenceRecordId("  i   prefer   concise answers  "),
        )
    }

    @Test
    fun explicitPreferenceConflictKeyRecognizesResponseFamilies() {
        assertEquals("response-length", explicitPreferenceConflictKey("用户偏好：我喜欢简洁回答"))
        assertEquals("response-language", explicitPreferenceConflictKey("please reply in English"))
        assertEquals(null, explicitPreferenceConflictKey("I like green tea"))
    }

    @Test
    fun taskStateMemoryRecordIdIsStableForWhitespace() {
        assertEquals("task-state-background:task-1", taskStateMemoryRecordId(" task-1 "))
        assertEquals("task-state-background:periodic-check-local", taskStateMemoryRecordId("periodic check local"))
        assertEquals("task-state-background:unknown", taskStateMemoryRecordId("   "))
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

        assertTrue(repository.search("简洁中文回答", topK = 3).isEmpty())
        assertTrue(store.records().isEmpty())
        assertTrue(repository.savedRecords().isEmpty())
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

    @Test
    fun savedRecordsListsOnlyPersistedLongTermRecords() {
        val store = FakeMemoryRecordStore()
        val repository = MemoryRepository(recordStore = store)
        repository.index("conversation-1", "用户正在讨论京都旅行")
        repository.indexPreference("pref-1", "回答尽量简洁")
        repository.indexTaskState("task-1", "等待确认分享摘要")

        val records = repository.savedRecords()

        assertEquals(listOf("pref-1", "task-1"), records.map { it.id })
        assertEquals(
            listOf(MemoryRecordType.Preference, MemoryRecordType.TaskState),
            records.map { it.type },
        )
        assertEquals("用户偏好：回答尽量简洁", records.first().text)
        assertEquals("任务状态：等待确认分享摘要", records.last().text)
    }

    @Test
    fun savedRecordsReflectForgetAndClear() {
        val store = FakeMemoryRecordStore()
        val repository = MemoryRepository(recordStore = store)
        repository.indexPreference("pref-1", "回答尽量简洁")
        repository.indexTaskState("task-1", "等待确认分享摘要")

        assertEquals(2, repository.savedRecords().size)

        assertTrue(repository.forget("pref-1"))
        assertEquals(listOf("task-1"), repository.savedRecords().map { it.id })

        repository.clear()
        assertTrue(repository.savedRecords().isEmpty())
    }

    @Test
    fun conflictingResponseLengthPreferenceReplacesOlderRecord() {
        val store = FakeMemoryRecordStore()
        val repository = MemoryRepository(recordStore = store)

        repository.indexPreference("pref-short", "回答尽量简洁")
        repository.indexPreference("pref-detailed", "回答要详细")

        val records = repository.savedRecords()
        assertEquals(listOf("pref-detailed"), records.map { it.id })
        assertEquals("用户偏好：回答要详细", records.single().text)
        assertTrue(repository.search("简洁").isEmpty())
        assertEquals("pref-detailed", repository.search("详细回答").first().id)
    }

    @Test
    fun unrelatedResponsePreferenceFamiliesCanCoexist() {
        val store = FakeMemoryRecordStore()
        val repository = MemoryRepository(recordStore = store)

        repository.indexPreference("pref-short", "回答尽量简洁")
        repository.indexPreference("pref-language", "请用中文回答")

        assertEquals(listOf("pref-short", "pref-language"), repository.savedRecords().map { it.id })
    }

    @Test
    fun combinedResponsePreferenceReplacesBothFamilies() {
        val store = FakeMemoryRecordStore()
        val repository = MemoryRepository(recordStore = store)

        repository.indexPreference("pref-short", "回答尽量简洁")
        repository.indexPreference("pref-language", "请用中文回答")
        repository.indexPreference("pref-combined", "请用详细英文回答")

        assertEquals(listOf("pref-combined"), repository.savedRecords().map { it.id })
        assertEquals("pref-combined", repository.search("详细英文回答").first().id)
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

    private class ConceptEmbeddingRuntime : EmbeddingRuntime {
        override val supportsSemanticRecall: Boolean = true
        override val semanticScoreThreshold: Float = 0.9f

        override fun embed(text: String): FloatArray {
            val lower = text.lowercase()
            return when {
                "concise" in lower -> floatArrayOf(1f, 0f)
                "brief" in lower -> floatArrayOf(1f, 0f)
                "nearby" in lower -> floatArrayOf(0.8f, 0f)
                else -> floatArrayOf(0f, 1f)
            }
        }
    }
}
