package com.bytedance.zgx.solin

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
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
import com.bytedance.zgx.solin.debug.CountingSharedContentProvider
import com.bytedance.zgx.solin.multimodal.ImageTextExtractor
import com.bytedance.zgx.solin.multimodal.ShareIntentReader
import com.bytedance.zgx.solin.multimodal.SharedInputReadMode
import com.bytedance.zgx.solin.multimodal.SharedInputSource
import com.bytedance.zgx.solin.multimodal.SharedInputSourcePrivacy
import com.bytedance.zgx.solin.multimodal.SharedTextPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MainActivitySharedIntentTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Before
    fun grantMediaPermissions() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        uiAutomation.grantRuntimePermission(
            packageName,
            android.Manifest.permission.READ_MEDIA_IMAGES,
        )
    }

    @Test
    fun shareIntentConsumptionKeyIsStableForRecreateButChangesForNewShare() {
        val first = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "same share")
        }
        val recreated = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "same share")
        }
        val nextShare = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "different share")
        }

        assertEquals(first.shareIntentConsumptionKey(), recreated.shareIntentConsumptionKey())
        assertNotEquals(first.shareIntentConsumptionKey(), nextShare.shareIntentConsumptionKey())
        assertNotNull(first.shareIntentConsumptionKey())
        assertNull(Intent(Intent.ACTION_VIEW).shareIntentConsumptionKey())
    }

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

            composeRule.onNodeWithTag("app_title").assertIsDisplayed()
            composeRule.onNodeWithTag("pending_shared_input_strip")
                .assertIsDisplayed()
            composeRule.onAllNodesWithText("文本", substring = true)
                .onFirst()
                .assertIsDisplayed()
            composeRule.assertTextAbsent(sharedText)
        }
    }

    @Test
    fun actionSendTextReadingAcrossActivityRecreateIsIngestedOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CountingSharedContentProvider.resetCounters(context)
        CountingSharedContentProvider.blockNextOpen(context)
        resetMainActivityPersistentState(context, inferenceMode = InferenceMode.Local)

        val launchIntent = Intent(Intent.ACTION_SEND).apply {
            setClass(context, MainActivity::class.java)
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, CountingSharedContentProvider.textUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(MainActivity.EXTRA_SKIP_STARTUP_MODEL_RUNTIME_WORK, true)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            try {
                composeRule.waitUntil(timeoutMillis = 5_000) {
                    CountingSharedContentProvider.blockedOpenHasStarted(context)
                }
                scenario.recreate()
            } finally {
                CountingSharedContentProvider.releaseBlockedOpen(context)
            }

            composeRule.waitForTag("app_title")
            composeRule.waitForTag("pending_shared_input_strip")

            composeRule.onNodeWithTag("pending_shared_input_strip")
                .assertIsDisplayed()
            assertEquals(1, CountingSharedContentProvider.textOpenCount(context))
            assertEquals(0, CountingSharedContentProvider.imageOpenCount(context))
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
            composeRule.waitForText("只在本机处理，不会自动发送", substring = true)

            composeRule.onNodeWithText("只在本机处理，不会自动发送", substring = true)
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
    fun actionSendImageInUnconfiguredRemoteModeReadsLocallyButDoesNotSend() {
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
            assertEquals(1, CountingSharedContentProvider.imageOpenCount(context))
            assertEquals(0, CountingSharedContentProvider.textOpenCount(context))
        }
    }

    @Test
    fun actionSendImageInRemoteModeWithVisionDisabledReadsLocallyButDoesNotSend() {
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

            composeRule.onNodeWithText("未执行 OCR 或发送图片", substring = true)
                .assertIsDisplayed()
            composeRule.assertTagAbsent("pending_shared_input_strip")
            composeRule.assertTextAbsent("counting-image.png")
            composeRule.assertTextAbsent("data:image")
            assertEquals(1, CountingSharedContentProvider.imageOpenCount(context))
            assertEquals(0, CountingSharedContentProvider.textOpenCount(context))
        }
    }

    @Test
    fun shareIntentReaderRemoteVisionReadsOnlyImageBytesAndProtectsNonImageAttachment() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CountingSharedContentProvider.resetCounters(context)
        val imageTextExtractor = CountingImageTextExtractor()

        // Read the expected PNG bytes directly from the provider (generated at runtime
        // for compatibility with the device's BitmapFactory implementation).
        val expectedPngBytes = context.contentResolver
            .openInputStream(CountingSharedContentProvider.imageUri)?.use { it.readBytes() }
        assertNotNull("Provider should deliver non-empty PNG bytes", expectedPngBytes)
        val expectedBase64 = android.util.Base64.encodeToString(
            expectedPngBytes, android.util.Base64.NO_WRAP,
        )
        // Reset counters after the diagnostic read.
        CountingSharedContentProvider.resetCounters(context)

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
        assertEquals("data:image/png;base64,$expectedBase64", imageAttachment.dataUrl)
        val prompt = sharedInput.toRemoteVisionPrompt()
        assertTrue(prompt.contains("已附加 1 张图片"))
        assertTrue(prompt.contains("1 个非图片或分享来源已被保护"))
        assertFalse(prompt.contains("counting-image.png"))
        assertFalse(prompt.contains("private-notes.txt"))
        assertFalse(prompt.contains("PRIVATE_NOTES_SHOULD_NOT_BE_READ"))
    }

    @Test
    fun destinationNeutralReaderUsesOneReadAndStableImagePrivacyAcrossInferenceModes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        InferenceMode.entries.forEach { inferenceMode ->
            CountingSharedContentProvider.resetCounters(context)
            val readMode = sharedInputReadModeFor(
                inferenceMode = inferenceMode,
                localSupportsVisionInput = inferenceMode == InferenceMode.Local,
            )
            val sharedInput = ShareIntentReader(
                context = context,
                imageTextExtractor = CountingImageTextExtractor(),
            ).readUris(
                uris = listOf(CountingSharedContentProvider.imageUri),
                intentMimeType = "image/png",
                mode = readMode,
            )

            assertEquals(SharedInputReadMode.DestinationNeutralVision, readMode)
            requireNotNull(sharedInput)
            assertEquals(
                listOf(
                    SharedInputSourcePrivacy(
                        SharedInputSource.Image,
                        MessagePrivacy.RemoteEligible,
                        false,
                    ),
                ),
                sharedInput.sourcePrivacy,
            )
            assertNotNull(sharedInput.attachments.single().imageAttachment)
            assertNotNull(sharedInput.attachments.single().localImageAttachment)
            assertEquals(1, CountingSharedContentProvider.imageOpenCount(context))
        }
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
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SolinTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
            "Failed to create shared image URI"
        }
        // Generate a valid 1x1 PNG at runtime for compatibility with the device's
        // BitmapFactory (the hardcoded tiny PNG from base64 is rejected on API 36).
        val pngBytes = java.io.ByteArrayOutputStream().use { out ->
            val bitmap = android.graphics.Bitmap.createBitmap(
                1, 1, android.graphics.Bitmap.Config.ARGB_8888,
            )
            try {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            } finally {
                bitmap.recycle()
            }
            out.toByteArray()
        }
        resolver.openOutputStream(uri)?.use { output ->
            output.write(pngBytes)
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

    private companion object
}
