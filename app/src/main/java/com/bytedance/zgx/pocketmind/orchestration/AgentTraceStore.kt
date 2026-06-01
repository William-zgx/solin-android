package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.audit.ToolAuditSummaryRedactor
import com.bytedance.zgx.pocketmind.data.AgentRunEntity
import com.bytedance.zgx.pocketmind.data.AgentStepEntity
import com.bytedance.zgx.pocketmind.data.AgentTraceDao
import com.bytedance.zgx.pocketmind.data.PendingAgentConfirmationEntity
import com.bytedance.zgx.pocketmind.skill.SkillManifest
import com.bytedance.zgx.pocketmind.skill.SkillPlan
import com.bytedance.zgx.pocketmind.skill.SkillRequest
import com.bytedance.zgx.pocketmind.skill.SkillStep
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

data class AgentTraceStepSummary(
    val runId: String,
    val position: Int,
    val type: String,
    val summary: String,
    val json: String,
    val createdAtMillis: Long,
)

data class AgentTraceRunSummary(
    val run: AgentRun,
    val steps: List<AgentTraceStepSummary>,
)

private val toolObservedCompletionMetadataAllowlist = listOf(
    "completionState",
    "completionVerified",
    "exceptionType",
    "externalOutcome",
    "intentAction",
    "metadataPolicy",
    "ocrTextIncluded",
    "payloadMimeType",
    "rawPayloadIncluded",
    "rawTreeIncluded",
    "settingsAction",
    "screenTextIncluded",
    "specialAccess",
    "targetKind",
    "targetId",
    "targetPackage",
    "targetUriHost",
    "targetUriPort",
    "targetUriScheme",
    "recoveryTaskId",
    "recoveryToolName",
)

private const val REDACTED_AGENT_RUN_INPUT = "[redacted]"

interface AgentTraceStore {
    fun createRun(input: String): AgentRun
    fun run(runId: String): AgentRun?
    fun updateState(runId: String, state: AgentRunState): AgentRun
    fun appendStep(runId: String, step: AgentStep)
    fun steps(runId: String): List<AgentStep>
    fun stepSummaries(runId: String): List<AgentTraceStepSummary>
    fun recentRunSummaries(limit: Int = 5, stepLimit: Int = 20): List<AgentTraceRunSummary>
    fun savePendingConfirmation(snapshot: PendingToolConfirmationSnapshot)
    fun latestPendingConfirmation(): PendingToolConfirmationSnapshot?
    fun clearPendingConfirmation(runId: String, requestId: String): Boolean
    fun failStaleInFlightRuns(reason: String): Int
}

