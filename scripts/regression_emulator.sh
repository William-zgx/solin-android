#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/regression-emulator-$(date +%Y%m%d-%H%M%S)}"
REGRESSION_REPORT_FILE="${REGRESSION_REPORT_FILE:-${ARTIFACT_DIR}/regression-emulator.properties}"
EMULATOR_REPORT_FILE="${EMULATOR_REPORT_FILE:-${ARTIFACT_DIR}/emulator-verification.properties}"
DEVICE_REPORT_FILE="${DEVICE_REPORT_FILE:-${ARTIFACT_DIR}/device-verification.properties}"
ANDROID_TEST_SOURCE_DIR="${ANDROID_TEST_SOURCE_DIR:-app/src/androidTest}"
STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
EXPECTED_ANDROID_TEST_COUNT="${EXPECTED_ANDROID_TEST_COUNT:-}"
SOURCE_ANDROID_TEST_COUNT=""
ACTUAL_ANDROID_TEST_COUNT=""
EMULATOR_SERIAL=""
EMULATOR_API_LEVEL=""
EMULATOR_ABI=""
EMULATOR_AVD=""
DEVICE_INSTRUMENTATION_OUTPUT_FILE=""

count_android_tests() {
  find "$ANDROID_TEST_SOURCE_DIR" \( -name '*.kt' -o -name '*.java' \) -print0 |
    xargs -0 awk '/^[[:space:]]*@(org[.]junit[.])?Test([[:space:](]|$)/ {count += 1} END {print count + 0}'
}

report_value() {
  local file="$1"
  local key="$2"
  awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$file"
}

fail() {
  echo "regression_emulator: $*" >&2
  exit 1
}

require_report() {
  local file="$1"
  [[ -f "$file" ]] || fail "Missing verification report: $file"
}

require_report_value() {
  local file="$1"
  local key="$2"
  local expected="$3"
  local actual
  actual="$(report_value "$file" "$key")"
  [[ "$actual" == "$expected" ]] ||
    fail "Expected $file to contain $key=$expected, got ${actual:-<missing>}."
}

require_non_empty_report_value() {
  local file="$1"
  local key="$2"
  local actual
  actual="$(report_value "$file" "$key")"
  [[ -n "$actual" ]] || fail "Expected $file to contain non-empty $key."
  printf '%s\n' "$actual"
}

harvest_reports() {
  local value
  if [[ -f "$EMULATOR_REPORT_FILE" ]]; then
    value="$(report_value "$EMULATOR_REPORT_FILE" "serial")"
    [[ -n "$value" ]] && EMULATOR_SERIAL="$value"
    value="$(report_value "$EMULATOR_REPORT_FILE" "api_level")"
    [[ -n "$value" ]] && EMULATOR_API_LEVEL="$value"
    value="$(report_value "$EMULATOR_REPORT_FILE" "abi")"
    [[ -n "$value" ]] && EMULATOR_ABI="$value"
    value="$(report_value "$EMULATOR_REPORT_FILE" "avd")"
    [[ -n "$value" ]] && EMULATOR_AVD="$value"
  fi
  if [[ -f "$DEVICE_REPORT_FILE" ]]; then
    value="$(report_value "$DEVICE_REPORT_FILE" "instrumentation_test_count")"
    [[ -n "$value" ]] && ACTUAL_ANDROID_TEST_COUNT="$value"
    value="$(report_value "$DEVICE_REPORT_FILE" "instrumentation_output_file")"
    [[ -n "$value" ]] && DEVICE_INSTRUMENTATION_OUTPUT_FILE="$value"
  fi
}

write_regression_report() {
  local exit_code="$1"
  local status_label="failed"
  [[ "$exit_code" -eq 0 ]] && status_label="passed"

  mkdir -p "$(dirname "$REGRESSION_REPORT_FILE")"
  {
    echo "status=$status_label"
    echo "exit_code=$exit_code"
    echo "target=regression-emulator"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "clean_device=1"
    echo "source_android_test_count=${SOURCE_ANDROID_TEST_COUNT:-}"
    echo "expected_android_test_count=${EXPECTED_ANDROID_TEST_COUNT:-}"
    echo "actual_android_test_count=${ACTUAL_ANDROID_TEST_COUNT:-}"
    echo "serial=${EMULATOR_SERIAL:-}"
    echo "api_level=${EMULATOR_API_LEVEL:-}"
    echo "abi=${EMULATOR_ABI:-}"
    echo "avd=${EMULATOR_AVD:-}"
    echo "instrumentation_output_file=${DEVICE_INSTRUMENTATION_OUTPUT_FILE:-}"
    echo "emulator_report_file=$EMULATOR_REPORT_FILE"
    echo "device_report_file=$DEVICE_REPORT_FILE"
  } > "$REGRESSION_REPORT_FILE"
  echo "Emulator regression report: $REGRESSION_REPORT_FILE"
}

