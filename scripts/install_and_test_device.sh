#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
ADB_BIN="${ANDROID_SDK}/platform-tools/adb"
CLEAN_DEVICE="${CLEAN_DEVICE:-0}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SKIP_INSTALL="${SKIP_INSTALL:-0}"
REQUIRE_SOLIN_ACCESSIBILITY="${REQUIRE_SOLIN_ACCESSIBILITY:-0}"
RESET_APP_DATA_AFTER_TESTS="${RESET_APP_DATA_AFTER_TESTS:-1}"
KEEP_DEVICE_AWAKE_FOR_TESTS="${KEEP_DEVICE_AWAKE_FOR_TESTS:-1}"
ALLOW_MIUI_BACKGROUND_ACTIVITY_STARTS="${ALLOW_MIUI_BACKGROUND_ACTIVITY_STARTS:-1}"
MIUI_BACKGROUND_ACTIVITY_START_OP="${MIUI_BACKGROUND_ACTIVITY_START_OP:-10021}"
PREPARE_RUNTIME_PERMISSION_DIALOG_TESTS="${PREPARE_RUNTIME_PERMISSION_DIALOG_TESTS:-1}"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/device-$(date +%Y%m%d-%H%M%S)}"
VERIFICATION_REPORT_FILE="${VERIFICATION_REPORT_FILE:-${ARTIFACT_DIR}/device-verification.properties}"
INSTRUMENTATION_OUTPUT_FILE="${INSTRUMENTATION_OUTPUT_FILE:-${ARTIFACT_DIR}/instrumentation.txt}"
LOGCAT_FILE="${LOGCAT_FILE:-${ARTIFACT_DIR}/logcat.txt}"
LOGCAT_TAIL_LINES="${LOGCAT_TAIL_LINES:-500}"
INSTRUMENTATION_CLASS="${INSTRUMENTATION_CLASS:-}"
INSTRUMENTATION_TIMEOUT_SECONDS="${INSTRUMENTATION_TIMEOUT_SECONDS:-900}"
RELEASE_ARTIFACT_SHA256="${RELEASE_ARTIFACT_SHA256:-}"
DEFER_DEVICE_TESTS="${DEFER_DEVICE_TESTS:-0}"
DEFERRED_REASON="${DEFERRED_REASON:-}"
STATUS_OVERRIDE="${STATUS_OVERRIDE:-}"

STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SELECTED_SERIAL=""
API_LEVEL=""
ABI_LIST=""
DATA_FREE_KB=""
INSTRUMENTATION_STATUS="not-run"
INSTRUMENTATION_TEST_COUNT=""
LOGCAT_CAPTURED=0
MIUI_BACKGROUND_ACTIVITY_STARTS_PREPARED=0
MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORE_TARGET=""
MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORE_TEST=""
MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORED=0
DEVICE_AWAKE_PREPARED=0
DEVICE_AWAKE_RESTORED=0
DEVICE_STAY_ON_WHILE_PLUGGED_IN_RESTORE=""
RUNTIME_PERMISSION_DIALOG_TESTS_PREPARED=0
RUNTIME_PERMISSION_DIALOG_TESTS_RESTORED=0
READ_CONTACTS_PERMISSION_RESTORE_STATE=""
RECORD_AUDIO_PERMISSION_RESTORE_STATE=""
FAILED_TARGET=""
FAILURE_REASON=""
SCRIPT_COMPLETED=0
REPORT_WRITTEN=0

PACKAGE_NAME="com.bytedance.zgx.solin"
TEST_PACKAGE_NAME="${PACKAGE_NAME}.test"
MAIN_ACTIVITY="${PACKAGE_NAME}/.MainActivity"
TEST_RUNNER="${TEST_PACKAGE_NAME}/androidx.test.runner.AndroidJUnitRunner"
SOLIN_ACCESSIBILITY_SERVICE="${PACKAGE_NAME}/${PACKAGE_NAME}.device.SolinAccessibilityService"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
ANDROID_TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
REQUIRED_FREE_KB=$((3 * 1024 * 1024))

