#!/usr/bin/env bash
set -euo pipefail

OUT_FILE="${OUT_FILE:-build/verification/rc/perf-baseline.properties}"
RELEASE_ARTIFACT="${RELEASE_ARTIFACT:-}"
ANDROID_SERIAL="${ANDROID_SERIAL:-}"
STATUS="${STATUS:-passed}"
VERIFY_REPORT_FILE="${VERIFY_REPORT_FILE:-${OUT_FILE}.verification.properties}"
PERFORMANCE_KEY="${PERFORMANCE_KEY:-}"
EVIDENCE_OWNER="${EVIDENCE_OWNER:-${OWNER:-release-engineering}}"
DEFER_DEVICE_TESTS="${DEFER_DEVICE_TESTS:-0}"
DEFER_PERF_BASELINE="${DEFER_PERF_BASELINE:-0}"
DEFERRED_REASON="${DEFERRED_REASON:-}"
STATUS_OVERRIDE="${STATUS_OVERRIDE:-}"
FAILED_TARGET=""
FAILURE_REASON=""
device_serial="$ANDROID_SERIAL"
device_model="${DEVICE_MODEL:-}"
android_api="${ANDROID_API:-}"
abi="${ABI:-}"
ORIGINAL_ARGS=("$@")

command_line() {
  local quoted=()
  local arg
  quoted+=("$(printf '%q' "$0")")
  if [[ "${#ORIGINAL_ARGS[@]}" -gt 0 ]]; then
    for arg in "${ORIGINAL_ARGS[@]}"; do
      quoted+=("$(printf '%q' "$arg")")
    done
  fi
  local IFS=' '
  printf '%s' "${quoted[*]}"
}

report_value() {
  local file="$1"
  local key="$2"
  [[ -f "$file" ]] || return 0
  awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$file"
}

write_failure_report() {
  local exit_code="$1"
  mkdir -p "$(dirname "$OUT_FILE")"
  {
    printf 'artifactSchema=PerfBaselineCollection/v1\n'
    printf 'status=%s\n' "${STATUS_OVERRIDE:-failed}"
    printf 'exit_code=%s\n' "$exit_code"
    printf 'target=perf-baseline-collector\n'
    printf 'owner=%s\n' "$EVIDENCE_OWNER"
    printf 'recordedAt=%s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    printf 'command=%s\n' "$(command_line)"
    printf 'reproduciblePath=%s\n' "$OUT_FILE"
    printf 'failedTarget=%s\n' "${FAILED_TARGET:-}"
    printf 'reason=%s\n' "${FAILURE_REASON:-unexpected-collector-failure}"
    printf 'deferredReason=%s\n' "$DEFERRED_REASON"
    printf 'releaseArtifact=%s\n' "$RELEASE_ARTIFACT"
    if [[ -f "$RELEASE_ARTIFACT" ]]; then
      printf 'releaseArtifactSha256=%s\n' "$(shasum -a 256 "$RELEASE_ARTIFACT" | awk '{print $1}')"
    fi
    printf 'androidSerialInput=%s\n' "$ANDROID_SERIAL"
    printf 'deviceSerial=%s\n' "${device_serial:-}"
    printf 'deviceModel=%s\n' "${device_model:-}"
    printf 'androidApi=%s\n' "${android_api:-}"
    printf 'abi=%s\n' "${abi:-}"
    printf 'appVersion=%s\n' "${APP_VERSION:-}"
    printf 'modelId=%s\n' "${MODEL_ID:-}"
    printf 'backend=%s\n' "${BACKEND:-}"
    printf 'verificationReport=%s\n' "$VERIFY_REPORT_FILE"
    printf 'verificationReason=%s\n' "$(report_value "$VERIFY_REPORT_FILE" reason)"
  } > "$OUT_FILE"
  echo "Perf baseline collection report: $OUT_FILE"
}

trap 'status=$?; if [[ "$status" -ne 0 ]]; then write_failure_report "$status"; fi; exit "$status"' EXIT

if [[ "$DEFER_DEVICE_TESTS" == "1" || "$DEFER_PERF_BASELINE" == "1" ]]; then
  DEFERRED_REASON="${DEFERRED_REASON:-no-device-test-in-this-phase}"
  STATUS_OVERRIDE="skipped"
  FAILED_TARGET="perf-baseline"
  FAILURE_REASON="$DEFERRED_REASON"
  write_failure_report 0
  exit 0
fi

fail() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  echo "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage:
  OUT_FILE=build/verification/rc/perf-baseline.properties \
  RELEASE_ARTIFACT=app-release-signed.apk \
  ANDROID_SERIAL=<device> \
  APP_VERSION=<versionName> MODEL_ID=chat-e2b BACKEND=GPU \
  PERFORMANCE_KEY=firstLaunch \
  FIRST_LAUNCH_INTERACTIVE_MS=... MODEL_LOAD_MS=... FIRST_TOKEN_MS=... \
  TOKENS_PER_SECOND=... STOP_GENERATION_RECOVERY_MS=... GPU_FALLBACK_STATUS=... \
  VISION_INPUT_MS=... MEMORY_SEARCH_5K_MS=... MEMORY_PEAK_MB=... \
  OOM_OR_ANR_OBSERVED=false \
  scripts/collect_perf_baseline.sh

