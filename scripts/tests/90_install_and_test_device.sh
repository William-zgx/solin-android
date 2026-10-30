# shellcheck shell=bash
# Validation script tests: install_and_test_device.sh and install_review_device.sh.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

reset_logs
expect_success \
  "real app search eval runs optional model-driven app search case" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_REAL_APP_SEARCH_INSTALLED_PACKAGES="com.taobao.taobao" \
  RUN_MODEL_DRIVEN_APP_SEARCH_EVAL=1 MODEL_DRIVEN_APP_SEARCH_CASES=taobao \
  MODEL_DRIVEN_APP_SEARCH_STRICT_MODE=flow \
  SKIP_BUILD=1 SKIP_INSTALL=1 FORCE_STOP_TARGET_APP=0 \
  REPORT_FILE="$ARTIFACT_DIR/real-app-search-eval.properties" \
  LOGCAT_FILE="$ARTIFACT_DIR/real-app-search-logcat.txt" \
  DIAGNOSTICS_DIR="$ARTIFACT_DIR/real-app-diagnostics" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/run_real_app_search_eval.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_model_driven_app_search_eval=1"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "model_driven_app_search_cases=taobao"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "model_driven_app_search_strict_mode=flow"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "debug_eval_result_wait_attempts=1200"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "run_count=2"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "pass_count=2"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "fail_count=0"
assert_report_contains "$ARTIFACT_DIR/real-app-search-eval.properties" "skip_count=7"
MODEL_DRIVEN_TAOBAO_CASE_REPORT="$ARTIFACT_DIR/model_driven_taobao.case.properties"
MODEL_DRIVEN_TAOBAO_RESULT="$ARTIFACT_DIR/model_driven_taobao-search.properties"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_CASE_REPORT" "case=model_driven_taobao"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_CASE_REPORT" "expected_package_name=com.taobao.taobao"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_CASE_REPORT" "expected_app_name=淘宝"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_CASE_REPORT" "status=passed"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_CASE_REPORT" "result_file=$MODEL_DRIVEN_TAOBAO_RESULT"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "resultType=model_driven_app_search"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "status=Succeeded"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "verificationRequired=true"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "expectedPackageName=com.taobao.taobao"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "searchVerificationStatus=verified"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "uiActionOutcome=verified"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "uiActionOutcomeReason=search_verified"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "appSearchProgressStage=verified"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "modelPlannedStepCount=4"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "modelReplanTraceCount=4"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "modelReplanAcceptedCount=4"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "modelReplanAttempted=true"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "modelReplanTrace_0_reason=accepted"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "evalGuardAppliedCount=4"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "step_3_status=Succeeded"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "step_0_appSearchProgressStage=opened"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "step_2_appSearchProgressStage=observed_entry"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "step_4_status=Succeeded"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "step_5_status=Succeeded"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "step_5_appSearchProgressStage=submitted"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "step_6_verifySearchQuery=海河牛奶"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "step_6_searchVerificationStatus=verified"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "step_6_uiActionOutcome=verified"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "step_6_uiActionOutcomeReason=search_verified"
assert_report_contains "$MODEL_DRIVEN_TAOBAO_RESULT" "step_6_appSearchProgressStage=verified"

reset_logs
expect_failure \
  "install helper without devices" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES="" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "target=device"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=device-selection"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=device-selection-ambiguous"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=not-run"

reset_logs
expect_failure \
  "install helper with unauthorized device" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tunauthorized' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper with multiple devices and no serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice\ndevice-b\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper with offline selected serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\toffline' ANDROID_SERIAL="device-a" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper with missing selected serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-b" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper rejects non arm64 device before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" FAKE_ABI_LIST="armeabi-v7a,x86" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=device-abi"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=device-abi-not-arm64"
grep -q "not arm64-v8a compatible" <<<"$LAST_OUTPUT" ||
  fail "Expected install helper to reject non arm64-v8a devices"
grep -q -- "-s device-a shell getprop ro.product.cpu.abilist64" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to check selected device ABI before Gradle"

reset_logs
expect_failure \
  "install helper rejects low data partition space before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" FAKE_ABI_LIST="arm64-v8a,armeabi-v7a" \
  FAKE_DATA_FREE_KB="3145727" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=data-free-space"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=data-free-below-threshold"
grep -q "less than 3 GB free on /data" <<<"$LAST_OUTPUT" ||
  fail "Expected install helper to reject devices with low /data free space"
