#!/usr/bin/env bash
set -euo pipefail

# Orchestrates a real RC performance collection from a connected Android device using the
# `rcPerfRelease` build variant. It installs the release-like variant (minify/shrink/signing
# inherited from release; RC_PERF_ENABLED=true), drives the in-app RC perf harness through a
# controlled broadcast, scrapes the structured result, samples the live memory peak, scans for
# OOM/ANR, and then feeds the measured metrics into scripts/collect_perf_baseline.sh.
#
# Hard constraints (do not relax):
#  - tokensPerSecond is only ever the raw LiteRT decode benchmark value reported by the harness.
#    This script never estimates throughput; if the harness fails it surfaces the failure.
#  - This script NEVER clears app data or deletes downloaded model files. There is no `pm clear`,
#    no `rm` of model directories, and no app-data reset. Already-downloaded models are reused.
#  - API secrets are never passed through broadcast extras; the rcPerf harness only uses the
#    already-installed local model state on the selected device.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
ADB_BIN="${ADB:-${ANDROID_SDK}/platform-tools/adb}"

ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/rc/rc-perf-collect-$(date +%Y%m%d-%H%M%S)}"
REPORT_FILE="${REPORT_FILE:-${ARTIFACT_DIR}/rc-perf-collect.properties}"
HARNESS_RESULT_FILE="${HARNESS_RESULT_FILE:-${ARTIFACT_DIR}/rc-perf-harness-result.properties}"
LOGCAT_FILE="${LOGCAT_FILE:-${ARTIFACT_DIR}/logcat.txt}"
OUT_FILE="${OUT_FILE:-build/verification/rc/perf-baseline.properties}"

PACKAGE_NAME="com.bytedance.zgx.pocketmind"
MAIN_ACTIVITY="${MAIN_ACTIVITY:-${PACKAGE_NAME}/.MainActivity}"
HARNESS_COMPONENT="${PACKAGE_NAME}/.rcperf.RcPerfHarnessService"
RC_PERF_ACTION="${PACKAGE_NAME}.rcperf.RUN"
RC_PERF_APK="${RC_PERF_APK:-app/build/outputs/apk/rcPerfRelease/app-rcPerfRelease.apk}"
RC_PERF_VARIANT_TASK="${RC_PERF_VARIANT_TASK:-assembleRcPerfRelease}"
HARNESS_REQUESTED_BACKEND="${HARNESS_REQUESTED_BACKEND:-GPU}"

# The release artifact recorded in the perf baseline is the production release under test (signed),
# distinct from the debug-signed rcPerfRelease measurement APK installed on the device.
RELEASE_ARTIFACT="${RELEASE_ARTIFACT:-}"
APP_VERSION="${APP_VERSION:-}"

SKIP_BUILD="${SKIP_BUILD:-0}"
SKIP_INSTALL="${SKIP_INSTALL:-0}"
HARNESS_TIMEOUT_SECONDS="${HARNESS_TIMEOUT_SECONDS:-900}"
STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

SELECTED_SERIAL=""
API_LEVEL=""
ABI_LIST=""
STATUS="failed"
FAILED_TARGET=""
FAILURE_REASON=""
MEMORY_PEAK_MB=""
OOM_OR_ANR_OBSERVED="false"
HARNESS_PROGRESS=""

mkdir -p "$ARTIFACT_DIR"

write_report() {
  local exit_code="$1"
  [[ "$exit_code" -eq 0 ]] && STATUS="passed"
  {
    echo "artifactSchema=RcPerfCollection/v1"
    echo "status=$STATUS"
    echo "exit_code=$exit_code"
    echo "target=rc-perf-collector"
    echo "failedTarget=${FAILED_TARGET:-}"
    echo "reason=${FAILURE_REASON:-}"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=$SELECTED_SERIAL"
    echo "api_level=$API_LEVEL"
    echo "abi=$ABI_LIST"
    echo "rc_perf_apk=$RC_PERF_APK"
    echo "release_artifact=$RELEASE_ARTIFACT"
    echo "harness_result_file=$HARNESS_RESULT_FILE"
    echo "perf_baseline_file=$OUT_FILE"
    echo "logcat_file=$LOGCAT_FILE"
    echo "memoryPeakMb=${MEMORY_PEAK_MB:-}"
    echo "oomOrAnrObserved=$OOM_OR_ANR_OBSERVED"
    echo "harnessProgress=${HARNESS_PROGRESS:-}"
    echo "runner=rc_perf_release_broadcast"
    echo "preserves_model_data=true"
  } > "$REPORT_FILE"
  echo "RC perf collection report: $REPORT_FILE"
}

