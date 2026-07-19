#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
ADB_BIN="${ANDROID_SDK}/platform-tools/adb"
BUILD_TOOLS_DIR="${BUILD_TOOLS_DIR:-$({ find "$ANDROID_SDK/build-tools" -maxdepth 1 -type d 2>/dev/null || true; } | sort | tail -n 1)}"
APKSIGNER="${APKSIGNER:-$BUILD_TOOLS_DIR/apksigner}"
AAPT="${AAPT:-$BUILD_TOOLS_DIR/aapt}"
CLEAN_DEVICE="${CLEAN_DEVICE:-0}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SKIP_INSTALL="${SKIP_INSTALL:-0}"
REQUIRE_SOLIN_ACCESSIBILITY="${REQUIRE_SOLIN_ACCESSIBILITY:-0}"
RESET_APP_DATA_AFTER_TESTS="${RESET_APP_DATA_AFTER_TESTS:-0}"
KEEP_DEVICE_AWAKE_FOR_TESTS="${KEEP_DEVICE_AWAKE_FOR_TESTS:-1}"
DEVICE_TEST_SCREEN_OFF_TIMEOUT_MILLIS="${DEVICE_TEST_SCREEN_OFF_TIMEOUT_MILLIS:-1800000}"
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
RELEASE_MAIN_SHELL_TIMEOUT_SECONDS="${RELEASE_MAIN_SHELL_TIMEOUT_SECONDS:-45}"
RELEASE_ARTIFACT_SHA256="${RELEASE_ARTIFACT_SHA256:-}"
RELEASE_ARTIFACT_TYPE="${RELEASE_ARTIFACT_TYPE:-}"
EXPECTED_SIGNING_CERT_SHA256="${EXPECTED_SIGNING_CERT_SHA256:-}"
APP_APK_MODE="${APP_APK_MODE:-debug}"
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
INSTRUMENTATION_EXECUTED_TEST_COUNT=""
INSTRUMENTATION_SKIPPED_TEST_COUNT=""
INSTRUMENTATION_SKIPPED_TESTS=""
LOGCAT_CAPTURED=0
RELEASE_MAIN_SHELL_VERIFIED=0
MIUI_BACKGROUND_ACTIVITY_STARTS_PREPARED=0
MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORE_TARGET=""
MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORE_TEST=""
MIUI_BACKGROUND_ACTIVITY_STARTS_RESTORED=0
DEVICE_AWAKE_PREPARED=0
DEVICE_AWAKE_RESTORED=0
DEVICE_STAY_ON_WHILE_PLUGGED_IN_RESTORE=""
DEVICE_SCREEN_OFF_TIMEOUT_RESTORE=""
DEVICE_SCREEN_OFF_TIMEOUT_PREPARED=0
RUNTIME_PERMISSION_DIALOG_TESTS_PREPARED=0
RUNTIME_PERMISSION_DIALOG_TESTS_RESTORED=0
READ_CONTACTS_PERMISSION_RESTORE_STATE=""
RECORD_AUDIO_PERMISSION_RESTORE_STATE=""
FAILED_TARGET=""
FAILURE_REASON=""
SCRIPT_COMPLETED=0
REPORT_WRITTEN=0

