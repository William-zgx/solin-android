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
 * A single structured, already-redacted record of an outbound remote-send decision. The summary
 * is passed through [ToolAuditSummaryRedactor] on construction so no raw credential/email ever
 * lands in the audit store, even if the caller forgets to redact.
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

object NoOpRemoteSendAuditSink : RemoteSendAuditSink {
    override fun record(event: RemoteSendAuditEvent) = Unit
}

/**
 * Bounded in-memory implementation of both sides. Keeps at most [capacity] most-recent events
 * (older ones are evicted) and always stores the redacted form. Thread-safe for the simple
 * single-writer/single-reader ViewModel usage.
 */
class InMemoryRemoteSendAuditStore(
    private val capacity: Int = DEFAULT_CAPACITY,
) : RemoteSendAuditSink, RemoteSendAuditLog {
    private val lock = Any()
    private val events = ArrayDeque<RemoteSendAuditEvent>()

    override fun record(event: RemoteSendAuditEvent) {
        val redacted = event.redactedForAudit()
        synchronized(lock) {
            events.addFirst(redacted)
            while (events.size > capacity) {
                events.removeLast()
            }
        }
    }

    override fun recentRemoteSends(limit: Int): List<RemoteSendAuditEvent> =
        synchronized(lock) {
            if (limit <= 0) emptyList() else events.take(limit)
        }

    private companion object {
        const val DEFAULT_CAPACITY = 100
    }
}

class RemoteSendAuditRepository(
    private val dao: RemoteSendAuditDao,
    private val maxStoredEvents: Int = DEFAULT_MAX_STORED_EVENTS,
) : RemoteSendAuditSink, RemoteSendAuditLog {
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

/**
 * Pure builder that turns a remote-send decision into a structured audit event. Kept free of any
 * Android / ViewModel dependency so it is trivially unit-testable. The human-readable summary is
 * deterministic and composed from the structured fields; the [RemoteSendAuditEvent] constructor
 * still redacts it defensively.
 */
object RemoteSendAuditFactory {
    fun build(
        decision: RemoteSendDecision,
        modelName: String?,
        sensitiveCategories: List<SafetyCategory> = emptyList(),
        imageCount: Int = 0,
        remoteHistoryCount: Int = 0,
        nowMillis: Long = System.currentTimeMillis(),
    ): RemoteSendAuditEvent {
        val parts = buildList {
            add(decision.label)
            if (imageCount > 0) add("图片 ${imageCount} 张")
            if (remoteHistoryCount > 0) add("历史 ${remoteHistoryCount} 条")
            if (sensitiveCategories.isNotEmpty()) {
                add("敏感类别：" + sensitiveCategories.joinToString("、") { it.label })
            }
        }
        return RemoteSendAuditEvent(
            decision = decision,
            modelName = modelName?.takeIf { it.isNotBlank() },
            sensitiveCategories = sensitiveCategories,
            imageCount = imageCount,
            remoteHistoryCount = remoteHistoryCount,
            summary = parts.joinToString("；"),
            createdAtMillis = nowMillis,
        )
    }
}
