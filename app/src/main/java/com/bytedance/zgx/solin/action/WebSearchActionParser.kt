package com.bytedance.zgx.solin.action

/*
 * Rule parser for explicit web search and weather lookups.
 *
 * Moved verbatim out of BasicDraftActionParsers.kt.
 */

internal object WebSearchActionParser {
    private val englishPattern =
        Regex("""\b(web\s+search|search\s+the\s+web|search\s+online|look\s+up|google|bing)\b""", RegexOption.IGNORE_CASE)
    private val discussionPrefixPattern =
        Regex("""^\s*(what\s+is|what\s+does|explain|how\s+do\s+i|how\s+to)\b""", RegexOption.IGNORE_CASE)
    private val negativeEnglishPattern =
        Regex("""\b(do\s+not|don't|dont)\s+(?:search|look\s+up|google|bing)\b""", RegexOption.IGNORE_CASE)
    private val cjkAsciiBoundaryPattern =
        Regex("""(?<=[\u4E00-\u9FFF])(?=[A-Za-z0-9])|(?<=[A-Za-z0-9])(?=[\u4E00-\u9FFF])""")
    private val searchQueryPunctuationPattern = Regex("""[，。！？、；;:：,.?()（）\[\]{}"'“”]+""")
    private val chineseSearchQuestionNoisePattern =
        Regex("""(搜索一下|搜一下|查一下|看一下|帮我看看|帮我查查|帮我|帮忙|麻烦|请问|请|给我|告诉我|你能不能|能不能|你能|可以|分别是什么|是什么|有哪些|怎么样|如何|多少|一下|分别|吗|呢)""")
    private val englishSearchQuestionNoisePattern =
        Regex("""(?i)\b(?:please|can\s+you|could\s+you|search\s+for|search|look\s+up|google|bing|tell\s+me|what\s+is|what\s+are|what's|which|who\s+is)\b""")

    fun matches(input: String): Boolean {
        if (input.looksLikeWebSearchNonAction()) return false
        val normalized = input.lowercase()
        val hasExplicitChineseTrigger = listOf(
            "网页搜索",
            "网络搜索",
            "互联网搜索",
            "上网搜",
            "网上搜",
            "上网查",
            "网上查",
            "网页查",
            "网络查",
            "搜索一下",
            "搜一下",
            "搜一搜",
            "百度一下",
        ).any { it in input }
        val hasEnglishTrigger = englishPattern.containsMatchIn(normalized)
        val hasWeatherTrigger = input.looksLikeWeatherInfoQuery()
        if (!hasExplicitChineseTrigger && !hasEnglishTrigger && !hasWeatherTrigger) return false
        return if (hasWeatherTrigger && !hasExplicitChineseTrigger && !hasEnglishTrigger) {
            strippedWeatherQuery(input).isMeaningfulSearchQuery()
        } else {
            strippedWebSearchQuery(input).isMeaningfulSearchQuery()
        }
    }

    fun draft(input: String): ActionDraft {
        val query = cleanedWebSearchQuery(input)
        return ActionDraft(
            functionName = MobileActionFunctions.WEB_SEARCH,
            title = "Web 搜索",
            summary = "将使用 Web 搜索工具查询并整理结果：$query",
            parameters = mapOf("query" to query),
            requiresConfirmation = false,
        )
    }

    private fun cleanedWebSearchQuery(input: String): String {
        val stripped = strippedWebSearchQuery(input)
        val keywordQuery = stripped.keywordizedWebSearchQuery()
        return keywordQuery.ifBlank { cleanedObject(input) }
    }

    private fun strippedWebSearchQuery(input: String): String {
        val cleaned = cleanedObject(input)
        return cleaned
            .replace(Regex("""^百度一下\s*[:：]?\s*"""), "")
            .replace(Regex("""^(网页|网络|互联网|上网|网上)?\s*(搜索|搜|查)(一下|一搜|一查)?\s*[:：]?\s*"""), "")
            .replace(
                Regex(
                    """^(web\s+search|search\s+the\s+web|search\s+online|look\s+up|google|bing)\s+(for\s+)?""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .trim()
    }

    private fun String.isMeaningfulSearchQuery(): Boolean =
        isNotBlank() && this !in setOf("是什么", "什么意思", "怎么实现", "如何实现", "怎么用")

    private fun String.looksLikeWeatherInfoQuery(): Boolean {
        val normalized = lowercase()
        return listOf("天气", "气温", "温度", "降雨", "下雨", "预报").any { it in this } ||
            Regex("""\b(?:weather|temperature|rain|forecast)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(normalized)
    }

    private fun strippedWeatherQuery(input: String): String =
        cleanedObject(input)
            .replace(Regex("""(?i)\b(?:what(?:'s| is)?|how(?:'s| is)?|tell me|check|look up)\b"""), " ")
            .replace(Regex("""(?i)\b(?:weather|temperature|rain|forecast)\b"""), " ")
            .replace(Regex("""(?i)\b(?:in|for|today|now|currently)\b"""), " ")
            .replace(Regex("""(天气|气温|温度|降雨|下雨|预报|怎么样|如何|多少|现在|今天|查询|查一下|搜一下|帮我|请|一下)"""), " ")
            .replace(Regex("""[，。！？、:：?]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun String.keywordizedWebSearchQuery(): String =
        replace(cjkAsciiBoundaryPattern, " ")
            .replace(searchQueryPunctuationPattern, " ")
            .replace(englishSearchQuestionNoisePattern, " ")
            .replace(chineseSearchQuestionNoisePattern, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun String.looksLikeWebSearchNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            "不要搜索" in this ||
            "别搜索" in this ||
            "不要搜" in this ||
            "别搜" in this ||
            "不要查" in this ||
            "别查" in this ||
            "搜索功能" in this ||
            "搜索框" in this ||
            "搜索页面" in this ||
            "网页搜索是什么" in this ||
            ("错误原因" in this && "吗" in this) ||
            negativeEnglishPattern.containsMatchIn(normalized) ||
            (discussionPrefixPattern.containsMatchIn(normalized) && englishPattern.containsMatchIn(normalized))
    }
}
