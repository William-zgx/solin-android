#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"

AVD_ROOT="${AVD_ROOT:-$HOME/.android/avd}"
REQUIRED_APIS="${REQUIRED_APIS:-28 32 33 34 36}"
EMULATOR_TAG="${EMULATOR_API_MATRIX_TAG:-google_apis}"
EMULATOR_ABI="${EMULATOR_API_MATRIX_ABI:-arm64-v8a}"
REPORT_FILE_WAS_DEFAULT=0
READINESS_REPORT_FILE_WAS_DEFAULT=0
[[ -z "${REPORT_FILE+x}" ]] && REPORT_FILE_WAS_DEFAULT=1
[[ -z "${READINESS_REPORT_FILE+x}" ]] && READINESS_REPORT_FILE_WAS_DEFAULT=1
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/regression-emulator-api-matrix-$(date +%Y%m%d-%H%M%S)}"
REPORT_FILE="${REPORT_FILE:-${ARTIFACT_DIR}/regression-emulator-api-matrix.properties}"
READINESS_REPORT_FILE="${READINESS_REPORT_FILE:-${ARTIFACT_DIR}/emulator-api-matrix-readiness.properties}"
CHECK_EMULATOR_API_MATRIX_SCRIPT="${CHECK_EMULATOR_API_MATRIX_SCRIPT:-scripts/check_emulator_api_matrix.sh}"
REGRESSION_EMULATOR_SCRIPT="${REGRESSION_EMULATOR_SCRIPT:-scripts/regression_emulator.sh}"
STOP_EMULATOR_AFTER_EACH="${STOP_EMULATOR_AFTER_EACH:-1}"
ALLOW_EXISTING_EMULATORS="${ALLOW_EXISTING_EMULATORS:-0}"
RELEASE_ARTIFACT_SHA256="${RELEASE_ARTIFACT_SHA256:-}"
CI_WORKFLOW="${REGRESSION_EMULATOR_API_MATRIX_WORKFLOW:-${GITHUB_WORKFLOW:-}}"
CI_JOB="${REGRESSION_EMULATOR_API_MATRIX_JOB:-${GITHUB_JOB:-}}"
CI_RUN_ID="${REGRESSION_EMULATOR_API_MATRIX_RUN_ID:-${GITHUB_RUN_ID:-}}"
CI_COMMIT_SHA="${REGRESSION_EMULATOR_API_MATRIX_COMMIT_SHA:-${GITHUB_SHA:-}}"
ADB_BIN="${ANDROID_SDK}/platform-tools/adb"

STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
FAILED_TARGET=""
FAILURE_REASON=""
PASSED_APIS=()
FAILED_APIS=()
SKIPPED_APIS=()
API_STATUS_LINES=()
READINESS_REPORT_SHA256=""

