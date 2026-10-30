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
