package com.bytedance.zgx.solin.orchestration

/**
 * Profile that governs agent runtime behavior such as parallel tool use
 * and step budget. Each run binds to exactly one profile.
 */
sealed class AgentProfile {
    abstract val profileId: String
    abstract val allowParallelTools: Boolean
    abstract val maxStepsPerTurn: Int

    /**
     * Returns `true` when this profile represents a sub-agent invocation
     * (nested under a parent run).
     */
    fun isSubagent(): Boolean = this is Subagent

    /**
     * Returns the effective maximum number of tool-call steps allowed per
     * turn for this profile.
     */
    fun effectiveMaxToolSteps(): Int = maxStepsPerTurn

    data class General(
        override val profileId: String = "general",
        override val allowParallelTools: Boolean = true,
        override val maxStepsPerTurn: Int = 15,
    ) : AgentProfile()

    data class Build(
        override val profileId: String = "build",
        override val allowParallelTools: Boolean = false,
        override val maxStepsPerTurn: Int = 30,
    ) : AgentProfile()

    data class Plan(
        override val profileId: String = "plan",
        override val allowParallelTools: Boolean = false,
        override val maxStepsPerTurn: Int = 3,
    ) : AgentProfile()

    data class Subagent(
        val parentRunId: String,
        val parentToolCallId: String,
        val depth: Int = 1,
        override val profileId: String = "subagent",
        override val allowParallelTools: Boolean = false,
        override val maxStepsPerTurn: Int = 10,
    ) : AgentProfile()

    companion object {
        /** Default profile used when no explicit profile is specified. */
        val DEFAULT: AgentProfile = General()

        /**
         * Maximum allowed nesting depth for sub-agents. Runs at this depth
         * must not spawn further sub-agents.
         */
        const val MAX_SUBAGENT_DEPTH: Int = 3
    }
}
