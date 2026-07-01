package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.DEFAULT_CHAT_MODEL_ID
import com.bytedance.zgx.solin.LocalModelTokenLimits
import com.bytedance.zgx.solin.MEMORY_EMBEDDING_MODEL_ID
import com.bytedance.zgx.solin.MOBILE_ACTION_MODEL_ID
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionIntentConfidence
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.AppDeepTargets
import com.bytedance.zgx.solin.action.IntentCandidate
import com.bytedance.zgx.solin.action.IntentRoutingPath
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.MobileActionPlanner
import com.bytedance.zgx.solin.action.ModelToolOutputParseResult
import com.bytedance.zgx.solin.action.SystemSettingsTargets
import com.bytedance.zgx.solin.audit.InMemoryToolAuditSink
import com.bytedance.zgx.solin.audit.ToolAuditEventType
import com.bytedance.zgx.solin.data.AgentSkillRunCheckpointEntity
import com.bytedance.zgx.solin.data.AgentRunEntity
import com.bytedance.zgx.solin.data.AgentStepEntity
import com.bytedance.zgx.solin.data.AgentTraceDao
import com.bytedance.zgx.solin.data.PendingAgentConfirmationEntity
import com.bytedance.zgx.solin.device.DeviceContextSnapshot
import com.bytedance.zgx.solin.device.DEVICE_CONTROL_METADATA_POLICY
import com.bytedance.zgx.solin.device.DEVICE_CONTROL_SOURCE_ACCESSIBILITY
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.memory.MemoryIndex
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrContract
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.skill.AppSearchPlanningMode
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.skill.SkillManifest
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.skill.SkillRequest
import com.bytedance.zgx.solin.skill.SkillRuntime
import com.bytedance.zgx.solin.skill.SkillStep
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolPermission
import com.bytedance.zgx.solin.tool.UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX
import com.bytedance.zgx.solin.tool.ToolError
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopRuntimeTest {
    @Test
    fun pureChatInputPlansAnswerAndKeepsMemoryHits() {
        val memoryRepository = MemoryRepository()
        memoryRepository.index("preference", "用户喜欢端侧离线聊天")
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
        val runtime = AgentLoopRuntime(
            memoryIndex = memoryRepository,
            actionPlanningRuntime = actionRuntime,
            traceStore = traceStore,
        )

        val result = runtime.runOnce(
            input = "端侧聊天偏好是什么",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
        )

        assertEquals(AgentRunState.GeneratingAnswer, result.run.state)
        require(result.plan is AgentPlan.Answer)
        assertTrue(result.plan.promptForModel.contains("用户当前输入的语言"))
        assertTrue(result.plan.promptForModel.contains("[证据=Memory"))
        assertTrue(result.plan.promptForModel.contains("用户喜欢端侧离线聊天"))
        assertEquals(listOf("preference"), result.plan.memoryHits.map { it.id })
        assertEquals(0, actionRuntime.planCallCount)
        assertTrue(result.steps.any { step ->
            step is AgentStep.ContextLoaded &&
                step.memoryHits.map { it.id } == listOf("preference")
        })
        assertTrue(result.steps.any { step ->
            step is AgentStep.ModelPlanned && step.plan is AgentPlan.Answer
        })
    }

    @Test
    fun answerPromptBudgetsEvidenceCardsAndMarksTruncation() {
        val memoryRepository = MemoryRepository()
        memoryRepository.index(
            id = "long-memory",
            text = "端侧上下文" + "很长".repeat(LocalModelTokenLimits.MAX_INPUT_TOKENS) + "TAIL_SHOULD_NOT_APPEAR",
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = memoryRepository,
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "端侧上下文是什么",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
        )

        require(result.plan is AgentPlan.Answer)
        assertTrue(result.plan.promptForModel.contains("已截断"))
        assertFalse(result.plan.promptForModel.contains("TAIL_SHOULD_NOT_APPEAR"))
    }

    @Test
    fun modelFirstRemoteToolsSkipsActionRuntimeForNonSkillInput() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = wifiPlanningResult(),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "帮我处理一个需要工具的复杂请求",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            options = AgentRunOptions(
                initialPlanningMode = InitialPlanningMode.ModelFirstRemoteTools,
                remoteToolScope = RemoteToolScope.ModelPlanning,
            ),
        )

        assertEquals(AgentRunState.GeneratingAnswer, result.run.state)
        assertTrue(result.plan is AgentPlan.Answer)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
    }

    @Test
    fun remoteModelCannotRequestToolBeforeToolSnapshotIsRecorded() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "普通远程问题",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            options = AgentRunOptions(
                initialPlanningMode = InitialPlanningMode.ModelFirstRemoteTools,
                remoteToolScope = RemoteToolScope.ModelPlanning,
            ),
        )

        val observed = runtime.observeModelToolRequest(
            runId = result.run.id,
            request = ToolRequest(
                id = "call-clipboard",
                toolName = MobileActionFunctions.READ_CLIPBOARD,
                reason = "remote guessed private tool",
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        val decision = observed.decision as AgentObservationDecision.Fail
        assertTrue(decision.reason.contains("before a remote tool snapshot is recorded"))
        assertEquals(null, runtime.latestPendingConfirmation())
        assertTrue(observed.steps.any { step -> step is AgentStep.ToolRejected })
        assertTrue(observed.steps.none { step -> step is AgentStep.UserConfirmationRequested })
    }

    @Test
    fun remoteToolSnapshotFiltersToolsNotEligibleForScope() {
        val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = traceStore,
        )
        val result = runtime.runOnce(
            input = "普通远程问题",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            options = AgentRunOptions(
                initialPlanningMode = InitialPlanningMode.ModelFirstRemoteTools,
                remoteToolScope = RemoteToolScope.ModelPlanning,
            ),
        )
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.ModelPlanning,
            toolNames = setOf(
                MobileActionFunctions.WEB_SEARCH,
                MobileActionFunctions.SHARE_TEXT,
                MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                "not_a_real_tool",
            ),
        )

        val exposed = traceStore.steps(result.run.id)
            .filterIsInstance<AgentStep.RemoteToolsExposed>()
            .single()
        assertEquals(
            listOf(MobileActionFunctions.SHARE_TEXT, MobileActionFunctions.WEB_SEARCH),
            exposed.toolNames,
        )

        val observed = runtime.observeModelToolRequest(
            runId = result.run.id,
            request = ToolRequest(
                id = "call-screen",
                toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                reason = "remote attempted private screen context",
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        val decision = observed.decision as AgentObservationDecision.Fail
        assertTrue(decision.reason.contains("current remote tool snapshot"))
        assertEquals(null, runtime.latestPendingConfirmation())
        assertTrue(observed.steps.any { step -> step is AgentStep.ToolRejected })
        assertTrue(observed.steps.none { step -> step is AgentStep.UserConfirmationRequested })
    }

    @Test
    fun remoteModelCannotRequestScopeEligibleToolMissingFromExposedSnapshot() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "普通远程问题",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            options = AgentRunOptions(
                initialPlanningMode = InitialPlanningMode.ModelFirstRemoteTools,
                remoteToolScope = RemoteToolScope.ModelPlanning,
            ),
        )
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.ModelPlanning,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )

        val observed = runtime.observeModelToolRequest(
            runId = result.run.id,
            request = ToolRequest(
                id = "call-share",
                toolName = MobileActionFunctions.SHARE_TEXT,
                arguments = mapOf("text" to "hello"),
                reason = "remote guessed hidden scope-eligible tool",
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        val decision = observed.decision as AgentObservationDecision.Fail
        assertTrue(decision.reason.contains("current remote tool snapshot"))
        assertEquals(null, runtime.latestPendingConfirmation())
        assertTrue(observed.steps.any { step -> step is AgentStep.RemoteToolsExposed })
        assertTrue(observed.steps.none { step -> step is AgentStep.UserConfirmationRequested })
    }

    @Test
    fun remoteMixedBatchRejectionRecordsAttemptedToolNames() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "普通远程问题",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            options = AgentRunOptions(
                initialPlanningMode = InitialPlanningMode.ModelFirstRemoteTools,
                remoteToolScope = RemoteToolScope.PublicEvidenceOnly,
            ),
        )
        val requests = listOf(
            ToolRequest(
                id = "search",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "天气"),
            ),
            ToolRequest(
                id = "contacts",
                toolName = MobileActionFunctions.QUERY_CONTACTS,
                arguments = mapOf("query" to "张三"),
            ),
        )
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = requests.mapTo(linkedSetOf()) { request -> request.toolName },
        )

        val observed = runtime.observeModelToolRequests(result.run.id, requests)

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        val rejected = observed.steps.filterIsInstance<AgentStep.ToolRejected>().single().result
        assertEquals(MobileActionFunctions.QUERY_CONTACTS, rejected.data["toolName"])
        assertEquals(
            "${MobileActionFunctions.WEB_SEARCH},${MobileActionFunctions.QUERY_CONTACTS}",
            rejected.data["attemptedToolNames"],
        )
        assertTrue(observed.steps.none { step -> step is AgentStep.UserConfirmationRequested })
    }

    @Test
    fun modelFirstRemoteToolsKeepsDirectSkillPreflightLocal() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = false,
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "查联系人 Alice",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            options = AgentRunOptions(
                initialPlanningMode = InitialPlanningMode.ModelFirstRemoteTools,
                remoteToolScope = RemoteToolScope.ModelPlanning,
            ),
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.QUERY_CONTACTS, result.plan.request.toolName)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
    }

    @Test
    fun modelFirstRemoteToolsKeepsDirectPublicEvidenceSkillLocal() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = false,
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "搜一下 Kotlin 协程",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            options = AgentRunOptions(
                initialPlanningMode = InitialPlanningMode.ModelFirstRemoteTools,
                remoteToolScope = RemoteToolScope.ModelPlanning,
            ),
        )

        assertEquals(AgentRunState.ExecutingTool, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.WEB_SEARCH, result.plan.request.toolName)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
    }

    @Test
    fun defaultRuleFirstStillPlansActionLikeInputBeforeModelAnswer() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = wifiPlanningResult(),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, result.plan.request.toolName)
    }

    @Test
    fun localModelWebSearchToolCallExecutesAfterAnswerGenerationWithoutConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = false,
            modelOutputResult = modelToolOutputPlanningResult(
                """call:web_search{"query":"Kotlin coroutines"}""",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "帮我查 Kotlin 协程资料",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.GeneratingAnswer, result.run.state)

        val observed = runtime.observeModelResult(
            result.run.id,
            """call:web_search{"query":"Kotlin coroutines"}""",
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.ExecutingTool, observed.run.state)
        val decision = observed.decision as AgentObservationDecision.PlanNextTool
        assertEquals(MobileActionFunctions.WEB_SEARCH, decision.plan.request.toolName)
        assertEquals(mapOf("query" to "Kotlin coroutines"), decision.plan.request.arguments)
        assertEquals(true, decision.plan.plannedByModel)
        assertEquals("local model tool call", decision.plan.fallbackReason)
        assertEquals(null, runtime.latestPendingConfirmation())
        assertTrue(observed.steps.none { it is AgentStep.UserConfirmationRequested })
        assertEquals(1, actionRuntime.parseModelToolOutputCallCount)
    }

    @Test
    fun localModelActionToolCallWithoutMobileActionProfileFailsClosed() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = false,
            modelOutputResult = modelToolOutputPlanningResult("""call:share_text{"text":"draft"}"""),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "准备分享一段文字",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        val observed = runtime.observeModelResult(result.run.id, """call:share_text{"text":"draft"}""")

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        require(observed.decision is AgentObservationDecision.Fail)
        assertTrue(observed.decision.reason.contains("Missing model capability MobileAction"))
        assertTrue(observed.steps.any { step ->
            step is AgentStep.ModelPlanned &&
                step.plan == AgentPlan.MissingModel(ModelCapability.MobileAction)
        })
        assertTrue(observed.steps.filterIsInstance<AgentStep.ToolRequested>().none { step ->
            step.request.toolName == MobileActionFunctions.SHARE_TEXT
        })
        assertEquals(null, runtime.latestPendingConfirmation())
        assertEquals(1, actionRuntime.parseModelToolOutputCallCount)
    }

    @Test
    fun remotePlainTextDoesNotUseInlineLocalToolParser() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = false,
            modelOutputResult = modelToolOutputPlanningResult(
                """call:share_text{"text":"should not become a tool"}""",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "普通远程问题",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            options = AgentRunOptions(initialPlanningMode = InitialPlanningMode.ModelFirstRemoteTools),
        )

        val observed = runtime.observeModelResult(
            runId = result.run.id,
            text = """call:share_text{"text":"should not become a tool"}""",
            allowInlineToolCalls = false,
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Completed, observed.run.state)
        assertEquals(AgentObservationDecision.Complete, observed.decision)
        assertEquals(null, runtime.latestPendingConfirmation())
        assertEquals(0, actionRuntime.parseModelToolOutputCallCount)
        assertTrue(observed.steps.none { step -> step is AgentStep.ToolRejected })
        assertTrue(observed.steps.none { step -> step is AgentStep.UserConfirmationRequested })
    }

    @Test
    fun publicEvidenceToolResultContinuesToModelForSynthesis() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = false,
            modelOutputResult = modelToolOutputPlanningResult(
                """call:web_search{"query":"北京和上海的天气"}""",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "北京和上海的温差是多少？",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        val planned = runtime.observeModelResult(
            result.run.id,
            """call:web_search{"query":"北京和上海的天气"}""",
        )
        requireNotNull(planned)
        require(planned.decision is AgentObservationDecision.PlanNextTool)

        val observed = runtime.observeToolResult(
            runId = result.run.id,
            result = ToolResult(
                requestId = planned.decision.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取北京、上海当前天气。",
                data = webSearchResultData(
                    query = "北京和上海的天气",
                    summaryText = "已读取北京、上海当前天气。",
                    resultsJson = """
                        {"kind":"weather_current","locations":[{"requestedLocation":"北京","current":{"temperature_2m":12.0}},{"requestedLocation":"上海","current":{"temperature_2m":18.0}}]}
                    """.trimIndent(),
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertFalse(observed.decision.requiresLocalModel)
        assertFalse(observed.continuationRequiresLocalModel)
        val prompt = observed.continuationPromptForModel.orEmpty()
        assertTrue(prompt.contains("北京和上海的温差是多少"))
        assertTrue(prompt.contains("已读取北京、上海当前天气"))
        assertTrue(prompt.contains("temperature_2m"))
        assertTrue(prompt.contains("可以继续调用公开只读工具补充证据"))
        assertTrue(prompt.contains("以每条证据的 retrievedAt"))
        assertTrue(prompt.contains("必须优先使用最新 retrievedAt 的工具证据"))
        assertTrue(prompt.contains("不得用模型训练知识、旧知识或未给出的网页内容补全空白"))
        assertTrue(prompt.contains("最终答案必须列出使用的来源/链接"))
    }

    @Test
    fun publicEvidenceContinuationRejectsActionToolRequestNotExposedInScope() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "普通远程问题",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            options = AgentRunOptions(
                initialPlanningMode = InitialPlanningMode.ModelFirstRemoteTools,
                remoteToolScope = RemoteToolScope.ModelPlanning,
            ),
        )
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.ModelPlanning,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )
        val planned = runtime.observeModelToolRequest(
            runId = result.run.id,
            request = ToolRequest(
                id = "call-weather",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "北京天气"),
                reason = "remote public evidence",
            ),
        )
        requireNotNull(planned)
        require(planned.decision is AgentObservationDecision.PlanNextTool)
        val observed = runtime.observeToolResult(
            runId = result.run.id,
            result = ToolResult(
                requestId = planned.decision.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取北京天气。",
                data = webSearchResultData(
                    query = "北京天气",
                    summaryText = "北京 12 摄氏度。",
                ),
            ),
        )
        requireNotNull(observed)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = setOf(MobileActionFunctions.COMPOSE_EMAIL),
        )

        val rejected = runtime.observeModelToolRequest(
            runId = result.run.id,
            request = ToolRequest(
                id = "call-email",
                toolName = MobileActionFunctions.COMPOSE_EMAIL,
                reason = "remote action after public evidence",
            ),
        )

        requireNotNull(rejected)
        assertEquals(AgentRunState.Failed, rejected.run.state)
        val decision = rejected.decision as AgentObservationDecision.Fail
        assertTrue(decision.reason.contains("current remote tool snapshot"))
        assertEquals(null, runtime.latestPendingConfirmation())
        assertTrue(rejected.steps.none { step -> step is AgentStep.UserConfirmationRequested })
    }

    @Test
    fun remoteModelSensitiveWebSearchRequiresConfirmationBeforeNetworkAccess() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "帮我检查手机号有没有泄露",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            options = AgentRunOptions(
                initialPlanningMode = InitialPlanningMode.ModelFirstRemoteTools,
                remoteToolScope = RemoteToolScope.ModelPlanning,
            ),
        )
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.ModelPlanning,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )

        val observed = runtime.observeModelToolRequest(
            runId = result.run.id,
            request = ToolRequest(
                id = "call-sensitive-web-search",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "搜索我的手机号 13800138000 有没有泄露"),
                reason = "remote public evidence",
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.AwaitingUserConfirmation, observed.run.state)
        val decision = observed.decision as AgentObservationDecision.PlanNextTool
        assertEquals(SafetyOutcome.RequireConfirmation, decision.plan.safetyDecision.outcome)
        assertTrue(decision.plan.draft.requiresConfirmation)
        assertEquals("call-sensitive-web-search", runtime.latestPendingConfirmation()?.request?.id)
        assertTrue(observed.steps.any { step -> step is AgentStep.UserConfirmationRequested })
        assertTrue(observed.steps.none { step -> step is AgentStep.ToolObserved })
    }

    @Test
    fun remoteModelMultiplePublicEvidenceToolCallsPlanBatchWithoutConfirmation() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "北京和上海今天温差多少？",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        val requests = listOf(
            ToolRequest(
                id = "call-beijing",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "北京天气"),
                reason = "remote tool call",
            ),
            ToolRequest(
                id = "call-shanghai",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "上海天气"),
                reason = "remote tool call",
            ),
        )
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )

        val observed = runtime.observeModelToolRequests(result.run.id, requests)

        requireNotNull(observed)
        assertEquals(AgentRunState.ExecutingTool, observed.run.state)
        val decision = observed.decision as AgentObservationDecision.PlanToolBatch
        assertEquals(2, decision.plans.size)
        assertEquals(requests.map { request -> request.id }, decision.plans.map { plan -> plan.request.id })
        assertEquals(
            listOf(MobileActionFunctions.WEB_SEARCH, MobileActionFunctions.WEB_SEARCH),
            decision.plans.map { plan -> plan.request.toolName },
        )
        assertTrue(decision.plans.all { plan -> plan.plannedByModel })
        assertEquals(null, runtime.latestPendingConfirmation())
        assertEquals(2, observed.steps.filterIsInstance<AgentStep.ToolRequested>().size)
        assertTrue(observed.steps.none { step -> step is AgentStep.UserConfirmationRequested })
    }

    @Test
    fun remoteModelSensitiveWebSearchBatchIsRejectedBeforeAnyNetworkAccess() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "普通远程问题",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        val requests = listOf(
            ToolRequest(
                id = "call-weather",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "北京天气"),
                reason = "remote tool call",
            ),
            ToolRequest(
                id = "call-sensitive",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "搜索我的手机号 13800138000 有没有泄露"),
                reason = "remote tool call",
            ),
        )
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )

        val observed = runtime.observeModelToolRequests(result.run.id, requests)

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        val decision = observed.decision as AgentObservationDecision.Fail
        assertTrue(decision.reason.contains("requires confirmation"))
        assertEquals(null, runtime.latestPendingConfirmation())
        assertTrue(observed.steps.none { step -> step is AgentStep.ToolRequested })
        assertTrue(observed.steps.none { step -> step is AgentStep.ToolObserved })
        assertTrue(observed.steps.any { step ->
            step is AgentStep.SafetyChecked &&
                step.decision.outcome == SafetyOutcome.RequireConfirmation
        })
    }

    @Test
    fun remoteModelMixedToolBatchIsRejectedAsWholeBeforeAnyToolRequest() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "查天气然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        val requests = listOf(
            ToolRequest(
                id = "call-weather",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "北京天气"),
                reason = "remote tool call",
            ),
            ToolRequest(
                id = "call-wifi",
                toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                reason = "remote tool call",
            ),
        )
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH, MobileActionFunctions.OPEN_WIFI_SETTINGS),
        )

        val observed = runtime.observeModelToolRequests(result.run.id, requests)

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        assertTrue(observed.decision is AgentObservationDecision.Fail)
        assertEquals(null, runtime.latestPendingConfirmation())
        assertTrue(observed.steps.none { step -> step is AgentStep.ToolRequested })
        assertTrue(observed.steps.any { step -> step is AgentStep.ToolRejected })
    }

    @Test
    fun publicEvidenceToolBatchResultsAggregateAndContinueToModel() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "北京和上海今天温差多少？",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        val requests = listOf(
            ToolRequest(
                id = "call-beijing",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "北京天气"),
                reason = "remote tool call",
            ),
            ToolRequest(
                id = "call-shanghai",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "上海天气"),
                reason = "remote tool call",
            ),
        )
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )
        val planned = runtime.observeModelToolRequests(result.run.id, requests)
        requireNotNull(planned)
        require(planned.decision is AgentObservationDecision.PlanToolBatch)

        val observed = runtime.observeToolResults(
            runId = result.run.id,
            results = listOf(
                ToolResult(
                    requestId = "call-beijing",
                    status = ToolStatus.Succeeded,
                    summary = "已读取北京当前天气。",
                    data = webSearchResultData(
                        query = "北京天气",
                        summaryText = "北京 12 摄氏度。",
                        resultsJson = """{"kind":"weather_current","locations":[{"requestedLocation":"北京","current":{"temperature_2m":12.0}}]}""",
                    ),
                ),
                ToolResult(
                    requestId = "call-shanghai",
                    status = ToolStatus.Succeeded,
                    summary = "已读取上海当前天气。",
                    data = webSearchResultData(
                        query = "上海天气",
                        summaryText = "上海 18 摄氏度。",
                        resultsJson = """{"kind":"weather_current","locations":[{"requestedLocation":"上海","current":{"temperature_2m":18.0}}]}""",
                    ),
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertFalse(observed.decision.requiresLocalModel)
        assertFalse(observed.continuationRequiresLocalModel)
        assertEquals(2, observed.steps.filterIsInstance<AgentStep.ToolObserved>().size)
        assertTrue(observed.assistantMessage.contains("证据摘要"))
        assertTrue(observed.assistantMessage.contains("查询："))
        assertTrue(observed.assistantMessage.contains("北京天气"))
        assertTrue(observed.assistantMessage.contains("上海天气"))
        assertTrue(observed.assistantMessage.contains("来源 1 个"))
        val prompt = observed.continuationPromptForModel.orEmpty()
        assertTrue(prompt.contains("北京和上海今天温差多少"))
        assertTrue(prompt.contains("已读取北京当前天气"))
        assertTrue(prompt.contains("已读取上海当前天气"))
        assertTrue(prompt.contains("temperature_2m"))
        assertTrue(prompt.contains("公开只读"))
    }

    @Test
    fun publicEvidenceToolBatchAssistantMessageIncludesAuditSummaryWithoutLocalOnlyData() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "Kotlin 目前最新发布是什么？",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        val requests = listOf(
            ToolRequest(
                id = "call-kotlin",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "Kotlin latest release"),
                reason = "remote tool call",
            ),
            ToolRequest(
                id = "call-local-only",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "local-only should not leak"),
                reason = "remote tool call",
            ),
        )
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )
        val planned = runtime.observeModelToolRequests(result.run.id, requests)
        requireNotNull(planned)
        require(planned.decision is AgentObservationDecision.PlanToolBatch)

        val observed = runtime.observeToolResults(
            runId = result.run.id,
            results = listOf(
                ToolResult(
                    requestId = "call-kotlin",
                    status = ToolStatus.Succeeded,
                    summary = "已读取 Kotlin 发布信息。",
                    data = webSearchResultData(
                        query = "Kotlin latest release",
                        summaryText = "Kotlin releases page.",
                        resultsJson = """
                            {
                              "kind": "web_search_evidence",
                              "query": "Kotlin latest release",
                              "retrievedAt": "2026-06-13T00:00:00Z",
                              "sources": [
                                {
                                  "id": "duckduckgo_html",
                                  "name": "DuckDuckGo HTML",
                                  "url": "https://html.duckduckgo.com/html/"
                                }
                              ],
                              "results": [
                                {
                                  "kind": "web_result",
                                  "sourceId": "duckduckgo_html",
                                  "title": "Kotlin releases",
                                  "url": "https://kotlinlang.org/docs/releases.html"
                                }
                              ]
                            }
                        """.trimIndent(),
                    ),
                ),
                ToolResult(
                    requestId = "call-local-only",
                    status = ToolStatus.Succeeded,
                    summary = "PRIVATE_LOCAL_SUMMARY",
                    data = mapOf(
                        "toolName" to MobileActionFunctions.WEB_SEARCH,
                        "privacy" to MessagePrivacy.LocalOnly.name,
                        "requiresLocalModel" to "true",
                        "query" to "PRIVATE_LOCAL_QUERY",
                        "secret" to "PRIVATE_LOCAL_DATA",
                    ),
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.assistantMessage.contains("部分失败"))
        assertTrue(observed.assistantMessage.contains("证据摘要"))
        assertTrue(observed.assistantMessage.contains("Kotlin latest release"))
        assertTrue(observed.assistantMessage.contains("来源 1 个"))
        assertTrue(observed.assistantMessage.contains("Kotlin releases"))
        assertTrue(observed.assistantMessage.contains("https://kotlinlang.org/docs/releases.html"))
        assertFalse(observed.assistantMessage.contains("PRIVATE_LOCAL_SUMMARY"))
        assertFalse(observed.assistantMessage.contains("PRIVATE_LOCAL_QUERY"))
        assertFalse(observed.assistantMessage.contains("PRIVATE_LOCAL_DATA"))
    }

    @Test
    fun publicEvidenceToolBatchPartialFailureContinuesWithEvidenceAndGap() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "北京和上海今天温差多少？",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        val requests = listOf(
            ToolRequest(
                id = "call-beijing",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "北京天气"),
                reason = "remote tool call",
            ),
            ToolRequest(
                id = "call-shanghai",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "上海天气"),
                reason = "remote tool call",
            ),
        )
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )
        val planned = runtime.observeModelToolRequests(result.run.id, requests)
        requireNotNull(planned)
        require(planned.decision is AgentObservationDecision.PlanToolBatch)

        val observed = runtime.observeToolResults(
            runId = result.run.id,
            results = listOf(
                ToolResult(
                    requestId = "call-beijing",
                    status = ToolStatus.Succeeded,
                    summary = "已读取北京当前天气。",
                    data = webSearchResultData(
                        query = "北京天气",
                        summaryText = "北京 12 摄氏度。",
                        resultsJson = """{"kind":"weather_current","locations":[{"requestedLocation":"北京","current":{"temperature_2m":12.0}}]}""",
                    ),
                ),
                ToolResult(
                    requestId = "call-shanghai",
                    status = ToolStatus.Failed,
                    summary = "搜索上海天气失败",
                    data = mapOf("toolName" to MobileActionFunctions.WEB_SEARCH),
                    error = ToolError(ToolErrorCode.ExecutionFailed, "网络错误"),
                    retryable = false,
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertEquals(ToolStatus.Succeeded, observed.result.status)
        assertEquals("1", observed.result.data["succeededToolCount"])
        assertEquals("1", observed.result.data["failedToolCount"])
        assertTrue(observed.assistantMessage.contains("部分失败"))
        val prompt = observed.continuationPromptForModel.orEmpty()
        assertTrue(prompt.contains("已读取北京当前天气"))
        assertTrue(prompt.contains("temperature_2m"))
        assertTrue(prompt.contains("失败缺口"))
        assertTrue(prompt.contains("上海天气"))
        assertTrue(prompt.contains("搜索上海天气失败"))
        assertTrue(prompt.contains("无法完成的部分明确说明缺少什么信息"))
    }

    @Test
    fun sequentialPublicEvidenceContinuationIncludesPriorEvidence() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "北京和上海今天温差多少？",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )
        val firstPlan = runtime.observeModelToolRequest(
            runId = result.run.id,
            request = ToolRequest(
                id = "call-beijing",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "北京天气"),
                reason = "remote tool call",
            ),
        )
        requireNotNull(firstPlan)
        require(firstPlan.decision is AgentObservationDecision.PlanNextTool)
        val firstObserved = runtime.observeToolResult(
            runId = result.run.id,
            result = ToolResult(
                requestId = "call-beijing",
                status = ToolStatus.Succeeded,
                summary = "已读取北京当前天气。",
                data = webSearchResultData(
                    query = "北京天气",
                    summaryText = "北京 12 摄氏度。",
                    resultsJson = """{"kind":"weather_current","locations":[{"requestedLocation":"北京","current":{"temperature_2m":12.0}}]}""",
                ),
            ),
        )
        requireNotNull(firstObserved)
        require(firstObserved.decision is AgentObservationDecision.ContinueWithModel)

        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )
        val secondPlan = runtime.observeModelToolRequest(
            runId = result.run.id,
            request = ToolRequest(
                id = "call-shanghai",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "上海天气"),
                reason = "remote tool call",
            ),
        )
        requireNotNull(secondPlan)
        require(secondPlan.decision is AgentObservationDecision.PlanNextTool)

        val secondObserved = runtime.observeToolResult(
            runId = result.run.id,
            result = ToolResult(
                requestId = "call-shanghai",
                status = ToolStatus.Succeeded,
                summary = "已读取上海当前天气。",
                data = webSearchResultData(
                    query = "上海天气",
                    summaryText = "上海 18 摄氏度。",
                    resultsJson = """{"kind":"weather_current","locations":[{"requestedLocation":"上海","current":{"temperature_2m":18.0}}]}""",
                ),
            ),
        )

        requireNotNull(secondObserved)
        assertEquals(AgentRunState.GeneratingAnswer, secondObserved.run.state)
        require(secondObserved.decision is AgentObservationDecision.ContinueWithModel)
        val prompt = secondObserved.continuationPromptForModel.orEmpty()
        assertTrue(prompt.contains("已读取北京当前天气"))
        assertTrue(prompt.contains("已读取上海当前天气"))
        assertTrue(prompt.contains("北京 12 摄氏度"))
        assertTrue(prompt.contains("上海 18 摄氏度"))
        assertTrue(prompt.contains("不要只回答最后一次工具结果"))
    }

    @Test
    fun publicEvidenceContinuationRejectsResultMissingRemotePrivacyDeclaration() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "北京和上海今天温差多少？",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )
        val planned = runtime.observeModelToolRequest(
            runId = result.run.id,
            request = ToolRequest(
                id = "call-beijing",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "北京天气"),
                reason = "remote tool call",
            ),
        )
        requireNotNull(planned)
        require(planned.decision is AgentObservationDecision.PlanNextTool)

        val observed = runtime.observeToolResult(
            runId = result.run.id,
            result = ToolResult(
                requestId = "call-beijing",
                status = ToolStatus.Succeeded,
                summary = "已读取北京当前天气。",
                data = webSearchResultData().minus("privacy").minus("requiresLocalModel"),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        require(observed.decision is AgentObservationDecision.Fail)
        assertNull(observed.continuationPromptForModel)
        assertEquals(ToolErrorCode.InvalidResult, observed.result.error?.code)
    }

    @Test
    fun publicEvidenceToolBatchCancelledResultCancelsRun() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "北京和上海今天温差多少？",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        val requests = listOf(
            ToolRequest(
                id = "call-beijing",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "北京天气"),
                reason = "remote tool call",
            ),
            ToolRequest(
                id = "call-shanghai",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "上海天气"),
                reason = "remote tool call",
            ),
        )
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )
        val planned = runtime.observeModelToolRequests(result.run.id, requests)
        requireNotNull(planned)
        require(planned.decision is AgentObservationDecision.PlanToolBatch)

        val observed = runtime.observeToolResults(
            runId = result.run.id,
            results = listOf(
                ToolResult(
                    requestId = "call-beijing",
                    status = ToolStatus.Cancelled,
                    summary = "用户停止了工具执行",
                    data = mapOf("toolName" to MobileActionFunctions.WEB_SEARCH),
                ),
                ToolResult(
                    requestId = "call-shanghai",
                    status = ToolStatus.Succeeded,
                    summary = "已读取上海当前天气。",
                    data = webSearchResultData(query = "上海天气", summaryText = "上海 18 摄氏度。"),
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Cancelled, observed.run.state)
        assertEquals(AgentObservationDecision.Cancel, observed.decision)
        assertEquals(ToolStatus.Cancelled, observed.result.status)
        assertEquals(null, observed.continuationPromptForModel)
        assertTrue(observed.assistantMessage.contains("已取消"))
    }

    @Test
    fun publicEvidenceToolBatchCancelAndFailureAggregatesAsCancelled() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "北京和上海今天温差多少？",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        val requests = listOf(
            ToolRequest(
                id = "call-beijing",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "北京天气"),
                reason = "remote tool call",
            ),
            ToolRequest(
                id = "call-shanghai",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "上海天气"),
                reason = "remote tool call",
            ),
        )
        runtime.recordRemoteToolsExposed(
            runId = result.run.id,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )
        val planned = runtime.observeModelToolRequests(result.run.id, requests)
        requireNotNull(planned)
        require(planned.decision is AgentObservationDecision.PlanToolBatch)

        val observed = runtime.observeToolResults(
            runId = result.run.id,
            results = listOf(
                ToolResult(
                    requestId = "call-beijing",
                    status = ToolStatus.Failed,
                    summary = "搜索失败",
                    data = mapOf("toolName" to MobileActionFunctions.WEB_SEARCH),
                    error = ToolError(ToolErrorCode.ExecutionFailed, "搜索失败"),
                    retryable = false,
                ),
                ToolResult(
                    requestId = "call-shanghai",
                    status = ToolStatus.Cancelled,
                    summary = "用户停止了工具执行",
                    data = mapOf("toolName" to MobileActionFunctions.WEB_SEARCH),
                    error = ToolError(ToolErrorCode.UserCancelled, "用户停止了工具执行"),
                    retryable = false,
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Cancelled, observed.run.state)
        assertEquals(AgentObservationDecision.Cancel, observed.decision)
        assertEquals(ToolStatus.Cancelled, observed.result.status)
        assertEquals(ToolErrorCode.UserCancelled, observed.result.error?.code)
        assertTrue(observed.assistantMessage.contains("已取消"))
    }

    @Test
    fun localModelToolCallAuditSummariesDoNotPersistArguments() {
        val secretPayload = "SECRET_SHARE_TEXT"
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = false,
            modelOutputResult = modelToolOutputPlanningResult("""call:share_text{"text":"$secretPayload"}"""),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "普通问题",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            installedCapabilityProfiles = listOf(ModelCatalog.profileForModelId(MOBILE_ACTION_MODEL_ID)),
        )

        val observed = runtime.observeModelResult(result.run.id, """call:share_text{"text":"$secretPayload"}""")

        requireNotNull(observed)
        assertEquals(AgentRunState.AwaitingUserConfirmation, observed.run.state)
        val planningEvents = auditSink.events.filter { event ->
            event.eventType in setOf(
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ConfirmationRequested,
            )
        }
        assertEquals(2, planningEvents.size)
        assertTrue(planningEvents.none { event -> event.summary.contains(secretPayload) })
        assertTrue(planningEvents.any { event -> event.summary == "local model tool call" })
    }

    @Test
    fun ordinaryModelAnswerStillCompletesWithoutActionParsingFallback() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = false,
            modelOutputResult = ModelToolOutputParseResult.None,
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "解释一下 web_search 工具",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        val observed = runtime.observeModelResult(
            result.run.id,
            "web_search 工具会直接返回只读搜索结果，不打开浏览器。",
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Completed, observed.run.state)
        assertEquals(AgentObservationDecision.Complete, observed.decision)
        assertEquals(null, runtime.latestPendingConfirmation())
        assertEquals(1, actionRuntime.parseModelToolOutputCallCount)
        assertEquals(0, actionRuntime.planCallCount)
    }

    @Test
    fun localModelUnknownToolCallOutputFailsRunWithoutPendingConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = false,
            modelOutputResult = modelToolOutputPlanningResult("""call:unknown_tool{}"""),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "执行未知工具",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        val observed = runtime.observeModelResult(result.run.id, """call:unknown_tool{}""")

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        assertTrue(observed.decision is AgentObservationDecision.Fail)
        assertTrue((observed.decision as AgentObservationDecision.Fail).reason.contains("Unknown tool"))
        assertEquals(null, runtime.latestPendingConfirmation())
        assertTrue(observed.steps.any { step -> step is AgentStep.ToolRejected })
        assertTrue(observed.steps.none { step -> step is AgentStep.UserConfirmationRequested })
    }

    @Test
    fun localModelInvalidToolArgumentsFailBeforeConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = false,
            modelOutputResult = modelToolOutputPlanningResult("""call:compose_email{"subject":"Hi"}"""),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val result = runtime.runOnce(
            input = "普通问题",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            installedCapabilityProfiles = listOf(ModelCatalog.profileForModelId(MOBILE_ACTION_MODEL_ID)),
        )

        val observed = runtime.observeModelResult(result.run.id, """call:compose_email{"subject":"Hi"}""")

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        assertTrue(observed.decision is AgentObservationDecision.Fail)
        assertEquals(null, runtime.latestPendingConfirmation())
        assertTrue(observed.steps.any { step -> step is AgentStep.ToolRejected })
        assertTrue(observed.steps.none { step -> step is AgentStep.UserConfirmationRequested })
    }

    @Test
    fun confirmToolRequestRevalidatesPendingRequestSchemaBeforeExecution() {
        val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = traceStore,
        )
        val run = traceStore.createRun("发邮件")
        val waitingRun = traceStore.updateState(run.id, AgentRunState.AwaitingUserConfirmation)
        val invalidRequest = ToolRequest(
            id = "invalid-email",
            toolName = MobileActionFunctions.COMPOSE_EMAIL,
            arguments = mapOf("subject" to "Hi"),
            reason = "restored invalid pending request",
        )
        traceStore.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = waitingRun,
                request = invalidRequest,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.COMPOSE_EMAIL,
                    title = "写邮件",
                    summary = "restored invalid pending request",
                    parameters = invalidRequest.arguments,
                ),
                skillId = null,
                plannedByModel = true,
                fallbackReason = "test restored pending",
            ),
        )

        val confirmed = runtime.confirmToolRequest(run.id, invalidRequest.id)

        requireNotNull(confirmed)
        assertEquals(AgentRunState.Failed, confirmed.state)
        assertEquals(null, runtime.latestPendingConfirmation())
        val steps = traceStore.steps(run.id)
        assertTrue(steps.any { step -> step is AgentStep.ToolRejected })
        assertTrue(steps.none { step -> step is AgentStep.UserConfirmed })
    }

    @Test
    fun modelToolRequestCannotReusePriorToolRequestId() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "读取剪贴板并总结",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observedRead = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = clipboardResultData("local private text"),
            ),
        )
        requireNotNull(observedRead)
        assertEquals(AgentRunState.GeneratingAnswer, observedRead.run.state)
        runtime.recordRemoteToolsExposed(
            runId = planned.run.id,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )

        val duplicate = runtime.observeModelToolRequest(
            runId = planned.run.id,
            request = ToolRequest(
                id = planned.plan.request.id,
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "Kotlin"),
                reason = "remote tool call",
            ),
        )

        requireNotNull(duplicate)
        assertEquals(AgentRunState.Failed, duplicate.run.state)
        assertTrue(duplicate.decision is AgentObservationDecision.Fail)
        assertTrue((duplicate.decision as AgentObservationDecision.Fail).reason.contains("already exists"))
        assertEquals(null, runtime.latestPendingConfirmation())
        assertTrue(duplicate.steps.none { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.toolName == MobileActionFunctions.WEB_SEARCH
        })
    }

    @Test
    fun memorySearchFailureFallsBackToEmptyContext() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = ThrowingMemoryIndex(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "普通问题",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
        )

        assertEquals(AgentRunState.GeneratingAnswer, result.run.state)
        require(result.plan is AgentPlan.Answer)
        assertTrue(result.plan.memoryHits.isEmpty())
        assertEquals("普通问题", result.plan.promptForModel)
        assertTrue(result.steps.any { step ->
            step is AgentStep.ContextLoaded && step.memoryHits.isEmpty()
        })
    }

    @Test
    fun memoryContextBuildFailureStillAllowsDeviceContextPrompt() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = ThrowingMemoryIndex(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val deviceContext = DeviceContextSnapshot(
            isArm64Supported = true,
            inferenceMode = "Remote",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
            availableStorageBytes = 512L * 1024L * 1024L,
            activeSessionId = "session-1",
            hasPendingConfirmation = false,
        )

        val result = runtime.runOnce(
            input = "现在用的是什么推理模式",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
            deviceContext = deviceContext,
        )

        require(result.plan is AgentPlan.Answer)
        assertTrue(result.plan.memoryHits.isEmpty())
        assertTrue(Regex("""本地记忆：\s+无""").containsMatchIn(result.plan.promptForModel))
        assertTrue(result.plan.promptForModel.contains("Inference mode: Remote"))
    }

    @Test
    fun pureChatInputCanCarryMinimalDeviceContext() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val deviceContext = DeviceContextSnapshot(
            isArm64Supported = true,
            inferenceMode = "Remote",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
            availableStorageBytes = 512L * 1024L * 1024L,
            activeSessionId = "session-1",
            hasPendingConfirmation = false,
        )

        val result = runtime.runOnce(
            input = "现在用的是什么推理模式",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
            deviceContext = deviceContext,
        )

        require(result.plan is AgentPlan.Answer)
        assertTrue(result.plan.promptForModel.contains("设备上下文"))
        assertTrue(result.plan.promptForModel.contains("Inference mode: Remote"))
        assertTrue(result.steps.any { step ->
            step is AgentStep.ContextLoaded && step.deviceContext == deviceContext
        })
    }

    @Test
    fun wifiActionInputRequestsConfirmationBeforeExecution() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        title = "打开 Wi-Fi 设置",
                        summary = "将打开系统 Wi-Fi 设置页。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = traceStore,
        )

        val result = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, result.plan.request.toolName)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, result.plan.draft.functionName)
        assertEquals(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL, result.plan.skillRequest?.skillId)
        assertEquals(SafetyOutcome.RequireConfirmation, result.plan.safetyDecision.outcome)
        assertTrue(result.plan.draft.requiresConfirmation)
        assertTrue(!result.plan.plannedByModel)
        assertEquals("test fallback", result.plan.fallbackReason)
        assertEquals(1, actionRuntime.planCallCount)
        val routing = result.steps.filterIsInstance<AgentStep.IntentRouted>().single()
        assertEquals(IntentRoutingPath.ActionPlanner, routing.decision.selectedPath)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, routing.decision.selectedToolName)
        assertEquals(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL, routing.decision.selectedSkillId)
        assertEquals(true, routing.decision.accepted)
        assertEquals(true, routing.decision.requiresConfirmation)
        assertEquals(
            listOf(
                AgentStep.ContextLoaded::class,
                AgentStep.IntentRouted::class,
                AgentStep.ModelPlanned::class,
                AgentStep.SkillPlanned::class,
                AgentStep.SafetyChecked::class,
                AgentStep.ToolRequested::class,
                AgentStep.UserConfirmationRequested::class,
            ),
            result.steps.map { it::class },
        )
        assertTrue(result.steps.any { step ->
            step is AgentStep.SkillPlanned &&
                step.request.skillId == BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL
        })
        assertTrue(result.steps.any { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.toolName == MobileActionFunctions.OPEN_WIFI_SETTINGS &&
                step.draft.functionName == MobileActionFunctions.OPEN_WIFI_SETTINGS
        })
    }

    @Test
    fun reduceDeviceActionConfirmationsExecutesLowRiskDeviceNavigationWithoutConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = wifiPlanningResult(),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
            options = AgentRunOptions(reduceDeviceActionConfirmations = true),
        )

        assertEquals(AgentRunState.ExecutingTool, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, result.plan.request.toolName)
        assertEquals(SafetyOutcome.Allow, result.plan.safetyDecision.outcome)
        assertFalse(result.plan.draft.requiresConfirmation)
        assertTrue(result.steps.none { it is AgentStep.UserConfirmationRequested })
    }

    @Test
    fun reduceDeviceActionConfirmationsExecutesLowRiskUiTapWithoutConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = uiTapPlanningResult("搜索"),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "点击搜索",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
            options = AgentRunOptions(reduceDeviceActionConfirmations = true),
        )

        assertEquals(AgentRunState.ExecutingTool, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.UI_TAP, result.plan.request.toolName)
        assertEquals(SafetyOutcome.Allow, result.plan.safetyDecision.outcome)
        assertFalse(result.plan.draft.requiresConfirmation)
    }

    @Test
    fun reduceDeviceActionConfirmationsKeepsHighRiskUiTapConfirmed() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = uiTapPlanningResult("确认支付"),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "点击确认支付",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
            options = AgentRunOptions(reduceDeviceActionConfirmations = true),
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.UI_TAP, result.plan.request.toolName)
        assertEquals(SafetyOutcome.RequireConfirmation, result.plan.safetyDecision.outcome)
        assertTrue(result.steps.any { it is AgentStep.UserConfirmationRequested })
    }

    @Test
    fun reduceDeviceActionConfirmationsKeepsHighRiskUiTapContinuationConfirmed() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = HighRiskContinuationSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val planned = runtime.runOnce(
            input = HighRiskContinuationSkillRuntime.INPUT,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            options = AgentRunOptions(reduceDeviceActionConfirmations = true),
        )

        assertEquals(AgentRunState.ExecutingTool, planned.run.state)
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OBSERVE_CURRENT_SCREEN, planned.plan.request.toolName)
        assertEquals(BuiltInSkillRuntime.CURRENT_PAGE_SIMPLE_INTERACTION_SKILL, planned.plan.skillRequest?.skillId)
        assertFalse(planned.plan.draft.requiresConfirmation)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = observeScreenResult(planned.plan.request),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.AwaitingUserConfirmation, observed.run.state)
        require(observed.decision is AgentObservationDecision.PlanNextTool)
        val nextPlan = observed.decision.plan
        assertEquals(MobileActionFunctions.UI_TAP, nextPlan.request.toolName)
        assertTrue(nextPlan.request.arguments["target"].orEmpty().contains("确认支付"))
        assertTrue(nextPlan.draft.requiresConfirmation)
        assertEquals(SafetyOutcome.RequireConfirmation, nextPlan.safetyDecision.outcome)
        assertEquals(nextPlan.request.id, runtime.latestPendingConfirmation()?.request?.id)
    }

    @Test
    fun reduceDeviceActionConfirmationsCompletesLowRiskExternalLaunchWithoutOutcomePopup() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = wifiPlanningResult(),
        )
        var deviceControlSessionFinishCount = 0
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            deviceControlSessionFinisher = { deviceControlSessionFinishCount += 1 },
        )
        val planned = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
            options = AgentRunOptions(reduceDeviceActionConfirmations = true),
        )
        require(planned.plan is AgentPlan.UseTool)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(
                    toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                    completionVerified = false,
                    externalOutcome = "Unknown",
                    externalOutcomeSource = "Unknown",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Completed, observed.run.state)
        assertEquals(AgentObservationDecision.Complete, observed.decision)
        assertEquals(1, deviceControlSessionFinishCount)
    }

    @Test
    fun reduceDeviceActionConfirmationsKeepsExternalTextDraftsConfirmed() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.COMPOSE_EMAIL,
                        title = "邮件草稿",
                        summary = "将打开邮件 App 并填入草稿内容。",
                        parameters = mapOf("body" to "明天聊"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "帮我写邮件：明天聊",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
            options = AgentRunOptions(reduceDeviceActionConfirmations = true),
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.COMPOSE_EMAIL, result.plan.request.toolName)
        assertEquals(SafetyOutcome.RequireConfirmation, result.plan.safetyDecision.outcome)
        assertTrue(result.steps.any { it is AgentStep.UserConfirmationRequested })
    }

    @Test
    fun reduceDeviceActionConfirmationsKeepsAccessibilitySettingsConfirmed() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.OPEN_SYSTEM_SETTINGS,
                        title = "打开无障碍设置",
                        summary = "将打开无障碍设置，由你手动完成后续操作。",
                        parameters = mapOf("target" to SystemSettingsTargets.ACCESSIBILITY),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "打开无障碍设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
            options = AgentRunOptions(reduceDeviceActionConfirmations = true),
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_SYSTEM_SETTINGS, result.plan.request.toolName)
        assertEquals(SystemSettingsTargets.ACCESSIBILITY, result.plan.request.arguments["target"])
        assertEquals(SafetyOutcome.RequireConfirmation, result.plan.safetyDecision.outcome)
    }

    @Test
    fun reminderActionInputUsesReminderSkillAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.SCHEDULE_REMINDER,
                        title = "后台提醒",
                        summary = "将在 15 分钟后提醒：喝水",
                        parameters = mapOf(
                            "title" to "喝水",
                            "body" to "提醒我 15 分钟后喝水",
                            "delayMinutes" to "15",
                        ),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "提醒我 15 分钟后喝水",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, result.plan.request.toolName)
        assertEquals(BuiltInSkillRuntime.REMINDER_SKILL, result.plan.skillRequest?.skillId)
        assertEquals(SafetyOutcome.RequireConfirmation, result.plan.safetyDecision.outcome)
    }

    @Test
    fun skillFirstReminderBypassesActionPlannerAndRequestsConfirmation() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "提醒我 15 分钟后喝水",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, result.plan.request.toolName)
        assertEquals("15", result.plan.request.arguments["delayMinutes"])
        assertEquals(BuiltInSkillRuntime.REMINDER_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertTrue(result.steps.any { step ->
            step is AgentStep.SkillPlanned &&
                step.plan?.request?.skillId == BuiltInSkillRuntime.REMINDER_SKILL
        })
        assertEquals(
            listOf(ToolAuditEventType.ToolPlanned, ToolAuditEventType.ConfirmationRequested),
            auditSink.events.map { it.eventType },
        )
        assertTrue(auditSink.events.all { event ->
            event.toolName == MobileActionFunctions.SCHEDULE_REMINDER &&
                event.skillId == BuiltInSkillRuntime.REMINDER_SKILL &&
                ToolPermission.SchedulesBackgroundWork in event.permissions &&
                ToolPermission.PostsNotification in event.permissions &&
                ToolPermission.RequiresAndroidRuntimePermission in event.permissions
        })
    }

    @Test
    fun skillFirstEnglishReminderBypassesActionPlannerAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "remind me in 1 hour to check build status",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, result.plan.request.toolName)
        assertEquals("60", result.plan.request.arguments["delayMinutes"])
        assertEquals("check build status", result.plan.request.arguments["title"])
        assertEquals(BuiltInSkillRuntime.REMINDER_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
    }

    @Test
    fun skillFirstCancelReminderBypassesActionPlannerAndRequestsConfirmation() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "取消提醒 task-123",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, result.plan.request.toolName)
        assertEquals("task-123", result.plan.request.arguments["taskId"])
        assertEquals(BuiltInSkillRuntime.REMINDER_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(BuiltInSkillRuntime.REMINDER_SKILL, runtime.latestPendingConfirmation()?.skillId)
        assertEquals(
            listOf(ToolAuditEventType.ToolPlanned, ToolAuditEventType.ConfirmationRequested),
            auditSink.events.map { it.eventType },
        )
        assertTrue(auditSink.events.all { event ->
            event.toolName == MobileActionFunctions.CANCEL_REMINDER &&
                event.skillId == BuiltInSkillRuntime.REMINDER_SKILL &&
                ToolPermission.SchedulesBackgroundWork in event.permissions
        })
    }

    @Test
    fun skillFirstPeriodicCheckBypassesActionPlannerAndRequestsConfirmation() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "开启周期检查，每 2 小时",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.CONFIGURE_PERIODIC_CHECK, result.plan.request.toolName)
        assertEquals("true", result.plan.request.arguments["enabled"])
        assertEquals("120", result.plan.request.arguments["intervalMinutes"])
        assertEquals(BuiltInSkillRuntime.PERIODIC_CHECK_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(BuiltInSkillRuntime.PERIODIC_CHECK_SKILL, runtime.latestPendingConfirmation()?.skillId)
        assertEquals(
            listOf(ToolAuditEventType.ToolPlanned, ToolAuditEventType.ConfirmationRequested),
            auditSink.events.map { it.eventType },
        )
        assertTrue(auditSink.events.all { event ->
            event.toolName == MobileActionFunctions.CONFIGURE_PERIODIC_CHECK &&
                event.skillId == BuiltInSkillRuntime.PERIODIC_CHECK_SKILL &&
                ToolPermission.SchedulesBackgroundWork in event.permissions &&
                ToolPermission.PostsNotification in event.permissions &&
                ToolPermission.RequiresAndroidRuntimePermission in event.permissions
            })
    }

    @Test
    fun skillFirstBackgroundTasksQueryBypassesActionPlannerAndRequestsReadOnlyConfirmation() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "周期检查状态",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.QUERY_BACKGROUND_TASKS, result.plan.request.toolName)
        assertEquals("policy", result.plan.request.arguments["scope"])
        assertEquals(BuiltInSkillRuntime.BACKGROUND_TASKS_CONTEXT_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(BuiltInSkillRuntime.BACKGROUND_TASKS_CONTEXT_SKILL, runtime.latestPendingConfirmation()?.skillId)
        assertEquals(
            listOf(ToolAuditEventType.ToolPlanned, ToolAuditEventType.ConfirmationRequested),
            auditSink.events.map { it.eventType },
        )
        assertTrue(auditSink.events.all { event ->
            event.toolName == MobileActionFunctions.QUERY_BACKGROUND_TASKS &&
                event.skillId == BuiltInSkillRuntime.BACKGROUND_TASKS_CONTEXT_SKILL &&
                event.permissions == setOf(ToolPermission.ReadsDeviceContext)
        })
    }

    @Test
    fun reminderTimingDiscussionFallsBackToAnswerWithoutConfirmation() {
        val input = "提醒我一下，“15 分钟后”是什么意思"
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = MobileActionPlanner().isLikelyAction(input),
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.SCHEDULE_REMINDER,
                        title = "should not be used",
                        summary = "should not be used",
                        parameters = mapOf(
                            "title" to "wrong",
                            "body" to "wrong",
                            "delayMinutes" to "15",
                        ),
                    ),
                ),
                usedModel = true,
                fallbackReason = null,
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = input,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.GeneratingAnswer, result.run.state)
        assertTrue(result.plan is AgentPlan.Answer)
        assertEquals(1, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertTrue(result.steps.none { it is AgentStep.UserConfirmationRequested })
        assertTrue(auditSink.events.isEmpty())
    }

    @Test
    fun genericAppFileAndMediaWordsFallBackToAnswerWithoutActionPlanning() {
        val cases = listOf(
            "帮我写一份文档",
            "这张图片是什么",
            "这个 app 架构怎么设计",
            "文件列表怎么实现",
        )

        cases.forEach { input ->
            val auditSink = InMemoryToolAuditSink()
            val actionRuntime = RecordingActionRuntime(
                likelyAction = MobileActionPlanner().isLikelyAction(input),
                planningResult = ActionPlanningResult(
                    plan = ActionPlan(
                        kind = ActionPlanKind.Draft,
                        draft = ActionDraft(
                            functionName = MobileActionFunctions.QUERY_RECENT_FILES,
                            title = "should not be used",
                            summary = "should not be used",
                            parameters = mapOf("kind" to "documents"),
                        ),
                    ),
                    usedModel = true,
                    fallbackReason = null,
                ),
            )
            val runtime = AgentLoopRuntime(
                memoryIndex = MemoryRepository(),
                actionPlanningRuntime = actionRuntime,
                auditSink = auditSink,
                traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            )

            val result = runtime.runOnce(
                input = input,
                installedCapabilities = setOf(ModelCapability.Chat),
                memoryEnabled = false,
            )

            assertEquals(AgentRunState.GeneratingAnswer, result.run.state)
            assertTrue(result.plan is AgentPlan.Answer)
            assertEquals(1, actionRuntime.isLikelyActionCallCount)
            assertEquals(0, actionRuntime.planCallCount)
            assertTrue(result.steps.none { it is AgentStep.UserConfirmationRequested })
            assertTrue(auditSink.events.isEmpty())
        }
    }

    @Test
    fun skillFirstSequentialCompositeSegmentPlansFirstSkillWhenRulePlannerRejectsFullInput() {
        val input = "总结剪贴板并分享，然后打开 Wi-Fi 设置"
        val planner = MobileActionPlanner()
        val auditSink = InMemoryToolAuditSink()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = object : ActionPlanningRuntime {
                override fun isLikelyAction(input: String): Boolean =
                    planner.isLikelyAction(input)

                override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
                    ActionPlanningResult(
                        plan = planner.plan(input),
                        usedModel = false,
                        fallbackReason = "test rule fallback",
                    )
            },
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = input,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, result.plan.request.toolName)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("打开 Wi-Fi 设置", runtime.latestPendingConfirmation()?.nextActionInput)
        assertEquals(
            listOf(
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ConfirmationRequested,
            ),
            auditSink.events.map { it.eventType },
        )
    }

    @Test
    fun clipboardActionInputUsesClipboardSkillAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_CLIPBOARD,
                        title = "读取剪贴板",
                        summary = "将读取当前剪贴板文本。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "读取剪贴板",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, result.plan.request.toolName)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_CONTEXT_SKILL, result.plan.skillRequest?.skillId)
        assertEquals(SafetyOutcome.RequireConfirmation, result.plan.safetyDecision.outcome)
    }

    @Test
    fun skillFirstClipboardSummaryShareBypassesActionPlannerAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "总结剪贴板并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, result.plan.request.toolName)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, result.plan.skillRequest?.skillId)
        assertEquals(3, result.plan.skillPlan?.steps?.size)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.planCallCount)
        val pending = runtime.latestPendingConfirmation()
        requireNotNull(pending)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, pending.skillId)
        assertEquals(result.plan.skillPlan, pending.skillPlan)
        assertTrue(result.steps.any { step ->
            step is AgentStep.SkillPlanned &&
                step.plan?.request?.skillId == BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL
        })
    }

    @Test
    fun skillFirstClipboardContextBypassesActionPlannerAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "读取剪贴板",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, result.plan.request.toolName)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_CONTEXT_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.planCallCount)
    }

    @Test
    fun skillFirstShareTextBypassesActionPlannerAndRequestsConfirmation() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val shareText = "明天十点开会"

        val result = runtime.runOnce(
            input = "分享这段文字：$shareText",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.SHARE_TEXT, result.plan.request.toolName)
        assertEquals(shareText, result.plan.request.arguments["text"])
        assertEquals(BuiltInSkillRuntime.SHARE_TEXT_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertTrue(auditSink.events.none { event -> event.summary.contains(shareText) })
    }

    @Test
    fun skillFirstDeviceSettingsBypassActionPlannerAndRequestConfirmation() {
        val cases = listOf(
            "打开 Wi-Fi 设置" to MobileActionFunctions.OPEN_WIFI_SETTINGS,
            "打开使用情况访问权限设置" to MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
            "打开手电筒设置" to MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
        )

        cases.forEach { (input, toolName) ->
            val actionRuntime = RecordingActionRuntime(likelyAction = false)
            val runtime = AgentLoopRuntime(
                memoryIndex = MemoryRepository(),
                actionPlanningRuntime = actionRuntime,
                traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            )

            val result = runtime.runOnce(
                input = input,
                installedCapabilities = setOf(ModelCapability.Chat),
                memoryEnabled = false,
            )

            assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
            require(result.plan is AgentPlan.UseTool)
            assertEquals(toolName, result.plan.request.toolName)
            assertTrue(result.plan.request.arguments.isEmpty())
            assertEquals(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL, result.plan.skillRequest?.skillId)
            assertEquals("skill-first", result.plan.fallbackReason)
            assertEquals(0, actionRuntime.isLikelyActionCallCount)
            assertEquals(0, actionRuntime.planCallCount)
            assertEquals(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL, runtime.latestPendingConfirmation()?.skillId)
            assertTrue(result.steps.any { step ->
                step is AgentStep.SkillPlanned &&
                    step.request.skillId == BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL
            })
        }
    }

    @Test
    fun naturalWifiCommandsPlanToolBeforeModelAnswer() {
        listOf("打开WiFi", "打开 WiFi", "打开 Wi-Fi").forEach { input ->
            val runtime = AgentLoopRuntime(
                memoryIndex = MemoryRepository(),
                actionPlanningRuntime = RuleOnlyActionRuntime(),
                traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            )

            val result = runtime.runOnce(
                input = input,
                installedCapabilities = setOf(ModelCapability.Chat),
                memoryEnabled = false,
            )

            assertEquals("$input should request tool confirmation before normal answer generation", AgentRunState.AwaitingUserConfirmation, result.run.state)
            require(result.plan is AgentPlan.UseTool)
            assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, result.plan.request.toolName)
            assertTrue(result.plan.request.arguments.isEmpty())
            assertTrue(result.plan.draft.requiresConfirmation)
        }
    }

    @Test
    fun skillFirstWebSearchBypassesActionPlannerAndExecutesWithoutConfirmation() {
        val cases = listOf(
            "搜一下 Kotlin 协程" to "Kotlin 协程",
            "北京天气怎么样" to "北京天气",
            "look up Kotlin coroutines" to "Kotlin coroutines",
        )

        cases.forEach { (input, query) ->
            val actionRuntime = RecordingActionRuntime(likelyAction = false)
            val runtime = AgentLoopRuntime(
                memoryIndex = MemoryRepository(),
                actionPlanningRuntime = actionRuntime,
                traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            )

            val result = runtime.runOnce(
                input = input,
                installedCapabilities = setOf(ModelCapability.Chat),
                memoryEnabled = false,
            )

            assertEquals(AgentRunState.ExecutingTool, result.run.state)
            require(result.plan is AgentPlan.UseTool)
            assertEquals(MobileActionFunctions.WEB_SEARCH, result.plan.request.toolName)
            assertEquals(query, result.plan.request.arguments["query"])
            assertEquals(BuiltInSkillRuntime.INFORMATION_LOOKUP_SKILL, result.plan.skillRequest?.skillId)
            assertEquals("skill-first", result.plan.fallbackReason)
            assertEquals(0, actionRuntime.isLikelyActionCallCount)
            assertEquals(0, actionRuntime.planCallCount)
            assertEquals(null, runtime.latestPendingConfirmation())
            assertTrue(result.steps.none { it is AgentStep.UserConfirmationRequested })
        }
    }

    @Test
    fun skillFirstRecentMediaFilesBypassesActionPlannerAndRequestsConfirmation() {
        val cases = listOf(
            Triple("最近 3 张图片", "images", "3"),
            Triple("recent screenshots", "screenshots", null),
        )

        cases.forEach { (input, kind, maxCount) ->
            val actionRuntime = RecordingActionRuntime(likelyAction = false)
            val runtime = AgentLoopRuntime(
                memoryIndex = MemoryRepository(),
                actionPlanningRuntime = actionRuntime,
                traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            )

            val result = runtime.runOnce(
                input = input,
                installedCapabilities = setOf(ModelCapability.Chat),
                memoryEnabled = false,
            )

            assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
            require(result.plan is AgentPlan.UseTool)
            assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, result.plan.request.toolName)
            assertEquals(kind, result.plan.request.arguments["kind"])
            if (maxCount == null) {
                assertNull(result.plan.request.arguments["maxCount"])
            } else {
                assertEquals(maxCount, result.plan.request.arguments["maxCount"])
            }
            assertEquals(BuiltInSkillRuntime.RECENT_FILES_CONTEXT_SKILL, result.plan.skillRequest?.skillId)
            assertEquals("skill-first", result.plan.fallbackReason)
            assertEquals(0, actionRuntime.isLikelyActionCallCount)
            assertEquals(0, actionRuntime.planCallCount)
            assertEquals(BuiltInSkillRuntime.RECENT_FILES_CONTEXT_SKILL, runtime.latestPendingConfirmation()?.skillId)
        }
    }

    @Test
    fun skillFirstRecentScreenshotOcrBypassesActionPlannerAndRequestsConfirmation() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "识别最近截图文字",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR, result.plan.request.toolName)
        assertEquals("1", result.plan.request.arguments["maxCount"])
        assertEquals(BuiltInSkillRuntime.RECENT_SCREENSHOT_OCR_CONTEXT_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(BuiltInSkillRuntime.RECENT_SCREENSHOT_OCR_CONTEXT_SKILL, runtime.latestPendingConfirmation()?.skillId)
        assertEquals(
            listOf(ToolAuditEventType.ToolPlanned, ToolAuditEventType.ConfirmationRequested),
            auditSink.events.map { it.eventType },
        )
        assertTrue(auditSink.events.all { event ->
            event.toolName == MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR &&
                event.skillId == BuiltInSkillRuntime.RECENT_SCREENSHOT_OCR_CONTEXT_SKILL &&
                ToolPermission.ReadsFiles in event.permissions &&
                ToolPermission.RequiresAndroidRuntimePermission in event.permissions
        })
    }

    @Test
    fun skillFirstRecentImageOcrBypassesActionPlannerAndRequestsConfirmation() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "识别最近2张照片文字",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.READ_RECENT_IMAGE_OCR, result.plan.request.toolName)
        assertEquals("2", result.plan.request.arguments["maxCount"])
        assertEquals(BuiltInSkillRuntime.RECENT_IMAGE_OCR_CONTEXT_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(BuiltInSkillRuntime.RECENT_IMAGE_OCR_CONTEXT_SKILL, runtime.latestPendingConfirmation()?.skillId)
        assertEquals(
            listOf(ToolAuditEventType.ToolPlanned, ToolAuditEventType.ConfirmationRequested),
            auditSink.events.map { it.eventType },
        )
        assertTrue(auditSink.events.all { event ->
            event.toolName == MobileActionFunctions.READ_RECENT_IMAGE_OCR &&
                event.skillId == BuiltInSkillRuntime.RECENT_IMAGE_OCR_CONTEXT_SKILL &&
                ToolPermission.ReadsFiles in event.permissions &&
                ToolPermission.RequiresAndroidRuntimePermission in event.permissions
        })
    }

    @Test
    fun skillFirstHttpsDeepLinkBypassesActionPlannerAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "打开链接 https://example.com/path?q=agent",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_DEEP_LINK, result.plan.request.toolName)
        assertEquals("https://example.com/path?q=agent", result.plan.request.arguments["uri"])
        assertEquals(BuiltInSkillRuntime.DEEP_LINK_NAVIGATION_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(BuiltInSkillRuntime.DEEP_LINK_NAVIGATION_SKILL, runtime.latestPendingConfirmation()?.skillId)
    }

    @Test
    fun skillFirstAppNavigationBypassesActionPlannerAndRequestsConfirmation() {
        val cases = listOf(
            "启动微信" to MobileActionFunctions.OPEN_APP_BY_NAME,
            "打开微信应用详情设置" to MobileActionFunctions.OPEN_APP_DEEP_TARGET,
            "打开高德地图" to MobileActionFunctions.OPEN_APP_BY_NAME,
            "打开信息" to MobileActionFunctions.OPEN_APP_BY_NAME,
        )

        cases.forEach { (input, toolName) ->
            val actionRuntime = RecordingActionRuntime(likelyAction = false)
            val runtime = AgentLoopRuntime(
                memoryIndex = MemoryRepository(),
                actionPlanningRuntime = actionRuntime,
                traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            )

            val result = runtime.runOnce(
                input = input,
                installedCapabilities = setOf(ModelCapability.Chat),
                memoryEnabled = false,
            )

            assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
            require(result.plan is AgentPlan.UseTool)
            assertEquals(toolName, result.plan.request.toolName)
            if (toolName == MobileActionFunctions.OPEN_APP_BY_NAME) {
                val expectedAppName = when (input) {
                    "打开高德地图" -> "高德地图"
                    "打开信息" -> "信息"
                    else -> "微信"
                }
                assertEquals(expectedAppName, result.plan.request.arguments["appName"])
            } else {
                assertEquals(AppDeepTargets.APP_DETAILS_SETTINGS_ID, result.plan.request.arguments["targetId"])
                assertEquals("com.tencent.mm", result.plan.request.arguments["packageName"])
            }
            assertEquals(BuiltInSkillRuntime.APP_NAVIGATION_SKILL, result.plan.skillRequest?.skillId)
            assertEquals("skill-first", result.plan.fallbackReason)
            assertEquals(0, actionRuntime.isLikelyActionCallCount)
            assertEquals(0, actionRuntime.planCallCount)
            assertEquals(BuiltInSkillRuntime.APP_NAVIGATION_SKILL, runtime.latestPendingConfirmation()?.skillId)
        }
    }

    @Test
    fun skillFirstOpenAppSearchRequestsConfirmationForFirstStep() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "打开淘宝搜索海河牛奶",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_APP_BY_NAME, result.plan.request.toolName)
        assertEquals("淘宝", result.plan.request.arguments["appName"])
        assertEquals(BuiltInSkillRuntime.OPEN_APP_UI_SEARCH_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(BuiltInSkillRuntime.OPEN_APP_UI_SEARCH_SKILL, runtime.latestPendingConfirmation()?.skillId)
    }

    @Test
    fun openAppUiSearchUnverifiedOpenAppByNameContinuesCurrentSkillToWait() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        var replanCallCount = 0
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner {
                replanCallCount += 1
                null
            },
        )
        val planned = runtime.runOnce(
            input = "打开淘宝搜索海河牛奶",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开淘宝",
                data = externalActivityResultData(
                    toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                    completionVerified = false,
                    externalOutcome = "Unknown",
                    externalOutcomeSource = "Unknown",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.ExecutingTool, observed.run.state)
        require(observed.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(MobileActionFunctions.UI_WAIT, observed.decision.plan.request.toolName)
        assertTrue(observed.decision.plan.request.reason.contains("等待淘宝前台界面稳定"))
        assertEquals(BuiltInSkillRuntime.OPEN_APP_UI_SEARCH_SKILL, observed.decision.plan.skillRequest?.skillId)
        assertFalse(observed.decision.plan.draft.requiresConfirmation)
        assertEquals(SafetyOutcome.Allow, observed.decision.plan.safetyDecision.outcome)
        assertEquals(0, replanCallCount)
        assertEquals(null, runtime.latestPendingConfirmation())
    }

    @Test
    fun openAppUiSearchLocalOnlyUiResultsContinueSkillBeforeModelContinuation() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = SequentialActionObservationReplanner(RecordingActionRuntime(likelyAction = false)),
        )
        val planned = runtime.runOnce(
            input = "打开淘宝搜索海河牛奶",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val opened = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = ToolResult(
                    requestId = planned.plan.request.id,
                    status = ToolStatus.Succeeded,
                    summary = "已打开淘宝",
                    data = externalActivityResultData(
                        toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                        completionVerified = false,
                        externalOutcome = "Unknown",
                        externalOutcomeSource = "Unknown",
                    ),
                ),
            ),
        )
        val waitPlan = opened.requireNextTool(MobileActionFunctions.UI_WAIT)

        val observedAfterWait = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = uiActionResult(waitPlan.request, actionType = "wait"),
            ),
        )

        assertEquals(AgentRunState.ExecutingTool, observedAfterWait.run.state)
        val observePlan = observedAfterWait.requireNextTool(MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
        assertFalse(observePlan.draft.requiresConfirmation)
        assertEquals(SafetyOutcome.Allow, observePlan.safetyDecision.outcome)
        assertEquals(null, runtime.latestPendingConfirmation())
    }

    @Test
    fun openAppUiSearchProgressesThroughLowRiskUiStepsUntilVerification() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = SequentialActionObservationReplanner(RecordingActionRuntime(likelyAction = false)),
        )
        val planned = runtime.runOnce(
            input = "打开淘宝搜索海河牛奶",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val opened = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = ToolResult(
                    requestId = planned.plan.request.id,
                    status = ToolStatus.Succeeded,
                    summary = "已打开淘宝",
                    data = externalActivityResultData(
                        toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                        completionVerified = false,
                        externalOutcome = "Unknown",
                        externalOutcomeSource = "Unknown",
                    ),
                ),
            ),
        )

        var plan = opened.requireNextTool(MobileActionFunctions.UI_WAIT)
        plan = runtime.observeToolResult(planned.run.id, uiActionResult(plan.request, actionType = "wait"))
            .requireNextTool(MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
        plan = runtime.observeToolResult(planned.run.id, observeScreenResult(plan.request))
            .requireNextTool(MobileActionFunctions.UI_TAP)
        plan = runtime.observeToolResult(planned.run.id, uiActionResult(plan.request, actionType = "tap", target = "搜索入口"))
            .requireNextTool(MobileActionFunctions.UI_WAIT)
        plan = runtime.observeToolResult(planned.run.id, uiActionResult(plan.request, actionType = "wait"))
            .requireNextTool(MobileActionFunctions.UI_TYPE_TEXT)
        plan = runtime.observeToolResult(planned.run.id, uiActionResult(plan.request, actionType = "type_text", target = "搜索输入框"))
            .requireNextTool(MobileActionFunctions.UI_SUBMIT_SEARCH)
        plan = runtime.observeToolResult(planned.run.id, uiActionResult(plan.request, actionType = "submit_search"))
            .requireNextTool(MobileActionFunctions.UI_WAIT)

        val verified = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = uiActionResult(
                    request = plan.request,
                    actionType = "wait",
                    extraData = mapOf(
                        "searchVerificationStatus" to "verified",
                        "searchVerificationEvidence" to "query_visible_after_change",
                    ),
                ),
            ),
        )

        assertEquals(AgentRunState.Completed, verified.run.state)
        assertEquals(AgentObservationDecision.Complete, verified.decision)
        assertEquals(null, runtime.latestPendingConfirmation())
    }

    @Test
    fun modelDrivenOpenAppSearchBootstrapsThenReplansUiChainUntilVerified() {
        val actionRuntime = QueuedModelActionRuntime(
            listOf(
                ActionDraft(
                    functionName = MobileActionFunctions.UI_TAP,
                    title = "点击搜索入口",
                    summary = "点击淘宝搜索入口。",
                    parameters = mapOf("target" to "search_entry"),
                ),
                ActionDraft(
                    functionName = MobileActionFunctions.UI_TYPE_TEXT,
                    title = "输入搜索词",
                    summary = "输入搜索关键词：耳机。",
                    parameters = mapOf("target" to "搜索输入框", "text" to "耳机"),
                ),
                ActionDraft(
                    functionName = MobileActionFunctions.UI_SUBMIT_SEARCH,
                    title = "提交搜索",
                    summary = "提交搜索。",
                    parameters = emptyMap(),
                ),
                ActionDraft(
                    functionName = MobileActionFunctions.UI_WAIT,
                    title = "等待搜索结果",
                    summary = "等待搜索结果验证。",
                    parameters = mapOf("verifySearchQuery" to "耳机"),
                ),
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = BuiltInSkillRuntime(
                appSearchPlanningModeProvider = { AppSearchPlanningMode.ModelDrivenBootstrap },
            ),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = ModelObservationReplanner(
                actionPlanningRuntime = actionRuntime,
                actionModelPathProvider = { "/verified/mobile-action.litertlm" },
                maxModelReplans = 5,
            ),
        )
        val planned = runtime.runOnce(
            input = "打开淘宝搜索耳机",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            installedCapabilityProfiles = listOf(ModelCatalog.profileForModelId(MOBILE_ACTION_MODEL_ID)),
        )
        assertEquals(AgentRunState.AwaitingUserConfirmation, planned.run.state)
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(BuiltInSkillRuntime.MODEL_DRIVEN_OPEN_APP_UI_SEARCH_SKILL, planned.plan.skillRequest?.skillId)
        assertEquals(MobileActionFunctions.OPEN_APP_BY_NAME, planned.plan.request.toolName)

        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        var plan = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开淘宝",
                data = externalActivityResultData(
                    toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                    completionVerified = false,
                    externalOutcome = "Unknown",
                    externalOutcomeSource = "Unknown",
                ),
            ),
        ).requireNextTool(MobileActionFunctions.UI_WAIT)
        plan = runtime.observeToolResult(
            planned.run.id,
            uiActionResult(plan.request, actionType = "wait"),
        ).requireNextTool(MobileActionFunctions.OBSERVE_CURRENT_SCREEN)

        plan = runtime.observeToolResult(
            planned.run.id,
            observeScreenResult(
                request = plan.request,
                rawScreenObservationJson = searchEntryScreenObservationJson("screen-${plan.request.id}"),
            ),
        ).requireNextTool(MobileActionFunctions.UI_TAP)
        assertTrue(plan.plannedByModel)
        assertEquals(BuiltInSkillRuntime.DEVICE_CONTROL_SKILL, plan.skillRequest?.skillId)
        assertEquals("search_entry", plan.request.arguments["target"])

        plan = runtime.observeToolResult(
            planned.run.id,
            uiActionResult(plan.request, actionType = "tap", target = "search_entry"),
        ).requireNextTool(MobileActionFunctions.UI_TYPE_TEXT)
        assertTrue(plan.plannedByModel)
        assertEquals("耳机", plan.request.arguments["text"])

        plan = runtime.observeToolResult(
            planned.run.id,
            uiActionResult(plan.request, actionType = "type_text", target = "搜索输入框"),
        ).requireNextTool(MobileActionFunctions.UI_SUBMIT_SEARCH)
        assertTrue(plan.plannedByModel)

        plan = runtime.observeToolResult(
            planned.run.id,
            uiActionResult(plan.request, actionType = "submit_search"),
        ).requireNextTool(MobileActionFunctions.UI_WAIT)
        assertTrue(plan.plannedByModel)
        assertEquals("耳机", plan.request.arguments["verifySearchQuery"])

        val verified = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = uiActionResult(
                    request = plan.request,
                    actionType = "wait",
                    extraData = mapOf(
                        "searchVerificationStatus" to "verified",
                        "searchVerificationEvidence" to "query_visible_after_change",
                    ),
                ),
            ),
        )

        assertEquals(AgentRunState.Completed, verified.run.state)
        assertEquals(AgentObservationDecision.Complete, verified.decision)
        assertEquals(4, actionRuntime.plannedInputs.size)
        assertEquals(List(4) { "/verified/mobile-action.litertlm" }, actionRuntime.actionModelPaths)
        assertEquals(null, runtime.latestPendingConfirmation())
    }

    @Test
    fun openAppUiSearchThenBackRequestsCheckpointBeforePressingBack() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner { error("skill progression should not replan") },
        )
        val planned = runtime.runOnce(
            input = "打开淘宝搜索耳机，然后返回",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(AgentRunState.AwaitingUserConfirmation, planned.run.state)
        assertEquals(MobileActionFunctions.OPEN_APP_BY_NAME, planned.plan.request.toolName)
        assertEquals("淘宝", planned.plan.request.arguments["appName"])
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val opened = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = ToolResult(
                    requestId = planned.plan.request.id,
                    status = ToolStatus.Succeeded,
                    summary = "已打开淘宝",
                    data = externalActivityResultData(
                        toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                        completionVerified = false,
                        externalOutcome = "Unknown",
                        externalOutcomeSource = "Unknown",
                    ),
                ),
            ),
        )

        var plan = opened.requireNextTool(MobileActionFunctions.UI_WAIT)
        plan = runtime.observeToolResult(planned.run.id, uiActionResult(plan.request, actionType = "wait"))
            .requireNextTool(MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
        plan = runtime.observeToolResult(planned.run.id, observeScreenResult(plan.request))
            .requireNextTool(MobileActionFunctions.UI_TAP)
        plan = runtime.observeToolResult(planned.run.id, uiActionResult(plan.request, actionType = "tap", target = "搜索入口"))
            .requireNextTool(MobileActionFunctions.UI_WAIT)
        plan = runtime.observeToolResult(planned.run.id, uiActionResult(plan.request, actionType = "wait"))
            .requireNextTool(MobileActionFunctions.UI_TYPE_TEXT)
        assertEquals("耳机", plan.request.arguments["text"])
        plan = runtime.observeToolResult(planned.run.id, uiActionResult(plan.request, actionType = "type_text", target = "搜索输入框"))
            .requireNextTool(MobileActionFunctions.UI_SUBMIT_SEARCH)
        plan = runtime.observeToolResult(planned.run.id, uiActionResult(plan.request, actionType = "submit_search"))
            .requireNextTool(MobileActionFunctions.UI_WAIT)
        assertEquals("耳机", plan.request.arguments["verifySearchQuery"])

        val backCheckpoint = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = uiActionResult(
                    request = plan.request,
                    actionType = "wait",
                    extraData = mapOf(
                        "searchVerificationStatus" to "verified",
                        "searchVerificationEvidence" to "query_visible_after_change",
                    ),
                ),
            ),
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, backCheckpoint.run.state)
        require(backCheckpoint.decision is AgentObservationDecision.PlanNextTool)
        val backPlan = backCheckpoint.decision.plan
        assertEquals(MobileActionFunctions.UI_PRESS_BACK, backPlan.request.toolName)
        assertTrue(backPlan.draft.requiresConfirmation)
        assertEquals(SafetyOutcome.RequireConfirmation, backPlan.safetyDecision.outcome)
        assertEquals(backPlan.request.id, runtime.latestPendingConfirmation()?.request?.id)
    }

    @Test
    fun openAppUiSearchThenBackPressesBackAfterUserConfirmsCheckpoint() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner { error("skill progression should not replan") },
        )
        val planned = runtime.runOnce(
            input = "打开淘宝搜索耳机，然后返回",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val opened = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = ToolResult(
                    requestId = planned.plan.request.id,
                    status = ToolStatus.Succeeded,
                    summary = "已打开淘宝",
                    data = externalActivityResultData(
                        toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                        completionVerified = false,
                        externalOutcome = "Unknown",
                        externalOutcomeSource = "Unknown",
                    ),
                ),
            ),
        )

        var plan = opened.requireNextTool(MobileActionFunctions.UI_WAIT)
        plan = runtime.observeToolResult(planned.run.id, uiActionResult(plan.request, actionType = "wait"))
            .requireNextTool(MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
        plan = runtime.observeToolResult(planned.run.id, observeScreenResult(plan.request))
            .requireNextTool(MobileActionFunctions.UI_TAP)
        plan = runtime.observeToolResult(planned.run.id, uiActionResult(plan.request, actionType = "tap", target = "搜索入口"))
            .requireNextTool(MobileActionFunctions.UI_WAIT)
        plan = runtime.observeToolResult(planned.run.id, uiActionResult(plan.request, actionType = "wait"))
            .requireNextTool(MobileActionFunctions.UI_TYPE_TEXT)
        plan = runtime.observeToolResult(planned.run.id, uiActionResult(plan.request, actionType = "type_text", target = "搜索输入框"))
            .requireNextTool(MobileActionFunctions.UI_SUBMIT_SEARCH)
        plan = runtime.observeToolResult(planned.run.id, uiActionResult(plan.request, actionType = "submit_search"))
            .requireNextTool(MobileActionFunctions.UI_WAIT)
        val backCheckpoint = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = uiActionResult(
                    request = plan.request,
                    actionType = "wait",
                    extraData = mapOf(
                        "searchVerificationStatus" to "verified",
                        "searchVerificationEvidence" to "query_visible_after_change",
                    ),
                ),
            ),
        )
        require(backCheckpoint.decision is AgentObservationDecision.PlanNextTool)
        val backPlan = backCheckpoint.decision.plan
        runtime.confirmToolRequest(planned.run.id, backPlan.request.id)

        val waitAfterBack = runtime.observeToolResult(
            runId = planned.run.id,
            result = uiActionResult(
                request = backPlan.request,
                actionType = "press_back",
            ),
        ).requireNextTool(MobileActionFunctions.UI_WAIT)

        val completed = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = uiActionResult(waitAfterBack.request, actionType = "wait"),
            ),
        )
        assertEquals(AgentRunState.GeneratingAnswer, completed.run.state)
        require(completed.decision is AgentObservationDecision.ContinueWithModel)
    }

    @Test
    fun openAppUiSearchRuntimeFlowCoversCommonAppProfiles() {
        listOf(
            OpenAppUiSearchRuntimeCase(
                input = "打开拼多多搜索纸巾",
                appName = "拼多多",
                query = "纸巾",
                expectedPackageName = "com.xunmeng.pinduoduo",
                resultSummary = "拼多多 纸巾 综合 销量 筛选 百亿补贴",
            ),
            OpenAppUiSearchRuntimeCase(
                input = "打开高德地图搜索机场",
                appName = "高德地图",
                query = "机场",
                expectedPackageName = "com.autonavi.minimap",
                resultSummary = "高德地图 机场 路线 导航 到这去 地点列表",
            ),
            OpenAppUiSearchRuntimeCase(
                input = "打开京东搜索数据线",
                appName = "京东",
                query = "数据线",
                expectedPackageName = "com.jingdong.app.mall",
                resultSummary = "京东 数据线 综合 销量 筛选 京东物流",
            ),
            OpenAppUiSearchRuntimeCase(
                input = "打开浏览器搜索 Kotlin 协程",
                appName = "浏览器",
                query = "Kotlin 协程",
                expectedPackageName = "com.android.chrome",
                resultSummary = "浏览器 Kotlin 协程 搜索结果 网页 相关搜索",
            ),
        ).forEach { searchCase ->
            assertOpenAppUiSearchRuntimeFlow(searchCase)
        }
    }

    @Test
    fun appNavigationUnverifiedOpenAppByNameCompletesWithoutExternalOutcomePopup() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "打开微信",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_APP_BY_NAME, planned.plan.request.toolName)
        assertEquals(BuiltInSkillRuntime.APP_NAVIGATION_SKILL, planned.plan.skillRequest?.skillId)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开微信",
                data = externalActivityResultData(
                    toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                    completionVerified = false,
                    externalOutcome = "Unknown",
                    externalOutcomeSource = "Unknown",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Completed, observed.run.state)
        assertEquals(AgentObservationDecision.Complete, observed.decision)
        assertNull(runtime.latestPendingExternalOutcome())
        assertTrue(observed.steps.filterIsInstance<AgentStep.ToolRequested>().none { step ->
            step.request.toolName == MobileActionFunctions.UI_WAIT ||
                step.request.toolName == MobileActionFunctions.OBSERVE_CURRENT_SCREEN
        })
    }

    @Test
    fun lowRiskAppControlContinuationCheckpointsAfterFiveUiActionsEvenWhenReduced() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = CheckpointingLowRiskTapSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val planned = runtime.runOnce(
            input = CheckpointingLowRiskTapSkillRuntime.INPUT,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            options = AgentRunOptions(reduceDeviceActionConfirmations = true),
        )

        assertEquals(AgentRunState.ExecutingTool, planned.run.state)
        val firstPlan = planned.plan
        require(firstPlan is AgentPlan.UseTool)
        assertEquals("tap_1", firstPlan.request.id)
        assertFalse(firstPlan.draft.requiresConfirmation)

        var plan: AgentPlan.UseTool = firstPlan
        for (index in 2..5) {
            plan = runtime.observeToolResult(
                runId = planned.run.id,
                result = uiActionResult(plan.request, actionType = "tap", target = "搜索${index - 1}"),
            ).requireNextTool(MobileActionFunctions.UI_TAP)
            assertEquals("tap_$index", plan.request.id)
            assertFalse(plan.draft.requiresConfirmation)
        }

        val checkpoint = runtime.observeToolResult(
            runId = planned.run.id,
            result = uiActionResult(plan.request, actionType = "tap", target = "搜索5"),
        )

        requireNotNull(checkpoint)
        assertEquals(AgentRunState.AwaitingUserConfirmation, checkpoint.run.state)
        require(checkpoint.decision is AgentObservationDecision.PlanNextTool)
        val checkpointPlan = checkpoint.decision.plan
        assertEquals("tap_6", checkpointPlan.request.id)
        assertEquals(MobileActionFunctions.UI_TAP, checkpointPlan.request.toolName)
        assertTrue(checkpointPlan.draft.requiresConfirmation)
        assertEquals(SafetyOutcome.RequireConfirmation, checkpointPlan.safetyDecision.outcome)
        assertEquals("tap_6", runtime.latestPendingConfirmation()?.request?.id)
    }

    @Test
    fun completedOpenAppUiSearchDoesNotRestoreInitialLaunchAsPendingExternalOutcome() {
        val dao = FakeAgentTraceDao()
        val traceStore = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { 1_000L },
            runIdFactory = { "run-open-app-ui-search-restored" },
        )
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = traceStore,
        )
        val planned = initialRuntime.runOnce(
            input = "打开淘宝搜索海河牛奶",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            sessionId = "session-open-app-ui-search",
        )
        require(planned.plan is AgentPlan.UseTool)
        initialRuntime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val opened = initialRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开淘宝",
                data = externalActivityResultData(
                    toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                    completionVerified = false,
                    externalOutcome = "Unknown",
                    externalOutcomeSource = "Unknown",
                ),
            ),
        )
        requireNotNull(opened)
        assertEquals(AgentRunState.ExecutingTool, opened.run.state)
        traceStore.updateState(planned.run.id, AgentRunState.Completed)

        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = RoomAgentTraceStore(traceDao = dao),
        )

        assertNull(restoredRuntime.latestPendingExternalOutcome("session-open-app-ui-search"))
    }

    @Test
    fun cameraAliasesPlanToolBeforeModelAnswer() {
        listOf("打开相机", "打开摄像头", "打开摄像机").forEach { input ->
            val runtime = AgentLoopRuntime(
                memoryIndex = MemoryRepository(),
                actionPlanningRuntime = RuleOnlyActionRuntime(),
                traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            )

            val result = runtime.runOnce(
                input = input,
                installedCapabilities = setOf(ModelCapability.Chat),
                memoryEnabled = false,
            )

            assertEquals("$input should request tool confirmation before normal answer generation", AgentRunState.AwaitingUserConfirmation, result.run.state)
            require(result.plan is AgentPlan.UseTool)
            assertCameraUseToolPlan(input, result.plan)
            assertTrue(result.plan.draft.requiresConfirmation)
        }
    }

    @Test
    fun skillFirstForegroundAppBypassesActionPlannerAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "当前应用是什么",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.QUERY_FOREGROUND_APP, result.plan.request.toolName)
        assertTrue(result.plan.request.arguments.isEmpty())
        assertEquals(BuiltInSkillRuntime.FOREGROUND_APP_CONTEXT_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(BuiltInSkillRuntime.FOREGROUND_APP_CONTEXT_SKILL, runtime.latestPendingConfirmation()?.skillId)
    }

    @Test
    fun foregroundAppObservationRedactsAppIdentityFromTrace() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "当前应用是什么",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "当前前台应用：Mail",
                data = mapOf(
                    "toolName" to MobileActionFunctions.QUERY_FOREGROUND_APP,
                    "source" to "usage_stats_estimate",
                    "confidence" to "estimate",
                    "packageName" to "com.example.mail",
                    "appLabel" to "Mail",
                    "lastTimeUsedMillis" to "1234",
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to "true",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals("[redacted]", observed.result.data["packageName"])
        assertEquals("[redacted]", observed.result.data["appLabel"])
        assertEquals("已读取当前前台应用", observed.result.summary)
        assertFalse(observed.steps.toString().contains("Mail"))
        assertFalse(observed.steps.toString().contains("com.example.mail"))
    }

    @Test
    fun skillFirstRecentNotificationsBypassesActionPlannerAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "最近 3 条通知",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS, result.plan.request.toolName)
        assertEquals("3", result.plan.request.arguments["maxCount"])
        assertEquals(BuiltInSkillRuntime.RECENT_NOTIFICATIONS_CONTEXT_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(BuiltInSkillRuntime.RECENT_NOTIFICATIONS_CONTEXT_SKILL, runtime.latestPendingConfirmation()?.skillId)
    }

    @Test
    fun skillFirstCurrentScreenTextBypassesActionPlannerAndRequestsConfirmation() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "总结当前屏幕文字，最多1200字",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, result.plan.request.toolName)
        assertEquals("1200", result.plan.request.arguments["maxChars"])
        assertEquals(BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_CONTEXT_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_CONTEXT_SKILL, runtime.latestPendingConfirmation()?.skillId)
        assertTrue(result.plan.draft.summary.contains("可访问文本快照"))
        assertEquals(
            listOf(ToolAuditEventType.ToolPlanned, ToolAuditEventType.ConfirmationRequested),
            auditSink.events.map { it.eventType },
        )
        assertTrue(auditSink.events.all { event ->
            event.toolName == MobileActionFunctions.READ_CURRENT_SCREEN_TEXT &&
                event.skillId == BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_CONTEXT_SKILL &&
                ToolPermission.ReadsAccessibilityText in event.permissions
        })
    }

    @Test
    fun skillFirstContactLookupBypassesActionPlannerAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "查联系人 Alice",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.QUERY_CONTACTS, result.plan.request.toolName)
        assertEquals("Alice", result.plan.request.arguments["query"])
        assertEquals(BuiltInSkillRuntime.CONTACT_LOOKUP_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(BuiltInSkillRuntime.CONTACT_LOOKUP_SKILL, runtime.latestPendingConfirmation()?.skillId)
    }

    @Test
    fun skillFirstContactDraftBypassesActionPlannerAndRequestsConfirmation() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "新建联系人 Alice",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.CREATE_CONTACT_DRAFT, result.plan.request.toolName)
        assertEquals("Alice", result.plan.request.arguments["name"])
        assertEquals(BuiltInSkillRuntime.CONTACT_DRAFT_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(BuiltInSkillRuntime.CONTACT_DRAFT_SKILL, runtime.latestPendingConfirmation()?.skillId)
        assertEquals(
            listOf(ToolAuditEventType.ToolPlanned, ToolAuditEventType.ConfirmationRequested),
            auditSink.events.map { it.eventType },
        )
        assertTrue(auditSink.events.all { event ->
            event.toolName == MobileActionFunctions.CREATE_CONTACT_DRAFT &&
                event.skillId == BuiltInSkillRuntime.CONTACT_DRAFT_SKILL &&
                ToolPermission.SendsTextToExternalApp in event.permissions &&
                ToolPermission.ReadsContacts !in event.permissions &&
                ToolPermission.RequiresAndroidRuntimePermission !in event.permissions
        })
    }

    @Test
    fun skillFirstCalendarAvailabilityBypassesActionPlannerAndRequestsConfirmation() {
        val input = "查忙闲 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z"
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = input,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY, result.plan.request.toolName)
        assertEquals("2026-06-01T09:00:00Z", result.plan.request.arguments["start"])
        assertEquals("2026-06-01T10:00:00Z", result.plan.request.arguments["end"])
        assertEquals(BuiltInSkillRuntime.CALENDAR_AVAILABILITY_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(BuiltInSkillRuntime.CALENDAR_AVAILABILITY_SKILL, runtime.latestPendingConfirmation()?.skillId)
    }

    @Test
    fun contactObservationRedactsPrivateTraceFields() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "查联系人 Alice",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val rawContactsJson = """[{"name":"Alice","phone":"+1 555 0100"}]"""
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已查询到 1 个联系人。",
                data = mapOf(
                    "toolName" to MobileActionFunctions.QUERY_CONTACTS,
                    "query" to "Alice",
                    "maxCount" to "5",
                    "contactCount" to "1",
                    "contactsJson" to rawContactsJson,
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to "true",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationRequiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains(rawContactsJson))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("查联系人 Alice"))
        assertEquals("[redacted]", observed.result.data["query"])
        assertEquals("[redacted]", observed.result.data["contactsJson"])
        assertEquals("已读取联系人摘要", observed.result.summary)
        val toolObserved = observed.steps.filterIsInstance<AgentStep.ToolObserved>().last()
        assertEquals("[redacted]", toolObserved.result.data["contactsJson"])
        assertFalse(observed.steps.toString().contains("+1 555 0100"))
    }

    @Test
    fun backgroundTasksObservationRedactsTaskAndPolicyJson() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "查看后台任务",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val rawTasksJson = """[{"id":"task-1","title":"Doctor appointment","status":"Scheduled"}]"""
        val rawPolicyJson = """{"enabled":true,"intervalMinutes":120}"""
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取 1 个活动后台任务。",
                data = mapOf(
                    "toolName" to MobileActionFunctions.QUERY_BACKGROUND_TASKS,
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to "true",
                    "scope" to "active",
                    "source" to "local_store",
                    "maxCount" to "20",
                    "activeTaskCount" to "1",
                    "tasksJson" to rawTasksJson,
                    "policyJson" to rawPolicyJson,
                    "metadataPolicy" to "background_tasks_local_only_no_reminder_body",
                    "rawPayloadIncluded" to "false",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationRequiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains(rawTasksJson))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains(rawPolicyJson))
        assertEquals("[redacted]", observed.result.data["activeTaskCount"])
        assertEquals("[redacted]", observed.result.data["tasksJson"])
        assertEquals("[redacted]", observed.result.data["policyJson"])
        assertEquals("已读取后台任务摘要", observed.result.summary)
        val toolObserved = observed.steps.filterIsInstance<AgentStep.ToolObserved>().last()
        assertEquals("[redacted]", toolObserved.result.data["tasksJson"])
        assertFalse(observed.steps.toString().contains("Doctor appointment"))
        assertFalse(observed.steps.toString().contains("intervalMinutes"))
    }

    @Test
    fun skillFirstMapEmailAndCalendarBypassActionPlannerAndRequestConfirmation() {
        val cases = listOf(
            SkillFirstDraftCase(
                input = "查去机场的路线",
                toolName = MobileActionFunctions.SEARCH_MAPS,
                skillId = BuiltInSkillRuntime.MAP_SEARCH_SKILL,
                argumentName = "query",
                argumentValue = "机场的路线",
            ),
            SkillFirstDraftCase(
                input = "帮我写邮件：明天延期到周五",
                toolName = MobileActionFunctions.COMPOSE_EMAIL,
                skillId = BuiltInSkillRuntime.EMAIL_DRAFT_SKILL,
                argumentName = "body",
                argumentValue = "明天延期到周五",
            ),
            SkillFirstDraftCase(
                input = "帮我建个日程：周五评审",
                toolName = MobileActionFunctions.CREATE_CALENDAR_EVENT,
                skillId = BuiltInSkillRuntime.CALENDAR_DRAFT_SKILL,
                argumentName = "title",
                argumentValue = "周五评审",
            ),
        )

        cases.forEach { testCase ->
            val auditSink = InMemoryToolAuditSink()
            val actionRuntime = RecordingActionRuntime(likelyAction = false)
            val runtime = AgentLoopRuntime(
                memoryIndex = MemoryRepository(),
                actionPlanningRuntime = actionRuntime,
                auditSink = auditSink,
                traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            )

            val result = runtime.runOnce(
                input = testCase.input,
                installedCapabilities = setOf(ModelCapability.Chat),
                memoryEnabled = false,
            )

            assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
            require(result.plan is AgentPlan.UseTool)
            assertEquals(testCase.toolName, result.plan.request.toolName)
            assertEquals(testCase.argumentValue, result.plan.request.arguments[testCase.argumentName])
            assertEquals(testCase.skillId, result.plan.skillRequest?.skillId)
            assertEquals("skill-first", result.plan.fallbackReason)
            assertEquals(0, actionRuntime.isLikelyActionCallCount)
            assertEquals(0, actionRuntime.planCallCount)
            if (testCase.toolName != MobileActionFunctions.SEARCH_MAPS) {
                assertTrue(auditSink.events.none { event -> event.summary.contains(testCase.argumentValue) })
            }
        }
    }

    @Test
    fun parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit() {
        val inputs = listOf(
            "查到这个错误原因了吗？",
            "How do I navigate to a Compose screen?",
            "不要发邮件，只帮我总结这段话",
            "Do not send email; summarize only",
            "add event listener to the button",
            "Wi-Fi 设置页面怎么设计",
            "不要打开 Wi-Fi 设置，只解释一下",
            "Do not open Wi-Fi settings; explain only",
            "不打开 Wi-Fi 设置",
            "请勿打开 Wi-Fi 设置",
            "不要设置 Wi-Fi",
            "手电筒 API 怎么用",
            "打开手电筒",
            "Usage Access API 怎么用",
            "解释一下 PACKAGE_USAGE_STATS",
            "Android 使用情况访问权限怎么实现",
            "不要打开使用情况访问权限设置",
            "do not open usage access settings",
            "网页搜索是什么",
            "不要搜索 Kotlin，只解释一下",
            "what is web search",
            "查一下这个错误原因了吗？",
            "提醒我 10 分钟后喝水，然后提醒我 20 分钟后运动",
            "识别最近4张图片文字",
            "识别最近四张照片文字",
            "read text from recent 4 photos",
            "读取所有图片 OCR",
            "不要识别最近图片文字",
            "图片 OCR API",
            "描述最近图片",
            "图片里有什么",
            "识别最近2张截图文字",
            "识别最近两张截图文字",
            "read text from recent 2 screenshots",
            "读取所有截图 OCR",
            "不要识别最近截图文字",
            "截图 OCR 怎么实现",
            "screenshot OCR API",
            "查询最近5个文档",
            "不要查询文件列表",
            "文件列表怎么实现",
            "查询文件 API",
            "最近文件",
            "https://example.com/path",
            "解释 https://example.com/path 是什么",
            "how do I open https://example.com/path",
            "不要打开 https://example.com/path",
            "打开链接 http://example.com/path",
            "打开应用",
            "启动 app",
            "打开应用详情设置",
            "打开应用信息",
            "不要打开微信",
            "别启动微信",
            "不启动微信",
            "请勿打开微信",
            "如何打开微信",
            "怎么打开微信",
            "打开微信小程序",
            "打开微信支付收款码",
            "打开微信应用设置",
            "open WeChat app settings",
            "open source WeChat SDK",
            "start using WeChat",
            "微信打不开怎么办",
            "微信打开方式",
            "打开微信扫一扫",
            "打开微信聊天",
            "打开微信朋友圈",
            "打开微信权限页",
            "打开微信通知设置",
            "打开 package:com.example.app",
            "打开 intent://scan/#Intent;scheme=zxing;end",
            "打开 file:///sdcard/private.txt",
            "打开 com.example.app/.MainActivity 应用详情设置",
            "打开微信应用详情设置然后发消息",
            "前台服务限制是什么",
            "current app architecture",
            "how do I implement current app state",
            "不要查询当前应用",
            "当前应用权限怎么申请",
            "前台应用 API 怎么用",
            "don't tell me the current app",
            "notification",
            "notifications",
            "recent app notifications",
            "notification permission",
            "notification channel",
            "push notification",
            "系统通知",
            "通知栏",
            "不要读取最近通知",
            "current app notifications API",
            "don't read current app notifications",
            "不要读取当前屏幕文字",
            "总结这页内容",
            "总结页面内容",
            "what is current screen text",
            "current screen text API",
            "how to implement current screen state",
            "联系人",
            "contact",
            "查询联系人",
            "联系人权限",
            "ContactsContract 怎么用",
            "search contacts API",
            "不要查联系人 Alice",
            "do not search contacts for Alice",
            "新建联系人",
            "不要新建联系人 Alice",
            "编辑联系人 Alice",
            "删除联系人 Alice",
            "导出联系人",
            "查一下忙闲",
            "明天我有空吗",
            "日历权限怎么申请",
            "不要查忙闲 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z",
            "取消提醒",
            "不要取消提醒 task-123",
            "取消提醒 API task-123",
            "取消日程 task-123",
            "what is free/busy",
            "API availability 2026-06-01T09:00:00Z to 2026-06-01T10:00:00Z",
            "calendar availability API",
            "free/busy schema 2026-06-01T09:00:00Z to 2026-06-01T10:00:00Z",
            "how to implement free/busy",
            "查忙闲 2026-06-01T10:00:00Z 到 2026-06-01T09:00:00Z",
            "查忙闲 2026-06-01T09:00:00Z 到 2026-07-10T09:00:00Z",
        )

        inputs.forEach { input ->
            val auditSink = InMemoryToolAuditSink()
            val actionRuntime = RecordingActionRuntime(likelyAction = false)
            val runtime = AgentLoopRuntime(
                memoryIndex = MemoryRepository(),
                actionPlanningRuntime = actionRuntime,
                auditSink = auditSink,
                traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            )

            val result = runtime.runOnce(
                input = input,
                installedCapabilities = setOf(ModelCapability.Chat),
                memoryEnabled = false,
            )

            assertEquals(AgentRunState.GeneratingAnswer, result.run.state)
            assertTrue(result.plan is AgentPlan.Answer)
            assertEquals(null, runtime.latestPendingConfirmation())
            assertTrue(auditSink.events.isEmpty())
        }
    }

    @Test
    fun skillFirstPlanStillUsesRegistryAndRejectsInvalidToolArguments() {
        val invalidSkillRuntime = object : SkillRuntime {
            private val manifest = SkillManifest(
                id = "invalid_email_skill",
                version = 1,
                title = "Invalid email",
                description = "Invalid direct skill for test",
                triggerExamples = listOf("invalid email"),
                requiredTools = listOf(MobileActionFunctions.COMPOSE_EMAIL),
                inputSchemaJson = """{"type":"object","additionalProperties":false}""",
                riskLevel = RiskLevel.MediumDraftOrNavigation,
            )

            override fun manifests(): List<SkillManifest> = listOf(manifest)

            override fun plan(input: String): SkillPlan =
                SkillPlan(
                    request = SkillRequest(
                        id = "invalid-skill-request",
                        skillId = manifest.id,
                        arguments = emptyMap(),
                        reason = input,
                    ),
                    manifest = manifest,
                    steps = listOf(
                        SkillStep.ToolStep(
                            id = "compose_email",
                            request = ToolRequest(
                                toolName = MobileActionFunctions.COMPOSE_EMAIL,
                                arguments = emptyMap(),
                                reason = "invalid direct skill",
                            ),
                            draft = ActionDraft(
                                functionName = MobileActionFunctions.COMPOSE_EMAIL,
                                title = "邮件草稿",
                                summary = "invalid direct skill",
                                parameters = emptyMap(),
                            ),
                        ),
                    ),
                )

            override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null
        }
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = invalidSkillRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "invalid email",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.Failed, result.run.state)
        require(result.plan is AgentPlan.RejectedTool)
        assertEquals(ToolStatus.Rejected, result.plan.result.status)
        assertTrue(result.plan.result.summary.contains("Invalid skill plan"))
        assertTrue(result.plan.result.summary.contains("required tool argument(s): body"))
        assertEquals(null, runtime.latestPendingConfirmation())
    }

    @Test
    fun skillFirstPlanMustMatchCurrentRuntimeManifestContract() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = ManifestTransformingSkillRuntime(ToolToToolSkillRuntime()) { manifest ->
                if (manifest.id == ToolToToolSkillRuntime.REMINDER_SKILL_ID) {
                    manifest.copy(riskLevel = RiskLevel.HighExternalSend)
                } else {
                    manifest
                }
            },
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = ToolToToolSkillRuntime.REMINDER_INPUT,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.Failed, result.run.state)
        require(result.plan is AgentPlan.RejectedTool)
        assertTrue(result.plan.result.summary.contains("Skill manifest changed"))
        assertEquals(null, runtime.latestPendingConfirmation())
    }

    @Test
    fun clipboardObservationBuildsContinuationPromptAndRedactsTrace() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_CLIPBOARD,
                        title = "读取剪贴板",
                        summary = "将读取当前剪贴板文本。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val rawClipboardText = "这是一段剪贴板文本"
        val planned = runtime.runOnce(
            input = "读取剪贴板并总结",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本（12 字符）",
                data = clipboardResultData(rawClipboardText),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.steps.any { step ->
            step is AgentStep.ObservationDecided &&
                step.decision is AgentObservationDecision.ContinueWithModel
        })
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("这是一段剪贴板文本"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("读取剪贴板并总结"))
        assertEquals("[redacted]", observed.result.data["text"])
        val toolObserved = observed.steps.filterIsInstance<AgentStep.ToolObserved>().last()
        assertEquals("[redacted]", toolObserved.result.data["text"])
        assertTrue(!observed.steps.toString().contains(rawClipboardText))
        assertTrue(!auditSink.events.toString().contains(rawClipboardText))
    }

    @Test
    fun malformedSucceededToolResultFailsBeforeContinuationAndDoesNotLeakPayload() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = readClipboardPlanningResult(),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner {
                error("invalid tool results must not reach replanning")
            },
        )
        val rawPayload = "malformed clipboard payload must not leak"
        val planned = runtime.runOnce(
            input = "读取剪贴板并总结",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "malformed success summary $rawPayload",
                data = mapOf(
                    "toolName" to MobileActionFunctions.READ_CLIPBOARD,
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to "true",
                    "text" to rawPayload,
                    "truncated" to "false",
                    "unexpected" to rawPayload,
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        require(observed.decision is AgentObservationDecision.Fail)
        assertTrue(observed.decision.reason.contains("returned invalid result"))
        assertEquals(ToolErrorCode.InvalidResult, observed.result.error?.code)
        assertNull(observed.continuationPromptForModel)
        assertFalse(observed.steps.any { step ->
            step is AgentStep.ObservationDecided &&
                step.decision is AgentObservationDecision.ContinueWithModel
        })
        assertFalse(observed.steps.toString().contains(rawPayload))
        assertFalse(auditSink.events.toString().contains(rawPayload))
    }

    @Test
    fun recentScreenshotOcrObservationBuildsLocalPromptAndRedactsTrace() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                        title = "读取最近截图 OCR",
                        summary = "将读取最近 1 张截图的像素并在本地提取 OCR 文本。",
                        parameters = mapOf("maxCount" to "1"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val rawOcrText = "截图里的私密验证码 123456"
        val rawImageName = "Screenshot-private-123456.png"
        val planned = runtime.runOnce(
            input = "识别最近1张截图文字并总结",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已从最近截图提取 14 个字符的本地 OCR 摘录。",
                data = recentImageOcrResultData(
                    toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                    text = rawOcrText,
                    source = "recent_screenshot",
                    maxCount = "1",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains(rawOcrText))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("不是当前屏幕捕获"))
        assertEquals("[redacted]", observed.result.data["ocrText"])
        assertFalse(observed.result.data.containsKey("name"))
        assertFalse(observed.result.data.containsKey("sizeBytes"))
        assertFalse(observed.result.data.containsKey("lastModifiedMillis"))
        val toolObserved = observed.steps.filterIsInstance<AgentStep.ToolObserved>().last()
        assertEquals("[redacted]", toolObserved.result.data["ocrText"])
        assertFalse(toolObserved.result.data.containsKey("name"))
        assertTrue(!observed.steps.toString().contains(rawOcrText))
        assertTrue(!observed.steps.toString().contains(rawImageName))
        assertTrue(!auditSink.events.toString().contains(rawOcrText))
        assertTrue(!auditSink.events.toString().contains(rawImageName))
    }

    @Test
    fun currentScreenshotOcrObservationIncludesFusedScreenObservationInLocalPromptOnly() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                        title = "当前屏幕截图 OCR",
                        summary = "单次截取当前屏幕并在本地提取 OCR 文本。",
                        parameters = mapOf("captureMode" to "current_screen"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val rawOcrText = "当前屏幕 OCR 里的搜索框"
        val rawObservationJson =
            """{"schemaVersion":1,"sources":["accessibility","ocr"],"elements":[{"id":"n0","text":"搜索"}]}"""
        val planned = runtime.runOnce(
            input = "识别当前屏幕截图文字并判断哪里能搜索",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取当前屏幕截图 OCR 摘录",
                data = currentScreenshotOcrResultData(
                    text = rawOcrText,
                    screenObservationJson = rawObservationJson,
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        val prompt = observed.continuationPromptForModel.orEmpty()
        assertTrue(prompt.contains(rawOcrText))
        assertTrue(prompt.contains(rawObservationJson))
        assertTrue(prompt.contains("LocalOnly"))
        assertTrue(prompt.contains("Accessibility/OCR"))
        assertTrue(prompt.contains("不推断视觉语义"))
        assertEquals("[redacted]", observed.result.data["ocrText"])
        assertEquals("[redacted]", observed.result.data["screenObservationJson"])
        val toolObserved = observed.steps.filterIsInstance<AgentStep.ToolObserved>().last()
        assertEquals("[redacted]", toolObserved.result.data["ocrText"])
        assertEquals("[redacted]", toolObserved.result.data["screenObservationJson"])
        assertFalse(observed.steps.toString().contains(rawOcrText))
        assertFalse(observed.steps.toString().contains(rawObservationJson))
        assertFalse(auditSink.events.toString().contains(rawOcrText))
        assertFalse(auditSink.events.toString().contains(rawObservationJson))
    }

    @Test
    fun recentImageOcrObservationBuildsLocalPromptAndRedactsTrace() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
                        title = "读取最近图片 OCR",
                        summary = "将扫描最近图片并在本地提取 OCR 文本。",
                        parameters = mapOf("maxCount" to "3"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val rawOcrText = "照片里的私密订单号 ABC123"
        val rawImageName = "IMG-private-order-ABC123.jpg"
        val planned = runtime.runOnce(
            input = "识别最近图片文字并总结",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已从最近图片提取 18 个字符的本地 OCR 摘录。",
                data = recentImageOcrResultData(
                    toolName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
                    text = rawOcrText,
                    source = "recent_image",
                    maxCount = "3",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains(rawOcrText))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("不是当前屏幕捕获"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("最近图片 OCR 文本"))
        assertEquals("[redacted]", observed.result.data["ocrText"])
        assertFalse(observed.result.data.containsKey("name"))
        assertFalse(observed.result.data.containsKey("mimeType"))
        assertFalse(observed.result.data.containsKey("sizeBytes"))
        assertFalse(observed.result.data.containsKey("lastModifiedMillis"))
        val toolObserved = observed.steps.filterIsInstance<AgentStep.ToolObserved>().last()
        assertEquals("[redacted]", toolObserved.result.data["ocrText"])
        assertFalse(toolObserved.result.data.containsKey("name"))
        assertTrue(!observed.steps.toString().contains(rawOcrText))
        assertTrue(!observed.steps.toString().contains(rawImageName))
        assertTrue(!auditSink.events.toString().contains(rawOcrText))
        assertTrue(!auditSink.events.toString().contains(rawImageName))
    }

    @Test
    fun currentScreenTextObservationBuildsLocalPromptAndRedactsTrace() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                        title = "读取当前屏幕文本",
                        summary = "将读取当前屏幕的可访问文本快照。",
                        parameters = mapOf("maxChars" to "1200"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val rawScreenText = "当前屏幕里的私密验证码 654321"
        val planned = runtime.runOnce(
            input = "总结当前屏幕文字",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取当前屏幕 17 个字符的可访问文本快照。",
                data = currentScreenTextResultData(rawScreenText),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains(rawScreenText))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("Accessibility"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("不是截图捕获"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("不是视觉/VLM 或语义屏幕理解"))
        assertEquals("[redacted]", observed.result.data["screenText"])
        val toolObserved = observed.steps.filterIsInstance<AgentStep.ToolObserved>().last()
        assertEquals("[redacted]", toolObserved.result.data["screenText"])
        assertTrue(!observed.steps.toString().contains(rawScreenText))
        assertTrue(!auditSink.events.toString().contains(rawScreenText))
    }

    @Test
    fun clipboardSummarySharePlansShareAfterLocalModelResult() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_CLIPBOARD,
                        title = "读取剪贴板",
                        summary = "将读取当前剪贴板文本。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val rawClipboardText = "只应出现在 continuation prompt 的剪贴板原文"
        val modelSummary = "摘要：这段内容适合分享。"
        val planned = runtime.runOnce(
            input = "总结剪贴板并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, planned.plan.skillRequest?.skillId)
        assertTrue(planned.plan.skillPlan?.steps?.size == 3)
        assertTrue(planned.steps.any { step ->
            step is AgentStep.SkillPlanned &&
                step.plan?.request?.skillId == BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL
        })
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = clipboardResultData(rawClipboardText),
            ),
        )
        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("摘要剪贴板内容"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("适合分享的简短摘要"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains(rawClipboardText))

        val modelObserved = runtime.observeModelResult(planned.run.id, modelSummary)

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.AwaitingUserConfirmation, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        val sharePlan = modelObserved.decision.plan
        assertEquals(MobileActionFunctions.SHARE_TEXT, sharePlan.request.toolName)
        assertEquals(modelSummary, sharePlan.request.arguments["text"])
        assertEquals(modelSummary, sharePlan.draft.parameters["text"])
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, sharePlan.skillRequest?.skillId)
        assertEquals(
            listOf(MobileActionFunctions.READ_CLIPBOARD, MobileActionFunctions.SHARE_TEXT),
            modelObserved.steps.filterIsInstance<AgentStep.ToolRequested>().map { it.request.toolName },
        )
        assertTrue(modelObserved.steps.toString().contains(modelSummary))
        assertTrue(!modelObserved.steps.toString().contains(rawClipboardText))
        assertTrue(!auditSink.events.toString().contains(rawClipboardText))
        assertTrue(!auditSink.events.toString().contains(modelSummary))

        runtime.confirmToolRequest(planned.run.id, sharePlan.request.id)
        val completed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = sharePlan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开系统分享面板",
                data = externalActivityResultData(MobileActionFunctions.SHARE_TEXT),
            ),
        )

        requireNotNull(completed)
        assertEquals(AgentRunState.Completed, completed.run.state)
        assertEquals(AgentObservationDecision.Complete, completed.decision)
    }

    @Test
    fun currentScreenTextSummarySharePlansShareAfterLocalModelResult() {
        val auditSink = InMemoryToolAuditSink()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val rawScreenText = "当前屏幕里的私密验证码 654321"
        val modelSummary = "屏幕摘要：验证码仅用于本地确认。"
        val planned = runtime.runOnce(
            input = "总结当前屏幕文字并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL, planned.plan.skillRequest?.skillId)
        assertTrue(planned.plan.skillPlan?.steps?.size == 3)
        assertTrue(planned.steps.any { step ->
            step is AgentStep.SkillPlanned &&
                step.plan?.request?.skillId == BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL
        })
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取当前屏幕可访问文本快照",
                data = currentScreenTextResultData(rawScreenText),
            ),
        )
        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("摘要当前屏幕文本"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("适合分享的简短摘要"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains(rawScreenText))
        assertFalse(observed.continuationPromptForModel.orEmpty().contains("不是截图捕获"))
        assertEquals("[redacted]", observed.result.data["screenText"])
        assertTrue(!observed.steps.toString().contains(rawScreenText))

        val modelObserved = runtime.observeModelResult(planned.run.id, modelSummary)

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.AwaitingUserConfirmation, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        val sharePlan = modelObserved.decision.plan
        assertEquals(MobileActionFunctions.SHARE_TEXT, sharePlan.request.toolName)
        assertEquals(modelSummary, sharePlan.request.arguments["text"])
        assertEquals(modelSummary, sharePlan.draft.parameters["text"])
        assertEquals(BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL, sharePlan.skillRequest?.skillId)
        assertEquals(
            listOf(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, MobileActionFunctions.SHARE_TEXT),
            modelObserved.steps.filterIsInstance<AgentStep.ToolRequested>().map { it.request.toolName },
        )
        assertTrue(modelObserved.steps.toString().contains(modelSummary))
        assertTrue(!modelObserved.steps.toString().contains(rawScreenText))
        assertTrue(!auditSink.events.toString().contains(rawScreenText))
        assertTrue(!auditSink.events.toString().contains(modelSummary))
    }

    @Test
    fun modelStepOutputBindsToDependentToolStepAndRequestsConfirmation() {
        val customSkillRuntime = CustomClipboardTransformSkillRuntime()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = customSkillRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val modelText = "自定义 Skill 生成的可分享文本"
        val planned = runtime.runOnce(
            input = "custom clipboard transform",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(CustomClipboardTransformSkillRuntime.SKILL_ID, planned.plan.skillRequest?.skillId)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, planned.plan.request.toolName)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = clipboardResultData("不应持久化的剪贴板原文"),
            ),
        )
        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("转换剪贴板内容"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("转换用户确认读取的剪贴板内容"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("不应持久化的剪贴板原文"))

        val modelObserved = runtime.observeModelResult(planned.run.id, modelText)

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.AwaitingUserConfirmation, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        val sharePlan = modelObserved.decision.plan
        assertEquals(MobileActionFunctions.SHARE_TEXT, sharePlan.request.toolName)
        assertEquals(modelText, sharePlan.request.arguments["text"])
        assertEquals(modelText, sharePlan.draft.parameters["text"])
        assertEquals(CustomClipboardTransformSkillRuntime.SKILL_ID, sharePlan.skillRequest?.skillId)
        assertEquals("skill model step", sharePlan.fallbackReason)
        assertTrue(modelObserved.steps.any { step ->
            step is AgentStep.SafetyChecked &&
                step.decision.outcome == SafetyOutcome.RequireConfirmation
        })
        assertTrue(modelObserved.steps.any { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.id == sharePlan.request.id
        })
    }

    @Test
    fun toolStepOutputBindsToDependentToolStepInCurrentProcessAndRequestsConfirmation() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = ToolToToolSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = ToolToToolSkillRuntime.REMINDER_INPUT,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(ToolToToolSkillRuntime.REMINDER_SKILL_ID, planned.plan.skillRequest?.skillId)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, planned.plan.request.toolName)

        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已安排 10000 触发的后台提醒",
                data = scheduleReminderResultData(taskId = "task-1", triggerAtMillis = "10000"),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.AwaitingUserConfirmation, observed.run.state)
        require(observed.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(null, observed.continuationPromptForModel)
        val cancelPlan = observed.decision.plan
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, cancelPlan.request.toolName)
        assertEquals(mapOf("taskId" to "task-1"), cancelPlan.request.arguments)
        assertEquals(mapOf("taskId" to "task-1"), cancelPlan.draft.parameters)
        assertEquals(ToolToToolSkillRuntime.REMINDER_SKILL_ID, cancelPlan.skillRequest?.skillId)
        assertEquals("skill tool step", cancelPlan.fallbackReason)
        assertEquals(cancelPlan.request.id, runtime.latestPendingConfirmation()?.request?.id)
        assertEquals(
            listOf(MobileActionFunctions.SCHEDULE_REMINDER, MobileActionFunctions.CANCEL_REMINDER),
            observed.steps.filterIsInstance<AgentStep.ToolRequested>().map { it.request.toolName },
        )

        runtime.confirmToolRequest(planned.run.id, cancelPlan.request.id)
        val completed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = cancelPlan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已取消后台任务：task-1",
                data = cancelReminderResultData(taskId = "task-1"),
            ),
        )

        requireNotNull(completed)
        assertEquals(AgentRunState.Completed, completed.run.state)
        assertEquals(AgentObservationDecision.Complete, completed.decision)
    }

    @Test
    fun toolStepToToolStepBindingCannotDirectlyExposePrivateToolOutputToShare() {
        val auditSink = InMemoryToolAuditSink()
        var replanCallCount = 0
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = ToolToToolSkillRuntime(),
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner {
                replanCallCount += 1
                error("unsafe skill progression must fail before replanning")
            },
        )
        val planned = runtime.runOnce(
            input = ToolToToolSkillRuntime.UNSAFE_CONTACTS_INPUT,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        assertEquals(AgentRunState.Failed, planned.run.state)
        require(planned.plan is AgentPlan.RejectedTool)
        assertTrue(planned.plan.result.summary.contains("private tool output cannot be bound directly"))
        assertTrue(planned.plan.result.summary.contains("contacts.contactsJson"))
        assertTrue(planned.steps.any { it is AgentStep.ToolRejected })
        assertTrue(planned.steps.none { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.toolName == MobileActionFunctions.SHARE_TEXT
        })
        assertNull(runtime.latestPendingConfirmation())
        assertEquals(0, replanCallCount)
        assertTrue(!planned.steps.toString().contains("+1 555 0100"))
        assertTrue(!auditSink.events.toString().contains("+1 555 0100"))
    }

    @Test
    fun ocrSkillModelStepTakesPrecedenceOverPrivateReadFallbackPrompt() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = OcrModelSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val rawOcrText = "截图 OCR 里的本地待办"
        val planned = runtime.runOnce(
            input = "ocr model skill",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR, planned.plan.request.toolName)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取最近截图 OCR 摘录",
                data = recentImageOcrResultData(
                    toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                    text = rawOcrText,
                    source = "recent_screenshot",
                    maxCount = "1",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationRequiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("整理截图 OCR"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("只保留 OCR 里的待办线索"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains(rawOcrText))
        assertFalse(observed.continuationPromptForModel.orEmpty().contains("不是当前屏幕捕获"))
        assertEquals("[redacted]", observed.result.data["ocrText"])
    }

    @Test
    fun localOnlyToolResultMetadataForcesGenericModelContinuationLocal() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = LocalOnlyResultSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "local only contacts skill",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.QUERY_CONTACTS, planned.plan.request.toolName)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "联系人 Alice：仅本地摘要",
                data = contactsResultData(),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationRequiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("整理联系人本地结果"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("联系人 Alice：仅本地摘要"))
    }

    @Test
    fun modelStepBindingRejectsMissingOutputBeforeConfirmation() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = CustomClipboardTransformSkillRuntime(shareBinding = "custom_model.missingOutput"),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "custom clipboard transform",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        assertEquals(AgentRunState.Failed, planned.run.state)
        require(planned.plan is AgentPlan.RejectedTool)
        assertTrue(planned.plan.result.summary.contains("Invalid skill plan"))
        assertTrue(planned.plan.result.summary.contains("custom_model.missingOutput"))
        assertTrue(planned.steps.any { it is AgentStep.ToolRejected })
        assertTrue(planned.steps.none { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.toolName == MobileActionFunctions.SHARE_TEXT
        })
    }

    @Test
    fun modelStepBindingRejectsUnmetDependenciesBeforeConfirmation() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = CustomClipboardTransformSkillRuntime(includeUnmetShareDependency = true),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "custom clipboard transform",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = clipboardResultData("剪贴板原文"),
            ),
        )
        requireNotNull(observed)

        val modelObserved = runtime.observeModelResult(planned.run.id, "模型输出")

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.Failed, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.Fail)
        assertTrue(modelObserved.decision.reason.contains("Unmet skill dependency"))
        assertTrue(modelObserved.decision.reason.contains("custom_foreground"))
        assertTrue(modelObserved.steps.any { it is AgentStep.ToolRejected })
        assertTrue(modelObserved.steps.none { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.toolName == MobileActionFunctions.SHARE_TEXT
        })
        assertNull(runtime.latestPendingConfirmation())
    }

    @Test
    fun modelStepBindingCannotDirectlyExposePrivateToolOutputToShare() {
        val auditSink = InMemoryToolAuditSink()
        val secretClipboardText = "secret clipboard text should not leave the model boundary"
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = CustomClipboardTransformSkillRuntime(shareBinding = "custom_read.text"),
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "custom clipboard transform",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        assertEquals(AgentRunState.Failed, planned.run.state)
        require(planned.plan is AgentPlan.RejectedTool)
        assertTrue(planned.plan.result.summary.contains("private tool output cannot be bound directly"))
        assertTrue(planned.steps.none { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.toolName == MobileActionFunctions.SHARE_TEXT
        })
        assertNull(runtime.latestPendingConfirmation())
        assertTrue(!planned.steps.toString().contains(secretClipboardText))
        assertTrue(!auditSink.events.toString().contains(secretClipboardText))
    }

    @Test
    fun compositeSkillIgnoresOldRequestIdsAfterShareIsPendingOrExecuting() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(
                likelyAction = true,
                planningResult = readClipboardPlanningResult(),
            ),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "总结剪贴板并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        val readRequestId = planned.plan.request.id
        runtime.confirmToolRequest(planned.run.id, readRequestId)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = readRequestId,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = clipboardResultData("剪贴板原文"),
            ),
        )
        requireNotNull(observed)
        val modelObserved = runtime.observeModelResult(planned.run.id, "摘要文本")
        requireNotNull(modelObserved)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        val shareRequestId = modelObserved.decision.plan.request.id

        val stillAwaiting = runtime.confirmToolRequest(planned.run.id, readRequestId)

        assertEquals(AgentRunState.AwaitingUserConfirmation, stillAwaiting?.state)
        runtime.confirmToolRequest(planned.run.id, shareRequestId)
        val staleObservation = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = readRequestId,
                status = ToolStatus.Succeeded,
                summary = "旧剪贴板观察不应生效",
                data = mapOf("text" to "不应重新进入模型"),
            ),
        )

        assertNull(staleObservation)
    }

    @Test
    fun blankCompositeModelResultFailsWithoutPlanningShare() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(
                likelyAction = true,
                planningResult = readClipboardPlanningResult(),
            ),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "总结剪贴板并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = clipboardResultData("剪贴板原文"),
            ),
        )
        requireNotNull(observed)

        val modelObserved = runtime.observeModelResult(planned.run.id, "   ")

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.Failed, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.Fail)
        assertTrue(modelObserved.steps.filterIsInstance<AgentStep.ToolRequested>().none {
            it.request.toolName == MobileActionFunctions.SHARE_TEXT
        })
    }

    @Test
    fun modelGenerationFailureMarksGeneratingRunFailedAndIgnoresLateOutput() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "远程工具解析失败",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        assertEquals(AgentRunState.GeneratingAnswer, planned.run.state)

        val failed = runtime.failModelGeneration(planned.run.id, "远程模型一次返回了多个工具调用，已拒绝执行")

        requireNotNull(failed)
        assertEquals(AgentRunState.Failed, failed.run.state)
        require(failed.decision is AgentObservationDecision.Fail)
        assertEquals("远程模型一次返回了多个工具调用，已拒绝执行", failed.decision.reason)
        val failedStep = failed.steps.filterIsInstance<AgentStep.Failed>().single()
        assertEquals("远程模型一次返回了多个工具调用，已拒绝执行", failedStep.reason)
        assertNull(runtime.observeModelResult(planned.run.id, "迟到的模型输出"))
    }

    @Test
    fun modelGenerationFailureIsNoOpAfterRunLeavesGeneratingState() {
        val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = traceStore,
        )
        val planned = runtime.runOnce(
            input = "普通聊天",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        val completed = runtime.observeModelResult(planned.run.id, "正常回答")
        requireNotNull(completed)
        assertEquals(AgentRunState.Completed, completed.run.state)

        val failed = runtime.failModelGeneration(planned.run.id, "迟到的失败")

        assertNull(failed)
        assertTrue(traceStore.steps(planned.run.id).none { step -> step is AgentStep.Failed })
    }

    @Test
    fun clipboardContinuationDoesNotCallObservationReplanner() {
        var replanCallCount = 0
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_CLIPBOARD,
                        title = "读取剪贴板",
                        summary = "将读取当前剪贴板文本。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner {
                replanCallCount += 1
                error("clipboard continuation should win before replanning")
            },
        )
        val planned = runtime.runOnce(
            input = "读取剪贴板并总结",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = clipboardResultData("需要本地续写的文本"),
            ),
        )

        requireNotNull(observed)
        assertEquals(0, replanCallCount)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.steps.none { it is AgentStep.ToolRejected })
    }

    @Test
    fun toolObservationIsIgnoredBeforeConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_CLIPBOARD,
                        title = "读取剪贴板",
                        summary = "将读取当前剪贴板文本。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = traceStore,
        )
        val planned = runtime.runOnce(
            input = "读取剪贴板并总结",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf("text" to "不应进入 trace 的内容"),
            ),
        )

        assertNull(observed)
        assertEquals(AgentRunState.AwaitingUserConfirmation, traceStore.run(planned.run.id)?.state)
        assertTrue(traceStore.steps(planned.run.id).none { it is AgentStep.ToolObserved })
        assertTrue(!traceStore.steps(planned.run.id).toString().contains("不应进入 trace 的内容"))
    }

    @Test
    fun toolObservationWithUnknownRequestIdIsIgnored() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_CLIPBOARD,
                        title = "读取剪贴板",
                        summary = "将读取当前剪贴板文本。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = traceStore,
        )
        val planned = runtime.runOnce(
            input = "读取剪贴板并总结",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = "unknown-request",
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf("text" to "不应进入 trace 的内容"),
            ),
        )

        assertNull(observed)
        assertEquals(AgentRunState.ExecutingTool, traceStore.run(planned.run.id)?.state)
        assertTrue(traceStore.steps(planned.run.id).none { it is AgentStep.ToolObserved })
        assertTrue(!traceStore.steps(planned.run.id).toString().contains("不应进入 trace 的内容"))
    }

    @Test
    fun confirmToolRequestEntersExecutingBeforeClearingPendingConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_CLIPBOARD,
                        title = "读取剪贴板",
                        summary = "将读取当前剪贴板文本。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val delegateStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
        val traceStore = ClearPendingThrowingTraceStore(delegateStore)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = traceStore,
        )
        val planned = runtime.runOnce(
            input = "读取剪贴板并总结",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)

        val failure = runCatching {
            runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(AgentRunState.ExecutingTool, delegateStore.run(planned.run.id)?.state)
        assertTrue(delegateStore.steps(planned.run.id).any { step ->
            step is AgentStep.UserConfirmed && step.requestId == planned.plan.request.id
        })
    }

    @Test
    fun confirmedToolResultIsObservedAndCompletesRun() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        title = "打开 Wi-Fi 设置",
                        summary = "将打开系统 Wi-Fi 设置页。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)

        val executing = runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        assertEquals(AgentRunState.ExecutingTool, executing?.state)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_WIFI_SETTINGS),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Completed, observed.run.state)
        assertEquals(AgentObservationDecision.Complete, observed.decision)
        assertTrue(observed.assistantMessage.contains("已打开 Wi-Fi 设置页"))
        assertTrue(observed.steps.any { it is AgentStep.UserConfirmed })
        assertTrue(observed.steps.any { it is AgentStep.ToolObserved })
        assertTrue(observed.steps.any { step ->
            step is AgentStep.ObservationDecided &&
                step.decision == AgentObservationDecision.Complete
        })
        assertTrue(observed.steps.any { it is AgentStep.AssistantResponded })
        assertEquals(
            listOf(
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ConfirmationRequested,
                ToolAuditEventType.UserConfirmed,
                ToolAuditEventType.ToolObserved,
            ),
            auditSink.events.map { it.eventType },
        )
        assertTrue(auditSink.events.all { event ->
            event.toolName == null || event.toolName == MobileActionFunctions.OPEN_WIFI_SETTINGS
        })
    }

    @Test
    fun nonReminderObservationDoesNotSurfaceSpoofedReminderMetadataInAudit() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        title = "打开 Wi-Fi 设置",
                        summary = "将打开系统 Wi-Fi 设置页。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_WIFI_SETTINGS) + mapOf(
                    "toolName" to MobileActionFunctions.SCHEDULE_REMINDER,
                    "taskId" to "task-spoofed",
                    "recoveryTaskId" to "task-spoofed",
                ),
            ),
        )

        requireNotNull(observed)
        val observedAudit = auditSink.events.single { event ->
            event.eventType == ToolAuditEventType.ToolObserved
        }
        assertFalse(observedAudit.summary.contains("task-spoofed"))
    }

    @Test
    fun reminderObservationSurfacesBoundedRecoveryHint() {
        val auditSink = InMemoryToolAuditSink()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "提醒我 10 分钟后喝水",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, planned.plan.request.toolName)

        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已安排 10000 触发的后台提醒",
                data = scheduleReminderResultData(taskId = "task-1", triggerAtMillis = "10000"),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Completed, observed.run.state)
        val recoveryAction = observed.recoveryAction
        requireNotNull(recoveryAction)
        assertEquals(planned.plan.request.id, recoveryAction.sourceRequestId)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, recoveryAction.sourceToolName)
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, recoveryAction.request.toolName)
        assertEquals(mapOf("taskId" to "task-1"), recoveryAction.request.arguments)
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, recoveryAction.draft.functionName)
        assertTrue(observed.assistantMessage.contains("如需撤销该提醒"))
        assertTrue(observed.assistantMessage.contains(MobileActionFunctions.CANCEL_REMINDER))
        assertTrue(observed.assistantMessage.contains("taskId=task-1"))
        assertFalse(observed.assistantMessage.contains("提醒我喝水"))
        assertTrue(observed.steps.any { step ->
            step is AgentStep.AssistantResponded &&
                step.text.contains("taskId=task-1") &&
                !step.text.contains("提醒我喝水")
        })
        val observedAudit = auditSink.events.single { event ->
            event.eventType == ToolAuditEventType.ToolObserved
        }
        assertTrue(observedAudit.summary.contains("taskId=task-1"))
        assertTrue(observedAudit.summary.contains("taskStatus=Scheduled"))
        assertTrue(observedAudit.summary.contains("triggerAtMillis=10000"))
        assertTrue(observedAudit.summary.contains("recoveryToolName=${MobileActionFunctions.CANCEL_REMINDER}"))
        assertTrue(observedAudit.summary.contains("recoveryTaskId=task-1"))
        assertFalse(observedAudit.summary.contains("喝水"))
        assertFalse(observedAudit.summary.contains("提醒我喝水"))
    }

    @Test
    fun periodicCheckObservationDoesNotSurfaceUnsupportedRecoveryAction() {
        val auditSink = InMemoryToolAuditSink()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "开启周期检查，每 2 小时",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.CONFIGURE_PERIODIC_CHECK, planned.plan.request.toolName)

        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已开启本地提醒周期检查：每 120 分钟",
                data = mapOf(
                    "toolName" to MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
                    "enabled" to "true",
                    "taskStatus" to "Scheduled",
                    "intervalMinutes" to "120",
                    "minNotificationSpacingMinutes" to "360",
                    "overdueGraceMinutes" to "30",
                    "requiresBatteryNotLow" to "true",
                    "requiresCharging" to "false",
                    "recoveryToolName" to MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
                    "recoveryEnabled" to "false",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Completed, observed.run.state)
        assertNull(observed.recoveryAction)
        assertFalse(observed.assistantMessage.contains("如需撤销"))
        assertFalse(observed.assistantMessage.contains("recoveryEnabled"))
        val observedAudit = auditSink.events.single { event ->
            event.eventType == ToolAuditEventType.ToolObserved
        }
        assertFalse(observedAudit.summary.contains("recoveryToolName"))
        assertFalse(observedAudit.summary.contains("recoveryEnabled"))
    }

    @Test
    fun reminderRecoveryActionRequestsAuditedCancelConfirmation() {
        val auditSink = InMemoryToolAuditSink()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val action = AgentRecoveryAction(
            sourceRequestId = "request-reminder",
            sourceToolName = MobileActionFunctions.SCHEDULE_REMINDER,
            request = ToolRequest(
                id = "request-recovery",
                toolName = MobileActionFunctions.CANCEL_REMINDER,
                arguments = mapOf("taskId" to "task-1"),
                reason = "unsafe source reason should be normalized",
            ),
            draft = ActionDraft(
                functionName = MobileActionFunctions.CANCEL_REMINDER,
                title = "unsafe title",
                summary = "unsafe reminder body: 喝水",
                parameters = mapOf("taskId" to "task-1"),
                requiresConfirmation = true,
            ),
        )

        val requested = runtime.requestRecoveryAction(action)

        requireNotNull(requested)
        assertEquals(AgentRunState.AwaitingUserConfirmation, requested.run.state)
        require(requested.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, requested.plan.request.toolName)
        assertEquals(mapOf("taskId" to "task-1"), requested.plan.request.arguments)
        assertEquals("撤销提醒", requested.plan.draft.title)
        assertEquals("将取消提醒任务：task-1", requested.plan.draft.summary)
        assertFalse(requested.plan.draft.summary.contains("喝水"))
        val pending = runtime.latestPendingConfirmation()
        requireNotNull(pending)
        assertEquals(requested.run.id, pending.run.id)
        assertEquals("request-recovery", pending.request.id)
        assertEquals(
            listOf(ToolAuditEventType.ToolPlanned, ToolAuditEventType.ConfirmationRequested),
            auditSink.events.map { it.eventType },
        )
        assertTrue(auditSink.events.all { event ->
            event.toolName == MobileActionFunctions.CANCEL_REMINDER &&
                event.riskLevel == RiskLevel.MediumDraftOrNavigation &&
                ToolPermission.SchedulesBackgroundWork in event.permissions
        })

        val executing = runtime.confirmToolRequest(requested.run.id, requested.plan.request.id)
        requireNotNull(executing)
        assertEquals(AgentRunState.ExecutingTool, executing.state)
        val observed = runtime.observeToolResult(
            runId = requested.run.id,
            result = ToolResult(
                requestId = requested.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已取消后台任务：task-1",
                data = cancelReminderResultData(taskId = "task-1"),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Completed, observed.run.state)
        assertEquals(
            listOf(
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ConfirmationRequested,
                ToolAuditEventType.UserConfirmed,
                ToolAuditEventType.ToolObserved,
            ),
            auditSink.events.map { it.eventType },
        )
        val observedAudit = auditSink.events.single { event ->
            event.eventType == ToolAuditEventType.ToolObserved
        }
        assertTrue(observedAudit.summary.contains("taskId=task-1"))
        assertTrue(observedAudit.summary.contains("taskStatus=Cancelled"))
        assertFalse(observedAudit.summary.contains("喝水"))
        assertFalse(observedAudit.summary.contains("提醒我喝水"))
    }

    @Test
    fun reminderObservationIgnoresUnsafeRecoveryMetadata() {
        val auditSink = InMemoryToolAuditSink()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "提醒我 10 分钟后喝水",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)

        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已安排 10000 触发的后台提醒",
                data = mapOf(
                    "toolName" to MobileActionFunctions.SCHEDULE_REMINDER,
                    "recoveryToolName" to MobileActionFunctions.CANCEL_REMINDER,
                    "recoveryTaskId" to "token=secret",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(null, observed.recoveryAction)
        assertFalse(observed.assistantMessage.contains("如需撤销"))
        assertFalse(observed.assistantMessage.contains("token=secret"))
        val observedAudit = auditSink.events.single { event ->
            event.eventType == ToolAuditEventType.ToolObserved
        }
        assertFalse(observedAudit.summary.contains("token=secret"))
        assertFalse(observedAudit.summary.contains("recoveryTaskId="))
    }

    @Test
    fun payloadBearingPendingConfirmationFailsClosedAfterRestart() {
        val dao = FakeAgentTraceDao()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.SHARE_TEXT,
                        title = "系统分享",
                        summary = "将打开系统分享面板。",
                        parameters = mapOf("text" to "Kotlin private payload"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-restored-web" },
            ),
        )
        val planned = initialRuntime.runOnce(
            input = "分享这段文字：Kotlin private payload",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)

        val restoredTraceStore = RoomAgentTraceStore(traceDao = dao)
        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = restoredTraceStore,
        )
        val restoredPending = restoredRuntime.latestPendingConfirmation()

        assertNull(restoredPending)
        assertEquals(AgentRunState.Failed, restoredTraceStore.run(planned.run.id)?.state)
        assertTrue(restoredTraceStore.steps(planned.run.id).any { step ->
            step is AgentStep.Failed &&
                step.reason.contains("Pending tool confirmation could not be restored")
        })
        assertNull(
            restoredRuntime.confirmToolRequest(
                runId = planned.run.id,
                requestId = planned.plan.request.id,
            )?.takeIf { run -> run.state == AgentRunState.ExecutingTool },
        )
    }

    @Test
    fun payloadBearingSequentialPendingFailsClosedAfterRestart() {
        val dao = FakeAgentTraceDao()
        val searchActionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.SHARE_TEXT,
                        title = "系统分享",
                        summary = "将打开系统分享面板。",
                        parameters = mapOf("text" to "Kotlin private payload"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = searchActionRuntime,
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-restored-sequential" },
            ),
        )
        val planned = initialRuntime.runOnce(
            input = "先分享 Kotlin private payload，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.SHARE_TEXT, planned.plan.request.toolName)

        val restoredTraceStore = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { 2_000L },
        )
        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = searchActionRuntime,
            traceStore = restoredTraceStore,
        )
        val restoredPending = restoredRuntime.latestPendingConfirmation()

        assertNull(restoredPending)
        assertEquals(AgentRunState.Failed, restoredTraceStore.run(planned.run.id)?.state)
        assertTrue(restoredTraceStore.steps(planned.run.id).any { step ->
            step is AgentStep.Failed &&
                step.reason.contains("Pending tool confirmation could not be restored")
        })
        val persistedTrace = dao.steps(planned.run.id).joinToString("\n") { step ->
            "${step.summary}\n${step.json}"
        }
        assertFalse(persistedTrace.contains("先分享 Kotlin private payload，然后"))
    }

    @Test
    fun restoredClipboardSummaryPendingContinuesWithModelAndPlansShareConfirmation() {
        val dao = FakeAgentTraceDao()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-restored-clipboard" },
            ),
        )
        val planned = initialRuntime.runOnce(
            input = "总结剪贴板并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, planned.plan.skillRequest?.skillId)

        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = RoomAgentTraceStore(traceDao = dao),
        )
        val restoredPending = restoredRuntime.latestPendingConfirmation()
        requireNotNull(restoredPending)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, restoredPending.request.toolName)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, restoredPending.skillPlan?.request?.skillId)
        assertEquals(3, restoredPending.skillPlan?.steps?.size)

        val executing = restoredRuntime.confirmToolRequest(
            runId = restoredPending.run.id,
            requestId = restoredPending.request.id,
        )
        assertEquals(AgentRunState.ExecutingTool, executing?.state)

        val observed = restoredRuntime.observeToolResult(
            runId = restoredPending.run.id,
            result = ToolResult(
                requestId = restoredPending.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = clipboardResultData("剪贴板原文"),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("剪贴板原文"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("摘要剪贴板内容"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("剪贴板摘要分享"))
        assertFalse(observed.continuationPromptForModel.orEmpty().contains("总结剪贴板并分享"))
        assertFalse(observed.continuationPromptForModel.orEmpty().contains("[redacted]"))

        val modelObserved = restoredRuntime.observeModelResult(
            runId = restoredPending.run.id,
            text = "摘要：恢复后生成的分享内容",
        )

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.AwaitingUserConfirmation, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        val sharePlan = modelObserved.decision.plan
        assertEquals(MobileActionFunctions.SHARE_TEXT, sharePlan.request.toolName)
        assertEquals("摘要：恢复后生成的分享内容", sharePlan.request.arguments["text"])
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, sharePlan.skillRequest?.skillId)

        val nextPending = restoredRuntime.latestPendingConfirmation()
        requireNotNull(nextPending)
        assertEquals(sharePlan.request.id, nextPending.request.id)
        assertEquals(MobileActionFunctions.SHARE_TEXT, nextPending.request.toolName)
        assertEquals("摘要：恢复后生成的分享内容", nextPending.request.arguments["text"])
    }

    @Test
    fun restoredToolStepOutputBoundPendingContinuesAfterRestart() {
        val dao = FakeAgentTraceDao()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = ToolToToolSkillRuntime(),
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-restored-tool-to-tool" },
            ),
        )
        val planned = initialRuntime.runOnce(
            input = ToolToToolSkillRuntime.REMINDER_INPUT,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(ToolToToolSkillRuntime.REMINDER_SKILL_ID, planned.plan.skillRequest?.skillId)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, planned.plan.request.toolName)

        initialRuntime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = initialRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已安排 10000 触发的后台提醒",
                data = scheduleReminderResultData(taskId = "task-restored", triggerAtMillis = "10000"),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.AwaitingUserConfirmation, observed.run.state)
        require(observed.decision is AgentObservationDecision.PlanNextTool)
        val cancelPlan = observed.decision.plan
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, cancelPlan.request.toolName)
        assertEquals(mapOf("taskId" to "task-restored"), cancelPlan.request.arguments)
        val persistedPending = dao.latestPendingConfirmation()
        requireNotNull(persistedPending)
        assertEquals(cancelPlan.request.id, persistedPending.requestId)
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, persistedPending.toolName)
        assertEquals("task-restored", JSONObject(persistedPending.argumentsJson).getString("taskId"))
        assertEquals("task-restored", JSONObject(persistedPending.draftParametersJson).getString("taskId"))
        assertFalse(persistedPending.argumentsJson.contains(ToolToToolSkillRuntime.REMINDER_INPUT))
        assertNull(persistedPending.nextActionInput)

        val restoredTraceStore = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { 2_000L },
        )
        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = ToolToToolSkillRuntime(),
            traceStore = restoredTraceStore,
        )
        val restoredPending = restoredRuntime.latestPendingConfirmation()
        requireNotNull(restoredPending)
        assertEquals(planned.run.id, restoredPending.run.id)
        assertEquals(cancelPlan.request.id, restoredPending.request.id)
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, restoredPending.request.toolName)
        assertEquals(mapOf("taskId" to "task-restored"), restoredPending.request.arguments)
        assertEquals(mapOf("taskId" to "task-restored"), restoredPending.draft.parameters)
        assertEquals(ToolToToolSkillRuntime.REMINDER_SKILL_ID, restoredPending.skillPlan?.request?.skillId)
        assertEquals(2, restoredPending.skillPlan?.steps?.size)
        assertTrue(restoredTraceStore.steps(planned.run.id).any { step ->
            step is AgentStep.ToolRequested &&
                step.request.id == planned.plan.request.id &&
                step.request.toolName == MobileActionFunctions.SCHEDULE_REMINDER
        })

        val executing = restoredRuntime.confirmToolRequest(
            runId = restoredPending.run.id,
            requestId = restoredPending.request.id,
        )
        assertEquals(AgentRunState.ExecutingTool, executing?.state)
        val completed = restoredRuntime.observeToolResult(
            runId = restoredPending.run.id,
            result = ToolResult(
                requestId = restoredPending.request.id,
                status = ToolStatus.Succeeded,
                summary = "已取消后台任务：task-restored",
                data = cancelReminderResultData(taskId = "task-restored"),
            ),
        )

        requireNotNull(completed)
        assertEquals(AgentRunState.Completed, completed.run.state)
        assertEquals(AgentObservationDecision.Complete, completed.decision)
        assertTrue(dao.pendingConfirmations().isEmpty())
        assertFalse(
            restoredRuntime.confirmToolRequest(planned.run.id, restoredPending.request.id)?.state ==
                AgentRunState.ExecutingTool,
        )
    }

    @Test
    fun restoredSkillPendingSurvivesDisplayOnlyManifestDrift() {
        val dao = FakeAgentTraceDao()
        val pending = persistToolToToolCancelPending(dao, runId = "run-display-only-manifest-drift")
        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = ManifestTransformingSkillRuntime(ToolToToolSkillRuntime()) { manifest ->
                if (manifest.id == ToolToToolSkillRuntime.REMINDER_SKILL_ID) {
                    manifest.copy(
                        title = "Updated reminder copy",
                        description = "Updated display copy only",
                        triggerExamples = listOf("updated example"),
                        inputSchemaJson = """
                            {
                              "additionalProperties": false,
                              "properties": {
                                "input": {
                                  "minLength": 1,
                                  "type": "string"
                                }
                              },
                              "required": ["input"],
                              "type": "object"
                            }
                        """.trimIndent(),
                    )
                } else {
                    manifest
                }
            },
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 2_000L },
            ),
        )

        val restoredPending = restoredRuntime.latestPendingConfirmation()

        requireNotNull(restoredPending)
        assertEquals(pending.runId, restoredPending.run.id)
        assertEquals(pending.cancelRequestId, restoredPending.request.id)
        assertEquals(
            AgentRunState.ExecutingTool,
            restoredRuntime.confirmToolRequest(pending.runId, pending.cancelRequestId)?.state,
        )
    }

    @Test
    fun restoredSkillPendingFailsClosedWhenCurrentRuntimeManifestContractChanged() {
        val dao = FakeAgentTraceDao()
        val pending = persistToolToToolCancelPending(dao, runId = "run-manifest-contract-drift")
        val restoredTraceStore = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { 2_000L },
        )
        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = ManifestTransformingSkillRuntime(ToolToToolSkillRuntime()) { manifest ->
                if (manifest.id == ToolToToolSkillRuntime.REMINDER_SKILL_ID) {
                    manifest.copy(riskLevel = RiskLevel.HighExternalSend)
                } else {
                    manifest
                }
            },
            traceStore = restoredTraceStore,
        )

        val restoredPending = restoredRuntime.latestPendingConfirmation()

        assertNull(restoredPending)
        assertEquals(AgentRunState.Failed, restoredTraceStore.run(pending.runId)?.state)
        assertTrue(dao.pendingConfirmations().isEmpty())
        assertTrue(dao.skillRunCheckpointsForRun(pending.runId).isEmpty())
        assertTrue(restoredTraceStore.steps(pending.runId).any { step ->
            step is AgentStep.Failed && step.reason.contains("Skill manifest changed")
        })
    }

    @Test
    fun directConfirmRestoredSkillPendingFailsClosedWhenCurrentRuntimeManifestContractChanged() {
        val dao = FakeAgentTraceDao()
        val pending = persistToolToToolCancelPending(dao, runId = "run-direct-confirm-manifest-drift")
        val restoredTraceStore = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { 2_000L },
        )
        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = ManifestTransformingSkillRuntime(ToolToToolSkillRuntime()) { manifest ->
                if (manifest.id == ToolToToolSkillRuntime.REMINDER_SKILL_ID) {
                    manifest.copy(requiredTools = listOf(MobileActionFunctions.SCHEDULE_REMINDER))
                } else {
                    manifest
                }
            },
            traceStore = restoredTraceStore,
        )

        val confirmed = restoredRuntime.confirmToolRequest(
            runId = pending.runId,
            requestId = pending.cancelRequestId,
        )

        assertEquals(AgentRunState.Failed, confirmed?.state)
        assertTrue(dao.pendingConfirmations().isEmpty())
        assertTrue(dao.skillRunCheckpointsForRun(pending.runId).isEmpty())
        assertTrue(restoredTraceStore.steps(pending.runId).any { step ->
            step is AgentStep.Failed && step.reason.contains("Skill manifest changed")
        })
    }

    @Test
    fun restoredValueFreeModelFrontierLetsMiddleToolContinueToNextNoPayloadTool() {
        val dao = FakeAgentTraceDao()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = ValueFreeFrontierSkillRuntime(),
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-value-free-frontier" },
            ),
        )
        val planned = initialRuntime.runOnce(
            input = ValueFreeFrontierSkillRuntime.INPUT,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, planned.plan.request.toolName)

        initialRuntime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val readObserved = initialRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = clipboardResultData("frontier clipboard text"),
            ),
        )
        requireNotNull(readObserved)
        assertEquals(AgentRunState.GeneratingAnswer, readObserved.run.state)

        val modelObserved = initialRuntime.observeModelResult(
            runId = planned.run.id,
            text = "SECRET_FRONTIER_MODEL_OUTPUT",
        )
        requireNotNull(modelObserved)
        assertEquals(AgentRunState.AwaitingUserConfirmation, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        val wifiPlan = modelObserved.decision.plan
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, wifiPlan.request.toolName)
        assertEquals(
            listOf("frontier_read", "frontier_model"),
            dao.skillRunCheckpoint(planned.run.id, wifiPlan.request.id)?.completedStepIdsJson
                ?.let { org.json.JSONArray(it).toStringListForTest() },
        )
        val persistedBeforeRestart = dao.steps(planned.run.id).joinToString("\n") { step -> step.json }
        assertFalse(persistedBeforeRestart.contains("SECRET_FRONTIER_MODEL_OUTPUT"))

        val restoredTraceStore = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { 2_000L },
        )
        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = ValueFreeFrontierSkillRuntime(),
            traceStore = restoredTraceStore,
        )
        val restoredPending = restoredRuntime.latestPendingConfirmation()
        requireNotNull(restoredPending)
        assertEquals(wifiPlan.request.id, restoredPending.request.id)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, restoredPending.request.toolName)
        assertEquals(listOf("frontier_read", "frontier_model"), restoredPending.skillRunCheckpoint?.completedStepIds)

        restoredRuntime.confirmToolRequest(restoredPending.run.id, restoredPending.request.id)
        val flashlightObserved = restoredRuntime.observeToolResult(
            runId = restoredPending.run.id,
            result = ToolResult(
                requestId = restoredPending.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_WIFI_SETTINGS),
            ),
        )

        requireNotNull(flashlightObserved)
        assertEquals(AgentRunState.AwaitingUserConfirmation, flashlightObserved.run.state)
        require(flashlightObserved.decision is AgentObservationDecision.PlanNextTool)
        val flashlightPlan = flashlightObserved.decision.plan
        assertEquals(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS, flashlightPlan.request.toolName)
        assertTrue(flashlightPlan.request.arguments.isEmpty())
        assertFalse(
            restoredTraceStore.steps(planned.run.id).joinToString("\n") { step -> step.toString() }
                .contains("SECRET_FRONTIER_MODEL_OUTPUT"),
        )
    }

    @Test
    fun restoredValueFreeModelFrontierSurvivesDirectConfirmWithoutPendingLookup() {
        val dao = FakeAgentTraceDao()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = ValueFreeFrontierSkillRuntime(),
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-direct-confirm-frontier" },
            ),
        )
        val planned = initialRuntime.runOnce(
            input = ValueFreeFrontierSkillRuntime.INPUT,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        initialRuntime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val readObserved = initialRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = clipboardResultData("frontier clipboard text"),
            ),
        )
        requireNotNull(readObserved)
        val modelObserved = initialRuntime.observeModelResult(
            runId = planned.run.id,
            text = "SECRET_FRONTIER_MODEL_OUTPUT",
        )
        requireNotNull(modelObserved)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        val wifiPlan = modelObserved.decision.plan
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, wifiPlan.request.toolName)
        assertEquals(
            listOf("frontier_read", "frontier_model"),
            dao.skillRunCheckpoint(planned.run.id, wifiPlan.request.id)?.completedStepIdsJson
                ?.let { org.json.JSONArray(it).toStringListForTest() },
        )

        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = ValueFreeFrontierSkillRuntime(),
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 2_000L },
            ),
        )

        val executing = restoredRuntime.confirmToolRequest(
            runId = planned.run.id,
            requestId = wifiPlan.request.id,
        )
        assertEquals(AgentRunState.ExecutingTool, executing?.state)
        val flashlightObserved = restoredRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = wifiPlan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_WIFI_SETTINGS),
            ),
        )

        requireNotNull(flashlightObserved)
        assertEquals(AgentRunState.AwaitingUserConfirmation, flashlightObserved.run.state)
        require(flashlightObserved.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS, flashlightObserved.decision.plan.request.toolName)
        assertFalse(
            dao.steps(planned.run.id).joinToString("\n") { step -> step.json }
                .contains("SECRET_FRONTIER_MODEL_OUTPUT"),
        )
    }

    @Test
    fun restoredValueFreeFrontierDoesNotRecoverModelOutputForPayloadBinding() {
        val dao = FakeAgentTraceDao()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = ValueFreeFrontierSkillRuntime(nextStepBindsModelPayload = true),
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-value-free-payload-frontier" },
            ),
        )
        val planned = initialRuntime.runOnce(
            input = ValueFreeFrontierSkillRuntime.INPUT,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        initialRuntime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val readObserved = initialRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = clipboardResultData("frontier clipboard text"),
            ),
        )
        requireNotNull(readObserved)
        initialRuntime.observeModelResult(
            runId = planned.run.id,
            text = "SECRET_FRONTIER_SHARE_TEXT",
        )
        val persistedPending = dao.latestPendingConfirmation()
        requireNotNull(persistedPending)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, persistedPending.toolName)

        val restoredTraceStore = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { 2_000L },
        )
        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = ValueFreeFrontierSkillRuntime(nextStepBindsModelPayload = true),
            traceStore = restoredTraceStore,
        )
        val restoredPending = restoredRuntime.latestPendingConfirmation()
        requireNotNull(restoredPending)
        restoredRuntime.confirmToolRequest(restoredPending.run.id, restoredPending.request.id)
        val failed = restoredRuntime.observeToolResult(
            runId = restoredPending.run.id,
            result = ToolResult(
                requestId = restoredPending.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_WIFI_SETTINGS),
            ),
        )

        requireNotNull(failed)
        assertEquals(AgentRunState.Failed, failed.run.state)
        require(failed.decision is AgentObservationDecision.Fail)
        assertTrue(failed.decision.reason.contains("Missing tool result binding"))
        assertNull(restoredRuntime.latestPendingConfirmation())
        assertTrue(restoredTraceStore.steps(planned.run.id).none { step ->
            step is AgentStep.ToolRequested && step.request.toolName == MobileActionFunctions.SHARE_TEXT
        })
        assertFalse(
            restoredTraceStore.steps(planned.run.id).joinToString("\n") { step -> step.toString() }
                .contains("SECRET_FRONTIER_SHARE_TEXT"),
        )
        assertTrue(dao.pendingConfirmations().isEmpty())
    }

    @Test
    fun restoredClipboardSummarySharePendingFailsClosedAfterRestart() {
        val dao = FakeAgentTraceDao()
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-restored-share" },
            ),
        )
        val rawClipboardText = "重启后不应重新进入 trace 的剪贴板原文"
        val modelSummary = "摘要：恢复后可分享的安全内容"
        val planned = initialRuntime.runOnce(
            input = "总结剪贴板并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        val readRequestId = planned.plan.request.id
        initialRuntime.confirmToolRequest(planned.run.id, readRequestId)
        val readObserved = initialRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = readRequestId,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = clipboardResultData(rawClipboardText),
            ),
        )
        requireNotNull(readObserved)
        assertEquals(AgentRunState.GeneratingAnswer, readObserved.run.state)

        val modelObserved = initialRuntime.observeModelResult(planned.run.id, modelSummary)

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.AwaitingUserConfirmation, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        val shareRequestId = modelObserved.decision.plan.request.id
        assertEquals(MobileActionFunctions.SHARE_TEXT, modelObserved.decision.plan.request.toolName)
        assertEquals(modelSummary, modelObserved.decision.plan.request.arguments["text"])
        val persistedPending = dao.latestPendingConfirmation()
        requireNotNull(persistedPending)
        assertEquals(shareRequestId, persistedPending.requestId)
        assertEquals(MobileActionFunctions.SHARE_TEXT, persistedPending.toolName)
        assertFalse(JSONObject(persistedPending.argumentsJson).has("text"))
        assertFalse(JSONObject(persistedPending.draftParametersJson).has("text"))
        assertFalse(persistedPending.argumentsJson.contains(modelSummary))
        assertFalse(persistedPending.draftParametersJson.contains(modelSummary))
        assertNull(persistedPending.nextActionInput)

        val restoredTraceStore = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { 2_000L },
        )
        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = restoredTraceStore,
        )
        val restoredPending = restoredRuntime.latestPendingConfirmation()

        assertNull(restoredPending)
        assertEquals(AgentRunState.Failed, restoredTraceStore.run(planned.run.id)?.state)
        assertNull(restoredRuntime.latestPendingConfirmation())
        assertTrue(dao.pendingConfirmations().isEmpty())
        assertTrue(restoredTraceStore.steps(planned.run.id).any { step ->
            step is AgentStep.Failed &&
                step.reason.contains("Pending tool confirmation could not be restored")
        })
        assertFalse(
            restoredRuntime.confirmToolRequest(planned.run.id, shareRequestId)?.state ==
                AgentRunState.ExecutingTool,
        )
        assertFalse(
            restoredRuntime.confirmToolRequest(planned.run.id, readRequestId)?.state ==
                AgentRunState.ExecutingTool,
        )
        val persistedTrace = dao.steps(planned.run.id).joinToString("\n") { step ->
            "${step.summary}\n${step.json}"
        }
        assertTrue(!persistedTrace.contains(rawClipboardText))
        assertTrue(!persistedTrace.contains(modelSummary))
        assertTrue(!auditSink.events.toString().contains(rawClipboardText))
        assertTrue(!auditSink.events.toString().contains(modelSummary))
    }

    @Test
    fun restoredCurrentScreenTextSummarySharePendingFailsClosedAfterRestart() {
        val dao = FakeAgentTraceDao()
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-restored-screen-share" },
            ),
        )
        val rawScreenText = "重启后不应重新进入 trace 的屏幕文本"
        val modelSummary = "摘要：恢复后可分享的屏幕摘要"
        val planned = initialRuntime.runOnce(
            input = "总结当前屏幕文字并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        val readRequestId = planned.plan.request.id
        initialRuntime.confirmToolRequest(planned.run.id, readRequestId)
        val readObserved = initialRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = readRequestId,
                status = ToolStatus.Succeeded,
                summary = "已读取当前屏幕可访问文本快照",
                data = currentScreenTextResultData(rawScreenText),
            ),
        )
        requireNotNull(readObserved)
        assertEquals(AgentRunState.GeneratingAnswer, readObserved.run.state)

        val modelObserved = initialRuntime.observeModelResult(planned.run.id, modelSummary)

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.AwaitingUserConfirmation, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        val shareRequestId = modelObserved.decision.plan.request.id
        assertEquals(MobileActionFunctions.SHARE_TEXT, modelObserved.decision.plan.request.toolName)
        assertEquals(modelSummary, modelObserved.decision.plan.request.arguments["text"])
        val persistedPending = dao.latestPendingConfirmation()
        requireNotNull(persistedPending)
        assertEquals(shareRequestId, persistedPending.requestId)
        assertEquals(MobileActionFunctions.SHARE_TEXT, persistedPending.toolName)
        assertFalse(JSONObject(persistedPending.argumentsJson).has("text"))
        assertFalse(JSONObject(persistedPending.draftParametersJson).has("text"))
        assertFalse(persistedPending.argumentsJson.contains(modelSummary))
        assertFalse(persistedPending.draftParametersJson.contains(modelSummary))
        assertNull(persistedPending.nextActionInput)

        val restoredTraceStore = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { 2_000L },
        )
        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = restoredTraceStore,
        )
        val restoredPending = restoredRuntime.latestPendingConfirmation()

        assertNull(restoredPending)
        assertEquals(AgentRunState.Failed, restoredTraceStore.run(planned.run.id)?.state)
        assertNull(restoredRuntime.latestPendingConfirmation())
        assertTrue(dao.pendingConfirmations().isEmpty())
        assertTrue(restoredTraceStore.steps(planned.run.id).any { step ->
            step is AgentStep.Failed &&
                step.reason.contains("Pending tool confirmation could not be restored")
        })
        assertFalse(
            restoredRuntime.confirmToolRequest(planned.run.id, shareRequestId)?.state ==
                AgentRunState.ExecutingTool,
        )
        assertFalse(
            restoredRuntime.confirmToolRequest(planned.run.id, readRequestId)?.state ==
                AgentRunState.ExecutingTool,
        )
        val persistedTrace = dao.steps(planned.run.id).joinToString("\n") { step ->
            "${step.summary}\n${step.json}"
        }
        assertTrue(!persistedTrace.contains(rawScreenText))
        assertTrue(!persistedTrace.contains(modelSummary))
        assertTrue(!auditSink.events.toString().contains(rawScreenText))
        assertTrue(!auditSink.events.toString().contains(modelSummary))
    }

    @Test
    fun restoredMultiModelSkillPayloadPendingFailsClosed() {
        val dao = FakeAgentTraceDao()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = MultiModelSkillRuntime(),
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-multi-model-skill" },
            ),
        )
        val planned = initialRuntime.runOnce(
            input = "multi model skill",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.WEB_SEARCH, planned.plan.request.toolName)

        initialRuntime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val searchObserved = initialRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "搜索结果摘要",
                data = webSearchResultData(summaryText = "搜索结果摘要"),
            ),
        )
        requireNotNull(searchObserved)
        assertEquals(AgentRunState.GeneratingAnswer, searchObserved.run.state)
        require(searchObserved.decision is AgentObservationDecision.ContinueWithModel)
        assertFalse(searchObserved.decision.requiresLocalModel)
        assertFalse(searchObserved.continuationRequiresLocalModel)
        assertTrue(searchObserved.continuationPromptForModel.orEmpty().contains("整理搜索结果"))
        assertTrue(searchObserved.continuationPromptForModel.orEmpty().contains("搜索结果摘要"))

        val sharePlanned = initialRuntime.observeModelResult(
            runId = planned.run.id,
            text = "可分享的搜索摘要",
        )
        requireNotNull(sharePlanned)
        assertEquals(AgentRunState.AwaitingUserConfirmation, sharePlanned.run.state)
        require(sharePlanned.decision is AgentObservationDecision.PlanNextTool)
        val shareRequest = sharePlanned.decision.plan.request
        assertEquals(MobileActionFunctions.SHARE_TEXT, shareRequest.toolName)
        assertEquals("可分享的搜索摘要", shareRequest.arguments["text"])
        val persistedPending = dao.latestPendingConfirmation()
        requireNotNull(persistedPending)
        assertEquals(shareRequest.id, persistedPending.requestId)
        assertEquals(MobileActionFunctions.SHARE_TEXT, persistedPending.toolName)
        assertFalse(JSONObject(persistedPending.argumentsJson).has("text"))
        assertFalse(JSONObject(persistedPending.draftParametersJson).has("text"))
        assertFalse(persistedPending.argumentsJson.contains("可分享的搜索摘要"))
        assertFalse(persistedPending.draftParametersJson.contains("可分享的搜索摘要"))
        assertNull(persistedPending.nextActionInput)

        val restoredTraceStore = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { 2_000L },
        )
        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = MultiModelSkillRuntime(),
            traceStore = restoredTraceStore,
        )
        val restoredPending = restoredRuntime.latestPendingConfirmation()

        assertNull(restoredPending)
        assertEquals(AgentRunState.Failed, restoredTraceStore.run(planned.run.id)?.state)
        assertTrue(restoredTraceStore.steps(planned.run.id).any { step ->
            step is AgentStep.Failed &&
                step.reason.contains("Pending tool confirmation could not be restored")
        })
        assertTrue(dao.pendingConfirmations().isEmpty())
        assertFalse(
            restoredRuntime.confirmToolRequest(planned.run.id, shareRequest.id)?.state ==
                AgentRunState.ExecutingTool,
        )
        val persistedTrace = dao.steps(planned.run.id).joinToString("\n") { step ->
            "${step.summary}\n${step.json}"
        }
        assertFalse(persistedTrace.contains("搜索结果摘要"))
        assertFalse(persistedTrace.contains("可分享的搜索摘要"))
        assertFalse(persistedTrace.contains("继续打开设置"))
    }

    @Test
    fun failedDeviceControlObservationPlansSafeObserveCheckpoint() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.UI_TAP,
                        title = "点击按钮",
                        summary = "将点击当前屏幕上的 Continue 按钮。",
                        parameters = mapOf("target" to "Continue"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "点击 Continue",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.UI_TAP, planned.plan.request.toolName)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val replanned = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Failed,
                summary = "未找到可点击目标：Continue",
                data = mapOf(
                    "toolName" to MobileActionFunctions.UI_TAP,
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to "true",
                    "actionType" to "tap",
                    "status" to "failed",
                    "retryable" to "true",
                    "failureKind" to "node_not_found",
                ),
                error = ToolError(ToolErrorCode.ExecutionFailed, "未找到可点击目标：Continue"),
                retryable = true,
            ),
        )

        requireNotNull(replanned)
        assertEquals(AgentRunState.AwaitingUserConfirmation, replanned.run.state)
        require(replanned.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(MobileActionFunctions.OBSERVE_CURRENT_SCREEN, replanned.decision.plan.request.toolName)
        assertEquals(BuiltInSkillRuntime.DEVICE_CONTROL_SKILL, replanned.decision.plan.skillRequest?.skillId)
        assertTrue(replanned.steps.none { it is AgentStep.ToolRetryScheduled })
    }

    @Test
    fun successfulObservationCanPlanNextToolAndRequestConfirmationAgain() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将使用 Web 搜索工具查询并整理结果：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val nextDraft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            summary = "搜索完成后继续打开 Wi-Fi 设置页。",
            parameters = emptyMap(),
            requiresConfirmation = true,
        )
        val nextRequest = ToolRequest(
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = nextDraft.summary,
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner { context ->
                if (
                    context.previousRequest.toolName == MobileActionFunctions.WEB_SEARCH &&
                    context.observedResult.status == ToolStatus.Succeeded
                ) {
                    AgentObservationReplan(
                        request = nextRequest,
                        draft = nextDraft,
                        plannedByModel = false,
                        fallbackReason = "test replan",
                    )
                } else {
                    null
                }
            },
        )
        val planned = runtime.runOnce(
            input = "先搜 Kotlin，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val replanned = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已完成 Web 搜索：Kotlin search summary",
                data = webSearchResultData(),
            ),
        )

        requireNotNull(replanned)
        assertEquals(AgentRunState.AwaitingUserConfirmation, replanned.run.state)
        require(replanned.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(nextRequest.id, replanned.decision.plan.request.id)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, replanned.decision.plan.request.toolName)
        assertEquals(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL, replanned.decision.plan.skillRequest?.skillId)
        assertEquals(
            listOf(
                MobileActionFunctions.WEB_SEARCH,
                MobileActionFunctions.OPEN_WIFI_SETTINGS,
            ),
            replanned.steps.filterIsInstance<AgentStep.ToolRequested>().map { it.request.toolName },
        )
        assertEquals(1, replanned.steps.filterIsInstance<AgentStep.UserConfirmationRequested>().size)
        assertTrue(replanned.steps.any { step ->
            step is AgentStep.ObservationDecided &&
                step.decision is AgentObservationDecision.PlanNextTool
        })
        assertEquals(
            listOf(
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ToolObserved,
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ConfirmationRequested,
            ),
            auditSink.events.map { it.eventType },
        )

        runtime.confirmToolRequest(planned.run.id, nextRequest.id)
        val completed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = nextRequest.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_WIFI_SETTINGS),
            ),
        )

        requireNotNull(completed)
        assertEquals(AgentRunState.Completed, completed.run.state)
        assertEquals(AgentObservationDecision.Complete, completed.decision)
    }

    @Test
    fun defaultSequentialReplannerCanAdvanceThroughThreeExplicitActions() {
        val actionRuntime = SequentialStepActionRuntime()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )
        val input = "先搜 Kotlin，然后打开 Wi-Fi 设置，再打开手电筒设置"
        val planned = runtime.runOnce(
            input = input,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.WEB_SEARCH, planned.plan.request.toolName)
        assertEquals(listOf(input), actionRuntime.plannedInputs)

        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val wifiPlanned = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已完成 Web 搜索：Kotlin search summary",
                data = webSearchResultData(),
            ),
        )

        requireNotNull(wifiPlanned)
        assertEquals(AgentRunState.AwaitingUserConfirmation, wifiPlanned.run.state)
        require(wifiPlanned.decision is AgentObservationDecision.PlanNextTool)
        val wifiRequest = wifiPlanned.decision.plan.request
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, wifiRequest.toolName)
        assertEquals("打开 Wi-Fi 设置", actionRuntime.plannedInputs[1])
        assertEquals(1, wifiPlanned.steps.filterIsInstance<AgentStep.UserConfirmationRequested>().size)

        runtime.confirmToolRequest(planned.run.id, wifiRequest.id)
        val flashlightPlanned = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = wifiRequest.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_WIFI_SETTINGS),
            ),
        )

        requireNotNull(flashlightPlanned)
        assertEquals(AgentRunState.AwaitingUserConfirmation, flashlightPlanned.run.state)
        require(flashlightPlanned.decision is AgentObservationDecision.PlanNextTool)
        val flashlightRequest = flashlightPlanned.decision.plan.request
        assertEquals(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS, flashlightRequest.toolName)
        assertEquals("打开手电筒设置", actionRuntime.plannedInputs[2])
        assertEquals(2, flashlightPlanned.steps.filterIsInstance<AgentStep.UserConfirmationRequested>().size)

        runtime.confirmToolRequest(planned.run.id, flashlightRequest.id)
        val completed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = flashlightRequest.id,
                status = ToolStatus.Succeeded,
                summary = "已打开系统设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS),
            ),
        )

        requireNotNull(completed)
        assertEquals(AgentRunState.Completed, completed.run.state)
        assertEquals(AgentObservationDecision.Complete, completed.decision)
        assertEquals(
            listOf(
                MobileActionFunctions.WEB_SEARCH,
                MobileActionFunctions.OPEN_WIFI_SETTINGS,
                MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
            ),
            completed.steps.filterIsInstance<AgentStep.ToolRequested>().map { it.request.toolName },
        )
        assertEquals(3, actionRuntime.plannedInputs.size)
    }

    @Test
    fun roomSequentialReplannerDoesNotRepeatFinalSegmentWhenNextInputClears() {
        val dao = FakeAgentTraceDao()
        val actionRuntime = SequentialStepActionRuntime()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-sequential-room" },
            ),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )
        val input = "先搜 Kotlin，然后打开 Wi-Fi 设置，再打开手电筒设置"
        val planned = runtime.runOnce(
            input = input,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val wifiPlanned = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已完成 Web 搜索：Kotlin search summary",
                data = webSearchResultData(),
            ),
        )
        requireNotNull(wifiPlanned)
        require(wifiPlanned.decision is AgentObservationDecision.PlanNextTool)
        val wifiRequest = wifiPlanned.decision.plan.request

        runtime.confirmToolRequest(planned.run.id, wifiRequest.id)
        val flashlightPlanned = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = wifiRequest.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_WIFI_SETTINGS),
            ),
        )
        requireNotNull(flashlightPlanned)
        require(flashlightPlanned.decision is AgentObservationDecision.PlanNextTool)
        val flashlightRequest = flashlightPlanned.decision.plan.request
        assertEquals(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS, flashlightRequest.toolName)
        assertNull(dao.latestPendingConfirmation()?.nextActionInput)

        runtime.confirmToolRequest(planned.run.id, flashlightRequest.id)
        val completed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = flashlightRequest.id,
                status = ToolStatus.Succeeded,
                summary = "已打开系统设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS),
            ),
        )

        requireNotNull(completed)
        assertEquals(AgentRunState.Completed, completed.run.state)
        assertEquals(AgentObservationDecision.Complete, completed.decision)
        assertEquals(3, actionRuntime.plannedInputs.size)
        assertNull(runtime.latestPendingConfirmation())
    }

    @Test
    fun restoredSequentialPendingUsesContinuationCursorForNoPayloadTailAfterObservation() {
        val dao = FakeAgentTraceDao()
        val actionRuntime = WifiFlashlightActionRuntime()
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-restored-sequence-cursor" },
            ),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )
        val rawInput = "打开 Wi-Fi 设置，然后打开手电筒设置"
        val planned = initialRuntime.runOnce(
            input = rawInput,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, planned.plan.request.toolName)
        val persistedPending = dao.latestPendingConfirmation()
        requireNotNull(persistedPending)
        assertNull(persistedPending.nextActionInput)
        val cursorJson = persistedPending.continuationCursorJson
        requireNotNull(cursorJson)
        assertFalse(cursorJson.contains(rawInput))
        assertFalse(cursorJson.contains("然后"))
        assertEquals(
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
            JSONObject(cursorJson)
                .getJSONObject("request")
                .getString("toolName"),
        )

        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = RoomAgentTraceStore(traceDao = dao),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )
        val restoredPending = restoredRuntime.latestPendingConfirmation()
        requireNotNull(restoredPending)
        assertNull(restoredPending.nextActionInput)
        assertEquals(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS, restoredPending.continuationCursor?.request?.toolName)

        restoredRuntime.confirmToolRequest(restoredPending.run.id, restoredPending.request.id)
        val flashlightPlanned = restoredRuntime.observeToolResult(
            runId = restoredPending.run.id,
            result = ToolResult(
                requestId = restoredPending.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_WIFI_SETTINGS),
            ),
        )

        requireNotNull(flashlightPlanned)
        assertEquals(AgentRunState.AwaitingUserConfirmation, flashlightPlanned.run.state)
        require(flashlightPlanned.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
            flashlightPlanned.decision.plan.request.toolName,
        )
    }

    @Test
    fun payloadSequentialTailDoesNotPersistContinuationCursor() {
        val dao = FakeAgentTraceDao()
        val actionRuntime = RuleActionRuntime()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-payload-sequence-cursor" },
            ),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )

        val planned = runtime.runOnce(
            input = "打开 Wi-Fi 设置，然后搜索 Kotlin",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        require(planned.plan is AgentPlan.UseTool)
        val persistedPending = dao.latestPendingConfirmation()
        requireNotNull(persistedPending)
        assertNull(persistedPending.nextActionInput)
        assertNull(persistedPending.continuationCursorJson)
    }

    @Test
    fun initialSequentialInputPlansFirstSingleToolSegmentThenContinues() {
        val actionRuntime = RuleActionRuntime()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )
        val planned = runtime.runOnce(
            input = "先搜一下 Kotlin，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.WEB_SEARCH, planned.plan.request.toolName)
        assertEquals("Kotlin", planned.plan.request.arguments["query"])

        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val wifiPlanned = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已完成 Web 搜索：Kotlin search summary",
                data = webSearchResultData(),
            ),
        )

        requireNotNull(wifiPlanned)
        assertEquals(AgentRunState.AwaitingUserConfirmation, wifiPlanned.run.state)
        require(wifiPlanned.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, wifiPlanned.decision.plan.request.toolName)
    }

    @Test
    fun initialSequentialCompositeSkillSegmentPlansFirstCompositeSkill() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RuleActionRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = SequentialActionObservationReplanner(RuleActionRuntime()),
        )

        val result = runtime.runOnce(
            input = "总结剪贴板并分享，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, result.plan.request.toolName)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("打开 Wi-Fi 设置", runtime.latestPendingConfirmation()?.nextActionInput)
    }

    @Test
    fun sequentialCompositeSkillSegmentContinuesToNextSegmentAfterInternalToolsComplete() {
        val actionRuntime = RuleActionRuntime()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )
        val planned = runtime.runOnce(
            input = "总结剪贴板并分享，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, planned.plan.request.toolName)

        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observedRead = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = clipboardResultData("只应留在本地模型 continuation 的剪贴板原文"),
            ),
        )
        requireNotNull(observedRead)
        assertEquals(AgentRunState.GeneratingAnswer, observedRead.run.state)
        require(observedRead.decision is AgentObservationDecision.ContinueWithModel)

        val modelObserved = runtime.observeModelResult(planned.run.id, "剪贴板摘要")

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.AwaitingUserConfirmation, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        val sharePlan = modelObserved.decision.plan
        assertEquals(MobileActionFunctions.SHARE_TEXT, sharePlan.request.toolName)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, sharePlan.skillRequest?.skillId)
        assertEquals("打开 Wi-Fi 设置", runtime.latestPendingConfirmation()?.nextActionInput)

        runtime.confirmToolRequest(planned.run.id, sharePlan.request.id)
        val wifiPlanned = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = sharePlan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开系统分享面板",
                data = externalActivityResultData(MobileActionFunctions.SHARE_TEXT),
            ),
        )

        requireNotNull(wifiPlanned)
        assertEquals(AgentRunState.AwaitingUserConfirmation, wifiPlanned.run.state)
        require(wifiPlanned.decision is AgentObservationDecision.PlanNextTool)
        val wifiPlan = wifiPlanned.decision.plan
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, wifiPlan.request.toolName)
        assertEquals(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL, wifiPlan.skillRequest?.skillId)
        assertEquals("打开 Wi-Fi 设置", wifiPlan.skillPlan?.request?.arguments?.get("input"))

        runtime.confirmToolRequest(planned.run.id, wifiPlan.request.id)
        val completed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = wifiPlan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_WIFI_SETTINGS),
            ),
        )

        requireNotNull(completed)
        assertEquals(AgentRunState.Completed, completed.run.state)
        assertEquals(AgentObservationDecision.Complete, completed.decision)
        assertEquals(
            listOf(
                MobileActionFunctions.READ_CLIPBOARD,
                MobileActionFunctions.SHARE_TEXT,
                MobileActionFunctions.OPEN_WIFI_SETTINGS,
            ),
            completed.steps.filterIsInstance<AgentStep.ToolRequested>().map { it.request.toolName },
        )
    }

    @Test
    fun sequentialMiddleCompositeSkillSegmentContinuesToTailAfterInternalToolsComplete() {
        val actionRuntime = WifiFlashlightActionRuntime()
        val auditSink = InMemoryToolAuditSink()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )
        val planned = runtime.runOnce(
            input = "打开 Wi-Fi 设置，然后总结剪贴板并分享，再打开手电筒设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, planned.plan.request.toolName)
        assertEquals("总结剪贴板并分享", runtime.latestPendingConfirmation()?.nextActionInput)

        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val clipboardPlanned = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_WIFI_SETTINGS),
            ),
        )

        requireNotNull(clipboardPlanned)
        assertEquals(AgentRunState.AwaitingUserConfirmation, clipboardPlanned.run.state)
        require(clipboardPlanned.decision is AgentObservationDecision.PlanNextTool)
        val clipboardPlan = clipboardPlanned.decision.plan
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, clipboardPlan.request.toolName)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, clipboardPlan.skillRequest?.skillId)
        assertEquals("打开手电筒设置", runtime.latestPendingConfirmation()?.nextActionInput)

        val rawClipboardText = "中间 composite Skill 的剪贴板原文"
        runtime.confirmToolRequest(planned.run.id, clipboardPlan.request.id)
        val observedClipboard = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = clipboardPlan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = clipboardResultData(rawClipboardText),
            ),
        )
        requireNotNull(observedClipboard)
        assertEquals(AgentRunState.GeneratingAnswer, observedClipboard.run.state)
        require(observedClipboard.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observedClipboard.continuationPromptForModel.orEmpty().contains(rawClipboardText))
        assertEquals("[redacted]", observedClipboard.result.data["text"])
        assertTrue(!observedClipboard.steps.toString().contains(rawClipboardText))
        assertTrue(!auditSink.events.toString().contains(rawClipboardText))

        val localModelSummary = "中间摘要"
        val shareObserved = runtime.observeModelResult(planned.run.id, localModelSummary)

        requireNotNull(shareObserved)
        require(shareObserved.decision is AgentObservationDecision.PlanNextTool)
        val sharePlan = shareObserved.decision.plan
        assertEquals(MobileActionFunctions.SHARE_TEXT, sharePlan.request.toolName)
        assertEquals(localModelSummary, sharePlan.request.arguments["text"])
        assertTrue(!sharePlan.request.arguments.toString().contains(rawClipboardText))
        assertTrue(!runtime.latestPendingConfirmation().toString().contains(rawClipboardText))
        assertEquals("打开手电筒设置", runtime.latestPendingConfirmation()?.nextActionInput)

        runtime.confirmToolRequest(planned.run.id, sharePlan.request.id)
        val flashlightPlanned = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = sharePlan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开系统分享面板",
                data = externalActivityResultData(MobileActionFunctions.SHARE_TEXT),
            ),
        )

        requireNotNull(flashlightPlanned)
        assertEquals(AgentRunState.AwaitingUserConfirmation, flashlightPlanned.run.state)
        require(flashlightPlanned.decision is AgentObservationDecision.PlanNextTool)
        val flashlightPlan = flashlightPlanned.decision.plan
        assertEquals(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS, flashlightPlan.request.toolName)

        runtime.confirmToolRequest(planned.run.id, flashlightPlan.request.id)
        val completed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = flashlightPlan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开手电筒设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS),
            ),
        )

        requireNotNull(completed)
        assertEquals(AgentRunState.Completed, completed.run.state)
        assertEquals(
            listOf(
                MobileActionFunctions.OPEN_WIFI_SETTINGS,
                MobileActionFunctions.READ_CLIPBOARD,
                MobileActionFunctions.SHARE_TEXT,
                MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
            ),
            completed.steps.filterIsInstance<AgentStep.ToolRequested>().map { it.request.toolName },
        )
    }

    @Test
    fun sequentialMiddlePrivateReadSegmentDoesNotPlanWhenTailRemains() {
        val actionRuntime = WifiFlashlightActionRuntime()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )
        val planned = runtime.runOnce(
            input = "打开 Wi-Fi 设置，然后读取剪贴板，再打开手电筒设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, planned.plan.request.toolName)

        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_WIFI_SETTINGS),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Completed, observed.run.state)
        assertEquals(AgentObservationDecision.Complete, observed.decision)
        assertTrue(observed.steps.none { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.toolName == MobileActionFunctions.READ_CLIPBOARD
        })
    }

    @Test
    fun initialSequentialPrivateReadSegmentFallsBackToAnswer() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RuleActionRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = SequentialActionObservationReplanner(RuleActionRuntime()),
        )

        val result = runtime.runOnce(
            input = "读取剪贴板，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.GeneratingAnswer, result.run.state)
        require(result.plan is AgentPlan.Answer)
        assertTrue(result.steps.none { it is AgentStep.UserConfirmationRequested })
    }

    @Test
    fun sequentialReplannerSkipsPrivateReadWhenMoreSegmentsRemain() {
        val actionRuntime = RuleActionRuntime()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )
        val planned = runtime.runOnce(
            input = "先搜一下 Kotlin，然后读取剪贴板，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.WEB_SEARCH, planned.plan.request.toolName)

        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已完成 Web 搜索：Kotlin search summary",
                data = webSearchResultData(),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertFalse(observed.decision.requiresLocalModel)
        assertTrue(observed.steps.none { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.toolName == MobileActionFunctions.READ_CLIPBOARD
        })
    }

    @Test
    fun sequentialReplannerAllowsFinalPrivateReadSegment() {
        val actionRuntime = RuleActionRuntime()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )
        val planned = runtime.runOnce(
            input = "先搜一下 Kotlin，然后读取剪贴板",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.WEB_SEARCH, planned.plan.request.toolName)

        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已完成 Web 搜索：Kotlin search summary",
                data = webSearchResultData(),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.AwaitingUserConfirmation, observed.run.state)
        require(observed.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, observed.decision.plan.request.toolName)
        assertTrue(observed.steps.none { it is AgentStep.ExternalOutcomeConfirmed })
    }

    @Test
    fun explanatorySequentialTextStillFallsBackToAnswer() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RuleActionRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = SequentialActionObservationReplanner(RuleActionRuntime()),
        )

        val result = runtime.runOnce(
            input = "解释一下先搜索再打开设置这个流程怎么实现",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.GeneratingAnswer, result.run.state)
        require(result.plan is AgentPlan.Answer)
        assertTrue(result.steps.none { it is AgentStep.UserConfirmationRequested })
    }

    @Test
    fun unverifiedExternalLaunchDoesNotAutoPlanNextTool() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.OPEN_DEEP_LINK,
                        title = "打开深链",
                        summary = "将打开示例网页。",
                        parameters = mapOf("uri" to "https://example.com/search?q=Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val nextDraft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            summary = "不应自动规划。",
            parameters = emptyMap(),
            requiresConfirmation = true,
        )
        var replanCallCount = 0
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner {
                replanCallCount += 1
                AgentObservationReplan(
                    request = ToolRequest(
                        toolName = nextDraft.functionName,
                        reason = nextDraft.summary,
                    ),
                    draft = nextDraft,
                    fallbackReason = "should not run",
                )
            },
        )
        val planned = runtime.runOnce(
            input = "先打开网页，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开深链",
                data = externalActivityResultData(
                    toolName = MobileActionFunctions.OPEN_DEEP_LINK,
                    completionVerified = false,
                    externalOutcome = "Unknown",
                    externalOutcomeSource = "Unknown",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.AwaitingExternalOutcome, observed.run.state)
        assertEquals(AgentObservationDecision.Complete, observed.decision)
        assertEquals(0, replanCallCount)
        assertTrue(observed.assistantMessage.startsWith(UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX))
        assertEquals(1, observed.steps.filterIsInstance<AgentStep.ToolRequested>().size)
        assertTrue(observed.steps.none { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.toolName == MobileActionFunctions.OPEN_WIFI_SETTINGS
        })
        val observedAudit = auditSink.events.last { event ->
            event.eventType == ToolAuditEventType.ToolObserved
        }
        assertTrue(observedAudit.summary.startsWith(UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX))
    }

    @Test
    fun completedExternalOutcomeConfirmationCanPlanNextTool() {
        val auditSink = InMemoryToolAuditSink()
        val nextDraft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            summary = "用户已确认搜索完成，可以继续。",
            parameters = emptyMap(),
            requiresConfirmation = true,
        )
        var replanCallCount = 0
        val runtime = externalOutcomeRuntime(
            auditSink = auditSink,
            onReplan = {
                replanCallCount += 1
                AgentObservationReplan(
                    request = ToolRequest(
                        toolName = nextDraft.functionName,
                        reason = nextDraft.summary,
                    ),
                    draft = nextDraft,
                    fallbackReason = "after external completion",
                )
            },
        )
        val planned = runtime.runOnce(
            input = "先打开网页，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val opened = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开深链",
                data = externalActivityResultData(
                    toolName = MobileActionFunctions.OPEN_DEEP_LINK,
                    completionVerified = false,
                    externalOutcome = "Unknown",
                    externalOutcomeSource = "Unknown",
                ),
            ),
        )
        requireNotNull(opened)
        assertEquals(0, replanCallCount)

        val confirmed = runtime.recordExternalOutcome(
            runId = planned.run.id,
            requestId = planned.plan.request.id,
            outcome = AgentExternalOutcome.Completed,
        )

        requireNotNull(confirmed)
        assertEquals(1, replanCallCount)
        assertEquals(AgentRunState.AwaitingUserConfirmation, confirmed.run.state)
        require(confirmed.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, confirmed.decision.plan.request.toolName)
        assertEquals("true", confirmed.result.data["completionVerified"])
        assertEquals("Completed", confirmed.result.data["externalOutcome"])
        assertEquals("UserConfirmed", confirmed.result.data["externalOutcomeSource"])
        assertTrue(confirmed.steps.any { step ->
            step is AgentStep.ExternalOutcomeConfirmed &&
                step.outcome == AgentExternalOutcome.Completed
        })
        assertTrue(auditSink.events.any { event ->
            event.eventType == ToolAuditEventType.ExternalOutcomeConfirmed
        })
    }

    @Test
    fun terminalRunRejectsLateExternalOutcomeConfirmation() {
        val auditSink = InMemoryToolAuditSink()
        var replanCallCount = 0
        val runtime = externalOutcomeRuntime(
            auditSink = auditSink,
            onReplan = {
                replanCallCount += 1
                AgentObservationReplan(
                    request = ToolRequest(
                        toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        reason = "should not run",
                    ),
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        title = "打开 Wi-Fi 设置",
                        summary = "不应自动规划。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                    fallbackReason = "late external completion",
                )
            },
        )
        val planned = runtime.runOnce(
            input = "先打开网页，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val opened = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开深链",
                data = externalActivityResultData(
                    toolName = MobileActionFunctions.OPEN_DEEP_LINK,
                    completionVerified = false,
                    externalOutcome = "Unknown",
                    externalOutcomeSource = "Unknown",
                ),
            ),
        )
        requireNotNull(opened)
        assertEquals(AgentRunState.AwaitingExternalOutcome, opened.run.state)

        val cancelled = runtime.cancelRun(planned.run.id, "user stopped")
        requireNotNull(cancelled)
        assertEquals(AgentRunState.Cancelled, cancelled.run.state)

        val lateOutcome = runtime.recordExternalOutcome(
            runId = planned.run.id,
            requestId = planned.plan.request.id,
            outcome = AgentExternalOutcome.Completed,
        )

        assertNull(lateOutcome)
        assertEquals(0, replanCallCount)
        assertFalse(auditSink.events.any { event ->
            event.eventType == ToolAuditEventType.ExternalOutcomeConfirmed
        })
    }

    @Test
    fun notCompletedExternalOutcomeConfirmationDoesNotPlanNextTool() {
        val auditSink = InMemoryToolAuditSink()
        var replanCallCount = 0
        val runtime = externalOutcomeRuntime(
            auditSink = auditSink,
            onReplan = {
                replanCallCount += 1
                AgentObservationReplan(
                    request = ToolRequest(
                        toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        reason = "should not run",
                    ),
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        title = "打开 Wi-Fi 设置",
                        summary = "不应自动规划。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                    fallbackReason = "should not run",
                )
            },
        )
        val planned = runtime.runOnce(
            input = "先打开网页，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开深链",
                data = externalActivityResultData(
                    toolName = MobileActionFunctions.OPEN_DEEP_LINK,
                    completionVerified = false,
                    externalOutcome = "Unknown",
                    externalOutcomeSource = "Unknown",
                ),
            ),
        )

        val confirmed = runtime.recordExternalOutcome(
            runId = planned.run.id,
            requestId = planned.plan.request.id,
            outcome = AgentExternalOutcome.NotCompleted,
        )

        requireNotNull(confirmed)
        assertEquals(0, replanCallCount)
        assertEquals(AgentRunState.Completed, confirmed.run.state)
        assertEquals(AgentObservationDecision.Complete, confirmed.decision)
        assertEquals("false", confirmed.result.data["completionVerified"])
        assertEquals("NotCompleted", confirmed.result.data["externalOutcome"])
        assertEquals("UserConfirmed", confirmed.result.data["externalOutcomeSource"])
        assertTrue(confirmed.steps.none { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.toolName == MobileActionFunctions.OPEN_WIFI_SETTINGS
        })
    }

    @Test
    fun restoredUnverifiedExternalLaunchRestoresPendingOutcomeAndRecordsConfirmation() {
        val dao = FakeAgentTraceDao()
        val traceStore = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { 1_000L },
            runIdFactory = { "run-restored-external-outcome" },
        )
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(
                likelyAction = true,
                planningResult = ActionPlanningResult(
                    plan = ActionPlan(
                        kind = ActionPlanKind.Draft,
                        draft = ActionDraft(
                            functionName = MobileActionFunctions.OPEN_DEEP_LINK,
                            title = "打开深链",
                            summary = "将打开示例网页。",
                            parameters = mapOf("uri" to "https://example.com/search?q=Kotlin"),
                            requiresConfirmation = true,
                        ),
                    ),
                    usedModel = false,
                    fallbackReason = "test fallback",
                ),
            ),
            traceStore = traceStore,
        )
        val planned = initialRuntime.runOnce(
            input = "打开示例网页",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            sessionId = "session-restored",
        )
        require(planned.plan is AgentPlan.UseTool)
        initialRuntime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val opened = initialRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开深链",
                data = externalActivityResultData(
                    toolName = MobileActionFunctions.OPEN_DEEP_LINK,
                    completionVerified = false,
                    externalOutcome = "Unknown",
                    externalOutcomeSource = "Unknown",
                ),
            ),
        )
        requireNotNull(opened)
        assertEquals(AgentRunState.AwaitingExternalOutcome, opened.run.state)

        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = RoomAgentTraceStore(traceDao = dao),
        )
        val pending = restoredRuntime.latestPendingExternalOutcome("session-restored")

        requireNotNull(pending)
        assertEquals(AgentRunState.AwaitingExternalOutcome.name, dao.run(planned.run.id)?.state)
        assertEquals(planned.run.id, pending.runId)
        assertEquals(planned.plan.request.id, pending.requestId)
        assertEquals(MobileActionFunctions.OPEN_DEEP_LINK, pending.toolName)
        assertEquals("打开深链", pending.title)
        assertTrue(pending.summary.startsWith(UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX))

        val confirmed = restoredRuntime.recordExternalOutcome(
            runId = pending.runId,
            requestId = pending.requestId,
            outcome = AgentExternalOutcome.Completed,
        )

        requireNotNull(confirmed)
        assertEquals("true", confirmed.result.data["completionVerified"])
        assertEquals("Completed", confirmed.result.data["externalOutcome"])
        assertEquals("UserConfirmed", confirmed.result.data["externalOutcomeSource"])
        assertEquals(null, restoredRuntime.latestPendingExternalOutcome("session-restored"))
    }

    @Test
    fun failStaleInFlightRunsClosesUnrestorableExternalOutcomeBeforeRestore() {
        val dao = FakeAgentTraceDao()
        val traceStore = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { 1_000L },
            runIdFactory = { "run-unrestorable-external-runtime" },
        )
        val run = traceStore.createRun("share text", sessionId = "session-unrestorable")
        val requestId = "request-share"
        dao.insertStep(
            AgentStepEntity(
                runId = run.id,
                position = 0,
                type = "ToolRequested",
                summary = "Requested tool share_text.",
                json = """{"type":"ToolRequested","requestId":"$requestId"}""",
                createdAtMillis = 1_000L,
            ),
        )
        traceStore.appendStep(
            run.id,
            AgentStep.ToolObserved(
                ToolResult(
                    requestId = requestId,
                    status = ToolStatus.Succeeded,
                    summary = "已打开系统分享面板",
                    data = externalActivityResultData(
                        toolName = MobileActionFunctions.SHARE_TEXT,
                        completionVerified = false,
                        externalOutcome = "Unknown",
                        externalOutcomeSource = "Unknown",
                    ),
                ),
            ),
        )
        traceStore.updateState(run.id, AgentRunState.AwaitingExternalOutcome)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = RoomAgentTraceStore(traceDao = dao),
        )

        val failedCount = runtime.failStaleInFlightRuns("process restarted")

        assertEquals(1, failedCount)
        assertEquals(AgentRunState.Failed.name, dao.run(run.id)?.state)
        assertNull(runtime.latestPendingExternalOutcome("session-unrestorable"))
    }

    @Test
    fun restoredExternalOutcomeUsesContinuationCursorForNoPayloadTailAfterCompletion() {
        val dao = FakeAgentTraceDao()
        val actionRuntime = WifiFlashlightActionRuntime()
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-restored-external-sequence-cursor" },
            ),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )
        val rawInput = "打开 Wi-Fi 设置，然后打开手电筒设置"
        val planned = initialRuntime.runOnce(
            input = rawInput,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            sessionId = "session-restored-external",
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, planned.plan.request.toolName)

        initialRuntime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val opened = initialRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(
                    toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                    completionVerified = false,
                    externalOutcome = "Unknown",
                    externalOutcomeSource = "Unknown",
                ),
            ),
        )

        requireNotNull(opened)
        assertEquals(AgentRunState.AwaitingUserConfirmation, opened.run.state)
        require(opened.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
            opened.decision.plan.request.toolName,
        )
        val cursorStep = dao.steps(planned.run.id).lastOrNull { step ->
            step.type == "ContinuationCursorRecorded"
        }
        requireNotNull(cursorStep)
        assertFalse(cursorStep.json.contains(rawInput))
        assertFalse(cursorStep.json.contains("然后"))
        assertEquals(
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
            JSONObject(cursorStep.json).getString("targetToolName"),
        )

        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = RoomAgentTraceStore(traceDao = dao),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )
        assertNull(restoredRuntime.latestPendingExternalOutcome("session-restored-external"))
        val nextPending = dao.latestPendingConfirmation()
        requireNotNull(nextPending)
        assertEquals(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS, nextPending.toolName)
        assertNull(nextPending.nextActionInput)
    }

    @Test
    fun restoredExternalOutcomeDoesNotContinuePayloadTailWithoutContinuationCursor() {
        val dao = FakeAgentTraceDao()
        val actionRuntime = RuleActionRuntime()
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-restored-external-payload-tail" },
            ),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )
        val planned = initialRuntime.runOnce(
            input = "打开 Wi-Fi 设置，然后搜索 Kotlin",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            sessionId = "session-restored-external-payload",
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, planned.plan.request.toolName)

        initialRuntime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val opened = initialRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(
                    toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                    completionVerified = false,
                    externalOutcome = "Unknown",
                    externalOutcomeSource = "Unknown",
                ),
            ),
        )
        requireNotNull(opened)
        assertEquals(AgentRunState.Completed, opened.run.state)
        assertEquals(AgentObservationDecision.Complete, opened.decision)
        assertTrue(dao.steps(planned.run.id).none { step ->
            step.type == "ContinuationCursorRecorded"
        })

        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = RoomAgentTraceStore(traceDao = dao),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )
        assertNull(restoredRuntime.latestPendingExternalOutcome("session-restored-external-payload"))
        assertNull(dao.latestPendingConfirmation())
    }

    @Test
    fun replannedToolCannotReuseExistingRequestId() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将使用 Web 搜索工具查询并整理结果：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner { context ->
                AgentObservationReplan(
                    request = context.previousRequest,
                    draft = ActionDraft(
                        functionName = context.previousRequest.toolName,
                        title = "重复请求",
                        summary = "不应复用已有 request id。",
                        parameters = context.previousRequest.arguments,
                        requiresConfirmation = true,
                    ),
                )
            },
        )
        val planned = runtime.runOnce(
            input = "搜一下 Kotlin",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已完成 Web 搜索：Kotlin search summary",
                data = webSearchResultData(),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        require(observed.decision is AgentObservationDecision.Fail)
        assertTrue(observed.decision.reason.contains("already exists"))
        assertTrue(observed.steps.any { step ->
            step is AgentStep.ModelPlanned && step.plan is AgentPlan.RejectedTool
        })
        assertTrue(observed.steps.any { it is AgentStep.ToolRejected })
    }

    @Test
    fun restoredPendingConfirmationRejectsReplannedOldRequestId() {
        val dao = FakeAgentTraceDao()
        val searchActionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将使用 Web 搜索工具查询并整理结果：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val currentDraft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            summary = "搜索完成后继续打开 Wi-Fi 设置页。",
            parameters = emptyMap(),
            requiresConfirmation = true,
        )
        val currentRequest = ToolRequest(
            id = "request-current-after-restore",
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = currentDraft.summary,
        )
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = searchActionRuntime,
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-restored-duplicate" },
            ),
            observationReplanner = AgentObservationReplanner { context ->
                if (context.previousRequest.toolName == MobileActionFunctions.WEB_SEARCH) {
                    AgentObservationReplan(
                        request = currentRequest,
                        draft = currentDraft,
                        plannedByModel = false,
                        fallbackReason = "test replan",
                    )
                } else {
                    null
                }
            },
        )
        val planned = initialRuntime.runOnce(
            input = "搜一下 Kotlin",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        val oldRequest = planned.plan.request
        initialRuntime.confirmToolRequest(planned.run.id, oldRequest.id)
        val currentPlanned = initialRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = oldRequest.id,
                status = ToolStatus.Succeeded,
                summary = "已完成 Web 搜索：Kotlin search summary",
                data = webSearchResultData(),
            ),
        )
        requireNotNull(currentPlanned)
        assertEquals(AgentRunState.AwaitingUserConfirmation, currentPlanned.run.state)
        require(currentPlanned.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(currentRequest.id, currentPlanned.decision.plan.request.id)

        val restoredTraceStore = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { 2_000L },
        )
        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = searchActionRuntime,
            traceStore = restoredTraceStore,
            observationReplanner = AgentObservationReplanner {
                AgentObservationReplan(
                    request = oldRequest,
                    draft = ActionDraft(
                        functionName = oldRequest.toolName,
                        title = "重复请求",
                        summary = "不应复用已有 request id。",
                        parameters = oldRequest.arguments,
                        requiresConfirmation = true,
                    ),
                    plannedByModel = false,
                    fallbackReason = "duplicate old request",
                )
            },
        )
        val restoredPending = restoredRuntime.latestPendingConfirmation()
        requireNotNull(restoredPending)
        assertEquals(currentRequest.id, restoredPending.request.id)
        assertEquals(emptyMap<String, String>(), restoredPending.request.arguments)
        assertEquals(null, restoredPending.nextActionInput)
        assertTrue(restoredTraceStore.steps(planned.run.id).any { step ->
            step is AgentStep.ToolRequested &&
                step.request.id == oldRequest.id &&
                step.request.arguments.isEmpty()
        })

        restoredRuntime.confirmToolRequest(planned.run.id, currentRequest.id)
        val observed = restoredRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = currentRequest.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_WIFI_SETTINGS),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        require(observed.decision is AgentObservationDecision.Fail)
        assertTrue(observed.decision.reason.contains("already exists"))
        assertTrue(observed.decision.reason.contains(oldRequest.id))
        assertTrue(observed.steps.any { step ->
            step is AgentStep.ModelPlanned && step.plan is AgentPlan.RejectedTool
        })
        assertTrue(observed.steps.any { it is AgentStep.ToolRejected })
        assertNull(restoredRuntime.latestPendingConfirmation())
    }

    @Test
    fun retryableToolFailureSchedulesSingleRetryThenFailsAfterLimit() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            maxToolRetryAttempts = 1,
        )
        val planned = runtime.runOnce(
            input = "搜一下 Kotlin",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.WEB_SEARCH, planned.plan.request.toolName)

        val retrying = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Failed,
                summary = "浏览器临时不可用",
                retryable = true,
            ),
        )

        requireNotNull(retrying)
        assertEquals(AgentRunState.RetryingTool, retrying.run.state)
        require(retrying.decision is AgentObservationDecision.RetryTool)
        assertEquals(1, retrying.decision.attempt)
        assertEquals(planned.plan.request, retrying.retryRequest)
        assertEquals(1, retrying.retryAttempt)
        assertTrue(retrying.assistantMessage.contains("正在重试"))
        val retryStep = retrying.steps.filterIsInstance<AgentStep.ToolRetryScheduled>().single()
        assertEquals(planned.plan.request.id, retryStep.request.id)
        assertEquals(1, retryStep.attempt)
        assertTrue(auditSink.events.any { event ->
            event.eventType == ToolAuditEventType.ToolRetryScheduled &&
                event.requestId == planned.plan.request.id
        })

        val failed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Failed,
                summary = "重试后仍不可用",
                retryable = true,
            ),
        )

        requireNotNull(failed)
        assertEquals(AgentRunState.Failed, failed.run.state)
        require(failed.decision is AgentObservationDecision.Fail)
        assertNull(failed.retryRequest)
        assertEquals(0, failed.retryAttempt)
        assertEquals(1, failed.steps.filterIsInstance<AgentStep.ToolRetryScheduled>().size)
        assertTrue(!failed.assistantMessage.contains("正在重试"))
        assertTrue(failed.assistantMessage.contains("下一步"))
    }

    @Test
    fun retryableLocalEvidenceToolFailureDoesNotScheduleAutomaticRetry() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = ForegroundThenWifiActionRuntime()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
            maxToolRetryAttempts = 1,
        )
        val input = "当前应用是什么，然后打开 Wi-Fi 设置"
        val planned = runtime.runOnce(
            input = input,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.QUERY_FOREGROUND_APP, planned.plan.request.toolName)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val retrying = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Failed,
                summary = "Usage stats provider temporarily unavailable",
                retryable = true,
            ),
        )

        requireNotNull(retrying)
        assertEquals(AgentRunState.Failed, retrying.run.state)
        require(retrying.decision is AgentObservationDecision.Fail)
        assertNull(retrying.retryRequest)
        assertEquals(0, retrying.retryAttempt)
        assertNull(runtime.latestPendingConfirmation())
        assertEquals(listOf(input), actionRuntime.plannedInputs)
        assertTrue(retrying.steps.none { it is AgentStep.ToolRetryScheduled })
        assertTrue(auditSink.events.none { event ->
            event.eventType == ToolAuditEventType.ToolRetryScheduled
        })
    }

    @Test
    fun permissionDeniedToolFailureDoesNotScheduleAutomaticRetry() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "当前应用是什么",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val failed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Failed,
                summary = "用户拒绝了所需权限",
                error = ToolError(ToolErrorCode.PermissionDenied, "用户拒绝了所需权限"),
                retryable = true,
            ),
        )

        requireNotNull(failed)
        assertEquals(AgentRunState.Failed, failed.run.state)
        require(failed.decision is AgentObservationDecision.Fail)
        assertNull(failed.retryRequest)
        assertEquals(0, failed.retryAttempt)
        assertTrue(failed.steps.none { it is AgentStep.ToolRetryScheduled })
    }

    @Test
    fun retryableSideEffectToolFailuresDoNotScheduleAutomaticRetry() {
        val cases = listOf(
            "分享这段文字：hello" to MobileActionFunctions.SHARE_TEXT,
            "提醒我 15 分钟后喝水" to MobileActionFunctions.SCHEDULE_REMINDER,
            "开启周期检查，每 2 小时" to MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
        )

        cases.forEach { (input, toolName) ->
            val runtime = AgentLoopRuntime(
                memoryIndex = MemoryRepository(),
                actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
                traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
                maxToolRetryAttempts = 1,
            )
            val planned = runtime.runOnce(
                input = input,
                installedCapabilities = setOf(ModelCapability.Chat),
                memoryEnabled = false,
            )
            require(planned.plan is AgentPlan.UseTool)
            assertEquals(toolName, planned.plan.request.toolName)
            runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

            val failed = runtime.observeToolResult(
                runId = planned.run.id,
                result = ToolResult(
                    requestId = planned.plan.request.id,
                    status = ToolStatus.Failed,
                    summary = "底层临时失败",
                    retryable = true,
                ),
            )

            requireNotNull(failed)
            assertEquals(AgentRunState.Failed, failed.run.state)
            require(failed.decision is AgentObservationDecision.Fail)
            assertNull(failed.retryRequest)
            assertEquals(0, failed.retryAttempt)
            assertTrue(failed.steps.none { it is AgentStep.ToolRetryScheduled })
        }
    }

    @Test
    fun pendingToolPermissionDenialIsObservedWithoutEnteringExecutionState() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        title = "打开 Wi-Fi 设置",
                        summary = "将打开系统 Wi-Fi 设置页。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)

        val failed = runtime.failPendingToolRequest(
            runId = planned.run.id,
            requestId = planned.plan.request.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Failed,
                summary = "用户拒绝了所需权限",
                error = ToolError(ToolErrorCode.PermissionDenied, "用户拒绝了所需权限"),
                retryable = false,
            ),
        )

        requireNotNull(failed)
        assertEquals(AgentRunState.Failed, failed.run.state)
        require(failed.decision is AgentObservationDecision.Fail)
        assertTrue(failed.steps.any { it is AgentStep.UserConfirmed })
        assertTrue(failed.steps.any { step ->
            step is AgentStep.ToolObserved &&
                step.result.error?.code == ToolErrorCode.PermissionDenied
        })
        assertTrue(failed.steps.none { it is AgentStep.ToolRetryScheduled })
        assertNull(runtime.latestPendingConfirmation())
        assertTrue(auditSink.events.any { event ->
            event.eventType == ToolAuditEventType.ToolObserved &&
                event.status == ToolStatus.Failed
        })
    }

    @Test
    fun nonRetryableToolFailureFailsWithoutRetryStep() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        title = "打开 Wi-Fi 设置",
                        summary = "将打开系统 Wi-Fi 设置页。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val failed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Failed,
                summary = "找不到可处理的设置页",
                retryable = false,
            ),
        )

        requireNotNull(failed)
        assertEquals(AgentRunState.Failed, failed.run.state)
        require(failed.decision is AgentObservationDecision.Fail)
        assertNull(failed.retryRequest)
        assertTrue(failed.steps.none { it is AgentStep.ToolRetryScheduled })
        assertTrue(failed.assistantMessage.contains("下一步"))
    }

    @Test
    fun lowConfidenceActionIntentDoesNotEnterToolPlanning() {
        val actionRuntime = LowConfidenceActionRuntime()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "也许打开 Wi-Fi 设置？",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        require(result.plan is AgentPlan.Answer)
        assertEquals(0, actionRuntime.planCallCount)
    }

    @Test
    fun cancelledToolRequestIsObservedWithoutExecution() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        title = "打开 Wi-Fi 设置",
                        summary = "将打开系统 Wi-Fi 设置页。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)

        val cancelled = runtime.cancelToolRequest(
            runId = planned.run.id,
            requestId = planned.plan.request.id,
        )

        requireNotNull(cancelled)
        assertEquals(AgentRunState.Cancelled, cancelled.run.state)
        assertEquals(AgentObservationDecision.Cancel, cancelled.decision)
        assertEquals(ToolStatus.Cancelled, cancelled.result.status)
        assertTrue(cancelled.steps.any { it is AgentStep.UserRejected })
        assertTrue(cancelled.steps.any { it is AgentStep.ToolObserved })
        assertEquals(
            listOf(
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ConfirmationRequested,
                ToolAuditEventType.UserCancelled,
                ToolAuditEventType.ToolObserved,
            ),
            auditSink.events.map { it.eventType },
        )
    }

    @Test
    fun cancelGeneratingRunMarksCancelledAndIgnoresLateModelOutput() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "普通问题",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        val cancelled = runtime.cancelRun(planned.run.id, "user stopped generation")
        val lateOutput = runtime.observeModelResult(planned.run.id, "迟到的模型输出")

        requireNotNull(cancelled)
        assertEquals(AgentRunState.Cancelled, cancelled.run.state)
        assertEquals(AgentObservationDecision.Cancel, cancelled.decision)
        assertTrue(cancelled.steps.any { step ->
            step is AgentStep.ObservationDecided &&
                step.decision == AgentObservationDecision.Cancel
        })
        assertTrue(cancelled.steps.none { it is AgentStep.Failed })
        assertNull(lateOutput)
    }

    @Test
    fun cancelRunAwaitingConfirmationClearsPendingWithoutExecutingTool() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        title = "打开 Wi-Fi 设置",
                        summary = "将打开系统 Wi-Fi 设置页。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)

        val cancelled = runtime.cancelRun(planned.run.id, "user stopped run")

        requireNotNull(cancelled)
        assertEquals(AgentRunState.Cancelled, cancelled.run.state)
        assertEquals(AgentObservationDecision.Cancel, cancelled.decision)
        assertNull(runtime.latestPendingConfirmation())
        assertTrue(cancelled.steps.any { it is AgentStep.UserRejected })
        assertTrue(cancelled.steps.any { step ->
            step is AgentStep.ToolObserved &&
                step.result.status == ToolStatus.Cancelled
        })
        assertEquals(
            listOf(
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ConfirmationRequested,
                ToolAuditEventType.UserCancelled,
                ToolAuditEventType.ToolObserved,
            ),
            auditSink.events.map { it.eventType },
        )
    }

    @Test
    fun runBudgetExceededFailsBeforeNextToolConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将使用 Web 搜索工具查询并整理结果：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val nextDraft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            summary = "搜索完成后继续打开 Wi-Fi 设置页。",
            parameters = emptyMap(),
            requiresConfirmation = true,
        )
        val nextRequest = ToolRequest(
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = nextDraft.summary,
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner {
                AgentObservationReplan(
                    request = nextRequest,
                    draft = nextDraft,
                    fallbackReason = "test replan",
                )
            },
            maxRunToolSteps = 1,
        )
        val planned = runtime.runOnce(
            input = "先搜 Kotlin，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已完成搜索",
                data = webSearchResultData(summaryText = "已完成搜索"),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        require(observed.decision is AgentObservationDecision.Fail)
        assertTrue(
            "Unexpected failure reason: ${observed.decision.reason}",
            observed.decision.reason.contains("budget exceeded"),
        )
        assertNull(runtime.latestPendingConfirmation())
        assertTrue(observed.steps.any { step ->
            step is AgentStep.Failed &&
                step.reason.contains("budget exceeded")
        })
        assertEquals(0, observed.steps.filterIsInstance<AgentStep.UserConfirmationRequested>().size)
    }

    @Test
    fun invalidActionDraftIsRejectedBeforeConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.COMPOSE_EMAIL,
                        title = "邮件草稿",
                        summary = "将打开邮件 App 并填入草稿内容。",
                        parameters = mapOf("subject" to "Hi"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = true,
                fallbackReason = null,
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "帮我写邮件",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            installedCapabilityProfiles = listOf(ModelCatalog.profileForModelId(MOBILE_ACTION_MODEL_ID)),
        )

        assertEquals(AgentRunState.Failed, result.run.state)
        require(result.plan is AgentPlan.RejectedTool)
        assertEquals(ToolStatus.Rejected, result.plan.result.status)
        assertTrue(result.plan.result.summary.contains("body"))
        assertTrue(result.steps.any { step ->
            step is AgentStep.ToolRejected &&
                step.result.status == ToolStatus.Rejected
        })
    }

    @Test
    fun modelBackedActionPlanningWithoutMobileActionReturnsMissingModel() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = modelBackedWifiPlanningResult(),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            actionModelPath = "/verified/action-model.task",
        )

        assertEquals(AgentRunState.Failed, result.run.state)
        require(result.plan is AgentPlan.MissingModel)
        assertEquals(ModelCapability.MobileAction, result.plan.capability)
        assertEquals(1, actionRuntime.planCallCount)
        assertTrue(result.steps.any { step ->
            step is AgentStep.ModelPlanned &&
                step.plan == AgentPlan.MissingModel(ModelCapability.MobileAction)
        })
        assertTrue(result.steps.none { it is AgentStep.UserConfirmationRequested })
        assertNull(runtime.latestPendingConfirmation())
    }

    @Test
    fun modelBackedActionPlanningPrefersCapabilityProfilesOverCapabilitySet() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = modelBackedWifiPlanningResult(),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat, ModelCapability.MobileAction),
            memoryEnabled = false,
            actionModelPath = "/verified/action-model.task",
            installedCapabilityProfiles = listOf(ModelCatalog.profileForModelId(MEMORY_EMBEDDING_MODEL_ID)),
        )

        assertEquals(AgentRunState.Failed, result.run.state)
        require(result.plan is AgentPlan.MissingModel)
        assertEquals(ModelCapability.MobileAction, result.plan.capability)
        assertEquals(1, actionRuntime.planCallCount)
        assertTrue(result.steps.none { it is AgentStep.UserConfirmationRequested })
        assertNull(runtime.latestPendingConfirmation())
    }

    @Test
    fun ruleBackedActionPlanningDoesNotRequireMobileAction() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = wifiPlanningResult(),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, result.plan.request.toolName)
        assertFalse(result.plan.plannedByModel)
        assertEquals("test fallback", result.plan.fallbackReason)
        assertEquals(1, actionRuntime.planCallCount)
    }

    @Test
    fun modelBackedActionPlanningWorksWhenMobileActionInstalled() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = modelBackedWifiPlanningResult(),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            actionModelPath = "/verified/action-model.task",
            installedCapabilityProfiles = listOf(ModelCatalog.profileForModelId(MOBILE_ACTION_MODEL_ID)),
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, result.plan.request.toolName)
        assertTrue(result.plan.plannedByModel)
        assertNull(result.plan.fallbackReason)
        assertEquals(1, actionRuntime.planCallCount)
    }

    @Test
    fun actionPlannerAttachedSkillPlanMustSatisfyManifestSchemaBeforeConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将使用 Web 搜索工具查询并整理结果：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = InvalidSkillRuntime(
                invalidForTool = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "Kotlin"),
            ),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "搜一下 Kotlin",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.Failed, result.run.state)
        require(result.plan is AgentPlan.RejectedTool)
        assertTrue(result.plan.result.summary.contains("Invalid skill plan"))
        assertTrue(result.plan.result.summary.contains("requires argument(s): input"))
        assertTrue(result.plan.result.summary.contains("does not accept argument(s): query"))
        assertTrue(result.steps.none { it is AgentStep.UserConfirmationRequested })
        assertNull(runtime.latestPendingConfirmation())
    }

    @Test
    fun replannedToolAttachedSkillPlanMustSatisfyManifestSchemaBeforeConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将使用 Web 搜索工具查询并整理结果：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val nextDraft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            summary = "搜索完成后继续打开 Wi-Fi 设置页。",
            parameters = emptyMap(),
            requiresConfirmation = true,
        )
        val nextRequest = ToolRequest(
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = nextDraft.summary,
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = InvalidSkillRuntime(
                invalidForTool = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                arguments = emptyMap(),
            ),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner {
                AgentObservationReplan(
                    request = nextRequest,
                    draft = nextDraft,
                    fallbackReason = "test replan",
                )
            },
        )
        val planned = runtime.runOnce(
            input = "先搜 Kotlin，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已完成 Web 搜索：Kotlin search summary",
                data = webSearchResultData(),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        require(observed.decision is AgentObservationDecision.Fail)
        assertTrue(observed.decision.reason.contains("Invalid skill plan"))
        assertTrue(observed.steps.any { it is AgentStep.ToolRejected })
        assertTrue(observed.steps.none { it is AgentStep.UserConfirmationRequested })
        assertNull(runtime.latestPendingConfirmation())
    }

    @Test
    fun modelBackedObservationReplanWithoutMobileActionReturnsMissingModel() {
        val actionRuntime = ObservationModelActionRuntime(
            initialDraft = ActionDraft(
                functionName = MobileActionFunctions.WEB_SEARCH,
                title = "Web 搜索",
                summary = "将使用 Web 搜索工具查询并整理结果：Kotlin",
                parameters = mapOf("query" to "Kotlin"),
                requiresConfirmation = true,
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = ModelObservationReplanner(
                actionPlanningRuntime = actionRuntime,
                actionModelPathProvider = { "/verified/mobile-action.litertlm" },
            ),
        )
        val planned = runtime.runOnce(
            input = "搜 Kotlin，并基于结果决定是否打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val confirmationRequestCountBeforeObservation =
            planned.steps.filterIsInstance<AgentStep.UserConfirmationRequested>().size

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已完成 Web 搜索：Kotlin search summary",
                data = webSearchResultData(),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        require(observed.decision is AgentObservationDecision.Fail)
        assertTrue(observed.decision.reason.contains("Missing model capability MobileAction"))
        assertTrue(observed.steps.any { step ->
            step is AgentStep.ModelPlanned &&
                step.plan == AgentPlan.MissingModel(ModelCapability.MobileAction)
        })
        assertEquals(
            confirmationRequestCountBeforeObservation,
            observed.steps.filterIsInstance<AgentStep.UserConfirmationRequested>().size,
        )
        assertTrue(observed.steps.filterIsInstance<AgentStep.ToolRequested>().none { step ->
            step.request.toolName == MobileActionFunctions.OPEN_WIFI_SETTINGS
        })
        assertNull(runtime.latestPendingConfirmation())
    }

    @Test
    fun modelBackedObservationReplanPrefersCapabilityProfilesOverCapabilitySet() {
        val actionRuntime = ObservationModelActionRuntime(
            initialDraft = ActionDraft(
                functionName = MobileActionFunctions.WEB_SEARCH,
                title = "Web 搜索",
                summary = "将使用 Web 搜索工具查询并整理结果：Kotlin",
                parameters = mapOf("query" to "Kotlin"),
                requiresConfirmation = true,
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = ModelObservationReplanner(
                actionPlanningRuntime = actionRuntime,
                actionModelPathProvider = { "/verified/mobile-action.litertlm" },
            ),
        )
        val planned = runtime.runOnce(
            input = "搜 Kotlin，并基于结果决定是否打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat, ModelCapability.MobileAction),
            memoryEnabled = false,
            installedCapabilityProfiles = listOf(ModelCatalog.profileForModelId(MEMORY_EMBEDDING_MODEL_ID)),
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已完成 Web 搜索：Kotlin search summary",
                data = webSearchResultData(),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        require(observed.decision is AgentObservationDecision.Fail)
        assertTrue(observed.decision.reason.contains("Missing model capability MobileAction"))
        assertTrue(observed.steps.any { step ->
            step is AgentStep.ModelPlanned &&
                step.plan == AgentPlan.MissingModel(ModelCapability.MobileAction)
        })
        assertTrue(observed.steps.filterIsInstance<AgentStep.ToolRequested>().none { step ->
            step.request.toolName == MobileActionFunctions.OPEN_WIFI_SETTINGS
        })
        assertNull(runtime.latestPendingConfirmation())
    }

    @Test
    fun ruleBackedObservationReplanDoesNotRequireMobileAction() {
        val actionRuntime = RuleActionRuntime()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = SequentialActionObservationReplanner(actionRuntime),
        )
        val planned = runtime.runOnce(
            input = "先搜一下 Kotlin，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已完成 Web 搜索：Kotlin search summary",
                data = webSearchResultData(),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.AwaitingUserConfirmation, observed.run.state)
        require(observed.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, observed.decision.plan.request.toolName)
        assertFalse(observed.decision.plan.plannedByModel)
        assertEquals(observed.decision.plan.request.id, runtime.latestPendingConfirmation()?.request?.id)
    }

    @Test
    fun modelBackedObservationReplanWorksWhenMobileActionInstalled() {
        val actionRuntime = ObservationModelActionRuntime(
            initialDraft = ActionDraft(
                functionName = MobileActionFunctions.WEB_SEARCH,
                title = "Web 搜索",
                summary = "将使用 Web 搜索工具查询并整理结果：Kotlin",
                parameters = mapOf("query" to "Kotlin"),
                requiresConfirmation = true,
            ),
        )
        val auditSink = InMemoryToolAuditSink()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = object : SkillRuntime {
                override fun manifests(): List<SkillManifest> = emptyList()
                override fun plan(input: String): SkillPlan? = null
                override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null
            },
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = ModelObservationReplanner(
                actionPlanningRuntime = actionRuntime,
                actionModelPathProvider = { "/verified/mobile-action.litertlm" },
            ),
        )
        val planned = runtime.runOnce(
            input = "搜 Kotlin，并基于结果决定是否打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            installedCapabilityProfiles = listOf(ModelCatalog.profileForModelId(MOBILE_ACTION_MODEL_ID)),
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已完成 Web 搜索：Kotlin search summary",
                data = webSearchResultData(),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.AwaitingUserConfirmation, observed.run.state)
        require(observed.decision is AgentObservationDecision.PlanNextTool)
        val nextPlan = observed.decision.plan
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, nextPlan.request.toolName)
        assertTrue(nextPlan.plannedByModel)
        assertEquals("observation model replan", nextPlan.fallbackReason)
        assertEquals("Observation model step planned.", nextPlan.request.reason)
        assertEquals(nextPlan.request.id, runtime.latestPendingConfirmation()?.request?.id)
        assertEquals(
            listOf(
                MobileActionFunctions.WEB_SEARCH,
                MobileActionFunctions.OPEN_WIFI_SETTINGS,
            ),
            observed.steps.filterIsInstance<AgentStep.ToolRequested>().map { it.request.toolName },
        )
        assertEquals(1, observed.steps.filterIsInstance<AgentStep.UserConfirmationRequested>().size)
        assertEquals(listOf(null, "/verified/mobile-action.litertlm"), actionRuntime.actionModelPaths)
        assertTrue(actionRuntime.plannedInputs[1].contains("Observation summary: 已完成 Web 搜索：Kotlin search summary"))
        assertTrue(actionRuntime.plannedInputs[1].contains("Previous tool: ${MobileActionFunctions.WEB_SEARCH}"))
        assertEquals(
            listOf(
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ToolObserved,
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ConfirmationRequested,
            ),
            auditSink.events.map { it.eventType },
        )

        runtime.confirmToolRequest(planned.run.id, nextPlan.request.id)
        val completed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = nextPlan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
                data = externalActivityResultData(MobileActionFunctions.OPEN_WIFI_SETTINGS),
            ),
        )

        requireNotNull(completed)
        assertEquals(AgentRunState.Completed, completed.run.state)
        assertEquals(AgentObservationDecision.Complete, completed.decision)
        assertEquals(2, actionRuntime.plannedInputs.size)
    }

    @Test
    fun localEvidenceContinuationTakesPriorityOverObservationReplanner() {
        val actionRuntime = ObservationModelActionRuntime(
            initialDraft = ActionDraft(
                functionName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                title = "查询当前前台应用",
                summary = "将读取当前前台应用名称。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = ModelObservationReplanner(
                actionPlanningRuntime = actionRuntime,
                actionModelPathProvider = { "/verified/mobile-action.litertlm" },
            ),
        )
        val planned = runtime.runOnce(
            input = "当前应用是什么 token=opaque-private-value，并基于结果决定是否打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "当前前台应用：Sensitive Mail",
                data = mapOf(
                    "toolName" to MobileActionFunctions.QUERY_FOREGROUND_APP,
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to "true",
                    "source" to "usage_stats_estimate",
                    "confidence" to "estimate",
                    "packageName" to "com.private.mail",
                    "appLabel" to "Sensitive Mail",
                    "lastTimeUsedMillis" to "1234",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationRequiresLocalModel)
        val continuationPrompt = observed.continuationPromptForModel.orEmpty()
        assertTrue(continuationPrompt.contains("Sensitive Mail"))
        assertTrue(continuationPrompt.contains("com.private.mail"))
        assertTrue(continuationPrompt.contains("1234"))
        assertEquals("已读取当前前台应用", observed.result.summary)
        assertEquals("[redacted]", observed.result.data["packageName"])
        assertEquals("[redacted]", observed.result.data["appLabel"])
        assertEquals("[redacted]", observed.result.data["lastTimeUsedMillis"])
        assertEquals(1, actionRuntime.plannedInputs.size)
        assertNull(runtime.latestPendingConfirmation())
    }

    @Test
    fun currentScreenOcrObservationReplannerUsesRawLocalEvidenceButKeepsTraceRedacted() {
        val actionRuntime = ObservationModelActionRuntime(
            initialDraft = ActionDraft(
                functionName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                title = "当前屏幕截图 OCR",
                summary = "单次截取当前屏幕并在本地提取 OCR 文本。",
                parameters = mapOf("captureMode" to "current_screen"),
                requiresConfirmation = true,
            ),
            modelDraft = ActionDraft(
                functionName = MobileActionFunctions.UI_TAP,
                title = "点击继续",
                summary = "点击当前屏幕上的继续按钮。",
                parameters = mapOf("target" to "继续"),
                requiresConfirmation = true,
            ),
        )
        val auditSink = InMemoryToolAuditSink()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = object : SkillRuntime {
                override fun manifests(): List<SkillManifest> = emptyList()
                override fun plan(input: String): SkillPlan? = null
                override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null
            },
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = ModelObservationReplanner(
                actionPlanningRuntime = actionRuntime,
                actionModelPathProvider = { "/verified/mobile-action.litertlm" },
            ),
        )
        val rawOcrText = "继续"
        val rawObservationJson = """
            {
              "schemaVersion": 1,
              "observationId": "screen-current",
              "capturedAtMillis": 1000,
              "packageName": "com.example.app",
              "privacyLevel": "LocalOnly",
              "sources": ["accessibility", "ocr"],
              "elementCount": 2,
              "sourceCounts": {"accessibility": 1, "ocr": 1},
              "truncated": false,
              "elements": [
                {
                  "id": "continue-button",
                  "source": "accessibility",
                  "bounds": {"left": 10, "top": 20, "right": 110, "bottom": 70},
                  "text": "继续",
                  "role": "button",
                  "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
                  "confidence": 1.0,
                  "sensitiveFlags": [],
                  "privacyLevel": "LocalOnly"
                },
                {
                  "id": "ocr:block:0",
                  "source": "ocr",
                  "bounds": {"left": 10, "top": 20, "right": 110, "bottom": 70},
                  "text": "继续",
                  "role": "ocr_block",
                  "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
                  "confidence": 0.72,
                  "sensitiveFlags": [],
                  "privacyLevel": "LocalOnly"
                }
              ]
            }
        """.trimIndent()

        val planned = runtime.runOnce(
            input = "看当前屏幕 OCR，点击继续",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            installedCapabilityProfiles = listOf(ModelCatalog.profileForModelId(MOBILE_ACTION_MODEL_ID)),
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取当前屏幕截图 OCR 摘录",
                data = currentScreenshotOcrResultData(
                    text = rawOcrText,
                    screenObservationJson = rawObservationJson,
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.AwaitingUserConfirmation, observed.run.state)
        require(observed.decision is AgentObservationDecision.PlanNextTool)
        val nextPlan = observed.decision.plan
        assertEquals(
            "actionModelPaths=${actionRuntime.actionModelPaths}, plannedInputs=${actionRuntime.plannedInputs}",
            MobileActionFunctions.UI_TAP,
            nextPlan.request.toolName,
        )
        assertTrue(nextPlan.plannedByModel)
        assertEquals("继续", nextPlan.request.arguments["target"])
        assertEquals(nextPlan.request.id, runtime.latestPendingConfirmation()?.request?.id)
        assertEquals(listOf(null, "/verified/mobile-action.litertlm"), actionRuntime.actionModelPaths)
        val replannerPrompt = actionRuntime.plannedInputs[1]
        assertTrue(replannerPrompt.contains("LocalOnly observation evidence"))
        assertTrue(replannerPrompt.contains("screenObservationJson(id=screen-current"))
        assertTrue(replannerPrompt.contains("sourceCounts=accessibility=1,ocr=1"))
        assertTrue(replannerPrompt.contains("continue-button{accessibility/button,text=继续"))
        assertTrue(replannerPrompt.contains("ocr:block:0{ocr/ocr_block,text=继续"))
        assertFalse(replannerPrompt.contains("[redacted]"))
        assertFalse(replannerPrompt.contains("\"elements\""))
        assertEquals("[redacted]", observed.result.data["ocrText"])
        assertEquals("[redacted]", observed.result.data["screenObservationJson"])
        val toolObserved = observed.steps.filterIsInstance<AgentStep.ToolObserved>().last()
        assertEquals("[redacted]", toolObserved.result.data["ocrText"])
        assertEquals("[redacted]", toolObserved.result.data["screenObservationJson"])
        assertFalse(observed.steps.toString().contains(rawObservationJson))
        assertFalse(auditSink.events.toString().contains(rawObservationJson))
    }

    @Test
    fun currentScreenOcrObservationDoesNotReplanWebSearchFromLocalOnlyEvidence() {
        val actionRuntime = ObservationModelActionRuntime(
            initialDraft = ActionDraft(
                functionName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                title = "当前屏幕截图 OCR",
                summary = "单次截取当前屏幕并在本地提取 OCR 文本。",
                parameters = mapOf("captureMode" to "current_screen"),
                requiresConfirmation = true,
            ),
            modelDraft = ActionDraft(
                functionName = MobileActionFunctions.WEB_SEARCH,
                title = "Web 搜索",
                summary = "错误地把当前屏幕 OCR 文本改写为网络搜索。",
                parameters = mapOf("query" to "Kotlin协程"),
                requiresConfirmation = false,
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = ModelObservationReplanner(
                actionPlanningRuntime = actionRuntime,
                actionModelPathProvider = { "/verified/mobile-action.litertlm" },
            ),
        )
        val rawObservationJson = """
            {
              "schemaVersion": 1,
              "observationId": "screen-current-local-only",
              "capturedAtMillis": 1000,
              "packageName": "com.example.app",
              "privacyLevel": "LocalOnly",
              "sources": ["accessibility", "ocr"],
              "elementCount": 1,
              "sourceCounts": {"ocr": 1},
              "truncated": false,
              "elements": [
                {
                  "id": "ocr:block:0",
                  "source": "ocr",
                  "bounds": {"left": 10, "top": 20, "right": 300, "bottom": 70},
                  "text": "Kotlin协程",
                  "role": "ocr_block",
                  "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
                  "confidence": 0.72,
                  "sensitiveFlags": [],
                  "privacyLevel": "LocalOnly"
                }
              ]
            }
        """.trimIndent()

        val planned = runtime.runOnce(
            input = "看当前屏幕 OCR，帮我决定下一步",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            installedCapabilityProfiles = listOf(ModelCatalog.profileForModelId(MOBILE_ACTION_MODEL_ID)),
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取当前屏幕截图 OCR 摘录",
                data = currentScreenshotOcrResultData(
                    text = "Kotlin协程",
                    screenObservationJson = rawObservationJson,
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationRequiresLocalModel)
        assertEquals(listOf(null, "/verified/mobile-action.litertlm"), actionRuntime.actionModelPaths)
        assertTrue(actionRuntime.plannedInputs[1].contains("LocalOnly observation evidence"))
        assertTrue(observed.steps.filterIsInstance<AgentStep.ToolRequested>().none { step ->
            step.request.toolName == MobileActionFunctions.WEB_SEARCH
        })
        assertNull(runtime.latestPendingConfirmation())
    }

    @Test
    fun failedUiActionObservationCanReplanWithRawLocalEvidenceButKeepsTraceRedacted() {
        val actionRuntime = ObservationModelActionRuntime(
            initialDraft = ActionDraft(
                functionName = MobileActionFunctions.UI_TAP,
                title = "点击搜索入口",
                summary = "点击当前屏幕上的搜索入口。",
                parameters = mapOf("target" to "搜索入口"),
                requiresConfirmation = true,
            ),
            modelDraft = ActionDraft(
                functionName = MobileActionFunctions.UI_TAP,
                title = "点击搜索输入框",
                summary = "根据失败后的屏幕证据点击搜索输入框。",
                parameters = mapOf("target" to "搜索输入框"),
                requiresConfirmation = true,
            ),
        )
        val auditSink = InMemoryToolAuditSink()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = object : SkillRuntime {
                override fun manifests(): List<SkillManifest> = emptyList()
                override fun plan(input: String): SkillPlan? = null
                override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null
            },
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = ModelObservationReplanner(
                actionPlanningRuntime = actionRuntime,
                actionModelPathProvider = { "/verified/mobile-action.litertlm" },
            ),
        )
        val beforeObservationJson = screenObservationJson("before-failed-tap", "com.example.app")
        val afterObservationJson = screenObservationJson("after-failed-tap", "com.example.app")
        val diffSummary =
            "changed=true;package=com.example.app->com.example.app;nodes=2->3;" +
                "addedText=搜索输入框|搜索;addedActionable=editable:搜索输入框|clickable:搜索"

        val planned = runtime.runOnce(
            input = "点击搜索入口",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            installedCapabilityProfiles = listOf(ModelCatalog.profileForModelId(MOBILE_ACTION_MODEL_ID)),
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.UI_TAP, planned.plan.request.toolName)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Failed,
                summary = "未找到可点击目标：搜索入口",
                retryable = true,
                data = localDeviceControlData(MobileActionFunctions.UI_TAP) + mapOf(
                    "actionType" to "tap",
                    "status" to "failed",
                    "retryable" to "true",
                    "summary" to "未找到可点击目标：搜索入口",
                    "failureKind" to "node_not_found",
                    "beforeObservationId" to "before-failed-tap",
                    "afterObservationId" to "after-failed-tap",
                    "verificationSummary" to "动作失败后已观察当前屏幕。",
                    "beforeScreenObservationJson" to beforeObservationJson,
                    "afterScreenObservationJson" to afterObservationJson,
                    "screenObservationDiffSummary" to diffSummary,
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.AwaitingUserConfirmation, observed.run.state)
        require(observed.decision is AgentObservationDecision.PlanNextTool)
        val nextPlan = observed.decision.plan
        assertEquals(listOf(null, "/verified/mobile-action.litertlm"), actionRuntime.actionModelPaths)
        assertEquals(MobileActionFunctions.UI_TAP, nextPlan.request.toolName)
        assertTrue(nextPlan.plannedByModel)
        assertEquals("搜索输入框", nextPlan.request.arguments["target"])
        assertEquals(nextPlan.request.id, runtime.latestPendingConfirmation()?.request?.id)
        val replannerPrompt = actionRuntime.plannedInputs[1]
        assertTrue(replannerPrompt.contains("Observation status: Failed"))
        assertTrue(replannerPrompt.contains("App search progress: stage=unknown outcome=advanced reason=screen_changed"))
        assertTrue(replannerPrompt.contains("screenObservationDiffSummary=changed=true"))
        assertTrue(replannerPrompt.contains("addedText=搜索输入框|搜索"))
        assertFalse(replannerPrompt.contains("[redacted]"))
        assertEquals("[redacted]", observed.result.data["beforeScreenObservationJson"])
        assertEquals("[redacted]", observed.result.data["afterScreenObservationJson"])
        assertEquals("[redacted]", observed.result.data["screenObservationDiffSummary"])
        assertFalse(observed.steps.toString().contains(beforeObservationJson))
        assertFalse(observed.steps.toString().contains(diffSummary))
        assertFalse(auditSink.events.toString().contains(diffSummary))
        assertTrue(observed.steps.filterIsInstance<AgentStep.ToolRequested>().none { step ->
            step.request.reason == "Device control failure recovery checkpoint."
        })
    }

    @Test
    fun modelObservationReplannerIgnoresRuleFallbackDraft() {
        val actionRuntime = ObservationModelActionRuntime(
            initialDraft = ActionDraft(
                functionName = MobileActionFunctions.WEB_SEARCH,
                title = "Web 搜索",
                summary = "将使用 Web 搜索工具查询并整理结果：Kotlin",
                parameters = mapOf("query" to "Kotlin"),
                requiresConfirmation = true,
            ),
            modelUsed = false,
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoDirectPlanSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = ModelObservationReplanner(
                actionPlanningRuntime = actionRuntime,
                actionModelPathProvider = { "/verified/mobile-action.litertlm" },
            ),
        )
        val planned = runtime.runOnce(
            input = "搜 Kotlin，并基于结果决定是否打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已完成 Web 搜索：Kotlin search summary",
                data = webSearchResultData(),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertFalse(observed.decision.requiresLocalModel)
        assertEquals(3, actionRuntime.plannedInputs.size)
        assertNull(runtime.latestPendingConfirmation())
    }

    private fun AgentObservationResult?.requireNextTool(expectedToolName: String): AgentPlan.UseTool {
        val observation = requireNotNull(this)
        assertEquals(AgentRunState.ExecutingTool, observation.run.state)
        require(observation.decision is AgentObservationDecision.PlanNextTool)
        val plan = observation.decision.plan
        assertEquals(expectedToolName, plan.request.toolName)
        assertFalse(plan.draft.requiresConfirmation)
        assertEquals(SafetyOutcome.Allow, plan.safetyDecision.outcome)
        return plan
    }

    private fun assertOpenAppUiSearchRuntimeFlow(searchCase: OpenAppUiSearchRuntimeCase) {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner { error("skill progression should not replan") },
        )
        val planned = runtime.runOnce(
            input = searchCase.input,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        assertEquals(AgentRunState.AwaitingUserConfirmation, planned.run.state)
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_APP_BY_NAME, planned.plan.request.toolName)
        assertEquals(searchCase.appName, planned.plan.request.arguments["appName"])
        assertEquals(BuiltInSkillRuntime.OPEN_APP_UI_SEARCH_SKILL, planned.plan.skillRequest?.skillId)

        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val opened = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = ToolResult(
                    requestId = planned.plan.request.id,
                    status = ToolStatus.Succeeded,
                    summary = "已打开${searchCase.appName}",
                    data = externalActivityResultData(
                        toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                        completionVerified = false,
                        externalOutcome = "Unknown",
                        externalOutcomeSource = "Unknown",
                    ),
                ),
            ),
        )

        var plan = opened.requireNextTool(MobileActionFunctions.UI_WAIT)
        assertTrue(plan.request.reason.contains("等待${searchCase.appName}前台界面稳定"))
        assertEquals(searchCase.expectedPackageName, plan.request.arguments["expectedPackageName"])
        plan = runtime.observeToolResult(
            planned.run.id,
            uiActionResult(
                request = plan.request,
                actionType = "wait",
                packageName = searchCase.expectedPackageName,
                textSummary = searchCase.appName,
            ),
        ).requireNextTool(MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
        plan = runtime.observeToolResult(
            planned.run.id,
            observeScreenResult(
                request = plan.request,
                packageName = searchCase.expectedPackageName,
                textSummary = "${searchCase.appName} 搜索入口",
            ),
        ).requireNextTool(MobileActionFunctions.UI_TAP)
        assertEquals("搜索入口", plan.request.arguments["target"])
        assertEquals(searchCase.expectedPackageName, plan.request.arguments["expectedPackageName"])
        plan = runtime.observeToolResult(
            planned.run.id,
            uiActionResult(
                request = plan.request,
                actionType = "tap",
                target = "搜索入口",
                packageName = searchCase.expectedPackageName,
                textSummary = "${searchCase.appName} 搜索输入框",
            ),
        ).requireNextTool(MobileActionFunctions.UI_WAIT)
        assertEquals(searchCase.expectedPackageName, plan.request.arguments["expectedPackageName"])
        plan = runtime.observeToolResult(
            planned.run.id,
            uiActionResult(
                request = plan.request,
                actionType = "wait",
                packageName = searchCase.expectedPackageName,
                textSummary = "${searchCase.appName} 搜索输入框",
            ),
        ).requireNextTool(MobileActionFunctions.UI_TYPE_TEXT)
        assertEquals(searchCase.query, plan.request.arguments["text"])
        assertEquals("搜索输入框", plan.request.arguments["target"])
        assertEquals(searchCase.expectedPackageName, plan.request.arguments["expectedPackageName"])
        plan = runtime.observeToolResult(
            planned.run.id,
            uiActionResult(
                request = plan.request,
                actionType = "type_text",
                target = "搜索输入框",
                packageName = searchCase.expectedPackageName,
                textSummary = "${searchCase.appName} ${searchCase.query}",
            ),
        ).requireNextTool(MobileActionFunctions.UI_SUBMIT_SEARCH)
        assertEquals(searchCase.expectedPackageName, plan.request.arguments["expectedPackageName"])
        plan = runtime.observeToolResult(
            planned.run.id,
            uiActionResult(
                request = plan.request,
                actionType = "submit_search",
                packageName = searchCase.expectedPackageName,
                textSummary = searchCase.resultSummary,
            ),
        ).requireNextTool(MobileActionFunctions.UI_WAIT)
        assertEquals(searchCase.query, plan.request.arguments["verifySearchQuery"])
        assertEquals(searchCase.expectedPackageName, plan.request.arguments["expectedPackageName"])
        assertEquals(searchCase.appName, plan.request.arguments["expectedAppName"])

        val verified = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = uiActionResult(
                    request = plan.request,
                    actionType = "wait",
                    packageName = searchCase.expectedPackageName,
                    textSummary = searchCase.resultSummary,
                    extraData = mapOf(
                        "searchVerificationStatus" to "verified",
                        "searchVerificationEvidence" to "query_visible_with_result_hint",
                    ),
                ),
            ),
        )

        assertEquals(AgentRunState.Completed, verified.run.state)
        assertEquals(AgentObservationDecision.Complete, verified.decision)
        assertEquals(null, runtime.latestPendingConfirmation())
    }

    private data class OpenAppUiSearchRuntimeCase(
        val input: String,
        val appName: String,
        val query: String,
        val expectedPackageName: String,
        val resultSummary: String,
    )

    private fun observeScreenResult(
        request: ToolRequest,
        packageName: String = "com.taobao.taobao",
        textSummary: String = "淘宝 搜索商品 海河牛奶",
        rawScreenObservationJson: String = screenObservationJson("screen-${request.id}", packageName),
    ): ToolResult =
        ToolResult(
            requestId = request.id,
            status = ToolStatus.Succeeded,
            summary = "已观察当前屏幕",
            data = localDeviceControlData(request.toolName) + mapOf(
                "observationId" to "screen-${request.id}",
                "capturedAtMillis" to "1000",
                "nodeCount" to "3",
                "actionableNodeCount" to "2",
                "textSummary" to textSummary,
                "truncated" to "false",
                "nodesJson" to "[]",
                "screenObservationJson" to rawScreenObservationJson,
                "maxTextChars" to "2000",
                "maxNodes" to "50",
                "packageName" to packageName,
            ),
        )

    private fun screenObservationJson(
        observationId: String,
        packageName: String,
    ): String =
        """{"schemaVersion":1,"observationId":"$observationId","capturedAtMillis":1000,"packageName":"$packageName","privacyLevel":"LocalOnly","sources":["accessibility"],"elementCount":0,"sourceCounts":{},"truncated":false,"elements":[]}"""

    private fun searchEntryScreenObservationJson(observationId: String): String =
        """{"schemaVersion":1,"observationId":"$observationId","capturedAtMillis":1000,"packageName":"com.taobao.taobao","privacyLevel":"LocalOnly","sources":["accessibility"],"elementCount":1,"sourceCounts":{"accessibility":1},"truncated":false,"elements":[{"id":"search_entry","source":"accessibility","bounds":{"left":0,"top":0,"right":200,"bottom":80},"text":"搜索商品","role":"button","clickability":{"clickable":true,"editable":false,"scrollable":false,"enabled":true},"confidence":1.0,"sensitiveFlags":[],"privacyLevel":"LocalOnly"}]}"""

    private fun uiActionResult(
        request: ToolRequest,
        actionType: String,
        target: String = "",
        packageName: String = "com.taobao.taobao",
        textSummary: String = "淘宝 搜索商品 海河牛奶",
        extraData: Map<String, String> = emptyMap(),
    ): ToolResult =
        ToolResult(
            requestId = request.id,
            status = ToolStatus.Succeeded,
            summary = "已执行 UI 动作",
            data = localDeviceControlData(request.toolName) +
                mapOf(
                    "actionType" to actionType,
                    "status" to "succeeded",
                    "retryable" to "false",
                    "summary" to "已执行 UI 动作",
                    "beforeObservationId" to "before-${request.id}",
                    "afterObservationId" to "after-${request.id}",
                    "verificationSummary" to "已观察当前屏幕：包名 $packageName，3 个节点，2 个可交互节点。",
                    "afterPackageName" to packageName,
                    "afterCapturedAtMillis" to "1000",
                    "afterNodeCount" to "3",
                    "afterActionableNodeCount" to "2",
                    "afterTextSummary" to textSummary,
                    "afterTruncated" to "false",
                    "afterNodesJson" to "[]",
                ) +
                target.takeIf { it.isNotBlank() }?.let { mapOf("target" to it) }.orEmpty() +
                extraData,
        )

    private fun localDeviceControlData(toolName: String): Map<String, String> =
        mapOf(
            "toolName" to toolName,
            "privacy" to MessagePrivacy.LocalOnly.name,
            "requiresLocalModel" to "true",
            "source" to DEVICE_CONTROL_SOURCE_ACCESSIBILITY,
            "metadataPolicy" to DEVICE_CONTROL_METADATA_POLICY,
        )

    private fun webSearchResultData(
        query: String = "Kotlin",
        summaryText: String = "Kotlin search summary",
        retrievedAt: String = "2026-06-13T00:00:00Z",
        resultsJson: String = """{"kind":"instant_answer","provider":"DuckDuckGo","results":[]}""",
    ): Map<String, String> =
        mapOf(
            "toolName" to MobileActionFunctions.WEB_SEARCH,
            "privacy" to MessagePrivacy.RemoteEligible.name,
            "requiresLocalModel" to "false",
            "query" to query,
            "source" to "duckduckgo",
            "searchMode" to "general",
            "retrievedAt" to retrievedAt,
            "freshness" to "current",
            "maxResults" to "3",
            "summaryText" to summaryText,
            "resultsJson" to resultsJson,
        )

    private fun clipboardResultData(text: String): Map<String, String> =
        mapOf(
            "toolName" to MobileActionFunctions.READ_CLIPBOARD,
            "privacy" to MessagePrivacy.LocalOnly.name,
            "requiresLocalModel" to "true",
            "text" to text,
            "truncated" to "false",
        )

    private fun recentImageOcrResultData(
        toolName: String,
        text: String,
        source: String,
        maxCount: String,
    ): Map<String, String> =
        mapOf(
            "toolName" to toolName,
            "privacy" to MessagePrivacy.LocalOnly.name,
            "requiresLocalModel" to "true",
            "source" to source,
            "maxCount" to maxCount,
            "scannedCount" to "1",
            "mediaAccessScope" to "full_visual_media",
            "ocrText" to text,
            "truncated" to "false",
            "ocrTextIncluded" to "true",
            "rawPayloadIncluded" to "false",
            "metadataPolicy" to "ocr_text_local_only_no_uri_path_or_pixels_persisted",
        )

    private fun currentScreenshotOcrResultData(
        text: String,
        screenObservationJson: String,
    ): Map<String, String> =
        mapOf(
            "toolName" to MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            "privacy" to MessagePrivacy.LocalOnly.name,
            "requiresLocalModel" to "true",
            "source" to CurrentScreenshotOcrContract.SOURCE,
            "captureMode" to CurrentScreenshotOcrContract.CAPTURE_MODE,
            "ocrText" to text,
            "truncated" to "false",
            "ocrTextIncluded" to "true",
            "screenObservationIncluded" to "true",
            "screenObservationJson" to screenObservationJson,
            "rawPayloadIncluded" to "false",
            "metadataPolicy" to CurrentScreenshotOcrContract.OUTPUT_METADATA_POLICY,
        )

    private fun currentScreenTextResultData(text: String): Map<String, String> =
        mapOf(
            "toolName" to MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            "privacy" to MessagePrivacy.LocalOnly.name,
            "requiresLocalModel" to "true",
            "source" to "accessibility_active_window",
            "maxChars" to "1200",
            "capturedAtMillis" to "1000",
            "nodeCount" to "1",
            "screenText" to text,
            "truncated" to "false",
            "screenTextIncluded" to "true",
            "structureSummaryIncluded" to "false",
            "rawTreeIncluded" to "false",
            "metadataPolicy" to "accessibility_text_local_only_no_node_ids_bounds_or_hierarchy_persisted",
        )

    private fun contactsResultData(): Map<String, String> =
        mapOf(
            "toolName" to MobileActionFunctions.QUERY_CONTACTS,
            "privacy" to MessagePrivacy.LocalOnly.name,
            "requiresLocalModel" to "true",
            "query" to "Alice",
            "maxCount" to "5",
            "contactCount" to "1",
            "contactsJson" to """[{"name":"Alice","phone":"+1 555 0100"}]""",
        )

    private fun scheduleReminderResultData(
        taskId: String,
        triggerAtMillis: String,
    ): Map<String, String> =
        mapOf(
            "toolName" to MobileActionFunctions.SCHEDULE_REMINDER,
            "taskId" to taskId,
            "taskStatus" to "Scheduled",
            "triggerAtMillis" to triggerAtMillis,
            "recoveryToolName" to MobileActionFunctions.CANCEL_REMINDER,
            "recoveryTaskId" to taskId,
        )

    private fun cancelReminderResultData(taskId: String): Map<String, String> =
        mapOf(
            "toolName" to MobileActionFunctions.CANCEL_REMINDER,
            "taskId" to taskId,
            "taskStatus" to "Cancelled",
        )

    private fun externalOutcomeRuntime(
        auditSink: InMemoryToolAuditSink,
        onReplan: () -> AgentObservationReplan,
    ): AgentLoopRuntime =
        AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(
                likelyAction = true,
                planningResult = ActionPlanningResult(
                    plan = ActionPlan(
                        kind = ActionPlanKind.Draft,
                        draft = ActionDraft(
                            functionName = MobileActionFunctions.OPEN_DEEP_LINK,
                            title = "打开深链",
                            summary = "将打开示例网页。",
                            parameters = mapOf("uri" to "https://example.com/search?q=Kotlin"),
                            requiresConfirmation = true,
                        ),
                    ),
                    usedModel = false,
                    fallbackReason = "test fallback",
                ),
            ),
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner { onReplan() },
        )

    private fun externalActivityResultData(
        toolName: String,
        completionVerified: Boolean = true,
        externalOutcome: String = "Completed",
        externalOutcomeSource: String = "UserConfirmed",
    ): Map<String, String> {
        val (targetKind, intentAction) = when (toolName) {
            MobileActionFunctions.SHARE_TEXT -> "android_chooser" to "android.intent.action.SEND"
            MobileActionFunctions.OPEN_WIFI_SETTINGS -> "android_settings" to "android.settings.WIFI_SETTINGS"
            else -> "external_activity" to "android.intent.action.VIEW"
        }
        return mapOf(
            "toolName" to toolName,
            "completionState" to "ExternalActivityOpened",
            "completionVerified" to completionVerified.toString(),
            "externalOutcome" to externalOutcome,
            "externalOutcomeSource" to externalOutcomeSource,
            "targetKind" to targetKind,
            "intentAction" to intentAction,
            "metadataPolicy" to "no_raw_payload_persisted",
            "rawPayloadIncluded" to "false",
        )
    }

    private fun org.json.JSONArray.toStringListForTest(): List<String> =
        (0 until length()).map { index -> getString(index) }

    private data class SkillFirstDraftCase(
        val input: String,
        val toolName: String,
        val skillId: String,
        val argumentName: String,
        val argumentValue: String,
    )

    private class SequentialStepActionRuntime : ActionPlanningRuntime {
        val plannedInputs: List<String>
            get() = mutablePlannedInputs.toList()

        private val mutablePlannedInputs = mutableListOf<String>()

        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            mutablePlannedInputs += input
            val draft = when {
                mutablePlannedInputs.size == 1 -> ActionDraft(
                    functionName = MobileActionFunctions.WEB_SEARCH,
                    title = "Web 搜索",
                    summary = "将使用 Web 搜索工具查询并整理结果：Kotlin",
                    parameters = mapOf("query" to "Kotlin"),
                    requiresConfirmation = true,
                )

                input.contains("Wi-Fi", ignoreCase = true) || input.contains("wifi", ignoreCase = true) ->
                    ActionDraft(
                        functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        title = "打开 Wi-Fi 设置",
                        summary = "将打开系统 Wi-Fi 设置页。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    )

                input.contains("手电筒") || input.contains("flashlight", ignoreCase = true) ->
                    ActionDraft(
                        functionName = MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
                        title = "打开手电筒设置",
                        summary = "将打开系统设置，由你手动确认手电筒相关操作。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    )

                else -> return ActionPlanningResult(
                    plan = ActionPlan(ActionPlanKind.NoAction),
                    usedModel = false,
                    fallbackReason = "test fallback",
                )
            }
            return ActionPlanningResult(
                plan = ActionPlan(kind = ActionPlanKind.Draft, draft = draft),
                usedModel = false,
                fallbackReason = "test fallback",
            )
        }
    }

    private class ForegroundThenWifiActionRuntime : ActionPlanningRuntime {
        val plannedInputs: List<String>
            get() = mutablePlannedInputs.toList()

        private val mutablePlannedInputs = mutableListOf<String>()

        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            mutablePlannedInputs += input
            val draft = when {
                mutablePlannedInputs.size == 1 -> ActionDraft(
                    functionName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                    title = "查询当前前台应用",
                    summary = "将读取当前前台应用名称。",
                    parameters = emptyMap(),
                    requiresConfirmation = true,
                )

                input.contains("Wi-Fi", ignoreCase = true) || input.contains("wifi", ignoreCase = true) ->
                    ActionDraft(
                        functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        title = "打开 Wi-Fi 设置",
                        summary = "将打开系统 Wi-Fi 设置页。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    )

                else -> return ActionPlanningResult(
                    plan = ActionPlan(ActionPlanKind.NoAction),
                    usedModel = false,
                    fallbackReason = "test fallback",
                )
            }
            return ActionPlanningResult(
                plan = ActionPlan(kind = ActionPlanKind.Draft, draft = draft),
                usedModel = false,
                fallbackReason = "test fallback",
            )
        }
    }

    private class ObservationModelActionRuntime(
        private val initialDraft: ActionDraft,
        private val modelDraft: ActionDraft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            summary = "将打开系统 Wi-Fi 设置页。",
            parameters = emptyMap(),
            requiresConfirmation = true,
        ),
        private val modelUsed: Boolean = true,
    ) : ActionPlanningRuntime {
        val plannedInputs: List<String>
            get() = mutablePlannedInputs.toList()
        val actionModelPaths: List<String?>
            get() = mutableActionModelPaths.toList()

        private val mutablePlannedInputs = mutableListOf<String>()
        private val mutableActionModelPaths = mutableListOf<String?>()

        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            mutablePlannedInputs += input
            mutableActionModelPaths += actionModelPath
            return if (mutablePlannedInputs.size == 1) {
                ActionPlanningResult(
                    plan = ActionPlan(kind = ActionPlanKind.Draft, draft = initialDraft),
                    usedModel = false,
                    fallbackReason = "test initial rule plan",
                )
            } else {
                ActionPlanningResult(
                    plan = ActionPlan(kind = ActionPlanKind.Draft, draft = modelDraft),
                    usedModel = modelUsed,
                    fallbackReason = if (modelUsed) null else "test rule fallback",
                )
            }
        }
    }

    private class QueuedModelActionRuntime(
        drafts: List<ActionDraft>,
    ) : ActionPlanningRuntime {
        private val remainingDrafts = ArrayDeque(drafts)
        private val mutablePlannedInputs = mutableListOf<String>()
        private val mutableActionModelPaths = mutableListOf<String?>()

        val plannedInputs: List<String>
            get() = mutablePlannedInputs.toList()
        val actionModelPaths: List<String?>
            get() = mutableActionModelPaths.toList()

        override fun isLikelyAction(input: String): Boolean = false

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            mutablePlannedInputs += input
            mutableActionModelPaths += actionModelPath
            return ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = remainingDrafts.removeFirst(),
                ),
                usedModel = true,
                fallbackReason = null,
            )
        }
    }

    private class RuleActionRuntime : ActionPlanningRuntime {
        private val planner = MobileActionPlanner()

        override fun isLikelyAction(input: String): Boolean =
            planner.isLikelyAction(input)

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            val plan = planner.plan(input)
            return ActionPlanningResult(
                plan = plan,
                usedModel = false,
                fallbackReason = "test fallback",
            )
        }
    }

    private class WifiFlashlightActionRuntime : ActionPlanningRuntime {
        override fun isLikelyAction(input: String): Boolean =
            input.contains("Wi-Fi", ignoreCase = true) ||
                input.contains("wifi", ignoreCase = true) ||
                input.contains("手电筒") ||
                input.contains("flashlight", ignoreCase = true)

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            val draft = when {
                input.contains("Wi-Fi", ignoreCase = true) || input.contains("wifi", ignoreCase = true) ->
                    ActionDraft(
                        functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        title = "打开 Wi-Fi 设置",
                        summary = "将打开系统 Wi-Fi 设置页。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    )

                input.contains("手电筒") || input.contains("flashlight", ignoreCase = true) ->
                    ActionDraft(
                        functionName = MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
                        title = "打开手电筒设置",
                        summary = "将打开系统设置，由你手动确认手电筒相关操作。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    )

                else -> return ActionPlanningResult(
                    plan = ActionPlan(ActionPlanKind.NoAction),
                    usedModel = false,
                    fallbackReason = "test fallback",
                )
            }
            return ActionPlanningResult(
                plan = ActionPlan(kind = ActionPlanKind.Draft, draft = draft),
                usedModel = false,
                fallbackReason = "test fallback",
            )
        }
    }

    private class LowConfidenceActionRuntime : ActionPlanningRuntime {
        var planCallCount: Int = 0
            private set

        override fun classifyIntent(input: String): IntentCandidate =
            IntentCandidate(
                toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                confidence = ActionIntentConfidence.Low,
                reason = "ambiguous action mention",
            )

        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            planCallCount += 1
            return ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        title = "打开 Wi-Fi 设置",
                        summary = "将打开系统 Wi-Fi 设置页。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "low confidence should not plan",
            )
        }
    }

    private class NoDirectPlanSkillRuntime : SkillRuntime {
        private val delegate = BuiltInSkillRuntime()

        override fun manifests(): List<SkillManifest> = delegate.manifests()

        override fun plan(input: String): SkillPlan? = null

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? =
            delegate.plan(input, draft, request)
    }

    private class CheckpointingLowRiskTapSkillRuntime : SkillRuntime {
        private val manifest = SkillManifest(
            id = BuiltInSkillRuntime.CURRENT_PAGE_SIMPLE_INTERACTION_SKILL,
            version = 1,
            title = "测试连续低风险点击",
            description = "测试 app control 连续 UI 动作 checkpoint。",
            triggerExamples = listOf(INPUT),
            requiredTools = listOf(MobileActionFunctions.UI_TAP),
            inputSchemaJson = """{"type":"object","properties":{"input":{"type":"string"}}}""",
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            lowRiskAppControlEligible = true,
        )

        override fun manifests(): List<SkillManifest> = listOf(manifest)

        override fun plan(input: String): SkillPlan? {
            if (input != INPUT) return null
            val steps = (1..6).map { index ->
                val stepId = "tap_$index"
                val target = "搜索$index"
                val arguments = mapOf(
                    "target" to target,
                    "timeoutMillis" to "1500",
                )
                SkillStep.ToolStep(
                    id = stepId,
                    dependsOn = if (index == 1) emptyList() else listOf("tap_${index - 1}"),
                    request = ToolRequest(
                        id = stepId,
                        toolName = MobileActionFunctions.UI_TAP,
                        arguments = arguments,
                        reason = "点击低风险目标：$target。",
                    ),
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.UI_TAP,
                        title = "点击屏幕元素",
                        summary = "点击低风险目标：$target。",
                        parameters = arguments,
                    ),
                )
            }
            return SkillPlan(
                request = SkillRequest(
                    id = "checkpoint-skill-request",
                    skillId = manifest.id,
                    arguments = mapOf("input" to input),
                    reason = input,
                ),
                manifest = manifest,
                steps = steps,
            )
        }

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null

        companion object {
            const val INPUT = "连续点击 6 个低风险目标"
        }
    }

    private class HighRiskContinuationSkillRuntime : SkillRuntime {
        private val manifest = SkillManifest(
            id = BuiltInSkillRuntime.CURRENT_PAGE_SIMPLE_INTERACTION_SKILL,
            version = 1,
            title = "测试高风险后续点击",
            description = "测试 app control 后续高风险 UI 动作必须确认。",
            triggerExamples = listOf(INPUT),
            requiredTools = listOf(
                MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                MobileActionFunctions.UI_TAP,
            ),
            inputSchemaJson = """{"type":"object","properties":{"input":{"type":"string"}}}""",
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            lowRiskAppControlEligible = true,
        )

        override fun manifests(): List<SkillManifest> = listOf(manifest)

        override fun plan(input: String): SkillPlan? {
            if (input != INPUT) return null
            val observeArguments = mapOf(
                "maxTextChars" to "2000",
                "maxNodes" to "80",
            )
            val tapArguments = mapOf(
                "target" to "确认支付",
                "timeoutMillis" to "1500",
            )
            return SkillPlan(
                request = SkillRequest(
                    id = "high-risk-continuation-skill-request",
                    skillId = manifest.id,
                    arguments = mapOf("input" to input),
                    reason = input,
                ),
                manifest = manifest,
                steps = listOf(
                    SkillStep.ToolStep(
                        id = "observe",
                        request = ToolRequest(
                            id = "observe",
                            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                            arguments = observeArguments,
                            reason = "观察当前屏幕。",
                        ),
                        draft = ActionDraft(
                            functionName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                            title = "观察当前屏幕",
                            summary = "观察当前屏幕。",
                            parameters = observeArguments,
                        ),
                    ),
                    SkillStep.ToolStep(
                        id = "tap_confirm_payment",
                        dependsOn = listOf("observe"),
                        request = ToolRequest(
                            id = "tap_confirm_payment",
                            toolName = MobileActionFunctions.UI_TAP,
                            arguments = tapArguments,
                            reason = "点击高风险目标：确认支付。",
                        ),
                        draft = ActionDraft(
                            functionName = MobileActionFunctions.UI_TAP,
                            title = "点击屏幕元素",
                            summary = "点击高风险目标：确认支付。",
                            parameters = tapArguments,
                        ),
                    ),
                ),
            )
        }

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null

        companion object {
            const val INPUT = "测试高风险后续点击"
        }
    }

    private class RecordingActionRuntime(
        private val likelyAction: Boolean,
        private val planningResult: ActionPlanningResult = ActionPlanningResult(
            plan = ActionPlan(ActionPlanKind.NoAction),
            usedModel = false,
            fallbackReason = null,
        ),
        private val modelOutputResult: ModelToolOutputParseResult = ModelToolOutputParseResult.None,
    ) : ActionPlanningRuntime {
        var isLikelyActionCallCount: Int = 0
            private set
        var planCallCount: Int = 0
            private set
        var parseModelToolOutputCallCount: Int = 0
            private set

        override fun isLikelyAction(input: String): Boolean {
            isLikelyActionCallCount += 1
            return likelyAction
        }

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            planCallCount += 1
            return planningResult
        }

        override fun parseModelToolOutput(output: String): ModelToolOutputParseResult {
            parseModelToolOutputCallCount += 1
            return modelOutputResult
        }
    }

    private class RuleOnlyActionRuntime : ActionPlanningRuntime {
        private val planner = MobileActionPlanner()

        override fun classifyIntent(input: String): IntentCandidate =
            planner.classifyIntent(input)

        override fun isLikelyAction(input: String): Boolean =
            planner.isLikelyAction(input)

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
            ActionPlanningResult(
                plan = planner.plan(input),
                usedModel = false,
                fallbackReason = "rule-only test",
            )

        override fun parseModelToolOutput(output: String): ModelToolOutputParseResult =
            planner.parseModelToolOutput(output)
    }

    private fun modelToolOutputPlanningResult(output: String): ModelToolOutputParseResult =
        MobileActionPlanner().parseModelToolOutput(output)

    private fun assertCameraUseToolPlan(input: String, plan: AgentPlan.UseTool) {
        assertEquals("$input should use the system camera tool", MobileActionFunctions.OPEN_CAMERA, plan.request.toolName)
        assertTrue("$input should not pass camera arguments", plan.request.arguments.isEmpty())
        assertEquals(MobileActionFunctions.OPEN_CAMERA, plan.draft.functionName)
    }

    private fun wifiPlanningResult(): ActionPlanningResult =
        ActionPlanningResult(
            plan = ActionPlan(
                kind = ActionPlanKind.Draft,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                    title = "打开 Wi-Fi 设置",
                    summary = "将打开系统 Wi-Fi 设置页。",
                    parameters = emptyMap(),
                    requiresConfirmation = true,
                ),
            ),
            usedModel = false,
            fallbackReason = "test fallback",
        )

    private fun modelBackedWifiPlanningResult(): ActionPlanningResult =
        wifiPlanningResult().copy(
            usedModel = true,
            fallbackReason = null,
        )

    private fun uiTapPlanningResult(target: String): ActionPlanningResult =
        ActionPlanningResult(
            plan = ActionPlan(
                kind = ActionPlanKind.Draft,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.UI_TAP,
                    title = "点击当前屏幕元素",
                    summary = "将点击当前屏幕上的 $target。",
                    parameters = mapOf("target" to target),
                    requiresConfirmation = true,
                ),
            ),
            usedModel = false,
            fallbackReason = "test fallback",
        )

    private fun readClipboardPlanningResult(): ActionPlanningResult =
        ActionPlanningResult(
            plan = ActionPlan(
                kind = ActionPlanKind.Draft,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.READ_CLIPBOARD,
                    title = "读取剪贴板",
                    summary = "将读取当前剪贴板文本。",
                    parameters = emptyMap(),
                    requiresConfirmation = true,
                ),
            ),
            usedModel = false,
            fallbackReason = "test fallback",
        )

    private data class PersistedToolToToolCancelPending(
        val runId: String,
        val cancelRequestId: String,
    )

    private fun persistToolToToolCancelPending(
        dao: FakeAgentTraceDao,
        runId: String,
    ): PersistedToolToToolCancelPending {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = ToolToToolSkillRuntime(),
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { runId },
            ),
        )
        val planned = initialRuntime.runOnce(
            input = ToolToToolSkillRuntime.REMINDER_INPUT,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        initialRuntime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = initialRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已安排 10000 触发的后台提醒",
                data = scheduleReminderResultData(taskId = "task-restored", triggerAtMillis = "10000"),
            ),
        )
        requireNotNull(observed)
        require(observed.decision is AgentObservationDecision.PlanNextTool)
        return PersistedToolToToolCancelPending(
            runId = planned.run.id,
            cancelRequestId = observed.decision.plan.request.id,
        )
    }

    private class ManifestTransformingSkillRuntime(
        private val delegate: SkillRuntime,
        private val transform: (SkillManifest) -> SkillManifest,
    ) : SkillRuntime {
        override fun manifests(): List<SkillManifest> =
            delegate.manifests().map(transform)

        override fun plan(input: String): SkillPlan? =
            delegate.plan(input)

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? =
            delegate.plan(input, draft, request)
    }

    private class ToolToToolSkillRuntime : SkillRuntime {
        private val reminderManifest = manifest(
            id = REMINDER_SKILL_ID,
            requiredTools = listOf(
                MobileActionFunctions.SCHEDULE_REMINDER,
                MobileActionFunctions.CANCEL_REMINDER,
            ),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
        )
        private val unsafeContactsManifest = manifest(
            id = UNSAFE_CONTACTS_SKILL_ID,
            requiredTools = listOf(
                MobileActionFunctions.QUERY_CONTACTS,
                MobileActionFunctions.SHARE_TEXT,
            ),
            riskLevel = RiskLevel.HighExternalSend,
        )

        override fun manifests(): List<SkillManifest> = listOf(reminderManifest, unsafeContactsManifest)

        override fun plan(input: String): SkillPlan? =
            when (input) {
                REMINDER_INPUT -> reminderPlan(input)
                UNSAFE_CONTACTS_INPUT -> unsafeContactsPlan(input)
                else -> null
            }

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null

        private fun reminderPlan(input: String): SkillPlan {
            val scheduleDraft = ActionDraft(
                functionName = MobileActionFunctions.SCHEDULE_REMINDER,
                title = "安排提醒",
                summary = "安排一个测试提醒。",
                parameters = mapOf("title" to "喝水", "delayMinutes" to "15"),
                requiresConfirmation = true,
            )
            val cancelDraft = ActionDraft(
                functionName = MobileActionFunctions.CANCEL_REMINDER,
                title = "撤销提醒",
                summary = "撤销刚安排的提醒。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            return SkillPlan(
                request = SkillRequest(
                    id = "tool-to-tool-reminder-skill-request",
                    skillId = REMINDER_SKILL_ID,
                    arguments = mapOf("input" to input),
                    reason = input,
                ),
                manifest = reminderManifest,
                steps = listOf(
                    SkillStep.ToolStep(
                        id = "schedule",
                        request = ToolRequest(
                            id = "tool-to-tool-schedule-request",
                            toolName = MobileActionFunctions.SCHEDULE_REMINDER,
                            arguments = scheduleDraft.parameters,
                            reason = scheduleDraft.summary,
                        ),
                        draft = scheduleDraft,
                    ),
                    SkillStep.ToolStep(
                        id = "cancel",
                        dependsOn = listOf("schedule"),
                        request = ToolRequest(
                            id = "tool-to-tool-cancel-request",
                            toolName = MobileActionFunctions.CANCEL_REMINDER,
                            reason = cancelDraft.summary,
                        ),
                        draft = cancelDraft,
                        argumentBindings = mapOf("taskId" to "schedule.taskId"),
                    ),
                ),
            )
        }

        private fun unsafeContactsPlan(input: String): SkillPlan {
            val contactsDraft = ActionDraft(
                functionName = MobileActionFunctions.QUERY_CONTACTS,
                title = "查询联系人",
                summary = "查询联系人摘要。",
                parameters = mapOf("query" to "Alice"),
                requiresConfirmation = true,
            )
            val shareDraft = ActionDraft(
                functionName = MobileActionFunctions.SHARE_TEXT,
                title = "分享联系人",
                summary = "分享联系人摘要。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            return SkillPlan(
                request = SkillRequest(
                    id = "unsafe-contacts-skill-request",
                    skillId = UNSAFE_CONTACTS_SKILL_ID,
                    arguments = mapOf("input" to input),
                    reason = input,
                ),
                manifest = unsafeContactsManifest,
                steps = listOf(
                    SkillStep.ToolStep(
                        id = "contacts",
                        request = ToolRequest(
                            id = "unsafe-contacts-request",
                            toolName = MobileActionFunctions.QUERY_CONTACTS,
                            arguments = contactsDraft.parameters,
                            reason = contactsDraft.summary,
                        ),
                        draft = contactsDraft,
                    ),
                    SkillStep.ToolStep(
                        id = "share",
                        dependsOn = listOf("contacts"),
                        request = ToolRequest(
                            id = "unsafe-share-request",
                            toolName = MobileActionFunctions.SHARE_TEXT,
                            reason = shareDraft.summary,
                        ),
                        draft = shareDraft,
                        argumentBindings = mapOf("text" to "contacts.contactsJson"),
                    ),
                ),
            )
        }

        private fun manifest(
            id: String,
            requiredTools: List<String>,
            riskLevel: RiskLevel,
        ): SkillManifest =
            SkillManifest(
                id = id,
                version = 1,
                title = id,
                description = "Test-only tool-to-tool skill",
                triggerExamples = listOf(id),
                requiredTools = requiredTools,
                inputSchemaJson = """
                    {
                      "type": "object",
                      "required": ["input"],
                      "properties": {
                        "input": {
                          "type": "string",
                          "minLength": 1
                        }
                      },
                      "additionalProperties": false
                    }
                """.trimIndent(),
                riskLevel = riskLevel,
            )

        companion object {
            const val REMINDER_INPUT = "tool to tool reminder skill"
            const val UNSAFE_CONTACTS_INPUT = "unsafe contacts share skill"
            const val REMINDER_SKILL_ID = "tool_to_tool_reminder_skill"
            const val UNSAFE_CONTACTS_SKILL_ID = "unsafe_contacts_share_skill"
        }
    }

    private class CustomClipboardTransformSkillRuntime(
        private val shareBinding: String = "custom_model.shareText",
        private val includeUnmetShareDependency: Boolean = false,
    ) : SkillRuntime {
        private val manifest = SkillManifest(
            id = SKILL_ID,
            version = 1,
            title = "Custom clipboard transform",
            description = "Test-only composite skill",
            triggerExamples = listOf("custom clipboard transform"),
            requiredTools = listOf(
                MobileActionFunctions.READ_CLIPBOARD,
                MobileActionFunctions.SHARE_TEXT,
            ) + if (includeUnmetShareDependency) {
                listOf(MobileActionFunctions.QUERY_FOREGROUND_APP)
            } else {
                emptyList()
            },
            inputSchemaJson = """
                {
                  "type": "object",
                  "required": ["input"],
                  "properties": {
                    "input": {
                      "type": "string",
                      "minLength": 1
                    }
                  },
                  "additionalProperties": false
                }
            """.trimIndent(),
            riskLevel = RiskLevel.HighExternalSend,
        )

        override fun manifests(): List<SkillManifest> = listOf(manifest)

        override fun plan(input: String): SkillPlan {
            val readDraft = ActionDraft(
                functionName = MobileActionFunctions.READ_CLIPBOARD,
                title = "读取剪贴板",
                summary = "将读取当前剪贴板文本。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            val readRequest = ToolRequest(
                id = "custom-read-request",
                toolName = MobileActionFunctions.READ_CLIPBOARD,
                reason = readDraft.summary,
            )
            val shareDraft = ActionDraft(
                functionName = MobileActionFunctions.SHARE_TEXT,
                title = "分享转换结果",
                summary = "将分享模型转换后的文本。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            val shareRequest = ToolRequest(
                id = "custom-share-request",
                toolName = MobileActionFunctions.SHARE_TEXT,
                reason = shareDraft.summary,
            )
            val foregroundDraft = ActionDraft(
                functionName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                title = "查询前台应用",
                summary = "查询当前前台应用。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            val foregroundStep = SkillStep.ToolStep(
                id = "custom_foreground",
                request = ToolRequest(
                    id = "custom-foreground-request",
                    toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                    reason = foregroundDraft.summary,
                ),
                draft = foregroundDraft,
            )
            val shareDependsOn = if (includeUnmetShareDependency) {
                listOf("custom_model", foregroundStep.id)
            } else {
                listOf("custom_model")
            }
            val steps = mutableListOf<SkillStep>(
                SkillStep.ToolStep(
                    id = "custom_read",
                    request = readRequest,
                    draft = readDraft,
                ),
                SkillStep.ModelStep(
                    id = "custom_model",
                    dependsOn = listOf("custom_read"),
                    title = "转换剪贴板内容",
                    instruction = "转换用户确认读取的剪贴板内容。",
                    inputBindings = mapOf("clipboardText" to "custom_read.text"),
                    outputKey = "shareText",
                ),
            )
            if (includeUnmetShareDependency) {
                steps += foregroundStep
            }
            steps += SkillStep.ToolStep(
                id = "custom_share",
                dependsOn = shareDependsOn,
                request = shareRequest,
                draft = shareDraft,
                argumentBindings = mapOf("text" to shareBinding),
            )
            return SkillPlan(
                request = SkillRequest(
                    id = "custom-skill-request",
                    skillId = SKILL_ID,
                    arguments = mapOf("input" to input),
                    reason = input,
                ),
                manifest = manifest,
                steps = steps,
            )
        }

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null

        companion object {
            const val SKILL_ID = "custom_clipboard_transform_skill"
        }
    }

    private class ValueFreeFrontierSkillRuntime(
        private val nextStepBindsModelPayload: Boolean = false,
    ) : SkillRuntime {
        private val finalToolName = if (nextStepBindsModelPayload) {
            MobileActionFunctions.SHARE_TEXT
        } else {
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS
        }
        private val manifest = SkillManifest(
            id = SKILL_ID,
            version = 1,
            title = "Value-free frontier skill",
            description = "Test-only skill for checkpoint frontier recovery",
            triggerExamples = listOf(INPUT),
            requiredTools = listOf(
                MobileActionFunctions.READ_CLIPBOARD,
                MobileActionFunctions.OPEN_WIFI_SETTINGS,
                finalToolName,
            ),
            inputSchemaJson = """
                {
                  "type": "object",
                  "required": ["input"],
                  "properties": {
                    "input": {
                      "type": "string",
                      "minLength": 1
                    }
                  },
                  "additionalProperties": false
                }
            """.trimIndent(),
            riskLevel = if (nextStepBindsModelPayload) {
                RiskLevel.HighExternalSend
            } else {
                RiskLevel.MediumDraftOrNavigation
            },
        )

        override fun manifests(): List<SkillManifest> = listOf(manifest)

        override fun plan(input: String): SkillPlan? {
            if (input != INPUT) return null
            val readDraft = ActionDraft(
                functionName = MobileActionFunctions.READ_CLIPBOARD,
                title = "读取剪贴板",
                summary = "将读取当前剪贴板文本。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            val readStep = SkillStep.ToolStep(
                id = "frontier_read",
                request = ToolRequest(
                    id = "frontier-read-request",
                    toolName = MobileActionFunctions.READ_CLIPBOARD,
                    reason = readDraft.summary,
                ),
                draft = readDraft,
            )
            val wifiDraft = ActionDraft(
                functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                title = "打开 Wi-Fi 设置",
                summary = "将打开系统 Wi-Fi 设置页。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            val wifiStep = SkillStep.ToolStep(
                id = "frontier_wifi",
                dependsOn = listOf("frontier_model"),
                request = ToolRequest(
                    id = "frontier-wifi-request",
                    toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                    reason = wifiDraft.summary,
                ),
                draft = wifiDraft,
            )
            val finalDraft = if (nextStepBindsModelPayload) {
                ActionDraft(
                    functionName = MobileActionFunctions.SHARE_TEXT,
                    title = "分享恢复摘要",
                    summary = "将分享模型生成的摘要。",
                    parameters = emptyMap(),
                    requiresConfirmation = true,
                )
            } else {
                ActionDraft(
                    functionName = MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
                    title = "打开手电筒设置",
                    summary = "将打开系统设置，由你手动确认手电筒相关操作。",
                    parameters = emptyMap(),
                    requiresConfirmation = true,
                )
            }
            val finalStep = SkillStep.ToolStep(
                id = "frontier_final",
                dependsOn = listOf("frontier_model", "frontier_wifi"),
                request = ToolRequest(
                    id = "frontier-final-request",
                    toolName = finalToolName,
                    reason = finalDraft.summary,
                ),
                draft = finalDraft,
                argumentBindings = if (nextStepBindsModelPayload) {
                    mapOf("text" to "frontier_model.shareText")
                } else {
                    emptyMap()
                },
            )
            return SkillPlan(
                request = SkillRequest(
                    id = "frontier-skill-request",
                    skillId = SKILL_ID,
                    arguments = mapOf("input" to input),
                    reason = input,
                ),
                manifest = manifest,
                steps = listOf(
                    readStep,
                    SkillStep.ModelStep(
                        id = "frontier_model",
                        dependsOn = listOf("frontier_read"),
                        title = "摘要剪贴板",
                        instruction = "生成一个后续步骤可使用的摘要。",
                        inputBindings = mapOf("clipboardText" to "frontier_read.text"),
                        outputKey = "shareText",
                    ),
                    wifiStep,
                    finalStep,
                ),
            )
        }

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null

        companion object {
            const val INPUT = "value-free frontier skill"
            const val SKILL_ID = "value_free_frontier_skill"
        }
    }

    private class OcrModelSkillRuntime : SkillRuntime {
        private val manifest = SkillManifest(
            id = SKILL_ID,
            version = 1,
            title = "OCR model skill",
            description = "Test-only OCR model skill",
            triggerExamples = listOf("ocr model skill"),
            requiredTools = listOf(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR),
            inputSchemaJson = """
                {
                  "type": "object",
                  "required": ["input"],
                  "properties": {
                    "input": {
                      "type": "string",
                      "minLength": 1
                    }
                  },
                  "additionalProperties": false
                }
            """.trimIndent(),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
        )

        override fun manifests(): List<SkillManifest> = listOf(manifest)

        override fun plan(input: String): SkillPlan? {
            if (input != "ocr model skill") return null
            val draft = ActionDraft(
                functionName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                title = "读取截图 OCR",
                summary = "将读取最近截图并提取 OCR。",
                parameters = mapOf("maxCount" to "1"),
                requiresConfirmation = true,
            )
            return SkillPlan(
                request = SkillRequest(
                    id = "ocr-skill-request",
                    skillId = SKILL_ID,
                    arguments = mapOf("input" to input),
                    reason = input,
                ),
                manifest = manifest,
                steps = listOf(
                    SkillStep.ToolStep(
                        id = "ocr_read",
                        request = ToolRequest(
                            id = "ocr-read-request",
                            toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                            arguments = draft.parameters,
                            reason = draft.summary,
                        ),
                        draft = draft,
                    ),
                    SkillStep.ModelStep(
                        id = "summarize_ocr",
                        dependsOn = listOf("ocr_read"),
                        title = "整理截图 OCR",
                        instruction = "只保留 OCR 里的待办线索。",
                        inputBindings = mapOf("ocrText" to "ocr_read.ocrText"),
                        outputKey = "summary",
                        keepsSensitiveInputLocal = false,
                    ),
                ),
            )
        }

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null

        companion object {
            const val SKILL_ID = "ocr_model_skill"
        }
    }

    private class LocalOnlyResultSkillRuntime : SkillRuntime {
        private val manifest = SkillManifest(
            id = SKILL_ID,
            version = 1,
            title = "Local only result skill",
            description = "Test-only local-only result skill",
            triggerExamples = listOf("local only contacts skill"),
            requiredTools = listOf(MobileActionFunctions.QUERY_CONTACTS),
            inputSchemaJson = """
                {
                  "type": "object",
                  "required": ["input"],
                  "properties": {
                    "input": {
                      "type": "string",
                      "minLength": 1
                    }
                  },
                  "additionalProperties": false
                }
            """.trimIndent(),
            riskLevel = RiskLevel.LowReadOnly,
        )

        override fun manifests(): List<SkillManifest> = listOf(manifest)

        override fun plan(input: String): SkillPlan? {
            if (input != "local only contacts skill") return null
            val draft = ActionDraft(
                functionName = MobileActionFunctions.QUERY_CONTACTS,
                title = "查询联系人",
                summary = "将读取联系人摘要。",
                parameters = mapOf("query" to "Alice"),
                requiresConfirmation = true,
            )
            return SkillPlan(
                request = SkillRequest(
                    id = "local-only-skill-request",
                    skillId = SKILL_ID,
                    arguments = mapOf("input" to input),
                    reason = input,
                ),
                manifest = manifest,
                steps = listOf(
                    SkillStep.ToolStep(
                        id = "contacts",
                        request = ToolRequest(
                            id = "local-only-contacts-request",
                            toolName = MobileActionFunctions.QUERY_CONTACTS,
                            arguments = draft.parameters,
                            reason = draft.summary,
                        ),
                        draft = draft,
                    ),
                    SkillStep.ModelStep(
                        id = "summarize_contacts",
                        dependsOn = listOf("contacts"),
                        title = "整理联系人本地结果",
                        instruction = "根据联系人摘要回答用户。",
                        inputBindings = mapOf("contactSummary" to "contacts.summary"),
                        outputKey = "answer",
                        keepsSensitiveInputLocal = false,
                    ),
                ),
            )
        }

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null

        companion object {
            const val SKILL_ID = "local_only_result_skill"
        }
    }

    private class MultiModelSkillRuntime : SkillRuntime {
        private val manifest = SkillManifest(
            id = SKILL_ID,
            version = 1,
            title = "Multi model skill",
            description = "Test-only multi model skill",
            triggerExamples = listOf("multi model skill"),
            requiredTools = listOf(
                MobileActionFunctions.WEB_SEARCH,
                MobileActionFunctions.SHARE_TEXT,
                MobileActionFunctions.OPEN_WIFI_SETTINGS,
            ),
            inputSchemaJson = """
                {
                  "type": "object",
                  "required": ["input"],
                  "properties": {
                    "input": {
                      "type": "string",
                      "minLength": 1
                    }
                  },
                  "additionalProperties": false
                }
            """.trimIndent(),
            riskLevel = RiskLevel.HighExternalSend,
        )

        override fun manifests(): List<SkillManifest> = listOf(manifest)

        override fun plan(input: String): SkillPlan? {
            if (input != "multi model skill") return null
            val searchDraft = ActionDraft(
                functionName = MobileActionFunctions.WEB_SEARCH,
                title = "Web 搜索",
                summary = "将使用 Web 搜索工具查询并整理结果：Kotlin",
                parameters = mapOf("query" to "Kotlin"),
                requiresConfirmation = true,
            )
            val shareDraft = ActionDraft(
                functionName = MobileActionFunctions.SHARE_TEXT,
                title = "分享摘要",
                summary = "将分享搜索摘要。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            val wifiDraft = ActionDraft(
                functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                title = "打开 Wi-Fi 设置",
                summary = "将打开系统 Wi-Fi 设置页。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            return SkillPlan(
                request = SkillRequest(
                    id = "multi-model-skill-request",
                    skillId = SKILL_ID,
                    arguments = mapOf("input" to input),
                    reason = input,
                ),
                manifest = manifest,
                steps = listOf(
                    SkillStep.ToolStep(
                        id = "search",
                        request = ToolRequest(
                            id = "multi-search-request",
                            toolName = MobileActionFunctions.WEB_SEARCH,
                            arguments = mapOf("query" to "Kotlin"),
                            reason = searchDraft.summary,
                        ),
                        draft = searchDraft,
                    ),
                    SkillStep.ModelStep(
                        id = "summarize_search",
                        dependsOn = listOf("search"),
                        title = "整理搜索结果",
                        instruction = "把搜索结果整理成适合分享的一句话。",
                        inputBindings = mapOf("searchSummary" to "search.summary"),
                        outputKey = "shareText",
                        keepsSensitiveInputLocal = false,
                    ),
                    SkillStep.ToolStep(
                        id = "share_search",
                        dependsOn = listOf("summarize_search"),
                        request = ToolRequest(
                            id = "multi-share-request",
                            toolName = MobileActionFunctions.SHARE_TEXT,
                            reason = shareDraft.summary,
                        ),
                        draft = shareDraft,
                        argumentBindings = mapOf("text" to "summarize_search.shareText"),
                    ),
                    SkillStep.ModelStep(
                        id = "decide_settings",
                        dependsOn = listOf("share_search"),
                        title = "决定是否打开设置",
                        instruction = "根据分享工具结果输出下一步确认说明。",
                        inputBindings = mapOf("shareSummary" to "share_search.summary"),
                        outputKey = "nextStep",
                        keepsSensitiveInputLocal = false,
                    ),
                    SkillStep.ToolStep(
                        id = "open_wifi",
                        dependsOn = listOf("decide_settings"),
                        request = ToolRequest(
                            id = "multi-wifi-request",
                            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                            reason = wifiDraft.summary,
                        ),
                        draft = wifiDraft,
                    ),
                ),
            )
        }

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null

        companion object {
            const val SKILL_ID = "multi_model_skill"
        }
    }

    private class InvalidSkillRuntime(
        private val invalidForTool: String,
        private val arguments: Map<String, String>,
    ) : SkillRuntime {
        private val manifest = SkillManifest(
            id = "invalid_attached_skill",
            version = 1,
            title = "Invalid attached skill",
            description = "Invalid skill for schema contract tests",
            triggerExamples = listOf("invalid skill"),
            requiredTools = listOf(invalidForTool),
            inputSchemaJson = """
                {
                  "type": "object",
                  "required": ["input"],
                  "properties": {
                    "input": {
                      "type": "string",
                      "minLength": 1
                    }
                  },
                  "additionalProperties": false
                }
            """.trimIndent(),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
        )

        override fun manifests(): List<SkillManifest> = listOf(manifest)

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? {
            if (request.toolName != invalidForTool) return null
            return SkillPlan(
                request = SkillRequest(
                    id = "invalid-skill-request",
                    skillId = manifest.id,
                    arguments = arguments,
                    reason = input,
                ),
                manifest = manifest,
                steps = listOf(SkillStep.ToolStep(request, draft)),
            )
        }
    }

    private class ClearPendingThrowingTraceStore(
        private val delegate: AgentTraceStore,
    ) : AgentTraceStore by delegate {
        override fun clearPendingConfirmation(runId: String, requestId: String): Boolean {
            error("pending delete failed")
        }
    }

    private class FakeAgentTraceDao : AgentTraceDao {
        private val runs = linkedMapOf<String, AgentRunEntity>()
        private val steps = mutableListOf<AgentStepEntity>()
        private val pendingConfirmations = linkedMapOf<String, PendingAgentConfirmationEntity>()
        private val skillRunCheckpoints = linkedMapOf<Pair<String, String>, AgentSkillRunCheckpointEntity>()

        override fun run(runId: String): AgentRunEntity? =
            runs[runId]

        override fun recentRuns(limit: Int): List<AgentRunEntity> =
            runs.values
                .sortedWith(
                    compareByDescending<AgentRunEntity> { run -> run.updatedAtMillis }
                        .thenByDescending { run -> run.createdAtMillis }
                        .thenByDescending { run -> run.id },
                )
                .take(limit)

        override fun runIdsForSession(sessionId: String): List<String> =
            runs.values
                .filter { run -> run.sessionId == sessionId }
                .map { run -> run.id }

        override fun upsertRun(run: AgentRunEntity) {
            runs[run.id] = run
        }

        override fun updateRunState(runId: String, state: String, updatedAtMillis: Long): Int {
            val run = runs[runId] ?: return 0
            runs[runId] = run.copy(state = state, updatedAtMillis = updatedAtMillis)
            return 1
        }

        override fun touchRun(runId: String, updatedAtMillis: Long): Int {
            val run = runs[runId] ?: return 0
            runs[runId] = run.copy(updatedAtMillis = updatedAtMillis)
            return 1
        }

        override fun nextStepPosition(runId: String): Int =
            steps
                .filter { step -> step.runId == runId }
                .maxOfOrNull { step -> step.position + 1 }
                ?: 0

        override fun insertStep(step: AgentStepEntity) {
            steps.removeAll { existing ->
                existing.runId == step.runId && existing.position == step.position
            }
            steps += step
        }

        override fun steps(runId: String): List<AgentStepEntity> =
            steps
                .filter { step -> step.runId == runId }
                .sortedBy { step -> step.position }

        override fun deleteStepsForSession(sessionId: String): Int {
            val runIdSet = runIdsForSession(sessionId).toSet()
            val before = steps.size
            steps.removeAll { step -> step.runId in runIdSet }
            return before - steps.size
        }

        override fun pendingConfirmations(): List<PendingAgentConfirmationEntity> =
            pendingConfirmations.values.sortedWith(
                compareByDescending<PendingAgentConfirmationEntity> { pending -> pending.updatedAtMillis }
                    .thenByDescending { pending -> pending.createdAtMillis }
                    .thenByDescending { pending -> pending.runId },
            )

        override fun latestPendingConfirmation(): PendingAgentConfirmationEntity? =
            pendingConfirmations().firstOrNull()

        override fun upsertPendingConfirmation(pending: PendingAgentConfirmationEntity) {
            pendingConfirmations[pending.runId] = pending
        }

        override fun deletePendingConfirmation(runId: String, requestId: String): Int {
            val existing = pendingConfirmations[runId] ?: return 0
            if (existing.requestId != requestId) return 0
            pendingConfirmations.remove(runId)
            return 1
        }

        override fun deletePendingConfirmationsForSession(sessionId: String): Int {
            val runIds = runIdsForSession(sessionId)
            val before = pendingConfirmations.size
            pendingConfirmations.keys.removeAll(runIds.toSet())
            return before - pendingConfirmations.size
        }

        override fun skillRunCheckpoint(
            runId: String,
            requestId: String,
        ): AgentSkillRunCheckpointEntity? =
            skillRunCheckpoints[runId to requestId]

        override fun skillRunCheckpointsForRun(runId: String): List<AgentSkillRunCheckpointEntity> =
            skillRunCheckpoints.values
                .filter { checkpoint -> checkpoint.runId == runId }
                .sortedWith(
                    compareByDescending<AgentSkillRunCheckpointEntity> { checkpoint -> checkpoint.updatedAtMillis }
                        .thenByDescending { checkpoint -> checkpoint.requestId },
                )

        override fun upsertSkillRunCheckpoint(checkpoint: AgentSkillRunCheckpointEntity) {
            skillRunCheckpoints[checkpoint.runId to checkpoint.requestId] = checkpoint
        }

        override fun deleteSkillRunCheckpoint(runId: String, requestId: String): Int =
            if (skillRunCheckpoints.remove(runId to requestId) != null) 1 else 0

        override fun deleteSkillRunCheckpointsForRun(runId: String): Int {
            val before = skillRunCheckpoints.size
            skillRunCheckpoints.keys.removeAll { (checkpointRunId, _) -> checkpointRunId == runId }
            return before - skillRunCheckpoints.size
        }

        override fun deleteSkillRunCheckpointsForSession(sessionId: String): Int {
            val runIdSet = runIdsForSession(sessionId).toSet()
            val before = skillRunCheckpoints.size
            skillRunCheckpoints.keys.removeAll { (checkpointRunId, _) -> checkpointRunId in runIdSet }
            return before - skillRunCheckpoints.size
        }

        override fun deleteRunsForSession(sessionId: String): Int {
            val runIds = runIdsForSession(sessionId)
            val before = runs.size
            runs.keys.removeAll(runIds.toSet())
            return before - runs.size
        }
    }

    private class ThrowingMemoryIndex : MemoryIndex {
        override var enabled: Boolean = true

        override fun rebuild(messages: List<com.bytedance.zgx.solin.ChatMessage>) = Unit

        override fun index(id: String, text: String) = Unit

        override fun search(query: String, topK: Int): List<MemoryHit> {
            error("memory unavailable")
        }

        override fun buildContext(hits: List<MemoryHit>): String =
            error("memory context unavailable")
    }
}
