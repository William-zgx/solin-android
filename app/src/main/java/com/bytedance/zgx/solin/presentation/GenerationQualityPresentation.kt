package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.orchestration.ModelOutputQualityTrace
import com.bytedance.zgx.solin.orchestration.RunDataReceipt
import com.bytedance.zgx.solin.runtime.GenerationQualityDecision
import com.bytedance.zgx.solin.runtime.GenerationQualityReport

internal fun GenerationQualityDecision.reportOrNull(): GenerationQualityReport? =
    when (this) {
        GenerationQualityDecision.Continue -> null
        is GenerationQualityDecision.StopAndKeepPrefix -> report
        is GenerationQualityDecision.StopAndReplaceWithNotice -> report
        is GenerationQualityDecision.RetrySuggested -> report
        is GenerationQualityDecision.FailClosed -> report
    }

internal fun GenerationQualityDecision.actionName(): String =
    when (this) {
        GenerationQualityDecision.Continue -> "Continue"
        is GenerationQualityDecision.StopAndKeepPrefix -> "StopAndKeepPrefix"
        is GenerationQualityDecision.StopAndReplaceWithNotice -> "StopAndReplaceWithNotice"
        is GenerationQualityDecision.RetrySuggested -> "RetrySuggested"
        is GenerationQualityDecision.FailClosed -> "FailClosed"
    }

internal fun GenerationQualityReport.visibleAssistantText(): String =
    if (safePrefix.isBlank()) {
        visibleNotice
    } else {
        "$safePrefix\n\n（$visibleNotice）"
    }

internal fun GenerationQualityReport.toTrace(decision: GenerationQualityDecision): ModelOutputQualityTrace =
    ModelOutputQualityTrace(
        issue = issue.name,
        severity = severity.name,
        triggeredRule = triggeredRule,
        action = decision.actionName(),
        rawOutputLength = rawOutputLength,
        keptPrefix = safePrefix.isNotBlank(),
        modelId = modelId,
        backend = backend?.name,
        runtimeKind = runtimeKind.name,
    )

internal fun RunDataReceipt.withOutputQualityDecision(decision: GenerationQualityDecision): RunDataReceipt {
    val report = decision.reportOrNull() ?: return this
    return copy(
        outputQualityGuardTriggered = true,
        outputQualityIssue = report.issue.name,
        outputQualityRule = report.triggeredRule,
        outputQualityAction = decision.actionName(),
        outputQualityStopped = decision !is GenerationQualityDecision.Continue,
        outputQualityKeptPrefix = report.safePrefix.isNotBlank(),
    )
}
