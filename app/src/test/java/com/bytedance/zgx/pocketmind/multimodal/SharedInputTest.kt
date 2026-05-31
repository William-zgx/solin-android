package com.bytedance.zgx.pocketmind.multimodal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedInputTest {
    @Test
    fun promptIncludesTextAndAttachmentMetadataWithoutContent() {
        val input = SharedInput(
            text = "请总结这张图",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Image,
                    mimeType = "image/png",
                    displayName = "screen.png",
                    sizeBytes = 42_000L,
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("请总结这张图"))
        assertTrue(prompt.contains("图片"))
        assertTrue(prompt.contains("screen.png"))
        assertTrue(prompt.contains("image/png"))
        assertTrue(prompt.contains("默认只读取元数据"))
        assertFalse(prompt.contains("\n   文本摘录"))
    }

    @Test
    fun promptIncludesTextPreviewForTextAttachment() {
        val input = SharedInput(
            text = "请总结文档",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "text/plain",
                    displayName = "notes.txt",
                    sizeBytes = 120L,
                    textPreview = SharedTextPreview(
                        text = "第一行\n第二行",
                        truncated = true,
                    ),
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("文本摘录（已截断）"))
        assertTrue(prompt.contains("第一行"))
        assertTrue(prompt.contains("第二行"))
    }

    @Test
    fun binaryAndRichDocumentAttachmentsRemainMetadataOnlyWithoutPreview() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/pdf",
                    displayName = "report.pdf",
                    sizeBytes = 42L,
                    textPreview = SharedTextPreview(
                        text = "must not leak",
                        truncated = false,
                    ),
                ),
                SharedAttachment(
                    kind = SharedAttachmentKind.Image,
                    mimeType = "image/png",
                    displayName = "screen.png",
                    sizeBytes = 43L,
                ),
                SharedAttachment(
                    kind = SharedAttachmentKind.Audio,
                    mimeType = "audio/mpeg",
                    displayName = "memo.mp3",
                    sizeBytes = 44L,
                ),
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    displayName = "doc.docx",
                    sizeBytes = 45L,
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("report.pdf"))
        assertTrue(prompt.contains("screen.png"))
        assertTrue(prompt.contains("memo.mp3"))
        assertTrue(prompt.contains("doc.docx"))
        assertFalse(prompt.contains("\n   文本摘录"))
        assertFalse(prompt.contains("must not leak"))
    }

    @Test
    fun promptUsesSafeAttachmentNameOnly() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/pdf",
                    displayName = "/storage/emulated/0/Download/private/report.pdf\u0000",
                    sizeBytes = 12_000L,
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("report.pdf"))
        assertFalse(prompt.contains("/storage"))
        assertFalse(prompt.contains("Download/private"))
        assertFalse(prompt.contains("\u0000"))
    }

    @Test
    fun attachmentOnlyShareBuildsPrompt() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/pdf",
                    displayName = "report.pdf",
                    sizeBytes = null,
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertFalse(input.isEmpty)
        assertTrue(prompt.startsWith("请根据我分享的附件信息"))
        assertTrue(prompt.contains("report.pdf"))
        assertTrue(prompt.contains("未知大小"))
    }

    @Test
    fun mapsMimeTypesToAttachmentKinds() {
        assertEquals(SharedAttachmentKind.Image, sharedAttachmentKindFor("image/jpeg"))
        assertEquals(SharedAttachmentKind.Image, sharedAttachmentKindFor(" IMAGE/PNG "))
        assertEquals(SharedAttachmentKind.Audio, sharedAttachmentKindFor("audio/mpeg"))
        assertEquals(SharedAttachmentKind.Video, sharedAttachmentKindFor("video/mp4"))
        assertEquals(SharedAttachmentKind.Document, sharedAttachmentKindFor("application/pdf"))
        assertEquals(SharedAttachmentKind.Document, sharedAttachmentKindFor("application/msword"))
        assertEquals(
            SharedAttachmentKind.Document,
            sharedAttachmentKindFor("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        )
        assertEquals(
            SharedAttachmentKind.Document,
            sharedAttachmentKindFor("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        )
        assertEquals(SharedAttachmentKind.Document, sharedAttachmentKindFor("text/plain"))
        assertEquals(SharedAttachmentKind.Document, sharedAttachmentKindFor("text/plain; charset=utf-8"))
        assertEquals(SharedAttachmentKind.Other, sharedAttachmentKindFor("application/octet-stream"))
    }

    @Test
    fun textPreviewReaderOnlyAllowsTextMediaTypes() {
        assertTrue(canReadTextPreviewFor("text/plain"))
        assertTrue(canReadTextPreviewFor(" text/markdown; charset=utf-8 "))
        assertFalse(canReadTextPreviewFor("application/json"))
        assertFalse(canReadTextPreviewFor("application/pdf"))
        assertFalse(canReadTextPreviewFor("image/png"))
        assertFalse(canReadTextPreviewFor("audio/mpeg"))
        assertFalse(canReadTextPreviewFor("video/mp4"))
        assertFalse(canReadTextPreviewFor("application/octet-stream"))
        assertFalse(canReadTextPreviewFor(null))
    }

    @Test
    fun textPreviewReaderCleansControlsAndNormalizesNewlines() {
        val preview = TextAttachmentPreviewReader.read(
            "第一\r第二\r\n第三\u0000\u0007\t尾\n\n\n\n结束"
                .byteInputStream(),
        )

        assertNotNull(preview)
        assertEquals("第一\n第二\n第三\t尾\n\n结束", preview!!.text)
        assertFalse(preview.truncated)
    }

    @Test
    fun textPreviewReaderTruncatesByByteAndCharacterLimits() {
        val preview = TextAttachmentPreviewReader.read(
            ByteArray(16 * 1024 + 1) { 'a'.code.toByte() }
                .inputStream(),
        )

        assertNotNull(preview)
        assertEquals(4_000, preview!!.text.length)
        assertTrue(preview.truncated)
    }
}