PACKAGE_NAME="com.bytedance.zgx.solin"
DEBUG_TEST_PACKAGE_NAME="${PACKAGE_NAME}.test"
RELEASE_TEST_PACKAGE_NAME="${PACKAGE_NAME}.releasesmoke"
TEST_PACKAGE_NAME="$DEBUG_TEST_PACKAGE_NAME"
MAIN_ACTIVITY="${PACKAGE_NAME}/.MainActivity"
TEST_RUNNER="${TEST_PACKAGE_NAME}/androidx.test.runner.AndroidJUnitRunner"
RELEASE_TEST_RUNNER="${RELEASE_TEST_PACKAGE_NAME}/${RELEASE_TEST_PACKAGE_NAME}.ReleaseSmokeInstrumentation"
RELEASE_SMOKE_TEST_CLASS="${PACKAGE_NAME}.ReleaseSignedSmokeDeviceTest"
SOLIN_ACCESSIBILITY_SERVICE="${PACKAGE_NAME}/${PACKAGE_NAME}.device.SolinAccessibilityService"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
DEFAULT_RELEASE_APK="app/build/outputs/apk/release/app-release-signed.apk"
DEFAULT_DEBUG_ANDROID_TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
DEFAULT_RELEASE_ANDROID_TEST_APK="releaseSmoke/build/outputs/apk/release/releaseSmoke-release-signed.apk"
APP_APK="${APP_APK:-}"
ANDROID_TEST_APK="${ANDROID_TEST_APK:-}"
REQUIRED_FREE_KB=$((3 * 1024 * 1024))
APP_APK_SHA256=""
ANDROID_TEST_APK_SHA256=""
APP_SIGNER_SHA256=""
ANDROID_TEST_SIGNER_SHA256=""
APP_APK_VERSION_CODE=""
APP_APK_VERSION_NAME=""
INSTALLED_APP_APK_PATH=""
INSTALLED_TEST_APK_PATH=""
INSTALLED_APP_APK_SHA256=""
INSTALLED_TEST_APK_SHA256=""
INSTALLED_APP_SIGNER_SHA256=""
INSTALLED_TEST_SIGNER_SHA256=""
INSTALLED_APP_VERSION_CODE=""
INSTALLED_APP_VERSION_NAME=""
RELEASE_MAIN_SHELL_START_OUTPUT_FILE="${ARTIFACT_DIR}/release-main-shell-start.txt"
RELEASE_MAIN_SHELL_ACTIVITY_DUMP_FILE="${ARTIFACT_DIR}/release-main-shell-activity.txt"
RELEASE_MAIN_SHELL_WINDOW_DUMP_FILE="${ARTIFACT_DIR}/release-main-shell-window.txt"
RELEASE_MAIN_SHELL_UI_DUMP_FILE="${ARTIFACT_DIR}/release-main-shell.xml"
RELEASE_MAIN_SHELL_SCREENSHOT_FILE="${ARTIFACT_DIR}/release-main-shell.png"
RELEASE_MAIN_SHELL_UI_DUMP_REMOTE_PATH="/sdcard/solin-release-main-shell.xml"

if [[ -z "$APP_APK" ]]; then
  case "$APP_APK_MODE" in
    debug)
      APP_APK="$DEBUG_APK"
      ;;
    release)
      APP_APK="$DEFAULT_RELEASE_APK"
      ;;
    *)
      APP_APK="$DEBUG_APK"
      ;;
  esac
fi
if [[ -z "$ANDROID_TEST_APK" ]]; then
  case "$APP_APK_MODE" in
    release)
      ANDROID_TEST_APK="$DEFAULT_RELEASE_ANDROID_TEST_APK"
      ;;
    *)
      ANDROID_TEST_APK="$DEFAULT_DEBUG_ANDROID_TEST_APK"
      ;;
  esac
fi
if [[ "$APP_APK_MODE" == "release" && -z "$INSTRUMENTATION_CLASS" ]]; then
  INSTRUMENTATION_CLASS="$RELEASE_SMOKE_TEST_CLASS"
fi
if [[ "$APP_APK_MODE" == "release" ]]; then
  TEST_PACKAGE_NAME="$RELEASE_TEST_PACKAGE_NAME"
  TEST_RUNNER="$RELEASE_TEST_RUNNER"
fi

sha256_file() {
  local file="$1"
  if [[ -f "$file" ]]; then
    shasum -a 256 "$file" | awk '{print $1}'
  fi
}

normalize_sha256() {
  tr '[:upper:]' '[:lower:]' <<<"$1"
}

apk_signer_sha256() {
  local apk="$1"
  local output
  [[ -f "$apk" && -x "$APKSIGNER" ]] || return 0
  output="$("$APKSIGNER" verify --print-certs "$apk" 2>/dev/null || true)"
  printf '%s\n' "$output" |
    awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {
      value = tolower($2)
      gsub(/[^0-9a-f]/, "", value)
      print value
      exit
    }'
}

apk_badging_value() {
  local apk="$1"
  local field="$2"
  [[ -f "$apk" && -x "$AAPT" ]] || return 0
  "$AAPT" dump badging "$apk" 2>/dev/null |
    sed -nE "s/^package: .*${field}='([^']*)'.*/\1/p" |
    head -n 1
}

