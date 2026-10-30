# shellcheck shell=bash
# Validation script tests: collect_perf_baseline.sh and collect_rc_perf_from_device.sh.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

COLLECTED_PERF_MISSING_ENV="$ARTIFACT_DIR/collected-perf-missing-env.properties"
expect_failure \
  "perf baseline collector reports missing required env" \
  env ADB="$TMP_DIR/missing-adb" \
  OUT_FILE="$COLLECTED_PERF_MISSING_ENV" \
  APP_VERSION=1.0 \
  MODEL_ID=chat-e2b \
  BACKEND=GPU \
  FIRST_LAUNCH_INTERACTIVE_MS=1200 \
  MODEL_LOAD_MS=3500 \
  FIRST_TOKEN_MS=900 \
  TOKENS_PER_SECOND=12.5 \
  STOP_GENERATION_RECOVERY_MS=200 \
  GPU_FALLBACK_STATUS=not-needed \
  VISION_INPUT_MS=500 \
  MEMORY_SEARCH_5K_MS=25 \
  ZVEC_MEMORY_INDEX_50K_MS=1500 \
  ZVEC_MEMORY_SEARCH_50K_MS=35 \
  MEMORY_PEAK_MB=512 \
  OOM_OR_ANR_OBSERVED=false \
  scripts/collect_perf_baseline.sh
assert_report_contains "$COLLECTED_PERF_MISSING_ENV" "status=failed"
assert_report_contains "$COLLECTED_PERF_MISSING_ENV" "artifactSchema=PerfBaselineCollection/v1"
assert_report_contains "$COLLECTED_PERF_MISSING_ENV" "target=perf-baseline-collector"
assert_report_contains "$COLLECTED_PERF_MISSING_ENV" "owner=release-engineering"
grep -q '^command=.*scripts/collect_perf_baseline.sh' "$COLLECTED_PERF_MISSING_ENV" ||
  fail "perf collector failure report must include reproducible command"
assert_report_contains "$COLLECTED_PERF_MISSING_ENV" "reproduciblePath=$COLLECTED_PERF_MISSING_ENV"
assert_report_contains "$COLLECTED_PERF_MISSING_ENV" "failedTarget=environment"
assert_report_contains "$COLLECTED_PERF_MISSING_ENV" "reason=missing-release-artifact"

COLLECTED_PERF_MISSING_ARTIFACT="$ARTIFACT_DIR/collected-perf-missing-artifact.properties"
expect_failure \
  "perf baseline collector reports missing release artifact" \
  env ADB="$TMP_DIR/missing-adb" \
  OUT_FILE="$COLLECTED_PERF_MISSING_ARTIFACT" \
  RELEASE_ARTIFACT="$TMP_DIR/missing-release-artifact.apk" \
  ANDROID_SERIAL=device-a \
  DEVICE_MODEL="Pixel Test" \
  ANDROID_API=36 \
  ABI=arm64-v8a \
  APP_VERSION=1.0 \
  MODEL_ID=chat-e2b \
  BACKEND=GPU \
  FIRST_LAUNCH_INTERACTIVE_MS=1200 \
  MODEL_LOAD_MS=3500 \
  FIRST_TOKEN_MS=900 \
  TOKENS_PER_SECOND=12.5 \
  STOP_GENERATION_RECOVERY_MS=200 \
  GPU_FALLBACK_STATUS=not-needed \
  VISION_INPUT_MS=500 \
  MEMORY_SEARCH_5K_MS=25 \
  ZVEC_MEMORY_INDEX_50K_MS=1500 \
  ZVEC_MEMORY_SEARCH_50K_MS=35 \
  MEMORY_PEAK_MB=512 \
  OOM_OR_ANR_OBSERVED=false \
  scripts/collect_perf_baseline.sh
assert_report_contains "$COLLECTED_PERF_MISSING_ARTIFACT" "status=failed"
assert_report_contains "$COLLECTED_PERF_MISSING_ARTIFACT" "failedTarget=input-artifact"
assert_report_contains "$COLLECTED_PERF_MISSING_ARTIFACT" "reason=release-artifact-missing"

