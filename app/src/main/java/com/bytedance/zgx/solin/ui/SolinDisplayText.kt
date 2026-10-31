package com.bytedance.zgx.solin.ui

import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.LocalModelTokenLimits
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.RunDataReceiptUiSummary
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.RunPlacement
import java.util.Locale

internal fun runDataReceiptDisplayText(receipt: RunDataReceiptUiSummary): String {
    val destination = when (receipt.destination) {
        "Remote" -> "远端"
        "Local" -> "本机"
        else -> receipt.destination.ifBlank { "未知" }
    }
    val protectedTypes = receipt.protectedContentTypes
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "、")
        ?: "无额外保护项"
    val deletableRecords = receipt.deletableRecordTypes
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "、")
        ?: "无"
    val rawPersisted = if (receipt.rawContentPersisted) "是" else "否"
    val memoryUsage = if (receipt.memoryContextIncluded) "已使用" else "未带出"
    val memoryRecall = if (receipt.memoryHitCount > 0) {
        "Semantic ${receipt.semanticMemoryHitCount} / Lexical ${receipt.lexicalMemoryHitCount}"
    } else {
        "未使用"
    }
    val outputQuality = if (receipt.outputQualityGuardTriggered) {
        val issue = receipt.outputQualityIssue ?: "已触发"
        val rule = receipt.outputQualityRule?.let { value -> "/$value" }.orEmpty()
        val action = receipt.outputQualityAction?.let { value -> "，动作=$value" }.orEmpty()
        val prefix = if (receipt.outputQualityKeptPrefix) "，已保留前缀" else ""
        "触发 $issue$rule$action$prefix"
    } else {
        "未触发"
    }
    val deviceUsage = if (receipt.deviceContextIncluded) "已使用" else "未带出"
    val evidenceSources = receipt.evidenceSourceTypes
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "/")
        ?: "无"
    val evidenceUsage = if (receipt.evidenceCardCount > 0) {
        "${receipt.evidenceCardCount} 条（仅本机 ${receipt.localOnlyEvidenceCardCount}，截断 ${receipt.truncatedEvidenceCardCount}，低质量 ${receipt.lowQualityEvidenceCardCount}，来源 $evidenceSources）"
    } else {
        "未使用"
    }
    return "去向：$destination；隐私：${receipt.currentPromptPrivacy.userFacingPrivacyLabel()}；远端历史：${receipt.remoteHistoryCount}；" +
        "过滤仅本机历史：${receipt.localOnlyHistoryFilteredCount}；记忆：$memoryUsage/${receipt.memoryHitCount}；" +
        "召回：$memoryRecall；设备上下文：$deviceUsage；图片：${receipt.imageAttachmentCount}；受保护源：${receipt.protectedSourceCount}；" +
        "证据：$evidenceUsage；输出保护：$outputQuality；保护：${protectedTypes.userFacingPrivacyLabel()}；可删除：$deletableRecords；原文持久化：$rawPersisted"
}

private fun String.userFacingPrivacyLabel(): String =
    replace("RemoteEligible 对话上下文", "可远程发送的对话上下文")
        .replace("LocalOnly 历史", "仅本机历史")
        .replace("RemoteEligible", "可远程发送")
        .replace("LocalOnly", "仅本机")

internal fun inferencePreferenceDisplayText(preference: InferenceMode): String =
    "偏好：${preference.label()}"

