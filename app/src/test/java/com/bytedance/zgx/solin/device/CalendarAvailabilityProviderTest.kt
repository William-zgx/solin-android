package com.bytedance.zgx.solin.device

import android.provider.CalendarContract
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarAvailabilityProviderTest {
    @Test
    fun parsesIsoWindowAndRejectsInvalidRanges() {
        val valid = CalendarAvailabilityQuery.parseWindow(
            startIso = "2026-06-01T09:00:00+08:00",
            endIso = "2026-06-01T10:00:00+08:00",
        )

        require(valid is CalendarAvailabilityQueryValidation.Valid)
        assertEquals(Instant.parse("2026-06-01T01:00:00Z"), valid.window.start)
        assertEquals(Instant.parse("2026-06-01T02:00:00Z"), valid.window.end)

        val reversed = CalendarAvailabilityQuery.parseWindow(
            startIso = "2026-06-01T10:00:00Z",
            endIso = "2026-06-01T09:00:00Z",
        )
        assertTrue(reversed is CalendarAvailabilityQueryValidation.Invalid)

        val tooWide = CalendarAvailabilityQuery.parseWindow(
            startIso = "2026-06-01T00:00:00Z",
            endIso = "2026-07-03T00:00:00Z",
        )
        assertTrue(tooWide is CalendarAvailabilityQueryValidation.Invalid)
    }

    @Test
    fun mergesBusyIntervalsAndEmitsOnlyFreeBusyBlocks() {
        val window = CalendarAvailabilityWindow(
            start = Instant.parse("2026-06-01T09:00:00Z"),
            end = Instant.parse("2026-06-01T13:00:00Z"),
        )

        val snapshot = CalendarAvailabilityQuery.snapshotFromBusyIntervals(
            window = window,
            busyIntervals = listOf(
                CalendarBusyInterval(
                    start = Instant.parse("2026-06-01T10:00:00Z"),
                    end = Instant.parse("2026-06-01T11:00:00Z"),
                ),
                CalendarBusyInterval(
                    start = Instant.parse("2026-06-01T10:30:00Z"),
                    end = Instant.parse("2026-06-01T12:00:00Z"),
                ),
            ),
        )

        assertEquals(1, snapshot.busyBlockCount)
        assertEquals(2, snapshot.freeBlockCount)
        assertEquals(
            listOf(
                CalendarAvailabilityBlockStatus.Free,
                CalendarAvailabilityBlockStatus.Busy,
                CalendarAvailabilityBlockStatus.Free,
            ),
            snapshot.blocks.map { it.status },
        )
        assertEquals(Instant.parse("2026-06-01T10:00:00Z"), snapshot.blocks[1].start)
        assertEquals(Instant.parse("2026-06-01T12:00:00Z"), snapshot.blocks[1].end)
    }

    @Test
    fun androidProjectionDoesNotReadSensitiveCalendarFields() {
        val projection = AndroidCalendarAvailabilityProvider.PROJECTION.toSet()

        assertTrue(CalendarContract.Instances.BEGIN in projection)
        assertTrue(CalendarContract.Instances.END in projection)
        assertTrue(CalendarContract.Instances.AVAILABILITY in projection)
        assertTrue(CalendarContract.Instances.STATUS in projection)
        assertFalse(CalendarContract.Instances.TITLE in projection)
        assertFalse(CalendarContract.Instances.EVENT_LOCATION in projection)
        assertFalse(CalendarContract.Instances.DESCRIPTION in projection)
        assertFalse(CalendarContract.Instances.ORGANIZER in projection)
    }
}
