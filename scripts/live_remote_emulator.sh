#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
ADB_BIN="${ANDROID_SDK}/platform-tools/adb"
LIVE_REMOTE_TARGET="${SOLIN_LIVE_REMOTE_TARGET:-emulator}"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/live-remote-${LIVE_REMOTE_TARGET}-$(date +%Y%m%d-%H%M%S)}"
REPORT_FILE="${REPORT_FILE:-${ARTIFACT_DIR}/live-remote-${LIVE_REMOTE_TARGET}.properties}"
SCREENSHOT_FILE="${ARTIFACT_DIR}/live-remote-result.png"
UI_DUMP_FILE="${ARTIFACT_DIR}/live-remote-result.xml"
INPUT_DUMP_FILE="${ARTIFACT_DIR}/live-remote-before-input.xml"
SEND_READY_DUMP_FILE="${ARTIFACT_DIR}/live-remote-before-send.xml"
AFTER_SEND_DUMP_FILE="${ARTIFACT_DIR}/live-remote-after-send.xml"
LOGCAT_FILE="${ARTIFACT_DIR}/live-remote-logcat.txt"
PACKAGE_NAME="com.bytedance.zgx.solin"
MAIN_ACTIVITY="${PACKAGE_NAME}/.MainActivity"
DEBUG_CONFIG_RECEIVER="${PACKAGE_NAME}/.debug.DebugRemoteConfigReceiver"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"

LIVE_REMOTE_BASE_URL="${SOLIN_LIVE_REMOTE_BASE_URL:-}"
LIVE_REMOTE_MODEL="${SOLIN_LIVE_REMOTE_MODEL:-}"
LIVE_REMOTE_API_KEY="${SOLIN_LIVE_REMOTE_API_KEY:-}"
LIVE_REMOTE_PROMPT="${SOLIN_LIVE_REMOTE_PROMPT:-return uppercase token formed by joining word solin with words live and ok using underscores only}"
LIVE_REMOTE_EXPECTED_TEXT="${SOLIN_LIVE_REMOTE_EXPECTED_TEXT:-SOLIN_LIVE_OK}"
SELECTED_SERIAL=""
STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
API_KEY_SOURCE=""
BASE_URL_SOURCE=""
MODEL_SOURCE=""
REMOTE_CONFIRMATION_HANDLED=""
FAILED_TARGET=""
FAILURE_REASON=""
REPORT_WRITTEN=0

write_report() {
  local exit_code="$1"
  local status="failed"
  [[ "$exit_code" -eq 0 ]] && status="passed"
  mkdir -p "$(dirname "$REPORT_FILE")"
  {
    echo "status=$status"
    echo "exit_code=$exit_code"
    echo "target=live-remote-$LIVE_REMOTE_TARGET"
    echo "device_target=$LIVE_REMOTE_TARGET"
    echo "failedTarget=${FAILED_TARGET:-}"
    echo "reason=${FAILURE_REASON:-}"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=${SELECTED_SERIAL:-}"
    echo "base_url=<redacted>"
    echo "base_url_source=${BASE_URL_SOURCE:-}"
    echo "model=<redacted>"
    echo "model_source=${MODEL_SOURCE:-}"
    echo "api_key_source=${API_KEY_SOURCE:-}"
    echo "expected_text=$LIVE_REMOTE_EXPECTED_TEXT"
    echo "debug_apk=$DEBUG_APK"
    echo "evidence_dir=$ARTIFACT_DIR"
    echo "screenshot=$SCREENSHOT_FILE"
    echo "ui_dump=$UI_DUMP_FILE"
    echo "input_dump=$INPUT_DUMP_FILE"
    echo "send_ready_dump=$SEND_READY_DUMP_FILE"
    echo "after_send_dump=$AFTER_SEND_DUMP_FILE"
    echo "logcat_file=$LOGCAT_FILE"
    echo "remote_confirmation_handled=${REMOTE_CONFIRMATION_HANDLED:-}"
  } > "$REPORT_FILE"
  REPORT_WRITTEN=1
  echo "Live remote $LIVE_REMOTE_TARGET report: $REPORT_FILE"
}

fail() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  echo "live_remote_emulator: $*" >&2
  capture_failure_evidence 1
  sanitize_live_remote_artifacts
  clear_remote_config
  write_report 1
  exit 1
}

