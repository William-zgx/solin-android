package com.bytedance.zgx.solin

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.KeyEvent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bytedance.zgx.solin.ui.REMOTE_ATTACHMENT_PROTECTION_NOTICE
import com.bytedance.zgx.solin.ui.VOICE_INPUT_PRIVACY_DESCRIPTION
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainActivityCompactResourceUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactTopBarShowsDeviceResourceBadgeBeforeModelStatus() {
        composeRule.setSolinScreenWithResourceSnapshot(
            modifier = Modifier
                .width(360.dp)
                .height(720.dp),
        )

        composeRule.waitForTag("app_title")
        composeRule.onNodeWithTag("resource_pressure_badge")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithTag("top_model_button").assertIsDisplayed()
        composeRule.onNodeWithTag("top_more_button").assertIsDisplayed()
    }
}

class MainActivityAdaptiveUiTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun largeFontChatShellAndModelManagerRemainReachable() {
        resetMainActivityPersistentState(targetContext, inferenceMode = InferenceMode.Local)

        withTargetFontScale(1.3f) {
            ActivityScenario.launch<MainActivity>(skipStartupIntent()).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(
                        "Expected test font scale to be applied to MainActivity.",
                        activity.resources.configuration.fontScale >= 1.29f,
                    )
                }
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
                "选择附件；远程模式逐次确认后发送图片，其他附件不读取正文或 OCR",
            )
            composeRule.assertLabeledAction("composer_voice_button", VOICE_INPUT_PRIVACY_DESCRIPTION)
            composeRule.onNodeWithTag("remote_attachment_protection_notice").assertIsDisplayed()
            composeRule.onNodeWithText(REMOTE_ATTACHMENT_PROTECTION_NOTICE).assertIsDisplayed()

            composeRule.waitForReadyComposer()
            val initialComposerHeight = composeRule.onNodeWithTag("composer_input")
                .fetchSemanticsNode()
                .boundsInRoot
                .height
            composeRule.onNodeWithTag("composer_input").performTextInput("第一行\n第二行\n第三行")
            composeRule.onNodeWithTag("composer_input")
                .assertTextContains("第二行", substring = true)
            val expandedComposerHeight = composeRule.onNodeWithTag("composer_input")
                .fetchSemanticsNode()
                .boundsInRoot
                .height
            assertTrue(
                "Expected composer input to grow for multiline text.",
                expandedComposerHeight > initialComposerHeight,
            )
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
            try {
                composeRule.waitForTag("app_title")
                composeRule.enableEveryMessageRemoteSendDisclosure()

                scenario.onActivity { activity ->
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                composeRule.waitForReadyComposer()

                composeRule.onNodeWithTag("composer_input").performTextInput("横屏确认远程发送")
                composeRule.onNodeWithTag("composer_send_button").performClick()
                composeRule.waitForTag("remote_send_disclosure_sheet")
                composeRule.onNodeWithTag("remote_send_confirm_button")
                    .assertHasClickAction()
                composeRule.onNodeWithTag("remote_send_dismiss_button")
                    .assertHasClickAction()
            } finally {
                scenario.onActivity { activity ->
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    private fun skipStartupIntent(
        debugRemoteModelConfig: RemoteModelConfig? = null,
    ): Intent =
        (if (debugRemoteModelConfig == null) {
            mainActivitySkipStartupIntent(targetContext)
        } else {
            mainActivitySkipStartupIntent(targetContext, debugRemoteModelConfig)
        })

    @Suppress("DEPRECATION")
    private fun <T> withTargetFontScale(scale: Float, block: () -> T): T {
        val resources = targetContext.resources
        val originalConfiguration = Configuration(resources.configuration)
        val originalDisplayMetrics = DisplayMetrics().apply {
            setTo(resources.displayMetrics)
        }
        val scaledConfiguration = Configuration(originalConfiguration).apply {
            fontScale = scale
        }
        resources.updateConfiguration(scaledConfiguration, resources.displayMetrics)
        return try {
            block()
        } finally {
            resources.updateConfiguration(originalConfiguration, originalDisplayMetrics)
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

    private fun ComposeTestRule.enableEveryMessageRemoteSendDisclosure() {
        onNodeWithTag("top_model_button").performClick()
        waitForTag("model_manager_sheet")
        onNodeWithTag("model_tab_privacy").performClick()
        onNodeWithTag("remote_send_policy_EveryMessage").performScrollTo().performClick()
        onNodeWithTag("model_manager_close_button").performClick()
        waitForIdle()
        if (onAllNodesWithTag("model_manager_sheet").fetchSemanticsNodes().isNotEmpty()) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitForIdle()
        }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithTag("model_manager_sheet").fetchSemanticsNodes().isEmpty()
        }
    }
}
