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
        ),
        // 大众点评: search bar on the home header; submit via "搜索" button or IME.
        AppInteractionProfile(
            appNameAliases = setOf("大众点评", "点评", "dianping"),
            packageNames = setOf("com.dianping.v1"),
            searchEntryHints = setOf("搜索", "搜索商户", "搜索商户地点", "搜索商户、地点"),
            submitHints = setOf("搜索"),
            resultHints = setOf("智能排序", "离我最近", "好评优先", "全部分类", "筛选", "人均"),
        ),
        // 小红书: magnifier icon (content-desc 搜索) in the top bar opens the search page; submit is a
        // "搜索" text button right of the field.
        AppInteractionProfile(
            appNameAliases = setOf("小红书", "xiaohongshu", "xhs", "rednote"),
            packageNames = setOf("com.xingin.xhs"),
            searchEntryHints = setOf("搜索", "搜索笔记", "搜索你感兴趣的内容"),
            submitHints = setOf("搜索"),
            resultHints = setOf("全部", "用户", "商品", "最新", "最热", "筛选"),
        ),
        // 哔哩哔哩: home header search box (often pre-filled with a trending suggestion); submit via the
        // "搜索" button or IME search. Home channel tabs (直播/影视) are deliberately NOT result hints.
        AppInteractionProfile(
            appNameAliases = setOf("哔哩哔哩", "b站", "bilibili", "bili"),
            packageNames = setOf("tv.danmaku.bili"),
            searchEntryHints = setOf("搜索", "搜索视频", "搜索框"),
            submitHints = setOf("搜索"),
            resultHints = setOf("综合", "番剧", "用户", "专栏", "默认排序", "筛选"),
        ),
        // 抖音: magnifier icon (content-desc 搜索) top-right of the feed; submit is a "搜索" text button.
        // 直播 is also a home-feed tab, so it is NOT a result hint.
        AppInteractionProfile(
            appNameAliases = setOf("抖音", "douyin"),
            packageNames = setOf("com.ss.android.ugc.aweme"),
            searchEntryHints = setOf("搜索", "搜索框", "搜索你感兴趣的内容"),
            submitHints = setOf("搜索"),
            resultHints = setOf("综合", "视频", "用户", "商品", "图文", "话题"),
        ),
        // 网易云音乐: header search box; submit via IME search (no visible button on most versions).
        // 歌单/专辑/播客 also label home-page shortcuts, so they are NOT result hints.
        AppInteractionProfile(
            appNameAliases = setOf("网易云音乐", "网易云", "cloudmusic", "netease cloud music"),
            packageNames = setOf("com.netease.cloudmusic"),
            searchEntryHints = setOf("搜索", "搜索音乐", "搜索歌曲"),
            submitHints = setOf("搜索"),
            resultHints = setOf("综合", "单曲", "歌手", "歌词"),
        ),
        // QQ音乐: header search box; submit via IME search or the "搜索" button.
        // 歌手/歌单/专辑 also label home-page shortcuts, so they are NOT result hints.
        AppInteractionProfile(
            appNameAliases = setOf("qq音乐", "qqmusic", "qq music"),
            packageNames = setOf("com.tencent.qqmusic"),
            searchEntryHints = setOf("搜索", "搜索音乐", "搜索歌曲"),
            submitHints = setOf("搜索"),
            resultHints = setOf("综合", "单曲", "歌词", "播放全部"),
        ),
        // 携程旅行: home header search box opens a global search page; submit via the "搜索" button.
        // Home-page tiles (酒店/机票/火车票/攻略) are deliberately NOT result hints.
        AppInteractionProfile(
            appNameAliases = setOf("携程", "携程旅行", "ctrip"),
            packageNames = setOf("ctrip.android.view"),
            searchEntryHints = setOf("搜索", "搜索目的地", "搜索目的地酒店景点"),
            submitHints = setOf("搜索"),
            resultHints = setOf("全部", "筛选", "综合排序", "智能排序", "门票"),
        ),
        // 饿了么: home search bar opens the search page; submit via the "搜索" button.
        // Shop-card words (月售/起送/配送) also fill the home feed, so they are NOT result hints.
        AppInteractionProfile(
            appNameAliases = setOf("饿了么", "eleme"),
            packageNames = setOf("me.ele"),
            searchEntryHints = setOf("搜索", "搜索商家", "搜索商家或商品", "搜索饿了么商家商品"),
            submitHints = setOf("搜索"),
            resultHints = setOf("综合排序", "距离最近", "销量最高", "速度最快", "筛选"),
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
        ),
    )
}
