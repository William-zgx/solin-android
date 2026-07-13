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
RELEASE_ARTIFACT_SHA256="${RELEASE_ARTIFACT_SHA256:-}"
CI_WORKFLOW="${REGRESSION_EMULATOR_WORKFLOW:-${GITHUB_WORKFLOW:-}}"
CI_JOB="${REGRESSION_EMULATOR_JOB:-${GITHUB_JOB:-}}"
CI_RUN_ID="${REGRESSION_EMULATOR_RUN_ID:-${GITHUB_RUN_ID:-}}"
CI_COMMIT_SHA="${REGRESSION_EMULATOR_COMMIT_SHA:-${GITHUB_SHA:-}}"
SOURCE_ANDROID_TEST_COUNT=""
ACTUAL_ANDROID_TEST_COUNT=""
EXECUTED_ANDROID_TEST_COUNT=""
SKIPPED_ANDROID_TEST_COUNT=""
SKIPPED_ANDROID_TESTS=""
EMULATOR_SERIAL=""
EMULATOR_API_LEVEL=""
EMULATOR_ABI=""
EMULATOR_AVD=""
DEVICE_INSTRUMENTATION_OUTPUT_FILE=""
DEVICE_LOGCAT_FILE=""
DEVICE_LOGCAT_SHA256=""
FAILED_TARGET=""
FAILURE_REASON=""
REPORT_STATUS_OVERRIDE=""
source "$ROOT_DIR/scripts/lib/report_helpers.sh"

count_android_tests() {
  find "$ANDROID_TEST_SOURCE_DIR" \( -name '*.kt' -o -name '*.java' \) -print0 |
    xargs -0 awk '/^[[:space:]]*@(org[.]junit[.])?Test([[:space:](]|$)/ {count += 1} END {print count + 0}'
}

fail() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  echo "regression_emulator: $*" >&2
  exit 1
}

skip() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  REPORT_STATUS_OVERRIDE="skipped"
  shift 2
  echo "regression_emulator: $*" >&2
  exit 0
}

is_allowed_hvf_infra_failure() {
  local nested_reason="$1"
  local emulator_log
  [[ "${ALLOW_EMULATOR_INFRA_UNAVAILABLE:-0}" == "1" ]] || return 1
  [[ "$nested_reason" == "no-single-authorized-emulator" || "$nested_reason" == "emulator-boot-timeout" ]] || return 1
  emulator_log="$(report_value "$EMULATOR_REPORT_FILE" "emulator_log")"
  [[ -n "$emulator_log" && -f "$emulator_log" ]] || return 1
  grep -Eq 'HV_UNSUPPORTED|failed to initialize HVF' "$emulator_log"
}

require_report() {
  local file="$1"
  [[ -f "$file" ]] || fail report missing-verification-report "Missing verification report: $file"
}

require_report_value() {
  local file="$1"
  local key="$2"
  local expected="$3"
  local actual
  actual="$(report_value "$file" "$key")"
  [[ "$actual" == "$expected" ]] ||
    fail report verification-report-value-mismatch "Expected $file to contain $key=$expected, got ${actual:-<missing>}."
}

require_non_empty_report_value() {
  local output_var="$1"
  local file="$2"
  local key="$3"
  local actual
  actual="$(report_value "$file" "$key")"
  [[ -n "$actual" ]] || fail report verification-report-value-missing "Expected $file to contain non-empty $key."
  printf -v "$output_var" '%s' "$actual"
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
    value="$(report_value "$DEVICE_REPORT_FILE" "instrumentation_executed_test_count")"
    [[ -n "$value" ]] && EXECUTED_ANDROID_TEST_COUNT="$value"
    value="$(report_value "$DEVICE_REPORT_FILE" "instrumentation_skipped_test_count")"
    [[ -n "$value" ]] && SKIPPED_ANDROID_TEST_COUNT="$value"
    value="$(report_value "$DEVICE_REPORT_FILE" "instrumentation_skipped_tests")"
    [[ -n "$value" ]] && SKIPPED_ANDROID_TESTS="$value"
    value="$(report_value "$DEVICE_REPORT_FILE" "instrumentation_output_file")"
    [[ -n "$value" ]] && DEVICE_INSTRUMENTATION_OUTPUT_FILE="$value"
    value="$(report_value "$DEVICE_REPORT_FILE" "logcat_file")"
    [[ -n "$value" ]] && DEVICE_LOGCAT_FILE="$value"
    value="$(report_value "$DEVICE_REPORT_FILE" "logcat_sha256")"
    [[ -n "$value" ]] && DEVICE_LOGCAT_SHA256="$value"
  fi
}

