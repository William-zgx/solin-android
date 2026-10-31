package com.bytedance.zgx.solin.device

/**
 * Data-only interaction profiles for lifestyle, content, media, travel and system-settings apps.
 *
 * Appended to [AppInteractionProfiles.profiles]; kept in their own file so the resolver file does not
 * keep growing with per-app data. Every entry is pure hint data consumed by the existing scoring
 * ([UiTargetResolver]), search-context guards and [AppSearchResultVerifier]; nothing here changes
 * risk, confirmation or dangerous-control handling.
 *
 * Hint hygiene (why the sets look the way they do):
 * - `searchEntryHints` only carry phrases that contain 搜 / search. `phraseScore` also matches a SHORT
 *   label contained in a hint, so a hint like "搜索歌曲、歌手" would hand every 2-char "歌手" tab
 *   search-entry evidence.
 * - `resultHints` prefer result-page-only vocabulary (sort/filter bars, result-type tabs). The verifier
 *   accepts "page changed + two result hints" without the query being visible, so hints that also sit
 *   on the app's home page (e.g. Ctrip's 酒店/机票 tiles) would let an unrelated page change verify.
 * - Submit behaviour is noted per app. It is informational: the runtime already tries the IME search
 *   action first and then the nearest "搜索" button, which covers every app below.
 *
 * `uiNotes` are short UI facts for planner prompts ([appUiNotesFor]); they never feed scoring or
 * verification. Rules (pinned by AppInteractionProfileCatalogTest): facts only, no instructions, at
 * most [MAX_APP_UI_NOTES] notes of at most [MAX_APP_UI_NOTE_CHARS] chars, and never phrased as an
 * order to send, pay, delete or confirm. App UI notes informed by Mika's knowledge files (Apache-2.0),
 * rewritten.
 *
 * [contentAndOfficeProfiles] are minimal profiles (package, aliases, notes, plain 搜索 hints) for apps
 * that had no profile before; they only add search-entry evidence for their own package.
 *
 * Package names are the publicly distributed Android application ids. Real-device acceptance for these
 * profiles is pending (docs/phone_acceptance.md); the JVM coverage is synthetic fixtures only.
 */
