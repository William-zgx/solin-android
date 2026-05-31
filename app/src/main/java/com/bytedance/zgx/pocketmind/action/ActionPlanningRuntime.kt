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
    fun isLikelyAction(input: String): Boolean
    fun plan(input: String, actionModelPath: String?): ActionPlanningResult
}

data class ActionPlanningResult(
    val plan: ActionPlan,
    val usedModel: Boolean,
    val fallbackReason: String?,
)

class HybridActionPlanningRuntime(
    cacheDir: File,
    private val rulePlanner: MobileActionPlanner = MobileActionPlanner(),
) : ActionPlanningRuntime, AutoCloseable {
    private val modelPlanner = ModelBackedActionPlanner(cacheDir, rulePlanner)

    override fun isLikelyAction(input: String): Boolean =
        rulePlanner.isLikelyAction(input)

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
    - search_maps {"query":"..."}
    - web_search {"query":"..."}
    - compose_email {"body":"..."}
    - create_calendar_event {"title":"..."}
    - create_contact_draft {"name":"..."}
    - open_flashlight_settings {}
    - schedule_reminder {"title":"...","body":"...","delayMinutes":"15"} 仅用于明确的分钟/小时后提醒
    - read_clipboard {} 仅用于用户明确要求读取剪贴板
    - share_text {"text":"...","title":"..."} 仅打开系统分享面板，不直接发送
    - open_deep_link {"uri":"https://..."} 仅用于打开 https 外部链接
    - open_app_intent {"packageName":"..."} 仅用于打开特定应用启动页
    - open_app_deep_target {"targetId":"android_app_details_settings","packageName":"..."} 仅用于打开允许列表中的固定应用目标
    - cancel_reminder {"taskId":"..."} 仅用于取消已安排的提醒任务
    - query_foreground_app {} 仅用于返回当前前台应用的包名与名称
    - query_calendar_availability {"start":"...","end":"..."} 仅用于查询指定时间窗日历忙闲
    - query_contacts {"query":"...","maxCount":"..."} 仅用于读取联系人名称与电话（maxCount 可选，默认 5）
    - query_recent_notifications {"maxCount":"..."} 仅用于返回当前应用最近通知的简要信息（可选）
    - query_recent_files {"kind":"...","maxCount":"..."} 仅用于返回最近文件摘要（kind 可为 all/screenshots/images/videos/audio/documents/downloads/others，maxCount 可选）

    用户请求：$input
    """.trimIndent()

private fun com.google.ai.edge.litertlm.Message.textContent(): String =
    contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString(separator = "") { it.text }
