package com.bytedance.zgx.solin.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRankingTest {
    @Test
    fun cjkBigramsDoNotCrossPunctuationOrLatinBoundaries() {
        val tokens = tokenize("北京，上海B2层")

        assertTrue("北京" in tokens)
        assertTrue("上海" in tokens)
        assertFalse("京上" in tokens)
        assertFalse("海层" in tokens)
        assertTrue("b2" in tokens)
    }

    @Test
    fun particlesPronounsAndStopBigramsAreNotTokens() {
        val tokens = tokenize("帮我推荐一下我的咖啡")

        assertFalse("我" in tokens)
        assertFalse("的" in tokens)
        assertFalse("我的" in tokens)
        assertFalse("帮我" in tokens)
        assertFalse("推荐" in tokens)
        assertFalse("推" in tokens)
        assertFalse("荐" in tokens)
        assertTrue("咖啡" in tokens)
        assertTrue("咖" in tokens)
    }

    @Test
    fun memoryRecordLabelPrefixIsNotSearchable() {
        val repository = MemoryRepository()
        repository.indexUserFact("fact", "我的血型是O型")

        assertTrue(repository.search("用户事实").isEmpty())
        assertEquals(listOf("fact"), repository.search("血型").map { it.id })
    }

    @Test
    fun shortSingleCharacterQueryRecallsButLongQueryReducedToOneCharacterDoesNot() {
        val repository = MemoryRepository()
        repository.indexPreference("tea", "我不喝绿茶，只喝红茶")
        repository.indexPreference("wake", "我每天早上七点起床")

        assertEquals(listOf("tea"), repository.search("茶").map { it.id })
        assertTrue(repository.search("现在几点了").isEmpty())
    }

    @Test
    fun genericQuestionsSharingOnlyFunctionWordsReturnNothing() {
        val repository = MemoryRepository()
        repository.indexUserFact("birthday", "我的生日是5月20日")
        repository.index("conv", "用户：帮我查一下明天的高铁")

        assertTrue(repository.search("我的问题是什么").isEmpty())
        assertTrue(repository.search("帮我翻译一下").isEmpty())
        assertTrue(repository.search("明天天气怎么样").isEmpty())
    }

    @Test
    fun explicitRecordsOutrankConversationSnippetsWithTheSameText() {
        val repository = MemoryRepository()
        repository.indexPreference("pref", "我喜欢猫")
        repository.index("conv", "用户：我喜欢猫")

        val hits = repository.search("喜欢猫吗")

        assertEquals(listOf("pref", "conv"), hits.map { it.id })
        assertEquals(hits[0].lexicalScore!!, hits[1].lexicalScore!!, 0.0001f)
    }

    @Test
    fun equalScoresPreferTheMostRecentlyIndexedRecord() {
        val repository = MemoryRepository()
        repository.index("older", "用户：周末去爬香山")
        repository.index("newer", "用户：周末去爬香山")

        assertEquals(listOf("newer", "older"), repository.search("爬香山").map { it.id })
    }

    @Test
    fun reindexingKeepsOriginalRecencyPosition() {
        val repository = MemoryRepository()
        repository.index("first", "用户：周末去爬香山")
        repository.index("second", "用户：周末去爬香山")
        repository.index("first", "用户：周末去爬香山")

        assertEquals(listOf("second", "first"), repository.search("爬香山").map { it.id })
    }

    @Test
    fun reciprocalRankFusionFavoursRecordsRankedByBothModes() {
        val repository = MemoryRepository(
            semanticRuntimeFactory = { FixedSemanticRuntime() },
        )
        // "both" matches the query lexically (weaker) and semantically; "lexical" only lexically
        // (stronger); "semantic" only semantically (stronger).
        repository.indexPreference("lexical", "orchid watering schedule")
        repository.indexPreference("both", "orchid fertilizer plan semantic-b")
        repository.indexPreference("semantic", "unrelated words semantic-a")
        repository.useMemoryModel("/verified/memory.tflite")

        val hits = repository.search("orchid watering", topK = 3)

        assertEquals("both", hits.first().id)
        assertEquals(setOf("lexical", "semantic"), hits.drop(1).map { it.id }.toSet())
        // Raw relevance stays the max of the two modes for user-facing score labels.
        hits.forEach { hit ->
            assertEquals(maxOf(hit.lexicalScore ?: 0f, hit.semanticScore ?: 0f), hit.finalScore, 0.0001f)
        }
    }

    @Test
    fun homeAddressFactIsRecalledByNavigationStylePhrasing() {
        val repository = MemoryRepository()
        repository.indexPreference("home", "我家在北京市朝阳区望京西园三区")
        repository.indexUserFact("wifi", "我家的WiFi密码是sunny")

        assertEquals("home", repository.search("导航回家").first().id)
        assertEquals("home", repository.search("我家住哪").first().id)
    }

    /** Query is semantic-a-ish and semantic-b-ish; "semantic-a" scores higher than "semantic-b". */
    private class FixedSemanticRuntime : EmbeddingRuntime {
        override val runtimeId: String = "fixed"
        override val modelId: String = "fixed"
        override val dimension: Int = 3
        override val supportsSemanticRecall: Boolean = true
        override val semanticScoreThreshold: Float = 0.5f

        override fun embed(text: String): FloatArray =
            when {
                "semantic-a" in text -> floatArrayOf(1f, 0f, 0f)
                "semantic-b" in text -> floatArrayOf(0.8f, 0.6f, 0f)
                "orchid watering" == text -> floatArrayOf(0.95f, 0.31f, 0f)
                else -> floatArrayOf(0f, 0f, 1f)
            }
    }
}
