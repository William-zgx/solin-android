package com.bytedance.zgx.solin

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bytedance.zgx.solin.multimodal.SharedInputReadMode
import com.bytedance.zgx.solin.multimodal.ShareIntentReader
import com.bytedance.zgx.solin.ui.SolinScreen
import com.bytedance.zgx.solin.ui.theme.SolinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val appContainer: SolinAppContainer by lazy {
        requireNotNull(
            (application as SolinApplication).readyAppContainer(
                skipLocalModelRuntime = shouldSkipStartupModelRuntimeWork(),
            ),
        ) { "Solin app container is not ready" }
    }
    private val viewModel: SolinViewModel by viewModels {
        appContainer.viewModelFactory(
            skipStartupModelRuntimeWork = shouldSkipStartupModelRuntimeWork(),
        )
    }
    private var contentInitialization by mutableStateOf<SolinAppContainerInitialization>(
        SolinAppContainerInitialization.Loading,
    )
    private var readyContentInitialized = false
    private val voiceRecognition = VoiceRecognitionCoordinator(
        activity = this,
        viewModel = { viewModel },
        isViewModelReady = { readyContentInitialized },
    )
    private val confirmationPermissions = ConfirmationPermissionCoordinator(
        activity = this,
        viewModel = { viewModel },
        appContainer = { appContainer },
    )
    private val debugIntents = DebugIntentHandler(context = this, viewModel = { viewModel })
    private val sharedAttachmentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        handlePickedSharedUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val skipStartupModelRuntimeWork = shouldSkipStartupModelRuntimeWork()
        val containerState = (application as SolinApplication).appContainerState(
            skipLocalModelRuntime = skipStartupModelRuntimeWork,
        )
        setContent {
            SolinTheme {
                when (val initialization = contentInitialization) {
                    is SolinAppContainerInitialization.Ready -> ReadySolinContent(
                        skipStartupModelRuntimeWork = skipStartupModelRuntimeWork,
                    )
                    SolinAppContainerInitialization.Loading,
                    is SolinAppContainerInitialization.Failed ->
                        ContainerInitializationContent(initialization)
                }
            }
        }
        lifecycleScope.launch {
            when (
                val resolved = containerState.first { state ->
                    state !is SolinAppContainerInitialization.Loading
                }
            ) {
                is SolinAppContainerInitialization.Ready -> initializeReadyContent(
                    savedInstanceState = savedInstanceState,
                    skipStartupModelRuntimeWork = skipStartupModelRuntimeWork,
                )
                is SolinAppContainerInitialization.Failed -> contentInitialization = resolved
                SolinAppContainerInitialization.Loading -> Unit
            }
        }
    }

    private fun initializeReadyContent(
        savedInstanceState: Bundle?,
        skipStartupModelRuntimeWork: Boolean,
    ) {
        if (readyContentInitialized) return
        readyContentInitialized = true
        val useDarkSystemBars =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        enableEdgeToEdge(
            statusBarStyle = if (useDarkSystemBars) {
                SystemBarStyle.dark(scrim = Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(
                    scrim = Color.TRANSPARENT,
                    darkScrim = Color.TRANSPARENT,
                )
            },
            navigationBarStyle = if (useDarkSystemBars) {
                SystemBarStyle.dark(scrim = Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(
                    scrim = Color.TRANSPARENT,
                    darkScrim = Color.TRANSPARENT,
                )
            },
        )
        viewModel.restoreStartupState(
            skipModelRuntimeWork = skipStartupModelRuntimeWork,
        )
        // onResume skips the permission sync while the container is still loading, so a cold
        // start would otherwise report nothing granted until the next resume.
        confirmationPermissions.syncDeviceContextAuthorizationSnapshot()
        contentInitialization = SolinAppContainerInitialization.Ready(appContainer)
        lifecycleScope.launch {
            runCatching { viewModel.awaitPersistenceInitialization() }
                .onSuccess {
                    withContext(Dispatchers.IO) {
                        debugIntents.configureRemoteModelForScreenshotEvidenceIfPresent(intent)
                    }
                    confirmationPermissions.restorePendingSpecialAccessRequirement(savedInstanceState)
                    handleSharedIntent(intent, allowPreviouslyConsumed = false)
                    debugIntents.dispatchPromptIfPresent(
                        intent = intent,
                        restoredFromSavedState = savedInstanceState != null,
                    )
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.refreshRemoteModelConnectivityIfStale()
                        awaitCancellation()
                    }
                }
        }
    }

    @Composable
    private fun ReadySolinContent(skipStartupModelRuntimeWork: Boolean) {
        val context = LocalContext.current
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        SolinScreen(
                    state = state,
                    onImportModel = { uri -> viewModel.launchPersistenceWork { viewModel.importModel(uri) } },
                    onDownloadModel = { viewModel.launchPersistenceWork(viewModel::startModelDownload) },
                    onDownloadRecommendedModel = { modelId ->
                        viewModel.launchPersistenceWork { viewModel.startRecommendedModelDownload(modelId) }
                    },
                    onDownloadCustomModel = { url ->
                        viewModel.launchPersistenceWork { viewModel.startCustomModelDownload(url) }
                    },
                    onSaveHuggingFaceAccessToken = { token ->
                        viewModel.launchPersistenceWork { viewModel.saveHuggingFaceAccessToken(token) }
                    },
                    onClearHuggingFaceAccessToken = {
                        viewModel.launchPersistenceWork(viewModel::clearHuggingFaceAccessToken)
                    },
                    onCancelDownload = { viewModel.launchPersistenceWork(viewModel::cancelModelDownload) },
                    onLoadModel = { viewModel.launchPersistenceWork(viewModel::loadModel) },
                    onRecommendedModelSelected = { modelId ->
                        viewModel.launchPersistenceWork { viewModel.selectRecommendedModel(modelId) }
                    },
                    onInstalledModelSelected = { modelId ->
                        viewModel.launchPersistenceWork { viewModel.selectInstalledModel(modelId) }
                    },
                    onDeleteInstalledModel = { modelId ->
                        viewModel.launchPersistenceWork { viewModel.deleteInstalledModel(modelId) }
                    },
                    // Both cross the trust boundary (mode switch closes the runtime and clears
                    // pending remote-send state; config save does Keystore encryption), so they
                    // are serialized with every other persistence action and kept off the main thread.
                    onInferenceModeSelected = { mode ->
                        viewModel.launchPersistenceWork { viewModel.selectInferenceMode(mode) }
                    },
                    onRemoteModelConfigChanged = { config ->
                        viewModel.launchPersistenceWork { viewModel.updateRemoteModelConfig(config) }
                    },
                    onTestRemoteModelConnectivity = {
                        viewModel.launchPersistenceWork(viewModel::testRemoteModelConnectivity)
                    },
                    onBackendSelected = { backend ->
                        viewModel.launchPersistenceWork { viewModel.selectBackend(backend) }
                    },
                    onGenerationParametersChanged = { parameters ->
                        viewModel.launchPersistenceWork { viewModel.updateGenerationParameters(parameters) }
                    },
                    onResetGenerationParameters = {
                        viewModel.launchPersistenceWork(viewModel::resetGenerationParameters)
                    },
                    onCreateSession = { viewModel.launchPersistenceWork(viewModel::createNewSession) },
                    onSessionSelected = { sessionId ->
                        viewModel.launchPersistenceWork { viewModel.selectSession(sessionId) }
                    },
                    onDeleteSession = { viewModel.launchPersistenceWork(viewModel::deleteActiveSession) },
                    onOpenModelPage = { url ->
                        if (!skipStartupModelRuntimeWork) {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                            )
                        }
                    },
                    onSetupModelToggled = { modelId, selected ->
                        viewModel.launchPersistenceWork { viewModel.toggleSetupModel(modelId, selected) }
                    },
                    onDownloadSetupModels = {
                        viewModel.launchPersistenceWork(viewModel::startSetupModelDownload)
                    },
                    onSkipFirstRunSetup = { viewModel.launchPersistenceWork(viewModel::skipFirstRunSetup) },
                    onMemoryEnabledChanged = { enabled ->
                        viewModel.launchPersistenceWork { viewModel.updateMemoryEnabled(enabled) }
                    },
                    onReduceDeviceActionConfirmationsChanged = { enabled ->
                        viewModel.launchPersistenceWork { viewModel.updateReduceDeviceActionConfirmations(enabled) }
                    },
                    onRemoteGuiAutomationEnabledChanged = { enabled ->
                        viewModel.launchPersistenceWork { viewModel.updateRemoteGuiAutomationEnabled(enabled) }
                    },
                    onForgetLongTermMemory = { memoryId ->
                        viewModel.launchPersistenceWork { viewModel.forgetLongTermMemory(memoryId) }
                    },
                    onClearLongTermMemory = {
                        viewModel.launchPersistenceWork(viewModel::clearLongTermMemory)
                    },
                    onRefreshBackgroundTasks = {
                        viewModel.launchPersistenceWork(viewModel::refreshBackgroundTasks)
                    },
                    onRefreshAuditEvents = { viewModel.launchPersistenceWork(viewModel::refreshAuditEvents) },
                    onCancelBackgroundTask = { taskId ->
                        viewModel.launchPersistenceWork { viewModel.cancelBackgroundTask(taskId) }
                    },
                    onSetPeriodicCheckPolicy = { request ->
                        viewModel.launchPersistenceWork { viewModel.setPeriodicCheckPolicy(request) }
                    },
                    onDisablePeriodicCheckPolicy = {
                        viewModel.launchPersistenceWork(viewModel::disablePeriodicCheckPolicy)
                    },
                    onOpenSpecialAccessSettings = confirmationPermissions::openSpecialAccessSettings,
                    resolveRuntimePermissionRequirements = { confirmation ->
                        confirmation.runtimePermissionRequirementsFor(
                            toolRegistry = appContainer.toolRegistry,
                        )
                    },
                    resolveSpecialAccessRequirements = { confirmation ->
                        confirmation.specialAccessRequirementsFor(
                            toolRegistry = appContainer.toolRegistry,
                        )
                    },
                    onConfirmAgentConfirmation = confirmationPermissions::confirmAgentConfirmationWithPermissions,
                    onDismissAgentConfirmation = { confirmation ->
                        viewModel.launchPersistenceWork { viewModel.dismissAgentConfirmation(confirmation) }
                    },
                    onRecordExternalOutcome = { pending, outcome ->
                        viewModel.launchPersistenceWork { viewModel.recordExternalOutcome(pending, outcome) }
                    },
                    onOpenRecoveryAction = { action ->
                        viewModel.launchPersistenceWork { viewModel.requestRecoveryActionConfirmation(action) }
                    },
                    onConfirmRemoteModeDisclosure = { disclosure ->
                        viewModel.launchPersistenceWork {
                            viewModel.confirmRemoteModeDisclosure(disclosure)
                        }
                    },
                    onDismissRemoteModeDisclosure = {
                        viewModel.launchPersistenceWork(viewModel::dismissRemoteModeDisclosure)
                    },
                    onConfirmRemoteSendDisclosure = { suppress ->
                        viewModel.launchPersistenceWork { viewModel.confirmRemoteSendDisclosure(suppress) }
                    },
                    onConfirmRemoteSendWithMasking = {
                        viewModel.launchPersistenceWork(viewModel::confirmRemoteSendWithMasking)
                    },
                    onConfirmRemoteSendDespiteSensitive = {
                        viewModel.launchPersistenceWork(viewModel::confirmRemoteSendDespiteSensitive)
                    },
                    onDismissRemoteSendDisclosure = {
                        viewModel.launchPersistenceWork(viewModel::dismissRemoteSendDisclosure)
                    },
                    onRemoteSendDisclosurePolicySelected = { policy ->
                        viewModel.launchPersistenceWork { viewModel.setRemoteSendDisclosurePolicy(policy) }
                    },
                    onSendMessage = { prompt -> viewModel.launchPersistenceWork { viewModel.sendMessage(prompt) } },
                    onSendPendingSharedInput = { instruction ->
                        viewModel.launchPersistenceWork { viewModel.sendPendingSharedInput(instruction) }
                    },
                    onClearPendingSharedInput = { draftId ->
                        viewModel.launchPersistenceWork { viewModel.clearPendingSharedInputDraft(draftId) }
                    },
                    onStartVoiceInput = voiceRecognition::start,
                    onCancelVoiceInput = voiceRecognition::cancel,
                    onFinishVoiceInput = voiceRecognition::finish,
                    onPickSharedAttachment = {
                        sharedAttachmentLauncher.launch(SHARED_ATTACHMENT_MIME_TYPES)
                    },
                    onVoiceInputConsumed = viewModel::consumeVoiceInputDraft,
                    onStopGeneration = { viewModel.launchPersistenceWork(viewModel::stopGeneration) },
                    resourceSampler = { appContainer.sampleSystemResources() },
                    activeRunPlacement = state.activeRunPlacement,
                    activeRunPlacementReason = state.activeRunPlacementReason,
        )
    }

    @Composable
    private fun ContainerInitializationContent(state: SolinAppContainerInitialization) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                SolinAppContainerInitialization.Loading -> CircularProgressIndicator()
                is SolinAppContainerInitialization.Failed ->
                    Text("初始化失败：${state.cause.message ?: state.cause.javaClass.simpleName}")
                is SolinAppContainerInitialization.Ready -> Unit
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!isAppContainerReady()) return
        lifecycleScope.launch {
            runCatching { viewModel.awaitPersistenceInitialization() }
                .onSuccess {
                    withContext(Dispatchers.IO) {
                        debugIntents.configureRemoteModelForScreenshotEvidenceIfPresent(intent)
                    }
                    handleSharedIntent(intent, allowPreviouslyConsumed = true)
                    debugIntents.dispatchPromptIfPresent(intent)
                }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isAppContainerReady()) return
        confirmationPermissions.syncDeviceContextAuthorizationSnapshot()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        confirmationPermissions.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun handleSharedIntent(intent: Intent?, allowPreviouslyConsumed: Boolean) {
        val sharedIntent = intent ?: return
        val shareKey = sharedIntent.shareIntentConsumptionKey() ?: return
        viewModel.enqueueSharedIntent(
            consumptionKey = shareKey,
            intent = sharedIntent,
            readMode = sharedInputReadMode(),
            reader = ShareIntentReader(applicationContext),
            allowPreviouslyConsumed = allowPreviouslyConsumed,
        )
    }

    private fun shouldSkipStartupModelRuntimeWork(): Boolean =
        intent.getBooleanExtra(EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, false) ||
            isRunningUnderAndroidTest()

    private fun isAppContainerReady(): Boolean =
        (application as SolinApplication).readyAppContainer(
            skipLocalModelRuntime = shouldSkipStartupModelRuntimeWork(),
        ) != null

    private fun handlePickedSharedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val readMode = sharedInputReadMode()
            val sharedInput = withContext(Dispatchers.IO) {
                ShareIntentReader(applicationContext).readUris(uris, mode = readMode)
            }
            sharedInput?.let { input ->
                viewModel.launchPersistenceWork { viewModel.stageSharedInput(input) }
            }
        }
    }

    private fun sharedInputReadMode(): SharedInputReadMode =
        sharedInputReadModeFor(
            inferenceMode = viewModel.uiState.value.inferenceMode,
            localSupportsVisionInput = viewModel.uiState.value.activeLocalModelSupportsVisionInput,
        )

    companion object {
        const val EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK =
            "com.bytedance.zgx.solin.extra.SKIP_STARTUP_MODEL_RUNTIME_WORK"
        // Debug-only extras, handled by DebugIntentHandler. Release builds ignore them.
        const val EXTRA_DEBUG_PROMPT =
            "com.bytedance.zgx.solin.extra.DEBUG_PROMPT"
        const val EXTRA_DEBUG_SCREENSHOT_REMOTE_BASE_URL =
            "com.bytedance.zgx.solin.extra.DEBUG_SCREENSHOT_REMOTE_BASE_URL"
        const val EXTRA_DEBUG_SCREENSHOT_REMOTE_MODEL_NAME =
            "com.bytedance.zgx.solin.extra.DEBUG_SCREENSHOT_REMOTE_MODEL_NAME"
        const val EXTRA_DEBUG_SCREENSHOT_REMOTE_SUPPORTS_VISION_INPUT =
            "com.bytedance.zgx.solin.extra.DEBUG_SCREENSHOT_REMOTE_SUPPORTS_VISION_INPUT"
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

        private fun isRunningUnderAndroidTest(): Boolean =
            runCatching {
                val registry = Class.forName("androidx.test.platform.app.InstrumentationRegistry")
                registry.getMethod("getInstrumentation").invoke(null) != null
            }.getOrDefault(false)
    }
}

