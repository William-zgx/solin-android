package com.bytedance.zgx.solin.audit

import com.bytedance.zgx.solin.data.RemoteSendAuditDao
import com.bytedance.zgx.solin.data.RemoteSendAuditEventEntity
import com.bytedance.zgx.solin.safety.SafetyCategory
import java.util.UUID

/**
 * The decision a user (or fail-closed policy) made about a single outbound remote-model send.
 * Recorded so the user can later review exactly what left the device and why (P2 egress audit).
 */
enum class RemoteSendDecision(val label: String) {
    /** A plain, non-sensitive payload the user confirmed (or that a suppressed session allowed). */
    Confirmed("已确认发送"),

    /** Sensitive spans were redacted via "mask & send" before the payload left the device. */
    MaskedSend("打码后发送"),

    /** The user explicitly chose to send raw sensitive content unchanged ("send anyway"). */
    SentAnyway("原样发送（含敏感内容）"),

    /** The user cancelled the disclosure; nothing was sent. */
    Cancelled("已取消，未发送"),

    /** A fail-closed block prevented the send (e.g. remote unconfigured / local-only marked). */
    Blocked("已拦截，未发送"),
}

/**
 * A single structured, already-redacted record of an outbound remote-send decision.
 *
 * This class intentionally has NO field for the raw prompt text. Only aggregate metadata
 * (decision, model name, detected sensitive-category LABELS, image/history counts, and a
 * human-readable summary built from those labels) is stored. The summary is passed through
 * [ToolAuditSummaryRedactor] on construction so no raw credential/email ever lands in the
 * audit store, even if the caller forgets to redact.
 *
 * Raw prompt text is NEVER persisted — see [RemoteSendAuditRepository.record] and
 * `SolinViewModel.recordRemoteSendAuditEvent` for the write path.
 */
data class RemoteSendAuditEvent(
    val id: String = UUID.randomUUID().toString(),
    val decision: RemoteSendDecision,
    val modelName: String?,
    val sensitiveCategories: List<SafetyCategory> = emptyList(),
    val imageCount: Int = 0,
    val remoteHistoryCount: Int = 0,
    val summary: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    /** A redacted copy safe to persist/display. Idempotent. */
    fun redactedForAudit(maxLength: Int = MAX_SUMMARY_LENGTH): RemoteSendAuditEvent =
        copy(
            summary = ToolAuditSummaryRedactor.redact(
                summary.replace(Regex("""\s+"""), " ").trim(),
            ).take(maxLength),
        )

    private companion object {
        const val MAX_SUMMARY_LENGTH = 200
    }
}

/** Write side of the remote-send audit. Implementations must redact before storing. */
interface RemoteSendAuditSink {
    fun record(event: RemoteSendAuditEvent)
}

/** Read side of the remote-send audit, surfaced to the user as an egress review list. */
interface RemoteSendAuditLog {
    /** Most-recent-first, capped at [limit]. */
    fun recentRemoteSends(limit: Int = 50): List<RemoteSendAuditEvent>
}

class RemoteSendAuditRepository(
    private val dao: RemoteSendAuditDao,
    private val maxStoredEvents: Int = DEFAULT_MAX_STORED_EVENTS,
) : RemoteSendAuditSink, RemoteSendAuditLog {
    /**
     * Persists an already-redacted audit event. Raw prompt text is NEVER stored — the event
     * contains only decision labels, counts, and detected sensitive-category labels. The
     * [RemoteSendAuditEvent.redactedForAudit] call here additionally strips any credentials
     * or emails that might appear in the summary via [ToolAuditSummaryRedactor].
     */
    override fun record(event: RemoteSendAuditEvent) {
        dao.insert(event.redactedForAudit().toEntity())
        if (maxStoredEvents > 0) {
            dao.pruneToMostRecent(maxStoredEvents)
        }
    }

    override fun recentRemoteSends(limit: Int): List<RemoteSendAuditEvent> =
        dao.recent(limit).map { entity -> entity.toEvent() }

    private fun RemoteSendAuditEvent.toEntity(): RemoteSendAuditEventEntity =
        RemoteSendAuditEventEntity(
            id = id,
            decision = decision.name,
            modelName = modelName,
            sensitiveCategoriesCsv = sensitiveCategories
                .map(SafetyCategory::name)
                .sorted()
                .joinToString(separator = ","),
            imageCount = imageCount,
            remoteHistoryCount = remoteHistoryCount,
            summary = summary,
            createdAtMillis = createdAtMillis,
        )

    private fun RemoteSendAuditEventEntity.toEvent(): RemoteSendAuditEvent =
        RemoteSendAuditEvent(
            id = id,
            decision = runCatching { RemoteSendDecision.valueOf(decision) }
                .getOrDefault(RemoteSendDecision.Blocked),
            modelName = modelName,
            sensitiveCategories = sensitiveCategoriesCsv
                .split(",")
                .mapNotNull { raw -> raw.trim().takeIf { it.isNotBlank() } }
                .mapNotNull { name -> runCatching { SafetyCategory.valueOf(name) }.getOrNull() },
            imageCount = imageCount,
            remoteHistoryCount = remoteHistoryCount,
            summary = summary,
            createdAtMillis = createdAtMillis,
        ).redactedForAudit()

    private companion object {
        const val DEFAULT_MAX_STORED_EVENTS = 100
    }
}
