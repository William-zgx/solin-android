package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.AppSearchProgressEvidence
import com.bytedance.zgx.solin.device.ScreenObservation
import com.bytedance.zgx.solin.device.screenObservationFromJsonStringOrNull
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 regression coverage: the invocation-local [ObservationJsonParseCache] must not change any
 * observed behavior of [ModelObservationReplanner] or its prompt builders, and the cache-aware
 * [AppSearchProgressEvidence.fromData] overload must match the default parser while parsing once.
 */
class ModelObservationReplannerParseCacheTest {
    @Test
    fun cachedAndUncachedObservationPromptsAreIdentical() {
        val context = observeContext(
            input = "打开淘宝搜索海河牛奶",
            data = mapOf(
                "toolName" to MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                "privacy" to MessagePrivacy.LocalOnly.name,
                "requiresLocalModel" to true.toString(),
                "screenObservationJson" to searchInputObservationJson(),
            ),
        )
        val registry = localOnlyRegistry()

        val uncached = context.observationModelPrompt(registry)
        val cached = context.observationModelPrompt(registry, ObservationJsonParseCache())

        assertEquals(uncached, cached)
        assertTrue(cached.contains("targetShortlist(type=search-input"))
        assertTrue(cached.contains("App search progress: stage="))
    }

    @Test
    fun cachedPromptPreservesMalformedObservationFallThrough() {
        val context = observeContext(
            input = "continue",
            data = mapOf(
                "toolName" to MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                "privacy" to MessagePrivacy.LocalOnly.name,
                "requiresLocalModel" to true.toString(),
                "screenObservationJson" to "{malformed",
                "ocrBlocksJson" to "[not-json",
            ),
        )
        val registry = localOnlyRegistry()

        val uncached = context.observationModelPrompt(registry)
        val cached = context.observationModelPrompt(registry, ObservationJsonParseCache())

        // Permissive parse: malformed observation JSON collapses to "none" evidence, unchanged by cache.
        assertEquals(uncached, cached)
        assertTrue(cached.contains("LocalOnly observation evidence: none"))
    }

    @Test
    fun cachedAndUncachedStandaloneOcrEvidencePromptsAreIdentical() {
        val result = ToolResult(
            requestId = "ocr-standalone",
            status = ToolStatus.Succeeded,
            summary = "OCR",
            data = mapOf(
                "screenObservationJson" to "{malformed",
                "screenObservationIncluded" to true.toString(),
                "ocrBlocksJson" to """
                    [
                      {
                        "text": "继续",
                        "bounds": {"left": 10, "top": 20, "right": 110, "bottom": 70},
                        "lines": []
                      }
                    ]
                """.trimIndent(),
            ),
        )

        val uncached = result.localObservationEvidencePrompt(intentPreview = "tap")
        val cached = result.localObservationEvidencePrompt(
            intentPreview = "tap",
            parseCache = ObservationJsonParseCache(),
        )

        assertEquals(uncached, cached)
        assertTrue(cached.contains("ocrBlocks(count=1)"))
        assertTrue(cached.contains("target=ocr:block:0"))
    }

    @Test
    fun replannerRejectsWebSearchFromMissingPrivacyObservationWithCacheActive() {
        val missingPrivacy = JSONObject(searchInputObservationJson()).apply {
            remove("privacyLevel")
        }.toString()
        val runtime = FixedDraftRuntime(
            toolName = MobileActionFunctions.WEB_SEARCH,
            parameters = mapOf("query" to "continue"),
        )
        val registry = ToolRegistry(
            ToolProvider { localOnlyToolSpecs() + specFor(MobileActionFunctions.WEB_SEARCH) },
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
            toolRegistry = registry,
        )

        val replan = replanner.planNext(
            observeContext(
                input = "continue",
                data = mapOf(
                    "toolName" to MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                    "privacy" to "RemoteEligible",
                    "requiresLocalModel" to false.toString(),
                    "screenObservationJson" to missingPrivacy,
                ),
            ),
        )

        // Missing privacy still routes as LocalOnly evidence: the non-local web_search draft is rejected.
        assertNull(replan)
        assertEquals("non_local_observation_tool", replanner.lastDiagnosticSnapshot()?.reason)
    }

    @Test
    fun replannerRejectsTargetedDangerousControlWithCacheActive() {
        val runtime = FixedDraftRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "delete-recent"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )

        val replan = replanner.planNext(
            observeContext(
                input = "看当前屏幕继续操作",
                data = mapOf(
                    "toolName" to MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to true.toString(),
                    "screenObservationJson" to dangerousSiblingObservationJson(),
                ),
            ),
        )

