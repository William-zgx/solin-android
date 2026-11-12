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
        issuedAtMillis: Long = System.currentTimeMillis()
    )

    fun clearOneShotConsent(requestId: String)

    fun hasOneShotConsent(
        requestId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean

    fun captureCurrentScreenshotOcr(
        requestId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): CurrentScreenshotOcrReadResult
}

sealed interface CurrentScreenshotOcrReadResult {
    data object MissingConsent : CurrentScreenshotOcrReadResult
    data class Available(
        val text: String?,
        val truncated: Boolean,
        val ocrBlocks: List<OcrTextBlock> = emptyList()
    ) : CurrentScreenshotOcrReadResult

    data class Failed(val reason: String) : CurrentScreenshotOcrReadResult
}

/**
 * Captures the currently visible screen as transient JPEG pixels for the opt-in
 * remote-vision GUI automation path (kimi-k2.5 sees the screenshot and decides taps).
 *
 * Unlike [CurrentScreenshotOcrProvider], whose output is LocalOnly OCR text with pixels
 * recycled on-device, this provider returns the compacted JPEG bytes so they can cross the
 * remote boundary AFTER user consent. The bytes are never persisted — the caller must send
 * them and drop the reference. Consent, one-shot capture, and the remote-vision opt-in gate
 * live upstream in the replanner ([com.bytedance.zgx.solin.orchestration]); this function contract
 * only performs the capture. See [RemoteVisionScreenshotContract] for the boundary invariants.
 */
typealias RawScreenshotProvider = (requestId: String) -> RawScreenshotReadResult

sealed interface RawScreenshotReadResult {
    data object MissingConsent : RawScreenshotReadResult

    /**
     * Transient compacted JPEG pixels of the current screen. Never persisted; the caller
     * base64-encodes these into a [com.bytedance.zgx.solin.ChatImageAttachment] for a single
     * remote send, then drops the reference. [widthPx]/[heightPx] are the compacted (sent)
     * dimensions — tap coordinates remain resolution-agnostic (0-1000 normalized) so these
     * are metadata only.
     */
    class Available(
        val jpegBytes: ByteArray,
        val widthPx: Int,
        val heightPx: Int
    ) : RawScreenshotReadResult

    data class Failed(val reason: String) : RawScreenshotReadResult
}

