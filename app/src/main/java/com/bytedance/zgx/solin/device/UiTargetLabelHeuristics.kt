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

// Keyword lists are normalized once here rather than on every call: these predicates run per node,
// several times per scored candidate. Each list's contents and order are unchanged.
private val SEARCH_LIKE_KEYS = listOf("搜索", "搜", "검색", "search", "查找", "查询").normalizedKeys()

private val STRONG_SEARCH_ENTRY_KEYS = listOf(
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
).normalizedKeys()

private val SEARCH_SUBMIT_EXTRA_KEYS = listOf("确定", "完成", "前往", "enter").normalizedKeys()

private val NON_TEXT_SEARCH_CONTROL_KEYS =
    listOf("语音", "拍照", "相机", "图片", "扫一扫", "扫码", "voice", "camera", "image").normalizedKeys()

private val INPUT_LIKE_KEYS = listOf(
    "输入", "地址", "网址", "url", "omnibox", "关键词", "商品", "目的地", "宝贝", "店铺", "input", "edit", "keyword",
).normalizedKeys()

private val RESULT_OR_COMMERCE_MARKER_KEYS = listOf(
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
).normalizedKeys()

private fun List<String>.normalizedKeys(): List<String> = map { value -> value.normalizedLookupKey() }

internal fun looksSearchLike(normalized: String): Boolean =
    SEARCH_LIKE_KEYS.any { normalized.contains(it) }

internal fun hasStrongSearchEntryEvidence(normalized: String): Boolean =
    STRONG_SEARCH_ENTRY_KEYS.any { normalized.contains(it) }

internal fun looksSearchSubmitLike(normalized: String): Boolean =
    looksSearchLike(normalized) ||
        SEARCH_SUBMIT_EXTRA_KEYS.any { normalized.contains(it) } ||
        normalized == "go"

internal fun looksNonTextSearchControl(normalized: String): Boolean =
    NON_TEXT_SEARCH_CONTROL_KEYS.any { normalized.contains(it) }

internal fun looksInputLike(normalized: String): Boolean =
    INPUT_LIKE_KEYS.any { normalized.contains(it) }

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
    val genericMarkers = RESULT_OR_COMMERCE_MARKER_KEYS.count { marker -> normalizedLabel.contains(marker) }
    return profileHintMatches >= 2 ||
        genericMarkers >= 3 ||
        (normalizedLabel.contains("综合") && normalizedLabel.contains("销量")) ||
        normalizedLabel.contains("商品列表")
}
