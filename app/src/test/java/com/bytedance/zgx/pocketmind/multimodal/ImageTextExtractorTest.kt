package com.bytedance.zgx.pocketmind.multimodal

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTextExtractorTest {
    @Test
    fun formatterPreservesBlockAndLineOrder() {
        val blocks = OcrTextLayoutFormatter.mergeRecognizedBlocks(
            listOf(
                listOf(
                    block(line("收据"), line("合计 42 元")),
                    block(line("商户"), line("谢谢惠顾")),
                ),
            ),
        )

        assertEquals("收据\n合计 42 元\n\n商户\n谢谢惠顾", OcrTextLayoutFormatter.toPlainText(blocks))
    }

    @Test
    fun formatterDeduplicatesStableLinesAcrossRecognizers() {
        val blocks = OcrTextLayoutFormatter.mergeRecognizedBlocks(
            listOf(
                listOf(
                    block(line("标题"), line("正文 A")),
                    block(line("页脚")),
                ),
                listOf(
                    block(line("标题"), line("正文 A"), line("正文 B")),
                    block(line("页脚"), line("附注")),
                ),
            ),
        )

        assertEquals("标题\n正文 A\n\n页脚\n\n正文 B\n\n附注", OcrTextLayoutFormatter.toPlainText(blocks))
    }

    @Test
    fun formatterCleansControlCharactersAndBlankLines() {
        val blocks = OcrTextLayoutFormatter.mergeRecognizedBlocks(
            listOf(
                listOf(
                    block(line(" 第一\r\n行\u0000 "), line("\t第二   行"), line(" ")),
                    block(line("\u0007"), line("第三\r行")),
                ),
            ),
        )

        assertEquals("第一 行\n第二 行\n\n第三 行", OcrTextLayoutFormatter.toPlainText(blocks))
    }

    @Test
    fun formatterKeepsBoundsAndElementTextInBlocksJson() {
        val bounds = OcrTextBounds(left = 1, top = 2, right = 30, bottom = 12)
        val blocks = OcrTextLayoutFormatter.mergeRecognizedBlocks(
            listOf(
                listOf(
                    block(
                        line(
                            "合计 42 元",
                            bounds = bounds,
                            elements = listOf(
                                element("合计", bounds),
                                element("42", OcrTextBounds(left = 10, top = 2, right = 18, bottom = 12)),
                            ),
                        ),
                        bounds = bounds,
                    ),
                ),
            ),
        )

        val blockJson = JSONArray(blocks.toOcrBlocksJsonString()).getJSONObject(0)
        val lineJson = blockJson.getJSONArray("lines").getJSONObject(0)
        val elementJson = lineJson.getJSONArray("elements").getJSONObject(1)

        assertEquals("合计 42 元", blockJson.getString("text"))
        assertEquals(1, blockJson.getJSONObject("bounds").getInt("left"))
        assertEquals("合计 42 元", lineJson.getString("text"))
        assertEquals(30, lineJson.getJSONObject("bounds").getInt("right"))
        assertEquals("42", elementJson.getString("text"))
        assertEquals(10, elementJson.getJSONObject("bounds").getInt("left"))
    }

    private fun block(vararg lines: OcrTextLine, bounds: OcrTextBounds? = null): OcrTextBlock =
        OcrTextBlock(
            text = lines.joinToString(separator = "\n") { line -> line.text },
            bounds = bounds,
            lines = lines.toList(),
        )

    private fun line(
        text: String,
        bounds: OcrTextBounds? = null,
        elements: List<OcrTextElement> = emptyList(),
    ): OcrTextLine =
        OcrTextLine(text = text, bounds = bounds, elements = elements)

    private fun element(text: String, bounds: OcrTextBounds? = null): OcrTextElement =
        OcrTextElement(text = text, bounds = bounds)
}
