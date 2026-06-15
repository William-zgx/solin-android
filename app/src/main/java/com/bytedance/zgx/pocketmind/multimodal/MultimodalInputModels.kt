package com.bytedance.zgx.pocketmind.multimodal

import com.bytedance.zgx.pocketmind.ChatImageAttachment
import com.bytedance.zgx.pocketmind.LocalImageAttachment
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

private const val MAX_SHARED_TEXT_CHARS = 4_000

data class SharedInput(
    val text: String,
    val attachments: List<SharedAttachment>,
    val protectedSourceCount: Int = 0,
    val protectedImageSourceCount: Int = 0,
) {
    val isEmpty: Boolean
        get() = text.toBoundedSharedText().text.isBlank() &&
            attachments.isEmpty() &&
            protectedSourceCount <= 0 &&
            protectedImageSourceCount <= 0

    fun toPrompt(): String {
        val sharedText = text.toBoundedSharedText()
        val hasAttachedImages = attachments.any { attachment ->
            attachment.imageAttachment != null || attachment.localImageAttachment != null
        }
        val attachmentBlock = attachments
            .take(MAX_ATTACHMENTS_IN_PROMPT)
            .mapIndexed { index, attachment ->
                val name = attachment.safeDisplayNameForPrompt() ?: "未命名"
                val size = attachment.sizeBytes?.let { "$it bytes" } ?: "未知大小"
                buildString {
                    append("${index + 1}. ${attachment.kind.label} · $name · ${attachment.mimeType ?: "未知类型"} · $size")
                    val safeTextPreview = attachment.textPreview
                        ?.takeIf { canUseTextPreviewFor(attachment) }
                    safeTextPreview?.let { preview ->
                        append("\n   ${preview.source.label}")
                        if (preview.truncated) append("（已截断）")
                        preview.quality.noticeForPrompt()?.let { notice ->
                            append("（$notice）")
                        }
                        append("：\n")
                        append(preview.text.lines().joinToString(separator = "\n") { line -> "   $line" })
                    } ?: attachment.unavailablePreviewNotice()?.let { notice ->
                        append("\n   $notice")
                    }
                }
            }
            .joinToString(separator = "\n")
        return buildString {
            if (protectedImageSourceCount > 0) {
                append("已收到受保护图片。当前模型未启用视觉输入能力，本次未读取、OCR 或发送图片内容；请切换支持视觉的远程模型后重新选择图片。")
            }
            if (protectedSourceCount > 0) {
                if (isNotEmpty()) append("\n\n")
                if (hasAttachedImages) {
                    append("除已附加图片外，还收到受保护分享源；为保护隐私，本次未读取这些分享文本、非图片附件、文本摘录或 OCR。")
                } else {
                    append(
                        "已收到受保护分享。为保护隐私，本次未读取分享文本、附件元数据、文本摘录或 OCR；请切换到本地模型后重新分享，或手动粘贴你愿意处理的内容。",
                    )
                }
            }
            if (sharedText.text.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                if (sharedText.truncated) {
                    append("分享文本（已截断）：\n")
                }
                append(sharedText.text)
            } else if (attachments.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                if (hasAttachedImages) {
                    append("请根据我分享的图片和附件信息进行处理；图片已随本次请求提供给模型。如果当前模型不支持图片输入，请直接说明不支持。")
                } else {
                    append("请根据我分享的附件信息和可用文字摘录进行处理；如果包含图片，请明确说明当前模型不支持视觉输入，且不会自动 OCR。")
                }
            }
            if (attachmentBlock.isNotBlank()) {
                append("\n\n")
                if (hasAttachedImages) {
                    append(
                        "已分享附件（图片会随本次模型请求提供；分享文本、非图片附件、文本摘录和 OCR 均未发送）：\n",
                    )
                } else {
                    append(
                        "已分享附件（默认只读取元数据；text/*/JSON/XML/YAML 文档、RTF/PDF 文本层、PDF 扫描页 OCR 和 Office Open XML 文档会读取受限文本/OCR 摘录；图片不会被自动 OCR，也不读取视觉内容或像素语义）：\n",
                    )
                }
                append(attachmentBlock)
            }
        }.trim()
    }

    fun toLocalVisionPrompt(): String {
        val sharedText = text.toBoundedSharedText()
        val localImageCount = attachments.count { attachment -> attachment.localImageAttachment != null }
        return buildString {
            if (localImageCount > 0) {
                append("已附加 $localImageCount 张图片。请直接根据图片内容处理；图片只会在本机交给本地模型，不会发送远端。如果当前模型不支持图片输入，请明确说明不支持。")
            }
            if (sharedText.text.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                if (sharedText.truncated) {
                    append("分享文本（已截断）：\n")
                }
                append(sharedText.text)
            }
            if (protectedImageSourceCount > 0) {
                if (isNotEmpty()) append("\n\n")
                append("另有受保护图片未读取或发送。")
            }
            if (protectedSourceCount > 0) {
                if (isNotEmpty()) append("\n\n")
                append("另有 $protectedSourceCount 个非图片或分享来源已被保护，本次未读取其文本、附件元数据、文本摘录或 OCR。")
            }
        }.trim()
    }

    fun toRemoteVisionPrompt(): String {
        val remoteImageCount = attachments.count { attachment -> attachment.imageAttachment != null }
        return buildString {
            if (remoteImageCount > 0) {
                append("已附加 $remoteImageCount 张图片。请直接根据图片内容处理；如果当前模型或接口不支持图片输入，请明确说明不支持。")
            }
            if (protectedImageSourceCount > 0) {
                if (isNotEmpty()) append("\n\n")
                append("另有受保护图片未读取或发送。")
            }
            if (protectedSourceCount > 0) {
                if (isNotEmpty()) append("\n\n")
                append("另有 $protectedSourceCount 个非图片或分享来源已被保护，本次未读取或发送其文本、附件元数据、文本摘录或 OCR。")
            }
        }.trim()
    }

    private companion object {
        const val MAX_ATTACHMENTS_IN_PROMPT = 5
    }
}

