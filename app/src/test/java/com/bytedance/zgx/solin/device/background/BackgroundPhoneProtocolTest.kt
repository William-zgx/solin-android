package com.bytedance.zgx.solin.device.background

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class BackgroundPhoneProtocolTest {
    @Test fun diagnosticCodesNeverEchoFrameworkOrPrivateContent() {
        assertEquals("app_in_use", BackgroundPhoneProtocol.safeFailure("app_in_use"))
        assertEquals("background_operation_refused", BackgroundPhoneProtocol.safeFailure("token=secret account=private"))
        assertEquals("background_operation_refused", BackgroundPhoneProtocol.safeFailure(null))
    }

    @Test fun runBackendIsFrozenAndMissingRunCannotFallBack() {
        BackgroundPhoneSession.selectForAgent(null)
        val id = "background-protocol-test-run"
        BackgroundPhoneSession.captureForRun(id)
        assertNull(BackgroundPhoneSession.forRun(id))
        BackgroundPhoneSession.captureForRun(id)
        assertNull(BackgroundPhoneSession.forRun(id))
        BackgroundPhoneSession.forgetRun(id)
        assertNull(BackgroundPhoneSession.forRun(id))
    }

    @Test fun protocolRoundTripsUnicodeWithoutLineFraming() {
        val bytes = ByteArrayOutputStream()
        val message = "{\"value\":\"中文\\n文字\"}"
        BackgroundPhoneProtocol.write(DataOutputStream(bytes), message)
        assertEquals(message, BackgroundPhoneProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))))
    }

    @Test fun hostileFrameLengthsAreRejectedBeforeAllocating() {
        listOf(-1, 0, BackgroundPhoneProtocol.MAX_MESSAGE_BYTES + 1, Int.MAX_VALUE).forEach { size ->
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).writeInt(size)
            assertThrows(IllegalArgumentException::class.java) {
                BackgroundPhoneProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
            }
        }
    }

    @Test fun requestBudgetIsIndependentOfImageResponseBudget() {
        val bytes = ByteArrayOutputStream()
        BackgroundPhoneProtocol.write(DataOutputStream(bytes), "x".repeat(4097))
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundPhoneProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())), 4096)
        }
    }

    @Test fun truncatedFrameFailsRatherThanAcceptingPartialCommand() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeInt(32); writeByte(1) }
        assertThrows(java.io.EOFException::class.java) {
            BackgroundPhoneProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        }
    }

    @Test fun tokenMustBeAnExactFullLengthCapability() {
        val expected = "01".repeat(32)
        assertTrue(BackgroundPhoneProtocol.sameToken(expected, expected))
        assertFalse(BackgroundPhoneProtocol.sameToken(expected, "01".repeat(31)))
        assertFalse(BackgroundPhoneProtocol.sameToken(expected, "02".repeat(32)))
        assertFalse(BackgroundPhoneProtocol.sameToken(expected, "x".repeat(64)))
    }

    @Test fun packageInputCannotContainCommandsUrisOrComponents() {
        assertTrue(BackgroundPhoneProtocol.validPackage("com.example.app"))
        listOf("com.example/.Activity", "com.example;id", "https://example.com", "com.example\n", "").forEach {
            assertFalse(it, BackgroundPhoneProtocol.validPackage(it))
        }
    }

    @Test fun displayGeometryHasHardResourceBounds() {
        BackgroundPhoneProtocol.requireGeometry(1080, 1920, 420)
        listOf(Triple(0, 1920, 420), Triple(1441, 1920, 420), Triple(1080, 2561, 420), Triple(1080, 1920, 1000)).forEach {
            assertThrows(IllegalArgumentException::class.java) { BackgroundPhoneProtocol.requireGeometry(it.first, it.second, it.third) }
        }
    }

    @Test fun displayNeverEnablesSecureContentOrAlwaysUnlocked() {
        for (sdk in 31..36) {
            val flags = BackgroundDisplayPolicy.flags(sdk)
            assertEquals(0, flags and (1 shl 2)) // VIRTUAL_DISPLAY_FLAG_SECURE
            assertEquals(0, flags and (1 shl 12)) // ALWAYS_UNLOCKED
            assertNotEquals(0, flags and (1 shl 3)) // OWN_CONTENT_ONLY, never mirror main screen
            assertNotEquals(0, flags and (1 shl 8)) // DESTROY_CONTENT_ON_REMOVAL
        }
    }

    @Test fun independentFocusRequiresAndroid14AndTrustedDisplay() {
        assertEquals(0, BackgroundDisplayPolicy.flags(33) and (1 shl 14))
        val flags = BackgroundDisplayPolicy.flags(34)
        assertNotEquals(0, flags and (1 shl 10))
        assertNotEquals(0, flags and (1 shl 14))
        assertNotEquals(0, flags and (1 shl 16))
        assertThrows(IllegalArgumentException::class.java) { BackgroundDisplayPolicy.flags(30) }
    }
}
