package com.bytedance.zgx.pocketmind

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bytedance.zgx.pocketmind.resource.SystemResourceSnapshot
import com.bytedance.zgx.pocketmind.resource.ThermalPressure
import com.bytedance.zgx.pocketmind.ui.PocketMindScreen
import com.bytedance.zgx.pocketmind.ui.theme.PocketMindTheme
import org.junit.Rule
import org.junit.Test

internal const val RESOURCE_ENTRY_TAG = "resource_pressure_entry"
internal const val RESOURCE_MENU_ENTRY_TAG = "top_resource_button"
internal const val RESOURCE_DETAIL_PANEL_TAG = "resource_pressure_panel"
internal const val RESOURCE_DETAIL_SHEET_TAG = "resource_detail_sheet"

class PocketMindResourceIndicatorUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun resourceEntryOpensDetailsWithoutPressurePercentBadgeDependency() {
        composeRule.setPocketMindScreenWithResourceSnapshot()

        composeRule.openResourceDetails()

        composeRule.waitForResourceDetail()
        composeRule.onNodeWithText("App 内存").assertIsDisplayed()
        composeRule.onNodeWithText("App CPU").assertIsDisplayed()
        composeRule.onNodeWithText("温度").assertIsDisplayed()
        composeRule.onNodeWithText("可用 RAM").assertIsDisplayed()
        composeRule.assertAdvancedHeapDetailsIfExposed()
    }

    private fun ComposeTestRule.openResourceDetails() {
        waitForTag("app_title")
        if (!hasResourceEntry()) {
            onNodeWithTag("top_more_button")
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()
        }
        waitForResourceEntry()
        onNode(hasTestTag(RESOURCE_ENTRY_TAG) or hasTestTag(RESOURCE_MENU_ENTRY_TAG))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
    }

    private fun ComposeTestRule.waitForResourceEntry() {
        waitUntil(timeoutMillis = 5_000) {
            hasResourceEntry()
        }
    }

    private fun ComposeTestRule.hasResourceEntry(): Boolean =
        hasTag(RESOURCE_ENTRY_TAG) || hasTag(RESOURCE_MENU_ENTRY_TAG)

    private fun ComposeTestRule.waitForResourceDetail() {
        waitUntil(timeoutMillis = 5_000) {
            hasTag(RESOURCE_DETAIL_PANEL_TAG) || hasTag(RESOURCE_DETAIL_SHEET_TAG)
        }
        onNode(hasTestTag(RESOURCE_DETAIL_PANEL_TAG) or hasTestTag(RESOURCE_DETAIL_SHEET_TAG))
            .assertIsDisplayed()
    }

    private fun ComposeTestRule.assertAdvancedHeapDetailsIfExposed() {
        if (onAllNodesWithText("Heap").fetchSemanticsNodes().isNotEmpty()) {
            onNodeWithText("Heap").assertIsDisplayed()
            onNodeWithText("Java", substring = true).assertIsDisplayed()
            onNodeWithText("Native", substring = true).assertIsDisplayed()
        }
    }
}

internal fun ComposeContentTestRule.setPocketMindScreenWithResourceSnapshot(
    modifier: Modifier = Modifier.fillMaxSize(),
    state: ChatUiState = ChatUiState(isReady = true),
    snapshot: SystemResourceSnapshot = testResourceSnapshot(),
) {
    setContent {
        PocketMindTheme {
            Box(modifier = modifier) {
                PocketMindScreen(
                    state = state,
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
                    onDismissRemoteModeDisclosure = {},
                    onConfirmRemoteSendDisclosure = {},
                    onConfirmRemoteSendWithMasking = {},
                    onConfirmRemoteSendDespiteSensitive = {},
                    onDismissRemoteSendDisclosure = {},
                    onRemoteSendDisclosurePolicySelected = {},
                    onReduceDeviceActionConfirmationsChanged = {},
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
                    resourceSampler = { snapshot },
                )
            }
        }
    }
}

internal fun ComposeTestRule.waitForTag(tag: String, timeoutMillis: Long = 5_000) {
    waitUntil(timeoutMillis = timeoutMillis) {
        hasTag(tag)
    }
}

internal fun ComposeTestRule.hasTag(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

internal fun testResourceSnapshot(): SystemResourceSnapshot =
    SystemResourceSnapshot(
        appPssBytes = 512L * 1024L * 1024L,
        javaHeapBytes = 128L * 1024L * 1024L,
        nativeHeapBytes = 180L * 1024L * 1024L,
        availableRamBytes = 640L * 1024L * 1024L,
        lowMemory = false,
        appCpuPercent = 82,
        thermalPressure = ThermalPressure.Hot,
    )
