package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.SpecialAccessRequirement
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.runtimePermissionRequirementsFor
import com.bytedance.zgx.solin.specialAccessRequirementsFor
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

@Composable
internal fun ActionDraftSheet(
    confirmation: PendingAgentConfirmation,
    grantedSpecialAccessIds: Set<String>,
    onOpenSpecialAccessSettings: (SpecialAccessRequirement) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = confirmation.draft
    val runtimePermissionRequirements = confirmation.runtimePermissionRequirementsFor()
    val specialAccessRequirements = confirmation.specialAccessRequirementsFor()
    val missingSpecialAccessRequirements = specialAccessRequirements
        .filterNot { requirement -> requirement.id in grantedSpecialAccessIds }
    TrustSheetSurface(
        accentColor = LocalSolinColors.current.busy,
    ) {
        SectionTitle(
            text = draft.title,
            subtitle = "动作只会在你确认后读取上下文、创建草稿或调起系统能力。",
        )
        TrustSheetGroup {
            Text(
                text = "参数只用于本次确认动作。链接优先显示域名，长文本会折叠显示长度。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExpandableActionText(
                text = draft.summary,
                collapsedMaxChars = ACTION_SUMMARY_COLLAPSE_CHARS,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                testTag = "action_summary_text",
            )
            ActionDataBoundary(functionName = draft.functionName)
        }
        if (draft.parameters.isNotEmpty()) {
            TrustSheetGroup(
                modifier = Modifier.testTag("action_parameters"),
            ) {
                draft.parameters.forEach { (key, value) ->
                    ActionParameterRows(key = key, value = value)
                }
            }
        }
        if (runtimePermissionRequirements.isNotEmpty()) {
            TrustSheetGroup(
                modifier = Modifier.testTag("runtime_permission_requirements"),
            ) {
                Text(
                    text = "确认后可能请求系统权限",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
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
            TrustSheetGroup(
                modifier = Modifier.testTag("special_access_requirements"),
            ) {
                Text(
                    text = "可能需要系统特殊授权",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
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
                            .testTag("open_special_access_${requirement.id}"),
                        onClick = { onOpenSpecialAccessSettings(requirement) },
                    ) {
                        SolinGlyph(
                            kind = SolinGlyphKind.Check,
                            modifier = Modifier.size(18.dp),
                            tint = LocalContentColor.current,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("打开系统设置")
                    }
                }
            }
        }
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("action_confirm_button"),
            onClick = {
                missingSpecialAccessRequirements.firstOrNull()
                    ?.let(onOpenSpecialAccessSettings)
                    ?: onConfirm()
            },
        ) {
            Text(if (missingSpecialAccessRequirements.isEmpty()) "确认执行" else "打开系统设置")
        }
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("action_dismiss_button"),
            onClick = onDismiss,
        ) {
            Text("取消")
        }
    }
}

@Composable
private fun ActionDataBoundary(functionName: String) {
    val rows = remember(functionName) { actionDataBoundaryDisplayRows(functionName) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("action_data_boundary"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "数据去向",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        rows.forEach { row ->
            Text(
                text = row,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            testTag = "action_parameter_${key.safeTestTagPart()}_$index",
        )
    }
}

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
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
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

        else -> listOf(
            ActionParameterDisplayRow(
                label = normalizedKey.ifBlank { "参数" },
                value = trimmedValue,
            ),
        )
    }
}

internal fun actionDataBoundaryDisplayRows(functionName: String): List<String> =
    when (functionName) {
        MobileActionFunctions.COMPOSE_EMAIL,
        MobileActionFunctions.CREATE_CALENDAR_EVENT,
        MobileActionFunctions.CREATE_CONTACT_DRAFT,
        MobileActionFunctions.SHARE_TEXT,
        -> listOf(
            "确认后会把草稿或分享内容交给外部 App；目标 App 的后续处理由你继续确认。",
            "Solin 只能记录外部界面已打开，不能在未确认结果前宣称已完成。",
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
            "确认后会打开系统设置、链接、地图或目标 App；不会自动完成外部页面里的操作。",
            "如果外部界面需要继续处理，返回后由你确认结果。",
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
            "确认后只读取本次动作需要的本机内容或权限范围内摘要。",
            "读取结果默认仅留在本机，不会自动发送给远程模型。",
        )

        MobileActionFunctions.SCHEDULE_REMINDER,
        MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
        MobileActionFunctions.CANCEL_REMINDER,
        MobileActionFunctions.QUERY_BACKGROUND_TASKS,
        -> listOf(
            "确认后只在本机创建、修改、取消或查询提醒与后台任务。",
            "提醒正文和后台任务状态默认留在本机。",
        )

        else -> listOf(
            "确认后只按本次动作使用必要参数；取消不会执行。",
            "如需外部 App 或系统继续处理，会在结果页让你确认完成状态。",
        )
    }

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
