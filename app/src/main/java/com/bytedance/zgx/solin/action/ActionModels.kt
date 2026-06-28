package com.bytedance.zgx.solin.action

data class ActionDraft(
    val functionName: String,
    val title: String,
    val summary: String,
    val parameters: Map<String, String>,
    val requiresConfirmation: Boolean = true,
)

enum class ActionIntentConfidence {
    None,
    Low,
    Medium,
    High,
}

data class IntentCandidate(
    val toolName: String?,
    val confidence: ActionIntentConfidence,
    val reason: String,
) {
    val isAction: Boolean
        get() = confidence == ActionIntentConfidence.Medium || confidence == ActionIntentConfidence.High
}

enum class IntentRoutingPath {
    SkillFirst,
    ActionPlanner,
    RemoteToolPlanning,
    ModelToolCall,
    NoAction,
}

data class IntentRoutingDecision(
    val input: String,
    val selectedPath: IntentRoutingPath,
    val selectedToolName: String?,
    val selectedSkillId: String? = null,
    val priority: Int,
    val accepted: Boolean,
    val confidence: ActionIntentConfidence = ActionIntentConfidence.None,
    val rejectionReasons: List<String> = emptyList(),
    val requiresConfirmation: Boolean? = null,
) {
    init {
        require(input.isNotBlank()) { "Intent routing input must not be blank" }
        require(priority >= 0) { "Intent routing priority must be >= 0" }
        require(rejectionReasons.all { it.isNotBlank() }) { "Intent routing rejection reasons must not be blank" }
        if (accepted) {
            require(!selectedToolName.isNullOrBlank() || !selectedSkillId.isNullOrBlank()) {
                "Accepted intent routing decisions must select a tool or skill"
            }
        } else {
            require(rejectionReasons.isNotEmpty()) { "Rejected intent routing decisions must explain why" }
        }
    }
}

enum class ActionPlanKind {
    NoAction,
    Draft,
    MissingModel,
}

data class ActionPlan(
    val kind: ActionPlanKind,
    val draft: ActionDraft? = null,
)

object MobileActionFunctions {
    const val OPEN_WIFI_SETTINGS = "open_wifi_settings"
    const val OPEN_USAGE_ACCESS_SETTINGS = "open_usage_access_settings"
    const val OPEN_SYSTEM_SETTINGS = "open_system_settings"
    const val SEARCH_MAPS = "search_maps"
    const val WEB_SEARCH = "web_search"
    const val COMPOSE_EMAIL = "compose_email"
    const val CREATE_CALENDAR_EVENT = "create_calendar_event"
    const val CREATE_CONTACT_DRAFT = "create_contact_draft"
    const val QUERY_CONTACTS = "query_contacts"
    const val OPEN_FLASHLIGHT_SETTINGS = "open_flashlight_settings"
    const val SCHEDULE_REMINDER = "schedule_reminder"
    const val SET_SYSTEM_ALARM = "set_system_alarm"
    const val SET_SYSTEM_TIMER = "set_system_timer"
    const val CONFIGURE_PERIODIC_CHECK = "configure_periodic_check"
    const val QUERY_BACKGROUND_TASKS = "query_background_tasks"
    const val READ_CLIPBOARD = "read_clipboard"
    const val SHARE_TEXT = "share_text"
    const val OPEN_DEEP_LINK = "open_deep_link"
    const val OPEN_CAMERA = "open_camera"
    const val OPEN_APP_BY_NAME = "open_app_by_name"
    const val OPEN_APP_INTENT = "open_app_intent"
    const val OPEN_APP_DEEP_TARGET = "open_app_deep_target"
    const val QUERY_CALENDAR_AVAILABILITY = "query_calendar_availability"
    const val QUERY_FOREGROUND_APP = "query_foreground_app"
    const val QUERY_RECENT_NOTIFICATIONS = "query_recent_notifications"
    const val QUERY_RECENT_FILES = "query_recent_files"
    const val READ_RECENT_SCREENSHOT_OCR = "read_recent_screenshot_ocr"
    const val READ_RECENT_IMAGE_OCR = "read_recent_image_ocr"
    const val READ_CURRENT_SCREEN_TEXT = "read_current_screen_text"
    const val CAPTURE_CURRENT_SCREENSHOT_OCR = "capture_current_screenshot_ocr"
    const val OBSERVE_CURRENT_SCREEN = "observe_current_screen"
    const val UI_TAP = "ui_tap"
    const val UI_TYPE_TEXT = "ui_type_text"
    const val UI_SUBMIT_SEARCH = "ui_submit_search"
    const val UI_SCROLL = "ui_scroll"
    const val UI_PRESS_BACK = "ui_press_back"
    const val UI_WAIT = "ui_wait"
    const val CANCEL_REMINDER = "cancel_reminder"

