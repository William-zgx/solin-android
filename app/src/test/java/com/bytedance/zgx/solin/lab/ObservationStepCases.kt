package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.AppSearchProgressEvidence
import com.bytedance.zgx.solin.device.DEVICE_CONTROL_METADATA_POLICY
import com.bytedance.zgx.solin.device.DEVICE_CONTROL_SOURCE_ACCESSIBILITY
import com.bytedance.zgx.solin.device.ScreenBounds
import com.bytedance.zgx.solin.device.ScreenNode
import com.bytedance.zgx.solin.device.ScreenStateSnapshot
import com.bytedance.zgx.solin.device.toScreenNodesJsonString
import com.bytedance.zgx.solin.device.toScreenObservationJsonString
import com.bytedance.zgx.solin.multimodal.OcrTextBlock
import com.bytedance.zgx.solin.multimodal.OcrTextBounds
import com.bytedance.zgx.solin.multimodal.OcrTextElement
import com.bytedance.zgx.solin.multimodal.OcrTextLine
import com.bytedance.zgx.solin.orchestration.AgentObservationReplanContext
import com.bytedance.zgx.solin.orchestration.AgentRun
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.ObservationReplanPromptFixtures
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node as DomNode

/** One labeled Suite A case from `lab/datasets/observation_step.jsonl`. */
data class ObservationCase(val json: JSONObject) {
    val id: String get() = json.getString("id")
    val category: String get() = json.getString("category")
    val goal: String get() = json.getString("goal")
    val goldLabel: String get() = json.getJSONObject("gold").getString("label")
    val accepts: List<JSONObject>
        get() = json.getJSONObject("gold").getJSONArray("accept").let { array ->
            (0 until array.length()).map(array::getJSONObject)
        }
    val query: String? get() = json.optJSONObject("app")?.optString("query")
    val packageName: String? get() = json.optJSONObject("app")?.optString("package")
    val appName: String? get() = json.optJSONObject("app")?.optString("name")
}

data class BuiltObservationCase(
    val context: AgentObservationReplanContext,
    /** Node id to the visible strings that name it, for gold target matching. */
    val nodeLabels: Map<String, List<String>>,
    val screen: ScreenStateSnapshot?,
    val before: ScreenStateSnapshot?,
)

object ObservationStepCases {
    fun load(): List<ObservationCase> =
        readJsonl(File(LabEnv.datasetsDir, "observation_step.jsonl")).map(::ObservationCase)

