package com.bytedance.zgx.pocketmind

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun chatShellShowsModelManager() {
        dismissFirstRunSetupIfPresent()

        composeRule.onNodeWithTag("app_title").assertIsDisplayed()
        composeRule.onNodeWithTag("top_model_button").assertIsDisplayed()
        composeRule.onNodeWithTag("top_session_button").assertIsDisplayed()

        composeRule.onNodeWithTag("top_model_button").performClick()
        composeRule.waitForTag("model_manager_sheet")

        composeRule.onNodeWithText("模型管理").assertIsDisplayed()
        composeRule.waitForText("当前模型")
        composeRule.onNodeWithTag("model_tab_advanced").performClick()
        composeRule.waitForText("生成参数")
        composeRule.waitForText("Temperature · 创造性")
        composeRule.onNodeWithTag("model_tab_models").performClick()
        composeRule.waitForText("推荐模型")
        composeRule.waitForText("添加模型")
        composeRule.waitForTag("custom_model_download_button")
    }

    @Test
    fun customDownloadLinkEntryLivesInModelManager() {
        dismissFirstRunSetupIfPresent()

        composeRule.onNodeWithTag("top_model_button").performClick()
        composeRule.waitForTag("model_manager_sheet")

        composeRule.onNodeWithTag("model_tab_models").performClick()
        composeRule.waitForTag("custom_model_url_input")
        composeRule.waitForTag("custom_model_download_button")
    }

    @Test
    fun sessionManagerShowsSessionControls() {
        dismissFirstRunSetupIfPresent()

        composeRule.onNodeWithTag("top_session_button").performClick()
        composeRule.waitForTag("session_manager_title")

        composeRule.onNodeWithTag("session_manager_title").assertIsDisplayed()
        composeRule.onNodeWithTag("session_create_button").assertIsDisplayed()
    }

    @Test
    fun backgroundTaskManagerShowsEmptyState() {
        dismissFirstRunSetupIfPresent()

        composeRule.onNodeWithTag("top_background_tasks_button").performClick()
        composeRule.waitForTag("background_task_manager_title")

        composeRule.onNodeWithText("后台任务").assertIsDisplayed()
        composeRule.onNodeWithText("暂无运行中的后台任务").assertIsDisplayed()
    }

    private fun ComposeTestRule.waitForTag(tag: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeTestRule.waitForText(text: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun dismissFirstRunSetupIfPresent() {
        val skipNodes = composeRule.onAllNodesWithText("先跳过").fetchSemanticsNodes()
        if (skipNodes.isNotEmpty()) {
            composeRule.onNodeWithText("准备基础能力包").assertIsDisplayed()
            composeRule.onNodeWithText("下载选中的模型").assertIsDisplayed()
            composeRule.onNodeWithText("先跳过").performClick()
        }
    }
}
