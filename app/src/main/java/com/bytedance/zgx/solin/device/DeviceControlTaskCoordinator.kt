package com.bytedance.zgx.solin.device

import java.lang.ref.WeakReference
import java.util.concurrent.Future

internal class DeviceControlTaskCancelledException : IllegalStateException()

internal enum class DeviceControlTaskCancellationReason {
    Lifecycle,
    Timeout,
    Failure,
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
internal class DeviceControlTaskCoordinator {
    private val lock = Any()
    private var generation = 0L
    private var activeOwner: WeakReference<Any>? = null
    private val tasks = mutableSetOf<DeviceControlTaskLease>()

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

    fun startTask(): DeviceControlTaskLease? =
        synchronized(lock) {
            val owner = activeOwner?.get() ?: return null
            DeviceControlTaskLease(
                coordinator = this,
                owner = owner,
                generation = generation,
            ).also(tasks::add)
        }

    fun currentOwner(): Any? =
        synchronized(lock) {
            activeOwner?.get()
        }

    fun isCurrentOwner(owner: Any): Boolean =
        synchronized(lock) {
            activeOwner?.get() === owner
        }

    internal fun requireActive(task: DeviceControlTaskLease) {
        synchronized(lock) {
            if (!isActiveLocked(task)) throw DeviceControlTaskCancelledException()
        }
    }

    /**
     * Validates and marks under the lock, then runs [action] OUTSIDE it.
     *
     * [action] is a binder call into the accessibility framework (perform action, dispatch gesture,
     * take screenshot) and can block for hundreds of milliseconds; holding the lock across it would
     * make any concurrent lifecycle callback wait that long on the main thread. The mark happens before
     * release, so a lifecycle change racing us still sees `hasSubmittedUiSideEffect` and the timeout
     * result stays correctly non-retryable.
     */
    internal fun <T> submitUiSideEffect(task: DeviceControlTaskLease, action: () -> T): T {
        synchronized(lock) {
            if (!isActiveLocked(task)) throw DeviceControlTaskCancelledException()
            task.markUiSideEffectSubmittedLocked()
        }
        return action()
    }

    /**
     * Validates like [submitUiSideEffect] but does NOT mark the task as having submitted a UI side
     * effect. For read-only framework calls (takeScreenshot): nothing on screen changes, so a timeout
     * afterwards must stay retryable.
     */
    internal fun <T> submitUiRead(task: DeviceControlTaskLease, action: () -> T): T {
        synchronized(lock) {
            if (!isActiveLocked(task)) throw DeviceControlTaskCancelledException()
        }
        return action()
    }

    internal fun runUiSideEffectIfActive(
        task: DeviceControlTaskLease,
        action: () -> Unit,
    ): Boolean {
        synchronized(lock) {
            if (!isActiveLocked(task)) return false
            task.markUiSideEffectSubmittedLocked()
        }
        action()
        return true
    }

    internal fun runIfActive(
        task: DeviceControlTaskLease,
        action: () -> Unit,
    ): Boolean {
        synchronized(lock) {
            if (!isActiveLocked(task)) return false
        }
        action()
        return true
    }

    internal fun runCleanupIfCurrent(
        task: DeviceControlTaskLease,
        action: () -> Unit,
    ): Boolean {
        synchronized(lock) {
            if (
                task.wasCancelledByLifecycleLocked() ||
                activeOwner?.get() !== task.owner ||
                generation != task.generation
            ) {
                return false
            }
        }
        action()
        return true
    }

    internal fun attachFuture(task: DeviceControlTaskLease, future: Future<*>) {
        val shouldCancel = synchronized(lock) {
            task.attachFutureLocked(future)
        }
        if (shouldCancel) {
            future.cancel(true)
        }
    }

    internal fun cancel(
        task: DeviceControlTaskLease,
        reason: DeviceControlTaskCancellationReason,
    ) {
        val future = synchronized(lock) {
            task.cancelLocked(reason).also { tasks.remove(task) }
        }
        future?.cancel(true)
    }

    internal fun finish(task: DeviceControlTaskLease) {
        synchronized(lock) {
            tasks.remove(task)
        }
    }

    internal fun hasSubmittedUiSideEffect(task: DeviceControlTaskLease): Boolean =
        synchronized(lock) {
            task.hasSubmittedUiSideEffectLocked()
        }

    internal fun wasCancelledByLifecycle(task: DeviceControlTaskLease): Boolean =
        synchronized(lock) {
            task.wasCancelledByLifecycleLocked()
        }

    private fun isActiveLocked(task: DeviceControlTaskLease): Boolean =
        !task.isCancelledLocked() &&
            tasks.contains(task) &&
            activeOwner?.get() === task.owner &&
            generation == task.generation

    private fun cancelTasksLocked(
        reason: DeviceControlTaskCancellationReason,
    ): List<Future<*>> {
        val futures = tasks.mapNotNull { task -> task.cancelLocked(reason) }
        tasks.clear()
        return futures
    }
}

internal class DeviceControlTaskLease internal constructor(
    private val coordinator: DeviceControlTaskCoordinator,
    internal val owner: Any,
    internal val generation: Long,
) {
    private var cancelled = false
    private var cancellationReason: DeviceControlTaskCancellationReason? = null
    private var submittedUiSideEffect = false
    private var future: Future<*>? = null

    internal fun requireActive() {
        coordinator.requireActive(this)
    }

    internal fun <T> submitUiSideEffect(action: () -> T): T =
        coordinator.submitUiSideEffect(this, action)

    internal fun <T> submitUiRead(action: () -> T): T =
        coordinator.submitUiRead(this, action)

    internal fun runUiSideEffectIfActive(action: () -> Unit): Boolean =
        coordinator.runUiSideEffectIfActive(this, action)

    internal fun runIfActive(action: () -> Unit): Boolean =
        coordinator.runIfActive(this, action)

    internal fun runCleanupIfCurrent(action: () -> Unit): Boolean =
        coordinator.runCleanupIfCurrent(this, action)

    internal fun attachFuture(future: Future<*>) {
        coordinator.attachFuture(this, future)
    }

    internal fun cancel(reason: DeviceControlTaskCancellationReason) {
        coordinator.cancel(this, reason)
    }

    internal fun finish() {
        coordinator.finish(this)
    }

    internal fun hasSubmittedUiSideEffect(): Boolean =
        coordinator.hasSubmittedUiSideEffect(this)

    internal fun markUiSideEffectSubmittedLocked() {
        submittedUiSideEffect = true
    }

    internal fun hasSubmittedUiSideEffectLocked(): Boolean =
        submittedUiSideEffect

    internal fun isCancelledLocked(): Boolean =
        cancelled

    internal fun wasCancelledByLifecycle(): Boolean =
        coordinator.wasCancelledByLifecycle(this)

    internal fun wasCancelledByLifecycleLocked(): Boolean =
        cancellationReason == DeviceControlTaskCancellationReason.Lifecycle

    internal fun attachFutureLocked(future: Future<*>): Boolean {
        this.future = future
        return cancelled
    }

    internal fun cancelLocked(reason: DeviceControlTaskCancellationReason): Future<*>? {
        cancelled = true
        cancellationReason = cancellationReason ?: reason
        return future
    }
}

internal fun uiActionTimeoutResult(task: DeviceControlTaskLease): UiActionReadResult.Failed {
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
