package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.RecommendedModel
import com.bytedance.zgx.solin.ui.HOME_SUGGESTIONS
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_ARM64_REQUIRED_LINE
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_CANCEL_DOWNLOAD_ACTION
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_DOWNLOAD_ACTION
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_LOAD_ACTION
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_GREETING
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_IMPORT_CHOICE_LINE
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_IMPORT_CHOICE_TITLE
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_LOCAL_CHOICE_LINE
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_LOCAL_CHOICE_TITLE
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_MODEL_GATED_LABEL
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_MODEL_SELECTION_ROW
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_NOT_READY_LINE
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_NOT_READY_TITLE
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_PLACEMENT_AUTO
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_PLACEMENT_LOCAL
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_PLACEMENT_REMOTE
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_PRIVACY_LINK
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_REMOTE_CHOICE_LINE
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_REMOTE_CHOICE_TITLE
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_SKIP_ACTION
import com.bytedance.zgx.solin.ui.pendingSelectedChatDownloadBytes
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors

/**
 * Home screen: one placement decision before a model is ready, one greeting after.
 *
 * Safety: while nothing is ready this screen only offers choices. Every callback here is
 * user-initiated, so nothing reads phone content and no remote request is sent on its own.
 *
 * [onOpenModelManager] stays in the signature for the caller contract (SolinScreen.kt is owned
 * elsewhere). The model manager is reachable from the top bar, so the home screen does not link
 * to it again: two "模型管理" nodes on screen would make the shell's text assertions ambiguous.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun ChatEmptyState(
    state: ChatUiState,
    onOpenModelManager: () -> Unit,
    onOpenPrivacyNotice: () -> Unit,
    onOpenRemoteModelConfig: () -> Unit,
    onPickModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onLoadModel: () -> Unit,
    onSendPrompt: (String) -> Unit,
    onSetupModelToggled: (String, Boolean) -> Unit,
    onDownloadSetupModels: () -> Unit,
    onSkipFirstRunSetup: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp),
    ) {
        if (state.isReady) {
            ReadyHome(
                state = state,
                onSendPrompt = onSendPrompt,
                onOpenPrivacyNotice = onOpenPrivacyNotice,
            )
        } else {
            NotReadyHome(
                state = state,
                onOpenRemoteModelConfig = onOpenRemoteModelConfig,
                onPickModel = onPickModel,
                onDownloadModel = onDownloadModel,
                onCancelDownload = onCancelDownload,
                onLoadModel = onLoadModel,
                onSetupModelToggled = onSetupModelToggled,
                onDownloadSetupModels = onDownloadSetupModels,
                onSkipFirstRunSetup = onSkipFirstRunSetup,
                onOpenPrivacyNotice = onOpenPrivacyNotice,
            )
        }
    }
}

// ---- ready ---------------------------------------------------------------------------------

@Composable
private fun ReadyHome(
    state: ChatUiState,
    onSendPrompt: (String) -> Unit,
    onOpenPrivacyNotice: () -> Unit,
) {
    Text(
        modifier = Modifier.testTag("home_ready_greeting"),
        text = PRODUCT_HOME_GREETING,
        style = MaterialTheme.typography.headlineMedium,
    )
    Spacer(modifier = Modifier.height(10.dp))
    PlacementLine(
        leavesPhone = state.inferenceMode != InferenceMode.Local,
        text = when (state.inferenceMode) {
            InferenceMode.Local -> PRODUCT_HOME_PLACEMENT_LOCAL
            InferenceMode.Remote -> PRODUCT_HOME_PLACEMENT_REMOTE
            InferenceMode.Auto -> PRODUCT_HOME_PLACEMENT_AUTO
        },
    )
    Spacer(modifier = Modifier.height(20.dp))
    HOME_SUGGESTIONS.forEachIndexed { index, suggestion ->
        if (index > 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = !state.isBusy) { onSendPrompt(suggestion.text) }
                .padding(horizontal = 2.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = suggestion.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = suggestion.category,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    PrivacyNoticeLink(
        enabled = !state.isBusy,
        onClick = onOpenPrivacyNotice,
    )
}

@Composable
private fun PlacementLine(leavesPhone: Boolean, text: String) {
    val colors = LocalSolinColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlacementMark(leavesPhone = leavesPhone, modifier = Modifier.size(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (leavesPhone) colors.remote else colors.local,
        )
    }
}

@Composable
private fun PrivacyNoticeLink(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("home_privacy_notice_button"),
        onClick = onClick,
        enabled = enabled,
    ) {
        Text(
            text = PRODUCT_HOME_PRIVACY_LINK,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- not ready -----------------------------------------------------------------------------

@Composable
private fun NotReadyHome(
    state: ChatUiState,
    onOpenRemoteModelConfig: () -> Unit,
    onPickModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onLoadModel: () -> Unit,
    onSetupModelToggled: (String, Boolean) -> Unit,
    onDownloadSetupModels: () -> Unit,
    onSkipFirstRunSetup: () -> Unit,
    onOpenPrivacyNotice: () -> Unit,
) {
    Text(
        text = PRODUCT_HOME_NOT_READY_TITLE,
        style = MaterialTheme.typography.headlineMedium,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = PRODUCT_HOME_NOT_READY_LINE,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(20.dp))
    LocalChoiceCard(
        state = state,
        onDownloadModel = onDownloadModel,
        onCancelDownload = onCancelDownload,
        onLoadModel = onLoadModel,
        onSetupModelToggled = onSetupModelToggled,
        onDownloadSetupModels = onDownloadSetupModels,
        onSkipFirstRunSetup = onSkipFirstRunSetup,
    )
    Spacer(modifier = Modifier.height(12.dp))
    ChoiceCard(
        modifier = Modifier.testTag("home_choice_remote"),
        // The old "配置远程模型" button played this role; androidTest still looks for its tag.
        innerTag = "quick_remote_config_button",
        leavesPhone = true,
        title = PRODUCT_HOME_REMOTE_CHOICE_TITLE,
        line = PRODUCT_HOME_REMOTE_CHOICE_LINE,
        onClick = onOpenRemoteModelConfig,
    )
    Spacer(modifier = Modifier.height(12.dp))
    ChoiceCard(
        modifier = Modifier
            .testTag("home_choice_import")
            .dashedRoundedBorder(
                color = MaterialTheme.colorScheme.outlineVariant,
                cornerRadius = 20.dp,
            ),
        leavesPhone = null,
        title = PRODUCT_HOME_IMPORT_CHOICE_TITLE,
        line = PRODUCT_HOME_IMPORT_CHOICE_LINE,
        quiet = true,
        onClick = onPickModel,
    )
    Spacer(modifier = Modifier.height(16.dp))
    if (!state.isArm64Supported) {
        Text(
            text = PRODUCT_HOME_ARM64_REQUIRED_LINE,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
    modelStorageLine(state, state.homeDownloadBytes())?.let { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    PrivacyNoticeLink(
        enabled = !state.isBusy,
        onClick = onOpenPrivacyNotice,
    )
}

@Composable
private fun LocalChoiceCard(
    state: ChatUiState,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onLoadModel: () -> Unit,
    onSetupModelToggled: (String, Boolean) -> Unit,
    onDownloadSetupModels: () -> Unit,
    onSkipFirstRunSetup: () -> Unit,
) {
    val downloading = state.hasDownloadProgress()
    val chatModelInstalled = state.isModelInstalled(state.selectedRecommendedModel.id)
    val canLoadInstalled = state.homeOffersLoadModel()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_choice_local"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(width = 1.5.dp, color = MaterialTheme.colorScheme.primary),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("model_startup_banner")
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlacementMark(leavesPhone = false, modifier = Modifier.size(14.dp))
                Text(
                    text = PRODUCT_HOME_LOCAL_CHOICE_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
            Text(
                text = "${state.selectedRecommendedModel.shortName} · " +
                    ModelCatalog.formatBytes(state.selectedRecommendedModel.byteSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = PRODUCT_HOME_LOCAL_CHOICE_LINE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A failure, an authorization block or a load failure arrives as statusText; it must
            // stay visible even though the old status row is gone. The idle placeholder does not.
            if (state.statusText.isNotBlank() && state.statusText != IDLE_STATUS_TEXT) {
                Text(
                    text = state.statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (downloading) {
                Spacer(modifier = Modifier.height(6.dp))
                ProgressBlock(state)
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = onCancelDownload,
                ) {
                    Text(PRODUCT_HOME_CANCEL_DOWNLOAD_ACTION)
                }
            } else {
                if (state.showFirstRunSetup) {
                    ModelSelectionDisclosure(
                        state = state,
                        onSetupModelToggled = onSetupModelToggled,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                if (canLoadInstalled) {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("home_load_model_button"),
                        onClick = onLoadModel,
                        enabled = !state.isBusy,
                    ) {
                        Text(PRODUCT_HOME_LOAD_ACTION)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("first_run_download_button"),
                            onClick = if (state.showFirstRunSetup) {
                                onDownloadSetupModels
                            } else {
                                onDownloadModel
                            },
                            enabled = !state.isBusy &&
                                !state.isDownloading &&
                                state.isArm64Supported &&
                                !chatModelInstalled &&
                                (!state.showFirstRunSetup || state.selectedDownloadModelIds().isNotEmpty()),
                        ) {
                            Text(PRODUCT_HOME_DOWNLOAD_ACTION)
                        }
                        if (state.showFirstRunSetup) {
                            TextButton(
                                modifier = Modifier.heightIn(min = 48.dp),
                                onClick = onSkipFirstRunSetup,
                                enabled = !state.isBusy,
                            ) {
                                Text(
                                    text = PRODUCT_HOME_SKIP_ACTION,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The first-run model set, collapsed behind one row so the card keeps a single primary download
 * action (audit #12).
 */