trap 'status=$?; write_regression_report "$status"; exit "$status"' EXIT

SOURCE_ANDROID_TEST_COUNT="$(count_android_tests)"
[[ "$SOURCE_ANDROID_TEST_COUNT" =~ ^[1-9][0-9]*$ ]] ||
  fail "AndroidTest source count must be a positive integer; got ${SOURCE_ANDROID_TEST_COUNT:-<empty>}."
[[ -n "$EXPECTED_ANDROID_TEST_COUNT" ]] || EXPECTED_ANDROID_TEST_COUNT="$SOURCE_ANDROID_TEST_COUNT"
[[ "$EXPECTED_ANDROID_TEST_COUNT" =~ ^[1-9][0-9]*$ ]] ||
  fail "EXPECTED_ANDROID_TEST_COUNT must be a positive integer; got ${EXPECTED_ANDROID_TEST_COUNT:-<empty>}."
if [[ "$EXPECTED_ANDROID_TEST_COUNT" -lt "$SOURCE_ANDROID_TEST_COUNT" ]]; then
  fail "EXPECTED_ANDROID_TEST_COUNT=$EXPECTED_ANDROID_TEST_COUNT cannot be lower than AndroidTest source count $SOURCE_ANDROID_TEST_COUNT."
fi

mkdir -p "$ARTIFACT_DIR"

set +e
ARTIFACT_DIR="$ARTIFACT_DIR" \
  EMULATOR_REPORT_FILE="$EMULATOR_REPORT_FILE" \
  DEVICE_REPORT_FILE="$DEVICE_REPORT_FILE" \
  CLEAN_DEVICE=1 \
  scripts/verify_emulator.sh
EMULATOR_VERIFY_STATUS=$?
set -e
harvest_reports
if [[ "$EMULATOR_VERIFY_STATUS" -ne 0 ]]; then
  fail "Emulator verification failed; see $EMULATOR_REPORT_FILE and $DEVICE_REPORT_FILE."
fi

require_report "$EMULATOR_REPORT_FILE"
require_report "$DEVICE_REPORT_FILE"

require_report_value "$EMULATOR_REPORT_FILE" "status" "passed"
require_report_value "$EMULATOR_REPORT_FILE" "target" "emulator"
require_report_value "$EMULATOR_REPORT_FILE" "clean_device" "1"
require_report_value "$EMULATOR_REPORT_FILE" "device_report_file" "$DEVICE_REPORT_FILE"

require_report_value "$DEVICE_REPORT_FILE" "status" "passed"
require_report_value "$DEVICE_REPORT_FILE" "target" "device"
require_report_value "$DEVICE_REPORT_FILE" "clean_device" "1"
require_report_value "$DEVICE_REPORT_FILE" "instrumentation" "passed"

EMULATOR_SERIAL="$(require_non_empty_report_value "$EMULATOR_REPORT_FILE" "serial")"
EMULATOR_API_LEVEL="$(require_non_empty_report_value "$EMULATOR_REPORT_FILE" "api_level")"
EMULATOR_ABI="$(require_non_empty_report_value "$EMULATOR_REPORT_FILE" "abi")"
EMULATOR_AVD="$(report_value "$EMULATOR_REPORT_FILE" "avd")"
DEVICE_INSTRUMENTATION_OUTPUT_FILE="$(require_non_empty_report_value "$DEVICE_REPORT_FILE" "instrumentation_output_file")"
[[ -s "$DEVICE_INSTRUMENTATION_OUTPUT_FILE" ]] ||
  fail "Expected non-empty instrumentation output artifact: $DEVICE_INSTRUMENTATION_OUTPUT_FILE."
ACTUAL_ANDROID_TEST_COUNT="$(require_non_empty_report_value "$DEVICE_REPORT_FILE" "instrumentation_test_count")"
[[ "$ACTUAL_ANDROID_TEST_COUNT" =~ ^[0-9]+$ ]] ||
  fail "instrumentation_test_count must be numeric; got $ACTUAL_ANDROID_TEST_COUNT."
if [[ "$ACTUAL_ANDROID_TEST_COUNT" -lt "$EXPECTED_ANDROID_TEST_COUNT" ]]; then
  fail "Instrumentation ran $ACTUAL_ANDROID_TEST_COUNT tests, expected at least $EXPECTED_ANDROID_TEST_COUNT."
fi

echo "Emulator regression passed with $ACTUAL_ANDROID_TEST_COUNT AndroidTest(s)."
