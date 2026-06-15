package com.bytedance.zgx.pocketmind.action

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

interface ActionPlanningRuntime {
    fun classifyIntent(input: String): IntentCandidate =
        if (isLikelyAction(input)) {
            IntentCandidate(
                toolName = null,
                confidence = ActionIntentConfidence.High,
                reason = "runtime likely action",
            )
        } else {
            IntentCandidate(
                toolName = null,
                confidence = ActionIntentConfidence.None,
                reason = "runtime rejected action intent",
            )
        }

    fun isLikelyAction(input: String): Boolean
    fun plan(input: String, actionModelPath: String?): ActionPlanningResult
    fun parseModelToolOutput(output: String): ModelToolOutputParseResult = ModelToolOutputParseResult.None
}

data class ActionPlanningResult(
    val plan: ActionPlan,
    val usedModel: Boolean,
    val fallbackReason: String?,
)

sealed class ModelToolOutputParseResult {
    data object None : ModelToolOutputParseResult()
    data class Parsed(val draft: ActionDraft) : ModelToolOutputParseResult()
    data class Rejected(
        val toolName: String?,
        val reason: String,
    ) : ModelToolOutputParseResult()
}

class HybridActionPlanningRuntime(
    cacheDir: File,
    private val rulePlanner: MobileActionPlanner = MobileActionPlanner(),
) : ActionPlanningRuntime, AutoCloseable {
    private val modelPlanner = ModelBackedActionPlanner(cacheDir, rulePlanner)

    override fun classifyIntent(input: String): IntentCandidate =
        rulePlanner.classifyIntent(input)

    override fun isLikelyAction(input: String): Boolean =
        classifyIntent(input).isAction

    override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
        if (actionModelPath != null) {
            val modelPlan = runCatching { modelPlanner.plan(input, actionModelPath) }
                .getOrNull()
            if (modelPlan?.kind == ActionPlanKind.Draft && modelPlan.draft != null) {
                return ActionPlanningResult(
                    plan = modelPlan,
                    usedModel = true,
                    fallbackReason = null,
                )
            }
        }

        return ActionPlanningResult(
            plan = rulePlanner.plan(input),
            usedModel = false,
            fallbackReason = if (actionModelPath == null) "动作模型未安装或未校验" else "动作模型未产出可执行草稿",
        )
    }

    override fun parseModelToolOutput(output: String): ModelToolOutputParseResult =
        rulePlanner.parseModelToolOutput(output)

    override fun close() {
        modelPlanner.close()
    }
}

private class ModelBackedActionPlanner(
    private val cacheDir: File,
    private val parser: MobileActionPlanner,
) : AutoCloseable {
    private var loadedPath: String? = null
    private var engine: Engine? = null

    fun plan(input: String, modelPath: String): ActionPlan? {
        val activeEngine = engineFor(modelPath)
        val conversation = activeEngine.createConversation(actionConversationConfig())
        return try {
            val output = StringBuilder()
            runBlocking {
                conversation.sendMessageAsync(actionPrompt(input)).collect { message ->
                    output.append(message.textContent())
                }
            }
            parser.parseModelOutput(output.toString())
                ?.let { ActionPlan(ActionPlanKind.Draft, it) }
        } finally {
            conversation.close()
        }
    }

    private fun engineFor(modelPath: String): Engine {
        if (loadedPath == modelPath && engine != null) return engine!!
        close()
        val created = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = cacheDir.absolutePath,
            ),
        )
        created.initialize()
        loadedPath = modelPath
        engine = created
        return created
    }

    override fun close() {
        engine?.close()
        engine = null
        loadedPath = null
    }
}

private fun actionConversationConfig(): ConversationConfig =
    ConversationConfig(
        systemInstruction = Contents.of(
            "你是手机动作规划器。只能输出 call:function {\"arg\":\"value\"}，不解释。",
        ),
        samplerConfig = SamplerConfig(
            topK = 1,
            topP = 0.1,
            temperature = 0.0,
        ),
    )

