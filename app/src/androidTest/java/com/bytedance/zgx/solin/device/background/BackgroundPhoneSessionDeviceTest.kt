package com.bytedance.zgx.solin.device.background

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertThrows
import org.junit.Test

/** Default path is a device regression: it must not connect or consume ADB authorization. */
class BackgroundPhoneSessionDeviceTest {
    @Test fun disabledSessionRejectsBeforeReadingProvisioningOrOpeningSocket() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThrows(IllegalStateException::class.java) { BackgroundPhoneSession.connect(context) }
    }
}
