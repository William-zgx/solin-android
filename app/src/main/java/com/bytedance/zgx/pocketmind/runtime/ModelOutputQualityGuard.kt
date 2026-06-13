package com.bytedance.zgx.pocketmind.runtime

import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.GenerationParameters
import com.bytedance.zgx.pocketmind.LocalModelTokenLimits

enum class GenerationRuntimeKind {
    Local,
    Remote,
}

enum class GenerationQualityIssue {
    RepetitionLoop,
    MalformedOutput,
    EmptyOutput,
    FormatViolation,
    LengthExceeded,
    UnsafeOrPolicyViolation,
}

enum class GenerationQualitySeverity {
    Warning,
    Critical,
}

data class GenerationQualityReport(
    val issue: GenerationQualityIssue,
    val severity: GenerationQualitySeverity,
    val triggeredRule: String,
    val safePrefix: String,
    val rawOutputLength: Int,
    val visibleNotice: String,
    val modelId: String?,
    val backend: BackendChoice?,
    val runtimeKind: GenerationRuntimeKind,
)

sealed class GenerationQualityDecision {
    data object Continue : GenerationQualityDecision()

    data class StopAndKeepPrefix(
        val report: GenerationQualityReport,
    ) : GenerationQualityDecision()

    data class StopAndReplaceWithNotice(
        val report: GenerationQualityReport,
    ) : GenerationQualityDecision()

    data class RetrySuggested(
        val report: GenerationQualityReport,
    ) : GenerationQualityDecision()

    data class FailClosed(
        val report: GenerationQualityReport,
    ) : GenerationQualityDecision()
}

