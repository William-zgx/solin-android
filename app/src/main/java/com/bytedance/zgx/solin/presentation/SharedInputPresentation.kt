package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatImageAttachment
import com.bytedance.zgx.solin.LocalImageAttachment
import com.bytedance.zgx.solin.multimodal.SharedAttachment
import com.bytedance.zgx.solin.multimodal.SharedAttachmentKind
import com.bytedance.zgx.solin.multimodal.SharedInput

internal fun SharedInput.composerSummary(): String {
    if (protectedSourceCount > 0 && protectedImageSourceCount <= 0 && attachments.isEmpty() && text.isBlank()) {
        return "受保护分享 ${protectedSourceCount} 项"
    }
    val labels = buildList {
        if (protectedSourceCount > 0) add("受保护 ${protectedSourceCount} 项")
        if (protectedImageSourceCount > 0) add("受保护图片")
        if (text.isNotBlank()) add("文本")
        attachments.take(3).forEach { attachment ->
            add(attachment.composerSummaryLabel())
        }
    }
    val extraCount = attachments.size - 3
    return buildString {
        append(labels.joinToString(separator = "、").ifBlank { "附件" })
        if (extraCount > 0) append(" 等 ${extraCount + 3} 项")
    }
}

internal fun SharedAttachment.composerSummaryLabel(): String {
    val base = safeDisplayNameForPrompt() ?: kind.label
    return when {
        kind == SharedAttachmentKind.Image && (imageAttachment != null || localImageAttachment != null) -> "$base · 图片"
        kind == SharedAttachmentKind.Image && textPreview == null -> "$base · 不支持视觉"
        kind == SharedAttachmentKind.Image -> "$base · OCR"
        else -> base
    }
}

internal fun SharedInput.remoteImageAttachments(): List<ChatImageAttachment> =
    attachments.mapNotNull { attachment -> attachment.imageAttachment }

internal fun SharedInput.localImageAttachments(): List<LocalImageAttachment> =
    attachments.mapNotNull { attachment -> attachment.localImageAttachment }

internal fun SharedInput.hasRemoteImageAttachment(): Boolean =
    attachments.any { attachment -> attachment.imageAttachment != null }

internal fun SharedInput.hasLocalImageAttachment(): Boolean =
    attachments.any { attachment -> attachment.localImageAttachment != null }

internal fun SharedInput.hasProtectedImageSource(): Boolean =
    protectedImageSourceCount > 0

internal fun SharedInput.isRemoteVisionSendable(): Boolean =
    text.isBlank() &&
        attachments.isNotEmpty() &&
        attachments.all { attachment ->
            attachment.kind == SharedAttachmentKind.Image &&
                attachment.imageAttachment != null &&
                attachment.textPreview == null
        }
