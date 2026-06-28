package com.bytedance.zgx.solin.data

import android.content.Context
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatSessionSummary
import com.bytedance.zgx.solin.GenerationStats
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.isUsable
import java.util.UUID
import kotlin.math.max

class SessionRepository(
    private val sessionDao: SessionDao,
    private val activeSessionStore: ActiveSessionStore,
) : SessionStore {
    constructor(context: Context) : this(
        SolinDatabase.get(context).sessionDao(),
        PreferenceSettingsStore(context),
    )

    override var activeSessionId: String = resolveActiveSessionId()
        private set

    override fun summaries(): List<ChatSessionSummary> =
        sessionDao.sessions().map { session ->
            ChatSessionSummary(
                id = session.id,
                title = session.title,
                updatedAtMillis = session.updatedAtMillis,
                messageCount = sessionDao.messagesForSession(session.id).size,
            )
        }

    override fun activeMessages(): List<ChatMessage> =
        sessionDao.messagesForSession(activeSessionId).toChatMessages()

    override fun allMessages(limit: Int): List<ChatMessage> =
        if (limit == Int.MAX_VALUE) {
            sessionDao.sessions()
                .sortedBy { it.updatedAtMillis }
                .flatMap { session -> sessionDao.messagesForSession(session.id).toChatMessages() }
        } else {
            sessionDao.recentMessages(limit)
                .asReversed()
                .toChatMessages()
        }

    override fun createNewSession(): List<ChatMessage> {
        val created = newChatSession()
        sessionDao.upsertSession(created)
        activeSessionId = created.id
        activeSessionStore.saveActiveSessionId(activeSessionId)
        return emptyList()
    }

    override fun selectSession(sessionId: String): List<ChatMessage>? {
        sessionDao.session(sessionId) ?: return null
        activeSessionId = sessionId
        activeSessionStore.saveActiveSessionId(activeSessionId)
        return activeMessages()
    }

    override fun deleteActiveSession(): List<ChatMessage>? {
        sessionDao.deleteMessages(activeSessionId)
        sessionDao.deleteSession(activeSessionId)
        activeSessionId = resolveActiveSessionId()
        activeSessionStore.saveActiveSessionId(activeSessionId)
        return activeMessages()
    }

    override fun replaceActiveSessionMessages(
        messages: List<ChatMessage>,
        persistNow: Boolean,
    ) {
        val existing = sessionDao.session(activeSessionId) ?: newChatSession(activeSessionId)
        val now = System.currentTimeMillis()
        sessionDao.upsertSession(
            existing.copy(
                title = SessionTitleRules.deriveTitle(messages),
                updatedAtMillis = max(existing.updatedAtMillis, now),
            ),
        )
        if (persistNow) {
            persistMessages(activeSessionId, messages)
        }
    }

    override fun persistActiveSessionFrom(messages: List<ChatMessage>) {
        replaceActiveSessionMessages(messages, persistNow = true)
    }

    private fun persistMessages(sessionId: String, messages: List<ChatMessage>) {
        sessionDao.deleteMessages(sessionId)
        sessionDao.insertMessages(
            messages.mapIndexed { index, message ->
                ChatMessageEntity(
                    sessionId = sessionId,
                    position = index,
                    role = message.role.name,
                    text = message.text,
                    tokenCount = message.generationStats?.tokenCount,
                    tokensPerSecond = message.generationStats?.tokensPerSecond,
                    privacy = message.privacy.name,
                )
            },
        )
    }

    private fun resolveActiveSessionId(): String {
        val sessions = sessionDao.sessions()
        val saved = activeSessionStore.activeSessionId()
        val resolved = sessions.firstOrNull { it.id == saved }?.id
            ?: sessions.firstOrNull()?.id
            ?: newChatSession().also { sessionDao.upsertSession(it) }.id
        activeSessionStore.saveActiveSessionId(resolved)
        return resolved
    }

    private fun newChatSession(id: String = UUID.randomUUID().toString()): ChatSessionEntity {
        val now = System.currentTimeMillis()
        return ChatSessionEntity(
            id = id,
            title = "新会话",
            createdAtMillis = now,
            updatedAtMillis = now,
        )
    }

    private fun List<ChatMessageEntity>.toChatMessages(): List<ChatMessage> =
        mapNotNull { entity ->
            val text = entity.text.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val role = runCatching { MessageRole.valueOf(entity.role) }
                .getOrDefault(MessageRole.Assistant)
            ChatMessage(
                role = role,
                text = text,
                generationStats = entity.tokenCount?.let { tokenCount ->
                    GenerationStats(
                        tokenCount = tokenCount,
                        tokensPerSecond = entity.tokensPerSecond ?: 0.0,
                    ).takeIf { it.isUsable() }
                },
                privacy = runCatching { MessagePrivacy.valueOf(entity.privacy) }
                    .getOrDefault(MessagePrivacy.LocalOnly),
            )
        }
}

object SessionTitleRules {
    fun deriveTitle(messages: List<ChatMessage>): String =
        messages
            .firstOrNull {
                it.role == MessageRole.User &&
                    it.privacy != MessagePrivacy.LocalOnly &&
                    it.text.isNotBlank()
            }
            ?.text
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(28)
            ?: if (messages.any { it.role == MessageRole.User && it.privacy == MessagePrivacy.LocalOnly }) {
                "本地内容"
            } else {
                "新会话"
            }
}