    val supported: Set<String> = setOf(
        OPEN_WIFI_SETTINGS,
        OPEN_USAGE_ACCESS_SETTINGS,
        OPEN_SYSTEM_SETTINGS,
        SEARCH_MAPS,
        WEB_SEARCH,
        COMPOSE_EMAIL,
        CREATE_CALENDAR_EVENT,
        CREATE_CONTACT_DRAFT,
        QUERY_CONTACTS,
        QUERY_RECENT_FILES,
        READ_RECENT_SCREENSHOT_OCR,
        READ_RECENT_IMAGE_OCR,
        READ_CURRENT_SCREEN_TEXT,
        CAPTURE_CURRENT_SCREENSHOT_OCR,
        OBSERVE_CURRENT_SCREEN,
        UI_TAP,
        UI_TYPE_TEXT,
        UI_SUBMIT_SEARCH,
        UI_SCROLL,
        UI_PRESS_BACK,
        UI_WAIT,
        OPEN_FLASHLIGHT_SETTINGS,
        SCHEDULE_REMINDER,
        SET_SYSTEM_ALARM,
        SET_SYSTEM_TIMER,
        CONFIGURE_PERIODIC_CHECK,
        QUERY_BACKGROUND_TASKS,
        READ_CLIPBOARD,
        SHARE_TEXT,
        OPEN_DEEP_LINK,
        OPEN_CAMERA,
        OPEN_APP_BY_NAME,
        OPEN_APP_INTENT,
        OPEN_APP_DEEP_TARGET,
        QUERY_CALENDAR_AVAILABILITY,
        QUERY_FOREGROUND_APP,
        QUERY_RECENT_NOTIFICATIONS,
        CANCEL_REMINDER,
    )
}

object SystemSettingsTargets {
    const val GENERAL = "general"
    const val BLUETOOTH = "bluetooth"
    const val LOCATION = "location"
    const val NOTIFICATION = "notification"
    const val DISPLAY = "display"
    const val SOUND = "sound"
    const val BATTERY_SAVER = "battery_saver"
    const val NETWORK = "network"
    const val AIRPLANE_MODE = "airplane_mode"
    const val INPUT_METHOD = "input_method"
    const val ACCESSIBILITY = "accessibility"

    val supported: Set<String> = setOf(
        GENERAL,
        BLUETOOTH,
        LOCATION,
        NOTIFICATION,
        DISPLAY,
        SOUND,
        BATTERY_SAVER,
        NETWORK,
        AIRPLANE_MODE,
        INPUT_METHOD,
        ACCESSIBILITY,
    )

    val confirmationBypassEligible: Set<String> = supported - ACCESSIBILITY

    fun pageLabel(target: String): String =
        when (target) {
            GENERAL -> "系统设置"
            BLUETOOTH -> "蓝牙设置"
            LOCATION -> "定位设置"
            NOTIFICATION -> "通知设置"
            DISPLAY -> "显示设置"
            SOUND -> "声音设置"
            BATTERY_SAVER -> "省电设置"
            NETWORK -> "网络设置"
            AIRPLANE_MODE -> "飞行模式设置"
            INPUT_METHOD -> "输入法设置"
            ACCESSIBILITY -> "无障碍设置"
            else -> "系统设置"
        }
}
