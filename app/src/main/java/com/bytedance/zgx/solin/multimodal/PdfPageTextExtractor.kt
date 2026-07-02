package com.bytedance.zgx.solin.multimodal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlin.math.min
import kotlin.math.roundToInt

class AndroidPdfPageTextExtractor(
    private val context: Context,
    private val imageTextExtractor: ImageTextExtractor,
) {
    fun extract(uri: Uri): SharedTextPreview? =
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val renderer = PdfRenderer(descriptor)
                try {
                    extractFromRenderer(renderer)
                } finally {
                    renderer.close()
                }
            }
        }.getOrNull()

    private fun extractFromRenderer(renderer: PdfRenderer): SharedTextPreview? {
        if (renderer.pageCount <= 0) return null
        val pageTexts = mutableListOf<String>()
        var truncated = renderer.pageCount > MAX_PDF_OCR_PAGES
        var totalChars = 0
        for (pageIndex in 0 until min(renderer.pageCount, MAX_PDF_OCR_PAGES)) {
            if (totalChars >= MAX_PDF_OCR_PREVIEW_CHARS) break
            val page = renderer.openPage(pageIndex)
            try {
                val bitmap = page.toBoundedBitmap()
                try {
                    val preview = imageTextExtractor.extract(bitmap) ?: continue
                    if (preview.text.isBlank()) continue
                    val pageText = buildString {
                        append("第 ")
                        append(pageIndex + 1)
                        append(" 页:\n")
                        append(preview.text)
                    }
                    pageTexts += pageText
                    totalChars += pageText.length
                    truncated = truncated || preview.truncated || totalChars >= MAX_PDF_OCR_PREVIEW_CHARS
                } finally {
                    bitmap.recycle()
                }
            } finally {
                page.close()
            }
        }
        val normalized = pageTexts
            .joinToString(separator = "\n\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return sharedTextPreviewFrom(
            normalizedText = normalized,
            maxChars = MAX_PDF_OCR_PREVIEW_CHARS,
            truncated = truncated || normalized.length > MAX_PDF_OCR_PREVIEW_CHARS,
            source = SharedTextPreviewSource.PdfImageOcr,
        )
    }

    private fun PdfRenderer.Page.toBoundedBitmap(): Bitmap {
        val sourceWidth = width.coerceAtLeast(1)
        val sourceHeight = height.coerceAtLeast(1)
        val scale = min(
            MAX_PDF_OCR_RENDER_DIMENSION.toFloat() / sourceWidth.toFloat(),
            MAX_PDF_OCR_RENDER_DIMENSION.toFloat() / sourceHeight.toFloat(),
        ).coerceAtMost(1f)
        val bitmap = Bitmap.createBitmap(
            (sourceWidth * scale).roundToInt().coerceAtLeast(1),
            (sourceHeight * scale).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        Canvas(bitmap).drawColor(Color.WHITE)
        render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private companion object {
        const val MAX_PDF_OCR_PAGES = 3
        const val MAX_PDF_OCR_RENDER_DIMENSION = 1600
        const val MAX_PDF_OCR_PREVIEW_CHARS = 4_000
    }
}
