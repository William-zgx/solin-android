package com.bytedance.zgx.solin.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControlTaskCoordinatorTest {
    @Test
    fun reconnectPreventsQueuedTaskFromSubmittingUiSideEffect() {
        val coordinator = DeviceControlTaskCoordinator()
        val firstOwner = Any()
        val replacementOwner = Any()
        coordinator.connect(firstOwner)
        val queuedTask = requireNotNull(coordinator.startTask())
        var sideEffectCount = 0

        coordinator.connect(replacementOwner)

        assertCancelled {
            queuedTask.submitUiSideEffect {
                sideEffectCount += 1
            }
        }
        assertEquals(0, sideEffectCount)
    }

    @Test
    fun interruptPreventsExecutingTaskFromSubmittingLaterUiSideEffect() {
        val coordinator = DeviceControlTaskCoordinator()
        val owner = Any()
        coordinator.connect(owner)
        val executingTask = requireNotNull(coordinator.startTask())
        var sideEffectCount = 0

        executingTask.submitUiSideEffect {
            sideEffectCount += 1
        }
        coordinator.interrupt(owner)

        assertCancelled {
            executingTask.submitUiSideEffect {
                sideEffectCount += 1
            }
        }
        assertEquals(1, sideEffectCount)
        assertTrue(coordinator.isCurrentOwner(owner))
        assertTrue(
            requireNotNull(coordinator.startTask()).runIfActive {},
        )
    }

    @Test
    fun reconnectCancelsOldTaskAndAllowsReplacementTask() {
        val coordinator = DeviceControlTaskCoordinator()
        val firstOwner = Any()
        val replacementOwner = Any()
        coordinator.connect(firstOwner)
        val oldTask = requireNotNull(coordinator.startTask())

        coordinator.connect(replacementOwner)
        val replacementTask = requireNotNull(coordinator.startTask())
        var replacementSideEffectExecuted = false

        assertCancelled {
            oldTask.requireActive()
        }
        replacementTask.submitUiSideEffect {
            replacementSideEffectExecuted = true
        }

        assertTrue(replacementSideEffectExecuted)
    }

    @Test
    fun reconnectPreventsOldTaskFromPublishingProgress() {
        val coordinator = DeviceControlTaskCoordinator()
        val firstOwner = Any()
        val replacementOwner = Any()
        coordinator.connect(firstOwner)
        val oldTask = requireNotNull(coordinator.startTask())
        var progressPublished = false

        coordinator.connect(replacementOwner)

        assertFalse(
            oldTask.runIfActive {
                progressPublished = true
            },
        )
        assertFalse(progressPublished)
    }

    @Test
    fun timeoutBeforeUiSubmissionRemainsRetryable() {
        val coordinator = DeviceControlTaskCoordinator()
        val owner = Any()
        coordinator.connect(owner)
        val task = requireNotNull(coordinator.startTask())

        val result = uiActionTimeoutResult(task)

        assertTrue(result.retryable)
        assertEquals(UiActionFailureKind.Timeout, result.failureKind)
        assertFalse(result.reason.contains("未确认"))
    }

    @Test
    fun lifecycleCancellationIsNotRetryableEvenBeforeUiSubmission() {
        val coordinator = DeviceControlTaskCoordinator()
        val owner = Any()
        coordinator.connect(owner)
        val task = requireNotNull(coordinator.startTask())

        coordinator.invalidate(owner)
        val result = uiActionTimeoutResult(task)

        assertFalse(result.retryable)
        assertEquals(UiActionFailureKind.Timeout, result.failureKind)
        assertTrue(result.reason.contains("生命周期"))
    }

    @Test
    fun timeoutAfterUiSubmissionIsNotRetryable() {
        val coordinator = DeviceControlTaskCoordinator()
        coordinator.connect(Any())
        val task = requireNotNull(coordinator.startTask())
        task.submitUiSideEffect {}

        val result = uiActionTimeoutResult(task)

        assertFalse(result.retryable)
        assertEquals(UiActionFailureKind.Timeout, result.failureKind)
        assertTrue(result.reason.contains("未确认"))
    }

    private fun assertCancelled(block: () -> Unit) {
        try {
            block()
        } catch (_: DeviceControlTaskCancelledException) {
            return
        }
        throw AssertionError("Expected task cancellation")
    }
}
