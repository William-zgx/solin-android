package com.bytedance.zgx.pocketmind.memory

import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.MessagePrivacy
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
        assertEquals(MemoryRecordType.Conversation, hits.first().recordType)
        assertEquals(hits.first().score, hits.first().lexicalScore ?: 0f, 0.001f)
        assertNull(hits.first().semanticScore)
        assertEquals(hits.first().score, hits.first().finalScore, 0.001f)
        assertTrue(hits.first().rankingReason.contains("lexical"))
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
    fun rebuildDoesNotIndexLocalOnlyConversationMessages() {
        val repository = MemoryRepository()

        repository.rebuild(
            listOf(
                ChatMessage(
                    role = MessageRole.User,
                    text = "私密 OCR 摘录 starboat-secret",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                ChatMessage(
                    role = MessageRole.Assistant,
                    text = "本地摘要 starboat-summary",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                ChatMessage(
                    role = MessageRole.User,
                    text = "公开远程问题 Kotlin 协程",
                    privacy = MessagePrivacy.RemoteEligible,
                ),
            ),
        )

        assertTrue(repository.search("starboat-secret").isEmpty())
        assertTrue(repository.search("starboat-summary").isEmpty())
        assertEquals(listOf("公开远程问题 Kotlin 协程"), repository.search("Kotlin 协程").map { it.text.removePrefix("用户：") })
    }

    @Test
    fun rebuildDoesNotIndexAssistantConversationMessagesEvenWhenRemoteEligible() {
        val repository = MemoryRepository()

        repository.rebuild(
            listOf(
                ChatMessage(
                    role = MessageRole.Assistant,
                    text = "远程助手输出包含 transient-answer-73",
                    privacy = MessagePrivacy.RemoteEligible,
                    id = 73L,
                ),
                ChatMessage(
                    role = MessageRole.User,
                    text = "公开用户问题 edge-memory-recall",
                    privacy = MessagePrivacy.RemoteEligible,
                    id = 74L,
                ),
            ),
        )

        assertTrue(repository.search("transient-answer-73").isEmpty())
        assertEquals(listOf("74"), repository.search("edge-memory-recall").map { it.id })
    }

    @Test
    fun hashRuntimeRecallsResponseLengthPreferenceThroughLocalAliases() {
        val repository = MemoryRepository()
        repository.indexPreference("pref", "I prefer concise answers")

        val hits = repository.search("brief replies")

        assertEquals(listOf("pref"), hits.map { it.id })
        assertEquals(MemoryRecallMode.Lexical, hits.first().recallMode)
        assertFalse(repository.semanticMemoryEnabled)
    }

    @Test
    fun preferenceAliasesDoNotChangePersistedRecordTextOrContext() {
        val store = FakeMemoryRecordStore()
        val repository = MemoryRepository(recordStore = store)
        repository.indexPreference("pref", "I prefer concise answers")

        val hits = repository.search("brief replies")

        assertEquals("用户偏好：I prefer concise answers", repository.savedRecords().single().text)
        assertEquals(listOf("用户偏好：I prefer concise answers"), hits.map { it.text })
        assertFalse(repository.buildContext(hits).contains("brief"))
    }

    @Test
    fun responseLengthAliasesAreValueSpecific() {
        val detailedRepository = MemoryRepository()
        detailedRepository.indexPreference("pref-detailed", "please give detailed answers")

        assertEquals(listOf("pref-detailed"), detailedRepository.search("展开回复").map { it.id })
        assertTrue(detailedRepository.search("brief replies").isEmpty())
        assertTrue(detailedRepository.search("简洁回复").isEmpty())

        val conciseRepository = MemoryRepository()
        conciseRepository.indexPreference("pref-concise", "I prefer concise answers")

        assertTrue(conciseRepository.search("verbose response").isEmpty())
        assertTrue(conciseRepository.search("详细回答").isEmpty())
    }

    @Test
    fun responseLanguageAliasesAreLanguageSpecific() {
        val chineseRepository = MemoryRepository()
        chineseRepository.indexPreference("pref-cn", "请用中文回答")

        assertEquals(listOf("pref-cn"), chineseRepository.search("Mandarin replies").map { it.id })
        assertTrue(chineseRepository.search("English replies").isEmpty())

        val englishRepository = MemoryRepository()
        englishRepository.indexPreference("pref-en", "please reply in English")

        assertEquals(listOf("pref-en"), englishRepository.search("英语回答").map { it.id })
        assertTrue(englishRepository.search("中文回答").isEmpty())
    }

    @Test
    fun preferenceAliasesRequireResponsePreferenceIntent() {
        val repository = MemoryRepository()
        repository.indexPreference("pref", "I like short stories")

        assertTrue(repository.search("brief replies").isEmpty())
        assertEquals(listOf("pref"), repository.search("short stories").map { it.id })
    }

    @Test
    fun taskStateAliasesRecallReminderAndPeriodicCheckByLocalizedActiveStatus() {
        val repository = MemoryRepository()
        repository.indexTaskState(
            "task-reminder",
            "后台任务=Reminder；任务记录=task-state-background:task-1；状态=Scheduled；触发时间=1000",
        )
        repository.indexTaskState(
            "task-periodic",
            "后台任务=PeriodicCheck；任务记录=task-state-background:periodic-check-local；状态=Running；触发时间=2000",
        )

        val reminderHits = repository.search("待执行提醒", topK = 1)
        val periodicHits = repository.search("运行中的周期检查", topK = 1)

        assertEquals(listOf("task-reminder"), reminderHits.map { it.id })
        assertEquals(listOf("task-periodic"), periodicHits.map { it.id })
        assertFalse(repository.buildContext(reminderHits).contains("本地提醒"))
        assertFalse(repository.buildContext(reminderHits).contains("待执行"))
    }

    @Test
    fun taskStateAliasesDoNotRecallTerminalStatusQueries() {
        val repository = MemoryRepository()
        repository.indexTaskState(
            "task-reminder",
            "后台任务=Reminder；任务记录=task-state-background:task-1；状态=Scheduled；触发时间=1000",
        )

        assertTrue(repository.search("已取消提醒").isEmpty())
        assertTrue(repository.search("失败提醒").isEmpty())
        assertTrue(repository.search("delivered reminder").isEmpty())
    }

    @Test
    fun conversationRecordsDoNotReceiveLongTermAliasTokens() {
        val repository = MemoryRepository()
        repository.index("conv", "助手：Reminder Scheduled")

        assertTrue(repository.search("待执行提醒").isEmpty())
    }

    @Test
    fun semanticRuntimeCanRecallWithoutTokenOverlap() {
        val repository = MemoryRepository(embeddingRuntime = ConceptEmbeddingRuntime())
        repository.indexPreference("pref", "I prefer concise answers")

        val hits = repository.search("compressed responses")

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
    fun conversationRecordsDoNotEnterSemanticLongTermIndex() {
        val repository = MemoryRepository(
            semanticRuntimeFactory = { ConceptEmbeddingRuntime() },
        )
        repository.index("conv", "I prefer concise answers")

        repository.useMemoryModel("/verified/memory.tflite")

        assertTrue(repository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.Active, repository.semanticMemoryRuntimeStatus)
        assertTrue(repository.search("compressed responses").isEmpty())
        assertEquals(MemoryRecallMode.Lexical, repository.search("concise answers").single().recallMode)
        assertEquals(0, repository.semanticMemoryIndexedRecordCount)
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
        assertEquals(SemanticMemoryRuntimeStatus.NoVerifiedModel, repository.semanticMemoryRuntimeStatus)
        assertTrue(repository.search("compressed responses").isEmpty())

        repository.useMemoryModel("/verified/memory.litertlm")

        assertTrue(repository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.Active, repository.semanticMemoryRuntimeStatus)
        assertEquals("/verified/memory.litertlm", repository.activeMemoryModelPath)
        val semanticHits = repository.search("compressed responses")
        assertEquals(listOf("pref"), semanticHits.map { it.id })
        assertEquals(MemoryRecallMode.Semantic, semanticHits.first().recallMode)
        assertEquals(MemoryRecordType.Preference, semanticHits.first().recordType)
        assertNull(semanticHits.first().lexicalScore)
        assertEquals(semanticHits.first().score, semanticHits.first().semanticScore ?: 0f, 0.001f)
        assertTrue(semanticHits.first().rankingReason.contains("semantic"))

        repository.useMemoryModel(null)

        assertFalse(repository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.NoVerifiedModel, repository.semanticMemoryRuntimeStatus)
        assertNull(repository.activeMemoryModelPath)
        assertTrue(repository.search("compressed responses").isEmpty())
    }

    @Test
    fun activeSemanticRuntimeCombinesLexicalAndSemanticScoresForSameRecord() {
        val repository = MemoryRepository(
            semanticRuntimeFactory = { path ->
                check(path == "/verified/memory.litertlm")
                ConceptEmbeddingRuntime()
            },
        )
        repository.indexPreference("pref", "I prefer concise answers")
        repository.useMemoryModel("/verified/memory.litertlm")

        val hits = repository.search("concise compressed responses")

        assertEquals(listOf("pref"), hits.map { it.id })
        assertEquals(MemoryRecordType.Preference, hits.first().recordType)
        assertTrue((hits.first().lexicalScore ?: 0f) > 0f)
        assertEquals(1f, hits.first().semanticScore ?: 0f, 0.001f)
        assertEquals(1f, hits.first().finalScore, 0.001f)
        assertTrue(hits.first().rankingReason.contains("lexical"))
        assertTrue(hits.first().rankingReason.contains("semantic"))
    }

    @Test
    fun semanticRuntimeStatusReportsUnavailableWhenFactoryIsMissing() {
        val repository = MemoryRepository()

        repository.useMemoryModel("/verified/memory.litertlm")

        assertFalse(repository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.RuntimeUnavailable, repository.semanticMemoryRuntimeStatus)
        assertNull(repository.activeMemoryModelPath)

        repository.useMemoryModel(null)

        assertFalse(repository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.NoVerifiedModel, repository.semanticMemoryRuntimeStatus)
        assertNull(repository.activeMemoryModelPath)
    }

    @Test
    fun semanticRuntimeFactoryReturningNullFallsBackAndReembedsExistingEntries() {
        val repository = MemoryRepository(
            semanticRuntimeFactory = { path ->
                when (path) {
                    "/ok" -> ConceptEmbeddingRuntime()
                    else -> null
                }
            },
        )
        repository.indexPreference("pref", "I prefer concise answers")
        repository.useMemoryModel("/ok")

        assertEquals(listOf("pref"), repository.search("compressed responses").map { it.id })

        repository.useMemoryModel("/missing")

        assertFalse(repository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.RuntimeUnavailable, repository.semanticMemoryRuntimeStatus)
        assertNull(repository.activeMemoryModelPath)
        assertTrue(repository.search("compressed responses").isEmpty())
        assertEquals(listOf("pref"), repository.search("concise answers").map { it.id })

        repository.useMemoryModel(null)

        assertEquals(SemanticMemoryRuntimeStatus.NoVerifiedModel, repository.semanticMemoryRuntimeStatus)
    }

    @Test
    fun semanticRuntimeFactoryThrowingFallsBackWithoutPropagating() {
        val requestedPaths = mutableListOf<String>()
        val repository = MemoryRepository(
            semanticRuntimeFactory = { path ->
                requestedPaths += path
                if (path == "/broken") error("embedding runtime unavailable")
                ConceptEmbeddingRuntime()
            },
        )
        repository.indexPreference("pref", "I prefer concise answers")
        repository.useMemoryModel("/ok")

        repository.useMemoryModel("/broken")

        assertEquals(listOf("/ok", "/broken"), requestedPaths)
        assertFalse(repository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.RuntimeUnavailable, repository.semanticMemoryRuntimeStatus)
        assertNull(repository.activeMemoryModelPath)
        assertTrue(repository.search("compressed responses").isEmpty())
    }

    @Test
    fun semanticRuntimeCanRetrySamePathAfterLoadFailure() {
        var failRuntimeLoad = true
        val repository = MemoryRepository(
            semanticRuntimeFactory = {
                if (failRuntimeLoad) null else ConceptEmbeddingRuntime()
            },
        )
        repository.indexPreference("pref", "I prefer concise answers")

        repository.useMemoryModel("/verified/memory.litertlm")

        assertFalse(repository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.RuntimeUnavailable, repository.semanticMemoryRuntimeStatus)

        failRuntimeLoad = false
        repository.useMemoryModel("/verified/memory.litertlm")

        assertTrue(repository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.Active, repository.semanticMemoryRuntimeStatus)
        assertEquals("/verified/memory.litertlm", repository.activeMemoryModelPath)
        assertEquals(listOf("pref"), repository.search("compressed responses").map { it.id })
    }

    @Test
    fun semanticRuntimeEmbedFailureFallsBackWithoutPropagating() {
        val repository = MemoryRepository(
            semanticRuntimeFactory = { FailingOnConciseEmbeddingRuntime() },
        )
        repository.indexPreference("pref", "I prefer concise answers")

        repository.useMemoryModel("/broken")

        assertFalse(repository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.DegradedLexical, repository.semanticMemoryRuntimeStatus)
        assertNull(repository.activeMemoryModelPath)
        assertTrue(repository.search("compressed responses").isEmpty())
        assertEquals(listOf("pref"), repository.search("concise answers").map { it.id })
    }

    @Test
    fun switchingSemanticRuntimeReembedsExistingEntriesWithNewRuntime() {
        val repository = MemoryRepository(
            semanticRuntimeFactory = { path ->
                when (path) {
                    "/model-a" -> AliasEmbeddingRuntime(queryAlias = "compressed")
                    "/model-b" -> AliasEmbeddingRuntime(queryAlias = "laconic")
                    else -> null
                }
            },
        )
        repository.indexPreference("pref", "I prefer concise answers")

        repository.useMemoryModel("/model-a")

        assertEquals(listOf("pref"), repository.search("compressed responses").map { it.id })
        assertTrue(repository.search("laconic replies").isEmpty())

        repository.useMemoryModel("/model-b")

        assertTrue(repository.search("compressed responses").isEmpty())
        val hits = repository.search("laconic replies")
        assertEquals(listOf("pref"), hits.map { it.id })
        assertEquals(MemoryRecallMode.Semantic, hits.first().recallMode)
    }

    @Test
    fun switchingSemanticRuntimeReembedsRestoredRecordStoreEntries() {
        val store = FakeMemoryRecordStore()
        val writer = MemoryRepository(recordStore = store)
        writer.indexPreference("pref-1", "I prefer concise answers")
        writer.indexTaskState("task-1", "Need concise task updates")
        val restored = MemoryRepository(
            recordStore = store,
            semanticRuntimeFactory = { path ->
                when (path) {
                    "/model-a" -> AliasEmbeddingRuntime(queryAlias = "compressed")
                    "/model-b" -> AliasEmbeddingRuntime(queryAlias = "laconic")
                    else -> null
                }
            },
        )
        restored.rebuild(emptyList())

        restored.useMemoryModel("/model-a")

        assertEquals(listOf("pref-1", "task-1"), restored.search("compressed responses").map { it.id })

        restored.useMemoryModel("/model-b")

        assertTrue(restored.search("compressed responses").isEmpty())
        assertEquals(listOf("pref-1", "task-1"), restored.search("laconic replies").map { it.id })
    }

    @Test
    fun semanticVectorCacheIsCreatedReusedAndInvalidated() {
        val recordStore = FakeMemoryRecordStore()
        val embeddingStore = FakeMemoryEmbeddingStore()
        val writerRuntime = RecordingConceptEmbeddingRuntime(modelId = "model-a")
        val writer = MemoryRepository(
            recordStore = recordStore,
            embeddingStore = embeddingStore,
            semanticRuntimeFactory = { writerRuntime },
            clockMillis = { 100L },
        )
        writer.indexPreference("pref", "I prefer concise answers")

        writer.useMemoryModel("/model-a")

        assertEquals(1, embeddingStore.entries.size)
        assertEquals(1, embeddingStore.upsertCount)
        assertEquals(1, writer.semanticMemoryIndexedRecordCount)

        val restoredRuntime = RecordingConceptEmbeddingRuntime(modelId = "model-a")
        val restored = MemoryRepository(
            recordStore = recordStore,
            embeddingStore = embeddingStore,
            semanticRuntimeFactory = { restoredRuntime },
            clockMillis = { 200L },
        )
        restored.rebuild(emptyList())
        restored.useMemoryModel("/model-a")

        assertEquals(listOf("semantic memory runtime probe"), restoredRuntime.embeddedTexts)
        assertEquals(listOf("pref"), restored.search("compressed responses").map { it.id })

        restored.indexPreference("pref", "I prefer compact answers")

        assertEquals(2, embeddingStore.upsertCount)
        assertTrue(restoredRuntime.embeddedTexts.any { "compact answers" in it })

        assertTrue(restored.forget("pref"))
        assertTrue(embeddingStore.entries.isEmpty())
        assertEquals(0, restored.semanticMemoryIndexedRecordCount)
    }

    @Test
    fun deletingSemanticMemoryModelClearsVectorCacheButKeepsLongTermText() {
        val recordStore = FakeMemoryRecordStore()
        val embeddingStore = FakeMemoryEmbeddingStore()
        val repository = MemoryRepository(
            recordStore = recordStore,
            embeddingStore = embeddingStore,
            semanticRuntimeFactory = { ConceptEmbeddingRuntime() },
        )
        repository.indexPreference("pref", "I prefer concise answers")
        repository.useMemoryModel("/model-a")
        assertEquals(listOf("pref"), repository.search("compressed responses").map { it.id })
        assertEquals(1, embeddingStore.entries.size)

        repository.clearSemanticMemoryForModel("concept-test")

        assertFalse(repository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.NoVerifiedModel, repository.semanticMemoryRuntimeStatus)
        assertTrue(embeddingStore.entries.isEmpty())
        assertEquals(listOf("pref"), repository.savedRecords().map { it.id })
        assertTrue(repository.search("compressed responses").isEmpty())
        val lexicalHits = repository.search("concise answers")
        assertEquals(listOf("pref"), lexicalHits.map { it.id })
        assertEquals(MemoryRecallMode.Lexical, lexicalHits.single().recallMode)
    }

    @Test
    fun memoryModelPathDoesNotEnableSemanticRecallWithoutRuntimeSupport() {
        val repository = MemoryRepository()
        repository.indexPreference("pref", "I prefer concise answers")

        repository.useMemoryModel("/verified/memory.litertlm")

        assertFalse(repository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.RuntimeUnavailable, repository.semanticMemoryRuntimeStatus)
        assertNull(repository.activeMemoryModelPath)
        assertTrue(repository.search("laconic replies").isEmpty())
        val aliasHits = repository.search("brief replies")
        assertEquals(listOf("pref"), aliasHits.map { it.id })
        assertEquals(MemoryRecallMode.Lexical, aliasHits.first().recallMode)
    }

    @Test
    fun missingProductionEmbeddingRuntimeFailsClosedToLexicalRecall() {
        val repository = MemoryRepository(
            semanticRuntimeFactory = { null },
        )
        repository.indexPreference("pref", "I prefer concise answers")

        repository.useMemoryModel("/verified/memory.tflite")

        assertTrue(repository.canLoadSemanticMemoryRuntime)
        assertFalse(repository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.RuntimeUnavailable, repository.semanticMemoryRuntimeStatus)
        assertNull(repository.activeMemoryModelPath)
        assertTrue(repository.search("laconic replies").isEmpty())
        val aliasHits = repository.search("brief replies")
        assertEquals(listOf("pref"), aliasHits.map { it.id })
        assertEquals(MemoryRecallMode.Lexical, aliasHits.single().recallMode)
    }

    @Test
    fun activeSemanticRuntimeQueryFailureFallsBackToLexicalRecall() {
        val repository = MemoryRepository(
            semanticRuntimeFactory = { FailingOnBriefQueryEmbeddingRuntime() },
        )
        repository.indexPreference("pref", "I prefer concise answers")
        repository.useMemoryModel("/verified/memory.litertlm")
        assertTrue(repository.semanticMemoryEnabled)

        val hits = repository.search("brief replies")

        assertFalse(repository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.DegradedLexical, repository.semanticMemoryRuntimeStatus)
        assertNull(repository.activeMemoryModelPath)
        assertEquals(listOf("pref"), hits.map { it.id })
        assertEquals(MemoryRecallMode.Lexical, hits.single().recallMode)
    }

    @Test
    fun activeSemanticRuntimeIndexFailurePersistsAndFallsBackToLexicalRecall() {
        val store = FakeMemoryRecordStore()
        val repository = MemoryRepository(
            recordStore = store,
            semanticRuntimeFactory = { FailingOnConciseEmbeddingRuntime() },
        )
        repository.useMemoryModel("/verified/memory.litertlm")
        assertTrue(repository.semanticMemoryEnabled)

        repository.indexPreference("pref", "I prefer concise answers")

        assertFalse(repository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.DegradedLexical, repository.semanticMemoryRuntimeStatus)
        assertNull(repository.activeMemoryModelPath)
        assertEquals(listOf("pref"), repository.savedRecords().map { it.id })
        val hits = repository.search("brief replies")
        assertEquals(listOf("pref"), hits.map { it.id })
        assertEquals(MemoryRecallMode.Lexical, hits.single().recallMode)
    }

    @Test
    fun verifiedMemoryModelPathWithoutSemanticRuntimeDoesNotEnableSemanticRecallAfterRebuild() {
        val store = FakeMemoryRecordStore()
        val writer = MemoryRepository(recordStore = store)
        writer.indexPreference("pref", "I prefer concise answers")
        val restored = MemoryRepository(recordStore = store)

        restored.useMemoryModel("/verified/memory.litertlm")
        restored.rebuild(emptyList())

        assertFalse(restored.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.RuntimeUnavailable, restored.semanticMemoryRuntimeStatus)
        assertNull(restored.activeMemoryModelPath)
        assertTrue(restored.search("laconic replies").isEmpty())
        assertEquals(listOf("pref"), restored.search("brief replies").map { it.id })
        assertEquals(listOf("pref"), restored.search("concise answers").map { it.id })
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
    fun rebuildSkipsExplicitPreferenceForgetCommandsWithoutConversationRecord() {
        val repository = MemoryRepository()
        val message = ChatMessage(
            role = MessageRole.User,
            text = "忘记：我喜欢简洁的中文回答",
            id = 8L,
        )

        repository.rebuild(listOf(message))

        assertTrue(repository.search("简洁中文回答", topK = 3).isEmpty())
        assertTrue(repository.search("忘记", topK = 3).isEmpty())
    }

    @Test
    fun explicitPreferenceExtractorSupportsChineseAndEnglishCommands() {
        assertEquals("我喜欢简洁的中文回答", explicitUserPreferenceFrom("请记住：我喜欢简洁的中文回答"))
        assertEquals("I prefer concise answers", explicitUserPreferenceFrom("please remember that I prefer concise answers"))
        assertNull(explicitUserPreferenceFrom("remember that my rcode is xb83"))
        assertEquals(null, explicitUserPreferenceFrom("我们讨论一下记忆系统"))
    }

    @Test
    fun explicitUserFactExtractorSupportsChineseAndEnglishRememberCommands() {
        assertEquals("我的工号是 xb83", explicitUserFactFrom("记住：我的工号是 xb83"))
        assertEquals("my rcode is xb83", explicitUserFactFrom("remember that my rcode is xb83"))
        assertNull(explicitUserFactFrom("remember that I prefer concise answers"))
        assertNull(explicitUserFactFrom("我们讨论一下记忆系统"))
    }

    @Test
    fun explicitPreferenceForgetExtractorSupportsChineseAndEnglishCommands() {
        assertEquals("我喜欢简洁的中文回答", explicitUserPreferenceForgetFrom("请忘记：我喜欢简洁的中文回答"))
        assertEquals("回答要详细", explicitUserPreferenceForgetFrom("不要再记住：用户偏好：回答要详细"))
        assertEquals("I prefer concise answers", explicitUserPreferenceForgetFrom("please forget that I prefer concise answers"))
        assertEquals(null, explicitUserPreferenceForgetFrom("我们讨论一下遗忘机制"))
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
    fun explicitPreferenceForgetConflictKeysRecognizeFamilyTargets() {
        assertEquals(setOf("response-language"), explicitPreferenceForgetConflictKeys("回答语言偏好"))
        assertEquals(setOf("response-length"), explicitPreferenceForgetConflictKeys("answer length preference"))
        assertEquals(setOf("response-length", "response-language"), explicitPreferenceForgetConflictKeys("回答偏好"))
        assertTrue(explicitPreferenceForgetConflictKeys("green tea preference").isEmpty())
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
    fun rebuildSkipsExplicitUserFactCommandsWithoutConversationRecord() {
        val store = FakeMemoryRecordStore()
        val repository = MemoryRepository(recordStore = store)
        val restored = ChatMessage(
            role = MessageRole.User,
            text = "remember that my rcode is xb83",
            id = 9L,
        )

        repository.rebuild(listOf(restored))

        assertTrue(repository.search("rcode xb83", topK = 3).isEmpty())
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
    fun explicitUserFactRecordsPersistUpdateAndForgetByFactKey() {
        val store = FakeMemoryRecordStore()
        val repository = MemoryRepository(recordStore = store)
        val firstId = explicitUserFactRecordId("my rcode is xb83")
        val updatedId = explicitUserFactRecordId("my rcode is yz99")

        assertEquals(firstId, updatedId)

        repository.indexUserFact(firstId, "my rcode is xb83")
        repository.indexUserFact(updatedId, "my rcode is yz99")

        val records = repository.savedRecords()
        assertEquals(listOf(updatedId), records.map { it.id })
        assertEquals(listOf(MemoryRecordType.UserFact), records.map { it.type })
        assertEquals("用户事实：my rcode is yz99", records.single().text)
        val hits = repository.search("rcode yz99", topK = 3)
        assertEquals(listOf(updatedId), hits.map { it.id })
        assertFalse(repository.buildContext(hits).contains("用户偏好"))

        assertTrue(repository.forgetUserFact("my rcode is yz99"))
        assertTrue(repository.savedRecords().isEmpty())
        assertTrue(repository.search("rcode yz99", topK = 3).isEmpty())
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
    fun suppressedTaskStateRecordsAreHiddenAndNotIndexed() {
        val store = FakeMemoryRecordStore()
        val repository = MemoryRepository(recordStore = store)
        val memoryId = taskStateMemoryRecordId("task-1")

        repository.indexTaskState(
            memoryId,
            "后台任务=Reminder；任务记录=$memoryId；状态=Scheduled；触发时间=1000",
        )
        repository.suppressAutoManagedTaskState(memoryId)

        assertTrue(repository.isAutoManagedTaskStateSuppressed(memoryId))
        assertEquals(listOf(suppressedTaskStateMemoryRecordId(memoryId)), store.records().map { it.id })
        assertTrue(repository.savedRecords().isEmpty())
        assertTrue(repository.search("待执行提醒").isEmpty())

        val restored = MemoryRepository(recordStore = store)
        restored.rebuild(emptyList())

        assertTrue(restored.isAutoManagedTaskStateSuppressed(memoryId))
        assertTrue(restored.savedRecords().isEmpty())
        assertTrue(restored.search("待执行提醒").isEmpty())

        restored.indexTaskState(memoryId, "重新同步的任务状态")

        assertTrue(restored.savedRecords().isEmpty())
        assertTrue(restored.search("重新同步").isEmpty())

        restored.unsuppressAutoManagedTaskState(memoryId)

        assertFalse(restored.isAutoManagedTaskStateSuppressed(memoryId))
        assertTrue(store.records().isEmpty())
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

    @Test
    fun forgetPreferenceCanDeleteResponsePreferenceFamily() {
        val store = FakeMemoryRecordStore()
        val repository = MemoryRepository(recordStore = store)
        repository.indexPreference("pref-short", "回答尽量简洁")
        repository.indexPreference("pref-language", "请用中文回答")
        repository.indexPreference("pref-tea", "我喜欢绿茶")

        assertTrue(repository.forgetPreference("回答语言偏好"))

        assertEquals(listOf("pref-short", "pref-tea"), repository.savedRecords().map { it.id })
        assertTrue(repository.search("Mandarin replies").isEmpty())
        assertEquals(listOf("pref-short"), repository.search("简洁回答").map { it.id })
    }

    @Test
    fun forgetPreferenceStillDeletesExactUnrelatedPreference() {
        val store = FakeMemoryRecordStore()
        val repository = MemoryRepository(recordStore = store)
        repository.indexPreference(explicitUserPreferenceRecordId("I like green tea"), "I like green tea")

        assertTrue(repository.forgetPreference("I like green tea"))

        assertTrue(repository.savedRecords().isEmpty())
        assertTrue(repository.search("green tea").isEmpty())
    }

    companion object {
        private fun conceptVectorFor(text: String): FloatArray {
            val lower = text.lowercase()
            return when {
                "concise" in lower -> floatArrayOf(1f, 0f)
                "compact" in lower -> floatArrayOf(1f, 0f)
                "brief" in lower -> floatArrayOf(1f, 0f)
                "compressed" in lower -> floatArrayOf(1f, 0f)
                "nearby" in lower -> floatArrayOf(0.8f, 0.6f)
                else -> floatArrayOf(0f, 1f)
            }
        }
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

    private class FakeMemoryEmbeddingStore : MemoryEmbeddingStore {
        val entries = linkedMapOf<Pair<String, String>, PersistedMemoryEmbedding>()
        var upsertCount: Int = 0

        override fun embedding(recordId: String, modelId: String): PersistedMemoryEmbedding? =
            entries[recordId to modelId]

        override fun upsert(embedding: PersistedMemoryEmbedding) {
            upsertCount += 1
            entries[embedding.recordId to embedding.modelId] = embedding
        }

        override fun delete(recordId: String) {
            entries.keys.filter { it.first == recordId }.forEach(entries::remove)
        }

        override fun deleteForModel(modelId: String) {
            entries.keys.filter { it.second == modelId }.forEach(entries::remove)
        }

        override fun clear() {
            entries.clear()
        }
    }

    private class RecordingConceptEmbeddingRuntime(
        override val modelId: String,
    ) : EmbeddingRuntime {
        val embeddedTexts = mutableListOf<String>()
        override val dimension: Int = 2
        override val supportsSemanticRecall: Boolean = true
        override val semanticScoreThreshold: Float = 0.9f

        override fun embed(text: String): FloatArray {
            embeddedTexts += text
            return conceptVectorFor(text)
        }
    }

    private class ConceptEmbeddingRuntime : EmbeddingRuntime {
        override val modelId: String = "concept-test"
        override val dimension: Int = 2
        override val supportsSemanticRecall: Boolean = true
        override val semanticScoreThreshold: Float = 0.9f

        override fun embed(text: String): FloatArray {
            return conceptVectorFor(text)
        }
    }

    private class AliasEmbeddingRuntime(
        private val queryAlias: String,
    ) : EmbeddingRuntime {
        override val modelId: String = "alias-test-$queryAlias"
        override val dimension: Int = 2
        override val supportsSemanticRecall: Boolean = true
        override val semanticScoreThreshold: Float = 0.9f

        override fun embed(text: String): FloatArray {
            val lower = text.lowercase()
            return when {
                "concise" in lower -> floatArrayOf(1f, 0f)
                queryAlias in lower -> floatArrayOf(1f, 0f)
                else -> floatArrayOf(0f, 1f)
            }
        }
    }

    private class FailingOnConciseEmbeddingRuntime : EmbeddingRuntime {
        override val modelId: String = "failing-concise-test"
        override val dimension: Int = 2
        override val supportsSemanticRecall: Boolean = true

        override fun embed(text: String): FloatArray {
            check("concise" !in text.lowercase()) { "semantic runtime failed" }
            return floatArrayOf(0f, 1f)
        }
    }

    private class FailingOnBriefQueryEmbeddingRuntime : EmbeddingRuntime {
        override val modelId: String = "failing-brief-test"
        override val dimension: Int = 2
        override val supportsSemanticRecall: Boolean = true
        override val semanticScoreThreshold: Float = 0.9f

        override fun embed(text: String): FloatArray {
            val lower = text.lowercase()
            check("brief" !in lower) { "semantic runtime failed" }
            return when {
                "concise" in lower -> floatArrayOf(1f, 0f)
                else -> floatArrayOf(0f, 1f)
            }
        }
    }
}
