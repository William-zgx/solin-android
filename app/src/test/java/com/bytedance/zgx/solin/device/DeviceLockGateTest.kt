package com.bytedance.zgx.solin.device

import com.bytedance.zgx.solin.multimodal.RawScreenshotReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLockGateTest {
    private fun boom(): Boolean = throw SecurityException("denied")

    @Test
    fun classifierTruthTable() {
        assertEquals(DeviceLockState.Unlocked, classifyDeviceLock({ false }, { false }))
        assertEquals(DeviceLockState.Locked, classifyDeviceLock({ true }, { false }))
        assertEquals(DeviceLockState.Locked, classifyDeviceLock({ false }, { true }))
        assertEquals(DeviceLockState.Locked, classifyDeviceLock({ true }, { true }))
        // One locked read wins even when the other is unreadable.
        assertEquals(DeviceLockState.Locked, classifyDeviceLock({ true }, ::boom))
        assertEquals(DeviceLockState.Locked, classifyDeviceLock(::boom, { true }))
        // Unlocked needs both reads to succeed.
        assertEquals(DeviceLockState.Unknown, classifyDeviceLock({ false }, ::boom))
        assertEquals(DeviceLockState.Unknown, classifyDeviceLock(::boom, { false }))
        assertEquals(DeviceLockState.Unknown, classifyDeviceLock(::boom, ::boom))
        assertEquals(DeviceLockState.Unknown, (null as android.app.KeyguardManager?).deviceLockState())
    }

    @Test
    fun refusalsExistOnlyWhenNotUnlockedAndNeverCarryAvailableData() {
        assertNull(DeviceLockState.Unlocked.screenStateRefusal())
        assertNull(DeviceLockState.Unlocked.screenTextRefusal())
        assertNull(DeviceLockState.Unlocked.screenshotRefusal())
        assertNull(DeviceLockState.Unlocked.uiActionRefusal())
        assertEquals(UiActionFailureKind.SnapshotUnknown, DeviceLockState.Locked.screenStateRefusal()?.failureKind)
        assertEquals(UiActionFailureKind.SnapshotUnknown, DeviceLockState.Unknown.screenStateRefusal()?.failureKind)
        assertTrue(DeviceLockState.Locked.screenTextRefusal() is CurrentScreenTextReadResult.Failed)
        assertTrue(DeviceLockState.Unknown.screenshotRefusal() is RawScreenshotReadResult.Failed)
        for (state in listOf(DeviceLockState.Locked, DeviceLockState.Unknown)) {
            val action = requireNotNull(state.uiActionRefusal())
            assertFalse(action.retryable)
            assertEquals(UiActionFailureKind.SnapshotUnknown, action.failureKind)
        }
    }

    private fun lease(vararg states: DeviceLockState): DeviceControlTaskCoordinator.Lease {
        val queue = ArrayDeque(states.toList())
        val coordinator = DeviceControlTaskCoordinator { queue.removeFirstOrNull() ?: DeviceLockState.Unknown }
        coordinator.connect(Any())
        return requireNotNull(coordinator.startTask())
    }

    private fun refusalOf(block: () -> Unit): DeviceLockState? = (runCatching(block).exceptionOrNull() as? DeviceLockedException)?.state

    @Test
    fun lockedAndUnknownRunNoSideEffectAndNoRead() {
        for (state in listOf(DeviceLockState.Locked, DeviceLockState.Unknown)) {
            val task = lease(state, state)
            var effects = 0
            var reads = 0
            assertEquals(state, refusalOf { task.submitUiSideEffect { effects += 1 } })
            assertEquals(state, refusalOf { task.submitUiRead { reads += 1 } })
            assertEquals(0, effects)
            assertEquals(0, reads)
            assertFalse(task.hasSubmittedUiSideEffect())
        }
    }

    @Test
    fun throwingProbeIsUnknownAndBlocks() {
        val coordinator = DeviceControlTaskCoordinator { throw IllegalStateException("probe") }
        coordinator.connect(Any())
        val task = requireNotNull(coordinator.startTask())
        assertEquals(DeviceLockState.Unknown, coordinator.lockState())
        var effects = 0
        assertEquals(DeviceLockState.Unknown, refusalOf { task.submitUiSideEffect { effects += 1 } })
        assertEquals(0, effects)
    }

    @Test
    fun unlockedRunsExactlyOnceAndMarksSubmission() {
        val task = lease(DeviceLockState.Unlocked, DeviceLockState.Unlocked)
        var effects = 0
        var reads = 0
        task.submitUiRead { reads += 1 }
        assertFalse(task.hasSubmittedUiSideEffect())
        task.submitUiSideEffect { effects += 1 }
        assertEquals(1, reads)
        assertEquals(1, effects)
        assertTrue(task.hasSubmittedUiSideEffect())
    }

    @Test
    fun unlockedThenLockedAllowsFirstAndBlocksSecond() {
        val task = lease(DeviceLockState.Unlocked, DeviceLockState.Locked)
        var effects = 0
        task.submitUiSideEffect { effects += 1 }
        assertEquals(DeviceLockState.Locked, refusalOf { task.submitUiSideEffect { effects += 1 } })
        assertEquals(1, effects)
    }

    @Test
    fun unlockedThenUnknownBlocksLaterReadAndKeepsFirstSubmissionMarked() {
        val task = lease(DeviceLockState.Unlocked, DeviceLockState.Unknown)
        var effects = 0
        var reads = 0
        task.submitUiSideEffect { effects += 1 }
        assertEquals(DeviceLockState.Unknown, refusalOf { task.submitUiRead { reads += 1 } })
        assertEquals(1, effects)
        assertEquals(0, reads)
        assertTrue(task.hasSubmittedUiSideEffect())
    }

    @Test
    fun refusedFirstSubmissionLeavesFlagFalseSoTimeoutStaysRetryable() {
        val task = lease(DeviceLockState.Locked)
        refusalOf { task.submitUiSideEffect { } }
        assertFalse(task.hasSubmittedUiSideEffect())
        assertTrue(uiActionTimeoutResult(task).retryable)
    }

    @Test
    fun cancellationDuringReadProbeNeverStartsTheRead() {
        val inProbe = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val coordinator = DeviceControlTaskCoordinator {
            inProbe.countDown()
            release.await()
            DeviceLockState.Unlocked
        }
        val owner = Any()
        coordinator.connect(owner)
        val task = requireNotNull(coordinator.startTask())
        var reads = 0
        var failure: Throwable? = null
        val worker = Thread { failure = runCatching { task.submitUiRead { reads += 1 } }.exceptionOrNull() }
        worker.start()
        assertTrue(inProbe.await(5, java.util.concurrent.TimeUnit.SECONDS))
        coordinator.interrupt(owner)
        release.countDown()
        worker.join(5000)
        assertTrue(failure is DeviceControlTaskCancelledException)
        assertEquals(0, reads)
    }

    @Test
    fun lifecycleCancellationStillWinsOverProbe() {
        val coordinator = DeviceControlTaskCoordinator { DeviceLockState.Unlocked }
        val owner = Any()
        coordinator.connect(owner)
        val task = requireNotNull(coordinator.startTask())
        coordinator.invalidate(owner)
        assertTrue(runCatching { task.submitUiSideEffect { } }.exceptionOrNull() is DeviceControlTaskCancelledException)
    }
}
