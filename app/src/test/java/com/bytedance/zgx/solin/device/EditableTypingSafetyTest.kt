package com.bytedance.zgx.solin.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditableTypingSafetyTest {
    private fun safe(
        enabled: Boolean = true,
        editable: Boolean = true,
        password: Boolean = false,
        visible: Boolean = true,
        bounds: Boolean = true,
    ) = isSafeEditableState(enabled, editable, password, visible, bounds)

    @Test
    fun visibleFocusedEditorWithBoundsIsSafe() {
        assertTrue(safe())
    }

    @Test
    fun hiddenNodeOrEmptyBoundsFailClosed() {
        assertFalse(safe(visible = false))
        assertFalse(safe(bounds = false))
        assertFalse(safe(enabled = false))
        assertFalse(safe(editable = false))
        assertFalse(safe(password = true))
    }

    @Test
    fun collapsedNodeBetweenGateAndWriteProducesNoWrite() {
        // Mirrors the write path: the gate is re-evaluated on the refreshed original node
        // immediately before ACTION_SET_TEXT, so a node that collapsed or hid gets zero writes.
        var writes = 0
        fun guardedWrite(visible: Boolean, bounds: Boolean) {
            if (safe(visible = visible, bounds = bounds)) writes += 1
        }
        guardedWrite(visible = true, bounds = false)
        guardedWrite(visible = false, bounds = true)
        assertEquals(0, writes)
    }

    @Test
    fun unbindInvalidationRejectsOldLeaseAndReconnectGetsFreshOwnership() {
        val coordinator = DeviceControlTaskCoordinator()
        val owner = Any()
        coordinator.connect(owner)
        val oldTask = requireNotNull(coordinator.startTask())
        var writes = 0

        coordinator.invalidate(owner)

        assertFalse(coordinator.isCurrentOwner(owner))
        assertFalse(
            runCatching { oldTask.submitUiSideEffect { writes += 1 } }.isSuccess,
        )
        assertEquals(0, writes)
        val replacement = Any()
        coordinator.connect(replacement)
        assertTrue(coordinator.isCurrentOwner(replacement))
        assertTrue(requireNotNull(coordinator.startTask()).runIfActive {})
    }
}
