package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.audit.RemoteSendAuditEvent
import com.bytedance.zgx.solin.audit.RemoteSendAuditSink
import com.bytedance.zgx.solin.audit.RemoteSendDecision
import com.bytedance.zgx.solin.device.UiScrollDirection
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_REMOTE
import com.bytedance.zgx.solin.logging.solinD
import com.bytedance.zgx.solin.multimodal.RawScreenshotProvider
import com.bytedance.zgx.solin.multimodal.RawScreenshotReadResult
import com.bytedance.zgx.solin.multimodal.rawScreenshotJpegToChatImageAttachment
import com.bytedance.zgx.solin.runtime.RemoteChatRuntime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val REMOTE_VISION_DECISION_TIMEOUT_MILLIS = 60_000L
private const val REMOTE_VISION_MAX_NORMALIZED = 1_000

/**
 * The strict-JSON decision instruction handed to the remote vision model. It receives the current
 * screenshot as an image attachment plus this prompt, and must reply with a single JSON object:
 * `{"action":"tap","x":<0-1000>,"y":<0-1000>}`, `{"action":"back"}`,
 * `{"action":"scroll","direction":"up|down|left|right"}` or `{"action":"stop"}`. The stop clause
 * mirrors the local action model's dangerous-control guard: the model is told to stop rather than tap
 * on payment/send/delete/publish/purchase/transfer/authorization controls, so those never
 * auto-execute.
 *
 * Deliberately carries ONLY the intent: no prior steps, decisions or screen text. Sending step history
 * would be new egress beyond the disclosed "screenshot + intent" contract; repeated-decision detection
 * is done locally instead (see [RemoteVisionObservationReplanner]).
 */
internal fun remoteVisionDecisionPrompt(intent: String): String =
    """
    你在帮助用户完成手机操作（已获用户授权）。下面附带的是当前手机屏幕的截图。
    用户意图：$intent
    请判断为完成用户意图，下一步应该做什么。
    坐标使用 0-1000 归一化：x 从左(0)到右(1000)，y 从上(0)到下(1000)。
    只输出一个 JSON 对象，不要解释：
    - 需要点击时：{"action":"tap","x":<0-1000整数>,"y":<0-1000整数>}
    - 需要返回上一页或关闭当前浮层时：{"action":"back"}
    - 需要滚动查看更多内容时：{"action":"scroll","direction":"down"}（direction 取 up/down/left/right；down 表示查看下方内容）
    - 任务已完成或无需继续时：{"action":"stop"}
    遇到支付、发送、删除、发布、下单、购买、转账、授权等危险操作控件时，输出 {"action":"stop"}，不要点击。
    """.trimIndent()

/**
 * Android implementation of [RemoteVisionDecider]: captures the current screen (retaining pixels),
 * bridges it to a vision image attachment, sends it to the remote model via [RemoteChatRuntime.send]
 * (which resolves its own API-key credential), and parses the strict-JSON tap/stop decision.
 *
 * Fail-closed throughout: any missing consent, capture failure, misconfiguration, send failure, or
 * unparseable output returns [RemoteVisionDecision.Unavailable] — never a default tap. The screenshot
 * bytes are transient and dropped after the send. This is the ONLY place screen pixels cross the
 * remote boundary in the loop, and it runs only when [RemoteVisionObservationReplanner]'s gate passed.
 *
 * @param configProvider supplies the remote config (need not carry the API key; the runtime resolves
 *   credentials itself). Vision support is re-checked here as defense-in-depth.
 * @param inferenceModeProvider re-checked here so a mode flip between gate and capture fails closed.
 */
