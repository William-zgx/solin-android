package com.bytedance.zgx.solin.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.bytedance.zgx.solin.ui.VOICE_INPUT_PRIVACY_DESCRIPTION
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors

internal const val MID_RUN_STEERING_COMPOSER_HINT = "运行中发送将插话调整"

/** Shown above the field when no model can answer yet; the send button stays disabled. */
internal const val COMPOSER_NOT_READY_HINT = "先下载本地模型或连接远程模型"

/** The field's own prompt while no model is ready; the hint line above says what to do. */
internal const val NOT_READY_PLACEHOLDER = "配置远程或下载本地后提问"

internal const val VOICE_CONSENT_TITLE = "开启语音输入"

/**
 * Four facts, one short paragraph: the system speech service does the work, no audio is stored,
 * the transcript only fills the input box, and nothing is sent automatically.
 */
internal const val VOICE_CONSENT_BODY =
    "由系统语音识别服务处理，Solin 不保存音频；转写只填入输入框，不会自动发送；确认后才请求麦克风权限。"

/** Input/send-button state of the composer, including mid-run steering. */
internal data class ComposerActionState(
    val inputEnabled: Boolean,
    val canSend: Boolean,
    /** The stop button is shown whenever a generation is in flight, even next to a steer send. */
    val showStop: Boolean,
    /** The send button is hidden only while a run is showing stop and there is nothing to send. */
    val showSend: Boolean,
    val placeholder: String,
)

/**
 * While a run is active and steering is available the input stays enabled: non-blank text sends a
 * steer/queue message (see ChatMidRunSteering) *and* the stop button stays on screen next to it,
 * so stopping never requires clearing the text. Blank input keeps only stop. Shared-input drafts
 * are never sent mid-run, and nothing is sent before a model is ready.
 */
internal fun composerActionState(state: ChatUiState, input: String): ComposerActionState {
    val hasPendingSharedInput = state.pendingSharedInputDraft != null
    val steering = state.isBusy && state.midRunSteeringAvailable && !hasPendingSharedInput
    val inputEnabled = !state.isBusy || steering
    val canSend = when {
        !state.isReady && !state.isBusy -> false
        steering -> input.isNotBlank()
        else -> inputEnabled && (input.isNotBlank() || hasPendingSharedInput)
    }
    val placeholder = when {
        steering && state.pendingSteerMessageCount > 0 ->
            "已插话 ${state.pendingSteerMessageCount} 条 · $MID_RUN_STEERING_COMPOSER_HINT"
        steering -> MID_RUN_STEERING_COMPOSER_HINT
        state.isBusy -> state.statusText
        !state.isReady -> NOT_READY_PLACEHOLDER
        hasPendingSharedInput -> "补充说明"
        else -> "输入问题"
    }
    return ComposerActionState(
        inputEnabled = inputEnabled,
        canSend = canSend,
        showStop = state.isGenerating,
        showSend = !state.isGenerating || canSend,
        placeholder = placeholder,
    )
}

/** One short notice line above the composer; [fullText] keeps the complete wording for a11y. */
internal data class ComposerNotice(
    val text: String,
    val fullText: String,
)

/**
 * The single notice line the composer may show. It always says that an image is confirmed before
 * it leaves the phone and that other attachments are not sent along; the long form stays in the
 * content description and in the privacy notice page.
 */
