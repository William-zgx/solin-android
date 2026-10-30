# shellcheck shell=bash
# Validation script tests: verify_emulator.sh, fresh-start, live remote and screenshot capture scripts.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

reset_logs
expect_success \
  "review install helper preserves app data and does not run instrumentation" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_review_device.sh
grep -q -- ":app:assembleDebug" "$FAKE_GRADLE_LOG" ||
  fail "Expected review install helper to assemble debug APK"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "target=manual-acceptance-install"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "manualAcceptanceInstall=true"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "regressionEvidence=false"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "clean_device=0"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "kept_app_data=1"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "instrumentation=not-run"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "remote_config=not-requested"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "install_output_file=$ARTIFACT_DIR/install.txt"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "start_output_file=$ARTIFACT_DIR/start.txt"
[[ -s "$ARTIFACT_DIR/install.txt" ]] ||
  fail "Expected review install helper to preserve install output"
[[ -s "$ARTIFACT_DIR/start.txt" ]] ||
  fail "Expected review install helper to preserve start output"
grep -q -- "-s device-a install -r app/build/outputs/apk/debug/app-debug.apk" "$FAKE_ADB_LOG" ||
  fail "Expected review install helper to install debug APK"
if grep -q -- "pm clear com.bytedance.zgx.solin" "$FAKE_ADB_LOG"; then
  fail "Review install helper must not clear app data"
fi
if grep -q -- "uninstall com.bytedance.zgx.solin" "$FAKE_ADB_LOG"; then
  fail "Review install helper must not uninstall app by default"
fi
if grep -q -- "am instrument" "$FAKE_ADB_LOG"; then
  fail "Review install helper must not run instrumentation"
fi

reset_logs
expect_success \
  "review install helper can inject debug remote config without logging secrets" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  SOLIN_REVIEW_REMOTE_BASE_URL="https://example.invalid" \
  SOLIN_REVIEW_REMOTE_MODEL="example-model" \
  SOLIN_REVIEW_REMOTE_API_KEY="example-secret" \
  scripts/install_review_device.sh
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "remote_config=saved"
assert_report_contains "$ARTIFACT_DIR/manual-acceptance-install-device.properties" "remote_config_source=SOLIN_REVIEW_REMOTE_BASE_URL,SOLIN_REVIEW_REMOTE_MODEL,SOLIN_REVIEW_REMOTE_API_KEY"
grep -q -- "-s device-a shell run-as com.bytedance.zgx.solin am broadcast --user 0 -n com.bytedance.zgx.solin/.debug.DebugRemoteConfigReceiver" "$FAKE_ADB_LOG" ||
  fail "Expected review install helper to use debug receiver via run-as"
if grep -q "example-secret" "$ARTIFACT_DIR/manual-acceptance-install-device.properties"; then
  fail "Review install report must not persist remote API key"
fi
if grep -q "example-secret" "$FAKE_ADB_LOG"; then
  fail "Review install helper must not pass remote API key through adb argv/log"
fi

reset_logs
expect_failure \
  "install helper fails failed instrumentation output even when adb exits zero" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_INSTRUMENTATION_OUTPUT=$'FAILURES!!!\nTests run: 3,  Failures: 1\nINSTRUMENTATION_CODE: -1' \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=instrumentation"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=instrumentation-failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_test_count=3"
grep -q "FAILURES!!!" "$ARTIFACT_DIR/instrumentation.txt" ||
  fail "Expected failed instrumentation output to be persisted"

reset_logs
expect_failure \
  "install helper rejects contradictory instrumentation output even with final OK" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  FAKE_INSTRUMENTATION_OUTPUT=$'FAILURES!!!\nTests run: 20,  Failures: 1\nOK (20 tests)\nINSTRUMENTATION_CODE: -1' \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "failedTarget=instrumentation"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "reason=instrumentation-failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_test_count=20"
