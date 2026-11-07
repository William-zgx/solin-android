package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessageKind
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.memory.LocalMemoryModel
import com.bytedance.zgx.solin.memory.LocalMemoryModelFallback
import com.bytedance.zgx.solin.memory.MemoryTaskPath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelCompactionSummaryTest {
    private val estimator: (List<ChatMessage>) -> Int = { msgs -> msgs.sumOf { m -> (m.text.length / 4) + 1 } }
    private val modelOn = CompactionConfig(preserveRecentTurns = 2, modelSummaryEnabled = true)
    private val modelOff = modelOn.copy(modelSummaryEnabled = false)

    private val users = listOf("帮我查明天去上海的高铁", "订最早那班", "好的，谢谢")
    private val history = listOf(
        user(users[0]),
        ChatMessage(
            role = MessageRole.Assistant,
            text = "好的，正在查询。" + "说明".repeat(80) + "可选车次G102，08:00发车，二等座553元。",
            privacy = MessagePrivacy.LocalOnly,
        ),
        ChatMessage(
            role = MessageRole.Assistant,
            text = "train_search: " + "结果".repeat(100) + "G102 08:00 553元；G104 09:30 553元",
            kind = MessageKind.ToolResult,
        ),
        user(users[1]),
        assistant("处理".repeat(120) + "已锁定G102，订单号K2048。"),
        user(users[2]),
        assistant("确认".repeat(120) + "已发送确认短信。"),
        user("顺便提醒我带身份证"),
        assistant("好的，已设置提醒。"),
        user("还有别的吗"),
        assistant("没有了。"),
    )
    private val budget = (estimator(history) * 0.75 / 0.85).toInt()

    @Test
    fun modelSummaryIsOffByDefault() {
        assertFalse(CompactionConfig().modelSummaryEnabled)
    }

    @Test
    fun flagOffNeverCallsTheModelAndMatchesTheHeuristicResult() = runTest {
        val model = FakeModel { _, _ -> error("the model must not be called") }

        val withModel = DefaultContextCompactor(modelOff, model).compact(history, budget, estimator)
        val heuristic = heuristic()

        assertEquals(0, model.calls)
        assertSameResult(heuristic, withModel)
        assertEquals(MemoryTaskPath.Heuristic, withModel.summaryPath)
        assertNull(withModel.summaryFallback)
    }

    @Test
    fun flagOnWithoutAModelMatchesTheHeuristicResult() = runTest {
        val result = DefaultContextCompactor(modelOn).compact(history, budget, estimator)

        assertSameResult(heuristic(), result)
        assertEquals(MemoryTaskPath.Heuristic, result.summaryPath)
    }

    @Test
    fun groundedModelSummaryReplacesTheHeuristicSummaryInTheSameSplit() = runTest {
        val summary = "• 已锁定G102，08:00发车，订单号K2048\n• 已发送确认短信"
        val model = FakeModel { _, _ -> summary }

        val result = DefaultContextCompactor(modelOn, model).compact(history, budget, estimator)
        val heuristic = heuristic()

        assertEquals(MemoryTaskPath.Model, result.summaryPath)
        assertNull(result.summaryFallback)
        assertEquals("Previous conversation summary: $summary", result.messages.first().text)
        assertEquals(MessageRole.Assistant, result.messages.first().role)
        assertEquals(heuristic.messages.drop(1), result.messages.drop(1))
        assertEquals(heuristic.compactionCount, result.compactionCount)
        assertEquals(heuristic.triggerReason, result.triggerReason)
        assertEquals(estimator(result.messages), result.tokensAfter)
        history.filter { it.role == MessageRole.User }.forEach { message ->
            assertTrue("user message kept: ${message.text}", result.messages.any { it === message })
        }
    }

    @Test
    fun modelSummaryIsLocalOnlyWhenAnySummarizedMessageIs() = runTest {
        val model = FakeModel { _, _ -> "• 已锁定G102" }

        val summary = DefaultContextCompactor(modelOn, model).compact(history, budget, estimator).messages.first()

        assertEquals(MessagePrivacy.LocalOnly, summary.privacy)
    }

    @Test
    fun theModelSeesOnlyTheSummarizedMessagesNeverUserText() = runTest {
        val model = FakeModel { _, _ -> "• 已锁定G102" }

        DefaultContextCompactor(modelOn, model).compact(history, budget, estimator)

        val prompt = model.requests.single().second
        history.filter { it.role == MessageRole.User }.forEach { message ->
            assertFalse("user text in prompt: ${message.text}", message.text in prompt)
        }
        assertTrue(prompt.lines().any { it.startsWith("assistant: 好的，正在查询。") })
        assertTrue(prompt.lines().any { it.startsWith("tool result: train_search: ") })
        assertFalse("没有了。" in prompt)
    }

    @Test
    fun everyFailureKeepsTheHeuristicResult() = runTest {
        val heuristic = heuristic()
        listOf(
            FakeModel { _, _ -> null } to LocalMemoryModelFallback.EngineUnavailable,
            FakeModel { _, _ -> throw IllegalStateException("engine crashed") } to LocalMemoryModelFallback.EngineFailed,
            FakeModel { _, _ -> "  " } to LocalMemoryModelFallback.EmptyOutput,
            FakeModel { _, _ -> "• 已锁定G102，订单号K9999" } to LocalMemoryModelFallback.Ungrounded,
            FakeModel { _, _ -> "好".repeat(modelOn.maxSummaryLengthChars + 1) } to LocalMemoryModelFallback.OverLength,
            FakeModel { _, _ -> "好".repeat(1_500) } to LocalMemoryModelFallback.OverLength,
            FakeModel { _, _ ->
                delay(60_000)
                "• 已锁定G102"
            } to LocalMemoryModelFallback.Timeout,
        ).forEachIndexed { index, (model, reason) ->
            val result = DefaultContextCompactor(modelOn, model).compact(history, budget, estimator)

            assertEquals("calls of case $index", 1, model.calls)
            assertEquals("path of case $index", MemoryTaskPath.HeuristicFallback, result.summaryPath)
            assertEquals("reason of case $index", reason, result.summaryFallback)
            assertSameResult(heuristic, result)
        }
    }

    @Test
    fun oversizedInputIsNotSentToTheModel() = runTest {
        val model = FakeModel { _, _ -> error("the model must not be called") }
        val config = modelOn.copy(modelSummaryMaxInputChars = 150)

        val result = DefaultContextCompactor(config, model).compact(history, budget, estimator)

        assertEquals(0, model.calls)
        assertEquals(LocalMemoryModelFallback.InputTooLarge, result.summaryFallback)
        assertSameResult(heuristic(), result)
    }

    @Test
    fun underThresholdAndDropFallbackNeverAskTheModel() = runTest {
        val model = FakeModel { _, _ -> error("the model must not be called") }
        val compactor = DefaultContextCompactor(modelOn, model)

        val untouched = compactor.compact(history, tokenBudget = 100_000, estimatedTokens = estimator)
        val dropped = compactor.compact(history, tokenBudget = 0, estimatedTokens = estimator)

        assertSame(history, untouched.messages)
        assertEquals(CompactionTrigger.OverBudget, dropped.triggerReason)
        assertEquals(MemoryTaskPath.Heuristic, dropped.summaryPath)
        assertEquals(0, model.calls)
    }

    @Test
    fun callerCancellationIsNotSwallowed() = runTest {
        val model = FakeModel { _, _ -> awaitCancellation() }
        var result: CompactionResult? = null
        val job = launch { result = DefaultContextCompactor(modelOn, model).compact(history, budget, estimator) }
        runCurrent()

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertNull(result)
    }

    private suspend fun heuristic(): CompactionResult {
        val result = DefaultContextCompactor(modelOff).compact(history, budget, estimator)
        assertTrue("fixture must compact by summarizing", result.messages.first().text.startsWith("Previous conversation summary: "))
        return result
    }

    /** Same messages apart from the fresh id of the synthetic summary message, and same counters. */
    private fun assertSameResult(expected: CompactionResult, actual: CompactionResult) {
        assertEquals(expected.messages.map { it.shape() }, actual.messages.map { it.shape() })
        expected.messages.zip(actual.messages).filter { (e, _) -> e.role == MessageRole.User }.forEach { (e, a) ->
            assertSame(e, a)
        }
        assertEquals(expected.compactionCount, actual.compactionCount)
        assertEquals(expected.tokensBefore, actual.tokensBefore)
        assertEquals(expected.tokensAfter, actual.tokensAfter)
        assertEquals(expected.triggerReason, actual.triggerReason)
    }

    private fun ChatMessage.shape() = listOf(role, text, privacy, kind)

    private class FakeModel(private val answer: suspend (String, String) -> String?) : LocalMemoryModel {
        val requests = mutableListOf<Pair<String, String>>()
        val calls: Int get() = requests.size

        override suspend fun generate(systemInstruction: String, prompt: String): String? {
            requests += systemInstruction to prompt
            return answer(systemInstruction, prompt)
        }
    }

    private companion object {
        fun user(text: String) = ChatMessage(role = MessageRole.User, text = text)
        fun assistant(text: String) = ChatMessage(role = MessageRole.Assistant, text = text)
    }
}