internal fun sharedInputReadModeFor(
    @Suppress("UNUSED_PARAMETER")
    inferenceMode: InferenceMode,
    @Suppress("UNUSED_PARAMETER")
    localSupportsVisionInput: Boolean = false,
): SharedInputReadMode = SharedInputReadMode.DestinationNeutralVision

internal fun Intent.shareIntentConsumptionKey(): String? {
    val actionValue = action?.takeIf {
        it == Intent.ACTION_SEND || it == Intent.ACTION_SEND_MULTIPLE
    } ?: return null
    val streamHashes = sharedStreamUrisForConsumptionKey()
        .map { uri -> uri.toString().hashCode().toString() }
        .sorted()
    val textHash = getCharSequenceExtra(Intent.EXTRA_TEXT)
        ?.toString()
        ?.hashCode()
        ?.toString()
        .orEmpty()
    val subjectHash = getCharSequenceExtra(Intent.EXTRA_SUBJECT)
        ?.toString()
        ?.hashCode()
        ?.toString()
        .orEmpty()
    val clipHashes = buildList {
        val clip = clipData ?: return@buildList
        repeat(clip.itemCount) { index ->
            clip.getItemAt(index).uri?.let { uri ->
                add(uri.toString().hashCode().toString())
            }
        }
    }.sorted()
    return listOf(
        actionValue,
        type.orEmpty(),
        dataString.orEmpty().hashCode().toString(),
        textHash,
        subjectHash,
        streamHashes.joinToString(separator = ","),
        clipHashes.joinToString(separator = ","),
    ).joinToString(separator = "|")
}

@Suppress("DEPRECATION")
private fun Intent.sharedStreamUrisForConsumptionKey(): List<Uri> =
    when (action) {
        Intent.ACTION_SEND -> listOfNotNull(getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        Intent.ACTION_SEND_MULTIPLE -> getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }
