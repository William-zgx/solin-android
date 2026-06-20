#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

VALIDATION_RECORD_FILE="${VALIDATION_RECORD_FILE:-docs/release_validation_record.json}"
ANDROID_TEST_SOURCE_DIR="${ANDROID_TEST_SOURCE_DIR:-app/src/androidTest}"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/release-flow-matrix-current}"
REPORT_FILE="${REPORT_FILE:-$ARTIFACT_DIR/release-flow-matrix-candidate-evidence.properties}"
OWNER="${OWNER:-QA Automation}"
VALIDATION_DATE="${VALIDATION_DATE:-$(date +%F)}"

REQUIRED_FLOWS=(
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

GENERATED_FLOWS=(
  firstInstall
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

FAILED_TARGET=""
FAILURE_REASON=""
GENERATED_EVIDENCE_PATHS=()
SOURCE_ANDROID_TEST_COUNT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file)
      VALIDATION_RECORD_FILE="${2:?missing validation record file}"
      shift 2
      ;;
    --android-test-source-dir)
      ANDROID_TEST_SOURCE_DIR="${2:?missing AndroidTest source directory}"
      shift 2
      ;;
    --artifact-dir)
      ARTIFACT_DIR="${2:?missing artifact directory}"
      if [[ "$REPORT_FILE" == "build/verification/release-flow-matrix-current/release-flow-matrix-candidate-evidence.properties" ]]; then
        REPORT_FILE="$ARTIFACT_DIR/release-flow-matrix-candidate-evidence.properties"
      fi
      shift 2
      ;;
    --report)
      REPORT_FILE="${2:?missing report path}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

join_csv() {
  local IFS=,
  printf '%s' "$*"
}

count_android_tests() {
  find "$ANDROID_TEST_SOURCE_DIR" \( -name '*.kt' -o -name '*.java' \) -print0 |
    xargs -0 awk '/^[[:space:]]*@(org[.]junit[.])?Test([[:space:](]|$)/ {count += 1} END {print count + 0}'
}

sha256_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

property_value() {
  local key="$1"
  local file="$2"
  awk -F= -v key="$key" '$1 == key {print substr($0, index($0, "=") + 1); exit}' "$file"
}

json_value() {
  local selector="$1"
  python3 - "$VALIDATION_RECORD_FILE" "$selector" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
value = record
for part in sys.argv[2].split("."):
    if not isinstance(value, dict):
        value = ""
        break
    value = value.get(part, "")
if value is None:
    value = ""
print(value if isinstance(value, str) else str(value))
PY
}

