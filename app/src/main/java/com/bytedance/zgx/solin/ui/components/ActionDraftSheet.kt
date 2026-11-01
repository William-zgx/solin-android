package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.RuntimePermissionRequirement
import com.bytedance.zgx.solin.SpecialAccessRequirement
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors
import java.net.URI
import java.util.Locale

internal const val ACTION_SUMMARY_COLLAPSE_CHARS = 160
internal const val ACTION_PARAMETER_COLLAPSE_CHARS = 120
internal const val ACTION_PARAMETER_COMPACT_CHARS = 80

internal data class ActionTextDisplay(
    val text: String,
    val totalChars: Int,
    val canToggle: Boolean,
)

internal data class ActionParameterDisplayRow(
    val label: String,
    val value: String,
    val preferCompact: Boolean = false,
)

/**
 * Confirmation sheet for a pending tool draft, in the order the user needs to decide:
 * what will happen, why it needs a confirmation, the parameters, then where the data goes.
 *
 * The permission and special-access requirements are passed in already resolved rather than
 * derived here: resolving them needs the module-aware tool registry, and a registry that does
 * not know a tool reports "nothing required", which would render a confirmation sheet that
 * silently understates what the action is about to do. Keeping that lookup at the composition
 * root (where the container's registry is reachable) means this composable cannot accidentally
 * resolve them against a built-in-only registry — same reason [grantedSpecialAccessIds] is
 * hoisted instead of queried here.
 */
