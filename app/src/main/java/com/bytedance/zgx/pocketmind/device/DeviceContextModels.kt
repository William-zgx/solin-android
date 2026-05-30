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
) {
    fun toPromptContext(): String {
        val capabilities = installedCapabilities
            .map { it.name }
            .sorted()
            .joinToString()
            .ifBlank { "None" }
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
        """.trimIndent()
    }

    private companion object {
        const val BYTES_PER_MIB = 1024L * 1024L
    }
}
