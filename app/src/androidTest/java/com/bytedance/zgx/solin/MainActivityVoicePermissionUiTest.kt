package com.bytedance.zgx.solin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.regex.Pattern

class MainActivityVoicePermissionUiTest {
    private val targetContext: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun voiceConsentThenDenyMicrophonePermissionShowsFailureAndKeepsRecoveryEntry() {
        resetPermission(Manifest.permission.RECORD_AUDIO)

        launchReadyRemoteActivity().use {
            composeRule.waitForTag("app_title")
            composeRule.waitForReadyComposer()

            composeRule.onNodeWithTag("composer_voice_button").performClick()
            composeRule.waitForTag("voice_permission_disclosure_dialog")
            composeRule.onNodeWithTag("voice_permission_consent_button").performClick()

            clickPermissionDeny()

            composeRule.waitForTagGone("voice_permission_disclosure_dialog", timeoutMillis = 10_000)
            composeRule.waitForText("未授权麦克风权限", substring = true, timeoutMillis = 10_000)
            composeRule.onNodeWithTag("app_status_text")
                .assertTextContains("未授权麦克风权限")
            composeRule.waitForTagGone("voice_capture_bar")
            composeRule.onNodeWithTag("composer_input").assertIsEnabled()
            assertPermissionDenied(Manifest.permission.RECORD_AUDIO)

            composeRule.onNodeWithTag("composer_voice_button").assertIsEnabled().performClick()
            composeRule.onNodeWithTag("voice_permission_disclosure_dialog").assertIsDisplayed()
            composeRule.onNodeWithTag("voice_permission_cancel_button").performClick()
            composeRule.waitForTagGone("voice_permission_disclosure_dialog")
        }
    }

    private fun launchReadyRemoteActivity(): ActivityScenario<MainActivity> {
        resetMainActivityPersistentState(
            context = targetContext,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = ReadyRemoteModelConfig,
        )
        return ActivityScenario.launch(
            mainActivitySkipStartupIntent(
                context = targetContext,
                debugRemoteModelConfig = ReadyRemoteModelConfig,
            ),
        )
    }

    private fun ComposeTestRule.waitForReadyComposer(timeoutMillis: Long = 10_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText("输入问题").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("composer_input").assertIsEnabled()
    }

    private fun ComposeTestRule.waitForTag(tag: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeTestRule.waitForTagGone(tag: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun ComposeTestRule.waitForText(
        text: String,
        timeoutMillis: Long = 5_000,
        substring: Boolean = false,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun resetPermission(permission: String) {
        assumeTrue(
            "$permission must be denied before instrumentation starts; revoke it before launching tests.",
            targetContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED,
        )
        shell("pm clear-permission-flags ${targetContext.packageName} $permission user-set user-fixed")
    }

    private fun assertPermissionDenied(permission: String) {
        check(targetContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            "$permission should remain denied after the system permission dialog is rejected"
        }
    }

    private fun shell(command: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            reader.readText()
        }
    }

    private fun clickPermissionDeny() {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            val button = device.findObject(By.res("com.android.permissioncontroller:id/permission_deny_button"))
                ?: device.findObject(By.res("com.google.android.permissioncontroller:id/permission_deny_button"))
                ?: device.findObject(By.text(Pattern.compile("(?i)(don'?t allow|deny|拒绝|不允许)")))
            if (button != null) {
                button.click()
                device.waitForIdle()
                return
            }
            if (isPermissionControllerVisible()) {
                device.pressBack()
                device.waitForIdle()
                return
            }
            if (targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            Thread.sleep(200)
        }
        check(false) { "Permission deny button not found" }
    }

    private fun isPermissionControllerVisible(): Boolean =
        device.currentPackageName == "com.android.permissioncontroller" ||
            device.currentPackageName == "com.google.android.permissioncontroller" ||
            device.hasObject(By.pkg("com.android.permissioncontroller")) ||
            device.hasObject(By.pkg("com.google.android.permissioncontroller")) ||
            device.hasObject(By.pkg("com.miui.securitycenter")) ||
            device.hasObject(By.pkg("com.lbe.security.miui"))
}
