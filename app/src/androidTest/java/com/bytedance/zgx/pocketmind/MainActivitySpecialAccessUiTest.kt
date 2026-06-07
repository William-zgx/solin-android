package com.bytedance.zgx.pocketmind

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
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
import org.junit.Rule
import org.junit.Test

class MainActivitySpecialAccessUiTest {
    private val targetContext: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun currentScreenTextConfirmationShowsSpecialAccessRequirementWithoutRuntimePermission() {
        launchReadyRemoteActivity().use {
            composeRule.waitForTag("app_title")

            composeRule.sendPrompt("总结当前屏幕文字，最多1200字")

            composeRule.waitForTag("special_access_requirements")
            composeRule.onNodeWithText("读取当前屏幕文本").assertIsDisplayed()
            composeRule.onNodeWithText(
                "将读取当前屏幕的可访问文本快照（最多 1200 字符）；不会读取截图、像素、坐标或完整节点树。",
            ).assertIsDisplayed()
            composeRule.onNodeWithTag("special_access_requirements")
                .assertIsDisplayed()
            composeRule.onNodeWithText("可能需要系统特殊授权")
                .assertIsDisplayed()
            composeRule.onNodeWithText(
                "无障碍屏幕文本权限：用于在你确认后只读获取当前屏幕暴露的可访问文本；不会点击、控制设备或读取截图像素。",
            )
                .assertIsDisplayed()
            composeRule.onNodeWithTag("open_special_access_accessibility_screen_text")
                .assertIsDisplayed()
            composeRule.assertTagAbsent("runtime_permission_requirements")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("special_access_requirements")
        }
    }

    @Test
    fun foregroundAppConfirmationShowsUsageAccessRequirementWithoutRuntimePermission() {
        launchReadyRemoteActivity().use {
            composeRule.waitForTag("app_title")

            composeRule.sendPrompt("当前应用是什么")

            composeRule.waitForTag("special_access_requirements")
            composeRule.onNodeWithText("查询当前前台应用").assertIsDisplayed()
            composeRule.onNodeWithText(
                "将通过 UsageStats 估计当前前台应用（包名与应用名）；不读取屏幕内容或使用历史。",
            ).assertIsDisplayed()
            composeRule.onNodeWithTag("special_access_requirements")
                .assertIsDisplayed()
            composeRule.onNodeWithText("可能需要系统特殊授权")
                .assertIsDisplayed()
            composeRule.onNodeWithText(
                "使用情况访问权限：用于通过 UsageStats 估计当前前台应用名和包名；不是窗口真值，不读取使用历史或屏幕内容，需要在系统设置中手动开启。",
            )
                .assertIsDisplayed()
            composeRule.onNodeWithTag("open_special_access_usage_stats")
                .assertIsDisplayed()
            composeRule.assertTagAbsent("runtime_permission_requirements")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("special_access_requirements")
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
}
