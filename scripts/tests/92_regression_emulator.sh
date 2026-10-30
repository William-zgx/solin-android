# shellcheck shell=bash
# Validation script tests: regression_emulator.sh.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

reset_logs
expect_success \
  "release screenshot capture records sanitized emulator evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/capture_release_screenshots.sh
grep -q -- ":app:assembleDebug" "$FAKE_GRADLE_LOG" ||
  fail "Expected release screenshot helper to assemble the debug APK"
receiver_broadcast_count="$(
  grep -cE -- "shell run-as com[.]bytedance[.]zgx[.]solin am broadcast .* -n com[.]bytedance[.]zgx[.]solin/[.]debug[.]DebugRemoteConfigReceiver" "$FAKE_ADB_LOG" || true
)"
[[ "$receiver_broadcast_count" -ge 1 ]] ||
  fail "Expected release screenshot helper to clear the debug receiver through run-as"
if grep -q -- "-s emulator-5554 shell am broadcast" "$FAKE_ADB_LOG"; then
  fail "Release screenshot helper must not broadcast to the debug receiver from the shell uid"
fi
grep -q -- "com.bytedance.zgx.solin.extra.DEBUG_SCREENSHOT_REMOTE_BASE_URL" "$FAKE_ADB_LOG" ||
  fail "Expected release screenshot helper to configure debug remote base URL through MainActivity extras"
grep -q -- "com.bytedance.zgx.solin.extra.DEBUG_SCREENSHOT_REMOTE_MODEL_NAME" "$FAKE_ADB_LOG" ||
  fail "Expected release screenshot helper to configure debug remote model through MainActivity extras"
grep -q -- "--ez clearRemoteConfig true" "$FAKE_ADB_LOG" ||
  fail "Expected release screenshot helper to clear remote config on exit"
grep -q -- "am broadcast --user 0 -n com.bytedance.zgx.solin/.debug.DebugRemoteConfigReceiver" "$FAKE_ADB_LOG" ||
  fail "Expected release screenshot helper to pin debug receiver broadcasts to user 0"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "failedTarget="
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "reason="
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "target=release-screenshots"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "clean_device=1"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.chat-home.path=$ARTIFACT_DIR/screenshots/chat-home.png"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.model-manager.path=$ARTIFACT_DIR/screenshots/model-manager.png"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.confirmation-sheet.path=$ARTIFACT_DIR/screenshots/confirmation-sheet.png"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.background-tasks-or-audit.path=$ARTIFACT_DIR/screenshots/background-tasks-or-audit.png"
for screenshot_name in chat-home model-manager confirmation-sheet background-tasks-or-audit; do
  [[ -s "$ARTIFACT_DIR/screenshots/$screenshot_name.png" ]] ||
    fail "Expected release screenshot evidence for $screenshot_name"
  assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.${screenshot_name}.sanitized=true"
  assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.${screenshot_name}.uiDump=$ARTIFACT_DIR/ui/screenshot-${screenshot_name}.xml"
  assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.${screenshot_name}.visualRegression=passed"
  assert_report_contains_text "$ARTIFACT_DIR/release-screenshots.properties" "screenshot.${screenshot_name}.requiredText="
  [[ -s "$ARTIFACT_DIR/ui/screenshot-${screenshot_name}.xml" ]] ||
    fail "Expected release screenshot UI dump for $screenshot_name"
  grep -Eq "^screenshot[.]${screenshot_name}[.]sha256=[0-9a-f]{64}$" "$ARTIFACT_DIR/release-screenshots.properties" ||
    fail "Expected release screenshot SHA for $screenshot_name"
  grep -Eq "^screenshot[.]${screenshot_name}[.]uiDumpSha256=[0-9a-f]{64}$" "$ARTIFACT_DIR/release-screenshots.properties" ||
    fail "Expected release screenshot UI dump SHA for $screenshot_name"
done

reset_logs
expect_success \
  "x86 release screenshot wrapper checks host and starts default x86 avd headlessly" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_EMULATOR_AVDS="solin_api36_x86_64" \
  HOST_MACHINE="x86_64" \
  CPUINFO_FILE="$X86_CPUINFO" \
  KVM_DEVICE="$X86_KVM_DEVICE" \
  GLIBC_VERSION="2.31" \
  X86_HOST_REPORT="$ARTIFACT_DIR/x86-emulator-host.properties" \
  GRADLE_CMD="$FAKE_GRADLE" \
  scripts/capture_x86_release_screenshots.sh
