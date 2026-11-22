package com.bytedance.zgx.solin.device

import android.app.KeyguardManager
import com.bytedance.zgx.solin.multimodal.RawScreenshotReadResult
import java.lang.ref.WeakReference
import java.util.concurrent.Future

internal class DeviceControlTaskCancelledException : IllegalStateException()

internal enum class DeviceControlTaskCancellationReason {
    Lifecycle,
    Timeout,
    Failure,
}

/**
 * Lock-screen truth for foreground device control. Threat: reading the lock screen or acting on it
 * (credentials, notifications shown over it, unlock gestures) while the user is away.
 * Fail-closed: only a successful "both false" read is [Unlocked]; anything unreadable is [Unknown].
 * Never cached: every observation, action and terminal submission asks again.
 */
internal enum class DeviceLockState { Unlocked, Locked, Unknown }

/** Reads [DeviceLockState] now. May block on binder IPC; never call while holding the lease lock. */
internal fun classifyDeviceLock(keyguardLocked: () -> Boolean, deviceLocked: () -> Boolean): DeviceLockState {
    val keyguard = runCatching(keyguardLocked).getOrNull()
    val device = runCatching(deviceLocked).getOrNull()
    return when {
        keyguard == true || device == true -> DeviceLockState.Locked
        keyguard == false && device == false -> DeviceLockState.Unlocked
        else -> DeviceLockState.Unknown
    }
}

internal fun KeyguardManager?.deviceLockState(): DeviceLockState = if (this == null) {
    DeviceLockState.Unknown
} else {
    classifyDeviceLock(::isKeyguardLocked, ::isDeviceLocked)
}

/** A submission was refused because the device is not provably unlocked; nothing was submitted. */
internal class DeviceLockedException(val state: DeviceLockState) : IllegalStateException()

private const val LOCKED_REASON = "设备已锁屏，已停止屏幕控制"
private const val UNKNOWN_LOCK_REASON = "无法确认设备已解锁，已停止屏幕控制"

private fun DeviceLockState.refusalReason(): String? = when (this) {
    DeviceLockState.Unlocked -> null
    DeviceLockState.Locked -> LOCKED_REASON
    DeviceLockState.Unknown -> UNKNOWN_LOCK_REASON
}

/**
 * One kind for Locked and Unknown: the release path maps it to the generic `unknown`, so a remote
 * planner cannot learn that the device is specifically locked.
 */
private val LOCK_REFUSAL_KIND = UiActionFailureKind.SnapshotUnknown

internal fun DeviceLockState.screenStateRefusal(): ScreenStateReadResult.Failed? = refusalReason()?.let { ScreenStateReadResult.Failed(it, LOCK_REFUSAL_KIND) }

internal fun DeviceLockState.screenTextRefusal(): CurrentScreenTextReadResult.Failed? = refusalReason()?.let { CurrentScreenTextReadResult.Failed(it) }

internal fun DeviceLockState.screenshotRefusal(): RawScreenshotReadResult.Failed? = refusalReason()?.let { RawScreenshotReadResult.Failed(it) }

/** Non-retryable: waiting on the user to unlock is not something the loop can fix by retrying. */
internal fun DeviceLockState.uiActionRefusal(): UiActionReadResult.Failed? = refusalReason()?.let {
    UiActionReadResult.Failed(reason = it, retryable = false, failureKind = LOCK_REFUSAL_KIND)
}

/**
 * Serializes device-control leases against accessibility-service lifecycle changes.
 *
 * The lock guards lease bookkeeping ONLY. Nothing that can block — no binder IPC, no gesture dispatch,
 * no clipboard write — may run while it is held: those calls come from the control thread, while
 * `onServiceConnected` / `onInterrupt` / `onDestroy` need the same lock on the main thread, so a slow
 * IPC would stall a lifecycle callback into an ANR. Every entry point therefore follows lock → validate
 * → mark → unlock → act.
 */
