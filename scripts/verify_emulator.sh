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
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/emulator-$(date +%Y%m%d-%H%M%S)}"
EMULATOR_LOG="${ARTIFACT_DIR}-emulator.log"
EMULATOR_REPORT_FILE="${EMULATOR_REPORT_FILE:-${ARTIFACT_DIR}/emulator-verification.properties}"
DEVICE_REPORT_FILE="${DEVICE_REPORT_FILE:-${ARTIFACT_DIR}/device-verification.properties}"
SCREENSHOT_FILE="${ARTIFACT_DIR}/screenshot.png"
WINDOW_DUMP_FILE="${ARTIFACT_DIR}/window.xml"
LOGCAT_FILE="${ARTIFACT_DIR}/logcat.txt"
CRASH_ANR_SMOKE_REPORT_FILE="${CRASH_ANR_SMOKE_REPORT_FILE:-${ARTIFACT_DIR}/crash-anr-smoke.properties}"

STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SELECTED_SERIAL=""
API_LEVEL=""
ABI_LIST=""
AVD_LABEL=""
FAILED_TARGET=""
FAILURE_REASON=""
source "$ROOT_DIR/scripts/lib/report_helpers.sh"

available_avd_summary() {
  if [[ ! -x "$EMULATOR_BIN" ]]; then
    echo "none"
    return
  fi
  local summary
  summary="$("$EMULATOR_BIN" -list-avds 2>/dev/null | awk 'NF {items = items ? items ", " $0 : $0} END {print items}')"
  echo "${summary:-none}"
}

fail() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  echo "verify_emulator: $*" >&2
  if [[ -x "$EMULATOR_BIN" ]]; then
    echo "Available AVDs: $(available_avd_summary)" >&2
  fi
  if [[ -f "$EMULATOR_LOG" ]]; then
    echo "Emulator log: $EMULATOR_LOG" >&2
  fi
  if [[ -x "$ADB_BIN" ]]; then
    "$ADB_BIN" devices -l >&2 || true
  fi
  exit 1
}

write_emulator_report() {
  local exit_code="$1"
  local status_label="failed"
  [[ "$exit_code" -eq 0 ]] && status_label="passed"

  mkdir -p "$(dirname "$EMULATOR_REPORT_FILE")"
  {
    echo "status=$status_label"
    echo "exit_code=$exit_code"
    echo "target=emulator"
    echo "failedTarget=${FAILED_TARGET:-}"
    echo "reason=${FAILURE_REASON:-}"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=${SELECTED_SERIAL:-}"
    echo "api_level=${API_LEVEL:-}"
    echo "abi=${ABI_LIST:-}"
    echo "avd=${AVD_LABEL:-${AVD_NAME:-}}"
    echo "clean_device=${CLEAN_DEVICE:-0}"
    echo "evidence_dir=$ARTIFACT_DIR"
    echo "screenshot_file=$SCREENSHOT_FILE"
    echo "window_dump_file=$WINDOW_DUMP_FILE"
    echo "logcat_file=$LOGCAT_FILE"
    echo "crash_anr_smoke_report_file=$CRASH_ANR_SMOKE_REPORT_FILE"
    echo "emulator_log=$EMULATOR_LOG"
    echo "device_report_file=$DEVICE_REPORT_FILE"
  } > "$EMULATOR_REPORT_FILE"
  echo "Emulator verification report: $EMULATOR_REPORT_FILE"
}

capture_failure_artifacts() {
  local status=$?
  if [[ "$status" -ne 0 && -n "${SELECTED_SERIAL:-}" && -x "$ADB_BIN" ]]; then
    mkdir -p "$ARTIFACT_DIR"
    "$ADB_BIN" -s "$SELECTED_SERIAL" exec-out screencap -p > "$SCREENSHOT_FILE" 2>/dev/null || true
    "$ADB_BIN" -s "$SELECTED_SERIAL" shell uiautomator dump /sdcard/solin-window.xml >/dev/null 2>&1 || true
    "$ADB_BIN" -s "$SELECTED_SERIAL" pull /sdcard/solin-window.xml "$WINDOW_DUMP_FILE" >/dev/null 2>&1 || true
    "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -d -t 300 > "$LOGCAT_FILE" 2>/dev/null || true
    echo "Emulator failure artifacts: $ARTIFACT_DIR" >&2
  elif [[ "$status" -ne 0 && -f "$EMULATOR_LOG" ]]; then
    echo "Emulator log: $EMULATOR_LOG" >&2
  fi
  write_emulator_report "$status"
  exit "$status"
}
trap capture_failure_artifacts EXIT

device_state_for() {
  local serial="$1"
  "$ADB_BIN" devices | awk -v serial="$serial" '$1 == serial {print $2; found = 1} END {if (!found) print ""}'
}

