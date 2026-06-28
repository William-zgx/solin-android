#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
ADB_BIN="${ANDROID_SDK}/platform-tools/adb"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/real-app-search-eval-$(date +%Y%m%d-%H%M%S)}"
REPORT_FILE="${REPORT_FILE:-${ARTIFACT_DIR}/real-app-search-eval.properties}"
LOGCAT_FILE="${LOGCAT_FILE:-${ARTIFACT_DIR}/logcat.txt}"
DIAGNOSTICS_DIR="${DIAGNOSTICS_DIR:-${ARTIFACT_DIR}/diagnostics}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SKIP_INSTALL="${SKIP_INSTALL:-0}"
FORCE_STOP_TARGET_APP="${FORCE_STOP_TARGET_APP:-1}"
DEFER_DEVICE_TESTS="${DEFER_DEVICE_TESTS:-0}"
DEFERRED_REASON="${DEFERRED_REASON:-}"
STATUS_OVERRIDE="${STATUS_OVERRIDE:-}"
AUTO_ENABLE_SOLIN_ACCESSIBILITY="${AUTO_ENABLE_SOLIN_ACCESSIBILITY:-1}"
REAL_APP_ACTION_TIMEOUT_MS="${REAL_APP_ACTION_TIMEOUT_MS:-8000}"
REAL_APP_VERIFY_TIMEOUT_MS="${REAL_APP_VERIFY_TIMEOUT_MS:-4000}"
REAL_APP_LAUNCH_OBSTRUCTION_RECOVERY="${REAL_APP_LAUNCH_OBSTRUCTION_RECOVERY:-1}"
REAL_APP_LAUNCH_OBSTRUCTION_WAIT_SECONDS="${REAL_APP_LAUNCH_OBSTRUCTION_WAIT_SECONDS:-4}"
REAL_APP_BACKGROUND_PACKAGES_TO_STOP="${REAL_APP_BACKGROUND_PACKAGES_TO_STOP:-}"
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
RUN_COUNT=0
PASS_COUNT=0
SKIP_COUNT=0
FAIL_COUNT=0
REQUEST_COUNT=0
CONTROL_SESSION_ACTIVE=0
LAST_DIAGNOSTICS_DIR=""

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

read_result_property() {
  local source_file="$1"
  local source_key="$2"
  grep -m 1 "^${source_key}=" "$source_file" 2>/dev/null | cut -d= -f2- | tr -d '\r\n' || true
}

copy_result_property() {
  local source_file="$1"
  local source_key="$2"
  local target_key="$3"
  local value
  value="$(read_result_property "$source_file" "$source_key")"
  echo "${target_key}=$value"
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
  LAST_DIAGNOSTICS_DIR="$diag_dir"
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
  } > "${diag_dir}/focused-window.txt"
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell dumpsys window windows 2>/dev/null |
    tr -d '\r' > "${diag_dir}/window-dump.txt" || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -d -t 1000 > "${diag_dir}/logcat.txt" 2>/dev/null || true
  {
    echo "screenshot_file=${diag_dir}/screenshot.png"
    echo "screenshot_sha256=$(sha256_file "${diag_dir}/screenshot.png")"
    echo "uiautomator_dump_file=${diag_dir}/uiautomator.xml"
    echo "uiautomator_dump_sha256=$(sha256_file "${diag_dir}/uiautomator.xml")"
    echo "focused_window_file=${diag_dir}/focused-window.txt"
    echo "focused_window_sha256=$(sha256_file "${diag_dir}/focused-window.txt")"
    echo "window_dump_file=${diag_dir}/window-dump.txt"
    echo "window_dump_sha256=$(sha256_file "${diag_dir}/window-dump.txt")"
    echo "logcat_file=${diag_dir}/logcat.txt"
    echo "logcat_sha256=$(sha256_file "${diag_dir}/logcat.txt")"
  } >> "${diag_dir}/diagnostics.properties"
  echo "Failure diagnostics: ${diag_dir}" >&2
}

write_failure_diagnostics_report_fields() {
  echo "failure_diagnostics_dir=$LAST_DIAGNOSTICS_DIR"
  if [[ -n "$LAST_DIAGNOSTICS_DIR" && -f "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" ]]; then
    copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "screenshot_file" "failure_screenshot_file"
    copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "screenshot_sha256" "failure_screenshot_sha256"
    copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "uiautomator_dump_file" "failure_uiautomator_dump_file"
    copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "uiautomator_dump_sha256" "failure_uiautomator_dump_sha256"
    copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "focused_window_file" "failure_focused_window_file"
    copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "focused_window_sha256" "failure_focused_window_sha256"
    copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "window_dump_file" "failure_window_dump_file"
    copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "window_dump_sha256" "failure_window_dump_sha256"
    copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "logcat_file" "failure_logcat_file"
    copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "logcat_sha256" "failure_logcat_sha256"
  fi
}

