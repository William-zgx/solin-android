package com.bytedance.zgx.solin.orchestration

data class AgentRunOptions(
    val initialPlanningMode: InitialPlanningMode = InitialPlanningMode.RuleFirst,
    val remoteToolScope: RemoteToolScope = RemoteToolScope.PublicEvidenceOnly,
    val reduceDeviceActionConfirmations: Boolean = false,
    /**
     * When true, the in-app observe→replan→tap loop is enabled without a local action model: the
     * remote vision model ([RemoteVisionObservationReplanner]) drives GUI automation instead. Set by
     * ChatController when inferenceMode==Remote AND the remote-GUI opt-in is on AND the remote config
     * declares vision support. OR-ed into `hasMobileActionPlanningModel` so the loop's fail-closed
     * MissingModel gate passes on the remote-vision path.
     */
    val remoteGuiDrivingEnabled: Boolean = false,
    /** How [InitialPlanningMode.LocalModelFirst] splits initial planning between rules and the model. */
    val localPlanningStrategy: LocalPlanningStrategy = LocalPlanningStrategy.RuleFirst,
    /**
     * Verified on-device model that plans tools under [InitialPlanningMode.LocalModelFirst]. Null
     * fails closed: the run is planned exactly as [InitialPlanningMode.RuleFirst].
     */
    val localPlanningModelPath: String? = null,
) {
    /** Whether a verified on-device model may propose this run's first tool. */
    fun localModelPlansTools(): Boolean =
        initialPlanningMode == InitialPlanningMode.LocalModelFirst &&
            localPlanningStrategy != LocalPlanningStrategy.RuleFirst &&
            localPlanningModelPath != null
}

enum class InitialPlanningMode {
    RuleFirst,
    ModelFirstRemoteTools,

    /**
     * An on-device model may propose the initial tool; see [LocalPlanningStrategy]. Planning runs
     * before Auto placement, and placement never reads run options, so an answer turn is placed
     * exactly like a [RuleFirst] one.
     */
    LocalModelFirst,
}

/**
 * Experiment flag for local runs (debug-only developer setting `local_planning_strategy`). Every
 * strategy keeps the same registry, safety and confirmation gates; only who proposes the tool
 * changes, and a failed model proposal always yields the [RuleFirst] plan.
 */
enum class LocalPlanningStrategy {
    /** Skills, the rule intent classifier and the action model, as before. */
    RuleFirst,

    /** The rules plan first; the model is asked only when they plan nothing. */
    ModelFirstWithRuleFastPath,

    /**
     * Rules first, then a negation guard and a high-confidence local action-intent gate. Only an
     * open gate reaches the ordinary retrieved-tool proposal path; every closed/error result is chat.
     */
    RuleFirstGatedModel,

    /**
     * The model is asked first on every turn and its choice, including "answer without a tool",
     * stands; the rules run only when its proposal fails (measures the model on every request).
     */
    ModelFirst,
    ;

    companion object {
        /** Null for anything but an exact strategy name. */
        fun parse(raw: String?): LocalPlanningStrategy? = entries.firstOrNull { strategy -> strategy.name == raw }
    }
}

enum class RemoteToolScope {
    PublicEvidenceOnly,
    ModelPlanning,
}
