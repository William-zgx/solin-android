package com.bytedance.zgx.solin.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

internal fun assertContainsAll(text: String, vararg expectedParts: String) {
    expectedParts.forEach { expected ->
        assertTrue("missing text: $expected", text.contains(expected))
    }
}

internal fun assertContainsNone(text: String, vararg forbiddenParts: String) {
    forbiddenParts.forEach { forbidden ->
        assertFalse("unexpected text: $forbidden", text.contains(forbidden))
    }
}
