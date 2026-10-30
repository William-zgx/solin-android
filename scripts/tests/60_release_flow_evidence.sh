# shellcheck shell=bash
# Validation script tests: release flow matrix, manual acceptance and release flow evidence recorders.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

FLOW_CANDIDATE_EMULATOR_REPORT="$TMP_DIR/flow-candidate-regression-emulator.properties"
FLOW_CANDIDATE_STALE_EMULATOR_REPORT="$TMP_DIR/flow-candidate-stale-regression-emulator.properties"
FLOW_CANDIDATE_RECORD_PENDING="$TMP_DIR/flow-candidate-pending.json"
FLOW_CANDIDATE_RECORD_APPROVED="$TMP_DIR/flow-candidate-approved.json"
FLOW_CANDIDATE_RECORD_BAD_SHA="$TMP_DIR/flow-candidate-bad-sha.json"
FLOW_CANDIDATE_RECORD_STALE_REPORT="$TMP_DIR/flow-candidate-stale-report.json"
FLOW_CANDIDATE_EVIDENCE_DIR="$TMP_DIR/flow-candidate-approved-evidence"
mkdir -p "$FLOW_CANDIDATE_EVIDENCE_DIR"
cat > "$FLOW_CANDIDATE_EMULATOR_REPORT" <<FLOW_CANDIDATE_EMULATOR_REPORT_PROPERTIES
status=passed
target=regression-emulator
clean_device=1
source_android_test_count=$SOURCE_ANDROID_TEST_COUNT
expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT
actual_android_test_count=$SOURCE_ANDROID_TEST_COUNT
avd=test-avd
api_level=36
abi=arm64-v8a
FLOW_CANDIDATE_EMULATOR_REPORT_PROPERTIES
FLOW_CANDIDATE_EMULATOR_SHA="$(shasum -a 256 "$FLOW_CANDIDATE_EMULATOR_REPORT" | awk '{print $1}')"
cat > "$FLOW_CANDIDATE_RECORD_PENDING" <<FLOW_CANDIDATE_RECORD_PENDING_JSON
{
  "version": 1,
  "status": "pending_validation",
  "emulatorRegression": {
    "status": "passed",
    "reportPath": "$FLOW_CANDIDATE_EMULATOR_REPORT",
    "reportSha256": "$FLOW_CANDIDATE_EMULATOR_SHA"
  },
  "flowMatrix": {
    "firstInstall": "pending"
  }
}
FLOW_CANDIDATE_RECORD_PENDING_JSON
expect_failure \
  "release flow matrix collector writes candidate evidence while approved record is incomplete" \
  scripts/collect_release_flow_matrix_evidence.sh \
    --file "$FLOW_CANDIDATE_RECORD_PENDING" \
    --artifact-dir "$ARTIFACT_DIR/release-flow-candidate-pending" \
    --report "$ARTIFACT_DIR/release-flow-candidate-pending.properties"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending.properties" "target=release-flow-matrix-candidate-evidence"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending.properties" "failedTarget=flow-matrix"
assert_report_contains_text "$ARTIFACT_DIR/release-flow-candidate-pending.properties" "missing-approved-release-evidence"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending.properties" "generatedCandidateFlows=firstInstall,localModelDownloadVerification,customModelImportOrUrlRejection,remoteHttpsConfiguration,encryptedApiKeyClear,sessionPersistence,memoryControls,privacyAndDataControls,remindersAfterReboot,shareAndPickerInput,voiceInput,adaptiveUi,accessibilityText,recentMediaOcr,mediaProjectionCancellation"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-firstInstall.properties" "candidateOnly=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-firstInstall.properties" "releaseFlowPassed=false"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-firstInstall.properties" "firstRunSetupVisibleCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-firstInstall.properties" "firstRunDefaultChatModelSelected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-firstInstall.properties" "firstRunSkipReachesMainShell=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-encryptedApiKeyClear.properties" "encryptedApiKeyBlankInputClearsSecret=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-encryptedApiKeyClear.properties" "legacyPlaintextApiKeyNotPersisted=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-sessionPersistence.properties" "sessionCreateSwitchRestoreCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-memoryControls.properties" "memoryPanelControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-remindersAfterReboot.properties" "bootCompletedReminderRescheduleCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-accessibilityText.properties" "accessibilityTextLocalOnlyMetadataCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-recentMediaOcr.properties" "recentMediaOcrRemoteLeakageBlocked=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-recentMediaOcr.properties" "recentScreenshotMaxCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-recentMediaOcr.properties" "recentImageMaxCount=3"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-recentMediaOcr.properties" "recentMediaOcrRawPayloadPersisted=false"
for generated_flow_key in \
  firstInstall localModelDownloadVerification customModelImportOrUrlRejection \
  remoteHttpsConfiguration encryptedApiKeyClear sessionPersistence memoryControls \
  privacyAndDataControls remindersAfterReboot shareAndPickerInput voiceInput adaptiveUi accessibilityText \
  recentMediaOcr mediaProjectionCancellation; do
  assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-$generated_flow_key.properties" "status=passed"
  assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-$generated_flow_key.properties" "target=release-flow-matrix-candidate-evidence"
  assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-$generated_flow_key.properties" "flow=$generated_flow_key"
  assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-$generated_flow_key.properties" "candidateOnly=true"
  assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-$generated_flow_key.properties" "releaseFlowPassed=false"
