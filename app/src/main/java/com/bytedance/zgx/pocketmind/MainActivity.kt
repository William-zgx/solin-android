package com.bytedance.zgx.pocketmind

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.bytedance.zgx.pocketmind.device.PocketMindAccessibilityService
import com.bytedance.zgx.pocketmind.multimodal.SharedInputReadMode
import com.bytedance.zgx.pocketmind.multimodal.ShareIntentReader
import com.bytedance.zgx.pocketmind.ui.PocketMindScreen
import com.bytedance.zgx.pocketmind.ui.theme.PocketMindTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val appContainer: PocketMindAppContainer by lazy {
        PocketMindAppContainer(applicationContext)
    }
    private val viewModel: PocketMindViewModel by viewModels {
        appContainer.viewModelFactory
    }
    private val shareIntentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingRuntimePermissionConfirmation: PendingAgentConfirmation? = null
    private var pendingSpecialAccessRequirement: SpecialAccessRequirement? = null
    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantResults ->
        val confirmation = pendingRuntimePermissionConfirmation ?: return@registerForActivityResult
        pendingRuntimePermissionConfirmation = null
        val deniedPermissions = confirmation.deniedRuntimePermissionsAfterGrantResult(
            grantResults = grantResults,
            hasRuntimePermission = ::hasRuntimePermission,
        )
        if (deniedPermissions.isEmpty()) {
            confirmAgentConfirmationWithPermissions(confirmation)
        } else {
            viewModel.rejectAgentConfirmationForRuntimePermissionDenial(
                confirmation = confirmation,
                deniedPermissions = deniedPermissions,
            )
        }
    }
    private val specialAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val requirement = pendingSpecialAccessRequirement ?: return@registerForActivityResult
        pendingSpecialAccessRequirement = null
        viewModel.reportSpecialAccessResult(
            requirement = requirement,
            granted = hasSpecialAccess(requirement),
        )
    }
    private val voiceInputLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            viewModel.reportVoiceInputUnavailable("语音输入已取消")
            return@registerForActivityResult
        }
        val transcript = result.data?.recognizedSpeechText()
        if (transcript.isNullOrBlank()) {
            viewModel.reportVoiceInputUnavailable("未识别到语音")
        } else {
            viewModel.acceptVoiceTranscript(transcript)
        }
    }
    private val sharedAttachmentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        handlePickedSharedUris(uris)
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
        restorePendingSpecialAccessRequirement(savedInstanceState)
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
                    onForgetLongTermMemory = viewModel::forgetLongTermMemory,
                    onClearLongTermMemory = viewModel::clearLongTermMemory,
                    onRefreshBackgroundTasks = viewModel::refreshBackgroundTasks,
                    onRefreshAuditEvents = viewModel::refreshAuditEvents,
                    onCancelBackgroundTask = viewModel::cancelBackgroundTask,
                    onSetPeriodicCheckPolicy = viewModel::setPeriodicCheckPolicy,
                    onDisablePeriodicCheckPolicy = viewModel::disablePeriodicCheckPolicy,
                    onOpenSpecialAccessSettings = ::openSpecialAccessSettings,
                    onConfirmAgentConfirmation = ::confirmAgentConfirmationWithPermissions,
                    onDismissAgentConfirmation = viewModel::dismissAgentConfirmation,
                    onOpenRecoveryAction = viewModel::requestRecoveryActionConfirmation,
                    onSendMessage = viewModel::sendMessage,
                    onStartVoiceInput = ::startVoiceInput,
                    onPickSharedAttachment = {
                        sharedAttachmentLauncher.launch(SHARED_ATTACHMENT_MIME_TYPES)
                    },
                    onVoiceInputConsumed = viewModel::consumeVoiceInputDraft,
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

    override fun onSaveInstanceState(outState: Bundle) {
        pendingSpecialAccessRequirement?.id?.let {
            outState.putString(KEY_PENDING_SPECIAL_ACCESS_REQUIREMENT_ID, it)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        shareIntentScope.cancel()
        super.onDestroy()
    }

    private fun restorePendingSpecialAccessRequirement(savedInstanceState: Bundle?) {
        pendingSpecialAccessRequirement = restoredPendingSpecialAccessRequirement(
            requirementId = savedInstanceState?.getString(KEY_PENDING_SPECIAL_ACCESS_REQUIREMENT_ID),
            pendingConfirmation = viewModel.uiState.value.pendingConfirmation,
        )
    }

    private fun handleSharedIntent(intent: Intent?) {
        val sharedIntent = intent ?: return
        shareIntentScope.launch {
            val readMode = sharedInputReadMode()
            val sharedInput = withContext(Dispatchers.IO) {
                ShareIntentReader(applicationContext).read(sharedIntent, mode = readMode)
            }
            sharedInput?.let(viewModel::ingestSharedInput)
        }
    }

    private fun handlePickedSharedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        shareIntentScope.launch {
            val readMode = sharedInputReadMode()
            val sharedInput = withContext(Dispatchers.IO) {
                ShareIntentReader(applicationContext).readUris(uris, mode = readMode)
            }
            sharedInput?.let(viewModel::ingestSharedInput)
        }
    }

    private fun sharedInputReadMode(): SharedInputReadMode =
        if (viewModel.uiState.value.inferenceMode == InferenceMode.Remote) {
            SharedInputReadMode.ProtectedSignal
        } else {
            SharedInputReadMode.LocalPrompt
        }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "开始说话")
        runCatching { voiceInputLauncher.launch(intent) }
            .onFailure {
                viewModel.reportVoiceInputUnavailable("当前设备没有可用语音识别服务")
            }
    }

    private fun openSpecialAccessSettings(requirement: SpecialAccessRequirement) {
        pendingSpecialAccessRequirement = requirement
        runCatching {
            specialAccessLauncher.launch(Intent(requirement.settingsAction))
        }.onFailure {
            pendingSpecialAccessRequirement = null
            viewModel.reportSystemSettingsUnavailable("当前设备无法打开${requirement.title}设置")
        }
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
        val missingSpecialAccess = confirmation.specialAccessRequirementsFor()
            .filterNot(::hasSpecialAccess)
        if (missingSpecialAccess.isNotEmpty()) {
            viewModel.rejectAgentConfirmationForSpecialAccessDenial(
                confirmation = confirmation,
                deniedRequirements = missingSpecialAccess,
            )
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

    private fun hasSpecialAccess(requirement: SpecialAccessRequirement): Boolean =
        when (requirement.id) {
            SPECIAL_ACCESS_USAGE_STATS -> hasUsageStatsAccess()
            SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT -> hasAccessibilityScreenTextAccess()
            else -> false
        }

    private fun hasUsageStatsAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    packageName,
                )
            } else {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    packageName,
                )
            }
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasAccessibilityScreenTextAccess(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val target = ComponentName(this, PocketMindAccessibilityService::class.java).flattenToString()
        return enabledServices
            .split(':')
            .any { enabled ->
                ComponentName.unflattenFromString(enabled)
                    ?.flattenToString()
                    ?.equals(target, ignoreCase = true) == true
            }
    }

    companion object {
        const val EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK =
            "com.bytedance.zgx.pocketmind.extra.SKIP_STARTUP_MODEL_RUNTIME_WORK"
        private const val KEY_PENDING_SPECIAL_ACCESS_REQUIREMENT_ID =
            "com.bytedance.zgx.pocketmind.state.PENDING_SPECIAL_ACCESS_REQUIREMENT_ID"
        private val SHARED_ATTACHMENT_MIME_TYPES = arrayOf(
            "text/*",
            "image/*",
            "audio/*",
            "video/*",
            "application/pdf",
            "application/rtf",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        )

        private fun isRunningUnderAndroidTest(): Boolean =
            runCatching {
                val registry = Class.forName("androidx.test.platform.app.InstrumentationRegistry")
                registry.getMethod("getInstrumentation").invoke(null) != null
            }.getOrDefault(false)
    }
}

private fun Intent.recognizedSpeechText(): String? =
    getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
