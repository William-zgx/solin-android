#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
ADB_BIN="${ANDROID_SDK}/platform-tools/adb"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/device-control-debug-eval-$(date +%Y%m%d-%H%M%S)}"
REPORT_FILE="${REPORT_FILE:-${ARTIFACT_DIR}/device-control-debug-eval.properties}"
LOGCAT_FILE="${LOGCAT_FILE:-${ARTIFACT_DIR}/logcat.txt}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SKIP_INSTALL="${SKIP_INSTALL:-0}"
STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

PACKAGE_NAME="com.bytedance.zgx.pocketmind"
MAIN_ACTIVITY="${PACKAGE_NAME}/.debug.DeviceControlEvalActivity"
RECEIVER_NAME="${PACKAGE_NAME}/.debug.DeviceControlEvalReceiver"
ACTION_NAME="${PACKAGE_NAME}.debug.DEVICE_CONTROL_EVAL"
RESULT_FILE="files/device_control_eval_result.properties"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
POCKETMIND_ACCESSIBILITY_SERVICE="${PACKAGE_NAME}/${PACKAGE_NAME}.device.PocketMindAccessibilityService"

SELECTED_SERIAL=""
STATUS="failed"
FAILURE_REASON=""
COMMAND_COUNT=0

mkdir -p "$ARTIFACT_DIR"

write_report() {
  local exit_code="$1"
  local command_count
  [[ "$exit_code" -eq 0 ]] && STATUS="passed"
  command_count="$COMMAND_COUNT"
  if [[ -d "$ARTIFACT_DIR" ]]; then
    command_count="$(
      find "$ARTIFACT_DIR" -maxdepth 1 -type f -name '*.properties' \
        ! -name "$(basename "$REPORT_FILE")" | wc -l | tr -d ' '
    )"
  fi
  {
    echo "status=$STATUS"
    echo "exit_code=$exit_code"
    echo "reason=$FAILURE_REASON"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=$SELECTED_SERIAL"
    echo "skip_build=$SKIP_BUILD"
    echo "skip_install=$SKIP_INSTALL"
    echo "command_count=$command_count"
    echo "runner=debug_broadcast"
    echo "debug_apk=$DEBUG_APK"
    echo "logcat_file=$LOGCAT_FILE"
    echo "device_primitives=observe,tap,type_text,scroll,wait,press_back,node_not_found_recovery"
  } > "$REPORT_FILE"
  echo "Device control debug eval report: $REPORT_FILE"
}

on_exit() {
  local status="$?"
  trap - EXIT
  if [[ -n "$SELECTED_SERIAL" && -x "$ADB_BIN" ]]; then
    "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -d -t 500 > "$LOGCAT_FILE" 2>/dev/null || true
  fi
  write_report "$status"
  exit "$status"
}

trap on_exit EXIT

fail_with_reason() {
  FAILURE_REASON="$1"
  shift
  echo "$*" >&2
  exit 1
}

if ! scripts/doctor.sh --device; then
  fail_with_reason doctor-device-failed "Android device environment check failed."
fi

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  SELECTED_SERIAL="$ANDROID_SERIAL"
  SELECTED_STATE="$("$ADB_BIN" devices | awk -v serial="$SELECTED_SERIAL" '$1 == serial {print $2; found = 1} END {if (!found) print ""}')"
  if [[ "$SELECTED_STATE" != "device" ]]; then
    "$ADB_BIN" devices
    fail_with_reason selected-device-unavailable \
      "ANDROID_SERIAL=$SELECTED_SERIAL is not an authorized Android device; state is ${SELECTED_STATE:-missing}."
  fi
else
  DEVICE_COUNT="$("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" {count += 1} END{print count + 0}')"
  if [[ "$DEVICE_COUNT" != "1" ]]; then
    "$ADB_BIN" devices
    fail_with_reason device-selection-ambiguous \
      "Connect exactly one authorized Android device, or set ANDROID_SERIAL to select one."
  fi
  SELECTED_SERIAL="$("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" {print $1; exit}')"
fi

ADB=("$ADB_BIN" -s "$SELECTED_SERIAL")
echo "Using Android device: $SELECTED_SERIAL"
"${ADB[@]}" logcat -c >/dev/null 2>&1 || true

if [[ "$SKIP_BUILD" != "1" ]]; then
  "$GRADLE_CMD" assembleDebug
