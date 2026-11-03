package com.bytedance.zgx.solin.tool

import android.content.Intent
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.CurrentScreenControlProvider
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.background.BackgroundPhoneSession
import com.bytedance.zgx.solin.device.background.BackgroundPhoneState
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrProvider
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrReadResult
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T6: a consented main-screen capture must not be used as background-display evidence. */
class BackgroundPhoneOcrIsolationTest {
    @Test
    fun backgroundBindingRejectsEvenConsentedOcrBeforeAnyMainScreenRead() {
        val fixture = Fixture()
        val binding = lazyBinding()
        assertNull(BackgroundPhoneSession.currentBinding())

        val result = BackgroundPhoneSession.withBinding(binding) {
            fixture.executor.execute(fixture.request)
        }

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.PermissionDenied, result.error?.code)
        assertFalse("A display isolation denial must not retry the main-screen capture", result.retryable)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("true", result.data["requiresLocalModel"])
        assertEquals(fixture.request.id, result.requestId)
        assertEquals(0, fixture.consentChecks)
        assertEquals(0, fixture.captureCalls)
        assertEquals(0, fixture.observeCalls)
        assertFalse(result.data.containsKey("ocrText"))
        assertFalse(result.data.containsKey("screenObservationJson"))
        assertNull("The worker's display scope must not leak to later work", BackgroundPhoneSession.currentBinding())
    }

    @Test
    fun sameConsentedFixtureCanCaptureAfterBackgroundScopeEnds() {
        val fixture = Fixture()
        BackgroundPhoneSession.withBinding(lazyBinding()) {
            fixture.executor.execute(fixture.request)
        }
        val result = fixture.executor.execute(fixture.request)

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(1, fixture.consentChecks)
        assertEquals(1, fixture.captureCalls)
        assertEquals(1, fixture.observeCalls)
        assertEquals("main-screen fixture text", result.data["ocrText"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertNull(BackgroundPhoneSession.currentBinding())
    }

    @Test
    fun exceptionalScopeExitDoesNotLeaveBackgroundBindingOnWorker() {
        val expected = IllegalStateException("fixture interruption")
        try {
            BackgroundPhoneSession.withBinding(lazyBinding()) { throw expected }
            throw AssertionError("scope should propagate its failure")
        } catch (error: IllegalStateException) {
            assertTrue(error === expected)
        }
        assertNull(BackgroundPhoneSession.currentBinding())
    }

    /**
     * The executor only needs binding presence. Allocate an inert session without constructing
     * LocalSocket, starting a helper, calling Looper or touching device state. Any accidental IPC
     * would fail rather than make this test depend on an Android runtime or a live socket.
     */
    private fun lazyBinding(): BackgroundPhoneSession.Binding {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val session = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, BackgroundPhoneSession::class.java) as BackgroundPhoneSession
        val constructor = BackgroundPhoneSession.Binding::class.java.getDeclaredConstructor(
            BackgroundPhoneSession::class.java,
            BackgroundPhoneState::class.java,
        ).apply { isAccessible = true }
        return constructor.newInstance(session, BackgroundPhoneState(
            displayId = 7,
            foregroundPackage = "example.background",
            width = 320,
            height = 640,
            densityDpi = 160,
            isolatedFocus = true,
        ))
    }

    private class Fixture {
        var consentChecks = 0
        var captureCalls = 0
        var observeCalls = 0
        val request = ToolRequest(
            id = "background-ocr-isolation",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )
        private val provider = object : CurrentScreenshotOcrProvider {
            override fun setOneShotConsent(requestId: String, resultCode: Int, data: Intent?, issuedAtMillis: Long) = Unit
            override fun clearOneShotConsent(requestId: String) = Unit
            override fun hasOneShotConsent(requestId: String, nowMillis: Long): Boolean {
                consentChecks++
                return true
            }
            override fun captureCurrentScreenshotOcr(requestId: String, nowMillis: Long): CurrentScreenshotOcrReadResult {
                captureCalls++
                return CurrentScreenshotOcrReadResult.Available("main-screen fixture text", truncated = false)
            }
        }
        private val screen = Proxy.newProxyInstance(
            CurrentScreenControlProvider::class.java.classLoader,
            arrayOf(CurrentScreenControlProvider::class.java),
        ) { _, method, _ ->
            check(method.name == "observeCurrentScreen") { "Unexpected device operation: ${method.name}" }
            observeCalls++
            ScreenStateReadResult.PermissionDenied("fixture has no accessibility consent")
        } as CurrentScreenControlProvider
        val executor = CurrentScreenshotOcrToolExecutor(provider, screen) { 1_000L }
    }
}
