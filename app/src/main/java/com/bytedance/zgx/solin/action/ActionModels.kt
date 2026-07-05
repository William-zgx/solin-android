package com.bytedance.zgx.solin.action

data class ActionDraft(
    val functionName: String,
    val title: String,
    val summary: String,
    val parameters: Map<String, String>,
    val requiresConfirmation: Boolean = true,
    /**
     * Model-annotated sensitivity reason. When non-null, the SafetyPolicy upgrades
     * the decision to RequireConfirmation with this reason appended. This is the
     * model's way of saying "this action might need extra care" (e.g. "涉及支付").
     *
     * Inspired by Open-AutoGLM's Tap with message="涉及支付" triggering confirmation_callback.
     * Can only INCREASE safety requirements, never decrease — preserves fail-closed.
     */
    val sensitivityReason: String? = null,
)

/**
 * Normalized 0-1000 coordinate for UI targeting.
 *
 * Inspired by Open-AutoGLM's normalized coordinate system where the model outputs
 * relative coordinates (0-1000 in each dimension) and the execution layer maps
 * them to absolute pixel coordinates based on the actual screen resolution.
 *
 * This makes the model's targeting resolution-agnostic: (500, 500) always means
 * the center of the screen regardless of device pixel density.
 */
data class NormalizedTarget(val x: Int, val y: Int) {
    init {
        require(x in 0..1000) { "NormalizedTarget x must be in 0..1000, got $x" }
        require(y in 0..1000) { "NormalizedTarget y must be in 0..1000, got $y" }
    }

    /** Convert to absolute pixel coordinates for the given screen dimensions. */
    fun toAbsolutePixels(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val absX = (x * screenWidth) / 1000
        val absY = (y * screenHeight) / 1000
        return absX to absY
    }

    companion object {
        /** Try to parse from "targetX" and "targetY" argument map entries. */
        fun fromArguments(arguments: Map<String, String>): NormalizedTarget? {
            val x = arguments["targetX"]?.toIntOrNull() ?: return null
            val y = arguments["targetY"]?.toIntOrNull() ?: return null
            return runCatching { NormalizedTarget(x, y) }.getOrNull()
        }
    }
}

/**
 * Perception result from a per-step screenshot capture.
 *
 * Inspired by Open-AutoGLM's per-step screenshot perception: after each action, capture
 * a screenshot and feed it to a vision model for richer understanding of the current screen
 * state. This complements the Accessibility tree observation with visual information that
 * Accessibility nodes may miss (e.g., custom-drawn content, images, canvas-based UIs).
 *
 * The actual vision model inference is NOT performed here — this data class only carries
 * the captured screenshot metadata. The model inference happens in the observation replanner
 * or a dedicated vision perception step.
 *
 * @property widthPx Screenshot width in pixels
 * @property heightPx Screenshot height in pixels
 * @property captureTimeMillis Wall-clock time of capture (for ordering/dead-loop detection)
 * @property ocrText Optional OCR text extracted from the screenshot (if OCR ran locally)
 * @property visionSummary Optional vision model summary of the screenshot content
 */
data class ScreenshotPerception(
    val widthPx: Int,
    val heightPx: Int,
    val captureTimeMillis: Long,
    val ocrText: String? = null,
    val visionSummary: String? = null,
) {
    /** Non-empty if we have any visual perception data to feed the model. */
    val hasPerceptionData: Boolean
        get() = !ocrText.isNullOrBlank() || !visionSummary.isNullOrBlank()

    /** Format for inclusion in observation prompt context. */
    fun formatForPrompt(): String = buildString {
        append("screenshotPerception(")
        append("size=${widthPx}x${heightPx}")
        if (!ocrText.isNullOrBlank()) {
            append(",ocr=")
            append(ocrText.take(200))
        }
        if (!visionSummary.isNullOrBlank()) {
            append(",vision=")
            append(visionSummary.take(200))
        }
        append(")")
    }
}

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
    const val ASK_USER = "ask_user"

    // ── Open-AutoGLM-inspired expanded action vocabulary ──
    /** Per-run scratchpad note. Records page content for later reference within the same run. */
    const val NOTE = "note"
    /** Explicit run termination with a summary message. */
    const val FINISH = "finish"
    /** Human takeover: hand control back to the user for login/captcha scenarios. */
    const val TAKE_OVER = "take_over"

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
        // NOTE: `ask_user`, `note`, `finish`, and `take_over` are registered in ToolRegistry
        // (builtInToolSpecs) and intercepted by AgentLoopRuntime at plan-apply time, but they are
        // NOT dispatched by BuiltInSkillRuntime — they are orchestration-level tools available to
        // the model in any skill context. They are therefore intentionally omitted from
        // MobileActionFunctions.supported (which tracks tools covered by at least one built-in
        // skill manifest).
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
