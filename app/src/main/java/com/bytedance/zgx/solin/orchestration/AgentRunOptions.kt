package com.bytedance.zgx.solin.orchestration

data class AgentRunOptions(
    val initialPlanningMode: InitialPlanningMode = InitialPlanningMode.RuleFirst,
    val remoteToolScope: RemoteToolScope = RemoteToolScope.PublicEvidenceOnly,
    val reduceDeviceActionConfirmations: Boolean = false,
    val profile: AgentProfile = AgentProfile.DEFAULT,
)

enum class InitialPlanningMode {
    RuleFirst,
    ModelFirstRemoteTools,
}

enum class RemoteToolScope {
    PublicEvidenceOnly,
    ModelPlanning,
}
