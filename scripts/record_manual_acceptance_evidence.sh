#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/manual-acceptance-current}"
REPORT_FILE="${REPORT_FILE:-$ARTIFACT_DIR/manual-acceptance-evidence.properties}"
VALIDATION_RECORD_FILE="${VALIDATION_RECORD_FILE:-docs/release_validation_record.json}"
OWNER="${OWNER:-}"
VALIDATION_DATE="${VALIDATION_DATE:-$(date +%F)}"
MANUAL_ACCEPTANCE_KEYS="${MANUAL_ACCEPTANCE_KEYS:-}"
MANUAL_ACCEPTANCE_ALL="${MANUAL_ACCEPTANCE_ALL:-0}"
MANUAL_ACCEPTANCE_NOTE="${MANUAL_ACCEPTANCE_NOTE:-Manual acceptance was explicitly confirmed by the named owner.}"
RELEASE_ARTIFACT_SHA256="${RELEASE_ARTIFACT_SHA256:-}"
RECORDED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
ORIGINAL_ARGS=("$@")

REQUIRED_MANUAL_KEYS=(
  modelSetup
  remoteModePrivacy
  toolConfirmation
  permissions
  backgroundReminders
  sharing
  multimodalEntryPoints
  voiceInput
  filePicker
  mediaProjection
  remoteSinglePublicEvidence
  remoteMultiEvidenceComparison
  mixedPrivateActionBatchFailClosed
)

usage() {
  cat >&2 <<'USAGE'
Usage:
  OWNER="QA Owner" MANUAL_ACCEPTANCE_ALL=1 scripts/record_manual_acceptance_evidence.sh

  OWNER="QA Owner" MANUAL_ACCEPTANCE_KEYS=modelSetup,toolConfirmation \
    scripts/record_manual_acceptance_evidence.sh

The script records formal manual-acceptance evidence only for keys explicitly
provided by the caller. It exits non-zero until every required key is accepted.
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
  if [[ "${#ORIGINAL_ARGS[@]}" -gt 0 ]]; then
    for arg in "${ORIGINAL_ARGS[@]}"; do
      quoted+=("$(printf '%q' "$arg")")
    done
  fi
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
    printf 'artifactSchema=ManualAcceptanceEvidenceCollection/v1\n'
    printf 'status=%s\n' "$status"
    printf 'target=manual-acceptance-evidence\n'
    printf 'owner=%s\n' "$OWNER"
    printf 'recordedAt=%s\n' "$RECORDED_AT"
    printf 'command=%s\n' "$(command_line)"
    printf 'reproduciblePath=%s\n' "$REPORT_FILE"
    printf 'reason=%s\n' "$reason"
    printf 'validationRecordFile=%s\n' "$VALIDATION_RECORD_FILE"
    printf 'artifactDir=%s\n' "$ARTIFACT_DIR"
    printf 'date=%s\n' "$VALIDATION_DATE"
    printf 'releaseArtifactSha256=%s\n' "$RELEASE_ARTIFACT_SHA256"
    printf 'requiredManualKeys=%s\n' "$(join_csv "${REQUIRED_MANUAL_KEYS[@]}")"
    printf 'acceptedManualKeys=%s\n' "$accepted"
    printf 'pendingManualKeys=%s\n' "$pending"
  } > "$REPORT_FILE"
}

write_manual_contract_fields() {
  local key="$1"
  case "$key" in
    modelSetup)
      printf 'modelManagerOpened=true\n'
      printf 'recommendedModelAvailabilityChecked=true\n'
      ;;
    remoteModePrivacy)
      printf 'remoteModeExplicitlySelected=true\n'
      printf 'localMemoryNotAutoIncluded=true\n'
      printf 'remoteRawPrivateContextSent=false\n'
      ;;
    toolConfirmation)
      printf 'confirmationSheetObserved=true\n'
      printf 'toolCancelPreventsExecution=true\n'
      printf 'toolExecutedWithoutConfirmation=false\n'
      ;;
    permissions)
      printf 'runtimePermissionPromptObserved=true\n'
      printf 'permissionDeniedRecoveryCovered=true\n'
      printf 'permissionGrantedSilently=false\n'
      ;;
    backgroundReminders)
      printf 'reminderCreateUpdateCancelObserved=true\n'
      printf 'backgroundReminderDeliveryObserved=true\n'
      ;;
    sharing)
      printf 'shareSheetBoundaryObserved=true\n'
      printf 'externalOutcomeNotAutoClaimed=true\n'
      ;;
    multimodalEntryPoints)
      printf 'localVisionCapabilityObserved=true\n'
      printf 'unsupportedVisionFailClosedObserved=true\n'
      ;;
    voiceInput)
      printf 'systemSpeechRecognizerObserved=true\n'
      printf 'voiceDraftNoAutoSend=true\n'
      printf 'voiceCancelCovered=true\n'
      ;;
    filePicker)
      printf 'systemDocumentPickerObserved=true\n'
      printf 'documentExcerptBounded=true\n'
      printf 'remoteNonImageAttachmentNotAutoIncluded=true\n'
      ;;
    mediaProjection)
      printf 'systemMediaProjectionPromptObserved=true\n'
      printf 'mediaProjectionCancelBlocksCapture=true\n'
      printf 'mediaProjectionOneShotConsentCovered=true\n'
      printf 'screenshotRawPayloadPersisted=false\n'
      ;;
    remoteSinglePublicEvidence)
      printf 'singlePublicEvidenceSelected=true\n'
      printf 'privateEvidenceExcluded=true\n'
      printf 'remoteRequestCount=1\n'
      ;;
    remoteMultiEvidenceComparison)
      printf 'multiplePublicEvidenceCompared=true\n'
      printf 'publicEvidenceCount=2\n'
      printf 'privateEvidenceSent=false\n'
      ;;
    mixedPrivateActionBatchFailClosed)
      printf 'mixedBatchRejected=true\n'
      printf 'partialActionExecution=false\n'
      printf 'remoteRequestCount=0\n'
      ;;
  esac
}

