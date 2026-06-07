#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"

ADB_BIN="${ANDROID_SDK}/platform-tools/adb"
EMULATOR_BIN="${ANDROID_EMULATOR:-${ANDROID_SDK}/emulator/emulator}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"

PACKAGE_NAME="com.bytedance.zgx.pocketmind"
MAIN_ACTIVITY="${PACKAGE_NAME}/.MainActivity"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"

BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-360}"
EMULATOR_SELECT_TIMEOUT_SECONDS="${EMULATOR_SELECT_TIMEOUT_SECONDS:-120}"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/fresh-start-main-shell-$(date +%Y%m%d-%H%M%S)}"
REPORT_FILE="${REPORT_FILE:-${ARTIFACT_DIR}/fresh-start-main-shell.properties}"
SCREENSHOT_FILE="${ARTIFACT_DIR}/fresh-start.png"
WINDOW_DUMP_FILE="${ARTIFACT_DIR}/fresh-start.xml"
MODEL_MANAGER_DUMP_FILE="${ARTIFACT_DIR}/model-manager.xml"
LOGCAT_FILE="${ARTIFACT_DIR}/logcat.txt"
EMULATOR_LOG="${ARTIFACT_DIR}/emulator.log"

MAIN_COPY_TEXT="${MAIN_COPY_TEXT:-隐私优先的随身 AI 助手}"
FORBIDDEN_FIRST_RUN_TEXT="${FORBIDDEN_FIRST_RUN_TEXT:-离线基础问答可选下载}"
FIRST_RUN_SKIP_LABEL="${FIRST_RUN_SKIP_LABEL:-先跳过}"
MODEL_MANAGER_BUTTON_LABEL="${MODEL_MANAGER_BUTTON_LABEL:-模型管理}"
MODEL_MANAGER_EXPECTED_TEXT="${MODEL_MANAGER_EXPECTED_TEXT:-本地离线可用；远程多模态可选。远程发送和设备动作仍会先确认。}"
SELECTED_SERIAL=""
API_LEVEL=""
ABI_LIST=""
AVD_LABEL=""
FAILED_TARGET=""
FAILURE_REASON=""
STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
STARTED_EMULATOR=0
FIRST_RUN_SETUP_VISIBLE=""
FIRST_RUN_SETUP_SKIPPED=""
MAIN_SHELL_COPY_VISIBLE=""
MODEL_MANAGER_CLICK_OPENED=""

write_report() {
  local exit_code="$1"
  local status_label="failed"
  [[ "$exit_code" -eq 0 ]] && status_label="passed"
  mkdir -p "$(dirname "$REPORT_FILE")"
  {
    echo "status=$status_label"
    echo "exit_code=$exit_code"
    echo "target=fresh-start-main-shell"
    echo "failedTarget=${FAILED_TARGET:-}"
    echo "reason=${FAILURE_REASON:-}"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=${SELECTED_SERIAL:-}"
    echo "api_level=${API_LEVEL:-}"
    echo "abi=${ABI_LIST:-}"
    echo "avd=${AVD_LABEL:-${AVD_NAME:-}}"
    echo "screenshot=$SCREENSHOT_FILE"
    echo "window_dump=$WINDOW_DUMP_FILE"
    echo "model_manager_window_dump=$MODEL_MANAGER_DUMP_FILE"
    echo "logcat_file=$LOGCAT_FILE"
    echo "emulator_log=$EMULATOR_LOG"
    echo "first_run_setup_visible=${FIRST_RUN_SETUP_VISIBLE:-}"
    echo "first_run_setup_skipped=${FIRST_RUN_SETUP_SKIPPED:-}"
    echo "main_shell_copy_visible=${MAIN_SHELL_COPY_VISIBLE:-}"
    echo "model_manager_click_opened=${MODEL_MANAGER_CLICK_OPENED:-}"
  } > "$REPORT_FILE"
  echo "Fresh start main shell report: $REPORT_FILE"
}

dump_ui() {
  local remote_path="$1"
  local local_path="$2"
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell uiautomator dump "$remote_path" >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" pull "$remote_path" "$local_path" >/dev/null 2>&1 || true
}

capture_artifacts() {
  if [[ -n "${SELECTED_SERIAL:-}" && -x "$ADB_BIN" ]]; then
    mkdir -p "$ARTIFACT_DIR"
    "$ADB_BIN" -s "$SELECTED_SERIAL" exec-out screencap -p > "$SCREENSHOT_FILE" 2>/dev/null || true
    dump_ui /sdcard/pocketmind-fresh-start.xml "$WINDOW_DUMP_FILE"
    "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -d -t 300 > "$LOGCAT_FILE" 2>/dev/null || true
  fi
}