class ModelOutputQualityGuard(
    private val repeatedCharacterThreshold: Int = DEFAULT_REPEATED_CHARACTER_THRESHOLD,
    private val repeatedPhraseThreshold: Int = DEFAULT_REPEATED_PHRASE_THRESHOLD,
    private val phraseLengthRange: IntRange = DEFAULT_PHRASE_LENGTH_RANGE,
    private val defaultMaxOutputChars: Int = LocalModelTokenLimits.OUTPUT_TOKEN_RESERVE * 4,
) {
    fun evaluate(
        accumulatedText: String,
        latestChunk: String,
        runtimeKind: GenerationRuntimeKind,
        modelId: String?,
        backend: BackendChoice?,
        parameters: GenerationParameters,
        maxOutputChars: Int = defaultMaxOutputChars,
    ): GenerationQualityDecision {
        if (latestChunk.isEmpty()) return GenerationQualityDecision.Continue
        val candidate = accumulatedText + latestChunk
        findRepeatedCharacterSpan(candidate)?.let { span ->
            return stopFor(
                issue = GenerationQualityIssue.RepetitionLoop,
                severity = GenerationQualitySeverity.Critical,
                triggeredRule = "same_character_run>=$repeatedCharacterThreshold",
                candidate = candidate,
                safePrefixEnd = span.first,
                runtimeKind = runtimeKind,
                modelId = modelId,
                backend = backend,
                notice = repetitionNotice(parameters),
            )
        }
        findRepeatedPhraseSpan(candidate)?.let { span ->
            return stopFor(
                issue = GenerationQualityIssue.RepetitionLoop,
                severity = GenerationQualitySeverity.Critical,
                triggeredRule = "short_phrase_loop>=$repeatedPhraseThreshold",
                candidate = candidate,
                safePrefixEnd = span.first,
                runtimeKind = runtimeKind,
                modelId = modelId,
                backend = backend,
                notice = repetitionNotice(parameters),
            )
        }
        findMalformedSpan(candidate)?.let { span ->
            return stopFor(
                issue = GenerationQualityIssue.MalformedOutput,
                severity = GenerationQualitySeverity.Critical,
                triggeredRule = "malformed_control_or_replacement_density",
                candidate = candidate,
                safePrefixEnd = span.first,
                runtimeKind = runtimeKind,
                modelId = modelId,
                backend = backend,
                notice = "模型输出出现异常字符，已自动停止。可以重新生成，或切换模型后重试。",
            )
        }
        if (candidate.length > maxOutputChars) {
            return stopFor(
                issue = GenerationQualityIssue.LengthExceeded,
                severity = GenerationQualitySeverity.Warning,
                triggeredRule = "output_chars>$maxOutputChars",
                candidate = candidate,
                safePrefixEnd = maxOutputChars.coerceAtMost(candidate.length),
                runtimeKind = runtimeKind,
                modelId = modelId,
                backend = backend,
                notice = "模型输出超过当前长度预算，已自动停止。可以缩小问题范围后重新生成。",
            )
        }
        return GenerationQualityDecision.Continue
    }

    fun evaluateCompleted(
        output: String,
        runtimeKind: GenerationRuntimeKind,
        modelId: String?,
        backend: BackendChoice?,
    ): GenerationQualityDecision {
        if (output.isNotBlank()) return GenerationQualityDecision.Continue
        val report = GenerationQualityReport(
            issue = GenerationQualityIssue.EmptyOutput,
            severity = GenerationQualitySeverity.Warning,
            triggeredRule = "blank_final_output",
            safePrefix = "",
            rawOutputLength = output.length,
            visibleNotice = "模型没有生成内容。可以重新生成，或切换模型后重试。",
            modelId = modelId,
            backend = backend,
            runtimeKind = runtimeKind,
        )
        return GenerationQualityDecision.StopAndReplaceWithNotice(report)
    }

    fun failClosedForFormatViolation(
        summary: String,
        accumulatedText: String,
        runtimeKind: GenerationRuntimeKind,
        modelId: String?,
        backend: BackendChoice?,
    ): GenerationQualityDecision.FailClosed =
        GenerationQualityDecision.FailClosed(
            GenerationQualityReport(
                issue = GenerationQualityIssue.FormatViolation,
                severity = GenerationQualitySeverity.Critical,
                triggeredRule = "tool_protocol_parse_error",
                safePrefix = accumulatedText.trimEnd(),
                rawOutputLength = accumulatedText.length,
                visibleNotice = "模型返回的工具调用格式无效，已停止执行，避免误触发动作。可以重新生成或切换模型后重试。",
                modelId = modelId,
                backend = backend,
                runtimeKind = runtimeKind,
            ),
        )

    private fun stopFor(
        issue: GenerationQualityIssue,
        severity: GenerationQualitySeverity,
        triggeredRule: String,
        candidate: String,
        safePrefixEnd: Int,
        runtimeKind: GenerationRuntimeKind,
        modelId: String?,
        backend: BackendChoice?,
        notice: String,
    ): GenerationQualityDecision {
        val safePrefix = candidate.take(safePrefixEnd.coerceIn(0, candidate.length)).trimEnd()
        val report = GenerationQualityReport(
            issue = issue,
            severity = severity,
            triggeredRule = triggeredRule,
            safePrefix = safePrefix,
            rawOutputLength = candidate.length,
            visibleNotice = notice,
            modelId = modelId,
            backend = backend,
            runtimeKind = runtimeKind,
        )
        return if (safePrefix.isBlank()) {
            GenerationQualityDecision.StopAndReplaceWithNotice(report)
        } else {
            GenerationQualityDecision.StopAndKeepPrefix(report)
        }
    }

    private fun findRepeatedCharacterSpan(text: String): IntRange? {
        if (text.length < repeatedCharacterThreshold) return null
        var runStart = 0
        var runChar = text.first()
        var runLength = 1
        for (index in 1 until text.length) {
            val char = text[index]
            if (char == runChar && !char.isWhitespace()) {
                runLength += 1
                if (runLength >= repeatedCharacterThreshold) {
                    return runStart until index + 1
                }
            } else {
                runStart = index
                runChar = char
                runLength = 1
            }
        }
        return null
    }

    private fun findRepeatedPhraseSpan(text: String): IntRange? {
        if (text.length < phraseLengthRange.first * repeatedPhraseThreshold) return null
        val scanStart = (text.length - PHRASE_SCAN_WINDOW_CHARS).coerceAtLeast(0)
        for (start in scanStart until text.length) {
            for (length in phraseLengthRange) {
                val repeatedEnd = start + length * repeatedPhraseThreshold
                if (repeatedEnd > text.length) continue
                val phrase = text.substring(start, start + length)
                if (phrase.isBlank() || phrase.all { it.isPunctuationLike() }) continue
                var count = 1
                var cursor = start + length
                while (cursor + length <= text.length && text.regionMatches(cursor, phrase, 0, length)) {
                    count += 1
                    cursor += length
                    if (count >= repeatedPhraseThreshold) {
                        return start until cursor
                    }
                }
            }
        }
        return null
    }

    private fun findMalformedSpan(text: String): IntRange? {
        if (text.length < MALFORMED_MIN_LENGTH) return null
        val malformedIndex = text.indexOfFirst { char ->
            char == '\uFFFD' || (char.isISOControl() && char !in ALLOWED_CONTROL_CHARS)
        }
        if (malformedIndex < 0) return null
        val malformedCount = text.count { char ->
            char == '\uFFFD' || (char.isISOControl() && char !in ALLOWED_CONTROL_CHARS)
        }
        val density = malformedCount.toDouble() / text.length.toDouble()
        return if (malformedCount >= MALFORMED_ABSOLUTE_THRESHOLD || density >= MALFORMED_DENSITY_THRESHOLD) {
            malformedIndex until text.length
        } else {
            null
        }
    }

    private fun repetitionNotice(
        @Suppress("UNUSED_PARAMETER") parameters: GenerationParameters,
    ): String =
        "模型输出出现重复，已自动停止。可以重新生成，或降低随机性后重试" +
            "（温度 $QUALITY_RECOVERY_TEMPERATURE / Top P $QUALITY_RECOVERY_TOP_P / Top K $QUALITY_RECOVERY_TOP_K）。"

    private fun Char.isPunctuationLike(): Boolean =
        !isLetterOrDigit() && !isWhitespace()

    companion object {
        const val DEFAULT_REPEATED_CHARACTER_THRESHOLD = 32
        const val DEFAULT_REPEATED_PHRASE_THRESHOLD = 8
        val DEFAULT_PHRASE_LENGTH_RANGE: IntRange = 2..8
        const val QUALITY_RECOVERY_TEMPERATURE = 0.2f
        const val QUALITY_RECOVERY_TOP_P = 0.8f
        const val QUALITY_RECOVERY_TOP_K = 20
        private const val PHRASE_SCAN_WINDOW_CHARS = 4096
        private const val MALFORMED_MIN_LENGTH = 24
        private const val MALFORMED_ABSOLUTE_THRESHOLD = 4
        private const val MALFORMED_DENSITY_THRESHOLD = 0.02
        private val ALLOWED_CONTROL_CHARS = setOf('\n', '\r', '\t')
    }
}
