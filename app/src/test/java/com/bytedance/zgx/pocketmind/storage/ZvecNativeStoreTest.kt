package com.bytedance.zgx.pocketmind.storage

import org.junit.Assert.assertTrue
import org.junit.Test

class ZvecNativeStoreTest {
    @Test
    fun probeReportsAvailabilityWithoutThrowing() {
        val status = ZvecNativeStore.probe()

        assertTrue(status.available || status.detail.isNotBlank())
    }
}
