package com.bytedance.zgx.solin.action

/*
 * Rule parsers for Wi-Fi / flashlight / usage-access and allowlisted system settings pages.
 */

private val EXPLICIT_APP_NAME_OPEN_APP_WORD_REGEX =
    Regex("""\bapp(?:lication)?\b""", RegexOption.IGNORE_CASE)

private val EXPLICIT_APP_NAME_OPEN_NAMED_CALLED_REGEX =
    Regex("""\b(?:named|called)\b""", RegexOption.IGNORE_CASE)

private val SETTINGS_PREFERENCES_REGEX =
    Regex("""\b(settings?|preferences?)\b""", RegexOption.IGNORE_CASE)

private val WIFI_SETTINGS_OPEN_TRIGGER_REGEX =
    Regex("""\b(?:open|show|go\s+to|launch|enable|turn\s+on)\b""", RegexOption.IGNORE_CASE)

private val FLASHLIGHT_WORD_REGEX = Regex("""\b(flashlight|torch)\b""", RegexOption.IGNORE_CASE)

private val USAGE_ACCESS_WORD_REGEX =
    Regex("""\b(?:usage\s+access|usage\s+stats(?:\s+permission)?)\b""", RegexOption.IGNORE_CASE)

internal object DeviceSettingsActionParser {
    private val wifiPattern =
        Regex("""(?i)(^|[^a-z0-9])(?:wi[-\s]?fi|wifi|wlan|wireless)(?=$|[^a-z0-9])""")
    private val englishWifiSettingsPattern =
        Regex("""\b(?:(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:wi[-\s]?fi|wifi|wlan|wireless)\s+(?:settings?|preferences?)|(?:wi[-\s]?fi|wifi|wlan|wireless)\s+(?:settings?|preferences?))\b""", RegexOption.IGNORE_CASE)
    private val englishFlashlightSettingsPattern =
        Regex("""\b(?:(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:flashlight|torch)\s+(?:settings?|preferences?)|(?:flashlight|torch)\s+(?:settings?|preferences?))\b""", RegexOption.IGNORE_CASE)
    private val englishUsageAccessPattern =
        Regex("""\b(?:(?:open|show|go\s+to|launch|grant)\s+(?:the\s+)?(?:usage\s+access|usage\s+stats(?:\s+permission)?)(?:\s+(?:settings?|permissions?|preferences?))?|(?:usage\s+access|usage\s+stats(?:\s+permission)?)\s+(?:settings?|preferences?))\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean = targetTool(input) != null

    fun draft(input: String): ActionDraft {
        val toolName = targetTool(input) ?: MobileActionFunctions.OPEN_WIFI_SETTINGS
        return when (toolName) {
            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS -> ActionDraft(
                functionName = MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
                title = "打开使用情况访问权限设置",
                summary = "将打开系统使用情况访问权限设置页，由你手动为Solin授权。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS -> ActionDraft(
                functionName = MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
                title = "打开手电筒设置",
                summary = "将打开系统设置，由你手动确认手电筒相关操作。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            else -> ActionDraft(
                functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                title = "打开 Wi-Fi 设置",
                summary = "将打开系统 Wi-Fi 设置页。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
        }
    }

    private fun targetTool(input: String): String? {
        if (input.looksLikeDiscussion() || input.looksLikeDeviceSettingsNonAction()) return null
        if (input.looksLikeExplicitAppNameOpen()) return null
        return when {
            input.requestsWifiSettings() -> MobileActionFunctions.OPEN_WIFI_SETTINGS
            input.requestsUsageAccessSettings() -> MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS
            input.requestsFlashlightSettings() -> MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS
            else -> null
        }
    }

    private fun String.looksLikeExplicitAppNameOpen(): Boolean {
        val normalized = lowercase()
        val referencesApp = listOf("app", "应用", "应用程序").any { it in this } ||
            EXPLICIT_APP_NAME_OPEN_APP_WORD_REGEX.containsMatchIn(normalized)
        val namesTarget = listOf("名为", "叫做", "叫").any { it in this } ||
            EXPLICIT_APP_NAME_OPEN_NAMED_CALLED_REGEX.containsMatchIn(normalized)
        return referencesApp && namesTarget
    }

    private fun String.requestsWifiSettings(): Boolean {
        val normalized = lowercase()
        val referencesWifi = wifiPattern.containsMatchIn(normalized) || "无线" in this
        val referencesSettings = "设置" in this ||
            SETTINGS_PREFERENCES_REGEX.containsMatchIn(normalized)
        val hasChineseOpenTrigger = listOf("打开", "开启", "进入", "跳转", "前往", "去", "设置").any { it in this }
        val hasEnglishOpenTrigger =
            WIFI_SETTINGS_OPEN_TRIGGER_REGEX.containsMatchIn(normalized)
        return referencesWifi && (
            (referencesSettings && (hasChineseOpenTrigger || englishWifiSettingsPattern.containsMatchIn(normalized))) ||
                hasChineseOpenTrigger ||
                hasEnglishOpenTrigger
            )
    }

    private fun String.requestsFlashlightSettings(): Boolean {
        val normalized = lowercase()
        val referencesFlashlight = "手电筒" in this ||
            FLASHLIGHT_WORD_REGEX.containsMatchIn(normalized)
        val referencesSettings = "设置" in this ||
            SETTINGS_PREFERENCES_REGEX.containsMatchIn(normalized)
        return referencesFlashlight && referencesSettings && (
            listOf("打开", "进入", "跳转", "前往", "去", "设置").any { it in this } ||
                englishFlashlightSettingsPattern.containsMatchIn(normalized)
            )
    }

    private fun String.requestsUsageAccessSettings(): Boolean {
        val normalized = lowercase()
        val referencesUsageAccess = listOf(
            "使用情况访问权限",
            "应用使用情况权限",
            "查看应用使用情况",
        ).any { it in this } ||
            USAGE_ACCESS_WORD_REGEX.containsMatchIn(normalized)
        return referencesUsageAccess && (
            listOf("打开", "进入", "跳转", "前往", "去", "设置", "开启", "授权").any { it in this } ||
                englishUsageAccessPattern.containsMatchIn(normalized)
            )
    }
}

private val REFERENCED_TARGET_BLUETOOTH_REGEX = Regex("""\bbluetooth\b""", RegexOption.IGNORE_CASE)

private val REFERENCED_TARGET_LOCATION_REGEX = Regex("""\blocation\b""", RegexOption.IGNORE_CASE)

private val REFERENCED_TARGET_NOTIFICATIONS_SETTINGS_REGEX =
    Regex("""\bnotifications?\s+(?:settings?|preferences?|permissions?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?notifications?\b""", RegexOption.IGNORE_CASE)

private val REFERENCED_TARGET_DISPLAY_BRIGHTNESS_REGEX =
    Regex("""\b(?:display|brightness)\s+(?:settings?|preferences?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:display|brightness)\b""", RegexOption.IGNORE_CASE)

private val REFERENCED_TARGET_DND_REGEX =
    Regex("""\b(?:do\s+not\s+disturb|dnd)\b""", RegexOption.IGNORE_CASE)

private val REFERENCED_TARGET_VOLUME_PANEL_REGEX =
    Regex("""\bvolume\s+(?:panel|slider|controls?)\b""", RegexOption.IGNORE_CASE)

private val REFERENCED_TARGET_SOUND_VOLUME_REGEX =
    Regex("""\b(?:sound|volume|ringtone)\s+(?:settings?|preferences?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:sound|volume|ringtone)\b""", RegexOption.IGNORE_CASE)

private val REFERENCED_TARGET_BATTERY_SAVER_REGEX =
    Regex("""\b(?:battery|battery\s+saver|power\s+saver)\s+(?:settings?|preferences?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:battery|battery\s+saver|power\s+saver)\b""", RegexOption.IGNORE_CASE)

private val REFERENCED_TARGET_NETWORK_MOBILE_REGEX =
    Regex("""\b(?:network|mobile\s+data|cellular)\s+(?:settings?|preferences?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:network|mobile\s+data|cellular)\b""", RegexOption.IGNORE_CASE)

private val REFERENCED_TARGET_AIRPLANE_MODE_REGEX =
    Regex("""\bairplane\s+mode\b""", RegexOption.IGNORE_CASE)

private val REFERENCED_TARGET_INPUT_METHOD_REGEX =
    Regex("""\b(?:input\s+method|keyboard)\s+(?:settings?|preferences?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:input\s+method|keyboard)\b""", RegexOption.IGNORE_CASE)

private val REFERENCED_TARGET_ACCESSIBILITY_SETTINGS_REGEX =
    Regex("""\baccessibility\s+(?:settings?|preferences?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?accessibility\b""", RegexOption.IGNORE_CASE)

private val REFERENCED_TARGET_GENERAL_REGEX =
    Regex("""\b(?:system|phone|android)\s+(?:settings?|preferences?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?settings?\b""", RegexOption.IGNORE_CASE)

private val VOLUME_PANEL_ADJUST_REGEX =
    Regex("""调(?:大|小|高|低|节|整)?(?:一下|一点)?(?:手机|媒体|系统)?音量|音量调(?:大|小|高|低)""")

internal object SystemSettingsActionParser {
    private val englishOpenPattern =
        Regex("""\b(?:open|show|go\s+to|launch|enable|turn\s+on)\b""", RegexOption.IGNORE_CASE)
    private val englishSettingsPattern =
        Regex("""\b(?:settings?|preferences?)\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean = targetFor(input) != null

    fun draft(input: String): ActionDraft {
        val target = targetFor(input) ?: SystemSettingsTargets.GENERAL
        val label = SystemSettingsTargets.pageLabel(target)
        return ActionDraft(
            functionName = MobileActionFunctions.OPEN_SYSTEM_SETTINGS,
            title = "打开 $label",
            summary = "将打开$label，由你手动完成后续操作。",
            parameters = mapOf("target" to target),
            requiresConfirmation = true,
        )
    }

    private fun targetFor(input: String): String? {
        if (input.looksLikeDiscussion() || input.looksLikeDeviceSettingsNonAction()) return null
        if (input.looksLikeAppScopedSystemSettings()) return null
        val target = referencedTarget(input) ?: return null
        return target.takeIf { input.hasSettingsOpenIntent() }
    }

    private fun referencedTarget(input: String): String? {
        val normalized = input.lowercase()
        return when {
            listOf("蓝牙").any { it in input } ||
                REFERENCED_TARGET_BLUETOOTH_REGEX.containsMatchIn(normalized) ->
                SystemSettingsTargets.BLUETOOTH

            listOf("定位", "位置信息", "位置服务", "gps", "GPS").any { it in input } ||
                REFERENCED_TARGET_LOCATION_REGEX.containsMatchIn(normalized) ->
                SystemSettingsTargets.LOCATION

            listOf("通知设置", "通知权限", "系统通知").any { it in input } ||
                REFERENCED_TARGET_NOTIFICATIONS_SETTINGS_REGEX.containsMatchIn(normalized) ->
                SystemSettingsTargets.NOTIFICATION

            listOf("显示设置", "屏幕设置", "亮度", "屏幕亮度").any { it in input } ||
                REFERENCED_TARGET_DISPLAY_BRIGHTNESS_REGEX.containsMatchIn(normalized) ->
                SystemSettingsTargets.DISPLAY

            listOf("勿扰", "免打扰").any { it in input } ||
                REFERENCED_TARGET_DND_REGEX.containsMatchIn(normalized) ->
                SystemSettingsTargets.DO_NOT_DISTURB

            input.requestsVolumePanel() ||
                REFERENCED_TARGET_VOLUME_PANEL_REGEX.containsMatchIn(normalized) ->
                SystemSettingsTargets.VOLUME

            listOf("声音设置", "音量设置", "铃声设置", "铃声", "静音模式", "响铃模式", "震动模式", "振动模式").any { it in input } ||
                REFERENCED_TARGET_SOUND_VOLUME_REGEX.containsMatchIn(normalized) ->
                SystemSettingsTargets.SOUND

            listOf("省电", "省电模式", "电池设置", "电量设置").any { it in input } ||
                REFERENCED_TARGET_BATTERY_SAVER_REGEX.containsMatchIn(normalized) ->
                SystemSettingsTargets.BATTERY_SAVER

            listOf("网络设置", "移动网络", "蜂窝网络", "数据网络").any { it in input } ||
                REFERENCED_TARGET_NETWORK_MOBILE_REGEX.containsMatchIn(normalized) ->
                SystemSettingsTargets.NETWORK

            listOf("飞行模式").any { it in input } ||
                REFERENCED_TARGET_AIRPLANE_MODE_REGEX.containsMatchIn(normalized) ->
                SystemSettingsTargets.AIRPLANE_MODE

            listOf("输入法", "键盘设置").any { it in input } ||
                REFERENCED_TARGET_INPUT_METHOD_REGEX.containsMatchIn(normalized) ->
                SystemSettingsTargets.INPUT_METHOD

            listOf("无障碍", "辅助功能").any { it in input } ||
                REFERENCED_TARGET_ACCESSIBILITY_SETTINGS_REGEX.containsMatchIn(normalized) ->
                SystemSettingsTargets.ACCESSIBILITY

            listOf("系统设置", "手机设置", "设置页").any { it in input } ||
                REFERENCED_TARGET_GENERAL_REGEX.containsMatchIn(normalized) ->
                SystemSettingsTargets.GENERAL

            else -> null
        }
    }

    /** 音量面板 / 调大音量 / 调节音量; "音量设置" keeps mapping to the sound settings page. */
    private fun String.requestsVolumePanel(): Boolean =
        "音量面板" in this || "音量调节" in this ||
            VOLUME_PANEL_ADJUST_REGEX.containsMatchIn(this)

    private fun String.hasSettingsOpenIntent(): Boolean {
        val normalized = lowercase()
        if (requestsVolumePanel()) return true
        return listOf("打开", "开启", "进入", "跳转", "前往", "去", "设置", "调出", "启动", "调成", "调到", "切换到", "切到").any { it in this } ||
            englishOpenPattern.containsMatchIn(normalized) ||
            englishSettingsPattern.containsMatchIn(normalized)
    }

    private fun String.looksLikeAppScopedSystemSettings(): Boolean {
        val normalized = lowercase()
        val referencesAppScope = listOf(
            "微信",
            "wechat",
            "支付宝",
            "alipay",
            "抖音",
            "douyin",
            "淘宝",
            "taobao",
            "应用",
            "app",
        ).any { it in normalized }
        val referencesScopedSettings = listOf("通知设置", "权限", "应用设置", "app settings").any { it in normalized }
        return referencesAppScope && referencesScopedSettings
    }
}

private val DEVICE_SETTINGS_NON_ACTION_DO_NOT_REGEX =
    Regex("""\b(do\s+not|don't|dont)\s+(?:open|show|go\s+to|launch)\b""")

private val DEVICE_SETTINGS_NON_ACTION_HOW_TO_REGEX =
    Regex("""\b(how\s+to|implement|design|api|code|compose)\b""")

private fun String.looksLikeDeviceSettingsNonAction(): Boolean {
    val normalized = lowercase()
    return startsWithActionNegation() ||
        listOf(
        "不要打开",
        "别打开",
        "不要进入",
        "别进入",
        "不要去",
        "在哪里",
        "在哪",
        "哪里",
        "怎么实现",
        "如何实现",
        "怎么设计",
        "代码",
        "组件",
        "接口",
    ).any { it in this } ||
        normalized.contains(DEVICE_SETTINGS_NON_ACTION_DO_NOT_REGEX) ||
        normalized.contains(DEVICE_SETTINGS_NON_ACTION_HOW_TO_REGEX)
}
