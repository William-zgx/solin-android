package com.bytedance.zgx.solin.multimodal

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Outcome of waiting for the short-lived `mediaProjection` foreground service that Android 10+
 * (and strictly Android 14+) requires before [android.media.projection.MediaProjectionManager.getMediaProjection]
 * may be called.
 */
sealed interface ScreenCaptureForegroundStatus {
    /** The foreground service is running with `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`. */
    data object Ready : ScreenCaptureForegroundStatus

    /**
     * The foreground service could not be started (or never reported foreground in time). This is
     * deliberately distinct from missing user consent: the user DID consent, but the platform
     * refused the capture session.
     */
    data class Failed(val reason: String) : ScreenCaptureForegroundStatus
}

/**
 * Seam between the MediaProjection capture core and the Android foreground service that keeps
 * one capture visible to the user. Production uses [AndroidScreenCaptureForegroundGate]; tests
 * use a fake so the capture flow (await -> capture -> always stop) is JVM-testable.
 */
interface ScreenCaptureForegroundGate {
    /**
     * Starts the foreground service for [requestId]. Must be called from the MediaProjection
     * consent callback (activity in foreground) AFTER the user granted consent and BEFORE
     * `getMediaProjection`, as Android 14 requires.
     */
    fun start(requestId: String)

    /** Blocks until the service for [requestId] is foreground, fails, or [timeoutMillis] elapses. */
    fun awaitForeground(requestId: String, timeoutMillis: Long): ScreenCaptureForegroundStatus

    /** Stops the service for [requestId]; idempotent and safe after failure or cancel. */
    fun stop(requestId: String)
}

/** Gate for platforms where no foreground service is needed (unit tests, pre-Android-10). */
object NoForegroundServiceRequiredGate : ScreenCaptureForegroundGate {
    override fun start(requestId: String) = Unit

    override fun awaitForeground(requestId: String, timeoutMillis: Long): ScreenCaptureForegroundStatus =
        ScreenCaptureForegroundStatus.Ready

    override fun stop(requestId: String) = Unit
}

internal const val SCREEN_CAPTURE_FOREGROUND_START_FAILED_REASON = "屏幕截图前台服务启动失败，未截图"
internal const val SCREEN_CAPTURE_FOREGROUND_TIMEOUT_REASON = "屏幕截图前台服务未及时启动，未截图"
internal const val SCREEN_CAPTURE_FOREGROUND_NOT_STARTED_REASON = "屏幕截图前台服务未启动，未截图"

/**
 * Process-wide, request-bound state of the screen-capture foreground service. The service
 * reports into it ([markForeground] / [markFailed]) and the capture thread waits on it
 * ([await]). Only one capture session exists at a time, mirroring the single one-shot consent
 * slot in [AndroidCurrentScreenshotOcrProvider]; beginning a new session supersedes the old one.
 *
 * Pure JVM (no Android types) so the state machine is unit-testable.
 */
class ScreenCaptureForegroundSessionTracker {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var session: Session? = null

    /** Starts tracking [requestId] as pending; returns the superseded request id, if any. */
    fun begin(requestId: String): String? = lock.withLock {
        val previous = session?.requestId?.takeIf { it != requestId }
        session = Session(requestId = requestId, status = null)
        changed.signalAll()
        previous
    }

    fun markForeground(requestId: String) = update(requestId, ScreenCaptureForegroundStatus.Ready)

    fun markFailed(requestId: String, reason: String) =
        update(requestId, ScreenCaptureForegroundStatus.Failed(reason))

    /** Ends tracking for [requestId]; returns true when it was the active session. */
    fun end(requestId: String): Boolean = lock.withLock {
        if (session?.requestId != requestId) return false
        session = null
        changed.signalAll()
        true
    }

    fun activeRequestId(): String? = lock.withLock { session?.requestId }

    fun isForeground(requestId: String): Boolean = lock.withLock {
        val current = session
        current?.requestId == requestId && current.status == ScreenCaptureForegroundStatus.Ready
    }

    fun await(requestId: String, timeoutMillis: Long): ScreenCaptureForegroundStatus = lock.withLock {
        var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0L))
        var result: ScreenCaptureForegroundStatus? = null
        while (result == null) {
            val current = session
            result = when {
                current == null || current.requestId != requestId ->
                    ScreenCaptureForegroundStatus.Failed(SCREEN_CAPTURE_FOREGROUND_NOT_STARTED_REASON)
                current.status != null -> current.status
                remainingNanos <= 0L ->
                    ScreenCaptureForegroundStatus.Failed(SCREEN_CAPTURE_FOREGROUND_TIMEOUT_REASON)
                else -> {
                    remainingNanos = changed.awaitNanos(remainingNanos)
                    null
                }
            }
        }
        checkNotNull(result)
    }

    private fun update(requestId: String, status: ScreenCaptureForegroundStatus): Boolean = lock.withLock {
        val current = session
        if (current == null || current.requestId != requestId) return false
        // A failure is terminal for the session; a late "foreground" must not resurrect it.
        if (current.status is ScreenCaptureForegroundStatus.Failed) return false
        session = current.copy(status = status)
        changed.signalAll()
        true
    }

    private data class Session(
        val requestId: String,
        val status: ScreenCaptureForegroundStatus?,
    )

    companion object {
        /** Shared between [ScreenCaptureForegroundService] and the capture provider. */
        val shared: ScreenCaptureForegroundSessionTracker = ScreenCaptureForegroundSessionTracker()
    }
}

/**
 * Classifies a [SecurityException] thrown by the MediaProjection APIs. The platform throws the
 * same exception type both for a stale/invalid consent token and for a missing `mediaProjection`
 * foreground service; only the former is "missing consent". Unknown messages fail closed as
 * missing consent so the user is asked again rather than silently retried.
 */
internal fun isForegroundServiceProjectionFailure(throwable: SecurityException): Boolean {
    val message = throwable.message.orEmpty().lowercase()
    return "foreground service" in message ||
        "foreground_service" in message ||
        "media_projection" in message && "service" in message
}

/**
 * Shared capture-session flow: wait for the foreground service, run [capture], map
 * MediaProjection security failures, and ALWAYS stop the foreground service afterwards (success,
 * failure, timeout, or exception). Kept free of Android types so it is unit-testable with a fake
 * [ScreenCaptureForegroundGate].
 */
internal fun <R> withScreenCaptureForegroundSession(
    gate: ScreenCaptureForegroundGate,
    requestId: String,
    timeoutMillis: Long,
    onMissingConsent: () -> R,
    onFailed: (String) -> R,
    unexpectedFailureReason: String,
    capture: () -> R,
): R {
    try {
        when (val status = gate.awaitForeground(requestId, timeoutMillis)) {
            ScreenCaptureForegroundStatus.Ready -> Unit
            is ScreenCaptureForegroundStatus.Failed -> return onFailed(status.reason)
        }
        return try {
            capture()
        } catch (securityException: SecurityException) {
            if (isForegroundServiceProjectionFailure(securityException)) {
                onFailed(SCREEN_CAPTURE_FOREGROUND_START_FAILED_REASON)
            } else {
                onMissingConsent()
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            onFailed(unexpectedFailureReason)
        } catch (_: Throwable) {
            // Parity with the previous capture core: bitmap OOM or OCR engine failures surface as
            // a Failed tool result instead of crashing the tool thread.
            onFailed(unexpectedFailureReason)
        }
    } finally {
        gate.stop(requestId)
    }
}