COLLECTED_PERF_EMULATOR="$ARTIFACT_DIR/collected-perf-emulator.properties"
expect_failure \
  "perf baseline collector reports verifier rejection reason" \
  env ADB="$TMP_DIR/missing-adb" \
  OUT_FILE="$COLLECTED_PERF_EMULATOR" \
  RELEASE_ARTIFACT="$SAFE_APK" \
  ANDROID_SERIAL=emulator-5554 \
  DEVICE_MODEL="Pixel Test" \
  ANDROID_API=36 \
  ABI=arm64-v8a \
  APP_VERSION=1.0 \
  MODEL_ID=chat-e2b \
  BACKEND=GPU \
  FIRST_LAUNCH_INTERACTIVE_MS=1200 \
  MODEL_LOAD_MS=3500 \
  FIRST_TOKEN_MS=900 \
  TOKENS_PER_SECOND=12.5 \
  STOP_GENERATION_RECOVERY_MS=200 \
  GPU_FALLBACK_STATUS=not-needed \
  VISION_INPUT_MS=500 \
  MEMORY_SEARCH_5K_MS=25 \
  ZVEC_MEMORY_INDEX_50K_MS=1500 \
  ZVEC_MEMORY_SEARCH_50K_MS=35 \
  MEMORY_PEAK_MB=512 \
  OOM_OR_ANR_OBSERVED=false \
  scripts/collect_perf_baseline.sh
assert_report_contains "$COLLECTED_PERF_EMULATOR" "status=failed"
assert_report_contains "$COLLECTED_PERF_EMULATOR" "failedTarget=perf-baseline-verification"
assert_report_contains "$COLLECTED_PERF_EMULATOR" "reason=device-serial-is-emulator"
assert_report_contains "$COLLECTED_PERF_EMULATOR" "verificationReport=$COLLECTED_PERF_EMULATOR.verification.properties"
assert_report_contains "$COLLECTED_PERF_EMULATOR" "verificationReason=device-serial-is-emulator"

COLLECTED_PERF="$ARTIFACT_DIR/collected-perf.properties"
expect_success \
  "perf baseline collector writes verifiable record from measured inputs" \
  env ADB="$TMP_DIR/missing-adb" \
  OUT_FILE="$COLLECTED_PERF" \
  RELEASE_ARTIFACT="$SAFE_APK" \
  ANDROID_SERIAL=device-a \
  DEVICE_MODEL="Pixel Test" \
  ANDROID_API=36 \
  ABI=arm64-v8a \
  APP_VERSION=1.0 \
  MODEL_ID=chat-e2b \
  BACKEND=GPU \
  FIRST_LAUNCH_INTERACTIVE_MS=1200 \
  MODEL_LOAD_MS=3500 \
  FIRST_TOKEN_MS=900 \
  TOKENS_PER_SECOND=12.5 \
  STOP_GENERATION_RECOVERY_MS=200 \
  GPU_FALLBACK_STATUS=not-needed \
  VISION_INPUT_MS=500 \
  MEMORY_SEARCH_5K_MS=25 \
  ZVEC_MEMORY_INDEX_50K_MS=1500 \
  ZVEC_MEMORY_SEARCH_50K_MS=35 \
  MEMORY_PEAK_MB=512 \
  OOM_OR_ANR_OBSERVED=false \
  scripts/collect_perf_baseline.sh
assert_report_contains "$COLLECTED_PERF" "status=passed"
assert_report_contains "$COLLECTED_PERF" "artifactSchema=PerfBaseline/v1"
assert_report_contains "$COLLECTED_PERF" "target=perf-baseline-record"
assert_report_contains "$COLLECTED_PERF" "owner=release-engineering"
grep -q '^collectionCommand=.*scripts/collect_perf_baseline.sh' "$COLLECTED_PERF" ||
  fail "perf collector success report must include collection command"
assert_report_contains "$COLLECTED_PERF" "reproduciblePath=$COLLECTED_PERF"
assert_report_contains "$COLLECTED_PERF" "releaseArtifact=$SAFE_APK"
assert_report_contains "$COLLECTED_PERF.verification.properties" "artifactSchema=PerfBaselineVerification/v1"
assert_report_contains "$COLLECTED_PERF.verification.properties" "expectedAppVersion=1.0"
assert_report_contains "$COLLECTED_PERF.verification.properties" "baselineSha256=$(shasum -a 256 "$COLLECTED_PERF" | awk '{print $1}')"

