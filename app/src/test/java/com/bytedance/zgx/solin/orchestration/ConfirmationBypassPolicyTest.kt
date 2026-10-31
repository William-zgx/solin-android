package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.safety.SafetyDecision
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolProvider
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Critical-only confirmation: which planned tools still ask the user before running. */
class ConfirmationBypassPolicyTest {
    private val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
    private var criticalOnly = true
    private val policy = ConfirmationBypassPolicy(
        toolRegistry = ToolRegistry(
            ToolProvider {
                defaultBuiltInToolRegistry.specs() + ToolSpec(
                    name = EXTERNAL_SEND_TOOL,
                    title = "外部发送",
                    description = "sends arguments to another app",
                    inputSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
                    capability = ToolCapability.Extension,
                    riskLevel = RiskLevel.HighExternalSend,
                )
            },
        ),
        traceStore = traceStore,
        traceQueries = AgentTraceQueries(traceStore),
        isCriticalOnlyConfirmationEnabled = { criticalOnly },
    )

    @Test
    fun reversibleDeviceActionsAreNotCritical() {
        listOf(
            ToolRequest(toolName = MobileActionFunctions.OPEN_APP_BY_NAME, arguments = mapOf("appName" to "淘宝")),
            ToolRequest(toolName = MobileActionFunctions.UI_TAP, arguments = mapOf("target" to "搜索")),
            ToolRequest(toolName = MobileActionFunctions.UI_TYPE_TEXT, arguments = mapOf("text" to "牛奶")),
            ToolRequest(toolName = MobileActionFunctions.COMPOSE_EMAIL, arguments = mapOf("body" to "明天聊")),
            ToolRequest(toolName = MobileActionFunctions.READ_CLIPBOARD),
            ToolRequest(toolName = MobileActionFunctions.WEB_SEARCH, arguments = mapOf("query" to "今天天气")),
        ).forEach { request ->
            assertNull(request.toolName, policy.criticalApprovalReason("run", request))
            assertTrue(request.toolName, policy.isAutoApproved("run", request))
        }
    }

    @Test
    fun unknownToolsExternalSendsFlaggedStepsAndSensitiveQueriesStayCritical() {
        assertEquals(
            CriticalApprovalReason.UnknownTool,
            policy.criticalApprovalReason("run", ToolRequest(toolName = "mcp__unknown")),
        )
        assertEquals(
            CriticalApprovalReason.ExternalSendOrPayment,
            policy.criticalApprovalReason("run", ToolRequest(toolName = EXTERNAL_SEND_TOOL)),
        )
        assertEquals(
            CriticalApprovalReason.ModelFlaggedSensitive,
            policy.criticalApprovalReason(
                "run",
                ToolRequest(
                    toolName = MobileActionFunctions.UI_TAP,
                    arguments = mapOf("target" to "下一步"),
                    sensitivityReason = "涉及支付",
                ),
            ),
        )
        assertEquals(
            CriticalApprovalReason.SensitiveNetworkQuery,
            policy.criticalApprovalReason(
                "run",
                ToolRequest(
                    toolName = MobileActionFunctions.WEB_SEARCH,
                    arguments = mapOf("query" to "我的身份证号 110101199003071234"),
                ),
            ),
        )
    }

    @Test
    fun sensitiveMapQueriesAndDeepLinksStayCritical() {
        assertEquals(
            CriticalApprovalReason.SensitiveNetworkQuery,
            policy.criticalApprovalReason(
                "run",
                ToolRequest(
                    toolName = MobileActionFunctions.OPEN_DEEP_LINK,
                    arguments = mapOf("uri" to "https://example.com/?q=身份证号110101199003071234"),
                ),
            ),
        )
        assertEquals(
            CriticalApprovalReason.SensitiveNetworkQuery,
            policy.criticalApprovalReason(
                "run",
                ToolRequest(
                    toolName = MobileActionFunctions.SEARCH_MAPS,
                    arguments = mapOf("query" to "我家附近 手机13800138000"),
                ),
            ),
        )
        assertNull(
            policy.criticalApprovalReason(
                "run",
                ToolRequest(toolName = MobileActionFunctions.OPEN_DEEP_LINK, arguments = mapOf("uri" to "https://example.com")),
            ),
        )
    }

