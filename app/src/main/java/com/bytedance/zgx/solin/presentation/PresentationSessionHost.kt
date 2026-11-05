package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AgentTraceRunUiSummary
import com.bytedance.zgx.solin.AuditEventSummary
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.LongTermMemorySummary
import com.bytedance.zgx.solin.tool.PublicWebEvidencePack
import com.bytedance.zgx.solin.background.BackgroundTaskSnapshot
import com.bytedance.zgx.solin.data.SessionStore
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Session persistence, memory, and read-model lookups shared by the chat and tool-execution
 * controllers. Replaces the ~20 per-controller callback lambdas they used to receive from the
 * ViewModel.
 */
interface PresentationSessionHost {
    /**
     * Re-dispatches [work] onto the serialized persistence queue when called on the main thread
     * while persistence initialization is deferred. Returns true when [work] was dispatched (the
     * caller must return), false when the caller should proceed inline.
     */
    fun dispatchPersistenceWorkIfNeeded(work: () -> Unit): Boolean

    fun replaceActiveSessionMessages(messages: List<ChatMessage>, persistNow: Boolean)

    fun persistActiveSessionFromUi()

    /**
     * Persists [messages] to the active session, optionally records an explicit preference memory
     * from [memoryUserMessage], then rebuilds the memory index.
     */
    fun persistMessagesAndRebuildMemory(
        messages: List<ChatMessage>,
        memoryUserMessage: ChatMessage? = null,
    )

    fun activeSessionId(): String

    fun rebuildMemoryIndex()

    fun syncTaskStateMemories()

    fun persistExplicitPreferenceMemory(message: ChatMessage): Boolean

    fun handleExplicitPreferenceCommand(trimmed: String)

    fun handleExplicitUserFactCommand(trimmed: String)

    fun handleExplicitMemoryForgetCommand(trimmed: String)

    fun loadLongTermMemories(): List<LongTermMemorySummary>

    fun loadAuditEvents(): List<AuditEventSummary>

    fun loadAgentTraceRuns(): List<AgentTraceRunUiSummary>

    fun loadBackgroundTaskSnapshot(): BackgroundTaskSnapshot

    fun activeRunTimelineFor(runId: String?): List<RunTimelineItemUiSummary>

    fun activePublicWebEvidenceFor(runId: String?): List<PublicWebEvidencePack>

    fun activeMemoryEvidenceFor(
        memoryHits: List<MemoryHit>,
        includePrivateLocalContext: Boolean,
    ): List<MemoryEvidenceUiSummary>
}

internal class DefaultPresentationSessionHost(
    private val sessionStore: SessionStore,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val assistantOrchestrator: AssistantRouter,
    private val memoryUiController: MemoryUiController,
    private val auditUiController: AuditUiController,
    private val backgroundTaskController: BackgroundTaskController,
    private val dispatchPersistenceWork: (work: () -> Unit) -> Boolean,
) : PresentationSessionHost {
    override fun dispatchPersistenceWorkIfNeeded(work: () -> Unit): Boolean =
        dispatchPersistenceWork(work)

    override fun replaceActiveSessionMessages(messages: List<ChatMessage>, persistNow: Boolean) {
        sessionStore.replaceActiveSessionMessages(messages, persistNow)
        uiState.update {
            it.copy(
                sessions = sessionStore.summaries(),
                activeSessionId = sessionStore.activeSessionId,
                messages = messages,
            )
        }
    }

    override fun persistActiveSessionFromUi() {
        val messages = uiState.value.messages
        sessionStore.persistActiveSessionFrom(messages)
        uiState.update {
            it.copy(
                sessions = sessionStore.summaries(),
                activeSessionId = sessionStore.activeSessionId,
                messages = messages,
            )
        }
    }

    override fun persistMessagesAndRebuildMemory(
        messages: List<ChatMessage>,
        memoryUserMessage: ChatMessage?,
    ) {
        replaceActiveSessionMessages(messages, persistNow = true)
        if (memoryUserMessage != null) {
            persistExplicitPreferenceMemory(memoryUserMessage)
            memoryUiController.onUserMessage(memoryUserMessage.text)
        }
        rebuildMemoryIndex()
    }

    override fun activeSessionId(): String = sessionStore.activeSessionId

    override fun rebuildMemoryIndex() = memoryUiController.rebuildMemoryIndex()

    override fun syncTaskStateMemories() = memoryUiController.syncTaskStateMemories()

    override fun persistExplicitPreferenceMemory(message: ChatMessage): Boolean =
        memoryUiController.persistExplicitPreferenceMemory(message)

    override fun handleExplicitPreferenceCommand(trimmed: String) =
        memoryUiController.handleExplicitPreferenceCommand(trimmed)

    override fun handleExplicitUserFactCommand(trimmed: String) =
        memoryUiController.handleExplicitUserFactCommand(trimmed)

    override fun handleExplicitMemoryForgetCommand(trimmed: String) =
        memoryUiController.handleExplicitMemoryForgetCommand(trimmed)

    override fun loadLongTermMemories(): List<LongTermMemorySummary> =
        memoryUiController.loadLongTermMemories()

    override fun loadAuditEvents(): List<AuditEventSummary> = auditUiController.loadAuditEvents()

    override fun loadAgentTraceRuns(): List<AgentTraceRunUiSummary> =
        auditUiController.loadAgentTraceRuns()

    override fun loadBackgroundTaskSnapshot(): BackgroundTaskSnapshot =
        backgroundTaskController.loadBackgroundTaskSnapshot()

    override fun activeRunTimelineFor(runId: String?): List<RunTimelineItemUiSummary> =
        runId
            ?.let { id -> runCatching { assistantOrchestrator.runEvents(id) }.getOrDefault(emptyList()) }
            ?.let(::runTimelineSummariesFor)
            .orEmpty()

    override fun activePublicWebEvidenceFor(runId: String?): List<PublicWebEvidencePack> =
        runId
            ?.let { id -> runCatching { assistantOrchestrator.publicWebEvidence(id) }.getOrDefault(emptyList()) }
            .orEmpty()

    override fun activeMemoryEvidenceFor(
        memoryHits: List<MemoryHit>,
        includePrivateLocalContext: Boolean,
    ): List<MemoryEvidenceUiSummary> =
        if (includePrivateLocalContext) memoryEvidenceSummariesFor(memoryHits) else emptyList()
}

/**
 * The common "run step finished" UI update: not busy, not generating, fresh trace runs and the
 * run's timeline, and [statusText]. Callers chain `.copy(...)` for anything run-specific.
 */
internal fun PresentationSessionHost.finishedRun(
    state: ChatUiState,
    runId: String?,
    statusText: String,
): ChatUiState =
    state.copy(
        isBusy = false,
        isGenerating = false,
        agentTraceRuns = loadAgentTraceRuns(),
        activeRunTimeline = activeRunTimelineFor(runId),
        statusText = statusText,
    )
