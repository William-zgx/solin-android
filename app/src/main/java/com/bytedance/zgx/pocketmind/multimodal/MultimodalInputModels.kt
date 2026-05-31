package com.bytedance.zgx.pocketmind.multimodal

import java.io.ByteArrayOutputStream
import java.io.InputStream
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
                buildString {
                    append("${index + 1}. ${attachment.kind.label} · $name · ${attachment.mimeType ?: "未知类型"} · $size")
                    val safeTextPreview = attachment.textPreview
                        ?.takeIf { canReadTextPreviewFor(attachment.mimeType) }
                    safeTextPreview?.let { preview ->
                        append("\n   文本摘录")
                        if (preview.truncated) append("（已截断）")
                        append("：\n")
                        append(preview.text.lines().joinToString(separator = "\n") { line -> "   $line" })
                    }
                }
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
                append("已分享附件（当前版本默认只读取元数据；text/* 文档会读取受限文本摘录）：\n")
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
    val textPreview: SharedTextPreview? = null,
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

data class SharedTextPreview(
    val text: String,
    val truncated: Boolean,
)

enum class SharedAttachmentKind(val label: String) {
    Image("图片"),
    Audio("音频"),
    Video("视频"),
    Document("文档"),
    Other("附件"),
}

fun sharedAttachmentKindFor(mimeType: String?): SharedAttachmentKind =
    when (val normalizedMimeType = mimeType.normalizedMediaType()) {
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

fun canReadTextPreviewFor(mimeType: String?): Boolean =
    when (val normalizedMimeType = mimeType.normalizedMediaType()) {
        null -> false
        else -> normalizedMimeType.startsWith("text/")
    }

private fun String?.normalizedMediaType(): String? =
    this
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotBlank() }

object TextAttachmentPreviewReader {
    fun read(inputStream: InputStream): SharedTextPreview? {
        val (bytes, truncatedByBytes) = inputStream.readLimitedBytes(MAX_TEXT_PREVIEW_BYTES + 1)
        val previewBytes = if (bytes.size > MAX_TEXT_PREVIEW_BYTES) {
            bytes.copyOf(MAX_TEXT_PREVIEW_BYTES)
        } else {
            bytes
        }
        val normalized = String(previewBytes, Charsets.UTF_8)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { char -> !char.isISOControl() || char == '\n' || char == '\t' }
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        if (normalized.isBlank()) return null
        val text = normalized.take(MAX_TEXT_PREVIEW_CHARS).trim()
        if (text.isBlank()) return null
        return SharedTextPreview(
            text = text,
            truncated = truncatedByBytes || normalized.length > MAX_TEXT_PREVIEW_CHARS,
        )
    }

    private fun InputStream.readLimitedBytes(limit: Int): Pair<ByteArray, Boolean> {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0
        while (totalBytes < limit) {
            val bytesToRead = minOf(buffer.size, limit - totalBytes)
            val read = read(buffer, 0, bytesToRead)
            if (read == -1) break
            output.write(buffer, 0, read)
            totalBytes += read
        }
        return output.toByteArray() to (totalBytes >= limit)
    }

    private const val MAX_TEXT_PREVIEW_BYTES = 16 * 1024
    private const val MAX_TEXT_PREVIEW_CHARS = 4_000
}

private val documentMimeTypes = setOf(
    "application/pdf",
    "application/rtf",
    "application/msword",
    "application/vnd.ms-excel",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
)