write_regression_report() {
  local exit_code="$1"
  local status_label="$REPORT_STATUS_OVERRIDE"
  if [[ -z "$status_label" ]]; then
    status_label="failed"
    [[ "$exit_code" -eq 0 ]] && status_label="passed"
  fi

  mkdir -p "$(dirname "$REGRESSION_REPORT_FILE")"
  {
    echo "status=$status_label"
    echo "exit_code=$exit_code"
    echo "target=regression-emulator"
    echo "failedTarget=${FAILED_TARGET:-}"
    echo "reason=${FAILURE_REASON:-}"
    echo "workflow=$CI_WORKFLOW"
    echo "job=$CI_JOB"
    echo "runId=$CI_RUN_ID"
    echo "commitSha=$CI_COMMIT_SHA"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "clean_device=1"
    echo "source_android_test_count=${SOURCE_ANDROID_TEST_COUNT:-}"
    echo "expected_android_test_count=${EXPECTED_ANDROID_TEST_COUNT:-}"
    echo "actual_android_test_count=${ACTUAL_ANDROID_TEST_COUNT:-}"
    echo "executed_android_test_count=${EXECUTED_ANDROID_TEST_COUNT:-}"
    echo "skipped_android_test_count=${SKIPPED_ANDROID_TEST_COUNT:-}"
    echo "skipped_android_tests=${SKIPPED_ANDROID_TESTS:-}"
    echo "releaseArtifactSha256=$RELEASE_ARTIFACT_SHA256"
    echo "serial=${EMULATOR_SERIAL:-}"
    echo "api_level=${EMULATOR_API_LEVEL:-}"
    echo "abi=${EMULATOR_ABI:-}"
    echo "avd=${EMULATOR_AVD:-}"
    echo "instrumentation_output_file=${DEVICE_INSTRUMENTATION_OUTPUT_FILE:-}"
    echo "logcat_file=${DEVICE_LOGCAT_FILE:-}"
    echo "logcat_sha256=${DEVICE_LOGCAT_SHA256:-}"
    echo "emulator_report_file=$EMULATOR_REPORT_FILE"
    echo "device_report_file=$DEVICE_REPORT_FILE"
  } > "$REGRESSION_REPORT_FILE"
  echo "Emulator regression report: $REGRESSION_REPORT_FILE"
}

trap 'status=$?; write_regression_report "$status"; exit "$status"' EXIT

SOURCE_ANDROID_TEST_COUNT="$(count_android_tests)"
[[ "$SOURCE_ANDROID_TEST_COUNT" =~ ^[1-9][0-9]*$ ]] ||
  fail android-test-source android-test-source-count-invalid "AndroidTest source count must be a positive integer; got ${SOURCE_ANDROID_TEST_COUNT:-<empty>}."
[[ -n "$EXPECTED_ANDROID_TEST_COUNT" ]] || EXPECTED_ANDROID_TEST_COUNT="$SOURCE_ANDROID_TEST_COUNT"
[[ "$EXPECTED_ANDROID_TEST_COUNT" =~ ^[1-9][0-9]*$ ]] ||
  fail expected-android-test-count expected-android-test-count-invalid "EXPECTED_ANDROID_TEST_COUNT must be a positive integer; got ${EXPECTED_ANDROID_TEST_COUNT:-<empty>}."
if [[ "$EXPECTED_ANDROID_TEST_COUNT" -lt "$SOURCE_ANDROID_TEST_COUNT" ]]; then
  fail expected-android-test-count expected-android-test-count-below-source "EXPECTED_ANDROID_TEST_COUNT=$EXPECTED_ANDROID_TEST_COUNT cannot be lower than AndroidTest source count $SOURCE_ANDROID_TEST_COUNT."
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
  nested_reason="$(report_value "$EMULATOR_REPORT_FILE" "reason")"
  [[ -n "$nested_reason" ]] || nested_reason="emulator-verification-failed"
  if is_allowed_hvf_infra_failure "$nested_reason"; then
    skip emulator-infra emulator-infra-hvf-unsupported \
      "Hosted emulator infrastructure does not support HVF for this AVD; see $EMULATOR_REPORT_FILE."
  fi
  fail emulator-verification "emulator-verification-$nested_reason" "Emulator verification failed; see $EMULATOR_REPORT_FILE and $DEVICE_REPORT_FILE."
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

