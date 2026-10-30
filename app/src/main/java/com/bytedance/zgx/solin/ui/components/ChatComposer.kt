package com.bytedance.zgx.solin.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.ui.ACTUAL_REMOTE_ATTACHMENT_PROTECTION_NOTICE
import com.bytedance.zgx.solin.ui.AUTO_ATTACHMENT_PROTECTION_NOTICE
import com.bytedance.zgx.solin.ui.REMOTE_ATTACHMENT_PROTECTION_NOTICE
import com.bytedance.zgx.solin.ui.VOICE_INPUT_PERMISSION_DISCLOSURE_BODY
import com.bytedance.zgx.solin.ui.VOICE_INPUT_PERMISSION_DISCLOSURE_TITLE
import com.bytedance.zgx.solin.ui.VOICE_INPUT_PRIVACY_DESCRIPTION
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors

@Composable
internal fun Composer(
    state: ChatUiState,
    input: String,
    onInputChanged: (String) -> Unit,
    onOpenModelManager: () -> Unit,
    onStartVoiceInput: () -> Unit,
    onCancelVoiceInput: () -> Unit,
    onFinishVoiceInput: () -> Unit,
    onPickSharedAttachment: () -> Unit,
    onClearPendingSharedInput: (Long) -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
    activeRunPlacement: RunPlacement?,
    activeRunPlacementReason: PlacementReasonCode?,
) {
    val inputEnabled = !state.isBusy
    val attachmentEnabled = !state.isBusy
    val voiceEnabled = !state.isBusy && !state.voiceCapture.isActive
    var showVoicePermissionDisclosure by rememberSaveable { mutableStateOf(false) }
    val actionIsStop = state.isGenerating
    val composerEdge = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
    val hasPendingSharedInput = state.pendingSharedInputDraft != null
    val canSend = inputEnabled && (input.isNotBlank() || hasPendingSharedInput)
    val placeholder = when {
        state.isBusy -> state.statusText
        !state.isReady -> "配置远程或下载本地后提问"
        hasPendingSharedInput -> "补充说明"
        else -> "输入问题"
    }
    val showCompactStatus = state.isBusy ||
        state.statusText.isVoiceStatusText() ||
        state.statusText.isAgentExecutionOutcomeStatusText()
    val attachmentNotice = attachmentProtectionNotice(
        preference = state.inferenceMode,
        actualPlacement = activeRunPlacement,
        actualReason = activeRunPlacementReason,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.86f))
            .drawBehind {
                drawLine(
                    color = composerEdge,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.voiceCapture.isActive) {
            VoiceCaptureBar(
                isTranscribing = state.voiceCapture.isTranscribing,
                level = state.voiceCapture.level,
                waveformLevels = state.voiceCapture.waveformLevels,
                partialText = state.voiceCapture.partialText,
                onCancel = onCancelVoiceInput,
                onFinish = onFinishVoiceInput,
            )
            VoiceInputPrivacyNotice()
        }
        if (attachmentNotice != null) {
            AttachmentProtectionNotice(attachmentNotice)
        }
        state.pendingSharedInputDraft?.let { draft ->
            PendingSharedInputStrip(
                summary = draft.summary,
                onRemove = { onClearPendingSharedInput(draft.id) },
            )
        }
        if (showCompactStatus) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_status_text"),
                text = state.statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // Narrow screens drop the model button; everything else renders identically.
            val compactControls = maxWidth < 360.dp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                ComposerAttachmentButton(
                    contentDescription = attachmentContentDescription(
                        preference = state.inferenceMode,
                        actualPlacement = activeRunPlacement,
                        actualReason = activeRunPlacementReason,
                    ),
                    enabled = attachmentEnabled,
                    onClick = onPickSharedAttachment,
                )
                ComposerTextInput(
                    modifier = Modifier.weight(1f),
                    input = input,
                    onInputChanged = onInputChanged,
                    inputEnabled = inputEnabled,
                    placeholder = placeholder,
                )
                ComposerVoiceButton(
                    enabled = voiceEnabled,
                    onClick = { showVoicePermissionDisclosure = true },
                )
                if (!compactControls) {
                    ComposerModelButton(
                        enabled = !state.isBusy,
                        onClick = onOpenModelManager,
                    )
                }
                ComposerSendButton(
                    actionIsStop = actionIsStop,
                    canSend = canSend,
                    onStopGeneration = onStopGeneration,
                    onSend = onSend,
                )
            }
        }
    }
    if (showVoicePermissionDisclosure) {
        VoiceInputPermissionDisclosureDialog(
            enabled = voiceEnabled,
            onConfirm = {
                showVoicePermissionDisclosure = false
                onStartVoiceInput()
            },
            onDismiss = {
                showVoicePermissionDisclosure = false
            },
        )
    }
}

