package com.bytedance.zgx.solin.data

import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.label
import org.junit.Assert.assertEquals
import org.junit.Test

class InferencePreferenceCodecTest {
    @Test
    fun decodesCompatibleAndUnknownValuesFailClosed() {
        assertEquals(InferenceMode.Local, decodeInferenceMode("Local"))
        assertEquals(InferenceMode.Auto, decodeInferenceMode("Auto"))
        assertEquals(InferenceMode.Remote, decodeInferenceMode("Remote"))
        assertEquals(InferenceMode.Local, decodeInferenceMode(null))
        assertEquals(InferenceMode.Local, decodeInferenceMode(""))
        assertEquals(InferenceMode.Local, decodeInferenceMode("future-mode"))
    }

    @Test
    fun keepsStableOrderNamesAndAutoLabel() {
        assertEquals(
            listOf("Local", "Auto", "Remote"),
            InferenceMode.values().map(InferenceMode::name),
        )
        assertEquals("自动", InferenceMode.Auto.label())
    }
}
