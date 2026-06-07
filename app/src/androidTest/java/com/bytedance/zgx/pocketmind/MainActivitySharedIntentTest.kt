package com.bytedance.zgx.pocketmind

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bytedance.zgx.pocketmind.debug.CountingSharedContentProvider
import com.bytedance.zgx.pocketmind.multimodal.ImageTextExtractor
import com.bytedance.zgx.pocketmind.multimodal.ShareIntentReader
import com.bytedance.zgx.pocketmind.multimodal.SharedInputReadMode
import com.bytedance.zgx.pocketmind.multimodal.SharedTextPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainActivitySharedIntentTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun actionSendTextIsStagedThroughActivityShareEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetMainActivityPersistentState(context, inferenceMode = InferenceMode.Local)

        val sharedText = "Shared ACTION_SEND text from Activity test 42"
        val launchIntent = Intent(Intent.ACTION_SEND).apply {
            setClass(context, MainActivity::class.java)
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sharedText)
            putExtra(MainActivity.EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, true)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use {
            composeRule.waitForTag("app_title")
            composeRule.waitForTag("pending_shared_input_strip")
            composeRule.waitForText("已接收分享内容", substring = true)

            composeRule.onNodeWithTag("app_title").assertIsDisplayed()
            composeRule.onNodeWithTag("pending_shared_input_strip")
                .assertIsDisplayed()
            composeRule.onAllNodesWithText("文本", substring = true)
                .onFirst()
                .assertIsDisplayed()
            composeRule.onAllNodesWithText("已接收分享内容", substring = true)
                .onFirst()
                .assertIsDisplayed()
            composeRule.assertTextAbsent(sharedText)
        }
    }

    @Test
    fun actionSendTextUsesProtectedSignalWhenActivityStartsInRemoteMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetMainActivityPersistentState(
            context,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = ReadyRemoteModelConfig,
        )

        val protectedText = "REMOTE_SHARE_SENTINEL_should_not_render_73"
        val launchIntent = Intent(Intent.ACTION_SEND).apply {
            setClass(context, MainActivity::class.java)
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, protectedText)
            putExtra(MainActivity.EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, true)
            putExtra(MainActivity.EXTRA_DEBUG_SCREENSHOT_REMOTE_BASE_URL, ReadyRemoteModelConfig.baseUrl)
            putExtra(MainActivity.EXTRA_DEBUG_SCREENSHOT_REMOTE_MODEL_NAME, ReadyRemoteModelConfig.modelName)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use {
            composeRule.waitForTag("app_title")
            composeRule.waitForText("不会读取或自动发送分享文本", substring = true)

            composeRule.onNodeWithText("不会读取或自动发送分享文本", substring = true)
                .assertIsDisplayed()
            composeRule.assertTextAbsent(protectedText)
        }
    }

    @Test
    fun actionSendImageIsStagedThroughActivityShareEntryWhenRemoteVisionIsEnabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetMainActivityPersistentState(
            context,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = ReadyRemoteModelConfig,
        )

        val imageUri = createSharedPngUri(context, "remote-vision-image.png")
        val launchIntent = Intent(Intent.ACTION_SEND).apply {
            setClass(context, MainActivity::class.java)
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(MainActivity.EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, true)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use {
            composeRule.waitForTag("app_title")
            composeRule.waitForTag("pending_shared_input_strip")

            composeRule.onNodeWithTag("pending_shared_input_strip")
                .assertIsDisplayed()
            composeRule.onAllNodesWithText("图片", substring = true)
                .onFirst()
                .assertIsDisplayed()
            composeRule.assertTextAbsent("data:image")
        }
    }

    @Test
    fun actionSendImageInUnconfiguredRemoteModeShowsConfigNoticeWithoutReadingImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CountingSharedContentProvider.resetCounters(context)
        resetMainActivityPersistentState(context, inferenceMode = InferenceMode.Remote)

        val launchIntent = Intent(Intent.ACTION_SEND).apply {
            setClass(context, MainActivity::class.java)
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, CountingSharedContentProvider.imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(MainActivity.EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, true)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use {
            composeRule.waitForTag("app_title")
            composeRule.waitForText("配置远程模型地址和模型名", substring = true)

            composeRule.onNodeWithText("配置远程模型地址和模型名", substring = true)
                .assertIsDisplayed()
            composeRule.assertTagAbsent("pending_shared_input_strip")
            composeRule.assertTextAbsent("counting-image.png")
            composeRule.assertTextAbsent("data:image")
            assertEquals(0, CountingSharedContentProvider.imageOpenCount(context))
            assertEquals(0, CountingSharedContentProvider.textOpenCount(context))
        }
    }

    @Test
    fun actionSendImageInRemoteModeWithVisionDisabledShowsUnsupportedNoticeWithoutReadingImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CountingSharedContentProvider.resetCounters(context)
        val remoteWithoutVision = ReadyRemoteModelConfig.copy(supportsVisionInput = false)
        resetMainActivityPersistentState(
            context,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = remoteWithoutVision,
        )

        val launchIntent = Intent(Intent.ACTION_SEND).apply {
            setClass(context, MainActivity::class.java)
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, CountingSharedContentProvider.imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(MainActivity.EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, true)
            putExtra(MainActivity.EXTRA_DEBUG_SCREENSHOT_REMOTE_BASE_URL, remoteWithoutVision.baseUrl)
            putExtra(MainActivity.EXTRA_DEBUG_SCREENSHOT_REMOTE_MODEL_NAME, remoteWithoutVision.modelName)
            putExtra(
                MainActivity.EXTRA_DEBUG_SCREENSHOT_REMOTE_SUPPORTS_VISION_INPUT,
                remoteWithoutVision.supportsVisionInput,
            )
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use {
            composeRule.waitForTag("app_title")
            composeRule.waitForText("当前远程模型未启用图片输入能力", substring = true)

            composeRule.onNodeWithText("未读取、OCR 或发送图片", substring = true)
                .assertIsDisplayed()
            composeRule.assertTagAbsent("pending_shared_input_strip")
            composeRule.assertTextAbsent("counting-image.png")
            composeRule.assertTextAbsent("data:image")
            assertEquals(0, CountingSharedContentProvider.imageOpenCount(context))
            assertEquals(0, CountingSharedContentProvider.textOpenCount(context))
        }
    }

    @Test
    fun shareIntentReaderRemoteVisionReadsOnlyImageBytesAndProtectsNonImageAttachment() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CountingSharedContentProvider.resetCounters(context)
        val imageTextExtractor = CountingImageTextExtractor()

        val sharedInput = ShareIntentReader(
            context = context,
            imageTextExtractor = imageTextExtractor,
        ).readUris(
            uris = listOf(
                CountingSharedContentProvider.imageUri,
                CountingSharedContentProvider.textUri,
            ),
            intentMimeType = "*/*",
            mode = SharedInputReadMode.RemoteVision,
        )

        requireNotNull(sharedInput)
        assertEquals(1, CountingSharedContentProvider.imageOpenCount(context))
        assertEquals(0, CountingSharedContentProvider.textOpenCount(context))
        assertEquals(0, imageTextExtractor.uriCallCount)
        assertEquals(0, imageTextExtractor.bitmapCallCount)
        assertEquals(1, sharedInput.attachments.size)
        assertEquals(1, sharedInput.protectedSourceCount)
        assertEquals(0, sharedInput.protectedImageSourceCount)
        val imageAttachment = requireNotNull(sharedInput.attachments.single().imageAttachment)
        assertEquals("image/png", imageAttachment.mimeType)
        assertEquals("data:image/png;base64,$TINY_PNG_BASE64", imageAttachment.dataUrl)
        val prompt = sharedInput.toRemoteVisionPrompt()
        assertTrue(prompt.contains("已附加 1 张图片"))
        assertTrue(prompt.contains("1 个非图片或分享来源已被保护"))
        assertFalse(prompt.contains("counting-image.png"))
        assertFalse(prompt.contains("private-notes.txt"))
        assertFalse(prompt.contains("PRIVATE_NOTES_SHOULD_NOT_BE_READ"))
    }

    @Test
    fun actionSendImageUnsupportedByRemoteVisionProducesProtectedSignalWithoutReadingImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CountingSharedContentProvider.resetCounters(context)
        val imageTextExtractor = CountingImageTextExtractor()
        val sharedIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, CountingSharedContentProvider.imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val sharedInput = ShareIntentReader(
            context = context,
            imageTextExtractor = imageTextExtractor,
        ).read(
            sharedIntent,
            mode = SharedInputReadMode.RemoteVisionUnsupportedSignal,
        )

        requireNotNull(sharedInput)
        assertEquals(0, CountingSharedContentProvider.imageOpenCount(context))
        assertEquals(0, CountingSharedContentProvider.textOpenCount(context))
        assertEquals(0, imageTextExtractor.uriCallCount)
        assertEquals(0, imageTextExtractor.bitmapCallCount)
        assertEquals(0, sharedInput.protectedSourceCount)
        assertEquals(1, sharedInput.protectedImageSourceCount)
        assertTrue(sharedInput.attachments.isEmpty())
        assertTrue(sharedInput.text.isBlank())
        assertFalse(sharedInput.toPrompt().contains("counting-image.png"))
        assertFalse(sharedInput.toPrompt().contains(CountingSharedContentProvider.imageUri.toString()))
        assertFalse(sharedInput.toPrompt().contains("data:image"))
    }

    private fun ComposeTestRule.waitForTag(tag: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeTestRule.waitForText(
        text: String,
        timeoutMillis: Long = 10_000,
        substring: Boolean = false,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeTestRule.assertTextAbsent(text: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun ComposeTestRule.assertTagAbsent(tag: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun createSharedPngUri(context: Context, fileName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PocketMindTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
            "Failed to create shared image URI"
        }
        resolver.openOutputStream(uri)?.use { output ->
            output.write(Base64.decode(TINY_PNG_BASE64, Base64.DEFAULT))
        }
        val publishValues = ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }
        resolver.update(uri, publishValues, null, null)
        return uri
    }

    private class CountingImageTextExtractor : ImageTextExtractor {
        var uriCallCount: Int = 0
            private set
        var bitmapCallCount: Int = 0
            private set

        override fun extract(uri: Uri): SharedTextPreview? {
            uriCallCount += 1
            return null
        }

        override fun extract(bitmap: Bitmap): SharedTextPreview? {
            bitmapCallCount += 1
            return null
        }
    }

    private companion object {
        const val TINY_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
    }
}
