# shellcheck shell=bash
# Validation script tests: doctor, device-control and real-app search eval scripts.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

reset_logs
RC_COLLECT_REPORT="$ARTIFACT_DIR/rc-collect.properties"
RC_COLLECT_BASELINE="$ARTIFACT_DIR/rc-collect-baseline.properties"
expect_success \
  "rc perf collector records a verifiable baseline from a real harness run" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  ADB="$FAKE_SDK/platform-tools/adb" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  RELEASE_ARTIFACT="$SAFE_APK" APP_VERSION=1.0 \
  SKIP_BUILD=1 SKIP_INSTALL=1 \
  RC_PERF_APK="$SAFE_APK" \
  HARNESS_TIMEOUT_SECONDS=30 \
  FAKE_RC_PERF_TOTAL_TIME_MS=1200 \
  FAKE_RC_PERF_MEMINFO_KB=524288 \
  FAKE_RC_PERF_RESULT_READY=1 \
  FAKE_RC_PERF_MODEL_ID=chat-e2b \
  FAKE_RC_PERF_BACKEND=GPU \
  FAKE_RC_PERF_TOKENS_PER_SECOND=12.5 \
  REPORT_FILE="$RC_COLLECT_REPORT" \
  OUT_FILE="$RC_COLLECT_BASELINE" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/collect_rc_perf_from_device.sh
assert_no_gradle_call
assert_report_contains "$RC_COLLECT_REPORT" "artifactSchema=RcPerfCollection/v1"
assert_report_contains "$RC_COLLECT_REPORT" "status=passed"
assert_report_contains "$RC_COLLECT_REPORT" "target=rc-perf-collector"
assert_report_contains "$RC_COLLECT_REPORT" "serial=device-a"
assert_report_contains "$RC_COLLECT_REPORT" "memoryPeakMb=512"
assert_report_contains "$RC_COLLECT_REPORT" "oomOrAnrObserved=false"
assert_report_contains "$RC_COLLECT_REPORT" "runner=rc_perf_release_broadcast"
assert_report_contains "$RC_COLLECT_REPORT" "preserves_model_data=true"
assert_report_contains "$RC_COLLECT_BASELINE" "artifactSchema=PerfBaseline/v1"
assert_report_contains "$RC_COLLECT_BASELINE" "target=perf-baseline-record"
assert_report_contains "$RC_COLLECT_BASELINE" "status=passed"
assert_report_contains "$RC_COLLECT_BASELINE" "tokensPerSecond=12.5"
assert_report_contains "$RC_COLLECT_BASELINE" "backend=GPU"
assert_report_contains "$RC_COLLECT_BASELINE" "modelId=chat-e2b"
assert_report_contains "$RC_COLLECT_BASELINE.verification.properties" "artifactSchema=PerfBaselineVerification/v1"
assert_report_contains "$RC_COLLECT_BASELINE.verification.properties" "status=passed"
grep -q -- "-s device-a shell am start-foreground-service -a com.bytedance.zgx.solin.rcperf.RUN -n com.bytedance.zgx.solin/.rcperf.RcPerfHarnessService --es requestId" "$FAKE_ADB_LOG" ||
  fail "rc perf collector must trigger the harness service with the app-specific action"
if grep -q -- "android.intent.action.RUN" "$FAKE_ADB_LOG"; then
  fail "rc perf collector must not send the generic RUN broadcast action"
fi
if grep -Eq -- "deepseekApiKey|deepseekBaseUrl" "$FAKE_ADB_LOG"; then
  fail "rc perf collector must not pass unused remote secret extras"
fi
if grep -q -- "pm clear com.bytedance.zgx.solin" "$FAKE_ADB_LOG"; then
  fail "rc perf collector must not clear app data"
fi
if grep -Eq -- "(rm|unlink|delete).*(model|litertlm)" "$FAKE_ADB_LOG"; then
  fail "rc perf collector must not delete model files"
fi