private fun SharedAttachment.unavailablePreviewNotice(): String? =
    when (kind) {
        SharedAttachmentKind.Image ->
            if (imageAttachment != null || localImageAttachment != null) {
                "图片已附加到本次模型请求；如果当前模型或接口不支持图片输入，请直接说明不支持。"
            } else {
                "当前模型不支持视觉输入；未读取图片内容，也不会自动 OCR。需要文字识别时，请显式使用 OCR 工具。"
            }

        SharedAttachmentKind.Video ->
            "视频画面和音频内容未读取；当前仅有元数据。"

        SharedAttachmentKind.Audio ->
            "音频内容未转写；当前仅有元数据。"

        SharedAttachmentKind.Document ->
            "文档正文未读取；当前仅有元数据。"

        SharedAttachmentKind.Other ->
            "附件正文未读取；当前仅有元数据。"
    }

private data class BoundedSharedText(
    val text: String,
    val truncated: Boolean,
)

private fun String.toBoundedSharedText(): BoundedSharedText {
    val normalized = replace("\r\n", "\n")
        .replace('\r', '\n')
        .filter { char -> !char.isISOControl() || char == '\n' || char == '\t' }
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
    val text = normalized.take(MAX_SHARED_TEXT_CHARS).trim()
    return BoundedSharedText(
        text = text,
        truncated = normalized.length > MAX_SHARED_TEXT_CHARS,
    )
}

data class SharedAttachment(
    val kind: SharedAttachmentKind,
    val mimeType: String?,
    val displayName: String?,
    val sizeBytes: Long?,
    val textPreview: SharedTextPreview? = null,
    val imageAttachment: ChatImageAttachment? = null,
    val localImageAttachment: LocalImageAttachment? = null,
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
    val source: SharedTextPreviewSource = SharedTextPreviewSource.TextFile,
    val quality: MultimodalQuality = MultimodalQuality.fromText(text, truncated),
)

enum class MultimodalQualityLevel {
    High,
    Medium,
    Low,
}

