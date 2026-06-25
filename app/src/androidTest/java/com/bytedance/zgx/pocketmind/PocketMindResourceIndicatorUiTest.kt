package com.bytedance.zgx.pocketmind

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bytedance.zgx.pocketmind.resource.SystemResourceSnapshot
import com.bytedance.zgx.pocketmind.resource.ThermalPressure
import com.bytedance.zgx.pocketmind.ui.ResourcePressureBadge
import com.bytedance.zgx.pocketmind.ui.theme.PocketMindTheme
import org.junit.Rule
import org.junit.Test

class PocketMindResourceIndicatorUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun badgeExpandsResourcePanel() {
        composeRule.setContent {
            PocketMindTheme {
                ResourcePressureBadge(
                    snapshot = SystemResourceSnapshot(
                        appPssBytes = 512L * 1024L * 1024L,
                        javaHeapBytes = 128L * 1024L * 1024L,
                        nativeHeapBytes = 180L * 1024L * 1024L,
                        availableRamBytes = 640L * 1024L * 1024L,
                        lowMemory = false,
                        appCpuPercent = 82,
                        thermalPressure = ThermalPressure.Hot,
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("resource_pressure_badge").assertIsDisplayed().performClick()

        composeRule.onNodeWithTag("resource_pressure_panel").assertIsDisplayed()
        composeRule.onNodeWithText("App 内存").assertIsDisplayed()
        composeRule.onNodeWithText("App CPU").assertIsDisplayed()
        composeRule.onNodeWithText("82%").assertIsDisplayed()
        composeRule.onNodeWithText("温度").assertIsDisplayed()
        composeRule.onNodeWithText("可用 RAM").assertIsDisplayed()
    }
}