installed_package_value() {
  local package_name="$1"
  local field="$2"
  "${ADB[@]}" shell dumpsys package "$package_name" 2>/dev/null |
    tr -d '\r' |
    awk -v field="$field" '
      index($1, field "=") == 1 {
        value = substr($1, length(field) + 2)
        print value
        exit
      }
    '
}

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
    echo "device_test_screen_off_timeout_millis=$DEVICE_TEST_SCREEN_OFF_TIMEOUT_MILLIS"
    echo "device_awake_prepared=$DEVICE_AWAKE_PREPARED"
    echo "device_awake_restored=$DEVICE_AWAKE_RESTORED"
    echo "device_screen_off_timeout_prepared=$DEVICE_SCREEN_OFF_TIMEOUT_PREPARED"
    echo "device_screen_off_timeout_restore=$DEVICE_SCREEN_OFF_TIMEOUT_RESTORE"
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
    echo "instrumentation_executed_test_count=${INSTRUMENTATION_EXECUTED_TEST_COUNT:-}"
    echo "instrumentation_skipped_test_count=${INSTRUMENTATION_SKIPPED_TEST_COUNT:-}"
    echo "instrumentation_skipped_tests=${INSTRUMENTATION_SKIPPED_TESTS:-}"
    echo "instrumentation_class=${INSTRUMENTATION_CLASS:-}"
    echo "instrumentation_runner=$TEST_RUNNER"
    echo "instrumentation_timeout_seconds=$INSTRUMENTATION_TIMEOUT_SECONDS"
    echo "instrumentation_output_file=$INSTRUMENTATION_OUTPUT_FILE"
    echo "instrumentation_output_sha256=$instrumentation_output_sha256"
    echo "test_count=${INSTRUMENTATION_TEST_COUNT:-}"
    echo "release_main_shell_verified=$RELEASE_MAIN_SHELL_VERIFIED"
    echo "release_main_shell_start_output_file=$RELEASE_MAIN_SHELL_START_OUTPUT_FILE"
    echo "release_main_shell_start_output_sha256=$(sha256_file "$RELEASE_MAIN_SHELL_START_OUTPUT_FILE")"
    echo "release_main_shell_activity_dump_file=$RELEASE_MAIN_SHELL_ACTIVITY_DUMP_FILE"
    echo "release_main_shell_activity_dump_sha256=$(sha256_file "$RELEASE_MAIN_SHELL_ACTIVITY_DUMP_FILE")"
    echo "release_main_shell_window_dump_file=$RELEASE_MAIN_SHELL_WINDOW_DUMP_FILE"
    echo "release_main_shell_window_dump_sha256=$(sha256_file "$RELEASE_MAIN_SHELL_WINDOW_DUMP_FILE")"
    echo "release_main_shell_ui_dump_file=$RELEASE_MAIN_SHELL_UI_DUMP_FILE"
    echo "release_main_shell_ui_dump_sha256=$(sha256_file "$RELEASE_MAIN_SHELL_UI_DUMP_FILE")"
    echo "release_main_shell_screenshot_file=$RELEASE_MAIN_SHELL_SCREENSHOT_FILE"
    echo "release_main_shell_screenshot_sha256=$(sha256_file "$RELEASE_MAIN_SHELL_SCREENSHOT_FILE")"
    echo "app_apk_mode=$APP_APK_MODE"
    echo "app_apk=$APP_APK"
    echo "app_apk_sha256=$APP_APK_SHA256"
    echo "android_test_apk=$ANDROID_TEST_APK"
    echo "android_test_apk_sha256=$ANDROID_TEST_APK_SHA256"
    echo "app_signer_sha256=$APP_SIGNER_SHA256"
    echo "android_test_signer_sha256=$ANDROID_TEST_SIGNER_SHA256"
    echo "app_apk_version_code=$APP_APK_VERSION_CODE"
    echo "app_apk_version_name=$APP_APK_VERSION_NAME"
    echo "app_android_test_signers_match=$([[ -n "$APP_SIGNER_SHA256" && "$APP_SIGNER_SHA256" == "$ANDROID_TEST_SIGNER_SHA256" ]] && echo true || echo false)"
    echo "expected_signing_cert_sha256=$EXPECTED_SIGNING_CERT_SHA256"
    echo "installed_app_apk_path=$INSTALLED_APP_APK_PATH"
    echo "installed_test_apk_path=$INSTALLED_TEST_APK_PATH"
    echo "installed_app_apk_sha256=$INSTALLED_APP_APK_SHA256"
    echo "installed_test_apk_sha256=$INSTALLED_TEST_APK_SHA256"
    echo "installed_app_signer_sha256=$INSTALLED_APP_SIGNER_SHA256"
    echo "installed_test_signer_sha256=$INSTALLED_TEST_SIGNER_SHA256"
    echo "installed_app_version_code=$INSTALLED_APP_VERSION_CODE"
    echo "installed_app_version_name=$INSTALLED_APP_VERSION_NAME"
    echo "installed_app_matches_requested_apk=$([[ -n "$INSTALLED_APP_APK_SHA256" && "$INSTALLED_APP_APK_SHA256" == "$APP_APK_SHA256" ]] && echo true || echo false)"
    echo "installed_test_matches_requested_apk=$([[ -n "$INSTALLED_TEST_APK_SHA256" && "$INSTALLED_TEST_APK_SHA256" == "$ANDROID_TEST_APK_SHA256" ]] && echo true || echo false)"
    echo "releaseArtifactType=$RELEASE_ARTIFACT_TYPE"
    echo "releaseArtifactSha256=$RELEASE_ARTIFACT_SHA256"
    echo "logcat_file=$LOGCAT_FILE"
    echo "logcat_sha256=$logcat_sha256"
    echo "logcat_captured=$LOGCAT_CAPTURED"
    echo "logcat_tail_lines=$LOGCAT_TAIL_LINES"
    echo "debug_apk=$DEBUG_APK"
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

