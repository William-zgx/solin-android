package com.bytedance.zgx.pocketmind

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bytedance.zgx.pocketmind.data.FirstRunSetupRepository
import com.bytedance.zgx.pocketmind.data.PocketMindDatabase
import com.bytedance.zgx.pocketmind.data.PreferenceSettingsStore
import org.junit.Rule
import org.junit.Test

class MainActivitySharedIntentTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun actionSendTextIsIngestedThroughActivityShareEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetPersistentState(context, inferenceMode = InferenceMode.Local)

        val sharedText = "Shared ACTION_SEND text from Activity test 42"
        val launchIntent = Intent(Intent.ACTION_SEND).apply {
            setClass(context, MainActivity::class.java)
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sharedText)
            putExtra(MainActivity.EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, true)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use {
            composeRule.waitForTag("app_title")
            composeRule.waitForText(sharedText, substring = true)
            composeRule.waitForText("已接收分享内容", substring = true)

            composeRule.onNodeWithTag("app_title").assertIsDisplayed()
            composeRule.onAllNodesWithText(sharedText, substring = true)
                .onFirst()
                .assertIsDisplayed()
            composeRule.onAllNodesWithText("已接收分享内容", substring = true)
                .onFirst()
                .assertIsDisplayed()
        }
    }

    @Test
    fun actionSendTextUsesProtectedSignalWhenActivityStartsInRemoteMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetPersistentState(context, inferenceMode = InferenceMode.Remote)

        val protectedText = "REMOTE_SHARE_SENTINEL_should_not_render_73"
        val launchIntent = Intent(Intent.ACTION_SEND).apply {
            setClass(context, MainActivity::class.java)
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, protectedText)
            putExtra(MainActivity.EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, true)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use {
            composeRule.waitForTag("app_title")
            composeRule.waitForText("不会读取或自动发送分享文本", substring = true)

            composeRule.onNodeWithText("不会读取或自动发送分享文本", substring = true)
                .assertIsDisplayed()
            composeRule.assertTextAbsent(protectedText)
        }
    }

    private fun resetPersistentState(context: Context, inferenceMode: InferenceMode) {
        val settingsStore = PreferenceSettingsStore(context)
        settingsStore.saveInferenceMode(inferenceMode)
        settingsStore.saveActiveSessionId("")
        FirstRunSetupRepository(settingsStore).markSetupDismissed()
        PocketMindDatabase.get(context).clearAllTables()
    }

    private fun ComposeTestRule.waitForTag(tag: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
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

    private fun ComposeTestRule.assertTextAbsent(text: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()
        }
    }
}
