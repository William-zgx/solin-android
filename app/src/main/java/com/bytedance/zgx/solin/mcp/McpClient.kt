package com.bytedance.zgx.solin.mcp

import com.bytedance.zgx.solin.audit.RemoteSendAuditEvent
import com.bytedance.zgx.solin.audit.RemoteSendAuditSink
import com.bytedance.zgx.solin.audit.RemoteSendDecision
import com.bytedance.zgx.solin.tool.ToolError
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolHandler
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolStatus
import kotlinx.coroutines.CancellationException

/**
 * Exposes user-approved MCP tools as Solin tools and proxies confirmed calls.
 *
 * Every call is fail-closed: it is refused unless the Trust Center MCP toggle is ON (which
 * itself requires the acknowledged "data leaves the device" disclosure), the tool is in the
 * persisted approval catalog, the server passes peer verification, the live tool schema still
 * matches the approved snapshot, and the call can be audited. Each attempted send is recorded in
 * the remote-send (egress) audit with tool/server names and an argument COUNT only — never
 * argument values or the user's prompt.
 *
 * Open for testing so tests can construct a fake client without wiring a full transport stack.
 */
open class McpClient(
    val registry: McpServerRegistry,
    private val settings: McpCapabilitySettings = InMemoryMcpCapabilitySettings(),
    private val auditSink: RemoteSendAuditSink? = null,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    /** Builds startup specs and handlers from one enabled catalog snapshot. */
    internal open fun registrationBindings(): List<McpToolBinding> {
        if (!settings.isEnabled()) return emptyList()
        return catalogBindings()
    }

    private suspend fun call(request: ToolRequest, serverId: String, mcpToolName: String): ToolResult {
        // Re-read the catalog at call time: revocations take effect immediately.
        val entry = registry.catalogEntry(serverId)
        val approved = entry?.approvedTools?.firstOrNull { it.name == mcpToolName }
        if (entry == null || approved == null) {
            return request.mcpRejected(
                McpServerEntry(serverId = serverId, displayName = serverId),
                "MCP 工具未获批准，未发送"
            )
        }
        if (!settings.isEnabled()) {
            audit(entry, mcpToolName, request, RemoteSendDecision.Blocked, "MCP 已在信任中心关闭")
            return request.mcpRejected(entry, "MCP 扩展已在信任中心关闭，未发送")
        }
        val sink = auditSink ?: return request.mcpRejected(entry, "MCP 调用审计不可用，未发送")
        return try {
            val connection = registry.connectionFor(serverId)
            if (connection == null) {
                audit(entry, mcpToolName, request, RemoteSendDecision.Blocked, "MCP 服务未通过校验", sink)
                return request.mcpRejected(entry, "MCP 服务未通过签名校验或不可用，未发送")
            }
            connection.ensureInitialized()
            val live = connection.tools().firstOrNull { it.name == mcpToolName }
            val liveSchema = live?.let { runCatching { canonicalJson(it.inputSchema) }.getOrNull() }
            if (liveSchema == null || liveSchema != approved.inputSchemaJson) {
                audit(entry, mcpToolName, request, RemoteSendDecision.Blocked, "MCP 工具定义已变化", sink)
                return request.mcpRejected(entry, "MCP 工具定义已变化或已下线，需要重新批准，未发送")
            }
            val arguments = McpToolSpecs.typedArguments(approved.inputSchemaJson, request.arguments)
            // Record before sending so a send that crashes or times out is still audited.
            audit(entry, mcpToolName, request, RemoteSendDecision.Confirmed, "MCP 工具调用", sink)
            when (val outcome = connection.callTool(mcpToolName, arguments)) {
                is McpCallOutcome.Result -> McpToolSpecs.toToolResult(request, entry, outcome.result)
                is McpCallOutcome.ProtocolError -> request.mcpFailed(entry, "MCP 工具返回协议错误 ${outcome.code}")
                McpCallOutcome.NotApproved -> request.mcpRejected(entry, "MCP 工具未获批准，未发送")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            request.mcpFailed(entry, "MCP 工具调用失败：${throwable.javaClass.simpleName}")
        }
    }

    private fun audit(
        entry: McpServerEntry,
        mcpToolName: String,
        request: ToolRequest,
        decision: RemoteSendDecision,
        label: String,
        sink: RemoteSendAuditSink? = auditSink
    ) {
        sink?.record(
            RemoteSendAuditEvent(
                decision = decision,
                modelName = "MCP:${entry.displayName}",
                summary = "$label：$mcpToolName，参数 ${request.arguments.size} 项（不记录参数内容）",
                createdAtMillis = clockMillis()
            )
        )
    }

    private fun catalogBindings(): List<McpToolBinding> = registry.catalog()
        .flatMap { entry ->
            entry.approvedTools.mapNotNull { tool ->
                McpToolSpecs.toToolSpecOrNull(entry, tool)?.let { spec ->
                    McpToolBinding(
                        spec = spec,
                        handler = ToolHandler { request -> call(request, entry.serverId, tool.name) }
                    )
                }
            }
        }
        .distinctBy { it.spec.name }
}

internal data class McpToolBinding(
    val spec: ToolSpec,
    val handler: ToolHandler
)

private fun ToolRequest.mcpRejected(entry: McpServerEntry, summary: String): ToolResult = ToolResult(
    requestId = id,
    status = ToolStatus.Rejected,
    summary = summary,
    data = McpToolSpecs.privacyData(this, entry),
    error = ToolError(ToolErrorCode.PermissionDenied, summary),
    retryable = false
)

private fun ToolRequest.mcpFailed(entry: McpServerEntry, summary: String): ToolResult = ToolResult(
    requestId = id,
    status = ToolStatus.Failed,
    summary = summary,
    data = McpToolSpecs.privacyData(this, entry),
    error = ToolError(ToolErrorCode.ExecutionFailed, summary),
    retryable = true
)
