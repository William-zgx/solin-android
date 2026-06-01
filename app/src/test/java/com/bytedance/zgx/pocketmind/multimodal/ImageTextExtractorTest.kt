package com.bytedance.zgx.pocketmind.multimodal

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTextExtractorTest {
    @Test
    fun formatterPreservesBlockAndLineOrder() {
        val text = OcrTextLayoutFormatter.mergeRecognizedBlocks(
            listOf(
                listOf(
                    listOf("收据", "合计 42 元"),
                    listOf("商户", "谢谢惠顾"),
                ),
            ),
        )

        assertEquals("收据\n合计 42 元\n\n商户\n谢谢惠顾", text)
    }

    @Test
    fun formatterDeduplicatesStableLinesAcrossRecognizers() {
        val text = OcrTextLayoutFormatter.mergeRecognizedBlocks(
            listOf(
                listOf(
                    listOf("标题", "正文 A"),
                    listOf("页脚"),
                ),
                listOf(
                    listOf("标题", "正文 A", "正文 B"),
                    listOf("页脚", "附注"),
                ),
            ),
        )

        assertEquals("标题\n正文 A\n\n页脚\n\n正文 B\n\n附注", text)
    }

    @Test
    fun formatterCleansControlCharactersAndBlankLines() {
        val text = OcrTextLayoutFormatter.mergeRecognizedBlocks(
            listOf(
                listOf(
                    listOf(" 第一\r\n行\u0000 ", "\t第二   行", " "),
                    listOf("\u0007", "第三\r行"),
                ),
            ),
        )

        assertEquals("第一 行\n第二 行\n\n第三 行", text)
    }
}