fi
if [[ "$SKIP_INSTALL" != "1" ]]; then
  "${ADB[@]}" install -r "$DEBUG_APK"
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

if ! pocketmind_accessibility_enabled; then
  fail_with_reason pocketmind-accessibility-not-enabled \
    "PocketMind Accessibility is not enabled. Enable it in system Accessibility settings, then rerun with SKIP_INSTALL=1."
fi

"${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" >/dev/null
sleep 1

result_file_for() {
  local name="$1"
  echo "${ARTIFACT_DIR}/${name}.properties"
}

read_result() {
  local name="$1"
  local output_file
  output_file="$(result_file_for "$name")"
  "${ADB[@]}" exec-out run-as "$PACKAGE_NAME" cat "$RESULT_FILE" > "$output_file"
  echo "$output_file"
}

broadcast_command() {
  local name="$1"
  local request_id output_file
  shift
  COMMAND_COUNT=$((COMMAND_COUNT + 1))
  request_id="${name}-${COMMAND_COUNT}-$(date +%s)"
  "${ADB[@]}" shell am broadcast -a "$ACTION_NAME" -n "$RECEIVER_NAME" --es requestId "$request_id" "$@" >/dev/null
  for _ in {1..40}; do
    output_file="$(read_result "$name")"
    if grep -Fq "requestId=$request_id" "$output_file"; then
      echo "$output_file"
      return
    fi
    sleep 0.25
  done
  echo "Timed out waiting for debug eval result: $request_id" >&2
  cat "$output_file" >&2 || true
  exit 1
}

assert_file_contains() {
  local file="$1"
  local expected="$2"
  if ! grep -Fq "$expected" "$file"; then
    echo "Expected $file to contain: $expected" >&2
    cat "$file" >&2
    exit 1
  fi
}

assert_file_not_contains() {
  local file="$1"
  local unexpected="$2"
  if grep -Fq "$unexpected" "$file"; then
    echo "Expected $file to not contain: $unexpected" >&2
    cat "$file" >&2
    exit 1
  fi
}

observe_file="$(broadcast_command observe --es command observe)"
assert_file_contains "$observe_file" "resultType=available"
assert_file_contains "$observe_file" "PocketMind Device Control Eval"
assert_file_contains "$observe_file" "hasBounds=true"
assert_file_contains "$observe_file" "hasClickable=true"
assert_file_contains "$observe_file" "hasEditable=true"
assert_file_contains "$observe_file" "hasScrollable=true"

tap_file="$(broadcast_command tap --es command tap --es target EvalTapTarget --el timeoutMillis 1500)"
assert_file_contains "$tap_file" "status=Succeeded"
assert_file_contains "$tap_file" "after.textSummary="
assert_file_contains "$tap_file" "Tap success"

type_file="$(broadcast_command type --es command type_text --es target EvalInputField --es text typed_via_accessibility --el timeoutMillis 1500)"
assert_file_contains "$type_file" "status=Succeeded"
assert_file_contains "$type_file" "typed_via_accessibility"

scroll_file=""
for index in 1 2 3 4 5 6 7 8 9 10 11 12; do
  scroll_file="$(broadcast_command "scroll-${index}" --es command scroll --es target EvalScrollContainer --es direction down --el timeoutMillis 1500)"
  if grep -Fq "Scroll target item 35" "$scroll_file"; then
    break
  fi
done
assert_file_contains "$scroll_file" "status=Succeeded"
assert_file_contains "$scroll_file" "Scroll target item 35"

wait_file="$(broadcast_command wait --es command wait --el timeoutMillis 500)"
assert_file_contains "$wait_file" "status=Succeeded"
assert_file_contains "$wait_file" "afterObservationId="

open_panel_file="$(broadcast_command open-panel --es command tap --es target OpenEvalPanel --el timeoutMillis 1500)"
assert_file_contains "$open_panel_file" "status=Succeeded"
assert_file_contains "$open_panel_file" "Eval panel open"

back_file="$(broadcast_command back --es command back --el timeoutMillis 1500)"
assert_file_contains "$back_file" "status=Succeeded"
assert_file_not_contains "$back_file" "Eval panel open"

missing_file="$(broadcast_command missing --es command tap --es target DefinitelyMissingEvalTarget --el timeoutMillis 500)"
assert_file_contains "$missing_file" "status=Failed"
assert_file_contains "$missing_file" "failureKind=NodeNotFound"
assert_file_contains "$missing_file" "retryable=true"

echo "Device control debug eval passed."
