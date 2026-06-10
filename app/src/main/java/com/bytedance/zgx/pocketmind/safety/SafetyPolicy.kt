package com.bytedance.zgx.pocketmind.safety

import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.tool.ConfirmationPolicy
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolSpec

data class SafetyContext(
    val userConfirmed: Boolean,
)

data class SafetyDecision(
    val outcome: SafetyOutcome,
    val reason: String,
)

enum class SafetyOutcome {
    Allow,
    RequireConfirmation,
    Reject,
}

/**
 * Categories of sensitive content that can be flagged before a remote send. Exposed so the
 * disclosure UI can explain *why* a payload was flagged (P1 explainability) instead of an
 * opaque "blocked" state.
 */
enum class SafetyCategory(val label: String) {
    Email("疑似邮箱"),
    Phone("疑似手机号/电话"),
    ChineseId("疑似身份证号"),
    SecretToken("疑似密钥/令牌"),
    CloudSecret("疑似云厂商密钥"),
    PrivateKey("疑似私钥"),
    SecretAssignment("疑似密码/密钥赋值"),
    PersonalIdentity("疑似个人身份信息"),
    SensitiveDomain("疑似敏感领域信息"),
}

/**
 * Result of [SafetyPolicy.maskSensitiveContent]: the redacted text plus the categories that were
 * actually masked. [maskedCategories] is empty iff nothing was changed.
 */
data class SensitiveMaskResult(
    val maskedText: String,
    val maskedCategories: List<SafetyCategory>,
) {
    val didMask: Boolean get() = maskedCategories.isNotEmpty()
}

class SafetyPolicy {
    fun containsSensitivePersonalOrSecretContent(text: String): Boolean =
        text.containsSensitiveNetworkSearchContent()

    /**
     * Returns the distinct [SafetyCategory] values matched in [text], in declaration order.
     * Empty when nothing sensitive is detected. Used by the remote-send disclosure to show the
     * user which kinds of sensitive content triggered the confirmation.
     */
    fun detectSensitiveCategories(text: String): List<SafetyCategory> {
        if (text.isBlank()) return emptyList()
        val normalized = text.lowercase()
        val withoutIsoTimeWindows = isoDateOrDateTimePattern.replace(text, " ")
        return buildList {
            if (emailPattern.containsMatchIn(text)) add(SafetyCategory.Email)
            if (phonePattern.containsMatchIn(withoutIsoTimeWindows)) add(SafetyCategory.Phone)
            if (chineseIdPattern.containsMatchIn(text)) add(SafetyCategory.ChineseId)
            if (secretTokenPattern.containsMatchIn(text)) add(SafetyCategory.SecretToken)
            if (cloudSecretPattern.containsMatchIn(text)) add(SafetyCategory.CloudSecret)
            if (privateKeyBlockPattern.containsMatchIn(text)) add(SafetyCategory.PrivateKey)
            if (secretAssignmentPattern.containsMatchIn(text)) add(SafetyCategory.SecretAssignment)
            if (personalChineseKeywordPattern.containsMatchIn(text) ||
                personalEnglishKeywordPattern.containsMatchIn(normalized)
            ) {
                add(SafetyCategory.PersonalIdentity)
            }
            if (sensitiveChineseDomainPattern.containsMatchIn(text) ||
                sensitiveChineseLocationPattern.containsMatchIn(text) ||
                sensitiveEnglishDomainPattern.containsMatchIn(normalized) ||
                sensitiveEnglishLocationPattern.containsMatchIn(normalized)
            ) {
                add(SafetyCategory.SensitiveDomain)
            }
        }
    }

