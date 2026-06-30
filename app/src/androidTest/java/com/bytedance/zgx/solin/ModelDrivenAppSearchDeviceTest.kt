package com.bytedance.zgx.solin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.bytedance.zgx.solin.action.HybridActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.actionPrompt
import com.bytedance.zgx.solin.data.ModelRepository
import com.bytedance.zgx.solin.orchestration.AgentObservationReplanContext
import com.bytedance.zgx.solin.orchestration.AgentRun
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES
import com.bytedance.zgx.solin.orchestration.ModelObservationReplanner
import com.bytedance.zgx.solin.orchestration.observationModelPrompt
import com.bytedance.zgx.solin.skill.AppSearchPlanningMode
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.skill.SkillStep
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDrivenAppSearchDeviceTest {
    private val targetContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun installedActionModelBootstrapsAndReplansAppSearchFromObservation() {
        val modelRepository = ModelRepository(targetContext)
        val actionModelPath = modelRepository.verifiedObservationActionModelPath()
        assertNotNull("Expected installed and verified observation action model on device.", actionModelPath)
        val actionModelFile = File(requireNotNull(actionModelPath))
        assertTrue(actionModelFile.isFile)
        assertTrue(
            "Expected E2B/E4B observation action model, got ${actionModelFile.name}",
            actionModelFile.name in setOf(
                "gemma-4-E2B-it.litertlm",
                "gemma-4-E4B-it.litertlm",
            ),
        )

        val skillRuntime = BuiltInSkillRuntime(
            appSearchPlanningModeProvider = { AppSearchPlanningMode.ModelDrivenBootstrap },
        )
        val skillPlan = requireNotNull(skillRuntime.plan("打开淘宝搜索海河牛奶"))
        assertEquals(BuiltInSkillRuntime.MODEL_DRIVEN_OPEN_APP_UI_SEARCH_SKILL, skillPlan.request.skillId)
        assertEquals(
            listOf(
                MobileActionFunctions.OPEN_APP_BY_NAME,
                MobileActionFunctions.UI_WAIT,
                MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            ),
            skillPlan.steps.mapNotNull { step -> (step as? SkillStep.ToolStep)?.request?.toolName },
        )

        val observationToolRegistry = ToolRegistry.fromSupportedActions(
            MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES,
        )
        val actionRuntime = HybridActionPlanningRuntime(
            cacheDir = targetContext.cacheDir,
            toolRegistry = observationToolRegistry,
        )
        try {
            val replanner = ModelObservationReplanner(
                actionPlanningRuntime = actionRuntime,
                actionModelPathProvider = { actionModelPath },
                toolRegistry = observationToolRegistry,
                maxModelReplans = 3,
            )
            val previousRequest = ToolRequest(toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
            val replanContext = AgentObservationReplanContext(
                    run = AgentRun(
                        id = "device-model-driven-app-search",
                        input = "打开淘宝搜索海河牛奶",
                        state = AgentRunState.Observing,
                        createdAtMillis = 1_000L,
                        updatedAtMillis = 1_000L,
                    ),
                    previousRequest = previousRequest,
                    observedResult = ToolResult(
                        requestId = previousRequest.id,
                        status = ToolStatus.Succeeded,
                        summary = "已观察到淘宝首页搜索入口和搜索输入框",
                        data = mapOf(
                            "privacy" to MessagePrivacy.LocalOnly.name,
                            "requiresLocalModel" to true.toString(),
                            "screenObservationJson" to taobaoSearchObservationJson,
                        ),
                    ),
                    priorRequests = skillPlan.steps.mapNotNull { step ->
                        (step as? SkillStep.ToolStep)?.request
                    },
            )
            val replan = replanner.planNext(replanContext)

            if (replan == null) {
                val rawOutput = rawModelActionOutput(actionModelPath, replanContext, observationToolRegistry)
                assertNotNull(
                    "Expected local action model to plan one UI step from observation. rawOutput=$rawOutput",
                    replan,
                )
            }
            val planned = requireNotNull(replan)
            assertTrue(planned.plannedByModel)
            assertTrue(planned.request.toolName in replannableUiSearchTools)
            val target = planned.request.arguments["target"].orEmpty()
            if (planned.request.toolName in toolsWithRequiredTarget) {
                assertTrue(
                    "Unexpected target from model replan: $target",
                    target in expectedSearchTargets,
                )
            }
            if (planned.request.toolName == MobileActionFunctions.UI_TYPE_TEXT) {
                assertEquals("海河牛奶", planned.request.arguments["text"])
            }
        } finally {
            actionRuntime.close()
        }
    }

    private fun rawModelActionOutput(
        actionModelPath: String,
        replanContext: AgentObservationReplanContext,
        toolRegistry: ToolRegistry,
    ): String {
        val engine = Engine(
            EngineConfig(
                modelPath = actionModelPath,
                backend = Backend.CPU(),
                cacheDir = targetContext.cacheDir.absolutePath,
            ),
        )
        engine.initialize()
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(
                    "你是手机动作规划器。只能输出 call:function {\"arg\":\"value\"}，不解释。",
                ),
                samplerConfig = SamplerConfig(
                    topK = 1,
                    topP = 0.1,
                    temperature = 0.0,
                ),
            ),
        )
        return try {
            val output = StringBuilder()
            runBlocking {
                conversation.sendMessageAsync(
                    actionPrompt(
                        input = replanContext.observationModelPrompt(toolRegistry),
                        toolSpecs = toolRegistry.specs(),
                    ),
                ).collect { message ->
                    output.append(message.textContent())
                }
            }
            output.toString().ifBlank { "<blank>" }
        } catch (error: Throwable) {
            "rawOutputError=${error::class.java.simpleName}:${error.message}"
        } finally {
            conversation.close()
            engine.close()
        }
    }
}