@Composable
private fun ModelSelectionDisclosure(
    state: ChatUiState,
    onSetupModelToggled: (String, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("first_run_setup_panel"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = PRODUCT_HOME_MODEL_SELECTION_ROW,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            state.basicSetupModels.forEach { model ->
                SetupModelRow(
                    model = model,
                    state = state,
                    onSetupModelToggled = onSetupModelToggled,
                )
            }
        }
    }
}

@Composable
private fun SetupModelRow(
    model: RecommendedModel,
    state: ChatUiState,
    onSetupModelToggled: (String, Boolean) -> Unit,
) {
    // A model behind Hugging Face authorisation cannot download without a read token, so it stays
    // visibly gated, unchecked and disabled until one is saved (fail-closed, audit #13).
    val gated = model.requiresHuggingFaceAuthorization && !state.huggingFaceAccessTokenConfigured
    val installed = state.isModelInstalled(model.id)
    val selected = !gated && model.id in state.setupSelectedModelIds
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            modifier = Modifier.testTag("first_run_model_${model.id}"),
            checked = selected,
            enabled = !state.isBusy && !installed && !gated,
            onCheckedChange = { onSetupModelToggled(model.id, it) },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.shortName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = ModelCatalog.formatBytes(model.byteSize),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = when {
                installed -> "已安装"
                gated -> PRODUCT_HOME_MODEL_GATED_LABEL
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (installed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    line: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leavesPhone: Boolean?,
    quiet: Boolean = false,
    innerTag: String? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (quiet) {
            MaterialTheme.colorScheme.background
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        },
        border = if (quiet) {
            null
        } else {
            BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        },
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (innerTag != null) Modifier.testTag(innerTag) else Modifier)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leavesPhone != null) {
                    PlacementMark(leavesPhone = leavesPhone, modifier = Modifier.size(14.dp))
                }
                Text(
                    modifier = Modifier.weight(1f),
                    text = title,
                    style = if (quiet) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Modifier.dashedRoundedBorder(
    color: Color,
    cornerRadius: Dp,
    width: Dp = 1.dp,
): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(cornerRadius.toPx()),
        style = Stroke(
            width = width.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
        ),
    )
}