internal object AppInteractionProfileCatalog {
    val lifestyleAndMediaProfiles: List<AppInteractionProfile> = listOf(
        // 美团: home shows a non-editable search bar that opens a dedicated search page; submit is a
        // "搜索" text button right of the field (IME search also works).
        AppInteractionProfile(
            appNameAliases = setOf("美团", "meituan"),
            packageNames = setOf("com.sankuai.meituan"),
            searchEntryHints = setOf("搜索", "美团搜索", "搜索商家", "搜索商家或商品", "搜索美食"),
            submitHints = setOf("搜索"),
            resultHints = setOf("综合排序", "距离优先", "销量优先", "好评优先", "筛选", "人均"),
            uiNotes = listOf(
                "首页顶部搜索栏点开后是单独的搜索页",
                "首页宫格是外卖、美食、酒店等频道入口",
                "结果页顶部有综合排序、距离优先等排序项",
            ),
        ),
        // 大众点评: search bar on the home header; submit via "搜索" button or IME.
        AppInteractionProfile(
            appNameAliases = setOf("大众点评", "点评", "dianping"),
            packageNames = setOf("com.dianping.v1"),
            searchEntryHints = setOf("搜索", "搜索商户", "搜索商户地点", "搜索商户、地点"),
            submitHints = setOf("搜索"),
            resultHints = setOf("智能排序", "离我最近", "好评优先", "全部分类", "筛选", "人均"),
            uiNotes = listOf(
                "首页顶部搜索框可搜商户和地点",
                "结果页顶部有智能排序、离我最近等排序栏",
                "商户详情页有评分、人均价格和评价列表",
            ),
        ),
        // 小红书: magnifier icon (content-desc 搜索) in the top bar opens the search page; submit is a
        // "搜索" text button right of the field.
        AppInteractionProfile(
            appNameAliases = setOf("小红书", "xiaohongshu", "xhs", "rednote"),
            packageNames = setOf("com.xingin.xhs"),
            searchEntryHints = setOf("搜索", "搜索笔记", "搜索你感兴趣的内容"),
            submitHints = setOf("搜索"),
            resultHints = setOf("全部", "用户", "商品", "最新", "最热", "筛选"),
            uiNotes = listOf(
                "顶栏的放大镜图标打开搜索页",
                "首页笔记卡片两列排布，点开是笔记详情",
                "搜索结果混有笔记、用户和商品卡片，样子相近",
                "笔记详情下方是点赞、收藏和评论计数",
            ),
        ),
        // 哔哩哔哩: home header search box (often pre-filled with a trending suggestion); submit via the
        // "搜索" button or IME search. Home channel tabs (直播/影视) are deliberately NOT result hints.
        AppInteractionProfile(
            appNameAliases = setOf("哔哩哔哩", "b站", "bilibili", "bili"),
            packageNames = setOf("tv.danmaku.bili"),
            searchEntryHints = setOf("搜索", "搜索视频", "搜索框"),
            submitHints = setOf("搜索"),
            resultHints = setOf("综合", "番剧", "用户", "专栏", "默认排序", "筛选"),
            uiNotes = listOf(
                "首页搜索框常预填热搜词，并非用户输入",
                "底部标签含首页、动态和我的",
                "搜索结果页有综合、番剧、用户等分类标签",
                "视频页播放器下方分简介和评论两栏",
            ),
        ),
        // 抖音: magnifier icon (content-desc 搜索) top-right of the feed; submit is a "搜索" text button.
        // 直播 is also a home-feed tab, so it is NOT a result hint.
        AppInteractionProfile(
            appNameAliases = setOf("抖音", "douyin"),
            packageNames = setOf("com.ss.android.ugc.aweme"),
            searchEntryHints = setOf("搜索", "搜索框", "搜索你感兴趣的内容"),
            submitHints = setOf("搜索"),
            resultHints = setOf("综合", "视频", "用户", "商品", "图文", "话题"),
            uiNotes = listOf(
                "首页是全屏竖屏视频，上下滑切换",
                "搜索入口是首页右上角的放大镜图标",
                "视频右侧竖排点赞、评论、收藏、分享图标",
                "底部标签含首页、朋友、消息和我，中间是「+」",
            ),
        ),
        // 网易云音乐: header search box; submit via IME search (no visible button on most versions).
        // 歌单/专辑/播客 also label home-page shortcuts, so they are NOT result hints.
        AppInteractionProfile(
            appNameAliases = setOf("网易云音乐", "网易云", "cloudmusic", "netease cloud music"),
            packageNames = setOf("com.netease.cloudmusic"),
            searchEntryHints = setOf("搜索", "搜索音乐", "搜索歌曲"),
            submitHints = setOf("搜索"),
            resultHints = setOf("综合", "单曲", "歌手", "歌词"),
            uiNotes = listOf(
                "顶部搜索框常预填推荐词，并非用户输入",
                "搜索结果分单曲、歌手、歌词等标签",
                "底部常驻迷你播放条，点开是全屏播放页",
            ),
        ),
        // QQ音乐: header search box; submit via IME search or the "搜索" button.
        // 歌手/歌单/专辑 also label home-page shortcuts, so they are NOT result hints.
        AppInteractionProfile(
            appNameAliases = setOf("qq音乐", "qqmusic", "qq music"),
            packageNames = setOf("com.tencent.qqmusic"),
            searchEntryHints = setOf("搜索", "搜索音乐", "搜索歌曲"),
            submitHints = setOf("搜索"),
            resultHints = setOf("综合", "单曲", "歌词", "播放全部"),
            uiNotes = listOf(
                "顶部搜索框常预填推荐词，并非用户输入",
                "搜索结果列表上方有「播放全部」按钮",
                "底部常驻迷你播放条，显示当前歌曲",
            ),
        ),
        // 携程旅行: home header search box opens a global search page; submit via the "搜索" button.
        // Home-page tiles (酒店/机票/火车票/攻略) are deliberately NOT result hints.
        AppInteractionProfile(
            appNameAliases = setOf("携程", "携程旅行", "ctrip"),
            packageNames = setOf("ctrip.android.view"),
            searchEntryHints = setOf("搜索", "搜索目的地", "搜索目的地酒店景点"),
            submitHints = setOf("搜索"),
            resultHints = setOf("全部", "筛选", "综合排序", "智能排序", "门票"),
            uiNotes = listOf(
                "首页顶部搜索框进入全站搜索页",
                "首页宫格是酒店、机票、火车票等频道入口",
                "城市和日期在各频道页里分别选择",
            ),
        ),
        // 饿了么: home search bar opens the search page; submit via the "搜索" button.
        // Shop-card words (月售/起送/配送) also fill the home feed, so they are NOT result hints.
        AppInteractionProfile(
            appNameAliases = setOf("饿了么", "eleme"),
            packageNames = setOf("me.ele"),
            searchEntryHints = setOf("搜索", "搜索商家", "搜索商家或商品", "搜索饿了么商家商品"),
            submitHints = setOf("搜索"),
            resultHints = setOf("综合排序", "距离最近", "销量最高", "速度最快", "筛选"),
            uiNotes = listOf(
                "首页顶部搜索栏点开后是单独的搜索页",
                "结果页顶部有综合排序、距离最近等排序栏",
                "商家卡片显示月售、起送价和配送时间",
            ),
        ),
        // Android 设置: "搜索设置" field at the top of the Settings home. Search is INCREMENTAL — results
        // appear while typing and there is usually no submit button; IME search is harmless. Pixel
        // devices host the search page in the Settings Intelligence package.
        AppInteractionProfile(
            appNameAliases = setOf("设置", "系统设置", "settings", "android settings"),
            packageNames = setOf("com.android.settings", "com.google.android.settings.intelligence"),
            searchEntryHints = setOf("搜索", "搜索设置", "搜索设置项", "在设置中搜索", "search settings", "search"),
            submitHints = setOf("搜索", "search"),
            resultHints = setOf("搜索结果", "search results"),
            uiNotes = listOf(
                "开关项一点就立即切换状态，没有二次询问",
                "开关状态有时只靠颜色和位置表示，没有开关文字",
                "首页顶部的搜索设置框边输入边列出结果",
                "菜单名称和层级随厂商与系统版本而不同",
                "关于手机页显示型号、系统版本等设备信息",
            ),
        ),
    )

