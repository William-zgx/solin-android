package com.bytedance.zgx.solin.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bytedance.zgx.solin.MOBILE_ACTION_MODEL_ID
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.action.ActionExecutor
import com.bytedance.zgx.solin.action.HybridActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.data.ModelRepository
import com.bytedance.zgx.solin.device.AndroidCalendarAvailabilityProvider
import com.bytedance.zgx.solin.device.AndroidContactSummaryProvider
import com.bytedance.zgx.solin.device.AndroidCurrentScreenControlProvider
import com.bytedance.zgx.solin.device.AndroidCurrentScreenTextProvider
import com.bytedance.zgx.solin.device.AndroidForegroundAppProvider
import com.bytedance.zgx.solin.device.AndroidNotificationSummaryProvider
import com.bytedance.zgx.solin.device.AndroidRecentFileProvider
import com.bytedance.zgx.solin.device.AndroidRecentImageTextProvider
import com.bytedance.zgx.solin.device.AppSearchProgressEvidence
import com.bytedance.zgx.solin.device.AppSearchResultVerifier
import com.bytedance.zgx.solin.device.DeviceControlSessionService
import com.bytedance.zgx.solin.device.SolinAccessibilityService
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.ScreenStateSnapshot
import com.bytedance.zgx.solin.device.UiActionReadResult
import com.bytedance.zgx.solin.device.UiActionExecutionResult
import com.bytedance.zgx.solin.device.UiActionFailureKind
import com.bytedance.zgx.solin.device.UiActionStatus
import com.bytedance.zgx.solin.device.UiScrollDirection
import com.bytedance.zgx.solin.device.UiTargetKind
import com.bytedance.zgx.solin.device.UiTargetResolver
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.orchestration.AgentObservationDecision
import com.bytedance.zgx.solin.orchestration.AgentPlan
import com.bytedance.zgx.solin.orchestration.AgentRunOptions
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.AssistantOrchestrator
import com.bytedance.zgx.solin.orchestration.AssistantRoute
import com.bytedance.zgx.solin.orchestration.CompositeAgentObservationReplanner
import com.bytedance.zgx.solin.orchestration.InMemoryAgentTraceStore
import com.bytedance.zgx.solin.orchestration.MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES
import com.bytedance.zgx.solin.orchestration.ModelObservationReplanDiagnostic
import com.bytedance.zgx.solin.orchestration.ModelObservationReplanner
import com.bytedance.zgx.solin.skill.AppSearchPlanningMode
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.tool.OkHttpWebSearchProvider
import com.bytedance.zgx.solin.tool.RoutingToolExecutor
import com.bytedance.zgx.solin.tool.ToolExecutor
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ValidatingToolExecutor
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class DeviceControlEvalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DEVICE_CONTROL_EVAL) return
        val pendingResult = goAsync()
        val pendingResultFinished = AtomicBoolean(false)
        fun finishPendingResult() {
            if (pendingResultFinished.compareAndSet(false, true)) {
                pendingResult.finish()
            }
        }
        val appContext = context.applicationContext
        Thread {
            try {
                val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: "missing-${System.currentTimeMillis()}"
                if (command == COMMAND_MODEL_DRIVEN_APP_SEARCH) {
                    // Model-backed app search can run longer than the broadcast timeout.
                    finishPendingResult()
                }
                val provider = AndroidCurrentScreenControlProvider()
                val commandLines = try {
                    when (command) {
                        COMMAND_START_CONTROL_SESSION -> {
                            val reason = intent.getStringExtra(EXTRA_TEXT)
                                ?.takeIf { it.isNotBlank() }
                                ?: DeviceControlSessionService.DEFAULT_REASON
                            val started = DeviceControlSessionService.start(appContext, reason)
                            SolinAccessibilityService.showControlProgress(reason)
                            listOf(
                                "command=${command.cleanValue()}",
                                "resultType=available",
                                "status=${if (started) "Succeeded" else "Failed"}",
                                "summary=${if (started) "已启动手机控制会话" else "手机控制会话启动失败"}",
                            )
                        }

                        COMMAND_STOP_CONTROL_SESSION -> {
                            val stopped = DeviceControlSessionService.stop(appContext)
                            SolinAccessibilityService.hideControlProgress()
                            listOf(
                                "command=${command.cleanValue()}",
                                "resultType=available",
                                "status=${if (stopped) "Succeeded" else "Failed"}",
                                "summary=${if (stopped) "已停止手机控制会话" else "手机控制会话停止失败"}",
                            )
                        }

                        COMMAND_OPEN_APP_BY_NAME -> ActionExecutor(appContext).execute(
                            ToolRequest(
                                toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                                arguments = mapOf(
                                    "appName" to intent.getStringExtra(EXTRA_APP_NAME).orEmpty(),
                                ),
                                reason = "Debug eval open app by name.",
                            ),
                        ).toLines(command)

                        COMMAND_OBSERVE -> provider.observeCurrentScreen(
                            maxTextChars = 4_000,
                            maxNodes = 120,
                        ).toLines(command)

                        COMMAND_TAP -> provider.tap(
                            target = intent.getStringExtra(EXTRA_TARGET).orEmpty(),
                            timeoutMillis = intent.timeoutMillis(),
                        ).toLines(
                            command = command,
                            target = intent.getStringExtra(EXTRA_TARGET),
                        )

                        COMMAND_TYPE_TEXT -> provider.typeText(
                            text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
                            target = intent.getStringExtra(EXTRA_TARGET),
                            timeoutMillis = intent.timeoutMillis(),
                            allowClipboardPasteFallback = false,
                        ).toLines(
                            command = command,
                            target = intent.getStringExtra(EXTRA_TARGET),
                        )

                        COMMAND_SUBMIT_SEARCH -> provider.submitSearch(
                            timeoutMillis = intent.timeoutMillis(),
                        ).toLines(command)

                        COMMAND_SCROLL -> provider.scroll(
                            direction = UiScrollDirection.fromSchemaValue(
                                intent.getStringExtra(EXTRA_DIRECTION),
                            ) ?: UiScrollDirection.Down,
                            target = intent.getStringExtra(EXTRA_TARGET),
                            timeoutMillis = intent.timeoutMillis(),
                        ).toLines(
                            command = command,
                            target = intent.getStringExtra(EXTRA_TARGET),
                        )

                        COMMAND_BACK -> provider.pressBack(
                            timeoutMillis = intent.timeoutMillis(),
                        ).toLines(command)

                        COMMAND_WAIT -> provider.waitForScreen(
                            timeoutMillis = intent.timeoutMillis(),
                        ).toLines(
                            command = command,
                            verifySearchQuery = intent.getStringExtra(EXTRA_VERIFY_SEARCH_QUERY),
                            expectedPackageName = intent.getStringExtra(EXTRA_EXPECTED_PACKAGE_NAME),
                            expectedAppName = intent.getStringExtra(EXTRA_EXPECTED_APP_NAME),
                        )

                        COMMAND_MODEL_DRIVEN_APP_SEARCH -> runModelDrivenAppSearch(
                            appContext = appContext,
                            intent = intent,
                        ).toLines(command)

                        else -> listOf(
                            "command=${command.cleanValue()}",
                            "resultType=failed",
                            "reason=unknown_command",
                        )
                    }
                } catch (throwable: Throwable) {
                    listOf(
                        "command=${command.cleanValue()}",
                        "resultType=failed",
                        "reason=${throwable.toResultReason()}",
                    )
                }
                val resultText = (listOf("requestId=${requestId.cleanValue()}") + commandLines)
                    .joinToString(separator = "\n", postfix = "\n")
                File(appContext.filesDir, requestId.resultFileName()).writeText(resultText)
                // Keep a latest-result file for existing manual probes; eval scripts use request files.
                File(appContext.filesDir, LEGACY_RESULT_FILE_NAME).writeText(resultText)
            } finally {
                finishPendingResult()
            }
        }.start()
    }

    private fun Intent.timeoutMillis(): Long =
        getLongExtra(EXTRA_TIMEOUT_MILLIS, DEFAULT_TIMEOUT_MILLIS)
            .coerceIn(100L, 10_000L)

    private fun ScreenStateReadResult.toLines(command: String): List<String> =
        when (this) {
            is ScreenStateReadResult.Available -> listOf(
                "command=${command.cleanValue()}",
                "resultType=available",
            ) + snapshot.toLines(prefix = "")

            is ScreenStateReadResult.PermissionDenied -> listOf(
                "command=${command.cleanValue()}",
                "resultType=permission_denied",
                "reason=${reason.cleanValue()}",
            )

            is ScreenStateReadResult.Failed -> listOf(
                "command=${command.cleanValue()}",
                "resultType=failed",
                "reason=${reason.cleanValue()}",
            )
        }

    private fun UiActionReadResult.toLines(
        command: String,
        target: String? = null,
        verifySearchQuery: String? = null,
        expectedPackageName: String? = null,
        expectedAppName: String? = null,
    ): List<String> =
        when (this) {
            is UiActionReadResult.Available -> listOf(
                "command=${command.cleanValue()}",
                "resultType=available",
                "status=${result.status.name}",
                "summary=${result.summary.cleanValue()}",
                "retryable=${result.retryable}",
                "failureKind=${result.failureKind?.name.orEmpty()}",
                "beforeObservationId=${result.before?.id.orEmpty()}",
                "afterObservationId=${result.after?.id.orEmpty()}",
            ) +
                result.targetResolutionLines(command = command, target = target) +
                searchVerificationLines(
                    verifySearchQuery = verifySearchQuery,
                    expectedPackageName = expectedPackageName,
                    expectedAppName = expectedAppName,
                    result = this,
                ) +
                result.after.toLines(prefix = "after.")

            is UiActionReadResult.PermissionDenied -> listOf(
                "command=${command.cleanValue()}",
                "resultType=permission_denied",
                "reason=${reason.cleanValue()}",
            )

            is UiActionReadResult.Failed -> listOf(
                "command=${command.cleanValue()}",
                "resultType=failed",
                "reason=${reason.cleanValue()}",
                "retryable=$retryable",
                "failureKind=${failureKind.name}",
            )
        }

    private fun UiActionExecutionResult.targetResolutionLines(
        command: String,
        target: String?,
    ): List<String> {
        val kind = resolutionKindFor(command, target) ?: return emptyList()
        val snapshot = before ?: return listOf(
            "targetResolution.available=false",
            "targetResolution.kind=${kind.schemaValue}",
            "targetResolution.target=${target.orEmpty().cleanValue()}",
            "targetResolution.reason=missing_before_snapshot",
        )
        val evidence = UiTargetResolver.explain(
            snapshot = snapshot,
            kind = kind,
            target = target,
        )
        val archivedCandidates = evidence.rankedCandidates.take(MAX_TARGET_RESOLUTION_CANDIDATES)
        return listOf(
            "targetResolution.available=true",
            "targetResolution.kind=${evidence.kind.schemaValue}",
            "targetResolution.target=${evidence.target.orEmpty().cleanValue()}",
            "targetResolution.packageName=${evidence.packageName.orEmpty().cleanValue()}",
            "targetResolution.selectedNodeId=${evidence.selectedNodeId.orEmpty().cleanValue()}",
            "targetResolution.failureKind=${evidence.failureKind?.schemaValue.orEmpty()}",
            "targetResolution.candidateCount=${evidence.rankedCandidates.size}",
            "targetResolution.candidateTotalCount=${evidence.rankedCandidates.size}",
            "targetResolution.archivedCandidateCount=${archivedCandidates.size}",
            "targetResolution.candidatesJson=${DeviceControlEvalResultFormatter.candidatesJson(archivedCandidates)}",
        )
    }

    private fun resolutionKindFor(
        command: String,
        target: String?,
    ): UiTargetKind? =
        when (command) {
            COMMAND_TAP -> UiTargetResolver.kindForTarget(target)
            COMMAND_TYPE_TEXT -> UiTargetKind.EditableField
            COMMAND_SUBMIT_SEARCH -> UiTargetKind.SubmitSearch
            COMMAND_SCROLL -> UiTargetResolver.kindForTarget(target) ?: UiTargetKind.ScrollContainer
            else -> null
        }

    private fun searchVerificationLines(
        verifySearchQuery: String?,
        expectedPackageName: String?,
        expectedAppName: String?,
        result: UiActionReadResult.Available,
    ): List<String> {
        val query = verifySearchQuery?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val primaryVerification = AppSearchResultVerifier.verify(
            before = result.result.before,
            after = result.result.after,
            query = query,
            expectedPackageName = expectedPackageName,
            expectedAppName = expectedAppName,
        )
        val verification = if (primaryVerification.verified) {
            primaryVerification
        } else {
            val beforeVerification = result.result.before?.let { beforeSnapshot ->
                AppSearchResultVerifier.verify(
                    before = null,
                    after = beforeSnapshot,
                    query = query,
                    expectedPackageName = expectedPackageName,
                    expectedAppName = expectedAppName,
                )
            }
            if (beforeVerification?.verified == true) {
                beforeVerification.copy(
                    summary = "搜索结果验证通过：等待前页面已包含结果证据。",
                    evidence = "before_wait_${beforeVerification.evidence}",
                )
            } else {
                primaryVerification
            }
        }
        return listOf(
            "searchVerificationStatus=${if (verification.verified) "verified" else "not_verified"}",
            "searchVerificationEvidence=${verification.evidence.cleanValue()}",
            "searchVerificationSummary=${verification.summary.cleanValue()}",
        )
    }

    private fun runModelDrivenAppSearch(
        appContext: Context,
        intent: Intent,
    ): ModelDrivenAppSearchEvalResult {
        val verificationSpec = ModelDrivenAppSearchEvalSpec(
            verifySearchQuery = intent.nonBlankStringExtra(EXTRA_VERIFY_SEARCH_QUERY),
            expectedPackageName = intent.nonBlankStringExtra(EXTRA_EXPECTED_PACKAGE_NAME),
            expectedAppName = intent.nonBlankStringExtra(EXTRA_EXPECTED_APP_NAME),
        )
        val input = intent.getStringExtra(EXTRA_TEXT)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "打开${intent.nonBlankStringExtra(EXTRA_APP_NAME).orEmpty()}搜索${
                verificationSpec.verifySearchQuery.orEmpty()
            }"
        val maxSteps = intent.getIntExtra(EXTRA_MAX_STEPS, DEFAULT_MODEL_DRIVEN_MAX_STEPS)
            .coerceIn(1, MAX_MODEL_DRIVEN_STEPS)
        val actionModelPath = ModelRepository(appContext).verifiedObservationActionModelPath()
            ?: return ModelDrivenAppSearchEvalResult.failed(
                reason = "missing_verified_observation_action_model",
                input = input,
                verificationSpec = verificationSpec,
            )
        val toolRegistry = ToolRegistry()
        val observationToolRegistry = ToolRegistry.fromSupportedActions(
            MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES,
        )
        val actionPlanningRuntime = HybridActionPlanningRuntime(
            cacheDir = appContext.cacheDir,
            toolRegistry = toolRegistry,
        )
        val observationActionPlanningRuntime = HybridActionPlanningRuntime(
            cacheDir = appContext.cacheDir,
            toolRegistry = observationToolRegistry,
        )
        val modelObservationReplanner = ModelObservationReplanner(
            actionPlanningRuntime = observationActionPlanningRuntime,
            actionModelPathProvider = { actionModelPath },
            toolRegistry = observationToolRegistry,
            maxModelReplans = maxSteps,
        )
        val observationReplanner = CompositeAgentObservationReplanner(
            modelObservationReplanner,
            ModelDrivenAppSearchRecoveryObservationReplanner(
                verifySearchQueryProvider = { verificationSpec.verifySearchQuery },
                expectedPackageNameProvider = { verificationSpec.expectedPackageName },
                expectedAppNameProvider = { verificationSpec.expectedAppName },
            ),
        )
        val orchestrator = AssistantOrchestrator(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionPlanningRuntime,
            toolRegistry = toolRegistry,
            traceStore = InMemoryAgentTraceStore(),
            skillRuntime = BuiltInSkillRuntime(
                appSearchPlanningModeProvider = { AppSearchPlanningMode.ModelDrivenBootstrap },
            ),
            observationReplanner = observationReplanner,
            deviceControlSessionFinisher = { DeviceControlSessionService.stop(appContext) },
        )
        return try {
            runModelDrivenAppSearchLoop(
                input = input,
                orchestrator = orchestrator,
                executor = modelDrivenEvalToolExecutor(appContext, toolRegistry),
                maxSteps = maxSteps,
                actionModelPath = actionModelPath,
                verificationSpec = verificationSpec,
                controlProvider = AndroidCurrentScreenControlProvider(),
                modelReplanDiagnosticProvider = { modelObservationReplanner.lastDiagnosticSnapshot() },
                modelReplanDiagnosticsProvider = { modelObservationReplanner.diagnosticSnapshots() },
            )
        } finally {
            orchestrator.close()
            observationActionPlanningRuntime.close()
        }
    }

    private fun runModelDrivenAppSearchLoop(
        input: String,
        orchestrator: AssistantOrchestrator,
        executor: ToolExecutor,
        maxSteps: Int,
        actionModelPath: String,
        verificationSpec: ModelDrivenAppSearchEvalSpec,
        controlProvider: AndroidCurrentScreenControlProvider,
        modelReplanDiagnosticProvider: () -> ModelObservationReplanDiagnostic? = { null },
        modelReplanDiagnosticsProvider: () -> List<ModelObservationReplanDiagnostic> = { emptyList() },
    ): ModelDrivenAppSearchEvalResult {
        val steps = mutableListOf<ModelDrivenAppSearchEvalStep>()
        val started = orchestrator.route(
            input = input,
            installedCapabilities = setOf(ModelCapability.MobileAction),
            memoryEnabled = false,
            actionModelPath = actionModelPath,
            options = AgentRunOptions(reduceDeviceActionConfirmations = true),
            installedCapabilityProfiles = listOf(ModelCatalog.profileForModelId(MOBILE_ACTION_MODEL_ID)),
        )
        var action = started.asEvalAction()
            ?: return ModelDrivenAppSearchEvalResult.failed(
                reason = started.evalFailureReason(),
                input = input,
                verificationSpec = verificationSpec,
            )
        repeat(maxSteps) {
            val runId = action.runId
                ?: return ModelDrivenAppSearchEvalResult.failed(
                    "missing_run_id",
                    input,
                    steps,
                    verificationSpec,
                    modelReplanDiagnostic = modelReplanDiagnosticProvider(),
                    modelReplanDiagnostics = modelReplanDiagnosticsProvider(),
                )
            val guardedRequest = action.request.withModelDrivenEvalGuards(
                verificationSpec = verificationSpec,
                injectSearchVerification = action.plannedByModel,
            )
            val request = guardedRequest.request
            if (action.requiresUserConfirmation) {
                val confirmedRun = orchestrator.confirmToolRequest(runId, action.request.id)
                    ?: return ModelDrivenAppSearchEvalResult.failed(
                        "confirmation_failed",
                        input,
                        steps,
                        verificationSpec,
                        modelReplanDiagnostic = modelReplanDiagnosticProvider(),
                        modelReplanDiagnostics = modelReplanDiagnosticsProvider(),
                    )
                if (confirmedRun.state != AgentRunState.ExecutingTool) {
                    return ModelDrivenAppSearchEvalResult.failed(
                        reason = "confirmation_state_${confirmedRun.state.name}",
                            input = input,
                            steps = steps,
                            verificationSpec = verificationSpec,
                            modelReplanDiagnostic = modelReplanDiagnosticProvider(),
                            modelReplanDiagnostics = modelReplanDiagnosticsProvider(),
                        )
                }
            }
            var result = executor.execute(request)
            steps += request.toModelDrivenEvalStep(
                result = result,
                plannedByModel = action.plannedByModel,
                evalGuardApplied = guardedRequest.guardApplied,
                recoveryKind = action.recoveryKind,
            )
            if (
                maybeRecoverLaunchConfirmation(
                    failedRequest = request,
                    failedResult = result,
                    verificationSpec = verificationSpec,
                    controlProvider = controlProvider,
                    steps = steps,
                )
            ) {
                result = executor.execute(request)
                steps += request.toModelDrivenEvalStep(
                    result = result,
                    plannedByModel = false,
                    evalGuardApplied = false,
                    recoveryKind = null,
                )
            }
            val observation = orchestrator.observeToolResult(runId, result)
                ?: return ModelDrivenAppSearchEvalResult.failed(
                    "observation_failed",
                    input,
                    steps,
                    verificationSpec,
                    modelReplanDiagnostic = modelReplanDiagnosticProvider(),
                    modelReplanDiagnostics = modelReplanDiagnosticsProvider(),
                )
            when (val decision = observation.decision) {
                AgentObservationDecision.Complete -> {
                    verificationSpec.completionFailureReason(steps)?.let { reason ->
                        return ModelDrivenAppSearchEvalResult.failed(
                            reason = reason,
                            input = input,
                            steps = steps,
                            verificationSpec = verificationSpec,
                            finalState = observation.run.state.name,
                            modelReplanDiagnostic = modelReplanDiagnosticProvider(),
                            modelReplanDiagnostics = modelReplanDiagnosticsProvider(),
                        )
                    }
                    return ModelDrivenAppSearchEvalResult.passed(
                        input = input,
                        steps = steps,
                        finalState = observation.run.state.name,
                        verificationSpec = verificationSpec,
                        modelReplanDiagnostic = modelReplanDiagnosticProvider(),
                        modelReplanDiagnostics = modelReplanDiagnosticsProvider(),
                    )
                }

                is AgentObservationDecision.PlanNextTool -> {
                    action = decision.plan.toEvalAction(
                        runId = observation.run.id,
                        requiresUserConfirmation = observation.run.state == AgentRunState.AwaitingUserConfirmation,
                    )
                }

                is AgentObservationDecision.RetryTool -> {
                    action = ModelDrivenEvalAction(
                        runId = observation.run.id,
                        request = decision.request,
                        plannedByModel = false,
                        requiresUserConfirmation = false,
                        recoveryKind = null,
                    )
                }

                is AgentObservationDecision.Fail ->
                    return ModelDrivenAppSearchEvalResult.failed(
                        decision.reason,
                        input,
                        steps,
                        verificationSpec,
                        modelReplanDiagnostic = modelReplanDiagnosticProvider(),
                        modelReplanDiagnostics = modelReplanDiagnosticsProvider(),
                    )

                AgentObservationDecision.Cancel ->
                    return ModelDrivenAppSearchEvalResult.failed(
                        "cancelled",
                        input,
                        steps,
                        verificationSpec,
                        modelReplanDiagnostic = modelReplanDiagnosticProvider(),
                        modelReplanDiagnostics = modelReplanDiagnosticsProvider(),
                    )

                is AgentObservationDecision.ContinueWithModel ->
                    return ModelDrivenAppSearchEvalResult.failed(
                        reason = "unexpected_model_text_continuation",
                        input = input,
                        steps = steps,
                        verificationSpec = verificationSpec,
                        modelReplanDiagnostic = modelReplanDiagnosticProvider(),
                        modelReplanDiagnostics = modelReplanDiagnosticsProvider(),
                    )

                is AgentObservationDecision.PlanToolBatch ->
                    return ModelDrivenAppSearchEvalResult.failed(
                        "unexpected_tool_batch",
                        input,
                        steps,
                        verificationSpec,
                        modelReplanDiagnostic = modelReplanDiagnosticProvider(),
                        modelReplanDiagnostics = modelReplanDiagnosticsProvider(),
                    )
            }
        }
        return ModelDrivenAppSearchEvalResult.failed(
            "step_limit_exceeded",
            input,
            steps,
            verificationSpec,
            modelReplanDiagnostic = modelReplanDiagnosticProvider(),
            modelReplanDiagnostics = modelReplanDiagnosticsProvider(),
        )
    }

    private fun ToolRequest.toModelDrivenEvalStep(
        result: ToolResult,
        plannedByModel: Boolean,
        evalGuardApplied: Boolean,
        recoveryKind: String?,
    ): ModelDrivenAppSearchEvalStep {
        val progressData = AppSearchProgressEvidence.fromData(
            result.data + mapOf(
                "toolName" to toolName,
                "status" to result.status.name,
            ),
        ).toData()
        return ModelDrivenAppSearchEvalStep(
            toolName = toolName,
            plannedByModel = plannedByModel,
            status = result.status.name,
            summary = result.summary,
            errorCode = result.error?.code?.name,
            failureKind = result.data["failureKind"],
            searchVerificationStatus = result.data["searchVerificationStatus"],
            target = arguments["target"],
            direction = arguments["direction"],
            textLength = arguments["text"]?.length,
            verifySearchQuery = arguments["verifySearchQuery"],
            expectedPackageName = arguments["expectedPackageName"] ?: arguments["targetPackageName"],
            expectedAppName = arguments["expectedAppName"],
            uiActionOutcome = result.data["uiActionOutcome"] ?: progressData["uiActionOutcome"],
            uiActionOutcomeReason = result.data["uiActionOutcomeReason"] ?: progressData["uiActionOutcomeReason"],
            appSearchProgressStage = result.data["appSearchProgressStage"] ?: progressData["appSearchProgressStage"],
            evalGuardApplied = evalGuardApplied,
            recoveryKind = recoveryKind,
        )
    }

    private fun maybeRecoverLaunchConfirmation(
        failedRequest: ToolRequest,
        failedResult: ToolResult,
        verificationSpec: ModelDrivenAppSearchEvalSpec,
        controlProvider: AndroidCurrentScreenControlProvider,
        steps: MutableList<ModelDrivenAppSearchEvalStep>,
    ): Boolean {
        if (failedRequest.toolName != MobileActionFunctions.UI_WAIT) return false
        if (failedResult.data["failureKind"] != UiActionFailureKind.AppNotForeground.schemaValue) return false
        val expectedAppName = verificationSpec.expectedAppName?.takeIf { it.isNotBlank() } ?: return false
        val snapshot = when (
            val observation = controlProvider.observeCurrentScreen(
                maxTextChars = 2_000,
                maxNodes = 80,
            )
        ) {
            is ScreenStateReadResult.Available -> observation.snapshot
            else -> return false
        }
        if (!snapshot.isMiuiLaunchConfirmationFor(expectedAppName)) return false
        val recovery = controlProvider.tap(
            target = "本次允许",
            timeoutMillis = 2_500,
        )
        steps += recovery.toModelDrivenLaunchRecoveryStep()
        return (recovery as? UiActionReadResult.Available)
            ?.result
            ?.status == UiActionStatus.Succeeded
    }

    private fun ScreenStateSnapshot.isMiuiLaunchConfirmationFor(expectedAppName: String): Boolean {
        if (packageName != MIUI_SECURITY_CENTER_PACKAGE) return false
        val screenText = (
            listOf(textSummary) + nodes.flatMap { node ->
                listOf(node.text, node.contentDescription)
            }
        ).joinToString(separator = " ")
        return "想要打开" in screenText &&
            expectedAppName in screenText &&
            "本次允许" in screenText
    }

    private fun UiActionReadResult.toModelDrivenLaunchRecoveryStep(): ModelDrivenAppSearchEvalStep =
        when (this) {
            is UiActionReadResult.Available -> {
                val progressData = launchRecoveryProgressData(
                    status = result.status.name.lowercase(),
                    failureKind = result.failureKind?.schemaValue,
                )
                ModelDrivenAppSearchEvalStep(
                    toolName = MODEL_DRIVEN_LAUNCH_CONFIRMATION_RECOVERY_TOOL,
                    plannedByModel = false,
                    status = result.status.name,
                    summary = result.summary,
                    failureKind = result.failureKind?.schemaValue,
                    searchVerificationStatus = null,
                    target = "本次允许",
                    uiActionOutcome = progressData["uiActionOutcome"],
                    uiActionOutcomeReason = progressData["uiActionOutcomeReason"],
                    appSearchProgressStage = progressData["appSearchProgressStage"],
                    recoveryKind = "launch_confirmation",
                )
            }

            is UiActionReadResult.PermissionDenied -> {
                val progressData = launchRecoveryProgressData(
                    status = "failed",
                    failureKind = UiActionFailureKind.PermissionMissing.schemaValue,
                )
                ModelDrivenAppSearchEvalStep(
                    toolName = MODEL_DRIVEN_LAUNCH_CONFIRMATION_RECOVERY_TOOL,
                    plannedByModel = false,
                    status = "Failed",
                    summary = reason,
                    failureKind = UiActionFailureKind.PermissionMissing.schemaValue,
                    searchVerificationStatus = null,
                    target = "本次允许",
                    uiActionOutcome = progressData["uiActionOutcome"],
                    uiActionOutcomeReason = progressData["uiActionOutcomeReason"],
                    appSearchProgressStage = progressData["appSearchProgressStage"],
                    recoveryKind = "launch_confirmation",
                )
            }

            is UiActionReadResult.Failed -> {
                val progressData = launchRecoveryProgressData(
                    status = "failed",
                    failureKind = failureKind.schemaValue,
                )
                ModelDrivenAppSearchEvalStep(
                    toolName = MODEL_DRIVEN_LAUNCH_CONFIRMATION_RECOVERY_TOOL,
                    plannedByModel = false,
                    status = "Failed",
                    summary = reason,
                    failureKind = failureKind.schemaValue,
                    searchVerificationStatus = null,
                    target = "本次允许",
                    uiActionOutcome = progressData["uiActionOutcome"],
                    uiActionOutcomeReason = progressData["uiActionOutcomeReason"],
                    appSearchProgressStage = progressData["appSearchProgressStage"],
                    recoveryKind = "launch_confirmation",
                )
            }
        }

    private fun launchRecoveryProgressData(
        status: String,
        failureKind: String?,
    ): Map<String, String> =
        AppSearchProgressEvidence.fromData(
            buildMap {
                put("actionType", "tap")
                put("status", status)
                failureKind?.let { value -> put("failureKind", value) }
            },
        ).toData()

    private fun ToolRequest.withModelDrivenEvalGuards(
        verificationSpec: ModelDrivenAppSearchEvalSpec,
        injectSearchVerification: Boolean,
    ): ModelDrivenEvalGuardedRequest {
        if (!verificationSpec.hasGuards) {
            return ModelDrivenEvalGuardedRequest(request = this, guardApplied = false)
        }
        val guardedArguments = arguments.toMutableMap()
        if (toolName in MODEL_DRIVEN_FOREGROUND_GUARDED_TOOLS) {
            verificationSpec.expectedPackageName
                ?.takeIf { "expectedPackageName" !in guardedArguments && "targetPackageName" !in guardedArguments }
                ?.let { packageName -> guardedArguments["expectedPackageName"] = packageName }
        }
        if (toolName == MobileActionFunctions.UI_WAIT && injectSearchVerification) {
            verificationSpec.verifySearchQuery
                ?.takeIf { "verifySearchQuery" !in guardedArguments }
                ?.let { query -> guardedArguments["verifySearchQuery"] = query }
            verificationSpec.expectedAppName
                ?.takeIf { "expectedAppName" !in guardedArguments }
                ?.let { appName -> guardedArguments["expectedAppName"] = appName }
        }
        if (guardedArguments == arguments) {
            return ModelDrivenEvalGuardedRequest(request = this, guardApplied = false)
        }
        return ModelDrivenEvalGuardedRequest(
            request = copy(arguments = guardedArguments),
            guardApplied = true,
        )
    }

    private fun modelDrivenEvalToolExecutor(
        appContext: Context,
        toolRegistry: ToolRegistry,
    ): ToolExecutor =
        ValidatingToolExecutor(
            delegate = RoutingToolExecutor(
                calendarAvailabilityProvider = AndroidCalendarAvailabilityProvider(appContext),
                foregroundAppProvider = AndroidForegroundAppProvider(appContext),
                contactSummaryProvider = AndroidContactSummaryProvider(appContext),
                notificationSummaryProvider = AndroidNotificationSummaryProvider(appContext),
                recentFileProvider = AndroidRecentFileProvider(appContext),
                webSearchProvider = OkHttpWebSearchProvider(),
                delegate = ActionExecutor(
                    context = appContext,
                    toolRegistry = toolRegistry,
                ),
                recentImageTextProvider = AndroidRecentImageTextProvider(appContext),
                currentScreenTextProvider = AndroidCurrentScreenTextProvider(),
                currentScreenControlProvider = AndroidCurrentScreenControlProvider(),
                toolRegistry = toolRegistry,
            ),
            registry = toolRegistry,
        )

    private fun AssistantRoute.asEvalAction(): ModelDrivenEvalAction? =
        when (this) {
            is AssistantRoute.Action -> {
                val request = toolRequest ?: ToolRequest(
                    toolName = draft.functionName,
                    arguments = draft.parameters,
                    reason = draft.summary,
                )
                ModelDrivenEvalAction(
                    runId = runId,
                    request = request,
                    plannedByModel = plannedByModel,
                    requiresUserConfirmation = requiresUserConfirmation,
                )
            }

            else -> null
        }

    private fun AssistantRoute.evalFailureReason(): String =
        when (this) {
            is AssistantRoute.Chat -> "unexpected_chat_route"
            is AssistantRoute.MissingModel -> "missing_model_${capability.name}"
            is AssistantRoute.ToolRejected -> "tool_rejected_${summary.cleanValue()}"
            is AssistantRoute.Action -> "missing_action_request"
        }

    private fun AgentPlan.UseTool.toEvalAction(
        runId: String,
        requiresUserConfirmation: Boolean,
    ): ModelDrivenEvalAction =
        ModelDrivenEvalAction(
            runId = runId,
            request = request,
            plannedByModel = plannedByModel,
            requiresUserConfirmation = requiresUserConfirmation,
            recoveryKind = request.modelDrivenAppSearchRecoveryKind(),
        )

    private fun ModelDrivenAppSearchEvalResult.toLines(command: String): List<String> {
        val modelReplanTrace = modelReplanDiagnostics.ifEmpty {
            listOfNotNull(modelReplanDiagnostic)
        }
        val latestModelReplanDiagnostic = modelReplanDiagnostic
            ?: modelReplanTrace.lastOrNull { diagnostic -> diagnostic.attempted }
            ?: modelReplanTrace.lastOrNull()
        return listOf(
            "command=${command.cleanValue()}",
            "resultType=model_driven_app_search",
            "status=${if (passed) "Succeeded" else "Failed"}",
            "reason=${reason.cleanValue()}",
            "input=${input.cleanValue()}",
            "finalState=${finalState.cleanValue()}",
            "verificationRequired=${verificationSpec.requiresSearchVerification}",
            "verifySearchQuery=${verificationSpec.verifySearchQuery.orEmpty().cleanValue()}",
            "expectedPackageName=${verificationSpec.expectedPackageName.orEmpty().cleanValue()}",
            "expectedAppName=${verificationSpec.expectedAppName.orEmpty().cleanValue()}",
            "stepCount=${steps.size}",
            "modelPlannedStepCount=${steps.count { it.plannedByModel }}",
            "recoveryStepCount=${steps.count { it.recoveryKind != null }}",
            "tools=${steps.joinToString(separator = ",") { it.toolName }.cleanValue()}",
            "toolStatuses=${steps.joinToString(separator = ",") { it.status }.cleanValue()}",
            "recoveryKinds=${steps.mapNotNull { it.recoveryKind }.joinToString(separator = ",").cleanValue()}",
            "evalGuardAppliedCount=${steps.count { it.evalGuardApplied }}",
            "modelReplanTraceCount=${modelReplanTrace.size}",
            "modelReplanAttemptCount=${modelReplanTrace.count { it.attempted }}",
            "modelReplanAcceptedCount=${modelReplanTrace.count { it.reason == "accepted" }}",
            "modelReplanRejectedCount=${modelReplanTrace.count { it.attempted && it.reason != "accepted" }}",
            "modelReplanAttempted=${modelReplanTrace.any { it.attempted }}",
            "modelReplanReason=${latestModelReplanDiagnostic?.reason.orEmpty().cleanValue()}",
            "modelReplanPromptIndex=${latestModelReplanDiagnostic?.promptIndex ?: -1}",
            "modelReplanUsedModel=${latestModelReplanDiagnostic?.usedModel ?: false}",
            "modelReplanModelAttempted=${latestModelReplanDiagnostic?.modelAttempted ?: false}",
            "modelReplanModelPlanKind=${latestModelReplanDiagnostic?.modelPlanKind.orEmpty().cleanValue()}",
            "modelReplanModelFailureReason=${latestModelReplanDiagnostic?.modelFailureReason.orEmpty().cleanValue()}",
            "modelReplanModelOutputPreview=${latestModelReplanDiagnostic?.modelOutputPreview.orEmpty().cleanValue()}",
            "uiActionOutcome=${steps.latestMeaningful { it.uiActionOutcome }.cleanValue()}",
            "uiActionOutcomeReason=${steps.latestMeaningful { it.uiActionOutcomeReason }.cleanValue()}",
            "appSearchProgressStage=${steps.latestMeaningful { it.appSearchProgressStage }.cleanValue()}",
            "searchVerificationStatus=${
                steps.lastOrNull { it.searchVerificationStatus != null }?.searchVerificationStatus.orEmpty()
            }",
        ) + modelReplanTrace.flatMapIndexed { index, diagnostic ->
            val prefix = "modelReplanTrace_${index}_"
            listOf(
                "${prefix}attempted=${diagnostic.attempted}",
                "${prefix}reason=${diagnostic.reason.cleanValue()}",
                "${prefix}promptIndex=${diagnostic.promptIndex ?: -1}",
                "${prefix}usedModel=${diagnostic.usedModel}",
                "${prefix}modelAttempted=${diagnostic.modelAttempted}",
                "${prefix}modelPlanKind=${diagnostic.modelPlanKind.orEmpty().cleanValue()}",
                "${prefix}modelFailureReason=${diagnostic.modelFailureReason.orEmpty().cleanValue()}",
                "${prefix}modelOutputPreview=${diagnostic.modelOutputPreview.orEmpty().cleanValue()}",
            )
        } + steps.flatMapIndexed { index, step ->
            val prefix = "step_${index}_"
            listOf(
                "${prefix}tool=${step.toolName.cleanValue()}",
                "${prefix}plannedByModel=${step.plannedByModel}",
                "${prefix}status=${step.status.cleanValue()}",
                "${prefix}summary=${step.summary.cleanValue()}",
                "${prefix}errorCode=${step.errorCode.orEmpty().cleanValue()}",
                "${prefix}failureKind=${step.failureKind.orEmpty().cleanValue()}",
                "${prefix}target=${step.target.orEmpty().cleanValue()}",
                "${prefix}direction=${step.direction.orEmpty().cleanValue()}",
                "${prefix}textLength=${step.textLength ?: 0}",
                "${prefix}verifySearchQuery=${step.verifySearchQuery.orEmpty().cleanValue()}",
                "${prefix}expectedPackageName=${step.expectedPackageName.orEmpty().cleanValue()}",
                "${prefix}expectedAppName=${step.expectedAppName.orEmpty().cleanValue()}",
                "${prefix}searchVerificationStatus=${step.searchVerificationStatus.orEmpty().cleanValue()}",
                "${prefix}uiActionOutcome=${step.uiActionOutcome.orEmpty().cleanValue()}",
                "${prefix}uiActionOutcomeReason=${step.uiActionOutcomeReason.orEmpty().cleanValue()}",
                "${prefix}appSearchProgressStage=${step.appSearchProgressStage.orEmpty().cleanValue()}",
                "${prefix}evalGuardApplied=${step.evalGuardApplied}",
                "${prefix}recoveryKind=${step.recoveryKind.orEmpty().cleanValue()}",
            )
        }
    }

    private fun ScreenStateSnapshot?.toLines(prefix: String): List<String> {
        if (this == null) {
            return listOf("${prefix}snapshot=false")
        }
        return listOf(
            "${prefix}snapshot=true",
            "${prefix}observationId=${id.cleanValue()}",
            "${prefix}packageName=${packageName.orEmpty().cleanValue()}",
            "${prefix}nodeCount=$nodeCount",
            "${prefix}actionableNodeCount=$actionableNodeCount",
            "${prefix}hasBounds=${nodes.any { node -> node.bounds != null }}",
            "${prefix}hasClickable=${nodes.any { node -> node.clickable }}",
            "${prefix}hasEditable=${nodes.any { node -> node.editable }}",
            "${prefix}hasScrollable=${nodes.any { node -> node.scrollable }}",
            "${prefix}truncated=$truncated",
            "${prefix}textSummary=${textSummary.cleanValue()}",
        )
    }

    private fun ToolResult.toLines(command: String): List<String> =
        listOf(
            "command=${command.cleanValue()}",
            "resultType=tool_result",
            "status=${status.name}",
            "summary=${summary.cleanValue()}",
            "retryable=$retryable",
            "errorCode=${error?.code?.name.orEmpty()}",
            "errorMessage=${error?.message?.cleanValue().orEmpty()}",
        ) + data.entries
            .sortedBy { it.key }
            .map { (key, value) -> "data.${key.cleanValue()}=${value.cleanValue()}" }

    private fun String.cleanValue(): String =
        DeviceControlEvalResultFormatter.cleanValue(this)

    private fun List<ModelDrivenAppSearchEvalStep>.latestMeaningful(
        selector: (ModelDrivenAppSearchEvalStep) -> String?,
    ): String =
        asReversed()
            .mapNotNull { step -> selector(step)?.takeIf { value -> value.isNotBlank() && value != "unknown" } }
            .firstOrNull()
            .orEmpty()

    private fun String.resultFileName(): String =
        DeviceControlEvalResultFormatter.resultFileName(this)

    private fun Intent.nonBlankStringExtra(name: String): String? =
        getStringExtra(name)?.trim()?.takeIf { it.isNotBlank() }

    private fun Throwable.toResultReason(): String =
        "${javaClass.simpleName}:${message.orEmpty()}".cleanValue()

    private companion object {
        const val EXTRA_COMMAND = "command"
        const val EXTRA_TARGET = "target"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_TIMEOUT_MILLIS = "timeoutMillis"
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_VERIFY_SEARCH_QUERY = "verifySearchQuery"
        const val EXTRA_EXPECTED_PACKAGE_NAME = "expectedPackageName"
        const val EXTRA_EXPECTED_APP_NAME = "expectedAppName"
        const val EXTRA_APP_NAME = "appName"
        const val EXTRA_MAX_STEPS = "maxSteps"
        const val ACTION_DEVICE_CONTROL_EVAL = "com.bytedance.zgx.solin.debug.DEVICE_CONTROL_EVAL"
        const val COMMAND_START_CONTROL_SESSION = "start_control_session"
        const val COMMAND_STOP_CONTROL_SESSION = "stop_control_session"
        const val COMMAND_OPEN_APP_BY_NAME = "open_app_by_name"
        const val COMMAND_OBSERVE = "observe"
        const val COMMAND_TAP = "tap"
        const val COMMAND_TYPE_TEXT = "type_text"
        const val COMMAND_SUBMIT_SEARCH = "submit_search"
        const val COMMAND_SCROLL = "scroll"
        const val COMMAND_BACK = "back"
        const val COMMAND_WAIT = "wait"
        const val COMMAND_MODEL_DRIVEN_APP_SEARCH = "model_driven_app_search"
        const val LEGACY_RESULT_FILE_NAME = "device_control_eval_result.properties"
        const val DEFAULT_TIMEOUT_MILLIS = 1_500L
        const val MAX_TARGET_RESOLUTION_CANDIDATES = 5
        const val DEFAULT_MODEL_DRIVEN_MAX_STEPS = 12
        const val MAX_MODEL_DRIVEN_STEPS = 24
        const val MIUI_SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"
        const val MODEL_DRIVEN_LAUNCH_CONFIRMATION_RECOVERY_TOOL = "debug_tap_launch_confirmation"
        val MODEL_DRIVEN_FOREGROUND_GUARDED_TOOLS = setOf(
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_SCROLL,
            MobileActionFunctions.UI_WAIT,
        )
    }
}

