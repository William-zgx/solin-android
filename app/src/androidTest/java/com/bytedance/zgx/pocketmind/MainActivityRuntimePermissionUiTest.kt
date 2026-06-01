package com.bytedance.zgx.pocketmind

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class MainActivityRuntimePermissionUiTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun contactLookupConfirmationShowsRuntimePermissionRequirementWithoutSpecialAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetMainActivityPersistentState(
            context = context,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = ReadyRemoteModelConfig,
        )
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, true)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use {
            composeRule.waitForTag("app_title")

            composeRule.sendPrompt("查联系人 Alice")

            composeRule.waitForTag("runtime_permission_requirements")
            composeRule.onNodeWithText("查询联系人").assertIsDisplayed()
            composeRule.onNodeWithText("将按“Alice”查询联系人。").assertIsDisplayed()
            composeRule.onNodeWithTag("runtime_permission_requirements")
                .assertIsDisplayed()
            composeRule.onNodeWithText("确认后可能请求系统权限")
                .assertIsDisplayed()
            composeRule.onNodeWithText("联系人权限：用于只读查询联系人摘要。")
                .assertIsDisplayed()
            composeRule.assertTagAbsent("special_access_requirements")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("runtime_permission_requirements")
        }
    }

    private fun ComposeTestRule.sendPrompt(prompt: String) {
        onNodeWithTag("composer_input").performTextClearance()
        onNodeWithTag("composer_input").performTextInput(prompt)
        onNodeWithTag("composer_send_button").performClick()
    }

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

    private fun ComposeTestRule.assertTagAbsent(tag: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }
}
