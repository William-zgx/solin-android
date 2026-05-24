package com.bytedance.zgx.pocketmind

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.bytedance.zgx.pocketmind.ui.PocketMindScreen
import com.bytedance.zgx.pocketmind.ui.theme.PocketMindTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PocketMindViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PocketMindTheme {
                val context = LocalContext.current
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                PocketMindScreen(
                    state = state,
                    onImportModel = viewModel::importModel,
                    onDownloadModel = viewModel::startModelDownload,
                    onDownloadCustomModel = viewModel::startCustomModelDownload,
                    onCancelDownload = viewModel::cancelModelDownload,
                    onLoadModel = viewModel::loadModel,
                    onRecommendedModelSelected = viewModel::selectRecommendedModel,
                    onInstalledModelSelected = viewModel::selectInstalledModel,
                    onBackendSelected = viewModel::selectBackend,
                    onGenerationParametersChanged = viewModel::updateGenerationParameters,
                    onResetGenerationParameters = viewModel::resetGenerationParameters,
                    onCreateSession = viewModel::createNewSession,
                    onSessionSelected = viewModel::selectSession,
                    onDeleteSession = viewModel::deleteActiveSession,
                    onOpenModelPage = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(state.selectedRecommendedModel.repositoryUrl)),
                        )
                    },
                    onSetupModelToggled = viewModel::toggleSetupModel,
                    onDownloadSetupModels = viewModel::startSetupModelDownload,
                    onSkipFirstRunSetup = viewModel::skipFirstRunSetup,
                    onMemoryEnabledChanged = viewModel::updateMemoryEnabled,
                    onConfirmActionDraft = viewModel::confirmActionDraft,
                    onDismissActionDraft = viewModel::dismissActionDraft,
                    onSendMessage = viewModel::sendMessage,
                    onStopGeneration = viewModel::stopGeneration,
                )
            }
        }
    }
}
