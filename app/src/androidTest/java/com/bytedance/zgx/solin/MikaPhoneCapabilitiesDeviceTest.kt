package com.bytedance.zgx.solin

import android.app.UiAutomation
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.debug.DeviceControlEvalActivity
import com.bytedance.zgx.solin.device.AndroidCurrentScreenControlProvider
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.ScreenStateSnapshot
import com.bytedance.zgx.solin.device.UiActionReadResult
import com.bytedance.zgx.solin.device.UiActionStatus
import com.bytedance.zgx.solin.device.UiInputVerification
import com.bytedance.zgx.solin.device.UiScreenStability
import com.bytedance.zgx.solin.safety.SafetyPolicyToolExecutionAuthorizer
import com.bytedance.zgx.solin.tool.DeviceControlToolExecutor
import com.bytedance.zgx.solin.tool.TimeoutToolExecutionBoundary
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Real Accessibility observations/actions on the existing debug fixture. No model or network.
 * The explicit child-approval callback below is a TEST grant, not evidence for product critical-only wiring.
 * This suite never changes Accessibility settings; the harness must enable the service before running it.
 */
class MikaPhoneCapabilitiesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val provider = AndroidCurrentScreenControlProvider()
    private val packageName get() = instrumentation.targetContext.packageName

    @Before fun preserveAccessibilityServiceDuringInstrumentation() {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    @Test fun actualInputPreservesSpacesAndLineBreaksAndReportsMatch() = onFixture { scenario ->
        val requested = "alpha  beta\n中文   gamma"
        val result = provider.typeText(requested, "EvalInputField", timeoutMillis = 2_000)
        val action = result as? UiActionReadResult.Available ?: error("Input unavailable: $result")
        assertEquals(action.result.summary, UiActionStatus.Succeeded, action.result.status)
        assertEquals(UiInputVerification.Match, action.result.inputVerification)
        // ScreenNode is intentionally whitespace-normalized for planning. Assert the actual widget,
        // independently of both that snapshot and the new verification implementation.
        scenario.onActivity { activity ->
            val fields = descendants(activity.window.decorView).filterIsInstance<EditText>()
                .filter { it.contentDescription?.toString() == "EvalInputField" }.toList()
            assertEquals("The actual input widget must remain unique", 1, fields.size)
            assertEquals("Whitespace must survive a real write/read cycle", requested, fields.single().text.toString())
        }
    }

    @Test fun actualWaitReportsStableAndFreshObservation() = onFixture {
        val before = awaitFixture()
        val result = provider.waitForScreen(timeoutMillis = 2_000)
        val action = result as? UiActionReadResult.Available ?: error("Wait unavailable: $result")
        assertEquals(action.result.summary, UiActionStatus.Succeeded, action.result.status)
        assertEquals(UiScreenStability.Stable, action.result.screenStability)
        val after = requireNotNull(action.result.after)
        assertEquals(packageName, after.packageName)
        assertTrue(after.capturedAtMillis >= before.capturedAtMillis)
        assertTrue(after.textSummary.contains("Solin Device Control Eval"))
    }

    @Test fun collectionReadsRealScreenThroughBoundaryAndRemainsPartialLocalOnly() = onFixture {
        val children = mutableListOf<ToolRequest>()
        val boundary = boundary(children)
        val result = runBlocking {
            boundary.execute(
                ToolRequest(
                    toolName = MobileActionFunctions.UI_COLLECT,
                    arguments = mapOf("pages" to "1", "expectedPackageName" to packageName)
                ),
                userConfirmed = true
            )
        }
        assertEquals(result.toString(), ToolStatus.Succeeded, result.status)
        assertEquals("LocalOnly", result.data["privacy"])
        assertEquals("true", result.data["requiresLocalModel"])
        assertEquals("true", result.data["partial"])
        assertEquals("false", result.data["completed"])
        assertEquals("true", result.data["truncated"])
        assertTrue(
            "Collection must contain real fixture content: ${result.data["text"]}",
            result.data["text"].orEmpty().contains("Eval status idle")
        )
        assertEquals(listOf(MobileActionFunctions.OBSERVE_CURRENT_SCREEN), children.map { it.toolName })
        assertEquals(packageName, children.single().arguments["expectedPackageName"])
    }

    @Test fun findReturnsRealUniqueNodeWithoutActivatingIt() = onFixture {
        val observedTarget = awaitFixture().nodes.single { it.contentDescription == "EvalTapTarget" }
        // Android may expose a button's accessibility name through text or description. Query the
        // actual exposed name, while independently pinning the intended fixture button by description.
        val exposedName = observedTarget.text.ifBlank { observedTarget.contentDescription }
        assertTrue(exposedName.isNotBlank())
        val children = mutableListOf<ToolRequest>()
        val result = runBlocking {
            boundary(children).execute(
                ToolRequest(
                    toolName = MobileActionFunctions.UI_FIND,
                    arguments = mapOf("text" to exposedName, "pages" to "1", "expectedPackageName" to packageName)
                ),
                true
            )
        }
        assertEquals(result.toString(), ToolStatus.Succeeded, result.status)
        assertEquals("true", result.data["completed"])
        assertEquals("found", result.data["stopReason"])
        assertEquals(1, JSONArray(result.data.getValue("candidateIds")).length())
        assertEquals(listOf(MobileActionFunctions.OBSERVE_CURRENT_SCREEN), children.map { it.toolName })
        assertTrue("Finding a button must not click it", awaitFixture().textSummary.contains("Eval status idle"))
    }

    @Test fun deniedScrollStopsAfterRealReadWithoutMovingTheList() = onFixture {
        val before = awaitFixture().nodes.filter { it.text.startsWith("Eval list item") }.map { it.text }
        assertTrue("Existing fixture list must be visible", before.isNotEmpty())
        val children = mutableListOf<ToolRequest>()
        val result = runBlocking {
            boundary(children, readsOnly = true).execute(
                ToolRequest(
                    toolName = MobileActionFunctions.UI_FIND,
                    arguments = mapOf(
                        "text" to "Mika acceptance absent target",
                        "pages" to "2",
                        "container" to "EvalScrollContainer",
                        "expectedPackageName" to packageName
                    )
                ),
                true
            )
        }
        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("authorization_required", result.data["stopReason"])
        assertEquals("true", result.data["partial"])
        assertFalse(result.retryable)
        assertEquals(listOf(MobileActionFunctions.OBSERVE_CURRENT_SCREEN, MobileActionFunctions.UI_SCROLL), children.map { it.toolName })
        assertEquals(before, awaitFixture().nodes.filter { it.text.startsWith("Eval list item") }.map { it.text })
    }

    private fun boundary(children: MutableList<ToolRequest>, readsOnly: Boolean = false): TimeoutToolExecutionBoundary {
        val registry = ToolRegistry()
        return TimeoutToolExecutionBoundary(
            executor = DeviceControlToolExecutor(provider = provider),
            toolRegistry = registry,
            executionAuthorizer = SafetyPolicyToolExecutionAuthorizer(registry),
            compositeChildAuthorizer = { _, child ->
                children += child
                // Explicit test-only approval, fresh per child; never inherit the parent boolean.
                !readsOnly || child.toolName == MobileActionFunctions.OBSERVE_CURRENT_SCREEN
            }
        )
    }

    private fun onFixture(block: (ActivityScenario<DeviceControlEvalActivity>) -> Unit) {
        ActivityScenario.launch(DeviceControlEvalActivity::class.java).use { scenario ->
            awaitFixture()
            block(scenario)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun awaitFixture(): ScreenStateSnapshot {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var connected = false
        var last: ScreenStateReadResult? = null
        while (SystemClock.uptimeMillis() < deadline) {
            last = provider.observeCurrentScreen(maxTextChars = 4_000, maxNodes = 120)
            if (last is ScreenStateReadResult.Available) {
                connected = true
                if (last.snapshot.packageName == packageName && last.snapshot.textSummary.contains("Solin Device Control Eval")) {
                    return last.snapshot
                }
            }
            SystemClock.sleep(100)
        }
        assumeTrue("PENDING DEVICE EVIDENCE: Solin Accessibility service is unavailable; enable it and rerun. Last result=$last", connected)
        error("Accessibility connected but the existing eval activity did not become observable: $last")
    }
}
