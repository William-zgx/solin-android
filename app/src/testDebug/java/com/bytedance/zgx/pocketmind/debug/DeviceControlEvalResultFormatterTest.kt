package com.bytedance.zgx.pocketmind.debug

import com.bytedance.zgx.pocketmind.device.ScreenBounds
import com.bytedance.zgx.pocketmind.device.UiTargetEvidenceCandidate
import com.bytedance.zgx.pocketmind.device.UiTargetScoreComponents
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControlEvalResultFormatterTest {
    @Test
    fun candidatesJsonWrapsRankedCandidatesInObjectContract() {
        val json = JSONObject(
            DeviceControlEvalResultFormatter.candidatesJson(
                listOf(
                    UiTargetEvidenceCandidate(
                        nodeId = "top-card",
                        label = "搜索商品",
                        bounds = ScreenBounds(16, 72, 1064, 152),
                        clickable = true,
                        editable = false,
                        scrollable = false,
                        enabled = true,
                        matchedProfileHint = "search_entry",
                        score = UiTargetScoreComponents(
                            semanticScore = 120,
                            profileHintScore = 80,
                            targetTextScore = 60,
                            actionabilityScore = 30,
                            positionScore = 20,
                            riskPenalty = 0,
                            noisePenalty = 5,
                            finalScore = 305,
                        ),
                        reason = "label+profile",
                    ),
                ),
            ),
        )

        val candidates = json.getJSONArray("candidates")
        val first = candidates.getJSONObject(0)
        assertEquals(1, candidates.length())
        assertEquals("top-card", first.getString("nodeId"))
        assertEquals("搜索商品", first.getString("label"))
        assertEquals(16, first.getJSONObject("bounds").getInt("left"))
        assertEquals(305, first.getJSONObject("score").getInt("final"))
    }

    @Test
    fun cleanValueRemovesLineBreaksAndNulBytes() {
        val value = DeviceControlEvalResultFormatter.cleanValue("line1\nline2\rline3\u0000tail")

        assertEquals("line1 line2 line3 tail", value)
    }

    @Test
    fun resultFileNameSanitizesRequestIdForRunAsCat() {
        val fileName = DeviceControlEvalResultFormatter.resultFileName("../tap 搜索\nentry")

        assertEquals("device_control_eval_result_.._tap____entry.properties", fileName)
        assertFalse(fileName.contains("/"))
        assertFalse(fileName.contains("\n"))
        assertTrue(fileName.endsWith(".properties"))
    }
}
