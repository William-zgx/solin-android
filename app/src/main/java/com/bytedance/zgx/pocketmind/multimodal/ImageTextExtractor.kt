package com.bytedance.zgx.pocketmind.multimodal

import android.content.Context
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
}

object NoOpImageTextExtractor : ImageTextExtractor {
    override fun extract(uri: Uri): SharedTextPreview? = null
}

class MlKitImageTextExtractor(
    private val context: Context,
) : ImageTextExtractor {
    override fun extract(uri: Uri): SharedTextPreview? =
        runCatching {
            val image = InputImage.fromFilePath(context, uri)
            val latinText = runCatching {
                extractText(
                    image = image,
                    recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
                )
            }.getOrNull()
            val chineseText = runCatching {
                extractText(
                    image = image,
                    recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
                )
            }.getOrNull()
            ImageTextPreviewReader.fromText(mergeOcrTexts(listOfNotNull(latinText, chineseText)))
        }.getOrNull()

    private fun extractText(
        image: InputImage,
        recognizer: TextRecognizer,
    ): String =
        try {
            Tasks.await(recognizer.process(image), RECOGNIZER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).text
        } finally {
            recognizer.close()
        }

    private fun mergeOcrTexts(texts: List<String>): String =
        texts
            .asSequence()
            .flatMap { text -> text.lines().asSequence() }
            .map { line -> line.trim() }
            .filter { line -> line.isNotBlank() }
            .distinct()
            .joinToString(separator = "\n")

    private companion object {
        const val RECOGNIZER_TIMEOUT_MILLIS = 2_500L
    }
}