private fun com.google.ai.edge.litertlm.Message.textContent(): String =
    contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString(separator = "") { it.text }

private val replannableUiSearchTools = setOf(
    MobileActionFunctions.UI_TAP,
    MobileActionFunctions.UI_TYPE_TEXT,
    MobileActionFunctions.UI_SUBMIT_SEARCH,
    MobileActionFunctions.UI_WAIT,
)

private val toolsWithRequiredTarget = setOf(
    MobileActionFunctions.UI_TAP,
    MobileActionFunctions.UI_TYPE_TEXT,
)

private val expectedSearchTargets = setOf(
    "search_entry",
    "search_goods",
    "search_input",
    "submit_search",
    "搜索入口",
    "搜索商品",
    "搜索输入框",
    "搜索",
)

private val taobaoSearchObservationJson = """
    {
      "schemaVersion": 1,
      "observationId": "device_model_driven_app_search_observation",
      "capturedAtMillis": 1000,
      "packageName": "com.taobao.taobao",
      "privacyLevel": "LocalOnly",
      "sources": ["accessibility"],
      "elementCount": 4,
      "sourceCounts": {"accessibility": 4},
      "truncated": false,
      "elements": [
        {
          "id": "search_entry",
          "source": "accessibility",
          "bounds": {"left": 24, "top": 64, "right": 1120, "bottom": 144},
          "text": "搜索入口",
          "role": "button",
          "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
          "confidence": 1.0,
          "sensitiveFlags": [],
          "privacyLevel": "LocalOnly"
        },
        {
          "id": "search_goods",
          "source": "accessibility",
          "bounds": {"left": 48, "top": 76, "right": 1000, "bottom": 132},
          "text": "搜索商品",
          "role": "button",
          "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
          "confidence": 1.0,
          "sensitiveFlags": [],
          "privacyLevel": "LocalOnly"
        },
        {
          "id": "search_input",
          "source": "accessibility",
          "bounds": {"left": 40, "top": 160, "right": 940, "bottom": 236},
          "text": "搜索输入框",
          "role": "editText",
          "clickability": {"clickable": true, "editable": true, "scrollable": false, "enabled": true},
          "confidence": 1.0,
          "sensitiveFlags": [],
          "privacyLevel": "LocalOnly"
        },
        {
          "id": "submit_search",
          "source": "accessibility",
          "bounds": {"left": 960, "top": 160, "right": 1120, "bottom": 236},
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
