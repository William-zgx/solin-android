package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.tool.ToolStatus

enum class AgentRunEventKind {
    InputReceived,
    ContextLoaded,
    PlanCreated,
    ConfirmationRequested,
    ToolExecuted,
    ObservationRecorded,
    AnswerGenerated,
    RunFailed,
    RunCancelled,
}

enum class AgentRunEventState {
    Empty,
    InputReceived,
    ContextLoaded,
    Planning,
    AwaitingConfirmation,
    Executing,
    Observing,
    Completed,
    Failed,
    Cancelled,
}

enum class AgentRunTerminalState {
    AnswerGenerated,
    Failed,
    Cancelled,
}

enum class AgentRunPrivacyMarker {
    LocalOnly,
    ContainsLocalContext,
    ThirdPartyOutput,
    UserMediated,
}

enum class AgentRunRiskMarker {
    ExternalSideEffect,
    DestructiveAction,
    NetworkAccess,
    NeedsHumanReview,
}

enum class AgentRunToolStatus {
    Succeeded,
    Failed,
    Cancelled,
}

sealed class AgentRunEvent(
    open val eventId: String,
    open val runId: String,
    val kind: AgentRunEventKind,
) {
    data class InputReceived(
        override val eventId: String,
        override val runId: String,
        val inputId: String,
        val sourceLabel: String,
        val privacyMarkers: Set<AgentRunPrivacyMarker> = emptySet(),
        val attachmentCount: Int = 0,
    ) : AgentRunEvent(eventId, runId, AgentRunEventKind.InputReceived)

    data class ContextLoaded(
        override val eventId: String,
        override val runId: String,
        val contextId: String,
        val memoryHitCount: Int = 0,
        val deviceContextIncluded: Boolean = false,
        val sourceLabels: List<String> = emptyList(),
        val privacyMarkers: Set<AgentRunPrivacyMarker> = emptySet(),
    ) : AgentRunEvent(eventId, runId, AgentRunEventKind.ContextLoaded)

    data class PlanCreated(
        override val eventId: String,
        override val runId: String,
        val planId: String,
        val stepCount: Int = 0,
        val toolLabels: List<String> = emptyList(),
        val riskMarkers: Set<AgentRunRiskMarker> = emptySet(),
    ) : AgentRunEvent(eventId, runId, AgentRunEventKind.PlanCreated)

    data class ConfirmationRequested(
        override val eventId: String,
        override val runId: String,
        val confirmationId: String,
        val toolCallId: String,
        val actionLabel: String,
        val privacyMarkers: Set<AgentRunPrivacyMarker> = emptySet(),
        val riskMarkers: Set<AgentRunRiskMarker> = emptySet(),
    ) : AgentRunEvent(eventId, runId, AgentRunEventKind.ConfirmationRequested)

    data class ToolExecuted(
        override val eventId: String,
        override val runId: String,
        val toolCallId: String,
        val toolLabel: String,
        val status: AgentRunToolStatus,
        val riskMarkers: Set<AgentRunRiskMarker> = emptySet(),
    ) : AgentRunEvent(eventId, runId, AgentRunEventKind.ToolExecuted)

    data class ObservationRecorded(
        override val eventId: String,
        override val runId: String,
        val observationId: String,
        val toolCallId: String? = null,
        val observationLabel: String,
        val sourceCount: Int = 0,
        val privacyMarkers: Set<AgentRunPrivacyMarker> = emptySet(),
    ) : AgentRunEvent(eventId, runId, AgentRunEventKind.ObservationRecorded)

    data class AnswerGenerated(
        override val eventId: String,
        override val runId: String,
        val answerId: String,
        val safeAnswerPreview: String? = null,
        val outputLabel: String? = null,
        val privacyMarkers: Set<AgentRunPrivacyMarker> = emptySet(),
    ) : AgentRunEvent(eventId, runId, AgentRunEventKind.AnswerGenerated)

    data class RunFailed(
        override val eventId: String,
        override val runId: String,
        val failureId: String,
        val reasonLabel: String,
        val riskMarkers: Set<AgentRunRiskMarker> = emptySet(),
    ) : AgentRunEvent(eventId, runId, AgentRunEventKind.RunFailed)

    data class RunCancelled(
        override val eventId: String,
        override val runId: String,
        val cancellationId: String,
        val reasonLabel: String,
    ) : AgentRunEvent(eventId, runId, AgentRunEventKind.RunCancelled)
}

