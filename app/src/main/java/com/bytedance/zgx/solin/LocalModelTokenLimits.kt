package com.bytedance.zgx.solin

object LocalModelTokenLimits {
    const val MAX_TOTAL_TOKENS = 8 * 1024
    const val OUTPUT_TOKEN_RESERVE = 2 * 1024
    const val CURRENT_PROMPT_TOKEN_RESERVE = 1024
    const val SYSTEM_PROMPT_TOKEN_RESERVE = 512
    const val MAX_INPUT_TOKENS = MAX_TOTAL_TOKENS - OUTPUT_TOKEN_RESERVE

    fun compactDisplayText(maxTotalTokens: Int = MAX_TOTAL_TOKENS): String =
        "Token ${formatTokenLimit(maxTotalTokens)}"

    fun totalDisplayText(maxTotalTokens: Int = MAX_TOTAL_TOKENS): String =
        "总窗口 ${formatTokenLimit(maxTotalTokens)} tokens"

    fun inputDisplayText(maxInputTokens: Int = MAX_INPUT_TOKENS): String =
        "输入预算 ${formatTokenLimit(maxInputTokens)} tokens"

    fun outputDisplayText(outputReserveTokens: Int = OUTPUT_TOKEN_RESERVE): String =
        "输出预留 ${formatTokenLimit(outputReserveTokens)} tokens"

    private fun formatTokenLimit(tokens: Int): String =
        if (tokens % 1024 == 0) {
            "${tokens / 1024}k"
        } else {
            tokens.toString()
        }
}