capture_release_main_shell_artifacts() {
  if [[ "$APP_APK_MODE" != "release" ]] || ! selected_device_is_available; then
    return
  fi
  mkdir -p "$ARTIFACT_DIR"
  "${ADB[@]}" shell dumpsys activity activities >"$RELEASE_MAIN_SHELL_ACTIVITY_DUMP_FILE" 2>/dev/null || true
  "${ADB[@]}" shell dumpsys window windows >"$RELEASE_MAIN_SHELL_WINDOW_DUMP_FILE" 2>/dev/null || true
  "${ADB[@]}" shell uiautomator dump "$RELEASE_MAIN_SHELL_UI_DUMP_REMOTE_PATH" >/dev/null 2>&1 || true
  "${ADB[@]}" exec-out cat "$RELEASE_MAIN_SHELL_UI_DUMP_REMOTE_PATH" \
    >"$RELEASE_MAIN_SHELL_UI_DUMP_FILE" 2>/dev/null || true
  "${ADB[@]}" exec-out screencap -p >"$RELEASE_MAIN_SHELL_SCREENSHOT_FILE" 2>/dev/null || true
}

release_main_shell_window_is_drawn() {
  awk -v target="$PACKAGE_NAME/$PACKAGE_NAME.MainActivity" '
    /^  Window #[0-9]+ Window\{/ {
      in_target = index($0, target) != 0
    }
    in_target && /shown=true.*mDrawState=HAS_DRAWN/ {
      drawn = 1
    }
    in_target && /isOnScreen=true/ {
      on_screen = 1
    }
    in_target && /isVisible=true/ {
      visible = 1
    }
    END {
      exit !(drawn && on_screen && visible)
    }
  ' "$RELEASE_MAIN_SHELL_WINDOW_DUMP_FILE"
}