write_verification_report() {
  local exit_code="$1"
  local status_label="failed"
  local artifact_id
  local instrumentation_output_sha256=""
  local logcat_sha256=""
  if [[ -n "$STATUS_OVERRIDE" ]]; then
    status_label="$STATUS_OVERRIDE"
  elif [[ "$exit_code" -eq 0 ]]; then
    status_label="passed"
  fi
  artifact_id="device-${SELECTED_SERIAL:-unselected}-api${API_LEVEL:-unknown}-${STARTED_AT_UTC}"
  artifact_id="${artifact_id//:/}"
  if [[ -f "$INSTRUMENTATION_OUTPUT_FILE" ]]; then
    instrumentation_output_sha256="$(shasum -a 256 "$INSTRUMENTATION_OUTPUT_FILE" | awk '{print $1}')"
  fi
  if [[ -f "$LOGCAT_FILE" ]]; then
    logcat_sha256="$(shasum -a 256 "$LOGCAT_FILE" | awk '{print $1}')"
  fi

  mkdir -p "$(dirname "$VERIFICATION_REPORT_FILE")"
  {
    echo "artifact_schema=DeviceVerificationArtifact/v1"
    echo "artifact_id=$artifact_id"
    echo "status=$status_label"
    echo "exit_code=$exit_code"
    echo "target=device"
    echo "failedTarget=${FAILED_TARGET:-}"
    echo "reason=${FAILURE_REASON:-}"
    echo "deferredReason=$DEFERRED_REASON"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=${SELECTED_SERIAL:-}"
    echo "api_level=${API_LEVEL:-}"
    echo "abi=${ABI_LIST:-}"
    echo "clean_device=$CLEAN_DEVICE"
    echo "skip_build=$SKIP_BUILD"
    echo "skip_install=$SKIP_INSTALL"
    echo "require_solin_accessibility=$REQUIRE_SOLIN_ACCESSIBILITY"
    echo "reset_app_data_after_tests=$RESET_APP_DATA_AFTER_TESTS"
    echo "keep_device_awake_for_tests=$KEEP_DEVICE_AWAKE_FOR_TESTS"
    echo "device_awake_prepared=$DEVICE_AWAKE_PREPARED"
    echo "device_awake_restored=$DEVICE_AWAKE_RESTORED"
    echo "allow_miui_background_activity_starts=$ALLOW_MIUI_BACKGROUND_ACTIVITY_STARTS"
    echo "miui_background_activity_start_op=$MIUI_BACKGROUND_ACTIVITY_START_OP"
    echo "miui_background_activity_starts_prepared=$MIUI_BACKGROUND_ACTIVITY_STARTS_PREPARED"
    echo "miui_background_activity_starts_restored=$MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORED"
    echo "prepare_runtime_permission_dialog_tests=$PREPARE_RUNTIME_PERMISSION_DIALOG_TESTS"
    echo "runtime_permission_dialog_tests_prepared=$RUNTIME_PERMISSION_DIALOG_TESTS_PREPARED"
    echo "runtime_permission_dialog_tests_restored=$RUNTIME_PERMISSION_DIALOG_TESTS_RESTORED"
    echo "data_free_kb=${DATA_FREE_KB:-}"
    echo "instrumentation=$INSTRUMENTATION_STATUS"
    echo "instrumentation_test_count=${INSTRUMENTATION_TEST_COUNT:-}"
    echo "instrumentation_class=${INSTRUMENTATION_CLASS:-}"
    echo "instrumentation_timeout_seconds=$INSTRUMENTATION_TIMEOUT_SECONDS"
    echo "instrumentation_output_file=$INSTRUMENTATION_OUTPUT_FILE"
    echo "instrumentation_output_sha256=$instrumentation_output_sha256"
    echo "test_count=${INSTRUMENTATION_TEST_COUNT:-}"
    echo "releaseArtifactSha256=$RELEASE_ARTIFACT_SHA256"
    echo "logcat_file=$LOGCAT_FILE"
    echo "logcat_sha256=$logcat_sha256"
    echo "logcat_captured=$LOGCAT_CAPTURED"
    echo "logcat_tail_lines=$LOGCAT_TAIL_LINES"
    echo "debug_apk=$DEBUG_APK"
    echo "android_test_apk=$ANDROID_TEST_APK"
  } > "$VERIFICATION_REPORT_FILE"
  REPORT_WRITTEN=1
  echo "Device verification report: $VERIFICATION_REPORT_FILE"
}