    /**
     * Minimal profiles for apps that had none: aliases, package, plain "搜索" entry/submit hints and UI
     * notes. `resultHints` stay empty on purpose, so search verification for these apps needs the
     * query itself on the result page (no "page changed + hints" shortcut).
     */
    val contentAndOfficeProfiles: List<AppInteractionProfile> = listOf(
        AppInteractionProfile(
            appNameAliases = setOf("微信", "wechat", "weixin"),
            packageNames = setOf("com.tencent.mm"),
            searchEntryHints = setOf("搜索"),
            submitHints = setOf("搜索"),
            resultHints = emptySet(),
            uiNotes = listOf(
                "底部标签：微信、通讯录、发现、我",
                "聊天输入框里留着的文字只是草稿，并未发出",
                "消息发出后才会在会话里显示为本人的气泡",
                "会话标题栏显示实际的聊天对象或群名",
                "顶部搜索结果按联系人、群聊、聊天记录等分组",
            ),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("快手", "kuaishou", "kwai"),
            packageNames = setOf("com.smile.gifmaker"),
            searchEntryHints = setOf("搜索"),
            submitHints = setOf("搜索"),
            resultHints = emptySet(),
            uiNotes = listOf(
                "首页右上角的「查找」是搜索入口",
                "底部标签含首页、精选、消息和我，中间是拍摄",
                "精选页是全屏竖屏视频，上下滑切换",
                "页面上常悬浮一个带关闭按钮的红包挂件",
                "搜索框里预先显示的是推荐词，并非用户输入",
            ),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("知乎", "zhihu"),
            packageNames = setOf("com.zhihu.android"),
            searchEntryHints = setOf("搜索"),
            submitHints = setOf("搜索"),
            resultHints = emptySet(),
            uiNotes = listOf(
                "首页顶部有搜索框，下方是推荐信息流",
                "问题页下列出多条回答，每条带作者和赞同数",
                "问题页的长回答常只显示开头，展开后才是全文",
            ),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("微博", "weibo", "新浪微博"),
            packageNames = setOf("com.sina.weibo"),
            searchEntryHints = setOf("搜索"),
            submitHints = setOf("搜索"),
            resultHints = emptySet(),
            uiNotes = listOf(
                "底部标签通常含首页、发现、消息和我",
                "热搜榜和搜索框在发现页",
                "每条微博下方显示转发、评论和点赞数",
            ),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("闲鱼", "xianyu", "goofish"),
            packageNames = setOf("com.taobao.idlefish"),
            searchEntryHints = setOf("搜索"),
            submitHints = setOf("搜索"),
            resultHints = emptySet(),
            uiNotes = listOf(
                "首页顶部有搜索框，可搜二手商品",
                "商品价格和描述由发布者自己填写",
                "商品页的「聊一聊」打开与卖家的私信会话",
            ),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("今日头条", "头条", "toutiao"),
            packageNames = setOf("com.ss.android.article.news"),
            searchEntryHints = setOf("搜索"),
            submitHints = setOf("搜索"),
            resultHints = emptySet(),
            uiNotes = listOf(
                "首页搜索框轮换显示热门词，并非用户输入",
                "底部标签含首页、视频和商城，中间是「+」",
                "未登录时启动常先显示登录页，左上角有关闭",
                "信息流每条显示标题、来源和评论数",
            ),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("飞书", "feishu", "lark"),
            packageNames = setOf("com.ss.android.lark"),
            searchEntryHints = setOf("搜索"),
            submitHints = setOf("搜索"),
            resultHints = emptySet(),
            uiNotes = listOf(
                "包含消息、日历、云文档和视频会议等模块",
                "底部导航含消息、日历、云文档等，可自定义",
                "顶部搜索可查找联系人、群组和文档",
            ),
        ),
    )
}
