package com.bytedance.zgx.solin.device

private val dangerousActionTextMarkers = listOf(
    "支付",
    "付款",
    "转账",
    "下单",
    "提交订单",
    "购买",
    "立即购买",
    "删除",
    "发送",
    "发布",
    "授权",
    "允许",
    "同意",
)

private val strongDangerousActionTextMarkers = listOf(
    "确认支付",
    "立即支付",
    "确认付款",
    "立即付款",
    "确认转账",
    "确认删除",
    "删除此",
    "发送",
    "发布",
    "授权登录",
    "确认授权",
    "同意授权",
    "提交订单",
    "确认下单",
    "立即购买",
)

private val standaloneDangerousActionTextMarkers = listOf(
    "支付",
    "付款",
    "转账",
    "下单",
    "购买",
    "删除",
    "发送",
    "发布",
    "授权",
    "允许",
    "同意",
)

internal fun String?.hasDangerousActionText(): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return false
    return dangerousActionTextMarkers.any { marker ->
        normalized.contains(marker.normalizedLookupKey())
    }
}

internal fun String?.hasOcrDangerousActionText(): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return false
    if (hasStrongDangerousActionText()) return true
    return standaloneDangerousActionTextMarkers.any { marker ->
        normalized == marker.normalizedLookupKey()
    }
}

internal fun String?.hasStrongDangerousActionText(): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return false
    return strongDangerousActionTextMarkers.any { marker ->
        normalized.contains(marker.normalizedLookupKey())
    }
}

internal fun ScreenStateSnapshot.hasDangerousActionControl(): Boolean =
    nodes.any { node -> node.hasDangerousActionControl() }

private fun ScreenNode.hasDangerousActionControl(): Boolean {
    if (!enabled) return false
    if (!clickable && !editable && !scrollable) return false
    val label = text.ifBlank { contentDescription }
    return label.hasDangerousActionText()
}