expect_success \
  "doctor local without adb" \
  env ANDROID_SDK_ROOT="$NO_ADB_SDK" ANDROID_HOME="$NO_ADB_SDK" \
  scripts/doctor.sh --local

expect_failure \
  "doctor device without adb" \
  env ANDROID_SDK_ROOT="$NO_ADB_SDK" ANDROID_HOME="$NO_ADB_SDK" \
  scripts/doctor.sh --device

reset_logs
expect_failure \
  "device control debug eval reports selected serial failure with target and reason" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-b" \
  REPORT_FILE="$ARTIFACT_DIR/device-control-debug-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/device-control-debug-logcat.txt" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_device_control_debug_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/device-control-debug-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-control-debug-eval.properties" "target=device-control-debug-eval"
assert_report_contains "$ARTIFACT_DIR/device-control-debug-eval.properties" "failedTarget=device-selection"
assert_report_contains "$ARTIFACT_DIR/device-control-debug-eval.properties" "reason=selected-device-unavailable"
assert_report_contains "$ARTIFACT_DIR/device-control-debug-eval.properties" "serial=device-b"
assert_report_contains "$ARTIFACT_DIR/device-control-debug-eval.properties" "api_level="
assert_report_contains "$ARTIFACT_DIR/device-control-debug-eval.properties" "abi="
grep -Eq '^logcat_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/device-control-debug-eval.properties" ||
  fail "Expected device control selected-serial failure report to bind logcat SHA-256"

reset_logs
expect_failure \
  "real app search eval does not treat bound accessibility service as enabled" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_SOLIN_ACCESSIBILITY_ENABLED=0 AUTO_ENABLE_SOLIN_ACCESSIBILITY=0 \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failedTarget=accessibility"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "reason=solin-accessibility-not-enabled"

reset_logs
expect_failure \
  "real app search eval archives resolver failure evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES="com.taobao.taobao" \
  FAKE_REAL_APP_SEARCH_FAIL_STEP="tap" \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failedTarget=real-app-search-case"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "reason=real-app-search-case-failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "fail_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "skip_count=7"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "case_artifact_schema=RealAppSearchCaseArtifact/v1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "cases=taobao,pdd,gaode,jd,chrome,android_browser,quark,uc"
REAL_APP_CASE_REPORT="$ARTIFACT_DIR/taobao.case.properties"
REAL_APP_RANKED_CANDIDATES="$ARTIFACT_DIR/taobao.ranked-candidates.json"
REAL_APP_TARGET_RESOLUTION="$ARTIFACT_DIR/taobao.target-resolution.properties"
assert_report_contains "$REAL_APP_CASE_REPORT" "artifact_schema=RealAppSearchCaseArtifact/v1"
assert_report_contains "$REAL_APP_CASE_REPORT" "case=taobao"
assert_report_contains "$REAL_APP_CASE_REPORT" "expected_package_name=com.taobao.taobao"
assert_report_contains "$REAL_APP_CASE_REPORT" "expected_app_name=淘宝"
assert_report_contains "$REAL_APP_CASE_REPORT" "status=failed"
assert_report_contains "$REAL_APP_CASE_REPORT" "reason=search_entry_not_found"
assert_report_contains "$REAL_APP_CASE_REPORT" "failure_kind=search_entry_not_found"
assert_report_contains "$REAL_APP_CASE_REPORT" "failed_step=tap"
assert_report_contains "$REAL_APP_CASE_REPORT" "result_file=$ARTIFACT_DIR/taobao-tap.properties"
grep -Eq '^result_file_sha256=[0-9a-f]{64}$' "$REAL_APP_CASE_REPORT" ||
  fail "Expected real app case report to hash the debug receiver result file"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_available=true"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_kind=search_entry"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_target=搜索入口"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_package_name=com.taobao.taobao"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_failure_kind=search_entry_not_found"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_candidate_count=2"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_candidate_total_count=2"
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_archived_candidate_count=2"
assert_report_contains_text "$REAL_APP_CASE_REPORT" 'target_resolution_candidates_json={"candidates"'
assert_report_contains "$REAL_APP_CASE_REPORT" "target_resolution_evidence_file=$REAL_APP_TARGET_RESOLUTION"
grep -Eq '^target_resolution_evidence_sha256=[0-9a-f]{64}$' "$REAL_APP_CASE_REPORT" ||
  fail "Expected real app case report to hash target resolution evidence"
