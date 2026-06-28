package com.bytedance.zgx.solin.audit

import com.bytedance.zgx.solin.data.RemoteSendAuditDao
import com.bytedance.zgx.solin.data.RemoteSendAuditEventEntity
import com.bytedance.zgx.solin.safety.SafetyCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSendAuditRepositoryTest {
    @Test
    fun recordPersistsRedactedStructuredRemoteSendEvent() {
        val dao = FakeRemoteSendAuditDao()
        val repository = RemoteSendAuditRepository(dao)
        val apiKey = "sk-" + "a".repeat(32)

        repository.record(
            RemoteSendAuditEvent(
                id = "remote-1",
                decision = RemoteSendDecision.SentAnyway,
                modelName = "model-a",
                sensitiveCategories = listOf(SafetyCategory.Email, SafetyCategory.SecretToken),
                imageCount = 1,
                remoteHistoryCount = 2,
                summary = "sent email alice@example.com token $apiKey",
                createdAtMillis = 2_000L,
            ),
        )

        val stored = dao.events.single()
        assertEquals("remote-1", stored.id)
        assertEquals(RemoteSendDecision.SentAnyway.name, stored.decision)
        assertEquals("Email,SecretToken", stored.sensitiveCategoriesCsv)
        assertFalse(stored.summary.contains("alice@example.com"))
        assertFalse(stored.summary.contains(apiKey))

        val event = repository.recentRemoteSends().single()
        assertEquals(RemoteSendDecision.SentAnyway, event.decision)
        assertEquals("model-a", event.modelName)
        assertEquals(listOf(SafetyCategory.Email, SafetyCategory.SecretToken), event.sensitiveCategories)
        assertEquals(1, event.imageCount)
        assertEquals(2, event.remoteHistoryCount)
        assertTrue(event.summary.contains("[email]"))
        assertTrue(event.summary.contains("sk-[redacted]"))
    }

    @Test
    fun recordPrunesOldRemoteSendEvents() {
        val dao = FakeRemoteSendAuditDao()
        val repository = RemoteSendAuditRepository(dao, maxStoredEvents = 2)

        listOf("oldest", "middle", "newest").forEachIndexed { index, id ->
            repository.record(
                RemoteSendAuditEvent(
                    id = id,
                    decision = RemoteSendDecision.Confirmed,
                    modelName = "model-a",
                    summary = "confirmed",
                    createdAtMillis = index.toLong(),
                ),
            )
        }

        assertEquals(listOf("newest", "middle"), repository.recentRemoteSends(limit = 10).map { it.id })
    }

    private class FakeRemoteSendAuditDao : RemoteSendAuditDao {
        private val mutableEvents = linkedMapOf<String, RemoteSendAuditEventEntity>()

        val events: List<RemoteSendAuditEventEntity>
            get() = mutableEvents.values.sortedByDescending { it.createdAtMillis }

        override fun recent(limit: Int): List<RemoteSendAuditEventEntity> =
            events.take(limit)

        override fun insert(event: RemoteSendAuditEventEntity) {
            mutableEvents[event.id] = event
        }

        override fun pruneToMostRecent(maxRecords: Int): Int {
            val kept = events.take(maxRecords).mapTo(linkedSetOf()) { it.id }
            val before = mutableEvents.size
            mutableEvents.keys.toList()
                .filterNot(kept::contains)
                .forEach(mutableEvents::remove)
            return before - mutableEvents.size
        }
    }
}
