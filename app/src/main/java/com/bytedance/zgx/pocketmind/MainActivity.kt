package com.bytedance.zgx.pocketmind

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.bytedance.zgx.pocketmind.multimodal.ShareIntentReader
import com.bytedance.zgx.pocketmind.ui.PocketMindScreen
import com.bytedance.zgx.pocketmind.ui.theme.PocketMindTheme

class MainActivity : ComponentActivity() {
    private val appContainer: PocketMindAppContainer by lazy {
        PocketMindAppContainer(applicationContext)
    }
    private val viewModel: PocketMindViewModel by viewModels {
        appContainer.viewModelFactory
    }
    private var pendingRuntimePermissionConfirmation: PendingAgentConfirmation? = null
    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        pendingRuntimePermissionConfirmation?.let(viewModel::confirmAgentConfirmation)
        pendingRuntimePermissionConfirmation = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val skipStartupModelRuntimeWork =
            intent.getBooleanExtra(EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, false) ||
                isRunningUnderAndroidTest()
        viewModel.restoreStartupState(
            skipModelRuntimeWork = skipStartupModelRuntimeWork,
        )
        handleSharedIntent(intent)

        setContent {
            PocketMindTheme {
                val context = LocalContext.current
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                PocketMindScreen(
                    state = state,
                    onImportModel = viewModel::importModel,
                    onDownloadModel = viewModel::startModelDownload,
                    onDownloadRecommendedModel = viewModel::startRecommendedModelDownload,
                    onDownloadCustomModel = viewModel::startCustomModelDownload,
                    onCancelDownload = viewModel::cancelModelDownload,
                    onLoadModel = viewModel::loadModel,
                    onRecommendedModelSelected = viewModel::selectRecommendedModel,
                    onInstalledModelSelected = viewModel::selectInstalledModel,
                    onInferenceModeSelected = viewModel::selectInferenceMode,
                    onRemoteModelConfigChanged = viewModel::updateRemoteModelConfig,
                    onBackendSelected = viewModel::selectBackend,
                    onGenerationParametersChanged = viewModel::updateGenerationParameters,
                    onResetGenerationParameters = viewModel::resetGenerationParameters,
                    onCreateSession = viewModel::createNewSession,
                    onSessionSelected = viewModel::selectSession,
                    onDeleteSession = viewModel::deleteActiveSession,
                    onOpenModelPage = {
                        if (!skipStartupModelRuntimeWork) {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(state.selectedRecommendedModel.repositoryUrl)),
                            )
                        }
                    },
                    onSetupModelToggled = viewModel::toggleSetupModel,
                    onDownloadSetupModels = viewModel::startSetupModelDownload,
                    onSkipFirstRunSetup = viewModel::skipFirstRunSetup,
                    onMemoryEnabledChanged = viewModel::updateMemoryEnabled,
                    onConfirmAgentConfirmation = ::confirmAgentConfirmationWithPermissions,
                    onDismissAgentConfirmation = viewModel::dismissAgentConfirmation,
                    onSendMessage = viewModel::sendMessage,
                    onStopGeneration = viewModel::stopGeneration,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent?) {
        ShareIntentReader(this).read(intent)?.let(viewModel::ingestSharedInput)
    }

    private fun confirmAgentConfirmationWithPermissions(confirmation: PendingAgentConfirmation) {
        if (pendingRuntimePermissionConfirmation != null) return
        val missingPermissions = confirmation.runtimePermissionsFor()
            .filterNot(::hasRuntimePermission)
        if (missingPermissions.isNotEmpty()) {
            pendingRuntimePermissionConfirmation = confirmation
            runtimePermissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }
        viewModel.confirmAgentConfirmation(confirmation)
    }

    private fun hasRuntimePermission(permission: String): Boolean {
        if (permission == Manifest.permission.POST_NOTIFICATIONS &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            return true
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK =
            "com.bytedance.zgx.pocketmind.extra.SKIP_STARTUP_MODEL_RUNTIME_WORK"

        private fun isRunningUnderAndroidTest(): Boolean =
            runCatching {
                val registry = Class.forName("androidx.test.platform.app.InstrumentationRegistry")
                registry.getMethod("getInstrumentation").invoke(null) != null
            }.getOrDefault(false)
    }
}