internal fun actionPrompt(input: String): String =
    """
    将用户请求转换成一个手机动作调用。支持函数：
    - open_wifi_settings {}
    - open_usage_access_settings {} 仅用于打开系统“使用情况访问权限”设置页
    - open_system_settings {"target":"general|bluetooth|location|notification|display|sound|battery_saver|network|airplane_mode|input_method|accessibility"} 仅用于打开 allowlisted Android 系统设置页，不静默修改系统开关
    - search_maps {"query":"..."}
    - web_search {"query":"..."} query 必须是理解后的搜索关键词，不要直接复制用户原文；保留实体、主题、限定词和必要时效词
    - compose_email {"body":"..."}
    - create_calendar_event {"title":"..."}
    - create_contact_draft {"name":"..."}
    - open_flashlight_settings {}
    - schedule_reminder {"title":"...","body":"...","delayMinutes":"15"} 仅用于明确的分钟/小时后一次性本地提醒
    - schedule_reminder {"title":"...","body":"...","triggerAtMillis":"..."} 仅用于已解析到未来 epoch millis 的一次性本地提醒；不支持重复
    - set_system_alarm {"hour":"23","minutes":"25","recurrence":"once|daily","message":"..."} 仅用于打开系统时钟闹钟设置界面；不跳过系统 UI；每天闹钟用 recurrence=daily
    - set_system_timer {"lengthSeconds":"1200","message":"..."} 仅用于打开系统时钟倒计时设置界面；不跳过系统 UI
    - query_background_tasks {"scope":"active|history|policy|all","maxCount":"..."} 仅用于只读查询本地后台任务/提醒任务/周期检查状态，不返回提醒正文
    - read_clipboard {} 仅用于用户明确要求读取剪贴板
    - share_text {"text":"...","title":"..."} 仅打开系统分享面板，不直接发送
    - open_deep_link {"uri":"https://..."} 仅用于打开 https 外部链接
    - open_app_by_name {"appName":"..."} 仅用于按本机 launcher 显示的应用名打开应用，不接受 URI、Activity、action 或 extras
    - open_app_intent {"packageName":"..."} 仅用于打开特定应用启动页
    - open_app_deep_target {"targetId":"android_app_details_settings","packageName":"..."} 仅用于打开允许列表中的固定应用目标
    - cancel_reminder {"taskId":"..."} 仅用于取消已安排的提醒任务
    - query_foreground_app {} 仅用于返回当前前台应用的包名与名称
    - query_calendar_availability {"start":"...","end":"..."} 仅用于查询指定时间窗日历忙闲
    - query_contacts {"query":"...","maxCount":"..."} 仅用于读取联系人名称与电话（maxCount 可选，默认 5）
    - query_recent_notifications {"maxCount":"..."} 仅用于返回当前应用最近通知的简要信息（可选）
    - query_recent_files {"kind":"...","maxCount":"..."} 仅用于返回最近文件摘要（kind 可为 all/screenshots/images/videos/audio/documents/downloads/others，maxCount 可选；Android 13 及以上 all 仅包含已授权媒体，documents/downloads/others 需要系统文件选择器授权）
    - read_recent_screenshot_ocr {"maxCount":"1"} 仅用于用户明确要求识别最近截图文字时，本地读取最近 1 张截图并提取 OCR 摘录
    - read_recent_image_ocr {"maxCount":"1..3"} 仅用于用户明确要求识别最近图片/照片文字时，本地扫描最近图片并提取第一条 OCR 摘录
    - read_current_screen_text {"maxChars":"1..4000"} 仅用于用户明确要求读取或总结当前屏幕/当前界面的可访问文本；这是 Accessibility 文本快照，不读取截图、像素或 OCR
    - open_camera {} 仅用于打开系统相机应用；不拍照、不录像、不读取照片
    - observe_current_screen {"maxTextChars":"1..4000","maxNodes":"1..120"} 仅用于观察当前屏幕 Accessibility 节点与可见文本，本地处理
    - ui_tap {"target":"...","timeoutMillis":"100..10000"} 仅用于点击当前屏幕上可见的文本、contentDescription 或短期节点 id
    - ui_type_text {"text":"...","target":"...","timeoutMillis":"100..10000"} 仅用于向当前或指定输入框输入文本，不发送、不发布
    - ui_submit_search {"timeoutMillis":"100..10000"} 仅用于提交当前搜索输入，不发送、不发布、不支付
    - ui_scroll {"direction":"up|down|left|right|forward|backward","target":"...","timeoutMillis":"100..10000"} 仅用于滚动当前屏幕或指定滚动容器
    - ui_press_back {"timeoutMillis":"100..10000"} 仅用于执行系统返回
    - ui_wait {"timeoutMillis":"100..10000","verifySearchQuery":"..."} 仅用于等待屏幕稳定并重新观察；可用 verifySearchQuery 本地验证低风险搜索结果

    用户请求：$input
    """.trimIndent()

private fun com.google.ai.edge.litertlm.Message.textContent(): String =
    contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString(separator = "") { it.text }
