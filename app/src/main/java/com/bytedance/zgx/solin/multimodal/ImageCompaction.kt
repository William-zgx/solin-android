package com.bytedance.zgx.solin.multimodal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.roundToInt

internal const val MAX_VISION_IMAGE_DIMENSION = 1024
internal const val MAX_VISION_IMAGE_BYTES = 2 * 1024 * 1024
internal const val JPEG_QUALITY = 80

internal fun Bitmap.compactedForVision(): Bitmap {
    val maxDimension = maxOf(width, height)
    if (maxDimension <= MAX_VISION_IMAGE_DIMENSION) return this
    val scale = MAX_VISION_IMAGE_DIMENSION.toFloat() / maxDimension.toFloat()
    val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
}

/**
 * Compacts image bytes for vision model input.
 *
 * If the byte array represents an image that exceeds [MAX_VISION_IMAGE_BYTES] or has
 * dimensions larger than [MAX_VISION_IMAGE_DIMENSION], it is decoded, resized, and
 * re-encoded as JPEG. If already small enough with acceptable dimensions, returns
 * the original byte array unchanged.
 *
 * @return compacted bytes (may be the same reference as the receiver if no
 *         compaction was needed), or `null` if compaction was required but failed.
 */
internal fun ByteArray.compactedImageBytesForVision(): ByteArray? {
    val bitmap = BitmapFactory.decodeByteArray(this, 0, size) ?: return null
    try {
        val needsResize = maxOf(bitmap.width, bitmap.height) > MAX_VISION_IMAGE_DIMENSION
        val needsCompress = size > MAX_VISION_IMAGE_BYTES
        if (!needsResize && !needsCompress) return this

        val transformed = if (needsResize) bitmap.compactedForVision() else bitmap
        try {
            val output = ByteArrayOutputStream()
            if (!transformed.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) return null
            val result = output.toByteArray()
            // If re-encoding without resize produced a larger file, keep original.
            if (result.size >= size && !needsResize) return this
            return result
        } finally {
            if (transformed !== bitmap) transformed.recycle()
        }
    } finally {
        bitmap.recycle()
    }
}

/**
 * Compacts a base64 data URL for vision model input.
 *
 * Parses the `data:image/...;base64,...` URL, compacts the embedded image bytes,
 * and returns a new data URL. If the image was already small enough with acceptable
 * dimensions, the original URL is returned unchanged.
 *
 * @return the compacted (or original) data URL, or `null` if the data URL could not
 *         be parsed or compaction failed.
 */
internal fun compactImageDataUrlForVision(dataUrl: String): String? {
    val prefixEnd = dataUrl.indexOf(";base64,")
    if (prefixEnd < 0) return null
    val base64Data = dataUrl.substring(prefixEnd + ";base64,".length)
    val bytes = runCatching { Base64.getDecoder().decode(base64Data) }.getOrNull() ?: return null

    if (bytes.size <= MAX_VISION_IMAGE_BYTES) {
        // Quick check: decode to verify dimensions before deciding.
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val maxDim = maxOf(options.outWidth, options.outHeight)
        if (maxDim in 1..MAX_VISION_IMAGE_DIMENSION) return dataUrl
    }

    val compacted = bytes.compactedImageBytesForVision() ?: return null
    if (compacted === bytes) return dataUrl
    val compactedBase64 = Base64.getEncoder().encodeToString(compacted)
    return "data:image/jpeg;base64,$compactedBase64"
}
