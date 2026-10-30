# shellcheck shell=bash
# Validation script tests: verify_release_validation_record.sh.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

VALIDATION_PENDING="$TMP_DIR/release-validation-pending.json"
VALIDATION_APPROVED="$TMP_DIR/release-validation-approved.json"
VALIDATION_EMULATOR_REPORT="$TMP_DIR/regression-emulator.properties"
VALIDATION_EMULATOR_HELPER_REPORT="$TMP_DIR/emulator-verification.properties"
VALIDATION_DEVICE_REPORT="$TMP_DIR/device-verification.properties"
VALIDATION_EMULATOR_DEVICE_REPORT="$TMP_DIR/emulator-device-verification.properties"
VALIDATION_EMULATOR_LOGCAT="$TMP_DIR/emulator-logcat.txt"
VALIDATION_EMULATOR_SMOKE_REPORT="$TMP_DIR/emulator-crash-anr-smoke.properties"
VALIDATION_SCREENSHOT_REPORT="$TMP_DIR/release-screenshots.properties"
VALIDATION_INSTRUMENTATION_OUTPUT="$TMP_DIR/instrumentation.txt"
VALIDATION_RELEASE_APK="$TMP_DIR/validation-release.apk"
VALIDATION_RELEASE_ANDROID_TEST_APK="$TMP_DIR/validation-release-androidTest.apk"
VALIDATION_RELEASE_MAIN_SHELL_DIR="$TMP_DIR/validation-release-main-shell"
VALIDATION_RELEASE_MAIN_SHELL_START="$VALIDATION_RELEASE_MAIN_SHELL_DIR/start.txt"
VALIDATION_RELEASE_MAIN_SHELL_ACTIVITY="$VALIDATION_RELEASE_MAIN_SHELL_DIR/activity.txt"
VALIDATION_RELEASE_MAIN_SHELL_WINDOW="$VALIDATION_RELEASE_MAIN_SHELL_DIR/window.txt"
VALIDATION_RELEASE_MAIN_SHELL_UI="$VALIDATION_RELEASE_MAIN_SHELL_DIR/window.xml"
VALIDATION_RELEASE_MAIN_SHELL_SCREENSHOT="$VALIDATION_RELEASE_MAIN_SHELL_DIR/screenshot.png"
VALIDATION_SIGNER_SHA="4444444444444444444444444444444444444444444444444444444444444444"
VALIDATION_DATE="$(date +%F)"
printf 'OK (%s tests)\n' "$SOURCE_ANDROID_TEST_COUNT" > "$VALIDATION_INSTRUMENTATION_OUTPUT"
printf 'clean emulator validation logcat\n' > "$VALIDATION_EMULATOR_LOGCAT"
printf 'release apk fixture\n' > "$VALIDATION_RELEASE_APK"
printf 'release androidTest apk fixture\n' > "$VALIDATION_RELEASE_ANDROID_TEST_APK"
VALIDATION_RELEASE_ARTIFACT_SHA="$(shasum -a 256 "$VALIDATION_RELEASE_APK" | awk '{print $1}')"
VALIDATION_RELEASE_ANDROID_TEST_SHA="$(shasum -a 256 "$VALIDATION_RELEASE_ANDROID_TEST_APK" | awk '{print $1}')"
mkdir -p "$TMP_DIR/validation-screenshots"
python3 - "$TMP_DIR/validation-screenshots" <<'PY'
import base64
import sys
from pathlib import Path

target = Path(sys.argv[1])
target.mkdir(parents=True, exist_ok=True)
png = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
)
for name in ("chat-home", "model-manager", "confirmation-sheet", "background-tasks-or-audit"):
    (target / f"{name}.png").write_bytes(png)
PY
mkdir -p "$VALIDATION_RELEASE_MAIN_SHELL_DIR"
printf 'Status: ok\nActivity: com.bytedance.zgx.solin/.MainActivity\n' \
  > "$VALIDATION_RELEASE_MAIN_SHELL_START"
cat > "$VALIDATION_RELEASE_MAIN_SHELL_ACTIVITY" <<'VALIDATION_RELEASE_MAIN_SHELL_ACTIVITY_TEXT'
topResumedActivity=ActivityRecord{com.bytedance.zgx.solin/.MainActivity}
mCurrentFocus=Window{com.bytedance.zgx.solin/com.bytedance.zgx.solin.MainActivity}
VALIDATION_RELEASE_MAIN_SHELL_ACTIVITY_TEXT
cat > "$VALIDATION_RELEASE_MAIN_SHELL_WINDOW" <<'VALIDATION_RELEASE_MAIN_SHELL_WINDOW_TEXT'
mCurrentFocus=Window{com.bytedance.zgx.solin/com.bytedance.zgx.solin.MainActivity}
  Window #8 Window{com.bytedance.zgx.solin/com.bytedance.zgx.solin.MainActivity}:
    WindowStateAnimator{com.bytedance.zgx.solin/com.bytedance.zgx.solin.MainActivity}:
      Surface: shown=true      mDrawState=HAS_DRAWN       mLastHidden=false
    isOnScreen=true
    isVisible=true
VALIDATION_RELEASE_MAIN_SHELL_WINDOW_TEXT
printf '<hierarchy><node text="Solin"/></hierarchy>\n' > "$VALIDATION_RELEASE_MAIN_SHELL_UI"
cp "$TMP_DIR/validation-screenshots/chat-home.png" "$VALIDATION_RELEASE_MAIN_SHELL_SCREENSHOT"
{
  printf 'status=passed\n'
  printf 'exit_code=0\n'
  printf 'target=release-screenshots\n'
  printf 'clean_device=1\n'
  printf 'serial=emulator-5554\n'
  printf 'api_level=36\n'
  printf 'abi=arm64-v8a\n'
  printf 'avd=test-avd\n'
  printf 'releaseArtifactSha256=%s\n' "$VALIDATION_RELEASE_ARTIFACT_SHA"
  printf 'screenshot_dir=%s\n' "$TMP_DIR/validation-screenshots"
  for screenshot_name in chat-home model-manager confirmation-sheet background-tasks-or-audit; do
    screenshot_path="$TMP_DIR/validation-screenshots/$screenshot_name.png"
    screenshot_ui_dump="$TMP_DIR/validation-screenshots/$screenshot_name.xml"
    screenshot_sha="$(shasum -a 256 "$screenshot_path" | awk '{print $1}')"
    case "$screenshot_name" in
      chat-home)
        screenshot_required_text="Solin|隐私优先的随身 AI 助手|为什么装它|模型管理"
        ;;
      model-manager)
        screenshot_required_text="模型管理|当前模型|本地可用|远程多模态可选"
        ;;
      confirmation-sheet)
        screenshot_required_text="即将发送到远程模型|确认后才会|取消"
        ;;
      background-tasks-or-audit)
        screenshot_required_text="后台任务|最近审计日志|最近 Agent 轨迹|暂无运行中的后台任务"
        ;;
    esac
    {
      printf '<hierarchy>\n'
      old_ifs="$IFS"
      IFS='|' read -r -a screenshot_required_texts <<< "$screenshot_required_text"
      IFS="$old_ifs"
      for screenshot_text in "${screenshot_required_texts[@]}"; do
        printf '  <node text="%s" />\n' "$screenshot_text"
      done
      printf '</hierarchy>\n'
    } > "$screenshot_ui_dump"
    screenshot_ui_dump_sha="$(shasum -a 256 "$screenshot_ui_dump" | awk '{print $1}')"
    printf 'screenshot.%s.path=%s\n' "$screenshot_name" "$screenshot_path"
    printf 'screenshot.%s.sha256=%s\n' "$screenshot_name" "$screenshot_sha"
    printf 'screenshot.%s.sanitized=true\n' "$screenshot_name"
    printf 'screenshot.%s.uiDump=%s\n' "$screenshot_name" "$screenshot_ui_dump"
    printf 'screenshot.%s.uiDumpSha256=%s\n' "$screenshot_name" "$screenshot_ui_dump_sha"
    printf 'screenshot.%s.visualRegression=passed\n' "$screenshot_name"
    printf 'screenshot.%s.requiredText=%s\n' "$screenshot_name" "$screenshot_required_text"
  done
} > "$VALIDATION_SCREENSHOT_REPORT"
mkdir -p "$TMP_DIR/validation-api-evidence"
for api_level in 28 32 33 34 36; do
  api_evidence_dir="$TMP_DIR/validation-api-evidence/api-$api_level"
  mkdir -p "$api_evidence_dir"
  api_instrumentation_output="$api_evidence_dir/instrumentation.txt"
  api_logcat_file="$api_evidence_dir/logcat.txt"
  api_device_report="$api_evidence_dir/device-verification.properties"
  api_emulator_report="$api_evidence_dir/emulator-verification.properties"
  api_smoke_report="$api_evidence_dir/crash-anr-smoke.properties"
  api_regression_report="$TMP_DIR/validation-api-evidence/api-$api_level.properties"
  printf 'OK (%s tests)\n' "$SOURCE_ANDROID_TEST_COUNT" > "$api_instrumentation_output"
  printf 'clean api %s emulator validation logcat\n' "$api_level" > "$api_logcat_file"
  api_instrumentation_sha="$(shasum -a 256 "$api_instrumentation_output" | awk '{print $1}')"
  api_logcat_sha="$(shasum -a 256 "$api_logcat_file" | awk '{print $1}')"
  cat > "$api_device_report" <<VALIDATION_API_DEVICE_EVIDENCE_PROPERTIES
artifact_schema=DeviceVerificationArtifact/v1
artifact_id=device-emulator-$api_level-api$api_level-2026-06-06T000000Z
status=passed
exit_code=0
target=device
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=emulator-$api_level
api_level=$api_level
abi=arm64-v8a
clean_device=1
data_free_kb=4194304
instrumentation=passed
instrumentation_test_count=$SOURCE_ANDROID_TEST_COUNT
test_count=$SOURCE_ANDROID_TEST_COUNT
instrumentation_output_file=$api_instrumentation_output
instrumentation_output_sha256=$api_instrumentation_sha
logcat_file=$api_logcat_file
logcat_sha256=$api_logcat_sha
logcat_captured=1
logcat_tail_lines=500
debug_apk=app/build/outputs/apk/debug/app-debug.apk
android_test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
VALIDATION_API_DEVICE_EVIDENCE_PROPERTIES
  cat > "$api_emulator_report" <<VALIDATION_API_EMULATOR_EVIDENCE_PROPERTIES
status=passed
exit_code=0
target=emulator
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=emulator-$api_level
api_level=$api_level
abi=arm64-v8a
avd=api${api_level}-avd
clean_device=1
evidence_dir=$api_evidence_dir
screenshot_file=$api_evidence_dir/screenshot.png
window_dump_file=$api_evidence_dir/window.xml
logcat_file=$api_evidence_dir/logcat.txt
crash_anr_smoke_report_file=$api_smoke_report
emulator_log=$api_evidence_dir-emulator.log
device_report_file=$api_device_report
VALIDATION_API_EMULATOR_EVIDENCE_PROPERTIES
  write_crash_anr_smoke_fixture \
    "$api_smoke_report" \
    "$api_device_report" \
    "$api_instrumentation_output" \
    "$api_logcat_file" \
    "emulator-$api_level" \
    "$api_level" \
    "arm64-v8a"
  cat > "$api_regression_report" <<VALIDATION_API_EVIDENCE_PROPERTIES
