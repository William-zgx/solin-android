package com.bytedance.zgx.pocketmind

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class MainActivitySkillUiTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun webSearchSkillFirstShowsConfirmationWithoutRemoteRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetMainActivityPersistentState(
            context = context,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = ReadyRemoteModelConfig,
        )
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, true)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use {
            composeRule.waitForTag("app_title")

            composeRule.sendPrompt("搜一下 Kotlin 协程")

            composeRule.waitForTag("action_confirm_button")
            composeRule.onNodeWithText("Web 搜索").assertIsDisplayed()
            composeRule.onNodeWithText("将在浏览器中搜索：Kotlin 协程").assertIsDisplayed()
            composeRule.onNodeWithText("query: Kotlin 协程").assertIsDisplayed()
            composeRule.onNodeWithTag("action_confirm_button").assertIsDisplayed()

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("action_confirm_button")

            composeRule.onNodeWithTag("top_background_tasks_button").performClick()
            composeRule.waitForTag("background_task_manager_title")
            composeRule.waitForText("最近审计日志", substring = true)
            composeRule.onNodeWithText("UserCancelled").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("ToolObserved").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("web_search", substring = true).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("工具执行已取消。").performScrollTo().assertIsDisplayed()

            composeRule.waitForText("最近 Agent 轨迹", substring = true)
            composeRule.onNodeWithText("已取消").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("UserRejected", substring = true).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun clipboardSummaryShareSkillStartsAtLocalReadConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetMainActivityPersistentState(
            context = context,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = ReadyRemoteModelConfig,
        )
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, true)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use {
            composeRule.waitForTag("app_title")

            composeRule.sendPrompt("总结剪贴板并分享")

            composeRule.waitForTag("action_confirm_button")
            composeRule.onNodeWithText("读取剪贴板").assertIsDisplayed()
            composeRule.onNodeWithText("将读取当前剪贴板文本，用于生成可分享摘要。").assertIsDisplayed()
            composeRule.assertTextAbsent("分享摘要")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("action_confirm_button")

            composeRule.onNodeWithTag("top_background_tasks_button").performClick()
            composeRule.waitForTag("background_task_manager_title")
            composeRule.onNodeWithText("UserCancelled").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("read_clipboard", substring = true).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("工具执行已取消。").performScrollTo().assertIsDisplayed()

            composeRule.waitForText("最近 Agent 轨迹", substring = true)
            composeRule.onNodeWithText("已取消").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("UserRejected", substring = true).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun currentScreenTextSummaryShareSkillStartsAtScreenTextConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetMainActivityPersistentState(
            context = context,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = ReadyRemoteModelConfig,
        )
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, true)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use {
            composeRule.waitForTag("app_title")

            composeRule.sendPrompt("总结当前屏幕文字并分享")

            composeRule.waitForTag("action_confirm_button")
            composeRule.onNodeWithText("读取当前屏幕文本").assertIsDisplayed()
            composeRule.onNodeWithText(
                "将读取当前屏幕的可访问文本快照，用于生成可分享摘要；不会读取截图、像素、坐标或完整节点树。",
            ).assertIsDisplayed()
            composeRule.onNodeWithTag("special_access_requirements").assertIsDisplayed()
            composeRule.assertTagAbsent("runtime_permission_requirements")
            composeRule.assertTextAbsent("分享屏幕摘要")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("action_confirm_button")

            composeRule.onNodeWithTag("top_background_tasks_button").performClick()
            composeRule.waitForTag("background_task_manager_title")
            composeRule.onNodeWithText("UserCancelled").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("read_current_screen_text", substring = true).performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithText("工具执行已取消。").performScrollTo().assertIsDisplayed()

            composeRule.waitForText("最近 Agent 轨迹", substring = true)
            composeRule.onNodeWithText("已取消").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("UserRejected", substring = true).performScrollTo().assertIsDisplayed()
        }
    }

    private fun ComposeTestRule.sendPrompt(prompt: String) {
        onNodeWithTag("composer_input").performTextClearance()
        onNodeWithTag("composer_input").performTextInput(prompt)
        onNodeWithTag("composer_send_button").performClick()
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

    private fun ComposeTestRule.assertTagAbsent(tag: String, timeoutMillis: Long = 5_000) {
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

    private fun ComposeTestRule.assertTextAbsent(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }
}