data class MultimodalQuality(
    val level: MultimodalQualityLevel,
    val reasons: List<String> = emptyList(),
) {
    init {
        require(reasons.all { it.isNotBlank() }) { "Multimodal quality reasons must not be blank" }
    }

    fun noticeForPrompt(): String? =
        when (level) {
            MultimodalQualityLevel.Low -> "质量较低：${reasons.joinToString(separator = "/")}"
            MultimodalQualityLevel.Medium -> reasons
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = "/", prefix = "质量提示：")
            MultimodalQualityLevel.High -> null
        }

    companion object {
        fun fromText(text: String, truncated: Boolean): MultimodalQuality {
            val normalized = text.trim()
            val replacementCount = normalized.count { it == '\uFFFD' }
            val punctuationLikeCount = normalized.count { char ->
                !char.isLetterOrDigit() && !char.isWhitespace()
            }
            val visibleCount = normalized.count { !it.isWhitespace() }
            val reasons = buildList {
                if (normalized.length < LOW_TEXT_LENGTH_THRESHOLD) add("short_text")
                if (replacementCount >= REPLACEMENT_CHAR_THRESHOLD) add("replacement_chars")
                if (visibleCount > 0 && punctuationLikeCount.toFloat() / visibleCount > PUNCTUATION_DENSITY_THRESHOLD) {
                    add("punctuation_dense")
                }
                if (truncated) add("truncated")
            }
            val level = when {
                reasons.any { it != "truncated" } -> MultimodalQualityLevel.Low
                truncated -> MultimodalQualityLevel.Medium
                else -> MultimodalQualityLevel.High
            }
            return MultimodalQuality(level = level, reasons = reasons)
        }

        private const val LOW_TEXT_LENGTH_THRESHOLD = 12
        private const val REPLACEMENT_CHAR_THRESHOLD = 2
        private const val PUNCTUATION_DENSITY_THRESHOLD = 0.55f
    }
}

enum class SharedTextPreviewSource(val label: String) {
    TextFile("文本摘录"),
    RichTextDocument("RTF 文本摘录"),
    PdfTextLayer("PDF 文本摘录"),
    PdfImageOcr("PDF 扫描页 OCR 摘录"),
    OfficeDocument("Office 文本摘录"),
    ImageOcr("图片文字摘录"),
}

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
            normalizedMimeType.startsWith("text/") ||
                normalizedMimeType in textLikeApplicationMimeTypes ||
                normalizedMimeType in documentMimeTypes ->
                SharedAttachmentKind.Document

            else -> SharedAttachmentKind.Other
        }
    }

fun resolveSharedAttachmentMimeType(
    resolverMimeType: String?,
    displayName: String?,
    intentMimeType: String? = null,
): String? {
    val normalizedResolverType = resolverMimeType.normalizedMediaType()
    if (normalizedResolverType.isConcreteSharedMimeType()) {
        return normalizedResolverType
    }
    val extensionType = displayName.inferredMimeTypeFromExtension()
    if (extensionType != null) {
        return extensionType
    }
    val normalizedIntentType = intentMimeType.normalizedMediaType()
    if (normalizedIntentType.isConcreteSharedMimeType()) {
        return normalizedIntentType
    }
    return normalizedResolverType ?: normalizedIntentType
}

fun canReadTextPreviewFor(mimeType: String?): Boolean =
    when (val normalizedMimeType = mimeType.normalizedMediaType()) {
        null -> false
        else ->
            (normalizedMimeType.startsWith("text/") && normalizedMimeType !in richTextMimeTypes) ||
                normalizedMimeType in textLikeApplicationMimeTypes
    }

fun canReadRichTextPreviewFor(mimeType: String?): Boolean =
    mimeType.normalizedMediaType() in richTextMimeTypes

fun canReadPdfTextPreviewFor(mimeType: String?): Boolean =
    mimeType.normalizedMediaType() == "application/pdf"

fun canReadOfficeOpenXmlTextPreviewFor(mimeType: String?): Boolean =
    mimeType.normalizedMediaType() in officeOpenXmlMimeTypes

fun canReadImageTextPreviewFor(mimeType: String?): Boolean =
    when (val normalizedMimeType = mimeType.normalizedMediaType()) {
        null -> false
        else -> normalizedMimeType.startsWith("image/")
    }