data class AgentRunEventCounts(
    val total: Int = 0,
    val inputReceived: Int = 0,
    val contextLoaded: Int = 0,
    val planCreated: Int = 0,
    val confirmationRequested: Int = 0,
    val toolExecuted: Int = 0,
    val observationRecorded: Int = 0,
    val answerGenerated: Int = 0,
    val runFailed: Int = 0,
    val runCancelled: Int = 0,
    val memoryHits: Int = 0,
    val deviceContextLoads: Int = 0,
    val plannedSteps: Int = 0,
    val observationSources: Int = 0,
    val inputAttachments: Int = 0,
)

data class AgentRunPendingConfirmationSummary(
    val confirmationId: String,
    val toolCallId: String,
    val actionLabel: String,
    val privacyMarkers: Set<AgentRunPrivacyMarker> = emptySet(),
    val riskMarkers: Set<AgentRunRiskMarker> = emptySet(),
)

data class AgentRunEventSummary(
    val runId: String?,
    val firstEventId: String?,
    val lastEventId: String?,
    val orderedEventKinds: List<AgentRunEventKind>,
    val currentState: AgentRunEventState,
    val terminalState: AgentRunTerminalState?,
    val terminalEventId: String?,
    val pendingConfirmation: AgentRunPendingConfirmationSummary?,
    val counts: AgentRunEventCounts,
    val labels: List<String>,
    val privacyMarkers: Set<AgentRunPrivacyMarker>,
    val riskMarkers: Set<AgentRunRiskMarker>,
    val latestSafeText: String?,
)

object AgentStepRunEventAdapter {
    fun adapt(run: AgentRun, steps: List<AgentStep>): List<AgentRunEvent> =
        buildList {
            add(
                AgentRunEvent.InputReceived(
                    eventId = "${run.id}:input",
                    runId = run.id,
                    inputId = "${run.id}:input",
                    sourceLabel = "typed",
                ),
            )
            steps.forEachIndexed { index, step ->
                addAll(step.toRunEvents(run.id, index))
            }
        }