write_report() {
  local status="$1"
  local reason="$2"
  local passed_flows="${3:-}"
  local pending_flows="${4:-}"
  local generated_candidate_evidence_paths=""
  if [[ ${#GENERATED_EVIDENCE_PATHS[@]} -gt 0 ]]; then
    generated_candidate_evidence_paths="$(join_csv "${GENERATED_EVIDENCE_PATHS[@]}")"
  fi
  mkdir -p "$(dirname "$REPORT_FILE")"
  {
    printf 'status=%s\n' "$status"
    printf 'target=release-flow-matrix-candidate-evidence\n'
    printf 'failedTarget=%s\n' "$FAILED_TARGET"
    printf 'reason=%s\n' "$reason"
    printf 'validationRecordFile=%s\n' "$VALIDATION_RECORD_FILE"
    printf 'androidTestSourceDir=%s\n' "$ANDROID_TEST_SOURCE_DIR"
    printf 'sourceAndroidTestCount=%s\n' "$SOURCE_ANDROID_TEST_COUNT"
    printf 'artifactDir=%s\n' "$ARTIFACT_DIR"
    printf 'owner=%s\n' "$OWNER"
    printf 'date=%s\n' "$VALIDATION_DATE"
    printf 'requiredFlows=%s\n' "$(join_csv "${REQUIRED_FLOWS[@]}")"
    printf 'generatedCandidateFlows=%s\n' "$(join_csv "${GENERATED_FLOWS[@]}")"
    printf 'generatedCandidateEvidencePaths=%s\n' "$generated_candidate_evidence_paths"
    printf 'passedRecordFlows=%s\n' "$passed_flows"
    printf 'pendingRecordFlows=%s\n' "$pending_flows"
  } > "$REPORT_FILE"
}

fail() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  write_report failed "$FAILURE_REASON"
  echo "$*" >&2
  exit 1
}

flow_summary() {
  case "$1" in
    firstInstall)
      printf 'Clean API 36 emulator regression covers first-run setup visibility, default chat-model selection, first-run skip, chat shell rendering, model manager entry, session controls, and background task empty state.'
      ;;
    localModelDownloadVerification)
      printf 'API 36 emulator regression covers custom .litertlm DownloadManager handoff; repository tests cover recommended model verification metadata, trusted model surfaces, insufficient-storage download failure, and failure-state UI contracts.'
      ;;
    customModelImportOrUrlRejection)
      printf 'API 36 emulator regression covers custom model URL rejection and custom .litertlm DownloadManager handoff; JVM contracts reject unsafe URLs and cover local import extension, storage, empty-file, temp-cleanup, and UnverifiedCustom registration boundaries.'
      ;;
    remoteHttpsConfiguration)
      printf 'API 36 emulator regression configures remote mode against a local OpenAI-compatible fixture; JVM contracts cover HTTPS validation, remote failure recovery, unconfigured remote handling, and local memory/device context protection.'
      ;;
    encryptedApiKeyClear)
      printf 'Repository tests prove blank API key clears the encrypted secret; API 36 emulator regression checks the legacy plaintext preference is not populated.'
      ;;
    sessionPersistence)
      printf 'API 36 emulator regression creates, switches, restores, and deletes sessions; repository tests cover active session and message persistence.'
      ;;
    memoryControls)
      printf 'API 36 emulator regression and memory panel UI tests cover explicit memory creation, forget, and clear controls.'
      ;;
    privacyAndDataControls)
      printf 'API 36 emulator smoke and UI tests cover the App privacy notice entry plus memory clear/forget, current-session deletion, remote configuration clear, and deletion/control copy.'
      ;;
    remindersAfterReboot)
      printf 'Repository tests cover BOOT_COMPLETED and package-replaced reminder rescheduling, catch-up scheduling, stale-running recovery, and metadata-only reminder audit boundaries.'
      ;;
    shareAndPickerInput)
      printf 'API 36 emulator regression covers ACTION_SEND text and image staging; androidTest provider counters cover remote vision image stream/OCR boundaries; JVM shared-input tests cover in-app picker attachment prompts, remote-mode protection, non-image attachment protection, document excerpts, and no implicit image OCR.'
      ;;
    voiceInput)
      printf 'API 36 emulator accessibility regression covers the voice entry disclosure and button label; ViewModel tests cover one-shot transcript drafts, partial transcript state, cancellation, and no auto-send.'
      ;;
    adaptiveUi)
      printf 'API 36 emulator adaptive UI regression covers large font reachability, landscape confirmation reachability, and accessible labels/actions for core controls.'
      ;;
    accessibilityText)
      printf 'API 36 emulator regression covers current screen Accessibility text confirmation, cancellation, audit evidence, and trace recording.'
      ;;
    recentMediaOcr)
      printf 'JVM tool, skill, and orchestration tests cover recent screenshot/image OCR routing, confirmation, one-item screenshot limits, local-only result handling, and remote-mode leakage prevention.'
      ;;
    mediaProjectionCancellation)
      printf 'API 36 emulator regression covers current screenshot OCR confirmation and user cancellation before MediaProjection execution, with audit and trace evidence.'
      ;;
  esac
}

