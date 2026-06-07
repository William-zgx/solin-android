package com.bytedance.zgx.pocketmind.device

import com.bytedance.zgx.pocketmind.ModelCapability

data class DeviceContextSnapshot(
    val isArm64Supported: Boolean,
    val inferenceMode: String,
    val installedCapabilities: Set<ModelCapability>,
    val memoryEnabled: Boolean,
    val availableStorageBytes: Long,
    val activeSessionId: String?,
    val hasPendingConfirmation: Boolean,
    val toolReadiness: List<DeviceContextToolReadiness> = emptyList(),
) {
    fun toPromptContext(): String {
        val capabilities = installedCapabilities
            .map { it.name }
            .sorted()
            .joinToString()
            .ifBlank { "None" }
        val readinessText = toolReadiness
            .sortedBy { readiness -> readiness.toolName }
            .joinToString(separator = "\n") { readiness ->
                val details = buildList {
                    if (readiness.runtimePermissions.isNotEmpty()) {
                        add("permissions=${readiness.runtimePermissions.sorted().joinToString("|")}")
                    }
                    readiness.specialAccessId?.let { add("specialAccess=$it") }
                    if (readiness.reason.isNotBlank()) add("reason=${readiness.reason}")
                }.joinToString(separator = "; ")
                "- ${readiness.toolName}: ${readiness.state.name}" +
                    details.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            }
            .ifBlank { "- None declared" }
        val storageText = if (availableStorageBytes > 0L) {
            "${availableStorageBytes / BYTES_PER_MIB} MiB"
        } else {
            "Unknown"
        }
        return """
            - CPU/ABI supports local arm64 model: $isArm64Supported
            - Inference mode: $inferenceMode
            - Installed capabilities: $capabilities
            - Local memory enabled: $memoryEnabled
            - Approximate available model storage: $storageText
            - Active session exists: ${activeSessionId != null}
            - Tool confirmation pending: $hasPendingConfirmation
            - Device context tool readiness:
            $readinessText
        """.trimIndent()
    }

    private companion object {
        const val BYTES_PER_MIB = 1024L * 1024L
    }
}

data class DeviceContextToolReadiness(
    val toolName: String,
    val state: DeviceContextToolReadinessState,
    val reason: String,
    val runtimePermissions: List<String> = emptyList(),
    val specialAccessId: String? = null,
) {
    init {
        require(toolName.isNotBlank()) { "tool readiness toolName must not be blank" }
    }
}

data class DeviceContextAuthorizationSnapshot(
    val grantedRuntimePermissions: Set<String> = emptySet(),
    val grantedSpecialAccessIds: Set<String> = emptySet(),
) {
    fun hasRuntimePermission(permission: String): Boolean =
        permission in grantedRuntimePermissions

    fun hasSpecialAccess(id: String): Boolean =
        id in grantedSpecialAccessIds
}

enum class DeviceContextToolReadinessState {
    Available,
    RequiresRuntimePermission,
    RequiresSpecialAccess,
    RequiresForegroundConsent,
    Unavailable,
}
