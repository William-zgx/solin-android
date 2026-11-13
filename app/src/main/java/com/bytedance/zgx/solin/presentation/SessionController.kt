package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AgentTraceRunUiSummary
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.data.SessionStore
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.logging.cleanMessage
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns chat-session lifecycle: create / select / delete, and local runtime conversation restore.
 *
 * The ViewModel keeps thin public wrappers and owns cross-cutting remote-pending / confirmation restore via callbacks.
 */
class SessionController(
    private val sessionRepository: SessionStore,
    private val assistantOrchestrator: AssistantRouter,
    private val runtime: LiteRtRuntime,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val runtimeLock: Mutex,
    private val clearPendingRemoteChatState: () -> Unit,
    private val resetRemoteSendDisclosureSuppression: () -> Unit,
    private val restorePendingAgentConfirmationIfAny: (clearMissing: Boolean) -> Unit,
    private val restorePendingExternalOutcomeIfAny: (clearMissing: Boolean) -> Unit,
    private val loadAgentTraceRuns: () -> List<AgentTraceRunUiSummary>
) {
    private var sessionRestoreJob: Job? = null
    private var sessionRestoreGeneration: Long = 0L

    fun close() {
        sessionRestoreJob?.cancel()
        sessionRestoreJob = null
    }

    fun createNewSession() {
        commitSessionTransition(
            operation = sessionRepository::createNewSession,
            refreshSummaries = true,
            unloadedStatus = "新会话",
            restoringStatus = "正在开启新会话"
        )
    }

    fun selectSession(sessionId: String) {
        if (uiState.value.activeSessionId == sessionId) return
        commitSessionTransition(
            operation = { sessionRepository.selectSession(sessionId) },
            refreshSummaries = false,
            unloadedStatus = "已切换会话",
            restoringStatus = "正在恢复会话",
            successPrefix = "已恢复会话"
        )
    }

    fun deleteActiveSession() {
        commitSessionTransition(
            operation = {
                val deletedSessionId = sessionRepository.activeSessionId
                assistantOrchestrator.deleteRunsForSession(deletedSessionId)
                sessionRepository.deleteActiveSession()
            },
            refreshSummaries = true,
            unloadedStatus = "已删除会话",
            restoringStatus = "正在恢复会话"
        )
    }

    private fun commitSessionTransition(
        operation: () -> List<ChatMessage>?,
        refreshSummaries: Boolean,
        unloadedStatus: String,
        restoringStatus: String,
        successPrefix: String = unloadedStatus
    ) {
        if (uiState.value.isBusy) return
        clearPendingRemoteChatState()
        // Every session transition is a fresh trust context, including a failed selection.
        resetRemoteSendDisclosureSuppression()
        val messages = operation() ?: return
        val activeSessionId = sessionRepository.activeSessionId
        val restoreGeneration = nextSessionRestoreGeneration()
        uiState.update {
            it.copy(
                sessions = if (refreshSummaries) sessionRepository.summaries() else it.sessions,
                activeSessionId = activeSessionId,
                messages = messages,
                pendingConfirmation = null,
                pendingRemoteSendDisclosure = null,
                pendingExternalOutcome = null,
                pendingSharedInputDraft = null,
                latestRecoveryAction = null,
                agentTraceRuns = loadAgentTraceRuns(),
                isReady = if (runtime.isLoaded) false else it.isReady,
                statusText = if (runtime.isLoaded) restoringStatus else unloadedStatus
            )
        }
        restorePendingAgentConfirmationIfAny(true)
        restorePendingExternalOutcomeIfAny(true)
        if (runtime.isLoaded) {
            recreateConversationForMessages(
                successPrefix = successPrefix,
                sessionId = activeSessionId,
                messages = messages,
                restoreGeneration = restoreGeneration
            )
        }
    }

    /**
     * Rebuilds the local runtime conversation for the current active session
     * (e.g. after generation-parameter changes).
     */
    fun recreateConversationForActiveSession(successPrefix: String) {
        val sessionId = sessionRepository.activeSessionId
        val history = sessionRepository.activeMessages()
        val restoreGeneration = nextSessionRestoreGeneration()
        recreateConversationForMessages(
            successPrefix = successPrefix,
            sessionId = sessionId,
            messages = history,
            restoreGeneration = restoreGeneration
        )
    }

    private fun recreateConversationForMessages(
        successPrefix: String,
        sessionId: String,
        messages: List<ChatMessage>,
        restoreGeneration: Long
    ) {
        sessionRestoreJob?.cancel()
        uiState.update {
            if (it.activeSessionId == sessionId) {
                it.copy(
                    isReady = false,
                    statusText = "正在恢复会话",
                    modelHealth = it.modelHealth.copy(
                        state = ModelHealthState.Loading,
                        backend = it.backend
                    )
                )
            } else {
                it
            }
        }
        sessionRestoreJob = scope.launch(ioDispatcher) {
            val result = runCatching {
                runtimeLock.withLock {
                    runtime.recreateConversation(
                        history = messages,
                        parameters = uiState.value.generationParameters
                    )
                }
            }
            result.fold(
                onSuccess = {
                    uiState.update { current ->
                        if (current.activeSessionId == sessionId && restoreGeneration == sessionRestoreGeneration) {
                            current.copy(
                                isReady = true,
                                statusText = "$successPrefix · ${current.backend.label()}",
                                modelHealth = current.modelHealth.copy(
                                    state = ModelHealthState.Loaded,
                                    backend = current.backend,
                                    failureReason = null
                                )
                            )
                        } else {
                            current
                        }
                    }
                },
                onFailure = { throwable ->
                    uiState.update { current ->
                        if (current.activeSessionId == sessionId && restoreGeneration == sessionRestoreGeneration) {
                            current.copy(
                                isReady = false,
                                statusText = "恢复会话失败：${throwable.cleanMessage()}",
                                modelHealth = current.modelHealth.copy(
                                    state = ModelHealthState.LoadFailed,
                                    backend = current.backend,
                                    failureReason = throwable.cleanMessage()
                                )
                            )
                        } else {
                            current
                        }
                    }
                }
            )
        }
    }

    private fun nextSessionRestoreGeneration(): Long {
        sessionRestoreGeneration += 1
        return sessionRestoreGeneration
    }
}