flow_source_files() {
  case "$1" in
    firstInstall)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityFirstRunSetupUiTest.kt \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivitySmokeTest.kt
      ;;
    localModelDownloadVerification)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityComprehensiveTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/data/ModelRepositoryTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/ChatUiStateModelVerificationTest.kt
      ;;
    customModelImportOrUrlRejection)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityComprehensiveTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/data/ModelRepositoryTest.kt
      ;;
    remoteHttpsConfiguration)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityComprehensiveTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/RemoteModelConfigTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/PocketMindViewModelTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/runtime/RemoteChatRuntimeTest.kt
      ;;
    encryptedApiKeyClear)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityComprehensiveTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/data/RemoteModelRepositoryTest.kt
      ;;
    sessionPersistence)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityComprehensiveTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/data/SessionRepositoryTest.kt
      ;;
    memoryControls)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityComprehensiveTest.kt \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityLongTermMemoryUiTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/memory/MemoryQualityContractTest.kt
      ;;
    privacyAndDataControls)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivitySmokeTest.kt \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityComprehensiveTest.kt \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityLongTermMemoryUiTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/ui/PocketMindScreenDisplayTest.kt
      ;;
    remindersAfterReboot)
      printf '%s\n' \
        app/src/test/java/com/bytedance/zgx/pocketmind/background/ScheduledTaskRepositoryTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/background/ReminderAlarmReceiverTest.kt \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/background/AndroidReminderAlarmPendingIntentTest.kt
      ;;
    shareAndPickerInput)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivitySharedIntentTest.kt \
        app/src/debug/java/com/bytedance/zgx/pocketmind/debug/CountingSharedContentProvider.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/multimodal/SharedInputTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/ui/PocketMindScreenDisplayTest.kt
      ;;
    voiceInput)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityAdaptiveUiTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/PocketMindViewModelTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/ui/PocketMindScreenDisplayTest.kt
      ;;
    adaptiveUi)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityAdaptiveUiTest.kt
      ;;
    accessibilityText)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivitySkillUiTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/tool/ToolRegistryTest.kt
      ;;
    recentMediaOcr)
      printf '%s\n' \
        app/src/test/java/com/bytedance/zgx/pocketmind/skill/BuiltInSkillRuntimeTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/tool/DeviceContextToolExecutorTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/orchestration/AgentLoopRuntimeTest.kt
      ;;
    mediaProjectionCancellation)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivitySkillUiTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/multimodal/CurrentScreenshotOcrContractTest.kt
      ;;
  esac
}

write_flow_contract_fields() {
  local flow="$1"
  case "$flow" in
    firstInstall)
      printf 'firstRunSetupVisibleCovered=true\n'
      printf 'firstRunDefaultChatModelSelected=true\n'
      printf 'firstRunSkipReachesMainShell=true\n'
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
    remoteHttpsConfiguration)
      printf 'remoteNetworkFailureRecoveryCovered=true\n'
      printf 'remoteUnconfiguredModelFailureCovered=true\n'
      printf 'remoteLocalMemoryNotAutoIncluded=true\n'
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
    adaptiveUi)
      printf 'largeFontReachabilityCovered=true\n'
      printf 'landscapeReachabilityCovered=true\n'
      printf 'accessibleLabelsCovered=true\n'
      ;;
    mediaProjectionCancellation)
      printf 'mediaProjectionOneShotConsentCovered=true\n'
      printf 'currentScreenshotOcrRemoteContinuationBlocked=true\n'
      ;;
  esac
}

if [[ ! -f "$VALIDATION_RECORD_FILE" ]]; then
  fail validation-record missing-validation-record-file "Release validation record file is missing: $VALIDATION_RECORD_FILE"
fi
if [[ ! -d "$ANDROID_TEST_SOURCE_DIR" ]]; then
  fail android-test-source missing-android-test-source-dir \
    "AndroidTest source directory is missing: $ANDROID_TEST_SOURCE_DIR"
fi
SOURCE_ANDROID_TEST_COUNT="$(count_android_tests)"
if [[ ! "$SOURCE_ANDROID_TEST_COUNT" =~ ^[1-9][0-9]*$ ]]; then
  fail android-test-source android-test-source-count-invalid \
    "AndroidTest source count must be a positive integer; got ${SOURCE_ANDROID_TEST_COUNT:-<empty>}."