release_main_shell_is_visible() {
  [[ -s "$RELEASE_MAIN_SHELL_START_OUTPUT_FILE" ]] ||
    return 1
  [[ -s "$RELEASE_MAIN_SHELL_ACTIVITY_DUMP_FILE" ]] ||
    return 1
  [[ -s "$RELEASE_MAIN_SHELL_WINDOW_DUMP_FILE" ]] ||
    return 1
  [[ -s "$RELEASE_MAIN_SHELL_UI_DUMP_FILE" ]] ||
    return 1
  [[ -s "$RELEASE_MAIN_SHELL_SCREENSHOT_FILE" ]] ||
    return 1
  grep -Fq "topResumedActivity=" "$RELEASE_MAIN_SHELL_ACTIVITY_DUMP_FILE" &&
    grep -Fq "$PACKAGE_NAME/.MainActivity" "$RELEASE_MAIN_SHELL_ACTIVITY_DUMP_FILE" &&
    grep -Eq 'm(CurrentFocus|FocusedWindow)=Window\{' "$RELEASE_MAIN_SHELL_ACTIVITY_DUMP_FILE" &&
    grep -Fq "$PACKAGE_NAME/$PACKAGE_NAME.MainActivity" "$RELEASE_MAIN_SHELL_ACTIVITY_DUMP_FILE" &&
    grep -Fq "$PACKAGE_NAME/$PACKAGE_NAME.MainActivity" "$RELEASE_MAIN_SHELL_WINDOW_DUMP_FILE" &&
    release_main_shell_window_is_drawn &&
    grep -Fq 'text="Solin"' "$RELEASE_MAIN_SHELL_UI_DUMP_FILE"
}

verify_release_main_shell() {
  local deadline launch_output
  if [[ "$APP_APK_MODE" != "release" ]]; then
    return 0
  fi
  mkdir -p "$ARTIFACT_DIR"
  if ! launch_output="$("${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" 2>&1)"; then
    printf '%s\n' "$launch_output" >"$RELEASE_MAIN_SHELL_START_OUTPUT_FILE"
    return 1
  fi
  printf '%s\n' "$launch_output" >"$RELEASE_MAIN_SHELL_START_OUTPUT_FILE"
  grep -q '^Status: ok' "$RELEASE_MAIN_SHELL_START_OUTPUT_FILE" ||
    return 1
  deadline=$((SECONDS + RELEASE_MAIN_SHELL_TIMEOUT_SECONDS))
  while true; do
    capture_release_main_shell_artifacts
    if release_main_shell_is_visible; then
      RELEASE_MAIN_SHELL_VERIFIED=1
      return 0
    fi
    [[ "$SECONDS" -lt "$deadline" ]] ||
      return 1
    sleep 1
  done
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
  if [[ "$DEVICE_AWAKE_PREPARED" == "1" ]]; then
    return
  fi

  DEVICE_STAY_ON_WHILE_PLUGGED_IN_RESTORE="$(
    "$ADB_BIN" -s "$SELECTED_SERIAL" shell settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r'
  )"
  DEVICE_SCREEN_OFF_TIMEOUT_RESTORE="$(
    "$ADB_BIN" -s "$SELECTED_SERIAL" shell settings get system screen_off_timeout 2>/dev/null | tr -d '\r'
  )"
  if [[ "$DEVICE_TEST_SCREEN_OFF_TIMEOUT_MILLIS" =~ ^[0-9]+$ &&
    "$DEVICE_TEST_SCREEN_OFF_TIMEOUT_MILLIS" -gt 0 ]]; then
    "$ADB_BIN" -s "$SELECTED_SERIAL" shell settings put system screen_off_timeout \
      "$DEVICE_TEST_SCREEN_OFF_TIMEOUT_MILLIS" >/dev/null 2>&1 && DEVICE_SCREEN_OFF_TIMEOUT_PREPARED=1
  fi
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell cmd power suppress-ambient-display solin-device-tests true >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell cmd power wakeup 0 >/dev/null 2>&1 || true
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
  if [[ "$DEVICE_SCREEN_OFF_TIMEOUT_PREPARED" == "1" ]]; then
    if [[ -z "$DEVICE_SCREEN_OFF_TIMEOUT_RESTORE" || "$DEVICE_SCREEN_OFF_TIMEOUT_RESTORE" == "null" ]]; then
      "$ADB_BIN" -s "$SELECTED_SERIAL" shell settings delete system screen_off_timeout >/dev/null 2>&1 || true
    else
      "$ADB_BIN" -s "$SELECTED_SERIAL" shell settings put system screen_off_timeout \
        "$DEVICE_SCREEN_OFF_TIMEOUT_RESTORE" >/dev/null 2>&1 || true
    fi
  fi
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell cmd power suppress-ambient-display solin-device-tests false >/dev/null 2>&1 || true
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
  if [[ "$SELECTED_SERIAL" != emulator-* ]]; then
    fail_with_reason device-selection android-serial-required-for-physical-device \
      "Set ANDROID_SERIAL explicitly before running tests on a physical Android device."
  fi
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
if [[ "$SELECTED_SERIAL" != emulator-* && "$CLEAN_DEVICE" != "0" ]]; then
  fail_with_reason device-cleanup physical-device-clean-not-allowed \
    "CLEAN_DEVICE must be 0 for a physical Android device."