on_exit() {
  local status="$?"
  trap - EXIT
  if [[ -n "$SELECTED_SERIAL" && -x "$ADB_BIN" ]]; then
    "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -d > "$LOGCAT_FILE" 2>/dev/null || true
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
  exit 1
}

stop_app_preserving_data() {
  if [[ -n "$SELECTED_SERIAL" ]]; then
    "${ADB[@]}" shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true
  fi
}

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    local reason
    reason="$(printf '%s' "$name" | tr '[:upper:]_' '[:lower:]-')"
    fail_with_reason environment "missing-$reason" "Missing required environment variable: $name"
  fi
}

require_env RELEASE_ARTIFACT
require_env APP_VERSION

if [[ ! -f "$RELEASE_ARTIFACT" ]]; then
  fail_with_reason input-artifact release-artifact-missing "Release artifact is missing: $RELEASE_ARTIFACT"
fi

if [[ ! -x "$ADB_BIN" ]] && ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  fail_with_reason adb adb-not-found "adb not found at $ADB_BIN; set ADB or ANDROID_SDK_ROOT."
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
# Primary ABI and model are recorded in the perf baseline (verifier requires abi=arm64-v8a and a
# non-empty deviceModel). Resolve them here so we can hand them to collect_perf_baseline.sh
# directly: that downstream script cannot reuse our `-s <serial>` adb wrapper.
DEVICE_ABI="$("${ADB[@]}" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r' || true)"
DEVICE_MODEL="$("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"

# A physical arm64 device is required: emulators cannot produce a valid perf baseline.
if [[ "$SELECTED_SERIAL" == emulator-* ]]; then
  fail_with_reason device-selection emulator-not-allowed \
    "RC perf collection requires a physical device; emulator serial $SELECTED_SERIAL is not allowed."
fi

"${ADB[@]}" logcat -c >/dev/null 2>&1 || true

if [[ "$SKIP_BUILD" != "1" ]]; then
  "$GRADLE_CMD" "$RC_PERF_VARIANT_TASK"
fi
if [[ ! -f "$RC_PERF_APK" ]]; then
  fail_with_reason rc-perf-apk rc-perf-apk-missing "rcPerfRelease APK is missing: $RC_PERF_APK"
fi
if [[ "$SKIP_INSTALL" != "1" ]]; then
  # -r reinstalls keeping data; we never uninstall or clear data so downloaded models are reused.
  "${ADB[@]}" install -r "$RC_PERF_APK"
fi