grep -q "OK (20 tests)" "$ARTIFACT_DIR/instrumentation.txt" ||
  fail "Expected contradictory instrumentation output to be persisted"

reset_logs
expect_failure \
  "emulator helper rejects physical serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  EMULATOR_SELECT_TIMEOUT_SECONDS=0 GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "failedTarget=android-serial"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "reason=android-serial-not-emulator"

reset_logs
expect_failure \
  "emulator helper rejects physical-only devices" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' EMULATOR_SELECT_TIMEOUT_SECONDS=0 \
  GRADLE_CMD="$FAKE_GRADLE" scripts/verify_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "failedTarget=emulator-selection"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "reason=no-single-authorized-emulator"

reset_logs
expect_failure \
  "emulator helper without emulator binary" \
  env ANDROID_SDK_ROOT="$NO_EMULATOR_SDK" ANDROID_HOME="$NO_EMULATOR_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "failedTarget=emulator-binary"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "reason=emulator-binary-missing"
grep -q "Android emulator binary not found" <<<"$LAST_OUTPUT" ||
  fail "Expected emulator helper to report missing emulator binary"

reset_logs
expect_failure \
  "emulator helper rejects unknown requested AVD" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES="" FAKE_EMULATOR_AVDS="other-avd" AVD_NAME="test-avd" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/verify_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "failedTarget=requested-avd"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "reason=requested-avd-not-found"
grep -q "AVD_NAME=test-avd not found" <<<"$LAST_OUTPUT" ||
  fail "Expected emulator helper to report unknown AVD"

reset_logs
expect_success \
  "emulator helper selects the only authorized emulator" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "target=emulator"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "failedTarget="
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "reason="
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "serial=emulator-5554"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "api_level=36"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "avd=test-avd"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "evidence_dir=$ARTIFACT_DIR"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "screenshot_file=$ARTIFACT_DIR/screenshot.png"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "window_dump_file=$ARTIFACT_DIR/window.xml"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "logcat_file=$ARTIFACT_DIR/logcat.txt"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "crash_anr_smoke_report_file=$ARTIFACT_DIR/crash-anr-smoke.properties"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "device_report_file=$ARTIFACT_DIR/device-verification.properties"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "artifact_schema=DeviceVerificationArtifact/v1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=emulator-5554"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_test_count=20"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "test_count=20"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_output_file=$ARTIFACT_DIR/instrumentation.txt"
grep -Eq '^instrumentation_output_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/device-verification.properties" ||
  fail "Expected emulator nested device report to write instrumentation output SHA-256"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "logcat_file=$ARTIFACT_DIR/logcat.txt"
grep -Eq '^logcat_sha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/device-verification.properties" ||
  fail "Expected emulator nested device report to write logcat SHA-256"
assert_report_contains "$ARTIFACT_DIR/crash-anr-smoke.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/crash-anr-smoke.properties" "logcatFile=$ARTIFACT_DIR/logcat.txt"
assert_report_contains "$ARTIFACT_DIR/crash-anr-smoke.properties" "noReproducibleAnr=true"
grep -q -- "-s emulator-5554 shell getprop sys.boot_completed" "$FAKE_ADB_LOG" ||
  fail "Expected emulator helper to wait for emulator boot completion"
grep -q -- "-s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk" "$FAKE_ADB_LOG" ||
  fail "Expected emulator helper to install debug APK on selected emulator"

reset_logs
expect_success \
  "emulator helper starts requested AVD" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5556\tdevice' AVD_NAME="test-avd" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/verify_emulator.sh
assert_gradle_called
grep -q "Starting emulator AVD: test-avd" <<<"$LAST_OUTPUT" ||
  fail "Expected emulator helper to enter AVD startup path"
grep -q -- "-avd test-avd -wipe-data -no-window -no-audio -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect" "$FAKE_EMULATOR_LOG" ||
  fail "Expected emulator helper to start requested AVD with deterministic default emulator args"

