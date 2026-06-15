package com.bytedance.zgx.pocketmind.evidence

import com.bytedance.zgx.pocketmind.MessagePrivacy

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