fi

EMULATOR_REPORT_PATH="$(json_value emulatorRegression.reportPath)"
EMULATOR_REPORT_SHA="$(json_value emulatorRegression.reportSha256)"
if [[ -z "$EMULATOR_REPORT_PATH" || ! -f "$EMULATOR_REPORT_PATH" ]]; then
  fail source-regression missing-emulator-regression-report \
    "Release flow matrix candidate evidence requires an existing clean emulator regression report."
fi
if [[ -z "$EMULATOR_REPORT_SHA" || "$(sha256_file "$EMULATOR_REPORT_PATH")" != "$EMULATOR_REPORT_SHA" ]]; then
  fail source-regression emulator-regression-report-sha-mismatch \
    "Release flow matrix candidate evidence requires the validation record emulator report SHA to match."
fi
if [[ "$(property_value status "$EMULATOR_REPORT_PATH")" != "passed" ]]; then
  fail source-regression emulator-regression-not-passed \
    "Release flow matrix candidate evidence requires a passed emulator regression report."
fi
if [[ "$(property_value target "$EMULATOR_REPORT_PATH")" != "regression-emulator" ]]; then
  fail source-regression emulator-regression-target-invalid \
    "Release flow matrix candidate evidence requires target=regression-emulator."
fi
if [[ "$(property_value clean_device "$EMULATOR_REPORT_PATH")" != "1" ]]; then
  fail source-regression emulator-regression-not-clean \
    "Release flow matrix candidate evidence requires clean_device=1."
fi
REGRESSION_SOURCE_ANDROID_TEST_COUNT="$(property_value source_android_test_count "$EMULATOR_REPORT_PATH")"
if [[ "$REGRESSION_SOURCE_ANDROID_TEST_COUNT" != "$SOURCE_ANDROID_TEST_COUNT" ]]; then
  fail source-regression emulator-regression-source-test-count-mismatch \
    "Release flow matrix candidate evidence requires source_android_test_count=$SOURCE_ANDROID_TEST_COUNT in $EMULATOR_REPORT_PATH, got ${REGRESSION_SOURCE_ANDROID_TEST_COUNT:-<missing>}."
fi
REGRESSION_EXPECTED_ANDROID_TEST_COUNT="$(property_value expected_android_test_count "$EMULATOR_REPORT_PATH")"
if [[ ! "$REGRESSION_EXPECTED_ANDROID_TEST_COUNT" =~ ^[0-9]+$ ||
  "$REGRESSION_EXPECTED_ANDROID_TEST_COUNT" -lt "$SOURCE_ANDROID_TEST_COUNT" ]]; then
  fail source-regression emulator-regression-expected-test-count-invalid \
    "Release flow matrix candidate evidence requires expected_android_test_count to cover current AndroidTest source count $SOURCE_ANDROID_TEST_COUNT."
fi
REGRESSION_ACTUAL_ANDROID_TEST_COUNT="$(property_value actual_android_test_count "$EMULATOR_REPORT_PATH")"
if [[ ! "$REGRESSION_ACTUAL_ANDROID_TEST_COUNT" =~ ^[0-9]+$ ||
  "$REGRESSION_ACTUAL_ANDROID_TEST_COUNT" -lt "$SOURCE_ANDROID_TEST_COUNT" ]]; then
  fail source-regression emulator-regression-actual-test-count-invalid \
    "Release flow matrix candidate evidence requires actual_android_test_count to cover current AndroidTest source count $SOURCE_ANDROID_TEST_COUNT."
fi

