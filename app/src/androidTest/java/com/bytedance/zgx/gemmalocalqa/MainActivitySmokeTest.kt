package com.bytedance.zgx.gemmalocalqa

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunchShowsModelSetupActions() {
        composeRule.onNodeWithText("Gemma 随身问答").assertIsDisplayed()
        composeRule.onNodeWithText("准备 Gemma 4 E2B").assertIsDisplayed()
        composeRule.onNodeWithText("设备检查").assertIsDisplayed()
        composeRule.onNodeWithText("下载推荐模型").assertIsDisplayed()
        composeRule.onNodeWithText("导入已有模型").assertIsDisplayed()
        composeRule.onNodeWithText("查看模型来源").assertIsDisplayed()
    }
}
