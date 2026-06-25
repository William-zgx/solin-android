package com.bytedance.zgx.pocketmind

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
        composeRule.waitForText("本地对话", substring = true)
        composeRule.waitForText("图片需视觉", substring = true)
        composeRule.waitForText("远程模型需配置并切换后使用", substring = true)
        composeRule.waitForText("普通文本按提醒设置处理", substring = true)
        composeRule.waitForText("设备动作默认保守确认", substring = true)
        composeRule.waitForText("模型未就绪")
        composeRule.onNodeWithTag("home_positioning_panel").performScrollTo().assertIsDisplayed()
        composeRule.waitForText("为什么装它")
        composeRule.waitForText("本地对话", substring = true)
        composeRule.waitForText("远程配置后使用", substring = true)
        composeRule.waitForText("远程提醒设置", substring = true)
        composeRule.waitForText("低风险连续动作", substring = true)
        composeRule.onNodeWithTag("home_privacy_notice_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("home_capability_pills").performScrollTo().assertIsDisplayed()
        composeRule.waitForText("图片需视觉")
        composeRule.onNodeWithTag("model_startup_banner").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("top_model_button").assertIsDisplayed()
        composeRule.onNodeWithTag("top_session_button").assertIsDisplayed()
        composeRule.onNodeWithTag("top_more_button").assertIsDisplayed()
        composeRule.onNodeWithTag("composer_attachment_button").assertIsDisplayed()
        composeRule.onNodeWithTag("composer_input").assertIsDisplayed()
        composeRule.onNodeWithTag("composer_voice_button").assertIsDisplayed()
        composeRule.onNodeWithTag("composer_send_button").assertIsDisplayed()

        composeRule.onNodeWithTag("top_model_button").performClick()
        composeRule.waitForTag("model_manager_sheet")

        composeRule.onNodeWithText("模型管理").assertIsDisplayed()
        composeRule.waitForText("本地对话", substring = true)
        composeRule.waitForText("已校验且支持视觉", substring = true)
        composeRule.waitForText("远程模型需配置并切换后使用", substring = true)
        composeRule.waitForText("设备动作默认保守确认", substring = true)
        composeRule.waitForText("当前模型")
        composeRule.onNodeWithTag("model_tab_current").assertIsDisplayed()
        composeRule.onNodeWithTag("model_tab_remote").assertIsDisplayed()
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

        composeRule.openTopMenuItem("top_privacy_button")
        composeRule.waitForTag("model_manager_sheet")

        composeRule.scrollToModelManagerText("隐私说明").assertIsDisplayed()
        composeRule.waitForText("为什么装它")
        composeRule.waitForText("本地对话", substring = true)
        composeRule.waitForText("远程配置后使用", substring = true)
        composeRule.waitForText("远程提醒设置", substring = true)
        composeRule.waitForText("设备动作默认保守确认", substring = true)
        composeRule.waitForText("低风险连续动作", substring = true)
        composeRule.waitForText("能力与信任中心")
        composeRule.waitForText("本地私密问答与记忆")
        composeRule.waitForText("屏幕/剪贴板总结分享")
        composeRule.waitForText("低风险 App 搜索控制")
        composeRule.waitForText("本地提醒与后台任务")
        composeRule.waitForText("远程公开证据查询")
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

        composeRule.scrollToModelManagerText("隐私说明").assertIsDisplayed()
        composeRule.waitForText("能力与信任中心")
        composeRule.waitForText("远程公开证据查询")
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
    fun topMoreButtonRemainsReachableWhenResourceSnapshotIsPresent() {
        composeRule.waitForTag("app_title")

        composeRule.onNodeWithTag("top_more_button")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.waitForAnyTag(RESOURCE_ENTRY_TAG, RESOURCE_MENU_ENTRY_TAG, timeoutMillis = 10_000)

        composeRule.onNodeWithTag("top_more_button")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithTag("top_privacy_button").assertIsDisplayed()
    }

    @Test
    fun backgroundTaskManagerShowsEmptyState() {
        composeRule.waitForTag("app_title")

        composeRule.openTopMenuItem("top_background_tasks_button")
        composeRule.waitForTag("background_task_manager_title")

        composeRule.onNodeWithText("后台任务").assertIsDisplayed()
        composeRule.onNodeWithTag("background_task_refresh_button").assertIsDisplayed()
        composeRule.onNodeWithTag("periodic_check_policy_section").assertIsDisplayed()
    }

    private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.waitForTag(
        tag: String,
        timeoutMillis: Long = 5_000,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.waitForAnyTag(
        vararg tags: String,
        timeoutMillis: Long = 5_000,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            tags.any { tag -> onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
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

    private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.scrollToModelManagerText(text: String) =
        onNode(hasText(text) and hasAnyAncestor(hasTestTag("model_manager_sheet"))).performScrollTo()

    private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.openTopMenuItem(tag: String) {
        onNodeWithTag("top_more_button").performClick()
        waitForTag(tag)
        onNodeWithTag(tag).performClick()
    }
}