    @Test
    fun networkStepsAfterAPrivateReadInTheSameRunStayCritical() {
        val runId = traceStore.createRun("读剪贴板再搜索").id
        val read = ToolRequest(id = "read", toolName = MobileActionFunctions.READ_CLIPBOARD)
        traceStore.appendStep(
            runId,
            AgentStep.ToolRequested(read, ActionDraft(read.toolName, "读剪贴板", "读剪贴板", emptyMap())),
        )
        val link = ToolRequest(id = "link", toolName = MobileActionFunctions.OPEN_DEEP_LINK, arguments = mapOf("uri" to "https://example.com/?q=abc"))
        val search = ToolRequest(id = "search", toolName = MobileActionFunctions.WEB_SEARCH, arguments = mapOf("query" to "abc"))
        assertEquals(CriticalApprovalReason.SensitiveNetworkQuery, policy.criticalApprovalReason(runId, link))
        assertEquals(CriticalApprovalReason.SensitiveNetworkQuery, policy.criticalApprovalReason(runId, search))
        // Local, reversible steps after the read still run without a prompt.
        assertNull(policy.criticalApprovalReason(runId, ToolRequest(toolName = MobileActionFunctions.UI_TAP, arguments = mapOf("target" to "搜索"))))
        // Another run is not tainted.
        assertNull(policy.criticalApprovalReason("other", link))
    }

    @Test
    fun taintedWebSearchIsRaisedToConfirmationInThePlanItself() {
        val runId = readPrivateDataRun("读剪贴板再搜索")
        val search = ToolRequest(id = "search", toolName = MobileActionFunctions.WEB_SEARCH, arguments = mapOf("query" to "abc"))

        val tainted = policy.withConfirmationBypass(runId, planOf(search, SafetyOutcome.Allow))
        assertEquals(SafetyOutcome.RequireConfirmation, tainted.safetyDecision.outcome)
        assertTrue(tainted.draft.requiresConfirmation)
        criticalOnly = false
        assertEquals(
            SafetyOutcome.RequireConfirmation,
            policy.withConfirmationBypass(runId, planOf(search, SafetyOutcome.Allow)).safetyDecision.outcome,
        )
        criticalOnly = true

        val clean = policy.withConfirmationBypass("other", planOf(search, SafetyOutcome.Allow))
        assertEquals(SafetyOutcome.Allow, clean.safetyDecision.outcome)
        val rejected = policy.withConfirmationBypass(runId, planOf(search, SafetyOutcome.Reject))
        assertEquals(SafetyOutcome.Reject, rejected.safetyDecision.outcome)
    }

    @Test
    fun calculateDoesNotTaintARun() {
        val runId = traceStore.createRun("算一下再搜索").id
        val calculate = ToolRequest(id = "calc", toolName = MobileActionFunctions.CALCULATE)
        traceStore.appendStep(
            runId,
            AgentStep.ToolRequested(calculate, ActionDraft(calculate.toolName, "计算", "计算", emptyMap())),
        )
        assertNull(
            policy.criticalApprovalReason(
                runId,
                ToolRequest(toolName = MobileActionFunctions.WEB_SEARCH, arguments = mapOf("query" to "abc")),
            ),
        )
    }

    @Test
    fun typedTextAfterAPrivateReadMustComeFromTheUser() {
        val runId = readPrivateDataRun("在淘宝搜索 海河 牛奶")
        fun typing(text: String) =
            ToolRequest(toolName = MobileActionFunctions.UI_TYPE_TEXT, arguments = mapOf("text" to text))

        assertNull(policy.criticalApprovalReason(runId, typing("海河牛奶".substring(2))))
        assertNull(policy.criticalApprovalReason(runId, typing("牛奶 海河")))
        assertEquals(
            CriticalApprovalReason.TypedTextNotFromUser,
            policy.criticalApprovalReason(runId, typing("https://example.com/?q=abc")),
        )
        // Nothing private has been read yet, so there is nothing to smuggle.
        val freshRun = traceStore.createRun("打开浏览器").id
        assertNull(policy.criticalApprovalReason(freshRun, typing("https://example.com")))
    }

    private fun readPrivateDataRun(input: String): String {
        val runId = traceStore.createRun(input).id
        val read = ToolRequest(id = "read", toolName = MobileActionFunctions.READ_CLIPBOARD)
        traceStore.appendStep(
            runId,
            AgentStep.ToolRequested(read, ActionDraft(read.toolName, "读剪贴板", "读剪贴板", emptyMap())),
        )
        return runId
    }

    private fun planOf(request: ToolRequest, outcome: SafetyOutcome) = AgentPlan.UseTool(
        request = request,
        draft = ActionDraft(request.toolName, "步骤", "步骤", request.arguments, requiresConfirmation = false),
        plannedByModel = true,
        fallbackReason = null,
        safetyDecision = SafetyDecision(outcome = outcome, reason = "test"),
    )

    @Test
    fun confirmEveryStepModeNeverAutoApproves() {
        criticalOnly = false
        assertFalse(
            policy.isAutoApproved("run", ToolRequest(toolName = MobileActionFunctions.UI_TAP, arguments = mapOf("target" to "搜索"))),
        )
    }

    private companion object {
        const val EXTERNAL_SEND_TOOL = "test_external_send"
    }
}
