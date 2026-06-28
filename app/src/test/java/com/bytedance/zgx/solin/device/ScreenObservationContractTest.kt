package com.bytedance.zgx.solin.device

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.multimodal.OcrTextBlock
import com.bytedance.zgx.solin.multimodal.OcrTextBounds
import com.bytedance.zgx.solin.multimodal.OcrTextElement
import com.bytedance.zgx.solin.multimodal.OcrTextLine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenObservationContractTest {
    @Test
    fun screenSnapshotSerializesLocalOnlyStructuredObservation() {
        val snapshot = ScreenStateSnapshot(
            id = "screen-1",
            packageName = "com.example",
            capturedAtMillis = 42L,
            nodes = listOf(
                node(
                    id = "n1",
                    text = "搜索",
                    bounds = ScreenBounds(left = 1, top = 2, right = 101, bottom = 42),
                    clickable = true,
                ),
                node(
                    id = "n2",
                    text = "关键词",
                    editable = true,
                ),
            ),
            textSummary = "搜索\n关键词",
            truncated = true,
        )

        val json = JSONObject(snapshot.toScreenObservationJsonString())
        val elements = json.getJSONArray("elements")
        val first = elements.getJSONObject(0)

        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals("screen-1", json.getString("observationId"))
        assertEquals("com.example", json.getString("packageName"))
        assertEquals(MessagePrivacy.LocalOnly.name, json.getString("privacyLevel"))
        assertEquals("accessibility", json.getJSONArray("sources").getString(0))
        assertEquals(2, json.getInt("elementCount"))
        assertEquals(2, json.getJSONObject("sourceCounts").getInt("accessibility"))
        assertTrue(json.getBoolean("truncated"))
        assertEquals("n1", first.getString("id"))
        assertEquals("accessibility", first.getString("source"))
        assertEquals("搜索", first.getString("text"))
        assertEquals("button", first.getString("role"))
        assertTrue(first.getJSONObject("clickability").getBoolean("clickable"))
        assertEquals(1, first.getJSONObject("bounds").getInt("left"))
        assertEquals(1.0, first.getDouble("confidence"), 0.0)
        assertEquals(MessagePrivacy.LocalOnly.name, first.getString("privacyLevel"))
    }

    @Test
    fun screenObservationDoesNotExposePixelOrCaptureMetadata() {
        val json = JSONObject(
            ScreenStateSnapshot(
                id = "screen-2",
                packageName = null,
                capturedAtMillis = 1L,
                nodes = listOf(node(id = "n1", text = "仅文本")),
                textSummary = "仅文本",
                truncated = false,
            ).toScreenObservationJsonString(),
        )
        val text = json.toString()

        assertFalse(text.contains("pixels"))
        assertFalse(text.contains("uri"))
        assertFalse(text.contains("path"))
        assertFalse(text.contains("windowTitle"))
        assertEquals(0, json.getJSONArray("elements").getJSONObject(0).getJSONArray("sensitiveFlags").length())
    }

    @Test
    fun screenObservationRedactsSensitiveElementText() {
        val rawSecret = "pa55w0rd-token"
        val jsonString = ScreenStateSnapshot(
            id = "screen-3",
            packageName = "com.example",
            capturedAtMillis = 1L,
            nodes = listOf(
                node(
                    id = "password-field",
                    text = rawSecret,
                    contentDescription = "Password",
                    className = "android.widget.EditText",
                    editable = true,
                ),
            ),
            textSummary = rawSecret,
            truncated = false,
        ).toScreenObservationJsonString()
        val element = JSONObject(jsonString).getJSONArray("elements").getJSONObject(0)

        assertFalse(jsonString.contains(rawSecret))
        assertEquals("", element.getString("text"))
        assertEquals("credential", element.getJSONArray("sensitiveFlags").getString(0))
        assertFalse(element.has("contentDescription"))
        assertFalse(element.has("className"))
    }

    @Test
    fun screenObservationSerializesOcrBlocksAsLocalOnlyStructuredElements() {
        val sensitiveOcrText = "验证码 123456"
        val snapshot = ScreenStateSnapshot(
            id = "screen-4",
            packageName = "com.xunmeng.pinduoduo",
            capturedAtMillis = 7L,
            nodes = listOf(
                node(
                    id = "blank-search-entry",
                    bounds = ScreenBounds(left = 60, top = 88, right = 900, bottom = 170),
                    clickable = true,
                ),
            ),
            textSummary = "",
            truncated = false,
        )
        val ocrBlocks = listOf(
            OcrTextBlock(
                text = "搜索商品 多多搜索",
                bounds = OcrTextBounds(left = 60, top = 88, right = 900, bottom = 170),
                lines = listOf(
                    OcrTextLine(
                        text = "搜索商品 多多搜索",
                        bounds = OcrTextBounds(left = 84, top = 102, right = 620, bottom = 148),
                        elements = listOf(
                            OcrTextElement(
                                text = "搜索商品",
                                bounds = OcrTextBounds(left = 84, top = 102, right = 288, bottom = 148),
                            ),
                        ),
                    ),
                ),
            ),
            OcrTextBlock(
                text = sensitiveOcrText,
                bounds = OcrTextBounds(left = 80, top = 400, right = 460, bottom = 460),
            ),
        )

        val jsonString = snapshot.toScreenObservationJsonString(ocrBlocks = ocrBlocks)
        val json = JSONObject(jsonString)
        val block = json.elementById("ocr:block:0")
        val line = json.elementById("ocr:block:0:line:0")
        val element = json.elementById("ocr:block:0:line:0:element:0")
        val sensitive = json.elementById("ocr:block:1")

        assertEquals("accessibility", json.getJSONArray("sources").getString(0))
        assertEquals("ocr", json.getJSONArray("sources").getString(1))
        assertEquals(1, json.getJSONObject("sourceCounts").getInt("accessibility"))
        assertEquals(4, json.getJSONObject("sourceCounts").getInt("ocr"))
        assertEquals("ocr", block.getString("source"))
        assertEquals("ocr_block", block.getString("role"))
        assertEquals("搜索商品 多多搜索", block.getString("text"))
        assertEquals(60, block.getJSONObject("bounds").getInt("left"))
        assertEquals(false, block.getJSONObject("clickability").getBoolean("clickable"))
        assertEquals(0.72, block.getDouble("confidence"), 0.0)
        assertEquals(MessagePrivacy.LocalOnly.name, block.getString("privacyLevel"))
        assertEquals("ocr_line", line.getString("role"))
        assertEquals("ocr_element", element.getString("role"))
        assertEquals("", sensitive.getString("text"))
        assertEquals("credential", sensitive.getJSONArray("sensitiveFlags").getString(0))
        assertFalse(jsonString.contains(sensitiveOcrText))
        assertFalse(jsonString.contains("pixels"))
        assertFalse(jsonString.contains("uri"))
        assertFalse(jsonString.contains("path"))
    }

    @Test
    fun screenObservationJsonParsesBackToLocalOnlyObservationModel() {
        val jsonString = ScreenStateSnapshot(
            id = "screen-round-trip",
            packageName = "com.taobao.taobao",
            capturedAtMillis = 9L,
            nodes = listOf(
                node(
                    id = "blank-search-entry",
                    bounds = ScreenBounds(left = 60, top = 88, right = 900, bottom = 170),
                    clickable = true,
                ),
            ),
            textSummary = "",
            truncated = false,
        ).toScreenObservationJsonString(
            ocrBlocks = listOf(
                OcrTextBlock(
                    text = "搜索商品",
                    bounds = OcrTextBounds(left = 80, top = 100, right = 320, bottom = 148),
                ),
            ),
        )

        val observation = requireNotNull(screenObservationFromJsonStringOrNull(jsonString))

        assertEquals("screen-round-trip", observation.observationId)
        assertEquals("com.taobao.taobao", observation.packageName)
        assertEquals(MessagePrivacy.LocalOnly, observation.privacyLevel)
        assertEquals(listOf("accessibility", "ocr"), observation.sources)
        assertEquals(2, observation.elements.size)
        assertEquals("blank-search-entry", observation.elements[0].id)
        assertTrue(observation.elements[0].clickability.clickable)
        assertEquals(ScreenBounds(left = 60, top = 88, right = 900, bottom = 170), observation.elements[0].bounds)
        assertEquals("ocr:block:0", observation.elements[1].id)
        assertEquals("搜索商品", observation.elements[1].text)
        assertEquals(MessagePrivacy.LocalOnly, observation.elements[1].privacyLevel)
    }

    private fun node(
        id: String,
        text: String = "",
        contentDescription: String = "",
        className: String = "android.widget.TextView",
        bounds: ScreenBounds? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
    ): ScreenNode =
        ScreenNode(
            id = id,
            text = text,
            contentDescription = contentDescription,
            className = className,
            bounds = bounds,
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            enabled = true,
        )

    private fun JSONObject.elementById(id: String): JSONObject {
        val elements = getJSONArray("elements")
        for (index in 0 until elements.length()) {
            val element = elements.getJSONObject(index)
            if (element.getString("id") == id) return element
        }
        error("Missing observation element: $id")
    }
}
