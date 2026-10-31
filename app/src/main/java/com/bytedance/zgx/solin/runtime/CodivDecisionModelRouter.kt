package com.bytedance.zgx.solin.runtime

import com.bytedance.zgx.solin.audit.RemoteSendAuditEvent
import com.bytedance.zgx.solin.audit.RemoteSendAuditSink
import com.bytedance.zgx.solin.audit.RemoteSendDecision
import com.bytedance.zgx.solin.orchestration.DECISION_MODEL_PLACEMENT_CRITERIA
import com.bytedance.zgx.solin.orchestration.DECISION_MODEL_PLACEMENT_INSTRUCTIONS
import com.bytedance.zgx.solin.orchestration.DecisionModelRouter
import com.bytedance.zgx.solin.orchestration.DecisionModelVerdict
import com.bytedance.zgx.solin.orchestration.RunPlacement
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

const val CODIV_BASE_URL = "https://api.codiv.ai/v1"
const val CODIV_DECISION_MODEL = "openjev-0.1"
private const val PLACEMENT_QUESTION_ID = "placement"
private const val DECISION_CALL_TIMEOUT_MILLIS = 1_500L
private const val MAX_ROUTING_TEXT_CHARS = 2_000

/**
 * [DecisionModelRouter] over the Codiv System One endpoint (`POST {base}/systemone`), the same typed
 * choice protocol Focus Agent uses for branch decisions.
 *
 * Sends the model name, `{"request": <text>}` and one choice question; nothing else from the run.
 * The API key comes from [credential] at call time and is never logged. A reply is used only when it
 * matches the declared contract (the declared question, a declared choice, probabilities over the
 * declared options summing to one, confidence in 0..1); anything else is [DecisionModelVerdict.Unavailable].
 * Each send is recorded in the remote-send audit without the text.
 */
class CodivDecisionModelRouter(
    private val credential: () -> String?,
    private val auditSink: RemoteSendAuditSink? = null,
    private val baseUrl: String = CODIV_BASE_URL,
    private val callFactory: Call.Factory = OkHttpClient.Builder()
        .callTimeout(DECISION_CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .build(),
) : DecisionModelRouter {
    override fun classify(requestText: String): DecisionModelVerdict {
        val key = credential()?.trim().orEmpty()
        if (key.isEmpty()) return DecisionModelVerdict.Unavailable("not_configured")
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/systemone")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $key")
            .post(decisionRequestBody(requestText).toString().toRequestBody(JSON))
            .build()
        auditSink?.record(
            RemoteSendAuditEvent(
                decision = RemoteSendDecision.Confirmed,
                modelName = "codiv:$CODIV_DECISION_MODEL",
                summary = "端云路由：本条请求文字发给决策模型，判断由本机还是云端回答。",
            ),
        )
        return runCatching {
            callFactory.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return DecisionModelVerdict.Unavailable("http_${response.code}")
                parseDecisionResponse(response.body.string())
            }
        }.getOrElse { DecisionModelVerdict.Unavailable("transport") }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun decisionRequestBody(requestText: String): JSONObject =
    JSONObject()
        .put("model", CODIV_DECISION_MODEL)
        .put("state", JSONObject().put("request", requestText.take(MAX_ROUTING_TEXT_CHARS)))
        .put(
            "questions",
            JSONObject().put(
                PLACEMENT_QUESTION_ID,
                JSONObject()
                    .put("type", "choice")
                    .put("instructions", DECISION_MODEL_PLACEMENT_INSTRUCTIONS)
                    .put("criteria", JSONObject(DECISION_MODEL_PLACEMENT_CRITERIA)),
            ),
        )

internal fun parseDecisionResponse(raw: String): DecisionModelVerdict {
    val json = runCatching { JSONObject(raw) }.getOrNull()
        ?: return DecisionModelVerdict.Unavailable("not_json")
    val answers = json.optJSONObject("answers") ?: return DecisionModelVerdict.Unavailable("no_answers")
    if (answers.length() != 1) return DecisionModelVerdict.Unavailable("answers_mismatch")
    val answer = answers.optJSONObject(PLACEMENT_QUESTION_ID)
        ?: return DecisionModelVerdict.Unavailable("answers_mismatch")
    if (answer.optString("type") != "choice") return DecisionModelVerdict.Unavailable("type_mismatch")
    val placement = when (answer.optString("choice")) {
        "local" -> RunPlacement.Local
        "remote" -> RunPlacement.Remote
        else -> return DecisionModelVerdict.Unavailable("choice_outside_criteria")
    }
    val probabilities = answer.optJSONObject("probabilities")
        ?: return DecisionModelVerdict.Unavailable("no_probabilities")
    val keys = probabilities.keys().asSequence().toSet()
    if (keys != DECISION_MODEL_PLACEMENT_CRITERIA.keys) return DecisionModelVerdict.Unavailable("probabilities_mismatch")
    val values = keys.map { key -> probabilities.optDouble(key, Double.NaN) }
    if (values.any { it.isNaN() || it < 0.0 || it > 1.0 } || abs(values.sum() - 1.0) > 1e-3) {
        return DecisionModelVerdict.Unavailable("probabilities_invalid")
    }
    val confidence = answer.optDouble("confidence", Double.NaN)
    if (confidence.isNaN() || confidence < 0.0 || confidence > 1.0) {
        return DecisionModelVerdict.Unavailable("confidence_invalid")
    }
    return DecisionModelVerdict.Decided(placement, confidence)
}
