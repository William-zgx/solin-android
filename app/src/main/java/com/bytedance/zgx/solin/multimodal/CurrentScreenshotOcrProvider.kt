package com.bytedance.zgx.solin.multimodal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private const val MAX_CURRENT_SCREEN_OCR_BITMAP_DIMENSION = 1_600
private const val CURRENT_SCREENSHOT_OCR_CONSENT_TTL_MILLIS = 30_000L

interface CurrentScreenshotOcrProvider {
    fun setOneShotConsent(
        requestId: String,
        resultCode: Int,
        data: Intent?,
        issuedAtMillis: Long = System.currentTimeMillis(),
    )

    fun clearOneShotConsent(requestId: String)

    fun captureCurrentScreenshotOcr(
        requestId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): CurrentScreenshotOcrReadResult
}

sealed interface CurrentScreenshotOcrReadResult {
    data object MissingConsent : CurrentScreenshotOcrReadResult
    data class Available(
        val text: String?,
        val truncated: Boolean,
        val ocrBlocks: List<OcrTextBlock> = emptyList(),
    ) : CurrentScreenshotOcrReadResult

    data class Failed(val reason: String) : CurrentScreenshotOcrReadResult
}

class AndroidCurrentScreenshotOcrProvider(
    private val context: Context,
    private val imageTextExtractor: ImageTextExtractor = MlKitImageTextExtractor(context),
) : CurrentScreenshotOcrProvider {
    private val pendingConsent =
        RequestBoundOneShotConsentStore<CurrentScreenshotOcrConsent>(
            ttlMillis = CURRENT_SCREENSHOT_OCR_CONSENT_TTL_MILLIS,
        )

    override fun setOneShotConsent(
        requestId: String,
        resultCode: Int,
        data: Intent?,
        issuedAtMillis: Long,
    ) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            pendingConsent.clear(requestId)
            return
        }
        pendingConsent.set(
            requestId = requestId,
            issuedAtMillis = issuedAtMillis,
            data = CurrentScreenshotOcrConsent(resultCode = resultCode, data = Intent(data)),
        )
    }

    override fun clearOneShotConsent(requestId: String) {
        pendingConsent.clear(requestId)
    }

    override fun captureCurrentScreenshotOcr(
        requestId: String,
        nowMillis: Long,
    ): CurrentScreenshotOcrReadResult {
        val consent = pendingConsent.consume(requestId, nowMillis)
            ?: return CurrentScreenshotOcrReadResult.MissingConsent
        val projection = mediaProjectionManager().getMediaProjection(consent.resultCode, consent.data)
            ?: return CurrentScreenshotOcrReadResult.MissingConsent
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val densityDpi = metrics.densityDpi.coerceAtLeast(1)
        val handlerThread = HandlerThread("SolinCurrentScreenshotOcr")
        handlerThread.start()
        val handler = Handler(handlerThread.looper)
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES)
        var virtualDisplay: VirtualDisplay? = null
        val projectionCallback = object : MediaProjection.Callback() {}
        return try {
            projection.registerCallback(projectionCallback, handler)
            val capturedImage = AtomicReference<Image?>()
            val imageLatch = CountDownLatch(1)
            imageReader.setOnImageAvailableListener(
                { reader ->
                    val image = reader.acquireLatestImage()
                    val previous = if (image != null) capturedImage.getAndSet(image) else null
                    previous?.close()
                    imageLatch.countDown()
                },
                handler,
            )
            virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler,
            )
            if (!imageLatch.await(CAPTURE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return CurrentScreenshotOcrReadResult.Failed("当前屏幕截图超时")
            }
            val image = capturedImage.getAndSet(null)
                ?: return CurrentScreenshotOcrReadResult.Failed("当前屏幕截图不可用")
            image.useToBitmap { bitmap ->
                val ocrBitmap = bitmap.scaledForOcr()
                try {
                    val preview = imageTextExtractor.extract(ocrBitmap)
                    CurrentScreenshotOcrReadResult.Available(
                        text = preview?.text?.takeIf { it.isNotBlank() },
                        truncated = preview?.truncated ?: false,
                        ocrBlocks = preview?.ocrBlocks.orEmpty(),
                    )
                } finally {
                    if (ocrBitmap !== bitmap) ocrBitmap.recycle()
                }
            }
        } catch (_: SecurityException) {
            CurrentScreenshotOcrReadResult.MissingConsent
        } catch (_: Throwable) {
            CurrentScreenshotOcrReadResult.Failed("当前屏幕 OCR 服务不可用")
        } finally {
            virtualDisplay?.release()
            imageReader.close()
            runCatching { projection.unregisterCallback(projectionCallback) }
            projection.stop()
            handlerThread.quitSafely()
        }
    }

    private fun mediaProjectionManager(): MediaProjectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private data class CurrentScreenshotOcrConsent(
        val resultCode: Int,
        val data: Intent,
    )

    private companion object {
        const val VIRTUAL_DISPLAY_NAME = "SolinCurrentScreenshotOcr"
        const val IMAGE_READER_MAX_IMAGES = 2
        const val CAPTURE_TIMEOUT_MILLIS = 2_500L
    }
}

internal class RequestBoundOneShotConsentStore<T>(
    private val ttlMillis: Long,
) {
    private val pending = AtomicReference<RequestBoundOneShotConsent<T>?>()

    fun set(requestId: String, issuedAtMillis: Long, data: T) {
        pending.set(
            RequestBoundOneShotConsent(
                requestId = requestId,
                issuedAtMillis = issuedAtMillis,
                data = data,
            ),
        )
    }

    fun clear(requestId: String) {
        while (true) {
            val current = pending.get() ?: return
            if (current.requestId != requestId) return
            if (pending.compareAndSet(current, null)) return
        }
    }

    fun consume(requestId: String, nowMillis: Long): T? {
        while (true) {
            val current = pending.get() ?: return null
            if (current.requestId != requestId) return null
            if (nowMillis < current.issuedAtMillis || nowMillis - current.issuedAtMillis > ttlMillis) {
                if (pending.compareAndSet(current, null)) return null
                continue
            }
            if (pending.compareAndSet(current, null)) return current.data
        }
    }
}

private data class RequestBoundOneShotConsent<T>(
    val requestId: String,
    val issuedAtMillis: Long,
    val data: T,
)

private inline fun Image.useToBitmap(block: (Bitmap) -> CurrentScreenshotOcrReadResult): CurrentScreenshotOcrReadResult =
    use { image ->
        val bitmap = image.toBitmap()
        try {
            block(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

private fun Image.toBitmap(): Bitmap {
    val plane = planes.first()
    val buffer = plane.buffer
    buffer.rewind()
    val pixelStride = plane.pixelStride.coerceAtLeast(1)
    val rowStride = plane.rowStride.coerceAtLeast(width * pixelStride)
    val rowPadding = (rowStride - pixelStride * width).coerceAtLeast(0)
    val bitmapWidth = width + rowPadding / pixelStride
    val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(buffer)
    if (bitmapWidth == width) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
        bitmap.recycle()
    }
}

private fun Bitmap.scaledForOcr(): Bitmap {
    val maxDimension = maxOf(width, height)
    if (maxDimension <= MAX_CURRENT_SCREEN_OCR_BITMAP_DIMENSION) return this
    val scale = MAX_CURRENT_SCREEN_OCR_BITMAP_DIMENSION.toFloat() / maxDimension.toFloat()
    val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
}
