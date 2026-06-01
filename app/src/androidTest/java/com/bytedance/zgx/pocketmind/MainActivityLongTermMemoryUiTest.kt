package com.bytedance.zgx.pocketmind

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class MainActivityLongTermMemoryUiTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun explicitPreferencesCanBeForgottenAndClearedFromLongTermMemoryPanel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetMainActivityPersistentState(
            context = context,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = ReadyRemoteModelConfig,
        )

        val firstPreference = "长期记忆 UI 测试偏好 Alpha"
        val secondPreference = "长期记忆 UI 测试偏好 Beta"
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, true)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use {
            composeRule.waitForTag("app_title")

            composeRule.sendPrompt("请记住：$firstPreference")
            composeRule.waitForText(firstPreference, substring = true)
            composeRule.waitForText("已记住这条本地偏好", substring = true)

            composeRule.openLongTermMemoryPanel()
            composeRule.onNodeWithTag("memory_switch").performScrollTo().assertIsOn()
            composeRule.onModelSheetText(firstPreference)
                .performScrollTo()
                .assertIsDisplayed()

            composeRule.onForgetLongTermMemoryButton()
                .performScrollTo()
                .performClick()
            composeRule.waitForModelSheetTextGone(firstPreference)
            composeRule.waitForText("还没有已保存的长期记忆。")

            composeRule.onNodeWithTag("model_manager_close_button").performClick()
            composeRule.waitForTagGone("model_manager_sheet", timeoutMillis = 10_000)

            composeRule.sendPrompt("请记住：$secondPreference")
            composeRule.waitForText(secondPreference, substring = true)

            composeRule.openLongTermMemoryPanel()
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
    }

    private fun ComposeTestRule.sendPrompt(prompt: String) {
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

    private fun ComposeTestRule.onModelSheetText(text: String) =
        onNode(
            hasText(text, substring = true) and
                hasAnyAncestor(hasTestTag("model_manager_sheet")),
        )

    private fun ComposeTestRule.onForgetLongTermMemoryButton() =
        onNode(
            hasClickAction() and
                hasContentDescription("遗忘这条记忆") and
                hasAnyAncestor(hasTestTag("model_manager_sheet")),
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
                    hasAnyAncestor(hasTestTag("model_manager_sheet")),
            ).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun ComposeTestRule.waitForText(
        text: String,
        timeoutMillis: Long = 10_000,
        substring: Boolean = false,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
