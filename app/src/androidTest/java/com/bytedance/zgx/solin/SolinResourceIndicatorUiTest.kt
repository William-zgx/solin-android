package com.bytedance.zgx.solin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bytedance.zgx.solin.ui.SolinScreen
import com.bytedance.zgx.solin.resource.SystemResourceSnapshot
import com.bytedance.zgx.solin.resource.ThermalPressure
import com.bytedance.zgx.solin.ui.ResourcePressureBadge
import com.bytedance.zgx.solin.ui.theme.SolinTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SolinResourceIndicatorUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun badgeExpandsResourcePanel() {
        composeRule.setContent {
            SolinTheme {
                ResourcePressureBadge(
                    snapshot = SystemResourceSnapshot(
                        appPssBytes = 512L * 1024L * 1024L,
                        javaHeapBytes = 128L * 1024L * 1024L,
                        nativeHeapBytes = 180L * 1024L * 1024L,
                        availableRamBytes = 640L * 1024L * 1024L,
                        lowMemory = false,
                        appCpuPercent = 82,
                        thermalPressure = ThermalPressure.Hot,
                    ),
                )
            }
        }

        val badge = composeRule.onNodeWithTag("resource_pressure_badge").assertIsDisplayed()
        val bounds = badge.fetchSemanticsNode().boundsInRoot
        with(composeRule.density) {
            assertTrue(bounds.width.toDp() >= 48.dp)
            assertTrue(bounds.height.toDp() >= 48.dp)
        }
        badge.performClick()

        composeRule.onNodeWithTag("resource_pressure_panel").assertIsDisplayed()
        composeRule.onNodeWithText("App 内存").assertIsDisplayed()
        composeRule.onNodeWithText("App CPU").assertIsDisplayed()
        composeRule.onNodeWithText("82%").assertIsDisplayed()
        composeRule.onNodeWithText("温度").assertIsDisplayed()
        composeRule.onNodeWithText("可用 RAM").assertIsDisplayed()
    }
}

internal fun ComposeContentTestRule.setSolinScreenWithResourceSnapshot(
    modifier: Modifier = Modifier.fillMaxSize(),
    state: ChatUiState = ChatUiState(isReady = true),
    snapshot: SystemResourceSnapshot = testResourceSnapshot(),
) {
    setContent {
        SolinTheme {
            Box(modifier = modifier) {
                SolinScreen(
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
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}

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