This script records measured RC performance inputs; it does not invent timings.
USAGE
}

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    local reason
    reason="$(printf '%s' "$name" | tr '[:upper:]_' '[:lower:]-')"
    FAILED_TARGET="environment"
    FAILURE_REASON="missing-$reason"
    echo "Missing required environment variable: $name" >&2
    usage
    exit 1
  fi
}

require_env RELEASE_ARTIFACT
require_env APP_VERSION
require_env MODEL_ID
require_env BACKEND
require_env FIRST_LAUNCH_INTERACTIVE_MS
require_env MODEL_LOAD_MS
require_env FIRST_TOKEN_MS
require_env TOKENS_PER_SECOND
require_env STOP_GENERATION_RECOVERY_MS
require_env GPU_FALLBACK_STATUS
require_env VISION_INPUT_MS
require_env MEMORY_SEARCH_5K_MS
require_env MEMORY_PEAK_MB
require_env OOM_OR_ANR_OBSERVED

if [[ ! -f "$RELEASE_ARTIFACT" ]]; then
  fail input-artifact release-artifact-missing "Release artifact is missing: $RELEASE_ARTIFACT"
fi

ADB="${ADB:-adb}"
if [[ -n "$ANDROID_SERIAL" ]]; then
  ADB_CMD=("$ADB" -s "$ANDROID_SERIAL")
else
  ADB_CMD=("$ADB")
fi

if command -v "$ADB" >/dev/null 2>&1; then
  device_serial="${device_serial:-$("${ADB_CMD[@]}" get-serialno 2>/dev/null || true)}"
  device_model="${device_model:-$("${ADB_CMD[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)}"
  android_api="${android_api:-$("${ADB_CMD[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)}"
  abi="${abi:-$("${ADB_CMD[@]}" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r' || true)}"
fi

require_value() {
  local name="$1"
  local value="$2"
  if [[ -z "$value" ]]; then
    local reason
    reason="$(printf '%s' "$name" | sed -E 's/([a-z0-9])([A-Z])/\1-\2/g' | tr '[:upper:]' '[:lower:]')"
    fail device-metadata "$reason-missing" "Missing $name; provide it via environment when adb cannot read the device."
  fi
}

require_value deviceSerial "$device_serial"
require_value deviceModel "$device_model"
require_value androidApi "$android_api"
require_value abi "$abi"

mkdir -p "$(dirname "$OUT_FILE")"
{
  printf 'artifactSchema=PerfBaseline/v1\n'
  printf 'status=%s\n' "$STATUS"
  printf 'target=perf-baseline-record\n'
  printf 'owner=%s\n' "$EVIDENCE_OWNER"
  printf 'collectionCommand=%s\n' "$(command_line)"
  printf 'reproduciblePath=%s\n' "$OUT_FILE"
  printf 'deviceSerial=%s\n' "$device_serial"
  printf 'deviceModel=%s\n' "$device_model"
  printf 'androidApi=%s\n' "$android_api"
  printf 'abi=%s\n' "$abi"
  printf 'appVersion=%s\n' "$APP_VERSION"
  printf 'releaseArtifact=%s\n' "$RELEASE_ARTIFACT"
  printf 'releaseArtifactSha256=%s\n' "$(shasum -a 256 "$RELEASE_ARTIFACT" | awk '{print $1}')"
  printf 'modelId=%s\n' "$MODEL_ID"
  printf 'backend=%s\n' "$BACKEND"
  printf 'firstLaunchInteractiveMs=%s\n' "$FIRST_LAUNCH_INTERACTIVE_MS"
  printf 'modelLoadMs=%s\n' "$MODEL_LOAD_MS"
  printf 'firstTokenMs=%s\n' "$FIRST_TOKEN_MS"
  printf 'tokensPerSecond=%s\n' "$TOKENS_PER_SECOND"
  printf 'stopGenerationRecoveryMs=%s\n' "$STOP_GENERATION_RECOVERY_MS"
  printf 'gpuFallbackStatus=%s\n' "$GPU_FALLBACK_STATUS"
  printf 'visionInputMs=%s\n' "$VISION_INPUT_MS"
  printf 'memorySearch5kMs=%s\n' "$MEMORY_SEARCH_5K_MS"
  printf 'memoryPeakMb=%s\n' "$MEMORY_PEAK_MB"
  printf 'oomOrAnrObserved=%s\n' "$OOM_OR_ANR_OBSERVED"
  printf 'recordedAt=%s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
} > "$OUT_FILE"

verify_args=(
  --file "$OUT_FILE"
  --artifact-sha256 "$(shasum -a 256 "$RELEASE_ARTIFACT" | awk '{print $1}')"
  --app-version "$APP_VERSION"
  --report "$VERIFY_REPORT_FILE"
)
if [[ -n "$PERFORMANCE_KEY" ]]; then
  verify_args+=(--performance-key "$PERFORMANCE_KEY")
fi
if ! scripts/verify_perf_baseline.sh "${verify_args[@]}"; then
  verifier_reason="$(report_value "$VERIFY_REPORT_FILE" reason)"
  [[ -n "$verifier_reason" ]] || verifier_reason="perf-baseline-verification-failed"
  fail perf-baseline-verification "$verifier_reason" "Collected perf baseline did not pass verification."
fi

echo "Perf baseline written to $OUT_FILE"
