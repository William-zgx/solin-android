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

STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SELECTED_SERIAL=""
API_LEVEL=""
ABI_LIST=""
AVD_LABEL=""

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
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=${SELECTED_SERIAL:-}"
    echo "api_level=${API_LEVEL:-}"
    echo "abi=${ABI_LIST:-}"
    echo "avd=${AVD_LABEL:-${AVD_NAME:-}}"
    echo "clean_device=${CLEAN_DEVICE:-0}"
    echo "emulator_log=$EMULATOR_LOG"
    echo "device_report_file=$DEVICE_REPORT_FILE"
  } > "$EMULATOR_REPORT_FILE"
  echo "Emulator verification report: $EMULATOR_REPORT_FILE"
}

capture_failure_artifacts() {
  local status=$?
  write_emulator_report "$status"
  if [[ "$status" -ne 0 && -n "${SELECTED_SERIAL:-}" && -x "$ADB_BIN" ]]; then
    mkdir -p "$ARTIFACT_DIR"
    "$ADB_BIN" -s "$SELECTED_SERIAL" exec-out screencap -p > "$ARTIFACT_DIR/screenshot.png" 2>/dev/null || true
    "$ADB_BIN" -s "$SELECTED_SERIAL" shell uiautomator dump /sdcard/pocketmind-window.xml >/dev/null 2>&1 || true
    "$ADB_BIN" -s "$SELECTED_SERIAL" pull /sdcard/pocketmind-window.xml "$ARTIFACT_DIR/window.xml" >/dev/null 2>&1 || true
    "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -d -t 300 > "$ARTIFACT_DIR/logcat.txt" 2>/dev/null || true
    echo "Emulator failure artifacts: $ARTIFACT_DIR" >&2
  elif [[ "$status" -ne 0 && -f "$EMULATOR_LOG" ]]; then
    echo "Emulator log: $EMULATOR_LOG" >&2
  fi
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
      fail "Timed out waiting for a single authorized emulator. Start exactly one emulator-* device or set AVD_NAME to an available AVD."
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
      fail "Timed out waiting for $SELECTED_SERIAL to finish booting."
    sleep 2
  done
}

scripts/doctor.sh --device
[[ -x "$EMULATOR_BIN" ]] || fail "Android emulator binary not found at $EMULATOR_BIN."
if [[ -n "${ANDROID_SERIAL:-}" && "$ANDROID_SERIAL" != emulator-* ]]; then
  fail "ANDROID_SERIAL=$ANDROID_SERIAL is not an emulator serial."
fi
mkdir -p "$(dirname "$ARTIFACT_DIR")"

if [[ -n "${AVD_NAME:-}" ]]; then
  "$EMULATOR_BIN" -list-avds 2>/dev/null | grep -Fx -- "$AVD_NAME" >/dev/null ||
    fail "AVD_NAME=$AVD_NAME not found."
  EXTRA_EMULATOR_ARGS=()
  if [[ -n "${EMULATOR_ARGS:-}" ]]; then
    read -r -a EXTRA_EMULATOR_ARGS <<< "$EMULATOR_ARGS"
  fi
  echo "Starting emulator AVD: $AVD_NAME"
  echo "Emulator log: $EMULATOR_LOG"
  "$EMULATOR_BIN" -avd "$AVD_NAME" "${EXTRA_EMULATOR_ARGS[@]}" \
    > "$EMULATOR_LOG" 2>&1 &
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

ANDROID_SERIAL="$SELECTED_SERIAL" \
  CLEAN_DEVICE="${CLEAN_DEVICE:-0}" \
  GRADLE_CMD="$GRADLE_CMD" \
  ARTIFACT_DIR="$ARTIFACT_DIR" \
  VERIFICATION_REPORT_FILE="$DEVICE_REPORT_FILE" \
  INSTRUMENTATION_OUTPUT_FILE="${ARTIFACT_DIR}/instrumentation.txt" \
  scripts/install_and_test_device.sh

echo "Emulator verification passed."
