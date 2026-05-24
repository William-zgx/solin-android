package com.bytedance.zgx.gemmalocalqa

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun chatShellShowsModelManager() {
        composeRule.onNodeWithTag("app_title").assertIsDisplayed()
        composeRule.onNodeWithTag("top_model_button").assertIsDisplayed()
        composeRule.onNodeWithTag("top_session_button").assertIsDisplayed()

        composeRule.onNodeWithTag("top_model_button").performClick()
        composeRule.onNodeWithText("模型管理").assertIsDisplayed()
        composeRule.onNodeWithText("当前模型").assertIsDisplayed()
        composeRule.onNodeWithText("生成参数").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Temperature · 创造性").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("推荐模型").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("添加模型").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("从链接下载").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("导入本地文件").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun customDownloadLinkEntryLivesInModelManager() {
        composeRule.onNodeWithTag("top_model_button").performClick()
        composeRule.onNodeWithText("粘贴 .litertlm 模型下载链接")
            .performScrollTo()
        composeRule.onNodeWithText("从链接下载")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun sessionManagerShowsSessionControls() {
        composeRule.onNodeWithTag("top_session_button").performClick()
        composeRule.waitForTag("session_manager_title")

        composeRule.onNodeWithTag("session_manager_title").assertIsDisplayed()
        composeRule.onNodeWithTag("session_create_button").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.waitForTag(tag: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