class AndroidCurrentScreenshotOcrProvider(
    private val context: Context,
    private val imageTextExtractor: ImageTextExtractor = MlKitImageTextExtractor(context),
    private val foregroundGate: ScreenCaptureForegroundGate = AndroidScreenCaptureForegroundGate(context)
) : CurrentScreenshotOcrProvider {
    private val pendingConsent =
        RequestBoundOneShotConsentStore<CurrentScreenshotOcrConsent>(
            ttlMillis = CURRENT_SCREENSHOT_OCR_CONSENT_TTL_MILLIS
        )

    override fun setOneShotConsent(
        requestId: String,
        resultCode: Int,
        data: Intent?,
        issuedAtMillis: Long
    ) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            clearOneShotConsent(requestId)
            return
        }
        pendingConsent.set(
            requestId = requestId,
            issuedAtMillis = issuedAtMillis,
            data = CurrentScreenshotOcrConsent(resultCode = resultCode, data = Intent(data))
        )
        // Called from the MediaProjection consent callback while the activity is in the
        // foreground: Android 14 requires the mediaProjection-type foreground service to be
        // started after consent and before getMediaProjection (done later on the tool thread).
        foregroundGate.start(requestId)
    }

    override fun clearOneShotConsent(requestId: String) {
        pendingConsent.clear(requestId)
        foregroundGate.stop(requestId)
    }

    override fun hasOneShotConsent(requestId: String, nowMillis: Long): Boolean = pendingConsent.has(requestId, nowMillis)

    override fun captureCurrentScreenshotOcr(
        requestId: String,
        nowMillis: Long
    ): CurrentScreenshotOcrReadResult {
        val consent = pendingConsent.consume(requestId, nowMillis)
            ?: return CurrentScreenshotOcrReadResult.MissingConsent
        return captureBitmap(
            requestId = requestId,
            consent = consent,
            onMissingConsent = { CurrentScreenshotOcrReadResult.MissingConsent },
            onFailed = { reason -> CurrentScreenshotOcrReadResult.Failed(reason) },
            serviceUnavailableReason = "当前屏幕 OCR 服务不可用"
        ) { bitmap ->
            val ocrBitmap = bitmap.scaledForOcr()
            try {
                val preview = imageTextExtractor.extract(ocrBitmap)
                // ML Kit reports bounds in the coordinate space of the bitmap it was handed, which
                // is the (possibly downscaled) [scaledForOcr] bitmap. Downstream consumers
                // (ScreenObservation elements -> ToolExecutor OCR grounding -> dispatchTapGesture)
                // treat these bounds as real screen pixels, so the un-scaling has to happen here,
                // inside the provider: `ocrBlocks` leaving this method are always source-screenshot
                // (real screen) pixels. Identity when no downscale happened.
                CurrentScreenshotOcrReadResult.Available(
                    text = preview?.text?.takeIf { it.isNotBlank() },
                    truncated = preview?.truncated ?: false,
                    ocrBlocks = preview?.ocrBlocks.orEmpty().mappedToSourcePixels(
                        sourceWidth = bitmap.width,
                        sourceHeight = bitmap.height,
                        ocrWidth = ocrBitmap.width,
                        ocrHeight = ocrBitmap.height
                    )
                )
            } finally {
                if (ocrBitmap !== bitmap) ocrBitmap.recycle()
            }
        }
    }

    /**
     * MediaProjection capture inside the foreground-service session. A stale consent token maps to
     * MissingConsent; a foreground-service failure maps to a distinct Failed reason. [block]
     * receives the captured bitmap, which is recycled afterwards.
     */
    private fun <R> captureBitmap(
        requestId: String,
        consent: CurrentScreenshotOcrConsent,
        onMissingConsent: () -> R,
        onFailed: (String) -> R,
        serviceUnavailableReason: String,
        block: (Bitmap) -> R
    ): R = withScreenCaptureForegroundSession(
        gate = foregroundGate,
        requestId = requestId,
        timeoutMillis = FOREGROUND_SERVICE_START_TIMEOUT_MILLIS,
        onMissingConsent = onMissingConsent,
        onFailed = onFailed,
        unexpectedFailureReason = serviceUnavailableReason
    ) {
        captureWithProjection(consent, onMissingConsent, onFailed, block)
    }

    private fun <R> captureWithProjection(
        consent: CurrentScreenshotOcrConsent,
        onMissingConsent: () -> R,
        onFailed: (String) -> R,
        block: (Bitmap) -> R
    ): R {
        // SecurityException from here (stale token or missing foreground service) is mapped by
        // withScreenCaptureForegroundSession.
        val projection = mediaProjectionManager().getMediaProjection(consent.resultCode, consent.data)
            ?: return onMissingConsent()
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val densityDpi = metrics.densityDpi.coerceAtLeast(1)
        val handlerThread = HandlerThread("SolinCurrentScreenshot")
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
                handler
            )
            virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler
            )
            if (!imageLatch.await(CAPTURE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return onFailed("当前屏幕截图超时")
            }
            val image = capturedImage.getAndSet(null)
                ?: return onFailed("当前屏幕截图不可用")
            image.useToBitmap(block)
        } finally {
            virtualDisplay?.release()
            imageReader.close()
            runCatching { projection.unregisterCallback(projectionCallback) }
            projection.stop()
            handlerThread.quitSafely()
        }
    }

    private fun mediaProjectionManager(): MediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private data class CurrentScreenshotOcrConsent(
        val resultCode: Int,
        val data: Intent
    )

    private companion object {
        const val VIRTUAL_DISPLAY_NAME = "SolinCurrentScreenshot"
        const val IMAGE_READER_MAX_IMAGES = 2
        const val CAPTURE_TIMEOUT_MILLIS = 2_500L
        const val FOREGROUND_SERVICE_START_TIMEOUT_MILLIS = 3_000L
    }
}

