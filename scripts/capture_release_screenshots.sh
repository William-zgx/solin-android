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
BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-180}"
EMULATOR_SELECT_TIMEOUT_SECONDS="${EMULATOR_SELECT_TIMEOUT_SECONDS:-10}"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/release-screenshots-$(date +%Y%m%d-%H%M%S)}"
REPORT_FILE="${REPORT_FILE:-${ARTIFACT_DIR}/release-screenshots.properties}"
SCREENSHOT_DIR="${ARTIFACT_DIR}/screenshots"
UI_DUMP_DIR="${ARTIFACT_DIR}/ui"
LOGCAT_FILE="${ARTIFACT_DIR}/logcat.txt"
EMULATOR_LOG="${ARTIFACT_DIR}-emulator.log"

PACKAGE_NAME="com.bytedance.zgx.solin"
MAIN_ACTIVITY="${PACKAGE_NAME}/.MainActivity"
DEBUG_CONFIG_RECEIVER="${PACKAGE_NAME}/.debug.DebugRemoteConfigReceiver"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
SKIP_STARTUP_MODEL_RUNTIME_EXTRA="com.bytedance.zgx.solin.extra.SKIP_STARTUP_MODEL_RUNTIME_WORK"
DEBUG_SCREENSHOT_REMOTE_BASE_URL_EXTRA="com.bytedance.zgx.solin.extra.DEBUG_SCREENSHOT_REMOTE_BASE_URL"
DEBUG_SCREENSHOT_REMOTE_MODEL_NAME_EXTRA="com.bytedance.zgx.solin.extra.DEBUG_SCREENSHOT_REMOTE_MODEL_NAME"

SCREENSHOT_REMOTE_BASE_URL="${SOLIN_SCREENSHOT_REMOTE_BASE_URL:-https://api.example.com/v1}"
SCREENSHOT_REMOTE_MODEL="${SOLIN_SCREENSHOT_REMOTE_MODEL:-screenshot-evidence-model}"
CONFIRMATION_PROMPT="${SOLIN_SCREENSHOT_CONFIRMATION_PROMPT:-summarize my clipboard and share it}"
RELEASE_ARTIFACT_SHA256="${RELEASE_ARTIFACT_SHA256:-}"

STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SELECTED_SERIAL=""
API_LEVEL=""
ABI_LIST=""
AVD_LABEL=""
FAILED_TARGET=""
FAILURE_REASON=""
STARTED_EMULATOR=0
REPORT_WRITTEN=0
ADB=()
CAPTURED_SCREENSHOTS=()

available_avd_summary() {
  if [[ ! -x "$EMULATOR_BIN" ]]; then
    echo "none"
    return
  fi
  local summary
  summary="$("$EMULATOR_BIN" -list-avds 2>/dev/null | awk 'NF {items = items ? items ", " $0 : $0} END {print items}')"
  echo "${summary:-none}"
}

write_report() {
  local exit_code="$1"
  local status="failed"
  [[ "$exit_code" -eq 0 ]] && status="passed"
  mkdir -p "$(dirname "$REPORT_FILE")"
  {
    echo "status=$status"
    echo "exit_code=$exit_code"
    echo "target=release-screenshots"
    echo "failedTarget=${FAILED_TARGET:-}"
    echo "reason=${FAILURE_REASON:-}"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=${SELECTED_SERIAL:-}"
    echo "api_level=${API_LEVEL:-}"
    echo "abi=${ABI_LIST:-}"
    echo "avd=${AVD_LABEL:-${AVD_NAME:-}}"
    echo "clean_device=1"
    echo "package=$PACKAGE_NAME"
    echo "debug_apk=$DEBUG_APK"
    echo "releaseArtifactSha256=$RELEASE_ARTIFACT_SHA256"
    echo "evidence_dir=$ARTIFACT_DIR"
    echo "screenshot_dir=$SCREENSHOT_DIR"
    echo "ui_dump_dir=$UI_DUMP_DIR"
    echo "logcat_file=$LOGCAT_FILE"
    echo "emulator_log=$EMULATOR_LOG"
    if [[ "${#CAPTURED_SCREENSHOTS[@]}" -gt 0 ]]; then
      local screenshot
      for screenshot in "${CAPTURED_SCREENSHOTS[@]}"; do
        echo "$screenshot"
      done
    fi
  } > "$REPORT_FILE"
  REPORT_WRITTEN=1
  echo "Release screenshot report: $REPORT_FILE"
}

