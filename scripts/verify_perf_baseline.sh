#!/usr/bin/env bash
set -euo pipefail

BASELINE_FILE=""
REPORT_FILE=""
EXPECTED_ARTIFACT_SHA256=""
EXPECTED_APP_VERSION=""
PERFORMANCE_KEY="${PERFORMANCE_KEY:-}"
MAX_RECORD_AGE_DAYS="${MAX_RECORD_AGE_DAYS:-30}"

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
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'status=%s\n' "$status"
      printf 'target=perf-baseline\n'
      printf 'performanceKey=%s\n' "$PERFORMANCE_KEY"
      printf 'reason=%s\n' "$reason"
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