// The statusText a fresh ChatUiState carries; anything else is a real message worth showing.
private val IDLE_STATUS_TEXT = ChatUiState().statusText

/**
 * Bytes the download button on this card will fetch: the checked first-run models while the
 * setup is showing, otherwise the recommended chat model. Zero when nothing is left to fetch.
 */
private fun ChatUiState.homeDownloadBytes(): Long =
    if (showFirstRunSetup) {
        basicSetupModels
            .filter {
                it.id in setupSelectedModelIds &&
                    !isModelInstalled(it.id) &&
                    !(it.requiresHuggingFaceAuthorization && !huggingFaceAccessTokenConfigured)
            }
            .sumOf { it.byteSize }
    } else {
        pendingSelectedChatDownloadBytes()
    }

/** Models the first-run download would fetch, minus gated or already-installed ones. */
private fun ChatUiState.selectedDownloadModelIds(): Set<String> =
    basicSetupModels
        .filter {
            it.id in setupSelectedModelIds &&
                !isModelInstalled(it.id) &&
                !(it.requiresHuggingFaceAuthorization && !huggingFaceAccessTokenConfigured)
        }
        .map { it.id }
        .toSet()

/**
 * A local model file is on disk but not loaded (common right after an app restart). The home then
 * offers "加载模型" in place of a download button that would be disabled, so the user is not sent
 * to the model manager to find the only way forward.
 */
internal fun ChatUiState.homeOffersLoadModel(): Boolean =
    !hasDownloadProgress() && isLocalModelLoading()
