package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.PendingRemoteModeDisclosure
import com.bytedance.zgx.solin.label

@Composable
internal fun RemoteModeDisclosureSheet(
    disclosure: PendingRemoteModeDisclosure,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    TrustSheetSurface(modifier = Modifier.testTag("remote_mode_disclosure_sheet")) {
        SheetPlacementHeader(
            leavesPhone = true,
            title = "远程模型说明",
            subtitle = "普通发送不逐条弹窗，疑似敏感内容仍会单独确认。",
        )
        RemoteModeDisclosureRows(disclosure)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("remote_mode_keep_local_button"),
                onClick = onDismiss,
            ) {
                Text("保持本地")
            }
            Button(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("remote_mode_confirm_button"),
                onClick = onConfirm,
            ) {
                Text("使用远程")
            }
        }
    }
}

/** Sheet title block: placement mark, title, one-line subtitle. */
@Composable
internal fun SheetPlacementHeader(
    leavesPhone: Boolean,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PlacementMark(
            modifier = Modifier
                .padding(top = 9.dp)
                .size(10.dp),
            leavesPhone = leavesPhone,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RemoteModeDisclosureRows(disclosure: PendingRemoteModeDisclosure) {
    val rows = remoteModeDisclosureDisplayRows(disclosure)
    TrustSheetGroup(
        modifier = Modifier.testTag("remote_mode_disclosure_rows"),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) SheetRowDivider()
            Text(
                text = row,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What the remote model does and does not receive. The destination and the protected-data list
 * stay explicit: the user is agreeing to send conversation context off the phone.
 */
internal fun remoteModeDisclosureDisplayRows(disclosure: PendingRemoteModeDisclosure): List<String> {
    val imagePolicy = if (disclosure.supportsVisionInput) {
        "主动选择的图片会在发送前预览确认。"
    } else {
        "当前模型不支持图片，选择时直接提示，不读取也不发送。"
    }
    val destinationSummary = if (disclosure.isConfigured) {
        "发往 ${disclosure.remoteHost}"
    } else {
        "尚未配置地址，配置前不会发送任何远程请求"
    }
    return listOf(
        destinationSummary,
        "模型：${disclosure.remoteModelName}",
        "会发送：可远程发送的对话上下文、当前输入，以及主动选择的图片（需确认）。",
        "不会发送：仅本机历史、本地记忆、设备上下文、非图片附件正文或 OCR 摘录。",
        imagePolicy,
        "远程服务方可能按它的政策记录或保留请求和响应。",
        if (disclosure.apiKeyConfigured) {
            "已配置 API Key，加密保存在本机"
        } else {
            "尚未配置 API Key"
        },
        "连接状态：${disclosure.connectivityStatus.label}",
    )
}
