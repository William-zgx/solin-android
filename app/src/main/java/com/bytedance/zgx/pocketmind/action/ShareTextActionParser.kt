package com.bytedance.zgx.pocketmind.action

internal object ShareTextActionParser {
    private val englishShareTextPattern =
        Regex("""\bshare\s+(this\s+)?(text|message|content)\b""", RegexOption.IGNORE_CASE)
    private val negativeChineseShareTextPattern =
        Regex("""^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:我\s*)?(?:不想|不需要|不用|不必|不要|别|请勿|请不要|先别|暂时别|不)\s*(?:把|将)?\s*(?:这段|以下)?\s*(?:文字|内容)?\s*(?:分享|发出去)""")
    private val negativeEnglishShareTextPattern =
        Regex("""^\s*(?:(?:please\s+)?(?:do\s+not|don't|dont|never)\s+share|i\s+(?:do\s+not|don't|dont)\s+want\s+to\s+share)\b""")
    private val questionChineseShareTextPattern =
        Regex("""^\s*(?:请问|问一下|如何|怎么|怎样|为什么|解释|说明|介绍).{0,40}分享""")
    private val documentationChineseShareTextPattern =
        Regex("""分享.{0,40}(?:怎么实现|如何实现|实现|API|api|接口|权限|文档|代码|功能|教程)""")
    private val questionEnglishShareTextPattern =
        Regex("""^\s*(?:how\s+(?:do|can|to)|what\s+is|explain)\b.*\bshare\s+(?:this\s+)?(?:text|message|content)\b""")
    private val documentationEnglishShareTextPattern =
        Regex("""\bshare\s+(?:this\s+)?(?:text|message|content)\b.*\b(?:api|sdk|docs?|documentation|implement|implementation|example|code)\b""")

    fun matches(input: String): Boolean {
        val normalized = input.lowercase()
        val commandHead = input.commandHead()
        if (commandHead.looksLikeShareTextNonAction(commandHead.lowercase())) return false
        return listOf("分享这段", "分享以下", "分享文字", "分享内容", "分享到", "分享出去")
            .any { it in input } ||
            englishShareTextPattern.containsMatchIn(normalized)
    }

    fun parameters(input: String): Map<String, String> =
        mapOf("text" to shareText(input))

    fun draft(input: String): ActionDraft =
        ActionDraft(
            functionName = MobileActionFunctions.SHARE_TEXT,
            title = "系统分享",
            summary = "将打开系统分享面板并填入文本。",
            parameters = parameters(input),
            requiresConfirmation = true,
        )

    private fun shareText(input: String): String {
        val cleaned = cleanedObject(input)
            .replace(Regex("""^分享(这段|以下)?\s*(文字|内容)?\s*(出去|一下)?\s*[:：]?\s*"""), "")
            .replace(Regex("""^(把|将)?\s*(这段|以下)?\s*(文字|内容)?\s*分享(出去|一下)?\s*[:：]?\s*"""), "")
            .replace(Regex("""(?i)^share\s+(this\s+)?(text|message|content)?\s*[:：]?\s*"""), "")
            .trim()
        return cleaned.ifBlank { cleanedObject(input) }
    }

    private fun cleanedObject(input: String): String =
        input.trim()
            .removePrefix("请")
            .removePrefix("帮我")
            .trim()
            .ifBlank { input.trim() }

    private fun String.commandHead(): String {
        val delimiterIndex = indexOfAny(charArrayOf(':', '：'))
        return if (delimiterIndex >= 0) substring(0, delimiterIndex) else this
    }

    private fun String.looksLikeShareTextNonAction(normalized: String): Boolean =
        negativeChineseShareTextPattern.containsMatchIn(this) ||
            negativeEnglishShareTextPattern.containsMatchIn(normalized) ||
            questionChineseShareTextPattern.containsMatchIn(this) ||
            documentationChineseShareTextPattern.containsMatchIn(this) ||
            questionEnglishShareTextPattern.containsMatchIn(normalized) ||
            documentationEnglishShareTextPattern.containsMatchIn(normalized)
}
