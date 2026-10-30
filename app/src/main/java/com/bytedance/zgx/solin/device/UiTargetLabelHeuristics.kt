package com.bytedance.zgx.solin.device

/*
 * Label heuristics shared by offline and runtime target scoring (search/submit/input/result
 * container detection and phrase scoring). Split out of UiTargetResolver.kt; behaviour unchanged.
 */

internal fun phraseScore(label: String, phrase: String): Int? {
    if (phrase.isBlank()) return null
    return when {
        label == phrase -> 450
        label.startsWith(phrase) -> 360
        label.contains(phrase) -> 260
        phrase.contains(label) && label.length >= 2 -> 160
        else -> null
    }
}

internal fun looksSearchLike(normalized: String): Boolean =
    listOf("搜索", "搜", "검색", "search", "查找", "查询").any { normalized.contains(it.normalizedLookupKey()) }

internal fun hasStrongSearchEntryEvidence(normalized: String): Boolean =
    listOf(
        "搜索栏",
        "搜索框",
        "搜索商品",
        "搜索发现",
        "搜索宝贝",
        "搜索京东",
        "搜索好物",
        "搜索输入",
        "输入文字",
        "输入关键词",
        "请输入搜索词",
        "地址栏",
        "网址",
        "目的地",
        "去哪儿",
        "搜地点",
        "公交地铁",
        "搜索词或网址",
        "searchbox",
        "searchfield",
        "omnibox",
    ).any { normalized.contains(it.normalizedLookupKey()) }

internal fun looksSearchSubmitLike(normalized: String): Boolean =
    looksSearchLike(normalized) ||
        listOf("确定", "完成", "前往", "enter").any { normalized.contains(it.normalizedLookupKey()) } ||
        normalized == "go"

internal fun looksNonTextSearchControl(normalized: String): Boolean =
    listOf("语音", "拍照", "相机", "图片", "扫一扫", "扫码", "voice", "camera", "image")
        .any { normalized.contains(it.normalizedLookupKey()) }

internal fun looksInputLike(normalized: String): Boolean =
    listOf("输入", "地址", "网址", "url", "omnibox", "关键词", "商品", "目的地", "宝贝", "店铺", "input", "edit", "keyword")
        .any { normalized.contains(it.normalizedLookupKey()) }

internal fun isBrowserResultSearchBarLabel(normalized: String): Boolean =
    normalized.startsWith("网页搜索")

internal fun looksResultOrCommerceContainer(
    normalizedLabel: String,
    profile: AppInteractionProfile? = null,
): Boolean {
    if (normalizedLabel.isBlank()) return false
    val profileHintMatches = profile?.resultHints.orEmpty().count { hint ->
        normalizedLabel.contains(hint.normalizedLookupKey())
    }
    val genericMarkers = listOf(
        "综合",
        "销量",
        "筛选",
        "商品列表",
        "旗舰店",
        "已售",
        "月销",
        "评价",
        "领券",
        "加购",
        "购买",
        "¥",
        "￥",
    ).count { marker -> normalizedLabel.contains(marker.normalizedLookupKey()) }
    return profileHintMatches >= 2 ||
        genericMarkers >= 3 ||
        (normalizedLabel.contains("综合") && normalizedLabel.contains("销量")) ||
        normalizedLabel.contains("商品列表")
}
