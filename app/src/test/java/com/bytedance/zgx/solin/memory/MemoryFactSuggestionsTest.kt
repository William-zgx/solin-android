package com.bytedance.zgx.solin.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFactSuggestionsTest {
    @Test
    fun detectsDurableChineseFacts() {
        assertSuggests("我对花生过敏", MemoryFactSuggestionKind.Allergy, "我对花生过敏")
        assertSuggests("我女儿对牛奶过敏。", MemoryFactSuggestionKind.Allergy, "我女儿对牛奶过敏")
        assertSuggests("我家在北京市朝阳区望京", MemoryFactSuggestionKind.HomeLocation, "我家在北京市朝阳区望京")
        assertSuggests("我住在上海浦东", MemoryFactSuggestionKind.HomeLocation, "我住在上海浦东")
        assertSuggests("我在字节跳动上班", MemoryFactSuggestionKind.WorkLocation, "我在字节跳动上班")
        assertSuggests("我老婆叫林小雨", MemoryFactSuggestionKind.RelationName, "我老婆叫林小雨")
        assertSuggests("我老婆的电话是138-1234-5678", MemoryFactSuggestionKind.ContactPhone, "我老婆的电话是138-1234-5678")
        assertSuggests("我不吃辣", MemoryFactSuggestionKind.Diet, "我不吃辣")
        assertSuggests("我的生日是5月20日", MemoryFactSuggestionKind.Birthday, "我的生日是5月20日")
    }

    @Test
    fun detectsDurableEnglishFacts() {
        assertSuggests("I'm allergic to shellfish.", MemoryFactSuggestionKind.Allergy, "I'm allergic to shellfish")
        assertSuggests("I live in Shanghai Pudong", MemoryFactSuggestionKind.HomeLocation, "I live in Shanghai Pudong")
        assertSuggests("my wife's name is Emily", MemoryFactSuggestionKind.RelationName, "my wife's name is Emily")
        assertSuggests("I am vegetarian", MemoryFactSuggestionKind.Diet, "I am vegetarian")
    }

    @Test
    fun extractsFactClauseFromLongerMessage() {
        val suggestions = MemoryFactSuggestionDetector.detect("对了，我对花生过敏，帮我推荐几家餐厅")

        assertEquals(listOf("我对花生过敏"), suggestions.map { it.fact })
    }

    @Test
    fun ignoresQuestionsHypotheticalsTransientAndNegatedStatements() {
        listOf(
            "我对什么过敏？",
            "我对花生过敏吗",
            "如果我对花生过敏怎么办",
            "我可能对花生过敏",
            "我对花生不过敏",
            "我今天不吃辣",
            "我不吃了",
            "我在努力工作",
            "我现在住在酒店",
            "I'm not allergic to shellfish",
            "Maybe I live in Paris",
            "我家在哪",
            "导航回家",
            "我朋友对花生过敏",
            "",
        ).forEach { message ->
            assertTrue("unexpected suggestion for '$message'", MemoryFactSuggestionDetector.detect(message).isEmpty())
        }
    }

    @Test
    fun explicitRememberAndForgetCommandsAreNotSuggestions() {
        assertTrue(MemoryFactSuggestionDetector.detect("记住我对花生过敏").isEmpty())
        assertTrue(MemoryFactSuggestionDetector.detect("忘记我对花生过敏").isEmpty())
        assertTrue(MemoryFactSuggestionDetector.detect("remember that I live in Paris").isEmpty())
    }

    @Test
    fun suggestionIdIsStablePerNormalizedFact() {
        val first = MemoryFactSuggestionDetector.detect("我对花生过敏").single()
        val again = MemoryFactSuggestionDetector.detect("对了，我对花生过敏。").single()
        val other = MemoryFactSuggestionDetector.detect("我对青霉素过敏").single()

        assertEquals(first.id, again.id)
        assertNotEquals(first.id, other.id)
    }

    @Test
    fun confirmedSuggestionsFeedTheArgumentResolver() {
        val repository = MemoryRepository(recordStore = FakeMemoryRecordStore())
        listOf("我家在北京市朝阳区望京西园三区", "我老婆的电话是13812345678").forEach { message ->
            val fact = MemoryFactSuggestionDetector.detect(message).single().fact
            val command = "记住$fact"
            explicitUserFactFrom(command)?.let { repository.indexUserFact(explicitUserFactRecordId(it), it) }
                ?: explicitUserPreferenceFrom(command)!!.let { repository.indexPreference(explicitUserPreferenceRecordId(it), it) }
        }

        assertEquals(
            "北京市朝阳区望京西园三区",
            MemoryArgumentResolver.resolve(MemoryArgumentSlot.HomeAddress, repository)?.value,
        )
        assertEquals(
            "13812345678",
            MemoryArgumentResolver.resolve(MemoryArgumentSlot.ContactPhone("老婆"), repository)?.value,
        )
    }

    private fun assertSuggests(message: String, kind: MemoryFactSuggestionKind, fact: String) {
        val suggestions = MemoryFactSuggestionDetector.detect(message)
        assertEquals("suggestions for '$message'", 1, suggestions.size)
        assertEquals(kind, suggestions.single().kind)
        assertEquals(fact, suggestions.single().fact)
    }
}