done
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "localModelDownloadVerified=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "modelSha256VerificationCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "storagePreflightCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "downloadDirectoryUnavailableCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "downloadShaFailureCleanupCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "downloadInsufficientStorageFailureCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "pendingDownloadMissingTaskRecoveryCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-localModelDownloadVerification.properties" "lightweightAlternativeExplained=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customLocalNonLitertlmImportRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customImportStoragePreflightCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customImportEmptyFileRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customImportTempCleanupOnCopyFailureCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customDownloadHttpsOnly=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customNonLitertlmDownloadRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customInvalidUrlRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-customModelImportOrUrlRejection.properties" "customUnverifiedModelMarked=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-remoteHttpsConfiguration.properties" "remoteNetworkFailureRecoveryCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-remoteHttpsConfiguration.properties" "remoteUnconfiguredModelFailureCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-remoteHttpsConfiguration.properties" "remoteLocalMemoryNotAutoIncluded=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-remoteHttpsConfiguration.properties" "remoteMemoryContextIncluded=false"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-remoteHttpsConfiguration.properties" "remoteMemoryHitCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-remoteHttpsConfiguration.properties" "remoteDeviceContextIncluded=false"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-remoteHttpsConfiguration.properties" "remoteRawContentPersisted=false"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "actionSendTextStaged=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteTextShareProtected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionImageAttachmentStaged=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedProtected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "noImplicitImageOcr=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteNonImageAttachmentNotAutoIncluded=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionSupportedOpenStreamCountCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionSupportedOcrSkipped=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedOpenStreamCountCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedOcrSkipped=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionMixedShareNonImageProtected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionSendPreviewConfirmed=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionCancelKeepsRuntimeIdle=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionHttpFixtureImagePartCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionHttpFixtureStreamRequested=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionSupportedImageStreamOpenCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionSupportedImageOcrInvocationCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedImageStreamOpenCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedImageOcrInvocationCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "remoteVisionMixedProtectedNonImageCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionVerifiedModelImageAttachmentStaged=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionRuntimeImageAttachmentSent=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionLocalOnlyPersistenceCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionPromptMetadataRedacted=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionRemoteRuntimeIdle=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionUnsupportedOcrSkipped=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionRuntimeImageAttachmentSendCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionRemoteRuntimeRequestCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionUnsupportedRuntimeImageSendCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-shareAndPickerInput.properties" "localVisionUnsupportedImageOcrInvocationCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-voiceInput.properties" "voiceEntryDisclosureVisible=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-voiceInput.properties" "voiceDraftNoAutoSendCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-voiceInput.properties" "voicePermissionFailureRecoveryCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-voiceInput.properties" "voiceCancelCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-privacyAndDataControls.properties" "privacyNoticeEntryVisible=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-privacyAndDataControls.properties" "memoryClearControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-privacyAndDataControls.properties" "sessionDeleteControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-privacyAndDataControls.properties" "remoteConfigClearCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-adaptiveUi.properties" "largeFontReachabilityCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-adaptiveUi.properties" "landscapeReachabilityCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-adaptiveUi.properties" "accessibleLabelsCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-mediaProjectionCancellation.properties" "mediaProjectionOneShotConsentCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-pending/flow-mediaProjectionCancellation.properties" "currentScreenshotOcrRemoteContinuationBlocked=true"
cat > "$FLOW_CANDIDATE_STALE_EMULATOR_REPORT" <<FLOW_CANDIDATE_STALE_EMULATOR_REPORT_PROPERTIES
status=passed
target=regression-emulator
clean_device=1
source_android_test_count=$LOW_ANDROID_TEST_COUNT
expected_android_test_count=$LOW_ANDROID_TEST_COUNT
actual_android_test_count=$LOW_ANDROID_TEST_COUNT
avd=test-avd
api_level=36
abi=arm64-v8a
FLOW_CANDIDATE_STALE_EMULATOR_REPORT_PROPERTIES
FLOW_CANDIDATE_STALE_EMULATOR_SHA="$(shasum -a 256 "$FLOW_CANDIDATE_STALE_EMULATOR_REPORT" | awk '{print $1}')"
python3 - "$FLOW_CANDIDATE_RECORD_PENDING" "$FLOW_CANDIDATE_RECORD_STALE_REPORT" "$FLOW_CANDIDATE_STALE_EMULATOR_REPORT" "$FLOW_CANDIDATE_STALE_EMULATOR_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["emulatorRegression"]["reportPath"] = sys.argv[3]
record["emulatorRegression"]["reportSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release flow matrix collector rejects stale emulator regression source count" \
  scripts/collect_release_flow_matrix_evidence.sh \
    --file "$FLOW_CANDIDATE_RECORD_STALE_REPORT" \
    --artifact-dir "$ARTIFACT_DIR/release-flow-candidate-stale-report" \
    --report "$ARTIFACT_DIR/release-flow-candidate-stale-report.properties"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-stale-report.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-stale-report.properties" "failedTarget=source-regression"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-stale-report.properties" "reason=emulator-regression-source-test-count-mismatch"
for flow_key in \
  firstInstall upgradeInstall localModelDownloadVerification customModelImportOrUrlRejection \
  remoteHttpsConfiguration encryptedApiKeyClear sessionPersistence memoryControls \
  privacyAndDataControls remindersAfterReboot shareAndPickerInput voiceInput adaptiveUi accessibilityText \
  recentMediaOcr mediaProjectionCancellation; do
  {
    printf 'status=passed\n'
    printf 'target=release-flow\n'
    printf 'flowKey=%s\n' "$flow_key"
    printf 'releaseFlowPassed=true\n'
    printf 'owner=QA\n'
    printf 'date=%s\n' "$VALIDATION_DATE"
    printf 'summary=%s release flow was explicitly approved.\n' "$flow_key"
    write_model_release_flow_contract_fixture "$flow_key"
  } > "$FLOW_CANDIDATE_EVIDENCE_DIR/$flow_key.properties"
done
python3 - "$FLOW_CANDIDATE_RECORD_APPROVED" "$FLOW_CANDIDATE_EMULATOR_REPORT" "$FLOW_CANDIDATE_EMULATOR_SHA" "$FLOW_CANDIDATE_EVIDENCE_DIR" "$VALIDATION_DATE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

target = Path(sys.argv[1])
emulator_report = sys.argv[2]
emulator_sha = sys.argv[3]
evidence_dir = Path(sys.argv[4])
validation_date = sys.argv[5]
flows = [
    "firstInstall",
    "upgradeInstall",
    "localModelDownloadVerification",
    "customModelImportOrUrlRejection",
    "remoteHttpsConfiguration",
    "encryptedApiKeyClear",
    "sessionPersistence",
    "memoryControls",
    "privacyAndDataControls",
    "remindersAfterReboot",
    "shareAndPickerInput",
    "voiceInput",
    "adaptiveUi",
    "accessibilityText",
    "recentMediaOcr",
    "mediaProjectionCancellation",
]

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

record = {
    "version": 1,
    "status": "approved",
    "emulatorRegression": {
        "status": "passed",
        "reportPath": emulator_report,
        "reportSha256": emulator_sha,
    },
    "flowMatrix": {},
}
for flow in flows:
    evidence_path = evidence_dir / f"{flow}.properties"
    record["flowMatrix"][flow] = {
        "status": "passed",
        "evidence": f"{flow} approved flow evidence.",
        "evidencePath": str(evidence_path),
        "evidenceSha256": sha(evidence_path),
        "owner": "QA",
        "date": validation_date,
    }
target.write_text(json.dumps(record, indent=2))
PY
expect_success \
  "release flow matrix collector accepts approved structured flow records" \
  scripts/collect_release_flow_matrix_evidence.sh \
    --file "$FLOW_CANDIDATE_RECORD_APPROVED" \
    --artifact-dir "$ARTIFACT_DIR/release-flow-candidate-approved" \
    --report "$ARTIFACT_DIR/release-flow-candidate-approved.properties"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-approved.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-approved.properties" "passedRecordFlows=firstInstall,upgradeInstall,localModelDownloadVerification,customModelImportOrUrlRejection,remoteHttpsConfiguration,encryptedApiKeyClear,sessionPersistence,memoryControls,privacyAndDataControls,remindersAfterReboot,shareAndPickerInput,voiceInput,adaptiveUi,accessibilityText,recentMediaOcr,mediaProjectionCancellation"
FLOW_CANDIDATE_WEAK_EVIDENCE="$TMP_DIR/flow-candidate-weak-evidence.properties"
FLOW_CANDIDATE_RECORD_WEAK_EVIDENCE="$TMP_DIR/flow-candidate-record-weak-evidence.json"
printf 'status=passed\nflow=firstInstall\n' > "$FLOW_CANDIDATE_WEAK_EVIDENCE"
python3 - "$FLOW_CANDIDATE_RECORD_APPROVED" "$FLOW_CANDIDATE_RECORD_WEAK_EVIDENCE" "$FLOW_CANDIDATE_WEAK_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
evidence = Path(sys.argv[3])
record["flowMatrix"]["firstInstall"]["evidencePath"] = str(evidence)
record["flowMatrix"]["firstInstall"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release flow matrix collector rejects weak structured flow evidence" \
  scripts/collect_release_flow_matrix_evidence.sh \
    --file "$FLOW_CANDIDATE_RECORD_WEAK_EVIDENCE" \
    --artifact-dir "$ARTIFACT_DIR/release-flow-candidate-weak-evidence" \
    --report "$ARTIFACT_DIR/release-flow-candidate-weak-evidence.properties"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-weak-evidence.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-weak-evidence.properties" "failedTarget=flow-matrix"
assert_report_contains_text "$ARTIFACT_DIR/release-flow-candidate-weak-evidence.properties" "pendingRecordFlows=firstInstall"
python3 - "$FLOW_CANDIDATE_RECORD_APPROVED" "$FLOW_CANDIDATE_RECORD_BAD_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["emulatorRegression"]["reportSha256"] = "0" * 64
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release flow matrix collector rejects mismatched source regression sha" \
  scripts/collect_release_flow_matrix_evidence.sh \
    --file "$FLOW_CANDIDATE_RECORD_BAD_SHA" \
    --artifact-dir "$ARTIFACT_DIR/release-flow-candidate-bad-sha" \
    --report "$ARTIFACT_DIR/release-flow-candidate-bad-sha.properties"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-bad-sha.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-bad-sha.properties" "failedTarget=source-regression"
assert_report_contains "$ARTIFACT_DIR/release-flow-candidate-bad-sha.properties" "reason=emulator-regression-report-sha-mismatch"

expect_failure \
  "manual acceptance evidence recorder requires owner" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/manual-acceptance-missing-owner" \
  MANUAL_ACCEPTANCE_ALL=1 scripts/record_manual_acceptance_evidence.sh
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-missing-owner/manual-acceptance-evidence.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-missing-owner/manual-acceptance-evidence.properties" "reason=missing-owner"
expect_failure \
  "manual acceptance evidence recorder reports pending keys" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/manual-acceptance-partial" \
  OWNER="QA" MANUAL_ACCEPTANCE_KEYS="modelSetup,toolConfirmation" \
  SOURCE_EVIDENCE_FILES="$VALIDATION_INSTRUMENTATION_OUTPUT" \
  scripts/record_manual_acceptance_evidence.sh
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-partial/manual-acceptance-evidence.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-partial/manual-acceptance-evidence.properties" "reason=missing-required-manual-keys"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-partial/manual-acceptance-evidence.properties" "acceptedManualKeys=modelSetup,toolConfirmation"
assert_report_contains_text "$ARTIFACT_DIR/manual-acceptance-partial/manual-acceptance-evidence.properties" "pendingManualKeys="
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-partial/manual-modelSetup.properties" "target=manual-acceptance"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-partial/manual-modelSetup.properties" "manualKey=modelSetup"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-partial/manual-modelSetup.properties" "manualAcceptance=true"
expect_success \
  "manual acceptance evidence recorder writes all formal evidence" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/manual-acceptance-full" \
  OWNER="QA" MANUAL_ACCEPTANCE_ALL=1 VALIDATION_DATE="$VALIDATION_DATE" \
  SOURCE_EVIDENCE_FILES="$VALIDATION_INSTRUMENTATION_OUTPUT" \
  scripts/record_manual_acceptance_evidence.sh
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/manual-acceptance-full/manual-acceptance-evidence.properties" \
  "ManualAcceptanceEvidenceCollection/v1" \
  "QA"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-acceptance-evidence.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-acceptance-evidence.properties" "target=manual-acceptance-evidence"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-acceptance-evidence.properties" "pendingManualKeys="
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-acceptance-evidence.properties" "sourceEvidenceFileCount=1"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-acceptance-evidence.properties" "sourceEvidenceFile1Path=$VALIDATION_INSTRUMENTATION_OUTPUT"
for manual_key in \
  modelSetup remoteModePrivacy toolConfirmation permissions backgroundReminders sharing \
  multimodalEntryPoints voiceInput filePicker mediaProjection remoteSinglePublicEvidence \
  remoteMultiEvidenceComparison mixedPrivateActionBatchFailClosed; do
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "status=passed"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "artifactSchema=ManualAcceptanceEvidence/v1"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "target=manual-acceptance"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "manualKey=$manual_key"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "manualAcceptance=true"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "owner=QA"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "date=$VALIDATION_DATE"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "command=scripts/record_manual_acceptance_evidence.sh"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "reproduciblePath=$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "sourceEvidenceFileCount=1"
  assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "sourceEvidenceFile1Path=$VALIDATION_INSTRUMENTATION_OUTPUT"
  case "$manual_key" in
    remoteModePrivacy)
      assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "localMemoryNotAutoIncluded=true"
      assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "remoteRawPrivateContextSent=false"
      ;;
    toolConfirmation)
      assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "confirmationSheetObserved=true"
      assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "toolCancelPreventsExecution=true"
      assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "toolExecutedWithoutConfirmation=false"
      ;;
    mediaProjection)
      assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "systemMediaProjectionPromptObserved=true"
      assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "screenshotRawPayloadPersisted=false"
      ;;
    remoteSinglePublicEvidence)
      assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "singlePublicEvidenceSelected=true"
      assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "remoteRequestCount=1"
      ;;
    remoteMultiEvidenceComparison)
      assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "multiplePublicEvidenceCompared=true"
      assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "publicEvidenceCount=2"
      assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "privateEvidenceSent=false"
      ;;
    mixedPrivateActionBatchFailClosed)
      assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "mixedBatchRejected=true"
      assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "partialActionExecution=false"
      assert_report_contains "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" "remoteRequestCount=0"
      ;;
  esac
  grep -Eq '^recordedAt=20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' \
    "$ARTIFACT_DIR/manual-acceptance-full/manual-$manual_key.properties" ||
    fail "Expected UTC recordedAt in manual acceptance evidence for $manual_key"