    private fun AgentStep.toRunEvents(runId: String, index: Int): List<AgentRunEvent> =
        when (this) {
            is AgentStep.ContextLoaded -> listOf(
                AgentRunEvent.ContextLoaded(
                    eventId = "$runId:context:$index",
                    runId = runId,
                    contextId = "$runId:context:$index",
                    memoryHitCount = memoryHits.size,
                    deviceContextIncluded = deviceContext != null,
                    sourceLabels = buildList {
                        if (memoryHits.isNotEmpty()) add("memory")
                        if (deviceContext != null) add("device")
                    },
                    privacyMarkers = if (memoryHits.isNotEmpty() || deviceContext != null) {
                        setOf(AgentRunPrivacyMarker.ContainsLocalContext)
                    } else {
                        emptySet()
                    },
                ),
            )

            is AgentStep.ModelPlanned -> listOf(
                AgentRunEvent.PlanCreated(
                    eventId = "$runId:model-plan:$index",
                    runId = runId,
                    planId = "$runId:model-plan:$index",
                    stepCount = plan.estimatedStepCount(),
                    toolLabels = plan.safeLabels(),
                    riskMarkers = plan.riskMarkers(),
                ),
            )

            is AgentStep.SkillPlanned -> listOf(
                AgentRunEvent.PlanCreated(
                    eventId = "$runId:skill-plan:$index",
                    runId = runId,
                    planId = request.id,
                    stepCount = plan?.steps?.size ?: 1,
                    toolLabels = listOf(plan?.manifest?.title ?: request.skillId),
                    riskMarkers = plan?.manifest?.riskLevel?.toRiskMarkers().orEmpty(),
                ),
            )

            is AgentStep.ToolRequested -> listOf(
                AgentRunEvent.PlanCreated(
                    eventId = "$runId:tool-plan:$index",
                    runId = runId,
                    planId = request.id,
                    stepCount = 1,
                    toolLabels = listOf(draft.title.ifBlank { request.toolName }),
                ),
            )

            is AgentStep.UserConfirmationRequested -> listOf(
                AgentRunEvent.ConfirmationRequested(
                    eventId = "$runId:confirmation:$index",
                    runId = runId,
                    confirmationId = request.id,
                    toolCallId = request.id,
                    actionLabel = draft.title.ifBlank { request.toolName },
                    privacyMarkers = setOf(AgentRunPrivacyMarker.UserMediated),
                ),
            )

            is AgentStep.UserRejected -> listOf(
                AgentRunEvent.RunCancelled(
                    eventId = "$runId:user-rejected:$index",
                    runId = runId,
                    cancellationId = requestId,
                    reasonLabel = "user rejected",
                ),
            )

            is AgentStep.ToolObserved -> listOf(
                AgentRunEvent.ToolExecuted(
                    eventId = "$runId:tool-executed:$index",
                    runId = runId,
                    toolCallId = result.requestId,
                    toolLabel = result.summary,
                    status = result.status.toRunToolStatus(),
                ),
                AgentRunEvent.ObservationRecorded(
                    eventId = "$runId:observation:$index",
                    runId = runId,
                    observationId = result.requestId,
                    toolCallId = result.requestId,
                    observationLabel = result.summary,
                    sourceCount = result.data.size,
                    privacyMarkers = result.privacyMarkers(),
                ),
            )

            is AgentStep.ExternalOutcomeConfirmed -> listOf(
                AgentRunEvent.ToolExecuted(
                    eventId = "$runId:external-outcome:$index",
                    runId = runId,
                    toolCallId = requestId,
                    toolLabel = result.summary,
                    status = result.status.toRunToolStatus(),
                    riskMarkers = setOf(AgentRunRiskMarker.NeedsHumanReview),
                ),
                AgentRunEvent.ObservationRecorded(
                    eventId = "$runId:external-observation:$index",
                    runId = runId,
                    observationId = requestId,
                    toolCallId = requestId,
                    observationLabel = outcome.name,
                    sourceCount = 1,
                    privacyMarkers = setOf(AgentRunPrivacyMarker.UserMediated),
                ),
            )

            is AgentStep.ModelOutputQualityGuardTriggered -> listOf(
                AgentRunEvent.ObservationRecorded(
                    eventId = "$runId:quality:$index",
                    runId = runId,
                    observationId = "$runId:quality:$index",
                    observationLabel = "output quality guard: ${trace.issue}",
                ),
            )

            is AgentStep.AssistantResponded -> listOf(
                AgentRunEvent.AnswerGenerated(
                    eventId = "$runId:answer:$index",
                    runId = runId,
                    answerId = "$runId:answer:$index",
                    safeAnswerPreview = text,
                    outputLabel = "answer",
                ),
            )

            is AgentStep.ToolRejected -> listOf(
                AgentRunEvent.ToolExecuted(
                    eventId = "$runId:tool-rejected:$index",
                    runId = runId,
                    toolCallId = result.requestId,
                    toolLabel = result.summary,
                    status = AgentRunToolStatus.Failed,
                ),
            )

            is AgentStep.Failed -> listOf(
                AgentRunEvent.RunFailed(
                    eventId = "$runId:failed:$index",
                    runId = runId,
                    failureId = "$runId:failed:$index",
                    reasonLabel = reason,
                ),
            )

            is AgentStep.RestoredSummary -> listOf(
                AgentRunEvent.ObservationRecorded(
                    eventId = "$runId:restored:$index",
                    runId = runId,
                    observationId = "$runId:restored:$index",
                    observationLabel = summary,
                ),
            )

            is AgentStep.ObservationDecided -> when (decision) {
                AgentObservationDecision.Complete -> listOf(
                    AgentRunEvent.AnswerGenerated(
                        eventId = "$runId:answer-complete:$index",
                        runId = runId,
                        answerId = "$runId:answer-complete:$index",
                        outputLabel = "answer",
                    ),
                )

                AgentObservationDecision.Cancel -> listOf(
                    AgentRunEvent.RunCancelled(
                        eventId = "$runId:cancelled:$index",
                        runId = runId,
                        cancellationId = "$runId:cancelled:$index",
                        reasonLabel = "cancelled",
                    ),
                )

                is AgentObservationDecision.Fail -> listOf(
                    AgentRunEvent.RunFailed(
                        eventId = "$runId:decision-failed:$index",
                        runId = runId,
                        failureId = "$runId:decision-failed:$index",
                        reasonLabel = decision.reason,
                    ),
                )

                else -> emptyList()
            }

            is AgentStep.IntentRouted,
            is AgentStep.RemoteToolsExposed,
            is AgentStep.RunDataReceiptRecorded,
            is AgentStep.SafetyChecked,
            is AgentStep.UserConfirmed,
            is AgentStep.ContinuationCursorRecorded,
            is AgentStep.ToolRetryScheduled -> emptyList()
        }

