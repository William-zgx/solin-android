package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.LocalModelTokenLimits
import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.ModelHealth
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.data.ModelSelectionState
import com.bytedance.zgx.solin.data.ModelVerificationStatus
import com.bytedance.zgx.solin.runtime.LocalModelRuntimeCapabilities

internal fun ModelSelectionState.activeLocalCapabilityProfile(): ModelCapabilityProfile? =
    installedModels
        .firstOrNull { model -> model.id == activeInstalledModelId }
        ?.capabilityProfile

internal fun ModelSelectionState.localContextWindowTokens(): Int =
    activeLocalCapabilityProfile()?.contextWindowTokens
        ?: LocalModelTokenLimits.MAX_TOTAL_TOKENS

internal fun ModelSelectionState.localPreferredBackends(): Set<BackendChoice> =
    activeLocalCapabilityProfile()?.preferredLocalBackends.orEmpty()

internal fun ModelSelectionState.modelHealthForCurrentSelection(backend: BackendChoice): ModelHealth {
    val activeModel = installedModels.firstOrNull { model -> model.id == activeInstalledModelId }
    val profileId = activeModel?.capabilityProfile?.id
        ?: activeModel?.recommendedModelId
        ?: selectedModelId
    val healthState = when {
        activeModel == null && activeModelPath == null -> ModelHealthState.NotInstalled
        activeModel?.isUsable == true &&
            activeModel.verificationStatus == ModelVerificationStatus.VerifiedRecommended -> ModelHealthState.Verified
        activeModel?.recommendedModelId == null && activeModelPath != null -> ModelHealthState.InstalledUnverified
        activeModelPath != null -> ModelHealthState.InstalledUnverified
        else -> ModelHealthState.NotInstalled
    }
    return ModelHealth(
        profileId = profileId,
        state = healthState,
        backend = backend.takeIf { activeModelPath != null },
    )
}

internal fun ChatUiState.activeModelProfileId(): String =
    installedModels.firstOrNull { model -> model.id == activeInstalledModelId }?.let { model ->
        model.capabilityProfile?.id ?: model.recommendedModelId
    }
        ?: selectedModelId

internal fun localModelRuntimeCapabilitiesFor(state: ChatUiState): LocalModelRuntimeCapabilities =
    LocalModelRuntimeCapabilities.fromProfile(state.activeLocalCapabilityProfile)

internal fun backendAllowedForActiveModel(state: ChatUiState, backend: BackendChoice): Boolean =
    state.localPreferredBackends.isEmpty() || backend in state.localPreferredBackends

internal fun preferredBackendForActiveModel(state: ChatUiState, current: BackendChoice): BackendChoice =
    if (backendAllowedForActiveModel(state, current)) {
        current
    } else {
        state.localPreferredBackends.firstOrNull() ?: current
    }
