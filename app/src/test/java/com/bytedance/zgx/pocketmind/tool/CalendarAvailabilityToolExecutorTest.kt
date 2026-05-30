package com.bytedance.zgx.pocketmind.tool

import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityProvider
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityQuery
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityReadResult
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityWindow
import com.bytedance.zgx.pocketmind.device.CalendarBusyInterval
import java.time.Instant
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarAvailabilityToolExecutorTest {
    @Test
    fun returnsLocalOnlyMinimalFreeBusyBlocks() {
        val provider = FakeCalendarAvailabilityProvider { window ->
            CalendarAvailabilityReadResult.Available(
                CalendarAvailabilityQuery.snapshotFromBusyIntervals(
                    window = window,
                    busyIntervals = listOf(
                        CalendarBusyInterval(
                            start = Instant.parse("2026-06-01T10:00:00Z"),
                            end = Instant.parse("2026-06-01T11:00:00Z"),
                        ),
                    ),
                ),
            )
        }
        val executor = CalendarAvailabilityToolExecutor(provider)

        val result = executor.execute(calendarRequest())

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("1", result.data["busyBlockCount"])
        assertEquals("2", result.data["freeBlockCount"])
        val blocks = JSONArray(result.data.getValue("blocksJson"))
        assertEquals(3, blocks.length())
        assertEquals("free", blocks.getJSONObject(0).getString("status"))
        assertEquals("busy", blocks.getJSONObject(1).getString("status"))
        assertEquals(setOf("status", "start", "end"), blocks.getJSONObject(1).keysSet())
        assertFalse(result.data.toString().contains("title", ignoreCase = true))
        assertFalse(result.data.toString().contains("location", ignoreCase = true))
        assertFalse(result.data.toString().contains("attendee", ignoreCase = true))
        assertEquals(Instant.parse("2026-06-01T09:00:00Z"), provider.lastWindow?.start)
        assertEquals(Instant.parse("2026-06-01T13:00:00Z"), provider.lastWindow?.end)
    }

    @Test
    fun reportsMissingCalendarPermissionAsStructuredFailure() {
        val executor = CalendarAvailabilityToolExecutor(
            FakeCalendarAvailabilityProvider { CalendarAvailabilityReadResult.MissingPermission },
        )

        val result = executor.execute(calendarRequest())

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.PermissionDenied, result.error?.code)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertTrue(result.summary.contains("日历读取权限"))
    }

    @Test
    fun rejectsInvalidOrTooWideWindowBeforeQueryingProvider() {
        val provider = FakeCalendarAvailabilityProvider {
            error("Provider should not be called for invalid windows")
        }
        val executor = CalendarAvailabilityToolExecutor(provider)

        val invalidIso = executor.execute(
            calendarRequest(
                start = "2026-06-01 09:00",
                end = "2026-06-01T10:00:00Z",
            ),
        )
        assertEquals(ToolStatus.Failed, invalidIso.status)
        assertEquals(ToolErrorCode.InvalidRequest, invalidIso.error?.code)
        assertFalse(invalidIso.retryable)

        val tooWide = executor.execute(
            calendarRequest(
                start = "2026-06-01T00:00:00Z",
                end = "2026-07-03T00:00:00Z",
            ),
        )
        assertEquals(ToolStatus.Failed, tooWide.status)
        assertEquals(ToolErrorCode.InvalidRequest, tooWide.error?.code)
        assertFalse(tooWide.retryable)
        assertEquals(0, provider.queryCount)
    }

    private fun calendarRequest(
        start: String = "2026-06-01T09:00:00Z",
        end: String = "2026-06-01T13:00:00Z",
    ): ToolRequest =
        ToolRequest(
            id = "request-calendar",
            toolName = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
            arguments = mapOf(
                "start" to start,
                "end" to end,
            ),
            reason = "test",
        )

    private class FakeCalendarAvailabilityProvider(
        private val resultFactory: (CalendarAvailabilityWindow) -> CalendarAvailabilityReadResult,
    ) : CalendarAvailabilityProvider {
        var lastWindow: CalendarAvailabilityWindow? = null
            private set
        var queryCount: Int = 0
            private set

        override fun queryAvailability(window: CalendarAvailabilityWindow): CalendarAvailabilityReadResult {
            queryCount += 1
            lastWindow = window
            return resultFactory(window)
        }
    }

    private fun org.json.JSONObject.keysSet(): Set<String> {
        val result = linkedSetOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) {
            result += iterator.next()
        }
        return result
    }
}