fail() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  echo "capture_release_screenshots: $*" >&2
  if [[ -x "$EMULATOR_BIN" ]]; then
    echo "Available AVDs: $(available_avd_summary)" >&2
  fi
  capture_failure_artifacts 1
  clear_remote_config
  write_report 1
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
    if [[ "$ANDROID_SERIAL" != emulator-* && "${ALLOW_PHYSICAL_SCREENSHOTS:-0}" != "1" ]]; then
      fail android-serial android-serial-not-emulator \
        "ANDROID_SERIAL=$ANDROID_SERIAL is not an emulator serial. Set ALLOW_PHYSICAL_SCREENSHOTS=1 only for explicitly approved physical-device screenshot capture."
    fi
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
        "Timed out waiting for a single authorized emulator. Start exactly one emulator-* device or set AVD_NAME to an available AVD."
    sleep 2
  done
}

wait_for_boot_completed() {
  local deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
  while true; do
    local state boot_completed
    state="$(device_state_for "$SELECTED_SERIAL")"
    boot_completed="$("$ADB_BIN" -s "$SELECTED_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    if [[ "$state" == "device" && "$boot_completed" == "1" ]]; then
      return
    fi
    [[ "$SECONDS" -lt "$deadline" ]] ||
      fail emulator-boot emulator-boot-timeout "Timed out waiting for $SELECTED_SERIAL to finish booting."
    sleep 2
  done
}

emulator_avd_name() {
  "${ADB[@]}" emu avd name 2>/dev/null |
    tr -d '\r' |
    awk 'NF && $0 != "OK" {value = $0} END {print value}'
}

debug_receiver_broadcast() {
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" am broadcast \
    --user 0 \
    -n "$DEBUG_CONFIG_RECEIVER" \
    "$@"
}

clear_remote_config() {
  if [[ -z "${SELECTED_SERIAL:-}" || "${#ADB[@]}" -eq 0 ]]; then
    return
  fi
  debug_receiver_broadcast --ez clearRemoteConfig true >/dev/null 2>&1 || true
}

dump_ui() {
  local label="$1"
  local device_file="/sdcard/solin-release-${label}.xml"
  local local_file="${UI_DUMP_DIR}/${label}.xml"
  mkdir -p "$UI_DUMP_DIR"
  "${ADB[@]}" shell uiautomator dump "$device_file" >/dev/null
  "${ADB[@]}" pull "$device_file" "$local_file" >/dev/null
  echo "$local_file"
}

pick_ui_node() {
  local xml_file="$1"
  shift
  python3 - "$xml_file" "$@" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

xml_path = Path(sys.argv[1])
specs = sys.argv[2:]
raw = xml_path.read_text(errors="ignore")
start = raw.find("<")
if start > 0:
    raw = raw[start:]
root = ET.fromstring(raw)

def matches(node, spec):
    if "=" not in spec:
        return False
    key, expected = spec.split("=", 1)
    contains = key.endswith("~")
    if contains:
        key = key[:-1]
    actual = node.attrib.get(key, "")
    if contains:
        return expected in actual
    return actual == expected

for spec in specs:
    for node in root.iter("node"):
        if not matches(node, spec):
            continue
        bounds = node.attrib.get("bounds", "")
        match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if not match:
            continue
        x1, y1, x2, y2 = map(int, match.groups())
        print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
        sys.exit(0)
sys.exit(1)
PY
}

wait_for_node() {
  local label="$1"
  local timeout="$2"
  shift 2
  local deadline=$((SECONDS + timeout))
  local xml_file coords
  while true; do
    xml_file="$(dump_ui "$label")"
    if coords="$(pick_ui_node "$xml_file" "$@")"; then
      echo "$coords"
      return
    fi
    [[ "$SECONDS" -lt "$deadline" ]] ||
      fail ui-node-missing "${label}-node-missing" "Missing UI node for $label in $xml_file."
    sleep 1
  done
}

tap_node() {
  local label="$1"
  local timeout="$2"
  shift 2
  local coords
  coords="$(wait_for_node "$label" "$timeout" "$@")"
  "${ADB[@]}" shell input tap $coords >/dev/null
}

optional_tap_node() {
  local label="$1"
  shift
  local xml_file coords
  xml_file="$(dump_ui "$label")"
  if coords="$(pick_ui_node "$xml_file" "$@")"; then
    "${ADB[@]}" shell input tap $coords >/dev/null
    return 0
  fi
  return 1
}

