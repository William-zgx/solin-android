package com.bytedance.zgx.solin

/**
 * Local (LiteRT-LM) token budgets.
 *
 * The context window is a per-model fact owned by the model capability profile
 * ([ModelCapabilityProfile.contextWindowTokens], documented in
 * `docs/model_capability_profiles.json`). Callers resolve it through
 * [contextWindowTokensFor] so that a missing or invalid profile value falls back to the
 * fail-safe [MAX_TOTAL_TOKENS] (8K) default, and an oversized claim is capped at
 * [MAX_SUPPORTED_CONTEXT_WINDOW_TOKENS] to bound KV-cache memory on phones.
 */
object LocalModelTokenLimits {
    /** Fail-safe default window used when a profile declares none (and for current profiles). */
    const val MAX_TOTAL_TOKENS = 8 * 1024
    const val DEFAULT_CONTEXT_WINDOW_TOKENS = MAX_TOTAL_TOKENS

    /** Upper bound for a profile-declared window; larger claims are capped, not trusted. */
    const val MAX_SUPPORTED_CONTEXT_WINDOW_TOKENS = 32 * 1024
    const val OUTPUT_TOKEN_RESERVE = 2 * 1024
    const val CURRENT_PROMPT_TOKEN_RESERVE = 1024
    const val SYSTEM_PROMPT_TOKEN_RESERVE = 512
    const val MAX_INPUT_TOKENS = MAX_TOTAL_TOKENS - OUTPUT_TOKEN_RESERVE

    /**
     * Resolves the effective local context window. `null` or non-positive values fall back to
     * the 8K default; values above [MAX_SUPPORTED_CONTEXT_WINDOW_TOKENS] are capped. A smaller
     * positive declaration is kept as-is (never inflated beyond what the model declares).
     */
    fun contextWindowTokensFor(declaredContextWindowTokens: Int?): Int {
        val declared = declaredContextWindowTokens?.takeIf { it > 0 } ?: return DEFAULT_CONTEXT_WINDOW_TOKENS
        return minOf(declared, MAX_SUPPORTED_CONTEXT_WINDOW_TOKENS)
    }

    fun contextWindowTokensFor(profile: ModelCapabilityProfile?): Int =
        contextWindowTokensFor(profile?.contextWindowTokens)

    /** Input budget for a resolved window: the window minus the output reserve, never negative. */
    fun maxInputTokensFor(contextWindowTokens: Int): Int =
        (contextWindowTokensFor(contextWindowTokens) - OUTPUT_TOKEN_RESERVE).coerceAtLeast(0)

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
