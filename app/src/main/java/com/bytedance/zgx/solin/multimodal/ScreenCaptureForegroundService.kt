package com.bytedance.zgx.solin.multimodal

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
import com.bytedance.zgx.solin.R

/**
 * Short-lived, user-visible foreground service of type `mediaProjection` that wraps ONE
 * current-screenshot capture. Android 10+ requires such a service before
 * `MediaProjectionManager.getMediaProjection`, and Android 14 additionally requires it to start
 * only after the user granted MediaProjection consent.
 *
 * Lifecycle: started from the consent callback (via [AndroidScreenCaptureForegroundGate.start]),
 * stopped by the capture provider after capture / failure / consent clear, and self-stops after
 * [MAX_SESSION_MILLIS] if nobody stopped it (e.g. the confirmation was abandoned).
 * Never captures anything itself; it only keeps the session visible.
 */
class ScreenCaptureForegroundService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeRequestId: String? = null
    private val stopAfterTimeout = Runnable { stopSession() }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
        when (intent?.action) {
            ACTION_START -> {
                if (requestId.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val started = runCatching { startForegroundCompat(buildNotification()) }
                if (started.isSuccess) {
                    activeRequestId = requestId
                    if (!ScreenCaptureForegroundSessionTracker.shared.markForeground(requestId)) {
                        // The capture already ended, timed out, or was superseded before this
                        // start arrived: satisfy the startForeground contract, then stop at once.
                        stopSession(startId)
                        return START_NOT_STICKY
                    }
                    mainHandler.removeCallbacks(stopAfterTimeout)
                    mainHandler.postDelayed(stopAfterTimeout, MAX_SESSION_MILLIS)
                } else {
                    ScreenCaptureForegroundSessionTracker.shared.markFailed(
                        requestId,
                        SCREEN_CAPTURE_FOREGROUND_START_FAILED_REASON,
                    )
                    stopSelf()
                }
            }

            else -> stopSession()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacks(stopAfterTimeout)
        activeRequestId?.let(ScreenCaptureForegroundSessionTracker.shared::end)
        activeRequestId = null
        super.onDestroy()
    }

    private fun stopSession(startId: Int? = null) {
        mainHandler.removeCallbacks(stopAfterTimeout)
        activeRequestId?.let(ScreenCaptureForegroundSessionTracker.shared::end)
        activeRequestId = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // With a startId, only stop when no newer capture start is queued behind this one.
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Solin屏幕截图",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Solin按你的确认读取一次当前屏幕时显示，读取结束后自动消失"
            },
        )
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_solin)
            .setContentTitle("Solin正在读取当前屏幕")
            .setContentText(NOTIFICATION_TEXT)
            .setStyle(Notification.BigTextStyle().bigText(NOTIFICATION_TEXT))
            .setOngoing(true)
            .setShowWhen(false)
            .apply { pendingIntent?.let(::setContentIntent) }
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ACTION_START = "com.bytedance.zgx.solin.multimodal.START_SCREEN_CAPTURE"
        private const val ACTION_STOP = "com.bytedance.zgx.solin.multimodal.STOP_SCREEN_CAPTURE"
        private const val EXTRA_REQUEST_ID = "requestId"
        const val CHANNEL_ID = "solin_screen_capture_session"
        const val NOTIFICATION_ID = 41_205
        private const val NOTIFICATION_TEXT = "仅本次截图并在本机识别文字，完成后自动结束；截图不保存。"

        /** Longer than the 30 s one-shot consent TTL so an unused session always self-stops. */
        internal const val MAX_SESSION_MILLIS = 45_000L

        internal fun startIntent(context: Context, requestId: String): Intent =
            Intent(context, ScreenCaptureForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_REQUEST_ID, requestId)

        internal fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureForegroundService::class.java)
                .setAction(ACTION_STOP)
    }
}

/**
 * Production [ScreenCaptureForegroundGate]: starts/stops [ScreenCaptureForegroundService] and
 * waits on the shared [ScreenCaptureForegroundSessionTracker]. A start that throws (for example
 * `ForegroundServiceStartNotAllowedException` or a missing `FOREGROUND_SERVICE_MEDIA_PROJECTION`
 * permission) is recorded as a distinct failure, never as missing consent.
 */
class AndroidScreenCaptureForegroundGate(
    context: Context,
    private val tracker: ScreenCaptureForegroundSessionTracker = ScreenCaptureForegroundSessionTracker.shared,
) : ScreenCaptureForegroundGate {
    private val appContext = context.applicationContext

    override fun start(requestId: String) {
        tracker.begin(requestId)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // MediaProjection does not require a typed foreground service before Android 10.
            tracker.markForeground(requestId)
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                ScreenCaptureForegroundService.startIntent(appContext, requestId),
            )
        }.onFailure {
            tracker.markFailed(requestId, SCREEN_CAPTURE_FOREGROUND_START_FAILED_REASON)
        }
    }

    override fun awaitForeground(requestId: String, timeoutMillis: Long): ScreenCaptureForegroundStatus =
        tracker.await(requestId, timeoutMillis)

    override fun stop(requestId: String) {
        val wasForeground = tracker.isForeground(requestId)
        tracker.end(requestId)
        // Only stop a service that already called startForeground; a still-pending start stops
        // itself in onStartCommand once it sees the ended session (avoids the platform's
        // "startForegroundService() did not then call startForeground()" crash).
        if (!wasForeground || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        // stopService is allowed from the background, unlike startService.
        runCatching { appContext.stopService(ScreenCaptureForegroundService.stopIntent(appContext)) }
    }
}
