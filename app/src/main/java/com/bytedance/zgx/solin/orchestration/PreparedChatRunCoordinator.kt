package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatImageAttachment
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.LocalImageAttachment
import com.bytedance.zgx.solin.resource.StableResourceState
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CancellationException

internal fun interface PreparedChatPlacementPolicy {
    fun decide(input: ModelPlacementInput): PlacementDecision
}

internal interface PreparedChatBindingStore {
    fun bindAndReserve(binding: RunPlacementBinding): ActiveRunPlacementPermit?
    fun abort(permit: ActiveRunPlacementPermit)
}

internal fun interface PreparedChatDispatcher {
    fun start(prepared: PreparedChatRun): PreparedChatRunningCall
}

internal interface PreparedChatRunningCall {
    suspend fun awaitCompletion()
    fun stop()
}

internal fun interface PreparedChatDispatchReadiness {
    suspend fun await(prepared: PreparedChatRun)
}

internal fun interface PreparedChatRevisionValidator {
    fun isCurrent(expectedRevision: String): Boolean
}

internal data class PrepareChatRunRequest(
    val runId: String,
    val sessionId: String,
    val preference: InferenceMode,
    val privacyPlan: PromptPrivacyPlan,
    val requirements: ModelRequirements,
    val complexity: RequestComplexity,
    val resources: StableResourceState,
    val localCandidate: ModelCandidateSnapshot,
    val remoteCandidate: ModelCandidateSnapshot,
    val remoteConfigRevision: String,
    val prompt: String,
    val history: List<ChatMessage>,
    val imageAttachments: List<ChatImageAttachment>,
    val localImageAttachments: List<LocalImageAttachment>,
    val generationParameters: GenerationParameters,
    val requiresRemoteDisclosure: Boolean,
    val bootCount: Long,
    val boundAtElapsedRealtimeMillis: Long,
) {
    override fun toString(): String =
        "PrepareChatRunRequest(runId=$runId, preference=$preference, historyCount=${history.size}, " +
            "imageCount=${imageAttachments.size}, localImageCount=${localImageAttachments.size})"
}

internal class PreparedChatRun private constructor(
    val runId: String,
    val sessionId: String,
    val preference: InferenceMode,
    val privacyPlan: PromptPrivacyPlan,
    val requirements: ModelRequirements,
    val complexity: RequestComplexity,
    val resources: StableResourceState,
    val localCandidate: ModelCandidateSnapshot,
    val remoteCandidate: ModelCandidateSnapshot,
    val remoteConfigRevision: String,
    val prompt: String,
    val history: List<ChatMessage>,
    val imageAttachments: List<ChatImageAttachment>,
    private val frozenLocalImageAttachments: List<LocalImageAttachment>,
    val generationParameters: GenerationParameters,
    val decision: PlacementDecision.Chosen,
    val binding: RunPlacementBinding,
    val permit: ActiveRunPlacementPermit,
) {
    val placement: RunPlacement
        get() = binding.placement

    val localImageAttachments: List<LocalImageAttachment>
        get() = frozenLocalImageAttachments.map { attachment -> attachment.frozenCopy() }

    override fun toString(): String =
        "PreparedChatRun(runId=$runId, placement=$placement, historyCount=${history.size}, " +
            "imageCount=${imageAttachments.size}, localImageCount=${frozenLocalImageAttachments.size})"

    companion object {
        fun freeze(
            request: PrepareChatRunRequest,
            decision: PlacementDecision.Chosen,
            binding: RunPlacementBinding,
            permit: ActiveRunPlacementPermit,
        ): PreparedChatRun = PreparedChatRun(
            runId = request.runId,
            sessionId = request.sessionId,
            preference = request.preference,
            privacyPlan = request.privacyPlan,
            requirements = request.requirements,
            complexity = request.complexity,
            resources = request.resources,
            localCandidate = request.localCandidate,
            remoteCandidate = request.remoteCandidate,
            remoteConfigRevision = request.remoteConfigRevision,
            prompt = request.prompt,
            history = request.history.toList(),
            imageAttachments = request.imageAttachments.toList(),
            frozenLocalImageAttachments = request.localImageAttachments.map { attachment -> attachment.frozenCopy() },
            generationParameters = request.generationParameters,
            decision = decision,
            binding = binding,
            permit = permit,
        )
    }
}