assert_report_contains "$ARTIFACT_DIR/x86-emulator-host.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "avd=test-avd"
grep -q -- "-avd solin_api36_x86_64 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot" "$FAKE_EMULATOR_LOG" ||
  fail "x86 screenshot wrapper must start the default x86_64 AVD with headless emulator args"

REGRESSION_COUNT_FIXTURE="$TMP_DIR/android-test-count-fixture"
mkdir -p "$REGRESSION_COUNT_FIXTURE/java/example"
cat > "$REGRESSION_COUNT_FIXTURE/java/example/FixtureTest.kt" <<'COUNT_FIXTURE'
package example

class FixtureTest {
    @Test
    fun bareAnnotation() = Unit

    @Test()
    fun emptyArguments() = Unit

    @Test(timeout = 1)
    fun annotationArguments() = Unit

    @org.junit.Test
    fun fullyQualifiedAnnotation() = Unit
}
COUNT_FIXTURE

reset_logs
REGRESSION_CI_SHA="2222222222222222222222222222222222222222"
expect_success \
  "regression emulator validates reports and forces clean device" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT="$SOURCE_INSTRUMENTATION_OUTPUT" \
  GITHUB_WORKFLOW="Android Verification" GITHUB_JOB="emulator-regression" \
  GITHUB_RUN_ID=123456 GITHUB_SHA="$REGRESSION_CI_SHA" \
  CLEAN_DEVICE=0 GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "target=regression-emulator"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget="
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason="
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "workflow=Android Verification"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "job=emulator-regression"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "runId=123456"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "commitSha=$REGRESSION_CI_SHA"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "clean_device=1"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "source_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "executed_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "skipped_android_test_count=0"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "instrumentation_output_file=$ARTIFACT_DIR/instrumentation.txt"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "logcat_file=$ARTIFACT_DIR/logcat.txt"
grep -Eq '^logcat_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/regression-emulator.properties" ||
  fail "Expected regression emulator report to bind nested device logcat SHA-256"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "clean_device=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "clean_device=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_output_file=$ARTIFACT_DIR/instrumentation.txt"
grep -q -- "-s emulator-5554 uninstall com.bytedance.zgx.solin" "$FAKE_ADB_LOG" ||
  fail "Expected regression emulator to force CLEAN_DEVICE=1 through device helper"

reset_logs
SKIPPED_TEST_NAME="example.OptionalDeviceTest#requiresInstalledModel"
expect_success \
  "regression emulator records assumption skips explicitly" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT=$'INSTRUMENTATION_STATUS: class=example.OptionalDeviceTest\nINSTRUMENTATION_STATUS: test=requiresInstalledModel\nINSTRUMENTATION_STATUS: numtests='"$SOURCE_ANDROID_TEST_COUNT"$'\nINSTRUMENTATION_STATUS_CODE: -4\nOK ('"$SOURCE_ANDROID_TEST_COUNT"$' tests)' \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" \
  "executed_android_test_count=$LOW_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "skipped_android_test_count=1"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "skipped_android_tests=$SKIPPED_TEST_NAME"

reset_logs
expect_failure \
  "regression emulator fails when instrumentation count is below source count" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT="$LOW_INSTRUMENTATION_OUTPUT" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget=instrumentation-test-count"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason=instrumentation-test-count-below-expected"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count=$LOW_ANDROID_TEST_COUNT"
grep -q "expected at least $SOURCE_ANDROID_TEST_COUNT" <<<"$LAST_OUTPUT" ||
  fail "Expected regression emulator to explain insufficient instrumentation count"

reset_logs
expect_success \
  "regression emulator honors higher expected count override" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT="$HIGH_INSTRUMENTATION_OUTPUT" EXPECTED_ANDROID_TEST_COUNT="$HIGH_ANDROID_TEST_COUNT" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "source_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=$HIGH_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count=$HIGH_ANDROID_TEST_COUNT"

