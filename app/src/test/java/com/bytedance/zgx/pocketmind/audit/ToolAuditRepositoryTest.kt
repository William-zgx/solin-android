package com.bytedance.zgx.pocketmind.audit

import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.data.ToolAuditDao
import com.bytedance.zgx.pocketmind.data.ToolAuditEventEntity
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import com.bytedance.zgx.pocketmind.tool.UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX
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

    @Test
    fun recordDoesNotPersistToolParametersFromPlannedSummary() {
        val dao = FakeToolAuditDao()
        val repository = ToolAuditRepository(dao)

        repository.record(
            ToolAuditEvent(
                id = "planned",
                runId = "run-1",
                requestId = "request-1",
                toolName = MobileActionFunctions.WEB_SEARCH,
                skillId = null,
                eventType = ToolAuditEventType.ToolPlanned,
                permissions = setOf(ToolPermission.StartsExternalActivity),
                summary = "将在浏览器中搜索：private query should stay hidden",
                createdAtMillis = 1_000L,
            ),
        )

        val stored = dao.events.single()
        assertFalse(stored.summary.contains("private query"))
        assertEquals("Tool request planned.", stored.summary)
    }

    @Test
    fun unverifiedExternalLaunchAuditDoesNotClaimExecutionSuccess() {
        val dao = FakeToolAuditDao()
        val repository = ToolAuditRepository(dao)
        dao.insert(
            entity(
                id = "launch-only",
                permissionsCsv = "StartsExternalActivity",
                summary = "$UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX：已打开系统分享面板",
                createdAtMillis = 1_000L,
            ),
        )

        val record = repository.recentAuditEvents(limit = 1).single()

        assertEquals("外部界面已打开，最终结果未验证。", record.summary)
    }

    @Test
    fun recentReminderAuditShowsTaskMetadataWithoutReminderContent() {
        val dao = FakeToolAuditDao()
        val repository = ToolAuditRepository(dao)
        val apiKey = "sk-" + "a".repeat(32)
        repository.record(
            ToolAuditEvent(
                id = "reminder",
                runId = "run-reminder",
                requestId = "request-reminder",
                toolName = MobileActionFunctions.SCHEDULE_REMINDER,
                skillId = null,
                eventType = ToolAuditEventType.ToolObserved,
                status = ToolStatus.Succeeded,
                riskLevel = RiskLevel.MediumDraftOrNavigation,
                permissions = setOf(ToolPermission.SchedulesBackgroundWork, ToolPermission.PostsNotification),
                summary = "已安排后台提醒：喝水 title=喝水 body=提醒我喝水 email alice@example.com token $apiKey " +
                    "(taskId=task-1; taskStatus=Scheduled; triggerAtMillis=10000; " +
                    "recoveryToolName=cancel_reminder; recoveryTaskId=task-1)",
                createdAtMillis = 1_000L,
            ),
        )

        val record = repository.recentAuditEvents(limit = 1).single()

        assertTrue(record.summary.contains("taskId=task-1"))
        assertTrue(record.summary.contains("taskStatus=Scheduled"))
        assertTrue(record.summary.contains("triggerAtMillis=10000"))
        assertTrue(record.summary.contains("recoveryToolName=cancel_reminder"))
        assertTrue(record.summary.contains("recoveryTaskId=task-1"))
        assertFalse(record.summary.contains("喝水"))
        assertFalse(record.summary.contains("提醒我喝水"))
        assertFalse(record.summary.contains("alice@example.com"))
        assertFalse(record.summary.contains(apiKey))
        assertFalse(record.summary.contains("title="))
        assertFalse(record.summary.contains("body="))
    }

    @Test
    fun recentCancelReminderAuditShowsTaskMetadataWithoutReminderContent() {
        val dao = FakeToolAuditDao()
        val repository = ToolAuditRepository(dao)
        val apiKey = "sk-" + "b".repeat(32)
        repository.record(
            ToolAuditEvent(
                id = "cancel-reminder",
                runId = "run-cancel",
                requestId = "request-cancel",
                toolName = MobileActionFunctions.CANCEL_REMINDER,
                skillId = null,
                eventType = ToolAuditEventType.ToolObserved,
                status = ToolStatus.Succeeded,
                riskLevel = RiskLevel.MediumDraftOrNavigation,
                permissions = setOf(ToolPermission.SchedulesBackgroundWork),
                summary = "已取消后台任务：喝水 body=提醒我喝水 email alice@example.com token $apiKey " +
                    "(taskId=task-1; taskStatus=Cancelled)",
                createdAtMillis = 1_000L,
            ),
        )

        val record = repository.recentAuditEvents(limit = 1).single()

        assertTrue(record.summary.contains("taskId=task-1"))
        assertTrue(record.summary.contains("taskStatus=Cancelled"))
        assertFalse(record.summary.contains("喝水"))
        assertFalse(record.summary.contains("提醒我喝水"))
        assertFalse(record.summary.contains("alice@example.com"))
        assertFalse(record.summary.contains(apiKey))
        assertFalse(record.summary.contains("body="))
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
