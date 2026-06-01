package com.bytedance.zgx.pocketmind.multimodal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns

class ShareIntentReader(
    private val context: Context,
    private val imageTextExtractor: ImageTextExtractor = MlKitImageTextExtractor(context),
) {
    fun read(intent: Intent?): SharedInput? {
        if (intent == null) return null
        val action = intent.action ?: return null
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null

        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val uris = when (action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamUri())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamUris()
            else -> emptyList()
        }
        return readUris(uris = uris, text = text, intentMimeType = intent.type)
    }

    fun readUris(
        uris: List<Uri>,
        text: String = "",
        intentMimeType: String? = null,
    ): SharedInput? {
        val attachments = uris
            .take(MAX_SHARED_ATTACHMENTS)
            .map { uri -> uri.toSharedAttachment(intentMimeType) }
        return SharedInput(text = text, attachments = attachments)
            .takeUnless { it.isEmpty }
    }

    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun Intent.streamUris(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }

    private fun Uri.toSharedAttachment(intentMimeType: String?): SharedAttachment {
        val resolvedMimeType = runCatching { context.contentResolver.getType(this) }.getOrNull() ?: intentMimeType
        val metadata = queryMetadata(this)
        val kind = sharedAttachmentKindFor(resolvedMimeType)
        val textPreview = when {
            canReadTextPreviewFor(resolvedMimeType) -> readTextPreview()
            canReadOfficeOpenXmlTextPreviewFor(resolvedMimeType) -> readOfficeOpenXmlTextPreview(resolvedMimeType)
            kind == SharedAttachmentKind.Image && canReadImageTextPreviewFor(resolvedMimeType) ->
                imageTextExtractor.extract(this)

            else -> null
        }
        return SharedAttachment(
            kind = kind,
            mimeType = resolvedMimeType,
            displayName = metadata.displayName,
            sizeBytes = metadata.sizeBytes,
            textPreview = textPreview,
        )
    }

    private fun Uri.readTextPreview(): SharedTextPreview? =
        runCatching {
            context.contentResolver.openInputStream(this)?.use { input ->
                TextAttachmentPreviewReader.read(input)
            }
        }.getOrNull()

    private fun Uri.readOfficeOpenXmlTextPreview(mimeType: String?): SharedTextPreview? =
        runCatching {
            context.contentResolver.openInputStream(this)?.use { input ->
                OfficeOpenXmlPreviewReader.read(input, mimeType)
            }
        }.getOrNull()

    private fun queryMetadata(uri: Uri): AttachmentMetadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                    }
                }
            }
        }
        return AttachmentMetadata(displayName = displayName, sizeBytes = sizeBytes)
    }

    private data class AttachmentMetadata(
        val displayName: String?,
        val sizeBytes: Long?,
    )

    private companion object {
        const val MAX_SHARED_ATTACHMENTS = 5
    }
}