safe_property_suffix() {
  local value="$1"
  value="$(printf '%s' "$value" | LC_ALL=C tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9' '_' | sed 's/_*$//')"
  [[ -n "$value" ]] || value="unknown"
  printf '%s' "$value"
}

write_step_target_resolution_fields() {
  local case_name="$1"
  local step_name="$2"
  local result_file="$3"
  local safe_step result_file_sha available kind target package_name selected_node_id failure_kind
  local candidate_count candidate_total_count archived_candidate_count candidates_json
  local ranked_candidates_file ranked_candidates_sha target_resolution_evidence_file target_resolution_evidence_sha
  safe_step="$(safe_property_suffix "$step_name")"
  result_file_sha=""
  available="false"
  kind=""
  target=""
  package_name=""
  selected_node_id=""
  failure_kind=""
  candidate_count="0"
  candidate_total_count="0"
  archived_candidate_count="0"
  candidates_json=""
  ranked_candidates_file=""
  ranked_candidates_sha=""
  target_resolution_evidence_file=""
  target_resolution_evidence_sha=""

  if [[ -n "$result_file" && -f "$result_file" ]]; then
    result_file_sha="$(sha256_file "$result_file")"
    available="$(read_result_property "$result_file" "targetResolution.available")"
    kind="$(read_result_property "$result_file" "targetResolution.kind")"
    target="$(read_result_property "$result_file" "targetResolution.target")"
    package_name="$(read_result_property "$result_file" "targetResolution.packageName")"
    selected_node_id="$(read_result_property "$result_file" "targetResolution.selectedNodeId")"
    failure_kind="$(read_result_property "$result_file" "targetResolution.failureKind")"
    candidate_count="$(read_result_property "$result_file" "targetResolution.candidateCount")"
    candidate_total_count="$(read_result_property "$result_file" "targetResolution.candidateTotalCount")"
    archived_candidate_count="$(read_result_property "$result_file" "targetResolution.archivedCandidateCount")"
    candidates_json="$(read_result_property "$result_file" "targetResolution.candidatesJson")"
    if [[ -n "$candidates_json" ]]; then
      ranked_candidates_file="${ARTIFACT_DIR}/${case_name}.${safe_step}.ranked-candidates.json"
      printf '%s\n' "$candidates_json" > "$ranked_candidates_file"
      ranked_candidates_sha="$(sha256_file "$ranked_candidates_file")"
    fi
    if [[ "${available:-false}" == "true" ]]; then
      target_resolution_evidence_file="${ARTIFACT_DIR}/${case_name}.${safe_step}.target-resolution.properties"
      {
        echo "artifact_schema=UiTargetResolutionStepEvidenceArtifact/v1"
        echo "case=$case_name"
        echo "step=$step_name"
        echo "result_file=$result_file"
        echo "result_file_sha256=$result_file_sha"
        echo "target_resolution_available=${available:-false}"
        echo "target_resolution_kind=$kind"
        echo "target_resolution_target=$target"
        echo "target_resolution_package_name=$package_name"
        echo "target_resolution_selected_node_id=$selected_node_id"
        echo "target_resolution_failure_kind=$failure_kind"
        echo "target_resolution_candidate_count=${candidate_count:-0}"
        echo "target_resolution_candidate_total_count=${candidate_total_count:-${candidate_count:-0}}"
        echo "target_resolution_archived_candidate_count=${archived_candidate_count:-${candidate_count:-0}}"
        echo "ranked_candidates_file=$ranked_candidates_file"
        echo "ranked_candidates_sha256=$ranked_candidates_sha"
      } > "$target_resolution_evidence_file"
      target_resolution_evidence_sha="$(sha256_file "$target_resolution_evidence_file")"
    fi
  fi

  echo "step_${safe_step}_result_file=$result_file"
  echo "step_${safe_step}_result_file_sha256=$result_file_sha"
  echo "step_${safe_step}_target_resolution_available=${available:-false}"
  echo "step_${safe_step}_target_resolution_kind=$kind"
  echo "step_${safe_step}_target_resolution_target=$target"
  echo "step_${safe_step}_target_resolution_package_name=$package_name"
  echo "step_${safe_step}_target_resolution_selected_node_id=$selected_node_id"
  echo "step_${safe_step}_target_resolution_failure_kind=$failure_kind"
  echo "step_${safe_step}_target_resolution_candidate_count=${candidate_count:-0}"
  echo "step_${safe_step}_target_resolution_candidate_total_count=${candidate_total_count:-${candidate_count:-0}}"
  echo "step_${safe_step}_target_resolution_archived_candidate_count=${archived_candidate_count:-${candidate_count:-0}}"
  echo "step_${safe_step}_target_resolution_evidence_file=$target_resolution_evidence_file"
  echo "step_${safe_step}_target_resolution_evidence_sha256=$target_resolution_evidence_sha"
  echo "step_${safe_step}_ranked_candidates_file=$ranked_candidates_file"
  echo "step_${safe_step}_ranked_candidates_sha256=$ranked_candidates_sha"
}

