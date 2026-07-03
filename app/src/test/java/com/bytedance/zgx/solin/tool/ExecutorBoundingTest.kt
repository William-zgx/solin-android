package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.device.CalendarAvailabilityProvider
import com.bytedance.zgx.solin.device.CalendarAvailabilityQuery
import com.bytedance.zgx.solin.device.CalendarAvailabilityReadResult
import com.bytedance.zgx.solin.device.ContactSummaryItem
import com.bytedance.zgx.solin.device.ContactSummaryProvider
import com.bytedance.zgx.solin.device.ContactSummaryReadResult
import com.bytedance.zgx.solin.device.ForegroundAppInfo
import com.bytedance.zgx.solin.device.ForegroundAppProvider
import com.bytedance.zgx.solin.device.ForegroundAppReadResult
import com.bytedance.zgx.solin.device.NotificationSummaryItem
import com.bytedance.zgx.solin.device.NotificationSummaryProvider
import com.bytedance.zgx.solin.device.NotificationSummaryReadResult
import com.bytedance.zgx.solin.device.RecentFileItem
import com.bytedance.zgx.solin.device.RecentFileProvider
import com.bytedance.zgx.solin.device.RecentFileReadResult
import com.bytedance.zgx.solin.evidence.EvidenceBlobStore
import com.bytedance.zgx.solin.evidence.NoOpEvidenceBlobStore
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutorBoundingTest {

    @Test
    fun longSummaryIsTruncatedWithNoOpStore() {
        val longText = "x".repeat(EvidenceBlobStore.MAX_INLINE_CHARS * 2)
        val captured = java.util.concurrent.atomic.AtomicReference<ToolResult>()
        val handler = com.bytedance.zgx.solin.module.ToolHandler { request ->
            val r = request.succeeded(summary = longText, data = mapOf("key" to longText))
            captured.set(r)
            r
        }
        val executor = buildExecutor(toolHandlers = mapOf("fake_long" to handler))
        val request = ToolRequest(id = "r1", toolName = "fake_long", arguments = emptyMap())
        val result = executor.execute(request)
        assertEquals(ToolStatus.Succeeded, result.status)
        // Summary must be bounded
        assertTrue("summary length ${result.summary.length} should be <= ${EvidenceBlobStore.MAX_INLINE_CHARS + 10}",
            result.summary.length <= EvidenceBlobStore.MAX_INLINE_CHARS + 10)
        // NoOp store -> no overflow refs
        assertEquals(0, result.overflowRefs.size)
    }

    @Test
    fun shortOutputIsNotModified() {
        val shortText = "short summary"
        val handler = com.bytedance.zgx.solin.module.ToolHandler { request ->
            request.succeeded(summary = shortText, data = mapOf("key" to "v"))
        }
        val executor = buildExecutor(toolHandlers = mapOf("fake_short" to handler))
        val request = ToolRequest(id = "r2", toolName = "fake_short", arguments = emptyMap())
        val result = executor.execute(request)
        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(shortText, result.summary)
        assertEquals("v", result.data["key"])
        assertEquals(0, result.overflowRefs.size)
    }

    @Test
    fun longDataValueIsTruncated() {
        val longBlob = "y".repeat(EvidenceBlobStore.MAX_INLINE_CHARS * 2)
        val handler = com.bytedance.zgx.solin.module.ToolHandler { request ->
            request.succeeded(summary = "ok", data = mapOf("blob" to longBlob))
        }
        val executor = buildExecutor(toolHandlers = mapOf("fake_blob" to handler))
        val request = ToolRequest(id = "r3", toolName = "fake_blob", arguments = emptyMap())
        val result = executor.execute(request)
        assertEquals(ToolStatus.Succeeded, result.status)
        val boundedBlob = result.data["blob"]!!
        assertTrue("blob length ${boundedBlob.length} should be <= ${EvidenceBlobStore.MAX_INLINE_CHARS + 10}",
            boundedBlob.length <= EvidenceBlobStore.MAX_INLINE_CHARS + 10)
        assertTrue(boundedBlob.endsWith("..."))
        assertEquals(0, result.overflowRefs.size)
    }

    private fun buildExecutor(
        toolHandlers: Map<String, com.bytedance.zgx.solin.module.ToolHandler> = emptyMap(),
        evidenceBlobStore: EvidenceBlobStore = NoOpEvidenceBlobStore,
    ): RoutingToolExecutor =
        RoutingToolExecutor(
            calendarAvailabilityProvider = object : CalendarAvailabilityProvider {
                override fun queryAvailability(window: com.bytedance.zgx.solin.device.CalendarAvailabilityWindow): CalendarAvailabilityReadResult =
                    CalendarAvailabilityReadResult.Available(
                        CalendarAvailabilityQuery.snapshotFromBusyIntervals(window = window, busyIntervals = emptyList()),
                    )
            },
            foregroundAppProvider = object : ForegroundAppProvider {
                override fun currentForegroundApp(): ForegroundAppReadResult =
                    ForegroundAppReadResult.Available(
                        ForegroundAppInfo(packageName = "x", appLabel = "x", lastTimeUsedMillis = 0L),
                    )
            },
            contactSummaryProvider = object : ContactSummaryProvider {
                override fun queryContacts(query: String, maxCount: Int): ContactSummaryReadResult =
                    ContactSummaryReadResult.Available(emptyList())
            },
            notificationSummaryProvider = object : NotificationSummaryProvider {
                override fun recentNotifications(maxCount: Int): NotificationSummaryReadResult =
                    NotificationSummaryReadResult.Available(
                        listOf(NotificationSummaryItem(id = 1, title = "t", isOngoing = false, postTimeMillis = 1L)),
                    )
            },
            recentFileProvider = object : RecentFileProvider {
                override fun recentFiles(kind: String, maxCount: Int): RecentFileReadResult =
                    RecentFileReadResult.Available(
                        listOf(
                            RecentFileItem(
                                id = 1L,
                                name = "f",
                                mimeType = "text/plain",
                                kind = "all",
                                sizeBytes = 1L,
                                lastModifiedMillis = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
                            ),
                        ),
                    )
            },
            webSearchProvider = object : WebSearchProvider {
                override fun search(request: WebSearchRequest): WebSearchReadResult =
                    WebSearchReadResult.Failed("unused")
            },
            delegate = object : ToolExecutor {
                override fun execute(request: ToolRequest): ToolResult =
                    ToolResult(requestId = request.id, status = ToolStatus.Failed, summary = "not reached")
            },
            toolHandlers = toolHandlers,
            evidenceBlobStore = evidenceBlobStore,
        )
}
