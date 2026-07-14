package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.PendingRemoteSendDisclosure
import com.bytedance.zgx.solin.RemoteSendDisclosureKind
import com.bytedance.zgx.solin.RemoteSendDisclosurePolicy
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors

@Composable
internal fun RemoteSendDisclosureSheet(
    disclosure: PendingRemoteSendDisclosure,
    disclosurePolicy: RemoteSendDisclosurePolicy,
    onConfirm: (Boolean) -> Unit,
    onMaskAndSend: () -> Unit,
    onSendAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canSuppressForSession = remoteSendDisclosureCanSuppressForSession(
        policy = disclosurePolicy,
        disclosure = disclosure,
    )
    val requiresSensitiveConsent = disclosure.requiresSensitiveConsent
    var suppressForSession by rememberSaveable(disclosure) { mutableStateOf(false) }
    TrustSheetSurface(
        modifier = Modifier.testTag("remote_send_disclosure_sheet"),
        accentColor = if (requiresSensitiveConsent) {
            MaterialTheme.colorScheme.error
        } else {
            LocalSolinColors.current.remote
        },
    ) {
        SectionTitle(
            text = "即将发送到远程模型",
            subtitle = "确认后才会把本次内容交给远程模型；API Key 只作为请求凭据使用，不在界面显示。",
        )
        RemoteSendDisclosureRows(disclosure)
        if (canSuppressForSession) {
            TrustSheetGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { suppressForSession = !suppressForSession }
                        .testTag("remote_send_suppress_session_row"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = suppressForSession,
                        onCheckedChange = { suppressForSession = it },
                        modifier = Modifier.testTag("remote_send_suppress_session_checkbox"),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "本次会话不再提示（含敏感内容或图片时仍会提示）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!requiresSensitiveConsent) {
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("remote_send_confirm_button"),
                onClick = { onConfirm(canSuppressForSession && suppressForSession) },
            ) {
                SolinGlyph(
                    kind = SolinGlyphKind.Spark,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("确认发送")
            }
        }
        if (requiresSensitiveConsent) {
            if (disclosure.maskedPromptPreview.isNotBlank()) {
                TrustSheetGroup {
                    Text(
                        modifier = Modifier.testTag("remote_send_masked_preview"),
                        text = "打码后将发送：${disclosure.maskedPromptPreview}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (disclosure.allowMaskedSend) {
                FilledTonalButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("remote_send_mask_button"),
                    onClick = onMaskAndSend,
                ) {
                    SolinGlyph(
                        kind = SolinGlyphKind.Shield,
                        modifier = Modifier.size(18.dp),
                        tint = LocalContentColor.current,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("打码后发送")
                }
            }
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("remote_send_anyway_button"),
                onClick = onSendAnyway,
            ) {
                SolinGlyph(
                    kind = SolinGlyphKind.Spark,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("仍然发送（已记录）")
            }
        }
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("remote_send_dismiss_button"),
            onClick = onDismiss,
        ) {
            SolinGlyph(
                kind = SolinGlyphKind.Close,
                modifier = Modifier.size(18.dp),
                tint = LocalContentColor.current,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("取消")
        }
    }
}

internal fun remoteSendDisclosureCanSuppressForSession(
    policy: RemoteSendDisclosurePolicy,
    disclosure: PendingRemoteSendDisclosure,
): Boolean =
    policy == RemoteSendDisclosurePolicy.OncePerSession &&
        !disclosure.forcedBySensitiveContent &&
        !disclosure.requiresSensitiveConsent &&
        disclosure.imageAttachmentCount == 0

@Composable
private fun RemoteSendDisclosureRows(disclosure: PendingRemoteSendDisclosure) {
    val rows = remoteSendDisclosureDisplayRows(disclosure)
    TrustSheetGroup(
        modifier = Modifier.testTag("remote_send_disclosure_rows"),
    ) {
        rows.forEach { row ->
            Text(
                text = row,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun remoteSendDisclosureDisplayRows(disclosure: PendingRemoteSendDisclosure): List<String> {
    val sendSummary = when (disclosure.kind) {
        RemoteSendDisclosureKind.CurrentInput ->
            "会发送：当前输入、可远程发送历史 ${disclosure.remoteHistoryCount} 条"

        RemoteSendDisclosureKind.ToolResultContinuation ->
            "会发送：工具结果续写提示、可远程发送历史 ${disclosure.remoteHistoryCount} 条"
    }.let { summary ->
        if (disclosure.imageAttachmentCount > 0) {
            "$summary、图片 ${disclosure.imageAttachmentCount} 张；图片字节会发往该远程地址"
        } else {
            "$summary；本次没有图片字节发送"
        }
    }
    val retentionNotice = if (disclosure.imageAttachmentCount > 0) {
        "远程服务方可能按其政策记录或保留请求、图片和响应；请只发送你愿意交给该服务处理的内容。"
    } else {
        "远程服务方可能按其政策记录或保留请求和响应；请只发送你愿意交给该服务处理的内容。"
    }
    return listOf(
        "发送到：${disclosure.remoteHost} · ${disclosure.remoteModelName}",
        sendSummary,
        "不会发送：仅本机历史 ${disclosure.localOnlyHistoryFilteredCount} 条、本地记忆、设备上下文、非图片附件",
        retentionNotice,
    ) + buildList {
        if (disclosure.promptPreview.isNotBlank()) {
            add("将发送内容预览：${disclosure.promptPreview}")
        }
        if (disclosure.sensitiveHitCategories.isNotEmpty()) {
            add("⚠ 检测到疑似敏感内容（${disclosure.sensitiveHitCategories.joinToString("、")}）；请确认是否仍要发送")
        }
        if (disclosure.sensitiveHitSnippets.isNotEmpty()) {
            add("命中片段：${disclosure.sensitiveHitSnippets.joinToString("、")}")
        }
    }
}