debug_receiver_broadcast() {
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell run-as "$PACKAGE_NAME" am broadcast \
    --user 0 \
    -n "$DEBUG_CONFIG_RECEIVER" \
    "$@"
}

shell_single_quote() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

debug_receiver_configure_remote() {
  local base_url_quoted model_quoted api_key_quoted
  base_url_quoted="$(shell_single_quote "$LIVE_REMOTE_BASE_URL")"
  model_quoted="$(shell_single_quote "$LIVE_REMOTE_MODEL")"
  api_key_quoted="$(shell_single_quote "$LIVE_REMOTE_API_KEY")"
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell run-as "$PACKAGE_NAME" sh -s <<EOF
base_url=$base_url_quoted
model_name=$model_quoted
api_key=$api_key_quoted
am broadcast --user 0 -n "$DEBUG_CONFIG_RECEIVER" --es baseUrl "\$base_url" --es modelName "\$model_name" --es apiKey "\$api_key" --ez clearState true
EOF
}

clear_remote_config() {
  if [[ -z "${SELECTED_SERIAL:-}" || ! -x "$ADB_BIN" ]]; then
    return
  fi
  debug_receiver_broadcast --ez clearRemoteConfig true >/dev/null 2>&1 || true
}

select_device() {
  case "$LIVE_REMOTE_TARGET" in
    emulator|device)
      ;;
    *)
      fail target invalid-target "SOLIN_LIVE_REMOTE_TARGET must be emulator or device."
      ;;
  esac

  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    if [[ "$LIVE_REMOTE_TARGET" == "emulator" && "$ANDROID_SERIAL" != emulator-* ]]; then
      fail emulator-selection android-serial-not-emulator "ANDROID_SERIAL=$ANDROID_SERIAL is not an emulator serial."
    fi
    if [[ "$LIVE_REMOTE_TARGET" == "device" && "$ANDROID_SERIAL" == emulator-* ]]; then
      fail device-selection android-serial-is-emulator "ANDROID_SERIAL=$ANDROID_SERIAL is not a physical device serial."
    fi
    local state
    state="$("$ADB_BIN" devices | awk -v serial="$ANDROID_SERIAL" '$1 == serial {print $2; found = 1} END {if (!found) print ""}')"
    [[ "$state" == "device" ]] ||
      fail "$LIVE_REMOTE_TARGET-selection" "selected-$LIVE_REMOTE_TARGET-unavailable" \
        "ANDROID_SERIAL=$ANDROID_SERIAL is not an authorized $LIVE_REMOTE_TARGET; state is ${state:-missing}."
    SELECTED_SERIAL="$ANDROID_SERIAL"
    return
  fi

  local serials=()
  if [[ "$LIVE_REMOTE_TARGET" == "emulator" ]]; then
    while IFS= read -r serial; do
      [[ -n "$serial" ]] && serials+=("$serial")
    done < <("$ADB_BIN" devices | awk 'NR > 1 && $1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}')
  else
    while IFS= read -r serial; do
      [[ -n "$serial" ]] && serials+=("$serial")
    done < <("$ADB_BIN" devices | awk 'NR > 1 && $1 !~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}')
  fi
  [[ "${#serials[@]}" -eq 1 ]] ||
    fail "$LIVE_REMOTE_TARGET-selection" "no-single-authorized-$LIVE_REMOTE_TARGET" \
      "Connect exactly one authorized $LIVE_REMOTE_TARGET or set ANDROID_SERIAL."
  SELECTED_SERIAL="${serials[0]}"
}

capture_failure_evidence() {
  local status="$1"
  if [[ "$status" -eq 0 || -z "${SELECTED_SERIAL:-}" || ! -x "$ADB_BIN" ]]; then
    return
  fi
  mkdir -p "$ARTIFACT_DIR"
  "$ADB_BIN" -s "$SELECTED_SERIAL" exec-out screencap -p > "$SCREENSHOT_FILE" 2>/dev/null || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell uiautomator dump /sdcard/solin-live-remote.xml >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" pull /sdcard/solin-live-remote.xml "$UI_DUMP_FILE" >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -d -t 300 > "$LOGCAT_FILE" 2>/dev/null || true
}