selected_device_is_available() {
  local state
  if [[ -z "${SELECTED_SERIAL:-}" || ! -x "$ADB_BIN" ]]; then
    return 1
  fi
  state="$("$ADB_BIN" devices | awk -v serial="$SELECTED_SERIAL" '$1 == serial {print $2; found = 1} END {if (!found) print ""}')"
  [[ "$state" == "device" ]]
}

clear_logcat_window() {
  if ! selected_device_is_available; then
    return
  fi
  "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -c >/dev/null 2>&1 || true
}

capture_logcat_artifact() {
  if ! selected_device_is_available; then
    return
  fi
  mkdir -p "$(dirname "$LOGCAT_FILE")"
  "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -d -t "$LOGCAT_TAIL_LINES" > "$LOGCAT_FILE" 2>/dev/null || true
  if [[ -s "$LOGCAT_FILE" ]]; then
    LOGCAT_CAPTURED=1
  else
    LOGCAT_CAPTURED=0
  fi
}

stop_test_processes() {
  if ! selected_device_is_available; then
    return
  fi
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell am force-stop "$TEST_PACKAGE_NAME" >/dev/null 2>&1 || true
}

runtime_permission_state_for() {
  local permission="$1"
  local line
  line="$(
    "${ADB[@]}" shell dumpsys package "$PACKAGE_NAME" 2>/dev/null |
      tr -d '\r' |
      awk -v permission="$permission" '$1 == permission ":" {print; exit}'
  )"
  if [[ "$line" == *"granted=true"* ]]; then
    echo "granted"
  else
    echo "denied"
  fi
}

should_prepare_runtime_permission_dialog_tests() {
  if [[ "$PREPARE_RUNTIME_PERMISSION_DIALOG_TESTS" != "1" ]]; then
    return 1
  fi
  [[ -z "$INSTRUMENTATION_CLASS" ||
    "$INSTRUMENTATION_CLASS" == *"MainActivityRuntimePermissionUiTest"* ||
    "$INSTRUMENTATION_CLASS" == *"MainActivityVoicePermissionUiTest"* ]]
}

revoke_runtime_permission_for_dialog_test() {
  local permission="$1"
  "${ADB[@]}" shell pm revoke "$PACKAGE_NAME" "$permission" >/dev/null 2>&1 || true
  "${ADB[@]}" shell pm clear-permission-flags "$PACKAGE_NAME" "$permission" user-set user-fixed >/dev/null 2>&1 || true
}

restore_runtime_permission_for_dialog_test() {
  local permission="$1"
  local state="$2"
  if [[ "$state" == "granted" ]]; then
    "${ADB[@]}" shell pm grant "$PACKAGE_NAME" "$permission" >/dev/null 2>&1 || true
  elif [[ "$state" == "denied" ]]; then
    "${ADB[@]}" shell pm revoke "$PACKAGE_NAME" "$permission" >/dev/null 2>&1 || true
  fi
}

