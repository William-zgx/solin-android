package com.bytedance.zgx.solin.action

import com.bytedance.zgx.solin.background.PeriodicCheckScheduleRequest
import kotlin.math.roundToLong

internal object ReminderActionParser {
    private const val AMOUNT_PATTERN = """\d+(?:\.\d+)?"""
    private val chineseRelativeDelayPattern =
        Regex("""(?:在\s*)?(?<![\d.])($AMOUNT_PATTERN)\s*(分钟|小时)\s*(?:后|以后|之后)""")
    private val englishRelativeDelayPattern =
        Regex("""\b(?:in|after)\s+(?<![\d.])($AMOUNT_PATTERN)\s*(minutes?|mins?|hours?|hrs?)\b""", RegexOption.IGNORE_CASE)
    private val chineseTimingQuestionAfterDelayPattern =
        Regex("""^[\s，,。.!?？、:："'“”‘’（）()]*?(?:是?什么意思|啥意思|怎么说|如何表达|什么含义|有?什么含义|用法|语法)""")
    private val chineseReminderCommandPattern =
        Regex("""^\s*(?:请\s*)?(?:帮我\s*)?提醒我""")
    private val englishReminderCommandPattern =
        Regex("""^\s*(?:please\s+)?(?:(?:can|could)\s+you\s+)?(?:remind me|set (?:a )?reminder)\b""", RegexOption.IGNORE_CASE)
    private val englishWhatPattern =
        Regex("""\bwhat(?:'s|\s+is)?\b""", RegexOption.IGNORE_CASE)
    private val englishMeaningPattern =
        Regex("""\b(?:means?|meaning)\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        val delay = relativeDelay(input) ?: return false
        return hasReminderCommand(input) && !looksLikeTimingDiscussion(input, delay)
    }

    fun parameters(input: String): Map<String, String> {
        val delayMinutes = relativeDelay(input)?.minutes ?: 1L
        val title = cleanedReminderTitle(input)
        return mapOf(
            "title" to title,
            "body" to input.trim(),
            "delayMinutes" to delayMinutes.toString(),
        )
    }

    fun draft(input: String): ActionDraft =
        parameters(input).let { parameters ->
            ActionDraft(
                functionName = MobileActionFunctions.SCHEDULE_REMINDER,
                title = "后台提醒",
                summary = "将在 ${parameters["delayMinutes"].orEmpty()} 分钟后提醒：${parameters["title"].orEmpty()}",
                parameters = parameters,
                requiresConfirmation = true,
            )
        }

    private fun relativeDelay(input: String): RelativeDelay? =
        (
            chineseRelativeDelayPattern.findAll(input).mapNotNull { match ->
                match.toRelativeDelay(amountGroupIndex = 1, unitGroupIndex = 2)
            } + englishRelativeDelayPattern.findAll(input).mapNotNull { match ->
                match.toRelativeDelay(amountGroupIndex = 1, unitGroupIndex = 2)
            }
            ).minByOrNull { delay -> delay.range.first }

    private fun cleanedReminderTitle(input: String): String {
        val withoutSelectedDelay = relativeDelay(input)?.let { delay ->
            input.removeRange(delay.range.first, delay.range.last + 1)
        } ?: input
        return withoutSelectedDelay.trim()
            .replace(Regex("""^(请\s*)?(帮我\s*)?提醒我\s*"""), "")
            .replace(
                Regex(
                    """(?i)^(please\s+)?(?:(can|could)\s+you\s+)?(?:remind me|set (?:a )?reminder)\s+""",
                ),
                "",
            )
            .replace(Regex("""^一下\s*"""), "")
            .replace(Regex("""(?i)^\s*to\s+"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim { it <= ' ' || it == '，' || it == ',' || it == ':' || it == '：' }
            .ifBlank { cleanedObject(input) }
    }

    private fun cleanedObject(input: String): String =
        input.trim()
            .removePrefix("请")
            .removePrefix("帮我")
            .trim()
            .ifBlank { input.trim() }

    private fun hasReminderCommand(input: String): Boolean =
        chineseReminderCommandPattern.containsMatchIn(input) || englishReminderCommandPattern.containsMatchIn(input)

    private fun looksLikeTimingDiscussion(input: String, delay: RelativeDelay): Boolean {
        val afterDelay = input.substring(delay.range.last + 1)
        if (chineseTimingQuestionAfterDelayPattern.containsMatchIn(afterDelay)) return true

        val whatIndex = englishWhatPattern.find(input)?.range?.first ?: return false
        val meaningIndex = englishMeaningPattern.findAll(input)
            .firstOrNull { match -> match.range.first > whatIndex }
            ?.range
            ?.first
            ?: return false
        return delay.range.first in whatIndex..meaningIndex
    }

