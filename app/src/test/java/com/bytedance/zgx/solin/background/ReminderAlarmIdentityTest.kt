package com.bytedance.zgx.solin.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderAlarmIdentityTest {
    @Test
    fun dataUriDistinguishesHashCollidingTaskIds() {
        val firstTaskId = "Aa"
        val secondTaskId = "BB"

        assertEquals(firstTaskId.hashCode(), secondTaskId.hashCode())
        assertEquals(0, reminderAlarmRequestCode())
        assertEquals(
            legacyReminderAlarmRequestCode(firstTaskId),
            legacyReminderAlarmRequestCode(secondTaskId),
        )
        assertNotEquals(
            reminderAlarmDataUriString(firstTaskId),
            reminderAlarmDataUriString(secondTaskId),
        )
    }

    @Test
    fun dataUriEscapesTaskIdForIntentIdentity() {
        val dataUri = reminderAlarmDataUriString("task/id with spaces")

        assertTrue(dataUri.startsWith("solin://reminder?taskId="))
        assertTrue(dataUri.contains("task%2Fid"))
        assertTrue(dataUri.contains("+"))
        assertFalse(dataUri.substringAfter("taskId=").contains(" "))
    }
}