prepare_runtime_permission_dialog_tests() {
  if ! selected_device_is_available || ! should_prepare_runtime_permission_dialog_tests; then
    return
  fi

  READ_CONTACTS_PERMISSION_RESTORE_STATE="$(runtime_permission_state_for android.permission.READ_CONTACTS)"
  RECORD_AUDIO_PERMISSION_RESTORE_STATE="$(runtime_permission_state_for android.permission.RECORD_AUDIO)"
  revoke_runtime_permission_for_dialog_test android.permission.READ_CONTACTS
  revoke_runtime_permission_for_dialog_test android.permission.RECORD_AUDIO
  RUNTIME_PERMISSION_DIALOG_TESTS_PREPARED=1
}

restore_runtime_permission_dialog_tests() {
  if [[ "$RUNTIME_PERMISSION_DIALOG_TESTS_PREPARED" != "1" ||
    "$RUNTIME_PERMISSION_DIALOG_TESTS_RESTORED" == "1" ]] ||
    ! selected_device_is_available; then
    return
  fi

  restore_runtime_permission_for_dialog_test android.permission.READ_CONTACTS \
    "$READ_CONTACTS_PERMISSION_RESTORE_STATE"
  restore_runtime_permission_for_dialog_test android.permission.RECORD_AUDIO \
    "$RECORD_AUDIO_PERMISSION_RESTORE_STATE"
  RUNTIME_PERMISSION_DIALOG_TESTS_RESTORED=1
}

collapse_system_overlays() {
  if ! selected_device_is_available; then
    return
  fi
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell cmd statusbar collapse >/dev/null 2>&1 || true
}

appops_mode_for() {
  local package_name="$1"
  local op="$2"
  local output

  output="$("$ADB_BIN" -s "$SELECTED_SERIAL" shell cmd appops get "$package_name" "$op" 2>/dev/null | tr -d '\r' || true)"
  if [[ "$output" =~ :[[:space:]]*([A-Za-z_]+) ]]; then
    echo "${BASH_REMATCH[1]}"
  else
    echo ""
  fi
}

set_appops_mode_if_known() {
  local package_name="$1"
  local op="$2"
  local mode="$3"
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell cmd appops set "$package_name" "$op" "$mode" >/dev/null 2>&1
}

prepare_miui_background_activity_starts() {
  if [[ "$ALLOW_MIUI_BACKGROUND_ACTIVITY_STARTS" != "1" ]] || ! selected_device_is_available; then
    return
  fi

  MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORE_TARGET="$(appops_mode_for "$PACKAGE_NAME" "$MIUI_BACKGROUND_ACTIVITY_START_OP")"
  MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORE_TEST="$(appops_mode_for "$TEST_PACKAGE_NAME" "$MIUI_BACKGROUND_ACTIVITY_START_OP")"
  if ! set_appops_mode_if_known "$PACKAGE_NAME" "$MIUI_BACKGROUND_ACTIVITY_START_OP" allow; then
    MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORE_TARGET=""
    MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORE_TEST=""
    return
  fi
  set_appops_mode_if_known "$TEST_PACKAGE_NAME" "$MIUI_BACKGROUND_ACTIVITY_START_OP" allow || true
  MIUI_BACKGROUND_ACTIVITY_STARTS_PREPARED=1
}

restore_miui_background_activity_starts() {
  if [[ "$MIUI_BACKGROUND_ACTIVITY_STARTS_PREPARED" != "1" || "$MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORED" == "1" ]] ||
    ! selected_device_is_available; then
    return
  fi

  if [[ -n "$MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORE_TARGET" ]]; then
    set_appops_mode_if_known "$PACKAGE_NAME" "$MIUI_BACKGROUND_ACTIVITY_START_OP" "$MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORE_TARGET" || true
  fi
  if [[ -n "$MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORE_TEST" ]]; then
    set_appops_mode_if_known "$TEST_PACKAGE_NAME" "$MIUI_BACKGROUND_ACTIVITY_START_OP" "$MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORE_TEST" || true
  fi
  MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORED=1
}

