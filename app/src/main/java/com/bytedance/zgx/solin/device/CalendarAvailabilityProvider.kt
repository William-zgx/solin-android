package com.bytedance.zgx.solin.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class CalendarAvailabilityWindow(
    val start: Instant,
    val end: Instant,
)

data class CalendarBusyInterval(
    val start: Instant,
    val end: Instant,
)

enum class CalendarAvailabilityBlockStatus(
    val wireValue: String,
) {
    Busy("busy"),
    Free("free"),
}

data class CalendarAvailabilityBlock(
    val status: CalendarAvailabilityBlockStatus,
    val start: Instant,
    val end: Instant,
)

data class CalendarAvailabilitySnapshot(
    val window: CalendarAvailabilityWindow,
    val blocks: List<CalendarAvailabilityBlock>,
) {
    val busyBlockCount: Int =
        blocks.count { it.status == CalendarAvailabilityBlockStatus.Busy }
    val freeBlockCount: Int =
        blocks.count { it.status == CalendarAvailabilityBlockStatus.Free }
}

sealed class CalendarAvailabilityQueryValidation {
    data class Valid(
        val window: CalendarAvailabilityWindow,
    ) : CalendarAvailabilityQueryValidation()

    data class Invalid(
        val reason: String,
    ) : CalendarAvailabilityQueryValidation()
}

sealed class CalendarAvailabilityReadResult {
    data class Available(
        val snapshot: CalendarAvailabilitySnapshot,
    ) : CalendarAvailabilityReadResult()

    data object MissingPermission : CalendarAvailabilityReadResult()

    data class Failed(
        val reason: String,
    ) : CalendarAvailabilityReadResult()
}

fun interface CalendarAvailabilityProvider {
    fun queryAvailability(window: CalendarAvailabilityWindow): CalendarAvailabilityReadResult
}

object CalendarAvailabilityQuery {
    const val MAX_QUERY_DAYS: Long = 31L

    fun parseWindow(
        startIso: String?,
        endIso: String?,
    ): CalendarAvailabilityQueryValidation {
        val startText = startIso?.trim().orEmpty()
        val endText = endIso?.trim().orEmpty()
        if (startText.isBlank() || endText.isBlank()) {
            return CalendarAvailabilityQueryValidation.Invalid("start 和 end 必须是带时区的 ISO-8601 时间")
        }
        val start = parseIsoInstant(startText)
            ?: return CalendarAvailabilityQueryValidation.Invalid("start 必须是带时区的 ISO-8601 时间")
        val end = parseIsoInstant(endText)
            ?: return CalendarAvailabilityQueryValidation.Invalid("end 必须是带时区的 ISO-8601 时间")
        if (!end.isAfter(start)) {
            return CalendarAvailabilityQueryValidation.Invalid("end 必须晚于 start")
        }
        if (Duration.between(start, end) > Duration.ofDays(MAX_QUERY_DAYS)) {
            return CalendarAvailabilityQueryValidation.Invalid("日历查询窗口不能超过 $MAX_QUERY_DAYS 天")
        }
        return CalendarAvailabilityQueryValidation.Valid(CalendarAvailabilityWindow(start, end))
    }

    fun snapshotFromBusyIntervals(
        window: CalendarAvailabilityWindow,
        busyIntervals: List<CalendarBusyInterval>,
    ): CalendarAvailabilitySnapshot {
        val mergedBusy = mergeBusyIntervals(window, busyIntervals)
        val blocks = mutableListOf<CalendarAvailabilityBlock>()
        var cursor = window.start
        mergedBusy.forEach { busy ->
            if (busy.start.isAfter(cursor)) {
                blocks += CalendarAvailabilityBlock(CalendarAvailabilityBlockStatus.Free, cursor, busy.start)
            }
            blocks += CalendarAvailabilityBlock(CalendarAvailabilityBlockStatus.Busy, busy.start, busy.end)
            if (busy.end.isAfter(cursor)) {
                cursor = busy.end
            }
        }
        if (cursor.isBefore(window.end)) {
            blocks += CalendarAvailabilityBlock(CalendarAvailabilityBlockStatus.Free, cursor, window.end)
        }
        return CalendarAvailabilitySnapshot(window, blocks)
    }

    fun formatInstant(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant)

    private fun parseIsoInstant(value: String): Instant? =
        try {
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }

    private fun mergeBusyIntervals(
        window: CalendarAvailabilityWindow,
        busyIntervals: List<CalendarBusyInterval>,
    ): List<CalendarBusyInterval> {
        val clamped = busyIntervals
            .mapNotNull { interval ->
                val start = maxOf(interval.start, window.start)
                val end = minOf(interval.end, window.end)
                if (end.isAfter(start)) CalendarBusyInterval(start, end) else null
            }
            .sortedWith(compareBy<CalendarBusyInterval> { it.start }.thenBy { it.end })
        if (clamped.isEmpty()) return emptyList()

        val merged = mutableListOf<CalendarBusyInterval>()
        var current = clamped.first()
        clamped.drop(1).forEach { next ->
            if (!next.start.isAfter(current.end)) {
                current = current.copy(end = maxOf(current.end, next.end))
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }
}

class AndroidCalendarAvailabilityProvider(
    private val context: Context,
) : CalendarAvailabilityProvider {
    override fun queryAvailability(window: CalendarAvailabilityWindow): CalendarAvailabilityReadResult {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return CalendarAvailabilityReadResult.MissingPermission
        }

        return try {
            val cursor = CalendarContract.Instances.query(
                context.contentResolver,
                PROJECTION,
                window.start.toEpochMilli(),
                window.end.toEpochMilli(),
            ) ?: return CalendarAvailabilityReadResult.Failed("日历服务不可用")

            cursor.use {
                val beginIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val availabilityIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.AVAILABILITY)
                val statusIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.STATUS)
                val busyIntervals = mutableListOf<CalendarBusyInterval>()
                while (it.moveToNext()) {
                    val availability = it.getInt(availabilityIndex)
                    val status = it.getInt(statusIndex)
                    if (
                        availability == CalendarContract.Events.AVAILABILITY_FREE ||
                        status == CalendarContract.Events.STATUS_CANCELED
                    ) {
                        continue
                    }
                    val start = Instant.ofEpochMilli(it.getLong(beginIndex))
                    val end = Instant.ofEpochMilli(it.getLong(endIndex))
                    if (end.isAfter(start)) {
                        busyIntervals += CalendarBusyInterval(start, end)
                    }
                }
                CalendarAvailabilityReadResult.Available(
                    CalendarAvailabilityQuery.snapshotFromBusyIntervals(window, busyIntervals),
                )
            }
        } catch (_: SecurityException) {
            CalendarAvailabilityReadResult.MissingPermission
        } catch (throwable: Throwable) {
            CalendarAvailabilityReadResult.Failed(
                throwable.message?.takeIf { it.isNotBlank() } ?: throwable::class.java.simpleName,
            )
        }
    }

    companion object {
        internal val PROJECTION: Array<String> = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.STATUS,
        )
    }
}
