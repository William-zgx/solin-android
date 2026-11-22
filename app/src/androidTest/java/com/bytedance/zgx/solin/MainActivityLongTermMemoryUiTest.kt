package com.bytedance.zgx.solin

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.AndroidComposeTestRule as createAndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class MainActivityLongTermMemoryUiTest {
    private val targetContext: Context = ApplicationProvider.getApplicationContext()
    private var viewModelJobForTeardown: Job? = null

    init {
        resetMainActivityPersistentState(
            context = targetContext,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = ReadyRemoteModelConfig
        )
    }

    private val composeRule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
        createAndroidComposeTestRule(
            ActivityScenarioRule(mainActivitySkipStartupIntent(targetContext, ReadyRemoteModelConfig))
        ) { rule -> activityFromScenarioRule(rule) }

    private val awaitViewModelTeardown = object : ExternalResource() {
        override fun after() {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            // The next test class may close Room during reset; join all cancelled child bodies first.
            viewModelJobForTeardown?.let { job ->
                runBlocking {
                    withTimeout(10_000) {
                        job.join()
                    }
                }
            }
            viewModelJobForTeardown = null
        }
    }

    // The probe endpoint must be up before the Activity (started by the inner rule) probes it.
    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(LocalModelProbeServerRule())
        .around(awaitViewModelTeardown)
        .around(composeRule)

    @After
    fun captureViewModelForTeardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModelJobForTeardown = ViewModelProvider(composeRule.activity)[SolinViewModel::class.java]
                .viewModelScope.coroutineContext[Job]
        }
    }

    @Test
    fun explicitPreferencesCanBeForgottenAndClearedFromLongTermMemoryPanel() {
        val firstPreference = "长期记忆 UI 测试偏好 Alpha"
        val secondPreference = "长期记忆 UI 测试偏好 Beta"

        composeRule.waitForTag("app_title")

        composeRule.sendPrompt("请记住：$firstPreference")
        composeRule.waitForText(firstPreference, substring = true)
        composeRule.waitForText("已记住这条本地偏好", substring = true)
        composeRule.sendPrompt("请记住：$secondPreference")
        composeRule.waitForText(secondPreference, substring = true)

        composeRule.openLongTermMemoryPanel()
        composeRule.onNodeWithTag("memory_switch").performScrollTo().assertIsOn()
        composeRule.onModelSheetText(firstPreference)
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag("memory_switch")
            .performScrollTo()
            .performClick()
        composeRule.waitForText("已有记录仍可查看和清除", substring = true)
        composeRule.onModelSheetText(firstPreference)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onModelSheetText(secondPreference)
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onForgetLongTermMemoryButton("用户偏好：$firstPreference")
            .performScrollTo()
            .performClick()
        composeRule.waitForTag("long_term_memory_forget_dialog")
        composeRule.onNodeWithTag("long_term_memory_confirm_forget_button").performClick()
        composeRule.waitForTagGone("long_term_memory_forget_dialog")
        composeRule.waitForModelSheetTextGone(firstPreference)
        composeRule.onModelSheetText(secondPreference)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("long_term_memory_clear_button")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("long_term_memory_confirm_clear_button").performClick()
        composeRule.waitForModelSheetTextGone(secondPreference)
        composeRule.waitForText("还没有已保存的长期记忆。")
    }

    private fun ComposeTestRule.sendPrompt(prompt: String) {
        waitForRemoteModelReady(ReadyRemoteModelConfig.baseUrl)
        onNodeWithTag("composer_input").performTextClearance()
        onNodeWithTag("composer_input").performTextInput(prompt)
        onNodeWithTag("composer_send_button").performClick()
    }

    private fun ComposeTestRule.openLongTermMemoryPanel() {
        onNodeWithTag("top_model_button").performClick()
        waitForTag("model_manager_sheet")
        onNodeWithTag("model_tab_advanced").performClick()
        waitForText("长期记忆")
    }

    private fun ComposeTestRule.onModelSheetText(text: String) = onNode(
        hasText(text, substring = true) and
            hasAnyAncestor(hasTestTag("model_manager_sheet"))
    )

    private fun ComposeTestRule.onForgetLongTermMemoryButton(memoryText: String) = onNode(
        hasClickAction() and
            hasContentDescription("遗忘这条记忆：$memoryText") and
            hasAnyAncestor(hasTestTag("model_manager_sheet"))
    )

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

    private fun ComposeTestRule.waitForModelSheetTextGone(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodes(
                hasText(text, substring = true) and
                    hasAnyAncestor(hasTestTag("model_manager_sheet"))
            ).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun ComposeTestRule.waitForText(
        text: String,
        timeoutMillis: Long = 10_000,
        substring: Boolean = false
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
