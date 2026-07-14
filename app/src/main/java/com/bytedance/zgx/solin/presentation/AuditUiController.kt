package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AgentTraceRunUiSummary
import com.bytedance.zgx.solin.AgentTraceStepUiSummary
import com.bytedance.zgx.solin.AuditEventSummary
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.RunDataReceiptUiSummary
import com.bytedance.zgx.solin.audit.ToolAuditLog
import com.bytedance.zgx.solin.orchestration.AgentTraceRunSummary
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns audit-event and agent-trace list loading for Trust / background UI surfaces.
 *
 * Extracted from [com.bytedance.zgx.solin.SolinViewModel] (Wave 6 Track C6b). Constructed before
 * `_uiState` so [createInitialState] can load audit/trace snapshots; [bindUiState] is called once
 * the ViewModel state flow exists.
 */
class AuditUiController(
    private val toolAuditLog: ToolAuditLog,
    private val assistantOrchestrator: AssistantRouter,
) {
    private var uiState: MutableStateFlow<ChatUiState>? = null

    fun bindUiState(uiState: MutableStateFlow<ChatUiState>) {
        this.uiState = uiState
    }

    fun refreshAuditEvents() {
        val state = uiState ?: return
        state.update {
            it.copy(
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
            )
        }
    }

    fun loadAuditEvents(): List<AuditEventSummary> =
        toolAuditLog.recentAuditEvents().map { event ->
            AuditEventSummary(
                id = event.id,
                toolName = event.toolName,
                eventType = event.eventType,
                status = event.status,
                riskLevel = event.riskLevel,
                permissions = event.permissions,
                summary = event.summary,
                createdAtMillis = event.createdAtMillis,
            )
        }

    fun loadAgentTraceRuns(): List<AgentTraceRunUiSummary> =
        runCatching {
            assistantOrchestrator.recentTraceRuns(limit = 5, stepLimit = 8)
                .map { run -> run.toUiSummary() }
        }.getOrDefault(emptyList())

    private fun AgentTraceRunSummary.toUiSummary(): AgentTraceRunUiSummary =
        AgentTraceRunUiSummary(
            id = run.id,
            state = run.state,
            updatedAtMillis = run.updatedAtMillis,
            steps = steps.map { step ->
                AgentTraceStepUiSummary(
                    type = step.type,
                    summary = step.summary,
                    createdAtMillis = step.createdAtMillis,
                    runDataReceipt = step.runDataReceiptUiSummaryOrNull(),
                )
            },
            runDataReceipt = runDataReceiptStep?.runDataReceiptUiSummaryOrNull()
                ?: steps.lastOrNull { step -> step.type == "RunDataReceiptRecorded" }?.runDataReceiptUiSummaryOrNull(),
        )

    private fun com.bytedance.zgx.solin.orchestration.AgentTraceStepSummary.runDataReceiptUiSummaryOrNull():
        RunDataReceiptUiSummary? {
        if (type != "RunDataReceiptRecorded") return null
        val json = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return RunDataReceiptUiSummary(
            destination = json.optString("destination"),
            currentPromptPrivacy = json.optString("currentPromptPrivacy"),
            remoteHistoryCount = json.optInt("remoteHistoryCount"),
            localOnlyHistoryFilteredCount = json.optInt("localOnlyHistoryFilteredCount"),
            memoryHitCount = json.optInt("memoryHitCount"),
            semanticMemoryHitCount = json.optInt("semanticMemoryHitCount"),
            lexicalMemoryHitCount = json.optInt("lexicalMemoryHitCount"),
            memoryContextIncluded = json.optBoolean("memoryContextIncluded"),
            deviceContextIncluded = json.optBoolean("deviceContextIncluded"),
            imageAttachmentCount = json.optInt("imageAttachmentCount"),
            protectedSourceCount = json.optInt("protectedSourceCount"),
            evidenceCardCount = json.optInt("evidenceCardCount"),
            localOnlyEvidenceCardCount = json.optInt("localOnlyEvidenceCardCount"),
            truncatedEvidenceCardCount = json.optInt("truncatedEvidenceCardCount"),
            lowQualityEvidenceCardCount = json.optInt("lowQualityEvidenceCardCount"),
            evidenceSourceTypes = json.optJSONArray("evidenceSourceTypes").toStringList(),
            rawContentPersisted = json.optBoolean("rawContentPersisted"),
            protectedContentTypes = json.optJSONArray("protectedContentTypes").toStringList(),
            deletableRecordTypes = json.optJSONArray("deletableRecordTypes").toStringList(),
            outputQualityGuardTriggered = json.optBoolean("outputQualityGuardTriggered"),
            outputQualityIssue = json.optString("outputQualityIssue").takeIf { value -> value.isNotBlank() },
            outputQualityRule = json.optString("outputQualityRule").takeIf { value -> value.isNotBlank() },
            outputQualityAction = json.optString("outputQualityAction").takeIf { value -> value.isNotBlank() },
            outputQualityStopped = json.optBoolean("outputQualityStopped"),
            outputQualityKeptPrefix = json.optBoolean("outputQualityKeptPrefix"),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val value = optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }
}
