package com.bytedance.zgx.solin.device

/**
 * Per-app interaction hints used by target resolution, plus short factual UI notes for planner
 * prompts ([appUiNotesFor]). App UI notes informed by Mika's knowledge files (Apache-2.0), rewritten.
 */

/** Upper bounds for [AppInteractionProfile.uiNotes]; pinned by AppInteractionProfileCatalogTest. */
internal const val MAX_APP_UI_NOTES = 5
internal const val MAX_APP_UI_NOTE_CHARS = 60

/**
 * UI notes for the app owning [packageName], or empty when the package is blank or unknown. The notes
 * are facts about the app UI, never instructions; callers must frame them as reference data.
 */
fun appUiNotesFor(packageName: String?): List<String> =
    AppInteractionProfiles.forPackage(packageName)?.uiNotes.orEmpty()

object AppInteractionProfiles {
    val profiles: List<AppInteractionProfile> = listOf(
        AppInteractionProfile(
            appNameAliases = setOf("淘宝", "taobao", "tb"),
            packageNames = setOf("com.taobao.taobao"),
            searchEntryHints = setOf("搜索", "搜一搜", "搜索商品", "搜索发现", "搜索宝贝", "搜索宝贝和店铺", "淘宝搜索"),
            submitHints = setOf("搜索", "搜一下"),
            resultHints = setOf("综合", "销量", "筛选"),
            uiNotes = listOf(
                "首页顶部搜索框点开后进入单独的搜索页",
                "搜索框里常显示推荐词，并非用户输入",
                "底部标签含首页、消息、购物车和我的淘宝",
                "结果页顶部有综合、销量和筛选栏",
            ),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("拼多多", "pinduoduo", "pdd"),
            packageNames = setOf("com.xunmeng.pinduoduo"),
            searchEntryHints = setOf("搜索", "搜索商品", "多多搜索", "搜"),
            submitHints = setOf("搜索", "搜一下"),
            resultHints = setOf("综合", "销量", "筛选", "百亿补贴"),
            uiNotes = listOf(
                "首页顶部有搜索框，常预填推荐词",
                "底部标签通常含首页、聊天和个人中心",
                "结果页顶部有综合、销量和筛选栏",
            ),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("高德", "高德地图", "amap", "gaode", "autonavi"),
            packageNames = setOf("com.autonavi.minimap"),
            searchEntryHints = setOf("搜索", "搜地点", "目的地", "去哪儿", "你要去哪儿", "查找地点", "公交地铁"),
            submitHints = setOf("搜索", "确定", "去这里"),
            resultHints = setOf("路线", "导航", "到这去", "查看地图", "展开列表"),
            uiNotes = listOf(
                "首页地图上方是搜索地点的输入框",
                "搜索结果以卡片列表显示在地图下方",
                "地点详情卡片上有路线和导航按钮",
                "底部标签通常含首页、附近、消息和我的",
            ),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("地图", "google maps", "maps"),
            packageNames = setOf("com.google.android.apps.maps"),
            searchEntryHints = setOf("搜索", "search", "搜索地点", "search here", "where to", "目的地"),
            submitHints = setOf("搜索", "search", "directions"),
            resultHints = setOf("路线", "directions", "start", "reviews", "photos"),
            uiNotes = listOf(
                "地图顶部是 Search here 搜索框",
                "地点详情面板从屏幕底部上拉展开",
                "地点面板有 Directions、Start 等按钮",
            ),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("京东", "jd", "jingdong"),
            packageNames = setOf("com.jingdong.app.mall"),
            searchEntryHints = setOf("搜索", "搜索商品", "搜一搜", "搜索京东", "搜索京东商品", "搜索京东商品店铺", "搜索好物"),
            submitHints = setOf("搜索"),
            resultHints = setOf("综合", "销量", "筛选", "京东物流", "显示模式", "列表"),
            uiNotes = listOf(
                "首页顶部搜索框常预填推荐词，并非用户输入",
                "底部标签含首页、购物车和我的",
                "结果页顶部有综合、销量、筛选栏",
            ),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("浏览器", "browser", "网页", "web", "chrome", "谷歌浏览器", "google", "谷歌"),
            packageNames = setOf(
                "com.android.chrome",
                "com.android.browser",
                "com.quark.browser",
                "com.UCMobile",
                "com.google.android.googlequicksearchbox",
            ),
            searchEntryHints = setOf(
                "搜索",
                "搜",
                "검색",
                "地址",
                "地址栏",
                "网址",
                "url",
                "omnibox",
                "输入网址",
                "搜索或输入网址",
                "请输入搜索词或网址",
                "搜索词或网址",
                "网页搜索",
                "AI搜索",
            ),
            submitHints = setOf("搜索", "검색", "前往", "转到", "search"),
            resultHints = setOf("搜索结果", "검색결과", "网页", "相关搜索", "百度", "全部", "综合", "图片", "资讯", "文档", "问答", "经验"),
            uiNotes = listOf(
                "顶部地址栏既可输入网址也可输入搜索词",
                "页面加载后地址栏显示网址或域名，不是页面标题",
                "网页里的输入框和地址栏是两个不同的输入位置",
            ),
        ),
    ) + AppInteractionProfileCatalog.lifestyleAndMediaProfiles +
        AppInteractionProfileCatalog.contentAndOfficeProfiles

    fun forPackage(packageName: String?): AppInteractionProfile? =
        packageName?.takeIf { it.isNotBlank() }?.let { packageValue ->
            profiles.firstOrNull { profile -> packageValue in profile.packageNames }
        }

    fun forAppName(appName: String?): AppInteractionProfile? {
        val normalized = appName.normalizedLookupKey()
        if (normalized.isBlank()) return null
        return profiles.firstOrNull { profile ->
            profile.appNameAliases.any { alias -> alias.normalizedLookupKey() == normalized }
        }
    }
}
