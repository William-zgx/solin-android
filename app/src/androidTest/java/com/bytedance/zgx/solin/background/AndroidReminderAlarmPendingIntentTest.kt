package com.bytedance.zgx.solin.background

import android.app.PendingIntent
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test
    fun reminderNotificationRedactsTaskContentFromLockScreenPublicVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val privateTitle = "私人提醒标题"
        val privateBody = "私人提醒正文，不应显示在锁屏上"

        val notification = ReminderNotificationHelper(context)
            .buildReminderNotification(privateTitle, privateBody)
        val publicNotification = requireNotNull(notification.publicVersion)

        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(Notification.VISIBILITY_PUBLIC, publicNotification.visibility)
        assertEquals(
            ReminderNotificationHelper.PUBLIC_NOTIFICATION_TITLE,
            publicNotification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            ReminderNotificationHelper.PUBLIC_NOTIFICATION_BODY,
            publicNotification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertFalse(publicNotification.extras.toString().contains(privateTitle))
        assertFalse(publicNotification.extras.toString().contains(privateBody))
    }

    @Test
    fun reminderDeliveryUsesIoAndFinishesAfterDelivery() {
        val complete = CountDownLatch(1)
        val deliveryCount = AtomicInteger()
        val finishCount = AtomicInteger()
        val deliveryRanOnMain = AtomicBoolean(true)

        ReminderAlarmReceiver().dispatchDelivery(
            finish = {
                finishCount.incrementAndGet()
                complete.countDown()
            },
            launch = ::launchOnIo,
            delivery = {
                deliveryRanOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                deliveryCount.incrementAndGet()
            },
        )

        assertTrue("Timed out waiting for reminder delivery", complete.await(5, TimeUnit.SECONDS))
        assertEquals(1, deliveryCount.get())
        assertEquals(1, finishCount.get())
        assertFalse(deliveryRanOnMain.get())
    }

    @Test
    fun failedReminderDeliveryStillFinishesFromIo() {
        val complete = CountDownLatch(1)
        val finishCount = AtomicInteger()
        val deliveryRanOnMain = AtomicBoolean(true)

        ReminderAlarmReceiver().dispatchDelivery(
            finish = {
                finishCount.incrementAndGet()
                complete.countDown()
            },
            launch = ::launchOnIo,
            delivery = {
                deliveryRanOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                throw IllegalStateException("encrypted database unavailable")
            },
        )

        assertTrue("Timed out waiting for failed reminder delivery", complete.await(5, TimeUnit.SECONDS))
        assertEquals(1, finishCount.get())
        assertFalse(deliveryRanOnMain.get())
    }

    private fun reminderIntent(context: Context, taskId: String): Intent =
        Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_DELIVER_REMINDER
            data = Uri.parse("solin://reminder?taskId=$taskId")
            putExtra(ReminderAlarmReceiver.EXTRA_TASK_ID, taskId)
        }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun launchOnIo(delivery: suspend () -> Unit) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            delivery()
        }
    }

    private companion object {
        const val REMINDER_REQUEST_CODE = 0
    }
}
