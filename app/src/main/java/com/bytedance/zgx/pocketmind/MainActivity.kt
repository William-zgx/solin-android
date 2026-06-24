package com.bytedance.zgx.pocketmind

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.bytedance.zgx.pocketmind.device.DeviceContextAuthorizationSnapshot
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
        appContainer.viewModelFactory(
            skipStartupModelRuntimeWork = shouldSkipStartupModelRuntimeWork(),
        )
    }
    private val shareIntentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingRuntimePermissionConfirmation: PendingAgentConfirmation? = null
    private var pendingMediaProjectionConfirmation: PendingAgentConfirmation? = null
    private var pendingSpecialAccessRequirement: SpecialAccessRequirement? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceInputCancellationRequested = false
    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantResults ->
        val confirmation = pendingRuntimePermissionConfirmationForResult(grantResults.keys)
        pendingRuntimePermissionConfirmation = null
        syncDeviceContextAuthorizationSnapshot()
        if (confirmation == null) {
            rejectCurrentRuntimePermissionPendingAfterUnmatchedResult(grantResults.keys)
            return@registerForActivityResult
        }
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
        syncDeviceContextAuthorizationSnapshot()
        viewModel.reportSpecialAccessResult(
            requirement = requirement,
            granted = hasSpecialAccess(requirement),
        )
    }
    private val voiceAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startVoiceInput()
        } else {
            viewModel.reportVoiceInputUnavailable("未授权麦克风权限")
        }
    }
    private val sharedAttachmentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        handlePickedSharedUris(uris)
    }
    private val currentScreenshotOcrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val confirmation = pendingMediaProjectionConfirmationForResult()
        pendingMediaProjectionConfirmation = null
        if (confirmation == null) {
            rejectCurrentMediaProjectionPendingAfterUnmatchedResult()
            return@registerForActivityResult
        }
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val requestId = confirmation.toolRequest?.id
            if (requestId == null) {
                viewModel.rejectAgentConfirmationForMediaProjectionDenial(confirmation)
            } else {
                appContainer.currentScreenshotOcrProvider.setOneShotConsent(
                    requestId = requestId,
                    resultCode = result.resultCode,
                    data = result.data,
                )
                viewModel.confirmAgentConfirmation(confirmation)
            }
        } else {
            viewModel.rejectAgentConfirmationForMediaProjectionDenial(confirmation)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val debugFontScale = runCatching {
            intent.getFloatExtra(EXTRA_DEBUG_UI_FONT_SCALE, 0f)
        }.getOrDefault(0f)
        if (debugFontScale > 0f && isRunningUnderAndroidTest()) {
            val configuration = Configuration(newBase.resources.configuration).apply {
                fontScale = debugFontScale
            }
            super.attachBaseContext(newBase.createConfigurationContext(configuration))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                scrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                scrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT,
            ),
        )
        val skipStartupModelRuntimeWork = shouldSkipStartupModelRuntimeWork()
        viewModel.restoreStartupState(
            skipModelRuntimeWork = skipStartupModelRuntimeWork,
        )
        configureDebugRemoteModelForScreenshotEvidenceIfPresent(intent)
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
                    onSaveHuggingFaceAccessToken = viewModel::saveHuggingFaceAccessToken,
                    onClearHuggingFaceAccessToken = viewModel::clearHuggingFaceAccessToken,
                    onCancelDownload = viewModel::cancelModelDownload,
                    onLoadModel = viewModel::loadModel,
                    onRecommendedModelSelected = viewModel::selectRecommendedModel,
                    onInstalledModelSelected = viewModel::selectInstalledModel,
                    onDeleteInstalledModel = viewModel::deleteInstalledModel,
                    onInferenceModeSelected = viewModel::selectInferenceMode,
                    onRemoteModelConfigChanged = viewModel::updateRemoteModelConfig,
                    onTestRemoteModelConnectivity = viewModel::testRemoteModelConnectivity,
                    onBackendSelected = viewModel::selectBackend,
                    onGenerationParametersChanged = viewModel::updateGenerationParameters,
                    onResetGenerationParameters = viewModel::resetGenerationParameters,
                    onCreateSession = viewModel::createNewSession,
                    onSessionSelected = viewModel::selectSession,
                    onDeleteSession = viewModel::deleteActiveSession,
                    onOpenModelPage = { url ->
                        if (!skipStartupModelRuntimeWork) {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                            )
                        }
                    },
                    onSetupModelToggled = viewModel::toggleSetupModel,
                    onDownloadSetupModels = viewModel::startSetupModelDownload,
                    onSkipFirstRunSetup = viewModel::skipFirstRunSetup,
                    onMemoryEnabledChanged = viewModel::updateMemoryEnabled,
                    onReduceDeviceActionConfirmationsChanged = viewModel::updateReduceDeviceActionConfirmations,
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
                    onRecordExternalOutcome = viewModel::recordExternalOutcome,
                    onOpenRecoveryAction = viewModel::requestRecoveryActionConfirmation,
                    onDismissRemoteModeDisclosure = viewModel::dismissRemoteModeDisclosure,
                    onConfirmRemoteSendDisclosure = viewModel::confirmRemoteSendDisclosure,
                    onConfirmRemoteSendWithMasking = viewModel::confirmRemoteSendWithMasking,
                    onConfirmRemoteSendDespiteSensitive = viewModel::confirmRemoteSendDespiteSensitive,
                    onDismissRemoteSendDisclosure = viewModel::dismissRemoteSendDisclosure,
                    onRemoteSendDisclosurePolicySelected = viewModel::setRemoteSendDisclosurePolicy,
                    onSendMessage = viewModel::sendMessage,
                    onSendPendingSharedInput = viewModel::sendPendingSharedInput,
                    onClearPendingSharedInput = viewModel::clearPendingSharedInputDraft,
                    onStartVoiceInput = ::startVoiceInput,
                    onCancelVoiceInput = ::cancelVoiceInput,
                    onFinishVoiceInput = ::finishVoiceInput,
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
        configureDebugRemoteModelForScreenshotEvidenceIfPresent(intent)
        handleSharedIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        syncDeviceContextAuthorizationSnapshot()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingSpecialAccessRequirement?.id?.let {
            outState.putString(KEY_PENDING_SPECIAL_ACCESS_REQUIREMENT_ID, it)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
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

    private fun configureDebugRemoteModelForScreenshotEvidenceIfPresent(intent: Intent) {
        if (!isDebuggableBuild()) return
        val baseUrl = intent.getStringExtra(EXTRA_DEBUG_SCREENSHOT_REMOTE_BASE_URL) ?: return
        val modelName = intent.getStringExtra(EXTRA_DEBUG_SCREENSHOT_REMOTE_MODEL_NAME) ?: return
        val supportsVisionInput =
            intent.getBooleanExtra(EXTRA_DEBUG_SCREENSHOT_REMOTE_SUPPORTS_VISION_INPUT, false)
        viewModel.configureDebugRemoteModelForScreenshotEvidence(
            baseUrl = baseUrl,
            modelName = modelName,
            supportsVisionInput = supportsVisionInput,
        )
    }

    private fun isDebuggableBuild(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun shouldSkipStartupModelRuntimeWork(): Boolean =
        intent.getBooleanExtra(EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, false) ||
            isRunningUnderAndroidTest()

    private fun handlePickedSharedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        shareIntentScope.launch {
            val readMode = sharedInputReadMode()
            val sharedInput = withContext(Dispatchers.IO) {
                ShareIntentReader(applicationContext).readUris(uris, mode = readMode)
            }
            sharedInput?.let(viewModel::stageSharedInput)
        }
    }

    private fun sharedInputReadMode(): SharedInputReadMode =
        sharedInputReadModeFor(
            inferenceMode = viewModel.uiState.value.inferenceMode,
            localSupportsVisionInput = viewModel.uiState.value.activeLocalModelSupportsVisionInput,
            remoteConfigured = viewModel.uiState.value.remoteModelConfig.isConfigured,
            remoteSupportsVisionInput = viewModel.uiState.value.remoteModelConfig.modelProfile().supportsVisionInput,
        )

    private fun startVoiceInput() {
        if (!hasRuntimePermission(Manifest.permission.RECORD_AUDIO)) {
            viewModel.reportVoiceInputUnavailable("需要授权麦克风权限")
            voiceAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            viewModel.reportVoiceInputUnavailable("当前设备没有可用语音识别服务")
            return
        }
        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            speechRecognizer = it
        }
        voiceInputCancellationRequested = false
        recognizer.setRecognitionListener(appVoiceRecognitionListener())
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        viewModel.startVoiceInputCapture()
        runCatching { recognizer.startListening(intent) }
            .onFailure {
                viewModel.reportVoiceInputUnavailable("语音输入启动失败")
            }
    }

    private fun finishVoiceInput() {
        runCatching { speechRecognizer?.stopListening() }
            .onSuccess { viewModel.finishVoiceInputCapture() }
            .onFailure {
                viewModel.reportVoiceInputUnavailable("当前设备没有可用语音识别服务")
            }
    }

    private fun cancelVoiceInput() {
        voiceInputCancellationRequested = true
        runCatching { speechRecognizer?.cancel() }
        viewModel.reportVoiceInputUnavailable("语音输入已取消")
    }

    private fun appVoiceRecognitionListener(): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                viewModel.startVoiceInputCapture()
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                viewModel.updateVoiceInputLevel(rmsDb = rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                viewModel.finishVoiceInputCapture()
            }

            override fun onError(error: Int) {
                if (voiceInputCancellationRequested) {
                    voiceInputCancellationRequested = false
                    return
                }
                viewModel.reportVoiceInputUnavailable(error.voiceInputErrorMessage())
            }

            override fun onResults(results: Bundle?) {
                val transcript = results?.recognizedSpeechText()
                if (transcript.isNullOrBlank()) {
                    viewModel.reportVoiceInputUnavailable("未识别到语音")
                } else {
                    viewModel.acceptVoiceTranscript(transcript)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.recognizedSpeechText()?.let(viewModel::updateVoiceInputPartialTranscript)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
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
        syncDeviceContextAuthorizationSnapshot()
        if (pendingRuntimePermissionConfirmation != null || pendingMediaProjectionConfirmation != null) return
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
        if (confirmation.requiresCurrentScreenshotOcrConsent()) {
            requestCurrentScreenshotOcrConsent(confirmation)
            return
        }
        viewModel.confirmAgentConfirmation(confirmation)
    }

    private fun requestCurrentScreenshotOcrConsent(confirmation: PendingAgentConfirmation) {
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mediaProjectionManager == null) {
            viewModel.rejectAgentConfirmationForMediaProjectionDenial(confirmation)
            return
        }
        pendingMediaProjectionConfirmation = confirmation
        runCatching {
            currentScreenshotOcrLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }.onFailure {
            pendingMediaProjectionConfirmation = null
            viewModel.rejectAgentConfirmationForMediaProjectionDenial(confirmation)
        }
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
        hasSpecialAccess(requirement.id)

    private fun hasSpecialAccess(id: String): Boolean =
        when (id) {
            SPECIAL_ACCESS_USAGE_STATS -> hasUsageStatsAccess()
            SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT -> hasAccessibilityScreenTextAccess()
            SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL -> hasAccessibilityScreenTextAccess()
            else -> false
        }

    private fun pendingRuntimePermissionConfirmationForResult(
        resultPermissions: Set<String>,
    ): PendingAgentConfirmation? {
        val current = viewModel.uiState.value.pendingConfirmation
        val remembered = pendingRuntimePermissionConfirmation
        if (current != null &&
            current.requiresRuntimePermissionResult(resultPermissions)
        ) {
            return current
        }
        return remembered?.takeIf { pending ->
            current == null && pending.requiresRuntimePermissionResult(resultPermissions)
        }
    }

    private fun rejectCurrentRuntimePermissionPendingAfterUnmatchedResult(
        resultPermissions: Set<String>,
    ) {
        val current = viewModel.uiState.value.pendingConfirmation
            ?.takeIf { pending ->
                pending.requiresRuntimePermissionResult(resultPermissions) ||
                    pending.runtimePermissionsFor().isNotEmpty()
            }
            ?: return
        val deniedPermissions = current.runtimePermissionsFor()
            .filterNot(::hasRuntimePermission)
            .ifEmpty { current.runtimePermissionsFor() }
        viewModel.rejectAgentConfirmationForRuntimePermissionDenial(
            confirmation = current,
            deniedPermissions = deniedPermissions,
        )
    }

    private fun pendingMediaProjectionConfirmationForResult(): PendingAgentConfirmation? {
        val current = viewModel.uiState.value.pendingConfirmation
        val remembered = pendingMediaProjectionConfirmation
        if (current != null && current.requiresCurrentScreenshotOcrConsent()) {
            return current
        }
        return remembered?.takeIf { pending ->
            current == null && pending.requiresCurrentScreenshotOcrConsent()
        }
    }

    private fun rejectCurrentMediaProjectionPendingAfterUnmatchedResult() {
        val current = viewModel.uiState.value.pendingConfirmation
            ?.takeIf { it.requiresCurrentScreenshotOcrConsent() }
            ?: return
        viewModel.rejectAgentConfirmationForMediaProjectionDenial(current)
    }

    private fun syncDeviceContextAuthorizationSnapshot() {
        viewModel.updateDeviceContextAuthorizationSnapshot(
            DeviceContextAuthorizationSnapshot(
                grantedRuntimePermissions = DEVICE_CONTEXT_RUNTIME_PERMISSIONS
                    .filter(::hasRuntimePermission)
                    .toSet(),
                grantedSpecialAccessIds = DEVICE_CONTEXT_SPECIAL_ACCESS_IDS
                    .filter(::hasSpecialAccess)
                    .toSet(),
            ),
        )
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
        const val EXTRA_DEBUG_SCREENSHOT_REMOTE_BASE_URL =
            "com.bytedance.zgx.pocketmind.extra.DEBUG_SCREENSHOT_REMOTE_BASE_URL"
        const val EXTRA_DEBUG_SCREENSHOT_REMOTE_MODEL_NAME =
            "com.bytedance.zgx.pocketmind.extra.DEBUG_SCREENSHOT_REMOTE_MODEL_NAME"
        const val EXTRA_DEBUG_SCREENSHOT_REMOTE_SUPPORTS_VISION_INPUT =
            "com.bytedance.zgx.pocketmind.extra.DEBUG_SCREENSHOT_REMOTE_SUPPORTS_VISION_INPUT"
        const val EXTRA_DEBUG_UI_FONT_SCALE =
            "com.bytedance.zgx.pocketmind.extra.DEBUG_UI_FONT_SCALE"
        private const val KEY_PENDING_SPECIAL_ACCESS_REQUIREMENT_ID =
            "com.bytedance.zgx.pocketmind.state.PENDING_SPECIAL_ACCESS_REQUIREMENT_ID"
        private val SHARED_ATTACHMENT_MIME_TYPES = arrayOf(
            "text/*",
            "image/*",
            "audio/*",
            "video/*",
            "application/json",
            "application/xml",
            "application/yaml",
            "application/x-yaml",
            "application/pdf",
            "application/rtf",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        )
        private val DEVICE_CONTEXT_RUNTIME_PERMISSIONS = buildList {
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        }
        private val DEVICE_CONTEXT_SPECIAL_ACCESS_IDS = listOf(
            SPECIAL_ACCESS_USAGE_STATS,
            SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT,
            SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL,
        )

        private fun isRunningUnderAndroidTest(): Boolean =
            runCatching {
                val registry = Class.forName("androidx.test.platform.app.InstrumentationRegistry")
                registry.getMethod("getInstrumentation").invoke(null) != null
            }.getOrDefault(false)
    }
}

internal fun sharedInputReadModeFor(
    inferenceMode: InferenceMode,
    localSupportsVisionInput: Boolean = false,
    remoteConfigured: Boolean,
    remoteSupportsVisionInput: Boolean,
): SharedInputReadMode =
    when {
        inferenceMode != InferenceMode.Remote && localSupportsVisionInput -> SharedInputReadMode.LocalVision
        inferenceMode != InferenceMode.Remote -> SharedInputReadMode.LocalPrompt
        remoteConfigured && remoteSupportsVisionInput -> SharedInputReadMode.RemoteVision
        else -> SharedInputReadMode.RemoteVisionUnsupportedSignal
    }

private fun Bundle.recognizedSpeechText(): String? =
    getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun Int.voiceInputErrorMessage(): String =
    when (this) {
        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到声音"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "未授权麦克风权限"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别服务忙碌"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> "语音识别网络不可用"

        else -> "语音输入不可用"
    }
