package com.bytedance.zgx.solin.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalModelMemoryFactExtractorTest {
    private val enabled = LocalModelMemoryExtractionConfig(enabled = true)

    @Test
    fun flagIsOffByDefault() {
        assertFalse(LocalModelMemoryExtractionConfig().enabled)
    }

    @Test
    fun flagOffReturnsTheRegexResultWithoutCallingTheModel() = runTest {
        val model = FakeModel { _, _ -> error("the model must not be called") }
        val extractor = LocalModelMemoryFactExtractor(model)

        REGRESSION_MESSAGES.forEach { message ->
            val extraction = extractor.extract(message)
            assertEquals("suggestions for '$message'", MemoryFactSuggestionDetector.detect(message), extraction.suggestions)
            assertEquals(MemoryTaskPath.Heuristic, extraction.path)
            assertNull(extraction.fallback)
        }
        assertEquals(0, model.calls)
    }

    @Test
    fun flagOnWithoutAModelIsTheRegexPath() = runTest {
        val extractor = LocalModelMemoryFactExtractor(model = null, config = enabled)

        REGRESSION_MESSAGES.forEach { message ->
            val extraction = extractor.extract(message)
            assertEquals(MemoryFactSuggestionDetector.detect(message), extraction.suggestions)
            assertEquals(MemoryTaskPath.Heuristic, extraction.path)
        }
    }

    @Test
    fun groundedModelFactsFollowTheRegexFacts() = runTest {
        val message = "我对花生过敏，我是一名小学老师，我每天早上六点半起床"
        val model = FakeModel { _, _ ->
            facts(
                "Allergy" to "我对花生过敏",
                "Occupation" to "我是一名小学老师",
                "Routine" to "我每天早上六点半起床",
            )
        }

        val extraction = LocalModelMemoryFactExtractor(model, enabled).extract(message)

        assertEquals(MemoryTaskPath.Model, extraction.path)
        assertEquals(
            listOf("我对花生过敏", "我是一名小学老师", "我每天早上六点半起床"),
            extraction.suggestions.map { it.fact },
        )
        assertEquals(
            listOf(MemoryFactSuggestionKind.Allergy, MemoryFactSuggestionKind.Occupation, MemoryFactSuggestionKind.Routine),
            extraction.suggestions.map { it.kind },
        )
        assertEquals(MemoryFactSuggestionDetector.detect(message).single(), extraction.suggestions.first())
        assertEquals(3, extraction.proposedFacts)
        assertEquals(0, extraction.droppedFacts)
    }

    @Test
    fun theModelGetsOnlyTheTrimmedMessage() = runTest {
        val model = FakeModel { _, _ -> facts() }

        LocalModelMemoryFactExtractor(model, enabled).extract("  我芒果过敏  ")

        assertEquals(listOf(MEMORY_FACT_EXTRACTION_INSTRUCTION to "Message: 我芒果过敏"), model.requests)
    }

    @Test
    fun storedFactIsTheUsersClauseNotTheModelsSpan() = runTest {
        val model = FakeModel { _, _ -> facts("Allergy" to "芒果过敏") }

        val extraction = LocalModelMemoryFactExtractor(model, enabled).extract("对了，我芒果过敏，帮我挑个水果")

        assertEquals(
            listOf(MemoryFactSuggestionDetector.suggestionOf(MemoryFactSuggestionKind.Allergy, "我芒果过敏")),
            extraction.suggestions,
        )
    }

    @Test
    fun ungroundedFactsAreDroppedAndCounted() = runTest {
        val model = FakeModel { _, _ -> facts("Allergy" to "我对花生过敏", "Allergy" to "我芒果过敏") }

        val extraction = LocalModelMemoryFactExtractor(model, enabled).extract("我芒果过敏")

        assertEquals(MemoryTaskPath.Model, extraction.path)
        assertEquals(listOf("我芒果过敏"), extraction.suggestions.map { it.fact })
        assertEquals(2, extraction.proposedFacts)
        assertEquals(1, extraction.ungroundedFacts)
        assertEquals(1, extraction.droppedFacts)
    }

    @Test
    fun rewordedOrTranslatedFactsAreUngrounded() = runTest {
        val model = FakeModel { _, _ -> facts("Occupation" to "我是护士", "Occupation" to "I am a nurse") }

        val extraction = LocalModelMemoryFactExtractor(model, enabled).extract("I'm a nurse")

        assertTrue(extraction.suggestions.isEmpty())
        assertEquals(2, extraction.ungroundedFacts)
    }

    @Test
    fun perFactGuardsDropGroundedButUnsafeFacts() = runTest {
        listOf(
            Triple("我同事对猫过敏", "Allergy", "我同事对猫过敏"),
            Triple("my colleague is allergic to cats", "Allergy", "my colleague is allergic to cats"),
            Triple("我不住在上海", "HomeLocation", "我不住在上海"),
            Triple("如果我住在上海就好了", "HomeLocation", "我住在上海"),
            Triple("他说：我住在杭州", "HomeLocation", "我住在杭州"),
            Triple("她写了“我住在杭州”这句话", "HomeLocation", "我住在杭州"),
            Triple("我以前住在杭州", "HomeLocation", "我以前住在杭州"),
            Triple("我现在住在酒店", "HomeLocation", "我现在住在酒店"),
            Triple("I used to live in Paris", "HomeLocation", "I used to live in Paris"),
            Triple("我想吃火锅", "Preference", "我想吃火锅"),
            Triple("我住在杭州", "Allergy", "我住在杭州"),
            Triple("我住在杭州，在西湖边", "HomeLocation", "我住在杭州，在西湖边"),
            Triple("我住在杭州", "Hometown", "我住在杭州"),
            Triple("我不是医生", "Occupation", "我不是医生"),
            Triple("周末喜欢爬山", "Preference", "周末喜欢爬山"),
        ).forEach { (message, kind, fact) ->
            val model = FakeModel { _, _ -> facts(kind to fact) }

            val extraction = LocalModelMemoryFactExtractor(model, enabled).extract(message)

            assertEquals("path for '$message'", MemoryTaskPath.Model, extraction.path)
            assertTrue("model fact kept for '$message'", extraction.modelSuggestions.isEmpty())
            assertEquals("suggestions for '$message'", MemoryFactSuggestionDetector.detect(message), extraction.suggestions)
            assertEquals("dropped for '$message'", 1, extraction.droppedFacts)
        }
    }

    @Test
    fun everyBrokenAnswerFallsBackToTheRegexResult() = runTest {
        val message = "我对花生过敏，我是一名小学老师"
        val regex = MemoryFactSuggestionDetector.detect(message)
        val cases = listOf(
            FakeModel { _, _ -> null } to LocalMemoryModelFallback.EngineUnavailable,
            FakeModel { _, _ -> throw IllegalStateException("engine crashed") } to LocalMemoryModelFallback.EngineFailed,
            FakeModel { _, _ -> throw CancellationException("engine stopped") } to LocalMemoryModelFallback.EngineFailed,
            FakeModel { _, _ -> "   " } to LocalMemoryModelFallback.EmptyOutput,
            FakeModel { _, _ -> "<think>先想一想</think>\n```json\n```" } to LocalMemoryModelFallback.EmptyOutput,
            FakeModel { _, _ -> "我是一名小学老师" } to LocalMemoryModelFallback.Unparseable,
            FakeModel { _, _ -> """{"facts":"我是一名小学老师"}""" } to LocalMemoryModelFallback.Unparseable,
            FakeModel { _, _ -> """{"result":[]}""" } to LocalMemoryModelFallback.Unparseable,
            FakeModel { _, _ -> """{"facts":[{"kind":"Occupation"}]}""" } to LocalMemoryModelFallback.Unparseable,
            FakeModel { _, _ -> """{"facts":[{"kind":"Occupation","fact":""}]}""" } to LocalMemoryModelFallback.Unparseable,
            FakeModel { _, _ -> """{"facts":["我是一名小学老师"]}""" } to LocalMemoryModelFallback.Unparseable,
            FakeModel { _, _ -> """{"facts":[{"kind":"Occupation","fact":"我是一名小学老师"}""" } to LocalMemoryModelFallback.Unparseable,
        )
        cases.forEachIndexed { index, (model, reason) ->
            val extraction = LocalModelMemoryFactExtractor(model, enabled).extract(message)

            assertEquals("path of case $index", MemoryTaskPath.HeuristicFallback, extraction.path)
            assertEquals("reason of case $index", reason, extraction.fallback)
            assertEquals(regex, extraction.suggestions)
            assertTrue(extraction.modelSuggestions.isEmpty())
        }
    }

    @Test
    fun slowModelTimesOutToTheRegexResult() = runTest {
        val model = FakeModel { _, _ ->
            delay(60_000)
            facts("Occupation" to "我是一名小学老师")
        }
        val config = LocalModelMemoryExtractionConfig(enabled = true, timeoutMillis = 1_000)

        val extraction = LocalModelMemoryFactExtractor(model, config).extract("我对花生过敏，我是一名小学老师")

        assertEquals(MemoryTaskPath.HeuristicFallback, extraction.path)
        assertEquals(LocalMemoryModelFallback.Timeout, extraction.fallback)
        assertEquals(listOf("我对花生过敏"), extraction.suggestions.map { it.fact })
    }

    @Test
    fun callerCancellationIsNotSwallowed() = runTest {
        val model = FakeModel { _, _ -> awaitCancellation() }
        var result: MemoryFactExtraction? = null
        val job = launch { result = LocalModelMemoryFactExtractor(model, enabled).extract("我芒果过敏") }
        runCurrent()

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertNull(result)
    }

    @Test
    fun thinkingFencesAndTrailingTextAroundTheJsonAreIgnored() = runTest {
        val model = FakeModel { _, _ ->
            "<think>\n\n</think>\n```json\n" + facts("Allergy" to "芒果过敏") + "\n```\nNote: {not json}"
        }

        val extraction = LocalModelMemoryFactExtractor(model, enabled).extract("我芒果过敏")

        assertEquals(MemoryTaskPath.Model, extraction.path)
        assertEquals(listOf("我芒果过敏"), extraction.suggestions.map { it.fact })
    }

    @Test
    fun nonCandidateMessagesNeverReachTheModel() = runTest {
        val model = FakeModel { _, _ -> error("the model must not be called") }
        val extractor = LocalModelMemoryFactExtractor(model, enabled)

        listOf("", "   ", "记住我对花生过敏", "忘记我对花生过敏", "remember that I live in Paris", "我" + "很".repeat(200)).forEach { message ->
            val extraction = extractor.extract(message)
            assertEquals(MemoryTaskPath.Heuristic, extraction.path)
            assertTrue(extraction.suggestions.isEmpty())
        }
        assertEquals(0, model.calls)
    }

    @Test
    fun addedModelFactsAreCappedAndNeverRepeatARegexFact() = runTest {
        val message = "我对花生过敏，我是一名小学老师，我每天早上六点半起床"
        val model = FakeModel { _, _ ->
            facts(
                "Allergy" to "花生过敏",
                "Occupation" to "我是一名小学老师",
                "Routine" to "我每天早上六点半起床",
            )
        }
        val config = LocalModelMemoryExtractionConfig(enabled = true, maxFacts = 1)

        val extraction = LocalModelMemoryFactExtractor(model, config).extract(message)

        assertEquals(listOf("我对花生过敏", "我是一名小学老师"), extraction.suggestions.map { it.fact })
        assertEquals(3, extraction.modelSuggestions.size)
    }

    @Test
    fun groundingFoldsWidthCaseSpacingAndApostrophesOnly() {
        assertTrue(isGroundedIn("I’m allergic to Shellfish.", "i'm allergic to shellfish"))
        assertTrue(isGroundedIn("我 住在 杭州。", "我住在杭州。"))
        assertTrue(isGroundedIn("我的电话是１３８１２３４５６７８", "我的电话是13812345678"))
        assertFalse(isGroundedIn("我住在杭州", "我住杭州"))
        assertFalse(isGroundedIn("I'm a nurse", "I am a nurse"))
        assertFalse(isGroundedIn("我住在杭州", "。"))
    }

    @Test
    fun ungroundedNumbersAreTheDigitRunsMissingFromTheSource() {
        val source = "已锁定G102，08:00发车，订单号K2048，二等座553元"

        assertTrue(ungroundedNumbers("• G102 08:00发车，订单K2048，553元", source).isEmpty())
        assertTrue(ungroundedNumbers("• 订单Ｋ２０４８", source).isEmpty())
        assertEquals(listOf("09", "2049"), ungroundedNumbers("• 09:00发车，订单K2049", source))
        assertTrue(ungroundedNumbers("• 已锁定车票", source).isEmpty())
    }

    private class FakeModel(private val answer: suspend (String, String) -> String?) : LocalMemoryModel {
        val requests = mutableListOf<Pair<String, String>>()
        val calls: Int get() = requests.size

        override suspend fun generate(systemInstruction: String, prompt: String): String? {
            requests += systemInstruction to prompt
            return answer(systemInstruction, prompt)
        }
    }

    private companion object {
        fun facts(vararg items: Pair<String, String>): String =
            JSONObject()
                .put("facts", JSONArray(items.map { (kind, fact) -> JSONObject().put("kind", kind).put("fact", fact) }))
                .toString()

        val REGRESSION_MESSAGES = listOf(
            "我对花生过敏",
            "对了，我对花生过敏，帮我推荐几家餐厅",
            "我女儿对牛奶过敏。",
            "我家在北京市朝阳区望京",
            "我老婆的电话是138-1234-5678",
            "I'm allergic to shellfish.",
            "my wife's name is Emily",
            "我今天不吃辣",
            "我朋友对花生过敏",
            "记住我对花生过敏",
            "我是一名小学老师",
            "",
        )
    }
}