done

expect_failure \
  "release flow evidence recorder requires owner" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-flow-missing-owner" \
  RELEASE_FLOW_ALL=1 scripts/record_release_flow_evidence.sh
assert_report_contains "$ARTIFACT_DIR/release-flow-missing-owner/release-flow-evidence.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-flow-missing-owner/release-flow-evidence.properties" "target=release-flow-evidence"
assert_report_contains "$ARTIFACT_DIR/release-flow-missing-owner/release-flow-evidence.properties" "reason=missing-owner"
expect_failure \
  "release flow evidence recorder reports pending flows" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-flow-partial" \
  OWNER="QA" RELEASE_FLOW_KEYS="firstInstall,sessionPersistence" \
  SOURCE_EVIDENCE_FILES="$VALIDATION_INSTRUMENTATION_OUTPUT" \
  scripts/record_release_flow_evidence.sh
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/release-flow-evidence.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/release-flow-evidence.properties" "target=release-flow-evidence"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/release-flow-evidence.properties" "reason=missing-required-release-flows"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/release-flow-evidence.properties" "acceptedFlows=firstInstall,sessionPersistence"
assert_report_contains_text "$ARTIFACT_DIR/release-flow-partial/release-flow-evidence.properties" "pendingFlows="
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "target=release-flow"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "flowKey=firstInstall"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "releaseFlowPassed=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "candidateOnly=false"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "firstRunSetupVisibleCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "firstRunDefaultChatModelSelected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-partial/flow-firstInstall.properties" "firstRunSkipReachesMainShell=true"
expect_success \
  "release flow evidence recorder writes all formal evidence" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-flow-full" \
  OWNER="QA" RELEASE_FLOW_ALL=1 VALIDATION_DATE="$VALIDATION_DATE" \
  SOURCE_EVIDENCE_FILES="$VALIDATION_INSTRUMENTATION_OUTPUT" \
  scripts/record_release_flow_evidence.sh
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/release-flow-full/release-flow-evidence.properties" \
  "ReleaseFlowEvidenceCollection/v1" \
  "QA"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/release-flow-evidence.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/release-flow-evidence.properties" "target=release-flow-evidence"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/release-flow-evidence.properties" "pendingFlows="
