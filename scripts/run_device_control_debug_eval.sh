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
DIAGNOSTICS_DIR="${DIAGNOSTICS_DIR:-${ARTIFACT_DIR}/diagnostics}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SKIP_INSTALL="${SKIP_INSTALL:-0}"
AUTO_ENABLE_SOLIN_ACCESSIBILITY="${AUTO_ENABLE_SOLIN_ACCESSIBILITY:-1}"
STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

PACKAGE_NAME="com.bytedance.zgx.solin"
MAIN_ACTIVITY="${PACKAGE_NAME}/.debug.DeviceControlEvalActivity"
RECEIVER_NAME="${PACKAGE_NAME}/.debug.DeviceControlEvalReceiver"
ACTION_NAME="${PACKAGE_NAME}.debug.DEVICE_CONTROL_EVAL"
RESULT_FILE_PREFIX="files/device_control_eval_result_"
RESULT_FILE_SUFFIX=".properties"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
SOLIN_ACCESSIBILITY_SERVICE="${PACKAGE_NAME}/${PACKAGE_NAME}.device.SolinAccessibilityService"

SELECTED_SERIAL=""
API_LEVEL=""
ABI_LIST=""
STATUS="failed"
FAILED_TARGET=""
FAILURE_REASON=""
COMMAND_COUNT=0

mkdir -p "$ARTIFACT_DIR"

sha256_file() {
  local file="$1"
  if [[ -f "$file" ]]; then
    shasum -a 256 "$file" | awk '{print $1}'
  fi
}

sanitize_artifact_name() {
  local value="$1"
  value="$(printf '%s' "$value" | LC_ALL=C tr -c 'A-Za-z0-9._-' '_' | cut -c 1-120)"
  [[ -n "$value" ]] || value="failure"
  printf '%s' "$value"
}

capture_failure_diagnostics() {
  local label="$1"
  local reason="$2"
  local safe_label diag_dir remote_dump
  if [[ -z "$SELECTED_SERIAL" || ! -x "$ADB_BIN" ]]; then
    return 0
  fi
  safe_label="$(sanitize_artifact_name "$label")"
  diag_dir="${DIAGNOSTICS_DIR}/${safe_label}"
  remote_dump="/sdcard/solin-eval-${safe_label}.xml"
  mkdir -p "$diag_dir"
  {
    echo "label=$label"
    echo "reason=$reason"
    echo "captured_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } > "${diag_dir}/diagnostics.properties"
  "$ADB_BIN" -s "$SELECTED_SERIAL" exec-out screencap -p > "${diag_dir}/screenshot.png" 2>/dev/null || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell uiautomator dump "$remote_dump" >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" exec-out cat "$remote_dump" > "${diag_dir}/uiautomator.xml" 2>/dev/null || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell rm -f "$remote_dump" >/dev/null 2>&1 || true
  {
    echo "focused_window_lines:"
    "$ADB_BIN" -s "$SELECTED_SERIAL" shell dumpsys window 2>/dev/null |
      tr -d '\r' |
      grep -E 'mCurrentFocus|mFocusedApp|mFocusedWindow|mInputMethodTarget|mTopFocusedDisplayId' || true
    echo
    echo "window_dump:"
    "$ADB_BIN" -s "$SELECTED_SERIAL" shell dumpsys window windows 2>/dev/null | tr -d '\r' || true
  } > "${diag_dir}/focused-window.txt"
  "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -d -t 1000 > "${diag_dir}/logcat.txt" 2>/dev/null || true
  echo "Failure diagnostics: ${diag_dir}" >&2
}

write_report() {
  local exit_code="$1"
  local command_count artifact_id logcat_sha256
  [[ "$exit_code" -eq 0 ]] && STATUS="passed"
  artifact_id="device-control-${SELECTED_SERIAL:-unselected}-api${API_LEVEL:-unknown}-${STARTED_AT_UTC}"
  artifact_id="${artifact_id//:/}"
  logcat_sha256="$(sha256_file "$LOGCAT_FILE")"
  command_count="$COMMAND_COUNT"
  if [[ -d "$ARTIFACT_DIR" ]]; then
    command_count="$(
      find "$ARTIFACT_DIR" -maxdepth 1 -type f -name '*.properties' \
        ! -name "$(basename "$REPORT_FILE")" | wc -l | tr -d ' '
    )"
  fi
  {
    echo "artifact_schema=DeviceDebugEvalArtifact/v1"
    echo "artifact_id=$artifact_id"
    echo "status=$STATUS"
    echo "exit_code=$exit_code"
    echo "target=device-control-debug-eval"
    echo "failedTarget=${FAILED_TARGET:-}"
    echo "reason=$FAILURE_REASON"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=$SELECTED_SERIAL"
    echo "api_level=$API_LEVEL"
    echo "abi=$ABI_LIST"
    echo "skip_build=$SKIP_BUILD"
    echo "skip_install=$SKIP_INSTALL"
    echo "command_count=$command_count"
    echo "runner=debug_broadcast"
    echo "debug_apk=$DEBUG_APK"
    echo "logcat_file=$LOGCAT_FILE"
    echo "logcat_sha256=$logcat_sha256"
    echo "diagnostics_dir=$DIAGNOSTICS_DIR"
    echo "result_file_pattern=${RESULT_FILE_PREFIX}<requestId>${RESULT_FILE_SUFFIX}"
    echo "device_primitives=observe,tap,type_text,scroll,wait,press_back,node_not_found_recovery"
    echo "app_search_profiles=taobao,pdd,gaode,browser"
  } > "$REPORT_FILE"
  echo "Device control debug eval report: $REPORT_FILE"
}

