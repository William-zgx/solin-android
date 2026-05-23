package com.bytedance.zgx.gemmalocalqa

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bytedance.zgx.gemmalocalqa.ui.theme.GemmaLocalQATheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: GemmaChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GemmaLocalQATheme {
                val context = LocalContext.current
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                GemmaChatScreen(
                    state = state,
                    onImportModel = viewModel::importModel,
                    onDownloadModel = viewModel::startModelDownload,
                    onCancelDownload = viewModel::cancelModelDownload,
                    onLoadModel = viewModel::loadModel,
                    onBackendSelected = viewModel::selectBackend,
                    onResetConversation = viewModel::resetConversation,
                    onOpenModelPage = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(GEMMA_MODEL_REPOSITORY_URL)),
                        )
                    },
                    onSendMessage = viewModel::sendMessage,
                )
            }
        }
    }
}

@Composable
private fun GemmaChatScreen(
    state: ChatUiState,
    onImportModel: (Uri) -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onLoadModel: () -> Unit,
    onBackendSelected: (BackendChoice) -> Unit,
    onResetConversation: () -> Unit,
    onOpenModelPage: () -> Unit,
    onSendMessage: (String) -> Unit,
) {
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportModel)
    }
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    val lastMessageText = state.messages.lastOrNull()?.text.orEmpty()

    LaunchedEffect(state.messages.size, lastMessageText) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            Header(
                state = state,
                onLoadModel = onLoadModel,
                onBackendSelected = onBackendSelected,
                onResetConversation = onResetConversation,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (state.messages.isEmpty()) {
                    EmptyExperience(
                        state = state,
                        onPickModel = { pickModel.launch(arrayOf("*/*")) },
                        onDownloadModel = onDownloadModel,
                        onCancelDownload = onCancelDownload,
                        onLoadModel = onLoadModel,
                        onOpenModelPage = onOpenModelPage,
                        onSendPrompt = onSendMessage,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = state.messages,
                            key = { it.id },
                        ) { message ->
                            MessageBubble(message)
                        }
                    }
                }
            }

            Composer(
                input = input,
                enabled = state.isReady && !state.isBusy,
                isBusy = state.isBusy,
                onInputChanged = { input = it },
                onSend = {
                    val message = input
                    input = ""
                    onSendMessage(message)
                },
            )
        }
    }
}

@Composable
private fun Header(
    state: ChatUiState,
    onLoadModel: () -> Unit,
    onBackendSelected: (BackendChoice) -> Unit,
    onResetConversation: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF0F3D35),
                        Color(0xFF284B63),
                    ),
                ),
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Gemma 随身问答",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFDDEBE7),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(
                onClick = onResetConversation,
                enabled = state.isReady && !state.isBusy,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "新会话",
                    tint = if (state.isReady && !state.isBusy) Color.White else Color(0x88FFFFFF),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(
                text = if (state.isReady) "离线可用" else "模型待加载",
                active = state.isReady,
            )

            BackendChip(
                label = "GPU",
                selected = state.backend == BackendChoice.GPU,
                enabled = !state.isBusy,
                onClick = { onBackendSelected(BackendChoice.GPU) },
            )
            BackendChip(
                label = "CPU",
                selected = state.backend == BackendChoice.CPU,
                enabled = !state.isBusy,
                onClick = { onBackendSelected(BackendChoice.CPU) },
            )

            if (state.modelPath != null && !state.isReady && !state.isBusy) {
                AssistChip(
                    onClick = onLoadModel,
                    label = { Text("加载模型") },
                )
            }
        }

        state.modelPath?.let { path ->
            Text(
                text = File(path).name,
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFC9D8D4),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyExperience(
    state: ChatUiState,
    onPickModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onLoadModel: () -> Unit,
    onOpenModelPage: () -> Unit,
    onSendPrompt: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.isReady) {
            ReadyPanel(onSendPrompt)
        } else {
            SetupPanel(
                state = state,
                onPickModel = onPickModel,
                onDownloadModel = onDownloadModel,
                onCancelDownload = onCancelDownload,
                onLoadModel = onLoadModel,
                onOpenModelPage = onOpenModelPage,
            )
        }
    }
}

