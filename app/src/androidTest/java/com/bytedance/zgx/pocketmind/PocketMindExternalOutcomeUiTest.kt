package com.bytedance.zgx.pocketmind

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.bytedance.zgx.pocketmind.orchestration.AgentExternalOutcome
import com.bytedance.zgx.pocketmind.ui.PocketMindScreen
import com.bytedance.zgx.pocketmind.ui.theme.PocketMindTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PocketMindExternalOutcomeUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun externalOutcomeSheetReportsCompletedSelection() {
        assertExternalOutcomeSelection(
            buttonTag = "external_outcome_completed_button",
            expectedOutcome = AgentExternalOutcome.Completed,
        )
    }

    @Test
    fun externalOutcomeSheetReportsNotCompletedSelection() {
        assertExternalOutcomeSelection(
            buttonTag = "external_outcome_not_completed_button",
            expectedOutcome = AgentExternalOutcome.NotCompleted,
        )
    }

    @Test
    fun externalOutcomeSheetReportsOpenedOnlySelection() {
        assertExternalOutcomeSelection(
            buttonTag = "external_outcome_opened_only_button",
            expectedOutcome = AgentExternalOutcome.OpenedOnly,
        )
    }

    private fun assertExternalOutcomeSelection(
        buttonTag: String,
        expectedOutcome: AgentExternalOutcome,
    ) {
        val pending = PendingExternalOutcomeConfirmation(
            runId = "run-share",
            requestId = "request-share",
            toolName = "share_text",
            title = "系统分享",
            summary = "已打开外部界面，但无法确认目标应用中的后续操作是否完成：已打开系统分享面板",
        )
        var recorded: Pair<PendingExternalOutcomeConfirmation, AgentExternalOutcome>? = null

        composeRule.setContent {
            PocketMindTheme {
                PocketMindScreen(
                    state = ChatUiState(
                        isReady = true,
                        pendingExternalOutcome = pending,
                    ),
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
                    onRecordExternalOutcome = { currentPending, outcome ->
                        recorded = currentPending to outcome
                    },
                    onOpenRecoveryAction = {},
                    onDismissRemoteModeDisclosure = {},
                    onConfirmRemoteSendDisclosure = {},
                    onConfirmRemoteSendWithMasking = {},
                    onConfirmRemoteSendDespiteSensitive = {},
                    onDismissRemoteSendDisclosure = {},
                    onRemoteSendDisclosurePolicySelected = {},
                    onSendMessage = {},
                    onSendPendingSharedInput = {},
                    onClearPendingSharedInput = {},
                    onStartVoiceInput = {},
                    onCancelVoiceInput = {},
                    onFinishVoiceInput = {},
                    onPickSharedAttachment = {},
                    onVoiceInputConsumed = {},
                    onSaveHuggingFaceAccessToken = {},
                    onClearHuggingFaceAccessToken = {},
                    onStopGeneration = {},
                )
            }
        }

        composeRule.onNodeWithTag("external_outcome_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("external_outcome_completed_button").assertIsDisplayed()
        composeRule.onNodeWithTag("external_outcome_not_completed_button").assertIsDisplayed()
        composeRule.onNodeWithTag("external_outcome_opened_only_button").assertIsDisplayed()

        composeRule.onNodeWithTag(buttonTag).performClick()

        assertEquals(pending, recorded?.first)
        assertEquals(expectedOutcome, recorded?.second)
    }
}
