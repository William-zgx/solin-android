package com.bytedance.zgx.solin.safety

import com.bytedance.zgx.solin.tool.ToolPermission
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolSpec
import java.text.Normalizer

/** Run-only provenance for egress decisions. Trace access stays in orchestration. */
internal data class EgressRunContext(
    val privateReadBefore: Boolean,
    val userInput: String
)

internal enum class EgressTrigger {
    SensitiveNetworkQuery,
    TypedTextNotFromUser,
    TextHandedToExternalApp
}

/** The three existing tool-egress rules, with no routing, placement or lifecycle policy. */
internal class EgressGate(
    private val sensitiveContent: (String) -> Boolean
) {
    fun evaluate(
        spec: ToolSpec,
        request: ToolRequest,
        typedText: String? = null,
        context: () -> EgressRunContext
    ): Set<EgressTrigger> {
        val textArguments = spec.egressTextArguments.mapNotNull { name ->
            request.arguments[name]?.takeIf(String::isNotBlank)
        }
        val sensitiveText = textArguments.any(sensitiveContent)
        return buildSet {
            if (spec.egressTextArguments.isNotEmpty() &&
                ToolPermission.SendsTextToExternalApp !in spec.permissions &&
                (sensitiveText || context().privateReadBefore)
            ) {
                add(EgressTrigger.SensitiveNetworkQuery)
            }
            if (typedText != null) {
                val runContext = context()
                if (runContext.privateReadBefore && !isTextFromUser(runContext.userInput, typedText)) {
                    add(EgressTrigger.TypedTextNotFromUser)
                }
            }
            if (ToolPermission.SendsTextToExternalApp in spec.permissions && textArguments.isNotEmpty()) {
                val tainted = sensitiveText ||
                    context().let { runContext ->
                        runContext.privateReadBefore && textArguments.any { text -> !isTextFromUser(runContext.userInput, text) }
                    }
                if (tainted) add(EgressTrigger.TextHandedToExternalApp)
            }
        }
    }
}

private class UserTextPieces(val asciiTokens: Set<String>, val otherCodePoints: Set<Int>)

private val asciiLetterOrDigitRun = Regex("[a-z]+|[0-9]+")
private val PUNCTUATION_TYPES = setOf(
    Character.CONNECTOR_PUNCTUATION.toInt(),
    Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt()
)

private fun userTextPieces(text: String): UserTextPieces {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
    return UserTextPieces(
        asciiTokens = asciiLetterOrDigitRun.findAll(normalized).map { it.value }.toSet(),
        otherCodePoints = buildSet {
            var index = 0
            while (index < normalized.length) {
                val codePoint = normalized.codePointAt(index)
                if (codePoint > 0x7F &&
                    !Character.isWhitespace(codePoint) &&
                    Character.getType(codePoint) !in PUNCTUATION_TYPES
                ) {
                    add(codePoint)
                }
                index += Character.charCount(codePoint)
            }
        }
    )
}

/**
 * Every ASCII letter/digit run must be a whole token of the run input, and every non-ASCII letter,
 * digit or symbol (including supplementary-plane characters) must occur in that input. Case,
 * punctuation and whitespace are ignored, so reordered user wording still counts while new symbols or private content cannot pass through empty token sets.
 */
private fun isTextFromUser(userInput: String, text: String): Boolean {
    val input = userTextPieces(userInput)
    val typed = userTextPieces(text)
    return input.asciiTokens.containsAll(typed.asciiTokens) && input.otherCodePoints.containsAll(typed.otherCodePoints)
}
