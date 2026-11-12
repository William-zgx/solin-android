package com.bytedance.zgx.solin.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LabRunConfigurationTest {
    @Test
    fun supportedRunDatesAreAccepted() {
        assertEquals("20261112", validateLabRunDate("20261112"))
        assertEquals("20261112-heldout", validateLabRunDate("20261112-heldout"))
        assertEquals("20261112-research", validateLabRunDate("20261112-research"))
    }

    @Test
    fun invalidRunDateFailsClosedInsteadOfReusingDefaultFolder() {
        assertThrows(IllegalArgumentException::class.java) {
            validateLabRunDate("20261112-research-typo")
        }
    }
}