    private fun AgentPlan.estimatedStepCount(): Int =
        when (this) {
            is AgentPlan.Answer -> 1
            is AgentPlan.UseTool -> skillPlan?.steps?.size ?: 1
            is AgentPlan.RejectedTool -> 1
            is AgentPlan.MissingModel -> 0
        }

    private fun AgentPlan.safeLabels(): List<String> =
        when (this) {
            is AgentPlan.Answer -> listOf("answer")
            is AgentPlan.UseTool -> listOf(draft.title.ifBlank { request.toolName })
            is AgentPlan.RejectedTool -> listOf(result.summary)
            is AgentPlan.MissingModel -> listOf("missing ${capability.name}")
        }

    private fun AgentPlan.riskMarkers(): Set<AgentRunRiskMarker> =
        when (this) {
            is AgentPlan.UseTool -> emptySet()
            else -> emptySet()
        }

    private fun com.bytedance.zgx.solin.tool.RiskLevel.toRiskMarkers(): Set<AgentRunRiskMarker> =
        when (this) {
            com.bytedance.zgx.solin.tool.RiskLevel.LowReadOnly -> emptySet()
            com.bytedance.zgx.solin.tool.RiskLevel.MediumDraftOrNavigation ->
                setOf(AgentRunRiskMarker.NeedsHumanReview)
            com.bytedance.zgx.solin.tool.RiskLevel.HighExternalSend ->
                setOf(AgentRunRiskMarker.ExternalSideEffect, AgentRunRiskMarker.NeedsHumanReview)
            com.bytedance.zgx.solin.tool.RiskLevel.CriticalDeviceOrPayment ->
                setOf(
                    AgentRunRiskMarker.ExternalSideEffect,
                    AgentRunRiskMarker.DestructiveAction,
                    AgentRunRiskMarker.NeedsHumanReview,
                )
        }

    private fun ToolStatus.toRunToolStatus(): AgentRunToolStatus =
        when (this) {
            ToolStatus.Succeeded -> AgentRunToolStatus.Succeeded
            ToolStatus.Cancelled -> AgentRunToolStatus.Cancelled
            ToolStatus.Failed,
            ToolStatus.Rejected -> AgentRunToolStatus.Failed
        }

    private fun com.bytedance.zgx.solin.tool.ToolResult.privacyMarkers(): Set<AgentRunPrivacyMarker> =
        when (data["privacy"]) {
            "LocalOnly" -> setOf(AgentRunPrivacyMarker.LocalOnly)
            "RemoteEligible" -> emptySet()
            else -> emptySet()
        }
}

