package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.evidence.EvidenceCard
import com.bytedance.zgx.solin.evidence.EvidenceSourceType

/**
 * Implement to contribute per-turn evidence to the system prompt (e.g., memory hits, device context snapshot).
 */
interface SystemContextContributor {
    val sourceType: EvidenceSourceType

    suspend fun contribute(userInput: String, run: AgentRun): EvidenceCard?
}
