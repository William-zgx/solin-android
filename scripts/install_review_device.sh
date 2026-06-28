#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
ADB_BIN="${ANDROID_SDK}/platform-tools/adb"

PACKAGE_NAME="com.bytedance.zgx.solin"
MAIN_ACTIVITY="${PACKAGE_NAME}/.MainActivity"
DEBUG_CONFIG_RECEIVER="${PACKAGE_NAME}/.debug.DebugRemoteConfigReceiver"

REVIEW_TARGET="${SOLIN_REVIEW_TARGET:-device}"
APK_MODE="${SOLIN_REVIEW_APK_MODE:-debug}"
APK_PATH_OVERRIDE="${SOLIN_REVIEW_APK_PATH:-}"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"

REMOTE_BASE_URL="${SOLIN_REVIEW_REMOTE_BASE_URL:-}"
REMOTE_MODEL="${SOLIN_REVIEW_REMOTE_MODEL:-}"
REMOTE_API_KEY="${SOLIN_REVIEW_REMOTE_API_KEY:-}"

CLEAN_DEVICE="${CLEAN_DEVICE:-0}"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/manual-acceptance-install-${REVIEW_TARGET}-$(date +%Y%m%d-%H%M%S)}"
REPORT_FILE="${REPORT_FILE:-${ARTIFACT_DIR}/manual-acceptance-install-${REVIEW_TARGET}.properties}"
INSTALL_OUTPUT_FILE="${INSTALL_OUTPUT_FILE:-${ARTIFACT_DIR}/install.txt}"
START_OUTPUT_FILE="${START_OUTPUT_FILE:-${ARTIFACT_DIR}/start.txt}"
STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

SELECTED_SERIAL=""
FAILED_TARGET=""
FAILURE_REASON=""
APK_PATH=""
APK_SHA256=""
API_LEVEL=""
ABI_LIST=""
FIRST_INSTALL_TIME=""
LAST_UPDATE_TIME=""
VERSION_CODE=""
VERSION_NAME=""
REMOTE_CONFIG_STATUS="not-requested"
REMOTE_CONFIG_SOURCE=""

write_report() {
  local exit_code="$1"
  local status_label="failed"
  [[ "$exit_code" -eq 0 ]] && status_label="passed"

  mkdir -p "$(dirname "$REPORT_FILE")"
  {
    echo "status=$status_label"
    echo "exit_code=$exit_code"
    echo "target=manual-acceptance-install"
    echo "manualAcceptanceInstall=true"
    echo "regressionEvidence=false"
    echo "failedTarget=${FAILED_TARGET:-}"
    echo "reason=${FAILURE_REASON:-}"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=${SELECTED_SERIAL:-}"
    echo "api_level=${API_LEVEL:-}"
    echo "abi=${ABI_LIST:-}"
    echo "apk_mode=$APK_MODE"
    echo "apk_path=${APK_PATH:-}"
    echo "apk_sha256=${APK_SHA256:-}"
    echo "install_output_file=$INSTALL_OUTPUT_FILE"
    echo "start_output_file=$START_OUTPUT_FILE"
    echo "clean_device=$CLEAN_DEVICE"
    echo "kept_app_data=1"
    echo "instrumentation=not-run"
    echo "remote_config=$REMOTE_CONFIG_STATUS"
    echo "remote_config_source=${REMOTE_CONFIG_SOURCE:-}"
    echo "firstInstallTime=${FIRST_INSTALL_TIME:-}"
    echo "lastUpdateTime=${LAST_UPDATE_TIME:-}"
    echo "versionCode=${VERSION_CODE:-}"
    echo "versionName=${VERSION_NAME:-}"
  } > "$REPORT_FILE"
  echo "Review install report: $REPORT_FILE"
}

on_exit() {
  local code="$?"
  trap - EXIT
  if [[ "$code" -ne 0 && -z "$FAILED_TARGET" ]]; then
    FAILED_TARGET="script"
    FAILURE_REASON="script-failed"
  fi
  write_report "$code"
  exit "$code"
}
trap on_exit EXIT