object AgentRunEventProjector {
    fun summarize(events: List<AgentRunEvent>): AgentRunEventSummary {
        if (events.isEmpty()) {
            return AgentRunEventSummary(
                runId = null,
                firstEventId = null,
                lastEventId = null,
                orderedEventKinds = emptyList(),
                currentState = AgentRunEventState.Empty,
                terminalState = null,
                terminalEventId = null,
                pendingConfirmation = null,
                counts = AgentRunEventCounts(),
                labels = emptyList(),
                privacyMarkers = emptySet(),
                riskMarkers = emptySet(),
                latestSafeText = null,
            )
        }

        val labels = linkedSetOf<String>()
        val privacyMarkers = linkedSetOf<AgentRunPrivacyMarker>()
        val riskMarkers = linkedSetOf<AgentRunRiskMarker>()
        var pendingConfirmation: AgentRunPendingConfirmationSummary? = null
        var terminalState: AgentRunTerminalState? = null
        var terminalEventId: String? = null
        var latestSafeText: String? = null

        var inputReceived = 0
        var contextLoaded = 0
        var planCreated = 0
        var confirmationRequested = 0
        var toolExecuted = 0
        var observationRecorded = 0
        var answerGenerated = 0
        var runFailed = 0
        var runCancelled = 0
        var memoryHits = 0
        var deviceContextLoads = 0
        var plannedSteps = 0
        var observationSources = 0
        var inputAttachments = 0

        events.forEach { event ->
            when (event) {
                is AgentRunEvent.InputReceived -> {
                    inputReceived += 1
                    inputAttachments += event.attachmentCount.coerceAtLeast(0)
                    privacyMarkers.addAll(event.privacyMarkers)
                    labels.addSanitizedLabel(event.sourceLabel)
                }

                is AgentRunEvent.ContextLoaded -> {
                    contextLoaded += 1
                    memoryHits += event.memoryHitCount.coerceAtLeast(0)
                    if (event.deviceContextIncluded) {
                        deviceContextLoads += 1
                    }
                    privacyMarkers.addAll(event.privacyMarkers)
                    event.sourceLabels.forEach(labels::addSanitizedLabel)
                }

                is AgentRunEvent.PlanCreated -> {
                    planCreated += 1
                    plannedSteps += event.stepCount.coerceAtLeast(0)
                    riskMarkers.addAll(event.riskMarkers)
                    event.toolLabels.forEach(labels::addSanitizedLabel)
                }

                is AgentRunEvent.ConfirmationRequested -> {
                    confirmationRequested += 1
                    privacyMarkers.addAll(event.privacyMarkers)
                    riskMarkers.addAll(event.riskMarkers)
                    labels.addSanitizedLabel(event.actionLabel)
                    pendingConfirmation = AgentRunPendingConfirmationSummary(
                        confirmationId = sanitizeId(event.confirmationId),
                        toolCallId = sanitizeId(event.toolCallId),
                        actionLabel = sanitizeText(event.actionLabel),
                        privacyMarkers = event.privacyMarkers,
                        riskMarkers = event.riskMarkers,
                    )
                }

                is AgentRunEvent.ToolExecuted -> {
                    toolExecuted += 1
                    riskMarkers.addAll(event.riskMarkers)
                    labels.addSanitizedLabel(event.toolLabel)
                    if (pendingConfirmation?.toolCallId == sanitizeId(event.toolCallId)) {
                        pendingConfirmation = null
                    }
                }

                is AgentRunEvent.ObservationRecorded -> {
                    observationRecorded += 1
                    observationSources += event.sourceCount.coerceAtLeast(0)
                    privacyMarkers.addAll(event.privacyMarkers)
                    labels.addSanitizedLabel(event.observationLabel)
                }

                is AgentRunEvent.AnswerGenerated -> {
                    answerGenerated += 1
                    privacyMarkers.addAll(event.privacyMarkers)
                    event.outputLabel?.let(labels::addSanitizedLabel)
                    latestSafeText = event.safeAnswerPreview?.let(::sanitizeText)
                    pendingConfirmation = null
                    terminalState = AgentRunTerminalState.AnswerGenerated
                    terminalEventId = sanitizeId(event.answerId)
                }

                is AgentRunEvent.RunFailed -> {
                    runFailed += 1
                    riskMarkers.addAll(event.riskMarkers)
                    labels.addSanitizedLabel(event.reasonLabel)
                    pendingConfirmation = null
                    terminalState = AgentRunTerminalState.Failed
                    terminalEventId = sanitizeId(event.failureId)
                }

                is AgentRunEvent.RunCancelled -> {
                    runCancelled += 1
                    labels.addSanitizedLabel(event.reasonLabel)
                    pendingConfirmation = null
                    terminalState = AgentRunTerminalState.Cancelled
                    terminalEventId = sanitizeId(event.cancellationId)
                }
            }
        }

        val counts = AgentRunEventCounts(
            total = events.size,
            inputReceived = inputReceived,
            contextLoaded = contextLoaded,
            planCreated = planCreated,
            confirmationRequested = confirmationRequested,
            toolExecuted = toolExecuted,
            observationRecorded = observationRecorded,
            answerGenerated = answerGenerated,
            runFailed = runFailed,
            runCancelled = runCancelled,
            memoryHits = memoryHits,
            deviceContextLoads = deviceContextLoads,
            plannedSteps = plannedSteps,
            observationSources = observationSources,
            inputAttachments = inputAttachments,
        )

        return AgentRunEventSummary(
            runId = summarizeRunId(events),
            firstEventId = sanitizeId(events.first().eventId),
            lastEventId = sanitizeId(events.last().eventId),
            orderedEventKinds = events.map { it.kind },
            currentState = currentState(events.last(), terminalState, pendingConfirmation),
            terminalState = terminalState,
            terminalEventId = terminalEventId,
            pendingConfirmation = pendingConfirmation,
            counts = counts,
            labels = labels.toList(),
            privacyMarkers = privacyMarkers,
            riskMarkers = riskMarkers,
            latestSafeText = latestSafeText,
        )
    }

