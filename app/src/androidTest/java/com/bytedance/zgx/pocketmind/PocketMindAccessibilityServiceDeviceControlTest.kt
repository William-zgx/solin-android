package com.bytedance.zgx.pocketmind

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bytedance.zgx.pocketmind.debug.DeviceControlEvalActivity
import com.bytedance.zgx.pocketmind.device.PocketMindAccessibilityService
import com.bytedance.zgx.pocketmind.device.ScreenStateReadResult
import com.bytedance.zgx.pocketmind.device.UiActionFailureKind
import com.bytedance.zgx.pocketmind.device.UiActionReadResult
import com.bytedance.zgx.pocketmind.device.UiActionStatus
import com.bytedance.zgx.pocketmind.device.UiScrollDirection
import java.io.FileInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class PocketMindAccessibilityServiceDeviceControlTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context = instrumentation.targetContext
    private var previousEnabledAccessibilityServices: String? = null
    private var previousAccessibilityEnabled: String? = null
    private var previousPocketMindAccessibilityActive: Boolean = false

    @Before
    fun keepAccessibilityServicesAvailableDuringInstrumentation() {
        accessibilitySafeUiAutomation()
    }

    @After
    fun restoreAccessibilityServices() {
        val previousServices = previousEnabledAccessibilityServices ?: return
        if (previousServices == SETTINGS_NULL_VALUE && previousPocketMindAccessibilityActive) {
            return
        }
        if (previousServices == SETTINGS_NULL_VALUE) {
            runShellCommand("settings delete secure enabled_accessibility_services")
        } else {
            runShellCommand(
                "settings put secure enabled_accessibility_services ${previousServices.shellSingleQuoted()}",
            )
        }
        previousAccessibilityEnabled
            ?.takeUnless { it == SETTINGS_NULL_VALUE }
            ?.let { runShellCommand("settings put secure accessibility_enabled ${it.shellSingleQuoted()}") }
    }

    @Test
    fun accessibilityServiceReportsRecoverablePermissionMissingWhenDisabled() {
        assumeTrue(
            "Permission-missing device test mutates Accessibility settings and must be explicitly opted in.",
            InstrumentationRegistry.getArguments()
                .getString(ARG_RUN_PERMISSION_MISSING_DEVICE_TEST) == "true",
        )
        rememberAccessibilitySettings()
        assumeTrue(
            "Device secure Accessibility settings are not restorable from instrumentation; permission-missing path is covered by unit tests.",
            previousEnabledAccessibilityServices?.isNotBlank() == true &&
                previousEnabledAccessibilityServices != SETTINGS_NULL_VALUE,
        )
        assumeTrue(
            "Device has an active manually enabled Accessibility service that shell settings cannot safely restore.",
            !previousPocketMindAccessibilityActive,
        )
        runShellCommand("settings delete secure enabled_accessibility_services")
        runShellCommand("settings put secure accessibility_enabled 0")

        val result = PocketMindAccessibilityService.observeCurrentScreen(
            maxTextChars = 2_000,
            maxNodes = 80,
        )

        assumeTrue(
            "Device kept Accessibility enabled; permission-missing path is covered by unit tests.",
            result is ScreenStateReadResult.PermissionDenied,
        )
        require(result is ScreenStateReadResult.PermissionDenied)
        assertTrue(result.reason.contains("无障碍服务"))
    }

    @Test
    fun accessibilityServiceObservesAndTypesIntoPocketMindOnDevice() {
        resetMainActivityPersistentState(
            context = targetContext,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = ReadyRemoteModelConfig,
        )
        enablePocketMindAccessibilityService()

        ActivityScenario.launch<MainActivity>(
            mainActivitySkipStartupIntent(targetContext, ReadyRemoteModelConfig),
        ).use {
            val initial = waitForScreenState(textContains = "PocketMind")
            assertEquals(targetContext.packageName, initial.packageName)
            assertTrue(initial.nodes.any { node -> node.text.contains("PocketMind") })

            val typed = PocketMindAccessibilityService.performTypeText(
                text = "device control emulator input",
                target = null,
                timeoutMillis = 1_500,
            ).requireAvailable()

            assertEquals(typed.result.summary, UiActionStatus.Succeeded, typed.result.status)
            val afterText = typed.result.after?.textSummary.orEmpty()
            assertTrue(
                "Expected typed text in after observation, got: $afterText",
                afterText.contains("device control emulator input"),
            )
        }
    }

    @Test
    fun accessibilityServiceRunsPrimitiveObserveTapTypeScrollWaitBackAndFailureRecoveryOnDevice() {
        enablePocketMindAccessibilityService()

        ActivityScenario.launch(DeviceControlEvalActivity::class.java).use {
            val initial = waitForScreenState(textContains = "PocketMind Device Control Eval")
            assertEquals(targetContext.packageName, initial.packageName)
            assertTrue(initial.nodes.any { node -> node.bounds != null })
            assertTrue(initial.nodes.any { node -> node.clickable })
            assertTrue(initial.nodes.any { node -> node.editable })
            assertTrue(initial.nodes.any { node -> node.scrollable })

            val tapTargetNode = initial.nodes.first { node ->
                node.text.contains("Eval Tap Target") || node.contentDescription.contains("EvalTapTarget")
            }
            val tap = PocketMindAccessibilityService.performTap(
                target = tapTargetNode.id,
                timeoutMillis = 1_500,
            ).requireAvailable()
            assertEquals(tap.result.summary, UiActionStatus.Succeeded, tap.result.status)
            assertTrue(tap.result.after?.textSummary.orEmpty().contains("Tap success"))

            val typed = PocketMindAccessibilityService.performTypeText(
                text = "typed via accessibility",
                target = "EvalInputField",
                timeoutMillis = 1_500,
            ).requireAvailable()
            assertEquals(typed.result.summary, UiActionStatus.Succeeded, typed.result.status)
            assertTrue(typed.result.after?.textSummary.orEmpty().contains("typed via accessibility"))

            val searchText = PocketMindAccessibilityService.performTypeText(
                text = "emulator search query",
                target = "EvalSearchInput",
                timeoutMillis = 1_500,
            ).requireAvailable()
            assertEquals(searchText.result.summary, UiActionStatus.Succeeded, searchText.result.status)
            assertTrue(searchText.result.after?.textSummary.orEmpty().contains("emulator search query"))

            val submitSearch = PocketMindAccessibilityService.performSubmitSearch(
                timeoutMillis = 1_500,
            ).requireAvailable()
            assertEquals(submitSearch.result.summary, UiActionStatus.Succeeded, submitSearch.result.status)
            assertTrue(
                submitSearch.result.after?.textSummary.orEmpty(),
                submitSearch.result.after?.textSummary.orEmpty()
                    .contains("Eval search result: emulator search query"),
            )

            var latestScroll = PocketMindAccessibilityService.performScroll(
                direction = UiScrollDirection.Down,
                target = "EvalScrollContainer",
                timeoutMillis = 1_500,
            ).requireAvailable()
            var foundScrollTarget = latestScroll.result.after?.textSummary.orEmpty()
                .contains("Scroll target item 35")
            repeat(11) {
                if (foundScrollTarget) return@repeat
                latestScroll = PocketMindAccessibilityService.performScroll(
                    direction = UiScrollDirection.Down,
                    target = "EvalScrollContainer",
                    timeoutMillis = 1_500,
                ).requireAvailable()
                foundScrollTarget = latestScroll.result.after?.textSummary.orEmpty()
                    .contains("Scroll target item 35")
            }
            assertEquals(latestScroll.result.summary, UiActionStatus.Succeeded, latestScroll.result.status)
            assertTrue(
                latestScroll.result.after?.textSummary.orEmpty(),
                latestScroll.result.after?.textSummary.orEmpty().contains("Scroll target item 35"),
            )

            val wait = PocketMindAccessibilityService.performWait(timeoutMillis = 500).requireAvailable()
            assertEquals(wait.result.summary, UiActionStatus.Succeeded, wait.result.status)
            assertTrue(wait.result.after?.id.orEmpty().isNotBlank())

            val openPanel = PocketMindAccessibilityService.performTap(
                target = "OpenEvalPanel",
                timeoutMillis = 1_500,
            ).requireAvailable()
            assertEquals(openPanel.result.summary, UiActionStatus.Succeeded, openPanel.result.status)
            assertTrue(openPanel.result.after?.textSummary.orEmpty().contains("Eval panel open"))

            val back = PocketMindAccessibilityService.performPressBack(timeoutMillis = 1_500).requireAvailable()
            assertEquals(back.result.summary, UiActionStatus.Succeeded, back.result.status)
            assertFalse(back.result.after?.textSummary.orEmpty().contains("Eval panel open"))

            val missing = PocketMindAccessibilityService.performTap(
                target = "DefinitelyMissingEvalTarget",
                timeoutMillis = 500,
            ).requireAvailable()
            assertEquals(missing.result.summary, UiActionStatus.Failed, missing.result.status)
            assertEquals(UiActionFailureKind.NodeNotFound, missing.result.failureKind)
            assertTrue(missing.result.retryable)
            assertTrue(missing.result.after?.id.orEmpty().isNotBlank())
        }
    }

    private fun enablePocketMindAccessibilityService() {
        rememberAccessibilitySettings()
        val flattened = ComponentName(
            targetContext,
            PocketMindAccessibilityService::class.java,
        ).flattenToString()
        runShellCommand(
            "settings put secure enabled_accessibility_services ${flattened.shellSingleQuoted()}",
        )
        runShellCommand("settings put secure accessibility_enabled 1")
        val enabledServices = runShellCommand("settings get secure enabled_accessibility_services")
        val activeServiceResult = PocketMindAccessibilityService.observeCurrentScreen(
            maxTextChars = 200,
            maxNodes = 10,
        )
        assumeTrue(
            manualEnableMessage(flattened, enabledServices),
            enabledServices.contains(flattened) || activeServiceResult is ScreenStateReadResult.Available,
        )
        val accessibilityEnabled = runShellCommand("settings get secure accessibility_enabled").trim()
        val activeServiceAfterFlagCheck = PocketMindAccessibilityService.observeCurrentScreen(
            maxTextChars = 200,
            maxNodes = 10,
        )
        assumeTrue(
            manualEnableMessage(flattened, "accessibility_enabled=$accessibilityEnabled"),
            accessibilityEnabled == "1" || activeServiceAfterFlagCheck is ScreenStateReadResult.Available,
        )
    }

    private fun rememberAccessibilitySettings() {
        if (previousEnabledAccessibilityServices != null) return
        previousEnabledAccessibilityServices =
            runShellCommand("settings get secure enabled_accessibility_services").trim()
        previousAccessibilityEnabled =
            runShellCommand("settings get secure accessibility_enabled").trim()
        previousPocketMindAccessibilityActive =
            PocketMindAccessibilityService.observeCurrentScreen(
                maxTextChars = 200,
                maxNodes = 10,
            ) is ScreenStateReadResult.Available
    }

    private fun manualEnableMessage(flattened: String, observedValue: String): String =
        "PocketMind Accessibility is not active. Expected $flattened, got: $observedValue. " +
            "Install the debug and test APKs, enable PocketMind in system Accessibility settings, " +
            "then rerun device-control acceptance with SKIP_INSTALL=1 or DEVICE_CONTROL_SKIP_INSTALL=1."

    private fun waitForScreenState(
        textContains: String,
    ): com.bytedance.zgx.pocketmind.device.ScreenStateSnapshot {
        val deadline = System.currentTimeMillis() + 30_000
        var lastResult: ScreenStateReadResult? = null
        while (System.currentTimeMillis() < deadline) {
            lastResult = PocketMindAccessibilityService.observeCurrentScreen(
                maxTextChars = 2_000,
                maxNodes = 80,
            )
            if (
                lastResult is ScreenStateReadResult.Available &&
                lastResult.snapshot.textSummary.contains(textContains)
            ) {
                return lastResult.snapshot
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for Accessibility screen state, lastResult=$lastResult")
    }

    private fun runShellCommand(command: String): String =
        accessibilitySafeUiAutomation()
            .executeShellCommand(command)
            .use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                }
            }

    private fun accessibilitySafeUiAutomation(): UiAutomation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun UiActionReadResult.requireAvailable(): UiActionReadResult.Available =
        this as? UiActionReadResult.Available ?: error("Expected action result, got $this")

    private fun String.shellSingleQuoted(): String =
        "'${replace("'", "'\\''")}'"

    private companion object {
        const val ARG_RUN_PERMISSION_MISSING_DEVICE_TEST = "runPermissionMissingDeviceTest"
        const val SETTINGS_NULL_VALUE = "null"
    }
}