    private fun MatchResult.toRelativeDelay(amountGroupIndex: Int, unitGroupIndex: Int): RelativeDelay? {
        val amount = groupValues.getOrNull(amountGroupIndex)?.toDoubleOrNull() ?: return null
        val unit = groupValues.getOrNull(unitGroupIndex)?.lowercase().orEmpty()
        val minutes = when {
            unit == "小时" || unit.startsWith("hour") || unit.startsWith("hr") -> amount * 60.0
            else -> amount
        }.roundToLong().coerceAtLeast(1L)
        return RelativeDelay(minutes = minutes, range = range)
    }

    private data class RelativeDelay(
        val minutes: Long,
        val range: IntRange,
    )
}

internal object CancelReminderActionParser {
    private val taskIdPattern = Regex("""task-[A-Za-z0-9_-]+""")
    private val englishPattern =
        Regex(
            """\b(?:cancel|undo|dismiss|remove)\s+(?:the\s+)?(?:reminder|scheduled\s+reminder|background\s+task)\b""",
            RegexOption.IGNORE_CASE,
        )

    fun matches(input: String): Boolean {
        if (input.looksLikeCancelReminderNonAction()) return false
        val normalized = input.lowercase()
        val hasTrigger = listOf("取消提醒", "撤销提醒", "取消后台提醒", "撤销后台提醒")
            .any { it in input } ||
            englishPattern.containsMatchIn(normalized)
        return hasTrigger && taskIdPattern.containsMatchIn(input)
    }

    fun draft(input: String): ActionDraft {
        val taskId = taskId(input).orEmpty()
        return ActionDraft(
            functionName = MobileActionFunctions.CANCEL_REMINDER,
            title = "取消提醒",
            summary = "将取消提醒任务：$taskId",
            parameters = mapOf("taskId" to taskId),
            requiresConfirmation = true,
        )
    }

    private fun taskId(input: String): String? =
        taskIdPattern.find(input)?.value

    private fun String.looksLikeCancelReminderNonAction(): Boolean {
        val normalized = lowercase()
        return listOf(
            "不要取消提醒",
            "别取消提醒",
            "不要撤销提醒",
            "别撤销提醒",
            "取消提醒是什么意思",
            "取消提醒怎么",
            "提醒取消怎么",
            "取消提醒 API",
            "取消提醒 接口",
            "取消提醒 实现",
            "取消提醒 设计",
            "取消提醒 文档",
            "取消提醒 测试",
            "取消日程",
            "取消联系人",
            "取消邮件",
            "取消搜索",
            "怎么实现",
            "如何实现",
            "怎么设计",
        ).any { it in this } ||
            normalized.contains(Regex("""\b(do\s+not|don't|dont)\s+(?:cancel|undo|dismiss|remove)\s+(?:the\s+)?(?:reminder|scheduled\s+reminder|background\s+task)\b""")) ||
            normalized.contains(Regex("""\b(?:cancel|undo|dismiss|remove)\s+(?:reminder|scheduled\s+reminder|background\s+task)\s+(?:api|implementation|architecture|design|schema|tests?|parser|docs?)\b""")) ||
            normalized.contains(Regex("""\b(?:what\s+is|explain|describe|meaning|how\s+do\s+i|how\s+to|implement|design)\b.*\b(?:cancel|undo|dismiss|remove)\s+(?:reminder|scheduled\s+reminder|background\s+task)\b"""))
    }
}

