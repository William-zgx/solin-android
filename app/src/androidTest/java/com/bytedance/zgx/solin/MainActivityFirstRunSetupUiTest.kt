package com.bytedance.zgx.solin

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
            mainActivitySkipStartupIntent(targetContext)
        ).useWithViewModelTeardown {
            composeRule.waitForTag("app_title", timeoutMillis = 10_000)
            composeRule.onNodeWithTag("model_startup_banner")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("home_choice_remote")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("home_choice_local")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.waitForText("连接远程模型", timeoutMillis = 10_000)
            composeRule.waitForTag("first_run_setup_panel", timeoutMillis = 10_000)
            composeRule.onNodeWithTag("first_run_setup_panel")
                .performScrollTo()
                .assertIsDisplayed()

            composeRule.onNodeWithTag("first_run_model_chat-e2b")
                .performScrollTo()
                .assertIsOn()
            composeRule.onNodeWithTag("first_run_download_button")
                .performScrollTo()
                .assertIsDisplayed()

            composeRule.onNodeWithText("稍后再说")
                .performScrollTo()
                .performClick()

            composeRule.waitForTagGone("first_run_setup_panel")
            composeRule.onNodeWithTag("model_startup_banner")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("home_choice_remote")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }
}
