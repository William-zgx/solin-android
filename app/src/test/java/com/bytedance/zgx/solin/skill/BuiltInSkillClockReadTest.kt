package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.action.MobileActionFunctions
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: [BuiltInSkillRuntime.plan] used to read the clock twice for an absolute reminder
 * (once in `matches`, once in `draft`). When the second read crossed the requested trigger time
 * the draft became null and `requireNotNull` threw out of `plan()`.
 */
class BuiltInSkillClockReadTest {
    @Test
    fun absoluteReminderPlanUsesOneClockReadingAcrossTriggerBoundary() {
        val beforeTrigger = Instant.parse("2026-06-14T08:59:59.999Z").toEpochMilli()
        val atTrigger = Instant.parse("2026-06-14T09:00:00Z").toEpochMilli()
        val readings = ArrayDeque(listOf(beforeTrigger, atTrigger, atTrigger, atTrigger))
        var reads = 0
        val runtime = BuiltInSkillRuntime(
            clockMillis = {
                reads += 1
                readings.removeFirstOrNull() ?: atTrigger
            },
            zoneId = ZoneOffset.UTC,
        )

        val plan = runtime.plan("提醒我今天9:00开会")

        val step = plan?.steps?.single() as SkillStep.ToolStep
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, step.request.toolName)
        assertEquals(atTrigger.toString(), step.request.arguments["triggerAtMillis"])
        assertEquals("plan() must read the clock exactly once", 1, reads)
    }
}
