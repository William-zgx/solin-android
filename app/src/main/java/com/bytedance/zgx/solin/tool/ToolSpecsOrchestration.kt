package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.action.MobileActionFunctions

/**
 * Built-in specs for the tail of the registry: reminder cancellation followed by the
 * agent orchestration tools (ask_user, note, finish, take_over).
 *
 * Moved verbatim out of ToolRegistry.kt. [builtInToolSpecs] concatenates the chunks in the
 * original order — that order feeds planning prompts and the capability matrix, and is
 * pinned by BuiltInToolSpecSnapshotTest. Keep each chunk contiguous and in place.
 */
internal val orchestrationToolSpecs: List<ToolSpec> = listOf(
    ToolSpec(
        name = MobileActionFunctions.CANCEL_REMINDER,
        title = "取消提醒",
        description = "在已安排的提醒列表中取消指定提醒任务，不再触发该提醒。",
        inputSchemaJson = cancelReminderSchemaJson,
        outputSchemaJson = cancelReminderOutputSchemaJson,
        capability = ToolCapability.BackgroundTask,
        permissions = setOf(ToolPermission.SchedulesBackgroundWork),
        riskLevel = RiskLevel.MediumDraftOrNavigation,
        confirmationPolicy = ConfirmationPolicy.Required,
        pendingArgumentAllowlist = setOf("taskId"),
    ),
    ToolSpec(
        name = MobileActionFunctions.ASK_USER,
        title = "询问用户",
        description = "当需要额外信息才能继续处理用户请求时，暂停执行并向用户提出澄清问题；可提供可选的简短选项标签供用户点选回答，省略则等待用户自由文本回复。请克制使用——仅在请求确实存在歧义且无法通过已有上下文解决时调用。",
        inputSchemaJson = askUserInputSchemaJson,
        outputSchemaJson = askUserOutputSchemaJson,
        capability = ToolCapability.DeviceContext,
        permissions = emptySet(),
        riskLevel = RiskLevel.LowReadOnly,
        confirmationPolicy = ConfirmationPolicy.NotRequired,
        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
        executionMode = ToolExecutionMode.Sequential,
        tags = setOf(ToolCapabilityTag.UxInteraction),
    ),
    // ── Open-AutoGLM-inspired expanded action vocabulary ──
    ToolSpec(
        name = MobileActionFunctions.NOTE,
        title = "记录笔记",
        description = "向本次运行的暂存笔记中添加一条记录，供后续步骤参考。不会触发任何外部操作，不离开当前页面。对记录搜索结果、页面标题、验证证据等场景特别有用。",
        inputSchemaJson = noteInputSchemaJson,
        outputSchemaJson = noteOutputSchemaJson,
        capability = ToolCapability.Orchestration,
        permissions = emptySet(),
        riskLevel = RiskLevel.LowReadOnly,
        confirmationPolicy = ConfirmationPolicy.NotRequired,
        // Note content can be derived from screen text / OCR / other private local evidence,
        // so it is a private LocalOnly output that only a local model may continue from.
        privateOutputKeys = setOf("content"),
        redactedResultSummary = "已记录笔记",
        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
        tags = setOf(ToolCapabilityTag.Planning),
    ),
    ToolSpec(
        name = MobileActionFunctions.FINISH,
        title = "完成任务",
        description = "显式结束本次运行并附上完成总结。当用户的请求已完成时使用此动作，不要只是停止操作。",
        inputSchemaJson = finishInputSchemaJson,
        outputSchemaJson = finishOutputSchemaJson,
        capability = ToolCapability.Orchestration,
        permissions = emptySet(),
        riskLevel = RiskLevel.LowReadOnly,
        confirmationPolicy = ConfirmationPolicy.NotRequired,
        tags = setOf(ToolCapabilityTag.Planning),
    ),
    ToolSpec(
        name = MobileActionFunctions.TAKE_OVER,
        title = "人工接管",
        description = "将控制权交还给用户处理。当遇到登录页面、验证码、密码输入框或任何 Agent 无法自主处理的界面时使用此动作。用户完成操作后可继续本次运行。",
        inputSchemaJson = takeOverInputSchemaJson,
        outputSchemaJson = takeOverOutputSchemaJson,
        capability = ToolCapability.Orchestration,
        permissions = emptySet(),
        riskLevel = RiskLevel.LowReadOnly,
        confirmationPolicy = ConfirmationPolicy.NotRequired,
        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
        tags = setOf(ToolCapabilityTag.UxInteraction, ToolCapabilityTag.Planning),
    ),
)
