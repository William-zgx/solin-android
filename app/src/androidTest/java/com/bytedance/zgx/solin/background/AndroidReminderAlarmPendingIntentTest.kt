package com.bytedance.zgx.solin.background

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AndroidReminderAlarmPendingIntentTest {
    @Test
    fun uniqueDataUriSeparatesHashCollidingReminderPendingIntents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstTaskId = "Aa"
        val secondTaskId = "BB"
        val firstIntent = reminderIntent(context, firstTaskId)
        val secondIntent = reminderIntent(context, secondTaskId)

        assertEquals(firstTaskId.hashCode(), secondTaskId.hashCode())

        val firstPendingIntent = PendingIntent.getBroadcast(
            context,
            REMINDER_REQUEST_CODE,
            firstIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        val secondPendingIntent = PendingIntent.getBroadcast(
            context,
            REMINDER_REQUEST_CODE,
            secondIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )

        try {
            assertNotEquals(firstPendingIntent, secondPendingIntent)
            assertNotNull(
                PendingIntent.getBroadcast(
                    context,
                    REMINDER_REQUEST_CODE,
                    firstIntent,
                    PendingIntent.FLAG_NO_CREATE or immutableFlag(),
                ),
            )
            assertNotNull(
                PendingIntent.getBroadcast(
                    context,
                    REMINDER_REQUEST_CODE,
                    secondIntent,
                    PendingIntent.FLAG_NO_CREATE or immutableFlag(),
                ),
            )
        } finally {
            firstPendingIntent.cancel()
            secondPendingIntent.cancel()
        }
    }

    private fun reminderIntent(context: Context, taskId: String): Intent =
        Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_DELIVER_REMINDER
            data = Uri.parse("solin://reminder?taskId=$taskId")
            putExtra(ReminderAlarmReceiver.EXTRA_TASK_ID, taskId)
        }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private companion object {
        const val REMINDER_REQUEST_CODE = 0
    }
}
