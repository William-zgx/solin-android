package com.bytedance.zgx.pocketmind.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.bytedance.zgx.pocketmind.R

class DeviceControlSessionService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopAfterTimeout = Runnable {
        PocketMindAccessibilityService.hideControlProgress()
        stopForegroundCompat()
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                mainHandler.removeCallbacks(stopAfterTimeout)
                PocketMindAccessibilityService.hideControlProgress()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val reason = intent.getStringExtra(EXTRA_REASON)
                    ?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_REASON
                startForegroundCompat(buildNotification(reason))
                PocketMindAccessibilityService.showControlProgress(reason)
                mainHandler.removeCallbacks(stopAfterTimeout)
                mainHandler.postDelayed(stopAfterTimeout, MAX_SESSION_MILLIS)
                return START_NOT_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacks(stopAfterTimeout)
        PocketMindAccessibilityService.hideControlProgress()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "PocketMind 手机控制",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "PocketMind 执行用户确认的手机操作时保持控制会话可见"
            },
        )
    }

    private fun buildNotification(reason: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag(),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("PocketMind 正在操作手机")
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setOngoing(true)
            .setShowWhen(false)
            .apply {
                pendingIntent?.let(::setContentIntent)
            }
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun pendingIntentImmutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        private const val ACTION_START = "com.bytedance.zgx.pocketmind.device.START_CONTROL_SESSION"
        private const val ACTION_STOP = "com.bytedance.zgx.pocketmind.device.STOP_CONTROL_SESSION"
        private const val EXTRA_REASON = "reason"
        const val CHANNEL_ID = "pocketmind_device_control_session"
        const val NOTIFICATION_ID = 41_204
        const val DEFAULT_REASON = "PocketMind 正在执行用户确认的手机操作"

        fun start(context: Context, reason: String = DEFAULT_REASON): Boolean =
            runCatching {
                val appContext = context.applicationContext
                val intent = Intent(appContext, DeviceControlSessionService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_REASON, reason.take(MAX_REASON_CHARS))
                ContextCompat.startForegroundService(appContext, intent)
                true
            }.getOrDefault(false)

        fun stop(context: Context): Boolean =
            runCatching {
                val appContext = context.applicationContext
                val intent = Intent(appContext, DeviceControlSessionService::class.java)
                    .setAction(ACTION_STOP)
                appContext.startService(intent)
                true
            }.getOrDefault(false)

        private const val MAX_REASON_CHARS = 120
        private const val MAX_SESSION_MILLIS = 2 * 60 * 1_000L
    }
}
