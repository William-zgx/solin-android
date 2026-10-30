package com.bytedance.zgx.solin.tool

/*
 * Shared private-output key sets and capability tag sets referenced by the built-in specs
 * (ToolSpecs*.kt); `internal` only so the spec files
 * can reference them.
 */

internal val recentImageOcrPrivateOutputKeys = setOf("ocrText")

internal val observeCurrentScreenPrivateOutputKeys = setOf(
    "observationId",
    "packageName",
    "capturedAtMillis",
    "nodeCount",
    "actionableNodeCount",
    "textSummary",
    "nodesJson",
    "screenObservationJson",
    "screenWidthPx",
    "screenHeightPx",
    "screenshotPerception",
)

internal val uiActionPrivateOutputKeys = setOf(
    "target",
    "summary",
    "beforeObservationId",
    "afterObservationId",
    "verificationSummary",
    "screenObservationDiffSummary",
    "searchVerificationStatus",
    "searchVerificationEvidence",
    "uiActionOutcome",
    "uiActionOutcomeReason",
    "appSearchProgressStage",
    "beforePackageName",
    "beforeCapturedAtMillis",
    "beforeNodeCount",
    "beforeActionableNodeCount",
    "beforeTextSummary",
    "beforeTruncated",
    "beforeNodesJson",
    "beforeScreenObservationJson",
    "afterPackageName",
    "afterCapturedAtMillis",
    "afterNodeCount",
    "afterActionableNodeCount",
    "afterTextSummary",
    "afterTruncated",
    "afterNodesJson",
    "afterScreenObservationJson",
    "screenWidthPx",
    "screenHeightPx",
    "beforeScreenWidthPx",
    "beforeScreenHeightPx",
    "beforeScreenshotPerception",
    "afterScreenWidthPx",
    "afterScreenHeightPx",
    "afterScreenshotPerception",
)

internal val deviceControlSessionTags = setOf(
    ToolCapabilityTag.DeviceControlSession,
)

internal val lowRiskDeviceControlSessionTags = setOf(
    ToolCapabilityTag.DeviceControlSession,
    ToolCapabilityTag.LowRiskDeviceAction,
    ToolCapabilityTag.RestoredExternalOutcomePopupSkippable,
)

internal val restoredPopupDeviceControlSessionTags = setOf(
    ToolCapabilityTag.DeviceControlSession,
    ToolCapabilityTag.RestoredExternalOutcomePopupSkippable,
)

internal val conditionalLowRiskDeviceControlSessionTags = setOf(
    ToolCapabilityTag.DeviceControlSession,
    ToolCapabilityTag.ConditionalLowRiskDeviceAction,
    ToolCapabilityTag.ConditionalRestoredExternalOutcomePopupSkippable,
)

internal val sequentialLocalContinuationTags = setOf(
    ToolCapabilityTag.SequentialLocalContinuation,
)

internal val backgroundSkillAllowedTags = setOf(
    ToolCapabilityTag.BackgroundSkillAllowed,
)

internal val backgroundSequentialLocalContinuationTags = setOf(
    ToolCapabilityTag.BackgroundSkillAllowed,
    ToolCapabilityTag.SequentialLocalContinuation,
)

internal val usageStatsSpecialAccessTags = setOf(
    ToolCapabilityTag.UsageStatsSpecialAccess,
)

internal val accessibilityScreenTextSequentialTags = setOf(
    ToolCapabilityTag.SequentialLocalContinuation,
    ToolCapabilityTag.AccessibilityScreenTextSpecialAccess,
)

internal val lowRiskSequentialContinuationTags = setOf(
    ToolCapabilityTag.SequentialLocalContinuation,
    ToolCapabilityTag.LowRiskDeviceAction,
    ToolCapabilityTag.LowRiskAppControlContinuation,
    ToolCapabilityTag.AccessibilityDeviceControlSpecialAccess,
)

internal val conditionalLowRiskSequentialContinuationTags = setOf(
    ToolCapabilityTag.SequentialLocalContinuation,
    ToolCapabilityTag.ConditionalLowRiskDeviceAction,
    ToolCapabilityTag.LowRiskAppControlContinuation,
    ToolCapabilityTag.CheckpointedUiAction,
    ToolCapabilityTag.AccessibilityDeviceControlSpecialAccess,
)

internal val checkpointedLowRiskSequentialContinuationTags = setOf(
    ToolCapabilityTag.SequentialLocalContinuation,
    ToolCapabilityTag.LowRiskDeviceAction,
    ToolCapabilityTag.LowRiskAppControlContinuation,
    ToolCapabilityTag.CheckpointedUiAction,
    ToolCapabilityTag.AccessibilityDeviceControlSpecialAccess,
)

internal val checkpointedLowRiskContinuationTags = setOf(
    ToolCapabilityTag.LowRiskDeviceAction,
    ToolCapabilityTag.LowRiskAppControlContinuation,
    ToolCapabilityTag.CheckpointedUiAction,
    ToolCapabilityTag.AccessibilityDeviceControlSpecialAccess,
)