# First-launch interactive time from a cold start of the main activity.
"${ADB[@]}" shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true
LAUNCH_OUTPUT="$("${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" 2>/dev/null | tr -d '\r' || true)"
FIRST_LAUNCH_INTERACTIVE_MS="$(awk -F: '/^TotalTime/ {gsub(/ /,"",$2); print $2; exit}' <<<"$LAUNCH_OUTPUT")"
if [[ -z "$FIRST_LAUNCH_INTERACTIVE_MS" ]]; then
  FIRST_LAUNCH_INTERACTIVE_MS="$(awk -F: '/^WaitTime/ {gsub(/ /,"",$2); print $2; exit}' <<<"$LAUNCH_OUTPUT")"
fi
if [[ -z "$FIRST_LAUNCH_INTERACTIVE_MS" ]]; then
  fail_with_reason first-launch first-launch-time-missing \
    "Could not read first-launch interactive time from am start -W output."
fi
sleep 1

REQUEST_ID="rc-perf-$$-$(date +%s)"
EXTERNAL_RESULT_FILE="/sdcard/Android/data/${PACKAGE_NAME}/files/rc_perf_result_${REQUEST_ID}.properties"

broadcast_extras=(
  --es requestId "$REQUEST_ID"
  --es backend "$HARNESS_REQUESTED_BACKEND"
)

"${ADB[@]}" shell am start-foreground-service -a "$RC_PERF_ACTION" -n "$HARNESS_COMPONENT" "${broadcast_extras[@]}" >/dev/null

sample_memory_peak_mb() {
  local meminfo total_kb total_mb
  meminfo="$("${ADB[@]}" shell dumpsys meminfo "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' || true)"
  total_kb="$(awk '/^[[:space:]]*TOTAL[[:space:]]/ {print $2; exit}' <<<"$meminfo")"
  [[ -z "$total_kb" ]] && total_kb="$(awk '/TOTAL PSS:/ {print $3; exit}' <<<"$meminfo")"
  if [[ -n "$total_kb" && "$total_kb" =~ ^[0-9]+$ ]]; then
    total_mb=$(( (total_kb + 1023) / 1024 ))
    if [[ -z "$MEMORY_PEAK_MB" || "$total_mb" -gt "$MEMORY_PEAK_MB" ]]; then
      MEMORY_PEAK_MB="$total_mb"
    fi
  fi
}

read_harness_result() {
  local tmp_result
  tmp_result="${HARNESS_RESULT_FILE}.tmp"
  if "${ADB[@]}" exec-out cat "$EXTERNAL_RESULT_FILE" 2>/dev/null > "$tmp_result" &&
    grep -Fq "rcPerfSchema=" "$tmp_result"; then
    mv "$tmp_result" "$HARNESS_RESULT_FILE"
    return 0
  fi
  rm -f "$tmp_result"
  read_harness_result_from_logcat
}

decode_base64_payload() {
  local payload="$1"
  local target="$2"
  if printf '%s' "$payload" | base64 --decode > "$target" 2>/dev/null; then
    return 0
  fi
  printf '%s' "$payload" | base64 -D > "$target" 2>/dev/null
}

read_harness_result_from_logcat() {
  local encoded tmp_result
  tmp_result="${HARNESS_RESULT_FILE}.tmp"
  encoded="$(
    "${ADB[@]}" logcat -d -s RcPerfHarness:I 2>/dev/null |
      tr -d '\r' |
      awk -v request="$REQUEST_ID" '
        index($0, "rcPerfResultBase64 requestId=" request " payload=") {
          sub(/^.* payload=/, "")
          line = $0
        }
        END { print line }
      '
  )"
  [[ -n "$encoded" ]] || return 1
  if decode_base64_payload "$encoded" "$tmp_result" && grep -Fq "rcPerfSchema=" "$tmp_result"; then
    mv "$tmp_result" "$HARNESS_RESULT_FILE"
    return 0
  fi
  rm -f "$tmp_result"
  return 1
}

update_harness_progress() {
  local progress
  progress="$(
    "${ADB[@]}" logcat -d -s RcPerfHarness:I 2>/dev/null |
      tr -d '\r' |
      awk -v request="$REQUEST_ID" '
        index($0, "rcPerfProgress requestId=" request " stage=") {
          sub(/^.* stage=/, "")
          line = $0
        }
        END { print line }
      '
  )"
  [[ -n "$progress" ]] && HARNESS_PROGRESS="$progress"
  return 0
}

deadline=$(( $(date +%s) + HARNESS_TIMEOUT_SECONDS ))
result_ready=0
while [[ "$(date +%s)" -lt "$deadline" ]]; do
  sample_memory_peak_mb
  update_harness_progress
  if read_harness_result; then
    result_ready=1
    break
  fi
  sleep 3
done