prepare_device_awake_state() {
  if [[ "$KEEP_DEVICE_AWAKE_FOR_TESTS" != "1" ]] || ! selected_device_is_available; then
    return
  fi

  DEVICE_STAY_ON_WHILE_PLUGGED_IN_RESTORE="$(
    "$ADB_BIN" -s "$SELECTED_SERIAL" shell settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r'
  )"
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell svc power stayon true >/dev/null 2>&1 || true
  DEVICE_AWAKE_PREPARED=1
}

restore_device_awake_state() {
  if [[ "$DEVICE_AWAKE_PREPARED" != "1" || "$DEVICE_AWAKE_RESTORED" == "1" ]] ||
    ! selected_device_is_available; then
    return
  fi

  if [[ -z "$DEVICE_STAY_ON_WHILE_PLUGGED_IN_RESTORE" || "$DEVICE_STAY_ON_WHILE_PLUGGED_IN_RESTORE" == "null" ]]; then
    "$ADB_BIN" -s "$SELECTED_SERIAL" shell settings delete global stay_on_while_plugged_in >/dev/null 2>&1 || true
  else
    "$ADB_BIN" -s "$SELECTED_SERIAL" shell settings put global stay_on_while_plugged_in \
      "$DEVICE_STAY_ON_WHILE_PLUGGED_IN_RESTORE" >/dev/null 2>&1 || true
  fi
  DEVICE_AWAKE_RESTORED=1
}

clear_app_data_after_tests() {
  if [[ "$RESET_APP_DATA_AFTER_TESTS" != "1" ]] || ! selected_device_is_available; then
    return
  fi
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell pm clear "$PACKAGE_NAME" >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell pm clear "$TEST_PACKAGE_NAME" >/dev/null 2>&1 || true
}

cleanup_test_device_state() {
  stop_test_processes
  clear_app_data_after_tests
  restore_runtime_permission_dialog_tests
  restore_miui_background_activity_starts
  restore_device_awake_state
  if [[ "$CLEAN_DEVICE" == "1" ]] && selected_device_is_available; then
    "$ADB_BIN" -s "$SELECTED_SERIAL" uninstall "$TEST_PACKAGE_NAME" >/dev/null 2>&1 || true
  fi
}

on_exit() {
  local status="$?"
  trap - EXIT
  if [[ "$SCRIPT_COMPLETED" != "1" ]]; then
    if [[ "$status" -eq 0 ]]; then
      status=1
    fi
    if [[ "$INSTRUMENTATION_STATUS" == "running" ]]; then
      INSTRUMENTATION_STATUS="failed"
    fi
    capture_logcat_artifact
    cleanup_test_device_state
    if [[ -z "$FAILED_TARGET" ]]; then
      FAILED_TARGET="script"
    fi
    if [[ -z "$FAILURE_REASON" ]]; then
      FAILURE_REASON="script-incomplete"
    fi
  fi
  if [[ "$REPORT_WRITTEN" != "1" ]]; then
    write_verification_report "$status"
  fi
  exit "$status"
}

trap on_exit EXIT

if [[ "$DEFER_DEVICE_TESTS" == "1" ]]; then
  DEFERRED_REASON="${DEFERRED_REASON:-no-device-test-in-this-phase}"
  STATUS_OVERRIDE="skipped"
  FAILED_TARGET="device-validation"
  FAILURE_REASON="$DEFERRED_REASON"
  INSTRUMENTATION_STATUS="skipped"
  SCRIPT_COMPLETED=1
  write_verification_report 0
  exit 0
fi

fail_with_reason() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  echo "$*" >&2
  capture_logcat_artifact
  cleanup_test_device_state
  SCRIPT_COMPLETED=1
  write_verification_report 1
  exit 1
}

if ! scripts/doctor.sh --device; then
  fail_with_reason doctor doctor-device-failed "Android device environment check failed."
