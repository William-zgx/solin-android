package com.bytedance.zgx.pocketmind.evidence

import com.bytedance.zgx.pocketmind.MessagePrivacy

enum class VerificationStatus {
    Passed,
    Failed,
    Skipped,
}

enum class EvidenceSourceType {
    UserPrompt,
    Memory,
    DeviceContext,
    ToolResult,
    OcrText,
    FilePreview,
    ImageAttachment,
    PublicWeb,
    ProtectedSource,
}

enum class EvidenceQualityLevel {
    High,
    Medium,
    Low,
    Unknown,
}

data class EvidenceQuality(
    val level: EvidenceQualityLevel,
    val reasons: List<String> = emptyList(),
) {
    init {
        require(reasons.all { it.isNotBlank() }) { "Evidence quality reasons must not be blank" }
    }
}

data class EvidenceCard(
    val id: String,
    val sourceType: EvidenceSourceType,
    val privacy: MessagePrivacy,
    val requiresLocalModel: Boolean,
    val text: String,
    val quality: EvidenceQuality = EvidenceQuality(EvidenceQualityLevel.Unknown),
    val freshnessMillis: Long? = null,
    val truncated: Boolean = false,
    val tokenEstimate: Int = 0,
) {
    init {
        require(id.isNotBlank()) { "Evidence id must not be blank" }
        require(tokenEstimate >= 0) { "Evidence token estimate must be >= 0" }
        freshnessMillis?.let { require(it >= 0L) { "Evidence freshness must be >= 0" } }
        if (privacy == MessagePrivacy.LocalOnly) {
            require(requiresLocalModel) { "LocalOnly evidence must require local model synthesis" }
        }
    }
}

data class EvidenceReceiptSummary(
    val evidenceCardCount: Int,
    val localOnlyEvidenceCardCount: Int,
    val truncatedEvidenceCardCount: Int,
    val lowQualityEvidenceCardCount: Int,
    val sourceTypes: List<EvidenceSourceType>,
) {
    init {
        require(evidenceCardCount >= 0) { "evidenceCardCount must be >= 0" }
        require(localOnlyEvidenceCardCount >= 0) { "localOnlyEvidenceCardCount must be >= 0" }
        require(truncatedEvidenceCardCount >= 0) { "truncatedEvidenceCardCount must be >= 0" }
        require(lowQualityEvidenceCardCount >= 0) { "lowQualityEvidenceCardCount must be >= 0" }
    }
}

fun Iterable<EvidenceCard>.toEvidenceReceiptSummary(): EvidenceReceiptSummary {
    val cards = toList()
    return EvidenceReceiptSummary(
        evidenceCardCount = cards.size,
        localOnlyEvidenceCardCount = cards.count { it.privacy == MessagePrivacy.LocalOnly },
        truncatedEvidenceCardCount = cards.count { it.truncated },
        lowQualityEvidenceCardCount = cards.count { it.quality.level == EvidenceQualityLevel.Low },
        sourceTypes = cards.map { it.sourceType }.distinct(),
    )
}

data class DeviceVerificationArtifact(
    val id: String,
    val status: VerificationStatus,
    val serial: String,
    val apiLevel: Int,
    val abi: String,
    val testCount: Int,
    val failedTarget: String? = null,
    val reason: String? = null,
    val instrumentationOutputPath: String? = null,
    val logcatPath: String? = null,
    val artifactSha256: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Device verification artifact id must not be blank" }
        require(serial.isNotBlank()) { "Device verification serial must not be blank" }
        require(apiLevel > 0) { "Device verification API level must be > 0" }
        require(abi.isNotBlank()) { "Device verification ABI must not be blank" }
        require(testCount >= 0) { "Device verification test count must be >= 0" }
        if (status == VerificationStatus.Failed) {
            require(!failedTarget.isNullOrBlank()) { "Failed device verification artifacts must include failedTarget" }
            require(!reason.isNullOrBlank()) { "Failed device verification artifacts must include reason" }
        } else {
            require(failedTarget.isNullOrBlank() && reason.isNullOrBlank()) {
                "Passed or skipped device verification artifacts must not include failure metadata"
            }
        }
        instrumentationOutputPath?.let {
            require(it.isNotBlank()) { "Instrumentation output path must not be blank" }
        }
        logcatPath?.let {
            require(it.isNotBlank()) { "Logcat path must not be blank" }
        }
        artifactSha256?.let {
            require(SHA_256_HEX.matches(it)) { "Device verification artifact SHA-256 must be 64 hex characters" }
        }
    }
}

private val SHA_256_HEX = Regex("^[a-fA-F0-9]{64}$")
