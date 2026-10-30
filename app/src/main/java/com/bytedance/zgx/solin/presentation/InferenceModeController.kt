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
 * Inference-mode switching and the remote-mode disclosure it may raise. Moved verbatim from
 * `SolinViewModel`. Switching mode is a trust-boundary change: it clears pending remote-send state
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
    fun selectInferenceMode(mode: InferenceMode) {
        if (uiState.value.isBusy) return
        if (mode == InferenceMode.Auto) {
            if (!autoInferenceAuthorizationCoordinator.request()) {
                uiState.update { it.copy(statusText = "自动模式当前未开放") }
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
        if (uiState.value.inferenceMode == mode) return
        // Trust boundary changed (inference mode switch): a prior session-scoped
        // "don't ask again" must not silently carry into the new destination.
        chatController.clearPendingRemoteChatState()
        chatController.resetRemoteSendDisclosureSuppression()
        remoteModelRepository.saveMode(mode)
        if (mode == InferenceMode.Remote) {
            runtime.close()
            uiState.update { it.copy(pendingSharedInputDraft = null) }
            modelLoadController.updateRemoteReadiness(
                prefix = "已切换到远程模型",
                showModeDisclosure = requireRemoteSendDisclosure,
            )
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
        uiState.update {
            if (it.pendingRemoteModeDisclosure == null) {
                it
            } else {
                it.copy(
                    pendingRemoteModeDisclosure = null,
                    statusText = when {
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
}
