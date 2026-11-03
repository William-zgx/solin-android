/*
 * Adapted from Mika RouteRecorder, Replayer and RouteMatcher.
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 * https://github.com/wangzan101/Mika/tree/0091d2d67b541a417df913a23fefbb8e3a04e4a7/core/src/main/kotlin/com/wangzan101/mika/core/replay
 * Modifications: session-memory only; salted evidence digests, exact user-request matching,
 * verified search routes only, and one proposed action at a time through Solin's existing gates.
 */
package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.device.ObservationElement
import com.bytedance.zgx.solin.device.ScreenObservation
import com.bytedance.zgx.solin.device.screenObservationFromJsonStringOrNull
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Learns short, deterministically verified search routes. This object never executes a tool.
 *
 * T1/T2: a route proposes one ordinary request; live authorization and critical-control guards
 * still decide whether it executes. T4/T5/T7: no raw goal, input, screen text, screenshots or
 * credentials are retained. Per-instance salted digests cannot be reused across process restarts.
 * Routes belong to one conversation session and disappear on clear/process death. They are NOT
 * generic macros: changed user wording does not match, and no prior approval is carried forward.
 */
class VerifiedPhoneRoutes(private val clock: () -> Long = System::currentTimeMillis) {
    private val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val routes = linkedMapOf<String, Route>()
    private val runs = linkedMapOf<String, Run>()

    private data class Slot(val start: Int, val end: Int) {
        fun value(input: String): String? = input.takeIf { end <= it.length }?.substring(start, end)
    }
    private data class Target(val digest: String, val role: String)
    private data class Step(
        val tool: String,
        val packageName: String,
        val before: String,
        val after: String,
        val target: Target?,
        val slots: Map<String, Slot>,
        val literals: Map<String, String>,
    )
    private data class Route(val key: String, val steps: List<Step>, var misses: Int = 0)
    private data class Pending(val requestId: String, val step: Step)
    private class Run(val key: String) {
        val seen = mutableSetOf<String>()
        val recorded = mutableListOf<Step>()
        var rejected = false
        var replay: Route? = null
        var next = 0
        var pending: Pending? = null
        var replayFinished = false
    }

    /** Clears private session memory, e.g. when chat history is cleared or the owner is disposed. */
    @Synchronized fun clear() { routes.clear(); runs.clear() }

    @Synchronized fun awaitingResult(runId: String): Boolean = runs[runId]?.pending != null

    /** Record actual executor results, including the terminal verified result before loop completion. */
    @Synchronized fun observe(
        runId: String,
        sessionId: String?,
        input: String,
        request: ToolRequest,
        result: ToolResult,
    ) {
        val run = run(runId, sessionId, input) ?: return
        if (run.rejected || !run.seen.add(request.id)) return
        // Bootstrap app launch is not recorded or replayed. No approval/arguments from it survive.
        if (request.toolName == MobileActionFunctions.OPEN_APP_BY_NAME && run.recorded.isEmpty() &&
            run.pending == null && run.replay == null && result.requestId == request.id &&
            result.status == ToolStatus.Succeeded && request.sensitivityReason == null
        ) return
        if (run.seen.size > MAX_OBSERVATIONS || request.id != result.requestId ||
            request.sensitivityReason != null || result.status != ToolStatus.Succeeded ||
            result.data["privacy"] != MessagePrivacy.LocalOnly.name || result.data["requiresLocalModel"] != "true"
        ) return reject(run)
        // Read-only observations may precede recording or refresh a page, but never complete a write.
        if (request.toolName in READ_TOOLS) {
            if (run.pending != null) reject(run)
            return
        }
        val step = step(input, request, result) ?: return reject(run)
        val pending = run.pending
        if (pending != null) {
            if (pending.requestId != request.id || pending.step != step) return reject(run)
            run.pending = null
            run.next++
            if (run.next == run.replay!!.steps.size) run.replayFinished = true
        } else if (run.replay != null) {
            return reject(run)
        }
        if (run.recorded.size >= MAX_STEPS) return reject(run)
        if (run.recorded.lastOrNull()?.after?.let { it != step.before } == true) return reject(run)
        run.recorded += step
        if (request.toolName == MobileActionFunctions.UI_SUBMIT_SEARCH &&
            result.data["searchVerificationStatus"] == "verified" &&
            run.recorded.any { it.tool == MobileActionFunctions.UI_TYPE_TEXT }
        ) {
            val query = step.slots["verifySearchQuery"]?.value(input) ?: return reject(run)
            val typed = run.recorded.lastOrNull { it.tool == MobileActionFunctions.UI_TYPE_TEXT }
                ?.slots?.get("text")?.value(input)
            if (typed != query) return reject(run)
            // Only successful deterministic verification can create/reset a route. Model finish,
            // generic success summaries and user-confirmed external outcomes are never sufficient.
            routes[run.key] = Route(run.key, run.recorded.toList())
            while (routes.size > MAX_ROUTES) routes.remove(routes.keys.first())
            run.replayFinished = true
        }
    }