write_report() {
  local exit_code="$1"
  local artifact_id logcat_sha256
  if [[ -n "$STATUS_OVERRIDE" ]]; then
    STATUS="$STATUS_OVERRIDE"
  elif [[ "$exit_code" -eq 0 ]]; then
    STATUS="passed"
  fi
  artifact_id="real-app-search-${SELECTED_SERIAL:-unselected}-api${API_LEVEL:-unknown}-${STARTED_AT_UTC}"
  artifact_id="${artifact_id//:/}"
  logcat_sha256="$(sha256_file "$LOGCAT_FILE")"
  {
    echo "artifact_schema=RealAppSearchEvalArtifact/v1"
    echo "artifact_id=$artifact_id"
    echo "status=$STATUS"
    echo "exit_code=$exit_code"
    echo "target=real-app-search-eval"
    echo "failedTarget=${FAILED_TARGET:-}"
    echo "reason=$FAILURE_REASON"
    echo "deferredReason=$DEFERRED_REASON"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=$SELECTED_SERIAL"
    echo "api_level=$API_LEVEL"
    echo "abi=$ABI_LIST"
    echo "skip_build=$SKIP_BUILD"
    echo "skip_install=$SKIP_INSTALL"
    echo "force_stop_target_app=$FORCE_STOP_TARGET_APP"
    echo "background_packages_stopped=$REAL_APP_BACKGROUND_PACKAGES_TO_STOP"
    echo "run_count=$RUN_COUNT"
    echo "pass_count=$PASS_COUNT"
    echo "skip_count=$SKIP_COUNT"
    echo "fail_count=$FAIL_COUNT"
    echo "debug_apk=$DEBUG_APK"
    echo "logcat_file=$LOGCAT_FILE"
    echo "logcat_sha256=$logcat_sha256"
    echo "diagnostics_dir=$DIAGNOSTICS_DIR"
    write_failure_diagnostics_report_fields
    echo "result_file_pattern=${RESULT_FILE_PREFIX}<requestId>${RESULT_FILE_SUFFIX}"
    echo "case_artifact_schema=RealAppSearchCaseArtifact/v1"
    echo "cases=taobao,pdd,gaode,jd,chrome,android_browser,quark,uc"
  } > "$REPORT_FILE"
  echo "Real app search eval report: $REPORT_FILE"
}

on_exit() {
  local status="$?"
  trap - EXIT
  if [[ -n "$SELECTED_SERIAL" && -x "$ADB_BIN" ]]; then
    if [[ "$CONTROL_SESSION_ACTIVE" == "1" ]]; then
      "$ADB_BIN" -s "$SELECTED_SERIAL" shell run-as "$PACKAGE_NAME" am broadcast --user 0 \
        -n "$RECEIVER_NAME" -a "$ACTION_NAME" --es command stop_control_session >/dev/null 2>&1 || true
    fi
    "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -d -t 500 > "$LOGCAT_FILE" 2>/dev/null || true
  fi
  write_report "$status"
  exit "$status"
}

trap on_exit EXIT

if [[ "$DEFER_DEVICE_TESTS" == "1" ]]; then
  trap - EXIT
  DEFERRED_REASON="${DEFERRED_REASON:-no-device-test-in-this-phase}"
  STATUS_OVERRIDE="skipped"
  FAILED_TARGET="real-app-search-eval"
  FAILURE_REASON="$DEFERRED_REASON"
  write_report 0
  exit 0
fi

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

ensure_solin_accessibility_for_eval() {
  if solin_accessibility_enabled; then
    return 0
  fi
  if [[ "$AUTO_ENABLE_SOLIN_ACCESSIBILITY" == "1" ]]; then
    echo "Solin Accessibility is not enabled; enabling it through adb secure settings for real-app eval."
    enable_solin_accessibility_for_eval
  fi
  if ! solin_accessibility_enabled; then
    return 1
  fi
}

if ! ensure_solin_accessibility_for_eval; then
  fail_with_reason accessibility solin-accessibility-not-enabled \
    "Solin Accessibility is not enabled. Enable it in system Accessibility settings, then rerun with SKIP_INSTALL=1."
fi

"${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" >/dev/null
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
  REQUEST_COUNT=$((REQUEST_COUNT + 1))
  request_id="${name}-${REQUEST_COUNT}-$$-$(date +%s)"
  output_file="$(result_file_for "$name")"
  rm -f "$output_file"
  remove_device_result "$request_id"
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" am broadcast --user 0 \
    -n "$RECEIVER_NAME" -a "$ACTION_NAME" --es requestId "$request_id" "$@" >/dev/null
  for _ in {1..60}; do
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
  capture_failure_diagnostics "${name}-timeout" "timeout_waiting_for_request:${request_id}" || true
  return 1
}