internal class RequestBoundOneShotConsentStore<T>(
    private val ttlMillis: Long
) {
    private val pending = AtomicReference<RequestBoundOneShotConsent<T>?>()

    fun set(requestId: String, issuedAtMillis: Long, data: T) {
        pending.set(
            RequestBoundOneShotConsent(
                requestId = requestId,
                issuedAtMillis = issuedAtMillis,
                data = data
            )
        )
    }

    fun clear(requestId: String) {
        while (true) {
            val current = pending.get() ?: return
            if (current.requestId != requestId) return
            if (pending.compareAndSet(current, null)) return
        }
    }

    fun has(requestId: String, nowMillis: Long): Boolean {
        while (true) {
            val current = pending.get() ?: return false
            if (current.requestId != requestId) return false
            if (!current.isFresh(nowMillis)) {
                if (pending.compareAndSet(current, null)) return false
                continue
            }
            return true
        }
    }

    fun consume(requestId: String, nowMillis: Long): T? {
        while (true) {
            val current = pending.get() ?: return null
            if (current.requestId != requestId) return null
            if (!current.isFresh(nowMillis)) {
                if (pending.compareAndSet(current, null)) return null
                continue
            }
            if (pending.compareAndSet(current, null)) return current.data
        }
    }

    private fun RequestBoundOneShotConsent<T>.isFresh(nowMillis: Long): Boolean = nowMillis >= issuedAtMillis && nowMillis - issuedAtMillis <= ttlMillis
}

private data class RequestBoundOneShotConsent<T>(
    val requestId: String,
    val issuedAtMillis: Long,
    val data: T
)

private inline fun <R> Image.useToBitmap(block: (Bitmap) -> R): R = use { image ->
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

/**
 * Maps OCR bounds recognized on the downscaled [scaledForOcr] bitmap back into source-screenshot
 * (real screen) pixel coordinates, for every nesting level (block -> line -> element).
 *
 * This is the provider-internal half of the OCR coordinate contract: `ocrBlocks` handed to
 * [CurrentScreenshotOcrReadResult.Available] are always source-screenshot pixels, so downstream
 * grounding and `dispatchTapGesture` need no scaling awareness. Pure function; no bitmap access.
 *
 * X and Y are un-scaled independently because [scaledForOcr] rounds width and height separately,
 * so the two axes can carry slightly different effective scale factors.
 *
 * Returns the receiver unchanged when no scaling happened (dimensions already equal) or when the
 * OCR dimensions are non-positive (nothing trustworthy to invert — fail closed to the raw values).
 */
internal fun List<OcrTextBlock>.mappedToSourcePixels(
    sourceWidth: Int,
    sourceHeight: Int,
    ocrWidth: Int,
    ocrHeight: Int
): List<OcrTextBlock> {
    if (isEmpty()) return this
    if (ocrWidth <= 0 || ocrHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return this
    if (ocrWidth == sourceWidth && ocrHeight == sourceHeight) return this
    val scaleX = sourceWidth.toFloat() / ocrWidth.toFloat()
    val scaleY = sourceHeight.toFloat() / ocrHeight.toFloat()
    return map { block ->
        block.copy(
            bounds = block.bounds?.mappedToSourcePixels(scaleX, scaleY, sourceWidth, sourceHeight),
            lines = block.lines.map { line ->
                line.copy(
                    bounds = line.bounds?.mappedToSourcePixels(scaleX, scaleY, sourceWidth, sourceHeight),
                    elements = line.elements.map { element ->
                        element.copy(
                            bounds = element.bounds
                                ?.mappedToSourcePixels(scaleX, scaleY, sourceWidth, sourceHeight)
                        )
                    }
                )
            }
        )
    }
}

private fun OcrTextBounds.mappedToSourcePixels(
    scaleX: Float,
    scaleY: Float,
    sourceWidth: Int,
    sourceHeight: Int
): OcrTextBounds = OcrTextBounds(
    left = (left * scaleX).roundToInt().coerceIn(0, sourceWidth),
    top = (top * scaleY).roundToInt().coerceIn(0, sourceHeight),
    right = (right * scaleX).roundToInt().coerceIn(0, sourceWidth),
    bottom = (bottom * scaleY).roundToInt().coerceIn(0, sourceHeight)
)