internal fun activePlacementDisplayText(
    placement: RunPlacement?,
    reason: PlacementReasonCode?,
): String? = when (placement) {
    RunPlacement.Local -> when (reason) {
        PlacementReasonCode.AUTO_DECISION_MODEL_LOCAL -> "本次使用本地模型：云端路由判断本机即可回答。"
        PlacementReasonCode.USER_FORCED_LOCAL -> "本次使用本地模型：你选择了本地偏好。"
        PlacementReasonCode.PRIVACY_REQUIRES_LOCAL -> "本次使用本地模型：内容仅限本地处理。"
        PlacementReasonCode.REMOTE_NOT_AUTHORIZED -> "本次使用本地模型：自动模式尚未获得远程授权。"
        PlacementReasonCode.REMOTE_NOT_CONFIGURED -> "本次使用本地模型：远程模型尚未配置。"
        PlacementReasonCode.REMOTE_CONNECTIVITY_UNAVAILABLE -> "本次使用本地模型：远程连接当前不可用。"
        PlacementReasonCode.REMOTE_STATUS_STALE -> "本次使用本地模型：远程连接状态需要重新验证。"
        PlacementReasonCode.REMOTE_CAPABILITY_MISMATCH -> "本次使用本地模型：远程模型能力不满足本次请求。"
        PlacementReasonCode.REMOTE_OVERLOADED -> "本次使用本地模型：远程模型当前繁忙。"
        PlacementReasonCode.AUTO_SIMPLE_LOCAL -> "本次使用本地模型：任务较轻，手机状态正常。"
        PlacementReasonCode.AUTO_IMAGE_LOCAL -> "本次使用本地模型：图片可在本机处理，避免发送到远程。"
        PlacementReasonCode.PLACEMENT_LOCAL_CONTINUATION_REQUIRED ->
            "本次使用本地模型：后续内容需要留在本机。"
        PlacementReasonCode.MODEL_EXECUTION_FAILED -> "本地模型执行失败。"
        else -> "本次使用本地模型。"
    }

    RunPlacement.Remote -> when (reason) {
        PlacementReasonCode.USER_FORCED_REMOTE -> "本次使用远程模型：你选择了远程偏好。"
        PlacementReasonCode.LOCAL_MODEL_UNAVAILABLE -> "本次使用远程模型：本地模型当前不可用。"
        PlacementReasonCode.LOCAL_RESOURCE_BLOCKED -> "本次使用远程模型：手机资源暂不适合本地运行。"
        PlacementReasonCode.LOCAL_CAPABILITY_MISMATCH -> "本次使用远程模型：本地模型能力不满足本次请求。"
        PlacementReasonCode.AUTO_COMPLEX_REMOTE -> "本次使用远程模型：任务较复杂，且远程连接已验证。"
        PlacementReasonCode.AUTO_RESOURCE_REMOTE -> "本次使用远程模型：手机资源压力较高，且远程连接已验证。"
        PlacementReasonCode.AUTO_DECISION_MODEL_REMOTE -> "本次使用远程模型：云端路由判断这条请求需要大模型。"
        PlacementReasonCode.MODEL_EXECUTION_FAILED -> "远程模型执行失败。"
        else -> "本次使用远程模型。"
    }

    null -> when (reason) {
        null -> null
        PlacementReasonCode.PRIVACY_REQUIRES_LOCAL ->
            "无法执行：内容只能在本地处理，但本地模型当前不可用。"
        PlacementReasonCode.LOCAL_MODEL_UNAVAILABLE -> "无法执行：本地模型当前不可用。"
        PlacementReasonCode.LOCAL_RESOURCE_BLOCKED -> "无法执行：手机资源暂不适合本地运行。"
        PlacementReasonCode.LOCAL_CAPABILITY_MISMATCH -> "无法执行：本地模型能力不满足本次请求。"
        PlacementReasonCode.REMOTE_NOT_AUTHORIZED -> "无法执行：尚未获得远程模型授权。"
        PlacementReasonCode.REMOTE_NOT_CONFIGURED -> "无法执行：远程模型尚未配置。"
        PlacementReasonCode.REMOTE_CONNECTIVITY_UNAVAILABLE -> "无法执行：远程连接当前不可用。"
        PlacementReasonCode.REMOTE_STATUS_STALE -> "无法执行：远程连接状态需要重新验证。"
        PlacementReasonCode.REMOTE_CAPABILITY_MISMATCH -> "无法执行：远程模型能力不满足本次请求。"
        PlacementReasonCode.REMOTE_OVERLOADED -> "无法执行：远程模型当前繁忙。"
        PlacementReasonCode.NO_ELIGIBLE_TARGET ->
            "无法执行：没有同时满足隐私、能力和可用性要求的模型。"
        PlacementReasonCode.PLACEMENT_DECISION_MISSING -> "无法执行：本次运行位置尚未确定。"
        PlacementReasonCode.PLACEMENT_NOT_RESTORABLE -> "无法继续：本次运行位置无法安全恢复。"
        PlacementReasonCode.PLACEMENT_LOCAL_CONTINUATION_REQUIRED ->
            "无法继续远程生成：后续内容需要留在本机。"
        PlacementReasonCode.MODEL_EXECUTION_FAILED -> "模型执行失败。"
        else -> "无法执行：没有可用的运行位置。"
    }
}

