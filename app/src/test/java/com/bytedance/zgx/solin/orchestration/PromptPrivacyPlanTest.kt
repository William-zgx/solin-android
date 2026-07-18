package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPrivacyPlanTest {
    @Test
    fun everyRequiredSourceRetainsItsLocalOnlyBoundary() {
        assertEquals(11, PromptSegmentSource.entries.size)

        PromptSegmentSource.entries.forEach { source ->
            val plan = PromptPrivacyPlanner.build(
                listOf(
                    PromptPrivacySegment(
                        source = source,
                        privacy = MessagePrivacy.LocalOnly,
                        requiresLocalModel = true,
                    ),
                ),
            )

            assertEquals(source.name, MessagePrivacy.LocalOnly, plan.aggregatePrivacy)
            assertTrue(source.name, plan.requiresLocalModel)
            assertEquals(source.name, 0, plan.optionalHistoryFilteredCount)
        }
    }

    @Test
    fun onlyOptionalHistoryWithALocalConstraintMayBeFiltered() {
        val plan = PromptPrivacyPlanner.build(
            listOf(
                remoteEligible(PromptSegmentSource.CurrentInput),
                PromptPrivacySegment(
                    source = PromptSegmentSource.History,
                    privacy = MessagePrivacy.LocalOnly,
                    requiresLocalModel = true,
                    optionalHistory = true,
                ),
                PromptPrivacySegment(
                    source = PromptSegmentSource.Evidence,
                    privacy = MessagePrivacy.LocalOnly,
                    requiresLocalModel = false,
                    optionalHistory = true,
                ),
            ),
        )

        assertEquals(MessagePrivacy.LocalOnly, plan.aggregatePrivacy)
        assertTrue(plan.requiresLocalModel)
        assertEquals(1, plan.optionalHistoryFilteredCount)
    }

    @Test
    fun privacyAndRuntimeMetadataNormalizeToFailClosedMatrix() {
        val remoteButLocalRuntime = PromptPrivacyPlanner.build(
            listOf(
                PromptPrivacySegment(
                    source = PromptSegmentSource.ToolObservation,
                    privacy = MessagePrivacy.RemoteEligible,
                    requiresLocalModel = true,
                ),
            ),
        )
        assertEquals(MessagePrivacy.RemoteEligible, remoteButLocalRuntime.aggregatePrivacy)
        assertTrue(remoteButLocalRuntime.requiresLocalModel)

        val localOnlyWithoutRuntimeRequirement = PromptPrivacyPlanner.build(
            listOf(
                PromptPrivacySegment(
                    source = PromptSegmentSource.Evidence,
                    privacy = MessagePrivacy.LocalOnly,
                    requiresLocalModel = false,
                ),
            ),
        )
        assertEquals(MessagePrivacy.LocalOnly, localOnlyWithoutRuntimeRequirement.aggregatePrivacy)
        assertTrue(localOnlyWithoutRuntimeRequirement.requiresLocalModel)

        val remoteWithUnknownRuntime = PromptPrivacyPlanner.build(
            listOf(
                PromptPrivacySegment(
                    source = PromptSegmentSource.Evidence,
                    privacy = MessagePrivacy.RemoteEligible,
                    requiresLocalModel = null,
                ),
            ),
        )
        assertEquals(MessagePrivacy.RemoteEligible, remoteWithUnknownRuntime.aggregatePrivacy)
        assertTrue(remoteWithUnknownRuntime.requiresLocalModel)

        val unknownPrivacy = PromptPrivacyPlanner.build(
            listOf(
                PromptPrivacySegment(
                    source = PromptSegmentSource.Evidence,
                    privacy = null,
                    requiresLocalModel = false,
                ),
            ),
        )
        assertEquals(MessagePrivacy.LocalOnly, unknownPrivacy.aggregatePrivacy)
        assertTrue(unknownPrivacy.requiresLocalModel)
    }

    @Test
    fun nullEmptyUnknownAndNoRetainedSegmentsFailClosed() {
        val plans = listOf(
            PromptPrivacyPlanner.build(null),
            PromptPrivacyPlanner.build(emptyList()),
            PromptPrivacyPlanner.build(
                listOf(
                    PromptPrivacySegment(
                        source = PromptSegmentSource.CurrentInput,
                        privacy = null,
                        requiresLocalModel = false,
                    ),
                ),
            ),
            PromptPrivacyPlanner.build(
                listOf(
                    PromptPrivacySegment(
                        source = PromptSegmentSource.CurrentInput,
                        privacy = MessagePrivacy.RemoteEligible,
                        requiresLocalModel = null,
                    ),
                ),
            ),
            PromptPrivacyPlanner.build(
                listOf(
                    PromptPrivacySegment(
                        source = PromptSegmentSource.History,
                        privacy = MessagePrivacy.LocalOnly,
                        requiresLocalModel = true,
                        optionalHistory = true,
                    ),
                ),
            ),
        )

        plans.forEachIndexed { index, plan ->
            val expectedPrivacy = if (index == 3) {
                MessagePrivacy.RemoteEligible
            } else {
                MessagePrivacy.LocalOnly
            }
            assertEquals(expectedPrivacy, plan.aggregatePrivacy)
            assertTrue(plan.requiresLocalModel)
        }
        assertEquals(1, plans.last().optionalHistoryFilteredCount)
    }

    @Test
    fun appendCanOnlyTightenAnExistingPlan() {
        val initial = PromptPrivacyPlanner.build(
            listOf(remoteEligible(PromptSegmentSource.CurrentInput)),
        )
        val withOptionalHistory = PromptPrivacyPlanner.append(
            initial,
            listOf(
                PromptPrivacySegment(
                    source = PromptSegmentSource.History,
                    privacy = MessagePrivacy.LocalOnly,
                    requiresLocalModel = true,
                    optionalHistory = true,
                ),
            ),
        )
        assertEquals(MessagePrivacy.RemoteEligible, withOptionalHistory.aggregatePrivacy)
        assertFalse(withOptionalHistory.requiresLocalModel)
        assertEquals(1, withOptionalHistory.optionalHistoryFilteredCount)

        val tightened = PromptPrivacyPlanner.append(
            withOptionalHistory,
            listOf(
                PromptPrivacySegment(
                    source = PromptSegmentSource.DeviceContext,
                    privacy = MessagePrivacy.RemoteEligible,
                    requiresLocalModel = true,
                ),
            ),
        )
        assertEquals(MessagePrivacy.RemoteEligible, tightened.aggregatePrivacy)
        assertTrue(tightened.requiresLocalModel)

        val stillTightened = PromptPrivacyPlanner.append(
            tightened,
            listOf(remoteEligible(PromptSegmentSource.QueuedInput)),
        )
        assertEquals(MessagePrivacy.RemoteEligible, stillTightened.aggregatePrivacy)
        assertTrue(stillTightened.requiresLocalModel)

        val missingBase = PromptPrivacyPlanner.append(
            null,
            listOf(remoteEligible(PromptSegmentSource.CurrentInput)),
        )
        assertEquals(MessagePrivacy.LocalOnly, missingBase.aggregatePrivacy)
        assertTrue(missingBase.requiresLocalModel)

        val nullAppend = PromptPrivacyPlanner.append(initial, null)
        assertEquals(MessagePrivacy.LocalOnly, nullAppend.aggregatePrivacy)
        assertTrue(nullAppend.requiresLocalModel)

        assertEquals(initial, PromptPrivacyPlanner.append(initial, emptyList()))
    }

    private fun remoteEligible(source: PromptSegmentSource): PromptPrivacySegment =
        PromptPrivacySegment(
            source = source,
            privacy = MessagePrivacy.RemoteEligible,
            requiresLocalModel = false,
        )
}
