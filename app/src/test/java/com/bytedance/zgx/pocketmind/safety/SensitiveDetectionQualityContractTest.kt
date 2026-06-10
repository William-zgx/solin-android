package com.bytedance.zgx.pocketmind.safety

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quantitative guardrail (P3) for outbound sensitive-content detection. Locks the current
 * [SafetyPolicy] regexes to a labeled baseline corpus so a future tightening/loosening that
 * regresses recall (privacy leak) or precision (over-blocking) fails CI instead of shipping.
 */
class SensitiveDetectionQualityContractTest {
    private val report = SensitiveDetectionQualityContract.evaluate()

    @Test
    fun recallMeetsPrivacyFloor() {
        assertTrue(
            "Sensitive-detection recall ${report.recallPercent}% < " +
                "${SensitiveDetectionQualityContract.RECALL_TARGET_PERCENT}% target. " +
                "Missed sensitive samples: " +
                report.falseNegativeSamples.joinToString { "'${it.text}' (${it.note})" },
            report.meetsRecallTarget,
        )
    }

    @Test
    fun falsePositiveRateStaysUnderCeiling() {
        assertTrue(
            "Sensitive-detection false-positive rate ${report.falsePositivePercent}% > " +
                "${SensitiveDetectionQualityContract.FALSE_POSITIVE_MAX_PERCENT}% ceiling. " +
                "Wrongly flagged benign samples: " +
                report.falsePositiveSamples.joinToString { "'${it.text}' (${it.note})" },
            report.meetsFalsePositiveCeiling,
        )
    }

    @Test
    fun baselineCorpusCoversBothClasses() {
        // Guards against the corpus accidentally degenerating to one class, which would make the
        // precision/recall percentages meaningless.
        assertTrue("corpus must contain sensitive samples", report.sensitiveTotal > 0)
        assertTrue("corpus must contain benign samples", report.benignTotal > 0)
    }

    @Test
    fun contractHoldsEndToEnd() {
        assertTrue(
            "recall=${report.recallPercent}% fp=${report.falsePositivePercent}% " +
                "(tp=${report.truePositives} fn=${report.falseNegatives} " +
                "tn=${report.trueNegatives} fp=${report.falsePositives})",
            report.meetsContract,
        )
    }
}
