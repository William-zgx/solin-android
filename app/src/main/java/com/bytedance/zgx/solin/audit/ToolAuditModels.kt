package com.bytedance.zgx.solin.audit

import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolPermission
import com.bytedance.zgx.solin.tool.ToolStatus
import java.util.UUID

data class ToolAuditEvent(
    val id: String = UUID.randomUUID().toString(),
    val runId: String?,
    val requestId: String?,
    val toolName: String?,
    val skillId: String?,
    val eventType: ToolAuditEventType,
    val status: ToolStatus? = null,
    val riskLevel: RiskLevel? = null,
    val permissions: Set<ToolPermission> = emptySet(),
    val summary: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    fun sanitizedSummary(maxLength: Int = MAX_SUMMARY_LENGTH): String =
        ToolAuditSummaryRedactor.redact(
            summary
                .replace(Regex("""\s+"""), " ")
                .trim(),
        )
            .take(maxLength)

    fun redactedForAudit(maxLength: Int = MAX_SUMMARY_LENGTH): ToolAuditEvent =
        copy(summary = sanitizedSummary(maxLength))

    private companion object {
        const val MAX_SUMMARY_LENGTH = 160
    }
}

data class ToolAuditRecord(
    val id: String,
    val runId: String?,
    val requestId: String?,
    val toolName: String?,
    val skillId: String?,
    val eventType: String,
    val status: String?,
    val riskLevel: String?,
    val permissions: List<String>,
    val summary: String,
    val createdAtMillis: Long,
)

object ToolAuditSummaryRedactor {
    private const val CREDENTIAL_LABELS =
        """api[_ -]?key|authorization|auth[_ -]?token|access[_ -]?token|refresh[_ -]?token|token|key|secret|password"""

    private val credentialAssignment = Regex(
        pattern = """(["']?\b(?:$CREDENTIAL_LABELS)\b["']?\s*[:=]\s*)(?:Bearer\s+)?["']?[^"'\s,;}]+["']?""",
        option = RegexOption.IGNORE_CASE,
    )
    private val bearerToken = Regex(
        pattern = """\bBearer\s+[A-Za-z0-9._~+/=-]{12,}""",
        option = RegexOption.IGNORE_CASE,
    )
    private val apiKeyToken = Regex("""\bsk-[A-Za-z0-9_-]{16,}\b""")
    private val emailAddress = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")

    fun redact(text: String): String =
        text
            .replace(credentialAssignment) { match -> "${match.groupValues[1]}[redacted]" }
            .replace(bearerToken, "Bearer [redacted]")
            .replace(apiKeyToken, "sk-[redacted]")
            .replace(emailAddress, "[email]")
}

enum class ToolAuditEventType {
    ToolPlanned,
    ToolRejected,
    ConfirmationRequested,
    UserConfirmed,
    UserCancelled,
    ToolObserved,
    ExternalOutcomeConfirmed,
    ToolRetryScheduled,
}

interface ToolAuditSink {
    fun record(event: ToolAuditEvent)
}

interface ToolAuditLog {
    fun recentAuditEvents(limit: Int = 50): List<ToolAuditRecord>
}

object NoOpToolAuditSink : ToolAuditSink {
    override fun record(event: ToolAuditEvent) = Unit
}