assert_report_contains "$ARTIFACT_DIR/release-flow-full/release-flow-evidence.properties" "sourceEvidenceFileCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/release-flow-evidence.properties" "sourceEvidenceFile1Path=$VALIDATION_INSTRUMENTATION_OUTPUT"
for flow_key in \
  firstInstall upgradeInstall localModelDownloadVerification customModelImportOrUrlRejection \
  remoteHttpsConfiguration encryptedApiKeyClear sessionPersistence memoryControls \
  privacyAndDataControls remindersAfterReboot shareAndPickerInput voiceInput adaptiveUi accessibilityText \
  recentMediaOcr mediaProjectionCancellation; do
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "status=passed"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "artifactSchema=ReleaseFlowEvidence/v1"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "target=release-flow"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "flowKey=$flow_key"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "releaseFlowPassed=true"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "candidateOnly=false"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "evidenceKind=formal-release-flow"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "owner=QA"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "date=$VALIDATION_DATE"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "command=scripts/record_release_flow_evidence.sh"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "reproduciblePath=$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "sourceEvidenceFileCount=1"
  assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" "sourceEvidenceFile1Path=$VALIDATION_INSTRUMENTATION_OUTPUT"
  grep -Eq '^recordedAt=20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' \
    "$ARTIFACT_DIR/release-flow-full/flow-$flow_key.properties" ||
    fail "Expected UTC recordedAt in release flow evidence for $flow_key"
