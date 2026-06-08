package com.bytedance.zgx.pocketmind

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bytedance.zgx.pocketmind.ui.REMOTE_ATTACHMENT_PROTECTION_NOTICE
import com.bytedance.zgx.pocketmind.ui.VOICE_INPUT_PRIVACY_DESCRIPTION
import java.io.FileInputStream
import org.junit.After
import org.junit.Rule
import org.junit.Test

class MainActivityAdaptiveUiTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun restoreDisplaySettings() {
        setFontScale(1.0f)
    }

    @Test
    fun largeFontChatShellAndModelManagerRemainReachable() {
        resetMainActivityPersistentState(targetContext, inferenceMode = InferenceMode.Local)
        setFontScale(1.3f)

        ActivityScenario.launch<MainActivity>(skipStartupIntent()).use {
            composeRule.waitForTag("app_title")
            composeRule.onNodeWithTag("top_model_button").assertIsDisplayed()
            composeRule.onNodeWithTag("top_session_button").assertIsDisplayed()
            composeRule.onNodeWithTag("composer_attachment_button").assertIsDisplayed()
            composeRule.onNodeWithTag("composer_voice_button").assertIsDisplayed()

            composeRule.onNodeWithTag("top_model_button").performClick()
            composeRule.waitForTag("model_manager_sheet")
            composeRule.onNodeWithText("模型管理").assertIsDisplayed()
            composeRule.onNodeWithTag("model_tab_advanced").performClick()
            composeRule.waitForText("生成参数")
            composeRule.onNodeWithText("Temperature · 创造性").assertIsDisplayed()
        }
    }

    @Test
    fun coreControlsExposeAccessibleLabelsAndActions() {
        resetMainActivityPersistentState(
            context = targetContext,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = ReadyRemoteModelConfig,
        )

        ActivityScenario.launch<MainActivity>(skipStartupIntent(ReadyRemoteModelConfig)).use {
            composeRule.waitForTag("app_title")

            composeRule.assertLabeledAction("top_model_button", "模型管理")
            composeRule.assertLabeledAction("top_session_button", "会话")
            composeRule.assertLabeledAction("top_more_button", "更多")
            composeRule.assertLabeledAction(
                "composer_attachment_button",
                "选择附件；远程模式会发送图片，其他附件不读取正文或 OCR",
            )
            composeRule.assertLabeledAction("composer_voice_button", VOICE_INPUT_PRIVACY_DESCRIPTION)
            composeRule.onNodeWithTag("remote_attachment_protection_notice").assertIsDisplayed()
            composeRule.onNodeWithText(REMOTE_ATTACHMENT_PROTECTION_NOTICE).assertIsDisplayed()
            composeRule.assertLabeledAction("composer_model_button", "模型管理")

            composeRule.waitForReadyComposer()
            composeRule.onNodeWithTag("composer_input").performTextInput("你好")
            composeRule.assertLabeledAction("composer_send_button", "发送")

            composeRule.onNodeWithTag("top_more_button").performClick()
            composeRule.assertLabeledAction("top_create_session_button", "新建会话")
            composeRule.assertLabeledAction("top_privacy_button", "隐私说明")
            composeRule.assertLabeledAction("top_background_tasks_button", "后台任务")
        }
    }

    @Test
    fun landscapeRemoteSendDisclosureRemainsReachable() {
        resetMainActivityPersistentState(
            context = targetContext,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = ReadyRemoteModelConfig,
        )

        ActivityScenario.launch<MainActivity>(skipStartupIntent(ReadyRemoteModelConfig)).use { scenario ->
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            composeRule.waitForTag("app_title")
            composeRule.waitForReadyComposer()

            composeRule.onNodeWithTag("composer_input").performTextInput("横屏确认远程发送")
            composeRule.onNodeWithTag("composer_send_button").performClick()
            composeRule.waitForTag("remote_send_disclosure_sheet")
            composeRule.onNodeWithTag("remote_send_confirm_button").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("remote_send_dismiss_button").performScrollTo().assertIsDisplayed()
        }
    }

    private fun skipStartupIntent(debugRemoteModelConfig: RemoteModelConfig? = null): Intent =
        if (debugRemoteModelConfig == null) {
            mainActivitySkipStartupIntent(targetContext)
        } else {
            mainActivitySkipStartupIntent(targetContext, debugRemoteModelConfig)
        }

    private fun setFontScale(scale: Float) {
        runShellCommand("settings put system font_scale $scale")
    }

    private fun runShellCommand(command: String) {
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
            .use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    input.readBytes()
                }
            }
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

    private fun ComposeTestRule.waitForText(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeTestRule.assertLabeledAction(tag: String, label: String) {
        onNodeWithTag(tag).assertHasClickAction()
        onNode(hasTestTag(tag) and hasContentDescription(label)).assertIsDisplayed()
    }
}