@Composable
private fun VoiceInputPermissionDisclosureDialog(
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("voice_permission_disclosure_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(VOICE_INPUT_PERMISSION_DISCLOSURE_TITLE) },
        text = { Text(VOICE_INPUT_PERMISSION_DISCLOSURE_BODY) },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("voice_permission_consent_button"),
                enabled = enabled,
                onClick = onConfirm,
            ) {
                Text("同意并开启语音输入")
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("voice_permission_cancel_button"),
                onClick = onDismiss,
            ) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun ComposerAttachmentButton(
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ComposerIconButton(
        modifier = Modifier
            .testTag("composer_attachment_button")
            .semantics {
                this.contentDescription = contentDescription
            },
        enabled = enabled,
        onClick = onClick,
    ) {
        SolinGlyph(
            kind = SolinGlyphKind.Add,
            tint = LocalContentColor.current,
        )
    }
}

@Composable
private fun ComposerTextInput(
    input: String,
    onInputChanged: (String) -> Unit,
    inputEnabled: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        modifier = modifier
            .heightIn(min = 50.dp, max = 132.dp)
            .testTag("composer_input"),
        value = input,
        onValueChange = onInputChanged,
        enabled = inputEnabled,
        minLines = 1,
        maxLines = 5,
        placeholder = { Text(placeholder) },
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
            disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.96f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.70f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.32f),
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun ComposerVoiceButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ComposerIconButton(
        modifier = Modifier
            .testTag("composer_voice_button")
            .semantics {
                contentDescription = VOICE_INPUT_PRIVACY_DESCRIPTION
            },
        onClick = onClick,
        enabled = enabled,
    ) {
        SolinGlyph(
            kind = SolinGlyphKind.Voice,
            tint = LocalContentColor.current,
        )
    }
}

@Composable
private fun ComposerModelButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ComposerIconButton(
        modifier = Modifier
            .testTag("composer_model_button")
            .semantics {
                contentDescription = "模型管理"
            },
        onClick = onClick,
        enabled = enabled,
    ) {
        SolinGlyph(
            kind = SolinGlyphKind.Spark,
            tint = LocalContentColor.current,
        )
    }
}

@Composable
private fun ComposerSendButton(
    actionIsStop: Boolean,
    canSend: Boolean,
    onStopGeneration: () -> Unit,
    onSend: () -> Unit,
) {
    val semanticColors = LocalSolinColors.current
    IconButton(
        modifier = Modifier
            .height(46.dp)
            .width(46.dp)
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = 1.dp,
                color = if (canSend || actionIsStop) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.26f)
                },
                shape = MaterialTheme.shapes.medium,
            )
            .testTag("composer_send_button")
            .semantics {
                contentDescription = if (actionIsStop) "停止生成" else "发送"
            },
        onClick = when {
            actionIsStop -> onStopGeneration
            else -> onSend
        },
        enabled = when {
            actionIsStop -> true
            else -> canSend
        },
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = when {
                actionIsStop -> semanticColors.busy
                canSend -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = when {
                actionIsStop -> semanticColors.onBusy
                canSend -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f)
            },
        ),
    ) {
        SolinGlyph(
            kind = if (actionIsStop) SolinGlyphKind.Stop else SolinGlyphKind.Send,
            tint = LocalContentColor.current,
        )
    }
}

@Composable
private fun AttachmentProtectionNotice(notice: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote_attachment_protection_notice"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.24f),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            text = notice,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun attachmentProtectionNotice(
    preference: InferenceMode,
    actualPlacement: RunPlacement?,
    actualReason: PlacementReasonCode?,
): String? = when {
    actualPlacement == RunPlacement.Remote -> ACTUAL_REMOTE_ATTACHMENT_PROTECTION_NOTICE
    actualPlacement == RunPlacement.Local || actualReason != null -> null
    preference == InferenceMode.Remote -> REMOTE_ATTACHMENT_PROTECTION_NOTICE
    preference == InferenceMode.Auto -> AUTO_ATTACHMENT_PROTECTION_NOTICE
    else -> null
}