internal object BackgroundTasksQueryActionParser {
    private val englishPattern =
        Regex(
            """\b(?:show|list|query|view|check)\b.*\b(?:background\s+tasks?|scheduled\s+(?:tasks?|reminders?)|reminder\s+(?:tasks?|list)|periodic\s+check\s+(?:status|policy))\b|\b(?:what|which)\b.*\b(?:background\s+tasks?|scheduled\s+reminders?)\b""",
            RegexOption.IGNORE_CASE,
        )
    private val maxCountPattern = Regex("""(?:最近|最多|前)\s*(\d{1,2})\s*(?:个|条|项)?""")
    private val englishMaxCountPattern = Regex("""\b(?:last|recent|top|limit)\s+(\d{1,2})\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        if (input.looksLikeBackgroundTasksQueryNonAction()) return false
        val normalized = input.lowercase()
        return input.hasChineseBackgroundTasksQueryTrigger() || englishPattern.containsMatchIn(normalized)
    }

    fun draft(input: String): ActionDraft {
        val parameters = parameters(input)
        return ActionDraft(
            functionName = MobileActionFunctions.QUERY_BACKGROUND_TASKS,
            title = "查询后台任务",
            summary = summary(parameters["scope"].orEmpty().ifBlank { "active" }, parameters["maxCount"]),
            parameters = parameters,
            requiresConfirmation = true,
        )
    }

    private fun parameters(input: String): Map<String, String> =
        buildMap {
            put("scope", scope(input))
            maxCount(input)?.let { put("maxCount", it.toString()) }
        }

    private fun scope(input: String): String {
        val normalized = input.lowercase()
        val wantsPolicy = listOf("周期检查状态", "周期检查策略", "周期检查设置", "后台检查状态", "后台检查策略")
            .any { it in input } ||
            Regex("""\bperiodic\s+check\s+(?:status|policy|settings?)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(normalized)
        val wantsHistory = listOf("历史", "最近完成", "已完成", "已取消", "失败记录", "执行记录")
            .any { it in input } ||
            Regex("""\b(?:history|recent|completed|cancelled|canceled|failed)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(normalized)
        val wantsActive = listOf("活动", "当前", "正在", "已安排", "待执行", "提醒列表", "有哪些提醒")
            .any { it in input } ||
            Regex("""\b(?:active|scheduled|running|pending)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(normalized)
        val wantsAll = listOf("全部", "所有", "完整", "都列出来").any { it in input } ||
            Regex("""\b(?:all|everything)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ||
            (wantsPolicy && (wantsActive || wantsHistory || "后台任务" in input))
        return when {
            wantsAll -> "all"
            wantsPolicy -> "policy"
            wantsHistory -> "history"
            wantsActive -> "active"
            else -> "active"
        }
    }

    private fun maxCount(input: String): Int? {
        val normalized = input.lowercase()
        val cleaned = input.replace(Regex("\\s+"), "")
        return maxCountPattern.find(cleaned)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: englishMaxCountPattern.find(normalized)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
    }

    private fun summary(scope: String, maxCount: String?): String {
        val countText = maxCount?.takeIf { it.isNotBlank() }?.let { "最多 $it 条" }.orEmpty()
        return when (scope) {
            "history" -> "将只读查询本地后台任务历史$countText，不返回提醒正文。"
            "policy" -> "将只读查询本地提醒周期检查策略，不返回提醒正文。"
            "all" -> "将只读查询本地后台任务与周期检查策略$countText，不返回提醒正文。"
            else -> "将只读查询本地活动后台任务$countText，不返回提醒正文。"
        }
    }

    private fun String.hasChineseBackgroundTasksQueryTrigger(): Boolean {
        val hasQueryVerb = listOf("查看", "查询", "列出", "看看", "看一下", "有哪些", "状态", "列表")
            .any { it in this }
        val hasTarget = listOf("后台任务", "提醒任务", "提醒列表", "已安排提醒", "本地提醒", "周期检查状态", "周期检查策略")
            .any { it in this }
        return hasQueryVerb && hasTarget
    }

    private fun String.looksLikeBackgroundTasksQueryNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
                "后台任务是什么",
                "后台任务怎么",
                "后台任务如何",
                "后台任务 api",
                "后台任务API",
                "后台任务 接口",
                "后台任务 实现",
                "后台任务 设计",
                "后台任务 文档",
                "后台任务 测试",
                "后台任务 schema",
                "后台任务 parser",
                "提醒任务怎么",
                "周期检查是什么",
                "周期检查 api",
                "周期检查API",
                "周期检查 实现",
                "周期检查 文档",
                "怎么实现",
                "如何实现",
                "怎么设计",
                "取消后台任务",
                "删除后台任务",
                "开启周期检查",
                "启用周期检查",
                "关闭周期检查",
                "停用周期检查",
                "配置周期检查",
                "设置周期检查",
            ).any { it in this } ||
            normalized.contains(Regex("""\b(?:api|implementation|architecture|design|schema|tests?|parser|docs?|documentation)\b.*\b(?:background\s+tasks?|scheduled\s+tasks?|periodic\s+checks?)\b""")) ||
            normalized.contains(Regex("""\b(?:what\s+is|explain|describe|meaning|how\s+(?:do|can|to)|implement|design)\b.*\b(?:background\s+tasks?|scheduled\s+tasks?|periodic\s+checks?)\b""")) ||
            normalized.contains(Regex("""\b(?:cancel|delete|remove)\s+(?:background\s+tasks?|scheduled\s+tasks?|reminders?)\b""")) ||
            normalized.contains(Regex("""\b(?:enable|disable|turn\s+on|turn\s+off|configure|set)\s+(?:local\s+)?periodic\s+checks?\b"""))
    }
}

internal object PeriodicCheckActionParser {
    private const val AMOUNT_PATTERN = """\d+(?:\.\d+)?"""
    private val intervalPattern =
        Regex("""\b(?:every|interval)\s+(?<![\d.])($AMOUNT_PATTERN)\s*(minutes?|mins?|hours?|hrs?)\b""", RegexOption.IGNORE_CASE)
    private val chineseIntervalPattern =
        Regex("""(?:每|间隔)\s*(?<![\d.])($AMOUNT_PATTERN)\s*(分钟|小时)""")
    private val enablePattern =
        Regex("""\b(?:enable|turn\s+on|start)\s+(?:local\s+)?periodic\s+checks?\b""", RegexOption.IGNORE_CASE)
    private val disablePattern =
        Regex("""\b(?:disable|turn\s+off|stop)\s+(?:local\s+)?periodic\s+checks?\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        if (input.looksLikePeriodicCheckNonAction()) return false
        return requestedMode(input) != null
    }

    fun parameters(input: String): Map<String, String> {
        val enabled = requestedMode(input) ?: true
        return buildMap {
            put("enabled", enabled.toString())
            if (enabled) {
                put(
                    "intervalMinutes",
                    (intervalMinutes(input) ?: PeriodicCheckScheduleRequest.DEFAULT_INTERVAL_MINUTES).toString(),
                )
            }
        }
    }

    fun draft(input: String): ActionDraft {
        val parameters = parameters(input)
        val enabled = parameters["enabled"].toBoolean()
        return ActionDraft(
            functionName = MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
            title = if (enabled) "开启周期检查" else "关闭周期检查",
            summary = if (enabled) {
                "将开启本地提醒周期检查，间隔 ${parameters["intervalMinutes"].orEmpty()} 分钟。"
            } else {
                "将关闭本地提醒周期检查。"
            },
            parameters = parameters,
            requiresConfirmation = true,
        )
    }

    private fun requestedMode(input: String): Boolean? {
        val normalized = input.lowercase()
        val requestsPeriodicCheck = "周期检查" in input ||
            "后台检查" in input ||
            Regex("""\bperiodic\s+checks?\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        if (!requestsPeriodicCheck) return null

        val requestsDisable = listOf("关闭", "停用", "禁用", "取消周期检查", "停止周期检查", "关闭后台检查")
            .any { it in input } ||
            disablePattern.containsMatchIn(normalized)
        if (requestsDisable) return false

        val requestsEnable = listOf("开启", "启用", "打开", "启动", "设置", "保存")
            .any { it in input } ||
            enablePattern.containsMatchIn(normalized)
        return true.takeIf { requestsEnable }
    }

    private fun intervalMinutes(input: String): Long? =
        (
            chineseIntervalPattern.findAll(input).mapNotNull { match ->
                match.toIntervalMinutes(amountGroupIndex = 1, unitGroupIndex = 2)
            } + intervalPattern.findAll(input).mapNotNull { match ->
                match.toIntervalMinutes(amountGroupIndex = 1, unitGroupIndex = 2)
            }
            ).minOrNull()

    private fun MatchResult.toIntervalMinutes(amountGroupIndex: Int, unitGroupIndex: Int): Long? {
        val amount = groupValues.getOrNull(amountGroupIndex)?.toDoubleOrNull() ?: return null
        val unit = groupValues.getOrNull(unitGroupIndex)?.lowercase().orEmpty()
        val minutes = when {
            unit == "小时" || unit.startsWith("hour") || unit.startsWith("hr") -> amount * 60.0
            else -> amount
        }.roundToLong()
        return minutes.coerceIn(
            PeriodicCheckScheduleRequest.MIN_INTERVAL_MINUTES,
            PeriodicCheckScheduleRequest.MAX_INTERVAL_MINUTES,
        )
    }

    private fun String.looksLikePeriodicCheckNonAction(): Boolean {
        val normalized = lowercase()
        return listOf(
            "周期检查是什么",
            "周期检查怎么",
            "周期检查如何",
            "周期检查 api",
            "周期检查 接口",
            "周期检查 实现",
            "周期检查 设计",
            "周期检查 文档",
            "周期检查 测试",
            "后台检查是什么",
            "怎么实现",
            "如何实现",
            "怎么设计",
        ).any { it in this } ||
            normalized.contains(
                Regex("""\b(?:what\s+is|explain|describe|meaning|how\s+(?:do|can|to)|implement|design)\b.*\bperiodic\s+checks?\b"""),
            ) ||
            normalized.contains(
                Regex("""\bperiodic\s+checks?\s+(?:api|implementation|architecture|design|schema|tests?|docs?)\b"""),
            )
    }
}