if [[ "$result_ready" != "1" ]]; then
  update_harness_progress
  stop_app_preserving_data
  fail_with_reason harness harness-timeout \
    "Timed out after ${HARNESS_TIMEOUT_SECONDS}s waiting for RC perf harness result at $EXTERNAL_RESULT_FILE; last progress: ${HARNESS_PROGRESS:-unknown}."
fi

harness_value() {
  awk -F= -v key="$1" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$HARNESS_RESULT_FILE"
}

RESULT_TYPE="$(harness_value resultType)"
if [[ "$RESULT_TYPE" != "success" ]]; then
  reason="$(harness_value reason)"
  fail_with_reason harness "harness-${RESULT_TYPE:-unknown}" \
    "RC perf harness did not succeed: ${reason:-no-reason-reported}"
fi

MODEL_ID="$(harness_value modelId)"
BACKEND="$(harness_value backend)"
MODEL_LOAD_MS="$(harness_value modelLoadMs)"
FIRST_TOKEN_MS="$(harness_value firstTokenMs)"
TOKENS_PER_SECOND="$(harness_value tokensPerSecond)"
STOP_GENERATION_RECOVERY_MS="$(harness_value stopGenerationRecoveryMs)"
GPU_FALLBACK_STATUS="$(harness_value gpuFallbackStatus)"
VISION_INPUT_MS="$(harness_value visionInputMs)"
MEMORY_SEARCH_5K_MS="$(harness_value memorySearch5kMs)"

sample_memory_peak_mb
if [[ -z "$MEMORY_PEAK_MB" ]]; then
  fail_with_reason memory memory-peak-missing "Could not sample memory peak via dumpsys meminfo."
fi

# Scan logcat for OOM/ANR signals around the run.
RUN_LOGCAT="$("${ADB[@]}" logcat -d 2>/dev/null | tr -d '\r' || true)"
if grep -Eq 'ANR in |OutOfMemoryError|lowmemorykiller|Process .* died' <<<"$RUN_LOGCAT"; then
  if grep -Eq "$PACKAGE_NAME" <<<"$(grep -E 'ANR in |OutOfMemoryError|lowmemorykiller|Process .* died' <<<"$RUN_LOGCAT")"; then
    OOM_OR_ANR_OBSERVED="true"
  fi
fi

# Hand the measured metrics to the perf baseline collector + verifier. The release artifact and
# app version describe the production release under test, recorded for provenance. Device metadata
# is passed explicitly because the downstream collector cannot reuse our `-s <serial>` adb wrapper.
OUT_FILE="$OUT_FILE" \
RELEASE_ARTIFACT="$RELEASE_ARTIFACT" \
ANDROID_SERIAL="$SELECTED_SERIAL" \
DEVICE_MODEL="$DEVICE_MODEL" \
ANDROID_API="$API_LEVEL" \
ABI="$DEVICE_ABI" \
APP_VERSION="$APP_VERSION" \
MODEL_ID="$MODEL_ID" \
BACKEND="$BACKEND" \
FIRST_LAUNCH_INTERACTIVE_MS="$FIRST_LAUNCH_INTERACTIVE_MS" \
MODEL_LOAD_MS="$MODEL_LOAD_MS" \
FIRST_TOKEN_MS="$FIRST_TOKEN_MS" \
TOKENS_PER_SECOND="$TOKENS_PER_SECOND" \
STOP_GENERATION_RECOVERY_MS="$STOP_GENERATION_RECOVERY_MS" \
GPU_FALLBACK_STATUS="$GPU_FALLBACK_STATUS" \
VISION_INPUT_MS="$VISION_INPUT_MS" \
MEMORY_SEARCH_5K_MS="$MEMORY_SEARCH_5K_MS" \
MEMORY_PEAK_MB="$MEMORY_PEAK_MB" \
OOM_OR_ANR_OBSERVED="$OOM_OR_ANR_OBSERVED" \
  scripts/collect_perf_baseline.sh

echo "RC perf baseline collected from device $SELECTED_SERIAL into $OUT_FILE"