authorized_emulators() {
  "$ADB_BIN" devices | awk 'NR > 1 && $1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}'
}

emulator_avd_name() {
  "${ADB[@]}" emu avd name 2>/dev/null |
    tr -d '\r' |
    awk 'NF && $0 != "OK" {value = $0} END {print value}'
}

select_emulator_once() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
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

if ! scripts/doctor.sh --device; then
  fail doctor doctor-device-failed "Android emulator environment check failed."
fi
[[ -x "$EMULATOR_BIN" ]] || fail emulator-binary emulator-binary-missing "Android emulator binary not found at $EMULATOR_BIN."
if [[ -n "${ANDROID_SERIAL:-}" && "$ANDROID_SERIAL" != emulator-* ]]; then
  fail android-serial android-serial-not-emulator "ANDROID_SERIAL=$ANDROID_SERIAL is not an emulator serial."
fi
mkdir -p "$(dirname "$ARTIFACT_DIR")"

if [[ -n "${AVD_NAME:-}" ]]; then
  "$EMULATOR_BIN" -list-avds 2>/dev/null | grep -Fx -- "$AVD_NAME" >/dev/null ||
    fail requested-avd requested-avd-not-found "AVD_NAME=$AVD_NAME not found."
  EXTRA_EMULATOR_ARGS=()
  EMULATOR_ARGS="${EMULATOR_ARGS:--wipe-data -no-window -no-audio -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect}"
  read -r -a EXTRA_EMULATOR_ARGS <<< "$EMULATOR_ARGS"
  EMULATOR_CMD=("$EMULATOR_BIN" -avd "$AVD_NAME")
  if [[ "${#EXTRA_EMULATOR_ARGS[@]}" -gt 0 ]]; then
    EMULATOR_CMD+=("${EXTRA_EMULATOR_ARGS[@]}")
  fi
  echo "Starting emulator AVD: $AVD_NAME"
  echo "Emulator log: $EMULATOR_LOG"
  "${EMULATOR_CMD[@]}" > "$EMULATOR_LOG" 2>&1 &
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
echo "CLEAN_DEVICE: ${CLEAN_DEVICE:-0}"

set +e
ANDROID_SERIAL="$SELECTED_SERIAL" \
  CLEAN_DEVICE="${CLEAN_DEVICE:-0}" \
  GRADLE_CMD="$GRADLE_CMD" \
  ARTIFACT_DIR="$ARTIFACT_DIR" \
  VERIFICATION_REPORT_FILE="$DEVICE_REPORT_FILE" \
  INSTRUMENTATION_OUTPUT_FILE="${ARTIFACT_DIR}/instrumentation.txt" \
  LOGCAT_FILE="$LOGCAT_FILE" \
  scripts/install_and_test_device.sh
DEVICE_VERIFY_STATUS=$?
set -e
if [[ "$DEVICE_VERIFY_STATUS" -ne 0 ]]; then
  nested_reason="$(report_value "$DEVICE_REPORT_FILE" "reason")"
  [[ -n "$nested_reason" ]] || nested_reason="device-verification-failed"
  fail device-verification "device-verification-$nested_reason" "Device verification failed; see $DEVICE_REPORT_FILE."
fi

set +e
DEVICE_INSTRUMENTATION_OUTPUT_FILE="$(report_value "$DEVICE_REPORT_FILE" "instrumentation_output_file")"
CRASH_ANR_SMOKE_ARGS=(
  --device-report "$DEVICE_REPORT_FILE"
  --logcat "$LOGCAT_FILE"
  --report "$CRASH_ANR_SMOKE_REPORT_FILE"
  --window "emulator verification"
  --track "local-emulator"
)
if [[ -n "$DEVICE_INSTRUMENTATION_OUTPUT_FILE" ]]; then
  CRASH_ANR_SMOKE_ARGS+=(--instrumentation-output "$DEVICE_INSTRUMENTATION_OUTPUT_FILE")
fi
scripts/collect_crash_anr_smoke_evidence.sh "${CRASH_ANR_SMOKE_ARGS[@]}"
CRASH_ANR_SMOKE_STATUS=$?
set -e
if [[ "$CRASH_ANR_SMOKE_STATUS" -ne 0 ]]; then
  nested_reason="$(report_value "$CRASH_ANR_SMOKE_REPORT_FILE" "reason")"
  [[ -n "$nested_reason" ]] || nested_reason="crash-anr-smoke-failed"
  fail crash-anr-smoke "crash-anr-smoke-$nested_reason" \
    "Crash/ANR smoke evidence failed; see $CRASH_ANR_SMOKE_REPORT_FILE."
fi

echo "Emulator verification passed."