    /**
     * Returns at most one proposal based on a complete fresh local observation. Repeated calls while
     * an action is pending return null; an unknown/failed result disables replay for this run.
     */
    @Synchronized fun propose(
        runId: String,
        sessionId: String?,
        input: String,
        result: ToolResult,
    ): ToolRequest? {
        val run = run(runId, sessionId, input) ?: return null
        if (run.rejected || run.replayFinished || run.pending != null) return null
        // Never start an old route halfway through an independently planned sequence of writes.
        if (run.replay == null && run.recorded.isNotEmpty()) return null
        val route = run.replay ?: routes[run.key]?.takeIf { it.misses < MAX_MISSES } ?: return null
        if (result.status != ToolStatus.Succeeded || result.data["privacy"] != MessagePrivacy.LocalOnly.name ||
            result.data["requiresLocalModel"] != "true"
        ) { run.replay = route; reject(run); return null }
        val screen = screen(result, "after") ?: screen(result, "")
        val step = route.steps.getOrNull(run.next) ?: return null
        if (screen == null || !fresh(screen) || digest(screen) != step.before || screen.packageName != step.packageName) {
            run.replay = route; reject(run); return null
        }
        val target = step.target?.let { wanted ->
            screen.elements.filter { safe(it) && it.role == wanted.role && digest(it.text) == wanted.digest }
                .singleOrNull()
        }
        if (step.target != null && target == null) { run.replay = route; reject(run); return null }
        val args = step.literals.toMutableMap()
        for ((name, slot) in step.slots) {
            val value = slot.value(input)
            if (value == null) { reject(run); return null }
            args[name] = value
        }
        // A fresh semantic label, never a cached node id or coordinate. The live target resolver and
        // critical action gate re-check it at execution time (T2/T3).
        target?.let { args["target"] = it.text }
        args["expectedPackageName"] = step.packageName
        val proposal = ToolRequest(toolName = step.tool, arguments = args, reason = "已验证搜索路线的下一步；仍需当前执行授权")
        run.replay = route
        run.pending = Pending(proposal.id, step)
        return proposal
    }

    /** Retires this run's route proposals after a revised/sequential intent; later reads cannot resume it. */
    @Synchronized fun abandon(runId: String, sessionId: String?, input: String) {
        run(runId, sessionId, input)?.let(::reject)
    }

    private fun run(runId: String, sessionId: String?, input: String): Run? {
        // Null session cannot establish a conversation boundary, so it cannot use learned routes.
        if (sessionId.isNullOrBlank() || input.isBlank() || input.length > MAX_GOAL_CHARS) return null
        val key = digest("${sessionId.length}:$sessionId$input")
        runs[runId]?.let {
            if (it.key != key) { reject(it); return null }
            return it
        }
        return Run(key).also {
            runs[runId] = it
            while (runs.size > MAX_RUNS) runs.remove(runs.keys.first())
        }
    }