private data class ModelDrivenEvalAction(
    val runId: String?,
    val request: ToolRequest,
    val plannedByModel: Boolean,
    val requiresUserConfirmation: Boolean,
    val recoveryKind: String? = null,
)

private data class ModelDrivenEvalGuardedRequest(
    val request: ToolRequest,
    val guardApplied: Boolean,
)

private data class ModelDrivenAppSearchEvalSpec(
    val verifySearchQuery: String? = null,
    val expectedPackageName: String? = null,
    val expectedAppName: String? = null,
) {
    val requiresSearchVerification: Boolean
        get() = !verifySearchQuery.isNullOrBlank()

    val hasGuards: Boolean
        get() = !verifySearchQuery.isNullOrBlank() ||
            !expectedPackageName.isNullOrBlank() ||
            !expectedAppName.isNullOrBlank()

    fun completionFailureReason(steps: List<ModelDrivenAppSearchEvalStep>): String? {
        if (!requiresSearchVerification) return null
        return if (steps.lastOrNull { it.searchVerificationStatus != null }
                ?.searchVerificationStatus == "verified"
        ) {
            null
        } else {
            "result_not_verified"
        }
    }
}

private data class ModelDrivenAppSearchEvalStep(
    val toolName: String,
    val plannedByModel: Boolean,
    val status: String,
    val summary: String,
    val errorCode: String? = null,
    val failureKind: String? = null,
    val searchVerificationStatus: String?,
    val target: String? = null,
    val direction: String? = null,
    val textLength: Int? = null,
    val verifySearchQuery: String? = null,
    val expectedPackageName: String? = null,
    val expectedAppName: String? = null,
    val uiActionOutcome: String? = null,
    val uiActionOutcomeReason: String? = null,
    val appSearchProgressStage: String? = null,
    val evalGuardApplied: Boolean = false,
    val recoveryKind: String? = null,
)

