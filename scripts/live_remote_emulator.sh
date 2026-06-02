#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
ADB_BIN="${ANDROID_SDK}/platform-tools/adb"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/live-remote-emulator-$(date +%Y%m%d-%H%M%S)}"
REPORT_FILE="${REPORT_FILE:-${ARTIFACT_DIR}/live-remote-emulator.properties}"
PACKAGE_NAME="com.bytedance.zgx.pocketmind"
MAIN_ACTIVITY="${PACKAGE_NAME}/.MainActivity"
DEBUG_CONFIG_RECEIVER="${PACKAGE_NAME}/.debug.DebugRemoteConfigReceiver"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"

LIVE_REMOTE_BASE_URL="${POCKETMIND_LIVE_REMOTE_BASE_URL:-https://api.deepseek.com/v1}"
LIVE_REMOTE_MODEL="${POCKETMIND_LIVE_REMOTE_MODEL:-deepseek-v4-pro}"
LIVE_REMOTE_API_KEY="${POCKETMIND_LIVE_REMOTE_API_KEY:-${DEEPSEEK_API_KEY:-}}"
LIVE_REMOTE_PROMPT="${POCKETMIND_LIVE_REMOTE_PROMPT:-return uppercase token formed by joining word pocketmind with words live and ok using underscores only}"
LIVE_REMOTE_EXPECTED_TEXT="${POCKETMIND_LIVE_REMOTE_EXPECTED_TEXT:-POCKETMIND_LIVE_OK}"
SELECTED_SERIAL=""
STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
API_KEY_SOURCE=""
SCREENSHOT_FILE=""
UI_DUMP_FILE=""

write_report() {
  local exit_code="$1"
  local status="failed"
  [[ "$exit_code" -eq 0 ]] && status="passed"
  mkdir -p "$(dirname "$REPORT_FILE")"
  {
    echo "status=$status"
    echo "exit_code=$exit_code"
    echo "target=live-remote-emulator"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=${SELECTED_SERIAL:-}"
    echo "base_url=$LIVE_REMOTE_BASE_URL"
    echo "model=$LIVE_REMOTE_MODEL"
    echo "api_key_source=${API_KEY_SOURCE:-}"
    echo "expected_text=$LIVE_REMOTE_EXPECTED_TEXT"
    echo "debug_apk=$DEBUG_APK"
    echo "screenshot=$SCREENSHOT_FILE"
    echo "ui_dump=$UI_DUMP_FILE"
  } > "$REPORT_FILE"
  echo "Live remote emulator report: $REPORT_FILE"
}

fail() {
  echo "live_remote_emulator: $*" >&2
  exit 1
}

select_emulator() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    [[ "$ANDROID_SERIAL" == emulator-* ]] || fail "ANDROID_SERIAL=$ANDROID_SERIAL is not an emulator serial."
    local state
    state="$("$ADB_BIN" devices | awk -v serial="$ANDROID_SERIAL" '$1 == serial {print $2; found = 1} END {if (!found) print ""}')"
    [[ "$state" == "device" ]] || fail "ANDROID_SERIAL=$ANDROID_SERIAL is not an authorized emulator; state is ${state:-missing}."
    SELECTED_SERIAL="$ANDROID_SERIAL"
    return
  fi

  local serials=()
  while IFS= read -r serial; do
    [[ -n "$serial" ]] && serials+=("$serial")
  done < <("$ADB_BIN" devices | awk 'NR > 1 && $1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}')
  [[ "${#serials[@]}" -eq 1 ]] || fail "Start exactly one authorized emulator or set ANDROID_SERIAL."
  SELECTED_SERIAL="${serials[0]}"
}

trap 'status=$?; write_report "$status"; exit "$status"' EXIT

[[ -x "$ADB_BIN" ]] || fail "adb not found at $ADB_BIN."
[[ -n "$LIVE_REMOTE_API_KEY" ]] ||
  fail "Set POCKETMIND_LIVE_REMOTE_API_KEY, or DEEPSEEK_API_KEY, before running live remote validation."
if [[ -n "${POCKETMIND_LIVE_REMOTE_API_KEY:-}" ]]; then
  API_KEY_SOURCE="POCKETMIND_LIVE_REMOTE_API_KEY"
else
  API_KEY_SOURCE="DEEPSEEK_API_KEY"
fi

scripts/doctor.sh --device
select_emulator
ADB=("$ADB_BIN" -s "$SELECTED_SERIAL")
echo "Using Android emulator: $SELECTED_SERIAL"
mkdir -p "$ARTIFACT_DIR"

"$GRADLE_CMD" :app:assembleDebug
"${ADB[@]}" install -r "$DEBUG_APK" >/dev/null

set +x
"${ADB[@]}" shell am broadcast \
  -n "$DEBUG_CONFIG_RECEIVER" \
  --es baseUrl "$LIVE_REMOTE_BASE_URL" \
  --es modelName "$LIVE_REMOTE_MODEL" \
  --es apiKey "$LIVE_REMOTE_API_KEY" \
  --ez clearState true >/dev/null

"${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" >/dev/null
sleep 2
"${ADB[@]}" shell input tap 320 2225
encoded_prompt="${LIVE_REMOTE_PROMPT// /%s}"
"${ADB[@]}" shell input text "$encoded_prompt"
sleep 0.5
"${ADB[@]}" shell input keyevent 4
sleep 0.8
"${ADB[@]}" shell input tap 980 2245
sleep "${POCKETMIND_LIVE_REMOTE_WAIT_SECONDS:-45}"

SCREENSHOT_FILE="${ARTIFACT_DIR}/live-remote-result.png"
UI_DUMP_FILE="${ARTIFACT_DIR}/live-remote-result.xml"
"${ADB[@]}" exec-out screencap -p > "$SCREENSHOT_FILE"
"${ADB[@]}" shell uiautomator dump /sdcard/pocketmind-live-remote.xml >/dev/null
"${ADB[@]}" pull /sdcard/pocketmind-live-remote.xml "$UI_DUMP_FILE" >/dev/null

if grep -Fq "远程模型请求失败" "$UI_DUMP_FILE"; then
  fail "Live remote request failed; inspect $UI_DUMP_FILE."
fi

grep -Fq -- "$LIVE_REMOTE_EXPECTED_TEXT" "$UI_DUMP_FILE" ||
  fail "Expected live remote response evidence in UI dump; inspect $UI_DUMP_FILE."

set +x
echo "Live remote emulator validation passed."
