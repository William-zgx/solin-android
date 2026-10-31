package com.bytedance.zgx.solin.device

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import com.bytedance.zgx.solin.multimodal.JPEG_QUALITY
import com.bytedance.zgx.solin.multimodal.RawScreenshotReadResult
import com.bytedance.zgx.solin.multimodal.compactedForVision
import java.io.ByteArrayOutputStream

/**
 * Converts an [AccessibilityService.ScreenshotResult] into compacted JPEG bytes, reusing [compactedForVision]/
 * [JPEG_QUALITY] (same compaction as the OCR/remote image paths). The wrapped hardware bitmap is
 * immutable, so it is copied to a software ARGB_8888 bitmap before JPEG encoding. All bitmaps are
 * recycled and the [android.hardware.HardwareBuffer] is closed to avoid leaks.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal fun AccessibilityService.ScreenshotResult.toRawScreenshotReadResult(): RawScreenshotReadResult {
    val buffer = hardwareBuffer
    return try {
        val wrapped = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
            ?: return RawScreenshotReadResult.Failed("当前屏幕截图解码失败")
        val software = try {
            wrapped.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            wrapped.recycle()
        } ?: return RawScreenshotReadResult.Failed("当前屏幕截图解码失败")
        try {
            if (software.looksBlank()) {
                return RawScreenshotReadResult.Failed("当前屏幕截图为空白，可能是受保护页面")
            }
            val vision = software.compactedForVision()
            try {
                val output = ByteArrayOutputStream()
                if (!vision.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    RawScreenshotReadResult.Failed("当前屏幕截图编码失败")
                } else {
                    RawScreenshotReadResult.Available(
                        jpegBytes = output.toByteArray(),
                        widthPx = vision.width,
                        heightPx = vision.height,
                    )
                }
            } finally {
                if (vision !== software) vision.recycle()
            }
        } finally {
            software.recycle()
        }
    } catch (_: Throwable) {
        RawScreenshotReadResult.Failed("当前屏幕截图不可用")
    } finally {
        buffer.close()
    }
}

private const val BLANK_SCREENSHOT_SAMPLE_GRID = 24

private fun Bitmap.looksBlank(): Boolean =
    sampledPixelsLookBlank(
        IntArray(BLANK_SCREENSHOT_SAMPLE_GRID * BLANK_SCREENSHOT_SAMPLE_GRID) { index ->
            getPixel(
                (index % BLANK_SCREENSHOT_SAMPLE_GRID) * width / BLANK_SCREENSHOT_SAMPLE_GRID,
                (index / BLANK_SCREENSHOT_SAMPLE_GRID) * height / BLANK_SCREENSHOT_SAMPLE_GRID,
            )
        },
    )

/**
 * Whether sampled ARGB pixels are all near black. The system blacks out secure windows in a
 * full-screen capture without reporting an error, while their accessibility text stays readable, so
 * a blank capture must stop the send: otherwise the control list would carry what the app hid.
 */
internal fun sampledPixelsLookBlank(pixels: IntArray): Boolean =
    pixels.all { pixel ->
        ((pixel shr 16) and 0xFF) < BLANK_PIXEL_MAX_CHANNEL &&
            ((pixel shr 8) and 0xFF) < BLANK_PIXEL_MAX_CHANNEL &&
            (pixel and 0xFF) < BLANK_PIXEL_MAX_CHANNEL
    }

private const val BLANK_PIXEL_MAX_CHANNEL = 12

/**
 * Maps an [AccessibilityService.TakeScreenshotCallback] error code to a fail-closed
 * [RawScreenshotReadResult.Failed]. Pure (no Android instances) so it is unit-testable. The code is
 * embedded for diagnostics; every error fails closed (no pixels leave the device).
 */
internal fun takeScreenshotErrorToResult(errorCode: Int): RawScreenshotReadResult.Failed =
    RawScreenshotReadResult.Failed("当前屏幕截图失败(code=$errorCode)")