fi
if [[ "$SELECTED_SERIAL" != emulator-* && "$RESET_APP_DATA_AFTER_TESTS" != "0" ]]; then
  fail_with_reason device-cleanup physical-device-data-reset-not-allowed \
    "RESET_APP_DATA_AFTER_TESTS must be 0 for a physical Android device."
fi

case "$APP_APK_MODE" in
  debug|release)
    ;;
  *)
    fail_with_reason build invalid-app-apk-mode "APP_APK_MODE must be debug or release."
    ;;
esac
if [[ "$APP_APK_MODE" == "release" && "$SKIP_INSTALL" == "1" ]]; then
  fail_with_reason install release-skip-install-not-allowed \
    "APP_APK_MODE=release requires SKIP_INSTALL=0 so evidence is bound to the requested APKs."
fi

DATA_FREE_KB="$("${ADB[@]}" shell df -k /data | awk 'NR == 2 {print $4}' | tr -d '\r')"
if [[ "$DATA_FREE_KB" =~ ^[0-9]+$ && "$DATA_FREE_KB" -lt "$REQUIRED_FREE_KB" ]]; then
  fail_with_reason data-free-space data-free-below-threshold \
    "Connected device has less than 3 GB free on /data; model download/import may fail."
fi

if [[ "$CLEAN_DEVICE" == "1" ]]; then
  "${ADB[@]}" uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true
fi
prepare_device_awake_state
if [[ "$SKIP_BUILD" == "1" ]]; then
  if [[ "$SKIP_INSTALL" != "1" && ( ! -f "$APP_APK" || ! -f "$ANDROID_TEST_APK" ) ]]; then
    fail_with_reason build skipped-build-apk-missing \
      "SKIP_BUILD=1 requires existing APKs when SKIP_INSTALL is not set."
  fi
else
  case "$APP_APK_MODE" in
    debug)
      "$GRADLE_CMD" :app:assembleDebug :app:assembleDebugAndroidTest
      ;;
    release)
      "$GRADLE_CMD" :releaseSmoke:assembleRelease
      [[ -f "$APP_APK" ]] ||
        fail_with_reason build release-app-apk-missing \
          "APP_APK_MODE=release requires an existing signed release APK at APP_APK=$APP_APK."
      ;;
  esac
fi

