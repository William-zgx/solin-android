package com.bytedance.zgx.pocketmind.data

import android.content.Context
import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.ChatSessionSummary
import com.bytedance.zgx.pocketmind.GenerationStats
import com.bytedance.zgx.pocketmind.MessageRole
import com.bytedance.zgx.pocketmind.isUsable
import java.util.UUID
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "pocketmind"
private const val PREF_SESSIONS_JSON = "sessions_json"
private const val PREF_ACTIVE_SESSION_ID = "active_session_id"
private const val SESSION_JSON_VERSION = 1
private const val MAX_SESSIONS = 20

private enum class SessionStorageFormat {
    LegacyArray,
    VersionedObject,
}

class SessionRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var sessionStorageFormat = SessionStorageFormat.VersionedObject
    private var sessions: List<ChatSession> = loadSessions()
    var activeSessionId: String = resolveActiveSessionId(sessions)
        private set

    fun summaries(): List<ChatSessionSummary> =
        sessions
            .sortedByDescending { it.updatedAtMillis }
            .map { session ->
                ChatSessionSummary(
                    id = session.id,
                    title = session.title,
                    updatedAtMillis = session.updatedAtMillis,
                    messageCount = session.messages.size,
                )
            }

    fun activeMessages(): List<ChatMessage> =
        sessions.firstOrNull { it.id == activeSessionId }?.messages.orEmpty()

    fun allMessages(): List<ChatMessage> =
        sessions.flatMap { it.messages }

    fun createNewSession(): List<ChatMessage> {
        val created = newChatSession()
        sessions = (listOf(created) + sessions).take(MAX_SESSIONS)
        activeSessionId = created.id
        persistSessions()
        return emptyList()
    }

    fun selectSession(sessionId: String): List<ChatMessage>? {
        val session = sessions.firstOrNull { it.id == sessionId } ?: return null
        activeSessionId = session.id
        prefs.edit().putString(PREF_ACTIVE_SESSION_ID, activeSessionId).apply()
        return session.messages
    }

    fun deleteActiveSession(): List<ChatMessage>? {
        if (sessions.size <= 1) return null
        sessions = sessions.filterNot { it.id == activeSessionId }
        activeSessionId = resolveActiveSessionId(sessions)
        persistSessions()
        return activeMessages()
    }

    fun replaceActiveSessionMessages(
        messages: List<ChatMessage>,
        persistNow: Boolean,
    ) {
        val now = System.currentTimeMillis()
        sessions = sessions.map { session ->
            if (session.id == activeSessionId) {
                session.copy(
                    title = SessionTitleRules.deriveTitle(messages),
                    updatedAtMillis = max(session.updatedAtMillis, now),
                    messages = messages,
                )
            } else {
                session
            }
        }.sortedByDescending { it.updatedAtMillis }
            .take(MAX_SESSIONS)
        if (persistNow) {
            persistSessions()
        }
    }

    fun persistActiveSessionFrom(messages: List<ChatMessage>) {
        replaceActiveSessionMessages(messages, persistNow = true)
    }

    private fun loadSessions(): List<ChatSession> {
        val decoded = prefs.getString(PREF_SESSIONS_JSON, null)
            ?.let { encoded ->
                runCatching {
                    val format = encoded.sessionStorageFormat()
                    sessionStorageFormat = format
                    val array = readSessionsArray(encoded, format)
                    buildList {
                        for (index in 0 until array.length()) {
                            val json = array.getJSONObject(index)
                            val messages = json.optJSONArray("messages").orEmptyMessages()
                            add(
                                ChatSession(
                                    id = json.optString("id").takeIf { it.isNotBlank() }
                                        ?: UUID.randomUUID().toString(),
                                    title = json.optString("title").takeIf { it.isNotBlank() }
                                        ?: SessionTitleRules.deriveTitle(messages),
                                    createdAtMillis = json.optLong("createdAtMillis", System.currentTimeMillis()),
                                    updatedAtMillis = json.optLong("updatedAtMillis", System.currentTimeMillis()),
                                    messages = messages,
                                ),
                            )
                        }
                    }
                }.getOrNull()
            }
            .orEmpty()
            .sortedByDescending { it.updatedAtMillis }
            .take(MAX_SESSIONS)

        return decoded.ifEmpty { listOf(newChatSession()) }
    }

    private fun String.sessionStorageFormat(): SessionStorageFormat =
        if (trimStart().startsWith("[")) SessionStorageFormat.LegacyArray else SessionStorageFormat.VersionedObject

    private fun readSessionsArray(encoded: String, format: SessionStorageFormat): JSONArray =
        when (format) {
            SessionStorageFormat.LegacyArray -> JSONArray(encoded)
            SessionStorageFormat.VersionedObject -> JSONObject(encoded).optJSONArray("sessions") ?: JSONArray()
        }

    private fun resolveActiveSessionId(availableSessions: List<ChatSession>): String {
        val saved = prefs.getString(PREF_ACTIVE_SESSION_ID, null)
        return availableSessions.firstOrNull { it.id == saved }?.id
            ?: availableSessions.firstOrNull()?.id
            ?: newChatSession().id
    }

    private fun newChatSession(): ChatSession {
        val now = System.currentTimeMillis()
        return ChatSession(
            id = UUID.randomUUID().toString(),
            title = "新会话",
            createdAtMillis = now,
            updatedAtMillis = now,
            messages = emptyList(),
        )
    }

    private fun persistSessions() {
        val encoded = when (sessionStorageFormat) {
            SessionStorageFormat.LegacyArray -> sessions.toSessionsArray().toString()
            SessionStorageFormat.VersionedObject -> sessions.toSessionsJson().toString()
        }
        prefs.edit()
            .putString(PREF_SESSIONS_JSON, encoded)
            .putString(PREF_ACTIVE_SESSION_ID, activeSessionId)
            .apply()
    }

    private fun List<ChatSession>.toSessionsJson(): JSONObject =
        JSONObject()
            .put("version", SESSION_JSON_VERSION)
            .put("sessions", toSessionsArray())

    private fun List<ChatSession>.toSessionsArray(): JSONArray =
        JSONArray().also { array ->
            forEach { session ->
                array.put(
                    JSONObject()
                        .put("id", session.id)
                        .put("title", session.title)
                        .put("createdAtMillis", session.createdAtMillis)
                        .put("updatedAtMillis", session.updatedAtMillis)
                        .put("messages", session.messages.toMessagesJson()),
                )
            }
        }

    private fun List<ChatMessage>.toMessagesJson(): JSONArray =
        JSONArray().also { array ->
            forEach { message ->
                array.put(
                    JSONObject()
                        .put("role", message.role.name)
                        .put("text", message.text)
                        .apply {
                            message.generationStats?.let { stats ->
                                put(
                                    "generationStats",
                                    JSONObject()
                                        .put("tokenCount", stats.tokenCount)
                                        .put("tokensPerSecond", stats.tokensPerSecond),
                                )
                            }
                        },
                )
            }
        }

    private fun JSONArray?.orEmptyMessages(): List<ChatMessage> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                val role = runCatching { MessageRole.valueOf(json.optString("role")) }
                    .getOrDefault(MessageRole.Assistant)
                val text = json.optString("text")
                if (text.isNotBlank()) {
                    add(
                        ChatMessage(
                            role = role,
                            text = text,
                            generationStats = json.optJSONObject("generationStats")?.let { stats ->
                                GenerationStats(
                                    tokenCount = stats.optInt("tokenCount"),
                                    tokensPerSecond = stats.optDouble("tokensPerSecond"),
                                ).takeIf { it.isUsable() }
                            },
                        ),
                    )
                }
            }
        }
    }
}

object SessionTitleRules {
    fun deriveTitle(messages: List<ChatMessage>): String =
        messages
            .firstOrNull { it.role == MessageRole.User && it.text.isNotBlank() }
            ?.text
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(28)
            ?: "新会话"
}

private data class ChatSession(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val messages: List<ChatMessage>,
)