@Composable
private fun SetupPanel(
    state: ChatUiState,
    onPickModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onLoadModel: () -> Unit,
    onOpenModelPage: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "准备 Gemma 4 E2B",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "推荐模型约 2.6 GB。下载完成后会自动加载；已有 .litertlm 文件可以直接导入。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SetupStep("1", "获取模型", "使用 Wi-Fi 下载推荐模型，或导入手机里已有的 .litertlm 文件。")
                SetupStep("2", "自动加载", "优先使用 GPU；如果设备不支持，会自动切到 CPU。")
                SetupStep("3", "离线问答", "加载成功后不需要联网，问题和回答都留在本机。")
            }

            DeviceCheck(state)

            Text(
                text = "导入已有模型时会复制一份到 App 私有目录，请预留额外空间。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.isDownloading || state.downloadProgressPercent != null || state.totalBytes > 0L) {
                ProgressBlock(state)
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDownloadModel,
                    enabled = !state.isBusy && !state.isDownloading,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("下载推荐模型")
                }

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onPickModel,
                    enabled = !state.isBusy,
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("导入已有模型")
                }

                if (state.isDownloading) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCancelDownload,
                    ) {
                        Text("取消下载")
                    }
                } else if (state.modelPath != null && !state.isReady) {
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onLoadModel,
                        enabled = !state.isBusy,
                    ) {
                        Text("加载已导入模型")
                    }
                }

                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenModelPage,
                    enabled = !state.isBusy,
                ) {
                    Text("查看模型来源")
                }
            }
        }
    }
}

@Composable
private fun ReadyPanel(onSendPrompt: (String) -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "模型已就绪",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "试试这些问题，或者直接在下方输入。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val prompts = listOf(
                "用三句话解释什么是端侧大模型",
                "帮我写一段中文自我介绍",
                "把今天要做的事整理成清单",
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                prompts.forEach { prompt ->
                    AssistChip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onSendPrompt(prompt) },
                        label = {
                            Text(
                                text = prompt,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCheck(state: ChatUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "设备检查",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DeviceMetric(
                modifier = Modifier.weight(1f),
                label = "架构",
                value = if (state.isArm64Supported) "arm64" else "不支持",
                good = state.isArm64Supported,
            )
            DeviceMetric(
                modifier = Modifier.weight(1f),
                label = "空间",
                value = GemmaModelRules.formatBytes(state.availableModelStorageBytes),
                good = GemmaModelRules.hasEnoughSpace(state.availableModelStorageBytes),
            )
            DeviceMetric(
                modifier = Modifier.weight(1f),
                label = "模型",
                value = GemmaModelRules.formatBytes(GEMMA_RECOMMENDED_MODEL_BYTES),
                good = true,
            )
        }
        if (!state.isArm64Supported) {
            Text(
                text = "此模型需要 64 位 ARM 安卓设备。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (!GemmaModelRules.hasEnoughSpace(state.availableModelStorageBytes)) {
            Text(
                text = "建议至少预留 2.6 GB，再多留一些空间给加载缓存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DeviceMetric(
    modifier: Modifier,
    label: String,
    value: String,
    good: Boolean,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (good) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (good) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SetupStep(
    index: String,
    title: String,
    detail: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProgressBlock(state: ChatUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val progress = state.downloadProgressPercent
        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
            text = progress?.let { "$it%" } ?: GemmaModelRules.formatBytes(state.downloadedBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.totalBytes > 0L) {
            Text(
                text = "${GemmaModelRules.formatBytes(state.downloadedBytes)} / " +
                    GemmaModelRules.formatBytes(state.totalBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    active: Boolean,
) {
    val background = if (active) Color(0xFFBDEFE4) else Color(0x33FFFFFF)
    val foreground = if (active) Color(0xFF00201B) else Color.White
    Surface(
        shape = CircleShape,
        color = background,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
            maxLines = 1,
        )
    }
}

@Composable
private fun BackendChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.User
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (isUser) {
        RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp)
    } else {
        RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(shape)
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            text = message.text.ifBlank { "..." },
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
        )
    }
}

@Composable
private fun Composer(
    input: String,
    enabled: Boolean,
    isBusy: Boolean,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            value = input,
            onValueChange = onInputChanged,
            enabled = !isBusy,
            minLines = 1,
            maxLines = 5,
            placeholder = { Text("输入问题") },
        )

        Button(
            modifier = Modifier
                .height(56.dp)
                .width(56.dp),
            onClick = onSend,
            enabled = enabled && input.isNotBlank(),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
            )
        }
    }
}
