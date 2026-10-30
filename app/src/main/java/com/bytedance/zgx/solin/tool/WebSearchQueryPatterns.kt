package com.bytedance.zgx.solin.tool

/*
 * Query-keywordization helpers shared by the rule parser (action/WebSearchActionParser) and
 * WebSearchProvider. Only the language-neutral pieces are shared; the CJK noise-word lists differ
 * on purpose between the two and stay with their owners.
 */

/** Zero-width boundary between a CJK character and an ASCII letter/digit (either order). */
internal val webSearchCjkAsciiBoundaryRegex =
    Regex("""(?<=[一-鿿])(?=[A-Za-z0-9])|(?<=[A-Za-z0-9])(?=[一-鿿])""")

/** CJK and ASCII punctuation stripped from search keywords. */
internal val webSearchPunctuationRegex = Regex("""[，。！？、；;:：,.?()（）\[\]{}"'“”]+""")

/** English request/question filler stripped from search keywords. */
internal val webSearchEnglishNoiseRegex =
    Regex("""(?i)\b(?:please|can\s+you|could\s+you|search\s+for|search|look\s+up|google|bing|tell\s+me|what\s+is|what\s+are|what's|which|who\s+is)\b""")

private val englishWeatherQuestionRegex =
    Regex("""(?i)\b(?:what(?:'s| is)?|how(?:'s| is)?|tell me|check|look up)\b""")
private val englishWeatherWordRegex = Regex("""(?i)\b(?:weather|temperature|rain|forecast)\b""")
private val englishWeatherConnectorRegex = Regex("""(?i)\b(?:in|for|today|now|currently)\b""")

/** Blanks English weather question words, weather nouns and time/location connectors. */
internal fun String.stripEnglishWeatherFiller(): String =
    replace(englishWeatherQuestionRegex, " ")
        .replace(englishWeatherWordRegex, " ")
        .replace(englishWeatherConnectorRegex, " ")

/**
 * Strips CJK request filler only at the EDGES of a text segment, iteratively, and never inside a
 * word. Substring removal used to turn 目的地 into "目 地", 申请签证 into "申 签证" and break 吗啡;
 * edge-only stripping keeps those words intact while still removing "帮我搜一下…吗".
 *
 * [politePrefixes] (请, 麻烦, 可以, 能不能 …) are only removed when another leading filler follows
 * them, so 请客/麻烦事/可以吃的野菜/能不能带充电宝 stay whole while 请帮我…/麻烦你搜一下… are cleaned.
 */
internal class CjkEdgeFillers(
    leading: Collection<String>,
    trailing: Collection<String>,
    politePrefixes: Collection<String> = DEFAULT_POLITE_PREFIXES,
) {
    private val leadingPhrases = leading.filter { it.isNotEmpty() }.sortedByDescending { it.length }
    private val trailingPhrases = trailing.filter { it.isNotEmpty() }.sortedByDescending { it.length }
    private val politePrefixes = politePrefixes.filter { it.isNotEmpty() }.sortedByDescending { it.length }

    companion object {
        val DEFAULT_POLITE_PREFIXES = listOf("请你", "请", "麻烦你", "麻烦", "可以", "你能不能", "能不能", "你能", "能否")
    }

    fun strip(segment: String): String {
        var current = segment.trim()
        while (true) {
            val next = stripOnce(current).trim()
            if (next == current) return current
            current = next
        }
    }

    private fun stripOnce(text: String): String {
        leadingPhrases.firstOrNull { text.startsWith(it) }?.let { return text.removePrefix(it) }
        politePrefixes.firstOrNull { prefix ->
            text.startsWith(prefix) && text.removePrefix(prefix).trimStart().let { rest ->
                leadingPhrases.any { rest.startsWith(it) } || politePrefixes.any { rest.startsWith(it) }
            }
        }?.let { return text.removePrefix(it) }
        trailingPhrases.firstOrNull { text.endsWith(it) }?.let { return text.removeSuffix(it) }
        return text
    }
}

private val whitespaceRunRegex = Regex("""\s+""")
private val weatherSegmentSeparatorRegex = Regex("""[，。！？、:：?!,]+""")

/**
 * Splits a query into keyword segments (CJK/ASCII boundary, punctuation, whitespace), drops English
 * request filler, edge-strips CJK filler per segment and re-joins with single spaces.
 * [nonFinalTrailing] particles (e.g. 的 before "AI") are stripped only from segments that are
 * followed by another segment, so a final word like 目的 is never cut.
 */
internal fun String.keywordizeSearchQuery(
    fillers: CjkEdgeFillers,
    nonFinalTrailing: Collection<String> = emptyList(),
): String {
    val segments = replace(webSearchCjkAsciiBoundaryRegex, " ")
        .replace(webSearchPunctuationRegex, " ")
        .replace(webSearchEnglishNoiseRegex, " ")
        .split(whitespaceRunRegex)
        .map { segment -> fillers.strip(segment) }
        .filter { segment -> segment.isNotBlank() }
    return segments.mapIndexed { index, segment ->
        if (index == segments.lastIndex) {
            segment
        } else {
            nonFinalTrailing
                .firstOrNull { particle -> segment.length > particle.length && segment.endsWith(particle) }
                ?.let { particle -> segment.removeSuffix(particle) }
                ?: segment
        }
    }.joinToString(" ").trim()
}

/** Edge-strips [fillers] from each punctuation/whitespace-separated segment of a weather query. */
internal fun String.stripWeatherFillerSegments(fillers: CjkEdgeFillers): String =
    replace(weatherSegmentSeparatorRegex, " ")
        .split(whitespaceRunRegex)
        .map { segment -> fillers.strip(segment) }
        .filter { segment -> segment.isNotBlank() }
        .joinToString(" ")
        .trim()