private data class ModelDrivenAppSearchEvalResult(
    val passed: Boolean,
    val reason: String,
    val input: String,
    val steps: List<ModelDrivenAppSearchEvalStep> = emptyList(),
    val finalState: String = "",
    val verificationSpec: ModelDrivenAppSearchEvalSpec = ModelDrivenAppSearchEvalSpec(),
    val modelReplanDiagnostic: ModelObservationReplanDiagnostic? = null,
    val modelReplanDiagnostics: List<ModelObservationReplanDiagnostic> = emptyList(),
) {
    companion object {
        fun passed(
            input: String,
            steps: List<ModelDrivenAppSearchEvalStep>,
            finalState: String,
            verificationSpec: ModelDrivenAppSearchEvalSpec,
            modelReplanDiagnostic: ModelObservationReplanDiagnostic? = null,
            modelReplanDiagnostics: List<ModelObservationReplanDiagnostic> = emptyList(),
        ): ModelDrivenAppSearchEvalResult =
            ModelDrivenAppSearchEvalResult(
                passed = true,
                reason = "",
                input = input,
                steps = steps,
                finalState = finalState,
                verificationSpec = verificationSpec,
                modelReplanDiagnostic = modelReplanDiagnostic,
                modelReplanDiagnostics = modelReplanDiagnostics,
            )

        fun failed(
            reason: String,
            input: String,
            steps: List<ModelDrivenAppSearchEvalStep> = emptyList(),
            verificationSpec: ModelDrivenAppSearchEvalSpec = ModelDrivenAppSearchEvalSpec(),
            finalState: String = "",
            modelReplanDiagnostic: ModelObservationReplanDiagnostic? = null,
            modelReplanDiagnostics: List<ModelObservationReplanDiagnostic> = emptyList(),
        ): ModelDrivenAppSearchEvalResult =
            ModelDrivenAppSearchEvalResult(
                passed = false,
                reason = reason,
                input = input,
                steps = steps,
                finalState = finalState,
                verificationSpec = verificationSpec,
                modelReplanDiagnostic = modelReplanDiagnostic,
                modelReplanDiagnostics = modelReplanDiagnostics,
            )
    }
}
