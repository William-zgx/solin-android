package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class SkillRunCheckpointPhase {
    AwaitingToolConfirmation,
}

data class SkillRunCheckpoint(
    val schemaVersion: Int = SCHEMA_VERSION,
    val runId: String,
    val skillId: String,
    val skillRequestId: String,
    val manifestId: String,
    val manifestVersion: Int,
    val manifestHash: String,
    val phase: SkillRunCheckpointPhase,
    val pendingStepIndex: Int,
    val pendingStepId: String,
    val pendingRequestId: String,
    val pendingToolName: String,
    val completedStepIds: List<String>,
    val outputKeysByStep: Map<String, List<String>>,
    val privateOutputRefs: Set<String>,
) {
    fun validationErrorFor(
        plan: SkillPlan,
        toolRegistry: ToolRegistry = ToolRegistry(),
    ): String? {
        if (schemaVersion != SCHEMA_VERSION) return "unsupported skill checkpoint version"
        if (runId.isNotSafeCheckpointToken()) return "unsafe skill checkpoint run id"
        if (!plan.validateStructure(toolRegistry).isValid) return "skill checkpoint plan is invalid"
        if (skillId != plan.request.skillId || manifestId != plan.manifest.id) {
            return "skill checkpoint does not match skill id"
        }
        if (skillRequestId != plan.request.id) return "skill checkpoint does not match skill request"
        if (manifestVersion != plan.manifest.version) return "skill checkpoint manifest version changed"
        if (manifestHash != plan.manifest.checkpointHash()) return "skill checkpoint manifest changed"
        if (phase != SkillRunCheckpointPhase.AwaitingToolConfirmation) {
            return "unsupported skill checkpoint phase"
        }
        val pendingStep = plan.steps.getOrNull(pendingStepIndex) as? SkillStep.ToolStep
            ?: return "skill checkpoint pending step is missing"
        if (pendingStep.id != pendingStepId) return "skill checkpoint pending step changed"
        if (pendingStep.request.id != pendingRequestId) return "skill checkpoint pending request changed"
        if (pendingStep.request.toolName != pendingToolName) return "skill checkpoint pending tool changed"
        val expectedCompletedStepIds = plan.steps
            .take(pendingStepIndex)
            .map { step -> step.id }
        if (completedStepIds != expectedCompletedStepIds) {
            return "skill checkpoint completed steps changed"
        }
        if (completedStepIds.any { stepId -> stepId.isNotSafeCheckpointToken() }) {
            return "skill checkpoint completed step id is unsafe"
        }
        val completedStepIdSet = expectedCompletedStepIds.toSet()
        if (outputKeysByStep.keys != completedStepIdSet) {
            return "skill checkpoint output frontier changed"
        }
        val expectedOutputKeysByStep = plan.steps
            .take(pendingStepIndex)
            .associate { step -> step.id to step.knownValueFreeOutputKeys(toolRegistry) }
        outputKeysByStep.forEach { (stepId, keys) ->
            if (stepId !in completedStepIdSet) return "skill checkpoint output keys reference incomplete step"
            if (keys.isEmpty()) return "skill checkpoint output keys are missing"
            if (keys.any { key -> key.isNotSafeCheckpointToken() }) {
                return "skill checkpoint output key is unsafe"
            }
            if (keys != keys.distinct().sorted()) return "skill checkpoint output keys are not canonical"
        }
        if (outputKeysByStep != expectedOutputKeysByStep) {
            return "skill checkpoint output keys changed"
        }
        privateOutputRefs.forEach { ref ->
            val stepId = ref.substringBefore('.', missingDelimiterValue = "")
            val key = ref.substringAfter('.', missingDelimiterValue = "")
            if (stepId !in completedStepIdSet || key.isBlank()) {
                return "skill checkpoint private output ref is invalid"
            }
            if (stepId.isNotSafeCheckpointToken() || key.isNotSafeCheckpointToken()) {
                return "skill checkpoint private output ref is unsafe"
            }
        }
        val expectedPrivateOutputRefs = plan.steps
            .take(pendingStepIndex)
            .filterIsInstance<SkillStep.ToolStep>()
            .flatMapTo(mutableSetOf()) { step ->
                toolRegistry.privateOutputKeysFor(step.request.toolName)
                    .map { key -> "${step.id}.$key" }
            }
        if (privateOutputRefs != expectedPrivateOutputRefs) {
            return "skill checkpoint private output refs changed"
        }
        val unpersistableBindingTargets = pendingStep.unpersistablePendingBindingTargets(toolRegistry)
        if (unpersistableBindingTargets.isNotEmpty()) {
            return "skill checkpoint pending payload cannot be restored: " +
                "${pendingStep.request.toolName} binding target(s) " +
                "${unpersistableBindingTargets.joinToString()} are not pending-allowlisted"
        }
        return listOf(
            runId,
            skillId,
            skillRequestId,
            manifestId,
            pendingStepId,
            pendingRequestId,
            pendingToolName,
        ).firstOrNull { token -> token.isNotSafeCheckpointToken() }
            ?.let { "skill checkpoint contains unsafe structural token" }
    }

    fun toJsonObject(): JSONObject =
        JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("runId", runId)
            .put("skillId", skillId)
            .put("skillRequestId", skillRequestId)
            .put("manifestId", manifestId)
            .put("manifestVersion", manifestVersion)
            .put("manifestHash", manifestHash)
            .put("phase", phase.name)
            .put("pendingStepIndex", pendingStepIndex)
            .put("pendingStepId", pendingStepId)
            .put("pendingRequestId", pendingRequestId)
            .put("pendingToolName", pendingToolName)
            .put("completedStepIds", completedStepIds.toJsonArray())
            .put("outputKeysByStep", outputKeysByStep.toStringListMapJsonObject())
            .put("privateOutputRefs", privateOutputRefs.sorted().toJsonArray())

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

fun SkillRunContinuation.toValueFreeCheckpoint(
    runId: String,
    plan: SkillPlan,
    toolRegistry: ToolRegistry = ToolRegistry(),
): SkillRunCheckpoint =
    SkillRunCheckpoint(
        runId = runId,
        skillId = skillId,
        skillRequestId = planRequestId,
        manifestId = plan.manifest.id,
        manifestVersion = plan.manifest.version,
        manifestHash = plan.manifest.checkpointHash(),
        phase = SkillRunCheckpointPhase.AwaitingToolConfirmation,
        pendingStepIndex = pendingStepIndex,
        pendingStepId = pendingStepId,
        pendingRequestId = pendingToolRequest.id,
        pendingToolName = pendingToolRequest.toolName,
        completedStepIds = plan.steps.take(pendingStepIndex).map { step -> step.id },
        outputKeysByStep = plan.steps
            .take(pendingStepIndex)
            .associate { step -> step.id to step.knownValueFreeOutputKeys(toolRegistry) },
        privateOutputRefs = privateOutputRefs.toSet(),
    )

fun SkillPlan.valueFreeCheckpointForPendingTool(
    runId: String,
    pendingRequest: ToolRequest,
    toolRegistry: ToolRegistry = ToolRegistry(),
): SkillRunCheckpoint? {
    val pendingStepIndex = steps.indexOfFirst { step ->
        step is SkillStep.ToolStep &&
            step.request.id == pendingRequest.id &&
            step.request.toolName == pendingRequest.toolName
    }
    if (pendingStepIndex < 0) return null
    val completedSteps = steps.take(pendingStepIndex)
    return SkillRunCheckpoint(
        runId = runId,
        skillId = request.skillId,
        skillRequestId = request.id,
        manifestId = manifest.id,
        manifestVersion = manifest.version,
        manifestHash = manifest.checkpointHash(),
        phase = SkillRunCheckpointPhase.AwaitingToolConfirmation,
        pendingStepIndex = pendingStepIndex,
        pendingStepId = steps[pendingStepIndex].id,
        pendingRequestId = pendingRequest.id,
        pendingToolName = pendingRequest.toolName,
        completedStepIds = completedSteps.map { step -> step.id },
        outputKeysByStep = completedSteps.associate { step ->
            step.id to step.knownValueFreeOutputKeys(toolRegistry)
        },
        privateOutputRefs = completedSteps
            .filterIsInstance<SkillStep.ToolStep>()
            .flatMapTo(mutableSetOf()) { step ->
                toolRegistry.privateOutputKeysFor(step.request.toolName)
                    .map { key -> "${step.id}.$key" }
            },
    )
}

fun skillRunCheckpointFromJson(json: String): SkillRunCheckpoint {
    val checkpointJson = JSONObject(json)
    checkpointJson.requireOnlyKeys(CHECKPOINT_JSON_KEYS, "skill checkpoint")
    return SkillRunCheckpoint(
        schemaVersion = checkpointJson.getInt("schemaVersion"),
        runId = checkpointJson.getString("runId"),
        skillId = checkpointJson.getString("skillId"),
        skillRequestId = checkpointJson.getString("skillRequestId"),
        manifestId = checkpointJson.getString("manifestId"),
        manifestVersion = checkpointJson.getInt("manifestVersion"),
        manifestHash = checkpointJson.getString("manifestHash"),
        phase = SkillRunCheckpointPhase.valueOf(checkpointJson.getString("phase")),
        pendingStepIndex = checkpointJson.getInt("pendingStepIndex"),
        pendingStepId = checkpointJson.getString("pendingStepId"),
        pendingRequestId = checkpointJson.getString("pendingRequestId"),
        pendingToolName = checkpointJson.getString("pendingToolName"),
        completedStepIds = checkpointJson.getJSONArray("completedStepIds").toStringList(),
        outputKeysByStep = checkpointJson.getJSONObject("outputKeysByStep").toStringListMap(),
        privateOutputRefs = checkpointJson.getJSONArray("privateOutputRefs").toStringList().toSet(),
    )
}

fun SkillManifest.checkpointHash(): String {
    val identity = buildString {
        appendLengthPrefixed(id)
        appendLengthPrefixed(version.toString())
        appendLengthPrefixed(title)
        appendLengthPrefixed(description)
        triggerExamples.forEach(::appendLengthPrefixed)
        requiredTools.forEach(::appendLengthPrefixed)
        appendLengthPrefixed(inputSchemaJson)
        appendLengthPrefixed(riskLevel.name)
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun SkillStep.knownValueFreeOutputKeys(toolRegistry: ToolRegistry): List<String> =
    when (this) {
        is SkillStep.ModelStep -> listOf(outputKey)
        is SkillStep.ToolStep -> (
            listOf("summary") +
                request.arguments.keys +
                draft.parameters.keys +
                toolRegistry.privateOutputKeysFor(request.toolName)
            )
            .filter { key -> key.isNotBlank() }
            .distinct()
            .sorted()
    }

private fun SkillStep.ToolStep.unpersistablePendingBindingTargets(toolRegistry: ToolRegistry): List<String> {
    if (argumentBindings.isEmpty()) return emptyList()
    val persistableTargets = toolRegistry.pendingArgumentAllowlistFor(request.toolName)
    return argumentBindings.keys
        .filter { target -> target !in persistableTargets }
        .sorted()
}

private fun StringBuilder.appendLengthPrefixed(value: String) {
    append(value.length)
    append(':')
    append(value)
    append(';')
}

private fun Map<String, List<String>>.toStringListMapJsonObject(): JSONObject =
    JSONObject().also { json ->
        entries.sortedBy { it.key }.forEach { (key, values) ->
            json.put(key, values.distinct().sorted().toJsonArray())
        }
    }

private fun List<String>.toJsonArray(): JSONArray =
    JSONArray().also { array -> forEach { value -> array.put(value) } }

private fun JSONObject.toStringListMap(): Map<String, List<String>> =
    buildMap {
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, getJSONArray(key).toStringList())
        }
    }.toSortedMap()

private fun JSONArray.toStringList(): List<String> =
    buildList {
        for (index in 0 until length()) {
            add(getString(index))
        }
    }

private fun JSONObject.requireOnlyKeys(allowed: Set<String>, owner: String) {
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        require(key in allowed) { "$owner has unsupported key: $key" }
    }
}

private fun String.isNotSafeCheckpointToken(): Boolean =
    !SAFE_CHECKPOINT_TOKEN.matches(this)

private val CHECKPOINT_JSON_KEYS = setOf(
    "schemaVersion",
    "runId",
    "skillId",
    "skillRequestId",
    "manifestId",
    "manifestVersion",
    "manifestHash",
    "phase",
    "pendingStepIndex",
    "pendingStepId",
    "pendingRequestId",
    "pendingToolName",
    "completedStepIds",
    "outputKeysByStep",
    "privateOutputRefs",
)

private val SAFE_CHECKPOINT_TOKEN = Regex("""[A-Za-z0-9_.:-]{1,160}""")