    /**
     * Builds the replanner context the production loop would hand to `ModelObservationReplanner`
     * after the case's last step: run input = goal, prior requests = the steps, and an observed
     * result shaped like `DeviceControlToolExecutor` output (observe vs. UI action data).
     */
    fun build(case: ObservationCase): BuiltObservationCase {
        val screenSpec = case.json.getJSONObject("screen")
        if (screenSpec.has("fixture")) {
            val context = ObservationReplanPromptFixtures.searchEntryObserveContext().let { fixture ->
                fixture.copy(run = fixture.run.copy(id = "lab-${case.id}", input = case.goal))
            }
            return BuiltObservationCase(
                context = context,
                nodeLabels = labelsFromObservationJson(context.observedResult.data["screenObservationJson"].orEmpty()),
                screen = null,
                before = null,
            )
        }
        val (screen, ocrBlocks) = loadScreen(screenSpec, "${case.id}-screen")
        val before = case.json.optJSONObject("before")?.let { spec -> loadScreen(spec, "${case.id}-before").first }
        val steps = case.json.getJSONArray("steps").let { array -> (0 until array.length()).map(array::getJSONObject) }
        require(steps.isNotEmpty()) { "case ${case.id} has no steps" }
        val requests = steps.mapIndexed { index, step ->
            ToolRequest(
                id = "${case.id}-step-$index",
                toolName = step.getString("tool"),
                arguments = step.optJSONObject("args").stringMap(),
                reason = LAB_PRIOR_STEP_REASON,
            )
        }
        val previous = requests.last()
        val observed = if (previous.toolName == MobileActionFunctions.OBSERVE_CURRENT_SCREEN) {
            observeResult(previous, screen, ocrBlocks)
        } else {
            uiActionResult(previous, before = before, after = screen)
        }
        val context = AgentObservationReplanContext(
            run = AgentRun(
                id = "lab-${case.id}",
                input = case.goal,
                state = AgentRunState.Observing,
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
            previousRequest = previous,
            observedResult = observed,
            priorRequests = requests,
            priorStepOutcomes = requests.dropLast(1).associate { request -> request.id to "ok" },
        )
        return BuiltObservationCase(
            context = context,
            nodeLabels = screen.nodes.associate { node ->
                node.id to listOf(node.text, node.contentDescription).filter(String::isNotBlank)
            },
            screen = screen,
            before = before,
        )
    }

    private fun observeResult(
        request: ToolRequest,
        snapshot: ScreenStateSnapshot,
        ocrBlocks: List<OcrTextBlock>,
    ): ToolResult {
        val observationJson = if (ocrBlocks.isEmpty()) {
            snapshot.toScreenObservationJsonString()
        } else {
            snapshot.toScreenObservationJsonString(ocrBlocks)
        }
        val data = baseData(request) + mapOf(
            "observationId" to snapshot.id,
            "capturedAtMillis" to snapshot.capturedAtMillis.toString(),
            "nodeCount" to snapshot.nodeCount.toString(),
            "actionableNodeCount" to snapshot.actionableNodeCount.toString(),
            "textSummary" to snapshot.textSummary,
            "truncated" to snapshot.truncated.toString(),
            "nodesJson" to snapshot.nodes.toScreenNodesJsonString(),
            "screenObservationJson" to observationJson,
            "maxTextChars" to "2000",
            "maxNodes" to "50",
        ) + snapshot.packageName?.let { mapOf("packageName" to it) }.orEmpty()
        return ToolResult(
            requestId = request.id,
            status = ToolStatus.Succeeded,
            summary = snapshot.observationSummary(),
            data = data,
        )
    }

    private fun uiActionResult(
        request: ToolRequest,
        before: ScreenStateSnapshot?,
        after: ScreenStateSnapshot,
    ): ToolResult {
        val (actionType, summary) = when (request.toolName) {
            MobileActionFunctions.UI_TAP -> "tap" to "已点击目标元素"
            MobileActionFunctions.UI_TYPE_TEXT -> "type_text" to "已输入文本"
            MobileActionFunctions.UI_SUBMIT_SEARCH -> "submit_search" to "已提交搜索"
            MobileActionFunctions.UI_LONG_PRESS -> "long_press" to "已长按目标元素"
            MobileActionFunctions.UI_SCROLL -> "scroll" to "已滚动"
            else -> error("unsupported last step ${request.toolName}")
        }
        val target = request.arguments["target"].orEmpty()
        val data = baseData(request) + mapOf(
            "actionType" to actionType,
            "status" to "succeeded",
            "retryable" to "false",
            "summary" to summary,
            "beforeObservationId" to before?.id.orEmpty(),
            "afterObservationId" to after.id,
            "verificationSummary" to after.observationSummary(),
            "screenObservationDiffSummary" to screenObservationDiffSummary(before, after),
        ) + (if (target.isNotBlank()) mapOf("target" to target) else emptyMap()) +
            before?.prefixedObservationData("before").orEmpty() +
            after.prefixedObservationData("after")
        return ToolResult(
            requestId = request.id,
            status = ToolStatus.Succeeded,
            summary = summary,
            data = data + AppSearchProgressEvidence.fromData(data).toData(),
        )
    }

    private fun baseData(request: ToolRequest): Map<String, String> =
        mapOf(
            "toolName" to request.toolName,
            "privacy" to MessagePrivacy.LocalOnly.name,
            "requiresLocalModel" to "true",
            "source" to DEVICE_CONTROL_SOURCE_ACCESSIBILITY,
            "metadataPolicy" to DEVICE_CONTROL_METADATA_POLICY,
        )

    private fun ScreenStateSnapshot.prefixedObservationData(prefix: String): Map<String, String> =
        mapOf(
            "${prefix}PackageName" to packageName.orEmpty(),
            "${prefix}CapturedAtMillis" to capturedAtMillis.toString(),
            "${prefix}NodeCount" to nodeCount.toString(),
            "${prefix}ActionableNodeCount" to actionableNodeCount.toString(),
            "${prefix}TextSummary" to textSummary,
            "${prefix}Truncated" to truncated.toString(),
            "${prefix}NodesJson" to nodes.toScreenNodesJsonString(),
            "${prefix}ScreenObservationJson" to toScreenObservationJsonString(),
        )

    private fun ScreenStateSnapshot.observationSummary(): String {
        val packagePart = packageName?.takeIf { it.isNotBlank() }?.let { "包名 $it，" }.orEmpty()
        return "已观察当前屏幕：${packagePart}$nodeCount 个节点，$actionableNodeCount 个可交互节点。"
    }

    /** Copy of the private `screenObservationDiffSummary` in tool/DeviceControlToolExecutor.kt. */
    private fun screenObservationDiffSummary(before: ScreenStateSnapshot?, after: ScreenStateSnapshot?): String {
        if (before == null && after == null) return "before_after_unavailable"
        if (before == null) return "before_unavailable;afterNodes=${after?.nodeCount ?: 0}"
        if (after == null) return "after_unavailable;beforeNodes=${before.nodeCount}"
        val beforeTexts = before.nodes.mapNotNull { it.diffTextLabel() }.toOrderedSet()
        val afterTexts = after.nodes.mapNotNull { it.diffTextLabel() }.toOrderedSet()
        val beforeActions = before.nodes.mapNotNull { it.diffActionableLabel() }.toOrderedSet()
        val afterActions = after.nodes.mapNotNull { it.diffActionableLabel() }.toOrderedSet()
        val addedTexts = (afterTexts - beforeTexts).take(6)
        val removedTexts = (beforeTexts - afterTexts).take(6)
        val addedActions = (afterActions - beforeActions).take(4)
        val removedActions = (beforeActions - afterActions).take(4)
        val changed = before.packageName != after.packageName ||
            before.nodeCount != after.nodeCount ||
            before.actionableNodeCount != after.actionableNodeCount ||
            addedTexts.isNotEmpty() || removedTexts.isNotEmpty() ||
            addedActions.isNotEmpty() || removedActions.isNotEmpty()
        val beforePackage = before.packageName.orEmpty().ifBlank { "unknown" }
        val afterPackage = after.packageName.orEmpty().ifBlank { "unknown" }
        return "changed=$changed;package=$beforePackage->$afterPackage" +
            ";nodes=${before.nodeCount}->${after.nodeCount}" +
            ";actionable=${before.actionableNodeCount}->${after.actionableNodeCount}" +
            ";addedText=${addedTexts.joinLimited()}" +
            ";removedText=${removedTexts.joinLimited()}" +
            ";addedActionable=${addedActions.joinLimited()}" +
            ";removedActionable=${removedActions.joinLimited()}"
    }

    private fun List<String>.toOrderedSet(): Set<String> =
        map(String::trim).filter(String::isNotBlank).toCollection(linkedSetOf())

    private fun List<String>.joinLimited(): String = joinToString("|") { it.take(64) }.ifBlank { "none" }

    private fun ScreenNode.diffTextLabel(): String? = text.ifBlank { contentDescription }.trim().takeIf(String::isNotBlank)

    private fun ScreenNode.diffActionableLabel(): String? {
        if (!clickable && !editable && !scrollable) return null
        val label = diffTextLabel() ?: id.takeIf(String::isNotBlank) ?: className
        val role = when {
            editable -> "editable"
            clickable -> "clickable"
            scrollable -> "scrollable"
            else -> "actionable"
        }
        return "$role:$label"
    }

    private fun labelsFromObservationJson(raw: String): Map<String, List<String>> {
        val elements = runCatching { JSONObject(raw).getJSONArray("elements") }.getOrNull() ?: return emptyMap()
        return (0 until elements.length()).map(elements::getJSONObject).associate { element ->
            element.optString("id") to listOf(element.optString("text")).filter(String::isNotBlank)
        }
    }

    // ---- screen loading ------------------------------------------------------------------------

    fun loadScreen(spec: JSONObject, snapshotId: String): Pair<ScreenStateSnapshot, List<OcrTextBlock>> {
        val (snapshot, ocr) = when {
            spec.has("dump") -> loadDump(spec.getString("dump"), snapshotId) to emptyList()
            spec.has("replay") -> loadReplay(spec.getString("replay"), snapshotId)
            spec.has("inline") -> loadInline(spec.getJSONObject("inline"), snapshotId) to emptyList()
            else -> error("unsupported screen spec $spec")
        }
        val edits = spec.optJSONObject("edits").stringMap()
        if (edits.isEmpty()) return snapshot to ocr
        val unknown = edits.keys - snapshot.nodes.map { it.id }.toSet()
        require(unknown.isEmpty()) { "edits reference unknown nodes $unknown in $snapshotId" }
        val nodes = snapshot.nodes.map { node -> edits[node.id]?.let { text -> node.copy(text = text) } ?: node }
        return snapshot.copy(nodes = nodes, textSummary = nodes.textSummary()) to ocr
    }

    private fun List<ScreenNode>.textSummary(): String = joinToString(" ") { node -> node.text.ifBlank { node.contentDescription } }

    private fun resourceText(path: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "missing fixture $path" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** Same conversion as `UiAutomatorDumpReplayTest.loadDump` (private there). */
    private fun loadDump(path: String, snapshotId: String): ScreenStateSnapshot {
        val document = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "missing dump $path" }
            .use { input -> DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input) }
        val nodes = mutableListOf<ScreenNode>()
        val root = document.documentElement
        collectNodes(root, "root", nodes)
        val packageName = firstPackageName(root)
            ?: nodes.firstOrNull { it.id.contains(":id/") }?.id?.substringBefore(":id/")
        return ScreenStateSnapshot(
            id = snapshotId,
            packageName = packageName,
            capturedAtMillis = 1L,
            nodes = nodes,
            textSummary = nodes.textSummary(),
            truncated = false,
        )
    }