fi

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  SELECTED_SERIAL="$ANDROID_SERIAL"
  SELECTED_STATE="$("$ADB_BIN" devices | awk -v serial="$SELECTED_SERIAL" '$1 == serial {print $2; found = 1} END {if (!found) print ""}')"
  if [[ "$SELECTED_STATE" != "device" ]]; then
    "$ADB_BIN" devices
    fail_with_reason device-selection selected-device-unavailable \
      "ANDROID_SERIAL=$SELECTED_SERIAL is not an authorized Android device; state is ${SELECTED_STATE:-missing}."
  fi
else
  DEVICE_COUNT="$("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" {count += 1} END{print count + 0}')"
  if [[ "$DEVICE_COUNT" != "1" ]]; then
    "$ADB_BIN" devices
    fail_with_reason device-selection device-selection-ambiguous \
      "Connect exactly one authorized Android device, or set ANDROID_SERIAL to select one."
  fi
  SELECTED_SERIAL="$("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" {print $1; exit}')"
fi

ADB=("$ADB_BIN" -s "$SELECTED_SERIAL")
echo "Using Android device: $SELECTED_SERIAL"
clear_logcat_window

API_LEVEL="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
ABI_LIST="$("${ADB[@]}" shell getprop ro.product.cpu.abilist64 | tr -d '\r')"
if [[ "$ABI_LIST" != *"arm64-v8a"* ]]; then
  fail_with_reason device-abi device-abi-not-arm64 \
    "Connected device is not arm64-v8a compatible: ${ABI_LIST:-unknown}"
fi

DATA_FREE_KB="$("${ADB[@]}" shell df -k /data | awk 'NR == 2 {print $4}' | tr -d '\r')"
if [[ "$DATA_FREE_KB" =~ ^[0-9]+$ && "$DATA_FREE_KB" -lt "$REQUIRED_FREE_KB" ]]; then
  fail_with_reason data-free-space data-free-below-threshold \
    "Connected device has less than 3 GB free on /data; model download/import may fail."
fi

if [[ "$CLEAN_DEVICE" == "1" ]]; then
  "${ADB[@]}" uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true
fi
if [[ "$SKIP_BUILD" == "1" ]]; then
  if [[ "$SKIP_INSTALL" != "1" && ( ! -f "$DEBUG_APK" || ! -f "$ANDROID_TEST_APK" ) ]]; then
    fail_with_reason build skipped-build-apk-missing \
      "SKIP_BUILD=1 requires existing APKs when SKIP_INSTALL is not set."
  fi
else
  "$GRADLE_CMD" assembleDebug assembleDebugAndroidTest
fi

solin_accessibility_enabled() {
  local dump bound_section enabled_line
  dump="$("${ADB[@]}" shell dumpsys accessibility 2>/dev/null | tr -d '\r')"
  bound_section="$(awk '
    /Bound services:/ {printing = 1}
    printing {print}
    /Enabled services:/ {printing = 0}
  ' <<<"$dump")"
  enabled_line="$(grep -m 1 'Enabled services:' <<<"$dump" || true)"
  grep -Fq "$SOLIN_ACCESSIBILITY_SERVICE" <<<"${bound_section}${enabled_line}"
}

require_solin_accessibility_if_needed() {
  if [[ "$REQUIRE_SOLIN_ACCESSIBILITY" != "1" ]]; then
    return 0
  fi
  if solin_accessibility_enabled; then
    return 0
  fi
  cat >&2 <<EOF
栖知 Accessibility service is not enabled.
Open system Accessibility settings, enable 栖知, then rerun with SKIP_INSTALL=1
or DEVICE_CONTROL_SKIP_INSTALL=1 so the debug APK is not reinstalled.
Expected service: $SOLIN_ACCESSIBILITY_SERVICE
EOF
  return 1
}

install_device_apks() {
  if [[ "$SKIP_INSTALL" != "1" ]]; then
    "${ADB[@]}" install -r "$DEBUG_APK" || return
    "${ADB[@]}" install -r -t "$ANDROID_TEST_APK" || return
  fi
}