    fun detectSensitiveSnippets(text: String, maxSnippets: Int = 4): List<String> {
        if (text.isBlank() || maxSnippets <= 0) return emptyList()
        val snippets = linkedSetOf<String>()
        val isoSpans = isoDateOrDateTimePattern.findAll(text).map { it.range }.toList()

        fun addMatches(pattern: Regex) {
            pattern.findAll(text).forEach { match ->
                if (snippets.size >= maxSnippets) return
                snippets += match.value.toDisclosureSnippet()
            }
        }

        addMatches(emailPattern)
        phonePattern.findAll(text).forEach { match ->
            if (snippets.size >= maxSnippets) return@forEach
            val inIsoWindow = isoSpans.any { span ->
                match.range.first >= span.first && match.range.last <= span.last
            }
            if (!inIsoWindow) snippets += match.value.toDisclosureSnippet()
        }
        addMatches(chineseIdPattern)
        addMatches(secretTokenPattern)
        addMatches(cloudSecretPattern)
        addMatches(privateKeyBlockPattern)
        addMatches(secretAssignmentPattern)
        addMatches(personalChineseKeywordPattern)
        addMatches(personalEnglishKeywordPattern)
        addMatches(sensitiveChineseDomainPattern)
        addMatches(sensitiveChineseLocationPattern)
        addMatches(sensitiveEnglishDomainPattern)
        addMatches(sensitiveEnglishLocationPattern)
        return snippets.take(maxSnippets)
    }

    /**
     * Masks the sensitive spans detected in [text] so the redacted form can still be sent to a
     * remote model when the user explicitly chooses "mask & send" (P1 tiered handling). The
     * masking is deterministic and preserves enough shape for the model to remain useful
     * (e.g. an email keeps its first char and TLD; a phone keeps its last 4 digits) while no
     * longer leaking the raw identifier. Returns the masked text together with the categories
     * that were actually masked, so the disclosure UI / audit log can report what changed.
     *
     * Masking is applied highest-entropy-first (private key blocks, then secrets, then
     * structured PII) so a later pattern never re-matches an already-masked span.
     */
    fun maskSensitiveContent(text: String): SensitiveMaskResult {
        if (text.isBlank()) return SensitiveMaskResult(text, emptyList())
        val maskedCategories = linkedSetOf<SafetyCategory>()
        var result = text

        fun maskWith(category: SafetyCategory, pattern: Regex, replacement: (MatchResult) -> String) {
            if (!pattern.containsMatchIn(result)) return
            maskedCategories += category
            result = pattern.replace(result) { match -> replacement(match) }
        }

        // 1. Whole-block / high-entropy secrets first.
        maskWith(SafetyCategory.PrivateKey, privateKeyBlockPattern) { "-----BEGIN [REDACTED] PRIVATE KEY-----" }
        maskWith(SafetyCategory.CloudSecret, cloudSecretPattern) { maskToken(it.value) }
        maskWith(SafetyCategory.SecretToken, secretTokenPattern) { maskToken(it.value) }
        maskWith(SafetyCategory.SecretAssignment, secretAssignmentPattern) { match ->
            // Keep the "label:" / "label=" prefix, redact the value.
            val raw = match.value
            val separatorIndex = raw.indexOfFirst { it == ':' || it == '=' }
            if (separatorIndex < 0) "[REDACTED]" else raw.substring(0, separatorIndex + 1) + " [REDACTED]"
        }

        // 2. Structured PII.
        maskWith(SafetyCategory.Email, emailPattern) { maskEmail(it.value) }
        maskWith(SafetyCategory.ChineseId, chineseIdPattern) { maskTail(it.value, keep = 4) }
        // Phone: avoid masking ISO date/time windows, mirroring detection.
        if (phonePattern.containsMatchIn(isoDateOrDateTimePattern.replace(result, " "))) {
            maskedCategories += SafetyCategory.Phone
            result = maskPhonesPreservingIsoWindows(result)
        }

        return SensitiveMaskResult(result, maskedCategories.toList())
    }

    fun evaluate(
        spec: ToolSpec,
        request: ToolRequest,
        context: SafetyContext,
    ): SafetyDecision {
        if (spec.riskLevel.requiresHardConfirmation() && spec.confirmationPolicy != ConfirmationPolicy.Required) {
            return SafetyDecision(
                outcome = SafetyOutcome.Reject,
                reason = "Tool ${request.toolName} has ${spec.riskLevel} risk and must require confirmation.",
            )
        }

        if (spec.permissions.any { permission -> permission in confirmationRequiredPermissions } &&
            spec.confirmationPolicy != ConfirmationPolicy.Required
        ) {
            return SafetyDecision(
                outcome = SafetyOutcome.Reject,
                reason = "Tool ${request.toolName} crosses a device, external app, background, notification, permission, or private-read boundary and must require confirmation.",
            )
        }

        if (!context.userConfirmed && spec.confirmationPolicy == ConfirmationPolicy.Required) {
            return SafetyDecision(
                outcome = SafetyOutcome.RequireConfirmation,
                reason = "Tool ${request.toolName} requires user confirmation before execution.",
            )
        }

        if (!context.userConfirmed && request.requiresSensitiveNetworkQueryConfirmation()) {
            return SafetyDecision(
                outcome = SafetyOutcome.RequireConfirmation,
                reason = "Web search query may contain personal or secret data and requires confirmation before network access.",
            )
        }

        return SafetyDecision(
            outcome = SafetyOutcome.Allow,
            reason = "Tool ${request.toolName} is allowed by current safety policy.",
        )
    }

