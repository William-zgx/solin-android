package com.bytedance.zgx.solin.data

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.remoteEligibleMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryTest {
    @Test
    fun persistsAndRestoresMessagePrivacy() {
        val dao = FakeSessionDao()
        val repository = SessionRepository(dao, FakeActiveSessionStore("session-1"))
        val messages = listOf(
            ChatMessage(
                role = MessageRole.User,
                text = "分享来的私密文本",
                privacy = MessagePrivacy.LocalOnly,
            ),
            ChatMessage(
                role = MessageRole.Assistant,
                text = "本地生成的私密回复",
                privacy = MessagePrivacy.LocalOnly,
            ),
        )

        repository.replaceActiveSessionMessages(messages, persistNow = true)

        assertEquals(
            listOf(MessagePrivacy.LocalOnly, MessagePrivacy.LocalOnly),
            repository.activeMessages().map { it.privacy },
        )
        assertEquals(
            listOf(MessagePrivacy.LocalOnly.name, MessagePrivacy.LocalOnly.name),
            dao.messagesForSession("session-1").map { it.privacy },
        )
    }

    @Test
    fun unknownStoredPrivacyFallsBackToLocalOnlyAndIsExcludedFromRemoteHistory() {
        val dao = FakeSessionDao()
        dao.deleteMessages("session-1")
        dao.insertMessages(
            listOf(
                ChatMessageEntity(
                    sessionId = "session-1",
                    position = 0,
                    role = MessageRole.User.name,
                    text = "旧数据",
                    tokenCount = null,
                    tokensPerSecond = null,
                    privacy = "UnknownFutureValue",
                ),
            ),
        )
        val repository = SessionRepository(dao, FakeActiveSessionStore("session-1"))

        val activeMessages = repository.activeMessages()
        assertEquals(MessagePrivacy.LocalOnly, activeMessages.single().privacy)
        assertTrue(activeMessages.remoteEligibleMessages().isEmpty())
    }

    @Test
    fun deletingOnlySessionClearsMessagesAndCreatesNewEmptySession() {
        val dao = FakeSessionDao()
        val activeSessionStore = FakeActiveSessionStore("session-1")
        val repository = SessionRepository(dao, activeSessionStore)
        repository.replaceActiveSessionMessages(
            listOf(ChatMessage(MessageRole.User, "需要删除的聊天记录")),
            persistNow = true,
        )

        val messages = repository.deleteActiveSession()

        assertEquals(emptyList<ChatMessage>(), messages)
        assertTrue(repository.activeSessionId != "session-1")
        assertEquals(repository.activeSessionId, activeSessionStore.activeSessionId())
        assertEquals(null, dao.session("session-1"))
        assertEquals(emptyList<ChatMessageEntity>(), dao.messagesForSession("session-1"))
        assertEquals(1, repository.summaries().size)
        assertEquals(0, repository.summaries().single().messageCount)
    }

    private class FakeActiveSessionStore(
        private var activeSessionId: String?,
    ) : ActiveSessionStore {
        override fun activeSessionId(): String? = activeSessionId

        override fun saveActiveSessionId(sessionId: String) {
            activeSessionId = sessionId
        }
    }

    private class FakeSessionDao : SessionDao {
        private val sessions = linkedMapOf(
            "session-1" to ChatSessionEntity(
                id = "session-1",
                title = "新会话",
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
        )
        private val messagesBySession = linkedMapOf<String, MutableList<ChatMessageEntity>>()

        override fun sessions(): List<ChatSessionEntity> =
            sessions.values.sortedByDescending { it.updatedAtMillis }

        override fun session(sessionId: String): ChatSessionEntity? =
            sessions[sessionId]

        override fun messagesForSession(sessionId: String): List<ChatMessageEntity> =
            messagesBySession[sessionId].orEmpty().sortedBy { it.position }

        override fun recentMessages(limit: Int): List<ChatMessageEntity> =
            messagesBySession.values
                .flatten()
                .sortedWith(compareByDescending<ChatMessageEntity> { it.sessionId }.thenByDescending { it.position })
                .take(limit)

        override fun upsertSession(session: ChatSessionEntity) {
            sessions[session.id] = session
        }

        override fun insertMessages(messages: List<ChatMessageEntity>) {
            messages
                .groupBy { it.sessionId }
                .forEach { (sessionId, sessionMessages) ->
                    messagesBySession.getOrPut(sessionId) { mutableListOf() }
                        .addAll(sessionMessages)
                }
        }

        override fun deleteMessages(sessionId: String) {
            messagesBySession[sessionId] = mutableListOf()
        }

        override fun deleteSession(sessionId: String) {
            sessions.remove(sessionId)
            messagesBySession.remove(sessionId)
        }
    }
}