sanitize_live_remote_artifacts() {
  python3 - "$LIVE_REMOTE_BASE_URL" "$LIVE_REMOTE_MODEL" "$LIVE_REMOTE_API_KEY" \
    "$UI_DUMP_FILE" "$INPUT_DUMP_FILE" "$SEND_READY_DUMP_FILE" "$AFTER_SEND_DUMP_FILE" "$LOGCAT_FILE" <<'PY'
import sys
from pathlib import Path
from urllib.parse import urlparse

base_url, model, api_key = sys.argv[1:4]
paths = [Path(value) for value in sys.argv[4:]]
replacements = []
for value in (api_key, base_url, model):
    if value:
        replacements.append(value)
if base_url:
    parsed = urlparse(base_url)
    if parsed.netloc:
        replacements.append(parsed.netloc)

for path in paths:
    if not path.is_file():
        continue
    try:
        text = path.read_text(errors="ignore")
    except OSError:
        continue
    sanitized = text
    for value in replacements:
        sanitized = sanitized.replace(value, "<redacted>")
    if sanitized != text:
        path.write_text(sanitized)
PY
}

read_screen_size() {
  local raw size
  raw="$("${ADB[@]}" shell wm size 2>/dev/null | tr -d '\r' || true)"
  size="$(sed -nE 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/p' <<<"$raw" | tail -n 1)"
  if [[ -n "$size" ]]; then
    printf '%s\n' "$size"
  else
    printf '1080 2400\n'
  fi
}

dump_ui() {
  local remote_path="$1"
  local local_path="$2"
  if ! "${ADB[@]}" shell uiautomator dump "$remote_path" >/dev/null; then
    return 1
  fi
  "${ADB[@]}" pull "$remote_path" "$local_path" >/dev/null
}

tap_node_from_dump() {
  local mode="$1"
  local label="$2"
  local dump_file="$3"
  local failure_reason="$4"
  local target_name="${label:-$mode}"
  local point
  point="$(python3 - "$mode" "$label" "$dump_file" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

mode, label, dump_path = sys.argv[1], sys.argv[2], Path(sys.argv[3])
try:
    root = ET.fromstring(dump_path.read_text(errors="ignore"))
except Exception:
    sys.exit(1)

def enabled(node):
    return node.attrib.get("enabled", "true") == "true"

def clickable(node):
    return node.attrib.get("clickable") == "true"

def center(node):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if not match:
        return None
    left, top, right, bottom = map(int, match.groups())
    return (left + right) // 2, (top + bottom) // 2

def walk(node, ancestors):
    if mode == "edittext" and node.attrib.get("class") == "android.widget.EditText" and enabled(node):
        target_center = center(node)
        if target_center:
            print(*target_center)
            sys.exit(0)
    if mode == "label" and enabled(node):
        if node.attrib.get("content-desc") == label or node.attrib.get("text") == label:
            candidates = [node] + list(reversed(ancestors))
            for candidate in candidates:
                if enabled(candidate) and clickable(candidate):
                    target_center = center(candidate)
                    if target_center:
                        print(*target_center)
                        sys.exit(0)
    for child in node:
        walk(child, ancestors + [node])

walk(root, [])
sys.exit(1)
PY
)"
  [[ -n "$point" ]] || fail ui-input "$failure_reason" "Could not find $target_name in $dump_file."
  "${ADB[@]}" shell input tap $point
}

on_exit() {
  local status="$?"
  trap - EXIT
  capture_failure_evidence "$status"
  sanitize_live_remote_artifacts
  clear_remote_config
  if [[ "$REPORT_WRITTEN" != "1" ]]; then
    write_report "$status"
  fi
  exit "$status"
}

trap on_exit EXIT

[[ -x "$ADB_BIN" ]] || fail adb adb-missing "adb not found at $ADB_BIN."
[[ -n "$LIVE_REMOTE_BASE_URL" ]] ||
  fail configuration missing-base-url "Set SOLIN_LIVE_REMOTE_BASE_URL before running live remote validation."
[[ -n "$LIVE_REMOTE_MODEL" ]] ||
  fail configuration missing-model "Set SOLIN_LIVE_REMOTE_MODEL before running live remote validation."
[[ -n "$LIVE_REMOTE_API_KEY" ]] ||
  fail configuration missing-api-key "Set SOLIN_LIVE_REMOTE_API_KEY before running live remote validation."
