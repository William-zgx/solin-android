package com.bytedance.zgx.solin

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSignedSmokeDeviceTest {
    @Test
    fun installedAppLaunchesMainShell() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertNotNull("Expected a launcher activity for $packageName", launchIntent)

        context.startActivity(requireNotNull(launchIntent))

        val device = UiDevice.getInstance(instrumentation)
        assertTrue(
            "Expected $packageName to become foreground.",
            device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 15_000),
        )
        assertTrue(
            "Expected the Solin main shell to render.",
            device.wait(Until.hasObject(By.text("Solin")), 30_000),
        )
    }
}
