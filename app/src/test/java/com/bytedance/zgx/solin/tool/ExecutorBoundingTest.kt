package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.device.CalendarAvailabilityProvider
import com.bytedance.zgx.solin.device.CalendarAvailabilityQuery
import com.bytedance.zgx.solin.device.CalendarAvailabilityReadResult
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
import com.bytedance.zgx.solin.evidence.EvidenceBlobRef
import com.bytedance.zgx.solin.evidence.EvidenceBlobStore
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
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
        val handler = ToolHandler { request ->
            val r = request.succeeded(summary = longText, data = mapOf("key" to longText))
            captured.set(r)
            r
        }
        val executor = buildExecutor(toolHandlers = mapOf("fake_long" to handler))
        val request = ToolRequest(id = "r1", toolName = "fake_long", arguments = emptyMap())
        val result = executor.execute(request)
        assertEquals(ToolStatus.Succeeded, result.status)
        // Summary must be bounded
        assertTrue(
            "summary length ${result.summary.length} should be <= ${EvidenceBlobStore.MAX_INLINE_CHARS + 10}",
            result.summary.length <= EvidenceBlobStore.MAX_INLINE_CHARS + 10
        )
        // NoOp store -> no overflow refs
        assertEquals(0, result.overflowRefs.size)
    }

    @Test
    fun shortOutputIsNotModified() {
        val shortText = "short summary"
        val handler = ToolHandler { request ->
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
        val handler = ToolHandler { request ->
            request.succeeded(summary = "ok", data = mapOf("blob" to longBlob))
        }
        val executor = buildExecutor(toolHandlers = mapOf("fake_blob" to handler))
        val request = ToolRequest(id = "r3", toolName = "fake_blob", arguments = emptyMap())
        val result = executor.execute(request)
        assertEquals(ToolStatus.Succeeded, result.status)
        val boundedBlob = result.data["blob"]!!
        assertTrue(
            "blob length ${boundedBlob.length} should be <= ${EvidenceBlobStore.MAX_INLINE_CHARS + 10}",
            boundedBlob.length <= EvidenceBlobStore.MAX_INLINE_CHARS + 10
        )
        assertTrue(boundedBlob.endsWith("..."))
        assertEquals(0, result.overflowRefs.size)
    }

    @Test
    fun batchBoundsEachResultWithoutChangingInputOrder() {
        val longText = "z".repeat(EvidenceBlobStore.MAX_INLINE_CHARS * 2)
        val handlers = (1..3).associate { index ->
            "fake_$index" to ToolHandler { request ->
                request.succeeded(
                    summary = "summary-$index",
                    data = mapOf("value" to if (index == 2) longText else "value-$index")
                )
            }
        }
        val executor = buildExecutor(toolHandlers = handlers)
        val requests = listOf(3, 1, 2).map { index ->
            ToolRequest(id = "request-$index", toolName = "fake_$index", arguments = emptyMap())
        }

        val results = executor.executeBatch(requests)

        assertEquals(requests.map { it.id }, results.map { it.requestId })
        assertEquals("value-3", results[0].data["value"])
        assertEquals("value-1", results[1].data["value"])
        assertTrue(results[2].data.getValue("value").endsWith("..."))
        assertTrue(results[2].data.getValue("value").length < longText.length)
    }

    @Test
    fun concurrentBatchBoundingFailureIsIsolatedToFailingRequest() {
        val failingText = "f".repeat(EvidenceBlobStore.MAX_INLINE_CHARS * 2)
        val goodText = "g".repeat(EvidenceBlobStore.MAX_INLINE_CHARS * 2)
        val registry = ToolRegistry(
            ToolProvider {
                listOf("fake_fail", "fake_good").map { name ->
                    ToolSpec(
                        name = name,
                        title = name,
                        description = name,
                        inputSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
                        capability = ToolCapability.Orchestration,
                        riskLevel = RiskLevel.LowReadOnly,
                        confirmationPolicy = ConfirmationPolicy.NotRequired,
                        executionMode = ToolExecutionMode.ConcurrentWhenIndependent
                    )
                }
            }
        )
        val executor = buildExecutor(
            toolHandlers = mapOf(
                "fake_fail" to ToolHandler { request ->
                    request.succeeded(summary = "failed write", data = mapOf("value" to failingText))
                },
                "fake_good" to ToolHandler { request ->
                    request.succeeded(summary = "stored", data = mapOf("value" to goodText))
                }
            ),
            evidenceBlobStore = ThrowingEvidenceBlobStore(failingText),
            toolRegistry = registry
        )
        val requests = listOf("fake_fail", "fake_good").map { name ->
            ToolRequest(id = name, toolName = name, arguments = emptyMap())
        }

        val results = executor.executeBatch(requests)

        assertEquals(requests.map { it.id }, results.map { it.requestId })
        assertEquals(ToolStatus.Failed, results[0].status)
        assertEquals(MessagePrivacy.LocalOnly.name, results[0].data["privacy"])
        assertEquals(ToolStatus.Succeeded, results[1].status)
        assertEquals(1, results[1].overflowRefs.size)
    }

    private fun buildExecutor(
        toolHandlers: Map<String, ToolHandler> = emptyMap(),
        evidenceBlobStore: EvidenceBlobStore = NoOpEvidenceBlobStore,
        toolRegistry: ToolRegistry = defaultBuiltInToolRegistry
    ): ToolExecutor = EvidenceBoundingToolExecutor(
        delegate = RoutingToolExecutor(
            calendarAvailabilityProvider = object : CalendarAvailabilityProvider {
                override fun queryAvailability(window: com.bytedance.zgx.solin.device.CalendarAvailabilityWindow): CalendarAvailabilityReadResult = CalendarAvailabilityReadResult.Available(
                    CalendarAvailabilityQuery.snapshotFromBusyIntervals(window = window, busyIntervals = emptyList())
                )
            },
            foregroundAppProvider = object : ForegroundAppProvider {
                override fun currentForegroundApp(): ForegroundAppReadResult = ForegroundAppReadResult.Available(
                    ForegroundAppInfo(packageName = "x", appLabel = "x", lastTimeUsedMillis = 0L)
                )
            },
            contactSummaryProvider = object : ContactSummaryProvider {
                override fun queryContacts(query: String, maxCount: Int): ContactSummaryReadResult = ContactSummaryReadResult.Available(emptyList())
            },
            notificationSummaryProvider = object : NotificationSummaryProvider {
                override fun recentNotifications(maxCount: Int): NotificationSummaryReadResult = NotificationSummaryReadResult.Available(
                    listOf(NotificationSummaryItem(id = 1, title = "t", isOngoing = false, postTimeMillis = 1L))
                )
            },
            recentFileProvider = object : RecentFileProvider {
                override fun recentFiles(kind: String, maxCount: Int): RecentFileReadResult = RecentFileReadResult.Available(
                    listOf(
                        RecentFileItem(
                            id = 1L,
                            name = "f",
                            mimeType = "text/plain",
                            kind = "all",
                            sizeBytes = 1L,
                            lastModifiedMillis = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
                        )
                    )
                )
            },
            webSearchProvider = object : WebSearchProvider {
                override fun search(request: WebSearchRequest): WebSearchReadResult = WebSearchReadResult.Failed("unused")
            },
            delegate = object : ToolExecutor {
                override fun execute(request: ToolRequest): ToolResult = ToolResult(requestId = request.id, status = ToolStatus.Failed, summary = "not reached")
            },
            toolHandlers = toolHandlers,
            toolRegistry = toolRegistry
        ),
        evidenceBlobStore = evidenceBlobStore
    )
}

private class ThrowingEvidenceBlobStore(
    private val failingText: String
) : EvidenceBlobStore by NoOpEvidenceBlobStore {
    override fun putText(
        text: String,
        sourceType: EvidenceSourceType,
        privacy: MessagePrivacy,
        ttlMs: Long
    ): EvidenceBlobRef {
        if (text == failingText) throw java.io.IOException("simulated evidence write failure")
        return EvidenceBlobRef.fromSha256(
            sha256 = "a".repeat(64),
            sizeBytes = text.length.toLong(),
            mimeType = "text/plain",
            privacy = privacy,
            sourceType = sourceType
        )
    }
}