capture_screenshot() {
  local name="$1"
  local path="${SCREENSHOT_DIR}/${name}.png"
  local ui_dump required_texts ui_dump_sha
  mkdir -p "$SCREENSHOT_DIR"
  if ! "${ADB[@]}" exec-out screencap -p > "$path"; then
    fail evidence "${name}-screenshot-capture-failed" "Failed to capture $name screenshot."
  fi
  ui_dump="$(dump_ui "screenshot-${name}")"
  validate_screenshot_visual_contract "$name" "$ui_dump"
  local sha
  sha="$(shasum -a 256 "$path" | awk '{print $1}')"
  ui_dump_sha="$(shasum -a 256 "$ui_dump" | awk '{print $1}')"
  required_texts="$(screenshot_required_texts "$name" | paste -sd '|' -)"
  CAPTURED_SCREENSHOTS+=("screenshot.${name}.path=$path")
  CAPTURED_SCREENSHOTS+=("screenshot.${name}.sha256=$sha")
  CAPTURED_SCREENSHOTS+=("screenshot.${name}.sanitized=true")
  CAPTURED_SCREENSHOTS+=("screenshot.${name}.uiDump=$ui_dump")
  CAPTURED_SCREENSHOTS+=("screenshot.${name}.uiDumpSha256=$ui_dump_sha")
  CAPTURED_SCREENSHOTS+=("screenshot.${name}.visualRegression=passed")
  CAPTURED_SCREENSHOTS+=("screenshot.${name}.requiredText=$required_texts")
  echo "Captured $name: $path"
}

screenshot_required_texts() {
  case "$1" in
    chat-home)
      printf '%s\n' "栖知" "让 AI 住在手机里" "为什么装它" "模型管理"
      ;;
    model-manager)
      printf '%s\n' "模型管理" "当前模型" "本地可用" "远程多模态可选"
      ;;
    confirmation-sheet)
      printf '%s\n' "即将发送到远程模型" "确认后才会" "取消"
      ;;
    background-tasks-or-audit)
      printf '%s\n' "后台任务" "最近审计日志" "最近 Agent 轨迹" "暂无运行中的后台任务"
      ;;
    *)
      return 1
      ;;
  esac
}

validate_screenshot_visual_contract() {
  local name="$1"
  local ui_dump="$2"
  python3 - "$name" "$ui_dump" <<'PY' || fail visual-regression "${name}-visual-contract-missing" "Screenshot $name is missing required UI text in $ui_dump."
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

name = sys.argv[1]
xml_path = Path(sys.argv[2])
required = {
    "chat-home": ["栖知", "让 AI 住在手机里", "为什么装它", "模型管理"],
    "model-manager": ["模型管理", "当前模型", "本地可用", "远程多模态可选"],
    "confirmation-sheet": ["即将发送到远程模型", "确认后才会", "取消"],
    "background-tasks-or-audit": ["后台任务", "最近审计日志", "最近 Agent 轨迹", "暂无运行中的后台任务"],
}[name]
raw = xml_path.read_text(errors="ignore")
start = raw.find("<")
if start > 0:
    raw = raw[start:]
root = ET.fromstring(raw)
surface = "\n".join(
    " ".join(
        value
        for value in (
            node.attrib.get("text", ""),
            node.attrib.get("content-desc", ""),
            node.attrib.get("resource-id", ""),
        )
        if value
    )
    for node in root.iter("node")
)
missing = [text for text in required if text not in surface]
if missing:
    print(",".join(missing), file=sys.stderr)
    sys.exit(1)
PY
}

capture_failure_artifacts() {
  local status="$1"
  if [[ "$status" -eq 0 || -z "${SELECTED_SERIAL:-}" || ! -x "$ADB_BIN" ]]; then
    return
  fi
  mkdir -p "$ARTIFACT_DIR"
  "$ADB_BIN" -s "$SELECTED_SERIAL" exec-out screencap -p > "${ARTIFACT_DIR}/failure.png" 2>/dev/null || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell uiautomator dump /sdcard/solin-release-failure.xml >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" pull /sdcard/solin-release-failure.xml "${ARTIFACT_DIR}/failure.xml" >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -d -t 300 > "$LOGCAT_FILE" 2>/dev/null || true
}

finish() {
  local status=$?
  trap - EXIT
  capture_failure_artifacts "$status"
  clear_remote_config
  if [[ "$STARTED_EMULATOR" == "1" && "${STOP_EMULATOR_AFTER_CAPTURE:-1}" == "1" && -n "${SELECTED_SERIAL:-}" && -x "$ADB_BIN" ]]; then
    "$ADB_BIN" -s "$SELECTED_SERIAL" emu kill >/dev/null 2>&1 || true
  fi
  if [[ "$REPORT_WRITTEN" != "1" ]]; then
    write_report "$status"
  fi
  exit "$status"
}
trap finish EXIT

[[ -x "$ADB_BIN" ]] || fail adb adb-missing "adb not found at $ADB_BIN."
if ! scripts/doctor.sh --device; then
  fail doctor doctor-device-failed "Android emulator environment check failed."
fi
[[ -x "$EMULATOR_BIN" ]] || fail emulator-binary emulator-binary-missing "Android emulator binary not found at $EMULATOR_BIN."
if [[ -n "${ANDROID_SERIAL:-}" && "$ANDROID_SERIAL" != emulator-* && "${ALLOW_PHYSICAL_SCREENSHOTS:-0}" != "1" ]]; then
  fail android-serial android-serial-not-emulator "ANDROID_SERIAL=$ANDROID_SERIAL is not an emulator serial."