mkdir -p "$ARTIFACT_DIR"
for flow in "${GENERATED_FLOWS[@]}"; do
  evidence_path="$ARTIFACT_DIR/flow-$flow.properties"
  source_files=()
  source_shas=()
  while IFS= read -r source_file; do
    [[ -n "$source_file" ]] || continue
    if [[ ! -f "$source_file" ]]; then
      fail source-file "missing-source-file-$flow" \
        "Release flow matrix candidate evidence source is missing for $flow: $source_file"
    fi
    source_files+=("$source_file")
    source_shas+=("$(sha256_file "$source_file")")
  done < <(flow_source_files "$flow")

  {
    printf 'status=passed\n'
    printf 'target=release-flow-matrix-candidate-evidence\n'
    printf 'flow=%s\n' "$flow"
    printf 'evidenceKind=api36-clean-emulator-regression\n'
    printf 'candidateOnly=true\n'
    printf 'releaseFlowPassed=false\n'
    printf 'validationRecordFile=%s\n' "$VALIDATION_RECORD_FILE"
    printf 'sourceRegressionReport=%s\n' "$EMULATOR_REPORT_PATH"
    printf 'sourceRegressionReportSha256=%s\n' "$EMULATOR_REPORT_SHA"
    printf 'sourceTestFiles=%s\n' "$(join_csv "${source_files[@]}")"
    printf 'sourceTestFileSha256s=%s\n' "$(join_csv "${source_shas[@]}")"
    printf 'manualAcceptance=false\n'
    printf 'owner=%s\n' "$OWNER"
    printf 'date=%s\n' "$VALIDATION_DATE"
    printf 'summary=%s\n' "$(flow_summary "$flow")"
    write_flow_contract_fields "$flow"
  } > "$evidence_path"
  GENERATED_EVIDENCE_PATHS+=("$evidence_path")
done

SUMMARY_FILE="$(mktemp)"
trap 'rm -f "$SUMMARY_FILE"' EXIT
python3 - "$VALIDATION_RECORD_FILE" "${REQUIRED_FLOWS[@]}" > "$SUMMARY_FILE" <<'PY'
import hashlib
import json
import re
import sys
from datetime import date
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
required = sys.argv[2:]
flows = record.get("flowMatrix")
if not isinstance(flows, dict):
    flows = {}

date_pattern = re.compile(r"^\d{4}-\d{2}-\d{2}$")

def non_empty_string(value):
    return isinstance(value, str) and bool(value.strip())

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def properties_for(path):
    props = {}
    with Path(path).open() as handle:
        for raw_line in handle:
            line = raw_line.rstrip("\n")
            if "=" not in line:
                continue
            key, value = line.split("=", 1)
            props[key] = value
    return props

