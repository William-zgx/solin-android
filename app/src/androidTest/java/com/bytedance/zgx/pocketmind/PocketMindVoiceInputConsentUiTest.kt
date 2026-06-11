package com.bytedance.zgx.pocketmind

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.bytedance.zgx.pocketmind.ui.PocketMindScreen
import com.bytedance.zgx.pocketmind.ui.VOICE_INPUT_PERMISSION_DISCLOSURE_BODY
import com.bytedance.zgx.pocketmind.ui.VOICE_INPUT_PERMISSION_DISCLOSURE_TITLE
import com.bytedance.zgx.pocketmind.ui.VOICE_INPUT_PRIVACY_DESCRIPTION
import com.bytedance.zgx.pocketmind.ui.theme.PocketMindTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PocketMindVoiceInputConsentUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun voiceButtonRequiresAppConsentBeforeStartingVoiceInput() {
        var startVoiceInputCount = 0
        var screenState by mutableStateOf(ChatUiState(isReady = true))

        setPocketMindScreen(
            state = { screenState },
            onStartVoiceInput = {
                startVoiceInputCount += 1
                screenState = screenState.copy(
                    voiceCapture = VoiceCaptureUiState(
                        isListening = true,
                        partialText = "正在听写",
                    ),
                )
            },
        )

        composeRule.onNodeWithTag("composer_voice_button").performClick()

        assertEquals(0, startVoiceInputCount)
        composeRule.onNodeWithTag("voice_permission_disclosure_dialog").assertIsDisplayed()
        composeRule.onNodeWithText(VOICE_INPUT_PERMISSION_DISCLOSURE_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(VOICE_INPUT_PERMISSION_DISCLOSURE_BODY).assertIsDisplayed()
        composeRule.onNodeWithText("同意并开启语音输入").assertIsDisplayed()

        composeRule.onNodeWithTag("voice_permission_cancel_button").performClick()
        composeRule.waitForTagGone("voice_permission_disclosure_dialog")
        assertEquals(0, startVoiceInputCount)

        composeRule.onNodeWithTag("composer_voice_button").performClick()
        composeRule.onNodeWithTag("voice_permission_consent_button").performClick()

        composeRule.waitForTagGone("voice_permission_disclosure_dialog")
        assertEquals(1, startVoiceInputCount)
        composeRule.onNodeWithTag("voice_capture_bar").assertIsDisplayed()
        composeRule.onNodeWithText(VOICE_INPUT_PRIVACY_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun voiceTranscriptDraftFillsComposerOnceWithoutSending() {
        var screenState by mutableStateOf(ChatUiState(isReady = true))
        val consumedDraftIds = mutableListOf<Long>()
        val sentMessages = mutableListOf<String>()

        setPocketMindScreen(
            state = { screenState },
            onSendMessage = { sentMessages += it },
            onVoiceInputConsumed = { consumedDraftIds += it },
        )

        composeRule.onNodeWithTag("composer_input").performTextInput("已有内容")

        composeRule.runOnIdle {
            screenState = screenState.copy(
                voiceInputDraft = VoiceInputDraft(
                    id = 42L,
                    text = "  语音转写结果  ",
                ),
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            consumedDraftIds == listOf(42L)
        }

        composeRule.onNodeWithTag("composer_input")
            .assertTextEquals("已有内容\n语音转写结果")
        assertEquals(emptyList<String>(), sentMessages)

        composeRule.runOnIdle {
            screenState = screenState.copy(statusText = "刷新 UI")
        }
        composeRule.waitForIdle()

        assertEquals(listOf(42L), consumedDraftIds)
        assertEquals(emptyList<String>(), sentMessages)
    }

    private fun setPocketMindScreen(
        state: () -> ChatUiState = { ChatUiState(isReady = true) },
        onSendMessage: (String) -> Unit = {},
        onStartVoiceInput: () -> Unit = {},
        onVoiceInputConsumed: (Long) -> Unit = {},
    ) {
        composeRule.setContent {
            PocketMindTheme {
                PocketMindScreen(
                    state = state(),
                    onImportModel = {},
                    onDownloadModel = {},
                    onDownloadRecommendedModel = {},
                    onDownloadCustomModel = {},
                    onCancelDownload = {},
                    onLoadModel = {},
                    onRecommendedModelSelected = {},
                    onInstalledModelSelected = {},
                    onDeleteInstalledModel = {},
                    onInferenceModeSelected = {},
                    onRemoteModelConfigChanged = {},
                    onTestRemoteModelConnectivity = {},
                    onBackendSelected = {},
                    onGenerationParametersChanged = {},
                    onResetGenerationParameters = {},
                    onCreateSession = {},
                    onSessionSelected = {},
                    onDeleteSession = {},
                    onOpenModelPage = {},
                    onSetupModelToggled = { _, _ -> },
                    onDownloadSetupModels = {},
                    onSkipFirstRunSetup = {},
                    onMemoryEnabledChanged = {},
                    onForgetLongTermMemory = {},
                    onClearLongTermMemory = {},
                    onRefreshBackgroundTasks = {},
                    onRefreshAuditEvents = {},
                    onCancelBackgroundTask = {},
                    onSetPeriodicCheckPolicy = {},
                    onDisablePeriodicCheckPolicy = {},
                    onOpenSpecialAccessSettings = {},
                    onConfirmAgentConfirmation = {},
                    onDismissAgentConfirmation = {},
                    onRecordExternalOutcome = { _, _ -> },
                    onOpenRecoveryAction = {},
                    onConfirmRemoteSendDisclosure = {},
                    onConfirmRemoteSendWithMasking = {},
                    onConfirmRemoteSendDespiteSensitive = {},
                    onDismissRemoteSendDisclosure = {},
                    onRemoteSendDisclosurePolicySelected = {},
                    onSendMessage = onSendMessage,
                    onSendPendingSharedInput = {},
                    onClearPendingSharedInput = {},
                    onStartVoiceInput = onStartVoiceInput,
                    onCancelVoiceInput = {},
                    onFinishVoiceInput = {},
                    onPickSharedAttachment = {},
                    onVoiceInputConsumed = onVoiceInputConsumed,
                    onStopGeneration = {},
                )
            }
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForTagGone(
        tag: String,
        timeoutMillis: Long = 5_000,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }
}
