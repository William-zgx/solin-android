package com.bytedance.zgx.pocketmind.runtime

import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.GenerationParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOutputQualityGuardTest {
    private val guard = ModelOutputQualityGuard()

    @Test
    fun repeatedChineseCharacterStopsAndKeepsSafePrefix() {
        val decision = guard.evaluate(
            accumulatedText = "前面是正常回答。",
            latestChunk = "好".repeat(32),
            runtimeKind = GenerationRuntimeKind.Local,
            modelId = "chat-e2b",
            backend = BackendChoice.CPU,
            parameters = GenerationParameters(),
        )

        val report = (decision as GenerationQualityDecision.StopAndKeepPrefix).report
        assertEquals(GenerationQualityIssue.RepetitionLoop, report.issue)
        assertEquals("前面是正常回答。", report.safePrefix)
        assertTrue(report.visibleNotice.contains("重复"))
    }

    @Test
    fun repeatedZeroCharacterStopsWithoutPersistingTail() {
        val decision = guard.evaluate(
            accumulatedText = "",
            latestChunk = "0".repeat(32),
            runtimeKind = GenerationRuntimeKind.Local,
            modelId = "chat-e2b",
            backend = BackendChoice.CPU,
            parameters = GenerationParameters(),
        )

        val report = (decision as GenerationQualityDecision.StopAndReplaceWithNotice).report
        assertEquals(GenerationQualityIssue.RepetitionLoop, report.issue)
        assertEquals("", report.safePrefix)
    }

    @Test
    fun shortPhraseLoopStops() {
        val decision = guard.evaluate(
            accumulatedText = "正常开头。",
            latestChunk = "光芒万丈".repeat(8),
            runtimeKind = GenerationRuntimeKind.Local,
            modelId = "chat-e2b",
            backend = BackendChoice.CPU,
            parameters = GenerationParameters(),
        )

        val report = (decision as GenerationQualityDecision.StopAndKeepPrefix).report
        assertEquals(GenerationQualityIssue.RepetitionLoop, report.issue)
        assertEquals("short_phrase_loop>=8", report.triggeredRule)
        assertEquals("正常开头。", report.safePrefix)
    }

    @Test
    fun normalChineseParagraphContinues() {
        val text = "这是一个正常的中文回答，包含多个句子，用来确认质量保护不会误杀普通长文。".repeat(12)

        val decision = guard.evaluate(
            accumulatedText = "",
            latestChunk = text,
            runtimeKind = GenerationRuntimeKind.Local,
            modelId = "chat-e2b",
            backend = BackendChoice.CPU,
            parameters = GenerationParameters(),
        )

        assertEquals(GenerationQualityDecision.Continue, decision)
    }

    @Test
    fun emptyFinalOutputShowsQualityNotice() {
        val decision = guard.evaluateCompleted(
            output = "",
            runtimeKind = GenerationRuntimeKind.Remote,
            modelId = "remote",
            backend = null,
        )

        val report = (decision as GenerationQualityDecision.StopAndReplaceWithNotice).report
        assertEquals(GenerationQualityIssue.EmptyOutput, report.issue)
        assertTrue(report.visibleNotice.contains("没有生成内容"))
    }
}
