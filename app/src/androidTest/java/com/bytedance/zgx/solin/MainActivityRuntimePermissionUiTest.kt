package com.bytedance.zgx.solin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.regex.Pattern

class MainActivityRuntimePermissionUiTest {
    private val targetContext: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun contactLookupConfirmationShowsRuntimePermissionRequirementWithoutSpecialAccess() {
        launchReadyRemoteActivity().use {
            composeRule.waitForTag("app_title")

            composeRule.sendPrompt("查联系人 Alice")

            composeRule.waitForTag("runtime_permission_requirements", timeoutMillis = 10_000)
            composeRule.onNodeWithText("查询联系人").assertIsDisplayed()
            composeRule.onNodeWithText("将按“Alice”查询联系人。").assertIsDisplayed()
            composeRule.onNodeWithTag("runtime_permission_requirements")
                .assertIsDisplayed()
            composeRule.onNodeWithText("确认后可能请求系统权限")
                .assertIsDisplayed()
            composeRule.onNodeWithText("联系人权限：用于只读查询联系人摘要。")
                .assertIsDisplayed()
            composeRule.assertTagAbsent("special_access_requirements")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("runtime_permission_requirements")
        }
    }

    @Test
    fun contactLookupConfirmThenDenyPermissionShowsDeniedStateAndNoToolResult() {
        resetPermission(Manifest.permission.READ_CONTACTS)

        launchReadyRemoteActivity().use {
            composeRule.waitForTag("app_title")

            composeRule.sendPrompt("查联系人 Alice")

            composeRule.waitForTag("runtime_permission_requirements", timeoutMillis = 10_000)
            composeRule.onNodeWithText("联系人权限：用于只读查询联系人摘要。")
                .assertIsDisplayed()
            composeRule.onNodeWithTag("action_confirm_button").performClick()

            clickPermissionDeny()

            composeRule.waitForTagGone("runtime_permission_requirements", timeoutMillis = 10_000)
            composeRule.waitForText("权限被拒，工具未执行", substring = true, timeoutMillis = 10_000)
            composeRule.onNodeWithTag("app_status_text")
                .assertTextContains("权限被拒，工具未执行")
            assertPermissionDenied(Manifest.permission.READ_CONTACTS)
            composeRule.assertTextAbsent("工具执行结果")
        }
    }

    @Test
    fun calendarAvailabilityConfirmationShowsRuntimePermissionRequirementWithoutSpecialAccess() {
        launchReadyRemoteActivity().use {
            composeRule.waitForTag("app_title")

            composeRule.sendPrompt("查忙闲 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z")

            composeRule.waitForTag("runtime_permission_requirements", timeoutMillis = 10_000)
            composeRule.onNodeWithText("查询日历忙闲").assertIsDisplayed()
            composeRule.onNodeWithTag("runtime_permission_requirements")
                .assertIsDisplayed()
            composeRule.onNodeWithText("确认后可能请求系统权限")
                .assertIsDisplayed()
            composeRule.onNodeWithText("日历权限：用于只读查询忙闲时间段，不读取标题、地点或参与人。")
                .assertIsDisplayed()
            composeRule.assertTagAbsent("special_access_requirements")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("runtime_permission_requirements")
        }
    }

    @Test
    fun recentScreenshotOcrConfirmationShowsImageReadRationaleAndCancelsCleanly() {
        launchReadyRemoteActivity().use {
            composeRule.waitForTag("app_title")

            composeRule.sendPrompt("识别最近 1 张截图文字")

            composeRule.waitForTag("runtime_permission_requirements")
            composeRule.onNodeWithText("读取最近截图 OCR").assertIsDisplayed()
            composeRule.onNodeWithTag("runtime_permission_requirements")
                .assertIsDisplayed()
            composeRule.onNodeWithText("确认后可能请求系统权限")
                .assertIsDisplayed()
            composeRule.onNodeWithText("照片和图片权限：用于在你确认后读取最近 1 张截图像素，并在本地提取 OCR 文本。")
                .assertIsDisplayed()
            composeRule.assertTagAbsent("special_access_requirements")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("runtime_permission_requirements")
            composeRule.assertTextAbsent("工具执行结果")
        }
    }