fun canUseTextPreviewFor(attachment: SharedAttachment): Boolean =
    when (attachment.textPreview?.source) {
        SharedTextPreviewSource.TextFile ->
            attachment.kind == SharedAttachmentKind.Document &&
                canReadTextPreviewFor(attachment.mimeType)

        SharedTextPreviewSource.RichTextDocument ->
            attachment.kind == SharedAttachmentKind.Document &&
                canReadRichTextPreviewFor(attachment.mimeType)

        SharedTextPreviewSource.PdfTextLayer ->
            attachment.kind == SharedAttachmentKind.Document &&
                canReadPdfTextPreviewFor(attachment.mimeType)

        SharedTextPreviewSource.PdfImageOcr ->
            attachment.kind == SharedAttachmentKind.Document &&
                canReadPdfTextPreviewFor(attachment.mimeType)

        SharedTextPreviewSource.OfficeDocument ->
            attachment.kind == SharedAttachmentKind.Document &&
                canReadOfficeOpenXmlTextPreviewFor(attachment.mimeType)

        SharedTextPreviewSource.ImageOcr ->
            attachment.kind == SharedAttachmentKind.Image && canReadImageTextPreviewFor(attachment.mimeType)

        null -> false
    }

internal fun String?.normalizedMediaType(): String? =
    this
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotBlank() }

internal fun String?.inferredMimeTypeFromExtension(): String? {
    val safeName = this
        ?.replace('\\', '/')
        ?.substringAfterLast('/')
        ?.substringBeforeLast('#')
        ?.substringBeforeLast('?')
        ?: return null
    val extension = safeName
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
        .takeIf { it.isNotBlank() }
        ?: return null
    return extensionMimeTypes[extension]
}

internal fun String?.isConcreteSharedMimeType(): Boolean {
    val normalized = this ?: return false
    if (normalized in abstractMimeTypes) return false
    return !normalized.endsWith("/*") && !normalized.contains('*')
}

internal fun trustedRemoteImageMimeType(
    resolverMimeType: String?,
    displayName: String?,
): String? {
    val normalizedResolverType = resolverMimeType.normalizedMediaType()
    if (normalizedResolverType.isConcreteSharedMimeType()) {
        return normalizedResolverType.takeIf { it.isSupportedRemoteImageMimeType() }
    }
    return displayName
        .inferredMimeTypeFromExtension()
        ?.takeIf { inferred -> inferred.isSupportedRemoteImageMimeType() }
}

internal fun remoteImageBytesMatchDeclaredMimeType(
    mimeType: String?,
    bytes: ByteArray,
): Boolean =
    when (mimeType.normalizedMediaType()) {
        "image/jpeg", "image/jpg", "image/pjpeg" -> bytes.hasJpegHeader()
        "image/png" -> bytes.hasPngHeader()
        "image/webp" -> bytes.hasWebpHeader()
        "image/gif" -> bytes.hasGifHeader()
        "image/bmp" -> bytes.hasBmpHeader()
        "image/heic", "image/heif" -> bytes.hasHeifFamilyHeader()
        else -> false
    }

private fun String?.isSupportedRemoteImageMimeType(): Boolean =
    when (normalizedMediaType()) {
        "image/jpeg", "image/jpg", "image/pjpeg",
        "image/png",
        "image/webp",
        "image/gif",
        "image/bmp",
        "image/heic",
        "image/heif",
        -> true

        else -> false
    }

private fun ByteArray.hasJpegHeader(): Boolean =
    size >= 3 &&
        this[0] == 0xFF.toByte() &&
        this[1] == 0xD8.toByte() &&
        this[2] == 0xFF.toByte()

private fun ByteArray.hasPngHeader(): Boolean =
    size >= pngMagic.size && pngMagic.indices.all { index -> this[index] == pngMagic[index] }

private fun ByteArray.hasWebpHeader(): Boolean =
    size >= 12 &&
        asciiEquals(offset = 0, value = "RIFF") &&
        asciiEquals(offset = 8, value = "WEBP")

private fun ByteArray.hasGifHeader(): Boolean =
    size >= 6 &&
        (asciiEquals(offset = 0, value = "GIF87a") || asciiEquals(offset = 0, value = "GIF89a"))

private fun ByteArray.hasBmpHeader(): Boolean =
    size >= 2 && this[0] == 'B'.code.toByte() && this[1] == 'M'.code.toByte()