done
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "localModelDownloadVerified=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "modelSha256VerificationCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "downloadFailureRecoveryCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "downloadDirectoryUnavailableCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "downloadShaFailureCleanupCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "downloadInsufficientStorageFailureCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "pendingDownloadMissingTaskRecoveryCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-localModelDownloadVerification.properties" "remoteFallbackExplained=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-upgradeInstall.properties" "upgradeInstallUsesAdbInstallR=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-upgradeInstall.properties" "upgradeInstallPreservesFirstInstallTime=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-upgradeInstall.properties" "upgradeInstallUpdatesLastUpdateTime=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-upgradeInstall.properties" "upgradeInstallVersionCodeIncreased=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-upgradeInstall.properties" "upgradeInstallInstrumentationCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customLitertlmImportCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customLocalNonLitertlmImportRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customImportStoragePreflightCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customImportEmptyFileRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customImportTempCleanupOnCopyFailureCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customNonLitertlmDownloadRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customCredentialedUrlRejected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-customModelImportOrUrlRejection.properties" "customUnverifiedModelMarked=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remoteHttpsConfiguration.properties" "remoteNetworkFailureRecoveryCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remoteHttpsConfiguration.properties" "remoteUnconfiguredModelFailureCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remoteHttpsConfiguration.properties" "remoteLocalMemoryNotAutoIncluded=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remoteHttpsConfiguration.properties" "remoteMemoryContextIncluded=false"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remoteHttpsConfiguration.properties" "remoteMemoryHitCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remoteHttpsConfiguration.properties" "remoteSemanticMemoryHitCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remoteHttpsConfiguration.properties" "remoteLexicalMemoryHitCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remoteHttpsConfiguration.properties" "remoteDeviceContextIncluded=false"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remoteHttpsConfiguration.properties" "remoteRawContentPersisted=false"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remoteHttpsConfiguration.properties" "remoteProtectedMemoryDeclared=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remoteHttpsConfiguration.properties" "remoteProtectedDeviceContextDeclared=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-encryptedApiKeyClear.properties" "encryptedApiKeyBlankInputClearsSecret=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-encryptedApiKeyClear.properties" "legacyPlaintextApiKeyNotPersisted=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-sessionPersistence.properties" "sessionCreateSwitchRestoreCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-sessionPersistence.properties" "activeSessionPersistenceCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-sessionPersistence.properties" "sessionDeleteCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-memoryControls.properties" "memoryCreateControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-memoryControls.properties" "memoryForgetControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-memoryControls.properties" "memoryClearControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-memoryControls.properties" "memoryPanelControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remindersAfterReboot.properties" "bootCompletedReminderRescheduleCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remindersAfterReboot.properties" "packageReplacedReminderRescheduleCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-remindersAfterReboot.properties" "reminderAuditMetadataOnly=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "actionSendTextStaged=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteTextShareProtected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionImageAttachmentStaged=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedProtected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteNonImageAttachmentNotAutoIncluded=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionSupportedOpenStreamCountCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionSupportedOcrSkipped=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedOpenStreamCountCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedOcrSkipped=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionMixedShareNonImageProtected=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionSendPreviewConfirmed=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionCancelKeepsRuntimeIdle=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionHttpFixtureImagePartCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionHttpFixtureStreamRequested=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionSupportedImageStreamOpenCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionSupportedImageOcrInvocationCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedImageStreamOpenCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionUnsupportedImageOcrInvocationCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "remoteVisionMixedProtectedNonImageCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionVerifiedModelImageAttachmentStaged=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionRuntimeImageAttachmentSent=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionLocalOnlyPersistenceCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionPromptMetadataRedacted=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionRemoteRuntimeIdle=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionUnsupportedOcrSkipped=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionRuntimeImageAttachmentSendCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionRemoteRuntimeRequestCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionUnsupportedRuntimeImageSendCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "localVisionUnsupportedImageOcrInvocationCount=0"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-shareAndPickerInput.properties" "documentExcerptBounded=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-mediaProjectionCancellation.properties" "mediaProjectionOneShotConsentCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-mediaProjectionCancellation.properties" "currentScreenshotOcrRemoteContinuationBlocked=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-voiceInput.properties" "voiceEntryDisclosureVisible=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-voiceInput.properties" "voiceDraftNoAutoSendCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-voiceInput.properties" "voicePermissionFailureRecoveryCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-voiceInput.properties" "voiceCancelCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-privacyAndDataControls.properties" "privacyNoticeEntryVisible=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-privacyAndDataControls.properties" "memoryForgetControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-privacyAndDataControls.properties" "sessionDeleteControlCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-privacyAndDataControls.properties" "dataDeletionCopyCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-adaptiveUi.properties" "largeFontReachabilityCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-adaptiveUi.properties" "landscapeReachabilityCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-adaptiveUi.properties" "accessibleLabelsCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-accessibilityText.properties" "accessibilityTextConfirmationCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-accessibilityText.properties" "accessibilityTextLocalOnlyMetadataCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-accessibilityText.properties" "accessibilityTextTraceRecorded=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-recentMediaOcr.properties" "recentScreenshotOcrRoutingCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-recentMediaOcr.properties" "recentImageOcrRoutingCovered=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-recentMediaOcr.properties" "recentScreenshotMaxCount=1"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-recentMediaOcr.properties" "recentImageMaxCount=3"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-recentMediaOcr.properties" "recentMediaOcrRawPayloadPersisted=false"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-recentMediaOcr.properties" "recentMediaOcrPrivateMetadataRedacted=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-recentMediaOcr.properties" "recentMediaOcrOcrTextTraceRedacted=true"
assert_report_contains "$ARTIFACT_DIR/release-flow-full/flow-recentMediaOcr.properties" "recentMediaOcrRemoteLeakageBlocked=true"