    private fun RiskLevel.requiresHardConfirmation(): Boolean =
        this == RiskLevel.HighExternalSend || this == RiskLevel.CriticalDeviceOrPayment

    private fun ToolRequest.requiresSensitiveNetworkQueryConfirmation(): Boolean =
        toolName == MobileActionFunctions.WEB_SEARCH &&
            arguments["query"].orEmpty().containsSensitiveNetworkSearchContent()

    private fun String.containsSensitiveNetworkSearchContent(): Boolean {
        val normalized = lowercase()
        val withoutIsoTimeWindows = isoDateOrDateTimePattern.replace(this, " ")
        return emailPattern.containsMatchIn(this) ||
            phonePattern.containsMatchIn(withoutIsoTimeWindows) ||
            chineseIdPattern.containsMatchIn(this) ||
            secretTokenPattern.containsMatchIn(this) ||
            cloudSecretPattern.containsMatchIn(this) ||
            privateKeyBlockPattern.containsMatchIn(this) ||
            secretAssignmentPattern.containsMatchIn(this) ||
            personalChineseKeywordPattern.containsMatchIn(this) ||
            personalEnglishKeywordPattern.containsMatchIn(normalized) ||
            sensitiveChineseDomainPattern.containsMatchIn(this) ||
            sensitiveChineseLocationPattern.containsMatchIn(this) ||
            sensitiveEnglishDomainPattern.containsMatchIn(normalized) ||
            sensitiveEnglishLocationPattern.containsMatchIn(normalized)
    }