fail() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  echo "install_review_device: $*" >&2
  exit 1
}

has_partial_remote_config() {
  [[ -n "$REMOTE_BASE_URL" || -n "$REMOTE_MODEL" || -n "$REMOTE_API_KEY" ]] &&
    [[ -z "$REMOTE_BASE_URL" || -z "$REMOTE_MODEL" || -z "$REMOTE_API_KEY" ]]
}

remote_config_requested() {
  [[ -n "$REMOTE_BASE_URL" && -n "$REMOTE_MODEL" && -n "$REMOTE_API_KEY" ]]
}

shell_single_quote() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

debug_receiver_configure_remote() {
  local base_url_quoted model_quoted api_key_quoted
  base_url_quoted="$(shell_single_quote "$REMOTE_BASE_URL")"
  model_quoted="$(shell_single_quote "$REMOTE_MODEL")"
  api_key_quoted="$(shell_single_quote "$REMOTE_API_KEY")"
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" sh -s <<EOF
base_url=$base_url_quoted
model_name=$model_quoted
api_key=$api_key_quoted
am broadcast --user 0 -n "$DEBUG_CONFIG_RECEIVER" --es baseUrl "\$base_url" --es modelName "\$model_name" --es apiKey "\$api_key" --ez clearState true
EOF
}

select_target() {
  case "$REVIEW_TARGET" in
    device|emulator)
      ;;
    *)
      fail target invalid-target "SOLIN_REVIEW_TARGET must be device or emulator."
      ;;
  esac

  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    local selected_state
    selected_state="$("$ADB_BIN" devices | awk -v serial="$ANDROID_SERIAL" '$1 == serial {print $2; found = 1} END {if (!found) print ""}')"
    [[ "$selected_state" == "device" ]] ||
      fail "$REVIEW_TARGET-selection" "selected-$REVIEW_TARGET-unavailable" \
        "ANDROID_SERIAL=$ANDROID_SERIAL is not an authorized $REVIEW_TARGET; state is ${selected_state:-missing}."
    if [[ "$REVIEW_TARGET" == "device" && "$ANDROID_SERIAL" == emulator-* ]]; then
      fail device-selection android-serial-is-emulator "ANDROID_SERIAL=$ANDROID_SERIAL is an emulator serial."
    fi
    if [[ "$REVIEW_TARGET" == "emulator" && "$ANDROID_SERIAL" != emulator-* ]]; then
      fail emulator-selection android-serial-not-emulator "ANDROID_SERIAL=$ANDROID_SERIAL is not an emulator serial."
    fi
    SELECTED_SERIAL="$ANDROID_SERIAL"
    return
  fi

  local serials=()
  if [[ "$REVIEW_TARGET" == "device" ]]; then
    while IFS= read -r serial; do
      [[ -n "$serial" ]] && serials+=("$serial")
    done < <("$ADB_BIN" devices | awk 'NR > 1 && $1 !~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}')
  else
    while IFS= read -r serial; do
      [[ -n "$serial" ]] && serials+=("$serial")
    done < <("$ADB_BIN" devices | awk 'NR > 1 && $1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}')
  fi

  [[ "${#serials[@]}" -eq 1 ]] ||
    fail "$REVIEW_TARGET-selection" "no-single-authorized-$REVIEW_TARGET" \
      "Connect exactly one authorized $REVIEW_TARGET or set ANDROID_SERIAL."
  SELECTED_SERIAL="${serials[0]}"
}

