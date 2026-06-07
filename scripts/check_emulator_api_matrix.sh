#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
AVD_ROOT="${AVD_ROOT:-$HOME/.android/avd}"
REQUIRED_APIS="${REQUIRED_APIS:-28 32 33 34 36}"
EMULATOR_TAG="${EMULATOR_API_MATRIX_TAG:-google_apis}"
EMULATOR_ABI="${EMULATOR_API_MATRIX_ABI:-arm64-v8a}"
REPORT_FILE="${REPORT_FILE:-build/verification/emulator-api-matrix-readiness.properties}"
SDKMANAGER_CMD="${SDKMANAGER_CMD:-}"

FAILED_TARGET=""
FAILURE_REASON=""
INSTALLED_SYSTEM_IMAGE_APIS=()
AVAILABLE_AVD_APIS=()
MISSING_SYSTEM_IMAGE_APIS=()
MISSING_AVD_APIS=()

usage() {
  cat >&2 <<'EOF'
Usage: scripts/check_emulator_api_matrix.sh [options]

Options:
  --report <path>         Write the readiness report to this path.
  --required-apis <list>  Space-separated API levels, for example "28 32 33 34 36".
  --avd-root <path>       Directory containing *.avd folders.
  --tag <id>              Emulator system image tag, default google_apis.
  --abi <abi>             Emulator ABI, default arm64-v8a.
  --sdkmanager <path>     sdkmanager executable path.
  -h, --help              Show this help text.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --report)
      REPORT_FILE="${2:?missing report path}"
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
    --sdkmanager)
      SDKMANAGER_CMD="${2:?missing sdkmanager path}"
      shift 2
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

write_report() {
  local status="$1"
  local reason="${2:-}"
  mkdir -p "$(dirname "$REPORT_FILE")"
  {
    printf 'status=%s\n' "$status"
    printf 'target=emulator-api-matrix-readiness\n'
    printf 'failedTarget=%s\n' "$FAILED_TARGET"
    printf 'reason=%s\n' "$reason"
    printf 'androidSdk=%s\n' "$ANDROID_SDK"
    printf 'sdkmanager=%s\n' "$SDKMANAGER_CMD"
    printf 'avdRoot=%s\n' "$AVD_ROOT"
    printf 'requiredApis=%s\n' "$(join_csv $REQUIRED_APIS)"
    printf 'tag=%s\n' "$EMULATOR_TAG"
    printf 'abi=%s\n' "$EMULATOR_ABI"
    printf 'installedSystemImageApis=%s\n' "$(join_array_csv INSTALLED_SYSTEM_IMAGE_APIS)"
    printf 'availableAvdApis=%s\n' "$(join_array_csv AVAILABLE_AVD_APIS)"
    printf 'missingSystemImageApis=%s\n' "$(join_array_csv MISSING_SYSTEM_IMAGE_APIS)"
    printf 'missingAvdApis=%s\n' "$(join_array_csv MISSING_AVD_APIS)"
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

find_sdkmanager() {
  if [[ -n "$SDKMANAGER_CMD" ]]; then
    return 0
  fi
  if command -v sdkmanager >/dev/null 2>&1; then
    SDKMANAGER_CMD="$(command -v sdkmanager)"
    return 0
  fi
  if [[ -x "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
    SDKMANAGER_CMD="$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager"
    return 0
  fi
  if [[ -x "$ANDROID_SDK/tools/bin/sdkmanager" ]]; then
    SDKMANAGER_CMD="$ANDROID_SDK/tools/bin/sdkmanager"
    return 0
  fi
}

if ! find_sdkmanager || [[ -z "$SDKMANAGER_CMD" || ! -x "$SDKMANAGER_CMD" ]]; then
  fail sdkmanager sdkmanager-missing "Android sdkmanager not found; cannot check emulator API matrix readiness."
fi

SDKMANAGER_OUTPUT="$(mktemp)"
trap 'rm -f "$SDKMANAGER_OUTPUT"' EXIT
if ! "$SDKMANAGER_CMD" --sdk_root="$ANDROID_SDK" --list_installed > "$SDKMANAGER_OUTPUT"; then
  fail sdkmanager sdkmanager-list-installed-failed "sdkmanager --list_installed failed."
fi

has_installed_system_image() {
  local api="$1"
  local package="system-images;android-${api};${EMULATOR_TAG};${EMULATOR_ABI}"
  grep -qF "$package" "$SDKMANAGER_OUTPUT"
}

has_avd_for_api() {
  local api="$1"
  local config image target abi tag
  shopt -s nullglob
  for config in "$AVD_ROOT"/*.avd/config.ini; do
    image="$(awk -F= '$1 == "image.sysdir.1" {print $2; exit}' "$config")"
    target="$(awk -F= '$1 == "target" {print $2; exit}' "$config")"
    abi="$(awk -F= '$1 == "abi.type" {print $2; exit}' "$config")"
    tag="$(awk -F= '$1 == "tag.id" {print $2; exit}' "$config")"
    if [[ "$image" == *"system-images/android-${api}/${EMULATOR_TAG}/${EMULATOR_ABI}"* ]]; then
      return 0
    fi
    if [[ "$target" == "android-${api}" && "$abi" == "$EMULATOR_ABI" && "$tag" == "$EMULATOR_TAG" ]]; then
      return 0
    fi
  done
  return 1
}

REASONS=()
for api in $REQUIRED_APIS; do
  if has_installed_system_image "$api"; then
    INSTALLED_SYSTEM_IMAGE_APIS+=("$api")
  else
    MISSING_SYSTEM_IMAGE_APIS+=("$api")
    REASONS+=("missing-system-image-api-$api")
  fi

  if has_avd_for_api "$api"; then
    AVAILABLE_AVD_APIS+=("$api")
  else
    MISSING_AVD_APIS+=("$api")
    REASONS+=("missing-avd-api-$api")
  fi
done

if [[ "${#REASONS[@]}" -gt 0 ]]; then
  FAILED_TARGET="api-matrix-readiness"
  FAILURE_REASON="$(join_csv "${REASONS[@]}")"
  write_report failed "$FAILURE_REASON"
  echo "Emulator API matrix readiness is incomplete." >&2
  exit 1
fi

write_report passed ""
echo "Emulator API matrix readiness passed."