on_exit() {
  local code="$?"
  trap - EXIT
  if [[ "$code" -ne 0 && -z "$FAILED_TARGET" ]]; then
    FAILED_TARGET="script"
    FAILURE_REASON="script-failed"
  fi
  if [[ "$code" -ne 0 ]]; then
    capture_artifacts
  fi
  write_report "$code"
  if [[ "$STARTED_EMULATOR" == "1" && -n "${SELECTED_SERIAL:-}" && "${KEEP_EMULATOR:-0}" != "1" ]]; then
    "$ADB_BIN" -s "$SELECTED_SERIAL" emu kill >/dev/null 2>&1 || true
  fi
  exit "$code"
}
trap on_exit EXIT

fail() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  echo "verify_fresh_start_main_shell_emulator: $*" >&2
  exit 1
}

device_state_for() {
  local serial="$1"
  "$ADB_BIN" devices | awk -v serial="$serial" '$1 == serial {print $2; found = 1} END {if (!found) print ""}'
}

authorized_emulators() {
  "$ADB_BIN" devices | awk 'NR > 1 && $1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}'
}

select_emulator_once() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    [[ "$ANDROID_SERIAL" == emulator-* ]] ||
      fail android-serial android-serial-not-emulator "ANDROID_SERIAL=$ANDROID_SERIAL is not an emulator serial."
    local selected_state
    selected_state="$(device_state_for "$ANDROID_SERIAL")"
    [[ "$selected_state" == "device" ]] || return 1
    SELECTED_SERIAL="$ANDROID_SERIAL"
    return
  fi

  local emulator_serials=()
  local serial
  while IFS= read -r serial; do
    [[ -n "$serial" ]] && emulator_serials+=("$serial")
  done < <(authorized_emulators)
  [[ "${#emulator_serials[@]}" -eq 1 ]] || return 1
  SELECTED_SERIAL="${emulator_serials[0]}"
}

wait_for_emulator_selection() {
  local deadline=$((SECONDS + EMULATOR_SELECT_TIMEOUT_SECONDS))
  while true; do
    if select_emulator_once; then
      return
    fi
    [[ "$SECONDS" -lt "$deadline" ]] ||
      fail emulator-selection no-single-authorized-emulator \
        "Timed out waiting for a single authorized emulator. Start exactly one emulator-* device or set AVD_NAME."
    sleep 2
  done
}

wait_for_boot_completed() {
  local deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
  while true; do
    local state boot_completed
    state="$(device_state_for "$SELECTED_SERIAL")"
    boot_completed="$("$ADB_BIN" -s "$SELECTED_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$state" == "device" && "$boot_completed" == "1" ]]; then
      return
    fi
    [[ "$SECONDS" -lt "$deadline" ]] ||
      fail emulator-boot emulator-boot-timeout "Timed out waiting for $SELECTED_SERIAL to finish booting."
    sleep 2
  done
}

emulator_avd_name() {
  "$ADB_BIN" -s "$SELECTED_SERIAL" emu avd name 2>/dev/null |
    tr -d '\r' |
    awk 'NF && $0 != "OK" {value = $0} END {print value}'
}

install_debug_apk() {
  local attempt output
  for attempt in 1 2 3; do
    if output="$("$ADB_BIN" -s "$SELECTED_SERIAL" install -r "$DEBUG_APK" 2>&1)"; then
      return
    fi
    if [[ "$attempt" -lt 3 ]]; then
      sleep $((attempt * 3))
    fi
  done
  printf '%s\n' "$output" >&2
  fail install debug-apk-install-failed "adb install failed after 3 attempts."
}

tap_clickable_node_by_label() {
  local label="$1"
  local dump_file="$2"
  local point
  point="$(python3 - "$dump_file" "$label" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

path = Path(sys.argv[1])
label = sys.argv[2]
try:
    root = ET.fromstring(path.read_text(errors="ignore"))
except Exception:
    sys.exit(1)

def bounds_center(bounds):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds or "")
    if not match:
        return None
    left, top, right, bottom = map(int, match.groups())
    return (left + right) // 2, (top + bottom) // 2

for node in root.iter("node"):
    if node.attrib.get("enabled") != "true":
        continue
    if node.attrib.get("clickable") != "true":
        continue
    if node.attrib.get("content-desc") != label and node.attrib.get("text") != label:
        continue
    center = bounds_center(node.attrib.get("bounds"))
    if center:
        print(center[0], center[1])
        sys.exit(0)
sys.exit(1)
PY
)"
  [[ -n "$point" ]] ||
    fail ui model-manager-button-not-found "Could not find a clickable $label node in $dump_file."
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell input tap $point >/dev/null
}

