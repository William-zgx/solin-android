package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.PendingExternalOutcomeConfirmation
import com.bytedance.zgx.solin.PendingRemoteModeDisclosure
import com.bytedance.zgx.solin.RuntimePermissionRequirement
import com.bytedance.zgx.solin.SpecialAccessRequirement
import com.bytedance.zgx.solin.orchestration.AgentExternalOutcome
import kotlinx.coroutines.launch

/**
 * Hosts the modal sheets driven by pending decisions in [ChatUiState]: the remote-mode and
 * remote-send privacy disclosures, the device-action confirmation draft, and the external-outcome
 * record. Each sheet is shown while its pending value is non-null and is cleared only by the
 * callbacks it forwards.
 *
 * Every sheet owns its own [rememberModalBottomSheetState]: two sheets sharing one state would
 * animate each other's visibility, so closing one could leave the other stuck.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SolinSheetHost(
    state: ChatUiState,
    onDismissRemoteModeDisclosure: () -> Unit,
    onConfirmRemoteModeDisclosure: (PendingRemoteModeDisclosure) -> Unit,
    onConfirmRemoteSendDisclosure: (Boolean) -> Unit,
    onConfirmRemoteSendWithMasking: () -> Unit,
    onConfirmRemoteSendDespiteSensitive: () -> Unit,
    onDismissRemoteSendDisclosure: () -> Unit,
    // Resolved by SolinScreen's caller, which owns the module-aware tool registry; see SolinScreen.
    resolveRuntimePermissionRequirements: (PendingAgentConfirmation) -> List<RuntimePermissionRequirement>,
    resolveSpecialAccessRequirements: (PendingAgentConfirmation) -> List<SpecialAccessRequirement>,
    onOpenSpecialAccessSettings: (SpecialAccessRequirement) -> Unit,
    onConfirmAgentConfirmation: (PendingAgentConfirmation) -> Unit,
    onDismissAgentConfirmation: (PendingAgentConfirmation?) -> Unit,
    onRecordExternalOutcome: (PendingExternalOutcomeConfirmation, AgentExternalOutcome) -> Unit,
) {
    state.pendingRemoteModeDisclosure?.let { disclosure ->
        ModalBottomSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismissRequest = onDismissRemoteModeDisclosure,
        ) {
            RemoteModeDisclosureSheet(
                disclosure = disclosure,
                onConfirm = { onConfirmRemoteModeDisclosure(disclosure) },
                onDismiss = onDismissRemoteModeDisclosure,
            )
        }
    }

    state.pendingRemoteSendDisclosure?.let { disclosure ->
        ModalBottomSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismissRequest = onDismissRemoteSendDisclosure,
        ) {
            RemoteSendDisclosureSheet(
                disclosure = disclosure,
                disclosurePolicy = state.remoteSendDisclosurePolicy,
                onConfirm = onConfirmRemoteSendDisclosure,
                onMaskAndSend = onConfirmRemoteSendWithMasking,
                onSendAnyway = onConfirmRemoteSendDespiteSensitive,
                onDismiss = onDismissRemoteSendDisclosure,
            )
        }
    }

    state.pendingConfirmation?.let { confirmation ->
        // Keyed on the resolvers too, so a new registry-backed resolver can never be served a
        // stale (possibly understated) requirement list.
        val runtimePermissionRequirements = remember(confirmation, resolveRuntimePermissionRequirements) {
            resolveRuntimePermissionRequirements(confirmation)
        }
        val specialAccessRequirements = remember(confirmation, resolveSpecialAccessRequirements) {
            resolveSpecialAccessRequirements(confirmation)
        }
        ModalBottomSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismissRequest = { onDismissAgentConfirmation(confirmation) },
        ) {
            ActionDraftSheet(
                confirmation = confirmation,
                grantedSpecialAccessIds = state.grantedSpecialAccessIds,
                runtimePermissionRequirements = runtimePermissionRequirements,
                specialAccessRequirements = specialAccessRequirements,
                onOpenSpecialAccessSettings = onOpenSpecialAccessSettings,
                onConfirm = { onConfirmAgentConfirmation(confirmation) },
                onDismiss = { onDismissAgentConfirmation(confirmation) },
            )
        }
    }

    state.pendingExternalOutcome?.let { pendingOutcome ->
        val sheetState = rememberModalBottomSheetState(
            // The outcome must be recorded explicitly, so the sheet refuses to hide: an empty
            // onDismissRequest alone would let a swipe hide it while it stays in composition.
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden },
        )
        val scope = rememberCoroutineScope()
        var showOutcomeHint by rememberSaveable(pendingOutcome.requestId) { mutableStateOf(false) }
        ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = {
                // Back / scrim / swipe cannot dismiss this sheet; say why instead of swallowing
                // the gesture. Nudge the sheet back to its expanded stop so it stays readable.
                showOutcomeHint = true
                scope.launch { sheetState.expand() }
            },
        ) {
            Column {
                if (showOutcomeHint) {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                            .testTag("external_outcome_hint"),
                        text = EXTERNAL_OUTCOME_REQUIRED_HINT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ExternalOutcomeSheet(
                    pending = pendingOutcome,
                    onRecord = { outcome ->
                        showOutcomeHint = false
                        onRecordExternalOutcome(pendingOutcome, outcome)
                    },
                )
            }
        }
    }
}

/** Shown when the user tries to dismiss the external-outcome sheet without choosing a result. */
internal const val EXTERNAL_OUTCOME_REQUIRED_HINT = "请先选择结果"