    @Test
    fun recentImageOcrConfirmationShowsBoundedImageReadRationaleAndCancelsCleanly() {
        launchReadyRemoteActivity().use {
            composeRule.waitForTag("app_title")

            composeRule.sendPrompt("识别最近图片文字")

            composeRule.waitForTag("runtime_permission_requirements")
            composeRule.onNodeWithText("读取最近图片 OCR").assertIsDisplayed()
            composeRule.onNodeWithText("将扫描最近 3 张图片并在本地提取第一条 OCR 文本；不会保存图片、URI 或路径。")
                .assertIsDisplayed()
            composeRule.onNodeWithTag("runtime_permission_requirements")
                .assertIsDisplayed()
            composeRule.onNodeWithText("确认后可能请求系统权限")
                .assertIsDisplayed()
            composeRule.onNodeWithText("照片和图片权限：用于在你确认后最多扫描最近 3 张图片像素，并在本地提取第一条 OCR 文本。")
                .assertIsDisplayed()
            composeRule.onNodeWithText("确认后只读取本次动作需要的本机内容或权限范围内摘要。")
                .assertIsDisplayed()
            composeRule.onNodeWithText("读取结果默认仅留在本机，不会自动发送给远程模型。")
                .assertIsDisplayed()
            composeRule.assertTagAbsent("special_access_requirements")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("runtime_permission_requirements")
            composeRule.assertTextAbsent("读取最近图片 OCR")
            composeRule.assertTextAbsent("工具执行结果")
        }
    }

    @Test
    fun recentImageFilesConfirmationShowsMetadataOnlyRationaleAndCancelsCleanly() {
        launchReadyRemoteActivity().use {
            composeRule.waitForTag("app_title")

            composeRule.sendPrompt("查询最近5个图片文件列表")

            composeRule.waitForTag("runtime_permission_requirements")
            composeRule.onNodeWithText("查询最近文件").assertIsDisplayed()
            composeRule.onNodeWithText(
                "将读取最近 5 个图片文件摘要（仅返回文件名、类型、大小和修改时间）。",
            ).assertIsDisplayed()
            composeRule.onNodeWithTag("runtime_permission_requirements")
                .assertIsDisplayed()
            composeRule.onNodeWithText("确认后可能请求系统权限")
                .assertIsDisplayed()
            composeRule.onNodeWithText("照片和图片权限：用于读取最近图片或截图的最小元数据。")
                .assertIsDisplayed()
            composeRule.onNodeWithText("确认后只读取本次动作需要的本机内容或权限范围内摘要。")
                .assertIsDisplayed()
            composeRule.onNodeWithText("读取结果默认仅留在本机，不会自动发送给远程模型。")
                .assertIsDisplayed()
            composeRule.assertTagAbsent("special_access_requirements")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("runtime_permission_requirements")
            composeRule.assertTextAbsent("查询最近文件")
            composeRule.assertTextAbsent("工具执行结果")
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

    private fun ComposeTestRule.sendPrompt(prompt: String) {
        waitForReadyComposer()
        onNodeWithTag("composer_input").performTextClearance()
        onNodeWithTag("composer_input").performTextInput(prompt)
        onNodeWithTag("composer_send_button").performClick()
        confirmRemoteSendIfPresent()
    }

    private fun ComposeTestRule.waitForReadyComposer(timeoutMillis: Long = 10_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText("输入问题").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("composer_input").assertIsEnabled()
    }

    private fun ComposeTestRule.confirmRemoteSendIfPresent() {
        val needsConfirmation = waitForOptionalTag("remote_send_disclosure_sheet", timeoutMillis = 1_500)
        if (!needsConfirmation) return
        onNodeWithTag("remote_send_confirm_button").performClick()
        waitForTagGone("remote_send_disclosure_sheet")
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

    private fun ComposeTestRule.waitForOptionalTag(tag: String, timeoutMillis: Long): Boolean =
        runCatching {
            waitForTag(tag, timeoutMillis = timeoutMillis)
            true
        }.getOrDefault(false)

    private fun ComposeTestRule.assertTagAbsent(tag: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun ComposeTestRule.assertTextAbsent(text: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()
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
            if (targetContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
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