assert_report_contains "$REAL_APP_CASE_REPORT" "ranked_candidates_file=$REAL_APP_RANKED_CANDIDATES"
grep -Eq '^ranked_candidates_sha256=[0-9a-f]{64}$' "$REAL_APP_CASE_REPORT" ||
  fail "Expected real app case report to hash ranked resolver candidates"
assert_report_contains "$REAL_APP_CASE_REPORT" "step_evidence_count=1"
assert_report_contains "$REAL_APP_CASE_REPORT" "step_tap_result_file=$ARTIFACT_DIR/taobao-tap.properties"
assert_report_contains "$REAL_APP_CASE_REPORT" "step_tap_target_resolution_available=true"
assert_report_contains "$REAL_APP_CASE_REPORT" "step_tap_target_resolution_failure_kind=search_entry_not_found"
assert_report_contains "$REAL_APP_CASE_REPORT" "step_tap_target_resolution_evidence_file=$ARTIFACT_DIR/taobao.tap.target-resolution.properties"
assert_report_contains "$REAL_APP_CASE_REPORT" "step_tap_ranked_candidates_file=$ARTIFACT_DIR/taobao.tap.ranked-candidates.json"
grep -Eq '^step_tap_result_file_sha256=[0-9a-f]{64}$' "$REAL_APP_CASE_REPORT" ||
  fail "Expected real app tap step evidence to hash the result file"
grep -Eq '^step_tap_target_resolution_evidence_sha256=[0-9a-f]{64}$' "$REAL_APP_CASE_REPORT" ||
  fail "Expected real app tap step evidence to hash target resolution evidence"
grep -Eq '^step_tap_ranked_candidates_sha256=[0-9a-f]{64}$' "$REAL_APP_CASE_REPORT" ||
  fail "Expected real app tap step evidence to hash ranked candidates"