[[ -x "$ADB_BIN" ]] || fail adb adb-missing "adb not found at $ADB_BIN."
[[ -x "$EMULATOR_BIN" ]] || fail emulator emulator-missing "Android emulator binary not found at $EMULATOR_BIN."
mkdir -p "$ARTIFACT_DIR"

if ! select_emulator_once; then
  [[ -n "${AVD_NAME:-}" ]] ||
    fail requested-avd missing-avd-name "Set AVD_NAME when no emulator is already running."
  "$EMULATOR_BIN" -list-avds 2>/dev/null | grep -Fx -- "$AVD_NAME" >/dev/null ||
    fail requested-avd requested-avd-not-found "AVD_NAME=$AVD_NAME not found."
  EMULATOR_ARGS="${EMULATOR_ARGS:--wipe-data -no-window -no-audio -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect}"
  read -r -a EXTRA_EMULATOR_ARGS <<< "$EMULATOR_ARGS"
  echo "Starting emulator AVD: $AVD_NAME"
  "$EMULATOR_BIN" -avd "$AVD_NAME" "${EXTRA_EMULATOR_ARGS[@]}" > "$EMULATOR_LOG" 2>&1 &
  STARTED_EMULATOR=1
fi

wait_for_emulator_selection
wait_for_boot_completed

API_LEVEL="$("$ADB_BIN" -s "$SELECTED_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
ABI_LIST="$("$ADB_BIN" -s "$SELECTED_SERIAL" shell getprop ro.product.cpu.abilist64 | tr -d '\r')"
AVD_LABEL="$(emulator_avd_name || true)"

"$GRADLE_CMD" :app:assembleDebug
[[ -f "$DEBUG_APK" ]] || fail apk apk-missing "Debug APK not found at $DEBUG_APK."

"$ADB_BIN" -s "$SELECTED_SERIAL" uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true
install_debug_apk
"$ADB_BIN" -s "$SELECTED_SERIAL" shell am start -W -n "$MAIN_ACTIVITY" >/dev/null
sleep 5

capture_artifacts
[[ -s "$SCREENSHOT_FILE" ]] || fail evidence screenshot-missing "Fresh start screenshot was not captured."
[[ -s "$WINDOW_DUMP_FILE" ]] || fail evidence window-dump-missing "Fresh start UI dump was not captured."
if grep -Fq "$FORBIDDEN_FIRST_RUN_TEXT" "$WINDOW_DUMP_FILE"; then
  FIRST_RUN_SETUP_VISIBLE="true"
  tap_clickable_node_by_label "$FIRST_RUN_SKIP_LABEL" "$WINDOW_DUMP_FILE"
  sleep 2
  capture_artifacts
  [[ -s "$WINDOW_DUMP_FILE" ]] || fail evidence window-dump-missing-after-first-run-skip "Fresh start UI dump after first-run skip was not captured."
  if grep -Fq "$FORBIDDEN_FIRST_RUN_TEXT" "$WINDOW_DUMP_FILE"; then
    FIRST_RUN_SETUP_SKIPPED="false"
    fail ui first-run-setup-skip-failed "Tapping $FIRST_RUN_SKIP_LABEL did not dismiss $FORBIDDEN_FIRST_RUN_TEXT."
  fi
  FIRST_RUN_SETUP_SKIPPED="true"
else
  FIRST_RUN_SETUP_VISIBLE="false"
  FIRST_RUN_SETUP_SKIPPED="not-needed"
fi
if ! grep -Fq "$MAIN_COPY_TEXT" "$WINDOW_DUMP_FILE"; then
  MAIN_SHELL_COPY_VISIBLE="false"
  fail ui main-shell-copy-missing "Fresh start UI dump is missing $MAIN_COPY_TEXT."
fi
MAIN_SHELL_COPY_VISIBLE="true"

MODEL_MANAGER_CLICK_OPENED="false"
tap_clickable_node_by_label "$MODEL_MANAGER_BUTTON_LABEL" "$WINDOW_DUMP_FILE"
sleep 2
dump_ui /sdcard/pocketmind-model-manager.xml "$MODEL_MANAGER_DUMP_FILE"
[[ -s "$MODEL_MANAGER_DUMP_FILE" ]] ||
  fail evidence model-manager-window-dump-missing "Model manager UI dump was not captured."
if ! grep -Fq "$MODEL_MANAGER_EXPECTED_TEXT" "$MODEL_MANAGER_DUMP_FILE"; then
  fail ui model-manager-click-no-response "Tapping $MODEL_MANAGER_BUTTON_LABEL did not open the model manager sheet."
fi
MODEL_MANAGER_CLICK_OPENED="true"

echo "Fresh start main shell validation passed."