    private fun collectNodes(element: Element, path: String, output: MutableList<ScreenNode>) {
        if (element.tagName == "node") {
            val resourceId = element.getAttribute("resource-id").orEmpty()
            output += ScreenNode(
                id = resourceId.ifBlank { path },
                text = element.getAttribute("text").orEmpty(),
                contentDescription = element.getAttribute("content-desc").orEmpty(),
                className = element.getAttribute("class").orEmpty(),
                bounds = parseBounds(element.getAttribute("bounds").orEmpty()),
                clickable = element.getAttribute("clickable").toBoolean(),
                editable = element.getAttribute("class").contains("EditText") || element.getAttribute("editable").toBoolean(),
                scrollable = element.getAttribute("scrollable").toBoolean(),
                enabled = element.getAttribute("enabled").ifBlank { "true" }.toBoolean(),
            )
        }
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == DomNode.ELEMENT_NODE) collectNodes(child as Element, "$path/$index", output)
        }
    }

    private fun firstPackageName(element: Element): String? {
        element.getAttribute("package").takeIf { it.isNotBlank() }?.let { return it }
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == DomNode.ELEMENT_NODE) firstPackageName(child as Element)?.let { return it }
        }
        return null
    }

    private val boundsRegex = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")

    private fun parseBounds(raw: String): ScreenBounds? {
        val match = boundsRegex.matchEntire(raw) ?: return null
        val (left, top, right, bottom) = match.destructured
        return ScreenBounds(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    /** Same conversion as `UiAutomatorDumpReplayTest.loadReplayCases` (private there). */
    private fun loadReplay(replayId: String, snapshotId: String): Pair<ScreenStateSnapshot, List<OcrTextBlock>> {
        val cases = JSONObject(resourceText("ui_dumps/p4_replay_eval/search_entry_contract.json")).getJSONArray("cases")
        val item = (0 until cases.length()).map(cases::getJSONObject).single { it.getString("id") == replayId }
        val nodesJson = item.getJSONArray("nodes")
        val nodes = (0 until nodesJson.length()).map(nodesJson::getJSONObject).map { node ->
            ScreenNode(
                id = node.getString("id"),
                text = node.optString("text"),
                contentDescription = node.optString("contentDescription"),
                className = node.optString("className"),
                bounds = node.optJSONObject("bounds")?.toScreenBounds(),
                clickable = node.optBoolean("clickable"),
                editable = node.optBoolean("editable"),
                scrollable = node.optBoolean("scrollable"),
                enabled = node.optBoolean("enabled", true),
            )
        }
        val snapshot = ScreenStateSnapshot(
            id = snapshotId,
            packageName = item.optString("packageName").takeIf(String::isNotBlank),
            capturedAtMillis = 1L,
            nodes = nodes,
            textSummary = nodes.textSummary(),
            truncated = false,
        )
        return snapshot to item.optJSONArray("ocrBlocks").ocrBlocks()
    }

    private fun loadInline(spec: JSONObject, snapshotId: String): ScreenStateSnapshot {
        val nodesJson = spec.getJSONArray("nodes")
        val nodes = (0 until nodesJson.length()).map(nodesJson::getJSONObject).map { node ->
            val bounds = node.getJSONArray("bounds")
            ScreenNode(
                id = node.getString("id"),
                text = node.optString("text"),
                contentDescription = node.optString("contentDescription"),
                className = node.optString("className"),
                bounds = ScreenBounds(bounds.getInt(0), bounds.getInt(1), bounds.getInt(2), bounds.getInt(3)),
                clickable = node.optBoolean("clickable"),
                editable = node.optBoolean("editable"),
                scrollable = node.optBoolean("scrollable"),
                enabled = node.optBoolean("enabled", true),
            )
        }
        return ScreenStateSnapshot(
            id = snapshotId,
            packageName = spec.getString("package"),
            capturedAtMillis = 1L,
            nodes = nodes,
            textSummary = nodes.textSummary(),
            truncated = false,
        )
    }

    private fun JSONObject.toScreenBounds(): ScreenBounds =
        ScreenBounds(getInt("left"), getInt("top"), getInt("right"), getInt("bottom"))

    private fun JSONObject.toOcrBounds(): OcrTextBounds =
        OcrTextBounds(getInt("left"), getInt("top"), getInt("right"), getInt("bottom"))

    private fun JSONArray?.ocrBlocks(): List<OcrTextBlock> =
        if (this == null) emptyList() else (0 until length()).map(::getJSONObject).map { block ->
            OcrTextBlock(
                text = block.getString("text"),
                bounds = block.optJSONObject("bounds")?.toOcrBounds(),
                lines = block.optJSONArray("lines").let { lines ->
                    if (lines == null) emptyList() else (0 until lines.length()).map(lines::getJSONObject).map { line ->
                        OcrTextLine(
                            text = line.getString("text"),
                            bounds = line.optJSONObject("bounds")?.toOcrBounds(),
                            elements = line.optJSONArray("elements").let { elements ->
                                if (elements == null) emptyList() else (0 until elements.length()).map(elements::getJSONObject).map {
                                    OcrTextElement(it.getString("text"), it.optJSONObject("bounds")?.toOcrBounds())
                                }
                            },
                        )
                    }
                },
            )
        }

    /** Prior steps use a neutral reason so they do not consume the replanner's model budget. */
    const val LAB_PRIOR_STEP_REASON = "lab fixed prior step"
}
