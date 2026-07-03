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
import com.bytedance.zgx.solin.evidence.NoOpEvidenceBlobStore
import com.bytedance.zgx.solin.module.ToolHandler
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ParallelToolExecutorTest {

    // ---- Providers ----

    private val dummyCalendar = object : CalendarAvailabilityProvider {
        override fun queryAvailability(window: com.bytedance.zgx.solin.device.CalendarAvailabilityWindow): CalendarAvailabilityReadResult =
            CalendarAvailabilityReadResult.Available(
                CalendarAvailabilityQuery.snapshotFromBusyIntervals(window = window, busyIntervals = emptyList()),
            )
    }
    private val dummyForeground = object : ForegroundAppProvider {
        override fun currentForegroundApp(): ForegroundAppReadResult =
            ForegroundAppReadResult.Available(
                ForegroundAppInfo(packageName = "x", appLabel = "x", lastTimeUsedMillis = 0L),
            )
    }
    private val dummyContacts = object : ContactSummaryProvider {
        override fun queryContacts(query: String, maxCount: Int): ContactSummaryReadResult =
            ContactSummaryReadResult.Available(emptyList())
    }
    private val dummyNotifications = object : NotificationSummaryProvider {
        override fun recentNotifications(maxCount: Int): NotificationSummaryReadResult =
            NotificationSummaryReadResult.Available(
                listOf(NotificationSummaryItem(id = 1, title = "t", isOngoing = false, postTimeMillis = 1L)),
            )
    }
    private val dummyFiles = object : RecentFileProvider {
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
    }

    private fun dummyWeb() = object : WebSearchProvider {
        override fun search(request: WebSearchRequest): WebSearchReadResult =
            WebSearchReadResult.Failed("unused")
    }

    private fun spec(
        name: String,
        capability: ToolCapability = ToolCapability.Orchestration,
        risk: RiskLevel = RiskLevel.LowReadOnly,
        confirm: ConfirmationPolicy = ConfirmationPolicy.NotRequired,
        mode: ToolExecutionMode = ToolExecutionMode.ConcurrentWhenIndependent,
    ) = ToolSpec(
        name = name,
        title = name,
        description = "test tool $name",
        inputSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
        capability = capability,
        riskLevel = risk,
        confirmationPolicy = confirm,
        executionMode = mode,
    )

    private data class Timing(val startMs: Long, val endMs: Long, val threadName: String)

    private fun buildExecutor(
        toolHandlers: Map<String, ToolHandler>,
        specs: List<ToolSpec>,
    ): RoutingToolExecutor {
        val registry = ToolRegistry(
            object : ToolProvider {
                override fun specs(): List<ToolSpec> = specs
            },
        )
        return RoutingToolExecutor(
            calendarAvailabilityProvider = dummyCalendar,
            foregroundAppProvider = dummyForeground,
            contactSummaryProvider = dummyContacts,
            notificationSummaryProvider = dummyNotifications,
            recentFileProvider = dummyFiles,
            webSearchProvider = dummyWeb(),
            delegate = object : ToolExecutor {
                override fun execute(request: ToolRequest): ToolResult =
                    request.failed(ToolErrorCode.UnknownTool, "delegate not reached for ${request.toolName}", retryable = false)
            },
            toolRegistry = registry,
            toolHandlers = toolHandlers,
            evidenceBlobStore = NoOpEvidenceBlobStore,
        )
    }

    @Test
    fun independentToolsRunConcurrentlyAndSequentialDeviceToolRunsFirst() {
        val timings = mutableMapOf<String, Timing>()
        val lock = Any()

        val delayMs = 200L

        val deviceHandler = ToolHandler { request ->
            val start = System.currentTimeMillis()
            val t = Thread.currentThread().name
            // Device-control tool is fast; does work serially before concurrent pool fires.
            synchronized(lock) {
                timings[request.toolName] = Timing(start, System.currentTimeMillis(), t)
            }
            request.succeeded(summary = "device done")
        }

        fun independentHandler(name: String) = ToolHandler { request ->
            val start = System.currentTimeMillis()
            // Delay to force observability of concurrency
            delay(delayMs)
            val end = System.currentTimeMillis()
            synchronized(lock) {
                timings[name] = Timing(start, end, Thread.currentThread().name)
            }
            request.succeeded(summary = "$name done")
        }

        val deviceToolName = "device_tap"
        val indyNames = listOf("ind_a", "ind_b", "ind_c")

        val specs = buildList {
            add(
                spec(
                    name = deviceToolName,
                    capability = ToolCapability.DeviceControl,
                    risk = RiskLevel.CriticalDeviceOrPayment,
                    confirm = ConfirmationPolicy.Required,
                    mode = ToolExecutionMode.Sequential,
                ),
            )
            indyNames.forEach { add(spec(it)) }
        }
        val handlers = buildMap<String, ToolHandler> {
            put(deviceToolName, deviceHandler)
            indyNames.forEach { put(it, independentHandler(it)) }
        }

        val executor = buildExecutor(handlers, specs)

        // Order matters: put device tool last to verify sequential segment reordering runs it first.
        val requests = (indyNames + deviceToolName).map { name ->
            ToolRequest(toolName = name)
        }

        val tStart = System.currentTimeMillis()
        val results = runBlocking { executor.executeBatch(requests) }
        val elapsed = System.currentTimeMillis() - tStart

        // (i) All four succeed.
        assertEquals(4, results.size)
        results.forEachIndexed { i, r ->
            assertEquals("result[$i] ${requests[i].toolName} should succeed, got ${r.status} ${r.summary}", ToolStatus.Succeeded, r.status)
        }

        // (ii) Total elapsed < 600ms proves concurrent independent execution (sequential would be >= 600ms).
        assertTrue(
            "Expected total elapsed < 600ms but was ${elapsed}ms (concurrent independent tools should run in parallel)",
            elapsed < 600L,
        )

        // (iii) Sequential device tool completed before any independent tool even started
        // (sequential segment runs first and device is fast/non-sleeping).
        val deviceTiming = synchronized(lock) { timings[deviceToolName] }
        assertNotNull(deviceTiming)
        val indyStarts = indyNames.mapNotNull { synchronized(lock) { timings[it]?.startMs } }
        assertEquals(3, indyStarts.size)
        val earliestIndyStart = indyStarts.minOrNull()!!
        assertTrue(
            "Device-control tool (end=${deviceTiming!!.endMs}) should finish before earliest independent start=$earliestIndyStart",
            deviceTiming.endMs <= earliestIndyStart + 50, // 50ms slack for scheduler
        )
    }

    @Test
    fun exceptionInOneIndependentToolDoesNotCrashSiblings() {
        val timings = mutableMapOf<String, Boolean>()
        val lock = Any()

        val delayMs = 200L
        val crashName = "crashy"
        val okNames = listOf("ok_a", "ok_b")
        val allNames = okNames + crashName

        val specs = allNames.map { spec(it) }
        val handlers = buildMap<String, ToolHandler> {
            put(crashName, ToolHandler { _ ->
                delay(delayMs / 2)
                error("boom from $crashName")
            })
            okNames.forEach { name ->
                put(name, ToolHandler { req ->
                    delay(delayMs)
                    synchronized(lock) { timings[name] = true }
                    req.succeeded(summary = "$name ok")
                })
            }
        }
        val executor = buildExecutor(handlers, specs)
        val requests = allNames.map { ToolRequest(toolName = it) }

        val tStart = System.currentTimeMillis()
        val results = runBlocking { executor.executeBatch(requests) }
        val elapsed = System.currentTimeMillis() - tStart

        assertEquals(allNames.size, results.size)
        // All results present: crashy is Failed; siblings are Succeeded.
        val byName = requests.zip(results).associate { (req, res) -> req.toolName to res }
        assertEquals(ToolStatus.Failed, byName[crashName]?.status)
        okNames.forEach { name ->
            assertEquals("$name should succeed", ToolStatus.Succeeded, byName[name]?.status)
        }
        // Both siblings actually ran.
        okNames.forEach { name ->
            synchronized(lock) { assertTrue("$name should have executed", timings[name] == true) }
        }
        // Siblings ran concurrently with the failing tool, so total time ~ delayMs not multiples.
        assertTrue(
            "Siblings should run concurrently with failing tool; elapsed=$elapsed ms",
            elapsed < delayMs * 2 + 100,
        )
    }
}
