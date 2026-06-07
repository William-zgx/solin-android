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

class SafetyPolicy {
    fun containsSensitivePersonalOrSecretContent(text: String): Boolean =
        text.containsSensitiveNetworkSearchContent()

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
        // 2022" year comparisons) and short ids, while keeping recall on real phone numbers and
        // long sensitive identifiers. Matches: international "+" prefixed numbers, CN mobile,
        // grouped phone numbers (NNN-NNN(N)-NNNN), or a contiguous run of >=9 digits.
        val phonePattern = Regex(
            """(?<!\d)(?:\+\d[\d -]{6,}\d|1[3-9]\d{9}|\d{3}[- ]\d{3,4}[- ]\d{4}|\d{9,})(?!\d)""",
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