internal fun composerNotice(
    preference: InferenceMode,
    actualPlacement: RunPlacement?,
    actualReason: PlacementReasonCode?,
): ComposerNotice? {
    val fullText = attachmentProtectionNotice(preference, actualPlacement, actualReason) ?: return null
    // Both safety facts survive in one line: an image is confirmed before it leaves the phone,
    // and the other attachments are not sent along.
    val shortText = when {
        actualPlacement == RunPlacement.Remote -> "本次远程：图片发送前确认，其他附件不发"
        preference == InferenceMode.Remote -> "远程：图片发送前确认，其他附件不发"
        else -> "自动：远程时图片发送前确认，其他附件不发"
    }
    return ComposerNotice(text = shortText, fullText = fullText)
}

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
) {
    val attachmentEnabled = !state.isBusy
    val voiceEnabled = !state.isBusy && !state.voiceCapture.isActive
    var showVoicePermissionDisclosure by rememberSaveable { mutableStateOf(false) }
    val composerAction = composerActionState(state, input)
    val showNotReadyHint = !state.isReady && !state.isBusy
    // app_status_text is part of the verification contract (other androidTests assert it), so it
    // stays even when the busy text also appears as the field's placeholder.
    val showCompactStatus = state.isBusy ||
        state.statusText.isVoiceStatusText() ||
        state.statusText.isAgentExecutionOutcomeStatusText()
    val attachmentNotice = composerNotice(
        preference = state.inferenceMode,
        actualPlacement = state.activeRunPlacement,
        actualReason = state.activeRunPlacementReason,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
        // One notice line at most: while voice capture is running its own privacy line stands in,
        // and attaching is disabled then, so the attachment warning comes back afterwards.
        if (attachmentNotice != null && !state.voiceCapture.isActive) {
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
        ComposerContainer {
            if (showNotReadyHint) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, bottom = 2.dp)
                        .testTag("composer_not_ready_hint"),
                    text = COMPOSER_NOT_READY_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ComposerTextInput(
                input = input,
                onInputChanged = onInputChanged,
                inputEnabled = composerAction.inputEnabled,
                placeholder = composerAction.placeholder,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ComposerAttachmentButton(
                    contentDescription = attachmentContentDescription(
                        preference = state.inferenceMode,
                        actualPlacement = state.activeRunPlacement,
                        actualReason = state.activeRunPlacementReason,
                    ),
                    enabled = attachmentEnabled,
                    onClick = onPickSharedAttachment,
                )
                ComposerVoiceButton(
                    enabled = voiceEnabled,
                    onClick = { showVoicePermissionDisclosure = true },
                )
                Spacer(modifier = Modifier.weight(1f))
                ComposerPlacementPill(
                    mode = state.inferenceMode,
                    onClick = if (state.isBusy) null else onOpenModelManager,
                )
                if (composerAction.showStop) {
                    ComposerStopButton(onStopGeneration = onStopGeneration)
                }
                if (composerAction.showSend) {
                    ComposerSendButton(
                        canSend = composerAction.canSend,
                        onSend = onSend,
                    )
                }
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

/** The single rounded composer surface: text on top, tools below, nothing else. */
@Composable
private fun ComposerContainer(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            content()
        }
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
        title = { Text(VOICE_CONSENT_TITLE) },
        text = { Text(VOICE_CONSENT_BODY) },
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
    // Borderless, transparent field: the composer container is the only frame around the text.
    OutlinedTextField(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp, max = 132.dp)
            .testTag("composer_input"),
        value = input,
        onValueChange = onInputChanged,
        enabled = inputEnabled,
        minLines = 1,
        maxLines = 5,
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * The placement pill doubles as the model switch: it shows where the next message runs (本机 /
 * 远程 / 自动) and opens the model manager when tapped.
 */
@Composable
private fun ComposerPlacementPill(
    mode: InferenceMode,
    onClick: (() -> Unit)?,
) {
    PlacementPill(
        label = when (mode) {
            InferenceMode.Local -> "本机"
            InferenceMode.Auto -> "自动"
            InferenceMode.Remote -> "远程"
        },
        leavesPhone = when (mode) {
            InferenceMode.Local -> false
            InferenceMode.Remote -> true
            InferenceMode.Auto -> null
        },
        modifier = Modifier
            .testTag("composer_model_button")
            .semantics { contentDescription = "模型管理" },
        onClick = onClick,
    )
}

@Composable
private fun ComposerSendButton(
    canSend: Boolean,
    onSend: () -> Unit,
) {
    ComposerRoundButton(
        modifier = Modifier
            .testTag("composer_send_button")
            .semantics { contentDescription = "发送" },
        enabled = canSend,
        containerColor = if (canSend) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (canSend) {
            MaterialTheme.colorScheme.background
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        },
        onClick = onSend,
    ) {
        SolinGlyph(
            kind = SolinGlyphKind.Send,
            modifier = Modifier.size(18.dp),
            tint = LocalContentColor.current,
        )
    }
}

@Composable
private fun ComposerStopButton(onStopGeneration: () -> Unit) {
    val semanticColors = LocalSolinColors.current
    ComposerRoundButton(
        modifier = Modifier
            .testTag("composer_stop_button")
            .semantics { contentDescription = "停止生成" },
        enabled = true,
        containerColor = semanticColors.busy,
        contentColor = semanticColors.onBusy,
        onClick = onStopGeneration,
    ) {
        SolinGlyph(
            kind = SolinGlyphKind.Stop,
            modifier = Modifier.size(18.dp),
            tint = LocalContentColor.current,
        )
    }
}

/** A 40dp circle drawn inside a 48dp touch target. */
@Composable
private fun ComposerRoundButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    IconButton(
        modifier = modifier.size(48.dp),
        onClick = onClick,
        enabled = enabled,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                content()
            }
        }
    }
}

@Composable
private fun AttachmentProtectionNotice(notice: ComposerNotice) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .testTag("remote_attachment_protection_notice")
            .semantics { contentDescription = notice.fullText },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlacementMark(
            leavesPhone = true,
            modifier = Modifier.size(10.dp),
            tint = LocalSolinColors.current.remote,
        )
        Text(
            text = notice.text,
            style = MaterialTheme.typography.labelSmall,
            color = LocalSolinColors.current.remote,
            maxLines = 1,
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
        maxLines = 1,
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
        modifier = modifier.size(48.dp),
        onClick = onClick,
        enabled = enabled,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        ) {
            content()
        }
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
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 2.dp, end = 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SolinGlyph(
                modifier = Modifier.size(16.dp),
                kind = SolinGlyphKind.Add,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                modifier = Modifier.weight(1f),
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                modifier = Modifier
                    .size(48.dp)
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
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 2.dp, end = 2.dp, bottom = 2.dp),
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                modifier = Modifier
                    .size(48.dp)
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
                        .size(48.dp)
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
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
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
