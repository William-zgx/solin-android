package com.bytedance.zgx.solin.plan

import com.bytedance.zgx.solin.module.ToolHandler
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolCapabilityTag
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.failed
import com.bytedance.zgx.solin.tool.succeeded
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class PlanToolHandler(
    private val store: SessionPlanStore,
) {
    fun toolSpecs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = PLAN_WRITE_TOOL,
            title = "写入计划",
            description = "Replace the current agent plan for a run with an ordered list of plan items. Meta/orchestration tool used by the agent itself.",
            inputSchemaJson = planWriteInputSchema,
            capability = ToolCapability.Orchestration,
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.NotRequired,
            tags = setOf(ToolCapabilityTag.Planning),
        ),
        ToolSpec(
            name = PLAN_READ_TOOL,
            title = "读取计划",
            description = "Read the current agent plan for a run, rendered as a numbered checklist. Meta/orchestration tool used by the agent itself.",
            inputSchemaJson = planReadInputSchema,
            capability = ToolCapability.Orchestration,
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.NotRequired,
            tags = setOf(ToolCapabilityTag.Planning),
        ),
    )

    fun handler(): ToolHandler = ToolHandler { request ->
        when (request.toolName) {
            PLAN_WRITE_TOOL -> handlePlanWrite(request)
            PLAN_READ_TOOL -> handlePlanRead(request)
            else -> null
        }
    }

    private fun handlePlanWrite(request: ToolRequest): ToolResult = runCatching {
        val runId = request.arguments["runId"]?.takeIf { it.isNotBlank() }
            ?: return@runCatching request.failed(
                ToolErrorCode.MissingArgument,
                "plan_write requires runId",
                retryable = false,
            )
        val itemsJsonRaw = request.arguments["itemsJson"]?.takeIf { it.isNotBlank() }
            ?: return@runCatching request.failed(
                ToolErrorCode.MissingArgument,
                "plan_write requires itemsJson (JSON array of plan items)",
                retryable = false,
            )
        val items = parseItems(runId, itemsJsonRaw)
        val snap = store.replaceAll(runId, items)
        val done = snap.doneCount()
        val pending = snap.pendingCount()
        request.succeeded(
            summary = "Plan updated (${snap.items.size} steps, $done done, $pending pending).",
            data = mapOf(
                "runId" to runId,
                "itemCount" to snap.items.size.toString(),
                "doneCount" to done.toString(),
                "pendingCount" to pending.toString(),
            ),
        )
    }.getOrElse { error ->
        request.failed(
            ToolErrorCode.InvalidRequest,
            "plan_write failed: ${error.message ?: error.javaClass.simpleName}",
            retryable = false,
        )
    }

    private fun handlePlanRead(request: ToolRequest): ToolResult = runCatching {
        val runId = request.arguments["runId"]?.takeIf { it.isNotBlank() }
            ?: return@runCatching request.failed(
                ToolErrorCode.MissingArgument,
                "plan_read requires runId",
                retryable = false,
            )
        val snap = store.get(runId)
        if (snap == null) {
            return@runCatching request.succeeded(
                summary = "No plan for run $runId.",
                data = mapOf("runId" to runId, "itemCount" to "0", "rendered" to ""),
            )
        }
        val rendered = snap.items.joinToString("\n") { item ->
            val marker = when (item.status) {
                PlanItemStatus.DONE -> "[D]"
                PlanItemStatus.IN_PROGRESS -> "[>]"
                PlanItemStatus.BLOCKED -> "[B]"
                PlanItemStatus.SKIPPED -> "[S]"
                PlanItemStatus.PENDING -> "[P]"
            }
            val pos = item.position + 1
            val note = item.note?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""
            "$pos. $marker ${item.title}$note"
        }
        request.succeeded(
            summary = "Plan for run $runId (${snap.items.size} steps, ${snap.doneCount()} done, ${snap.pendingCount()} pending).",
            data = mapOf(
                "runId" to runId,
                "itemCount" to snap.items.size.toString(),
                "doneCount" to snap.doneCount().toString(),
                "pendingCount" to snap.pendingCount().toString(),
                "rendered" to rendered,
            ),
        )
    }.getOrElse { error ->
        request.failed(
            ToolErrorCode.InvalidRequest,
            "plan_read failed: ${error.message ?: error.javaClass.simpleName}",
            retryable = false,
        )
    }

    private fun parseItems(runId: String, itemsJsonRaw: String): List<PlanItem> {
        val array = JSONArray(itemsJsonRaw.trim())
        return (0 until array.length()).map { idx ->
            val obj = array.getJSONObject(idx)
            val id = if (obj.has("planItemId") && !obj.isNull("planItemId")) {
                obj.getString("planItemId").takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()
            } else {
                UUID.randomUUID().toString()
            }
            val position = if (obj.has("position") && !obj.isNull("position")) {
                obj.getInt("position")
            } else {
                idx
            }
            val title = if (obj.has("title") && !obj.isNull("title")) {
                obj.getString("title").takeIf { it.isNotBlank() }
                    ?: error("item at index $idx is missing title")
            } else {
                error("item at index $idx is missing title")
            }
            val statusRaw = if (obj.has("status") && !obj.isNull("status")) {
                obj.getString("status").takeIf { it.isNotBlank() }
            } else {
                null
            }
            val status = if (statusRaw != null) {
                runCatching { PlanItemStatus.valueOf(statusRaw) }
                    .getOrElse { PlanItemStatus.PENDING }
            } else {
                PlanItemStatus.PENDING
            }
            val note = if (obj.has("note") && !obj.isNull("note")) {
                obj.getString("note").takeIf { it.isNotBlank() }
            } else {
                null
            }
            PlanItem(
                planItemId = id,
                runId = runId,
                position = position,
                title = title,
                status = status,
                note = note,
            )
        }
    }

    companion object {
        const val PLAN_WRITE_TOOL = "plan_write"
        const val PLAN_READ_TOOL = "plan_read"
    }
}

private val planWriteInputSchema = """
    {
      "type": "object",
      "required": ["runId", "itemsJson"],
      "properties": {
        "runId": {
          "type": "string",
          "minLength": 1,
          "description": "Agent run id the plan belongs to."
        },
        "itemsJson": {
          "type": "string",
          "minLength": 1,
          "description": "JSON array of plan item objects: {planItemId?, position?, title, status?, note?}."
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val planReadInputSchema = """
    {
      "type": "object",
      "required": ["runId"],
      "properties": {
        "runId": {
          "type": "string",
          "minLength": 1,
          "description": "Agent run id whose plan to read."
        }
      },
      "additionalProperties": false
    }
""".trimIndent()
