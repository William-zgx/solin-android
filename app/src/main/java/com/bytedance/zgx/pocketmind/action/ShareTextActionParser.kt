package com.bytedance.zgx.pocketmind.action

internal object ShareTextActionParser {
    private val englishShareTextPattern =
        Regex("""\bshare\s+(this\s+)?(text|message|content)\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        val normalized = input.lowercase()
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
            .replace(Regex("""(?i)^share\s+(this\s+)?(text\s+)?"""), "")
            .trim()
        return cleaned.ifBlank { cleanedObject(input) }
    }

    private fun cleanedObject(input: String): String =
        input.trim()
            .removePrefix("请")
            .removePrefix("帮我")
            .trim()
            .ifBlank { input.trim() }
}
