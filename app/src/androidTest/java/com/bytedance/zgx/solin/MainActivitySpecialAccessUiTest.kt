package com.bytedance.zgx.solin

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test

class MainActivitySpecialAccessUiTest {
    private val targetContext: Context = ApplicationProvider.getApplicationContext()

    // The Activity is launched in the test body, so this endpoint is up before it starts.
    @get:Rule
    val probeServer = LocalModelProbeServerRule()

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun currentScreenTextConfirmationShowsSpecialAccessRequirementWithoutRuntimePermission() {
        launchReadyRemoteMainActivity(targetContext).useWithViewModelTeardown {
            composeRule.waitForTag("app_title")

            composeRule.sendRemotePrompt("总结当前屏幕文字，最多1200字")

            composeRule.waitForTag("special_access_requirements")
            composeRule.onNodeWithText("读取当前屏幕文本").assertIsDisplayed()
            composeRule.onNodeWithText(
                "将读取当前屏幕的可访问文本快照（最多 1200 字符）；不会读取截图、像素、坐标或完整节点树。"
            ).assertIsDisplayed()
            composeRule.onNodeWithTag("special_access_requirements")
                .assertIsDisplayed()
            composeRule.onNodeWithText("可能需要系统特殊授权")
                .assertIsDisplayed()
            composeRule.onNodeWithText(
                "无障碍屏幕文本权限：用于在你确认后只读获取当前屏幕暴露的可访问文本；不会点击、控制设备或读取截图像素。"
            )
                .assertIsDisplayed()
            composeRule.onNodeWithTag("open_special_access_accessibility_screen_text")
                .assertIsDisplayed()
                .assertTextContains("打开系统设置")
            // The primary stays inert until the access is granted; the per-requirement button opens settings.
            composeRule.onNodeWithTag("action_confirm_button")
                .assertTextContains("确认")
                .assertIsNotEnabled()
            composeRule.assertTagAbsent("runtime_permission_requirements")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("special_access_requirements")
        }
    }

    @Test
    fun deviceControlConfirmationShowsAccessibilityControlRequirementWithoutRuntimePermission() {
        launchReadyRemoteMainActivity(targetContext).useWithViewModelTeardown {
            composeRule.waitForTag("app_title")

            composeRule.sendRemotePrompt("观察当前屏幕")

            composeRule.waitForTag("special_access_requirements")
            composeRule.onAllNodesWithText("观察当前屏幕")
                .onFirst()
                .assertIsDisplayed()
            composeRule.onNodeWithText(
                "将读取当前屏幕的可访问状态快照；不会读取截图像素或发送远程。"
            ).assertIsDisplayed()
            composeRule.onNodeWithTag("special_access_requirements")
                .assertIsDisplayed()
            composeRule.onNodeWithText("可能需要系统特殊授权")
                .assertIsDisplayed()
            composeRule.onNodeWithText(
                "无障碍设备控制权限：用于在你确认后观察当前屏幕的可访问状态并执行点击、输入、滚动、返回或等待；不读取截图像素，不保存原始节点树。"
            )
                .assertIsDisplayed()
            composeRule.onNodeWithTag("open_special_access_accessibility_device_control")
                .assertIsDisplayed()
                .assertTextContains("打开系统设置")
            // The primary stays inert until the access is granted; the per-requirement button opens settings.
            composeRule.onNodeWithTag("action_confirm_button")
                .assertTextContains("确认")
                .assertIsNotEnabled()
            composeRule.assertTagAbsent("runtime_permission_requirements")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("special_access_requirements")
        }
    }

    @Test
    fun foregroundAppConfirmationShowsUsageAccessRequirementWithoutRuntimePermission() {
        launchReadyRemoteMainActivity(targetContext).useWithViewModelTeardown {
            composeRule.waitForTag("app_title")

            composeRule.sendRemotePrompt("当前应用是什么")

            composeRule.waitForTag("special_access_requirements")
            composeRule.onNodeWithText("查询当前前台应用").assertIsDisplayed()
            composeRule.onNodeWithText(
                "将通过 UsageStats 估计当前前台应用（包名与应用名）；不读取屏幕内容或使用历史。"
            ).assertIsDisplayed()
            composeRule.onNodeWithTag("special_access_requirements")
                .assertIsDisplayed()
            composeRule.onNodeWithText("可能需要系统特殊授权")
                .assertIsDisplayed()
            composeRule.onNodeWithText(
                "使用情况访问权限：用于通过 UsageStats 估计当前前台应用名和包名；不是窗口真值，不读取使用历史或屏幕内容，需要在系统设置中手动开启。"
            )
                .assertIsDisplayed()
            composeRule.onNodeWithTag("open_special_access_usage_stats")
                .assertIsDisplayed()
                .assertTextContains("打开系统设置")
            // The primary stays inert until the access is granted; the per-requirement button opens settings.
            composeRule.onNodeWithTag("action_confirm_button")
                .assertTextContains("确认")
                .assertIsNotEnabled()
            composeRule.assertTagAbsent("runtime_permission_requirements")

            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            composeRule.waitForTagGone("special_access_requirements")
        }
    }
}
