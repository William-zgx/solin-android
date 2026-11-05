package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AgentTraceRunUiSummary
import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatImageAttachment
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.orchestration.AgentModelObservationResult
import com.bytedance.zgx.solin.runtime.RemoteChatEvent
import com.bytedance.zgx.solin.runtime.RemoteChatRuntime
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.LocalImageAttachment
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.ModelHealth
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.StreamingAssistantUpdateCoalescer
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.logging.solinD
import com.bytedance.zgx.solin.logging.solinE
import com.bytedance.zgx.solin.logging.solinW
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_REMOTE
import com.bytedance.zgx.solin.modelProfile
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.orchestration.PromptPrivacyPlan
import com.bytedance.zgx.solin.orchestration.RunDataReceipt
import com.bytedance.zgx.solin.runtime.AdaptiveGenerationPolicy
import com.bytedance.zgx.solin.runtime.AdaptiveGenerationPolicyInput
import com.bytedance.zgx.solin.runtime.GenerationQualityDecision
import com.bytedance.zgx.solin.runtime.GenerationQualityIssue
import com.bytedance.zgx.solin.runtime.GenerationRuntimeKind
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import com.bytedance.zgx.solin.runtime.LocalModelRequest
import com.bytedance.zgx.solin.runtime.ModelOutputQualityGuard
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.bytedance.zgx.solin.logging.cleanMessage

private val CONTEXT_OVERFLOW_MARKERS = listOf(
    "context_length",
    "context length",
    "maximum context",
    "prompt is too long",
    "prompt too long",
    "context window",
    "exceeded",
    "上下文过长",
    "context_overflow",
)

/**
 * Owns local/remote generation streaming, output-quality guard application, stop cleanup,
 * and remote context-overflow retry helpers.
 *
 * Callers keep orchestration and job
 * lifecycle ownership; this collaborator only mutates UI/session state for generation I/O.
 */
/** Mutable state of one guarded generation turn, shared across its streaming callbacks. */
internal class GuardedTurn(val streamingUpdates: StreamingAssistantUpdateCoalescer) {
    val partial = StringBuilder()
    var observation: AgentModelObservationResult? = null
    var qualityDecision: GenerationQualityDecision? = null
}

