package com.bytedance.zgx.pocketmind

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test

class MainActivityFirstRunSetupUiTest {
    private val targetContext: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun firstRunSetupShowsDefaultChatDownloadAndCanSkip() {
        resetMainActivityFreshInstallState(targetContext)

        ActivityScenario.launch<MainActivity>(
            mainActivitySkipStartupIntent(targetContext),
        ).use {
            composeRule.waitForTag("app_title", timeoutMillis = 10_000)
            composeRule.onNodeWithTag("home_positioning_panel")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.waitForText("为什么装它", timeoutMillis = 10_000)
            composeRule.waitForText("本地可用", timeoutMillis = 10_000)
            composeRule.waitForText("远程多模态可选", timeoutMillis = 10_000)
            composeRule.waitForText("动作确认执行", timeoutMillis = 10_000)

            val setupVisible = composeRule.waitForOptionalText("离线基础问答可选下载", timeoutMillis = 3_000)
            if (!setupVisible) {
                composeRule.onNodeWithTag("model_startup_banner")
                    .performScrollTo()
                    .assertIsDisplayed()
                composeRule.onNodeWithTag("quick_remote_config_button")
                    .performScrollTo()
                    .assertIsDisplayed()
                return@use
            }

            composeRule.onNodeWithTag("first_run_model_chat-e2b")
                .performScrollTo()
                .assertIsOn()
            composeRule.onNodeWithTag("first_run_download_button")
                .performScrollTo()
                .assertIsDisplayed()

            composeRule.onNodeWithText("先跳过")
                .performScrollTo()
                .performClick()

            composeRule.waitForTagGone("first_run_download_button")
            composeRule.onNodeWithTag("model_startup_banner")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("quick_remote_config_button")
                .performScrollTo()
                .assertIsDisplayed()
        }
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

    private fun ComposeTestRule.waitForOptionalText(text: String, timeoutMillis: Long): Boolean =
        runCatching {
            waitForText(text, timeoutMillis = timeoutMillis)
            true
        }.getOrDefault(false)

    private fun ComposeTestRule.waitForTagGone(tag: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }
}
