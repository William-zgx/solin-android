/*
 * Portions adapted from Mika (Copyright 2026 wangzan101), Apache-2.0.
 * Source: wangzan101/Mika @ 0091d2d67b541a417df913a23fefbb8e3a04e4a7,
 * PhoneAutomationEngine.kt, PhoneAutomationTools.kt, PhoneAdvancedTools.kt, WheelMath.kt.
 * Modifications: bounded Solin boundary subrequests, LocalOnly results, no OCR fallback,
 * explicit incomplete outcomes and exact, observed wheel-value verification.
 */
package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.action.MobileActionFunctions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Bounded composition of existing tools, never an alternative device execution entry. */
internal class PhoneCompositeOperations(
    private val request: ToolRequest,
    private val executeChild: suspend (ToolRequest) -> ToolResult,
) {
    private var sequence = 0
    private var writes = 0
    private var pages = 0
    private var packageName = request.arguments["expectedPackageName"].orEmpty()
    private var collected = emptyList<String>()
    private var candidateIds = emptyList<String>()
    private var lastObservationId = ""
    private var truncation = false
    private var actualValue = ""
    private var stopped = "unknown"

    suspend fun execute(): ToolResult = when (request.toolName) {
        MobileActionFunctions.UI_COLLECT -> collect()
        MobileActionFunctions.UI_FIND -> find()
        MobileActionFunctions.UI_SET_WHEEL -> setWheel()
        else -> request.rejected("Unknown composite operation")
    }

    /** Timeouts may follow an effective write: T2, never repeat the composite automatically. */
    fun timedOut(): ToolResult = result(false, "timeout")
    fun interrupted(): ToolResult = result(false, "execution_interrupted")

    private suspend fun call(tool: String, arguments: Map<String, String> = emptyMap()): ToolResult {
        currentCoroutineContext().ensureActive()
        require(tool in CHILD_TOOLS) // T2: no recursive composites, arbitrary tools or external sends.
        if (tool != MobileActionFunctions.OBSERVE_CURRENT_SCREEN) {
            if (writes >= MAX_WRITES) return request.failed(ToolErrorCode.ExecutionFailed, "Composite write budget exhausted", false)
            writes++
        }
        val child = ToolRequest(
            id = "${request.id}:child:${++sequence}",
            toolName = tool,
            arguments = arguments + if (tool == MobileActionFunctions.OBSERVE_CURRENT_SCREEN) {
                mapOf("maxNodes" to "80", "maxTextChars" to "4000") +
                    packageName.takeIf(String::isNotBlank)?.let { mapOf("expectedPackageName" to it) }.orEmpty()
            } else {
                mapOf("expectedPackageName" to packageName, "timeoutMillis" to "1500")
            },
            reason = "Bounded phone operation",
            sensitivityReason = request.sensitivityReason,
        )
        return executeChild(child)
    }

    private suspend fun observe(): Page? {
        val read = call(MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
        if (read.status != ToolStatus.Succeeded) {
            stopped = if (read.status == ToolStatus.Rejected) "authorization_required" else "read_failed"
            return null
        }
        // T4: incomplete or unknown privacy labels never become uploadable observations.
        if (read.data["privacy"] != "LocalOnly" || read.data["requiresLocalModel"] != "true") {
            stopped = "invalid_observation"
            return null
        }
        val actualPackage = read.data["packageName"].orEmpty()
        if (actualPackage.isBlank() || (packageName.isNotBlank() && actualPackage != packageName)) {
            stopped = "app_changed"
            return null
        }
        packageName = actualPackage
        val page = runCatching { Page.parse(read.data.getValue("nodesJson")) }.getOrNull()
        if (page == null || page.nodes.isEmpty() || read.data["observationId"].isNullOrBlank()) {
            stopped = "invalid_observation"
            return null
        }
        lastObservationId = read.data.getValue("observationId")
        truncation = truncation || read.data["truncated"] == "true"
        if (read.data["truncated"] == "true" && request.toolName != MobileActionFunctions.UI_COLLECT) {
            stopped = "invalid_observation" // T2: a truncated tree cannot prove target uniqueness.
            return null
        }
        pages++
        return page
    }

    private suspend fun scroll(direction: String, target: String? = null): Boolean {
        if (writes >= MAX_WRITES) { stopped = "write_limit"; return false }
        val args = mapOf("direction" to direction) + target?.let { mapOf("target" to it) }.orEmpty()
        val out = call(MobileActionFunctions.UI_SCROLL, args)
        if (out.status != ToolStatus.Succeeded) {
            stopped = if (out.status == ToolStatus.Rejected) "authorization_required" else "scroll_failed"
            return false
        }
        return true
    }

    private suspend fun collect(): ToolResult {
        val limit = request.arguments["pages"]?.toIntOrNull() ?: MAX_PAGES
        val maxItems = request.arguments["maxItems"]?.toIntOrNull() ?: 100
        val direction = request.arguments["direction"] ?: "down"
        var unchanged = 0
        repeat(limit) { index ->
            val page = observe() ?: return result(false, stopped)
            val lines = page.nodes.filter { it.text.isNotBlank() && !it.editable }
                .sortedWith(compareBy<Node> { it.top }.thenBy { it.left }).map { it.text }
            val previous = collected
            collected = if (direction == "down") appendPage(collected, lines) else appendPage(lines, collected)
            unchanged = if (previous == collected) unchanged + 1 else 0
            if (collected.size > maxItems || collected.sumOf { it.length + 1 } > MAX_TEXT) {
                collected = collected.take(maxItems)
                truncation = true
                return result(false, "item_limit")
            }
            if (unchanged >= 2) return result(false, "no_new_content")
            if (index == limit - 1) return result(false, "page_limit")
            if (!scroll(direction, request.arguments["container"])) return result(false, stopped)
        }
        return result(false, "page_limit")
    }

    private suspend fun find(): ToolResult {
        val query = request.arguments.getValue("text")
        val role = request.arguments["role"]
        val limit = request.arguments["pages"]?.toIntOrNull() ?: MAX_PAGES
        val direction = request.arguments["direction"] ?: "down"
        var previous: List<String>? = null
        var unchanged = 0
        repeat(limit) { index ->
            val page = observe() ?: return result(false, stopped)
            val matches = page.nodes.filter { node ->
                node.enabled && node.text == query && (role == null || node.role == role)
            }
            candidateIds = matches.map { it.id }
            if (matches.size == 1) return result(true, "found")
            if (matches.size > 1) return result(false, "ambiguous_target")
            val signature = page.nodes.map { "${it.text}|${it.role}|${it.top}" }
            unchanged = if (previous == signature) unchanged + 1 else 0
            previous = signature
            if (unchanged >= 2) return result(false, "no_change")
            if (index == limit - 1) return result(false, "page_limit")
            if (!scroll(direction, request.arguments["container"])) return result(false, stopped)
        }
        return result(false, "page_limit")
    }

    private suspend fun setWheel(): ToolResult {
        val target = request.arguments.getValue("target")
        val desired = request.arguments.getValue("value").toInt()
        val min = request.arguments["minValue"]?.toIntOrNull()
        val max = request.arguments["maxValue"]?.toIntOrNull()
        if ((min == null) != (max == null) || (min != null && max != null && (min > max || desired !in min..max))) {
            return result(false, "invalid_range")
        }
        val modulus = if (min != null && max != null) max - min + 1 else null
        var page = observe() ?: return result(false, stopped)
        var wheel = page.nodes.filter { it.enabled && it.className.endsWith("NumberPicker") && (it.id == target || it.text == target) }
            .singleOrNull() ?: return result(false, "wheel_not_unique")
        if (wheel.right <= wheel.left || wheel.bottom <= wheel.top) return result(false, "wheel_unreadable")
        val anchor = wheel.bounds
        var previousValue: Int? = null
        var previousDistance: Int? = null
        repeat(MAX_WRITES + 1) {
            // Only a visible editable child or an actual centered label counts; never infer a hidden value.
            val valueNode = page.wheelValue(wheel) ?: return result(false, "wheel_unreadable")
            val current = valueNode.text.toIntOrNull() ?: return result(false, "wheel_unreadable")
            actualValue = valueNode.text
            if (current == desired) return result(true, "verified")
            if (min != null && max != null && current !in min..max) return result(false, "invalid_range")
            val distance = wheelDistance(current, desired, modulus)
            if (previousValue == current) return result(false, "no_change")
            if (previousDistance != null && abs(distance) >= abs(previousDistance!!)) return result(false, "wheel_not_converging")
            if (writes >= MAX_WRITES) return result(false, "write_limit")
            previousValue = current
            previousDistance = distance
            if (valueNode.editable) {
                val out = call(MobileActionFunctions.UI_TYPE_TEXT, mapOf("target" to valueNode.id, "text" to desired.toString()))
                if (out.status != ToolStatus.Succeeded) return result(false, if (out.status == ToolStatus.Rejected) "authorization_required" else "input_failed")
            } else if (!scroll(if (distance > 0) "forward" else "backward", wheel.id)) {
                return result(false, stopped)
            }
            page = observe() ?: return result(false, stopped)
            wheel = page.nodes.filter { it.enabled && it.className.endsWith("NumberPicker") && it.bounds == anchor }
                .singleOrNull() ?: return result(false, "wheel_changed")
        }
        return result(false, "write_limit")
    }

    private fun result(completed: Boolean, reason: String): ToolResult {
        val rawText = collected.joinToString("\n")
        val data = request.localOnlyData() + mapOf(
            "completed" to completed.toString(),
            "partial" to (!completed && pages > 0).toString(),
            "stopReason" to reason,
            "remaining" to if (completed) "none" else "observe_before_continuing",
            "pagesInspected" to pages.toString(),
            "writesAttempted" to writes.toString(),
            "packageName" to packageName,
            "observationId" to lastObservationId,
            "text" to rawText.take(MAX_TEXT),
            "candidateIds" to JSONArray(candidateIds).toString(),
            "actualValue" to actualValue,
            "truncated" to (truncation || rawText.length > MAX_TEXT || reason in setOf("page_limit", "item_limit")).toString(),
        )
        // A bounded collection can deliver useful evidence without claiming the entire list was read.
        val deliveredCollection = request.toolName == MobileActionFunctions.UI_COLLECT && pages > 0 &&
            reason in setOf("page_limit", "item_limit", "no_new_content")
        return if (completed || deliveredCollection) request.succeeded(
            if (completed) "已核对组合操作结果" else "已采集有限页面，完整性未确认", data,
        ) else request.failed(
            ToolErrorCode.ExecutionFailed, "组合操作未完成：$reason", retryable = false, data = data,
        )
    }

    private data class Node(
        val id: String, val text: String, val className: String, val editable: Boolean,
        val scrollable: Boolean, val enabled: Boolean, val left: Int, val top: Int, val right: Int, val bottom: Int,
    ) {
        val bounds get() = listOf(left, top, right, bottom)
        val role get() = when { editable -> "editable"; scrollable -> "scrollable"; else -> "text" }
        fun inside(other: Node) = left >= other.left && top >= other.top && right <= other.right && bottom <= other.bottom
    }

    private data class Page(val nodes: List<Node>) {
        fun wheelValue(wheel: Node): Node? {
            val children = nodes.filter { it.id != wheel.id && it.enabled && it.inside(wheel) && it.text.toIntOrNull() != null }
            children.filter { it.editable }.singleOrNull()?.let { return it }
            val center = (wheel.top + wheel.bottom) / 2
            return children.filter { center in it.top..it.bottom }.singleOrNull()
        }
        companion object {
            fun parse(json: String): Page {
                val array = JSONArray(json)
                require(array.length() <= 200)
                return Page((0 until array.length()).map { index ->
                    val n = array.getJSONObject(index)
                    val b = n.optJSONObject("bounds") ?: JSONObject()
                    Node(n.getString("id"), n.optString("text").ifBlank { n.optString("contentDescription") },
                        n.optString("className"), n.optBoolean("editable"), n.optBoolean("scrollable"),
                        n.optBoolean("enabled"), b.optInt("left"), b.optInt("top"), b.optInt("right"), b.optInt("bottom"))
                })
            }
        }
    }

    companion object {
        const val MAX_PAGES = 5
        const val MAX_WRITES = 5
        const val MAX_TEXT = 12_000
        val TOOL_NAMES = setOf(MobileActionFunctions.UI_COLLECT, MobileActionFunctions.UI_FIND, MobileActionFunctions.UI_SET_WHEEL)
        private val CHILD_TOOLS = setOf(MobileActionFunctions.OBSERVE_CURRENT_SCREEN, MobileActionFunctions.UI_SCROLL, MobileActionFunctions.UI_TYPE_TEXT)

        /** Mika's adjacent-page overlap merge: equal messages elsewhere remain separate entries. */
        internal fun appendPage(previous: List<String>, next: List<String>): List<String> {
            val overlap = (minOf(previous.size, next.size) downTo 1).firstOrNull { size -> previous.takeLast(size) == next.take(size) } ?: 0
            return previous + next.drop(overlap)
        }

        /** Adapted from Mika WheelMath.distance; the modulus is an explicit range hint, not success evidence. */
        internal fun wheelDistance(current: Int, target: Int, modulus: Int?): Int {
            val distance = target - current
            if (modulus == null || modulus < 2) return distance
            val forward = ((distance % modulus) + modulus) % modulus
            return if (forward <= modulus / 2) forward else forward - modulus
        }
    }
}