usage() {
  cat >&2 <<'EOF'
Usage: scripts/regression_emulator_api_matrix.sh [options]

Options:
  --artifact-dir <path>              Directory for matrix and per-API reports.
  --report <path>                    Write the top-level matrix report to this path.
  --readiness-report <path>          Write the readiness report to this path.
  --required-apis <list>             Space-separated API levels, for example "28 32 33 34 36".
  --avd-root <path>                  Directory containing *.avd folders.
  --tag <id>                         Emulator system image tag, default google_apis.
  --abi <abi>                        Emulator ABI, default arm64-v8a.
  --check-script <path>              Readiness checker script.
  --regression-script <path>         Per-API regression script.
  --allow-existing-emulators         Do not fail if an emulator is already running.
  --keep-emulators                   Do not stop each emulator after its API run.
  -h, --help                         Show this help text.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --artifact-dir)
      ARTIFACT_DIR="${2:?missing artifact directory}"
      if [[ "$REPORT_FILE_WAS_DEFAULT" == "1" ]]; then
        REPORT_FILE="$ARTIFACT_DIR/regression-emulator-api-matrix.properties"
      fi
      if [[ "$READINESS_REPORT_FILE_WAS_DEFAULT" == "1" ]]; then
        READINESS_REPORT_FILE="$ARTIFACT_DIR/emulator-api-matrix-readiness.properties"
      fi
      shift 2
      ;;
    --report)
      REPORT_FILE="${2:?missing report path}"
      shift 2
      ;;
    --readiness-report)
      READINESS_REPORT_FILE="${2:?missing readiness report path}"
      shift 2
      ;;
    --required-apis)
      REQUIRED_APIS="${2:?missing required API list}"
      shift 2
      ;;
    --avd-root)
      AVD_ROOT="${2:?missing AVD root}"
      shift 2
      ;;
    --tag)
      EMULATOR_TAG="${2:?missing emulator tag}"
      shift 2
      ;;
    --abi)
      EMULATOR_ABI="${2:?missing emulator ABI}"
      shift 2
      ;;
    --check-script)
      CHECK_EMULATOR_API_MATRIX_SCRIPT="${2:?missing check script path}"
      shift 2
      ;;
    --regression-script)
      REGRESSION_EMULATOR_SCRIPT="${2:?missing regression script path}"
      shift 2
      ;;
    --allow-existing-emulators)
      ALLOW_EXISTING_EMULATORS=1
      shift
      ;;
    --keep-emulators)
      STOP_EMULATOR_AFTER_EACH=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 2
      ;;
  esac
done

join_csv() {
  local IFS=,
  printf '%s' "$*"
}

join_array_csv() {
  local array_name="$1"
  local count
  eval "count=\${#${array_name}[@]}"
  if [[ "$count" -eq 0 ]]; then
    return 0
  fi
  eval "local IFS=,; printf '%s' \"\${${array_name}[*]}\""
}

report_value() {
  local file="$1"
  local key="$2"
  [[ -f "$file" ]] || return 0
  awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$file"
}

sha256_for() {
  local file="$1"
  shasum -a 256 "$file" | awk '{print $1}'
}

write_report() {
  local status="$1"
  local reason="${2:-}"
  mkdir -p "$(dirname "$REPORT_FILE")"
  {
    printf 'status=%s\n' "$status"
    printf 'target=regression-emulator-api-matrix\n'
    printf 'failedTarget=%s\n' "$FAILED_TARGET"
    printf 'reason=%s\n' "$reason"
    printf 'workflow=%s\n' "$CI_WORKFLOW"
    printf 'job=%s\n' "$CI_JOB"
    printf 'runId=%s\n' "$CI_RUN_ID"
    printf 'commitSha=%s\n' "$CI_COMMIT_SHA"
    printf 'started_at_utc=%s\n' "$STARTED_AT_UTC"
    printf 'finished_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'artifactDir=%s\n' "$ARTIFACT_DIR"
    printf 'releaseArtifactSha256=%s\n' "$RELEASE_ARTIFACT_SHA256"
    printf 'requiredApis=%s\n' "$(join_csv $REQUIRED_APIS)"
    printf 'tag=%s\n' "$EMULATOR_TAG"
    printf 'abi=%s\n' "$EMULATOR_ABI"
    printf 'readinessReportFile=%s\n' "$READINESS_REPORT_FILE"
    printf 'readinessReportSha256=%s\n' "$READINESS_REPORT_SHA256"
    printf 'passedApis=%s\n' "$(join_array_csv PASSED_APIS)"
    printf 'failedApis=%s\n' "$(join_array_csv FAILED_APIS)"
    printf 'skippedApis=%s\n' "$(join_array_csv SKIPPED_APIS)"
    if [[ "${#API_STATUS_LINES[@]}" -gt 0 ]]; then
      printf '%s\n' "${API_STATUS_LINES[@]}"
    fi
  } > "$REPORT_FILE"
}

fail() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  write_report failed "$FAILURE_REASON"
  echo "$*" >&2
  exit 1
}

running_emulators() {
  [[ -x "$ADB_BIN" ]] || return 0
  "$ADB_BIN" devices | awk 'NR > 1 && $1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}'
}

