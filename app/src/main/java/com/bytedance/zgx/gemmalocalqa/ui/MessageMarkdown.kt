package com.bytedance.zgx.gemmalocalqa.ui

data class MessageSegment(
    val text: String,
    val isCode: Boolean,
)

fun splitMessageSegments(text: String): List<MessageSegment> {
    if (!text.contains("```")) return listOf(MessageSegment(text, isCode = false))
    val pieces = text.split("```")
    return pieces.mapIndexedNotNull { index, piece ->
        if (piece.isEmpty()) {
            null
        } else {
            MessageSegment(text = piece, isCode = index % 2 == 1)
        }
    }.ifEmpty { listOf(MessageSegment(text, isCode = false)) }
}