status=passed
exit_code=0
target=regression-emulator
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
clean_device=1
source_android_test_count=$SOURCE_ANDROID_TEST_COUNT
expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT
actual_android_test_count=$SOURCE_ANDROID_TEST_COUNT
releaseArtifactSha256=$VALIDATION_RELEASE_ARTIFACT_SHA
serial=emulator-$api_level
api_level=$api_level
abi=arm64-v8a
avd=api${api_level}-avd
instrumentation_output_file=$api_instrumentation_output
logcat_file=$api_logcat_file
emulator_report_file=$api_emulator_report
device_report_file=$api_device_report
VALIDATION_API_EVIDENCE_PROPERTIES
done
mkdir -p "$TMP_DIR/validation-manual-evidence" "$TMP_DIR/validation-flow-evidence" "$TMP_DIR/validation-performance-evidence"
for manual_key in \
  modelSetup remoteModePrivacy toolConfirmation permissions backgroundReminders sharing \
  multimodalEntryPoints voiceInput filePicker mediaProjection remoteSinglePublicEvidence \
  remoteMultiEvidenceComparison mixedPrivateActionBatchFailClosed; do
  manual_evidence_path="$TMP_DIR/validation-manual-evidence/$manual_key.properties"
  cat > "$manual_evidence_path" <<VALIDATION_MANUAL_EVIDENCE_PROPERTIES
artifactSchema=ManualAcceptanceEvidence/v1
status=passed
target=manual-acceptance
manualKey=$manual_key
manualAcceptance=true
owner=QA
date=$VALIDATION_DATE
recordedAt=$PERF_RECORDED_AT
command=scripts/record_manual_acceptance_evidence.sh
reproduciblePath=$manual_evidence_path
validationRecordFile=$VALIDATION_APPROVED
releaseArtifactSha256=$VALIDATION_RELEASE_ARTIFACT_SHA
sourceEvidenceFileCount=1
sourceEvidenceFile1Path=$VALIDATION_INSTRUMENTATION_OUTPUT
sourceEvidenceFile1Sha256=$(shasum -a 256 "$VALIDATION_INSTRUMENTATION_OUTPUT" | awk '{print $1}')
VALIDATION_MANUAL_EVIDENCE_PROPERTIES
  write_manual_acceptance_contract_fixture "$manual_key" >> "$manual_evidence_path"
done
for flow_key in \
  firstInstall upgradeInstall localModelDownloadVerification customModelImportOrUrlRejection \
  remoteHttpsConfiguration encryptedApiKeyClear sessionPersistence memoryControls \
  privacyAndDataControls remindersAfterReboot shareAndPickerInput voiceInput adaptiveUi accessibilityText \
  recentMediaOcr mediaProjectionCancellation; do
  flow_evidence_path="$TMP_DIR/validation-flow-evidence/$flow_key.properties"
  cat > "$flow_evidence_path" <<VALIDATION_FLOW_EVIDENCE_PROPERTIES
artifactSchema=ReleaseFlowEvidence/v1
status=passed
target=release-flow
flowKey=$flow_key
releaseFlowPassed=true
owner=QA
date=$VALIDATION_DATE
recordedAt=$PERF_RECORDED_AT
command=scripts/record_release_flow_evidence.sh
reproduciblePath=$flow_evidence_path
validationRecordFile=$VALIDATION_APPROVED
releaseArtifactSha256=$VALIDATION_RELEASE_ARTIFACT_SHA
sourceEvidenceFileCount=1
sourceEvidenceFile1Path=$VALIDATION_INSTRUMENTATION_OUTPUT
sourceEvidenceFile1Sha256=$(shasum -a 256 "$VALIDATION_INSTRUMENTATION_OUTPUT" | awk '{print $1}')
VALIDATION_FLOW_EVIDENCE_PROPERTIES
  write_model_release_flow_contract_fixture "$flow_key" >> "$flow_evidence_path"
done
VALIDATION_PERF_BASELINE="$TMP_DIR/validation-performance-evidence/perf-baseline.properties"
cat > "$VALIDATION_PERF_BASELINE" <<VALIDATION_PERF_BASELINE_PROPERTIES
artifactSchema=PerfBaseline/v1
status=passed
target=perf-baseline-record
owner=release-engineering
collectionCommand=scripts/collect_perf_baseline.sh
reproduciblePath=$VALIDATION_PERF_BASELINE
deviceSerial=device-a
deviceModel=Pixel Test
androidApi=36
abi=arm64-v8a
appVersion=0.1.0
releaseArtifactSha256=$VALIDATION_RELEASE_ARTIFACT_SHA
modelId=chat-e2b
backend=GPU
firstLaunchInteractiveMs=1200
modelLoadMs=3500
firstTokenMs=900
tokensPerSecond=12.5
stopGenerationRecoveryMs=200
gpuFallbackStatus=not-needed
visionInputMs=500
memorySearch5kMs=25
zvecMemoryIndex50kMs=1500
zvecMemorySearch50kMs=35
memoryPeakMb=512
oomOrAnrObserved=false
recordedAt=$PERF_RECORDED_AT
VALIDATION_PERF_BASELINE_PROPERTIES
VALIDATION_PERF_BASELINE_SHA="$(shasum -a 256 "$VALIDATION_PERF_BASELINE" | awk '{print $1}')"
for perf_key in firstLaunch modelLoad firstToken streamingStopCancel backgroundReminderDelivery memoryPressure; do
  cat > "$TMP_DIR/validation-performance-evidence/$perf_key.properties" <<VALIDATION_PERFORMANCE_EVIDENCE_PROPERTIES
artifactSchema=PerfBaselineVerification/v1
status=passed
target=perf-baseline
owner=release-engineering
recordedAt=$PERF_RECORDED_AT
command=scripts/verify_perf_baseline.sh --file $VALIDATION_PERF_BASELINE --performance-key $perf_key
performanceKey=$perf_key
failedTarget=
reproduciblePath=$VALIDATION_PERF_BASELINE
baselineFile=$VALIDATION_PERF_BASELINE
baselineSha256=$VALIDATION_PERF_BASELINE_SHA
missingFieldCount=0
expectedArtifactSha256=$VALIDATION_RELEASE_ARTIFACT_SHA
expectedAppVersion=0.1.0
maxRecordAgeDays=30
VALIDATION_PERFORMANCE_EVIDENCE_PROPERTIES
done
cat > "$VALIDATION_PENDING" <<'VALIDATION_PENDING_JSON'
{
  "version": 1,
  "status": "pending_validation"
}
VALIDATION_PENDING_JSON
VALIDATION_PENDING_SHA="$(shasum -a 256 "$VALIDATION_PENDING" | awk '{print $1}')"
expect_failure \
  "release validation verifier rejects pending records" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_PENDING" --report "$ARTIFACT_DIR/release-validation-pending.properties"
assert_release_verifier_failed_report "$ARTIFACT_DIR/release-validation-pending.properties" "ReleaseValidationRecordVerification/v1"
assert_report_contains "$ARTIFACT_DIR/release-validation-pending.properties" "failedTarget=release-validation-record"
assert_report_contains "$ARTIFACT_DIR/release-validation-pending.properties" "validationRecordSha256=$VALIDATION_PENDING_SHA"
cat > "$VALIDATION_EMULATOR_REPORT" <<VALIDATION_EMULATOR_REPORT_PROPERTIES
status=passed
exit_code=0
target=regression-emulator
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
clean_device=1
source_android_test_count=$SOURCE_ANDROID_TEST_COUNT
expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT
actual_android_test_count=$SOURCE_ANDROID_TEST_COUNT
releaseArtifactSha256=$VALIDATION_RELEASE_ARTIFACT_SHA
serial=emulator-5554
avd=test-avd
api_level=36
abi=arm64-v8a
instrumentation_output_file=$VALIDATION_INSTRUMENTATION_OUTPUT
logcat_file=$VALIDATION_EMULATOR_LOGCAT
emulator_report_file=$VALIDATION_EMULATOR_HELPER_REPORT
device_report_file=$VALIDATION_EMULATOR_DEVICE_REPORT
VALIDATION_EMULATOR_REPORT_PROPERTIES
cat > "$VALIDATION_DEVICE_REPORT" <<VALIDATION_DEVICE_REPORT_PROPERTIES
artifact_schema=DeviceVerificationArtifact/v1
artifact_id=device-device-a-api36-2026-06-06T000000Z
status=passed
exit_code=0
target=device
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=device-a
api_level=36
abi=arm64-v8a
clean_device=0
reset_app_data_after_tests=0
data_free_kb=4194304
instrumentation=passed
instrumentation_test_count=$SOURCE_ANDROID_TEST_COUNT
instrumentation_executed_test_count=$SOURCE_ANDROID_TEST_COUNT
instrumentation_skipped_test_count=0
instrumentation_skipped_tests=
test_count=$SOURCE_ANDROID_TEST_COUNT
instrumentation_class=com.bytedance.zgx.solin.ReleaseSignedSmokeDeviceTest
instrumentation_runner=com.bytedance.zgx.solin.releasesmoke/com.bytedance.zgx.solin.releasesmoke.ReleaseSmokeInstrumentation
app_apk_mode=release
app_apk=$VALIDATION_RELEASE_APK
app_apk_sha256=$VALIDATION_RELEASE_ARTIFACT_SHA
android_test_apk=$VALIDATION_RELEASE_ANDROID_TEST_APK
android_test_apk_sha256=$VALIDATION_RELEASE_ANDROID_TEST_SHA
app_signer_sha256=$VALIDATION_SIGNER_SHA
android_test_signer_sha256=$VALIDATION_SIGNER_SHA
app_android_test_signers_match=true
expected_signing_cert_sha256=$VALIDATION_SIGNER_SHA
skip_install=0
installed_app_apk_path=/data/app/com.bytedance.zgx.solin/base.apk
installed_test_apk_path=/data/app/com.bytedance.zgx.solin.test/base.apk
installed_app_apk_sha256=$VALIDATION_RELEASE_ARTIFACT_SHA
installed_test_apk_sha256=$VALIDATION_RELEASE_ANDROID_TEST_SHA
installed_app_signer_sha256=$VALIDATION_SIGNER_SHA
installed_test_signer_sha256=$VALIDATION_SIGNER_SHA
installed_app_matches_requested_apk=true
installed_test_matches_requested_apk=true
app_apk_version_code=1
app_apk_version_name=0.1.0
installed_app_version_code=1
installed_app_version_name=0.1.0
releaseArtifactType=apk
releaseArtifactSha256=$VALIDATION_RELEASE_ARTIFACT_SHA
instrumentation_output_file=$VALIDATION_INSTRUMENTATION_OUTPUT
instrumentation_output_sha256=$(shasum -a 256 "$VALIDATION_INSTRUMENTATION_OUTPUT" | awk '{print $1}')
release_main_shell_verified=1
release_main_shell_start_output_file=$VALIDATION_RELEASE_MAIN_SHELL_START
release_main_shell_start_output_sha256=$(shasum -a 256 "$VALIDATION_RELEASE_MAIN_SHELL_START" | awk '{print $1}')
release_main_shell_activity_dump_file=$VALIDATION_RELEASE_MAIN_SHELL_ACTIVITY
release_main_shell_activity_dump_sha256=$(shasum -a 256 "$VALIDATION_RELEASE_MAIN_SHELL_ACTIVITY" | awk '{print $1}')
release_main_shell_window_dump_file=$VALIDATION_RELEASE_MAIN_SHELL_WINDOW
release_main_shell_window_dump_sha256=$(shasum -a 256 "$VALIDATION_RELEASE_MAIN_SHELL_WINDOW" | awk '{print $1}')
release_main_shell_ui_dump_file=$VALIDATION_RELEASE_MAIN_SHELL_UI
release_main_shell_ui_dump_sha256=$(shasum -a 256 "$VALIDATION_RELEASE_MAIN_SHELL_UI" | awk '{print $1}')
release_main_shell_screenshot_file=$VALIDATION_RELEASE_MAIN_SHELL_SCREENSHOT
release_main_shell_screenshot_sha256=$(shasum -a 256 "$VALIDATION_RELEASE_MAIN_SHELL_SCREENSHOT" | awk '{print $1}')
logcat_file=$VALIDATION_EMULATOR_LOGCAT
logcat_sha256=$(shasum -a 256 "$VALIDATION_EMULATOR_LOGCAT" | awk '{print $1}')
debug_apk=app/build/outputs/apk/debug/app-debug.apk
VALIDATION_DEVICE_REPORT_PROPERTIES
cat > "$VALIDATION_EMULATOR_DEVICE_REPORT" <<VALIDATION_EMULATOR_DEVICE_REPORT_PROPERTIES
artifact_schema=DeviceVerificationArtifact/v1
artifact_id=device-emulator-5554-api36-2026-06-06T000000Z
status=passed
exit_code=0
target=device
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=emulator-5554
api_level=36
abi=arm64-v8a
clean_device=1
data_free_kb=4194304
instrumentation=passed
instrumentation_test_count=$SOURCE_ANDROID_TEST_COUNT
test_count=$SOURCE_ANDROID_TEST_COUNT
instrumentation_output_file=$VALIDATION_INSTRUMENTATION_OUTPUT
instrumentation_output_sha256=$(shasum -a 256 "$VALIDATION_INSTRUMENTATION_OUTPUT" | awk '{print $1}')
logcat_file=$VALIDATION_EMULATOR_LOGCAT
logcat_sha256=$(shasum -a 256 "$VALIDATION_EMULATOR_LOGCAT" | awk '{print $1}')
logcat_captured=1
logcat_tail_lines=500
debug_apk=app/build/outputs/apk/debug/app-debug.apk
android_test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
VALIDATION_EMULATOR_DEVICE_REPORT_PROPERTIES
cat > "$VALIDATION_EMULATOR_HELPER_REPORT" <<VALIDATION_EMULATOR_HELPER_REPORT_PROPERTIES
status=passed
exit_code=0
target=emulator
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=emulator-5554
api_level=36
abi=arm64-v8a
avd=test-avd
clean_device=1
evidence_dir=$TMP_DIR
screenshot_file=$TMP_DIR/screenshot.png
window_dump_file=$TMP_DIR/window.xml
logcat_file=$VALIDATION_EMULATOR_LOGCAT
crash_anr_smoke_report_file=$VALIDATION_EMULATOR_SMOKE_REPORT
emulator_log=$TMP_DIR-emulator.log
device_report_file=$VALIDATION_EMULATOR_DEVICE_REPORT
VALIDATION_EMULATOR_HELPER_REPORT_PROPERTIES
write_crash_anr_smoke_fixture \
  "$VALIDATION_EMULATOR_SMOKE_REPORT" \
  "$VALIDATION_EMULATOR_DEVICE_REPORT" \
  "$VALIDATION_INSTRUMENTATION_OUTPUT" \
  "$VALIDATION_EMULATOR_LOGCAT" \
  "emulator-5554" \
  "36" \
  "arm64-v8a"
