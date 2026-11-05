package com.bytedance.zgx.solin.runtime

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.GenerationParameters
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
    fun punctuationOrWhitespaceOnlyPhraseLoopsAreIgnored() {
        listOf("。！".repeat(12), " \n".repeat(12), "\t ".repeat(12)).forEach { chunk ->
            val decision = guard.evaluate(
                accumulatedText = "正常开头。",
                latestChunk = chunk,
                runtimeKind = GenerationRuntimeKind.Local,
                modelId = "chat-e2b",
                backend = BackendChoice.CPU,
                parameters = GenerationParameters(),
            )

            assertEquals(GenerationQualityDecision.Continue, decision)
        }
    }

    @Test
    fun phraseMixingPunctuationAndLetterStillCountsAsLoop() {
        val decision = guard.evaluate(
            accumulatedText = "正常开头",
            latestChunk = "。好".repeat(8),
            runtimeKind = GenerationRuntimeKind.Local,
            modelId = "chat-e2b",
            backend = BackendChoice.CPU,
            parameters = GenerationParameters(),
        )

        val report = (decision as GenerationQualityDecision.StopAndKeepPrefix).report
        assertEquals("short_phrase_loop>=8", report.triggeredRule)
        assertEquals("正常开头", report.safePrefix)
    }

    @Test
    fun phraseLoopFarIntoLongReplyKeepsExactPrefix() {
        val prefix = (0 until 2500).joinToString(separator = "") { index -> "${'a' + index % 26}${index % 7}" }
        val longGuard = ModelOutputQualityGuard(defaultMaxOutputChars = 100_000)
        val beforeLastChar = longGuard.evaluate(
            accumulatedText = prefix,
            latestChunk = "循环".repeat(7) + "循",
            runtimeKind = GenerationRuntimeKind.Local,
            modelId = "chat-e2b",
            backend = BackendChoice.CPU,
            parameters = GenerationParameters(),
        )
        assertEquals(GenerationQualityDecision.Continue, beforeLastChar)

        val stopped = longGuard.evaluate(
            accumulatedText = prefix + "循环".repeat(7) + "循",
            latestChunk = "环",
            runtimeKind = GenerationRuntimeKind.Local,
            modelId = "chat-e2b",
            backend = BackendChoice.CPU,
            parameters = GenerationParameters(),
        )
        val report = (stopped as GenerationQualityDecision.StopAndKeepPrefix).report
        assertEquals("short_phrase_loop>=8", report.triggeredRule)
        assertEquals(prefix, report.safePrefix)
        assertEquals(prefix.length + 16, report.rawOutputLength)
    }

    @Test
    fun evaluateStreamedTextMatchesPrefixPlusChunk() {
        val cases = listOf(
            "前面是正常回答。" to "好".repeat(32),
            "正常开头。" to "光芒万丈".repeat(8),
            "正常回答，没有问题。".repeat(3) to "\uFFFD\uFFFD\uFFFD\uFFFD",
            "正常回答" to "，继续说明。",
        )
        cases.forEach { (accumulated, chunk) ->
            val viaChunk = guard.evaluate(
                accumulatedText = accumulated,
                latestChunk = chunk,
                runtimeKind = GenerationRuntimeKind.Remote,
                modelId = "remote",
                backend = null,
                parameters = GenerationParameters(),
            )
            val viaText = guard.evaluateStreamedText(
                candidate = accumulated + chunk,
                runtimeKind = GenerationRuntimeKind.Remote,
                modelId = "remote",
                backend = null,
                parameters = GenerationParameters(),
            )

            assertEquals(viaChunk, viaText)
        }
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