# --- RC perf device collector (scripts/collect_rc_perf_from_device.sh) -----------------------
# The RC perf collector drives the in-app rcPerfRelease harness on a physical device and feeds the
# measured metrics into collect_perf_baseline.sh. It must never clear app data or delete models.
reset_logs

RC_COLLECT_MISSING_ARTIFACT_REPORT="$ARTIFACT_DIR/rc-collect-missing-artifact.properties"
expect_failure \
  "rc perf collector requires the release artifact env" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  ADB="$FAKE_SDK/platform-tools/adb" \
  APP_VERSION=1.0 \
  REPORT_FILE="$RC_COLLECT_MISSING_ARTIFACT_REPORT" \
  OUT_FILE="$ARTIFACT_DIR/rc-collect-missing-artifact-baseline.properties" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/collect_rc_perf_from_device.sh
assert_no_gradle_call
assert_report_contains "$RC_COLLECT_MISSING_ARTIFACT_REPORT" "artifactSchema=RcPerfCollection/v1"
assert_report_contains "$RC_COLLECT_MISSING_ARTIFACT_REPORT" "status=failed"
assert_report_contains "$RC_COLLECT_MISSING_ARTIFACT_REPORT" "target=rc-perf-collector"
assert_report_contains "$RC_COLLECT_MISSING_ARTIFACT_REPORT" "failedTarget=environment"
assert_report_contains "$RC_COLLECT_MISSING_ARTIFACT_REPORT" "reason=missing-release-artifact"
assert_report_contains "$RC_COLLECT_MISSING_ARTIFACT_REPORT" "preserves_model_data=true"

reset_logs
RC_COLLECT_EMULATOR_REPORT="$ARTIFACT_DIR/rc-collect-emulator.properties"
expect_failure \
  "rc perf collector rejects emulator devices" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  ADB="$FAKE_SDK/platform-tools/adb" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' ANDROID_SERIAL="emulator-5554" \
  RELEASE_ARTIFACT="$SAFE_APK" APP_VERSION=1.0 \
  SKIP_BUILD=1 SKIP_INSTALL=1 \
  RC_PERF_APK="$SAFE_APK" \
  REPORT_FILE="$RC_COLLECT_EMULATOR_REPORT" \
  OUT_FILE="$ARTIFACT_DIR/rc-collect-emulator-baseline.properties" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/collect_rc_perf_from_device.sh
assert_no_gradle_call
assert_report_contains "$RC_COLLECT_EMULATOR_REPORT" "status=failed"
assert_report_contains "$RC_COLLECT_EMULATOR_REPORT" "failedTarget=device-selection"
assert_report_contains "$RC_COLLECT_EMULATOR_REPORT" "reason=emulator-not-allowed"
assert_report_contains "$RC_COLLECT_EMULATOR_REPORT" "serial=emulator-5554"

reset_logs
RC_COLLECT_NO_APK_REPORT="$ARTIFACT_DIR/rc-collect-no-apk.properties"
expect_failure \
  "rc perf collector reports a missing rcPerfRelease apk" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  ADB="$FAKE_SDK/platform-tools/adb" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  RELEASE_ARTIFACT="$SAFE_APK" APP_VERSION=1.0 \
  SKIP_BUILD=1 SKIP_INSTALL=1 \
  RC_PERF_APK="$TMP_DIR/missing-rc-perf.apk" \
  REPORT_FILE="$RC_COLLECT_NO_APK_REPORT" \
  OUT_FILE="$ARTIFACT_DIR/rc-collect-no-apk-baseline.properties" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/collect_rc_perf_from_device.sh
assert_no_gradle_call
assert_report_contains "$RC_COLLECT_NO_APK_REPORT" "status=failed"
assert_report_contains "$RC_COLLECT_NO_APK_REPORT" "failedTarget=rc-perf-apk"
assert_report_contains "$RC_COLLECT_NO_APK_REPORT" "reason=rc-perf-apk-missing"

reset_logs
RC_COLLECT_NO_LAUNCH_REPORT="$ARTIFACT_DIR/rc-collect-no-launch.properties"
expect_failure \
  "rc perf collector requires a first-launch time" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  ADB="$FAKE_SDK/platform-tools/adb" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  RELEASE_ARTIFACT="$SAFE_APK" APP_VERSION=1.0 \
  SKIP_BUILD=1 SKIP_INSTALL=1 \
  RC_PERF_APK="$SAFE_APK" \
  REPORT_FILE="$RC_COLLECT_NO_LAUNCH_REPORT" \
  OUT_FILE="$ARTIFACT_DIR/rc-collect-no-launch-baseline.properties" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/collect_rc_perf_from_device.sh
