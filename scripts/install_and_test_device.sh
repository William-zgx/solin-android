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
REQUIRE_POCKETMIND_ACCESSIBILITY="${REQUIRE_POCKETMIND_ACCESSIBILITY:-0}"
RESET_APP_DATA_AFTER_TESTS="${RESET_APP_DATA_AFTER_TESTS:-1}"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/device-$(date +%Y%m%d-%H%M%S)}"
VERIFICATION_REPORT_FILE="${VERIFICATION_REPORT_FILE:-${ARTIFACT_DIR}/device-verification.properties}"
INSTRUMENTATION_OUTPUT_FILE="${INSTRUMENTATION_OUTPUT_FILE:-${ARTIFACT_DIR}/instrumentation.txt}"
LOGCAT_FILE="${LOGCAT_FILE:-${ARTIFACT_DIR}/logcat.txt}"
LOGCAT_TAIL_LINES="${LOGCAT_TAIL_LINES:-500}"
INSTRUMENTATION_CLASS="${INSTRUMENTATION_CLASS:-}"
INSTRUMENTATION_TIMEOUT_SECONDS="${INSTRUMENTATION_TIMEOUT_SECONDS:-900}"
RELEASE_ARTIFACT_SHA256="${RELEASE_ARTIFACT_SHA256:-}"

STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SELECTED_SERIAL=""
API_LEVEL=""
ABI_LIST=""
DATA_FREE_KB=""
INSTRUMENTATION_STATUS="not-run"
INSTRUMENTATION_TEST_COUNT=""
LOGCAT_CAPTURED=0
FAILED_TARGET=""
FAILURE_REASON=""
SCRIPT_COMPLETED=0
REPORT_WRITTEN=0

PACKAGE_NAME="com.bytedance.zgx.pocketmind"
TEST_PACKAGE_NAME="${PACKAGE_NAME}.test"
MAIN_ACTIVITY="${PACKAGE_NAME}/.MainActivity"
TEST_RUNNER="${TEST_PACKAGE_NAME}/androidx.test.runner.AndroidJUnitRunner"
POCKETMIND_ACCESSIBILITY_SERVICE="${PACKAGE_NAME}/${PACKAGE_NAME}.device.PocketMindAccessibilityService"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
ANDROID_TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
REQUIRED_FREE_KB=$((3 * 1024 * 1024))

write_verification_report() {
  local exit_code="$1"
  local status_label="failed"
  local artifact_id
  local instrumentation_output_sha256=""
  local logcat_sha256=""
  [[ "$exit_code" -eq 0 ]] && status_label="passed"
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
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=${SELECTED_SERIAL:-}"
    echo "api_level=${API_LEVEL:-}"
    echo "abi=${ABI_LIST:-}"
    echo "clean_device=$CLEAN_DEVICE"
    echo "skip_build=$SKIP_BUILD"
    echo "skip_install=$SKIP_INSTALL"
    echo "require_pocketmind_accessibility=$REQUIRE_POCKETMIND_ACCESSIBILITY"
    echo "reset_app_data_after_tests=$RESET_APP_DATA_AFTER_TESTS"
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

clear_logcat_window() {
  if [[ -z "${SELECTED_SERIAL:-}" || ! -x "$ADB_BIN" ]]; then
    return
  fi
  "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -c >/dev/null 2>&1 || true
}

capture_logcat_artifact() {
  if [[ -z "${SELECTED_SERIAL:-}" || ! -x "$ADB_BIN" ]]; then
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
  if [[ -z "${SELECTED_SERIAL:-}" || ! -x "$ADB_BIN" ]]; then
    return
  fi
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell am force-stop "$TEST_PACKAGE_NAME" >/dev/null 2>&1 || true
}

clear_app_data_after_tests() {
  if [[ "$RESET_APP_DATA_AFTER_TESTS" != "1" || -z "${SELECTED_SERIAL:-}" || ! -x "$ADB_BIN" ]]; then
    return
  fi
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell pm clear "$PACKAGE_NAME" >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell pm clear "$TEST_PACKAGE_NAME" >/dev/null 2>&1 || true
}

cleanup_test_device_state() {
  stop_test_processes
  clear_app_data_after_tests
  if [[ "$CLEAN_DEVICE" == "1" && -n "${SELECTED_SERIAL:-}" && -x "$ADB_BIN" ]]; then
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

fail_with_reason() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  echo "$*" >&2
  capture_logcat_artifact
  cleanup_test_device_state
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

pocketmind_accessibility_enabled() {
  local dump bound_section enabled_line
  dump="$("${ADB[@]}" shell dumpsys accessibility 2>/dev/null | tr -d '\r')"
  bound_section="$(awk '
    /Bound services:/ {printing = 1}
    printing {print}
    /Enabled services:/ {printing = 0}
  ' <<<"$dump")"
  enabled_line="$(grep -m 1 'Enabled services:' <<<"$dump" || true)"
  grep -Fq "$POCKETMIND_ACCESSIBILITY_SERVICE" <<<"${bound_section}${enabled_line}"
}

require_pocketmind_accessibility_if_needed() {
  if [[ "$REQUIRE_POCKETMIND_ACCESSIBILITY" != "1" ]]; then
    return 0
  fi
  if pocketmind_accessibility_enabled; then
    return 0
  fi
  cat >&2 <<EOF
PocketMind Accessibility service is not enabled.
Open system Accessibility settings, enable PocketMind, then rerun with SKIP_INSTALL=1
or DEVICE_CONTROL_SKIP_INSTALL=1 so the debug APK is not reinstalled.
Expected service: $POCKETMIND_ACCESSIBILITY_SERVICE
EOF
  return 1
}

run_device_tests() {
  if [[ "$SKIP_INSTALL" != "1" ]]; then
    "${ADB[@]}" install -r "$DEBUG_APK" || return
    "${ADB[@]}" install -r -t "$ANDROID_TEST_APK" || return
  fi
  require_pocketmind_accessibility_if_needed || return
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
        kill -INT "$command_pid" >/dev/null 2>&1 || true
        sleep 2
        kill -TERM "$command_pid" >/dev/null 2>&1 || true
        sleep 2
        kill -KILL "$command_pid" >/dev/null 2>&1 || true
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
    kill "$watchdog_pid" >/dev/null 2>&1 || true
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
  grep -qE '^(FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2|INSTRUMENTATION_RESULT: shortMsg=|INSTRUMENTATION_STATUS: stack=|Error in )' <<<"$1"
}

instrumentation_output_succeeded() {
  sed 's/\r$//' <<<"$1" | grep -qE '^OK( \([0-9]+ tests?\))?$'
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

INSTRUMENTATION_STATUS="running"
set +e
TEST_OUTPUT="$(run_with_timeout_capture "$INSTRUMENTATION_TIMEOUT_SECONDS" run_device_tests)"
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
  if grep -q "PocketMind Accessibility service is not enabled" <<<"$TEST_OUTPUT"; then
    FAILED_TARGET="accessibility-permission"
    FAILURE_REASON="pocketmind-accessibility-not-enabled"
  fi
  if [[ -z "$FAILED_TARGET" ]]; then
    FAILED_TARGET="instrumentation"
  fi
  if [[ -z "$FAILURE_REASON" ]]; then
    FAILURE_REASON="instrumentation-command-failed"
  fi
  exit "$TEST_STATUS"
fi
INSTRUMENTATION_STATUS="passed"

clear_app_data_after_tests
"${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" >/dev/null
capture_logcat_artifact

SCRIPT_COMPLETED=1
echo "Device install and smoke test passed. App remains installed."
