package com.bytedance.zgx.pocketmind.action

import com.bytedance.zgx.pocketmind.background.PeriodicCheckScheduleRequest
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
