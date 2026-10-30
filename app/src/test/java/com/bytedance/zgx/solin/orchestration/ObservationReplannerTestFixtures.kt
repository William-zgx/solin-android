package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolProvider
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolStatus

/**
 * Shared fixtures for the observation replanner test classes: fake action runtimes, tool specs,
 * replan contexts, and screen-observation / OCR JSON builders.
 */
abstract class ObservationReplannerTestFixtures {
    protected fun replannerFor(spec: ToolSpec): SequentialActionObservationReplanner =
        SequentialActionObservationReplanner(
            actionPlanningRuntime = DraftActionRuntime(spec.name),
            toolRegistry = ToolRegistry(ToolProvider { listOf(spec) }),
        )

    protected fun contextFor(input: String): AgentObservationReplanContext {
        val previousRequest = ToolRequest(
            id = "previous-request",
            toolName = MobileActionFunctions.WEB_SEARCH,
            arguments = mapOf("query" to "Kotlin"),
        )
        return AgentObservationReplanContext(
            run = AgentRun(
                id = "run-1",
                input = input,
                state = AgentRunState.Observing,
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
            previousRequest = previousRequest,
            observedResult = ToolResult(
                requestId = previousRequest.id,
                status = ToolStatus.Succeeded,
                summary = "searched",
                data = mapOf("toolName" to previousRequest.toolName),
            ),
            priorRequests = listOf(previousRequest),
        )
    }

    protected fun localOnlyAllowedToolSpecs(): List<ToolSpec> =
        listOf(
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_SCROLL,
            MobileActionFunctions.UI_WAIT,
            MobileActionFunctions.UI_PRESS_BACK,
        ).map { toolName -> specFor(toolName = toolName) }

    protected fun specFor(
        toolName: String,
        privateOutputKeys: Set<String> = emptySet(),
        resultContinuationPolicy: ToolResultContinuationPolicy = ToolResultContinuationPolicy.None,
        capability: ToolCapability = when (toolName) {
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_SCROLL,
            MobileActionFunctions.UI_PRESS_BACK,
            MobileActionFunctions.UI_WAIT -> ToolCapability.DeviceControl

            MobileActionFunctions.WEB_SEARCH -> ToolCapability.WebSearch
            else -> ToolCapability.DeviceContext
        },
    ): ToolSpec =
        ToolSpec(
            name = toolName,
            title = "Test tool",
            description = "Test tool",
            inputSchemaJson = EMPTY_OBJECT_SCHEMA_JSON,
            capability = capability,
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Required,
            privateOutputKeys = privateOutputKeys,
            resultContinuationPolicy = resultContinuationPolicy,
        )

    protected class DraftActionRuntime(
        private val toolName: String,
    ) : ActionPlanningRuntime {
        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
            ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = toolName,
                        title = "Test tool",
                        summary = "Run test tool",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test",
            )
    }

