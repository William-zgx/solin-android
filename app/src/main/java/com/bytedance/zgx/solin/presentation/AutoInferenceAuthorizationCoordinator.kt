package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AdaptiveInferenceRollout
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.data.RemoteModelStore

private data class PendingAutoInferenceAuthorization(
    val requestedMode: InferenceMode = InferenceMode.Auto,
    val remoteProfileRevision: String,
)

internal class AutoInferenceAuthorizationCoordinator(
    private val rollout: AdaptiveInferenceRollout,
    private val remoteModelStore: RemoteModelStore,
) {
    private val lock = Any()
    private var pending: PendingAutoInferenceAuthorization? = null

    val hasPending: Boolean
        get() = synchronized(lock) { pending != null }

    fun request(): Boolean = synchronized(lock) {
        if (!rollout.autoSelectable) return@synchronized false
        pending = PendingAutoInferenceAuthorization(
            remoteProfileRevision = remoteModelStore.loadConfig().profileRevision.trim(),
        )
        true
    }

    fun confirm(): Boolean = synchronized(lock) {
        val requested = pending.also { pending = null } ?: return false
        val current = remoteModelStore.loadConfig().normalized()
        if (
            requested.requestedMode != InferenceMode.Auto ||
            requested.remoteProfileRevision.isBlank() ||
            current.profileRevision != requested.remoteProfileRevision ||
            !current.isConfigured
        ) {
            return false
        }
        remoteModelStore.saveMode(InferenceMode.Auto)
        return true
    }

    fun cancel() {
        synchronized(lock) { pending = null }
    }

    fun <T> serialized(block: () -> T): T = synchronized(lock) { block() }

    fun <T> mutateRemoteConfig(
        block: () -> T,
        project: (T) -> Unit,
    ): T = synchronized(lock) {
        pending = null
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