internal sealed interface PrepareChatRunResult {
    data class Blocked(val decision: PlacementDecision.Blocked) : PrepareChatRunResult
    data object BindingRejected : PrepareChatRunResult
    data object DispatchRejected : PrepareChatRunResult
    data class Ready(val prepared: PreparedChatRun) : PrepareChatRunResult
    data class AwaitingDisclosure(
        val runId: String,
        val expectedConfigRevision: String,
    ) : PrepareChatRunResult
}

internal class PreparedChatRunCoordinator(
    private val policy: PreparedChatPlacementPolicy,
    private val bindingStore: PreparedChatBindingStore,
    private val dispatcher: PreparedChatDispatcher,
    private val revisionValidator: PreparedChatRevisionValidator,
    private val dispatchReadiness: PreparedChatDispatchReadiness = PreparedChatDispatchReadiness {},
) {
    private data class LifecycleToken(
        val runGeneration: Long,
        val sessionGeneration: Long,
    )

    private enum class PreparedRunState {
        AwaitingDisclosure,
        ReadyToDispatch,
        StartingDispatch,
        Running,
        StopRequested,
    }

    private class RegisteredPreparedRun(
        val sessionId: String,
        val expectedConfigRevision: String,
        val prepared: PreparedChatRun,
        val lifecycleToken: LifecycleToken,
        var state: PreparedRunState,
        var runningCall: PreparedChatRunningCall? = null,
        var stopIssued: Boolean = false,
        val stopFinished: CountDownLatch = CountDownLatch(1),
    )

    private enum class DispatchOutcome {
        Dispatched,
        Invalidated,
        Failed,
    }

    private val lifecycleLock = Any()
    private val runGenerations = mutableMapOf<String, Long>()
    private val sessionGenerations = mutableMapOf<String, Long>()
    private val preparedRuns = mutableMapOf<String, RegisteredPreparedRun>()

    suspend fun prepare(request: PrepareChatRunRequest): PrepareChatRunResult {
        val frozenRequest = request.frozenCopy()
        val lifecycleToken = captureLifecycleToken(frozenRequest.runId, frozenRequest.sessionId)
        val decision = policy.decide(frozenRequest.toPlacementInput())
        if (decision is PlacementDecision.Blocked) return PrepareChatRunResult.Blocked(decision)
        decision as PlacementDecision.Chosen
        val binding = runCatching {
            RunPlacementBinding.fromDecision(
                runId = frozenRequest.runId,
                decision = decision,
                remoteProfileRevision = frozenRequest.remoteConfigRevision,
                bootCount = frozenRequest.bootCount,
                boundAtElapsedRealtimeMillis = frozenRequest.boundAtElapsedRealtimeMillis,
            )
        }.getOrNull() ?: return PrepareChatRunResult.BindingRejected
        val permit = runCatching { bindingStore.bindAndReserve(binding) }.getOrNull()
            ?: return PrepareChatRunResult.BindingRejected

        val prepared = PreparedChatRun.freeze(frozenRequest, decision, binding, permit)
        if (decision.placement == RunPlacement.Remote && frozenRequest.requiresRemoteDisclosure) {
            val registered = RegisteredPreparedRun(
                frozenRequest.sessionId,
                frozenRequest.remoteConfigRevision,
                prepared,
                lifecycleToken,
                PreparedRunState.AwaitingDisclosure,
            )
            val published = register(registered)
            if (!published) {
                abort(permit)
                return PrepareChatRunResult.BindingRejected
            }
            return PrepareChatRunResult.AwaitingDisclosure(
                frozenRequest.runId,
                frozenRequest.remoteConfigRevision,
            )
        }
        val registered = RegisteredPreparedRun(
            sessionId = frozenRequest.sessionId,
            expectedConfigRevision = frozenRequest.remoteConfigRevision,
            prepared = prepared,
            lifecycleToken = lifecycleToken,
            state = PreparedRunState.ReadyToDispatch,
        )
        if (!register(registered)) {
            abort(permit)
            return PrepareChatRunResult.BindingRejected
        }
        return when (dispatchRegistered(registered)) {
            DispatchOutcome.Dispatched -> PrepareChatRunResult.Ready(prepared)
            DispatchOutcome.Invalidated -> PrepareChatRunResult.BindingRejected
            DispatchOutcome.Failed -> PrepareChatRunResult.DispatchRejected
        }
    }

    suspend fun confirmAndDispatch(runId: String, expectedConfigRevision: String): Boolean {
        var invalidated: RegisteredPreparedRun? = null
        val taken = synchronized(lifecycleLock) {
            val registered = preparedRuns[runId] ?: return@synchronized null
            when {
                registered.state != PreparedRunState.AwaitingDisclosure -> null
                registered.expectedConfigRevision != expectedConfigRevision -> null
                !isLifecycleCurrent(runId, registered.sessionId, registered.lifecycleToken) ||
                    !runCatching { revisionValidator.isCurrent(expectedConfigRevision) }.getOrDefault(false) -> {
                    preparedRuns.remove(runId)
                    invalidated = registered
                    null
                }
                else -> registered.also { it.state = PreparedRunState.ReadyToDispatch }
            }
        }
        invalidated?.let { registered -> abort(registered.prepared.permit) }
        return taken != null && dispatchRegistered(taken) == DispatchOutcome.Dispatched
    }

    fun cancel(runId: String) {
        var abort: RegisteredPreparedRun? = null
        var pendingStop: Pair<RegisteredPreparedRun, PreparedChatRunningCall>? = null
        synchronized(lifecycleLock) {
            runGenerations[runId] = runGenerations.getOrDefault(runId, 0) + 1
            preparedRuns[runId]?.let { registered ->
                when (registered.state) {
                    PreparedRunState.AwaitingDisclosure,
                    PreparedRunState.ReadyToDispatch,
                    -> {
                        preparedRuns.remove(runId)
                        abort = registered
                    }
                    PreparedRunState.StartingDispatch -> registered.state = PreparedRunState.StopRequested
                    PreparedRunState.Running -> {
                        registered.state = PreparedRunState.StopRequested
                        takeStopLocked(registered)?.let { runningCall ->
                            pendingStop = registered to runningCall
                        }
                    }
                    PreparedRunState.StopRequested -> Unit
                }
            }
        }
        abort?.let { registered -> abort(registered.prepared.permit) }
        pendingStop?.let { (registered, runningCall) -> stop(registered, runningCall) }
    }

    fun teardownSession(sessionId: String) {
        val aborts = mutableListOf<RegisteredPreparedRun>()
        val stops = mutableListOf<Pair<RegisteredPreparedRun, PreparedChatRunningCall>>()
        synchronized(lifecycleLock) {
            sessionGenerations[sessionId] = sessionGenerations.getOrDefault(sessionId, 0) + 1
            preparedRuns.values.filter { registered -> registered.sessionId == sessionId }.forEach { registered ->
                when (registered.state) {
                    PreparedRunState.AwaitingDisclosure,
                    PreparedRunState.ReadyToDispatch,
                    -> {
                        preparedRuns.remove(registered.prepared.runId)
                        aborts += registered
                    }
                    PreparedRunState.StartingDispatch -> registered.state = PreparedRunState.StopRequested
                    PreparedRunState.Running -> {
                        registered.state = PreparedRunState.StopRequested
                        takeStopLocked(registered)?.let { runningCall -> stops += registered to runningCall }
                    }
                    PreparedRunState.StopRequested -> Unit
                }
            }
        }
        aborts.forEach { registered -> abort(registered.prepared.permit) }
        stops.forEach { (registered, runningCall) -> stop(registered, runningCall) }
    }

    internal fun pendingDisclosureCount(): Int = synchronized(lifecycleLock) {
        preparedRuns.values.count { registered ->
            registered.state == PreparedRunState.AwaitingDisclosure
        }
    }

    private fun register(registered: RegisteredPreparedRun): Boolean = synchronized(lifecycleLock) {
        val prepared = registered.prepared
        isLifecycleCurrent(prepared.runId, registered.sessionId, registered.lifecycleToken) &&
            preparedRuns.putIfAbsent(prepared.runId, registered) == null
    }

    private fun captureLifecycleToken(runId: String, sessionId: String): LifecycleToken =
        synchronized(lifecycleLock) {
            LifecycleToken(
                runGeneration = runGenerations.getOrDefault(runId, 0),
                sessionGeneration = sessionGenerations.getOrDefault(sessionId, 0),
            )
        }

    private fun isLifecycleCurrent(
        runId: String,
        sessionId: String,
        token: LifecycleToken,
    ): Boolean =
        runGenerations.getOrDefault(runId, 0) == token.runGeneration &&
            sessionGenerations.getOrDefault(sessionId, 0) == token.sessionGeneration

    private suspend fun dispatchRegistered(registered: RegisteredPreparedRun): DispatchOutcome {
        try {
            dispatchReadiness.await(registered.prepared)
        } catch (error: Throwable) {
            terminateAndAbort(registered)
            if (error is CancellationException) throw error
            return DispatchOutcome.Failed
        }
        val claimed = synchronized(lifecycleLock) {
            val prepared = registered.prepared
            if (
                preparedRuns[prepared.runId] !== registered ||
                registered.state != PreparedRunState.ReadyToDispatch ||
                !isLifecycleCurrent(prepared.runId, registered.sessionId, registered.lifecycleToken)
            ) {
                false
            } else {
                registered.state = PreparedRunState.StartingDispatch
                true
            }
        }
        if (!claimed) return DispatchOutcome.Invalidated
        val runningCall = try {
            dispatcher.start(registered.prepared)
        } catch (error: Throwable) {
            terminateAndAbort(registered)
            if (error is CancellationException) throw error
            return DispatchOutcome.Failed
        }
        val pendingStop = synchronized(lifecycleLock) {
            registered.runningCall = runningCall
            when (registered.state) {
                PreparedRunState.StartingDispatch -> {
                    registered.state = PreparedRunState.Running
                    null
                }
                PreparedRunState.StopRequested -> takeStopLocked(registered)
                else -> takeStopLocked(registered)
            }
        }
        pendingStop?.let { runningCall -> stop(registered, runningCall) }
        return try {
            runningCall.awaitCompletion()
            removeRegistered(registered)
            DispatchOutcome.Dispatched
        } catch (error: Throwable) {
            terminateAndAbort(registered)
            if (error is CancellationException) throw error
            DispatchOutcome.Failed
        }
    }

    private fun removeRegistered(registered: RegisteredPreparedRun) {
        synchronized(lifecycleLock) {
            preparedRuns.remove(registered.prepared.runId, registered)
        }
    }

    private fun terminateAndAbort(registered: RegisteredPreparedRun) {
        var runningCall: PreparedChatRunningCall? = null
        var awaitStop = false
        val removed = synchronized(lifecycleLock) {
            preparedRuns.remove(registered.prepared.runId, registered).also { removed ->
                if (removed) {
                    runningCall = takeStopLocked(registered)
                    awaitStop = runningCall == null && registered.stopIssued
                }
            }
        }
        runningCall?.let { call -> stop(registered, call) }
        if (awaitStop) registered.stopFinished.await()
        if (removed) abort(registered.prepared.permit)
    }

    private fun takeStopLocked(registered: RegisteredPreparedRun): PreparedChatRunningCall? {
        if (registered.stopIssued) return null
        val runningCall = registered.runningCall ?: return null
        registered.stopIssued = true
        return runningCall
    }

    private fun abort(permit: ActiveRunPlacementPermit) {
        runCatching { bindingStore.abort(permit) }
    }

    private fun stop(registered: RegisteredPreparedRun, runningCall: PreparedChatRunningCall) {
        try {
            runCatching { runningCall.stop() }
        } finally {
            registered.stopFinished.countDown()
        }
    }

    private fun PrepareChatRunRequest.toPlacementInput(): ModelPlacementInput = ModelPlacementInput(
        preference = preference,
        privacy = privacyPlan.aggregatePrivacy,
        requiresLocalModel = privacyPlan.requiresLocalModel,
        requirements = requirements,
        local = localCandidate,
        remote = remoteCandidate,
        resources = resources,
        complexity = complexity,
    )

    private fun PrepareChatRunRequest.frozenCopy(): PrepareChatRunRequest = copy(
        history = history.toList(),
        imageAttachments = imageAttachments.toList(),
        localImageAttachments = localImageAttachments.map { attachment -> attachment.frozenCopy() },
    )
}

private fun LocalImageAttachment.frozenCopy(): LocalImageAttachment = LocalImageAttachment(
    mimeType = mimeType,
    bytes = bytes.copyOf(),
    sizeBytes = sizeBytes,
)
