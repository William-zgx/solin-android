package com.bytedance.zgx.solin.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.PendingExternalOutcomeConfirmation
import com.bytedance.zgx.solin.PendingRemoteModeDisclosure
import com.bytedance.zgx.solin.RuntimePermissionRequirement
import com.bytedance.zgx.solin.SpecialAccessRequirement
import com.bytedance.zgx.solin.orchestration.AgentExternalOutcome

/**
 * Hosts the modal sheets driven by pending decisions in [ChatUiState]: the remote-mode and
 * remote-send privacy disclosures, the device-action confirmation draft, and the external-outcome
 * record. Each sheet is shown while its pending value is non-null and is cleared only by the
 * callbacks it forwards, so the ordering and dismissal semantics match the original inline host
 * in SolinScreen.
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
        ModalBottomSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismissRequest = { onDismissAgentConfirmation(confirmation) },
        ) {
            ActionDraftSheet(
                confirmation = confirmation,
                grantedSpecialAccessIds = state.grantedSpecialAccessIds,
                runtimePermissionRequirements = resolveRuntimePermissionRequirements(confirmation),
                specialAccessRequirements = resolveSpecialAccessRequirements(confirmation),
                onOpenSpecialAccessSettings = onOpenSpecialAccessSettings,
                onConfirm = { onConfirmAgentConfirmation(confirmation) },
                onDismiss = { onDismissAgentConfirmation(confirmation) },
            )
        }
    }

    state.pendingExternalOutcome?.let { pendingOutcome ->
        ModalBottomSheet(
            // The outcome must be recorded explicitly, so the sheet refuses to hide: an empty
            // onDismissRequest alone would let a swipe hide it while it stays in composition.
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden },
            ),
            onDismissRequest = {},
        ) {
            ExternalOutcomeSheet(
                pending = pendingOutcome,
                onRecord = { outcome -> onRecordExternalOutcome(pendingOutcome, outcome) },
            )
        }
    }
}