internal fun ChatUiState.downloadStatusLabel(): String =
    downloadProgressPercent?.let { "下载 $it%" } ?: "下载中"

internal fun currentModelStatus(state: ChatUiState): String {
    val ready = if (state.inferenceMode == InferenceMode.Remote) {
        when {
            state.isBusy -> state.statusText
            state.isReady -> "已就绪"
            else -> state.statusText
        }
    } else {
        when {
            state.isDownloading -> state.downloadStatusLabel()
            state.isBusy -> state.statusText
            state.isReady -> "已就绪"
            state.modelPath != null -> "待加载"
            else -> state.statusText
        }
    }
    return when (state.inferenceMode) {
        InferenceMode.Local -> {
            val modelName = state.installedModels
                .firstOrNull { it.id == state.activeInstalledModelId }
                ?.displayName
                ?: state.selectedRecommendedModel.shortName
            "${inferencePreferenceDisplayText(state.inferenceMode)} · 本地候选：$modelName · " +
                "${state.backend.name} · ${LocalModelTokenLimits.compactDisplayText(state.localMaxTotalTokens)} · $ready"
        }

        InferenceMode.Auto ->
            "${inferencePreferenceDisplayText(state.inferenceMode)} · 本地与远程候选按请求评估 · $ready"

        InferenceMode.Remote -> {
            val modelName = state.remoteModelConfig.modelName.ifBlank { "远程模型" }
            "${inferencePreferenceDisplayText(state.inferenceMode)} · 远程候选：$modelName · $ready"
        }
    }
}

internal fun modelHealthDisplayText(state: ChatUiState): String {
    val health = state.modelHealth
    val parts = mutableListOf("健康：${health.state.label()}")
    health.backend?.let { backend -> parts += "backend=${backend.label()}" }
    health.fallbackBackend?.let { fallback -> parts += "fallback=${fallback.label()}" }
    health.loadMs?.let { loadMs -> parts += "load=${loadMs}ms" }
    health.firstTokenMs?.let { firstTokenMs -> parts += "first=${firstTokenMs}ms" }
    health.tokenCount?.let { tokens -> parts += "tokens=$tokens" }
    health.tokensPerSecond?.takeIf { it > 0.0 }?.let { value ->
        parts += "speed=${String.format(Locale.US, "%.1f", value)} tok/s"
    }
    health.failureReason
        ?.takeIf { it.isNotBlank() }
        ?.let { reason -> parts += "reason=${reason.take(80)}" }
    health.lastOutputQualityIssue
        ?.takeIf { it.isNotBlank() }
        ?.let { issue ->
            val rule = health.lastOutputQualityRule
                ?.takeIf { value -> value.isNotBlank() }
                ?.let { value -> "/$value" }
                .orEmpty()
            parts += "quality=$issue$rule"
        }
    return parts.joinToString(separator = " · ")
}

internal fun ModelHealthState.label(): String =
    when (this) {
        ModelHealthState.NotInstalled -> "未安装"
        ModelHealthState.InstalledUnverified -> "待校验"
        ModelHealthState.Verified -> "已校验"
        ModelHealthState.Loading -> "加载中"
        ModelHealthState.Loaded -> "已加载"
        ModelHealthState.LoadFailed -> "加载失败"
        ModelHealthState.FallbackActive -> "Fallback"
    }

internal fun capabilityLabel(capability: ModelCapability): String =
    when (capability) {
        ModelCapability.Chat -> "对话"
        ModelCapability.MemoryEmbedding -> "记忆"
        ModelCapability.MobileAction -> "动作"
    }

internal fun ChatUiState.pendingSelectedChatDownloadBytes(): Long =
    if (isModelInstalled(selectedRecommendedModel.id)) {
        0L
    } else {
        selectedRecommendedModel.byteSize
    }
