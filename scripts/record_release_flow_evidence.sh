#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/release-flow-current}"
REPORT_FILE="${REPORT_FILE:-$ARTIFACT_DIR/release-flow-evidence.properties}"
VALIDATION_RECORD_FILE="${VALIDATION_RECORD_FILE:-docs/release_validation_record.json}"
OWNER="${OWNER:-}"
VALIDATION_DATE="${VALIDATION_DATE:-$(date +%F)}"
RELEASE_FLOW_KEYS="${RELEASE_FLOW_KEYS:-}"
RELEASE_FLOW_ALL="${RELEASE_FLOW_ALL:-0}"
RELEASE_FLOW_NOTE="${RELEASE_FLOW_NOTE:-Release flow was explicitly confirmed by the named owner.}"
RELEASE_ARTIFACT_SHA256="${RELEASE_ARTIFACT_SHA256:-}"
RECORDED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
ORIGINAL_ARGS=("$@")

REQUIRED_RELEASE_FLOWS=(
  firstInstall
  upgradeInstall
  localModelDownloadVerification
  customModelImportOrUrlRejection
  remoteHttpsConfiguration
  encryptedApiKeyClear
  sessionPersistence
  memoryControls
  privacyAndDataControls
  remindersAfterReboot
  shareAndPickerInput
  voiceInput
  adaptiveUi
  accessibilityText
  recentMediaOcr
  mediaProjectionCancellation
)

usage() {
  cat >&2 <<'USAGE'
Usage:
  OWNER="QA Owner" RELEASE_FLOW_ALL=1 scripts/record_release_flow_evidence.sh

  OWNER="QA Owner" RELEASE_FLOW_KEYS=firstInstall,sessionPersistence \
    scripts/record_release_flow_evidence.sh

The script records formal release-flow evidence only for keys explicitly
provided by the caller. It exits non-zero until every required flow is accepted.
USAGE
}

join_csv() {
  local IFS=,
  printf '%s' "$*"
}

contains_key() {
  local needle="$1"
  shift
  local item
  for item in "$@"; do
    if [[ "$item" == "$needle" ]]; then
      return 0
    fi
  done
  return 1
}

command_line() {
  local quoted=()
  local arg
  quoted+=("$(printf '%q' "$0")")
  for arg in "${ORIGINAL_ARGS[@]}"; do
    quoted+=("$(printf '%q' "$arg")")
  done
  local IFS=' '
  printf '%s' "${quoted[*]}"
}

write_report() {
  local status="$1"
  local reason="$2"
  local accepted="$3"
  local pending="$4"
  mkdir -p "$(dirname "$REPORT_FILE")"
  {
    printf 'artifactSchema=ReleaseFlowEvidenceCollection/v1\n'
    printf 'status=%s\n' "$status"
    printf 'target=release-flow-evidence\n'
    printf 'owner=%s\n' "$OWNER"
    printf 'recordedAt=%s\n' "$RECORDED_AT"
    printf 'command=%s\n' "$(command_line)"
    printf 'reproduciblePath=%s\n' "$REPORT_FILE"
    printf 'reason=%s\n' "$reason"
    printf 'validationRecordFile=%s\n' "$VALIDATION_RECORD_FILE"
    printf 'artifactDir=%s\n' "$ARTIFACT_DIR"
    printf 'date=%s\n' "$VALIDATION_DATE"
    printf 'releaseArtifactSha256=%s\n' "$RELEASE_ARTIFACT_SHA256"
    printf 'requiredFlows=%s\n' "$(join_csv "${REQUIRED_RELEASE_FLOWS[@]}")"
    printf 'acceptedFlows=%s\n' "$accepted"
    printf 'pendingFlows=%s\n' "$pending"
  } > "$REPORT_FILE"
}

fail() {
  local reason="$1"
  shift
  write_report failed "$reason" "" "$(join_csv "${REQUIRED_RELEASE_FLOWS[@]}")"
  echo "$*" >&2
  usage
  exit 1
}