reset_logs
expect_success \
  "emulator helper honors explicit emulator args" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5556\tdevice' AVD_NAME="test-avd" \
  EMULATOR_ARGS="-no-window -no-audio" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/verify_emulator.sh
assert_gradle_called
grep -q -- "-avd test-avd -no-window -no-audio" "$FAKE_EMULATOR_LOG" ||
  fail "Expected emulator helper to pass explicit emulator args"
if grep -q -- "-wipe-data" "$FAKE_EMULATOR_LOG"; then
  fail "Explicit EMULATOR_ARGS should override default emulator args"
fi

reset_logs
SAVED_ARTIFACT_DIR="$ARTIFACT_DIR"
expect_success \
  "emulator helper keeps default device artifacts with emulator artifacts" \
  env -u ARTIFACT_DIR ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_emulator.sh
EMULATOR_DEFAULT_REPORT="$(sed -nE 's/^Emulator verification report: (.*)$/\1/p' <<<"$LAST_OUTPUT" | tail -n 1)"
[[ -n "$EMULATOR_DEFAULT_REPORT" && -f "$EMULATOR_DEFAULT_REPORT" ]] ||
  fail "Expected emulator helper to print its default report path"
DEFAULT_DEVICE_REPORT="$(awk -F= '$1 == "device_report_file" {print $2}' "$EMULATOR_DEFAULT_REPORT")"
[[ -n "$DEFAULT_DEVICE_REPORT" && -f "$DEFAULT_DEVICE_REPORT" ]] ||
  fail "Expected default emulator report to link an existing nested device report"
DEFAULT_INSTRUMENTATION_OUTPUT="$(awk -F= '$1 == "instrumentation_output_file" {print $2}' "$DEFAULT_DEVICE_REPORT")"
[[ "$DEFAULT_INSTRUMENTATION_OUTPUT" == "$(dirname "$DEFAULT_DEVICE_REPORT")/instrumentation.txt" ]] ||
  fail "Expected default nested instrumentation output to live beside the device report"
[[ -s "$DEFAULT_INSTRUMENTATION_OUTPUT" ]] ||
  fail "Expected default nested instrumentation output to be non-empty"
ARTIFACT_DIR="$SAVED_ARTIFACT_DIR"
export ARTIFACT_DIR

reset_logs
expect_success \
  "fresh start main shell helper accepts clean install main shell" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_fresh_start_main_shell_emulator.sh
grep -q -- ":app:assembleDebug" "$FAKE_GRADLE_LOG" ||
  fail "Expected fresh start helper to assemble debug APK"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "target=fresh-start-main-shell"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "serial=emulator-5554"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "first_run_setup_visible=false"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "main_shell_copy_visible=true"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "model_manager_click_opened=true"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "screenshot=$ARTIFACT_DIR/fresh-start.png"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "window_dump=$ARTIFACT_DIR/fresh-start.xml"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "model_manager_window_dump=$ARTIFACT_DIR/model-manager.xml"
grep -q -- "-s emulator-5554 uninstall com.bytedance.zgx.solin" "$FAKE_ADB_LOG" ||
  fail "Expected fresh start helper to clean install the app"
grep -q -- "-s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk" "$FAKE_ADB_LOG" ||
  fail "Expected fresh start helper to install debug APK"
grep -q -- "-s emulator-5554 shell am start -W -n com.bytedance.zgx.solin/.MainActivity" "$FAKE_ADB_LOG" ||
  fail "Expected fresh start helper to launch MainActivity"
grep -q -- "-s emulator-5554 shell input tap 534 212" "$FAKE_ADB_LOG" ||
  fail "Expected fresh start helper to tap the model manager button"
assert_report_contains_text "$ARTIFACT_DIR/model-manager.xml" "远程多模态可选"

