package com.bytedance.zgx.solin.action

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: the rule planner matched an absolute reminder against one clock reading and drafted
 * it against a second one; crossing the trigger time in between turned a matched request into
 * "no action".
 */
class ActionPlannerClockReadTest {
    @Test
    fun absoluteReminderDraftUsesOneClockReadingAcrossTriggerBoundary() {
        val beforeTrigger = Instant.parse("2026-06-14T08:59:59.999Z").toEpochMilli()
        val atTrigger = Instant.parse("2026-06-14T09:00:00Z").toEpochMilli()
        val readings = ArrayDeque(listOf(beforeTrigger, atTrigger, atTrigger, atTrigger))
        var reads = 0
        val planner = MobileActionPlanner(
            clockMillis = {
                reads += 1
                readings.removeFirstOrNull() ?: atTrigger
            },
            zoneId = ZoneOffset.UTC,
        )

        val plan = planner.plan("提醒我今天9:00开会")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, plan.draft?.functionName)
        assertEquals(atTrigger.toString(), plan.draft?.parameters?.get("triggerAtMillis"))
        assertEquals("plan() must read the clock exactly once", 1, reads)
    }
}