APP_APK_SHA256="$(sha256_file "$APP_APK")"
ANDROID_TEST_APK_SHA256="$(sha256_file "$ANDROID_TEST_APK")"
if [[ "$APP_APK_MODE" == "release" ]]; then
  [[ -n "$APP_APK_SHA256" ]] ||
    fail_with_reason build release-app-apk-missing "Release APP_APK is missing: $APP_APK"
  [[ -n "$ANDROID_TEST_APK_SHA256" ]] ||
    fail_with_reason build release-android-test-apk-missing \
      "Release AndroidTest APK is missing: $ANDROID_TEST_APK"
  [[ -x "$APKSIGNER" ]] ||
    fail_with_reason signing apksigner-not-found "apksigner is required for release device validation: $APKSIGNER"
  [[ -x "$AAPT" ]] ||
    fail_with_reason build aapt-not-found "aapt is required for release device validation: $AAPT"
  APP_SIGNER_SHA256="$(apk_signer_sha256 "$APP_APK")"
  ANDROID_TEST_SIGNER_SHA256="$(apk_signer_sha256 "$ANDROID_TEST_APK")"
  APP_APK_VERSION_CODE="$(apk_badging_value "$APP_APK" versionCode)"
  APP_APK_VERSION_NAME="$(apk_badging_value "$APP_APK" versionName)"
  [[ "$APP_SIGNER_SHA256" =~ ^[0-9a-f]{64}$ ]] ||
    fail_with_reason signing release-app-signer-invalid "Could not read the release app signing certificate."
  [[ "$ANDROID_TEST_SIGNER_SHA256" =~ ^[0-9a-f]{64}$ ]] ||
    fail_with_reason signing release-android-test-signer-invalid \
      "Could not read the release AndroidTest signing certificate."
  [[ "$APP_SIGNER_SHA256" == "$ANDROID_TEST_SIGNER_SHA256" ]] ||
    fail_with_reason signing release-app-android-test-signer-mismatch \
      "Release app and AndroidTest APK must use the same signing certificate."
  [[ "$APP_APK_VERSION_CODE" =~ ^[0-9]+$ ]] ||
    fail_with_reason build release-app-version-code-invalid \
      "Could not read a numeric versionCode from the release APK."
  [[ -n "$APP_APK_VERSION_NAME" ]] ||
    fail_with_reason build release-app-version-name-missing \
      "Could not read versionName from the release APK."
  if [[ -n "$EXPECTED_SIGNING_CERT_SHA256" &&
    "$APP_SIGNER_SHA256" != "$(normalize_sha256 "$EXPECTED_SIGNING_CERT_SHA256" | tr -d ':')" ]]; then
    fail_with_reason signing release-signing-certificate-mismatch \
      "Release APK certificate does not match EXPECTED_SIGNING_CERT_SHA256."
  fi
  if [[ -z "$RELEASE_ARTIFACT_TYPE" ]]; then
    RELEASE_ARTIFACT_TYPE="apk"
  fi
  if [[ -z "$RELEASE_ARTIFACT_SHA256" ]]; then
    RELEASE_ARTIFACT_SHA256="$APP_APK_SHA256"
  elif [[ "$(normalize_sha256 "$RELEASE_ARTIFACT_SHA256")" != "$(normalize_sha256 "$APP_APK_SHA256")" ]]; then
    fail_with_reason release-artifact release-artifact-sha-not-installed-apk \
      "RELEASE_ARTIFACT_SHA256 must match the release APP_APK SHA-256 for physical-device validation."
  fi
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
Solin Accessibility service is not enabled.
Open system Accessibility settings, enable Solin, then rerun with SKIP_INSTALL=1
or DEVICE_CONTROL_SKIP_INSTALL=1 so the debug APK is not reinstalled.
Expected service: $SOLIN_ACCESSIBILITY_SERVICE
EOF
  return 1
}

install_device_apks() {
  if [[ "$SKIP_INSTALL" != "1" ]]; then
    "${ADB[@]}" install -r "$APP_APK" || return
    "${ADB[@]}" install -r -t "$ANDROID_TEST_APK" || return
  fi
}

