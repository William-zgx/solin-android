package com.bytedance.zgx.pocketmind.action

interface ActionPlanner {
    fun isLikelyAction(input: String): Boolean
    fun plan(input: String): ActionPlan
}

class MobileActionPlanner : ActionPlanner {
    override fun isLikelyAction(input: String): Boolean {
        val normalized = input.lowercase()
        return listOf(
            "打开",
            "设置",
            "wifi",
            "wi-fi",
            "地图",
            "导航",
            "邮件",
            "日程",
            "联系人",
            "手电筒",
            "calendar",
            "email",
            "map",
            "contact",
        ).any { it in normalized }
    }

    override fun plan(input: String): ActionPlan =
        parseModelOutput(input)?.let { ActionPlan(ActionPlanKind.Draft, it) }
            ?: inferDraft(input)?.let { ActionPlan(ActionPlanKind.Draft, it) }
            ?: ActionPlan(ActionPlanKind.NoAction)

    fun parseModelOutput(output: String): ActionDraft? {
        val match = CALL_PATTERN.find(output.trim()) ?: return null
        val functionName = match.groupValues[1]
        if (functionName !in MobileActionFunctions.supported) return null
        val parameters = parseJsonLikeObject(match.groupValues[2])
        return functionName.toDraft(parameters)
    }

    private fun inferDraft(input: String): ActionDraft? {
        val normalized = input.lowercase()
        return when {
            "wifi" in normalized || "wi-fi" in normalized || "无线" in input ->
                MobileActionFunctions.OPEN_WIFI_SETTINGS.toDraft(emptyMap())

            "地图" in input || "导航" in input || "map" in normalized ->
                MobileActionFunctions.SEARCH_MAPS.toDraft(mapOf("query" to cleanedObject(input)))

            "邮件" in input || "email" in normalized || "mail" in normalized ->
                MobileActionFunctions.COMPOSE_EMAIL.toDraft(mapOf("body" to cleanedObject(input)))

            "日程" in input || "calendar" in normalized || "提醒" in input ->
                MobileActionFunctions.CREATE_CALENDAR_EVENT.toDraft(mapOf("title" to cleanedObject(input)))

            "联系人" in input || "contact" in normalized ->
                MobileActionFunctions.CREATE_CONTACT_DRAFT.toDraft(mapOf("name" to cleanedObject(input)))

            "手电筒" in input || "flashlight" in normalized ->
                MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS.toDraft(emptyMap())

            else -> null
        }
    }

    private fun String.toDraft(parameters: Map<String, String>): ActionDraft =
        ActionDraft(
            functionName = this,
            title = titleFor(this),
            summary = summaryFor(this, parameters),
            parameters = parameters,
            requiresConfirmation = true,
        )

    private fun titleFor(functionName: String): String =
        when (functionName) {
            MobileActionFunctions.OPEN_WIFI_SETTINGS -> "打开 Wi-Fi 设置"
            MobileActionFunctions.SEARCH_MAPS -> "地图搜索"
            MobileActionFunctions.COMPOSE_EMAIL -> "邮件草稿"
            MobileActionFunctions.CREATE_CALENDAR_EVENT -> "日程草稿"
            MobileActionFunctions.CREATE_CONTACT_DRAFT -> "联系人草稿"
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS -> "打开手电筒设置"
            else -> "动作草稿"
        }

    private fun summaryFor(functionName: String, parameters: Map<String, String>): String =
        when (functionName) {
            MobileActionFunctions.OPEN_WIFI_SETTINGS -> "将打开系统 Wi-Fi 设置页。"
            MobileActionFunctions.SEARCH_MAPS -> "将在地图中搜索：${parameters["query"].orEmpty()}"
            MobileActionFunctions.COMPOSE_EMAIL -> "将打开邮件 App 并填入草稿内容。"
            MobileActionFunctions.CREATE_CALENDAR_EVENT -> "将打开日历新建事件页面。"
            MobileActionFunctions.CREATE_CONTACT_DRAFT -> "将打开联系人新建页面。"
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS -> "将打开系统设置，由你手动确认手电筒相关操作。"
            else -> "将打开系统页面完成这个动作。"
        }

    private fun cleanedObject(input: String): String =
        input.trim()
            .removePrefix("请")
            .removePrefix("帮我")
            .trim()
            .ifBlank { input.trim() }

    private fun parseJsonLikeObject(raw: String): Map<String, String> {
        val content = raw.trim().removePrefix("{").removeSuffix("}")
        if (content.isBlank()) return emptyMap()
        return KEY_VALUE_PATTERN.findAll(content)
            .associate { match ->
                match.groupValues[1] to match.groupValues[2]
            }
    }

    private companion object {
        val CALL_PATTERN = Regex("""^call:([a-zA-Z0-9_]+)\s*(\{.*\})$""", RegexOption.DOT_MATCHES_ALL)
        val KEY_VALUE_PATTERN = Regex(""""([^"]+)"\s*:\s*"([^"]*)"""")
    }
}