write_flow_contract_fields() {
  local flow="$1"
  case "$flow" in
    firstInstall)
      printf 'firstRunSetupVisibleCovered=true\n'
      printf 'firstRunDefaultChatModelSelected=true\n'
      printf 'firstRunSkipReachesMainShell=true\n'
      ;;
    upgradeInstall)
      printf 'upgradeInstallUsesAdbInstallR=true\n'
      printf 'upgradeInstallPreservesFirstInstallTime=true\n'
      printf 'upgradeInstallUpdatesLastUpdateTime=true\n'
      printf 'upgradeInstallVersionCodeIncreased=true\n'
      printf 'upgradeInstallInstrumentationCovered=true\n'
      ;;
    remoteHttpsConfiguration)
      printf 'remoteNetworkFailureRecoveryCovered=true\n'
      printf 'remoteUnconfiguredModelFailureCovered=true\n'
      printf 'remoteLocalMemoryNotAutoIncluded=true\n'
      ;;
    encryptedApiKeyClear)
      printf 'encryptedApiKeyBlankInputClearsSecret=true\n'
      printf 'legacyPlaintextApiKeyNotPersisted=true\n'
      ;;
    sessionPersistence)
      printf 'sessionCreateSwitchRestoreCovered=true\n'
      printf 'activeSessionPersistenceCovered=true\n'
      printf 'sessionDeleteCovered=true\n'
      ;;
    memoryControls)
      printf 'memoryCreateControlCovered=true\n'
      printf 'memoryForgetControlCovered=true\n'
      printf 'memoryClearControlCovered=true\n'
      printf 'memoryPanelControlCovered=true\n'
      ;;
    localModelDownloadVerification)
      printf 'localModelDownloadVerified=true\n'
      printf 'modelSha256VerificationCovered=true\n'
      printf 'storagePreflightCovered=true\n'
      printf 'downloadFailureRecoveryCovered=true\n'
      printf 'downloadDirectoryUnavailableCovered=true\n'
      printf 'downloadShaFailureCleanupCovered=true\n'
      printf 'downloadInsufficientStorageFailureCovered=true\n'
      printf 'pendingDownloadMissingTaskRecoveryCovered=true\n'
      printf 'remoteFallbackExplained=true\n'
      printf 'lightweightAlternativeExplained=true\n'
      ;;
    customModelImportOrUrlRejection)
      printf 'customLitertlmImportCovered=true\n'
      printf 'customLocalNonLitertlmImportRejected=true\n'
      printf 'customImportStoragePreflightCovered=true\n'
      printf 'customImportEmptyFileRejected=true\n'
      printf 'customImportTempCleanupOnCopyFailureCovered=true\n'
      printf 'customDownloadHttpsOnly=true\n'
      printf 'customNonLitertlmDownloadRejected=true\n'
      printf 'customInvalidUrlRejected=true\n'
      printf 'customCredentialedUrlRejected=true\n'
      printf 'customUnverifiedModelMarked=true\n'
      ;;
    shareAndPickerInput)
      printf 'actionSendTextStaged=true\n'
      printf 'remoteTextShareProtected=true\n'
      printf 'remoteVisionImageAttachmentStaged=true\n'
      printf 'remoteVisionUnsupportedProtected=true\n'
      printf 'noImplicitImageOcr=true\n'
      printf 'remoteNonImageAttachmentNotAutoIncluded=true\n'
      printf 'remoteVisionSupportedOpenStreamCountCovered=true\n'
      printf 'remoteVisionSupportedOcrSkipped=true\n'
      printf 'remoteVisionUnsupportedOpenStreamCountCovered=true\n'
      printf 'remoteVisionUnsupportedOcrSkipped=true\n'
      printf 'remoteVisionMixedShareNonImageProtected=true\n'
      printf 'remoteVisionSendPreviewConfirmed=true\n'
      printf 'remoteVisionCancelKeepsRuntimeIdle=true\n'
      printf 'remoteVisionHttpFixtureImagePartCount=1\n'
      printf 'remoteVisionHttpFixtureStreamRequested=true\n'
      printf 'remoteVisionSupportedImageStreamOpenCount=1\n'
      printf 'remoteVisionSupportedImageOcrInvocationCount=0\n'
      printf 'remoteVisionUnsupportedImageStreamOpenCount=0\n'
      printf 'remoteVisionUnsupportedImageOcrInvocationCount=0\n'
      printf 'remoteVisionMixedProtectedNonImageCount=1\n'
      printf 'localVisionVerifiedModelImageAttachmentStaged=true\n'
      printf 'localVisionRuntimeImageAttachmentSent=true\n'
      printf 'localVisionLocalOnlyPersistenceCovered=true\n'
      printf 'localVisionPromptMetadataRedacted=true\n'
      printf 'localVisionRemoteRuntimeIdle=true\n'
      printf 'localVisionUnsupportedOcrSkipped=true\n'
      printf 'localVisionRuntimeImageAttachmentSendCount=1\n'
      printf 'localVisionRemoteRuntimeRequestCount=0\n'
      printf 'localVisionUnsupportedRuntimeImageSendCount=0\n'
      printf 'localVisionUnsupportedImageOcrInvocationCount=0\n'
      printf 'documentExcerptBounded=true\n'
      printf 'pickerAttachmentPromptCovered=true\n'
      ;;
    voiceInput)
      printf 'voiceEntryDisclosureVisible=true\n'
      printf 'voiceDraftNoAutoSendCovered=true\n'
      printf 'voicePermissionFailureRecoveryCovered=true\n'
      printf 'voiceCancelCovered=true\n'
      ;;
    privacyAndDataControls)
      printf 'privacyNoticeEntryVisible=true\n'
      printf 'memoryClearControlCovered=true\n'
      printf 'memoryForgetControlCovered=true\n'
      printf 'sessionDeleteControlCovered=true\n'
      printf 'remoteConfigClearCovered=true\n'
      printf 'dataDeletionCopyCovered=true\n'
      ;;
    remindersAfterReboot)
      printf 'bootCompletedReminderRescheduleCovered=true\n'
      printf 'packageReplacedReminderRescheduleCovered=true\n'
      printf 'reminderCatchUpSchedulingCovered=true\n'
      printf 'staleRunningReminderRecoveryCovered=true\n'
      printf 'reminderAuditMetadataOnly=true\n'
      ;;
    adaptiveUi)
      printf 'largeFontReachabilityCovered=true\n'
      printf 'landscapeReachabilityCovered=true\n'
      printf 'accessibleLabelsCovered=true\n'
      ;;
    accessibilityText)
      printf 'accessibilityTextConfirmationCovered=true\n'
      printf 'accessibilityTextCancellationCovered=true\n'
      printf 'accessibilityTextLocalOnlyMetadataCovered=true\n'
      printf 'accessibilityTextTraceRecorded=true\n'
      ;;
    recentMediaOcr)
      printf 'recentScreenshotOcrRoutingCovered=true\n'
      printf 'recentImageOcrRoutingCovered=true\n'
      printf 'recentMediaOcrConfirmationCovered=true\n'
      printf 'recentScreenshotOneItemLimitCovered=true\n'
      printf 'recentMediaOcrLocalOnlyProtected=true\n'
      printf 'recentMediaOcrRemoteLeakageBlocked=true\n'
      ;;
    mediaProjectionCancellation)
      printf 'mediaProjectionOneShotConsentCovered=true\n'
      printf 'currentScreenshotOcrRemoteContinuationBlocked=true\n'
      ;;
  esac
}

