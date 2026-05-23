package com.bytedance.zgx.gemmalocalqa

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunchShowsChatAndModelManager() {
        composeRule.onNodeWithText("PocketMind").assertIsDisplayed()
        composeRule.onNodeWithText("模型").assertIsDisplayed()
        composeRule.onNodeWithText("会话").assertIsDisplayed()
        composeRule.onNodeWithText("先把本地模型准备好").assertIsDisplayed()
        composeRule.onNodeWithText("设备检查").assertIsDisplayed()
        composeRule.onNodeWithText("下载 Gemma 4 E2B").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("模型").performClick()
        composeRule.onNodeWithText("模型管理").assertIsDisplayed()
        composeRule.onNodeWithText("当前模型").assertIsDisplayed()
        composeRule.onNodeWithText("推荐模型").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("添加模型").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("从链接下载").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("导入本地文件").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun customDownloadLinkEntryLivesInModelManager() {
        composeRule.onNodeWithText("模型").performClick()
        composeRule.onNodeWithText("粘贴 .litertlm 模型下载链接")
            .performScrollTo()
        composeRule.onNodeWithText("从链接下载")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