on_exit() {
  local status="$?"
  trap - EXIT
  if [[ -n "$SELECTED_SERIAL" && -x "$ADB_BIN" ]]; then
    "$ADB_BIN" -s "$SELECTED_SERIAL" shell cmd power suppress-ambient-display solin-debug-eval false >/dev/null 2>&1 || true
    "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -d -t 500 > "$LOGCAT_FILE" 2>/dev/null || true
  fi
  write_report "$status"
  exit "$status"
}

trap on_exit EXIT

fail_with_reason() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  echo "$*" >&2
  capture_failure_diagnostics "fatal-${FAILURE_REASON}" "$*" || true
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
API_LEVEL="$("${ADB[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
ABI_LIST="$("${ADB[@]}" shell getprop ro.product.cpu.abilist64 2>/dev/null | tr -d '\r' || true)"
"${ADB[@]}" logcat -c >/dev/null 2>&1 || true

if [[ "$SKIP_BUILD" != "1" ]]; then
  "$GRADLE_CMD" :app:assembleDebug
fi
if [[ "$SKIP_INSTALL" != "1" ]]; then
  "${ADB[@]}" install -r "$DEBUG_APK"
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

enable_solin_accessibility_for_eval() {
  local current updated
  current="$("${ADB[@]}" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r' || true)"
  if [[ -z "$current" || "$current" == "null" ]]; then
    updated="$SOLIN_ACCESSIBILITY_SERVICE"
  elif [[ ":$current:" == *":$SOLIN_ACCESSIBILITY_SERVICE:"* ]]; then
    updated="$current"
  else
    updated="${current}:$SOLIN_ACCESSIBILITY_SERVICE"
  fi
  "${ADB[@]}" shell settings put secure enabled_accessibility_services "$updated"
  "${ADB[@]}" shell settings put secure accessibility_enabled 1
  sleep 2
}

prepare_interactive_surface() {
  "${ADB[@]}" shell cmd power suppress-ambient-display solin-debug-eval true >/dev/null 2>&1 || true
  "${ADB[@]}" shell cmd power wakeup 0 >/dev/null 2>&1 || true
  "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  "${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  "${ADB[@]}" shell input keyevent KEYCODE_MENU >/dev/null 2>&1 || true
  "${ADB[@]}" shell input swipe 600 2100 600 700 200 >/dev/null 2>&1 || true
  "${ADB[@]}" shell cmd statusbar collapse >/dev/null 2>&1 || true
  sleep 1
}

if ! solin_accessibility_enabled; then
  if [[ "$AUTO_ENABLE_SOLIN_ACCESSIBILITY" == "1" ]]; then
    echo "栖知 Accessibility is not enabled; enabling it through adb secure settings for debug eval."
    enable_solin_accessibility_for_eval
  fi
  if ! solin_accessibility_enabled; then
    fail_with_reason accessibility solin-accessibility-not-enabled \
      "栖知 Accessibility is not enabled. Enable it in system Accessibility settings, then rerun with SKIP_INSTALL=1."
  fi
fi

prepare_interactive_surface
"${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" >/dev/null
prepare_interactive_surface
sleep 1

result_file_for() {
  local name="$1"
  echo "${ARTIFACT_DIR}/${name}.properties"
}

device_result_file_for() {
  local request_id="$1"
  echo "${RESULT_FILE_PREFIX}${request_id}${RESULT_FILE_SUFFIX}"
}

read_result() {
  local request_id="$1"
  local output_file="$2"
  "${ADB[@]}" exec-out run-as "$PACKAGE_NAME" cat "$(device_result_file_for "$request_id")" > "$output_file" 2>/dev/null
}

remove_device_result() {
  local request_id="$1"
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" rm -f "$(device_result_file_for "$request_id")" >/dev/null 2>&1 || true
}

broadcast_command() {
  local name="$1"
  local request_id output_file
  shift
  COMMAND_COUNT=$((COMMAND_COUNT + 1))
  request_id="${name}-${COMMAND_COUNT}-$$-$(date +%s)"
  output_file="$(result_file_for "$name")"
  rm -f "$output_file"
  remove_device_result "$request_id"
  "${ADB[@]}" shell am broadcast -a "$ACTION_NAME" -n "$RECEIVER_NAME" --es requestId "$request_id" "$@" >/dev/null
  for _ in {1..40}; do
    if read_result "$request_id" "$output_file"; then
      if grep -Fq "requestId=$request_id" "$output_file"; then
        echo "$output_file"
        return
      fi
    fi
    sleep 0.25
  done
  echo "Timed out waiting for debug eval result: $request_id" >&2
  [[ -f "$output_file" ]] && cat "$output_file" >&2 || true
  FAILED_TARGET="debug-eval-timeout"
  FAILURE_REASON="debug-eval-timeout:${request_id}"
  capture_failure_diagnostics "${name}-timeout" "timeout_waiting_for_request:${request_id}" || true
  exit 1
}

assert_file_contains() {
  local file="$1"
  local expected="$2"
  if ! grep -Fq "$expected" "$file"; then
    echo "Expected $file to contain: $expected" >&2
    cat "$file" >&2
    FAILED_TARGET="assertion"
    FAILURE_REASON="assertion-failed"
    capture_failure_diagnostics "assert-$(basename "$file" .properties)" "missing_expected:${expected}" || true
    exit 1
  fi
}

assert_file_not_contains() {
  local file="$1"
  local unexpected="$2"
  if grep -Fq "$unexpected" "$file"; then
    echo "Expected $file to not contain: $unexpected" >&2
    cat "$file" >&2
    FAILED_TARGET="assertion"
    FAILURE_REASON="assertion-failed"
    capture_failure_diagnostics "assert-$(basename "$file" .properties)" "unexpected_present:${unexpected}" || true
    exit 1
  fi
}

assert_file_contains_any() {
  local file="$1"
  shift
  local expected
  for expected in "$@"; do
    if grep -Fq "$expected" "$file"; then
      return
    fi
  done
  echo "Expected $file to contain one of: $*" >&2
  cat "$file" >&2
  FAILED_TARGET="assertion"
  FAILURE_REASON="assertion-failed"
  capture_failure_diagnostics "assert-$(basename "$file" .properties)" "missing_any_expected:$*" || true
  exit 1
}

observe_file="$(broadcast_command observe --es command observe)"
assert_file_contains "$observe_file" "resultType=available"
assert_file_contains "$observe_file" "Solin Device Control Eval"
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

launch_eval_profile() {
  local profile="$1"
  "${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" --es profile "$profile" >/dev/null
  sleep 1
}

run_app_search_case() {
  local profile="$1"
  local app_name="$2"
  local query="$3"
  local tap_target="$4"
  local type_target="$5"
  local title_text="$6"
  local result_hint="$7"
  local supports_filter="${8:-0}"
  local observe_file tap_file type_file submit_file verify_file filter_file

  launch_eval_profile "$profile"

  observe_file="$(broadcast_command "search-${profile}-observe" --es command observe)"
  assert_file_contains "$observe_file" "resultType=available"
  assert_file_contains "$observe_file" "$title_text"
  assert_file_contains "$observe_file" "EvalSearchInput"

  tap_file="$(broadcast_command "search-${profile}-tap" --es command tap --es target "$tap_target" --el timeoutMillis 1500)"
  assert_file_contains "$tap_file" "status=Succeeded"
  assert_file_contains "$tap_file" "EvalSearchInput"

  type_file="$(broadcast_command "search-${profile}-type" --es command type_text --es target "$type_target" --es text "$query" --el timeoutMillis 1500)"
  assert_file_contains "$type_file" "status=Succeeded"
  assert_file_contains "$type_file" "$query"

  submit_file="$(broadcast_command "search-${profile}-submit" --es command submit_search --el timeoutMillis 1500)"
  assert_file_contains "$submit_file" "status=Succeeded"
  assert_file_contains_any "$submit_file" "已提交当前搜索输入" "已点击搜索提交入口"
  assert_file_contains "$submit_file" "$query"
  assert_file_contains "$submit_file" "$result_hint"

  verify_file="$(broadcast_command "search-${profile}-verify" --es command wait --es verifySearchQuery "$query" --es expectedAppName "$app_name" --el timeoutMillis 500)"
  assert_file_contains "$verify_file" "status=Succeeded"
  assert_file_contains "$verify_file" "searchVerificationStatus=verified"
  assert_file_contains_any "$verify_file" \
    "searchVerificationEvidence=query_visible_with_result_hint" \
    "searchVerificationEvidence=result_hints_visible"

  if [[ "$supports_filter" == "1" ]]; then
    filter_file="$(broadcast_command "search-${profile}-filter" --es command tap --es target "筛选" --el timeoutMillis 1500)"
    assert_file_contains "$filter_file" "status=Succeeded"
    assert_file_contains "$filter_file" "Filter applied"
  fi
}

run_app_search_case taobao "淘宝" "海河牛奶" "搜索入口" "搜索输入框" "Solin Device Control Eval - 淘宝" "综合" 1
run_app_search_case pdd "拼多多" "纸巾" "搜索入口" "搜索输入框" "Solin Device Control Eval - 拼多多" "百亿补贴" 1
run_app_search_case gaode "高德" "机场" "搜索入口" "搜索输入框" "Solin Device Control Eval - 高德地图" "路线" 0
run_app_search_case browser "浏览器" "Kotlin协程" "地址栏" "地址栏" "Solin Device Control Eval - 浏览器" "搜索结果" 0

echo "Device control debug eval passed."
