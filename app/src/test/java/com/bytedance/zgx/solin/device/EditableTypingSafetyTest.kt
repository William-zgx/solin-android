package com.bytedance.zgx.solin.device

import java.io.File
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
        bounds: Boolean = true
    ) = isSafeEditableState(enabled, editable, password, visible) { bounds }

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
    fun serviceSourceContractOrdersUnbindInvalidationAndTypingGuards() {
        // Source contract (JVM cannot construct AccessibilityNodeInfo); not device coverage.
        val source = listOf(
            File("src/main/java/com/bytedance/zgx/solin/device/SolinAccessibilityService.kt"),
            File("app/src/main/java/com/bytedance/zgx/solin/device/SolinAccessibilityService.kt")
        ).first { it.isFile }.readText()

        val unbind = source.substringAfter("override fun onUnbind")
            .substringBefore("override fun onDestroy")
        val invalidateAt = unbind.indexOf("deviceControlTasks.invalidate(this)")
        assertTrue(invalidateAt >= 0)
        assertTrue(invalidateAt < unbind.indexOf("drainPendingClipboardOnTeardown()"))

        val inputBody = source.substringAfter("val inputWindow = editableNode.windowId")
        val write = inputBody.indexOf("setTextDirectly(editableNode, text)")
        val paste = inputBody.indexOf("pasteTextIntoEditable(editableNode, text)")
        val verify = inputBody.indexOf("verifyWrittenText(")
        val preWriteGuard = inputBody.indexOf("editableNode.isSafeEditableForTyping()")
        val readBackGuard = inputBody.indexOf(
            "editableNode.isSafeEditableForTyping()",
            verify
        )
        assertTrue(write >= 0 && paste >= 0 && verify >= 0)
        assertTrue(preWriteGuard in 0 until minOf(write, paste))
        assertTrue(inputBody.substring(preWriteGuard, write).contains("isFocused"))
        assertTrue(readBackGuard > verify)
        // The pre-write guard sits between prepareEditableForTextInput and the first write.
        assertTrue(inputBody.indexOf("prepareEditableForTextInput(editableNode)") < preWriteGuard)
        // Earlier gate (before the inputWindow capture) also guards the node.
        assertTrue(
            source.substringBefore("val inputWindow = editableNode.windowId")
                .contains("!editableNode.refresh() || !editableNode.isSafeEditableForTyping()")
        )
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
            runCatching { oldTask.submitUiSideEffect { writes += 1 } }.isSuccess
        )
        assertEquals(0, writes)
        val replacement = Any()
        coordinator.connect(replacement)
        assertTrue(coordinator.isCurrentOwner(replacement))
        assertTrue(requireNotNull(coordinator.startTask()).runIfActive {})
    }
}
