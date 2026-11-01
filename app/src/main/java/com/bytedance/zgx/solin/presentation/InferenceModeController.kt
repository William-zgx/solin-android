package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.ModelHealth
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.PendingRemoteModeDisclosure
import com.bytedance.zgx.solin.data.RemoteModelStore
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Inference-mode switching and the remote-mode disclosure it may raise. Switching mode is a
 * trust-boundary change: it clears pending remote-send state
 * and resets any session-scoped "don't ask again" before the new destination is used. Callers
 * must run these on the serialized persistence queue (see `MainActivity`).
 */
internal class InferenceModeController(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val runtime: LiteRtRuntime,
    private val remoteModelRepository: RemoteModelStore,
    private val autoInferenceAuthorizationCoordinator: AutoInferenceAuthorizationCoordinator,
    private val chatController: ChatController,
    private val modelLoadController: ModelLoadController,
    private val requireRemoteSendDisclosure: Boolean,
) {
    /**
     * A Remote selection whose disclosure is still open. The switch is not persisted and the local
     * runtime is untouched until the user confirms; dismissing restores the previous mode.
     */
    private var pendingRemoteSwitch: PendingRemoteSwitch? = null

    fun selectInferenceMode(mode: InferenceMode) {
        // Still blocked during a download: its completion auto-loads the local model, which would
        // silently override a mode chosen mid-download.
        if (uiState.value.isBusy || uiState.value.isDownloading) return
        if (mode == InferenceMode.Auto) {
            pendingRemoteSwitch = null
            if (!autoInferenceAuthorizationCoordinator.request()) {
                uiState.update {
                    it.copy(pendingRemoteModeDisclosure = null, statusText = "自动模式当前未开放")
                }
                return
            }
            chatController.clearPendingRemoteChatState()
            chatController.resetRemoteSendDisclosureSuppression()
            val disclosure = modelLoadController.remoteModeDisclosure()
            uiState.update {
                it.copy(
                    pendingSharedInputDraft = null,
                    pendingRemoteModeDisclosure = disclosure,
                    pendingRemoteSendDisclosure = null,
                    statusText = "自动模式待确认",
                )
            }
            return
        }

        val cancelledAutoSelection = autoInferenceAuthorizationCoordinator.hasPending
        autoInferenceAuthorizationCoordinator.cancel()
        if (cancelledAutoSelection) {
            uiState.update { it.copy(pendingRemoteModeDisclosure = null) }
        }
        if (uiState.value.inferenceMode == mode) {
            // Selecting the mode already in effect withdraws any staged switch to the other one.
            if (pendingRemoteSwitch != null) {
                pendingRemoteSwitch = null
                uiState.update { it.copy(pendingRemoteModeDisclosure = null) }
            }
            return
        }
        // Trust boundary changed (inference mode switch): a prior session-scoped
        // "don't ask again" must not silently carry into the new destination.
        chatController.clearPendingRemoteChatState()
        chatController.resetRemoteSendDisclosureSuppression()
        if (mode == InferenceMode.Remote && requireRemoteSendDisclosure) {
            // The remote destination is only used after the disclosure is confirmed. Keeping the
            // persisted mode and the local runtime untouched means a dismissal is a plain revert.
            val openDisclosure = uiState.value.pendingRemoteModeDisclosure
            val staged = pendingRemoteSwitch
            val previousStatusText = staged
                ?.takeIf { openDisclosure != null && it.disclosure == openDisclosure }
                ?.previousStatusText
                ?: uiState.value.statusText
            val disclosure = modelLoadController.remoteModeDisclosure()
            pendingRemoteSwitch = PendingRemoteSwitch(
                disclosure = disclosure,
                previousStatusText = previousStatusText,
            )
            uiState.update {
                it.copy(
                    pendingRemoteModeDisclosure = disclosure,
                    pendingRemoteSendDisclosure = null,
                    statusText = REMOTE_MODE_PENDING_STATUS,
                )
            }
            return
        }
        pendingRemoteSwitch = null
        remoteModelRepository.saveMode(mode)
        if (mode == InferenceMode.Remote) {
            applyRemoteSwitch()
            return
        }

        uiState.update {
            it.copy(
                inferenceMode = InferenceMode.Local,
                pendingSharedInputDraft = null,
                pendingRemoteModeDisclosure = null,
                pendingRemoteSendDisclosure = null,
                isReady = runtime.isLoaded,
                statusText = if (runtime.isLoaded) {
                    "就绪 · ${it.backend.label()}"
                } else if (it.modelPath != null) {
                    "已切换到本地模型，点击加载模型"
                } else {
                    "请先下载或导入本地模型"
                },
                modelHealth = ModelHealth(
                    profileId = it.activeModelProfileId(),
                    state = if (runtime.isLoaded) {
                        ModelHealthState.Loaded
                    } else if (it.modelPath != null) {
                        it.modelHealth.state.takeIf { state ->
                            state == ModelHealthState.Verified || state == ModelHealthState.InstalledUnverified
                        } ?: ModelHealthState.Verified
                    } else {
                        ModelHealthState.NotInstalled
                    },
                    backend = it.backend.takeIf { _ -> it.modelPath != null },
                ),
            )
        }
    }

    fun confirmRemoteModeDisclosure(disclosure: PendingRemoteModeDisclosure) {
        var refreshConnectivity = false
        autoInferenceAuthorizationCoordinator.serialized {
            if (uiState.value.pendingRemoteModeDisclosure !== disclosure) return@serialized
            val remoteSwitch = pendingRemoteSwitch
            if (remoteSwitch != null && remoteSwitch.disclosure == disclosure) {
                // Remote was only staged: persist it now that the user has seen the disclosure.
                remoteModelRepository.saveMode(InferenceMode.Remote)
                pendingRemoteSwitch = null
                applyRemoteSwitch()
                return@serialized
            }
            pendingRemoteSwitch = null
            if (!autoInferenceAuthorizationCoordinator.hasPending) {
                dismissRemoteModeDisclosure()
                return@serialized
            }
            val confirmed = autoInferenceAuthorizationCoordinator.confirm()
            val currentConfig = remoteModelRepository.loadConfig()
            uiState.update {
                it.copy(
                    inferenceMode = if (confirmed) InferenceMode.Auto else it.inferenceMode,
                    remoteModelConfig = currentConfig,
                    pendingRemoteModeDisclosure = null,
                    pendingRemoteSendDisclosure = null,
                    isReady = if (confirmed) runtime.isLoaded || currentConfig.isConfigured else it.isReady,
                    statusText = if (confirmed) {
                        "已启用自动模式"
                    } else {
                        "远程配置已变化，请重新选择自动模式"
                    },
                )
            }
            refreshConnectivity = confirmed
        }
        if (refreshConnectivity) modelLoadController.refreshRemoteConnectivityIfStale()
    }

    fun dismissRemoteModeDisclosure() {
        val cancelledAutoSelection = autoInferenceAuthorizationCoordinator.hasPending
        autoInferenceAuthorizationCoordinator.cancel()
        val remoteSwitch = pendingRemoteSwitch
        pendingRemoteSwitch = null
        uiState.update {
            if (it.pendingRemoteModeDisclosure == null) {
                it
            } else {
                it.copy(
                    pendingRemoteModeDisclosure = null,
                    statusText = when {
                        // A dismissed Remote selection never took effect: stay on the previous mode.
                        // Keep any status written since then; only our own prompt is replaced.
                        remoteSwitch != null && it.statusText == REMOTE_MODE_PENDING_STATUS ->
                            remoteSwitch.previousStatusText
                        cancelledAutoSelection && it.inferenceMode == InferenceMode.Local ->
                            if (runtime.isLoaded) "就绪 · ${it.backend.label()}" else it.statusText
                        cancelledAutoSelection -> it.statusText
                        it.inferenceMode == InferenceMode.Remote ->
                            if (it.remoteModelConfig.isConfigured) "就绪 · 远程" else "请配置远程模型"
                        else -> it.statusText
                    },
                )
            }
        }
    }

    /** Projects the already-persisted Remote mode into the UI state. */
    private fun applyRemoteSwitch() {
        runtime.close()
        uiState.update { it.copy(pendingSharedInputDraft = null) }
        modelLoadController.updateRemoteReadiness(prefix = "已切换到远程模型")
    }
}

private const val REMOTE_MODE_PENDING_STATUS = "远程模式待确认"

/** A Remote selection awaiting its disclosure confirmation; dismissing it reverts. */
private data class PendingRemoteSwitch(
    val disclosure: PendingRemoteModeDisclosure,
    val previousStatusText: String,
)
