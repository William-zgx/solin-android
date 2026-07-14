package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.PendingRemoteModeDisclosure
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors

@Composable
internal fun RemoteModeDisclosureSheet(
    disclosure: PendingRemoteModeDisclosure,
    onDismiss: () -> Unit,
) {
    TrustSheetSurface(
        modifier = Modifier.testTag("remote_mode_disclosure_sheet"),
        accentColor = LocalSolinColors.current.remote,
    ) {
        SectionTitle(
            text = "已切换到远程模型",
            subtitle = "远程模式提醒只在切换时展示；普通发送不会逐条弹窗，疑似敏感内容仍会单独确认。",
        )
        RemoteModeDisclosureRows(disclosure)
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("remote_mode_confirm_button"),
            onClick = onDismiss,
        ) {
            SolinGlyph(
                kind = SolinGlyphKind.Spark,
                modifier = Modifier.size(18.dp),
                tint = LocalContentColor.current,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("知道了")
        }
    }
}

@Composable
private fun RemoteModeDisclosureRows(disclosure: PendingRemoteModeDisclosure) {
    val rows = remoteModeDisclosureDisplayRows(disclosure)
    TrustSheetGroup(
        modifier = Modifier.testTag("remote_mode_disclosure_rows"),
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

internal fun remoteModeDisclosureDisplayRows(disclosure: PendingRemoteModeDisclosure): List<String> {
    val imagePolicy = if (disclosure.supportsVisionInput) {
        "主动选择的图片会在每次发送前弹出远程发送预览确认；请只选择你愿意交给该服务处理的图片。"
    } else {
        "当前远程模型未启用图片输入；选择图片时会直接提示不支持，不会读取或发送。"
    }
    val destinationSummary = if (disclosure.isConfigured) {
        "远程地址：${disclosure.remoteHost}"
    } else {
        "远程地址：尚未配置，配置完成前不会发送远程请求"
    }
    return listOf(
        destinationSummary,
        "模型：${disclosure.remoteModelName}",
        "发送范围：仅发送可远程发送的对话上下文、当前输入，以及确认后的主动选择图片。",
        "不会发送：仅本机历史、本地记忆、设备上下文、非图片附件正文或 OCR 摘录。",
        "图片规则：$imagePolicy",
        "远程服务方可能按其政策记录或保留请求和响应。",
        "凭据状态：${if (disclosure.apiKeyConfigured) "已配置 API Key" else "未配置 API Key"}",
        "连接状态：${disclosure.connectivityStatus.label}",
    )
}
