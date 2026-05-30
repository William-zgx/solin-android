package com.bytedance.zgx.pocketmind.multimodal

import java.util.Locale

data class SharedInput(
    val text: String,
    val attachments: List<SharedAttachment>,
) {
    val isEmpty: Boolean
        get() = text.isBlank() && attachments.isEmpty()

    fun toPrompt(): String {
        val trimmedText = text.trim()
        val attachmentBlock = attachments
            .take(MAX_ATTACHMENTS_IN_PROMPT)
            .mapIndexed { index, attachment ->
                val name = attachment.safeDisplayNameForPrompt() ?: "未命名"
                val size = attachment.sizeBytes?.let { "$it bytes" } ?: "未知大小"
                "${index + 1}. ${attachment.kind.label} · $name · ${attachment.mimeType ?: "未知类型"} · $size"
            }
            .joinToString(separator = "\n")
        return buildString {
            if (trimmedText.isNotBlank()) {
                append(trimmedText)
            } else if (attachments.isNotEmpty()) {
                append("请根据我分享的附件信息进行处理。")
            }
            if (attachmentBlock.isNotBlank()) {
                append("\n\n")
                append("已分享附件（当前版本只读取元数据，未读取文件内容）：\n")
                append(attachmentBlock)
            }
        }.trim()
    }

    private companion object {
        const val MAX_ATTACHMENTS_IN_PROMPT = 5
    }
}

data class SharedAttachment(
    val kind: SharedAttachmentKind,
    val mimeType: String?,
    val displayName: String?,
    val sizeBytes: Long?,
) {
    fun safeDisplayNameForPrompt(): String? {
        val normalized = displayName
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.filterNot { it.isISOControl() }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return normalized.take(MAX_DISPLAY_NAME_CHARS)
    }

    private companion object {
        const val MAX_DISPLAY_NAME_CHARS = 80
    }
}

enum class SharedAttachmentKind(val label: String) {
    Image("图片"),
    Audio("音频"),
    Video("视频"),
    Document("文档"),
    Other("附件"),
}

fun sharedAttachmentKindFor(mimeType: String?): SharedAttachmentKind =
    when (val normalizedMimeType = mimeType?.trim()?.lowercase(Locale.ROOT)) {
        null -> SharedAttachmentKind.Other
        else -> when {
            normalizedMimeType.startsWith("image/") -> SharedAttachmentKind.Image
            normalizedMimeType.startsWith("audio/") -> SharedAttachmentKind.Audio
            normalizedMimeType.startsWith("video/") -> SharedAttachmentKind.Video
            normalizedMimeType.startsWith("text/") || normalizedMimeType in documentMimeTypes ->
                SharedAttachmentKind.Document

            else -> SharedAttachmentKind.Other
        }
    }

private val documentMimeTypes = setOf(
    "application/pdf",
    "application/msword",
    "application/rtf",
    "application/vnd.ms-excel",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
)
