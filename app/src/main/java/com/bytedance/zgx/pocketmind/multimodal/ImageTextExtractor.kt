package com.bytedance.zgx.pocketmind.multimodal

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

interface ImageTextExtractor {
    fun extract(uri: Uri): SharedTextPreview?
    fun extract(bitmap: Bitmap): SharedTextPreview? = null
}

object NoOpImageTextExtractor : ImageTextExtractor {
    override fun extract(uri: Uri): SharedTextPreview? = null
    override fun extract(bitmap: Bitmap): SharedTextPreview? = null
}

class MlKitImageTextExtractor(
    private val context: Context,
) : ImageTextExtractor {
    override fun extract(uri: Uri): SharedTextPreview? =
        runCatching {
            extract(InputImage.fromFilePath(context, uri))
        }.getOrNull()

    override fun extract(bitmap: Bitmap): SharedTextPreview? =
        runCatching {
            extract(InputImage.fromBitmap(bitmap, 0))
        }.getOrNull()

    private fun extract(image: InputImage): SharedTextPreview? {
        val latinBlocks = runCatching {
            extractTextBlocks(
                image = image,
                recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            )
        }.getOrNull()
        val chineseBlocks = runCatching {
            extractTextBlocks(
                image = image,
                recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            )
        }.getOrNull()
        return ImageTextPreviewReader.fromText(
            OcrTextLayoutFormatter.mergeRecognizedBlocks(listOfNotNull(latinBlocks, chineseBlocks)),
        )
    }

    private fun extractTextBlocks(
        image: InputImage,
        recognizer: TextRecognizer,
    ): List<List<String>> =
        try {
            Tasks.await(recognizer.process(image), RECOGNIZER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .textBlocks
                .map { block -> block.lines.map { line -> line.text } }
        } finally {
            recognizer.close()
        }

    private companion object {
        const val RECOGNIZER_TIMEOUT_MILLIS = 2_500L
    }
}

internal object OcrTextLayoutFormatter {
    fun mergeRecognizedBlocks(recognizerBlocks: List<List<List<String>>>): String {
        val seenLines = linkedSetOf<String>()
        val outputBlocks = mutableListOf<List<String>>()
        recognizerBlocks.forEach { blocks ->
            blocks.forEach { block ->
                val lines = block
                    .mapNotNull { line ->
                        line.normalizedOcrLine()
                            .takeIf { normalized -> normalized.isNotBlank() }
                            ?.takeIf(seenLines::add)
                    }
                if (lines.isNotEmpty()) {
                    outputBlocks += lines
                }
            }
        }
        return outputBlocks.joinToString(separator = "\n\n") { block ->
            block.joinToString(separator = "\n")
        }
    }

    private fun String.normalizedOcrLine(): String =
        replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { char -> !char.isISOControl() || char == '\n' || char == '\t' }
            .replace(Regex("""[ \t\n]+"""), " ")
            .trim()
}
