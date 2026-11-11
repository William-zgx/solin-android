package com.bytedance.zgx.solin.logging

import org.junit.Assert.assertEquals
import org.junit.Test

class SolinLogTest {
    @Test
    fun errorOverloadsDelegateWithAndWithoutThrowable() {
        val original = SolinLogHolder.current
        val events = mutableListOf<String>()
        val delegate = object : SolinLog {
            override fun d(tag: String, msg: String) = Unit
            override fun i(tag: String, msg: String) = Unit
            override fun w(tag: String, msg: String) = Unit
            override fun w(tag: String, msg: String, t: Throwable) = Unit

            override fun e(tag: String, msg: String) {
                events += "$tag|$msg|null"
            }

            override fun e(tag: String, msg: String, t: Throwable) {
                events += "$tag|$msg|$t"
            }
        }
        val error = IllegalStateException("failure")

        try {
            SolinLogHolder.current = delegate
            solinE("Solin/Test", "without")
            solinE("Solin/Test", "with", error)
        } finally {
            SolinLogHolder.current = original
        }

        assertEquals(listOf("Solin/Test|without|null", "Solin/Test|with|$error"), events)
    }
}