prepare_device_for_instrumentation() {
  prepare_device_awake_state
  stop_test_processes
  prepare_runtime_permission_dialog_tests
  collapse_system_overlays
  prepare_miui_background_activity_starts
  require_solin_accessibility_if_needed || return
}

run_instrumentation_tests() {
  if [[ -n "$INSTRUMENTATION_CLASS" ]]; then
    "${ADB[@]}" shell am instrument -w -r -e class "$INSTRUMENTATION_CLASS" "$TEST_RUNNER"
  else
    "${ADB[@]}" shell am instrument -w -r "$TEST_RUNNER"
  fi
}

run_with_timeout_capture() {
  local timeout_seconds="$1"
  shift
  local output_file timeout_marker command_pid watchdog_pid status

  output_file="$(mktemp)"
  timeout_marker="$(mktemp)"
  rm -f "$timeout_marker"

  "$@" >"$output_file" 2>&1 &
  command_pid=$!
  if [[ "$timeout_seconds" =~ ^[0-9]+$ && "$timeout_seconds" -gt 0 ]]; then
    (
      sleep "$timeout_seconds"
      if kill -0 "$command_pid" >/dev/null 2>&1; then
        touch "$timeout_marker"
        kill_process_tree INT "$command_pid"
        sleep 2
        kill_process_tree TERM "$command_pid"
        sleep 2
        kill_process_tree KILL "$command_pid"
      fi
    ) >/dev/null 2>&1 &
    watchdog_pid=$!
  else
    watchdog_pid=""
  fi

  set +e
  wait "$command_pid"
  status=$?
  set -e

  if [[ -n "$watchdog_pid" ]]; then
    kill_process_tree TERM "$watchdog_pid"
    sleep 0.1
    kill_process_tree KILL "$watchdog_pid"
    wait "$watchdog_pid" >/dev/null 2>&1 || true
  fi

  cat "$output_file"
  if [[ -f "$timeout_marker" ]]; then
    rm -f "$output_file" "$timeout_marker"
    return 124
  fi
  rm -f "$output_file" "$timeout_marker"
  return "$status"
}

instrumentation_output_failed() {
  grep -qE '^(FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2|INSTRUMENTATION_RESULT: shortMsg=|Error in )' <<<"$1"
}

instrumentation_output_succeeded() {
  grep -qE '^OK( \([0-9]+ tests?\))?\r?$' <<<"$1"
}

kill_process_tree() {
  local signal="$1"
  local pid="$2"
  local child
  if [[ -z "$pid" ]]; then
    return
  fi
  while IFS= read -r child; do
    [[ -n "$child" ]] || continue
    kill_process_tree "$signal" "$child"
  done < <(pgrep -P "$pid" 2>/dev/null || true)
  kill "-$signal" "$pid" >/dev/null 2>&1 || true
}

instrumentation_test_count_for() {
  local output="$1"
  local count

  count="$(awk -F= '/^INSTRUMENTATION_STATUS: numtests=/ {value = $2} END {print value}' <<<"$output" | tr -d '\r')"
  if [[ "$count" =~ ^[0-9]+$ ]]; then
    echo "$count"
    return
  fi

  count="$(sed -nE 's/.*OK \(([0-9]+) tests?\).*/\1/p' <<<"$output" | tail -n 1 | tr -d '\r')"
  if [[ "$count" =~ ^[0-9]+$ ]]; then
    echo "$count"
    return
  fi

  count="$(sed -nE 's/.*Tests run:[[:space:]]*([0-9]+).*/\1/p' <<<"$output" | tail -n 1 | tr -d '\r')"
  if [[ "$count" =~ ^[0-9]+$ ]]; then
    echo "$count"
  fi
}

if ! install_device_apks; then
  fail_with_reason install install-command-failed "Failed to install 栖知 debug or androidTest APK."
