package com.bytedance.zgx.solin.action

/*
 * Rule parsers for deep links, camera, app navigation (incl. knownAppPackages) and the
 * foreground-app query.
 */

private val DEEP_LINK_NON_ACTION_DO_NOT_REGEX =
    Regex("""\b(do\s+not|don't|dont)\s+(?:open|visit|go\s+to|launch)\b""")

private val DEEP_LINK_NON_ACTION_HOW_DO_REGEX =
    Regex("""^\s*how\s+do\s+i\s+(?:open|visit|go\s+to|launch)\b""")

private val DEEP_LINK_NON_ACTION_WHAT_IS_REGEX = Regex("""^\s*(what\s+is|explain|describe)\b""")

internal object DeepLinkActionParser {
    private val uriPattern = Regex("""\bhttps://\S+""", RegexOption.IGNORE_CASE)
    private val englishOpenPattern = Regex("""\b(open|visit|go\s+to|launch)\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        if (input.looksLikeDeepLinkNonAction()) return false
        val hasUri = uriPattern.containsMatchIn(input)
        val hasOpenIntent = listOf("打开", "访问", "前往", "跳转", "进入").any { it in input } ||
            englishOpenPattern.containsMatchIn(input)
        return hasUri && hasOpenIntent
    }

    fun draft(input: String): ActionDraft {
        val uri = extractUri(input)
        return ActionDraft(
            functionName = MobileActionFunctions.OPEN_DEEP_LINK,
            title = "打开深链",
            summary = "将打开深度链接：$uri",
            parameters = mapOf("uri" to uri),
            requiresConfirmation = true,
        )
    }

    private fun extractUri(input: String): String =
        cleanUri(uriPattern.find(input)?.value.orEmpty()).ifBlank { cleanedObject(input) }

    private fun cleanUri(raw: String): String =
        raw.trim().trimEnd(*TRAILING_URI_PUNCTUATION.toCharArray())

    private fun String.looksLikeDeepLinkNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "不要打开",
            "别打开",
            "不要访问",
            "别访问",
            "解释",
            "说明",
            "什么意思",
            "是什么",
        ).any { it in this } ||
            normalized.contains(DEEP_LINK_NON_ACTION_DO_NOT_REGEX) ||
            normalized.contains(DEEP_LINK_NON_ACTION_HOW_DO_REGEX) ||
            normalized.contains(DEEP_LINK_NON_ACTION_WHAT_IS_REGEX)
    }

    private const val TRAILING_URI_PUNCTUATION = ".,;:!?)]}。！）；】"
}

private val CAMERA_NON_ACTION_DO_NOT_REGEX =
    Regex("""\b(do\s+not|don't|dont)\s+(?:open|launch|start|use)\s+(?:the\s+)?camera\b""")

private val CAMERA_NON_ACTION_WHAT_IS_REGEX =
    Regex("""\b(?:what\s+is|explain|meaning|how\s+do\s+i|how\s+to|implement|design|api|sdk|code|schema|tests?|parser|docs?|architecture|permissions?)\b.*\bcamera\b""")

internal object CameraActionParser {
    private val englishPattern =
        Regex("""\b(?:open|launch|start)\s+(?:the\s+)?camera\b|\b(?:take|capture)\s+(?:a\s+)?(?:photo|picture)\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        if (input.looksLikeCameraNonAction()) return false
        val normalized = input.lowercase()
        val hasChineseTarget = listOf("相机", "摄像头", "摄像机", "拍照").any { it in input }
        val hasChineseTrigger = listOf("打开", "启动", "开启", "拍").any { it in input }
        return (hasChineseTarget && hasChineseTrigger) || englishPattern.containsMatchIn(normalized)
    }

    fun draft(): ActionDraft =
        ActionDraft(
            functionName = MobileActionFunctions.OPEN_CAMERA,
            title = "打开相机",
            summary = "将打开系统相机应用；不会拍照、录像或读取照片。",
            parameters = emptyMap(),
            requiresConfirmation = true,
        )

    private fun String.looksLikeCameraNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
                "不要打开",
                "别打开",
                "不要启动",
                "别启动",
                "不要开启",
                "解释",
                "说明",
                "是什么",
                "什么意思",
                "怎么",
                "如何",
                "代码",
                "组件",
                "接口",
                "权限",
                "api",
                "API",
                "实现",
                "设计",
            ).any { it in this } ||
            normalized.contains(CAMERA_NON_ACTION_DO_NOT_REGEX) ||
            normalized.contains(CAMERA_NON_ACTION_WHAT_IS_REGEX)
    }
}

private val FOLLOW_UP_VERB_REGEX = Regex(
    """(搜索|搜一搜|查找|查询|找一?下|打开|进入|点击|看看|浏览|search|find|look\s+up|open|go\s+to|tap|browse)""",
    RegexOption.IGNORE_CASE,
)

private val APP_NAME_LEADING_OPEN_VERB_REGEX =
    Regex("""(?i)^\s*(?:open|launch|start)\s+(?:the\s+)?(?:app(?:lication)?\s+)?""")

private val APP_NAME_LEADING_APP_WORD_REGEX =
    Regex("""^\s*(?:应用|app|应用程序)\s*""", RegexOption.IGNORE_CASE)

private val APP_NAME_TRAILING_APP_WORD_REGEX =
    Regex("""\s*(?:应用|app|应用程序)\s*$""", RegexOption.IGNORE_CASE)

private val APP_NAME_PATH_CHARS_REGEX = Regex("""[/:\\]""")

private val APP_NAVIGATION_NON_ACTION_DO_NOT_REGEX =
    Regex("""\b(do\s+not|don't|dont)\s+(?:open|launch|start)\b""")

private val APP_NAVIGATION_NON_ACTION_OPEN_SOURCE_REGEX =
    Regex("""(?:open\s+source|start\s+using|package:|intent:|content:|file:)""")

private val APP_NAVIGATION_NON_ACTION_WHAT_IS_REGEX =
    Regex("""\b(what\s+is|explain|meaning|how\s+do\s+i|how\s+to|implement|design|api|sdk|code|schema|tests?|parser|docs?|architecture|activity|extras?|data\s+uri|intent|app\s+settings|then|after\s+that)\b""")

private val APP_WORD_REGEX = Regex("""\bapp(?:lication)?\b""", RegexOption.IGNORE_CASE)

private val SYSTEM_FUNCTION_RATHER_THAN_APP_REGEX =
    Regex("""\b(?:flashlight|torch)\b""", RegexOption.IGNORE_CASE)

private val WEB_TARGET_RATHER_THAN_APP_REGEX =
    Regex("""\b(?:web\s*page|website|link|url)\b""", RegexOption.IGNORE_CASE)

internal object AppNavigationActionParser {
    private val packageNamePattern =
        Regex("""\b[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+\b""")
    private val uriSchemePattern =
        Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://""")
    private val chineseLaunchPattern =
        Regex("""(?:打开并|打开|启动并|启动)\s*(?:应用|app|应用程序)?""")
    private val englishLaunchPattern =
        Regex("""^\s*(?:please\s+)?(?:open|launch|start)\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean = targetTool(input) != null

    fun draft(input: String): ActionDraft {
        return when (targetTool(input)) {
            MobileActionFunctions.OPEN_APP_DEEP_TARGET -> {
                val packageName = packageNameFromInput(input).orEmpty()
                ActionDraft(
                    functionName = MobileActionFunctions.OPEN_APP_DEEP_TARGET,
                    title = "打开应用深层目标",
                    summary = "将打开应用详情设置：$packageName",
                    parameters = mapOf(
                        AppDeepTargets.TARGET_ID_ARGUMENT to AppDeepTargets.APP_DETAILS_SETTINGS_ID,
                        AppDeepTargets.PACKAGE_NAME_ARGUMENT to packageName,
                    ),
                    requiresConfirmation = true,
                )
            }
            MobileActionFunctions.OPEN_APP_INTENT -> {
                val packageName = packageNamePattern.find(input)?.value.orEmpty()
                ActionDraft(
                    functionName = MobileActionFunctions.OPEN_APP_INTENT,
                    title = "打开应用",
                    summary = "将打开应用启动页：$packageName",
                    parameters = mapOf("packageName" to packageName),
                    requiresConfirmation = true,
                )
            }
            else -> {
                val appName = appNameFromInput(input).orEmpty()
                val followUp = followUpIntentFromInput(input, appName)
                ActionDraft(
                    functionName = MobileActionFunctions.OPEN_APP_BY_NAME,
                    title = "打开应用",
                    summary = if (followUp != null) {
                        "将打开 $appName 应用并${followUpSummary(followUp)}"
                    } else {
                        "将按本机应用名打开：$appName"
                    },
                    parameters = buildMap {
                        put("appName", appName)
                        if (followUp != null) put("followUpIntent", followUp)
                    },
                    requiresConfirmation = true,
                )
            }
        }
    }

    /**
     * Extract an in-app follow-up intent from "open <app> <follow-up>" style input, e.g.
     * "打开微信联系张三" -> "联系张三", "open X and message Bob" -> "message Bob".
     *
     * Returns null unless a clear follow-up verb (in zh/en) appears in the text AFTER the app token
     * — so a bare "打开微信" keeps the old open-then-stop behavior. Note that inputs whose follow-up
     * is a search ("打开小红书搜索跑鞋") are routed to the model-driven UI-search skill upstream and do
     * NOT reach this rule-based parser (they are gated out by [looksLikeAppNavigationNonAction]); this
     * extractor is a convenience for non-search follow-ups. The follow-up text stays LocalOnly and is
     * only used to steer the local action-planning model inside the opened app; it is NOT an
     * Intent/URI/extra (injection resistance unchanged).
     */
    private fun followUpIntentFromInput(input: String, appName: String): String? {
        if (appName.isBlank()) return null
        val followUpVerb = FOLLOW_UP_VERB_REGEX
        // Take the text after the app name; if the app name isn't literally present (fuzzy alias),
        // fall back to the text after the launch verb.
        val afterApp = input.substringAfter(appName, missingDelimiterValue = "")
            .ifBlank {
                input.replace(chineseLaunchPattern, " ")
                    .replace(englishLaunchPattern, " ")
            }
        val trimmed = afterApp
            .trim(' ', '，', ',', '。', '.', ':', '：', '的')
            .removePrefix("并").removePrefix("再").removePrefix("然后")
            .removePrefix("and").removePrefix("then")
            .trim()
            .trimEnd('。', '.', '！', '!')
        if (trimmed.isBlank() || trimmed.length < 2 || trimmed.length > 200) return null
        // Guard the greedy-appName degenerate case: appNameFromInput can return the whole phrase
        // (e.g. "微信查找联系人"), making the fallback follow-up echo the app name verbatim. A follow-up
        // that is the app name (or still contains it) is not a real in-app instruction — reject it so
        // we fall back to open-then-stop instead of surfacing a garbled "打开 X 并执行：X" summary.
        if (trimmed == appName || trimmed.contains(appName)) return null
        if (!followUpVerb.containsMatchIn(trimmed)) return null
        return trimmed
    }

    private fun followUpSummary(followUp: String): String =
        "执行：$followUp"

    private fun targetTool(input: String): String? {
        if (input.looksLikeAppNavigationNonAction() || uriSchemePattern.containsMatchIn(input)) return null
        if (input.looksLikeSystemFunctionRatherThanApp()) return null
        if (input.looksLikeWebTargetRatherThanApp()) return null
        if (!input.hasAppLaunchTrigger()) return null
        return if (input.isAppDetailsSettingsTarget()) {
            packageNameFromInput(input) ?: return null
            MobileActionFunctions.OPEN_APP_DEEP_TARGET
        } else if (packageNamePattern.containsMatchIn(input)) {
            MobileActionFunctions.OPEN_APP_INTENT
        } else {
            appNameFromInput(input) ?: return null
            MobileActionFunctions.OPEN_APP_BY_NAME
        }
    }

    private fun packageNameFromInput(input: String): String? {
        val normalized = input.lowercase()
        if (input.isBareAppInfoTarget()) return null
        return knownAppPackages.entries.firstNotNullOfOrNull { (alias, packageName) ->
            if (normalized.contains(alias.lowercase())) packageName else null
        } ?: packageNamePattern.find(input)?.value
    }

    private fun appNameFromInput(input: String): String? {
        if (input.isBareAppInfoTarget() || packageNamePattern.containsMatchIn(input)) return null
        val cleaned = cleanedObject(input)
            .replace(chineseLaunchPattern, "")
            .replace(APP_NAME_LEADING_OPEN_VERB_REGEX, "")
            .replace(APP_NAME_LEADING_APP_WORD_REGEX, "")
            .replace(APP_NAME_TRAILING_APP_WORD_REGEX, "")
            .trim()
            .trimEnd('。', '.', '！', '!', '，', ',')
        return cleaned.takeIf { candidate ->
            candidate.length in 1..80 &&
                candidate !in setOf("应用", "app", "应用程序", "软件") &&
                !candidate.contains(APP_NAME_PATH_CHARS_REGEX)
        }
    }

    private fun String.hasAppLaunchTrigger(): Boolean =
        chineseLaunchPattern.containsMatchIn(this) ||
            englishLaunchPattern.containsMatchIn(this)

    private fun String.isAppDetailsSettingsTarget(): Boolean {
        val normalized = lowercase()
        return listOf(
            "应用详情",
            "应用信息",
            "app info",
            "application details",
            "application info",
        ).any { it in normalized }
    }

    private fun String.isBareAppInfoTarget(): Boolean {
        if (!isAppDetailsSettingsTarget()) return false
        val normalized = lowercase()
        val hasConcreteAppAlias = knownAppPackages.keys
            .filterNot { it in genericMessagingAliases }
            .any { normalized.contains(it.lowercase()) }
        return !hasConcreteAppAlias && !packageNamePattern.containsMatchIn(this)
    }

    private fun String.looksLikeAppNavigationNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "不要打开",
            "别打开",
            "不要启动",
            "别启动",
            "不要进入",
            "别进入",
            "解释",
            "说明",
            "是什么",
            "什么意思",
            "在哪里",
            "在哪",
            "哪里",
            "怎么",
            "如何",
            "代码",
            "组件",
            "接口",
            "小程序",
            "应用设置",
            "搜索",
            "搜一下",
            "筛选",
            "然后搜索",
            "支付",
            "收款码",
            "扫一扫",
            "聊天",
            "朋友圈",
            "权限",
            "通知页",
            "通知设置",
            "打不开",
            "无法打开",
            "不能打开",
            "启动失败",
            "打开方式",
            "然后",
            "接着",
            "/",
        ).any { it in this } ||
            normalized.contains(APP_NAVIGATION_NON_ACTION_DO_NOT_REGEX) ||
            normalized.contains(APP_NAVIGATION_NON_ACTION_OPEN_SOURCE_REGEX) ||
            normalized.contains(APP_NAVIGATION_NON_ACTION_WHAT_IS_REGEX)
    }

    private fun String.looksLikeSystemFunctionRatherThanApp(): Boolean {
        val normalized = lowercase()
        val referencesExplicitApp = listOf("应用", "app", "应用程序").any { it in this } ||
            APP_WORD_REGEX.containsMatchIn(normalized)
        val referencesFlashlight = "手电筒" in this ||
            SYSTEM_FUNCTION_RATHER_THAN_APP_REGEX.containsMatchIn(normalized)
        return referencesFlashlight && !referencesExplicitApp
    }

    private fun String.looksLikeWebTargetRatherThanApp(): Boolean {
        val normalized = lowercase()
        val referencesExplicitApp = listOf("应用", "app", "应用程序").any { it in this } ||
            APP_WORD_REGEX.containsMatchIn(normalized)
        val referencesWebTarget = listOf("网页", "网站", "链接", "网址").any { it in this } ||
            WEB_TARGET_RATHER_THAN_APP_REGEX.containsMatchIn(normalized)
        return referencesWebTarget && !referencesExplicitApp
    }

    private val knownAppPackages = mapOf(
        "微信" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "支付宝" to "com.eg.android.AlipayGphone",
        "alipay" to "com.eg.android.AlipayGphone",
        "抖音" to "com.ss.android.ugc.aweme",
        "douyin" to "com.ss.android.ugc.aweme",
        "哔哩哔哩" to "tv.danmaku.bili",
        "bilibili" to "tv.danmaku.bili",
        "淘宝" to "com.taobao.taobao",
        "taobao" to "com.taobao.taobao",
        "拼多多" to "com.xunmeng.pinduoduo",
        "pinduoduo" to "com.xunmeng.pinduoduo",
        "pdd" to "com.xunmeng.pinduoduo",
        "京东" to "com.jingdong.app.mall",
        "jd" to "com.jingdong.app.mall",
        "美团" to "com.sankuai.meituan",
        "meituan" to "com.sankuai.meituan",
        "大众点评" to "com.dianping.v1",
        "dianping" to "com.dianping.v1",
        "小红书" to "com.xingin.xhs",
        "xiaohongshu" to "com.xingin.xhs",
        "rednote" to "com.xingin.xhs",
        "网易云音乐" to "com.netease.cloudmusic",
        "网易云" to "com.netease.cloudmusic",
        "qq音乐" to "com.tencent.qqmusic",
        "携程" to "ctrip.android.view",
        "ctrip" to "ctrip.android.view",
        "饿了么" to "me.ele",
        "eleme" to "me.ele",
        "高德地图" to "com.autonavi.minimap",
        "高德" to "com.autonavi.minimap",
        "amap" to "com.autonavi.minimap",
        "gaode" to "com.autonavi.minimap",
        "autonavi" to "com.autonavi.minimap",
        "谷歌地图" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "google map" to "com.google.android.apps.maps",
        "maps" to "com.google.android.apps.maps",
        "地图" to "com.autonavi.minimap",
        "浏览器" to "com.android.browser",
        "browser" to "com.android.browser",
        "夸克" to "com.quark.browser",
        "quark" to "com.quark.browser",
        "日历" to "com.android.calendar",
        "calendar" to "com.android.calendar",
        "邮件" to "com.android.email",
        "邮箱" to "com.android.email",
        "email" to "com.android.email",
        "mail" to "com.android.email",
        "相册" to "com.miui.gallery",
        "图库" to "com.miui.gallery",
        "gallery" to "com.miui.gallery",
        "photos" to "com.miui.gallery",
        "计算器" to "com.miui.calculator",
        "calculator" to "com.miui.calculator",
        "时钟" to "com.android.deskclock",
        "闹钟" to "com.android.deskclock",
        "clock" to "com.android.deskclock",
        "alarm" to "com.android.deskclock",
        "便签" to "com.miui.notes",
        "笔记" to "com.miui.notes",
        "notes" to "com.miui.notes",
        "note" to "com.miui.notes",
        "通讯录" to "com.android.contacts",
        "联系人" to "com.android.contacts",
        "电话" to "com.android.contacts",
        "拨号" to "com.android.contacts",
        "contacts" to "com.android.contacts",
        "dialer" to "com.android.contacts",
        "phone" to "com.android.contacts",
        "信息" to "com.android.mms",
        "短信" to "com.android.mms",
        "messages" to "com.android.mms",
        "messaging" to "com.android.mms",
        "sms" to "com.android.mms",
    )

    private val genericMessagingAliases = setOf(
        "信息",
        "短信",
        "messages",
        "messaging",
        "sms",
    )
}

private val FOREGROUND_APP_NON_ACTION_NOTIFICATIONS_REGEX = Regex("""\bnotifications?\b""")

private val FOREGROUND_APP_NON_ACTION_FOREGROUND_SERVICES_REGEX =
    Regex("""\bforeground\s+services?\b""")

private val FOREGROUND_APP_NON_ACTION_CURRENT_FOREGROUND_REGEX =
    Regex("""\b(current|foreground|active)\s+app\s+(architecture|module|component|code|api|screen|state)\b""")

private val FOREGROUND_APP_NON_ACTION_HOW_DO_REGEX =
    Regex("""\b(how\s+do\s+i|how\s+to|implement|design)\b.*\b(current|foreground|active)\s+app\b""")

internal object ForegroundAppActionParser {
    private val englishPattern =
        Regex(
            """\b(what\s+(?:app|application)\s+is\s+(?:currently\s+)?(?:open|active|foreground)|current\s+(?:foreground\s+)?app|foreground\s+app|active\s+app)\b""",
            RegexOption.IGNORE_CASE,
        )

    fun matches(input: String): Boolean {
        if (input.looksLikeForegroundAppNonAction()) return false
        val normalized = input.lowercase()
        val hasChineseTrigger = listOf(
            "当前应用",
            "当前 app",
            "当前app",
            "当前前台应用",
            "前台应用",
            "前台 app",
            "前台app",
            "正在打开的应用",
            "现在打开的应用",
        ).any { it in input }
        return hasChineseTrigger || englishPattern.containsMatchIn(normalized)
    }

    fun draft(): ActionDraft =
        ActionDraft(
            functionName = MobileActionFunctions.QUERY_FOREGROUND_APP,
            title = "查询当前前台应用",
            summary = "将通过 UsageStats 估计当前前台应用（包名与应用名）；不读取屏幕内容或使用历史。",
            parameters = emptyMap(),
            requiresConfirmation = true,
        )

    private fun String.looksLikeForegroundAppNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "前台服务",
            "前台任务",
            "前台进程",
            "后台前台",
            "架构",
            "代码",
            "模块",
            "组件",
            "接口",
            "权限",
            "api",
            "API",
            "通知",
            "怎么实现",
            "如何实现",
            "怎么设计",
        ).any { it in this } ||
            normalized.contains(FOREGROUND_APP_NON_ACTION_NOTIFICATIONS_REGEX) ||
            normalized.contains(FOREGROUND_APP_NON_ACTION_FOREGROUND_SERVICES_REGEX) ||
            normalized.contains(FOREGROUND_APP_NON_ACTION_CURRENT_FOREGROUND_REGEX) ||
            normalized.contains(FOREGROUND_APP_NON_ACTION_HOW_DO_REGEX)
    }
}