    private fun step(input: String, request: ToolRequest, result: ToolResult): Step? {
        if (request.toolName !in WRITE_TOOLS || request.arguments.keys.any { it !in ARGUMENTS }) return null
        val before = screen(result, "before") ?: return null
        val after = screen(result, "after") ?: return null
        if (!fresh(before) || !fresh(after) || after.capturedAtMillis < before.capturedAtMillis ||
            before.packageName != after.packageName || request.arguments["expectedPackageName"] != before.packageName
        ) return null
        val targetText = request.arguments["target"]
        val target = targetText?.let { name ->
            before.elements.filter { safe(it) && (it.id == name || it.text == name) }.singleOrNull()
        } ?: if (targetText == null && request.toolName == MobileActionFunctions.UI_SUBMIT_SEARCH) {
            before.elements.filter { safe(it) && it.clickability.editable }.singleOrNull()
        } else null
        if ((targetText != null || request.toolName == MobileActionFunctions.UI_SUBMIT_SEARCH) &&
            (target == null || target.text.isBlank())) return null
        if (target != null && before.elements.count { it.text == target.text } != 1) return null
        when (request.toolName) {
            MobileActionFunctions.UI_TAP -> if (target?.text?.lowercase() !in SEARCH_LABELS || !target!!.clickability.clickable) return null
            MobileActionFunctions.UI_TYPE_TEXT -> if (target == null || !target.clickability.editable ||
                !result.data["inputVerification"].equals("match", ignoreCase = true)) return null
            MobileActionFunctions.UI_SUBMIT_SEARCH -> if (request.arguments["verifySearchQuery"].isNullOrBlank()) return null
            MobileActionFunctions.UI_SCROLL -> if (request.arguments["direction"] !in setOf("up", "down")) return null
        }
        val slots = mutableMapOf<String, Slot>()
        for (key in SLOT_ARGS) request.arguments[key]?.let { value ->
            val start = input.indexOf(value)
            if (value.isBlank() || start < 0 || input.indexOf(value, start + 1) >= 0) return null
            slots[key] = Slot(start, start + value.length)
        }
        // All retained literal values are constrained here; there is no raw parameter escape hatch.
        val literals = mutableMapOf<String, String>()
        request.arguments["direction"]?.let { if (it !in setOf("up", "down")) return null else literals["direction"] = it }
        request.arguments["timeoutMillis"]?.let { value ->
            val n = value.toLongOrNull()?.takeIf { it in 1..5000 } ?: return null
            literals["timeoutMillis"] = n.toString()
        }
        request.arguments["allowClipboardPasteFallback"]?.let {
            if (it != "false") return null
            literals["allowClipboardPasteFallback"] = "false"
        }
        return Step(request.toolName, before.packageName!!, digest(before), digest(after),
            target?.let { Target(digest(it.text), it.role) }, slots.toMap(), literals.toMap())
    }

    private fun screen(result: ToolResult, prefix: String): ScreenObservation? {
        val key = if (prefix.isEmpty()) "screenObservationJson" else "${prefix}ScreenObservationJson"
        val raw = result.data[key]?.takeIf { it.length <= MAX_OBSERVATION_CHARS } ?: return null
        return screenObservationFromJsonStringOrNull(raw)?.takeIf { screen ->
            screen.privacyLevel == MessagePrivacy.LocalOnly && !screen.truncated &&
                !screen.packageName.isNullOrBlank() && screen.elements.isNotEmpty() &&
                screen.elements.size <= MAX_ELEMENTS && screen.elements.all(::safe) &&
                screen.elements.map { it.id }.distinct().size == screen.elements.size
        }
    }
    private fun safe(element: ObservationElement): Boolean = element.source == "accessibility" &&
        element.sensitiveFlags.isEmpty() && element.privacyLevel == MessagePrivacy.LocalOnly &&
        element.clickability.enabled && element.text.length <= MAX_ELEMENT_CHARS

    private fun fresh(screen: ScreenObservation): Boolean = clock() - screen.capturedAtMillis in 0..MAX_AGE_MS

    private fun reject(run: Run) {
        if (!run.rejected) run.replay?.let { it.misses++ }
        run.rejected = true
        run.pending = null
        run.recorded.clear()
    }

    private fun digest(screen: ScreenObservation): String = digest(buildString {
        append(screen.packageName).append('|').append(screen.widthPx).append('|').append(screen.heightPx)
        screen.elements.forEach { e ->
            // IDs are scoped to a snapshot. Stable text/role/geometry (including field contents)
            // detect drift without retaining the observed text itself.
            append('|').append(e.role.length).append(':').append(e.role)
            append('|').append(e.text.length).append(':').append(e.text)
            append('|').append(e.bounds).append('|').append(e.clickability)
        }
    })
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").run {
        update(salt)
        digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_STEPS = 5
        const val MAX_ROUTES = 32
        const val MAX_RUNS = 32
        const val MAX_MISSES = 2
        const val MAX_OBSERVATIONS = 32
        const val MAX_GOAL_CHARS = 4000
        const val MAX_OBSERVATION_CHARS = 64_000
        const val MAX_ELEMENTS = 100
        const val MAX_ELEMENT_CHARS = 1000
        const val MAX_AGE_MS = 5000L
        val READ_TOOLS = setOf(MobileActionFunctions.OBSERVE_CURRENT_SCREEN, MobileActionFunctions.UI_WAIT)
        val WRITE_TOOLS = setOf(MobileActionFunctions.UI_TAP, MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH, MobileActionFunctions.UI_SCROLL)
        val SLOT_ARGS = setOf("text", "verifySearchQuery", "expectedAppName")
        val ARGUMENTS = SLOT_ARGS + setOf("target", "expectedPackageName", "direction", "timeoutMillis", "allowClipboardPasteFallback")
        val SEARCH_LABELS = setOf("搜索", "search")
    }
}