grep -q -- "-s device-a shell df -k /data" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to check /data free space before Gradle"

reset_logs
expect_success \
  "install helper selects the only authorized emulator" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' RELEASE_ARTIFACT_SHA256="$VALID_PERF_SHA" \
  GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "artifact_schema=DeviceVerificationArtifact/v1"
grep -Eq '^artifact_id=device-emulator-5554-api36-' "$ARTIFACT_DIR/device-verification.properties" ||
  fail "Expected install helper to write a stable device artifact id"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget="
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason="
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=emulator-5554"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "api_level=36"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "abi=arm64-v8a,armeabi-v7a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reset_app_data_after_tests=0"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_test_count=20"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "test_count=20"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "app_apk_mode=debug"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "app_apk=app/build/outputs/apk/debug/app-debug.apk"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "releaseArtifactSha256=$VALID_PERF_SHA"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_output_file=$ARTIFACT_DIR/instrumentation.txt"
grep -Eq '^instrumentation_output_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/device-verification.properties" ||
  fail "Expected install helper to write instrumentation output SHA-256"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "logcat_file=$ARTIFACT_DIR/logcat.txt"
grep -Eq '^logcat_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/device-verification.properties" ||
  fail "Expected install helper to write logcat SHA-256"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "logcat_captured=1"
grep -q "OK (20 tests)" "$ARTIFACT_DIR/instrumentation.txt" ||
  fail "Expected install helper to persist instrumentation output"
[[ -s "$ARTIFACT_DIR/logcat.txt" ]] ||
  fail "Expected install helper to persist logcat output"
grep -q -- "-s emulator-5554 shell getprop ro.product.cpu.abilist64" "$FAKE_ADB_LOG" ||
  fail "Expected adb device commands to target the only authorized device"
grep -q -- "-s emulator-5554 logcat -c" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to clear the logcat window before validation"
grep -q -- "-s emulator-5554 logcat -d -t 500" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to capture logcat after validation"
if grep -q -- "-s emulator-5554 shell pm clear com.bytedance.zgx.solin" "$FAKE_ADB_LOG"; then
  fail "Default install helper must preserve target app data"
fi
if grep -q -- "-s emulator-5554 shell pm clear com.bytedance.zgx.solin.test" "$FAKE_ADB_LOG"; then
  fail "Default install helper must preserve test app data"
fi

reset_logs
expect_failure \
  "install helper requires an explicit serial for a physical device" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=device-selection"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" \
  "reason=android-serial-required-for-physical-device"

reset_logs
DEVICE_RELEASE_APK="$TMP_DIR/device-release.apk"
DEVICE_RELEASE_ANDROID_TEST_APK="$TMP_DIR/device-release-androidTest.apk"
printf 'device release apk\n' > "$DEVICE_RELEASE_APK"
printf 'device release androidTest apk\n' > "$DEVICE_RELEASE_ANDROID_TEST_APK"
DEVICE_RELEASE_APK_SHA="$(shasum -a 256 "$DEVICE_RELEASE_APK" | awk '{print $1}')"
DEVICE_RELEASE_ANDROID_TEST_APK_SHA="$(shasum -a 256 "$DEVICE_RELEASE_ANDROID_TEST_APK" | awk '{print $1}')"
expect_success \
  "install helper can verify an explicit release apk on device" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' \
  ANDROID_SERIAL="device-a" \
  FAKE_SOLIN_MAIN_SHELL_VISIBLE=1 \
  APP_APK_MODE=release APP_APK="$DEVICE_RELEASE_APK" \
  ANDROID_TEST_APK="$DEVICE_RELEASE_ANDROID_TEST_APK" \
  FAKE_INSTALLED_APP_APK_SOURCE="$DEVICE_RELEASE_APK" \
  FAKE_INSTALLED_TEST_APK_SOURCE="$DEVICE_RELEASE_ANDROID_TEST_APK" \
  RELEASE_ARTIFACT_TYPE=apk RELEASE_ARTIFACT_SHA256="$DEVICE_RELEASE_APK_SHA" \
  GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "app_apk_mode=release"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "app_apk=$DEVICE_RELEASE_APK"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "app_apk_sha256=$DEVICE_RELEASE_APK_SHA"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "android_test_apk=$DEVICE_RELEASE_ANDROID_TEST_APK"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "android_test_apk_sha256=$DEVICE_RELEASE_ANDROID_TEST_APK_SHA"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "app_android_test_signers_match=true"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_executed_test_count=20"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_skipped_test_count=0"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "installed_app_matches_requested_apk=true"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "installed_test_matches_requested_apk=true"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "app_apk_version_code=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "installed_app_version_code=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "releaseArtifactType=apk"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "releaseArtifactSha256=$DEVICE_RELEASE_APK_SHA"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "release_main_shell_verified=1"