fi
mkdir -p "$ARTIFACT_DIR"

if [[ -n "${AVD_NAME:-}" ]]; then
  "$EMULATOR_BIN" -list-avds 2>/dev/null | grep -Fx -- "$AVD_NAME" >/dev/null ||
    fail requested-avd requested-avd-not-found "AVD_NAME=$AVD_NAME not found."
  EXTRA_EMULATOR_ARGS=()
  if [[ -n "${EMULATOR_ARGS:-}" ]]; then
    read -r -a EXTRA_EMULATOR_ARGS <<< "$EMULATOR_ARGS"
  fi
  EMULATOR_CMD=("$EMULATOR_BIN" -avd "$AVD_NAME")
  if [[ "${#EXTRA_EMULATOR_ARGS[@]}" -gt 0 ]]; then
    EMULATOR_CMD+=("${EXTRA_EMULATOR_ARGS[@]}")
  fi
  echo "Starting emulator AVD: $AVD_NAME"
  echo "Emulator log: $EMULATOR_LOG"
  "${EMULATOR_CMD[@]}" > "$EMULATOR_LOG" 2>&1 &
  STARTED_EMULATOR=1
fi

wait_for_emulator_selection
wait_for_boot_completed

ADB=("$ADB_BIN" -s "$SELECTED_SERIAL")
API_LEVEL="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
ABI_LIST="$("${ADB[@]}" shell getprop ro.product.cpu.abilist64 | tr -d '\r')"
AVD_LABEL="$(emulator_avd_name || true)"

echo "Using Android emulator: $SELECTED_SERIAL"
echo "API: ${API_LEVEL:-unknown}"
echo "ABI: ${ABI_LIST:-unknown}"
echo "AVD: ${AVD_LABEL:-${AVD_NAME:-unknown}}"

if ! "$GRADLE_CMD" :app:assembleDebug; then
  fail gradle assemble-debug-failed "Debug APK assembly failed."
fi
if ! "${ADB[@]}" install -r "$DEBUG_APK" >/dev/null; then
  fail install debug-apk-install-failed "Debug APK install failed."
fi
if ! "${ADB[@]}" shell pm clear "$PACKAGE_NAME" >/dev/null; then
  fail install app-state-clear-failed "Failed to clear app state before sanitized screenshot capture."
fi
if ! "${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" \
  --ez "$SKIP_STARTUP_MODEL_RUNTIME_EXTRA" true \
  --es "$DEBUG_SCREENSHOT_REMOTE_BASE_URL_EXTRA" "$SCREENSHOT_REMOTE_BASE_URL" \
  --es "$DEBUG_SCREENSHOT_REMOTE_MODEL_NAME_EXTRA" "$SCREENSHOT_REMOTE_MODEL" >/dev/null; then
  fail app-launch app-launch-failed "MainActivity launch failed."
fi

optional_tap_node "first-run" "text=先跳过" >/dev/null || true
wait_for_node "chat-home-ready" 20 "text=输入问题" "text=模型管理" >/dev/null
capture_screenshot "chat-home"

tap_node "open-model-manager" 10 "text=模型管理" "content-desc=模型管理"
wait_for_node "model-manager-ready" 20 "content-desc=关闭模型管理" >/dev/null
capture_screenshot "model-manager"
tap_node "close-model-manager" 10 "content-desc=关闭模型管理"

tap_node "composer-input" 20 "text=输入问题"
encoded_prompt="${CONFIRMATION_PROMPT// /%s}"
if ! "${ADB[@]}" shell input text "$encoded_prompt" >/dev/null; then
  fail ui-input prompt-text-input-failed "Prompt text input failed."
fi
"${ADB[@]}" shell input keyevent 4 >/dev/null || true
tap_node "send-confirmation-prompt" 10 "content-desc=发送"
wait_for_node "confirmation-ready" 20 "text=即将发送到远程模型" "text~=确认后才会" >/dev/null
capture_screenshot "confirmation-sheet"
tap_node "dismiss-confirmation" 10 "text=取消"

tap_node "open-background-tasks" 20 "text=后台任务" "content-desc=后台任务"
wait_for_node "background-tasks-top-ready" 20 "text=暂无运行中的后台任务" >/dev/null
"${ADB[@]}" shell input swipe 540 2100 540 650 600 >/dev/null || true
sleep 1
wait_for_node "background-tasks-ready" 20 "text=最近审计日志" >/dev/null
capture_screenshot "background-tasks-or-audit"

"${ADB[@]}" logcat -d -t 300 > "$LOGCAT_FILE" 2>/dev/null || true
echo "Release screenshot capture passed."