fail() {
  local reason="$1"
  shift
  write_report failed "$reason" "" "$(join_csv "${REQUIRED_MANUAL_KEYS[@]}")"
  echo "$*" >&2
  usage
  exit 1
}

if [[ -z "$OWNER" ]]; then
  fail missing-owner "OWNER is required for manual acceptance evidence."
fi

if [[ ! "$VALIDATION_DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  fail invalid-date "VALIDATION_DATE must use YYYY-MM-DD."
fi

accepted_keys=()
if [[ "$MANUAL_ACCEPTANCE_ALL" == "1" ]]; then
  accepted_keys=("${REQUIRED_MANUAL_KEYS[@]}")
elif [[ -n "$MANUAL_ACCEPTANCE_KEYS" ]]; then
  IFS=, read -r -a raw_keys <<< "$MANUAL_ACCEPTANCE_KEYS"
  for raw_key in "${raw_keys[@]}"; do
    key="$(printf '%s' "$raw_key" | xargs)"
    [[ -n "$key" ]] || continue
    if ! contains_key "$key" "${REQUIRED_MANUAL_KEYS[@]}"; then
      fail "unknown-key-$key" "Unknown manual acceptance key: $key"
    fi
    if [[ "${#accepted_keys[@]}" -eq 0 ]] || ! contains_key "$key" "${accepted_keys[@]}"; then
      accepted_keys+=("$key")
    fi
  done
fi

if [[ "${#accepted_keys[@]}" -eq 0 ]]; then
  fail missing-accepted-keys "Set MANUAL_ACCEPTANCE_ALL=1 or MANUAL_ACCEPTANCE_KEYS=<comma-separated keys>."
fi

mkdir -p "$ARTIFACT_DIR"
for key in "${accepted_keys[@]}"; do
  evidence_path="$ARTIFACT_DIR/manual-$key.properties"
  {
    printf 'artifactSchema=ManualAcceptanceEvidence/v1\n'
    printf 'status=passed\n'
    printf 'target=manual-acceptance\n'
    printf 'manualKey=%s\n' "$key"
    printf 'manualAcceptance=true\n'
    printf 'owner=%s\n' "$OWNER"
    printf 'date=%s\n' "$VALIDATION_DATE"
    printf 'recordedAt=%s\n' "$RECORDED_AT"
    printf 'command=%s\n' "$(command_line)"
    printf 'reproduciblePath=%s\n' "$evidence_path"
    printf 'releaseArtifactSha256=%s\n' "$RELEASE_ARTIFACT_SHA256"
    printf 'validationRecordFile=%s\n' "$VALIDATION_RECORD_FILE"
    printf 'evidenceSummary=%s\n' "$MANUAL_ACCEPTANCE_NOTE"
    write_manual_contract_fields "$key"
  } > "$evidence_path"
done

pending_keys=()
for key in "${REQUIRED_MANUAL_KEYS[@]}"; do
  if [[ "${#accepted_keys[@]}" -eq 0 ]] || ! contains_key "$key" "${accepted_keys[@]}"; then
    pending_keys+=("$key")
  fi
done

accepted_csv="$(join_csv "${accepted_keys[@]}")"
if [[ "${#pending_keys[@]}" -gt 0 ]]; then
  pending_csv="$(join_csv "${pending_keys[@]}")"
else
  pending_csv=""
fi
if [[ "${#pending_keys[@]}" -gt 0 ]]; then
  write_report failed missing-required-manual-keys "$accepted_csv" "$pending_csv"
  echo "Manual acceptance evidence is incomplete: $pending_csv" >&2
  exit 1
fi

write_report passed "" "$accepted_csv" ""
echo "Manual acceptance evidence written to $ARTIFACT_DIR"