reset_logs
expect_failure \
  "regression emulator rejects expected count override below source count before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' EXPECTED_ANDROID_TEST_COUNT="$LOW_ANDROID_TEST_COUNT" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget=expected-android-test-count"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason=expected-android-test-count-below-source"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "source_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=$LOW_ANDROID_TEST_COUNT"
grep -q "cannot be lower than AndroidTest source count" <<<"$LAST_OUTPUT" ||
  fail "Expected regression emulator to reject lowered expected count override"

reset_logs
expect_success \
  "regression emulator counts supported JUnit test annotations" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  ANDROID_TEST_SOURCE_DIR="$REGRESSION_COUNT_FIXTURE" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT=$'INSTRUMENTATION_STATUS: numtests=4\nOK (4 tests)' \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=4"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count=4"

reset_logs
expect_failure \
  "regression emulator rejects invalid expected count before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' EXPECTED_ANDROID_TEST_COUNT=abc \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget=expected-android-test-count"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason=expected-android-test-count-invalid"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=abc"

reset_logs
expect_failure \
  "regression emulator fails when instrumentation count is missing" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' FAKE_INSTRUMENTATION_OUTPUT="OK" \
  EXPECTED_ANDROID_TEST_COUNT="$SOURCE_ANDROID_TEST_COUNT" GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget=instrumentation-test-count"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason=instrumentation-test-count-missing"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count="
grep -q "instrumentation_test_count" <<<"$LAST_OUTPUT" ||
  fail "Expected regression emulator to explain missing instrumentation count"

reset_logs
expect_failure \
  "regression emulator writes failed report when emulator helper fails preflight" \
  env ANDROID_SDK_ROOT="$NO_EMULATOR_SDK" ANDROID_HOME="$NO_EMULATOR_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' EXPECTED_ANDROID_TEST_COUNT="$SOURCE_ANDROID_TEST_COUNT" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "target=regression-emulator"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget=emulator-verification"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason=emulator-verification-emulator-binary-missing"

reset_logs
expect_success \
  "regression emulator records hosted HVF_UNSUPPORTED infrastructure as skipped when allowed" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES="" FAKE_EMULATOR_AVDS="test-avd" AVD_NAME="test-avd" \
  FAKE_EMULATOR_OUTPUT=$'HVF_UNSUPPORTED' \
  EMULATOR_SELECT_TIMEOUT_SECONDS=0 ALLOW_EMULATOR_INFRA_UNAVAILABLE=1 \
  EXPECTED_ANDROID_TEST_COUNT="$SOURCE_ANDROID_TEST_COUNT" GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=skipped"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget=emulator-infra"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason=emulator-infra-hvf-unsupported"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "reason=emulator-startup-process-exited"
grep -q "HVF_UNSUPPORTED" "$ARTIFACT_DIR-emulator.log" ||
  fail "Expected hosted HVF skip to preserve the emulator log evidence"

reset_logs
expect_failure \
  "regression emulator failed report harvests nested device evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT=$'INSTRUMENTATION_STATUS: numtests=3\nFAILURES!!!\nTests run: 3,  Failures: 1\nINSTRUMENTATION_CODE: -1' \
  ALLOW_EMULATOR_INFRA_UNAVAILABLE=1 \
  EXPECTED_ANDROID_TEST_COUNT="$SOURCE_ANDROID_TEST_COUNT" GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "failedTarget=emulator-verification"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "reason=emulator-verification-device-verification-instrumentation-failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "serial=emulator-5554"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "api_level=36"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "abi=arm64-v8a,armeabi-v7a"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count=3"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "instrumentation_output_file=$ARTIFACT_DIR/instrumentation.txt"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "logcat_file=$ARTIFACT_DIR/logcat.txt"
grep -Eq '^logcat_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/regression-emulator.properties" ||
  fail "Expected failed regression emulator report to bind nested device logcat SHA-256"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "failedTarget=device-verification"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "reason=device-verification-instrumentation-failed"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "screenshot_file=$ARTIFACT_DIR/screenshot.png"
[[ -s "$ARTIFACT_DIR/screenshot.png" ]] ||
  fail "Expected emulator helper to capture a failure screenshot"
grep -q "FAILURES!!!" "$ARTIFACT_DIR/instrumentation.txt" ||
  fail "Expected regression failed report to link persisted instrumentation failure output"