    protected class RecordingModelActionRuntime(
        private val toolName: String,
        private val parameters: Map<String, String> = mapOf("target" to "继续"),
    ) : ActionPlanningRuntime {
        var lastInput: String? = null
        var lastActionModelPath: String? = null
        var lastToolSpecNames: List<String>? = null
        val inputs = mutableListOf<String>()

        /** The full observation prompt; later inputs may be the corrective re-prompt. */
        val firstInput: String? get() = inputs.firstOrNull()

        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?, toolSpecs: List<ToolSpec>): ActionPlanningResult {
            lastToolSpecNames = toolSpecs.map { spec -> spec.name }
            return plan(input, actionModelPath)
        }

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            inputs += input
            lastInput = input
            lastActionModelPath = actionModelPath
            return ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = toolName,
                        title = "Tap",
                        summary = "Tap target",
                        parameters = parameters,
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = true,
                fallbackReason = null,
            )
        }
    }

    protected fun screenObservationJson(): String =
        screenObservationFixtureJson(
            observationId = "screen-1",
            elements = arrayOf(
                accessibilityElement(
                    id = "continue-button",
                    text = "继续",
                    role = "button",
                    left = 10,
                    top = 20,
                    right = 110,
                    bottom = 70,
                    clickable = true,
                ),
                *accessibilityFillerElements().toTypedArray(),
                ocrElement("ocr:block:0", "继续", left = 10, top = 20, right = 110, bottom = 70),
            ),
        )

    protected fun String.shortlistDebugText(): String {
        val index = indexOf("screenObservationJson(")
        return if (index < 0) {
            take(500)
        } else {
            substring(index, minOf(length, index + 640))
        }
    }

    protected data class ScreenBoundsFixture(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    protected data class ScreenElementFixture(
        val id: String,
        val source: String,
        val text: String,
        val role: String,
        val bounds: ScreenBoundsFixture?,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val enabled: Boolean,
        val confidence: Double,
    )

    protected fun accessibilityElement(
        id: String,
        text: String,
        role: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        enabled: Boolean = true,
        confidence: Double = 1.0,
    ): ScreenElementFixture =
        ScreenElementFixture(
            id = id,
            source = "accessibility",
            text = text,
            role = role,
            bounds = ScreenBoundsFixture(left, top, right, bottom),
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            enabled = enabled,
            confidence = confidence,
        )

    protected fun ocrElement(
        id: String,
        text: String,
        left: Int? = null,
        top: Int? = null,
        right: Int? = null,
        bottom: Int? = null,
        role: String = "ocr_block",
        confidence: Double = 0.72,
    ): ScreenElementFixture =
        ScreenElementFixture(
            id = id,
            source = "ocr",
            text = text,
            role = role,
            bounds = if (left == null || top == null || right == null || bottom == null) {
                null
            } else {
                ScreenBoundsFixture(left, top, right, bottom)
            },
            clickable = false,
            editable = false,
            scrollable = false,
            enabled = true,
            confidence = confidence,
        )

    protected fun screenObservationFixtureJson(
        observationId: String,
        elements: Array<out ScreenElementFixture> = emptyArray(),
        capturedAtMillis: Long = 1L,
        packageName: String = "com.example.app",
        sources: List<String> = elements.map { element -> element.source }.distinct().ifEmpty { listOf("accessibility") },
        truncated: Boolean = false,
    ): String {
        val sourceCounts = sources
            .mapNotNull { source ->
                val count = elements.count { element -> element.source == source }
                if (count == 0) null else """"${source.asJsonString()}": $count"""
            }
            .joinToString(prefix = "{", postfix = "}")
        val elementsJson = if (elements.isEmpty()) {
            "[]"
        } else {
            elements.joinToString(prefix = "[\n", separator = ",\n", postfix = "\n]") { element ->
                element.toJson()
            }
        }
        return """
        {
          "schemaVersion": 1,
          "observationId": "${observationId.asJsonString()}",
          "capturedAtMillis": $capturedAtMillis,
          "packageName": "${packageName.asJsonString()}",
          "privacyLevel": "LocalOnly",
          "sources": ${sources.joinToString(prefix = "[", postfix = "]") { source -> """"${source.asJsonString()}"""" }},
          "elementCount": ${elements.size},
          "sourceCounts": $sourceCounts,
          "truncated": $truncated,
          "elements": $elementsJson
        }
        """.trimIndent()
    }

    protected fun ScreenElementFixture.toJson(): String {
        val boundsJson = bounds?.let { bounds ->
            """
              "bounds": {"left": ${bounds.left}, "top": ${bounds.top}, "right": ${bounds.right}, "bottom": ${bounds.bottom}},
            """.trimIndent()
        }
        return """
        {
          "id": "${id.asJsonString()}",
          "source": "${source.asJsonString()}",
          ${boundsJson.orEmpty()}
          "text": "${text.asJsonString()}",
          "role": "${role.asJsonString()}",
          "clickability": {"clickable": $clickable, "editable": $editable, "scrollable": $scrollable, "enabled": $enabled},
          "confidence": $confidence,
          "sensitiveFlags": [],
          "privacyLevel": "LocalOnly"
        }
        """.trimIndent()
    }

    protected fun accessibilityFillerElementsJson(): String =
        accessibilityFillerElements().joinToString(separator = ",\n") { element -> element.toJson() }

    protected fun accessibilityFillerElements(): List<ScreenElementFixture> =
        (1..12).map { index ->
            accessibilityElement(
                id = "label-$index",
                text = "列表项 $index",
                role = "text",
                left = 0,
                top = 100 + index,
                right = 10,
                bottom = 120 + index,
            )
        }

    protected fun ocrLabeledBlankAccessibilityObservationJson(): String =
        screenObservationFixtureJson(
            observationId = "screen-ocr-labeled-icon",
            elements = arrayOf(
                *actionableFillerElements(count = 12).toTypedArray(),
                accessibilityElement(
                    id = "icon-search-entry",
                    text = "",
                    role = "button",
                    left = 20,
                    top = 32,
                    right = 420,
                    bottom = 96,
                    clickable = true,
                ),
                ocrElement("ocr:block:0", "搜索入口", left = 48, top = 44, right = 220, bottom = 84),
            ),
        )

    protected fun sparseOcrLabeledBlankAccessibilityObservationJson(): String =
        screenObservationFixtureJson(
            observationId = "screen-sparse-ocr-labeled-icon",
            elements = arrayOf(
                *actionableFillerElements(count = 12).toTypedArray(),
                accessibilityElement(
                    id = "icon-search-entry",
                    text = "",
                    role = "button",
                    left = 20,
                    top = 32,
                    right = 420,
                    bottom = 96,
                    clickable = true,
                ),
                ocrElement("ocr:block:0", "搜索入口", left = 398, top = 44, right = 760, bottom = 84),
            ),
        )

    protected fun resolverRankedSearchEntryObservationJson(): String =
        screenObservationFixtureJson(
            observationId = "screen-search-noise",
            packageName = "com.taobao.taobao",
            elements = arrayOf(
                accessibilityElement(
                    id = "camera-search",
                    text = "拍照搜索",
                    role = "button",
                    left = 20,
                    top = 32,
                    right = 220,
                    bottom = 96,
                    clickable = true,
                ),
                accessibilityElement(
                    id = "home-feed",
                    text = "综合 销量 筛选 商品列表 旗舰店 ￥29 已售1000 评价",
                    role = "button",
                    left = 0,
                    top = 220,
                    right = 1080,
                    bottom = 1800,
                    clickable = true,
                    scrollable = true,
                ),
                accessibilityElement(
                    id = "promo-search",
                    text = "搜索好物 推荐 商品图片",
                    role = "button",
                    left = 240,
                    top = 32,
                    right = 420,
                    bottom = 96,
                    clickable = true,
                ),
                accessibilityElement(
                    id = "real-search-entry",
                    text = "搜索商品",
                    role = "button",
                    left = 36,
                    top = 84,
                    right = 1044,
                    bottom = 168,
                    clickable = true,
                ),
            ),
        )

    protected fun intentRankedFilterObservationJson(): String =
        screenObservationFixtureJson(
            observationId = "screen-filter-intent",
            packageName = "com.taobao.taobao",
            elements = arrayOf(
                accessibilityElement(
                    id = "search-entry",
                    text = "搜索商品",
                    role = "button",
                    left = 36,
                    top = 84,
                    right = 760,
                    bottom = 168,
                    clickable = true,
                ),
                accessibilityElement(
                    id = "result-list",
                    text = "综合 销量 筛选 商品列表 旗舰店 ￥29 已售1000 评价",
                    role = "scrollable",
                    left = 0,
                    top = 220,
                    right = 1080,
                    bottom = 1800,
                    clickable = true,
                    scrollable = true,
                ),
                accessibilityElement(
                    id = "filter-button",
                    text = "筛选",
                    role = "button",
                    left = 860,
                    top = 84,
                    right = 1044,
                    bottom = 168,
                    clickable = true,
                ),
            ),
        )

    protected fun delayedActionableObservationJson(): String =
        screenObservationFixtureJson(
            observationId = "screen-actionable-late",
            elements = arrayOf(
                *accessibilityFillerElements().toTypedArray(),
                accessibilityElement(
                    id = "late-search-input",
                    text = "搜索输入框",
                    role = "input",
                    left = 24,
                    top = 520,
                    right = 820,
                    bottom = 588,
                    clickable = true,
                    editable = true,
                ),
                accessibilityElement(
                    id = "late-submit",
                    text = "搜索",
                    role = "button",
                    left = 860,
                    top = 520,
                    right = 1040,
                    bottom = 588,
                    clickable = true,
                ),
                ocrElement(
                    id = "ocr:submit",
                    text = "搜索",
                    left = 860,
                    top = 520,
                    right = 1040,
                    bottom = 588,
                    confidence = 0.68,
                ),
            ),
        )

    protected fun searchEntryObservationJson(): String =
        screenObservationFixtureJson(
            observationId = "screen-search-entry",
            elements = arrayOf(
                accessibilityElement(
                    id = "search-entry",
                    text = "搜索入口",
                    role = "button",
                    left = 20,
                    top = 32,
                    right = 420,
                    bottom = 96,
                    clickable = true,
                ),
                accessibilityElement(
                    id = "home-title",
                    text = "首页",
                    role = "text",
                    left = 20,
                    top = 120,
                    right = 220,
                    bottom = 160,
                ),
            ),
        )

    protected fun searchInputObservationJson(): String =
        screenObservationFixtureJson(
            observationId = "screen-search-input",
            capturedAtMillis = 2L,
            elements = arrayOf(
                accessibilityElement(
                    id = "search-input",
                    text = "搜索输入框",
                    role = "input",
                    left = 20,
                    top = 32,
                    right = 820,
                    bottom = 96,
                    clickable = true,
                    editable = true,
                ),
                accessibilityElement(
                    id = "search-submit",
                    text = "搜索",
                    role = "button",
                    left = 900,
                    top = 32,
                    right = 1040,
                    bottom = 96,
                    clickable = true,
                ),
                accessibilityElement(
                    id = "cancel",
                    text = "取消",
                    role = "button",
                    left = 20,
                    top = 120,
                    right = 120,
                    bottom = 160,
                    clickable = true,
                ),
            ),
        )

    protected fun searchInputWithDangerousSiblingObservationJson(): String =
        screenObservationFixtureJson(
            observationId = "screen-search-input-dangerous-sibling",
            capturedAtMillis = 2L,
            elements = arrayOf(
                accessibilityElement(
                    id = "search-input",
                    text = "搜索输入框",
                    role = "input",
                    left = 20,
                    top = 32,
                    right = 820,
                    bottom = 96,
                    clickable = true,
                    editable = true,
                ),
                accessibilityElement(
                    id = "delete-recent",
                    text = "删除",
                    role = "button",
                    left = 20,
                    top = 120,
                    right = 180,
                    bottom = 160,
                    clickable = true,
                ),
                accessibilityElement(
                    id = "search-submit",
                    text = "搜索",
                    role = "button",
                    left = 900,
                    top = 32,
                    right = 1040,
                    bottom = 96,
                    clickable = true,
                ),
            ),
        )

    protected fun accessibilityOnlyObservationJson(): String =
        screenObservationFixtureJson(
            observationId = "screen-accessibility-only",
            elements = arrayOf(
                accessibilityElement(
                    id = "accessibility-button",
                    text = "继续",
                    role = "button",
                    left = 1,
                    top = 2,
                    right = 101,
                    bottom = 52,
                    clickable = true,
                ),
            ),
        )

    protected data class OcrBlockFixture(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val includeEmptyLines: Boolean = false,
    )

    protected fun ocrBlock(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        includeEmptyLines: Boolean = false,
    ): OcrBlockFixture =
        OcrBlockFixture(text, left, top, right, bottom, includeEmptyLines)

    protected fun ocrBlocksJson(vararg blocks: OcrBlockFixture): String =
        blocks.joinToString(prefix = "[", postfix = "]") { block ->
            val linesJson = if (block.includeEmptyLines) ""","lines":[]""" else ""
            """{"text":"${block.text.asJsonString()}","bounds":{"left":${block.left},"top":${block.top},"right":${block.right},"bottom":${block.bottom}}$linesJson}"""
        }

    protected fun String.asJsonString(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    protected fun repeatedContinueOcrBlocksJson(): String =
        ocrBlocksJson(
            ocrBlock("继续", left = 12, top = 22, right = 112, bottom = 72, includeEmptyLines = true),
            ocrBlock("继续", left = 12, top = 220, right = 112, bottom = 270, includeEmptyLines = true),
        )

    protected fun nestedOcrScreenObservationJson(): String =
        screenObservationFixtureJson(
            observationId = "screen-nested-ocr",
            sources = listOf("ocr"),
            elements = arrayOf(
                ocrElement(
                    id = "ocr:block:0",
                    text = "底部操作 取消 继续",
                    left = 20,
                    top = 20,
                    right = 230,
                    bottom = 70,
                ),
                ocrElement(
                    id = "ocr:block:0:line:0",
                    text = "取消 继续",
                    left = 20,
                    top = 20,
                    right = 230,
                    bottom = 70,
                    role = "ocr_line",
                ),
                ocrElement(
                    id = "ocr:block:0:line:0:element:0",
                    text = "取消",
                    left = 20,
                    top = 20,
                    right = 120,
                    bottom = 70,
                    role = "ocr_element",
                ),
                ocrElement(
                    id = "ocr:block:0:line:0:element:1",
                    text = "继续",
                    left = 130,
                    top = 20,
                    right = 230,
                    bottom = 70,
                    role = "ocr_element",
                ),
            ),
        )

    protected fun boundlessOcrScreenObservationJson(): String =
        screenObservationFixtureJson(
            observationId = "screen-boundless-ocr",
            sources = listOf("ocr"),
            elements = arrayOf(
                ocrElement("ocr:block:0", "继续"),
            ),
        )

    protected fun multiTokenOcrBlocksJson(): String =
        """
        [
          {
            "text":"取消 继续",
            "bounds":{"left":20,"top":20,"right":230,"bottom":70},
            "lines":[
              {
                "text":"取消 继续",
                "bounds":{"left":20,"top":20,"right":230,"bottom":70},
                "elements":[
                  {"text":"取消","bounds":{"left":20,"top":20,"right":110,"bottom":70}},
                  {"text":"继续","bounds":{"left":130,"top":20,"right":230,"bottom":70}}
                ]
              }
            ]
          }
        ]
        """.trimIndent()

    protected fun fusedRepeatedOcrObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-fused-repeated-ocr",
          "capturedAtMillis": 1,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility", "ocr"],
          "elementCount": 11,
          "sourceCounts": {"accessibility": 6, "ocr": 5},
          "truncated": false,
          "elements": [
            ${actionableFillerElementsJson()},
            {
              "id": "ocr:block:0",
              "source": "ocr",
              "bounds": {"left": 20, "top": 160, "right": 220, "bottom": 228},
              "text": "噪声",
              "role": "ocr_block",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.80,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "ocr:block:0:line:0",
              "source": "ocr",
              "bounds": {"left": 20, "top": 160, "right": 220, "bottom": 228},
              "text": "噪声",
              "role": "ocr_line",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.78,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "ocr:block:0:line:0:element:0",
              "source": "ocr",
              "bounds": {"left": 20, "top": 160, "right": 110, "bottom": 228},
              "text": "噪声",
              "role": "ocr_element",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.76,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "ocr:block:0:line:0:element:1",
              "source": "ocr",
              "bounds": {"left": 110, "top": 160, "right": 220, "bottom": 228},
              "text": "噪声",
              "role": "ocr_element",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.74,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "ocr:block:1",
              "source": "ocr",
              "bounds": {"left": 20, "top": 260, "right": 220, "bottom": 328},
              "text": "继续",
              "role": "ocr_block",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.84,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    protected fun actionableFillerElementsJson(count: Int = 6): String =
        actionableFillerElements(count).joinToString(separator = ",\n") { element -> element.toJson() }

    protected fun actionableFillerElements(count: Int = 6): List<ScreenElementFixture> =
        (0 until count).map { index ->
            val top = 20 + (index * 20)
            accessibilityElement(
                id = "action-$index",
                text = "操作 $index",
                role = "button",
                left = 0,
                top = top,
                right = 10,
                bottom = top + 16,
                clickable = true,
            )
        }

    protected fun scrollableObservationJson(): String =
        screenObservationFixtureJson(
            observationId = "screen-scrollable-list",
            elements = arrayOf(
                accessibilityElement(
                    id = "results-list",
                    text = "搜索结果",
                    role = "list",
                    left = 0,
                    top = 180,
                    right = 1080,
                    bottom = 1880,
                    scrollable = true,
                ),
                accessibilityElement(
                    id = "result-title",
                    text = "结果标题",
                    role = "text",
                    left = 24,
                    top = 220,
                    right = 620,
                    bottom = 280,
                ),
            ),
        )

    protected fun staticPaymentCopyObservationJson(): String =
        screenObservationFixtureJson(
            observationId = "screen-static-payment-copy",
            elements = arrayOf(
                accessibilityElement(
                    id = "payment-description",
                    text = "支付说明",
                    role = "text",
                    left = 20,
                    top = 32,
                    right = 620,
                    bottom = 96,
                ),
                accessibilityElement(
                    id = "continue-button",
                    text = "继续",
                    role = "button",
                    left = 20,
                    top = 120,
                    right = 220,
                    bottom = 180,
                    clickable = true,
                ),
            ),
        )

    protected fun emptyObservationJson(observationId: String): String =
        screenObservationFixtureJson(observationId = observationId)

    protected fun ocrOnlyObservationJson(
        observationId: String,
        texts: List<String>,
    ): String =
        screenObservationFixtureJson(
            observationId = observationId,
            sources = listOf("ocr"),
            elements = texts.mapIndexed { index, text ->
                val top = 520 + (index * 80)
                val bottom = top + 68
                ocrElement(
                    id = "ocr:submit:$index",
                    text = text,
                    left = 20,
                    top = top,
                    right = 220,
                    bottom = bottom,
                    confidence = 0.80,
                )
            }.toTypedArray(),
        )

    protected fun staticPaymentOcrCopyObservationJson(): String =
        screenObservationFixtureJson(
            observationId = "screen-static-payment-ocr-copy",
            sources = listOf("accessibility", "ocr"),
            elements = arrayOf(
                ocrElement(
                    id = "ocr:payment-description",
                    text = "支付说明",
                    left = 20,
                    top = 32,
                    right = 620,
                    bottom = 96,
                    confidence = 0.82,
                ),
                accessibilityElement(
                    id = "continue-button",
                    text = "继续",
                    role = "button",
                    left = 20,
                    top = 120,
                    right = 220,
                    bottom = 180,
                    clickable = true,
                ),
            ),
        )

    protected companion object {
        const val LOCAL_EVIDENCE_TOOL = "test_local_evidence_tool"
        const val PRIVATE_OUTPUT_TOOL = "test_private_output_tool"
        const val EMPTY_OBJECT_SCHEMA_JSON =
            """{"type":"object","properties":{},"additionalProperties":false}"""
    }
}