def is_valid_evidence(flow, value):
    if not isinstance(value, dict):
        return False
    if value.get("status") != "passed":
        return False
    if not non_empty_string(value.get("evidence")):
        return False
    if not non_empty_string(value.get("owner")):
        return False
    evidence_path = value.get("evidencePath", "")
    if not non_empty_string(evidence_path) or not Path(evidence_path).is_file():
        return False
    evidence_path = Path(evidence_path)
    if value.get("evidenceSha256") != sha(evidence_path):
        return False
    props = properties_for(evidence_path)
    if props.get("status") != "passed":
        return False
    if props.get("target") != "release-flow":
        return False
    if props.get("flowKey") != flow:
        return False
    if props.get("candidateOnly", "").lower() in {"true", "1", "yes"}:
        return False
    if props.get("releaseFlowPassed", "").lower() not in {"true", "1", "yes"}:
        return False
    required_true_fields = {
        "firstInstall": [
            "firstRunSetupVisibleCovered",
            "firstRunDefaultChatModelSelected",
            "firstRunSkipReachesMainShell",
        ],
        "localModelDownloadVerification": [
            "localModelDownloadVerified",
            "modelSha256VerificationCovered",
            "storagePreflightCovered",
            "downloadFailureRecoveryCovered",
            "downloadDirectoryUnavailableCovered",
            "downloadShaFailureCleanupCovered",
            "downloadInsufficientStorageFailureCovered",
            "pendingDownloadMissingTaskRecoveryCovered",
            "remoteFallbackExplained",
            "lightweightAlternativeExplained",
        ],
        "customModelImportOrUrlRejection": [
            "customLitertlmImportCovered",
            "customLocalNonLitertlmImportRejected",
            "customImportStoragePreflightCovered",
            "customImportEmptyFileRejected",
            "customImportTempCleanupOnCopyFailureCovered",
            "customDownloadHttpsOnly",
            "customNonLitertlmDownloadRejected",
            "customInvalidUrlRejected",
            "customCredentialedUrlRejected",
            "customUnverifiedModelMarked",
        ],
        "shareAndPickerInput": [
            "actionSendTextStaged",
            "remoteTextShareProtected",
            "remoteVisionImageAttachmentStaged",
            "remoteVisionUnsupportedProtected",
            "noImplicitImageOcr",
            "remoteNonImageAttachmentNotAutoIncluded",
            "remoteVisionSupportedOpenStreamCountCovered",
            "remoteVisionSupportedOcrSkipped",
            "remoteVisionUnsupportedOpenStreamCountCovered",
            "remoteVisionUnsupportedOcrSkipped",
            "remoteVisionMixedShareNonImageProtected",
            "remoteVisionSendPreviewConfirmed",
            "remoteVisionCancelKeepsRuntimeIdle",
            "remoteVisionHttpFixtureStreamRequested",
            "documentExcerptBounded",
            "pickerAttachmentPromptCovered",
        ],
        "voiceInput": [
            "voiceEntryDisclosureVisible",
            "voiceDraftNoAutoSendCovered",
            "voicePermissionFailureRecoveryCovered",
            "voiceCancelCovered",
        ],
        "privacyAndDataControls": [
            "privacyNoticeEntryVisible",
            "memoryClearControlCovered",
            "memoryForgetControlCovered",
            "sessionDeleteControlCovered",
            "remoteConfigClearCovered",
            "dataDeletionCopyCovered",
        ],
        "adaptiveUi": [
            "largeFontReachabilityCovered",
            "landscapeReachabilityCovered",
            "accessibleLabelsCovered",
        ],
        "mediaProjectionCancellation": [
            "mediaProjectionOneShotConsentCovered",
            "currentScreenshotOcrRemoteContinuationBlocked",
        ],
    }.get(flow, [])
    for field in required_true_fields:
        if props.get(field, "").lower() not in {"true", "1", "yes"}:
            return False
    required_exact_fields = {
        "shareAndPickerInput": {
            "remoteVisionHttpFixtureImagePartCount": "1",
            "remoteVisionSupportedImageStreamOpenCount": "1",
            "remoteVisionSupportedImageOcrInvocationCount": "0",
            "remoteVisionUnsupportedImageStreamOpenCount": "0",
            "remoteVisionUnsupportedImageOcrInvocationCount": "0",
            "remoteVisionMixedProtectedNonImageCount": "1",
        },
    }.get(flow, {})
    for field, expected in required_exact_fields.items():
        if props.get(field) != expected:
            return False
    recorded_date = value.get("date", "")
    if not non_empty_string(recorded_date) or not date_pattern.match(recorded_date):
        return False
    try:
        if date.fromisoformat(recorded_date) > date.today():
            return False
    except ValueError:
        return False
    return True

passed = []
pending = []
for flow in required:
    if is_valid_evidence(flow, flows.get(flow)):
        passed.append(flow)
    else:
        pending.append(flow)

print("passedRecordFlows=" + ",".join(passed))
print("pendingRecordFlows=" + ",".join(pending))
PY

PASSED_RECORD_FLOWS="$(property_value passedRecordFlows "$SUMMARY_FILE")"
PENDING_RECORD_FLOWS="$(property_value pendingRecordFlows "$SUMMARY_FILE")"

if [[ -n "$PENDING_RECORD_FLOWS" ]]; then
  FAILED_TARGET="flow-matrix"
  FAILURE_REASON="missing-approved-release-evidence-${PENDING_RECORD_FLOWS}"
  write_report failed "$FAILURE_REASON" "$PASSED_RECORD_FLOWS" "$PENDING_RECORD_FLOWS"
  echo "Release flow matrix approved evidence is incomplete." >&2
  exit 1
fi

write_report passed "" "$PASSED_RECORD_FLOWS" ""
echo "Release flow matrix approved evidence passed."
