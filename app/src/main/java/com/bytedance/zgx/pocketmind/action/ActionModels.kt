package com.bytedance.zgx.pocketmind.action

data class ActionDraft(
    val functionName: String,
    val title: String,
    val summary: String,
    val parameters: Map<String, String>,
    val requiresConfirmation: Boolean = true,
)

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
    const val SEARCH_MAPS = "search_maps"
    const val WEB_SEARCH = "web_search"
    const val COMPOSE_EMAIL = "compose_email"
    const val CREATE_CALENDAR_EVENT = "create_calendar_event"
    const val CREATE_CONTACT_DRAFT = "create_contact_draft"
    const val QUERY_CONTACTS = "query_contacts"
    const val OPEN_FLASHLIGHT_SETTINGS = "open_flashlight_settings"
    const val SCHEDULE_REMINDER = "schedule_reminder"
    const val READ_CLIPBOARD = "read_clipboard"
    const val SHARE_TEXT = "share_text"
    const val OPEN_DEEP_LINK = "open_deep_link"
    const val OPEN_APP_INTENT = "open_app_intent"
    const val QUERY_CALENDAR_AVAILABILITY = "query_calendar_availability"
    const val QUERY_FOREGROUND_APP = "query_foreground_app"
    const val QUERY_RECENT_NOTIFICATIONS = "query_recent_notifications"
    const val QUERY_RECENT_FILES = "query_recent_files"
    const val CANCEL_REMINDER = "cancel_reminder"

    val supported: Set<String> = setOf(
        OPEN_WIFI_SETTINGS,
        SEARCH_MAPS,
        WEB_SEARCH,
        COMPOSE_EMAIL,
        CREATE_CALENDAR_EVENT,
        CREATE_CONTACT_DRAFT,
        QUERY_CONTACTS,
        QUERY_RECENT_FILES,
        OPEN_FLASHLIGHT_SETTINGS,
        SCHEDULE_REMINDER,
        READ_CLIPBOARD,
        SHARE_TEXT,
        OPEN_DEEP_LINK,
        OPEN_APP_INTENT,
        QUERY_CALENDAR_AVAILABILITY,
        QUERY_FOREGROUND_APP,
        QUERY_RECENT_NOTIFICATIONS,
        CANCEL_REMINDER,
    )
}
