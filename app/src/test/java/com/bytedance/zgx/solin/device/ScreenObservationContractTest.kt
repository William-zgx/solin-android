package com.bytedance.zgx.solin.device

import com.bytedance.zgx.solin.MessagePrivacy
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
}