assert_no_gradle_call
assert_report_contains "$RC_COLLECT_NO_LAUNCH_REPORT" "status=failed"
assert_report_contains "$RC_COLLECT_NO_LAUNCH_REPORT" "failedTarget=first-launch"
assert_report_contains "$RC_COLLECT_NO_LAUNCH_REPORT" "reason=first-launch-time-missing"

reset_logs
RC_COLLECT_TIMEOUT_REPORT="$ARTIFACT_DIR/rc-collect-timeout.properties"
expect_failure \
  "rc perf collector force-stops the app when the harness never writes a result" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  ADB="$FAKE_SDK/platform-tools/adb" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  RELEASE_ARTIFACT="$SAFE_APK" APP_VERSION=1.0 \
  SKIP_BUILD=1 SKIP_INSTALL=1 \
  RC_PERF_APK="$SAFE_APK" \
  HARNESS_TIMEOUT_SECONDS=1 \
  FAKE_RC_PERF_TOTAL_TIME_MS=1200 \
  FAKE_RC_PERF_MEMINFO_KB=524288 \
  REPORT_FILE="$RC_COLLECT_TIMEOUT_REPORT" \
  OUT_FILE="$ARTIFACT_DIR/rc-collect-timeout-baseline.properties" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/collect_rc_perf_from_device.sh
assert_no_gradle_call
assert_report_contains "$RC_COLLECT_TIMEOUT_REPORT" "status=failed"
assert_report_contains "$RC_COLLECT_TIMEOUT_REPORT" "failedTarget=harness"
assert_report_contains "$RC_COLLECT_TIMEOUT_REPORT" "reason=harness-timeout"
force_stop_count="$(grep -c -- "-s device-a shell am force-stop com.bytedance.zgx.solin" "$FAKE_ADB_LOG" || true)"
[[ "$force_stop_count" -ge 2 ]] ||
  fail "rc perf collector must force-stop the app after a harness timeout while preserving data"

reset_logs
RC_COLLECT_BENCHMARK_REPORT="$ARTIFACT_DIR/rc-collect-benchmark.properties"
expect_failure \
  "rc perf collector surfaces a harness benchmark-unavailable failure without estimating" \
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
  FAKE_RC_PERF_RESULT_TYPE=failed \
  FAKE_RC_PERF_REASON=benchmark-unavailable \
  REPORT_FILE="$RC_COLLECT_BENCHMARK_REPORT" \
  OUT_FILE="$ARTIFACT_DIR/rc-collect-benchmark-baseline.properties" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/collect_rc_perf_from_device.sh
assert_no_gradle_call
assert_report_contains "$RC_COLLECT_BENCHMARK_REPORT" "status=failed"
assert_report_contains "$RC_COLLECT_BENCHMARK_REPORT" "failedTarget=harness"
assert_report_contains "$RC_COLLECT_BENCHMARK_REPORT" "reason=harness-failed"
grep -q 'benchmark-unavailable' <<<"$LAST_OUTPUT" ||
  fail "rc perf collector must report the harness benchmark-unavailable reason"

reset_logs
RC_COLLECT_OOM_REPORT="$ARTIFACT_DIR/rc-collect-oom.properties"
RC_COLLECT_OOM_BASELINE="$ARTIFACT_DIR/rc-collect-oom-baseline.properties"
expect_failure \
  "rc perf collector flags OOM/ANR observed during the run" \
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
  FAKE_RC_PERF_LOGCAT_OOM_ANR="E AndroidRuntime: java.lang.OutOfMemoryError in com.bytedance.zgx.solin" \
  REPORT_FILE="$RC_COLLECT_OOM_REPORT" \
  OUT_FILE="$RC_COLLECT_OOM_BASELINE" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/collect_rc_perf_from_device.sh
assert_no_gradle_call
assert_report_contains "$RC_COLLECT_OOM_REPORT" "status=failed"
assert_report_contains "$RC_COLLECT_OOM_REPORT" "oomOrAnrObserved=true"
assert_report_contains "$RC_COLLECT_OOM_BASELINE" "reason=oom-or-anr-observed"