for key in \
  release_main_shell_start_output_sha256 \
  release_main_shell_activity_dump_sha256 \
  release_main_shell_window_dump_sha256 \
  release_main_shell_ui_dump_sha256 \
  release_main_shell_screenshot_sha256; do
  grep -Eq "^${key}=[0-9a-f]{64}$" "$ARTIFACT_DIR/device-verification.properties" ||
    fail "Expected release main-shell evidence SHA for $key"
done
grep -q -- "-s device-a install -r $DEVICE_RELEASE_APK" "$FAKE_ADB_LOG" ||
  fail "Expected install helper release mode to install the explicit release APK"

reset_logs
expect_failure \
  "install helper rejects a release main shell that is not visible" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' \
  ANDROID_SERIAL="device-a" \
  APP_APK_MODE=release APP_APK="$DEVICE_RELEASE_APK" \
  ANDROID_TEST_APK="$DEVICE_RELEASE_ANDROID_TEST_APK" \
  FAKE_INSTALLED_APP_APK_SOURCE="$DEVICE_RELEASE_APK" \
  FAKE_INSTALLED_TEST_APK_SOURCE="$DEVICE_RELEASE_ANDROID_TEST_APK" \
  RELEASE_MAIN_SHELL_TIMEOUT_SECONDS=1 \
  SKIP_BUILD=1 \
  scripts/install_and_test_device.sh
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=release-main-shell"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=release-main-shell-not-visible"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "release_main_shell_verified=0"

reset_logs
expect_failure \
  "install helper rejects release mode skip install" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' \
  ANDROID_SERIAL="device-a" \
  APP_APK_MODE=release APP_APK="$DEVICE_RELEASE_APK" \
  ANDROID_TEST_APK="$DEVICE_RELEASE_ANDROID_TEST_APK" \
  SKIP_INSTALL=1 SKIP_BUILD=1 \
  scripts/install_and_test_device.sh
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=release-skip-install-not-allowed"

reset_logs
expect_failure \
  "install helper rejects release app and test signer mismatch" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' \
  ANDROID_SERIAL="device-a" \
  FAKE_APK_SIGNER_SHA256=2222222222222222222222222222222222222222222222222222222222222222 \
  APP_APK_MODE=release APP_APK="$DEVICE_RELEASE_APK" \
  ANDROID_TEST_APK="$DEVICE_RELEASE_ANDROID_TEST_APK" \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  SKIP_BUILD=1 \
  scripts/install_and_test_device.sh
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=release-signing-certificate-mismatch"

reset_logs
expect_failure \
  "install helper rejects skipped release smoke tests" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' \
  ANDROID_SERIAL="device-a" \
  FAKE_INSTRUMENTATION_OUTPUT=$'INSTRUMENTATION_STATUS: class=com.bytedance.zgx.solin.ReleaseSignedSmokeDeviceTest\nINSTRUMENTATION_STATUS: test=installedAppLaunchesMainShell\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: -4\nOK (1 test)' \
  APP_APK_MODE=release APP_APK="$DEVICE_RELEASE_APK" \
  ANDROID_TEST_APK="$DEVICE_RELEASE_ANDROID_TEST_APK" \
  FAKE_INSTALLED_APP_APK_SOURCE="$DEVICE_RELEASE_APK" \
  FAKE_INSTALLED_TEST_APK_SOURCE="$DEVICE_RELEASE_ANDROID_TEST_APK" \
  SKIP_BUILD=1 \
  scripts/install_and_test_device.sh
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=release-instrumentation-tests-skipped"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_skipped_test_count=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" \
  "instrumentation_skipped_tests=com.bytedance.zgx.solin.ReleaseSignedSmokeDeviceTest#installedAppLaunchesMainShell"

reset_logs
expect_success \
  "install helper scopes instrumentation class" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  INSTRUMENTATION_CLASS="com.bytedance.zgx.solin.MainActivitySmokeTest" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_class=com.bytedance.zgx.solin.MainActivitySmokeTest"