private fun attachmentContentDescription(
    preference: InferenceMode,
    actualPlacement: RunPlacement?,
    actualReason: PlacementReasonCode?,
): String = when {
    actualPlacement == RunPlacement.Remote ->
        "选择附件；本次使用远程模型，逐次确认后发送图片，其他附件不读取正文或 OCR"
    actualPlacement == RunPlacement.Local || actualReason != null -> "选择附件"
    preference == InferenceMode.Remote ->
        "选择附件；远程模式逐次确认后发送图片，其他附件不读取正文或 OCR"
    preference == InferenceMode.Auto ->
        "选择附件；自动偏好会在发送时决定位置，使用远程时逐次确认图片"
    else -> "选择附件"
}

@Composable
private fun VoiceInputPrivacyNotice() {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_privacy_notice"),
        text = VOICE_INPUT_PRIVACY_DESCRIPTION,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ComposerIconButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    IconButton(
        modifier = modifier
            .height(46.dp)
            .width(42.dp)
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
                shape = MaterialTheme.shapes.medium,
            ),
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.34f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        ),
    ) {
        content()
    }
}

@Composable
private fun PendingSharedInputStrip(
    summary: String,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pending_shared_input_strip"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.66f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SolinGlyph(
                modifier = Modifier.size(16.dp),
                kind = SolinGlyphKind.Add,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                modifier = Modifier.weight(1f),
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                modifier = Modifier
                    .size(36.dp)
                    .semantics { contentDescription = "移除附件" },
                onClick = onRemove,
            ) {
                SolinGlyph(
                    kind = SolinGlyphKind.Close,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current,
                )
            }
        }
    }
}

@Composable
private fun VoiceCaptureBar(
    isTranscribing: Boolean,
    level: Float,
    waveformLevels: List<Float>,
    partialText: String,
    onCancel: () -> Unit,
    onFinish: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "voice_wave")
    val pulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice_wave_pulse",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_capture_bar"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.66f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoiceWaveform(
                level = level,
                waveformLevels = waveformLevels,
                pulse = pulse,
            )
            Text(
                modifier = Modifier.weight(1f),
                text = partialText.ifBlank { if (isTranscribing) "正在转写" else "正在收音" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                modifier = Modifier
                    .size(38.dp)
                    .semantics { contentDescription = "取消语音输入" },
                onClick = onCancel,
            ) {
                SolinGlyph(
                    kind = SolinGlyphKind.Close,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current,
                )
            }
            if (!isTranscribing) {
                IconButton(
                    modifier = Modifier
                        .size(38.dp)
                    .semantics { contentDescription = "结束语音输入" },
                    onClick = onFinish,
                ) {
                    SolinGlyph(
                        kind = SolinGlyphKind.Check,
                        modifier = Modifier.size(18.dp),
                        tint = LocalContentColor.current,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceWaveform(
    level: Float,
    waveformLevels: List<Float>,
    pulse: Float,
) {
    val normalized = level.coerceIn(0.08f, 1f)
    val bars = waveformLevels.takeIf { it.isNotEmpty() } ?: VOICE_WAVEFORM_FALLBACK_BARS
    val activity = ((normalized - 0.08f) / 0.92f).coerceIn(0f, 1f)
    val pulseGain = 0.86f + pulse * (0.12f + activity * 0.18f)
    Row(
        modifier = Modifier.width(56.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bars.forEachIndexed { index, sample ->
            val sampleLevel = sample.coerceIn(0.08f, 1f)
            val animated = (sampleLevel * pulseGain * (0.94f + index * 0.015f)).coerceIn(0.12f, 1f)
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((7f + animated * 30f).dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private fun String.isVoiceStatusText(): Boolean =
    listOf("语音", "麦克风", "收音", "转写", "识别", "声音")
        .any { marker -> marker in this }

private fun String.isAgentExecutionOutcomeStatusText(): Boolean =
    listOf("工具未执行", "权限被拒", "特殊权限未开启", "屏幕截图同意已取消")
        .any { marker -> marker in this }

private val VOICE_WAVEFORM_FALLBACK_BARS =
    listOf(0.08f, 0.14f, 0.18f, 0.11f, 0.16f, 0.12f, 0.17f, 0.1f, 0.15f)
