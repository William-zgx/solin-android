package com.bytedance.zgx.pocketmind

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
    fun webSearchSkillFirstExecutesReadOnlyToolWithoutRemoteRuntime() {
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

            composeRule.waitForText("正在使用工具：Web 搜索")
            composeRule.waitForTagGone("action_confirm_button")
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
            composeRule.assertToolCancellationEvidence("read_clipboard")
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
            composeRule.assertToolCancellationEvidence("read_current_screen_text")
        }
    }

    @Test
    fun currentScreenshotOcrSkillShowsOneShotMediaProjectionConfirmation() {
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

            composeRule.sendPrompt("OCR 当前屏幕截图文字")

            composeRule.waitForTag("action_confirm_button")
            composeRule.onNodeWithText("截取当前屏幕 OCR").assertIsDisplayed()
            composeRule.onNodeWithText(
                "将请求 Android MediaProjection 前台同意，单次截取当前屏幕并在本地提取 OCR 文本；不会保存图片、像素、URI、路径或窗口标题。",
            ).assertIsDisplayed()
            composeRule.onNodeWithText("captureMode: current_screen").assertIsDisplayed()
            composeRule.assertTagAbsent("runtime_permission_requirements")
            composeRule.assertTagAbsent("special_access_requirements")
            composeRule.assertTextAbsent("已从当前屏幕单次截图提取")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("action_confirm_button")

            composeRule.onNodeWithTag("top_background_tasks_button").performClick()
            composeRule.waitForTag("background_task_manager_title")
            composeRule.assertToolCancellationEvidence("capture_current_screenshot_ocr")
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

    private fun ComposeTestRule.assertToolCancellationEvidence(toolName: String) {
        waitForText("最近审计日志", substring = true)
        assertTaggedRowDisplayed(
            tagPrefix = "audit_event_",
            matcher = hasAnyDescendant(hasText("UserCancelled")) and
                hasAnyDescendant(hasText("$toolName · Cancelled", substring = true)) and
                hasAnyDescendant(hasText("用户已取消工具执行。")),
        )
        assertTaggedRowDisplayed(
            tagPrefix = "audit_event_",
            matcher = hasAnyDescendant(hasText("ToolObserved")) and
                hasAnyDescendant(hasText("$toolName · Cancelled", substring = true)) and
                hasAnyDescendant(hasText("工具执行已取消。")),
        )

        waitForText("最近 Agent 轨迹", substring = true)
        assertTaggedRowDisplayed(
            tagPrefix = "agent_trace_run_",
            matcher = hasAnyDescendant(hasText("已取消")) and
                hasAnyDescendant(hasText("UserRejected", substring = true)) and
                hasAnyDescendant(hasText("ToolObserved · Observed Cancelled", substring = true)),
        )
    }

    private fun ComposeTestRule.assertTaggedRowDisplayed(
        tagPrefix: String,
        matcher: SemanticsMatcher,
        timeoutMillis: Long = 5_000,
    ) {
        val rowMatcher = hasTestTagPrefix(tagPrefix) and matcher
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodes(rowMatcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodes(rowMatcher, useUnmergedTree = true)
            .onFirst()
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun hasTestTagPrefix(prefix: String): SemanticsMatcher =
        SemanticsMatcher("has test tag prefix $prefix") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }

    private fun ComposeTestRule.assertTextAbsent(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }
}