internal class ChatGenerationSupport(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val runtime: LiteRtRuntime,
    private val remoteRuntime: RemoteChatRuntime,
    private val assistantOrchestrator: AssistantRouter,
    private val outputQualityGuard: ModelOutputQualityGuard,
    private val runtimeLock: Mutex,
    private val persistActiveSessionFromUi: () -> Unit,
    private val persistMessagesAndRebuildMemory: (
        messages: List<ChatMessage>,
        memoryUserMessage: ChatMessage?,
    ) -> Unit,
    private val loadAgentTraceRuns: () -> List<AgentTraceRunUiSummary>,
    private val activeRunTimelineFor: (String?) -> List<RunTimelineItemUiSummary>,
) {
    suspend fun collectLocalRuntimeResponse(
        promptForModel: String,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        imageAttachments: List<LocalImageAttachment> = emptyList(),
        onChunk: (String) -> Unit,
    ) {
        val policyDecision = AdaptiveGenerationPolicy.decide(
            AdaptiveGenerationPolicyInput(
                preferredBackend = uiState.value.backend,
                contextWindowTokens = uiState.value.localMaxTotalTokens,
                lastGenerationStats = uiState.value.lastGenerationStatsForAdaptivePolicy(),
                qualityIssue = uiState.value.lastOutputQualityIssueForAdaptivePolicy(),
                requestedImageCount = imageAttachments.size,
            ),
        )
        val effectiveImageAttachments = imageAttachments.take(policyDecision.maxImages)
        runtimeLock.withLock {
            runtime.recreateConversationForSend(
                history = history,
                prompt = promptForModel,
                parameters = parameters,
                maxInputTokens = policyDecision.maxInputTokens,
            )
            runtime.send(
                LocalModelRequest(
                    prompt = promptForModel,
                    imageAttachments = effectiveImageAttachments,
                ),
            ).collect { chunk ->
                onChunk(chunk)
            }
        }
    }

    /**
     * Streams one remote turn with [tools] into [turn], stopping on the first output-quality
     * violation and recording tool calls as the turn's observation. Retries once on context
     * overflow (see [sendRemoteWithOverflowRetry]). Returns the history actually sent.
     */
    suspend fun streamRemoteTurn(
        turn: GuardedTurn,
        runId: String?,
        prompt: String,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        config: RemoteModelConfig,
        tools: List<ToolSpec>,
        imageAttachments: List<ChatImageAttachment> = emptyList(),
        acceptDelta: (String) -> Boolean = { true },
        onServingStarted: () -> Unit,
    ): List<ChatMessage> {
        val tokenBudget = config.modelProfile().contextWindowTokens
            ?.let { window -> (window * SolinConstants.Ui.REMOTE_COMPACTION_BUDGET_RATIO).toInt() }
            ?: Int.MAX_VALUE
        val modelId = config.modelProfile().id
        return sendRemoteWithOverflowRetry(runId = runId, history = history, tokenBudget = tokenBudget) { historyToSend ->
            remoteRuntime.sendWithTools(
                prompt = prompt,
                history = historyToSend,
                parameters = parameters,
                config = config,
                tools = tools,
                imageAttachments = imageAttachments,
            ).collect { event ->
                onServingStarted()
                if (turn.qualityDecision != null) return@collect
                when (event) {
                    is RemoteChatEvent.TextDelta -> {
                        if (turn.observation == null && acceptDelta(event.text)) {
                            val decision = appendGuardedGenerationChunk(
                                partial = turn.partial,
                                chunk = event.text,
                                runtimeKind = GenerationRuntimeKind.Remote,
                                modelId = modelId,
                                backend = null,
                                parameters = parameters,
                                streamingUpdates = turn.streamingUpdates,
                            )
                            if (decision !is GenerationQualityDecision.Continue) {
                                turn.qualityDecision = decision
                                remoteRuntime.stop()
                            }
                        }
                    }

                    is RemoteChatEvent.ToolCall -> {
                        turn.streamingUpdates.flush()
                        val id = runId ?: error("远程工具调用缺少 Agent run")
                        turn.observation = assistantOrchestrator.observeModelToolRequest(id, event.request)
                            ?: error("远程工具调用无法进入确认流程")
                    }

                    is RemoteChatEvent.ToolCalls -> {
                        turn.streamingUpdates.flush()
                        val id = runId ?: error("远程工具调用缺少 Agent run")
                        turn.observation = assistantOrchestrator.observeModelToolRequests(id, event.requests)
                            ?: error("远程批量工具调用无法进入执行流程")
                    }

                    is RemoteChatEvent.ParseError -> {
                        val decision = outputQualityGuard.failClosedForFormatViolation(
                            summary = event.summary,
                            accumulatedText = turn.partial.toString(),
                            runtimeKind = GenerationRuntimeKind.Remote,
                            modelId = modelId,
                            backend = null,
                        )
                        applyOutputQualityDecisionToAssistant(
                            partial = turn.partial,
                            decision = decision,
                            streamingUpdates = turn.streamingUpdates,
                        )
                        turn.qualityDecision = decision
                        remoteRuntime.stop()
                    }
                }
            }
        }.first
    }

    /** Streams one local turn into [turn], stopping the runtime on the first quality violation. */
    suspend fun streamLocalTurn(
        turn: GuardedTurn,
        prompt: String,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        imageAttachments: List<LocalImageAttachment> = emptyList(),
        acceptDelta: (String) -> Boolean = { true },
    ) {
        collectLocalRuntimeResponse(
            promptForModel = prompt,
            history = history,
            parameters = parameters,
            imageAttachments = imageAttachments,
        ) { chunk ->
            if (turn.qualityDecision == null && acceptDelta(chunk)) {
                val decision = appendGuardedGenerationChunk(
                    partial = turn.partial,
                    chunk = chunk,
                    runtimeKind = GenerationRuntimeKind.Local,
                    modelId = uiState.value.activeModelProfileId(),
                    backend = uiState.value.backend,
                    parameters = parameters,
                    streamingUpdates = turn.streamingUpdates,
                )
                if (decision !is GenerationQualityDecision.Continue) {
                    turn.qualityDecision = decision
                    runtime.stop()
                }
            }
        }
    }

    /** Runs the completed-output quality check on a turn that ended without a decision or tool call. */
    fun guardCompletedTurn(turn: GuardedTurn, useRemoteModel: Boolean, remoteConfig: RemoteModelConfig) {
        if (turn.qualityDecision != null || turn.observation != null) return
        val finalDecision = outputQualityGuard.evaluateCompleted(
            output = turn.partial.toString(),
            runtimeKind = if (useRemoteModel) GenerationRuntimeKind.Remote else GenerationRuntimeKind.Local,
            modelId = if (useRemoteModel) {
                remoteConfig.modelProfile().id
            } else {
                uiState.value.activeModelProfileId()
            },
            backend = if (useRemoteModel) null else uiState.value.backend,
        )
        if (finalDecision !is GenerationQualityDecision.Continue) {
            applyOutputQualityDecisionToAssistant(
                partial = turn.partial,
                decision = finalDecision,
                streamingUpdates = turn.streamingUpdates,
            )
            turn.qualityDecision = finalDecision
        }
    }

    /**
     * Fails the run before any model call when the chosen runtime cannot serve it. Returns true
     * when the run was ended.
     */
    fun abortIfServingUnavailable(
        runId: String?,
        useRemoteModel: Boolean,
        remoteConfigured: Boolean,
        localNotReadyText: String,
    ): Boolean {
        when {
            !useRemoteModel && !runtime.isLoaded ->
                abortBeforeGeneration(runId, "本地模型尚未就绪", localNotReadyText, "未加载模型")
            useRemoteModel && !remoteConfigured ->
                abortBeforeGeneration(runId, "远程模型未配置", "请先在模型管理中配置远程模型地址和模型名", "请配置远程模型")
            else -> return false
        }
        return true
    }

    private fun abortBeforeGeneration(runId: String?, failureReason: String, assistantText: String, statusText: String) {
        runId?.let { id -> assistantOrchestrator.failModelGeneration(id, failureReason) }
        uiState.updateLastAssistant(assistantText)
        persistActiveSessionFromUi()
        uiState.update {
            it.copy(
                isBusy = false,
                isGenerating = false,
                isReady = false,
                pendingConfirmation = null,
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(runId),
                statusText = statusText,
            )
        }
    }

    /** Ends a run whose generation threw: fails the run and surfaces the error. */
    fun failGeneration(
        runId: String?,
        throwable: Throwable,
        useRemoteModel: Boolean,
        remoteConfigured: Boolean,
        showErrorOnlyWhileGenerating: Boolean = false,
    ) {
        val errorMessage = generationFailureMessage(throwable, useRemoteModel)
        runId?.let { id -> assistantOrchestrator.failModelGeneration(id, errorMessage) }
        if (!showErrorOnlyWhileGenerating || uiState.value.isGenerating) {
            uiState.updateLastAssistant("出错了：$errorMessage")
            persistActiveSessionFromUi()
        }
        uiState.update {
            it.copy(
                isBusy = false,
                isGenerating = false,
                isReady = useRemoteModel && remoteConfigured,
                pendingConfirmation = null,
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(runId),
                modelHealth = it.failedGenerationModelHealth(
                    useRemoteModel = useRemoteModel,
                    reason = errorMessage,
                ),
                statusText = if (useRemoteModel) "远程生成失败" else "生成失败，建议重新加载",
            )
        }
    }

    fun appendGuardedGenerationChunk(
        partial: StringBuilder,
        chunk: String,
        runtimeKind: GenerationRuntimeKind,
        modelId: String?,
        backend: BackendChoice?,
        parameters: GenerationParameters,
        streamingUpdates: StreamingAssistantUpdateCoalescer,
    ): GenerationQualityDecision {
        if (chunk.isEmpty()) {
            streamingUpdates.update(partial.toString())
            return GenerationQualityDecision.Continue
        }
        // One string per chunk: the guard and the UI update share it. On a stop decision the
        // builder is reset to the safe prefix below, so appending first is harmless.
        partial.append(chunk)
        val text = partial.toString()
        val decision = outputQualityGuard.evaluateStreamedText(
            candidate = text,
            runtimeKind = runtimeKind,
            modelId = modelId,
            backend = backend,
            parameters = parameters,
        )
        if (decision is GenerationQualityDecision.Continue) {
            streamingUpdates.update(text)
        } else {
            applyOutputQualityDecisionToAssistant(
                partial = partial,
                decision = decision,
                streamingUpdates = streamingUpdates,
            )
        }
        return decision
    }

    fun applyOutputQualityDecisionToAssistant(
        partial: StringBuilder,
        decision: GenerationQualityDecision,
        streamingUpdates: StreamingAssistantUpdateCoalescer? = null,
    ) {
        val report = decision.reportOrNull() ?: return
        partial.clear()
        partial.append(report.safePrefix)
        streamingUpdates?.discard()
        uiState.updateLastAssistantLocalOnly(report.visibleAssistantText())
    }

    fun finishOutputQualityGuardedGeneration(
        runId: String?,
        decision: GenerationQualityDecision,
        receipt: RunDataReceipt,
        useRemoteModel: Boolean,
    ) {
        val report = decision.reportOrNull() ?: return
        persistActiveSessionFromUi()
        runId?.let { id ->
            recordOutputQualityGuardTriggered(
                runId = id,
                decision = decision,
                receipt = receipt,
            )
            if (decision is GenerationQualityDecision.FailClosed) {
                assistantOrchestrator.failModelGeneration(id, report.visibleNotice)
            } else {
                assistantOrchestrator.cancelRun(id, report.visibleNotice)
            }
        }
        val checkedAtMillis = System.currentTimeMillis()
        uiState.update {
            it.copy(
                isBusy = false,
                isGenerating = false,
                isReady = if (useRemoteModel) {
                    it.remoteModelConfig.isConfigured
                } else {
                    runtime.isLoaded
                },
                pendingConfirmation = null,
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(runId),
                modelHealth = it.modelHealth.copy(
                    profileId = report.modelId ?: if (useRemoteModel) {
                        it.remoteModelConfig.modelProfile().id
                    } else {
                        it.activeModelProfileId()
                    },
                    backend = report.backend ?: it.modelHealth.backend,
                    lastOutputQualityIssue = report.issue.name,
                    lastOutputQualityRule = report.triggeredRule,
                    lastOutputQualityAtMillis = checkedAtMillis,
                ),
                statusText = when (report.issue) {
                    GenerationQualityIssue.EmptyOutput -> "模型没有生成内容"
                    GenerationQualityIssue.FormatViolation -> "模型输出格式已拦截"
                    else -> "模型输出已停止"
                },
            )
        }
    }

    fun recordOutputQualityGuardTriggered(
        runId: String,
        decision: GenerationQualityDecision,
        receipt: RunDataReceipt,
    ) {
        val report = decision.reportOrNull() ?: return
        assistantOrchestrator.recordRunDataReceipt(runId, receipt.withOutputQualityDecision(decision))
        assistantOrchestrator.recordModelOutputQualityGuardTriggered(runId, report.toTrace(decision))
    }

    fun finishStoppedGeneration(runId: String?) {
        val remoteMode = uiState.value.inferenceMode == InferenceMode.Remote
        val currentMessages = uiState.value.messages
        val messages = if (
            currentMessages.lastOrNull()?.role == MessageRole.Assistant &&
            currentMessages.last().text.isBlank()
        ) {
            currentMessages.dropLast(1)
        } else {
            currentMessages
        }
        persistMessagesAndRebuildMemory(messages, null)
        uiState.update {
            it.copy(
                isBusy = false,
                isGenerating = false,
                isReady = if (remoteMode) it.remoteModelConfig.isConfigured else runtime.isLoaded,
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(runId),
                statusText = if (remoteMode) {
                    "已停止 · 远程"
                } else if (!runtime.isLoaded) {
                    "未加载模型"
                } else {
                    "已停止 · ${it.backend.label()}"
                },
                modelHealth = if (remoteMode) {
                    ModelHealth(
                        profileId = it.remoteModelConfig.modelProfile().id,
                        state = if (it.remoteModelConfig.isConfigured) {
                            ModelHealthState.Loaded
                        } else {
                            ModelHealthState.LoadFailed
                        },
                        failureReason = if (it.remoteModelConfig.isConfigured) null else "远程模型未配置",
                    )
                } else {
                    it.modelHealth.copy(
                        state = if (runtime.isLoaded) {
                            ModelHealthState.Loaded
                        } else if (it.modelPath != null) {
                            ModelHealthState.Verified
                        } else {
                            ModelHealthState.NotInstalled
                        },
                        backend = it.backend,
                    )
                },
            )
        }
    }

    fun generationFailureMessage(throwable: Throwable, useRemoteModel: Boolean): String {
        val cleanMessage = throwable.cleanMessage()
        if (!useRemoteModel) return cleanMessage
        return when (throwable) {
            is IOException -> if (cleanMessage == throwable::class.java.simpleName) {
                "远程模型网络连接失败，请检查网络或远程模型配置后重试"
            } else {
                "远程模型网络连接失败：$cleanMessage"
            }
            else -> cleanMessage
        }
    }

    /**
     * Call [block] streaming remote events; if it throws a context-overflow error, force a
     * compaction pass against [history] (returning the compacted list) and retry exactly once.
     * Second throw propagates. Preflight proactive compaction is performed before the first call.
     *
     * @return Pair(compactedHistory, retried) — caller should use the returned history for any
     *   subsequent work (it may be the original or a compacted replacement).
     */
    suspend fun sendRemoteWithOverflowRetry(
        runId: String?,
        history: List<ChatMessage>,
        tokenBudget: Int,
        estimatedTokens: ((List<ChatMessage>) -> Int)? = null,
        block: suspend (history: List<ChatMessage>) -> Unit,
    ): Pair<List<ChatMessage>, Boolean> {
        val estimator = estimatedTokens ?: ::estimateHistoryTokensDefault
        // Preflight: proactive compaction under normal budget. No-op for NoOpContextCompactor.
        val preflightBudget = if (tokenBudget > 0) tokenBudget else Int.MAX_VALUE
        var currentHistory: List<ChatMessage> = assistantOrchestrator.compactHistory(
            messages = history,
            tokenBudget = preflightBudget,
            runId = runId,
            force = false,
            estimatedTokens = estimator,
        )
        val remoteUrl = uiState.value.remoteModelConfig.normalized().baseUrl
        solinD(TAG_REMOTE, "sendRemote: start url=$remoteUrl historySize=${currentHistory.size}")
        val requestStartMs = System.currentTimeMillis()
        return try {
            block(currentHistory)
            val durationMs = System.currentTimeMillis() - requestStartMs
            solinD(TAG_REMOTE, "sendRemote: success url=$remoteUrl durationMs=$durationMs")
            currentHistory to false
        } catch (first: Throwable) {
            if (!first.isContextOverflowError()) {
                solinE(TAG_REMOTE, "sendRemote: error url=$remoteUrl", first)
                throw first
            }
            solinW(TAG_REMOTE, "sendRemote: context overflow, retrying with compaction url=$remoteUrl")
            // ONE retry with forced aggressive compaction (budget=0 triggers OverBudget).
            currentHistory = assistantOrchestrator.compactHistory(
                messages = currentHistory,
                tokenBudget = 0,
                runId = runId,
                force = true,
                estimatedTokens = estimator,
            )
            try {
                block(currentHistory)
                val durationMs = System.currentTimeMillis() - requestStartMs
                solinD(TAG_REMOTE, "sendRemote: success-after-compaction url=$remoteUrl durationMs=$durationMs")
                currentHistory to true
            } catch (second: Throwable) {
                second.addSuppressed(first)
                solinE(TAG_REMOTE, "sendRemote: error-after-compaction url=$remoteUrl", second)
                throw second
            }
        }
    }

    fun estimateHistoryTokensDefault(messages: List<ChatMessage>): Int =
        com.bytedance.zgx.solin.orchestration.estimateTokensApproximate(messages)

    /**
     * Detect context-length overflow phrases in an exception chain. Matches wording produced by
     * common remote providers (OpenAI "context_length_exceeded", Anthropic "prompt is too long",
     * Gemini "maximum context", LiteLLM/OpenAI-compatible proxies) plus the Chinese-localized
     * surface strings we emit ourselves. We walk the cause chain so wrapped IOExceptions/
     * RemoteChatRuntime failures still match.
     */
    private fun Throwable.isContextOverflowError(): Boolean {
        var current: Throwable? = this
        val visited = HashSet<Throwable>(4)
        while (current != null && current !in visited) {
            visited += current
            val msg = current.message.orEmpty().lowercase()
            if (CONTEXT_OVERFLOW_MARKERS.any { it in msg }) return true
            current = current.cause
        }
        return false
    }
}
