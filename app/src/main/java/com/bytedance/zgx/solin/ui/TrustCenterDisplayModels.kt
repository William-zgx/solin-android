package com.bytedance.zgx.solin.ui

import com.bytedance.zgx.solin.capability.CapabilityMatrix
import com.bytedance.zgx.solin.capability.CapabilityPrivacyLevel
import com.bytedance.zgx.solin.tool.ConfirmationPolicy

internal data class SensitiveCapabilityDisclosureDisplayRow(
    val title: String,
    /** One line the trust page shows before the row is expanded. */
    val summary: String,
    /** The full data / consent / remote / revoke boundary. */
    val body: String,
)

internal fun sensitiveCapabilityDisclosureDisplayRows(): List<SensitiveCapabilityDisclosureDisplayRow> =
    CapabilityMatrix.sensitiveCapabilityDisclosures.map { disclosure ->
        SensitiveCapabilityDisclosureDisplayRow(
            title = disclosure.displayName,
            summary = SENSITIVE_CAPABILITY_SUMMARIES[disclosure.capabilityId]
                ?: disclosure.consentBoundary.take(24),
            body = "数据：${disclosure.dataAccessed.userFacingTrustCopy()}\n" +
                "同意：${disclosure.consentBoundary.userFacingTrustCopy()}\n" +
                "远程：${disclosure.remoteBoundary.userFacingTrustCopy()}\n" +
                "撤销/清除：${disclosure.revokeOrClearControl.userFacingTrustCopy()}",
        )
    }

/**
 * One-line summaries for the sensitive capabilities. Each keeps the safety fact (leaves the
 * phone / needs consent / local only) that the full body states in more words.
 */
private val SENSITIVE_CAPABILITY_SUMMARIES = mapOf(
    "remote_model_send" to "内容会离开手机，确认前不发送。",
    "voice_transcript_input" to "系统转写，先同意再录音。",
    "share_and_file_picker_input" to "只处理你主动选择的内容。",
    "confirmed_device_actions" to "支付、发送、删除始终由你确认。",
    "contacts_calendar_reads" to "先确认再读取，结果留在本机。",
    "media_and_recent_ocr" to "先确认再读取，OCR 结果仅本机。",
    "usage_stats_foreground_app" to "需系统授权，结果仅本机。",
    "accessibility_current_screen_text" to "需无障碍授权和单次确认。",
    "media_projection_screenshot_ocr" to "每次截图都要前台同意。",
    "remote_vision_gui_automation" to "默认关闭；截图会离开手机。",
)

internal data class TrustCenterCapabilityDisplayRow(
    val title: String,
    /** One line: privacy level, confirmation, and whether it may go remote. */
    val summary: String,
    val body: String,
)

internal fun trustCenterCapabilityDisplayRows(): List<TrustCenterCapabilityDisplayRow> =
    CapabilityMatrix.nextStageMvpDescriptors.map { descriptor ->
        TrustCenterCapabilityDisplayRow(
            title = CapabilityMatrix.nextStageMvpScenarioTitle(descriptor.capabilityId),
            summary = "${descriptor.privacyLevel.displayText()} · " +
                descriptor.confirmationPolicy.displayText() + " · " +
                if (descriptor.remoteEligible) "可远程" else "不自动外发",
            body = "入口：${descriptor.entrypoint.userFacingEntrypoint()}\n" +
                "数据边界：${descriptor.privacyLevel.displayText()}；" +
                "确认：${descriptor.confirmationPolicy.displayText()}；" +
                "远程：${if (descriptor.remoteEligible) "可用" else "不可自动发送"}\n" +
                "失败处理：${descriptor.failureBehavior.userFacingTrustCopy()}",
        )
    }

internal val TRUST_CENTER_CAPABILITY_TEXT: String
    get() = "集中说明这些能力的数据边界、确认方式和失败处理：" +
        CapabilityMatrix.nextStageMvpScenarioTitles.values.joinToString("、") +
        "。"

private fun CapabilityPrivacyLevel.displayText(): String =
    when (this) {
        CapabilityPrivacyLevel.UserProvided -> "用户主动提供"
        CapabilityPrivacyLevel.PublicEvidence -> "公开信息"
        CapabilityPrivacyLevel.LocalEvidence -> "仅本机数据"
        CapabilityPrivacyLevel.ExternalAction -> "外部动作"
        CapabilityPrivacyLevel.BackgroundTask -> "本地后台任务"
    }

private fun ConfirmationPolicy.displayText(): String =
    when (this) {
        ConfirmationPolicy.Required -> "必须确认"
        ConfirmationPolicy.Optional -> "按设置确认"
        ConfirmationPolicy.NotRequired -> "不需要确认"
    }

private fun String.userFacingEntrypoint(): String =
    when (this) {
        "chat_input_and_remember_forget_commands" -> "聊天输入、记住/忘记指令"
        "clipboard_or_current_screen_summary_share_skill" -> "剪贴板或当前屏幕总结分享"
        "app_search_skill_and_accessibility_control" -> "App 搜索技能与无障碍控制"
        "schedule_reminder_and_background_tasks" -> "提醒创建与后台任务"
        "remote_tool_calls_web_search" -> "远程公开搜索工具"
        "model_manager_privacy_tab" -> "隐私与权限页"
        else -> "应用内入口"
    }

private fun String.userFacingTrustCopy(): String =
    replace("LocalOnly", "仅本机")
        .replace("fail-closed", "停止执行并提示")
