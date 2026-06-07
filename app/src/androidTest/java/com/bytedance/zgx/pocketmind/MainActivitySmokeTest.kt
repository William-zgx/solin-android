package com.bytedance.zgx.pocketmind

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.junit4.v2.AndroidComposeTestRule as createAndroidComposeTestRule

class MainActivitySmokeTest {
    private val targetContext: Context = ApplicationProvider.getApplicationContext()

    init {
        resetMainActivityPersistentState(
            context = targetContext,
            inferenceMode = InferenceMode.Local,
        )
    }

    @get:Rule
    val composeRule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
        createAndroidComposeTestRule(
            ActivityScenarioRule(mainActivitySkipStartupIntent(targetContext)),
        ) { rule -> activityFromScenarioRule(rule) }

    @Test
    fun chatShellShowsModelManager() {
        composeRule.waitForTag("app_title")

        composeRule.onNodeWithTag("app_title").assertIsDisplayed()
        composeRule.onNodeWithTag("app_positioning_subtitle").assertIsDisplayed()
        composeRule.waitForText("隐私优先的随身 AI 助手")
        composeRule.waitForText("本地可用，远程多模态可选，设备动作必须确认执行", substring = true)
        composeRule.waitForText("模型未就绪")
        composeRule.onNodeWithTag("home_positioning_panel").performScrollTo().assertIsDisplayed()
        composeRule.waitForText("为什么装它")
        composeRule.waitForText("本地可用")
        composeRule.waitForText("远程多模态可选")
        composeRule.waitForText("动作确认执行")
        composeRule.onNodeWithTag("home_privacy_notice_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("home_capability_pills").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("model_startup_banner").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("top_model_button").assertIsDisplayed()
        composeRule.onNodeWithTag("top_session_button").assertIsDisplayed()
        composeRule.onNodeWithTag("composer_attachment_button").assertIsDisplayed()
        composeRule.onNodeWithTag("composer_voice_button").assertIsDisplayed()

        composeRule.onNodeWithTag("top_model_button").performClick()
        composeRule.waitForTag("model_manager_sheet")

        composeRule.onNodeWithText("模型管理").assertIsDisplayed()
        composeRule.waitForText("本地离线可用；远程多模态可选。远程发送和设备动作仍会先确认。")
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
        composeRule.waitForTag("app_title")

        composeRule.onNodeWithTag("top_model_button").performClick()
        composeRule.waitForTag("model_manager_sheet")

        composeRule.onNodeWithTag("model_tab_models").performClick()
        composeRule.waitForTag("custom_model_url_input")
        composeRule.waitForTag("custom_model_download_button")
    }

    @Test
    fun quickRemoteConfigEntryOpensRemoteModelForm() {
        composeRule.waitForTag("app_title")

        composeRule.onNodeWithTag("quick_remote_config_button").performScrollTo().performClick()
        composeRule.waitForTag("model_manager_sheet")

        composeRule.onNodeWithText("远程模型").assertIsDisplayed()
        composeRule.waitForTag("remote_base_url_input")
    }

    @Test
    fun privacyButtonOpensAppPrivacyNotice() {
        composeRule.waitForTag("app_title")

        composeRule.onNodeWithTag("top_privacy_button").performClick()
        composeRule.waitForTag("model_manager_sheet")

        composeRule.onNodeWithText("隐私说明").assertIsDisplayed()
        composeRule.waitForText("为什么装它")
        composeRule.waitForText("本地可用")
        composeRule.waitForText("远程多模态可选")
        composeRule.waitForText("动作确认执行")
        composeRule.waitForText("用户控制")
        composeRule.waitForText("可清空长期记忆", substring = true)
        composeRule.waitForText("删除当前会话", substring = true)
        composeRule.waitForText("清除远程服务地址", substring = true)
        composeRule.waitForText("敏感能力披露")
        composeRule.waitForText("设备动作和外部 App")
        composeRule.waitForText("Usage Stats 前台应用估计")
        composeRule.waitForText("当前屏幕截图 OCR")
    }

    @Test
    fun homePrivacyEntryOpensAppPrivacyNotice() {
        composeRule.waitForTag("app_title")

        composeRule.onNodeWithTag("home_privacy_notice_button").performScrollTo().performClick()
        composeRule.waitForTag("model_manager_sheet")

        composeRule.onNodeWithText("隐私说明").assertIsDisplayed()
        composeRule.waitForText("敏感能力披露")
        composeRule.waitForText("设备动作和外部 App")
        composeRule.waitForText("用户控制")
    }

    @Test
    fun sessionManagerShowsSessionControls() {
        composeRule.waitForTag("app_title")

        composeRule.onNodeWithTag("top_session_button").performClick()
        composeRule.waitForTag("session_manager_title")

        composeRule.onNodeWithTag("session_manager_title").assertIsDisplayed()
        composeRule.onNodeWithTag("session_create_button").assertIsDisplayed()
    }

    @Test
    fun backgroundTaskManagerShowsEmptyState() {
        composeRule.waitForTag("app_title")

        composeRule.onNodeWithTag("top_background_tasks_button").performClick()
        composeRule.waitForTag("background_task_manager_title")

        composeRule.onNodeWithText("后台任务").assertIsDisplayed()
        composeRule.onNodeWithTag("background_task_refresh_button").assertIsDisplayed()
        composeRule.onNodeWithTag("periodic_check_policy_section").assertIsDisplayed()
    }

    private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.waitForTag(tag: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.waitForText(
        text: String,
        substring: Boolean = false,
    ) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