find_avd_for_api() {
  local api="$1"
  local config image target abi tag avd_dir avd_name
  shopt -s nullglob
  for config in "$AVD_ROOT"/*.avd/config.ini; do
    image="$(awk -F= '$1 == "image.sysdir.1" {print $2; exit}' "$config")"
    target="$(awk -F= '$1 == "target" {print $2; exit}' "$config")"
    abi="$(awk -F= '$1 == "abi.type" {print $2; exit}' "$config")"
    tag="$(awk -F= '$1 == "tag.id" {print $2; exit}' "$config")"
    if [[ "$image" != *"system-images/android-${api}/${EMULATOR_TAG}/${EMULATOR_ABI}"* ]] &&
      [[ "$target" != "android-${api}" || "$abi" != "$EMULATOR_ABI" || "$tag" != "$EMULATOR_TAG" ]]; then
      continue
    fi
    avd_dir="$(basename "$(dirname "$config")")"
    avd_name="${avd_dir%.avd}"
    printf '%s\n' "$avd_name"
    return 0
  done
  return 1
}

stop_emulator_for_report() {
  local report="$1"
  local serial
  [[ "$STOP_EMULATOR_AFTER_EACH" == "1" ]] || return 0
  [[ -x "$ADB_BIN" ]] || return 0
  serial="$(report_value "$report" serial)"
  [[ "$serial" == emulator-* ]] || return 0
  "$ADB_BIN" -s "$serial" emu kill >/dev/null 2>&1 || true
}

mkdir -p "$ARTIFACT_DIR"
if [[ "$ALLOW_EXISTING_EMULATORS" != "1" ]]; then
  existing_emulators="$(running_emulators | paste -sd, -)"
  if [[ -n "$existing_emulators" ]]; then
    fail emulator-state running-emulator-present \
      "Running emulator(s) present before matrix execution: $existing_emulators. Stop them first or set ALLOW_EXISTING_EMULATORS=1."
  fi
fi

set +e
ANDROID_HOME="$ANDROID_HOME" \
  ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" \
  AVD_ROOT="$AVD_ROOT" \
  REQUIRED_APIS="$REQUIRED_APIS" \
  EMULATOR_API_MATRIX_TAG="$EMULATOR_TAG" \
  EMULATOR_API_MATRIX_ABI="$EMULATOR_ABI" \
  REPORT_FILE="$READINESS_REPORT_FILE" \
  "$CHECK_EMULATOR_API_MATRIX_SCRIPT"
READINESS_STATUS=$?
set -e
if [[ -f "$READINESS_REPORT_FILE" ]]; then
  READINESS_REPORT_SHA256="$(sha256_for "$READINESS_REPORT_FILE")"
fi
if [[ "$READINESS_STATUS" -ne 0 ]]; then
  readiness_reason="$(report_value "$READINESS_REPORT_FILE" reason)"
  fail readiness "${readiness_reason:-emulator-api-matrix-readiness-failed}" \
    "Emulator API matrix readiness failed; see $READINESS_REPORT_FILE."
fi

for api in $REQUIRED_APIS; do
  avd_name="$(find_avd_for_api "$api")" ||
    fail "api-$api-avd" "api-$api-avd-not-found" "No matching AVD found for API $api."
  api_artifact_dir="$ARTIFACT_DIR/api-$api"
  api_report_file="$api_artifact_dir/regression-emulator.properties"
  mkdir -p "$api_artifact_dir"

  set +e
  ANDROID_HOME="$ANDROID_HOME" \
    ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" \
    AVD_NAME="$avd_name" \
    ARTIFACT_DIR="$api_artifact_dir" \
    RELEASE_ARTIFACT_SHA256="$RELEASE_ARTIFACT_SHA256" \
    REGRESSION_REPORT_FILE="$api_report_file" \
    EMULATOR_REPORT_FILE="$api_artifact_dir/emulator-verification.properties" \
    DEVICE_REPORT_FILE="$api_artifact_dir/device-verification.properties" \
    "$REGRESSION_EMULATOR_SCRIPT"
  api_status=$?
  set -e
  stop_emulator_for_report "$api_report_file"

  if [[ "$api_status" -ne 0 ]]; then
    child_reason="$(report_value "$api_report_file" reason)"
    [[ -n "$child_reason" ]] || child_reason="regression-failed"
    FAILED_APIS+=("$api")
    API_STATUS_LINES+=("api${api}Status=failed")
    API_STATUS_LINES+=("api${api}Avd=$avd_name")
    API_STATUS_LINES+=("api${api}ReportFile=$api_report_file")
    fail "api-$api-regression" "api-$api-regression-$child_reason" \
      "Emulator regression failed for API $api; see $api_report_file."
  fi

  if [[ ! -f "$api_report_file" ]]; then
    FAILED_APIS+=("$api")
    API_STATUS_LINES+=("api${api}Status=failed")
    API_STATUS_LINES+=("api${api}Avd=$avd_name")
    API_STATUS_LINES+=("api${api}ReportFile=$api_report_file")
    fail "api-$api-report" "api-$api-report-missing" \
      "Emulator regression report missing for API $api: $api_report_file."
  fi
  child_status="$(report_value "$api_report_file" status)"
  child_reason="$(report_value "$api_report_file" reason)"
  if [[ "$child_status" == "skipped" && "${ALLOW_EMULATOR_INFRA_UNAVAILABLE:-0}" == "1" && "$child_reason" == "emulator-infra-hvf-unsupported" ]]; then
    SKIPPED_APIS+=("$api")
    API_STATUS_LINES+=("api${api}Status=skipped")
    API_STATUS_LINES+=("api${api}Avd=$avd_name")
    API_STATUS_LINES+=("api${api}ReportFile=$api_report_file")
    API_STATUS_LINES+=("api${api}ReportSha256=$(sha256_for "$api_report_file")")
    continue
  fi
  if [[ "$child_status" != "passed" ]]; then
    FAILED_APIS+=("$api")
    fail "api-$api-report" "api-$api-report-not-passed" \
      "Emulator regression report for API $api is not passed."
  fi
  if [[ "$(report_value "$api_report_file" target)" != "regression-emulator" ]]; then
    FAILED_APIS+=("$api")
    fail "api-$api-report" "api-$api-report-target-invalid" \
      "Emulator regression report for API $api has invalid target."
  fi
  if [[ "$(report_value "$api_report_file" api_level)" != "$api" ]]; then
    FAILED_APIS+=("$api")
    fail "api-$api-report" "api-$api-report-api-mismatch" \
      "Emulator regression report for API $api does not match requested API."
  fi
  if [[ "$(report_value "$api_report_file" abi)" != *"$EMULATOR_ABI"* ]]; then
    FAILED_APIS+=("$api")
    fail "api-$api-report" "api-$api-report-abi-mismatch" \
      "Emulator regression report for API $api does not include ABI $EMULATOR_ABI."
  fi

  PASSED_APIS+=("$api")
  API_STATUS_LINES+=("api${api}Status=passed")
  API_STATUS_LINES+=("api${api}Avd=$avd_name")
  API_STATUS_LINES+=("api${api}ReportFile=$api_report_file")
  API_STATUS_LINES+=("api${api}ReportSha256=$(sha256_for "$api_report_file")")
done

if [[ "${#SKIPPED_APIS[@]}" -gt 0 ]]; then
  FAILED_TARGET="emulator-infra"
  FAILURE_REASON="emulator-infra-hvf-unsupported"
  write_report skipped "$FAILURE_REASON"
  echo "Emulator API matrix regression skipped for infra-limited APIs: $(join_array_csv SKIPPED_APIS)"
  exit 0
fi

write_report passed ""
echo "Emulator API matrix regression passed for APIs: $(join_array_csv PASSED_APIS)"