cat > "$VALIDATION_APPROVED" <<VALIDATION_APPROVED_JSON
{
  "version": 1,
  "status": "approved",
  "emulatorRegression": {
    "status": "passed",
    "reportPath": "$VALIDATION_EMULATOR_REPORT",
    "avd": "test-avd",
    "apiLevel": 36,
    "abi": "arm64-v8a",
    "cleanDevice": true
  },
  "physicalDevice": {
    "status": "passed",
    "reportPath": "$VALIDATION_DEVICE_REPORT",
    "serial": "device-a",
    "apiLevel": 36,
    "abi": "arm64-v8a",
    "cleanDevice": false
  },
  "apiMatrix": [
    {"apiLevel": 28, "status": "passed", "evidence": "API 28 smoke passed.", "evidencePath": "$TMP_DIR/validation-api-evidence/api-28.properties"},
    {"apiLevel": 32, "status": "passed", "evidence": "API 32 legacy storage path passed.", "evidencePath": "$TMP_DIR/validation-api-evidence/api-32.properties"},
    {"apiLevel": 33, "status": "passed", "evidence": "API 33 media and notification path passed.", "evidencePath": "$TMP_DIR/validation-api-evidence/api-33.properties"},
    {"apiLevel": 34, "status": "passed", "evidence": "API 34 selected visual media path passed.", "evidencePath": "$TMP_DIR/validation-api-evidence/api-34.properties"},
    {"apiLevel": 36, "status": "passed", "evidence": "API 36 target behavior passed.", "evidencePath": "$TMP_DIR/validation-api-evidence/api-36.properties"}
  ],
  "manualAcceptance": {
    "modelSetup": {"status": "passed", "evidence": "Model setup manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/modelSetup.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "remoteModePrivacy": {"status": "passed", "evidence": "Remote mode privacy manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/remoteModePrivacy.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "toolConfirmation": {"status": "passed", "evidence": "Tool confirmation manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/toolConfirmation.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "permissions": {"status": "passed", "evidence": "Permission prompt manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/permissions.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "backgroundReminders": {"status": "passed", "evidence": "Background reminders manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/backgroundReminders.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "sharing": {"status": "passed", "evidence": "Sharing manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/sharing.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "multimodalEntryPoints": {"status": "passed", "evidence": "Multimodal entry points manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/multimodalEntryPoints.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "voiceInput": {"status": "passed", "evidence": "Voice input manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/voiceInput.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "filePicker": {"status": "passed", "evidence": "File picker manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/filePicker.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "mediaProjection": {"status": "passed", "evidence": "MediaProjection manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/mediaProjection.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "remoteSinglePublicEvidence": {"status": "passed", "evidence": "Remote single public evidence manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/remoteSinglePublicEvidence.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "remoteMultiEvidenceComparison": {"status": "passed", "evidence": "Remote multi evidence comparison manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/remoteMultiEvidenceComparison.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "mixedPrivateActionBatchFailClosed": {"status": "passed", "evidence": "Mixed private/action batch fail-closed manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/mixedPrivateActionBatchFailClosed.properties", "owner": "QA", "date": "$VALIDATION_DATE"}
  },
  "flowMatrix": {
    "firstInstall": {"status": "passed", "evidence": "First install flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/firstInstall.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "upgradeInstall": {"status": "passed", "evidence": "Upgrade install flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/upgradeInstall.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "localModelDownloadVerification": {"status": "passed", "evidence": "Local model download verification flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/localModelDownloadVerification.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "customModelImportOrUrlRejection": {"status": "passed", "evidence": "Custom model import or URL rejection flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/customModelImportOrUrlRejection.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "remoteHttpsConfiguration": {"status": "passed", "evidence": "Remote HTTPS configuration flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/remoteHttpsConfiguration.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "encryptedApiKeyClear": {"status": "passed", "evidence": "Encrypted API key clear flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/encryptedApiKeyClear.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "sessionPersistence": {"status": "passed", "evidence": "Session persistence flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/sessionPersistence.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "memoryControls": {"status": "passed", "evidence": "Memory controls flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/memoryControls.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "privacyAndDataControls": {"status": "passed", "evidence": "Privacy and data controls flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/privacyAndDataControls.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "remindersAfterReboot": {"status": "passed", "evidence": "Reminders after reboot flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/remindersAfterReboot.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "shareAndPickerInput": {"status": "passed", "evidence": "Share and picker input flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/shareAndPickerInput.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "voiceInput": {"status": "passed", "evidence": "Voice input flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/voiceInput.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "adaptiveUi": {"status": "passed", "evidence": "Adaptive UI flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/adaptiveUi.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "accessibilityText": {"status": "passed", "evidence": "Accessibility text flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/accessibilityText.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "recentMediaOcr": {"status": "passed", "evidence": "Recent media OCR flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/recentMediaOcr.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "mediaProjectionCancellation": {"status": "passed", "evidence": "MediaProjection cancellation flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/mediaProjectionCancellation.properties", "owner": "QA", "date": "$VALIDATION_DATE"}
  },
  "screenshots": [
    {"name": "chat-home", "path": "$TMP_DIR/validation-screenshots/chat-home.png", "reportPath": "$VALIDATION_SCREENSHOT_REPORT", "sanitized": true},
    {"name": "model-manager", "path": "$TMP_DIR/validation-screenshots/model-manager.png", "reportPath": "$VALIDATION_SCREENSHOT_REPORT", "sanitized": true},
    {"name": "confirmation-sheet", "path": "$TMP_DIR/validation-screenshots/confirmation-sheet.png", "reportPath": "$VALIDATION_SCREENSHOT_REPORT", "sanitized": true},
    {"name": "background-tasks-or-audit", "path": "$TMP_DIR/validation-screenshots/background-tasks-or-audit.png", "reportPath": "$VALIDATION_SCREENSHOT_REPORT", "sanitized": true}
  ],
  "performanceSanity": {
    "firstLaunch": {"status": "passed", "evidence": "First launch performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/firstLaunch.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "modelLoad": {"status": "passed", "evidence": "Model load performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/modelLoad.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "firstToken": {"status": "passed", "evidence": "First token performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/firstToken.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "streamingStopCancel": {"status": "passed", "evidence": "Streaming stop/cancel performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/streamingStopCancel.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "backgroundReminderDelivery": {"status": "passed", "evidence": "Background reminder delivery performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/backgroundReminderDelivery.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "memoryPressure": {"status": "passed", "evidence": "Memory pressure performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/memoryPressure.properties", "owner": "QA", "date": "$VALIDATION_DATE"}
  },
  "review": {
    "reviewer": "Validation Reviewer",
    "reviewDate": "$VALIDATION_DATE"
  }
}
VALIDATION_APPROVED_JSON
python3 - "$VALIDATION_APPROVED" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record_path = Path(sys.argv[1])
record = json.loads(record_path.read_text())

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

record["emulatorRegression"]["reportSha256"] = sha(record["emulatorRegression"]["reportPath"])
record["physicalDevice"]["reportSha256"] = sha(record["physicalDevice"]["reportPath"])
for entry in record["apiMatrix"]:
    entry["evidenceSha256"] = sha(entry["evidencePath"])
for section in ("manualAcceptance", "flowMatrix", "performanceSanity"):
    for item in record[section].values():
        item["evidenceSha256"] = sha(item["evidencePath"])
for entry in record["screenshots"]:
    entry["sha256"] = sha(entry["path"])
    entry["reportSha256"] = sha(entry["reportPath"])

record_path.write_text(json.dumps(record, indent=2))
PY
VALIDATION_APPROVED_SHA="$(shasum -a 256 "$VALIDATION_APPROVED" | awk '{print $1}')"
expect_success \
  "release validation verifier accepts approved evidence record" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_APPROVED" --report "$ARTIFACT_DIR/release-validation-approved.properties"
assert_release_verifier_passed_report "$ARTIFACT_DIR/release-validation-approved.properties" "ReleaseValidationRecordVerification/v1"
assert_report_contains "$ARTIFACT_DIR/release-validation-approved.properties" "validationRecordSha256=$VALIDATION_APPROVED_SHA"
expect_success \
  "release validation verifier accepts current release artifact context" \
  env EXPECTED_RELEASE_ARTIFACT_TYPE=apk EXPECTED_RELEASE_ARTIFACT_SHA256="$VALIDATION_RELEASE_ARTIFACT_SHA" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_APPROVED" --report "$ARTIFACT_DIR/release-validation-current-artifact.properties"
assert_release_verifier_passed_report "$ARTIFACT_DIR/release-validation-current-artifact.properties" "ReleaseValidationRecordVerification/v1"
assert_report_contains "$ARTIFACT_DIR/release-validation-current-artifact.properties" "expectedReleaseArtifactSha256=$VALIDATION_RELEASE_ARTIFACT_SHA"
assert_report_contains "$ARTIFACT_DIR/release-validation-current-artifact.properties" "expectedReleaseArtifactType=apk"
expect_success \
  "release validation verifier accepts aab release context with apk physical proof" \
  env EXPECTED_RELEASE_ARTIFACT_TYPE=aab EXPECTED_RELEASE_ARTIFACT_SHA256="$VALIDATION_RELEASE_ARTIFACT_SHA" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_APPROVED" --report "$ARTIFACT_DIR/release-validation-aab-context.properties"
assert_release_verifier_passed_report "$ARTIFACT_DIR/release-validation-aab-context.properties" "ReleaseValidationRecordVerification/v1"
VALIDATION_LOCAL_VISION_COUNT_TWO="$TMP_DIR/release-validation-local-vision-count-two.json"
VALIDATION_LOCAL_VISION_COUNT_TWO_EVIDENCE="$TMP_DIR/validation-flow-evidence/local-vision-count-two.properties"
sed \
  -e 's/^localVisionRuntimeImageAttachmentSendCount=1$/localVisionRuntimeImageAttachmentSendCount=2/' \
  -e "s#^reproduciblePath=.*#reproduciblePath=$VALIDATION_LOCAL_VISION_COUNT_TWO_EVIDENCE#" \
  "$TMP_DIR/validation-flow-evidence/shareAndPickerInput.properties" > "$VALIDATION_LOCAL_VISION_COUNT_TWO_EVIDENCE"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_LOCAL_VISION_COUNT_TWO" "$VALIDATION_LOCAL_VISION_COUNT_TWO_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["shareAndPickerInput"]["evidencePath"] = str(evidence)
record["flowMatrix"]["shareAndPickerInput"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
python3 - "$VALIDATION_LOCAL_VISION_COUNT_TWO" "$TMP_DIR/validation-local-vision-count-two-bound-evidence" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record_path = Path(sys.argv[1])
bound_dir = Path(sys.argv[2])
record = json.loads(record_path.read_text())
for section_name in ("manualAcceptance", "flowMatrix"):
    section = record.get(section_name, {})
    for key, entry in section.items():
        if not isinstance(entry, dict) or "evidencePath" not in entry:
            continue
        source = Path(entry["evidencePath"])
        target = bound_dir / section_name / f"{key}.properties"
        target.parent.mkdir(parents=True, exist_ok=True)
        lines = source.read_text(encoding="utf-8").splitlines()
        rewritten = []
        wrote_binding = False
        for line in lines:
            if line.startswith("validationRecordFile="):
                rewritten.append(f"validationRecordFile={record_path}")
                wrote_binding = True
            elif line.startswith("reproduciblePath="):
                rewritten.append(f"reproduciblePath={target}")
            else:
                rewritten.append(line)
        if not wrote_binding:
            rewritten.append(f"validationRecordFile={record_path}")
        target.write_text("\n".join(rewritten) + "\n", encoding="utf-8")
        entry["evidencePath"] = str(target)
        entry["evidenceSha256"] = hashlib.sha256(target.read_bytes()).hexdigest()
record_path.write_text(json.dumps(record, indent=2))
PY
expect_success \
  "release validation verifier accepts local vision runtime send count above minimum" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_LOCAL_VISION_COUNT_TWO" --report "$ARTIFACT_DIR/release-validation-local-vision-count-two.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-local-vision-count-two.properties" "status=passed"
VALIDATION_X86_EMULATOR="$TMP_DIR/release-validation-x86-emulator.json"
VALIDATION_X86_EMULATOR_REPORT="$TMP_DIR/x86-regression-emulator.properties"
VALIDATION_X86_EMULATOR_HELPER_REPORT="$TMP_DIR/x86-emulator-verification.properties"
VALIDATION_X86_EMULATOR_DEVICE_REPORT="$TMP_DIR/x86-emulator-device-verification.properties"
sed 's/^abi=.*/abi=x86_64/' "$VALIDATION_EMULATOR_DEVICE_REPORT" > "$VALIDATION_X86_EMULATOR_DEVICE_REPORT"
sed \
  -e 's/^abi=.*/abi=x86_64/' \
  -e "s#^device_report_file=.*#device_report_file=$VALIDATION_X86_EMULATOR_DEVICE_REPORT#" \
  "$VALIDATION_EMULATOR_HELPER_REPORT" > "$VALIDATION_X86_EMULATOR_HELPER_REPORT"
sed \
  -e 's/^abi=.*/abi=x86_64/' \
  -e "s#^emulator_report_file=.*#emulator_report_file=$VALIDATION_X86_EMULATOR_HELPER_REPORT#" \
  -e "s#^device_report_file=.*#device_report_file=$VALIDATION_X86_EMULATOR_DEVICE_REPORT#" \
  "$VALIDATION_EMULATOR_REPORT" > "$VALIDATION_X86_EMULATOR_REPORT"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_X86_EMULATOR" "$VALIDATION_X86_EMULATOR_REPORT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
report = Path(sys.argv[3])
record = json.loads(source.read_text())
record["emulatorRegression"]["abi"] = "x86_64"
record["emulatorRegression"]["reportPath"] = str(report)
record["emulatorRegression"]["reportSha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects x86 emulator release evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_X86_EMULATOR" --report "$ARTIFACT_DIR/release-validation-x86-emulator.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-x86-emulator.properties" "emulator-regression-abi-not-arm64"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-x86-emulator.properties" "emulator-regression-abi-x86-not-allowed"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-x86-emulator.properties" "emulator-report-abi-not-arm64"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-x86-emulator.properties" "emulator-report-abi-x86-not-allowed"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-x86-emulator.properties" "emulator-regression-emulator-report-abi-x86-not-allowed"
VALIDATION_API_MIXED_ABI="$TMP_DIR/release-validation-api-mixed-abi.json"
VALIDATION_API_MIXED_ABI_DIR="$TMP_DIR/validation-api-evidence/mixed-abi-api-28"
VALIDATION_API_MIXED_ABI_EVIDENCE="$TMP_DIR/validation-api-evidence/mixed-abi-api-28.properties"
VALIDATION_API_MIXED_ABI_EMULATOR_REPORT="$VALIDATION_API_MIXED_ABI_DIR/emulator-verification.properties"
VALIDATION_API_MIXED_ABI_DEVICE_REPORT="$VALIDATION_API_MIXED_ABI_DIR/device-verification.properties"
mkdir -p "$VALIDATION_API_MIXED_ABI_DIR"
sed 's/^abi=.*/abi=arm64-v8a,x86_64/' \
  "$TMP_DIR/validation-api-evidence/api-28/device-verification.properties" > "$VALIDATION_API_MIXED_ABI_DEVICE_REPORT"
sed \
  -e 's/^abi=.*/abi=arm64-v8a,x86_64/' \
  -e "s#^device_report_file=.*#device_report_file=$VALIDATION_API_MIXED_ABI_DEVICE_REPORT#" \
  "$TMP_DIR/validation-api-evidence/api-28/emulator-verification.properties" > "$VALIDATION_API_MIXED_ABI_EMULATOR_REPORT"
sed \
  -e 's/^abi=.*/abi=arm64-v8a,x86_64/' \
  -e "s#^emulator_report_file=.*#emulator_report_file=$VALIDATION_API_MIXED_ABI_EMULATOR_REPORT#" \
  -e "s#^device_report_file=.*#device_report_file=$VALIDATION_API_MIXED_ABI_DEVICE_REPORT#" \
  "$TMP_DIR/validation-api-evidence/api-28.properties" > "$VALIDATION_API_MIXED_ABI_EVIDENCE"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_API_MIXED_ABI" "$VALIDATION_API_MIXED_ABI_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["apiMatrix"][0]["evidencePath"] = str(evidence)
record["apiMatrix"][0]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects mixed x86 api matrix evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_MIXED_ABI" --report "$ARTIFACT_DIR/release-validation-api-mixed-abi.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-mixed-abi.properties" "api-28-evidence-abi-x86-not-allowed"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-mixed-abi.properties" "api-28-emulator-report-abi-x86-not-allowed"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-mixed-abi.properties" "api-28-device-report-abi-x86-not-allowed"
expect_failure \
  "release validation verifier rejects stale release artifact context" \
  env EXPECTED_RELEASE_ARTIFACT_SHA256=2222222222222222222222222222222222222222222222222222222222222222 \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_APPROVED" --report "$ARTIFACT_DIR/release-validation-stale-artifact.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-stale-artifact.properties" "emulator-report-release-artifact-sha-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-stale-artifact.properties" "physical-device-report-release-artifact-sha-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-stale-artifact.properties" "api-28-evidence-release-artifact-sha-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-stale-artifact.properties" "manual-modelSetup-evidence-release-artifact-sha-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-stale-artifact.properties" "flow-firstInstall-evidence-release-artifact-sha-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-stale-artifact.properties" "chat-home-screenshot-report-release-artifact-sha-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-stale-artifact.properties" "performance-firstLaunch-evidence-expected-artifact-sha-mismatch"
VALIDATION_CANDIDATE_FLOW="$TMP_DIR/release-validation-candidate-flow.json"
VALIDATION_CANDIDATE_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/candidate-firstInstall.properties"
cat > "$VALIDATION_CANDIDATE_FLOW_EVIDENCE" <<'VALIDATION_CANDIDATE_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow-matrix-candidate-evidence
flow=firstInstall
candidateOnly=true
releaseFlowPassed=false
VALIDATION_CANDIDATE_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_CANDIDATE_FLOW" "$VALIDATION_CANDIDATE_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
candidate = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["firstInstall"]["evidencePath"] = str(candidate)
record["flowMatrix"]["firstInstall"]["evidenceSha256"] = hashlib.sha256(candidate.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects candidate-only flow evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_CANDIDATE_FLOW" --report "$ARTIFACT_DIR/release-validation-candidate-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-candidate-flow.properties" "flow-firstInstall-candidate-evidence-not-approved"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-candidate-flow.properties" "flow-firstInstall-release-flow-not-passed"
VALIDATION_FLOW_MISSING_AUDIT="$TMP_DIR/release-validation-flow-missing-audit.json"
VALIDATION_FLOW_MISSING_AUDIT_EVIDENCE="$TMP_DIR/validation-flow-evidence/first-install-missing-audit.properties"
grep -Ev '^(artifactSchema|recordedAt|command|reproduciblePath)=' \
  "$TMP_DIR/validation-flow-evidence/firstInstall.properties" > "$VALIDATION_FLOW_MISSING_AUDIT_EVIDENCE"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_FLOW_MISSING_AUDIT" "$VALIDATION_FLOW_MISSING_AUDIT_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["firstInstall"]["evidencePath"] = str(evidence)
record["flowMatrix"]["firstInstall"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects flow evidence missing audit fields" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_FLOW_MISSING_AUDIT" --report "$ARTIFACT_DIR/release-validation-flow-missing-audit.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-flow-missing-audit.properties" "flow-firstInstall-evidence-artifact-schema-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-flow-missing-audit.properties" "flow-firstInstall-evidence-recorded-at-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-flow-missing-audit.properties" "flow-firstInstall-evidence-command-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-flow-missing-audit.properties" "flow-firstInstall-evidence-reproducible-path-invalid"
VALIDATION_FLOW_MISMATCH_RECORD_BINDING="$TMP_DIR/release-validation-flow-mismatch-record-binding.json"
VALIDATION_FLOW_MISMATCH_RECORD_BINDING_EVIDENCE="$TMP_DIR/validation-flow-evidence/first-install-mismatch-record-binding.properties"
sed 's#^validationRecordFile=.*#validationRecordFile=/tmp/other-release-validation-record.json#' \
  "$TMP_DIR/validation-flow-evidence/firstInstall.properties" > "$VALIDATION_FLOW_MISMATCH_RECORD_BINDING_EVIDENCE"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_FLOW_MISMATCH_RECORD_BINDING" "$VALIDATION_FLOW_MISMATCH_RECORD_BINDING_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["firstInstall"]["evidencePath"] = str(evidence)
record["flowMatrix"]["firstInstall"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects flow evidence bound to another validation record" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_FLOW_MISMATCH_RECORD_BINDING" --report "$ARTIFACT_DIR/release-validation-flow-mismatch-record-binding.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-flow-mismatch-record-binding.properties" "flow-firstInstall-evidence-validation-record-file-mismatch"
VALIDATION_WEAK_FLOW="$TMP_DIR/release-validation-weak-flow.json"
VALIDATION_WEAK_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-firstInstall.properties"
cat > "$VALIDATION_WEAK_FLOW_EVIDENCE" <<'VALIDATION_WEAK_FLOW_EVIDENCE_PROPERTIES'
status=passed
flow=firstInstall
VALIDATION_WEAK_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_FLOW" "$VALIDATION_WEAK_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["firstInstall"]["evidencePath"] = str(evidence)
record["flowMatrix"]["firstInstall"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak flow matrix evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-flow.properties" "flow-firstInstall-evidence-target-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-flow.properties" "flow-firstInstall-evidence-key-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-flow.properties" "flow-firstInstall-release-flow-not-passed"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-flow.properties" "flow-firstInstall-first-run-setup-visibility-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-flow.properties" "flow-firstInstall-first-run-default-chat-model-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-flow.properties" "flow-firstInstall-first-run-skip-main-shell-missing"
VALIDATION_WEAK_UPGRADE_FLOW="$TMP_DIR/release-validation-weak-upgrade-flow.json"
VALIDATION_WEAK_UPGRADE_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-upgrade-install.properties"
cat > "$VALIDATION_WEAK_UPGRADE_FLOW_EVIDENCE" <<'VALIDATION_WEAK_UPGRADE_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=upgradeInstall
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_UPGRADE_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_UPGRADE_FLOW" "$VALIDATION_WEAK_UPGRADE_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["upgradeInstall"]["evidencePath"] = str(evidence)
record["flowMatrix"]["upgradeInstall"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak upgrade install evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_UPGRADE_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-upgrade-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-upgrade-flow.properties" "flow-upgradeInstall-upgrade-install-adb-install-r-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-upgrade-flow.properties" "flow-upgradeInstall-upgrade-install-first-install-time-preservation-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-upgrade-flow.properties" "flow-upgradeInstall-upgrade-install-version-code-increase-missing"
VALIDATION_WEAK_LOCAL_MODEL_FLOW="$TMP_DIR/release-validation-weak-local-model-flow.json"
VALIDATION_WEAK_LOCAL_MODEL_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-local-model-download.properties"
cat > "$VALIDATION_WEAK_LOCAL_MODEL_FLOW_EVIDENCE" <<'VALIDATION_WEAK_LOCAL_MODEL_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=localModelDownloadVerification
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_LOCAL_MODEL_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_LOCAL_MODEL_FLOW" "$VALIDATION_WEAK_LOCAL_MODEL_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["localModelDownloadVerification"]["evidencePath"] = str(evidence)
record["flowMatrix"]["localModelDownloadVerification"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak local model download evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_LOCAL_MODEL_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties" "flow-localModelDownloadVerification-model-download-verification-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties" "flow-localModelDownloadVerification-storage-preflight-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties" "flow-localModelDownloadVerification-download-directory-unavailable-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties" "flow-localModelDownloadVerification-download-sha-failure-cleanup-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties" "flow-localModelDownloadVerification-download-insufficient-storage-failure-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties" "flow-localModelDownloadVerification-pending-download-missing-task-recovery-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-local-model-flow.properties" "flow-localModelDownloadVerification-lightweight-alternative-explanation-missing"
VALIDATION_WEAK_CUSTOM_MODEL_FLOW="$TMP_DIR/release-validation-weak-custom-model-flow.json"
VALIDATION_WEAK_CUSTOM_MODEL_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-custom-model.properties"
cat > "$VALIDATION_WEAK_CUSTOM_MODEL_FLOW_EVIDENCE" <<'VALIDATION_WEAK_CUSTOM_MODEL_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=customModelImportOrUrlRejection
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_CUSTOM_MODEL_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_CUSTOM_MODEL_FLOW" "$VALIDATION_WEAK_CUSTOM_MODEL_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["customModelImportOrUrlRejection"]["evidencePath"] = str(evidence)
record["flowMatrix"]["customModelImportOrUrlRejection"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak custom model evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_CUSTOM_MODEL_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-custom-https-only-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-custom-local-non-litertlm-import-rejection-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-custom-import-storage-preflight-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-custom-import-empty-file-rejection-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-custom-import-temp-cleanup-on-copy-failure-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-custom-non-litertlm-rejection-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-invalid-url-rejection-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-custom-model-flow.properties" "flow-customModelImportOrUrlRejection-custom-unverified-marker-missing"
VALIDATION_WEAK_REMOTE_FLOW="$TMP_DIR/release-validation-weak-remote-flow.json"
VALIDATION_WEAK_REMOTE_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-remote.properties"
cat > "$VALIDATION_WEAK_REMOTE_FLOW_EVIDENCE" <<'VALIDATION_WEAK_REMOTE_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=remoteHttpsConfiguration
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_REMOTE_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_REMOTE_FLOW" "$VALIDATION_WEAK_REMOTE_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["remoteHttpsConfiguration"]["evidencePath"] = str(evidence)
record["flowMatrix"]["remoteHttpsConfiguration"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak remote evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_REMOTE_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-remote-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-remote-flow.properties" "flow-remoteHttpsConfiguration-remote-network-failure-recovery-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-remote-flow.properties" "flow-remoteHttpsConfiguration-remote-unconfigured-model-failure-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-remote-flow.properties" "flow-remoteHttpsConfiguration-remote-local-memory-boundary-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-remote-flow.properties" "flow-remoteHttpsConfiguration-remote-memory-context-included"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-remote-flow.properties" "flow-remoteHttpsConfiguration-remote-memory-hit-count-nonzero"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-remote-flow.properties" "flow-remoteHttpsConfiguration-remote-protected-memory-declared-missing"
VALIDATION_WEAK_SESSION_FLOW="$TMP_DIR/release-validation-weak-session-flow.json"
VALIDATION_WEAK_SESSION_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-session.properties"
cat > "$VALIDATION_WEAK_SESSION_FLOW_EVIDENCE" <<'VALIDATION_WEAK_SESSION_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=sessionPersistence
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_SESSION_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_SESSION_FLOW" "$VALIDATION_WEAK_SESSION_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["sessionPersistence"]["evidencePath"] = str(evidence)
record["flowMatrix"]["sessionPersistence"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak session persistence evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_SESSION_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-session-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-session-flow.properties" "flow-sessionPersistence-session-create-switch-restore-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-session-flow.properties" "flow-sessionPersistence-active-session-persistence-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-session-flow.properties" "flow-sessionPersistence-session-delete-missing"
VALIDATION_WEAK_SHARE_FLOW="$TMP_DIR/release-validation-weak-share-flow.json"
VALIDATION_WEAK_SHARE_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-share-picker.properties"
cat > "$VALIDATION_WEAK_SHARE_FLOW_EVIDENCE" <<'VALIDATION_WEAK_SHARE_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=shareAndPickerInput
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_SHARE_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_SHARE_FLOW" "$VALIDATION_WEAK_SHARE_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["shareAndPickerInput"]["evidencePath"] = str(evidence)
record["flowMatrix"]["shareAndPickerInput"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak share and picker evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_SHARE_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-share-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-text-share-protection-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-vision-image-staging-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-no-implicit-image-ocr-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-non-image-attachment-protection-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-vision-supported-open-stream-count-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-vision-unsupported-ocr-skip-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-vision-send-preview-confirmation-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-vision-cancel-runtime-idle-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-vision-http-fixture-image-part-count-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-remote-vision-supported-image-stream-count-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-image-staging-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-runtime-image-attachment-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-local-only-persistence-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-prompt-metadata-redaction-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-remote-runtime-idle-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-unsupported-ocr-skip-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-runtime-image-attachment-send-count-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-remote-runtime-request-count-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-unsupported-runtime-image-send-count-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-share-flow.properties" "flow-shareAndPickerInput-local-vision-unsupported-image-ocr-count-mismatch"
VALIDATION_LOCAL_VISION_ZERO_COUNT="$TMP_DIR/release-validation-local-vision-zero-count.json"
VALIDATION_LOCAL_VISION_ZERO_COUNT_EVIDENCE="$TMP_DIR/validation-flow-evidence/local-vision-zero-count.properties"
sed 's/^localVisionRuntimeImageAttachmentSendCount=1$/localVisionRuntimeImageAttachmentSendCount=0/' \
  "$TMP_DIR/validation-flow-evidence/shareAndPickerInput.properties" > "$VALIDATION_LOCAL_VISION_ZERO_COUNT_EVIDENCE"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_LOCAL_VISION_ZERO_COUNT" "$VALIDATION_LOCAL_VISION_ZERO_COUNT_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["shareAndPickerInput"]["evidencePath"] = str(evidence)
record["flowMatrix"]["shareAndPickerInput"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects zero local vision runtime image send count" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_LOCAL_VISION_ZERO_COUNT" --report "$ARTIFACT_DIR/release-validation-local-vision-zero-count.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-local-vision-zero-count.properties" "flow-shareAndPickerInput-local-vision-runtime-image-attachment-send-count-mismatch"
VALIDATION_WEAK_VOICE_FLOW="$TMP_DIR/release-validation-weak-voice-flow.json"
VALIDATION_WEAK_VOICE_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-voice.properties"
cat > "$VALIDATION_WEAK_VOICE_FLOW_EVIDENCE" <<'VALIDATION_WEAK_VOICE_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=voiceInput
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_VOICE_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_VOICE_FLOW" "$VALIDATION_WEAK_VOICE_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["voiceInput"]["evidencePath"] = str(evidence)
record["flowMatrix"]["voiceInput"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak voice input evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_VOICE_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-voice-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-voice-flow.properties" "flow-voiceInput-entry-disclosure-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-voice-flow.properties" "flow-voiceInput-draft-no-auto-send-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-voice-flow.properties" "flow-voiceInput-permission-failure-recovery-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-voice-flow.properties" "flow-voiceInput-cancel-path-missing"
VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW="$TMP_DIR/release-validation-weak-privacy-controls-flow.json"
VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-privacy-controls.properties"
cat > "$VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW_EVIDENCE" <<'VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=privacyAndDataControls
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW" "$VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["privacyAndDataControls"]["evidencePath"] = str(evidence)
record["flowMatrix"]["privacyAndDataControls"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak privacy and data controls evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_PRIVACY_CONTROLS_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-privacy-controls-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-privacy-controls-flow.properties" "flow-privacyAndDataControls-privacy-notice-entry-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-privacy-controls-flow.properties" "flow-privacyAndDataControls-memory-clear-control-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-privacy-controls-flow.properties" "flow-privacyAndDataControls-remote-config-clear-control-missing"
VALIDATION_WEAK_ADAPTIVE_UI_FLOW="$TMP_DIR/release-validation-weak-adaptive-ui-flow.json"
VALIDATION_WEAK_ADAPTIVE_UI_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-adaptive-ui.properties"
cat > "$VALIDATION_WEAK_ADAPTIVE_UI_FLOW_EVIDENCE" <<'VALIDATION_WEAK_ADAPTIVE_UI_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=adaptiveUi
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_ADAPTIVE_UI_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_ADAPTIVE_UI_FLOW" "$VALIDATION_WEAK_ADAPTIVE_UI_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["adaptiveUi"]["evidencePath"] = str(evidence)
record["flowMatrix"]["adaptiveUi"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak adaptive UI evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_ADAPTIVE_UI_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-adaptive-ui-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-adaptive-ui-flow.properties" "flow-adaptiveUi-large-font-reachability-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-adaptive-ui-flow.properties" "flow-adaptiveUi-landscape-reachability-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-adaptive-ui-flow.properties" "flow-adaptiveUi-accessible-labels-missing"
VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW="$TMP_DIR/release-validation-weak-recent-media-ocr-flow.json"
VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW_EVIDENCE="$TMP_DIR/validation-flow-evidence/weak-recent-media-ocr.properties"
cat > "$VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW_EVIDENCE" <<'VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW_EVIDENCE_PROPERTIES'
status=passed
target=release-flow
flowKey=recentMediaOcr
releaseFlowPassed=true
candidateOnly=false
VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW" "$VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["flowMatrix"]["recentMediaOcr"]["evidencePath"] = str(evidence)
record["flowMatrix"]["recentMediaOcr"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak recent media OCR evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_RECENT_MEDIA_OCR_FLOW" --report "$ARTIFACT_DIR/release-validation-weak-recent-media-ocr-flow.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-recent-media-ocr-flow.properties" "flow-recentMediaOcr-recent-screenshot-ocr-routing-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-recent-media-ocr-flow.properties" "flow-recentMediaOcr-recent-screenshot-max-count-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-recent-media-ocr-flow.properties" "flow-recentMediaOcr-recent-image-max-count-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-recent-media-ocr-flow.properties" "flow-recentMediaOcr-recent-media-ocr-private-metadata-redaction-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-recent-media-ocr-flow.properties" "flow-recentMediaOcr-recent-media-ocr-trace-redaction-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-recent-media-ocr-flow.properties" "flow-recentMediaOcr-recent-media-ocr-local-only-protection-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-recent-media-ocr-flow.properties" "flow-recentMediaOcr-recent-media-ocr-remote-leakage-block-missing"
VALIDATION_BARE_MANUAL="$TMP_DIR/release-validation-bare-manual.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_BARE_MANUAL" <<'PY'
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
record = json.loads(source.read_text())
record["manualAcceptance"]["modelSetup"] = "passed"
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects bare passed manual acceptance" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_BARE_MANUAL" --report "$ARTIFACT_DIR/release-validation-bare-manual.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-bare-manual.properties" "manual-modelSetup-evidence-record-invalid"
VALIDATION_WEAK_MANUAL="$TMP_DIR/release-validation-weak-manual.json"
VALIDATION_WEAK_MANUAL_EVIDENCE="$TMP_DIR/validation-manual-evidence/weak-modelSetup.properties"
cat > "$VALIDATION_WEAK_MANUAL_EVIDENCE" <<'VALIDATION_WEAK_MANUAL_EVIDENCE_PROPERTIES'
status=passed
manual=modelSetup
VALIDATION_WEAK_MANUAL_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_MANUAL" "$VALIDATION_WEAK_MANUAL_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["manualAcceptance"]["modelSetup"]["evidencePath"] = str(evidence)
record["manualAcceptance"]["modelSetup"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak manual acceptance evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_MANUAL" --report "$ARTIFACT_DIR/release-validation-weak-manual.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-manual.properties" "manual-modelSetup-evidence-target-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-manual.properties" "manual-modelSetup-evidence-key-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-manual.properties" "manual-modelSetup-evidence-manual-acceptance-not-true"
VALIDATION_MANUAL_MISSING_AUDIT="$TMP_DIR/release-validation-manual-missing-audit.json"
VALIDATION_MANUAL_MISSING_AUDIT_EVIDENCE="$TMP_DIR/validation-manual-evidence/modelSetup-missing-audit.properties"
grep -Ev '^(artifactSchema|recordedAt|command|reproduciblePath)=' \
  "$TMP_DIR/validation-manual-evidence/modelSetup.properties" > "$VALIDATION_MANUAL_MISSING_AUDIT_EVIDENCE"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_MANUAL_MISSING_AUDIT" "$VALIDATION_MANUAL_MISSING_AUDIT_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["manualAcceptance"]["modelSetup"]["evidencePath"] = str(evidence)
record["manualAcceptance"]["modelSetup"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects manual evidence missing audit fields" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MANUAL_MISSING_AUDIT" --report "$ARTIFACT_DIR/release-validation-manual-missing-audit.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-missing-audit.properties" "manual-modelSetup-evidence-artifact-schema-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-missing-audit.properties" "manual-modelSetup-evidence-recorded-at-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-missing-audit.properties" "manual-modelSetup-evidence-command-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-missing-audit.properties" "manual-modelSetup-evidence-reproducible-path-invalid"
VALIDATION_MANUAL_MISSING_RECORD_BINDING="$TMP_DIR/release-validation-manual-missing-record-binding.json"
VALIDATION_MANUAL_MISSING_RECORD_BINDING_EVIDENCE="$TMP_DIR/validation-manual-evidence/modelSetup-missing-record-binding.properties"
grep -Ev '^validationRecordFile=' \
  "$TMP_DIR/validation-manual-evidence/modelSetup.properties" > "$VALIDATION_MANUAL_MISSING_RECORD_BINDING_EVIDENCE"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_MANUAL_MISSING_RECORD_BINDING" "$VALIDATION_MANUAL_MISSING_RECORD_BINDING_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["manualAcceptance"]["modelSetup"]["evidencePath"] = str(evidence)
record["manualAcceptance"]["modelSetup"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects manual evidence missing validation record binding" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MANUAL_MISSING_RECORD_BINDING" --report "$ARTIFACT_DIR/release-validation-manual-missing-record-binding.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-missing-record-binding.properties" "manual-modelSetup-evidence-validation-record-file-missing"
VALIDATION_MANUAL_MISSING_CONTRACT="$TMP_DIR/release-validation-manual-missing-contract.json"
VALIDATION_MANUAL_MISSING_CONTRACT_EVIDENCE="$TMP_DIR/validation-manual-evidence/mediaProjection-missing-contract.properties"
grep -Ev '^systemMediaProjectionPromptObserved=' \
  "$TMP_DIR/validation-manual-evidence/mediaProjection.properties" > "$VALIDATION_MANUAL_MISSING_CONTRACT_EVIDENCE"
write_validation_record_with_manual_evidence \
  "$VALIDATION_APPROVED" \
  "$VALIDATION_MANUAL_MISSING_CONTRACT" \
  mediaProjection \
  "$VALIDATION_MANUAL_MISSING_CONTRACT_EVIDENCE"
expect_failure \
  "release validation verifier rejects manual evidence missing key-specific contract" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MANUAL_MISSING_CONTRACT" --report "$ARTIFACT_DIR/release-validation-manual-missing-contract.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-missing-contract.properties" "manual-mediaProjection-evidence-system-media-projection-prompt-missing"
VALIDATION_MANUAL_FALSE_CONTRACT="$TMP_DIR/release-validation-manual-false-contract.json"
VALIDATION_MANUAL_FALSE_CONTRACT_EVIDENCE="$TMP_DIR/validation-manual-evidence/toolConfirmation-false-contract.properties"
sed 's/^toolCancelPreventsExecution=true$/toolCancelPreventsExecution=false/' \
  "$TMP_DIR/validation-manual-evidence/toolConfirmation.properties" > "$VALIDATION_MANUAL_FALSE_CONTRACT_EVIDENCE"
write_validation_record_with_manual_evidence \
  "$VALIDATION_APPROVED" \
  "$VALIDATION_MANUAL_FALSE_CONTRACT" \
  toolConfirmation \
  "$VALIDATION_MANUAL_FALSE_CONTRACT_EVIDENCE"
expect_failure \
  "release validation verifier rejects false manual key-specific contract" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MANUAL_FALSE_CONTRACT" --report "$ARTIFACT_DIR/release-validation-manual-false-contract.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-false-contract.properties" "manual-toolConfirmation-evidence-tool-cancel-prevents-execution-missing"
VALIDATION_MANUAL_NEGATIVE_CONTRACT="$TMP_DIR/release-validation-manual-negative-contract.json"
VALIDATION_MANUAL_NEGATIVE_CONTRACT_EVIDENCE="$TMP_DIR/validation-manual-evidence/remoteModePrivacy-negative-contract.properties"
sed 's/^remoteRawPrivateContextSent=false$/remoteRawPrivateContextSent=true/' \
  "$TMP_DIR/validation-manual-evidence/remoteModePrivacy.properties" > "$VALIDATION_MANUAL_NEGATIVE_CONTRACT_EVIDENCE"
write_validation_record_with_manual_evidence \
  "$VALIDATION_APPROVED" \
  "$VALIDATION_MANUAL_NEGATIVE_CONTRACT" \
  remoteModePrivacy \
  "$VALIDATION_MANUAL_NEGATIVE_CONTRACT_EVIDENCE"
expect_failure \
  "release validation verifier rejects unsafe false manual key-specific contract" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MANUAL_NEGATIVE_CONTRACT" --report "$ARTIFACT_DIR/release-validation-manual-negative-contract.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-negative-contract.properties" "manual-remoteModePrivacy-evidence-remote-raw-private-context-sent-not-false"
VALIDATION_MANUAL_COUNT_CONTRACT="$TMP_DIR/release-validation-manual-count-contract.json"
VALIDATION_MANUAL_COUNT_CONTRACT_EVIDENCE="$TMP_DIR/validation-manual-evidence/remoteMultiEvidenceComparison-count-contract.properties"
sed 's/^publicEvidenceCount=2$/publicEvidenceCount=1/' \
  "$TMP_DIR/validation-manual-evidence/remoteMultiEvidenceComparison.properties" > "$VALIDATION_MANUAL_COUNT_CONTRACT_EVIDENCE"
write_validation_record_with_manual_evidence \
  "$VALIDATION_APPROVED" \
  "$VALIDATION_MANUAL_COUNT_CONTRACT" \
  remoteMultiEvidenceComparison \
  "$VALIDATION_MANUAL_COUNT_CONTRACT_EVIDENCE"
expect_failure \
  "release validation verifier rejects low public evidence comparison count" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MANUAL_COUNT_CONTRACT" --report "$ARTIFACT_DIR/release-validation-manual-count-contract.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-count-contract.properties" "manual-remoteMultiEvidenceComparison-evidence-public-evidence-count-too-low"
VALIDATION_EMULATOR_BAD_SHA="$TMP_DIR/release-validation-emulator-bad-sha.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_EMULATOR_BAD_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["emulatorRegression"]["reportSha256"] = "0" * 64
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects emulator report sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_EMULATOR_BAD_SHA" --report "$ARTIFACT_DIR/release-validation-emulator-bad-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-emulator-bad-sha.properties" "emulator-report-sha-mismatch"
VALIDATION_API_BAD_SHA="$TMP_DIR/release-validation-api-bad-sha.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_API_BAD_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["apiMatrix"][0]["evidenceSha256"] = "0" * 64
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects api evidence sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_BAD_SHA" --report "$ARTIFACT_DIR/release-validation-api-bad-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-bad-sha.properties" "api-28-evidence-sha-mismatch"
VALIDATION_API_WEAK_EVIDENCE="$TMP_DIR/release-validation-api-weak-evidence.json"
VALIDATION_API_WEAK_EVIDENCE_FILE="$TMP_DIR/validation-api-evidence/weak-api-28.properties"
cat > "$VALIDATION_API_WEAK_EVIDENCE_FILE" <<'VALIDATION_API_WEAK_EVIDENCE_PROPERTIES'
status=passed
api_level=28
VALIDATION_API_WEAK_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_API_WEAK_EVIDENCE" "$VALIDATION_API_WEAK_EVIDENCE_FILE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["apiMatrix"][0]["evidencePath"] = str(evidence)
record["apiMatrix"][0]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak api matrix evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_WEAK_EVIDENCE" --report "$ARTIFACT_DIR/release-validation-api-weak-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-weak-evidence.properties" "api-28-evidence-target-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-weak-evidence.properties" "api-28-evidence-test-count-invalid"
VALIDATION_API_MISSING_NESTED="$TMP_DIR/release-validation-api-missing-nested.json"
VALIDATION_API_MISSING_NESTED_FILE="$TMP_DIR/validation-api-evidence/missing-nested-api-28.properties"
cat > "$VALIDATION_API_MISSING_NESTED_FILE" <<VALIDATION_API_MISSING_NESTED_PROPERTIES
status=passed
exit_code=0
target=regression-emulator
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
clean_device=1
source_android_test_count=$SOURCE_ANDROID_TEST_COUNT
expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT
actual_android_test_count=$SOURCE_ANDROID_TEST_COUNT
serial=emulator-28
api_level=28
abi=arm64-v8a
avd=api28-avd
VALIDATION_API_MISSING_NESTED_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_API_MISSING_NESTED" "$VALIDATION_API_MISSING_NESTED_FILE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["apiMatrix"][0]["evidencePath"] = str(evidence)
record["apiMatrix"][0]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects api matrix evidence without nested reports" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_MISSING_NESTED" --report "$ARTIFACT_DIR/release-validation-api-missing-nested.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-missing-nested.properties" "api-28-evidence-instrumentation-output-file-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-missing-nested.properties" "api-28-emulator-report-path-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-missing-nested.properties" "api-28-device-report-path-missing"
VALIDATION_API_MISSING_SMOKE="$TMP_DIR/release-validation-api-missing-smoke.json"
VALIDATION_API_MISSING_SMOKE_DIR="$TMP_DIR/validation-api-evidence/missing-smoke-api-28"
VALIDATION_API_MISSING_SMOKE_EMULATOR_REPORT="$VALIDATION_API_MISSING_SMOKE_DIR/emulator-verification.properties"
VALIDATION_API_MISSING_SMOKE_EVIDENCE="$TMP_DIR/validation-api-evidence/missing-smoke-api-28.properties"
mkdir -p "$VALIDATION_API_MISSING_SMOKE_DIR"
grep -v '^crash_anr_smoke_report_file=' "$TMP_DIR/validation-api-evidence/api-28/emulator-verification.properties" > "$VALIDATION_API_MISSING_SMOKE_EMULATOR_REPORT"
sed "s#^emulator_report_file=.*#emulator_report_file=$VALIDATION_API_MISSING_SMOKE_EMULATOR_REPORT#" \
  "$TMP_DIR/validation-api-evidence/api-28.properties" > "$VALIDATION_API_MISSING_SMOKE_EVIDENCE"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_API_MISSING_SMOKE" "$VALIDATION_API_MISSING_SMOKE_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["apiMatrix"][0]["evidencePath"] = str(evidence)
record["apiMatrix"][0]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects api matrix nested emulator report without crash smoke evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_MISSING_SMOKE" --report "$ARTIFACT_DIR/release-validation-api-missing-smoke.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-missing-smoke.properties" "api-28-emulator-report-crash-anr-smoke-path-missing"
VALIDATION_MANUAL_BAD_SHA="$TMP_DIR/release-validation-manual-bad-sha.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_MANUAL_BAD_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["manualAcceptance"]["modelSetup"]["evidenceSha256"] = "0" * 64
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects manual evidence sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MANUAL_BAD_SHA" --report "$ARTIFACT_DIR/release-validation-manual-bad-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-bad-sha.properties" "manual-modelSetup-evidence-sha-mismatch"
VALIDATION_PERF_WEAK_EVIDENCE="$TMP_DIR/release-validation-perf-weak-evidence.json"
VALIDATION_PERF_WEAK_EVIDENCE_FILE="$TMP_DIR/validation-performance-evidence/weak-firstLaunch.properties"
cat > "$VALIDATION_PERF_WEAK_EVIDENCE_FILE" <<'VALIDATION_PERF_WEAK_EVIDENCE_PROPERTIES'
status=passed
performance=firstLaunch
VALIDATION_PERF_WEAK_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_PERF_WEAK_EVIDENCE" "$VALIDATION_PERF_WEAK_EVIDENCE_FILE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["performanceSanity"]["firstLaunch"]["evidencePath"] = str(evidence)
record["performanceSanity"]["firstLaunch"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak performance sanity evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_PERF_WEAK_EVIDENCE" --report "$ARTIFACT_DIR/release-validation-perf-weak-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-weak-evidence.properties" "performance-firstLaunch-evidence-target-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-weak-evidence.properties" "performance-firstLaunch-evidence-key-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-weak-evidence.properties" "performance-firstLaunch-evidence-expected-artifact-sha-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-weak-evidence.properties" "performance-firstLaunch-evidence-expected-app-version-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-weak-evidence.properties" "performance-firstLaunch-evidence-baseline-file-missing"
VALIDATION_PERF_KEY_MISMATCH="$TMP_DIR/release-validation-perf-key-mismatch.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_PERF_KEY_MISMATCH" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
first_launch_evidence = Path(record["performanceSanity"]["firstLaunch"]["evidencePath"])
record["performanceSanity"]["modelLoad"]["evidencePath"] = str(first_launch_evidence)
record["performanceSanity"]["modelLoad"]["evidenceSha256"] = hashlib.sha256(first_launch_evidence.read_bytes()).hexdigest()
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects performance evidence key mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_PERF_KEY_MISMATCH" --report "$ARTIFACT_DIR/release-validation-perf-key-mismatch.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-key-mismatch.properties" "performance-modelLoad-evidence-key-mismatch"
VALIDATION_PERF_BASELINE_SHA_MISMATCH="$TMP_DIR/release-validation-perf-baseline-sha-mismatch.json"
VALIDATION_PERF_BASELINE_SHA_MISMATCH_EVIDENCE="$TMP_DIR/validation-performance-evidence/firstLaunch-sha-mismatch.properties"
cat > "$VALIDATION_PERF_BASELINE_SHA_MISMATCH_EVIDENCE" <<VALIDATION_PERF_BASELINE_SHA_MISMATCH_EVIDENCE_PROPERTIES
status=passed
target=perf-baseline
performanceKey=firstLaunch
baselineFile=$VALIDATION_PERF_BASELINE
baselineSha256=$VALIDATION_PERF_BASELINE_SHA
missingFieldCount=0
expectedArtifactSha256=2222222222222222222222222222222222222222222222222222222222222222
expectedAppVersion=0.1.0
maxRecordAgeDays=30
VALIDATION_PERF_BASELINE_SHA_MISMATCH_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_PERF_BASELINE_SHA_MISMATCH" "$VALIDATION_PERF_BASELINE_SHA_MISMATCH_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["performanceSanity"]["firstLaunch"]["evidencePath"] = str(evidence)
record["performanceSanity"]["firstLaunch"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects performance baseline artifact sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_PERF_BASELINE_SHA_MISMATCH" --report "$ARTIFACT_DIR/release-validation-perf-baseline-sha-mismatch.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-baseline-sha-mismatch.properties" "performance-firstLaunch-evidence-baseline-artifact-sha-mismatch"
VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH="$TMP_DIR/release-validation-perf-baseline-file-sha-mismatch.json"
VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH_EVIDENCE="$TMP_DIR/validation-performance-evidence/firstLaunch-file-sha-mismatch.properties"
cat > "$VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH_EVIDENCE" <<VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH_EVIDENCE_PROPERTIES
status=passed
target=perf-baseline
performanceKey=firstLaunch
baselineFile=$VALIDATION_PERF_BASELINE
baselineSha256=0000000000000000000000000000000000000000000000000000000000000000
missingFieldCount=0
expectedArtifactSha256=$VALIDATION_RELEASE_ARTIFACT_SHA
expectedAppVersion=0.1.0
maxRecordAgeDays=30
VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH_EVIDENCE_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH" "$VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
evidence = Path(sys.argv[3])
record = json.loads(source.read_text())
record["performanceSanity"]["firstLaunch"]["evidencePath"] = str(evidence)
record["performanceSanity"]["firstLaunch"]["evidenceSha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects performance baseline file sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_PERF_BASELINE_FILE_SHA_MISMATCH" --report "$ARTIFACT_DIR/release-validation-perf-baseline-file-sha-mismatch.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-perf-baseline-file-sha-mismatch.properties" "performance-firstLaunch-evidence-baseline-sha-mismatch"
VALIDATION_SCREENSHOT_BAD_SHA="$TMP_DIR/release-validation-screenshot-bad-sha.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_SCREENSHOT_BAD_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["screenshots"][0]["sha256"] = "0" * 64
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects screenshot sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_SCREENSHOT_BAD_SHA" --report "$ARTIFACT_DIR/release-validation-screenshot-bad-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-screenshot-bad-sha.properties" "chat-home-screenshot-sha-mismatch"
VALIDATION_SCREENSHOT_NOT_PNG="$TMP_DIR/release-validation-screenshot-not-png.json"
VALIDATION_SCREENSHOT_NOT_PNG_FILE="$TMP_DIR/validation-screenshots/not-png-chat-home.png"
VALIDATION_SCREENSHOT_NOT_PNG_REPORT="$TMP_DIR/release-screenshots-not-png.properties"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_SCREENSHOT_NOT_PNG" "$VALIDATION_SCREENSHOT_NOT_PNG_FILE" "$VALIDATION_SCREENSHOT_NOT_PNG_REPORT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
screenshot = Path(sys.argv[3])
report = Path(sys.argv[4])
record = json.loads(source.read_text())
screenshot.write_text("not a png\n")
screenshot_sha = hashlib.sha256(screenshot.read_bytes()).hexdigest()
ui_dump = screenshot.with_suffix(".xml")
ui_dump.write_text(
    "\n".join(
        [
            "<hierarchy>",
            '  <node text="Solin" />',
            '  <node text="隐私优先的随身 AI 助手" />',
            '  <node text="为什么装它" />',
            '  <node text="模型管理" />',
            "</hierarchy>",
            "",
        ]
    )
)
ui_dump_sha = hashlib.sha256(ui_dump.read_bytes()).hexdigest()
report.write_text(
    "\n".join(
        [
            "status=passed",
            "exit_code=0",
            "target=release-screenshots",
            "clean_device=1",
            "serial=emulator-5554",
            "api_level=36",
            "abi=arm64-v8a",
            "avd=test-avd",
            f"screenshot.chat-home.path={screenshot}",
            f"screenshot.chat-home.sha256={screenshot_sha}",
            "screenshot.chat-home.sanitized=true",
            f"screenshot.chat-home.uiDump={ui_dump}",
            f"screenshot.chat-home.uiDumpSha256={ui_dump_sha}",
            "screenshot.chat-home.visualRegression=passed",
            "screenshot.chat-home.requiredText=Solin|隐私优先的随身 AI 助手|为什么装它|模型管理",
            "",
        ]
    )
)
record["screenshots"][0]["path"] = str(screenshot)
record["screenshots"][0]["sha256"] = screenshot_sha
record["screenshots"][0]["reportPath"] = str(report)
record["screenshots"][0]["reportSha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects non-png screenshot evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_SCREENSHOT_NOT_PNG" --report "$ARTIFACT_DIR/release-validation-screenshot-not-png.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-screenshot-not-png.properties" "chat-home-screenshot-not-png"
VALIDATION_SCREENSHOT_WEAK_VISUAL="$TMP_DIR/release-validation-screenshot-weak-visual.json"
VALIDATION_SCREENSHOT_WEAK_VISUAL_REPORT="$TMP_DIR/release-screenshots-weak-visual.properties"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_SCREENSHOT_WEAK_VISUAL" "$VALIDATION_SCREENSHOT_REPORT" "$VALIDATION_SCREENSHOT_WEAK_VISUAL_REPORT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
report = Path(sys.argv[3])
weak_report = Path(sys.argv[4])
record = json.loads(source.read_text())
weak_lines = [
    line
    for line in report.read_text().splitlines()
    if not line.startswith("screenshot.chat-home.uiDump")
    and not line.startswith("screenshot.chat-home.uiDumpSha256")
    and not line.startswith("screenshot.chat-home.visualRegression")
]
weak_report.write_text("\n".join(weak_lines) + "\n")
record["screenshots"][0]["reportPath"] = str(weak_report)
record["screenshots"][0]["reportSha256"] = hashlib.sha256(weak_report.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects screenshot report without visual contract" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_SCREENSHOT_WEAK_VISUAL" --report "$ARTIFACT_DIR/release-validation-screenshot-weak-visual.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-screenshot-weak-visual.properties" "chat-home-screenshot-visual-regression-not-passed"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-screenshot-weak-visual.properties" "chat-home-screenshot-ui-dump-missing"
VALIDATION_MISSING_DEVICE="$TMP_DIR/release-validation-missing-device.json"
sed 's#"reportPath": "'"$VALIDATION_DEVICE_REPORT"'"#"reportPath": "'"$TMP_DIR/missing-device.properties"'"#' "$VALIDATION_APPROVED" > "$VALIDATION_MISSING_DEVICE"
expect_failure \
  "release validation verifier rejects missing physical report" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MISSING_DEVICE" --report "$ARTIFACT_DIR/release-validation-missing-device.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-missing-device.properties" "status=failed"
VALIDATION_WEAK_DEVICE_REPORT_RECORD="$TMP_DIR/release-validation-weak-device-report.json"
VALIDATION_WEAK_DEVICE_REPORT="$TMP_DIR/weak-device-verification.properties"
cat > "$VALIDATION_WEAK_DEVICE_REPORT" <<VALIDATION_WEAK_DEVICE_REPORT_PROPERTIES
status=passed
target=device
serial=device-a
api_level=36
abi=arm64-v8a
clean_device=1
instrumentation=passed
instrumentation_test_count=$SOURCE_ANDROID_TEST_COUNT
VALIDATION_WEAK_DEVICE_REPORT_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_WEAK_DEVICE_REPORT_RECORD" "$VALIDATION_WEAK_DEVICE_REPORT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
report = Path(sys.argv[3])
record = json.loads(source.read_text())
record["physicalDevice"]["reportPath"] = str(report)
record["physicalDevice"]["reportSha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects weak physical device report" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_WEAK_DEVICE_REPORT_RECORD" --report "$ARTIFACT_DIR/release-validation-weak-device-report.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-device-report.properties" "physical-device-report-exit-code-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-device-report.properties" "physical-device-report-started-at-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-device-report.properties" "physical-device-report-data-free-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-device-report.properties" "physical-device-report-instrumentation-output-file-missing"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-weak-device-report.properties" "physical-device-report-release-main-shell-not-verified"
VALIDATION_MISSING_MAIN_SHELL_RECORD="$TMP_DIR/release-validation-missing-main-shell.json"
VALIDATION_MISSING_MAIN_SHELL_REPORT="$TMP_DIR/release-validation-missing-main-shell.properties"
grep -Ev '^release_main_shell_(verified|start_output_file|start_output_sha256|activity_dump_file|activity_dump_sha256|window_dump_file|window_dump_sha256|ui_dump_file|ui_dump_sha256|screenshot_file|screenshot_sha256)=' \
  "$VALIDATION_DEVICE_REPORT" > "$VALIDATION_MISSING_MAIN_SHELL_REPORT"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_MISSING_MAIN_SHELL_RECORD" "$VALIDATION_MISSING_MAIN_SHELL_REPORT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
report = Path(sys.argv[3])
record = json.loads(source.read_text())
record["physicalDevice"]["reportPath"] = str(report)
record["physicalDevice"]["reportSha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects physical report without release main shell evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MISSING_MAIN_SHELL_RECORD" --report "$ARTIFACT_DIR/release-validation-missing-main-shell.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-missing-main-shell.properties" "physical-device-report-release-main-shell-not-verified"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-missing-main-shell.properties" "physical-device-report-release-main-shell-ui-dump-file-missing"
VALIDATION_MANUAL_INSTALL_AS_DEVICE_RECORD="$TMP_DIR/release-validation-manual-install-as-device.json"
VALIDATION_MANUAL_INSTALL_REPORT="$TMP_DIR/manual-acceptance-install-device.properties"
cat > "$VALIDATION_MANUAL_INSTALL_REPORT" <<VALIDATION_MANUAL_INSTALL_REPORT_PROPERTIES
status=passed
exit_code=0
target=manual-acceptance-install
manualAcceptanceInstall=true
regressionEvidence=false
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=device-a
api_level=36
abi=arm64-v8a
clean_device=0
kept_app_data=1
instrumentation=not-run
VALIDATION_MANUAL_INSTALL_REPORT_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_MANUAL_INSTALL_AS_DEVICE_RECORD" "$VALIDATION_MANUAL_INSTALL_REPORT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
report = Path(sys.argv[3])
record = json.loads(source.read_text())
record["physicalDevice"]["reportPath"] = str(report)
record["physicalDevice"]["reportSha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects manual install report as physical regression evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MANUAL_INSTALL_AS_DEVICE_RECORD" --report "$ARTIFACT_DIR/release-validation-manual-install-as-device.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-install-as-device.properties" "physical-device-report-target-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-install-as-device.properties" "physical-device-report-instrumentation-not-passed"
VALIDATION_DEBUG_APK_AS_PHYSICAL="$TMP_DIR/release-validation-debug-apk-as-physical.json"
VALIDATION_DEBUG_APK_AS_PHYSICAL_REPORT="$TMP_DIR/debug-apk-as-physical.properties"
cat > "$VALIDATION_DEBUG_APK_AS_PHYSICAL_REPORT" <<VALIDATION_DEBUG_APK_AS_PHYSICAL_PROPERTIES
status=passed
exit_code=0
target=device
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=device-a
api_level=36
abi=arm64-v8a
clean_device=1
reset_app_data_after_tests=0
data_free_kb=4194304
instrumentation=passed
instrumentation_test_count=$SOURCE_ANDROID_TEST_COUNT
test_count=$SOURCE_ANDROID_TEST_COUNT
app_apk_mode=debug
app_apk=app/build/outputs/apk/debug/app-debug.apk
app_apk_sha256=$VALIDATION_RELEASE_ARTIFACT_SHA
releaseArtifactType=apk
releaseArtifactSha256=$VALIDATION_RELEASE_ARTIFACT_SHA
instrumentation_output_file=$VALIDATION_INSTRUMENTATION_OUTPUT
instrumentation_output_sha256=$(shasum -a 256 "$VALIDATION_INSTRUMENTATION_OUTPUT" | awk '{print $1}')
logcat_file=$VALIDATION_EMULATOR_LOGCAT
logcat_sha256=$(shasum -a 256 "$VALIDATION_EMULATOR_LOGCAT" | awk '{print $1}')
android_test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
VALIDATION_DEBUG_APK_AS_PHYSICAL_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_DEBUG_APK_AS_PHYSICAL" "$VALIDATION_DEBUG_APK_AS_PHYSICAL_REPORT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
report = Path(sys.argv[3])
record = json.loads(source.read_text())
record["physicalDevice"]["reportPath"] = str(report)
record["physicalDevice"]["reportSha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects debug apk physical release evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_DEBUG_APK_AS_PHYSICAL" --report "$ARTIFACT_DIR/release-validation-debug-apk-as-physical.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-debug-apk-as-physical.properties" "physical-device-report-app-apk-mode-not-release"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-debug-apk-as-physical.properties" "physical-device-report-debug-apk-installed"
VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH="$TMP_DIR/release-validation-device-output-count-mismatch.json"
VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_REPORT="$TMP_DIR/device-output-count-mismatch.properties"
VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_OUTPUT="$TMP_DIR/instrumentation-count-mismatch.txt"
printf 'OK (1 test)\n' > "$VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_OUTPUT"
cat > "$VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_REPORT" <<VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_PROPERTIES
status=passed
exit_code=0
target=device
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=device-a
api_level=36
abi=arm64-v8a
clean_device=1
data_free_kb=4194304
instrumentation=passed
instrumentation_test_count=$SOURCE_ANDROID_TEST_COUNT
instrumentation_output_file=$VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_OUTPUT
debug_apk=app/build/outputs/apk/debug/app-debug.apk
android_test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH" "$VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH_REPORT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
report = Path(sys.argv[3])
record = json.loads(source.read_text())
record["physicalDevice"]["reportPath"] = str(report)
record["physicalDevice"]["reportSha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects physical report output count mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_DEVICE_OUTPUT_COUNT_MISMATCH" --report "$ARTIFACT_DIR/release-validation-device-output-count-mismatch.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-device-output-count-mismatch.properties" "physical-device-report-instrumentation-output-count-mismatch"
VALIDATION_DEVICE_CONFLICTING_OUTPUT="$TMP_DIR/release-validation-device-conflicting-output.json"
VALIDATION_DEVICE_CONFLICTING_OUTPUT_REPORT="$TMP_DIR/device-conflicting-output.properties"
VALIDATION_DEVICE_CONFLICTING_INSTRUMENTATION="$TMP_DIR/instrumentation-conflicting.txt"
printf 'FAILURES!!!\nTests run: %s,  Failures: 1\nOK (%s tests)\n' "$SOURCE_ANDROID_TEST_COUNT" "$SOURCE_ANDROID_TEST_COUNT" > "$VALIDATION_DEVICE_CONFLICTING_INSTRUMENTATION"
cat > "$VALIDATION_DEVICE_CONFLICTING_OUTPUT_REPORT" <<VALIDATION_DEVICE_CONFLICTING_OUTPUT_PROPERTIES
status=passed
exit_code=0
target=device
failedTarget=
reason=
started_at_utc=2026-06-06T00:00:00Z
finished_at_utc=2026-06-06T00:01:00Z
serial=device-a
api_level=36
abi=arm64-v8a
clean_device=1
data_free_kb=4194304
instrumentation=passed
instrumentation_test_count=$SOURCE_ANDROID_TEST_COUNT
instrumentation_output_file=$VALIDATION_DEVICE_CONFLICTING_INSTRUMENTATION
instrumentation_output_sha256=$(shasum -a 256 "$VALIDATION_DEVICE_CONFLICTING_INSTRUMENTATION" | awk '{print $1}')
logcat_file=$VALIDATION_EMULATOR_LOGCAT
logcat_sha256=$(shasum -a 256 "$VALIDATION_EMULATOR_LOGCAT" | awk '{print $1}')
debug_apk=app/build/outputs/apk/debug/app-debug.apk
android_test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
VALIDATION_DEVICE_CONFLICTING_OUTPUT_PROPERTIES
python3 - "$VALIDATION_APPROVED" "$VALIDATION_DEVICE_CONFLICTING_OUTPUT" "$VALIDATION_DEVICE_CONFLICTING_OUTPUT_REPORT" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
report = Path(sys.argv[3])
record = json.loads(source.read_text())
record["physicalDevice"]["reportPath"] = str(report)
record["physicalDevice"]["reportSha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects contradictory physical instrumentation output" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_DEVICE_CONFLICTING_OUTPUT" --report "$ARTIFACT_DIR/release-validation-device-conflicting-output.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-device-conflicting-output.properties" "physical-device-report-instrumentation-output-failure-marker"
VALIDATION_EMULATOR_AS_PHYSICAL="$TMP_DIR/release-validation-emulator-as-physical.json"
sed \
  -e 's#"reportPath": "'"$VALIDATION_DEVICE_REPORT"'"#"reportPath": "'"$VALIDATION_EMULATOR_DEVICE_REPORT"'"#' \
  -e 's/"serial": "device-a"/"serial": "emulator-5554"/' \
  "$VALIDATION_APPROVED" > "$VALIDATION_EMULATOR_AS_PHYSICAL"
expect_failure \
  "release validation verifier rejects emulator device report as physical evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_EMULATOR_AS_PHYSICAL" --report "$ARTIFACT_DIR/release-validation-emulator-as-physical.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-emulator-as-physical.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-emulator-as-physical.properties" "physical-device-serial-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-emulator-as-physical.properties" "physical-device-report-serial-is-emulator"
VALIDATION_API_GAP="$TMP_DIR/release-validation-api-gap.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_API_GAP" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
for entry in record["apiMatrix"]:
    if entry.get("apiLevel") == 34:
        entry["status"] = "pending"
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects incomplete api matrix" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_GAP" --report "$ARTIFACT_DIR/release-validation-api-gap.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-api-gap.properties" "status=failed"
VALIDATION_API_MISSING_EVIDENCE="$TMP_DIR/release-validation-api-missing-evidence.json"
sed 's#'"$TMP_DIR"'/validation-api-evidence/api-34.properties#'"$TMP_DIR"'/validation-api-evidence/missing-api-34.properties#' "$VALIDATION_APPROVED" > "$VALIDATION_API_MISSING_EVIDENCE"
expect_failure \
  "release validation verifier rejects missing api evidence file" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_MISSING_EVIDENCE" --report "$ARTIFACT_DIR/release-validation-api-missing-evidence.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-api-missing-evidence.properties" "status=failed"
VALIDATION_UNSANITIZED_SCREENSHOT="$TMP_DIR/release-validation-unsanitized-screenshot.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_UNSANITIZED_SCREENSHOT" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
for entry in record["screenshots"]:
    if entry.get("name") == "chat-home":
        entry["sanitized"] = False
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects unsanitized screenshots" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_UNSANITIZED_SCREENSHOT" --report "$ARTIFACT_DIR/release-validation-unsanitized.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-unsanitized.properties" "status=failed"