verify_installed_release_apks() {
  local installed_dir
  if [[ "$APP_APK_MODE" != "release" ]]; then
    return 0
  fi
  installed_dir="$ARTIFACT_DIR/installed-apks"
  mkdir -p "$installed_dir"
  INSTALLED_APP_APK_PATH="$("${ADB[@]}" shell pm path "$PACKAGE_NAME" | tr -d '\r' | sed -n 's/^package://p' | head -n 1)"
  INSTALLED_TEST_APK_PATH="$("${ADB[@]}" shell pm path "$TEST_PACKAGE_NAME" | tr -d '\r' | sed -n 's/^package://p' | head -n 1)"
  [[ -n "$INSTALLED_APP_APK_PATH" && -n "$INSTALLED_TEST_APK_PATH" ]] ||
    return 1
  "${ADB[@]}" pull "$INSTALLED_APP_APK_PATH" "$installed_dir/app-installed.apk" >/dev/null || return 1
  "${ADB[@]}" pull "$INSTALLED_TEST_APK_PATH" "$installed_dir/test-installed.apk" >/dev/null || return 1
  INSTALLED_APP_APK_SHA256="$(sha256_file "$installed_dir/app-installed.apk")"
  INSTALLED_TEST_APK_SHA256="$(sha256_file "$installed_dir/test-installed.apk")"
  INSTALLED_APP_SIGNER_SHA256="$(apk_signer_sha256 "$installed_dir/app-installed.apk")"
  INSTALLED_TEST_SIGNER_SHA256="$(apk_signer_sha256 "$installed_dir/test-installed.apk")"
  INSTALLED_APP_VERSION_CODE="$(installed_package_value "$PACKAGE_NAME" versionCode)"
  INSTALLED_APP_VERSION_NAME="$(installed_package_value "$PACKAGE_NAME" versionName)"
  [[ "$INSTALLED_APP_APK_SHA256" == "$APP_APK_SHA256" ]] || return 1
  [[ "$INSTALLED_TEST_APK_SHA256" == "$ANDROID_TEST_APK_SHA256" ]] || return 1
  [[ "$INSTALLED_APP_SIGNER_SHA256" == "$APP_SIGNER_SHA256" ]] || return 1
  [[ "$INSTALLED_TEST_SIGNER_SHA256" == "$ANDROID_TEST_SIGNER_SHA256" ]] || return 1
  [[ "$INSTALLED_APP_VERSION_CODE" == "$APP_APK_VERSION_CODE" ]] || return 1
  [[ "$INSTALLED_APP_VERSION_NAME" == "$APP_APK_VERSION_NAME" ]] || return 1
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

instrumentation_skipped_tests_for() {
  local output="$1"
  awk '
    /^INSTRUMENTATION_STATUS: class=/ {
      test_class = $0
      sub(/^INSTRUMENTATION_STATUS: class=/, "", test_class)
    }
    /^INSTRUMENTATION_STATUS: test=/ {
      test_name = $0
      sub(/^INSTRUMENTATION_STATUS: test=/, "", test_name)
    }
    /^INSTRUMENTATION_STATUS_CODE: -[34]\r?$/ {
      if (test_class != "" && test_name != "") {
        key = test_class "#" test_name
        if (!seen[key]++) {
          if (result != "") {
            result = result "|"
          }
          result = result key
        }
      }
    }
    END {
      print result
    }
  ' <<<"$output"
}

instrumentation_skipped_test_count_for() {
  local skipped_tests="$1"
  if [[ -z "$skipped_tests" ]]; then
    echo 0
    return
  fi
  awk -F'|' '{print NF}' <<<"$skipped_tests"
}

if ! install_device_apks; then
  fail_with_reason install install-command-failed "Failed to install Solin app or androidTest APK."
fi
if ! verify_installed_release_apks; then
  fail_with_reason install installed-release-apk-binding-failed \
    "Installed release app/test APKs do not match the requested files and signing certificate."
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
INSTRUMENTATION_SKIPPED_TESTS="$(instrumentation_skipped_tests_for "$TEST_OUTPUT")"
INSTRUMENTATION_SKIPPED_TEST_COUNT="$(
  instrumentation_skipped_test_count_for "$INSTRUMENTATION_SKIPPED_TESTS"
)"
if [[ "$INSTRUMENTATION_TEST_COUNT" =~ ^[0-9]+$ &&
  "$INSTRUMENTATION_SKIPPED_TEST_COUNT" =~ ^[0-9]+$ &&
  "$INSTRUMENTATION_SKIPPED_TEST_COUNT" -le "$INSTRUMENTATION_TEST_COUNT" ]]; then
  INSTRUMENTATION_EXECUTED_TEST_COUNT="$(
    echo $((INSTRUMENTATION_TEST_COUNT - INSTRUMENTATION_SKIPPED_TEST_COUNT))
  )"
fi
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
if [[ "$TEST_STATUS" -eq 0 &&
  "$APP_APK_MODE" == "release" &&
  "$INSTRUMENTATION_SKIPPED_TEST_COUNT" != "0" ]]; then
  echo "Release instrumentation skipped test(s): $INSTRUMENTATION_SKIPPED_TESTS" >&2
  TEST_STATUS=1
  FAILED_TARGET="instrumentation"
  FAILURE_REASON="release-instrumentation-tests-skipped"
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
  if grep -q "Solin Accessibility service is not enabled" <<<"$TEST_OUTPUT"; then
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

if ! verify_release_main_shell; then
  FAILED_TARGET="release-main-shell"
  FAILURE_REASON="release-main-shell-not-visible"
  capture_logcat_artifact
  cleanup_test_device_state
  SCRIPT_COMPLETED=1
  write_verification_report 1
  exit 1
fi

clear_app_data_after_tests
restore_runtime_permission_dialog_tests
restore_miui_background_activity_starts
restore_device_awake_state
capture_logcat_artifact

SCRIPT_COMPLETED=1
echo "Device install and smoke test passed. App remains installed."