grep -q -- "-s device-a shell am instrument -w -r -e class com.bytedance.zgx.solin.MainActivitySmokeTest com.bytedance.zgx.solin.test/androidx.test.runner.AndroidJUnitRunner" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to pass the requested instrumentation class"

reset_logs
expect_success \
  "install helper clears clean-device app data after successful instrumentation" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  CLEAN_DEVICE=1 \
  RESET_APP_DATA_AFTER_TESTS=1 \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "clean_device=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reset_app_data_after_tests=1"
grep -q -- "-s emulator-5554 shell pm clear com.bytedance.zgx.solin" "$FAKE_ADB_LOG" ||
  fail "Expected clean-device install helper to clear target app data before success launch"
grep -q -- "-s emulator-5554 shell pm clear com.bytedance.zgx.solin.test" "$FAKE_ADB_LOG" ||
  fail "Expected clean-device install helper to clear test app data after successful instrumentation"
pm_clear_line="$(grep -n -- "-s emulator-5554 shell pm clear com.bytedance.zgx.solin" "$FAKE_ADB_LOG" | head -n 1 | cut -d: -f1)"
instrumentation_line="$(grep -n -- "-s emulator-5554 shell am instrument -w -r com.bytedance.zgx.solin.test/androidx.test.runner.AndroidJUnitRunner" "$FAKE_ADB_LOG" | head -n 1 | cut -d: -f1)"
if [[ -z "$pm_clear_line" || -z "$instrumentation_line" || "$instrumentation_line" -ge "$pm_clear_line" ]]; then
  fail "Expected target app data to be cleared after successful instrumentation"
fi

reset_logs
expect_failure \
  "install helper times out stuck instrumentation" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_INSTRUMENTATION_SLEEP_SECONDS=2 \
  INSTRUMENTATION_TIMEOUT_SECONDS=1 \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=instrumentation"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=instrumentation-timeout"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_timeout_seconds=1"
grep -q "timed out after 1s" <<<"$LAST_OUTPUT" ||
  fail "Expected install helper to report instrumentation timeout"
grep -q -- "-s device-a shell am force-stop com.bytedance.zgx.solin" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to stop target package after instrumentation timeout"

reset_logs
expect_failure \
  "install helper clears clean-device state after timeout" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_SLEEP_SECONDS=2 \
  CLEAN_DEVICE=1 \
  RESET_APP_DATA_AFTER_TESTS=1 \
  INSTRUMENTATION_TIMEOUT_SECONDS=1 \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "clean_device=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reset_app_data_after_tests=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=instrumentation-timeout"
grep -q -- "-s emulator-5554 shell pm clear com.bytedance.zgx.solin" "$FAKE_ADB_LOG" ||
  fail "Expected clean-device install helper to clear target app data after timeout"
grep -q -- "-s emulator-5554 shell pm clear com.bytedance.zgx.solin.test" "$FAKE_ADB_LOG" ||
  fail "Expected clean-device install helper to clear test app data after timeout"
grep -q -- "-s emulator-5554 uninstall com.bytedance.zgx.solin.test" "$FAKE_ADB_LOG" ||
  fail "Expected clean-device install helper to uninstall test package after timeout"

reset_logs
expect_failure \
  "install helper rejects instrumentation numtests without final OK" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_INSTRUMENTATION_OUTPUT=$'INSTRUMENTATION_STATUS: numtests=20' \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=instrumentation"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=instrumentation-success-marker-missing"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_test_count=20"
grep -q "final OK/success marker" <<<"$LAST_OUTPUT" ||
  fail "Expected install helper to reject malformed instrumentation output without final OK"

reset_logs
expect_failure \
  "install helper does not accept OK output when instrumentation command exits nonzero" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_INSTRUMENTATION_OUTPUT=$'OK (20 tests)\nINSTRUMENTATION_CODE: -1' \
  FAKE_INSTRUMENTATION_EXIT_CODE=1 \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=instrumentation"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=instrumentation-command-failed"

reset_logs
expect_success \
  "install helper selects requested serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice\ndevice-b\tdevice' ANDROID_SERIAL="device-b" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
grep -q -- "-s device-b shell getprop ro.product.cpu.abilist64" "$FAKE_ADB_LOG" ||
  fail "Expected adb device commands to target ANDROID_SERIAL"
grep -q -- "-s device-b install -r app/build/outputs/apk/debug/app-debug.apk" "$FAKE_ADB_LOG" ||
  fail "Expected debug APK install to target ANDROID_SERIAL"
