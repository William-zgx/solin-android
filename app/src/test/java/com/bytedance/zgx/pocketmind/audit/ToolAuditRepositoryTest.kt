package com.bytedance.zgx.pocketmind.audit

import com.bytedance.zgx.pocketmind.data.ToolAuditDao
import com.bytedance.zgx.pocketmind.data.ToolAuditEventEntity
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAuditRepositoryTest {
    @Test
    fun recordPersistsSanitizedSummaryAndSortedPermissions() {
        val dao = FakeToolAuditDao()
        val repository = ToolAuditRepository(dao)
        val apiKey = "sk-" + "a".repeat(32)

        repository.record(
            ToolAuditEvent(
                id = "audit-1",
                runId = "run-1",
                requestId = "request-1",
                toolName = "read_clipboard",
                skillId = "skill-1",
                eventType = ToolAuditEventType.ToolObserved,
                status = ToolStatus.Succeeded,
                riskLevel = RiskLevel.LowReadOnly,
                permissions = setOf(ToolPermission.ReadsClipboard, ToolPermission.ReadsDeviceContext),
                summary = "token $apiKey\nemail alice@example.com",
                createdAtMillis = 2_000L,
            ),
        )

        val stored = dao.events.single()
        assertEquals("ReadsClipboard,ReadsDeviceContext", stored.permissionsCsv)
        assertFalse(stored.summary.contains(apiKey))
        assertFalse(stored.summary.contains("alice@example.com"))
        assertTrue(stored.summary.contains("sk-[redacted]"))
        assertTrue(stored.summary.contains("[email]"))
        assertFalse(stored.summary.contains("\n"))
    }

    @Test
    fun recentAuditEventsMapsLatestRecordsAndHidesStoredSummariesFromUi() {
        val dao = FakeToolAuditDao()
        val repository = ToolAuditRepository(dao)
        val bearer = "Bearer " + "b".repeat(32)
        dao.insert(
            entity(
                id = "older",
                permissionsCsv = "ReadsFiles, RequiresAndroidRuntimePermission",
                summary = "older",
                createdAtMillis = 1_000L,
            ),
        )
        dao.insert(
            entity(
                id = "newer",
                permissionsCsv = "ReadsClipboard",
                summary = "Authorization: $bearer clipboard text should stay hidden",
                createdAtMillis = 3_000L,
            ),
        )

        val records = repository.recentAuditEvents(limit = 2)

        assertEquals(listOf("newer", "older"), records.map { it.id })
        assertEquals(listOf("ReadsClipboard"), records.first().permissions)
        assertEquals(
            listOf("ReadsFiles", "RequiresAndroidRuntimePermission"),
            records.last().permissions,
        )
        assertFalse(records.first().summary.contains(bearer))
        assertFalse(records.first().summary.contains("Authorization"))
        assertFalse(records.first().summary.contains("clipboard text"))
        assertEquals("工具执行成功，结果详情不在审计视图中展示。", records.first().summary)
    }

    @Test
    fun recentAuditEventsDoesNotExposeToolParametersFromPlannedSummary() {
        val dao = FakeToolAuditDao()
        val repository = ToolAuditRepository(dao)
        dao.insert(
            entity(
                id = "planned",
                eventType = ToolAuditEventType.ToolPlanned.name,
                permissionsCsv = "StartsExternalActivity",
                summary = "将在浏览器中搜索：private query should stay hidden",
                createdAtMillis = 1_000L,
            ),
        )

        val record = repository.recentAuditEvents(limit = 1).single()

        assertFalse(record.summary.contains("private query"))
        assertEquals("工具请求已记录，参数内容不在审计视图中展示。", record.summary)
    }

    private class FakeToolAuditDao : ToolAuditDao {
        private val mutableEvents = linkedMapOf<String, ToolAuditEventEntity>()

        val events: List<ToolAuditEventEntity>
            get() = mutableEvents.values.toList()

        override fun recent(limit: Int): List<ToolAuditEventEntity> =
            mutableEvents.values
                .sortedByDescending { it.createdAtMillis }
                .take(limit)

        override fun insert(event: ToolAuditEventEntity) {
            mutableEvents[event.id] = event
        }
    }

    private fun entity(
        id: String,
        permissionsCsv: String,
        summary: String,
        createdAtMillis: Long,
        eventType: String = ToolAuditEventType.ToolObserved.name,
    ): ToolAuditEventEntity =
        ToolAuditEventEntity(
            id = id,
            runId = "run-$id",
            requestId = "request-$id",
            toolName = "query_recent_files",
            skillId = null,
            eventType = eventType,
            status = ToolStatus.Succeeded.name,
            riskLevel = RiskLevel.LowReadOnly.name,
            permissionsCsv = permissionsCsv,
            summary = summary,
            createdAtMillis = createdAtMillis,
        )
}
