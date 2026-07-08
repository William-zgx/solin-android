package com.bytedance.zgx.solin.logging

import com.bytedance.zgx.solin.BuildConfig

/**
 * Structured logging facade for Solin.
 *
 * The interface intentionally does NOT reference [android.util.Log] so it can be
 * used in pure-Kotlin modules and unit tests without pulling in the Android SDK.
 */
interface SolinLog {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable)
    fun e(tag: String, msg: String, t: Throwable)
}

/**
 * Production implementation that delegates to [android.util.Log].
 *
 * Each call is wrapped in [runCatching] so that unit tests (where
 * `android.util.Log` methods are not mocked) do not crash.
 */
class AndroidSolinLog : SolinLog {
    override fun d(tag: String, msg: String) {
        runCatching { android.util.Log.d(tag, msg) }
    }

    override fun i(tag: String, msg: String) {
        runCatching { android.util.Log.i(tag, msg) }
    }

    override fun w(tag: String, msg: String) {
        runCatching { android.util.Log.w(tag, msg) }
    }

    override fun w(tag: String, msg: String, t: Throwable) {
        runCatching { android.util.Log.w(tag, msg, t) }
    }

    override fun e(tag: String, msg: String, t: Throwable) {
        runCatching { android.util.Log.e(tag, msg, t) }
    }
}

/**
 * No-op implementation that silently discards all log calls.
 * Useful for release builds and tests that do not care about logging.
 */
class NoOpSolinLog : SolinLog {
    override fun d(tag: String, msg: String) {}
    override fun i(tag: String, msg: String) {}
    override fun w(tag: String, msg: String) {}
    override fun w(tag: String, msg: String, t: Throwable) {}
    override fun e(tag: String, msg: String, t: Throwable) {}
}

/**
 * Holds the current [SolinLog] implementation used by the top-level convenience
 * functions. The default is [AndroidSolinLog] when [BuildConfig.DEBUG] is true,
 * [NoOpSolinLog] otherwise.
 */
object SolinLogHolder {
    @Volatile
    var current: SolinLog = if (BuildConfig.DEBUG) AndroidSolinLog() else NoOpSolinLog()
}

/**
 * Swap the active [SolinLog] implementation. Primarily used by tests to inject
 * a fake or capturing logger.
 */
fun setSolinLog(impl: SolinLog) {
    SolinLogHolder.current = impl
}

// ---------------------------------------------------------------------------
// Top-level convenience functions
// ---------------------------------------------------------------------------

fun solinD(tag: String, msg: String) = SolinLogHolder.current.d(tag, msg)

fun solinI(tag: String, msg: String) = SolinLogHolder.current.i(tag, msg)

fun solinW(tag: String, msg: String) = SolinLogHolder.current.w(tag, msg)

fun solinW(tag: String, msg: String, t: Throwable) = SolinLogHolder.current.w(tag, msg, t)

fun solinE(tag: String, msg: String, t: Throwable) = SolinLogHolder.current.e(tag, msg, t)