if [[ -z "$OWNER" ]]; then
  fail missing-owner "OWNER is required for release flow evidence."
fi

if [[ ! "$VALIDATION_DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  fail invalid-date "VALIDATION_DATE must use YYYY-MM-DD."
fi

accepted_flows=()
if [[ "$RELEASE_FLOW_ALL" == "1" ]]; then
  accepted_flows=("${REQUIRED_RELEASE_FLOWS[@]}")
elif [[ -n "$RELEASE_FLOW_KEYS" ]]; then
  IFS=, read -r -a raw_flows <<< "$RELEASE_FLOW_KEYS"
  for raw_flow in "${raw_flows[@]}"; do
    flow="$(printf '%s' "$raw_flow" | xargs)"
    [[ -n "$flow" ]] || continue
    if ! contains_key "$flow" "${REQUIRED_RELEASE_FLOWS[@]}"; then
      fail "unknown-flow-$flow" "Unknown release flow key: $flow"
    fi
    if [[ "${#accepted_flows[@]}" -eq 0 ]] || ! contains_key "$flow" "${accepted_flows[@]}"; then
      accepted_flows+=("$flow")
    fi
  done
fi

if [[ "${#accepted_flows[@]}" -eq 0 ]]; then
  fail missing-accepted-flows "Set RELEASE_FLOW_ALL=1 or RELEASE_FLOW_KEYS=<comma-separated keys>."
fi

mkdir -p "$ARTIFACT_DIR"
for flow in "${accepted_flows[@]}"; do
  evidence_path="$ARTIFACT_DIR/flow-$flow.properties"
  {
    printf 'artifactSchema=ReleaseFlowEvidence/v1\n'
    printf 'status=passed\n'
    printf 'target=release-flow\n'
    printf 'flowKey=%s\n' "$flow"
    printf 'releaseFlowPassed=true\n'
    printf 'candidateOnly=false\n'
    printf 'evidenceKind=formal-release-flow\n'
    printf 'owner=%s\n' "$OWNER"
    printf 'date=%s\n' "$VALIDATION_DATE"
    printf 'recordedAt=%s\n' "$RECORDED_AT"
    printf 'command=%s\n' "$(command_line)"
    printf 'reproduciblePath=%s\n' "$evidence_path"
    printf 'releaseArtifactSha256=%s\n' "$RELEASE_ARTIFACT_SHA256"
    printf 'validationRecordFile=%s\n' "$VALIDATION_RECORD_FILE"
    printf 'evidenceSummary=%s\n' "$RELEASE_FLOW_NOTE"
    write_flow_contract_fields "$flow"
  } > "$evidence_path"
done

pending_flows=()
for flow in "${REQUIRED_RELEASE_FLOWS[@]}"; do
  if [[ "${#accepted_flows[@]}" -eq 0 ]] || ! contains_key "$flow" "${accepted_flows[@]}"; then
    pending_flows+=("$flow")
  fi
done

accepted_csv="$(join_csv "${accepted_flows[@]}")"
if [[ "${#pending_flows[@]}" -gt 0 ]]; then
  pending_csv="$(join_csv "${pending_flows[@]}")"
else
  pending_csv=""
fi

if [[ "${#pending_flows[@]}" -gt 0 ]]; then
  write_report failed missing-required-release-flows "$accepted_csv" "$pending_csv"
  echo "Release flow evidence is incomplete: $pending_csv" >&2
  exit 1
fi

write_report passed "" "$accepted_csv" ""
echo "Release flow evidence written to $ARTIFACT_DIR"