assert_file_contains() {
  local file="$1"
  local expected="$2"
  if ! grep -Fq "$expected" "$file"; then
    echo "Expected $file to contain: $expected" >&2
    cat "$file" >&2
    capture_failure_diagnostics "assert-$(basename "$file" .properties)" "missing_expected:${expected}" || true
    return 1
  fi
}

focused_window_matches_package() {
  local package_name="$1"
  "${ADB[@]}" shell dumpsys window 2>/dev/null |
    tr -d '\r' |
    grep -E "mCurrentFocus=.*${package_name}|mFocusedApp=.*${package_name}|mFocusedWindow=.*${package_name}" >/dev/null
}

tap_top_right_skip_affordance() {
  local size width height x y
  size="$(
    "${ADB[@]}" shell wm size 2>/dev/null |
      tr -d '\r' |
      awk -F'[: x]+' '/Override size|Physical size/ {width = $3; height = $4} END {if (width && height) print width " " height}'
  )"
  width="${size%% *}"
  height="${size##* }"
  if [[ ! "$width" =~ ^[0-9]+$ || ! "$height" =~ ^[0-9]+$ ]]; then
    width=1080
    height=2400
  fi
  x=$((width * 85 / 100))
  y=$((height * 5 / 100))
  "${ADB[@]}" shell input tap "$x" "$y" >/dev/null 2>&1 || true
}

recover_obscured_launch_observation() {
  local output_var="$1"
  local case_name="$2"
  local package_name="$3"
  local observe_file="$4"
  local current_package retry_file
  current_package="$(read_result_property "$observe_file" "packageName")"
  printf -v "$output_var" '%s' "$observe_file"
  if [[ "$current_package" == "$package_name" ]]; then
    return 0
  fi
  if [[ "$REAL_APP_LAUNCH_OBSTRUCTION_RECOVERY" != "1" ]]; then
    return 1
  fi
  if ! focused_window_matches_package "$package_name"; then
    return 1
  fi

  echo "Recovering $case_name launch obstruction: observed $current_package while focus belongs to $package_name"
  sleep "$REAL_APP_LAUNCH_OBSTRUCTION_WAIT_SECONDS"
  case_broadcast_command retry_file "$case_name" "observe_retry_timeout" \
    "${case_name}-observe-launch-wait" --es command observe || return 1
  printf -v "$output_var" '%s' "$retry_file"
  if grep -Fq "packageName=$package_name" "$retry_file"; then
    return 0
  fi

  tap_top_right_skip_affordance
  sleep 1
  case_broadcast_command retry_file "$case_name" "observe_retry_timeout" \
    "${case_name}-observe-launch-skip" --es command observe || return 1
  printf -v "$output_var" '%s' "$retry_file"
  if grep -Fq "packageName=$package_name" "$retry_file"; then
    return 0
  fi

  "${ADB[@]}" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  sleep 1
  case_broadcast_command retry_file "$case_name" "observe_retry_timeout" \
    "${case_name}-observe-launch-back" --es command observe || return 1
  printf -v "$output_var" '%s' "$retry_file"
  grep -Fq "packageName=$package_name" "$retry_file"
}

case_broadcast_command() {
  local output_var="$1"
  local case_name="$2"
  local failure_reason="$3"
  local command_name output_file
  shift 3
  command_name="${1:-unknown}"
  if ! output_file="$(broadcast_command "$@")"; then
    FAIL_COUNT=$((FAIL_COUNT + 1))
    write_case_result "$case_name" "failed" "$failure_reason" "$command_name" ""
    return 1
  fi
  printf -v "$output_var" '%s' "$output_file"
}

start_control_session_for_case() {
  local case_name="$1"
  local app_name="$2"
  local session_file

  "${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" >/dev/null
  sleep 0.5
  case_broadcast_command session_file "$case_name" "control_session_timeout" \
    "${case_name}-session" --es command start_control_session --es text "正在准备在${app_name}中搜索" || return 1
  assert_file_contains "$session_file" "status=Succeeded" || return 1
  CONTROL_SESSION_ACTIVE=1
}

stop_control_session() {
  if [[ "$CONTROL_SESSION_ACTIVE" != "1" ]]; then
    return 0
  fi
  "${ADB[@]}" shell run-as "$PACKAGE_NAME" am broadcast --user 0 \
    -n "$RECEIVER_NAME" -a "$ACTION_NAME" --es command stop_control_session >/dev/null 2>&1 || true
  CONTROL_SESSION_ACTIVE=0
}