reset_logs
expect_success \
  "fresh start main shell helper skips first-run setup then validates main shell" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_FRESH_START_SHOW_FIRST_RUN_ONCE=1 \
  GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_fresh_start_main_shell_emulator.sh
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "first_run_setup_visible=true"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "first_run_setup_skipped=true"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "main_shell_copy_visible=true"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "model_manager_click_opened=true"

reset_logs
expect_failure \
  "fresh start main shell helper rejects model manager click with no response" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_MODEL_MANAGER_UI_TEXT="仍停留在主页面" \
  GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_fresh_start_main_shell_emulator.sh
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "failedTarget=ui"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "reason=model-manager-click-no-response"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "main_shell_copy_visible=true"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "model_manager_click_opened=false"

reset_logs
expect_failure \
  "fresh start main shell helper rejects stuck first-run setup page" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_FRESH_START_SHOW_STUCK_FIRST_RUN=1 \
  GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_fresh_start_main_shell_emulator.sh
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "failedTarget=ui"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "reason=first-run-setup-skip-failed"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "first_run_setup_visible=true"
assert_report_contains "$ARTIFACT_DIR/fresh-start-main-shell.properties" "first_run_setup_skipped=false"

reset_logs
LIVE_REMOTE_TEST_TOKEN="$TMP_DIR/live-remote-token-from-env"
expect_failure \
  "live remote emulator reports missing base url reason before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  SOLIN_LIVE_REMOTE_MODEL="validation-model" \
  SOLIN_LIVE_REMOTE_API_KEY="$LIVE_REMOTE_TEST_TOKEN" \
  SOLIN_LIVE_REMOTE_WAIT_SECONDS=0 \
  scripts/live_remote_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "target=live-remote-emulator"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "device_target=emulator"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "failedTarget=configuration"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "reason=missing-base-url"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "screenshot=$ARTIFACT_DIR/live-remote-result.png"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "ui_dump=$ARTIFACT_DIR/live-remote-result.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "logcat_file=$ARTIFACT_DIR/live-remote-logcat.txt"

reset_logs
expect_failure \
  "live remote emulator rejects physical serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" GRADLE_CMD="$FAKE_GRADLE" \
  SOLIN_LIVE_REMOTE_BASE_URL="https://remote.example.test/v1" \
  SOLIN_LIVE_REMOTE_MODEL="validation-model" \
  SOLIN_LIVE_REMOTE_API_KEY="$LIVE_REMOTE_TEST_TOKEN" \
  SOLIN_LIVE_REMOTE_WAIT_SECONDS=0 \
  scripts/live_remote_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "failedTarget=emulator-selection"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "reason=android-serial-not-emulator"

reset_logs
expect_success \
  "live remote helper allows explicit physical target" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" GRADLE_CMD="$FAKE_GRADLE" \
  SOLIN_LIVE_REMOTE_TARGET=device \
  SOLIN_LIVE_REMOTE_BASE_URL="https://remote.example.test/v1" \
  SOLIN_LIVE_REMOTE_MODEL="validation-model" \
  SOLIN_LIVE_REMOTE_API_KEY="$LIVE_REMOTE_TEST_TOKEN" \
  SOLIN_LIVE_REMOTE_WAIT_SECONDS=0 \
  scripts/live_remote_emulator.sh
grep -q -- "-s device-a install -r app/build/outputs/apk/debug/app-debug.apk" "$FAKE_ADB_LOG" ||
  fail "Expected explicit physical live remote helper to install the debug APK on the selected device"
grep -q -- "-s device-a shell wm size" "$FAKE_ADB_LOG" ||
  fail "Expected explicit physical live remote helper to read the device screen size"
grep -q -- "-s device-a shell input tap 395 2208" "$FAKE_ADB_LOG" ||
  fail "Expected explicit physical live remote helper to tap the prompt field from UI dump coordinates"
grep -q -- "-s device-a shell input tap 979 2244" "$FAKE_ADB_LOG" ||
  fail "Expected explicit physical live remote helper to tap the send button from UI dump coordinates"
