package com.bytedance.zgx.solin.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistedEnumTest {
    private enum class Sample { Alpha, Beta }

    @Test
    fun decodesEveryExactName() {
        Sample.values().forEach { value ->
            assertEquals(value, persistedEnumOrNull<Sample>(value.name))
        }
    }

    @Test
    fun rejectsUnknownNullBlankWrongCaseAndWhitespaceWithoutChangingThem() {
        listOf(null, "", " ", "Alpha ", " alpha", "ALPHA", "Gamma").forEach { raw ->
            assertNull(persistedEnumOrNull<Sample>(raw))
        }
    }
}
