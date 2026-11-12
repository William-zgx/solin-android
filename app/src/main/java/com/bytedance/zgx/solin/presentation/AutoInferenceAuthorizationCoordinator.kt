package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AdaptiveInferenceRollout
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.data.RemoteModelStore

internal class AutoInferenceAuthorizationCoordinator(
    private val rollout: AdaptiveInferenceRollout,
    private val remoteModelStore: RemoteModelStore
) {
    private val lock = Any()
    private var pendingRemoteProfileRevision: String? = null

    val hasPending: Boolean
        get() = synchronized(lock) { pendingRemoteProfileRevision != null }

    fun request(): Boolean = synchronized(lock) {
        if (!rollout.autoSelectable) return@synchronized false
        pendingRemoteProfileRevision = remoteModelStore.loadConfig().profileRevision.trim()
        true
    }

    fun confirm(): Boolean = synchronized(lock) {
        val requestedRevision = pendingRemoteProfileRevision.also { pendingRemoteProfileRevision = null } ?: return false
        val current = remoteModelStore.loadConfig().normalized()
        if (
            requestedRevision.isBlank() ||
            current.profileRevision != requestedRevision ||
            !current.isConfigured
        ) {
            return false
        }
        remoteModelStore.saveMode(InferenceMode.Auto)
        return true
    }

    fun cancel() {
        synchronized(lock) { pendingRemoteProfileRevision = null }
    }

    fun <T> serialized(block: () -> T): T = synchronized(lock) { block() }

    fun <T> mutateRemoteConfig(
        block: () -> T,
        project: (T) -> Unit
    ): T = synchronized(lock) {
        pendingRemoteProfileRevision = null
        val previousRevision = remoteModelStore.loadConfig().profileRevision.trim()
        val result = try {
            block()
        } finally {
            val currentRevision = remoteModelStore.loadConfig().profileRevision.trim()
            if (
                currentRevision != previousRevision &&
                remoteModelStore.loadMode() == InferenceMode.Auto
            ) {
                remoteModelStore.saveMode(InferenceMode.Local)
            }
        }
        project(result)
        result
    }
}
