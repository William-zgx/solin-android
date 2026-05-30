package com.bytedance.zgx.pocketmind.multimodal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(prompt.contains("当前版本只读取元数据"))
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
        assertEquals(SharedAttachmentKind.Other, sharedAttachmentKindFor("application/octet-stream"))
    }
}
