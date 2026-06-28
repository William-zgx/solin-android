package com.bytedance.zgx.solin.multimodal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
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
        val blocks = OcrTextLayoutFormatter.mergeRecognizedBlocks(listOfNotNull(latinBlocks, chineseBlocks))
        return ImageTextPreviewReader.fromText(
            text = OcrTextLayoutFormatter.toPlainText(blocks),
            ocrBlocks = blocks,
        )
    }

    private fun extractTextBlocks(
        image: InputImage,
        recognizer: TextRecognizer,
    ): List<OcrTextBlock> =
        try {
            Tasks.await(recognizer.process(image), RECOGNIZER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .textBlocks
                .map { block -> block.toOcrTextBlock() }
        } finally {
            recognizer.close()
        }

    private fun Text.TextBlock.toOcrTextBlock(): OcrTextBlock =
        OcrTextBlock(
            text = text,
            bounds = boundingBox.toOcrTextBounds(),
            lines = lines.map { line -> line.toOcrTextLine() },
        )

    private fun Text.Line.toOcrTextLine(): OcrTextLine =
        OcrTextLine(
            text = text,
            bounds = boundingBox.toOcrTextBounds(),
            elements = elements.map { element -> element.toOcrTextElement() },
        )

    private fun Text.Element.toOcrTextElement(): OcrTextElement =
        OcrTextElement(
            text = text,
            bounds = boundingBox.toOcrTextBounds(),
        )

    private fun Rect?.toOcrTextBounds(): OcrTextBounds? =
        this?.let { rect ->
            OcrTextBounds(
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
            )
        }

    private companion object {
        const val RECOGNIZER_TIMEOUT_MILLIS = 2_500L
    }
}

internal object OcrTextLayoutFormatter {
    fun mergeRecognizedBlocks(recognizerBlocks: List<List<OcrTextBlock>>): List<OcrTextBlock> {
        val seenLines = linkedSetOf<String>()
        val outputBlocks = mutableListOf<OcrTextBlock>()
        recognizerBlocks.forEach { blocks ->
            blocks.forEach { block ->
                val lines = block
                    .lines
                    .mapNotNull { line ->
                        line.normalizedOcrLine()
                            .takeIf { normalized -> normalized.isNotBlank() }
                            ?.takeIf(seenLines::add)
                            ?.let { normalized ->
                                line.copy(
                                    text = normalized,
                                    elements = line.elements.normalizedOcrElements(),
                                )
                            }
                    }
                if (lines.isNotEmpty()) {
                    outputBlocks += block.copy(
                        text = lines.joinToString(separator = "\n") { line -> line.text },
                        lines = lines,
                    )
                }
            }
        }
        return outputBlocks
    }

    fun toPlainText(blocks: List<OcrTextBlock>): String =
        blocks.joinToString(separator = "\n\n") { block ->
            block.lines.joinToString(separator = "\n") { line -> line.text }
        }

    private fun List<OcrTextElement>.normalizedOcrElements(): List<OcrTextElement> =
        mapNotNull { element ->
            element.text.normalizedOcrLine()
                .takeIf { it.isNotBlank() }
                ?.let { normalized -> element.copy(text = normalized) }
        }

    private fun OcrTextLine.normalizedOcrLine(): String =
        text.normalizedOcrLine()

    private fun String.normalizedOcrLine(): String =
        replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { char -> !char.isISOControl() || char == '\n' || char == '\t' }
            .replace(Regex("""[ \t\n]+"""), " ")
            .trim()
}
