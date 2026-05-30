package com.bytedance.zgx.pocketmind.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.bytedance.zgx.pocketmind.R

class ReminderNotificationHelper(
    private val context: Context,
) {
    private val notificationManager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                REMINDER_CHANNEL_ID,
                "PocketMind 提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Agent 后台提醒和定时任务通知"
            },
        )
    }

    fun postReminder(taskId: String, title: String, body: String): Boolean {
        if (!canPostNotifications()) return false
        ensureChannel()
        val notification = Notification.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body.ifBlank { title })
            .setStyle(Notification.BigTextStyle().bigText(body.ifBlank { title }))
            .setAutoCancel(true)
            .build()
        notificationManager?.notify(taskId.hashCode(), notification)
        return true
    }

    companion object {
        const val REMINDER_CHANNEL_ID = "pocketmind_agent_reminders"
    }
}
