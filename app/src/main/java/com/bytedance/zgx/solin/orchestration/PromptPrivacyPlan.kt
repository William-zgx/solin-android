package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy

enum class PromptSegmentSource {
    CurrentInput,
    History,
    Image,
    File,
    ScreenOcr,
    DeviceContext,
    Memory,
    Evidence,
    ToolObservation,
    Steer,
    QueuedInput,
}

data class PromptPrivacySegment(
    val source: PromptSegmentSource,
    val privacy: MessagePrivacy?,
    val requiresLocalModel: Boolean?,
    val optionalHistory: Boolean = false,
)

data class PromptPrivacyPlan(
    val aggregatePrivacy: MessagePrivacy,
    val requiresLocalModel: Boolean,
    val optionalHistoryFilteredCount: Int,
)

object PromptPrivacyPlanner {
    fun build(segments: List<PromptPrivacySegment>?): PromptPrivacyPlan {
        val aggregate = aggregate(segments)
        return if (aggregate.retainedSegmentCount == 0) {
            failClosed(aggregate.optionalHistoryFilteredCount)
        } else {
            aggregate.toPlan()
        }
    }

    fun append(
        plan: PromptPrivacyPlan?,
        segments: List<PromptPrivacySegment>?,
    ): PromptPrivacyPlan {
        if (plan == null) return failClosed()
        if (segments == null) return failClosed()
        if (segments.isEmpty()) return plan

        val appended = aggregate(segments)
        if (appended.retainedSegmentCount == 0) {
            return plan.copy(
                optionalHistoryFilteredCount =
                    plan.optionalHistoryFilteredCount + appended.optionalHistoryFilteredCount,
            )
        }
        return PromptPrivacyPlan(
            aggregatePrivacy = if (
                plan.aggregatePrivacy == MessagePrivacy.LocalOnly ||
                appended.aggregatePrivacy == MessagePrivacy.LocalOnly
            ) {
                MessagePrivacy.LocalOnly
            } else {
                MessagePrivacy.RemoteEligible
            },
            requiresLocalModel =
                plan.aggregatePrivacy == MessagePrivacy.LocalOnly ||
                    plan.requiresLocalModel ||
                    appended.requiresLocalModel,
            optionalHistoryFilteredCount =
                plan.optionalHistoryFilteredCount + appended.optionalHistoryFilteredCount,
        )
    }

    private fun aggregate(segments: List<PromptPrivacySegment>?): SegmentAggregate {
        if (segments.isNullOrEmpty()) return SegmentAggregate()

        var privacy = MessagePrivacy.RemoteEligible
        var requiresLocalModel = false
        var retainedSegmentCount = 0
        var optionalHistoryFilteredCount = 0
        segments.forEach { segment ->
            if (segment.isFilterableOptionalHistory()) {
                optionalHistoryFilteredCount += 1
                return@forEach
            }

            retainedSegmentCount += 1
            val segmentPrivacy = segment.privacy ?: MessagePrivacy.LocalOnly
            val segmentRequiresLocalModel =
                segmentPrivacy == MessagePrivacy.LocalOnly || segment.requiresLocalModel != false
            if (segmentPrivacy == MessagePrivacy.LocalOnly) privacy = MessagePrivacy.LocalOnly
            if (segmentRequiresLocalModel == true) requiresLocalModel = true
        }
        return SegmentAggregate(
            aggregatePrivacy = privacy,
            requiresLocalModel = requiresLocalModel,
            optionalHistoryFilteredCount = optionalHistoryFilteredCount,
            retainedSegmentCount = retainedSegmentCount,
        )
    }

    private fun PromptPrivacySegment.isFilterableOptionalHistory(): Boolean =
        source == PromptSegmentSource.History &&
            optionalHistory &&
            (privacy != MessagePrivacy.RemoteEligible || requiresLocalModel != false)

    private fun failClosed(optionalHistoryFilteredCount: Int = 0): PromptPrivacyPlan =
        PromptPrivacyPlan(
            aggregatePrivacy = MessagePrivacy.LocalOnly,
            requiresLocalModel = true,
            optionalHistoryFilteredCount = optionalHistoryFilteredCount,
        )

    private data class SegmentAggregate(
        val aggregatePrivacy: MessagePrivacy = MessagePrivacy.RemoteEligible,
        val requiresLocalModel: Boolean = false,
        val optionalHistoryFilteredCount: Int = 0,
        val retainedSegmentCount: Int = 0,
    ) {
        fun toPlan(): PromptPrivacyPlan = PromptPrivacyPlan(
            aggregatePrivacy = aggregatePrivacy,
            requiresLocalModel = requiresLocalModel,
            optionalHistoryFilteredCount = optionalHistoryFilteredCount,
        )
    }
}