private fun ByteArray.hasHeifFamilyHeader(): Boolean {
    if (size < 12 || !asciiEquals(offset = 4, value = "ftyp")) return false
    val brands = heifCompatibleBrands
    var offset = 8
    while (offset + 4 <= size && offset <= 64) {
        if (brands.any { brand -> asciiEquals(offset, brand) }) return true
        offset += 4
    }
    return false
}

private fun ByteArray.asciiEquals(offset: Int, value: String): Boolean {
    if (offset < 0 || offset + value.length > size) return false
    return value.indices.all { index -> this[offset + index] == value[index].code.toByte() }
}

object TextAttachmentPreviewReader {
    fun read(inputStream: InputStream): SharedTextPreview? {
        val (bytes, truncatedByBytes) = inputStream.readLimitedBytes(MAX_TEXT_PREVIEW_BYTES + 1)
        val previewBytes = if (bytes.size > MAX_TEXT_PREVIEW_BYTES) {
            bytes.copyOf(MAX_TEXT_PREVIEW_BYTES)
        } else {
            bytes
        }
        val decodedText = previewBytes.decodeStrictUtf8OrNull(
            allowDroppingTrailingPartial = truncatedByBytes,
        ) ?: return null
        val normalized = decodedText
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

private fun ByteArray.decodeStrictUtf8OrNull(allowDroppingTrailingPartial: Boolean): String? {
    val maxDropBytes = if (allowDroppingTrailingPartial) minOf(3, size - 1) else 0
    for (dropBytes in 0..maxDropBytes) {
        val length = size - dropBytes
        val decoded = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(this, 0, length))
                .toString()
        }.getOrNull()
        if (decoded != null) return decoded
    }
    return null
}

object ImageTextPreviewReader {
    fun fromText(text: String): SharedTextPreview? {
        val normalized = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { char -> !char.isISOControl() || char == '\n' || char == '\t' }
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        if (normalized.isBlank()) return null
        val previewText = normalized.take(MAX_IMAGE_TEXT_PREVIEW_CHARS).trim()
        if (previewText.isBlank()) return null
        return SharedTextPreview(
            text = previewText,
            truncated = normalized.length > MAX_IMAGE_TEXT_PREVIEW_CHARS,
            source = SharedTextPreviewSource.ImageOcr,
        )
    }

    private const val MAX_IMAGE_TEXT_PREVIEW_CHARS = 4_000
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

private val textLikeApplicationMimeTypes = setOf(
    "application/json",
    "application/xml",
    "application/yaml",
    "application/x-yaml",
)

private val abstractMimeTypes = setOf(
    "*/*",
    "application/*",
    "application/octet-stream",
    "audio/*",
    "image/*",
    "text/*",
    "video/*",
)

private val richTextMimeTypes = setOf(
    "application/rtf",
    "text/rtf",
)

internal val officeOpenXmlMimeTypes = setOf(
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
)

private val extensionMimeTypes = mapOf(
    "txt" to "text/plain",
    "text" to "text/plain",
    "log" to "text/plain",
    "md" to "text/markdown",
    "markdown" to "text/markdown",
    "csv" to "text/csv",
    "json" to "application/json",
    "xml" to "application/xml",
    "yaml" to "application/x-yaml",
    "yml" to "application/x-yaml",
    "rtf" to "application/rtf",
    "pdf" to "application/pdf",
    "doc" to "application/msword",
    "xls" to "application/vnd.ms-excel",
    "ppt" to "application/vnd.ms-powerpoint",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "webp" to "image/webp",
    "gif" to "image/gif",
    "bmp" to "image/bmp",
    "heic" to "image/heic",
    "heif" to "image/heif",
)

private val pngMagic = byteArrayOf(
    0x89.toByte(),
    'P'.code.toByte(),
    'N'.code.toByte(),
    'G'.code.toByte(),
    0x0D.toByte(),
    0x0A.toByte(),
    0x1A.toByte(),
    0x0A.toByte(),
)

private val heifCompatibleBrands = setOf(
    "heic",
    "heix",
    "hevc",
    "hevx",
    "heif",
    "heis",
    "mif1",
    "msf1",
)
