package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.audit.RemoteSendAuditEvent
import com.bytedance.zgx.solin.audit.RemoteSendAuditSink
import com.bytedance.zgx.solin.audit.RemoteSendDecision
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

private fun RemoteSendDecision.isSuccessfulRemoteSendDecision(): Boolean =
    this == RemoteSendDecision.Confirmed ||
        this == RemoteSendDecision.MaskedSend ||
        this == RemoteSendDecision.SentAnyway

/**
 * Holds back the "sent to remote" audit event and its in-chat note for a prepared run until
 * remote serving has actually started, so a disclosure that is confirmed but never dispatched
 * (stale profile, cancelled run) does not leave a success record behind.
 */
internal class DeferredRemoteSendAudit(
    private val auditSink: RemoteSendAuditSink,
    private val uiState: StateFlow<ChatUiState>,
    private val replaceActiveSessionMessages: (List<ChatMessage>, Boolean) -> Unit,
    private val refreshAuditEvents: () -> Unit,
) {
    private val audits = ConcurrentHashMap<String, RemoteSendAuditEvent>()
    private val notes = ConcurrentHashMap<String, String>()
    private val deferringRunId = ThreadLocal<String?>()

    /** Sink handed to remote-send support: successful sends confirmed inside [deferSuccessfulAudit] wait for [commit]. */
    val claimGatedSink = object : RemoteSendAuditSink {
        override fun record(event: RemoteSendAuditEvent) {
            val runId = deferringRunId.get()
            if (runId != null && event.decision.isSuccessfulRemoteSendDecision()) {
                audits[runId] = event
            } else {
                auditSink.record(event)
            }
        }
    }

    fun deferSuccessfulAudit(confirm: () -> Unit) {
        val runId = uiState.value.pendingRemoteSendDisclosure?.runId
        if (runId.isNullOrBlank()) {
            confirm()
            return
        }
        val previous = deferringRunId.get()
        deferringRunId.set(runId)
        try {
            confirm()
        } finally {
            if (previous == null) deferringRunId.remove() else deferringRunId.set(previous)
        }
    }

    fun deferNote(runId: String?, note: String): Boolean {
        if (runId.isNullOrBlank()) return false
        notes[runId] = note
        return true
    }

    fun commit(runId: String) {
        notes.remove(runId)?.let(::insertNoteBeforeActiveResponse)
        audits.remove(runId)?.let { event ->
            auditSink.record(event)
            refreshAuditEvents()
        }
    }

    fun discard(runId: String) {
        audits.remove(runId)
        notes.remove(runId)
    }

    fun retainOnly(keep: (runId: String) -> Boolean) {
        audits.keys.removeAll { runId -> !keep(runId) }
        notes.keys.removeAll { runId -> !keep(runId) }
    }

    private fun insertNoteBeforeActiveResponse(note: String) {
        val messages = uiState.value.messages.toMutableList()
        messages.add((messages.size - 2).coerceAtLeast(0), ChatMessage(
            role = MessageRole.Assistant,
            text = note,
            privacy = MessagePrivacy.LocalOnly,
        ))
        replaceActiveSessionMessages(messages, true)
    }
}