write_case_result() {
  local case_name="$1"
  local status="$2"
  local reason="$3"
  local failed_step="${4:-}"
  local result_file="${5:-}"
  local step_result_specs=("${@:6}")
  local file="${ARTIFACT_DIR}/${case_name}.case.properties"
  local expected_package expected_app result_file_sha failure_kind
  local target_resolution_available target_resolution_kind target_resolution_target
  local target_resolution_package_name target_resolution_selected_node_id
  local target_resolution_failure_kind target_resolution_candidate_count
  local target_resolution_candidate_total_count target_resolution_archived_candidate_count
  local target_resolution_candidates_json ranked_candidates_file ranked_candidates_sha
  local target_resolution_evidence_file target_resolution_evidence_sha
  expected_package="$(case_expected_package "$case_name")"
  expected_app="$(case_expected_app_name "$case_name")"
  result_file_sha=""
  failure_kind="$reason"
  target_resolution_available="false"
  target_resolution_kind=""
  target_resolution_target=""
  target_resolution_package_name=""
  target_resolution_selected_node_id=""
  target_resolution_failure_kind=""
  target_resolution_candidate_count="0"
  target_resolution_candidate_total_count="0"
  target_resolution_archived_candidate_count="0"
  target_resolution_candidates_json=""
  ranked_candidates_file=""
  ranked_candidates_sha=""
  target_resolution_evidence_file=""
  target_resolution_evidence_sha=""

  if [[ -n "$result_file" && -f "$result_file" ]]; then
    result_file_sha="$(sha256_file "$result_file")"
    target_resolution_available="$(read_result_property "$result_file" "targetResolution.available")"
    target_resolution_kind="$(read_result_property "$result_file" "targetResolution.kind")"
    target_resolution_target="$(read_result_property "$result_file" "targetResolution.target")"
    target_resolution_package_name="$(read_result_property "$result_file" "targetResolution.packageName")"
    target_resolution_selected_node_id="$(read_result_property "$result_file" "targetResolution.selectedNodeId")"
    target_resolution_failure_kind="$(read_result_property "$result_file" "targetResolution.failureKind")"
    target_resolution_candidate_count="$(read_result_property "$result_file" "targetResolution.candidateCount")"
    target_resolution_candidate_total_count="$(read_result_property "$result_file" "targetResolution.candidateTotalCount")"
    target_resolution_archived_candidate_count="$(read_result_property "$result_file" "targetResolution.archivedCandidateCount")"
    target_resolution_candidates_json="$(read_result_property "$result_file" "targetResolution.candidatesJson")"
    failure_kind="${target_resolution_failure_kind:-$(read_result_property "$result_file" "failureKind")}"
    failure_kind="${failure_kind:-$reason}"
    if [[ -n "$target_resolution_candidates_json" ]]; then
      ranked_candidates_file="${ARTIFACT_DIR}/${case_name}.ranked-candidates.json"
      printf '%s\n' "$target_resolution_candidates_json" > "$ranked_candidates_file"
      ranked_candidates_sha="$(sha256_file "$ranked_candidates_file")"
    fi
    if [[ "$target_resolution_available" == "true" ]]; then
      target_resolution_evidence_file="${ARTIFACT_DIR}/${case_name}.target-resolution.properties"
      {
        echo "artifact_schema=UiTargetResolutionEvidenceArtifact/v1"
        echo "case=$case_name"
        echo "result_file=$result_file"
        echo "result_file_sha256=$result_file_sha"
        echo "target_resolution_available=$target_resolution_available"
        echo "target_resolution_kind=$target_resolution_kind"
        echo "target_resolution_target=$target_resolution_target"
        echo "target_resolution_package_name=$target_resolution_package_name"
        echo "target_resolution_selected_node_id=$target_resolution_selected_node_id"
        echo "target_resolution_failure_kind=$target_resolution_failure_kind"
        echo "target_resolution_candidate_count=${target_resolution_candidate_count:-0}"
        echo "target_resolution_candidate_total_count=${target_resolution_candidate_total_count:-${target_resolution_candidate_count:-0}}"
        echo "target_resolution_archived_candidate_count=${target_resolution_archived_candidate_count:-${target_resolution_candidate_count:-0}}"
        echo "ranked_candidates_file=$ranked_candidates_file"
        echo "ranked_candidates_sha256=$ranked_candidates_sha"
      } > "$target_resolution_evidence_file"
      target_resolution_evidence_sha="$(sha256_file "$target_resolution_evidence_file")"
    fi
  fi
  target_resolution_available="${target_resolution_available:-false}"
  target_resolution_candidate_count="${target_resolution_candidate_count:-0}"
  target_resolution_candidate_total_count="${target_resolution_candidate_total_count:-$target_resolution_candidate_count}"
  target_resolution_archived_candidate_count="${target_resolution_archived_candidate_count:-$target_resolution_candidate_count}"

  {
    echo "artifact_schema=RealAppSearchCaseArtifact/v1"
    echo "case=$case_name"
    echo "expected_package_name=$expected_package"
    echo "expected_app_name=$expected_app"
    echo "status=$status"
    echo "reason=$reason"
    echo "failure_kind=$failure_kind"
    echo "failed_step=$failed_step"
    echo "result_file=$result_file"
    echo "result_file_sha256=$result_file_sha"
    echo "target_resolution_available=$target_resolution_available"
    echo "target_resolution_kind=$target_resolution_kind"
    echo "target_resolution_target=$target_resolution_target"
    echo "target_resolution_package_name=$target_resolution_package_name"
    echo "target_resolution_selected_node_id=$target_resolution_selected_node_id"
    echo "target_resolution_failure_kind=$target_resolution_failure_kind"
    echo "target_resolution_candidate_count=${target_resolution_candidate_count:-0}"
    echo "target_resolution_candidate_total_count=${target_resolution_candidate_total_count:-${target_resolution_candidate_count:-0}}"
    echo "target_resolution_archived_candidate_count=${target_resolution_archived_candidate_count:-${target_resolution_candidate_count:-0}}"
    echo "target_resolution_candidates_json=$target_resolution_candidates_json"
    echo "target_resolution_evidence_file=$target_resolution_evidence_file"
    echo "target_resolution_evidence_sha256=$target_resolution_evidence_sha"
    echo "ranked_candidates_file=$ranked_candidates_file"
    echo "ranked_candidates_sha256=$ranked_candidates_sha"
    echo "step_evidence_count=${#step_result_specs[@]}"
    local spec step_name step_result_file
    if [[ "${#step_result_specs[@]}" -gt 0 ]]; then
      for spec in "${step_result_specs[@]}"; do
        step_name="${spec%%=*}"
        step_result_file="${spec#*=}"
        write_step_target_resolution_fields "$case_name" "$step_name" "$step_result_file"
      done
    fi
    echo "diagnostics_dir=$LAST_DIAGNOSTICS_DIR"
    if [[ -n "$LAST_DIAGNOSTICS_DIR" && -f "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" ]]; then
      copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "screenshot_file" "screenshot_file"
      copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "screenshot_sha256" "screenshot_sha256"
      copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "uiautomator_dump_file" "uiautomator_dump_file"
      copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "uiautomator_dump_sha256" "uiautomator_dump_sha256"
      copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "focused_window_file" "focused_window_file"
      copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "focused_window_sha256" "focused_window_sha256"
      copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "window_dump_file" "window_dump_file"
      copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "window_dump_sha256" "window_dump_sha256"
      copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "logcat_file" "case_logcat_file"
      copy_result_property "${LAST_DIAGNOSTICS_DIR}/diagnostics.properties" "logcat_sha256" "case_logcat_sha256"
    fi
  } > "$file"
}