prepare_apk() {
  case "$APK_MODE" in
    debug)
      if [[ -n "$APK_PATH_OVERRIDE" ]]; then
        APK_PATH="$APK_PATH_OVERRIDE"
      else
        "$GRADLE_CMD" :app:assembleDebug
        APK_PATH="$DEBUG_APK"
      fi
      ;;
    release)
      [[ -n "$APK_PATH_OVERRIDE" ]] ||
        fail apk signed-release-apk-required \
          "Set SOLIN_REVIEW_APK_PATH to an already signed release APK for formal-package review installs."
      if remote_config_requested; then
        fail remote-config release-remote-config-injection-unsupported \
          "Remote config injection is only available for debug review APKs."
      fi
      APK_PATH="$APK_PATH_OVERRIDE"
      ;;
    *)
      fail apk invalid-apk-mode "SOLIN_REVIEW_APK_MODE must be debug or release."
      ;;
  esac

  [[ -f "$APK_PATH" ]] || fail apk apk-missing "APK not found at $APK_PATH."
  APK_SHA256="$(shasum -a 256 "$APK_PATH" | awk '{print $1}')"
}

collect_package_info() {
  local package_info version_line
  package_info="$("${ADB[@]}" shell dumpsys package "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' || true)"
  FIRST_INSTALL_TIME="$(awk -F= '/firstInstallTime=/ {print $2; exit}' <<<"$package_info")"
  LAST_UPDATE_TIME="$(awk -F= '/lastUpdateTime=/ {print $2; exit}' <<<"$package_info")"
  version_line="$(awk '/versionCode=/ {print; exit}' <<<"$package_info")"
  VERSION_CODE="$(sed -nE 's/.*versionCode=([^ ]+).*/\1/p' <<<"$version_line")"
  VERSION_NAME="$(sed -nE 's/.*versionName=([^ ]+).*/\1/p' <<<"$package_info" | head -n 1)"
}

configure_remote_if_requested() {
  if has_partial_remote_config; then
    fail remote-config incomplete-remote-config \
      "Set SOLIN_REVIEW_REMOTE_BASE_URL, SOLIN_REVIEW_REMOTE_MODEL, and SOLIN_REVIEW_REMOTE_API_KEY together."
  fi
  if ! remote_config_requested; then
    return
  fi
  [[ "$APK_MODE" == "debug" ]] ||
    fail remote-config release-remote-config-injection-unsupported \
      "Remote config injection is only available for debug review APKs."

  REMOTE_CONFIG_STATUS="requested"
  REMOTE_CONFIG_SOURCE="SOLIN_REVIEW_REMOTE_BASE_URL,SOLIN_REVIEW_REMOTE_MODEL,SOLIN_REVIEW_REMOTE_API_KEY"
  if ! debug_receiver_configure_remote >/dev/null; then
    fail remote-config remote-config-broadcast-failed "Debug remote config broadcast failed."
  fi
  REMOTE_CONFIG_STATUS="saved"
}

[[ -x "$ADB_BIN" ]] || fail adb adb-missing "adb not found at $ADB_BIN."
if ! scripts/doctor.sh --device; then
  fail doctor doctor-device-failed "Android review install environment check failed."
fi

select_target
ADB=("$ADB_BIN" -s "$SELECTED_SERIAL")
echo "Using Android $REVIEW_TARGET: $SELECTED_SERIAL"

API_LEVEL="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
ABI_LIST="$("${ADB[@]}" shell getprop ro.product.cpu.abilist64 | tr -d '\r')"
prepare_apk

if [[ "$CLEAN_DEVICE" == "1" ]]; then
  "${ADB[@]}" uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true
fi

mkdir -p "$ARTIFACT_DIR"
if ! "${ADB[@]}" install -r "$APK_PATH" >"$INSTALL_OUTPUT_FILE" 2>&1; then
  cat "$INSTALL_OUTPUT_FILE" >&2
  fail install apk-install-failed "APK install failed."
fi
configure_remote_if_requested
if ! "${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" >"$START_OUTPUT_FILE" 2>&1; then
  cat "$START_OUTPUT_FILE" >&2
  fail app-launch app-launch-failed "MainActivity launch failed."
fi
collect_package_info

echo "Review install passed. App data was preserved unless CLEAN_DEVICE=1 was set."