    private companion object {
        /** Replaces all but the first 2 and last 2 chars of a token with asterisks. */
        fun maskToken(token: String): String {
            if (token.length <= 6) return "*".repeat(token.length)
            return token.take(2) + "*".repeat(token.length - 4) + token.takeLast(2)
        }

        /** a***@***.com — keeps first local char and the TLD only. */
        fun maskEmail(email: String): String {
            val at = email.indexOf('@')
            if (at <= 0) return "***"
            val local = email.substring(0, at)
            val domain = email.substring(at + 1)
            val tld = domain.substringAfterLast('.', missingDelimiterValue = "")
            val maskedLocal = local.first() + "***"
            return if (tld.isNotEmpty()) "$maskedLocal@***.$tld" else "$maskedLocal@***"
        }

        /** Keeps only the last [keep] characters, masking the rest with asterisks. */
        fun maskTail(value: String, keep: Int): String {
            if (value.length <= keep) return "*".repeat(value.length)
            return "*".repeat(value.length - keep) + value.takeLast(keep)
        }

        /**
         * Masks phone-like spans while leaving ISO date/time windows untouched (parity with the
         * detection path). Keeps the last 4 digits so the model can still disambiguate references.
         */
        fun maskPhonesPreservingIsoWindows(text: String): String {
            val isoSpans = isoDateOrDateTimePattern.findAll(text).map { it.range }.toList()
            return phonePattern.replace(text) { match ->
                val inIsoWindow = isoSpans.any { span ->
                    match.range.first >= span.first && match.range.last <= span.last
                }
                if (inIsoWindow) match.value else maskTail(match.value.filter { it.isDigit() || it == '+' }, keep = 4)
            }
        }

        fun String.toDisclosureSnippet(maxLength: Int = 80): String {
            val collapsed = trim().replace(Regex("""\s+"""), " ")
            return if (collapsed.length <= maxLength) {
                collapsed
            } else {
                collapsed.take(maxLength).trimEnd() + "…"
            }
        }

        val confirmationRequiredPermissions = setOf(
            ToolPermission.StartsExternalActivity,
            ToolPermission.SendsTextToExternalApp,
            ToolPermission.RequiresAndroidRuntimePermission,
            ToolPermission.SchedulesBackgroundWork,
            ToolPermission.PostsNotification,
            ToolPermission.ReadsClipboard,
            ToolPermission.ReadsContacts,
            ToolPermission.ReadsFiles,
            ToolPermission.ReadsCalendar,
            ToolPermission.ReadsAccessibilityText,
            ToolPermission.RequiresMediaProjectionConsent,
            ToolPermission.ReadsDeviceContext,
        )
        val emailPattern = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        val isoDateOrDateTimePattern = Regex(
            """\b\d{4}-\d{2}-\d{2}(?:[T\s]\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:\d{2})?)?\b""",
        )
        // Tightened to reduce false positives on space-separated number lists (e.g. "2020 2021
        // 2022" year comparisons) and medium-length ids (e.g. 9-10 digit order/tracking numbers),
        // while keeping recall on real phone numbers and long sensitive identifiers. Matches:
        // international "+" prefixed numbers, CN mobile, grouped phone numbers (NNN-NNN(N)-NNNN),
        // or a contiguous run of >=11 digits (covers phone-length and longer card/account ids,
        // but no longer mis-flags 9-10 digit order numbers).
        val phonePattern = Regex(
            """(?<!\d)(?:\+\d[\d -]{6,}\d|1[3-9]\d{9}|\d{3}[- ]\d{3,4}[- ]\d{4}|\d{11,})(?!\d)""",
        )
        val chineseIdPattern = Regex("""(?<!\d)\d{17}[0-9Xx](?!\d)""")
        val secretTokenPattern = Regex("""\b(?:sk-[A-Za-z0-9_-]{16,}|[A-Za-z0-9_-]{24,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,})\b""")
        val cloudSecretPattern = Regex(
            """\b(?:AKIA|ASIA)[A-Z0-9]{16}\b|\bAIza[0-9A-Za-z_-]{35}\b|\bxox[abprs]-[0-9A-Za-z-]{16,}\b""",
        )
        val privateKeyBlockPattern = Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----""")
        val secretAssignmentPattern = Regex(
            """(?i)\b(?:password|passwd|pwd|token|secret|api[_\s-]*key|access[_\s-]*key|client[_\s-]*secret)\b\s*[:=]\s*['"]?[^\s'"]{6,}""",
        )
        val personalChineseKeywordPattern =
            Regex("""(我|我的|本人|自己).{0,12}(手机号|电话|邮箱|住址|地址|身份证|工号|银行卡|账号|密码|口令|令牌|密钥|API\s*Key)""")
        val personalEnglishKeywordPattern =
            Regex("""\b(my|mine|personal|private)\b.{0,24}\b(phone|email|address|id|employee\s*id|bank|account|password|token|secret|api\s*key)\b""")
        val sensitiveChineseDomainPattern = Regex(
            """(我|我的|本人|自己).{0,16}(HIV|艾滋|怀孕|孕检|心理|抑郁|焦虑|病历|诊断|律师|起诉|破产|债务|贷款|信用卡|保险|理赔|孩子|儿童|未成年)""",
            RegexOption.IGNORE_CASE,
        )
        val sensitiveChineseLocationPattern =
            Regex("""(附近|本地|周边).{0,16}(医院|诊所|HIV|艾滋|怀孕|孕检|心理|抑郁|焦虑|咨询|律师|破产|债务|保险|理赔|银行|贷款)""", RegexOption.IGNORE_CASE)
        val sensitiveEnglishDomainPattern =
            Regex("""\b(my|mine|personal|private)\b.{0,32}\b(hiv|aids|pregnan\w*|therapy|therapist|mental|depress\w*|anxiety|medical|diagnos\w*|bankruptcy|debt|credit\s*card|insurance|claim|lawyer|attorney|child|minor)\b""")
        val sensitiveEnglishLocationPattern =
            Regex("""\b(near me|nearby|in my area|local)\b.{0,40}\b(hiv|aids|pregnan\w*|clinic|hospital|therap\w*|mental|depress\w*|anxiety|lawyer|attorney|bankruptcy|debt|insurance|bank|loan)\b|\b(hiv|aids|pregnan\w*|clinic|hospital|therap\w*|mental|depress\w*|anxiety|lawyer|attorney|bankruptcy|debt|insurance|bank|loan)\b.{0,40}\b(near me|nearby|in my area|local)\b""")
    }
}