class AndroidRemoteVisionDecider(
    private val rawScreenshotProvider: RawScreenshotProvider,
    private val remoteRuntime: RemoteChatRuntime,
    private val configProvider: () -> RemoteModelConfig,
    private val inferenceModeProvider: () -> InferenceMode,
    private val generationParametersProvider: () -> GenerationParameters,
    private val auditSink: RemoteSendAuditSink? = null,
    private val timeoutMillis: Long = REMOTE_VISION_DECISION_TIMEOUT_MILLIS,
    private val logger: (String) -> Unit = { message -> solinD(TAG_REMOTE, message) },
) : RemoteVisionDecider {
    override fun decide(intent: String, requestId: String): RemoteVisionDecision {
        if (inferenceModeProvider() != InferenceMode.Remote) {
            return RemoteVisionDecision.Unavailable("not_remote_mode")
        }
        val config = configProvider().normalized()
        if (!config.supportsVisionInput) return RemoteVisionDecision.Unavailable("vision_unsupported")
        if (!config.isConfigured) return RemoteVisionDecision.Unavailable("remote_not_configured")

        val capture = rawScreenshotProvider.captureCurrentScreenshotRaw(requestId)
        val attachment = when (capture) {
            RawScreenshotReadResult.MissingConsent -> return RemoteVisionDecision.Unavailable("missing_consent")
            is RawScreenshotReadResult.Failed -> {
                logger("remote-vision capture failed: ${capture.reason}")
                return RemoteVisionDecision.Unavailable("capture_failed")
            }
            is RawScreenshotReadResult.Available -> {
                logger("remote-vision capture ${capture.widthPx}x${capture.heightPx} bytes=${capture.jpegBytes.size}")
                rawScreenshotJpegToChatImageAttachment(capture.jpegBytes)
                    ?: return RemoteVisionDecision.Unavailable("attachment_bridge_failed")
            }
        }

        // Egress transparency: record that a screen screenshot left the device. Recorded BEFORE the
        // send resolves (the pixels are already committed to leave once we call send) so the user's
        // Trust-Center egress log reflects every screenshot, not just successful round-trips. No raw
        // pixels or prompt text are stored — only the aggregate decision + image count + summary.
        auditSink?.record(
            RemoteSendAuditEvent(
                decision = RemoteSendDecision.Confirmed,
                modelName = config.modelName,
                imageCount = 1,
                summary = "远程视觉 GUI 操作：当前屏幕截图外发给远程视觉模型以规划下一步操作。",
            ),
        )

        val response = runCatching {
            runBlocking {
                withTimeout(timeoutMillis) {
                    remoteRuntime.send(
                        prompt = remoteVisionDecisionPrompt(intent),
                        history = emptyList(),
                        parameters = generationParametersProvider(),
                        config = config,
                        imageAttachments = listOf(attachment),
                    ).toList().joinToString(separator = "")
                }
            }
        }.getOrElse { throwable ->
            logger("remote-vision send failed: ${throwable.javaClass.simpleName}")
            return RemoteVisionDecision.Unavailable("send_failed")
        }
        return parseRemoteVisionDecision(response)
    }
}

/**
 * Parses the remote vision model's strict-JSON reply into a [RemoteVisionDecision]. Tolerant of
 * surrounding prose (extracts the first `{...}` block) but fail-closed on anything it cannot read as
 * a valid tap-with-in-range-coords or an explicit stop. Pure and side-effect-free for unit testing.
 */
internal fun parseRemoteVisionDecision(raw: String): RemoteVisionDecision {
    val jsonText = raw.substringAfter('{', "").let { if (it.isBlank()) "" else "{$it" }
        .substringBeforeLast('}', "").let { if (it.isBlank()) "" else "$it}" }
    if (jsonText.isBlank()) return RemoteVisionDecision.Unavailable("unparseable_no_json")
    val json = runCatching { JSONObject(jsonText) }.getOrNull()
        ?: return RemoteVisionDecision.Unavailable("unparseable_json")
    return when (json.optString("action").trim().lowercase()) {
        "stop" -> RemoteVisionDecision.Stop
        "back" -> RemoteVisionDecision.Back
        "scroll" -> {
            val direction = UiScrollDirection.fromSchemaValue(json.optString("direction").trim().lowercase())
                ?.takeIf { it in REMOTE_VISION_SCROLL_DIRECTIONS }
                ?: return RemoteVisionDecision.Unavailable("scroll_invalid_direction")
            RemoteVisionDecision.Scroll(direction)
        }
        "tap" -> {
            val x = json.optIntOrNull("x") ?: return RemoteVisionDecision.Unavailable("tap_missing_x")
            val y = json.optIntOrNull("y") ?: return RemoteVisionDecision.Unavailable("tap_missing_y")
            if (x !in 0..REMOTE_VISION_MAX_NORMALIZED || y !in 0..REMOTE_VISION_MAX_NORMALIZED) {
                RemoteVisionDecision.Unavailable("tap_out_of_range")
            } else {
                RemoteVisionDecision.Tap(normalizedX = x, normalizedY = y)
            }
        }

        else -> RemoteVisionDecision.Unavailable("unknown_action")
    }
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}