require_non_empty_report_value EMULATOR_SERIAL "$EMULATOR_REPORT_FILE" "serial"
require_non_empty_report_value EMULATOR_API_LEVEL "$EMULATOR_REPORT_FILE" "api_level"
require_non_empty_report_value EMULATOR_ABI "$EMULATOR_REPORT_FILE" "abi"
EMULATOR_AVD="$(report_value "$EMULATOR_REPORT_FILE" "avd")"
DEVICE_INSTRUMENTATION_OUTPUT_FILE="$(report_value "$DEVICE_REPORT_FILE" "instrumentation_output_file")"
[[ -n "$DEVICE_INSTRUMENTATION_OUTPUT_FILE" ]] ||
  fail instrumentation-output instrumentation-output-file-missing "Expected $DEVICE_REPORT_FILE to contain non-empty instrumentation_output_file."
[[ -s "$DEVICE_INSTRUMENTATION_OUTPUT_FILE" ]] ||
  fail instrumentation-output instrumentation-output-file-empty "Expected non-empty instrumentation output artifact: $DEVICE_INSTRUMENTATION_OUTPUT_FILE."
ACTUAL_ANDROID_TEST_COUNT="$(report_value "$DEVICE_REPORT_FILE" "instrumentation_test_count")"
[[ -n "$ACTUAL_ANDROID_TEST_COUNT" ]] ||
  fail instrumentation-test-count instrumentation-test-count-missing "Expected $DEVICE_REPORT_FILE to contain non-empty instrumentation_test_count."
[[ "$ACTUAL_ANDROID_TEST_COUNT" =~ ^[0-9]+$ ]] ||
  fail instrumentation-test-count instrumentation-test-count-invalid "instrumentation_test_count must be numeric; got $ACTUAL_ANDROID_TEST_COUNT."
if [[ "$ACTUAL_ANDROID_TEST_COUNT" -lt "$EXPECTED_ANDROID_TEST_COUNT" ]]; then
  fail instrumentation-test-count instrumentation-test-count-below-expected "Instrumentation ran $ACTUAL_ANDROID_TEST_COUNT tests, expected at least $EXPECTED_ANDROID_TEST_COUNT."
fi

EXECUTED_ANDROID_TEST_COUNT="$(report_value "$DEVICE_REPORT_FILE" "instrumentation_executed_test_count")"
SKIPPED_ANDROID_TEST_COUNT="$(report_value "$DEVICE_REPORT_FILE" "instrumentation_skipped_test_count")"
SKIPPED_ANDROID_TESTS="$(report_value "$DEVICE_REPORT_FILE" "instrumentation_skipped_tests")"
[[ "$EXECUTED_ANDROID_TEST_COUNT" =~ ^[0-9]+$ ]] ||
  fail instrumentation-test-count instrumentation-executed-test-count-invalid \
    "instrumentation_executed_test_count must be numeric; got ${EXECUTED_ANDROID_TEST_COUNT:-<missing>}."
[[ "$SKIPPED_ANDROID_TEST_COUNT" =~ ^[0-9]+$ ]] ||
  fail instrumentation-test-count instrumentation-skipped-test-count-invalid \
    "instrumentation_skipped_test_count must be numeric; got ${SKIPPED_ANDROID_TEST_COUNT:-<missing>}."
if [[ $((EXECUTED_ANDROID_TEST_COUNT + SKIPPED_ANDROID_TEST_COUNT)) -ne "$ACTUAL_ANDROID_TEST_COUNT" ]]; then
  fail instrumentation-test-count instrumentation-execution-accounting-mismatch \
    "Executed $EXECUTED_ANDROID_TEST_COUNT plus skipped $SKIPPED_ANDROID_TEST_COUNT does not equal discovered $ACTUAL_ANDROID_TEST_COUNT."
fi

echo "Emulator regression passed: discovered=$ACTUAL_ANDROID_TEST_COUNT executed=$EXECUTED_ANDROID_TEST_COUNT skipped=$SKIPPED_ANDROID_TEST_COUNT."
