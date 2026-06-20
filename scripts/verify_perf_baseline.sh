#!/usr/bin/env bash
set -euo pipefail

BASELINE_FILE=""
REPORT_FILE=""
EXPECTED_ARTIFACT_SHA256=""
EXPECTED_APP_VERSION=""
PERFORMANCE_KEY="${PERFORMANCE_KEY:-}"
MAX_RECORD_AGE_DAYS="${MAX_RECORD_AGE_DAYS:-30}"
EVIDENCE_OWNER="${EVIDENCE_OWNER:-${OWNER:-release-engineering}}"
ORIGINAL_ARGS=("$@")

command_line() {
  local quoted=()
  local arg
  quoted+=("$(printf '%q' "$0")")
  for arg in "${ORIGINAL_ARGS[@]}"; do
    quoted+=("$(printf '%q' "$arg")")
  done
  local IFS=' '
  printf '%s' "${quoted[*]}"
}

failed_target_for_reason() {
  local reason="$1"
  local first="${reason%%,*}"
  case "$first" in
    "")
      printf ''
      ;;
    baseline-file-missing)
      printf 'baseline-file'
      ;;
    *-missing)
      printf 'baseline-fields'
      ;;
    status-not-passed|oom-or-anr-observed)
      printf 'baseline-status'
      ;;
    device-serial-is-emulator|abi-not-arm64-v8a|android-api-out-of-range)
      printf 'device-metadata'
      ;;
    release-artifact-sha-*)
      printf 'release-artifact'
      ;;
    model-id-invalid)
      printf 'model-profile'
      ;;
    backend-invalid|gpu-fallback-status-invalid)
      printf 'runtime-backend'
      ;;
    app-version-mismatch)
      printf 'app-version'
      ;;
    recorded-at-*)
      printf 'baseline-timestamp'
      ;;
    *-not-integer|*-not-positive|tokens-per-second-*)
      printf 'baseline-metrics'
      ;;
    *)
      printf 'perf-baseline'
      ;;
  esac
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file)
      BASELINE_FILE="${2:?missing perf baseline file}"
      shift 2
      ;;
    --report)
      REPORT_FILE="${2:?missing report path}"
      shift 2
      ;;
    --artifact-sha256)
      EXPECTED_ARTIFACT_SHA256="${2:?missing artifact sha256}"
      shift 2
      ;;
    --app-version)
      EXPECTED_APP_VERSION="${2:?missing app version}"
      shift 2
      ;;
    --performance-key)
      PERFORMANCE_KEY="${2:?missing performance key}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

write_report() {
  local status="$1"
  local missing="$2"
  local reason="${3:-}"
  local failed_target=""
  if [[ "$status" != "passed" ]]; then
    failed_target="$(failed_target_for_reason "$reason")"
  fi
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'artifactSchema=PerfBaselineVerification/v1\n'
      printf 'status=%s\n' "$status"
      printf 'target=perf-baseline\n'
      printf 'owner=%s\n' "$EVIDENCE_OWNER"
      printf 'recordedAt=%s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
      printf 'command=%s\n' "$(command_line)"
      printf 'performanceKey=%s\n' "$PERFORMANCE_KEY"
      printf 'failedTarget=%s\n' "$failed_target"
      printf 'reason=%s\n' "$reason"
      printf 'reproduciblePath=%s\n' "${BASELINE_FILE:-}"
      printf 'baselineFile=%s\n' "${BASELINE_FILE:-}"
      if [[ -n "${BASELINE_FILE:-}" && -f "$BASELINE_FILE" ]]; then
        printf 'baselineSha256=%s\n' "$(shasum -a 256 "$BASELINE_FILE" | awk '{print $1}')"
      else
        printf 'baselineSha256=\n'
      fi
      printf 'missingFieldCount=%s\n' "$missing"
      printf 'expectedArtifactSha256=%s\n' "$EXPECTED_ARTIFACT_SHA256"
      printf 'expectedAppVersion=%s\n' "$EXPECTED_APP_VERSION"
      printf 'maxRecordAgeDays=%s\n' "$MAX_RECORD_AGE_DAYS"
    } > "$REPORT_FILE"
  fi
}

FAILURES=()

record_failure() {
  local reason="$1"
  FAILURES+=("$reason")
  missing=$((missing + 1))
}

failure_reason() {
  local IFS=,
  printf '%s' "${FAILURES[*]}"
}

if [[ -z "$BASELINE_FILE" || ! -f "$BASELINE_FILE" ]]; then
  FAILURES=(baseline-file-missing)
  write_report failed 1 "$(failure_reason)"
  echo "Perf baseline file is missing." >&2
  exit 1
fi

required_fields=(
  status
  deviceSerial
  deviceModel
  androidApi
  abi
  appVersion
  releaseArtifactSha256
  modelId
  backend
  firstLaunchInteractiveMs
  modelLoadMs
  firstTokenMs
  tokensPerSecond
  stopGenerationRecoveryMs
  gpuFallbackStatus
  visionInputMs
  memorySearch5kMs
  memoryPeakMb
  oomOrAnrObserved
  recordedAt
)

missing=0
for field in "${required_fields[@]}"; do
  if ! grep -qE "^${field}=.+" "$BASELINE_FILE"; then
    echo "Missing perf baseline field: $field" >&2
    record_failure "${field}-missing"
  fi
done

if ! grep -qx 'status=passed' "$BASELINE_FILE"; then
  echo "Perf baseline status must be passed." >&2
  record_failure "status-not-passed"
fi

if ! grep -qx 'oomOrAnrObserved=false' "$BASELINE_FILE"; then
  echo "Perf baseline must record oomOrAnrObserved=false." >&2
  record_failure "oom-or-anr-observed"
fi