grep -q -- "-s device-a shell input tap 530 1910" "$FAKE_ADB_LOG" ||
  fail "Expected explicit physical live remote helper to confirm remote send from UI dump coordinates"
receiver_broadcast_count="$(
  grep -cE -- "shell run-as com[.]bytedance[.]zgx[.]solin am broadcast .* -n com[.]bytedance[.]zgx[.]solin/[.]debug[.]DebugRemoteConfigReceiver" "$FAKE_ADB_LOG" || true
)"
[[ "$receiver_broadcast_count" -ge 2 ]] ||
  fail "Expected explicit physical live remote helper to configure and clear the debug receiver"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "target=live-remote-device"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "device_target=device"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "api_key_source=SOLIN_LIVE_REMOTE_API_KEY"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "base_url=<redacted>"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "model=<redacted>"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "input_dump=$ARTIFACT_DIR/live-remote-before-input.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "send_ready_dump=$ARTIFACT_DIR/live-remote-before-send.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "after_send_dump=$ARTIFACT_DIR/live-remote-after-send.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-device.properties" "remote_confirmation_handled=true"
assert_report_contains_text "$ARTIFACT_DIR/live-remote-after-send.xml" "远程地址：<redacted>"
assert_report_contains_text "$ARTIFACT_DIR/live-remote-after-send.xml" "模型：<redacted>"
if grep -Fq "$LIVE_REMOTE_TEST_TOKEN" "$ARTIFACT_DIR/live-remote-after-send.xml" ||
  grep -Fq "remote.example.test" "$ARTIFACT_DIR/live-remote-after-send.xml" ||
  grep -Fq "validation-model" "$ARTIFACT_DIR/live-remote-after-send.xml"; then
  fail "Live remote text evidence must redact remote secret/config values"
fi
[[ -s "$ARTIFACT_DIR/live-remote-logcat.txt" ]] ||
  fail "Expected physical live remote success logcat evidence"
if grep -Fq "$LIVE_REMOTE_TEST_TOKEN" "$ARTIFACT_DIR/live-remote-device.properties"; then
  fail "Physical live remote report must not persist the remote API key"
fi
if grep -Fq "$LIVE_REMOTE_TEST_TOKEN" "$FAKE_ADB_LOG"; then
  fail "Physical live remote helper must not pass the remote API key through adb argv/log"
fi

reset_logs
expect_success \
  "live remote emulator uses app uid for debug receiver broadcasts" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  SOLIN_LIVE_REMOTE_BASE_URL="https://remote.example.test/v1" \
  SOLIN_LIVE_REMOTE_MODEL="validation-model" \
  SOLIN_LIVE_REMOTE_API_KEY="$LIVE_REMOTE_TEST_TOKEN" \
  SOLIN_LIVE_REMOTE_WAIT_SECONDS=0 \
  scripts/live_remote_emulator.sh
grep -q -- ":app:assembleDebug" "$FAKE_GRADLE_LOG" ||
  fail "Expected live remote helper to assemble the debug APK"
receiver_broadcast_count="$(
  grep -cE -- "shell run-as com[.]bytedance[.]zgx[.]solin am broadcast .* -n com[.]bytedance[.]zgx[.]solin/[.]debug[.]DebugRemoteConfigReceiver" "$FAKE_ADB_LOG" || true
)"
[[ "$receiver_broadcast_count" -ge 2 ]] ||
  fail "Expected live remote helper to configure and clear the debug receiver through run-as"
if grep -q -- "-s emulator-5554 shell am broadcast" "$FAKE_ADB_LOG"; then
  fail "Live remote helper must not broadcast to the debug receiver from the shell uid"
fi
grep -q -- "--ez clearState true" "$FAKE_ADB_LOG" ||
  fail "Expected live remote helper to request state clearing during setup"
