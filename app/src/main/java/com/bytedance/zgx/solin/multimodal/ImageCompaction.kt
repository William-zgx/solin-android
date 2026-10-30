package com.bytedance.zgx.solin.multimodal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
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
