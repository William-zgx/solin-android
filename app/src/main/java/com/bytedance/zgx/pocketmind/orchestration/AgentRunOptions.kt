package com.bytedance.zgx.pocketmind.orchestration

data class AgentRunOptions(
    val initialPlanningMode: InitialPlanningMode = InitialPlanningMode.RuleFirst,
    val remoteToolScope: RemoteToolScope = RemoteToolScope.PublicEvidenceOnly,
    val reduceDeviceActionConfirmations: Boolean = false,
)

enum class InitialPlanningMode {
    RuleFirst,
    ModelFirstRemoteTools,
}

enum class RemoteToolScope {
    PublicEvidenceOnly,
    ModelPlanning,
}
