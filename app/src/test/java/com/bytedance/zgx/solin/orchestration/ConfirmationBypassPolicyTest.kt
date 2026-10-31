package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.safety.SafetyDecision
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.skill.SkillManifest
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.skill.SkillRequest
import com.bytedance.zgx.solin.skill.SkillStep
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
    private var remoteVisionDriving = false
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
        isRemoteVisionDrivingEnabled = { remoteVisionDriving },
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

    @Test
    fun typedChineseTextBuiltFromTheUsersCharactersCountsAsFromTheUser() {
        fun typing(text: String) =
            ToolRequest(toolName = MobileActionFunctions.UI_TYPE_TEXT, arguments = mapOf("text" to text))

        assertNull(policy.criticalApprovalReason(readPrivateDataRun("在携程搜索上海的酒店"), typing("上海酒店")))
        assertNull(policy.criticalApprovalReason(readPrivateDataRun("在高德地图搜索附近的咖啡店"), typing("附近咖啡店")))
        assertNull(
            policy.criticalApprovalReason(
                readPrivateDataRun("在微信里打开文件传输助手，输入“基准测试消息”但不要发送"),
                typing("基准测试消息"),
            ),
        )
        // Case, punctuation, whitespace and full-width forms are ignored; letters and digits split.
        assertNull(policy.criticalApprovalReason(readPrivateDataRun("在京东搜索 iPhone15 手机壳"), typing("ＩＰＨＯＮＥ 15，手机壳")))

        val hotel = readPrivateDataRun("在携程搜索上海的酒店")
        assertEquals(CriticalApprovalReason.TypedTextNotFromUser, policy.criticalApprovalReason(hotel, typing("上海民宿")))
    }

    @Test
    fun typedNumbersAndWordsMustAppearAsWholeTokens() {
        val runId = readPrivateDataRun("给10086发短信说查询话费")
        fun typing(text: String) =
            ToolRequest(toolName = MobileActionFunctions.UI_TYPE_TEXT, arguments = mapOf("text" to text))

        assertNull(policy.criticalApprovalReason(runId, typing("10086 查询话费")))
        assertEquals(CriticalApprovalReason.TypedTextNotFromUser, policy.criticalApprovalReason(runId, typing("13800138000")))
        assertEquals(CriticalApprovalReason.TypedTextNotFromUser, policy.criticalApprovalReason(runId, typing("1")))
        assertEquals(CriticalApprovalReason.TypedTextNotFromUser, policy.criticalApprovalReason(runId, typing("008")))

        val mail = readPrivateDataRun("给test@example.com写一封邮件")
        assertNull(policy.criticalApprovalReason(mail, typing("test@example.com")))
        assertEquals(CriticalApprovalReason.TypedTextNotFromUser, policy.criticalApprovalReason(mail, typing("te@example.com")))
        assertEquals(CriticalApprovalReason.TypedTextNotFromUser, policy.criticalApprovalReason(mail, typing("test@evil.com")))
    }

    @Test
    fun modelFlaggedTaintedWebSearchIsStillRaisedToConfirmation() {
        val runId = readPrivateDataRun("读剪贴板再搜索")
        val search = ToolRequest(
            id = "search",
            toolName = MobileActionFunctions.WEB_SEARCH,
            arguments = mapOf("query" to "abc"),
            sensitivityReason = "查询内容来自剪贴板",
        )
        assertEquals(CriticalApprovalReason.ModelFlaggedSensitive, policy.criticalApprovalReason(runId, search))
        listOf(true, false).forEach { mode ->
            criticalOnly = mode
            val planned = policy.withConfirmationBypass(runId, planOf(search, SafetyOutcome.Allow))
            assertEquals(SafetyOutcome.RequireConfirmation, planned.safetyDecision.outcome)
            assertTrue(planned.draft.requiresConfirmation)
        }
        // A flagged step SafetyPolicy allows (a NotRequired tool) is confirmed even in a clean run.
        criticalOnly = true
        val flaggedSearch = ToolRequest(toolName = MobileActionFunctions.WEB_SEARCH, arguments = mapOf("query" to "天气"), sensitivityReason = "x")
        assertEquals(
            SafetyOutcome.RequireConfirmation,
            policy.withConfirmationBypass("clean", planOf(flaggedSearch, SafetyOutcome.Allow)).safetyDecision.outcome,
        )
    }

    @Test
    fun confirmEveryStepContinuationNeverRelaxesACriticalReason() {
        criticalOnly = false
        val skillPlan = lowRiskSkillPlan()
        val runId = traceStore.createRun("在淘宝搜索牛奶").id
        val first = (skillPlan.steps.first() as SkillStep.ToolStep).request
        traceStore.appendStep(runId, AgentStep.ToolRequested(first, ActionDraft(first.toolName, "点击", "点击", first.arguments)))
        traceStore.appendStep(runId, AgentStep.UserConfirmed(first.id))

        val tap = ToolRequest(id = "tap", toolName = MobileActionFunctions.UI_TAP, arguments = mapOf("target" to "搜索"))
        assertEquals(
            SafetyOutcome.Allow,
            policy.withLowRiskAppControlContinuationBypass(runId, planOf(tap, SafetyOutcome.RequireConfirmation), skillPlan)
                .safetyDecision.outcome,
        )
        // The confirmed ui_tap read screen text, so typing is checked against the request.
        fun continued(request: ToolRequest) =
            policy.withLowRiskAppControlContinuationBypass(runId, planOf(request, SafetyOutcome.RequireConfirmation), skillPlan)
                .safetyDecision.outcome
        fun typing(text: String) =
            ToolRequest(id = "type", toolName = MobileActionFunctions.UI_TYPE_TEXT, arguments = mapOf("text" to text))
        assertEquals(SafetyOutcome.Allow, continued(typing("牛奶")))
        assertEquals(SafetyOutcome.RequireConfirmation, continued(typing("https://example.com/?q=abc")))
        assertEquals(SafetyOutcome.RequireConfirmation, continued(tap.copy(sensitivityReason = "涉及支付")))
    }

    @Test
    fun remoteVisionRunMustConfirmItsFirstInAppStepInCriticalOnlyMode() {
        remoteVisionDriving = true
        val runId = traceStore.createRun("打开淘宝并搜索").id
        val firstTap = ToolRequest(id = "tap-1", toolName = MobileActionFunctions.UI_TAP, arguments = mapOf("target" to "搜索"))

        // Critical-only mode would otherwise auto-run this reversible tap; the screenshot egress
        // it unlocks needs the run's first confirmation, so the tap is raised to a confirmation.
        assertEquals(CriticalApprovalReason.RemoteVisionFirstSend, policy.criticalApprovalReason(runId, firstTap))
        assertFalse(policy.isAutoApproved(runId, firstTap))
        val planned = policy.withConfirmationBypass(runId, planOf(firstTap, SafetyOutcome.Allow))
        assertEquals(SafetyOutcome.RequireConfirmation, planned.safetyDecision.outcome)
        assertTrue(planned.draft.requiresConfirmation)

        // Once the user confirmed that step, later steps run without a prompt again.
        traceStore.appendStep(runId, AgentStep.UserConfirmed(firstTap.id))
        val nextTap = ToolRequest(id = "tap-2", toolName = MobileActionFunctions.UI_TAP, arguments = mapOf("target" to "商品"))
        assertNull(policy.criticalApprovalReason(runId, nextTap))
        assertTrue(policy.isAutoApproved(runId, nextTap))

        // A run without remote-vision driving keeps the documented auto-run behaviour.
        remoteVisionDriving = false
        assertNull(policy.criticalApprovalReason(traceStore.createRun("再搜一次").id, firstTap))
    }

    @Test
    fun remoteVisionFirstSendOnlyGatesTheInAppActionNotUnrelatedTools() {
        remoteVisionDriving = true
        val runId = traceStore.createRun("先搜个公开资料").id
        // A public read-only search does not start the in-app GUI loop, so it is not the gate.
        assertNull(
            policy.criticalApprovalReason(
                runId,
                ToolRequest(id = "search", toolName = MobileActionFunctions.WEB_SEARCH, arguments = mapOf("query" to "Kotlin")),
            ),
        )
    }

    private fun lowRiskSkillPlan(): SkillPlan {
        val manifest = SkillManifest(
            id = "test_low_risk_app_control",
            version = 1,
            title = "测试",
            description = "测试",
            triggerExamples = emptyList(),
            requiredTools = listOf(MobileActionFunctions.UI_TAP),
            inputSchemaJson = """{"type":"object"}""",
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            lowRiskAppControlEligible = true,
        )
        val request = ToolRequest(id = "skill-tap", toolName = MobileActionFunctions.UI_TAP, arguments = mapOf("target" to "搜索框"))
        return SkillPlan(
            request = SkillRequest("skill-request", manifest.id, emptyMap(), "测试"),
            manifest = manifest,
            steps = listOf(
                SkillStep.ToolStep(
                    request = request,
                    draft = ActionDraft(request.toolName, "点击", "点击", request.arguments),
                ),
            ),
        )
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
