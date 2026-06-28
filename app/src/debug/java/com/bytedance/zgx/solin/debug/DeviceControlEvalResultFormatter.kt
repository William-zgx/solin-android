package com.bytedance.zgx.solin.debug

import com.bytedance.zgx.solin.device.ScreenBounds
import com.bytedance.zgx.solin.device.UiTargetEvidenceCandidate
import org.json.JSONArray
import org.json.JSONObject

internal object DeviceControlEvalResultFormatter {
    private const val RESULT_FILE_PREFIX = "device_control_eval_result_"
    private const val MAX_RESULT_VALUE_CHARS = 8_000
    private const val MAX_RESULT_ID_CHARS = 120

    fun resultFileName(requestId: String): String =
        "$RESULT_FILE_PREFIX${fileToken(requestId)}.properties"

    fun cleanValue(value: String): String =
        value.replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\u0000', ' ')
            .take(MAX_RESULT_VALUE_CHARS)

    fun fileToken(value: String): String =
        value.map { char ->
            if (
                char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char == '_' ||
                char == '-' ||
                char == '.'
            ) {
                char
            } else {
                '_'
            }
        }
            .joinToString(separator = "")
            .take(MAX_RESULT_ID_CHARS)
            .ifBlank { "missing" }

    fun candidatesJson(candidates: List<UiTargetEvidenceCandidate>): String =
        cleanValue(
            JSONObject()
                .put("candidates", candidates.toJsonArray())
                .toString(),
        )

    private fun List<UiTargetEvidenceCandidate>.toJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { candidate ->
            array.put(
                JSONObject()
                    .put("nodeId", candidate.nodeId.orEmpty())
                    .put("label", candidate.label)
                    .put("bounds", candidate.bounds?.toJson() ?: JSONObject.NULL)
                    .put("clickable", candidate.clickable)
                    .put("editable", candidate.editable)
                    .put("scrollable", candidate.scrollable)
                    .put("enabled", candidate.enabled)
                    .put("matchedProfileHint", candidate.matchedProfileHint.orEmpty())
                    .put("confidence", candidate.score.finalScore)
                    .put("finalScore", candidate.score.finalScore)
                    .put("riskPenalty", candidate.score.riskPenalty)
                    .put("noisePenalty", candidate.score.noisePenalty)
                    .put("totalPenalty", candidate.score.riskPenalty + candidate.score.noisePenalty)
                    .put("reason", candidate.reason)
                    .put(
                        "score",
                        JSONObject()
                            .put("semantic", candidate.score.semanticScore)
                            .put("profileHint", candidate.score.profileHintScore)
                            .put("targetText", candidate.score.targetTextScore)
                            .put("actionability", candidate.score.actionabilityScore)
                            .put("position", candidate.score.positionScore)
                            .put("riskPenalty", candidate.score.riskPenalty)
                            .put("noisePenalty", candidate.score.noisePenalty)
                            .put("final", candidate.score.finalScore),
                    ),
            )
        }
        return array
    }

    private fun ScreenBounds.toJson(): JSONObject =
        JSONObject()
            .put("left", left)
            .put("top", top)
            .put("right", right)
            .put("bottom", bottom)
}