case_expected_package() {
  case "$1" in
    taobao) echo "com.taobao.taobao" ;;
    pdd) echo "com.xunmeng.pinduoduo" ;;
    gaode) echo "com.autonavi.minimap" ;;
    jd) echo "com.jingdong.app.mall" ;;
    chrome) echo "com.android.chrome" ;;
    android_browser) echo "com.android.browser" ;;
    quark) echo "com.quark.browser" ;;
    uc) echo "com.UCMobile" ;;
    *) echo "" ;;
  esac
}

case_expected_app_name() {
  case "$1" in
    taobao) echo "淘宝" ;;
    pdd) echo "拼多多" ;;
    gaode) echo "高德" ;;
    jd) echo "京东" ;;
    chrome | android_browser) echo "浏览器" ;;
    quark) echo "夸克" ;;
    uc) echo "UC浏览器" ;;
    *) echo "" ;;
  esac
}

case_launch_ready_hint() {
  local case_name="$1"
  local tap_target="$2"
  case "$case_name" in
    uc) echo "搜索框" ;;
    *) echo "$tap_target" ;;
  esac
}

case_launch_recovery_target() {
  case "$1" in
    jd) echo "首页" ;;
    uc) echo "首页" ;;
    *) echo "" ;;
  esac
}

package_installed() {
  local package_name="$1"
  "${ADB[@]}" shell cmd package path "$package_name" >/dev/null 2>&1
}

launch_package() {
  local package_name="$1"
  local component
  component="$("${ADB[@]}" shell cmd package resolve-activity --brief "$package_name" 2>/dev/null |
    tr -d '\r' |
    awk '/\// {line = $0} END {print line}')"
  if [[ -n "$component" ]]; then
    "${ADB[@]}" shell am start -W -n "$component" >/dev/null
  else
    "${ADB[@]}" shell monkey -p "$package_name" -c android.intent.category.LAUNCHER 1 >/dev/null
  fi
  sleep 2
}

force_stop_target_app() {
  local package_name="$1"
  if [[ "$FORCE_STOP_TARGET_APP" != "1" ]]; then
    return 0
  fi
  echo "Force-stopping target app before case: $package_name"
  "${ADB[@]}" shell am force-stop "$package_name" >/dev/null 2>&1 || true
  sleep 0.5
}