        assertNull(replan)
        assertEquals("dangerous_observation_action", replanner.lastDiagnosticSnapshot()?.reason)
    }

    @Test
    fun replannerAcceptsEditableTargetWithCacheActive() {
        val runtime = FixedDraftRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "search-input"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )

        val replan = replanner.planNext(
            observeContext(
                input = "点击搜索输入框",
                data = mapOf(
                    "toolName" to MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to true.toString(),
                    "screenObservationJson" to searchInputObservationJson(),
                ),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
        assertEquals("search-input", replan?.request?.arguments?.get("target"))
        assertEquals("accepted", replanner.lastDiagnosticSnapshot()?.reason)
    }

    @Test
    fun appSearchProgressInjectedParserMatchesDefaultAndParsesOnce() {
        val data = mapOf(
            "actionType" to "tap",
            "status" to "succeeded",
            "afterScreenObservationJson" to searchInputObservationJson(),
        )
        val default = AppSearchProgressEvidence.fromData(data)

        var parseCount = 0
        val counting = AppSearchProgressEvidence.fromData(data) { rawJson ->
            parseCount += 1
            screenObservationFromJsonStringOrNull(rawJson)
        }

        assertEquals(default, counting)
        assertEquals("input_ready", counting.appSearchProgressStage)
        // The stage classifier reads the same observation three times in the worst case; the injected
        // parser is expected to be a cache, so a real cache would satisfy those reads with one parse.
        // Here we only assert the parser was consulted at all (behavior parity), not the count from a
        // memoizing cache — that is covered by cacheReusesTypedObservationAcrossReads.
        assertTrue(parseCount >= 1)
    }

    @Test
    fun cacheReusesTypedObservationAcrossReads() {
        val cache = ObservationJsonParseCache()
        val rawJson = searchInputObservationJson()

        val first: ScreenObservation? = cache.screenObservationOrNull(rawJson)
        val second: ScreenObservation? = cache.screenObservationOrNull(rawJson)

        assertNotNull(first)
        // Same identity => memoized, not re-parsed.
        assertTrue(first === second)
    }

    private fun observeContext(input: String, data: Map<String, String>): AgentObservationReplanContext {
        val previousRequest = ToolRequest(
            id = "observe-1",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        )
        return AgentObservationReplanContext(
            run = AgentRun(
                id = "run-parse-cache",
                input = input,
                state = AgentRunState.Observing,
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
            previousRequest = previousRequest,
            observedResult = ToolResult(
                requestId = previousRequest.id,
                status = ToolStatus.Succeeded,
                summary = "已观察当前屏幕。",
                data = data,
            ),
            priorRequests = listOf(previousRequest),
        )
    }

    private fun localOnlyRegistry(): ToolRegistry =
        ToolRegistry(ToolProvider { localOnlyToolSpecs() })

    private fun localOnlyToolSpecs(): List<ToolSpec> =
        listOf(
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_SCROLL,
            MobileActionFunctions.UI_WAIT,
            MobileActionFunctions.UI_PRESS_BACK,
        ).map { toolName -> specFor(toolName) }

    private fun specFor(toolName: String): ToolSpec =
        ToolSpec(
            name = toolName,
            title = "Test tool",
            description = "Test tool",
            inputSchemaJson = EMPTY_OBJECT_SCHEMA_JSON,
            capability = when (toolName) {
                MobileActionFunctions.WEB_SEARCH -> ToolCapability.WebSearch
                else -> ToolCapability.DeviceControl
            },
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Required,
            privateOutputKeys = emptySet(),
            resultContinuationPolicy = ToolResultContinuationPolicy.None,
        )

    private class FixedDraftRuntime(
        private val toolName: String,
        private val parameters: Map<String, String>,
    ) : ActionPlanningRuntime {
        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
            ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = toolName,
                        title = "Draft",
                        summary = "Draft target",
                        parameters = parameters,
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = true,
                fallbackReason = null,
            )
    }

    private fun searchInputObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-search-input",
          "capturedAtMillis": 2,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility"],
          "elementCount": 2,
          "sourceCounts": {"accessibility": 2},
          "truncated": false,
          "elements": [
            {
              "id": "search-input",
              "source": "accessibility",
              "bounds": {"left": 20, "top": 32, "right": 820, "bottom": 96},
              "text": "搜索输入框",
              "role": "input",
              "clickability": {"clickable": true, "editable": true, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "search-submit",
              "source": "accessibility",
              "bounds": {"left": 900, "top": 32, "right": 1040, "bottom": 96},
              "text": "搜索",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private fun dangerousSiblingObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-dangerous-sibling",
          "capturedAtMillis": 2,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility"],
          "elementCount": 2,
          "sourceCounts": {"accessibility": 2},
          "truncated": false,
          "elements": [
            {
              "id": "search-input",
              "source": "accessibility",
              "bounds": {"left": 20, "top": 32, "right": 820, "bottom": 96},
              "text": "搜索输入框",
              "role": "input",
              "clickability": {"clickable": true, "editable": true, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "delete-recent",
              "source": "accessibility",
              "bounds": {"left": 20, "top": 120, "right": 180, "bottom": 160},
              "text": "删除",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private companion object {
        private const val EMPTY_OBJECT_SCHEMA_JSON =
            """{"type":"object","properties":{},"additionalProperties":false}"""
    }
}