assert_report_contains "$REAL_APP_TARGET_RESOLUTION" "artifact_schema=UiTargetResolutionEvidenceArtifact/v1"
assert_report_contains "$REAL_APP_TARGET_RESOLUTION" "case=taobao"
assert_report_contains "$REAL_APP_TARGET_RESOLUTION" "target_resolution_failure_kind=search_entry_not_found"
assert_report_contains "$REAL_APP_TARGET_RESOLUTION" "ranked_candidates_file=$REAL_APP_RANKED_CANDIDATES"
assert_report_contains "$ARTIFACT_DIR/taobao.tap.target-resolution.properties" "artifact_schema=UiTargetResolutionStepEvidenceArtifact/v1"
assert_report_contains "$ARTIFACT_DIR/taobao.tap.target-resolution.properties" "step=tap"
assert_report_contains "$ARTIFACT_DIR/taobao.tap.target-resolution.properties" "target_resolution_failure_kind=search_entry_not_found"
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"label":"搜索推荐"'
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"bounds":{"left":0'
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"clickable":true'
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"editable":false'
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"matchedProfileHint":"搜索"'
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"riskPenalty":360'
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"noisePenalty":0'
assert_report_contains_text "$REAL_APP_RANKED_CANDIDATES" '"finalScore":430'
assert_report_contains "$REAL_APP_CASE_REPORT" "diagnostics_dir=$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap"
assert_report_contains "$REAL_APP_CASE_REPORT" "screenshot_file=$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/screenshot.png"
assert_report_contains "$REAL_APP_CASE_REPORT" "uiautomator_dump_file=$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/uiautomator.xml"
assert_report_contains "$REAL_APP_CASE_REPORT" "focused_window_file=$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/focused-window.txt"
assert_report_contains "$REAL_APP_CASE_REPORT" "window_dump_file=$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/window-dump.txt"
assert_report_contains "$REAL_APP_CASE_REPORT" "case_logcat_file=$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/logcat.txt"
for key in screenshot_sha256 uiautomator_dump_sha256 focused_window_sha256 window_dump_sha256 case_logcat_sha256; do
  grep -Eq "^${key}=[0-9a-f]{64}$" "$REAL_APP_CASE_REPORT" ||
    fail "Expected real app case report to include $key"
done
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/uiautomator.xml" ]] ||
  fail "Expected real app failure diagnostics to preserve a UIAutomator dump"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/screenshot.png" ]] ||
  fail "Expected real app failure diagnostics to preserve a screenshot"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/window-dump.txt" ]] ||
  fail "Expected real app failure diagnostics to preserve a window dump"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-taobao-tap/logcat.txt" ]] ||
  fail "Expected real app failure diagnostics to preserve logcat"

REAL_APP_SOURCE_REPORT="$ARTIFACT_DIR/real-app-search-eval.properties"
REAL_APP_SOURCE_SHA="$(shasum -a 256 "$REAL_APP_SOURCE_REPORT" | awk '{print $1}')"
REAL_APP_EVIDENCE_REPORT="$ARTIFACT_DIR/real-app-search-evidence-verification.properties"
expect_success \
  "real app search evidence verifier accepts archived resolver failure evidence" \
  scripts/verify_real_app_search_report.sh \
    --file "$REAL_APP_SOURCE_REPORT" \
    --report "$REAL_APP_EVIDENCE_REPORT"
assert_release_verifier_report_schema \
  "$REAL_APP_EVIDENCE_REPORT" \
  "RealAppSearchEvidenceVerification/v1" \
  "real-app-control"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "status=passed"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "target=real-app-search-evidence"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "failedTarget="
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "reason="
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "realAppSearchReportFile=$REAL_APP_SOURCE_REPORT"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "realAppSearchReportSha256=$REAL_APP_SOURCE_SHA"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "sourceArtifactSchema=RealAppSearchEvalArtifact/v1"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "sourceStatus=failed"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "sourceFailedTarget=real-app-search-case"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "runCount=1"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "passCount=0"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "failCount=1"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "skipCount=7"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "caseArtifactCount=8"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "failedCaseArtifactCount=1"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "skippedCaseArtifactCount=7"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "rankedCandidatesArtifactCount=2"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "targetResolutionEvidenceCount=2"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "diagnosticsArtifactCount=5"
assert_report_contains "$REAL_APP_EVIDENCE_REPORT" "failureKindBreakdown=search_entry_not_found:1"

REAL_APP_CASE_REPORT_TAMPERED="$(mktemp)"
sed 's/^ranked_candidates_sha256=.*/ranked_candidates_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$REAL_APP_CASE_REPORT" > "$REAL_APP_CASE_REPORT_TAMPERED"
mv "$REAL_APP_CASE_REPORT_TAMPERED" "$REAL_APP_CASE_REPORT"
expect_failure \
  "real app search evidence verifier rejects tampered ranked candidates" \
  scripts/verify_real_app_search_report.sh \
    --file "$REAL_APP_SOURCE_REPORT" \
    --report "$ARTIFACT_DIR/real-app-search-evidence-tampered.properties"
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/real-app-search-evidence-tampered.properties" \
  "RealAppSearchEvidenceVerification/v1" \
  "real-app-control"
assert_report_contains "$ARTIFACT_DIR/real-app-search-evidence-tampered.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-evidence-tampered.properties" "failedTarget=real-app-search-target-resolution-evidence"
assert_report_contains_text "$ARTIFACT_DIR/real-app-search-evidence-tampered.properties" "ranked-candidates-sha-mismatch:taobao"

reset_logs
expect_failure \
  "real app search eval archives editable failure evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES="com.autonavi.minimap" \
  FAKE_REAL_APP_SEARCH_FAIL_STEP="type" \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "reason=real-app-search-case-failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "fail_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "skip_count=7"
GAODE_CASE_REPORT="$ARTIFACT_DIR/gaode.case.properties"
GAODE_RANKED_CANDIDATES="$ARTIFACT_DIR/gaode.ranked-candidates.json"
GAODE_TARGET_RESOLUTION="$ARTIFACT_DIR/gaode.target-resolution.properties"
assert_report_contains "$GAODE_CASE_REPORT" "artifact_schema=RealAppSearchCaseArtifact/v1"
assert_report_contains "$GAODE_CASE_REPORT" "case=gaode"
assert_report_contains "$GAODE_CASE_REPORT" "expected_package_name=com.autonavi.minimap"
assert_report_contains "$GAODE_CASE_REPORT" "expected_app_name=高德"
assert_report_contains "$GAODE_CASE_REPORT" "status=failed"
assert_report_contains "$GAODE_CASE_REPORT" "reason=editable_not_found"
assert_report_contains "$GAODE_CASE_REPORT" "failure_kind=editable_not_found"
assert_report_contains "$GAODE_CASE_REPORT" "failed_step=type_text"
assert_report_contains "$GAODE_CASE_REPORT" "result_file=$ARTIFACT_DIR/gaode-type.properties"
grep -Eq '^result_file_sha256=[0-9a-f]{64}$' "$GAODE_CASE_REPORT" ||
  fail "Expected Gaode case report to hash the debug receiver result file"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_available=true"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_kind=editable_field"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_target=搜索输入框"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_package_name=com.autonavi.minimap"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_failure_kind=editable_not_found"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_candidate_count=2"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_candidate_total_count=2"
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_archived_candidate_count=2"
assert_report_contains_text "$GAODE_CASE_REPORT" 'target_resolution_candidates_json={"candidates"'
assert_report_contains "$GAODE_CASE_REPORT" "target_resolution_evidence_file=$GAODE_TARGET_RESOLUTION"
grep -Eq '^target_resolution_evidence_sha256=[0-9a-f]{64}$' "$GAODE_CASE_REPORT" ||
  fail "Expected Gaode case report to hash target resolution evidence"
assert_report_contains "$GAODE_CASE_REPORT" "ranked_candidates_file=$GAODE_RANKED_CANDIDATES"
grep -Eq '^ranked_candidates_sha256=[0-9a-f]{64}$' "$GAODE_CASE_REPORT" ||
  fail "Expected Gaode case report to hash ranked resolver candidates"
assert_report_contains "$GAODE_TARGET_RESOLUTION" "artifact_schema=UiTargetResolutionEvidenceArtifact/v1"
assert_report_contains "$GAODE_TARGET_RESOLUTION" "case=gaode"
assert_report_contains "$GAODE_TARGET_RESOLUTION" "target_resolution_failure_kind=editable_not_found"
assert_report_contains "$GAODE_TARGET_RESOLUTION" "ranked_candidates_file=$GAODE_RANKED_CANDIDATES"
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"label":"你要去哪儿 搜地点、公交、地铁"'
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"bounds":{"left":40'
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"clickable":true'
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"editable":false'
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"matchedProfileHint":"你要去哪儿"'
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"riskPenalty":0'
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"noisePenalty":0'
assert_report_contains_text "$GAODE_RANKED_CANDIDATES" '"finalScore":360'
assert_report_contains "$GAODE_CASE_REPORT" "diagnostics_dir=$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type"
assert_report_contains "$GAODE_CASE_REPORT" "screenshot_file=$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/screenshot.png"
assert_report_contains "$GAODE_CASE_REPORT" "uiautomator_dump_file=$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/uiautomator.xml"
assert_report_contains "$GAODE_CASE_REPORT" "focused_window_file=$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/focused-window.txt"
assert_report_contains "$GAODE_CASE_REPORT" "window_dump_file=$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/window-dump.txt"
assert_report_contains "$GAODE_CASE_REPORT" "case_logcat_file=$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/logcat.txt"
for key in screenshot_sha256 uiautomator_dump_sha256 focused_window_sha256 window_dump_sha256 case_logcat_sha256; do
  grep -Eq "^${key}=[0-9a-f]{64}$" "$GAODE_CASE_REPORT" ||
    fail "Expected Gaode case report to include $key"
done
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/uiautomator.xml" ]] ||
  fail "Expected Gaode failure diagnostics to preserve a UIAutomator dump"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/screenshot.png" ]] ||
  fail "Expected Gaode failure diagnostics to preserve a screenshot"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/window-dump.txt" ]] ||
  fail "Expected Gaode failure diagnostics to preserve a window dump"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/assert-gaode-type/logcat.txt" ]] ||
  fail "Expected Gaode failure diagnostics to preserve logcat"

reset_logs
expect_failure \
  "real app search eval archives submit failure evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES="com.jingdong.app.mall" \
  FAKE_REAL_APP_SEARCH_FAIL_STEP="submit" \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "reason=real-app-search-case-failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "fail_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "skip_count=7"
JD_CASE_REPORT="$ARTIFACT_DIR/jd.case.properties"
JD_RANKED_CANDIDATES="$ARTIFACT_DIR/jd.ranked-candidates.json"
JD_TARGET_RESOLUTION="$ARTIFACT_DIR/jd.target-resolution.properties"
assert_report_contains "$JD_CASE_REPORT" "case=jd"
assert_report_contains "$JD_CASE_REPORT" "expected_package_name=com.jingdong.app.mall"
assert_report_contains "$JD_CASE_REPORT" "status=failed"
assert_report_contains "$JD_CASE_REPORT" "reason=submit_not_found"
assert_report_contains "$JD_CASE_REPORT" "failure_kind=submit_not_found"
assert_report_contains "$JD_CASE_REPORT" "failed_step=submit_search"
assert_report_contains "$JD_CASE_REPORT" "result_file=$ARTIFACT_DIR/jd-submit.properties"
grep -Eq '^result_file_sha256=[0-9a-f]{64}$' "$JD_CASE_REPORT" ||
  fail "Expected JD case report to hash the submit result file"
assert_report_contains "$JD_CASE_REPORT" "target_resolution_available=true"
assert_report_contains "$JD_CASE_REPORT" "target_resolution_kind=submit_button"
assert_report_contains "$JD_CASE_REPORT" "target_resolution_selected_node_id="
assert_report_contains "$JD_CASE_REPORT" "target_resolution_failure_kind=submit_not_found"
assert_report_contains "$JD_CASE_REPORT" "target_resolution_candidate_count=1"
assert_report_contains "$JD_CASE_REPORT" "target_resolution_candidate_total_count=1"
assert_report_contains "$JD_CASE_REPORT" "target_resolution_archived_candidate_count=1"
assert_report_contains "$JD_CASE_REPORT" "target_resolution_evidence_file=$JD_TARGET_RESOLUTION"
assert_report_contains "$JD_CASE_REPORT" "ranked_candidates_file=$JD_RANKED_CANDIDATES"
assert_report_contains "$JD_TARGET_RESOLUTION" "target_resolution_failure_kind=submit_not_found"
assert_report_contains "$JD_TARGET_RESOLUTION" "target_resolution_selected_node_id="
assert_report_contains "$JD_TARGET_RESOLUTION" "ranked_candidates_file=$JD_RANKED_CANDIDATES"
assert_report_contains_text "$JD_RANKED_CANDIDATES" '"label":"键盘搜索"'
assert_report_contains_text "$JD_RANKED_CANDIDATES" '"enabled":false'
assert_report_contains_text "$JD_RANKED_CANDIDATES" '"finalScore":180'
assert_report_contains_text "$JD_RANKED_CANDIDATES" '"reason":"matched disabled keyboard action"'
assert_real_app_case_diagnostics "$JD_CASE_REPORT" "$ARTIFACT_DIR/real-app-diagnostics/assert-jd-submit"

reset_logs
expect_failure \
  "real app search eval archives result verification failure evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES="com.android.chrome" \
  FAKE_REAL_APP_SEARCH_FAIL_STEP="verify" \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "reason=real-app-search-case-failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "fail_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "skip_count=7"
CHROME_CASE_REPORT="$ARTIFACT_DIR/chrome.case.properties"
assert_report_contains "$CHROME_CASE_REPORT" "case=chrome"
assert_report_contains "$CHROME_CASE_REPORT" "expected_package_name=com.android.chrome"
assert_report_contains "$CHROME_CASE_REPORT" "status=failed"
assert_report_contains "$CHROME_CASE_REPORT" "reason=result_not_verified"
assert_report_contains "$CHROME_CASE_REPORT" "failure_kind=result_not_verified"
assert_report_contains "$CHROME_CASE_REPORT" "failed_step=verify"
assert_report_contains "$CHROME_CASE_REPORT" "result_file=$ARTIFACT_DIR/chrome-verify.properties"
grep -Eq '^result_file_sha256=[0-9a-f]{64}$' "$CHROME_CASE_REPORT" ||
  fail "Expected Chrome case report to hash the verify result file"
assert_report_contains "$CHROME_CASE_REPORT" "target_resolution_available=false"
assert_real_app_case_diagnostics "$CHROME_CASE_REPORT" "$ARTIFACT_DIR/real-app-diagnostics/assert-chrome-verify"

reset_logs
expect_failure \
  "real app search eval archives required hint failure evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES="com.xunmeng.pinduoduo" \
  FAKE_REAL_APP_SEARCH_FAIL_STEP="required_hint" \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "reason=real-app-search-case-failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "fail_count=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "skip_count=7"
PDD_CASE_REPORT="$ARTIFACT_DIR/pdd.case.properties"
assert_report_contains "$PDD_CASE_REPORT" "case=pdd"
assert_report_contains "$PDD_CASE_REPORT" "expected_package_name=com.xunmeng.pinduoduo"
assert_report_contains "$PDD_CASE_REPORT" "status=failed"
assert_report_contains "$PDD_CASE_REPORT" "reason=required_hint_missing"
assert_report_contains "$PDD_CASE_REPORT" "failure_kind=required_hint_missing"
assert_report_contains "$PDD_CASE_REPORT" "failed_step=verify"
assert_report_contains "$PDD_CASE_REPORT" "result_file=$ARTIFACT_DIR/pdd-verify.properties"
grep -Eq '^result_file_sha256=[0-9a-f]{64}$' "$PDD_CASE_REPORT" ||
  fail "Expected PDD case report to hash the verify result file"
assert_report_contains "$PDD_CASE_REPORT" "target_resolution_available=false"
assert_real_app_case_diagnostics "$PDD_CASE_REPORT" "$ARTIFACT_DIR/real-app-diagnostics/assert-pdd-verify"

reset_logs
expect_failure \
  "real app search eval fails closed when no target apps are installed" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES="" \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failedTarget=target-apps"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "reason=no-target-apps-installed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "api_level=36"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "abi=arm64-v8a,armeabi-v7a"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_count=0"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "pass_count=0"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "fail_count=0"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "skip_count=8"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failure_diagnostics_dir=$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failure_screenshot_file=$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/screenshot.png"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failure_uiautomator_dump_file=$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/uiautomator.xml"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failure_focused_window_file=$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/focused-window.txt"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failure_window_dump_file=$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/window-dump.txt"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "failure_logcat_file=$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/logcat.txt"
for key in failure_screenshot_sha256 failure_uiautomator_dump_sha256 failure_focused_window_sha256 failure_window_dump_sha256 failure_logcat_sha256; do
  grep -Eq "^${key}=[0-9a-f]{64}$" "$ARTIFACT_DIR/real-app-search-eval.properties" ||
    fail "Expected real app search fatal report to include $key"
done
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/screenshot.png" ]] ||
  fail "Expected real app search fatal diagnostics to preserve a screenshot"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/uiautomator.xml" ]] ||
  fail "Expected real app search fatal diagnostics to preserve a UIAutomator dump"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/window-dump.txt" ]] ||
  fail "Expected real app search fatal diagnostics to preserve a window dump"
[[ -s "$ARTIFACT_DIR/real-app-diagnostics/fatal-no-target-apps-installed/logcat.txt" ]] ||
  fail "Expected real app search fatal diagnostics to preserve logcat"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "case=taobao"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "status=skipped"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "reason=package_not_installed:com.taobao.taobao"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "failed_step=package_check"

reset_logs
expect_success \
  "real app search eval passes when all fake target apps verify" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES="com.taobao.taobao,com.xunmeng.pinduoduo,com.autonavi.minimap,com.jingdong.app.mall,com.android.chrome,com.android.browser,com.quark.browser,com.UCMobile" \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "reason="
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_count=8"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "pass_count=8"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "fail_count=0"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "skip_count=0"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "case=taobao"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "result_file=$ARTIFACT_DIR/taobao-verify.properties"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "step_evidence_count=4"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "step_tap_result_file=$ARTIFACT_DIR/taobao-tap.properties"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "step_tap_target_resolution_available=true"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "step_tap_target_resolution_selected_node_id=search-entry"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "step_type_text_result_file=$ARTIFACT_DIR/taobao-type.properties"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "step_type_text_target_resolution_available=true"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "step_type_text_target_resolution_selected_node_id=search-edit-text"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "step_submit_search_result_file=$ARTIFACT_DIR/taobao-submit.properties"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "step_submit_search_target_resolution_available=true"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "step_submit_search_target_resolution_selected_node_id=search-submit"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "step_verify_result_file=$ARTIFACT_DIR/taobao-verify.properties"
assert_report_contains "$ARTIFACT_DIR/taobao.case.properties" "step_verify_target_resolution_available=false"
grep -Eq '^step_tap_ranked_candidates_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/taobao.case.properties" ||
  fail "Expected passing real app tap step evidence to hash ranked candidates"
grep -Eq '^step_type_text_ranked_candidates_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/taobao.case.properties" ||
  fail "Expected passing real app type step evidence to hash ranked candidates"
grep -Eq '^step_submit_search_ranked_candidates_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/taobao.case.properties" ||
  fail "Expected passing real app submit step evidence to hash ranked candidates"
assert_report_contains "$ARTIFACT_DIR/taobao.tap.target-resolution.properties" "artifact_schema=UiTargetResolutionStepEvidenceArtifact/v1"
assert_report_contains "$ARTIFACT_DIR/taobao.type_text.target-resolution.properties" "artifact_schema=UiTargetResolutionStepEvidenceArtifact/v1"
assert_report_contains "$ARTIFACT_DIR/taobao.submit_search.target-resolution.properties" "artifact_schema=UiTargetResolutionStepEvidenceArtifact/v1"
assert_report_contains_text "$ARTIFACT_DIR/taobao.tap.ranked-candidates.json" '"nodeId":"search-entry"'
assert_report_contains_text "$ARTIFACT_DIR/taobao.type_text.ranked-candidates.json" '"editable":true'
assert_report_contains_text "$ARTIFACT_DIR/taobao.submit_search.ranked-candidates.json" '"nodeId":"search-submit"'
assert_report_contains "$ARTIFACT_DIR/uc.case.properties" "case=uc"
assert_report_contains "$ARTIFACT_DIR/uc.case.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/uc.case.properties" "result_file=$ARTIFACT_DIR/uc-verify.properties"