@Composable
internal fun ActionDraftSheet(
    confirmation: PendingAgentConfirmation,
    grantedSpecialAccessIds: Set<String>,
    runtimePermissionRequirements: List<RuntimePermissionRequirement>,
    specialAccessRequirements: List<SpecialAccessRequirement>,
    onOpenSpecialAccessSettings: (SpecialAccessRequirement) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = confirmation.draft
    val missingSpecialAccessRequirements = specialAccessRequirements
        .filterNot { requirement -> requirement.id in grantedSpecialAccessIds }
    TrustSheetSurface {
        NeedsYouChip()
        Text(
            modifier = Modifier.testTag("action_title_text"),
            text = draft.title,
            style = MaterialTheme.typography.titleLarge,
        )
        ExpandableActionText(
            text = draft.summary,
            collapsedMaxChars = ACTION_SUMMARY_COLLAPSE_CHARS,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            testTag = "action_summary_text",
        )
        if (draft.parameters.isNotEmpty()) {
            SheetRowDivider()
            TrustSheetGroup(
                modifier = Modifier.testTag("action_parameters"),
            ) {
                draft.parameters.forEach { (key, value) ->
                    ActionParameterRows(key = key, value = value)
                }
            }
        }
        if (runtimePermissionRequirements.isNotEmpty()) {
            SheetRowDivider()
            TrustSheetGroup(
                modifier = Modifier.testTag("runtime_permission_requirements"),
            ) {
                Text(
                    text = "确认后可能请求系统权限",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                runtimePermissionRequirements.forEach { requirement ->
                    Text(
                        text = "${requirement.title}：${requirement.rationale}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (missingSpecialAccessRequirements.isNotEmpty()) {
            SheetRowDivider()
            TrustSheetGroup(
                modifier = Modifier.testTag("special_access_requirements"),
            ) {
                Text(
                    text = "可能需要系统特殊授权",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                missingSpecialAccessRequirements.forEach { requirement ->
                    Text(
                        text = "${requirement.title}：${requirement.rationale}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("open_special_access_${requirement.id}"),
                        onClick = { onOpenSpecialAccessSettings(requirement) },
                    ) {
                        GlyphLabel(SolinGlyphKind.Check, "打开系统设置")
                    }
                }
            }
        }
        ActionDataBoundary(functionName = draft.functionName)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("action_dismiss_button"),
                onClick = onDismiss,
            ) {
                Text("取消")
            }
            Button(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("action_confirm_button"),
                onClick = onConfirm,
                // The per-requirement button above opens the system page; the primary stays
                // inert until the access is actually granted, so the sheet cannot imply that
                // confirming alone grants a special access.
                enabled = missingSpecialAccessRequirements.isEmpty(),
            ) {
                Text(
                    if (missingSpecialAccessRequirements.isEmpty()) {
                        actionConfirmButtonLabel(draft.functionName)
                    } else {
                        // Disabled until the access is granted; no second "打开系统设置" button.
                        "确认"
                    },
                )
            }
        }
    }
}

/** The amber chip that marks a step which waits for the user. */
@Composable
private fun NeedsYouChip() {
    val colors = LocalSolinColors.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.busyContainer,
        contentColor = colors.onBusyContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlacementMark(leavesPhone = false, tint = colors.busy)
            Text(
                text = "需要你确认",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * The primary button says what happens, not just "confirm": the user should be able to read the
 * outcome off the button. Unknown tools fall back to a plain "确认".
 */
internal fun actionConfirmButtonLabel(functionName: String): String =
    when (functionName) {
        MobileActionFunctions.SHARE_TEXT -> "确认分享"
        MobileActionFunctions.COMPOSE_EMAIL -> "确认，去邮件"
        MobileActionFunctions.COMPOSE_SMS -> "确认，去短信"
        MobileActionFunctions.DIAL_PHONE_NUMBER -> "确认，拨号"
        MobileActionFunctions.CREATE_CALENDAR_EVENT -> "创建日程"
        MobileActionFunctions.SCHEDULE_REMINDER -> "创建提醒"
        MobileActionFunctions.SET_SYSTEM_ALARM -> "设定闹钟"
        MobileActionFunctions.SET_SYSTEM_TIMER -> "设定倒计时"
        MobileActionFunctions.CANCEL_REMINDER -> "取消提醒"
        MobileActionFunctions.CONFIGURE_PERIODIC_CHECK -> "保存周期检查"
        MobileActionFunctions.OPEN_WIFI_SETTINGS,
        MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
        MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
        MobileActionFunctions.OPEN_SYSTEM_SETTINGS,
        -> "打开设置"

        MobileActionFunctions.OPEN_DEEP_LINK,
        MobileActionFunctions.OPEN_APP_BY_NAME,
        MobileActionFunctions.OPEN_APP_INTENT,
        MobileActionFunctions.OPEN_APP_DEEP_TARGET,
        MobileActionFunctions.OPEN_CAMERA,
        -> "打开"

        MobileActionFunctions.SEARCH_MAPS -> "查地图"
        MobileActionFunctions.WEB_SEARCH -> "搜索"
        MobileActionFunctions.QUERY_CONTACTS -> "读取联系人"
        MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY -> "读取日历"
        MobileActionFunctions.QUERY_FOREGROUND_APP -> "读取前台应用"
        MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS -> "读取通知"
        MobileActionFunctions.QUERY_RECENT_FILES -> "读取文件"
        MobileActionFunctions.QUERY_BACKGROUND_TASKS -> "查询后台任务"
        MobileActionFunctions.READ_CLIPBOARD -> "读取剪贴板"
        MobileActionFunctions.READ_CURRENT_SCREEN_TEXT -> "读取屏幕文字"
        MobileActionFunctions.OBSERVE_CURRENT_SCREEN -> "观察屏幕"
        MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
        MobileActionFunctions.READ_RECENT_IMAGE_OCR,
        MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
        -> "读取截图文字"

        MobileActionFunctions.CONTROL_MEDIA -> "控制媒体"
        MobileActionFunctions.CALCULATE -> "计算"
        MobileActionFunctions.UI_TAP -> "点击"
        MobileActionFunctions.UI_TYPE_TEXT -> "输入"
        MobileActionFunctions.UI_SUBMIT_SEARCH -> "搜索"
        MobileActionFunctions.UI_SCROLL -> "滚动"
        MobileActionFunctions.UI_SWIPE -> "滑动"
        MobileActionFunctions.UI_LONG_PRESS -> "长按"
        MobileActionFunctions.UI_PRESS_KEY,
        MobileActionFunctions.UI_PRESS_BACK,
        -> "按键"

        MobileActionFunctions.UI_WAIT -> "等待"
        else -> "确认"
    }

@Composable
private fun ActionDataBoundary(functionName: String) {
    val rows = remember(functionName) { actionDataBoundaryDisplayRows(functionName) }
    var expanded by rememberSaveable(functionName) { mutableStateOf(false) }
    val leavesPhone = remember(functionName) { actionDataLeavesPhone(functionName) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("action_data_boundary"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PlacementMark(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(10.dp),
                leavesPhone = leavesPhone,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = rows.first(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (expanded) {
                    rows.drop(1).forEach { row ->
                        Text(
                            text = row,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (rows.size > 1) {
            TextButton(
                modifier = Modifier.testTag("action_data_boundary_toggle"),
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Text(if (expanded) "收起" else "更多")
            }
        }
    }
}

@Composable
private fun ActionParameterRows(
    key: String,
    value: String,
) {
    val rows = remember(key, value) { actionParameterDisplayRows(key, value) }
    rows.forEachIndexed { index, row ->
        ExpandableActionText(
            label = row.label,
            text = row.value,
            collapsedMaxChars = if (row.preferCompact) {
                ACTION_PARAMETER_COMPACT_CHARS
            } else {
                ACTION_PARAMETER_COLLAPSE_CHARS
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            testTag = "action_parameter_${key.safeTestTagPart()}_$index",
        )
    }
}

/** One key/value row: the label stays quiet, the value carries the weight. */
@Composable
private fun ExpandableActionText(
    text: String,
    collapsedMaxChars: Int,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    testTag: String,
    label: String? = null,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val display = remember(text, collapsedMaxChars, expanded) {
        actionTextDisplay(
            text = text,
            collapsedMaxChars = collapsedMaxChars,
            expanded = expanded,
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (label != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    modifier = Modifier.width(72.dp),
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        modifier = Modifier.testTag(testTag),
                        text = display.text,
                        style = style,
                        color = color,
                    )
                    if (display.canToggle) {
                        TextButton(
                            modifier = Modifier.testTag("${testTag}_toggle"),
                            onClick = { expanded = !expanded },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        ) {
                            Text(
                                text = if (expanded) {
                                    "收起"
                                } else {
                                    "显示全部（${display.totalChars} 字）"
                                },
                            )
                        }
                    }
                }
            }
        } else {
            Text(
                modifier = Modifier.testTag(testTag),
                text = display.text,
                style = style,
                color = color,
            )
            if (display.canToggle) {
                TextButton(
                    modifier = Modifier.testTag("${testTag}_toggle"),
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = if (expanded) {
                            "收起"
                        } else {
                            "显示全部（${display.totalChars} 字）"
                        },
                    )
                }
            }
        }
    }
}

internal fun actionTextDisplay(
    text: String,
    collapsedMaxChars: Int,
    expanded: Boolean,
): ActionTextDisplay {
    val normalized = text.trim()
    val canToggle = normalized.length > collapsedMaxChars
    return ActionTextDisplay(
        text = if (!canToggle || expanded) {
            normalized
        } else {
            normalized.take((collapsedMaxChars - 3).coerceAtLeast(1)).trimEnd() + "..."
        },
        totalChars = normalized.length,
        canToggle = canToggle,
    )
}

internal fun actionParameterDisplayRows(
    key: String,
    value: String,
): List<ActionParameterDisplayRow> {
    val trimmedValue = value.trim()
    val normalizedKey = key.trim()
    val keyForMatching = normalizedKey.lowercase(Locale.US)
    val domain = if (keyForMatching in linkParameterKeys) {
        actionLinkDomain(trimmedValue)
    } else {
        null
    }
    return when {
        domain != null -> listOf(
            ActionParameterDisplayRow(
                label = "链接域名",
                value = domain,
                preferCompact = true,
            ),
            ActionParameterDisplayRow(
                label = "完整链接",
                value = trimmedValue,
            ),
        )

        keyForMatching == "packagename" -> listOf(
            ActionParameterDisplayRow(
                label = "目标包",
                value = trimmedValue,
                preferCompact = true,
            ),
        )

        keyForMatching == "targetid" -> listOf(
            ActionParameterDisplayRow(
                label = "目标类型",
                value = trimmedValue,
                preferCompact = true,
            ),
        )

        keyForMatching in epochMillisParameterKeys && trimmedValue.toLongOrNull() != null -> listOf(
            ActionParameterDisplayRow(
                label = parameterLabels[keyForMatching] ?: normalizedKey,
                value = formatEpochMillis(trimmedValue.toLong()),
                preferCompact = true,
            ),
        )

        keyForMatching == "command" && trimmedValue in mediaCommandLabels -> listOf(
            ActionParameterDisplayRow(
                label = "媒体操作",
                value = mediaCommandLabels.getValue(trimmedValue),
                preferCompact = true,
            ),
        )

        else -> listOf(
            ActionParameterDisplayRow(
                label = parameterLabels[keyForMatching] ?: normalizedKey.ifBlank { "参数" },
                value = trimmedValue,
                preferCompact = keyForMatching in compactParameterKeys,
            ),
        )
    }
}

/** Readable labels for common draft arguments; unknown keys keep their raw name. */
private val parameterLabels = mapOf(
    "phonenumber" to "电话号码",
    "body" to "短信内容",
    "expression" to "算式",
    "begintimemillis" to "开始时间",
    "endtimemillis" to "结束时间",
    "triggeratmillis" to "提醒时间",
    "delayminutes" to "延迟（分钟）",
    "lengthseconds" to "时长（秒）",
    "hour" to "小时",
    "minutes" to "分钟",
    "message" to "备注",
    "title" to "标题",
)

private val compactParameterKeys = setOf("phonenumber", "expression", "delayminutes", "lengthseconds", "hour", "minutes")

private val epochMillisParameterKeys = setOf("begintimemillis", "endtimemillis", "triggeratmillis")

private val mediaCommandLabels = mapOf(
    "play" to "播放",
    "pause" to "暂停",
    "play_pause" to "播放/暂停",
    "next" to "下一首",
    "previous" to "上一首",
    "stop" to "停止",
)

private fun formatEpochMillis(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(java.util.Date(millis))

/**
 * Where the data goes, shortest form. The first row is shown inline next to the placement mark;
 * the rest stay available behind the "更多" toggle so no boundary fact is lost.
 */
internal fun actionDataBoundaryDisplayRows(functionName: String): List<String> =
    when (functionName) {
        MobileActionFunctions.COMPOSE_EMAIL,
        MobileActionFunctions.CREATE_CALENDAR_EVENT,
        MobileActionFunctions.CREATE_CONTACT_DRAFT,
        MobileActionFunctions.SHARE_TEXT,
        -> listOf(
            "内容会交给外部 App，之后由它处理。",
            "Solin 不会替你确认外部结果，也不会宣称已完成。",
        )

        MobileActionFunctions.OPEN_WIFI_SETTINGS,
        MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
        MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
        MobileActionFunctions.OPEN_DEEP_LINK,
        MobileActionFunctions.OPEN_APP_BY_NAME,
        MobileActionFunctions.OPEN_APP_INTENT,
        MobileActionFunctions.OPEN_APP_DEEP_TARGET,
        MobileActionFunctions.SEARCH_MAPS,
        -> listOf(
            "会打开系统设置、链接或目标 App，不会自动完成页面里的操作。",
            "外部界面要你继续处理，返回后由你确认结果。",
        )

        MobileActionFunctions.QUERY_CONTACTS,
        MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
        MobileActionFunctions.QUERY_RECENT_FILES,
        MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
        MobileActionFunctions.READ_RECENT_IMAGE_OCR,
        MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
        MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
        MobileActionFunctions.QUERY_FOREGROUND_APP,
        MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
        MobileActionFunctions.READ_CLIPBOARD,
        -> listOf(
            "只读本次需要的本机内容。",
            "读取结果只留在本机，不会自动发送给远程模型。",
        )

        MobileActionFunctions.SCHEDULE_REMINDER,
        MobileActionFunctions.SET_SYSTEM_ALARM,
        MobileActionFunctions.SET_SYSTEM_TIMER,
        MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
        MobileActionFunctions.CANCEL_REMINDER,
        MobileActionFunctions.QUERY_BACKGROUND_TASKS,
        -> listOf(
            "只在本机创建、修改、取消或查询提醒与后台任务。",
            "提醒正文和任务状态默认留在本机。",
        )

        else -> listOf(
            "只按本次参数执行；取消不会执行。",
            "如需外部 App 或系统继续处理，会在结果页让你确认完成状态。",
        )
    }

/** True when the step hands content outside Solin, so the sheet shows the remote placement mark. */
internal fun actionDataLeavesPhone(functionName: String): Boolean =
    functionName in setOf(
        MobileActionFunctions.COMPOSE_EMAIL,
        MobileActionFunctions.CREATE_CALENDAR_EVENT,
        MobileActionFunctions.CREATE_CONTACT_DRAFT,
        MobileActionFunctions.SHARE_TEXT,
        MobileActionFunctions.OPEN_WIFI_SETTINGS,
        MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
        MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
        MobileActionFunctions.OPEN_DEEP_LINK,
        MobileActionFunctions.OPEN_APP_BY_NAME,
        MobileActionFunctions.OPEN_APP_INTENT,
        MobileActionFunctions.OPEN_APP_DEEP_TARGET,
        MobileActionFunctions.SEARCH_MAPS,
        MobileActionFunctions.WEB_SEARCH,
    )

internal fun actionLinkDomain(rawValue: String): String? {
    val host = runCatching { URI(rawValue.trim()).host }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return host.lowercase(Locale.US)
}

private val linkParameterKeys = setOf("uri", "url", "link")

private fun String.safeTestTagPart(): String =
    replace(Regex("""[^A-Za-z0-9_]+"""), "_")
        .trim('_')
        .ifBlank { "parameter" }
