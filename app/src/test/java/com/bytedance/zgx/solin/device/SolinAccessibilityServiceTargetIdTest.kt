package com.bytedance.zgx.solin.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SolinAccessibilityServiceTargetIdTest {
    @Test
    fun transientNodeIdMatchesExactObservedId() {
        assertEquals(1_000, transientNodeIdTargetMatchScore("n0_abcd1234", "n0_abcd1234"))
    }

    @Test
    fun transientNodeIdMatchesObservedIdWithSnapshotSalt() {
        assertEquals(950, transientNodeIdTargetMatchScore("n0_abcd1234", "n0_abcd1234_f00dbeef"))
    }

    @Test
    fun transientNodeIdDoesNotMatchDifferentCandidatePrefix() {
        assertNull(transientNodeIdTargetMatchScore("n1_abcd1234", "n10_abcd1234_f00dbeef"))
    }
}