internal class DeviceControlTaskCoordinator(
    private val lockProbe: () -> DeviceLockState,
) {
    private val lock = Any()
    private var generation = 0L
    private var activeOwner: WeakReference<Any>? = null
    private val tasks = mutableSetOf<Lease>()

    fun connect(owner: Any): Any? {
        val (previous, futures) = synchronized(lock) {
            val previousOwner = activeOwner?.get()
            generation += 1
            activeOwner = WeakReference(owner)
            previousOwner to cancelTasksLocked(DeviceControlTaskCancellationReason.Lifecycle)
        }
        futures.forEach { future -> future.cancel(true) }
        return previous
    }

    fun invalidate(owner: Any) {
        val futures = synchronized(lock) {
            if (activeOwner?.get() !== owner) return
            generation += 1
            activeOwner = null
            cancelTasksLocked(DeviceControlTaskCancellationReason.Lifecycle)
        }
        futures.forEach { future -> future.cancel(true) }
    }

    fun interrupt(owner: Any) {
        val futures = synchronized(lock) {
            if (activeOwner?.get() !== owner) return
            generation += 1
            cancelTasksLocked(DeviceControlTaskCancellationReason.Lifecycle)
        }
        futures.forEach { future -> future.cancel(true) }
    }

    /** Fresh probe; a throwing probe is [DeviceLockState.Unknown]. */
    fun lockState(): DeviceLockState = runCatching(lockProbe).getOrDefault(DeviceLockState.Unknown)

    fun startTask(): Lease? =
        synchronized(lock) {
            val owner = activeOwner?.get() ?: return null
            Lease(owner = owner, generation = generation).also(tasks::add)
        }

    fun currentOwner(): Any? =
        synchronized(lock) {
            activeOwner?.get()
        }

    fun isCurrentOwner(owner: Any): Boolean =
        synchronized(lock) {
            activeOwner?.get() === owner
        }

    private fun cancelTasksLocked(
        reason: DeviceControlTaskCancellationReason,
    ): List<Future<*>> {
        val futures = tasks.mapNotNull { task -> task.cancelLocked(reason) }
        tasks.clear()
        return futures
    }

    /** One device-control task. Every method takes the coordinator lock only for bookkeeping. */
    internal inner class Lease internal constructor(
        internal val owner: Any,
        private val generation: Long,
    ) {
        private var cancellationReason: DeviceControlTaskCancellationReason? = null
        private var submittedUiSideEffect = false
        private var future: Future<*>? = null

        internal fun requireActive() {
            synchronized(lock) { requireActiveLocked() }
        }

        /**
         * Validates and marks under the lock, then runs [action] OUTSIDE it.
         *
         * [action] is a binder call into the accessibility framework (perform action, dispatch gesture)
         * and can block for hundreds of milliseconds; holding the lock across it would make a concurrent
         * lifecycle callback wait that long on the main thread. The mark happens before release, so a
         * lifecycle change racing us still sees [hasSubmittedUiSideEffect] and the timeout result stays
         * non-retryable.
         */
        internal fun <T> submitUiSideEffect(action: () -> T): T {
            requireActive()
            requireUnlocked()
            synchronized(lock) {
                requireActiveLocked()
                submittedUiSideEffect = true
            }
            return action()
        }

        /**
         * Validates like [submitUiSideEffect] but does NOT mark a UI side effect. For read-only framework
         * calls (takeScreenshot): nothing on screen changes, so a timeout afterwards must stay retryable.
         */
        internal fun <T> submitUiRead(action: () -> T): T {
            requireActive()
            requireUnlocked()
            // A cancellation during the probe's binder call must not start the framework read.
            requireActive()
            return action()
        }

        /** Fresh probe per submission, outside the lock (binder IPC); refuses before anything is marked. */
        private fun requireUnlocked() {
            val state = lockState()
            if (state != DeviceLockState.Unlocked) throw DeviceLockedException(state)
        }

        internal fun runIfActive(action: () -> Unit): Boolean {
            synchronized(lock) {
                if (!isActiveLocked()) return false
            }
            action()
            return true
        }

        internal fun attachFuture(future: Future<*>) {
            val shouldCancel = synchronized(lock) {
                this.future = future
                cancellationReason != null
            }
            if (shouldCancel) future.cancel(true)
        }

        internal fun cancel(reason: DeviceControlTaskCancellationReason) {
            val future = synchronized(lock) {
                cancelLocked(reason).also { tasks.remove(this) }
            }
            future?.cancel(true)
        }

        internal fun finish() {
            synchronized(lock) { tasks.remove(this) }
        }

        internal fun hasSubmittedUiSideEffect(): Boolean =
            synchronized(lock) { submittedUiSideEffect }

        internal fun wasCancelledByLifecycle(): Boolean =
            synchronized(lock) { cancellationReason == DeviceControlTaskCancellationReason.Lifecycle }

        private fun isActiveLocked(): Boolean =
            cancellationReason == null &&
                tasks.contains(this) &&
                activeOwner?.get() === owner &&
                generation == this@DeviceControlTaskCoordinator.generation

        private fun requireActiveLocked() {
            if (!isActiveLocked()) throw DeviceControlTaskCancelledException()
        }

        internal fun cancelLocked(reason: DeviceControlTaskCancellationReason): Future<*>? {
            cancellationReason = cancellationReason ?: reason
            return future
        }
    }
}

internal fun uiActionTimeoutResult(task: DeviceControlTaskCoordinator.Lease): UiActionReadResult.Failed {
    if (task.wasCancelledByLifecycle()) {
        return UiActionReadResult.Failed(
            reason = "UI 动作因无障碍服务生命周期变化而取消",
            retryable = false,
            failureKind = UiActionFailureKind.Timeout,
        )
    }
    val submitted = task.hasSubmittedUiSideEffect()
    return UiActionReadResult.Failed(
        reason = if (submitted) {
            "UI 动作执行超时，提交状态未确认"
        } else {
            "UI 动作执行超时"
        },
        retryable = !submitted,
        failureKind = UiActionFailureKind.Timeout,
    )
}