class InMemoryAgentTraceStore(
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : AgentTraceStore {
    private val nextRunId = AtomicLong(0L)
    private val runs = linkedMapOf<String, AgentRun>()
    private val runSteps = linkedMapOf<String, MutableList<AgentStep>>()
    private val runStepSummaries = linkedMapOf<String, MutableList<AgentTraceStepSummary>>()
    private val pendingConfirmations = linkedMapOf<String, PendingToolConfirmationSnapshot>()

    override fun createRun(input: String): AgentRun {
        val now = clockMillis()
        val run = AgentRun(
            id = "run-${nextRunId.incrementAndGet()}",
            input = input,
            state = AgentRunState.Created,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        runs[run.id] = run
        runSteps[run.id] = mutableListOf()
        runStepSummaries[run.id] = mutableListOf()
        return run
    }

    override fun run(runId: String): AgentRun? =
        runs[runId]

    override fun updateState(runId: String, state: AgentRunState): AgentRun {
        val existing = runs.getValue(runId)
        val updated = existing.copy(
            state = state,
            updatedAtMillis = clockMillis(),
        )
        runs[runId] = updated
        return updated
    }

    override fun appendStep(runId: String, step: AgentStep) {
        val steps = runSteps.getValue(runId)
        runStepSummaries.getValue(runId).add(
            step.toTraceStepSummary(
                runId = runId,
                position = steps.size,
                createdAtMillis = clockMillis(),
            ),
        )
        steps.add(step)
    }

    override fun steps(runId: String): List<AgentStep> =
        runSteps[runId].orEmpty().toList()

    override fun stepSummaries(runId: String): List<AgentTraceStepSummary> =
        runStepSummaries[runId].orEmpty().toList()

    override fun recentRunSummaries(limit: Int, stepLimit: Int): List<AgentTraceRunSummary> {
        if (limit <= 0) return emptyList()
        return runs.values
            .sortedWith(compareByDescending<AgentRun> { it.updatedAtMillis }.thenByDescending { it.createdAtMillis })
            .take(limit)
            .map { run ->
                AgentTraceRunSummary(
                    run = run,
                    steps = stepSummaries(run.id).takeLast(stepLimit.coerceAtLeast(0)),
                )
            }
    }

    override fun savePendingConfirmation(snapshot: PendingToolConfirmationSnapshot) {
        pendingConfirmations[snapshot.run.id] = snapshot
    }

    override fun latestPendingConfirmation(): PendingToolConfirmationSnapshot? =
        pendingConfirmations.values
            .sortedByDescending { snapshot -> snapshot.run.updatedAtMillis }
            .firstNotNullOfOrNull { snapshot ->
                val run = runs[snapshot.run.id]
                when (run?.state) {
                    AgentRunState.AwaitingUserConfirmation -> snapshot.copy(run = run)
                    null -> null
                    else -> {
                        pendingConfirmations.remove(snapshot.run.id)
                        null
                    }
                }
            }

    override fun clearPendingConfirmation(runId: String, requestId: String): Boolean =
        pendingConfirmations[runId]
            ?.takeIf { snapshot -> snapshot.request.id == requestId }
            ?.let {
                pendingConfirmations.remove(runId)
                true
            } ?: false

    override fun failStaleInFlightRuns(reason: String): Int {
        val staleRuns = runs.values
            .filter { run -> run.state.isStaleAfterProcessRestart() }
        staleRuns.forEach { run ->
            appendStep(run.id, AgentStep.Failed(reason))
            updateState(run.id, AgentRunState.Failed)
            pendingConfirmations.remove(run.id)
        }
        return staleRuns.size
    }
}

class RoomAgentTraceStore(
    private val traceDao: AgentTraceDao,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val runIdFactory: () -> String = { "run-${UUID.randomUUID()}" },
) : AgentTraceStore {
    private val liveRuns = linkedMapOf<String, AgentRun>()
    private val liveSteps = linkedMapOf<String, MutableList<AgentStep>>()

    override fun createRun(input: String): AgentRun {
        val now = clockMillis()
        val run = AgentRun(
            id = runIdFactory(),
            input = input,
            state = AgentRunState.Created,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        liveRuns[run.id] = run
        traceDao.upsertRun(run.toEntity())
        liveSteps[run.id] = mutableListOf()
        return run
    }

    override fun run(runId: String): AgentRun? =
        liveRuns[runId]
            ?: traceDao.run(runId)?.toDomain()

    override fun updateState(runId: String, state: AgentRunState): AgentRun {
        val now = clockMillis()
        val updatedRows = traceDao.updateRunState(runId, state.name, now)
        require(updatedRows > 0) { "Agent run does not exist: $runId" }
        liveRuns[runId]?.let { liveRun ->
            val updated = liveRun.copy(
                state = state,
                updatedAtMillis = now,
            )
            liveRuns[runId] = updated
            return updated
        }
        return traceDao.run(runId)?.toDomain()
            ?: error("Agent run disappeared after update: $runId")
    }

    override fun appendStep(runId: String, step: AgentStep) {
        requireNotNull(traceDao.run(runId)) { "Agent run does not exist: $runId" }
        val now = clockMillis()
        val position = traceDao.nextStepPosition(runId)
        traceDao.insertStep(step.toEntity(runId, position, now))
        traceDao.touchRun(runId, now)
        liveRuns[runId]?.let { liveRun ->
            liveRuns[runId] = liveRun.copy(updatedAtMillis = now)
        }
        liveSteps.getOrPut(runId) { mutableListOf() }.add(step)
    }

    override fun steps(runId: String): List<AgentStep> =
        liveSteps[runId]?.toList()
            ?: traceDao.steps(runId).map { entity -> entity.toRestoredStep() }

    override fun stepSummaries(runId: String): List<AgentTraceStepSummary> =
        traceDao.steps(runId).map { entity -> entity.toSummary() }

    override fun recentRunSummaries(limit: Int, stepLimit: Int): List<AgentTraceRunSummary> {
        if (limit <= 0) return emptyList()
        val safeStepLimit = stepLimit.coerceAtLeast(0)
        return traceDao.recentRuns(limit).map { entity ->
            val run = entity.toDomain()
            AgentTraceRunSummary(
                run = run,
                steps = stepSummaries(run.id).takeLast(safeStepLimit),
            )
        }
    }

    override fun savePendingConfirmation(snapshot: PendingToolConfirmationSnapshot) {
        val now = clockMillis()
        traceDao.upsertPendingConfirmation(snapshot.toEntity(now))
    }

    override fun latestPendingConfirmation(): PendingToolConfirmationSnapshot? {
        for (entity in traceDao.pendingConfirmations()) {
            val run = run(entity.runId)
            if (run == null || run.state != AgentRunState.AwaitingUserConfirmation) {
                traceDao.deletePendingConfirmation(entity.runId, entity.requestId)
                continue
            }
            val snapshot = try {
                entity.toSnapshot(run)
            } catch (_: Exception) {
                traceDao.deletePendingConfirmation(entity.runId, entity.requestId)
                null
            } ?: continue
            if (!snapshot.hasRestorableSkillPlanRequest()) {
                traceDao.deletePendingConfirmation(entity.runId, entity.requestId)
                continue
            }
            hydrateLivePendingSteps(snapshot)
            return snapshot
        }
        return null
    }

    override fun clearPendingConfirmation(runId: String, requestId: String): Boolean =
        traceDao.deletePendingConfirmation(runId, requestId) > 0

    override fun failStaleInFlightRuns(reason: String): Int {
        val staleRuns = traceDao.recentRuns(Int.MAX_VALUE)
            .map { entity -> entity.toDomain() }
            .filter { run -> run.state.isStaleAfterProcessRestart() }
        staleRuns.forEach { run ->
            appendStep(run.id, AgentStep.Failed(reason))
            updateState(run.id, AgentRunState.Failed)
        }
        return staleRuns.size
    }

    private fun hydrateLivePendingSteps(snapshot: PendingToolConfirmationSnapshot) {
        val steps = liveSteps.getOrPut(snapshot.run.id) { mutableListOf() }
        snapshot.skillPlan?.let { plan ->
            if (steps.none { step -> step is AgentStep.SkillPlanned && step.request.id == plan.request.id }) {
                val toolIndex = steps.indexOfFirst { step ->
                    step is AgentStep.ToolRequested && step.request.id == snapshot.request.id
                }
                val skillStep = AgentStep.SkillPlanned(plan.request, plan)
                if (toolIndex >= 0) {
                    steps.add(toolIndex, skillStep)
                } else {
                    steps += skillStep
                }
            }
        }
        if (steps.none { step -> step is AgentStep.ToolRequested && step.request.id == snapshot.request.id }) {
            steps += AgentStep.ToolRequested(snapshot.request, snapshot.draft)
        }
        if (steps.none { step ->
                step is AgentStep.UserConfirmationRequested && step.request.id == snapshot.request.id
            }
        ) {
            steps += AgentStep.UserConfirmationRequested(snapshot.request, snapshot.draft)
        }
    }
}

private fun AgentRunState.isStaleAfterProcessRestart(): Boolean =
    this in setOf(
        AgentRunState.Created,
        AgentRunState.LoadingContext,
        AgentRunState.Planning,
        AgentRunState.ExecutingTool,
        AgentRunState.RetryingTool,
        AgentRunState.Observing,
        AgentRunState.GeneratingAnswer,
    )

private fun AgentRun.toEntity(): AgentRunEntity =
    AgentRunEntity(
        id = id,
        input = REDACTED_AGENT_RUN_INPUT,
        state = state.name,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

private fun AgentRunEntity.toDomain(): AgentRun =
    AgentRun(
        id = id,
        input = input,
        state = runCatching { AgentRunState.valueOf(state) }.getOrDefault(AgentRunState.Failed),
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

private fun AgentStep.toEntity(runId: String, position: Int, createdAtMillis: Long): AgentStepEntity {
    val summary = toTraceStepSummary(runId, position, createdAtMillis)
    return AgentStepEntity(
        runId = summary.runId,
        position = summary.position,
        type = summary.type,
        summary = summary.summary,
        json = summary.json,
        createdAtMillis = summary.createdAtMillis,
    )
}

private fun AgentStep.toTraceStepSummary(
    runId: String,
    position: Int,
    createdAtMillis: Long,
): AgentTraceStepSummary {
    val type = traceType()
    return AgentTraceStepSummary(
        runId = runId,
        position = position,
        type = type,
        summary = traceSummary(),
        json = traceJson(type).toString(),
        createdAtMillis = createdAtMillis,
    )
}

private fun AgentStepEntity.toSummary(): AgentTraceStepSummary =
    AgentTraceStepSummary(
        runId = runId,
        position = position,
        type = type,
        summary = summary,
        json = json,
        createdAtMillis = createdAtMillis,
    )

private fun AgentStepEntity.toRestoredStep(): AgentStep.RestoredSummary =
    AgentStep.RestoredSummary(
        persistedType = type,
        summary = summary,
        json = json,
    )

private fun PendingToolConfirmationSnapshot.toEntity(now: Long): PendingAgentConfirmationEntity =
    PendingAgentConfirmationEntity(
        runId = run.id,
        requestId = request.id,
        toolName = request.toolName,
        argumentsJson = request.arguments.toJsonObject().toString(),
        reason = request.reason,
        draftFunctionName = draft.functionName,
        draftTitle = draft.title,
        draftSummary = draft.summary,
        draftParametersJson = draft.parameters.toJsonObject().toString(),
        skillId = skillId,
        skillPlanJson = skillPlan?.toJsonObject()?.toString(),
        plannedByModel = plannedByModel,
        fallbackReason = fallbackReason,
        createdAtMillis = now,
        updatedAtMillis = now,
    )

private fun PendingAgentConfirmationEntity.toSnapshot(run: AgentRun): PendingToolConfirmationSnapshot =
    PendingToolConfirmationSnapshot(
        run = run,
        request = ToolRequest(
            id = requestId,
            toolName = toolName,
            arguments = argumentsJson.toStringMap(),
            reason = reason,
        ),
        draft = ActionDraft(
            functionName = draftFunctionName,
            title = draftTitle,
            summary = draftSummary,
            parameters = draftParametersJson.toStringMap(),
        ),
        skillId = skillId,
        skillPlan = skillPlanJson?.toSkillPlanOrNull(),
        plannedByModel = plannedByModel,
        fallbackReason = fallbackReason,
    )

private fun PendingToolConfirmationSnapshot.hasRestorableSkillPlanRequest(): Boolean {
    val plan = skillPlan ?: return true
    return plan.steps
        .filterIsInstance<SkillStep.ToolStep>()
        .any { step ->
            step.request.id == request.id && step.request.toolName == request.toolName
        }
}

private fun Map<String, String>.toJsonObject(): JSONObject {
    val json = JSONObject()
    entries.sortedBy { it.key }.forEach { (key, value) ->
        json.put(key, value)
    }
    return json
}

private fun String.toStringMap(): Map<String, String> =
    JSONObject(this).toStringMap()

private fun JSONObject.toStringMap(): Map<String, String> {
    return buildMap {
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, optString(key))
        }
    }
}

private fun Map<String, String>.allowlistedCompletionMetadataJson(): JSONObject {
    val json = JSONObject()
    toolObservedCompletionMetadataAllowlist.forEach { key ->
        this[key]?.takeIf { value -> value.isNotBlank() }?.let { value ->
            json.put(key, value.shortTraceText())
        }
    }
    return json
}

private fun SkillPlan.toJsonObject(): JSONObject =
    JSONObject()
        .put("request", request.toJsonObject())
        .put("manifest", manifest.toJsonObject())
        .put(
            "steps",
            JSONArray().also { array ->
                steps.forEach { step -> array.put(step.toJsonObject()) }
            },
        )

private fun String.toSkillPlanOrNull(): SkillPlan? =
    runCatching { JSONObject(this).toSkillPlan() }.getOrNull()

private fun JSONObject.toSkillPlan(): SkillPlan =
    SkillPlan(
        request = getJSONObject("request").toSkillRequest(),
        manifest = getJSONObject("manifest").toSkillManifest(),
        steps = getJSONArray("steps").toSkillSteps(),
    )

private fun SkillRequest.toJsonObject(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("skillId", skillId)
        .put("arguments", arguments.toJsonObject())
        .put("reason", reason)

private fun JSONObject.toSkillRequest(): SkillRequest =
    SkillRequest(
        id = getString("id"),
        skillId = getString("skillId"),
        arguments = getJSONObject("arguments").toStringMap(),
        reason = optString("reason"),
    )

private fun SkillManifest.toJsonObject(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("version", version)
        .put("title", title)
        .put("description", description)
        .put("triggerExamples", triggerExamples.toJsonArray())
        .put("requiredTools", requiredTools.toJsonArray())
        .put("inputSchemaJson", inputSchemaJson)
        .put("riskLevel", riskLevel.name)

private fun JSONObject.toSkillManifest(): SkillManifest =
    SkillManifest(
        id = getString("id"),
        version = getInt("version"),
        title = getString("title"),
        description = getString("description"),
        triggerExamples = getJSONArray("triggerExamples").toStringList(),
        requiredTools = getJSONArray("requiredTools").toStringList(),
        inputSchemaJson = getString("inputSchemaJson"),
        riskLevel = RiskLevel.valueOf(getString("riskLevel")),
    )

private fun SkillStep.toJsonObject(): JSONObject =
    when (this) {
        is SkillStep.ToolStep -> JSONObject()
            .put("type", "tool")
            .put("id", id)
            .put("dependsOn", dependsOn.toJsonArray())
            .put("request", request.toJsonObject())
            .put("draft", draft.toJsonObject())
            .put("argumentBindings", argumentBindings.toJsonObject())

        is SkillStep.ModelStep -> JSONObject()
            .put("type", "model")
            .put("id", id)
            .put("dependsOn", dependsOn.toJsonArray())
            .put("title", title)
            .put("instruction", instruction)
            .put("inputBindings", inputBindings.toJsonObject())
            .put("outputKey", outputKey)
            .put("keepsSensitiveInputLocal", keepsSensitiveInputLocal)
    }

private fun JSONArray.toSkillSteps(): List<SkillStep> =
    buildList {
        for (index in 0 until length()) {
            val step = getJSONObject(index)
            add(step.toSkillStep())
        }
    }

private fun JSONObject.toSkillStep(): SkillStep =
    when (getString("type")) {
        "tool" -> SkillStep.ToolStep(
            id = getString("id"),
            dependsOn = getJSONArray("dependsOn").toStringList(),
            request = getJSONObject("request").toToolRequest(),
            draft = getJSONObject("draft").toActionDraft(),
            argumentBindings = getJSONObject("argumentBindings").toStringMap(),
        )

        "model" -> SkillStep.ModelStep(
            id = getString("id"),
            dependsOn = getJSONArray("dependsOn").toStringList(),
            title = getString("title"),
            instruction = getString("instruction"),
            inputBindings = getJSONObject("inputBindings").toStringMap(),
            outputKey = getString("outputKey"),
            keepsSensitiveInputLocal = optBoolean("keepsSensitiveInputLocal", true),
        )

        else -> error("Unknown skill step type: ${getString("type")}")
    }

private fun ToolRequest.toJsonObject(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("toolName", toolName)
        .put("arguments", arguments.toJsonObject())
        .put("reason", reason)

private fun JSONObject.toToolRequest(): ToolRequest =
    ToolRequest(
        id = getString("id"),
        toolName = getString("toolName"),
        arguments = getJSONObject("arguments").toStringMap(),
        reason = optString("reason"),
    )

private fun ActionDraft.toJsonObject(): JSONObject =
    JSONObject()
        .put("functionName", functionName)
        .put("title", title)
        .put("summary", summary)
        .put("parameters", parameters.toJsonObject())
        .put("requiresConfirmation", requiresConfirmation)

private fun JSONObject.toActionDraft(): ActionDraft =
    ActionDraft(
        functionName = getString("functionName"),
        title = getString("title"),
        summary = getString("summary"),
        parameters = getJSONObject("parameters").toStringMap(),
        requiresConfirmation = optBoolean("requiresConfirmation", true),
    )

private fun List<String>.toJsonArray(): JSONArray =
    JSONArray().also { array -> forEach { value -> array.put(value) } }

private fun JSONArray.toStringList(): List<String> =
    buildList {
        for (index in 0 until length()) {
            add(optString(index))
        }
    }

private fun AgentStep.traceType(): String =
    when (this) {
        is AgentStep.ContextLoaded -> "ContextLoaded"
        is AgentStep.ModelPlanned -> "ModelPlanned"
        is AgentStep.ToolRequested -> "ToolRequested"
        is AgentStep.SkillPlanned -> "SkillPlanned"
        is AgentStep.SafetyChecked -> "SafetyChecked"
        is AgentStep.UserConfirmationRequested -> "UserConfirmationRequested"
        is AgentStep.UserConfirmed -> "UserConfirmed"
        is AgentStep.UserRejected -> "UserRejected"
        is AgentStep.ToolObserved -> "ToolObserved"
        is AgentStep.ToolRetryScheduled -> "ToolRetryScheduled"
        is AgentStep.ObservationDecided -> "ObservationDecided"
        is AgentStep.AssistantResponded -> "AssistantResponded"
        is AgentStep.ToolRejected -> "ToolRejected"
        is AgentStep.Failed -> "Failed"
        is AgentStep.RestoredSummary -> persistedType
    }

private fun AgentStep.traceSummary(): String =
    when (this) {
        is AgentStep.ContextLoaded -> {
            val deviceContext = if (deviceContext == null) "without device context" else "with device context"
            "Loaded ${memoryHits.size} memory hit(s) $deviceContext."
        }

        is AgentStep.ModelPlanned -> plan.traceSummary()
        is AgentStep.ToolRequested -> "Requested tool ${request.toolName}."
        is AgentStep.SkillPlanned -> "Planned skill ${request.skillId} with ${plan?.steps?.size ?: 0} step(s)."
        is AgentStep.SafetyChecked -> "Safety ${decision.outcome}: ${decision.reason.shortTraceText()}"
        is AgentStep.UserConfirmationRequested -> "Requested confirmation for ${request.toolName}."
        is AgentStep.UserConfirmed -> "User confirmed request $requestId."
        is AgentStep.UserRejected -> "User rejected request $requestId."
        is AgentStep.ToolObserved -> "Observed ${result.status} for ${result.requestId}: ${result.summary.shortTraceText()}"
        is AgentStep.ToolRetryScheduled -> "Retry $attempt scheduled for ${request.toolName}: ${reason.shortTraceText()}"
        is AgentStep.ObservationDecided -> decision.traceSummary()
        is AgentStep.AssistantResponded -> "Assistant responded: ${text.shortTraceText()}"
        is AgentStep.ToolRejected -> "Rejected tool result ${result.requestId}: ${result.summary.shortTraceText()}"
        is AgentStep.Failed -> "Failed: ${reason.shortTraceText()}"
        is AgentStep.RestoredSummary -> summary
    }

private fun AgentPlan.traceSummary(): String =
    when (this) {
        is AgentPlan.Answer -> "Planned answer generation."
        is AgentPlan.UseTool -> "Planned tool ${request.toolName}."
        is AgentPlan.RejectedTool -> "Rejected tool plan: ${result.summary.shortTraceText()}"
        is AgentPlan.MissingModel -> "Missing model capability ${capability.name}."
    }

private fun AgentObservationDecision.traceSummary(): String =
    when (this) {
        AgentObservationDecision.Complete -> "Observation complete."
        is AgentObservationDecision.ContinueWithModel ->
            "Continue with model: ${reason.shortTraceText()}"
        is AgentObservationDecision.RetryTool ->
            "Retry ${request.toolName} attempt $attempt: ${reason.shortTraceText()}"
        is AgentObservationDecision.PlanNextTool ->
            "Plan next tool ${plan.request.toolName}: ${reason.shortTraceText()}"
        is AgentObservationDecision.Fail -> "Observation failed: ${reason.shortTraceText()}"
        AgentObservationDecision.Cancel -> "Observation cancelled."
    }

private fun AgentStep.traceJson(type: String): JSONObject {
    val json = JSONObject().put("type", type)
    when (this) {
        is AgentStep.ContextLoaded -> json
            .put("memoryHitCount", memoryHits.size)
            .put("hasDeviceContext", deviceContext != null)

        is AgentStep.ModelPlanned -> json.put("plan", plan.traceJson())
        is AgentStep.ToolRequested -> json
            .put("requestId", request.id)
            .put("toolName", request.toolName)
            .put("argumentKeys", request.arguments.keys.sorted())
            .put("draftTitle", draft.title.shortTraceText())

        is AgentStep.SkillPlanned -> json
            .put("skillRequestId", request.id)
            .put("skillId", request.skillId)
            .put("stepCount", plan?.steps?.size ?: 0)

        is AgentStep.SafetyChecked -> json
            .put("outcome", decision.outcome.name)
            .put("reason", decision.reason.shortTraceText())

        is AgentStep.UserConfirmationRequested -> json
            .put("requestId", request.id)
            .put("toolName", request.toolName)
            .put("draftTitle", draft.title.shortTraceText())

        is AgentStep.UserConfirmed -> json.put("requestId", requestId)
        is AgentStep.UserRejected -> json.put("requestId", requestId)
        is AgentStep.ToolObserved -> json
            .put("requestId", result.requestId)
            .put("status", result.status.name)
            .put("summary", result.summary.shortTraceText())
            .put("retryable", result.retryable)
            .also {
                val metadata = result.data.allowlistedCompletionMetadataJson()
                if (metadata.length() > 0) {
                    it.put("completionMetadata", metadata)
                }
            }

        is AgentStep.ToolRetryScheduled -> json
            .put("requestId", request.id)
            .put("toolName", request.toolName)
            .put("attempt", attempt)
            .put("reason", reason.shortTraceText())

        is AgentStep.ObservationDecided -> json.put("decision", decision.traceJson())
        is AgentStep.AssistantResponded -> json
            .put("textPreview", text.shortTraceText())
            .put("textLength", text.length)

        is AgentStep.ToolRejected -> json
            .put("requestId", result.requestId)
            .put("status", result.status.name)
            .put("summary", result.summary.shortTraceText())

        is AgentStep.Failed -> json.put("reason", reason.shortTraceText())

        is AgentStep.RestoredSummary -> runCatching { JSONObject(this.json) }.getOrElse {
            json
                .put("persistedType", persistedType)
                .put("summary", summary.shortTraceText())
        }
    }
    return json
}

private fun AgentPlan.traceJson(): JSONObject {
    val json = JSONObject()
    when (this) {
        is AgentPlan.Answer -> json
            .put("type", "Answer")
            .put("memoryHitCount", memoryHits.size)
            .put("hasDeviceContext", deviceContext != null)

        is AgentPlan.UseTool -> json
            .put("type", "UseTool")
            .put("requestId", request.id)
            .put("toolName", request.toolName)
            .put("plannedByModel", plannedByModel)
            .put("skillId", skillRequest?.skillId)
            .put("fallbackReason", fallbackReason?.shortTraceText())
            .put("safetyOutcome", safetyDecision.outcome.name)

        is AgentPlan.RejectedTool -> json
            .put("type", "RejectedTool")
            .put("requestId", result.requestId)
            .put("summary", result.summary.shortTraceText())

        is AgentPlan.MissingModel -> json
            .put("type", "MissingModel")
            .put("capability", capability.name)
    }
    return json
}

private fun AgentObservationDecision.traceJson(): JSONObject {
    val json = JSONObject()
    when (this) {
        AgentObservationDecision.Complete -> json.put("type", "Complete")
        is AgentObservationDecision.ContinueWithModel -> json
            .put("type", "ContinueWithModel")
            .put("requiresLocalModel", requiresLocalModel)
            .put("reason", reason.shortTraceText())

        is AgentObservationDecision.RetryTool -> json
            .put("type", "RetryTool")
            .put("requestId", request.id)
            .put("toolName", request.toolName)
            .put("attempt", attempt)
            .put("reason", reason.shortTraceText())

        is AgentObservationDecision.PlanNextTool -> json
            .put("type", "PlanNextTool")
            .put("requestId", plan.request.id)
            .put("toolName", plan.request.toolName)
            .put("reason", reason.shortTraceText())

        is AgentObservationDecision.Fail -> json
            .put("type", "Fail")
            .put("reason", reason.shortTraceText())

        AgentObservationDecision.Cancel -> json.put("type", "Cancel")
    }
    return json
}

private fun String.shortTraceText(maxLength: Int = 160): String {
    val compact = lineSequence()
        .joinToString(" ") { line -> line.trim() }
        .replace(Regex("\\s+"), " ")
        .trim()
    val redacted = ToolAuditSummaryRedactor.redact(compact)
    return if (redacted.length <= maxLength) redacted else redacted.take(maxLength - 3) + "..."
}