stop_background_packages_preserving_data() {
  local target_package="$1"
  local package_name
  if [[ -z "$REAL_APP_BACKGROUND_PACKAGES_TO_STOP" ]]; then
    return 0
  fi
  "${ADB[@]}" shell cmd statusbar collapse >/dev/null 2>&1 || true
  for package_name in $REAL_APP_BACKGROUND_PACKAGES_TO_STOP; do
    if [[ "$package_name" == "$target_package" || "$package_name" == "$PACKAGE_NAME" ]]; then
      continue
    fi
    "${ADB[@]}" shell am force-stop "$package_name" >/dev/null 2>&1 || true
  done
  sleep 0.5
}

run_case() {
  local case_name="$1"
  local package_name="$2"
  local app_name="$3"
  local query="$4"
  local tap_target="$5"
  local type_target="$6"
  local required_hint="$7"
  local observe_file tap_file type_file submit_file verify_file ready_hint recovery_target recovery_file

  if ! package_installed "$package_name"; then
    echo "Skipping $case_name: package not installed ($package_name)"
    SKIP_COUNT=$((SKIP_COUNT + 1))
    LAST_DIAGNOSTICS_DIR=""
    write_case_result "$case_name" "skipped" "package_not_installed:$package_name" "package_check" ""
    return 0
  fi

  RUN_COUNT=$((RUN_COUNT + 1))
  LAST_DIAGNOSTICS_DIR=""
  echo "Running $case_name on $package_name"
  if ! ensure_solin_accessibility_for_eval; then
    FAIL_COUNT=$((FAIL_COUNT + 1))
    write_case_result "$case_name" "failed" "solin_accessibility_not_enabled" "accessibility" ""
    return 1
  fi
  force_stop_target_app "$package_name"
  stop_background_packages_preserving_data "$package_name"
  if ! start_control_session_for_case "$case_name" "$app_name"; then
    FAIL_COUNT=$((FAIL_COUNT + 1))
    write_case_result "$case_name" "failed" "control_session_failed" "start_control_session" ""
    return 1
  fi
  launch_package "$package_name"

  case_broadcast_command observe_file "$case_name" "observe_timeout" \
    "${case_name}-observe" --es command observe || return 1
  assert_file_contains "$observe_file" "resultType=available" || {
    FAIL_COUNT=$((FAIL_COUNT + 1))
    write_case_result "$case_name" "failed" "observe_failed" "observe" "$observe_file"
    return 1
  }
  if ! grep -Fq "packageName=$package_name" "$observe_file"; then
    recover_obscured_launch_observation observe_file "$case_name" "$package_name" "$observe_file" || true
    assert_file_contains "$observe_file" "resultType=available" || {
      FAIL_COUNT=$((FAIL_COUNT + 1))
      write_case_result "$case_name" "failed" "observe_failed_after_launch_recovery" "observe" "$observe_file"
      return 1
    }
  fi
  assert_file_contains "$observe_file" "packageName=$package_name" || {
    FAIL_COUNT=$((FAIL_COUNT + 1))
    write_case_result "$case_name" "failed" "app_not_foreground" "observe" "$observe_file"
    return 1
  }
  ready_hint="$(case_launch_ready_hint "$case_name" "$tap_target")"
  recovery_target="$(case_launch_recovery_target "$case_name")"
  if [[ -n "$ready_hint" && -n "$recovery_target" ]] && ! grep -Fq "$ready_hint" "$observe_file"; then
    echo "Recovering $case_name launch surface via $recovery_target before search entry tap"
    case_broadcast_command recovery_file "$case_name" "launch_recovery_timeout" \
      "${case_name}-launch-recovery" --es command tap --es target "$recovery_target" --el timeoutMillis "$REAL_APP_ACTION_TIMEOUT_MS" || true
    sleep 2
    case_broadcast_command observe_file "$case_name" "observe_retry_timeout" \
      "${case_name}-observe-retry" --es command observe || return 1
    if ! grep -Fq "packageName=$package_name" "$observe_file"; then
      recover_obscured_launch_observation observe_file "$case_name" "$package_name" "$observe_file" || true
    fi
    assert_file_contains "$observe_file" "resultType=available" || {
      FAIL_COUNT=$((FAIL_COUNT + 1))
      write_case_result "$case_name" "failed" "observe_failed_after_launch_recovery" "observe" "$observe_file"
      return 1
    }
    assert_file_contains "$observe_file" "packageName=$package_name" || {
      FAIL_COUNT=$((FAIL_COUNT + 1))
      write_case_result "$case_name" "failed" "app_not_foreground_after_launch_recovery" "observe" "$observe_file"
      return 1
    }
  fi

  case_broadcast_command tap_file "$case_name" "tap_timeout" \
    "${case_name}-tap" --es command tap --es target "$tap_target" --el timeoutMillis "$REAL_APP_ACTION_TIMEOUT_MS" || return 1
  assert_file_contains "$tap_file" "status=Succeeded" || {
    FAIL_COUNT=$((FAIL_COUNT + 1))
    write_case_result "$case_name" "failed" "search_entry_not_found" "tap" "$tap_file" \
      "tap=$tap_file"
    return 1
  }

  case_broadcast_command type_file "$case_name" "type_timeout" \
    "${case_name}-type" --es command type_text --es target "$type_target" --es text "$query" --el timeoutMillis "$REAL_APP_ACTION_TIMEOUT_MS" || return 1
  assert_file_contains "$type_file" "status=Succeeded" || {
    FAIL_COUNT=$((FAIL_COUNT + 1))
    write_case_result "$case_name" "failed" "editable_not_found" "type_text" "$type_file" \
      "tap=$tap_file" "type_text=$type_file"
    return 1
  }

  stop_background_packages_preserving_data "$package_name"
  case_broadcast_command submit_file "$case_name" "submit_timeout" \
    "${case_name}-submit" --es command submit_search --el timeoutMillis "$REAL_APP_ACTION_TIMEOUT_MS" || return 1
  assert_file_contains "$submit_file" "status=Succeeded" || {
    FAIL_COUNT=$((FAIL_COUNT + 1))
    write_case_result "$case_name" "failed" "submit_not_found" "submit_search" "$submit_file" \
      "tap=$tap_file" "type_text=$type_file" "submit_search=$submit_file"
    return 1
  }

  stop_background_packages_preserving_data "$package_name"
  case_broadcast_command verify_file "$case_name" "verify_timeout" \
    "${case_name}-verify" --es command wait --es verifySearchQuery "$query" --es expectedPackageName "$package_name" --es expectedAppName "$app_name" --el timeoutMillis "$REAL_APP_VERIFY_TIMEOUT_MS" || return 1
  assert_file_contains "$verify_file" "status=Succeeded" || {
    FAIL_COUNT=$((FAIL_COUNT + 1))
    write_case_result "$case_name" "failed" "result_not_verified" "verify" "$verify_file" \
      "tap=$tap_file" "type_text=$type_file" "submit_search=$submit_file" "verify=$verify_file"
    return 1
  }
  assert_file_contains "$verify_file" "searchVerificationStatus=verified" || {
    FAIL_COUNT=$((FAIL_COUNT + 1))
    write_case_result "$case_name" "failed" "result_not_verified" "verify" "$verify_file" \
      "tap=$tap_file" "type_text=$type_file" "submit_search=$submit_file" "verify=$verify_file"
    return 1
  }
  if [[ -n "$required_hint" ]]; then
    assert_file_contains "$verify_file" "$required_hint" || {
      FAIL_COUNT=$((FAIL_COUNT + 1))
      write_case_result "$case_name" "failed" "required_hint_missing" "verify" "$verify_file" \
        "tap=$tap_file" "type_text=$type_file" "submit_search=$submit_file" "verify=$verify_file"
      return 1
    }
  fi

  PASS_COUNT=$((PASS_COUNT + 1))
  LAST_DIAGNOSTICS_DIR=""
  write_case_result "$case_name" "passed" "" "" "$verify_file" \
    "tap=$tap_file" "type_text=$type_file" "submit_search=$submit_file" "verify=$verify_file"
  stop_control_session
}

