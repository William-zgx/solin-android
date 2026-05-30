package com.bytedance.zgx.pocketmind.audit

import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.ToolStatus
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

object ToolAuditSummaryRedactor {
    private const val CREDENTIAL_LABELS =
        """api[_ -]?key|authorization|auth[_ -]?token|access[_ -]?token|refresh[_ -]?token|secret|password"""

    private val credentialAssignment = Regex(
        pattern = """\b($CREDENTIAL_LABELS)\s*[:=]\s*(?:Bearer\s+)?["']?[^"'\s,;]+["']?""",
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
            .replace(credentialAssignment) { match -> "${match.groupValues[1]}=[redacted]" }
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
    ToolRetryScheduled,
}

interface ToolAuditSink {
    fun record(event: ToolAuditEvent)
}

object NoOpToolAuditSink : ToolAuditSink {
    override fun record(event: ToolAuditEvent) = Unit
}

class InMemoryToolAuditSink : ToolAuditSink {
    private val mutableEvents = mutableListOf<ToolAuditEvent>()

    val events: List<ToolAuditEvent>
        get() = mutableEvents.toList()

    override fun record(event: ToolAuditEvent) {
        mutableEvents += event.redactedForAudit()
    }
}