    private fun currentState(
        lastEvent: AgentRunEvent,
        terminalState: AgentRunTerminalState?,
        pendingConfirmation: AgentRunPendingConfirmationSummary?,
    ): AgentRunEventState =
        when {
            terminalState == AgentRunTerminalState.Failed -> AgentRunEventState.Failed
            terminalState == AgentRunTerminalState.Cancelled -> AgentRunEventState.Cancelled
            terminalState == AgentRunTerminalState.AnswerGenerated -> AgentRunEventState.Completed
            pendingConfirmation != null -> AgentRunEventState.AwaitingConfirmation
            else -> when (lastEvent) {
                is AgentRunEvent.InputReceived -> AgentRunEventState.InputReceived
                is AgentRunEvent.ContextLoaded -> AgentRunEventState.ContextLoaded
                is AgentRunEvent.PlanCreated -> AgentRunEventState.Planning
                is AgentRunEvent.ConfirmationRequested -> AgentRunEventState.AwaitingConfirmation
                is AgentRunEvent.ToolExecuted -> AgentRunEventState.Executing
                is AgentRunEvent.ObservationRecorded -> AgentRunEventState.Observing
                is AgentRunEvent.AnswerGenerated -> AgentRunEventState.Completed
                is AgentRunEvent.RunFailed -> AgentRunEventState.Failed
                is AgentRunEvent.RunCancelled -> AgentRunEventState.Cancelled
            }
        }

    private fun summarizeRunId(events: List<AgentRunEvent>): String? {
        val firstRunId = events.first().runId
        return if (events.all { it.runId == firstRunId }) {
            sanitizeId(firstRunId)
        } else {
            "mixed"
        }
    }
}

private fun MutableSet<String>.addSanitizedLabel(value: String) {
    add(sanitizeText(value))
}

private fun sanitizeId(value: String): String {
    val normalized = normalizeText(value)
    return if (
        normalized.isBlank() ||
        normalized.length > MAX_ID_LENGTH ||
        normalized.anyUnsafeContent() ||
        !SAFE_ID_PATTERN.matches(normalized)
    ) {
        REDACTED_ID
    } else {
        normalized
    }
}

private fun sanitizeText(value: String): String {
    val normalized = normalizeText(value)
    if (normalized.isBlank()) {
        return REDACTED_TEXT
    }
    if (normalized.anyUnsafeContent()) {
        return REDACTED_TEXT
    }
    return normalized.take(MAX_TEXT_LENGTH)
}

private fun normalizeText(value: String): String =
    value.trim().replace(WHITESPACE_PATTERN, " ")

private fun String.anyUnsafeContent(): Boolean =
    UNSAFE_TEXT_PATTERNS.any { pattern -> pattern.containsMatchIn(this) }

private const val REDACTED_ID = "[redacted-id]"
private const val REDACTED_TEXT = "[redacted]"
private const val MAX_ID_LENGTH = 80
private const val MAX_TEXT_LENGTH = 120

private val WHITESPACE_PATTERN = Regex("\\s+")
private val SAFE_ID_PATTERN = Regex("[A-Za-z0-9._:-]+")
private val UNSAFE_TEXT_PATTERNS = listOf(
    Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
    Regex("(?i)\\b(password|passwd|secret|token|api[_ -]?key|credential|private)\\b\\s*[:=]?\\s*\\S*"),
    Regex("(?i)\\b(rm\\s+-rf|curl\\s+|wget\\s+|bash\\s+-c|sh\\s+-c|powershell\\s+|sudo\\s+|chmod\\s+|eval\\s*\\(|exec\\s*\\(|adb\\s+shell|am\\s+start)\\b"),
    Regex("(?i)\\bhttps?://\\S+"),
    Regex("[{}]"),
)