grep -q -- "--ez clearRemoteConfig true" "$FAKE_ADB_LOG" ||
  fail "Expected live remote helper to clear remote config on exit"
grep -q -- "am broadcast --user 0 -n com.bytedance.zgx.solin/.debug.DebugRemoteConfigReceiver" "$FAKE_ADB_LOG" ||
  fail "Expected live remote helper to pin debug receiver broadcasts to user 0"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "target=live-remote-emulator"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "device_target=emulator"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "failedTarget="
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "reason="
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "api_key_source=SOLIN_LIVE_REMOTE_API_KEY"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "base_url=<redacted>"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "evidence_dir=$ARTIFACT_DIR"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "screenshot=$ARTIFACT_DIR/live-remote-result.png"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "ui_dump=$ARTIFACT_DIR/live-remote-result.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "input_dump=$ARTIFACT_DIR/live-remote-before-input.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "send_ready_dump=$ARTIFACT_DIR/live-remote-before-send.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "after_send_dump=$ARTIFACT_DIR/live-remote-after-send.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "logcat_file=$ARTIFACT_DIR/live-remote-logcat.txt"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "remote_confirmation_handled=true"
assert_report_contains_text "$ARTIFACT_DIR/live-remote-after-send.xml" "远程地址：<redacted>"
assert_report_contains_text "$ARTIFACT_DIR/live-remote-after-send.xml" "模型：<redacted>"
[[ -s "$ARTIFACT_DIR/live-remote-logcat.txt" ]] ||
  fail "Expected live remote success logcat evidence"
if grep -Fq "$LIVE_REMOTE_TEST_TOKEN" "$ARTIFACT_DIR/live-remote-emulator.properties"; then
  fail "Live remote report must not persist the remote API key"
fi
if grep -Fq "$LIVE_REMOTE_TEST_TOKEN" "$FAKE_ADB_LOG"; then
  fail "Live remote helper must not pass the remote API key through adb argv/log"
fi

reset_logs
expect_failure \
  "live remote emulator reports expected text missing with evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  FAKE_LIVE_REMOTE_UI_TEXT="WRONG_REMOTE_TEXT" \
  SOLIN_LIVE_REMOTE_BASE_URL="https://remote.example.test/v1" \
  SOLIN_LIVE_REMOTE_MODEL="validation-model" \
  SOLIN_LIVE_REMOTE_API_KEY="$LIVE_REMOTE_TEST_TOKEN" \
  SOLIN_LIVE_REMOTE_WAIT_SECONDS=0 \
  scripts/live_remote_emulator.sh
grep -q -- ":app:assembleDebug" "$FAKE_GRADLE_LOG" ||
  fail "Expected live remote helper to assemble the debug APK before expected-text validation"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "failedTarget=expected-response"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "reason=expected-text-not-found"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "screenshot=$ARTIFACT_DIR/live-remote-result.png"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "ui_dump=$ARTIFACT_DIR/live-remote-result.xml"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "logcat_file=$ARTIFACT_DIR/live-remote-logcat.txt"
[[ -s "$ARTIFACT_DIR/live-remote-result.png" ]] ||
  fail "Expected live remote failure screenshot evidence"
[[ -s "$ARTIFACT_DIR/live-remote-result.xml" ]] ||
  fail "Expected live remote failure UI dump evidence"
[[ -s "$ARTIFACT_DIR/live-remote-logcat.txt" ]] ||
  fail "Expected live remote failure logcat evidence"
if grep -Fq "$LIVE_REMOTE_TEST_TOKEN" "$ARTIFACT_DIR/live-remote-emulator.properties"; then
  fail "Live remote failure report must not persist the remote API key"
fi

reset_logs
expect_failure \
  "release screenshot capture rejects physical serial by default" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/capture_release_screenshots.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "failedTarget=android-serial"
assert_report_contains "$ARTIFACT_DIR/release-screenshots.properties" "reason=android-serial-not-emulator"