BASE_URL_SOURCE="SOLIN_LIVE_REMOTE_BASE_URL"
MODEL_SOURCE="SOLIN_LIVE_REMOTE_MODEL"
API_KEY_SOURCE="SOLIN_LIVE_REMOTE_API_KEY"

if ! scripts/doctor.sh --device; then
  fail doctor doctor-device-failed "Android $LIVE_REMOTE_TARGET environment check failed."
fi
select_device
ADB=("$ADB_BIN" -s "$SELECTED_SERIAL")
echo "Using Android $LIVE_REMOTE_TARGET: $SELECTED_SERIAL"
mkdir -p "$ARTIFACT_DIR"

if ! "$GRADLE_CMD" :app:assembleDebug; then
  fail gradle assemble-debug-failed "Debug APK assembly failed."
fi
if ! "${ADB[@]}" install -r "$DEBUG_APK" >/dev/null; then
  fail install debug-apk-install-failed "Debug APK install failed."
fi

set +x
if ! debug_receiver_configure_remote >/dev/null; then
  fail remote-config remote-config-broadcast-failed "Debug remote config broadcast failed."
fi

if ! "${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" >/dev/null; then
  fail app-launch app-launch-failed "MainActivity launch failed."
fi
sleep 2
read -r screen_width screen_height <<<"$(read_screen_size)"
if ! dump_ui /sdcard/solin-live-remote-before-input.xml "$INPUT_DUMP_FILE"; then
  fail evidence input-ui-dump-failed "Live remote input UI dump failed."
fi
tap_node_from_dump edittext "" "$INPUT_DUMP_FILE" prompt-field-not-found
encoded_prompt="${LIVE_REMOTE_PROMPT// /%s}"
if ! "${ADB[@]}" shell input text "$encoded_prompt"; then
  fail ui-input prompt-text-input-failed "Prompt text input failed."
fi
sleep 0.5
if ! "${ADB[@]}" shell input keyevent 4; then
  fail ui-input keyboard-dismiss-failed "Keyboard dismiss failed."
fi
sleep 0.8
if ! dump_ui /sdcard/solin-live-remote-before-send.xml "$SEND_READY_DUMP_FILE"; then
  fail evidence send-ready-ui-dump-failed "Live remote send-ready UI dump failed."
fi
tap_node_from_dump label "发送" "$SEND_READY_DUMP_FILE" send-button-not-found
sleep 1
if ! dump_ui /sdcard/solin-live-remote-after-send.xml "$AFTER_SEND_DUMP_FILE"; then
  fail evidence after-send-ui-dump-failed "Live remote after-send UI dump failed."
fi
if grep -Fq "即将发送到远程模型" "$AFTER_SEND_DUMP_FILE"; then
  tap_node_from_dump label "确认发送" "$AFTER_SEND_DUMP_FILE" remote-send-confirm-button-not-found
  REMOTE_CONFIRMATION_HANDLED="true"
else
  REMOTE_CONFIRMATION_HANDLED="false"
fi
sleep "${SOLIN_LIVE_REMOTE_WAIT_SECONDS:-45}"

if ! "${ADB[@]}" exec-out screencap -p > "$SCREENSHOT_FILE"; then
  fail evidence screenshot-capture-failed "Live remote screenshot capture failed."
fi
if ! "${ADB[@]}" shell uiautomator dump /sdcard/solin-live-remote.xml >/dev/null; then
  fail evidence ui-dump-command-failed "Live remote UI dump command failed."
fi
if ! "${ADB[@]}" pull /sdcard/solin-live-remote.xml "$UI_DUMP_FILE" >/dev/null; then
  fail evidence ui-dump-pull-failed "Live remote UI dump pull failed."
fi
if ! "${ADB[@]}" logcat -d -t 300 > "$LOGCAT_FILE"; then
  fail evidence logcat-capture-failed "Live remote logcat capture failed."
fi

if grep -Fq "远程模型请求失败" "$UI_DUMP_FILE"; then
  fail remote-request remote-request-failed "Live remote request failed; inspect $UI_DUMP_FILE."
fi

grep -Fq -- "$LIVE_REMOTE_EXPECTED_TEXT" "$UI_DUMP_FILE" ||
  fail expected-response expected-text-not-found "Expected live remote response evidence in UI dump; inspect $UI_DUMP_FILE."

set +x
echo "Live remote $LIVE_REMOTE_TARGET validation passed."