device_serial="$(awk -F= '$1 == "deviceSerial" {print $2; exit}' "$BASELINE_FILE")"
if [[ "$device_serial" == emulator-* ]]; then
  echo "Perf baseline must come from a non-emulator physical device." >&2
  record_failure "device-serial-is-emulator"
fi

abi="$(awk -F= '$1 == "abi" {print $2; exit}' "$BASELINE_FILE")"
if [[ "$abi" != "arm64-v8a" ]]; then
  echo "Perf baseline abi must be arm64-v8a." >&2
  record_failure "abi-not-arm64-v8a"
fi

model_id="$(awk -F= '$1 == "modelId" {print $2; exit}' "$BASELINE_FILE")"
case "$model_id" in
  chat-e2b|chat-e4b)
    ;;
  "")
    ;;
  *)
    echo "Perf baseline modelId must be a release chat profile: chat-e2b or chat-e4b." >&2
    record_failure "model-id-invalid"
    ;;
esac

backend="$(awk -F= '$1 == "backend" {print $2; exit}' "$BASELINE_FILE")"
case "$backend" in
  CPU|GPU)
    ;;
  "")
    ;;
  *)
    echo "Perf baseline backend must be CPU or GPU." >&2
    record_failure "backend-invalid"
    ;;
esac

gpu_fallback_status="$(awk -F= '$1 == "gpuFallbackStatus" {print $2; exit}' "$BASELINE_FILE")"
case "$gpu_fallback_status" in
  not-needed|cpu-fallback-passed)
    ;;
  "")
    ;;
  *)
    echo "Perf baseline gpuFallbackStatus must be not-needed or cpu-fallback-passed." >&2
    record_failure "gpu-fallback-status-invalid"
    ;;
esac

if [[ -n "$EXPECTED_ARTIFACT_SHA256" ]]; then
  recorded_sha="$(awk -F= '$1 == "releaseArtifactSha256" {print $2; exit}' "$BASELINE_FILE")"
  if [[ "$recorded_sha" != "$EXPECTED_ARTIFACT_SHA256" ]]; then
    echo "Perf baseline releaseArtifactSha256 does not match release artifact." >&2
    record_failure "release-artifact-sha-mismatch"
  fi
fi

recorded_sha="$(awk -F= '$1 == "releaseArtifactSha256" {print $2; exit}' "$BASELINE_FILE")"
if [[ -n "$recorded_sha" && ! "$recorded_sha" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "Perf baseline releaseArtifactSha256 must be a SHA-256 hex digest." >&2
  record_failure "release-artifact-sha-invalid"
fi

if [[ -n "$EXPECTED_APP_VERSION" ]]; then
  recorded_app_version="$(awk -F= '$1 == "appVersion" {print $2; exit}' "$BASELINE_FILE")"
  if [[ "$recorded_app_version" != "$EXPECTED_APP_VERSION" ]]; then
    echo "Perf baseline appVersion does not match release version." >&2
    record_failure "app-version-mismatch"
  fi
fi

numeric_fields=(
  androidApi
  firstLaunchInteractiveMs
  modelLoadMs
  firstTokenMs
  stopGenerationRecoveryMs
  visionInputMs
  memorySearch5kMs
  memoryPeakMb
)

for field in "${numeric_fields[@]}"; do
  value="$(awk -F= -v key="$field" '$1 == key {print $2; exit}' "$BASELINE_FILE")"
  if [[ -n "$value" && ! "$value" =~ ^[0-9]+$ ]]; then
    echo "Perf baseline field $field must be an integer." >&2
    record_failure "${field}-not-integer"
  elif [[ -n "$value" && "$value" -le 0 ]]; then
    echo "Perf baseline field $field must be > 0." >&2
    record_failure "${field}-not-positive"
  fi
done

android_api="$(awk -F= '$1 == "androidApi" {print $2; exit}' "$BASELINE_FILE")"
if [[ "$android_api" =~ ^[0-9]+$ ]] && { [[ "$android_api" -lt 28 ]] || [[ "$android_api" -gt 36 ]]; }; then
  echo "Perf baseline androidApi must be between 28 and 36." >&2
  record_failure "android-api-out-of-range"
fi

tps="$(awk -F= '$1 == "tokensPerSecond" {print $2; exit}' "$BASELINE_FILE")"
if [[ -n "$tps" && ! "$tps" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "Perf baseline field tokensPerSecond must be numeric." >&2
  record_failure "tokens-per-second-not-numeric"
elif [[ -n "$tps" ]]; then
  awk -v value="$tps" 'BEGIN { exit(value > 0 ? 0 : 1) }' || {
    echo "Perf baseline field tokensPerSecond must be > 0." >&2
    record_failure "tokens-per-second-not-positive"
  }
fi

recorded_at="$(awk -F= '$1 == "recordedAt" {print $2; exit}' "$BASELINE_FILE")"
if [[ -n "$recorded_at" ]]; then
  if ! python3 - "$recorded_at" "$MAX_RECORD_AGE_DAYS" <<'PY'
import sys
from datetime import datetime, timezone

recorded_at = sys.argv[1]
max_age_days = int(sys.argv[2])
try:
    parsed = datetime.strptime(recorded_at, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
except ValueError:
    sys.exit(1)
now = datetime.now(timezone.utc)
if parsed > now:
    sys.exit(2)
if (now - parsed).days > max_age_days:
    sys.exit(3)
PY
  then
    echo "Perf baseline recordedAt must be UTC, non-future, and no older than ${MAX_RECORD_AGE_DAYS} day(s)." >&2
    record_failure "recorded-at-invalid-or-stale"
  fi
fi

if [[ "$missing" -gt 0 ]]; then
  write_report failed "$missing" "$(failure_reason)"
  exit 1
fi

write_report passed 0 ""
echo "Perf baseline verification passed."