overall_status=0
run_case taobao com.taobao.taobao "淘宝" "海河牛奶" "搜索入口" "搜索输入框" "筛选" || overall_status=1
run_case pdd com.xunmeng.pinduoduo "拼多多" "纸巾" "搜索入口" "搜索输入框" "筛选" || overall_status=1
run_case gaode com.autonavi.minimap "高德" "机场" "搜索入口" "搜索输入框" "查看地图" || overall_status=1
run_case jd com.jingdong.app.mall "京东" "数据线" "搜索入口" "搜索输入框" "数据线" || overall_status=1
run_case chrome com.android.chrome "浏览器" "SolinAgentChrome" "地址栏" "地址栏" "SolinAgentChrome" || overall_status=1
run_case android_browser com.android.browser "浏览器" "SolinAgentBrowser" "地址栏" "地址栏" "SolinAgentBrowser" || overall_status=1
run_case quark com.quark.browser "夸克" "SolinAgentQuark" "地址栏" "地址栏" "SolinAgentQuark" || overall_status=1
run_case uc com.UCMobile "UC浏览器" "SolinAgentUC" "地址栏" "地址栏" "SolinAgentUC" || overall_status=1

if [[ "$RUN_COUNT" -eq 0 ]]; then
  fail_with_reason target-apps no-target-apps-installed "No target app packages were installed; all cases skipped."
fi

if [[ "$overall_status" -ne 0 ]]; then
  fail_with_reason real-app-search-case real-app-search-case-failed "At least one installed app search case failed."
fi

echo "Real app search eval passed for $PASS_COUNT installed app(s), skipped $SKIP_COUNT."