fi

if ! prepare_device_for_instrumentation; then
  fail_with_reason instrumentation-preparation instrumentation-preparation-failed \
    "Failed to prepare the connected device for instrumentation."
fi

INSTRUMENTATION_STATUS="running"
set +e
TEST_OUTPUT="$(run_with_timeout_capture "$INSTRUMENTATION_TIMEOUT_SECONDS" run_instrumentation_tests)"
TEST_STATUS=$?
set -e
mkdir -p "$(dirname "$INSTRUMENTATION_OUTPUT_FILE")"
printf '%s\n' "$TEST_OUTPUT" > "$INSTRUMENTATION_OUTPUT_FILE"
printf '%s\n' "$TEST_OUTPUT"
INSTRUMENTATION_TEST_COUNT="$(instrumentation_test_count_for "$TEST_OUTPUT")"
INSTRUMENTATION_SUCCEEDED=0
if instrumentation_output_succeeded "$TEST_OUTPUT"; then
  INSTRUMENTATION_SUCCEEDED=1
fi
if [[ "$TEST_STATUS" -eq 124 ]]; then
  echo "Device instrumentation timed out after ${INSTRUMENTATION_TIMEOUT_SECONDS}s." >&2
  TEST_STATUS=1
  FAILED_TARGET="instrumentation"
  FAILURE_REASON="instrumentation-timeout"
  cleanup_test_device_state
fi
if [[ "$TEST_STATUS" -ne 0 &&
  "$FAILURE_REASON" != "instrumentation-timeout" &&
  "$INSTRUMENTATION_SUCCEEDED" == "1" ]]; then
  TEST_STATUS=0
fi
if [[ "$TEST_STATUS" -eq 0 ]] && instrumentation_output_failed "$TEST_OUTPUT"; then
  TEST_STATUS=1
  FAILED_TARGET="instrumentation"
  FAILURE_REASON="instrumentation-failed"
fi
if [[ "$TEST_STATUS" -eq 0 && "$INSTRUMENTATION_SUCCEEDED" != "1" ]]; then
  echo "Instrumentation output did not include a final OK/success marker." >&2
  TEST_STATUS=1
  FAILED_TARGET="instrumentation"
  FAILURE_REASON="instrumentation-success-marker-missing"
fi
if [[ "$TEST_STATUS" -ne 0 ]]; then
  INSTRUMENTATION_STATUS="failed"
  if grep -q "INSTALL_FAILED_USER_RESTRICTED" <<<"$TEST_OUTPUT"; then
    FAILED_TARGET="install"
    FAILURE_REASON="install-user-restricted"
    cat >&2 <<'EOF'

Device refused ADB installation.
On Xiaomi/HyperOS/MIUI devices, enable Developer options -> USB debugging,
USB install / Install via USB, and accept any install confirmation shown on the phone.
Then rerun scripts/install_and_test_device.sh.
EOF
  fi
  if grep -q "栖知 Accessibility service is not enabled" <<<"$TEST_OUTPUT"; then
    FAILED_TARGET="accessibility-permission"
    FAILURE_REASON="solin-accessibility-not-enabled"
  fi
  if [[ -z "$FAILED_TARGET" ]]; then
    FAILED_TARGET="instrumentation"
  fi
  if [[ -z "$FAILURE_REASON" ]]; then
    FAILURE_REASON="instrumentation-command-failed"
  fi
  capture_logcat_artifact
  cleanup_test_device_state
  SCRIPT_COMPLETED=1
  write_verification_report "$TEST_STATUS"
  exit "$TEST_STATUS"
fi
INSTRUMENTATION_STATUS="passed"

clear_app_data_after_tests
restore_runtime_permission_dialog_tests
restore_miui_background_activity_starts
restore_device_awake_state
"${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" >/dev/null
capture_logcat_artifact

SCRIPT_COMPLETED=1
echo "Device install and smoke test passed. App remains installed."
