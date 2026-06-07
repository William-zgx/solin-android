#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
AVD_ROOT="${AVD_ROOT:-$HOME/.android/avd}"
REQUIRED_APIS="${REQUIRED_APIS:-28 32 33 34 36}"
EMULATOR_TAG="${EMULATOR_API_MATRIX_TAG:-google_apis}"
EMULATOR_ABI="${EMULATOR_API_MATRIX_ABI:-arm64-v8a}"
AVD_NAME_PREFIX="${AVD_NAME_PREFIX:-pocketmind_api}"
APPLY="${APPLY:-0}"
REPORT_FILE="${REPORT_FILE:-build/verification/emulator-api-matrix-prepare.properties}"
SDKMANAGER_CMD="${SDKMANAGER_CMD:-}"
AVDMANAGER_CMD="${AVDMANAGER_CMD:-}"

FAILED_TARGET=""
FAILURE_REASON=""
MISSING_SYSTEM_IMAGE_APIS=()
MISSING_AVD_APIS=()
INSTALL_PACKAGES=()
CREATE_AVD_COMMANDS=()

usage() {
  cat >&2 <<'EOF'
Usage:
  scripts/prepare_emulator_api_matrix.sh
  APPLY=1 scripts/prepare_emulator_api_matrix.sh

Options:
  --report <path>         Write the preparation report to this path.
  --required-apis <list>  Space-separated API levels, for example "28 32 33 34 36".
  --avd-root <path>       Directory containing *.avd folders.
  --tag <id>              Emulator system image tag, default google_apis.
  --abi <abi>             Emulator ABI, default arm64-v8a.
  --avd-prefix <prefix>   AVD name prefix, default pocketmind_api.
  --apply                 Install missing SDK packages and create missing AVDs.
  --sdkmanager <path>     sdkmanager executable path.
  --avdmanager <path>     avdmanager executable path.
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
    --avd-prefix)
      AVD_NAME_PREFIX="${2:?missing AVD name prefix}"
      shift 2
      ;;
    --apply)
      APPLY=1
      shift
      ;;
    --sdkmanager)
      SDKMANAGER_CMD="${2:?missing sdkmanager path}"
      shift 2
      ;;
    --avdmanager)
      AVDMANAGER_CMD="${2:?missing avdmanager path}"
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

write_report() {
  local status="$1"
  local reason="${2:-}"
  mkdir -p "$(dirname "$REPORT_FILE")"
  {
    printf 'status=%s\n' "$status"
    printf 'target=emulator-api-matrix-prepare\n'
    printf 'failedTarget=%s\n' "$FAILED_TARGET"
    printf 'reason=%s\n' "$reason"
    printf 'androidSdk=%s\n' "$ANDROID_SDK"
    printf 'avdRoot=%s\n' "$AVD_ROOT"
    printf 'requiredApis=%s\n' "$(join_csv $REQUIRED_APIS)"
    printf 'tag=%s\n' "$EMULATOR_TAG"
    printf 'abi=%s\n' "$EMULATOR_ABI"
    printf 'apply=%s\n' "$APPLY"
    printf 'sdkmanager=%s\n' "$SDKMANAGER_CMD"
    printf 'avdmanager=%s\n' "$AVDMANAGER_CMD"
    printf 'missingSystemImageApis=%s\n' "$(join_csv "${MISSING_SYSTEM_IMAGE_APIS[@]:-}")"
    printf 'missingAvdApis=%s\n' "$(join_csv "${MISSING_AVD_APIS[@]:-}")"
    printf 'installPackages=%s\n' "$(join_csv "${INSTALL_PACKAGES[@]:-}")"
    printf 'createAvdCommands=%s\n' "$(join_csv "${CREATE_AVD_COMMANDS[@]:-}")"
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

find_tool() {
  local output_var="$1"
  local executable="$2"
  local configured="$3"
  if [[ -n "$configured" ]]; then
    printf -v "$output_var" '%s' "$configured"
    return
  fi
  if command -v "$executable" >/dev/null 2>&1; then
    printf -v "$output_var" '%s' "$(command -v "$executable")"
    return
  fi
  if [[ -x "$ANDROID_SDK/cmdline-tools/latest/bin/$executable" ]]; then
    printf -v "$output_var" '%s' "$ANDROID_SDK/cmdline-tools/latest/bin/$executable"
    return
  fi
  if [[ -x "$ANDROID_SDK/tools/bin/$executable" ]]; then
    printf -v "$output_var" '%s' "$ANDROID_SDK/tools/bin/$executable"
    return
  fi
}

find_tool SDKMANAGER_CMD sdkmanager "$SDKMANAGER_CMD"
find_tool AVDMANAGER_CMD avdmanager "$AVDMANAGER_CMD"
[[ -n "$SDKMANAGER_CMD" && -x "$SDKMANAGER_CMD" ]] ||
  fail sdkmanager sdkmanager-missing "Android sdkmanager not found."
[[ -n "$AVDMANAGER_CMD" && -x "$AVDMANAGER_CMD" ]] ||
  fail avdmanager avdmanager-missing "Android avdmanager not found."

SDKMANAGER_OUTPUT="$(mktemp)"
trap 'rm -f "$SDKMANAGER_OUTPUT"' EXIT
if ! "$SDKMANAGER_CMD" --sdk_root="$ANDROID_SDK" --list_installed > "$SDKMANAGER_OUTPUT"; then
  fail sdkmanager sdkmanager-list-installed-failed "sdkmanager --list_installed failed."
fi

has_installed_package() {
  local package="$1"
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

for api in $REQUIRED_APIS; do
  platform_package="platforms;android-${api}"
  image_package="system-images;android-${api};${EMULATOR_TAG};${EMULATOR_ABI}"
  if ! has_installed_package "$image_package"; then
    MISSING_SYSTEM_IMAGE_APIS+=("$api")
    if ! has_installed_package "$platform_package"; then
      INSTALL_PACKAGES+=("$platform_package")
    fi
    INSTALL_PACKAGES+=("$image_package")
  fi
  if ! has_avd_for_api "$api"; then
    MISSING_AVD_APIS+=("$api")
    avd_name="${AVD_NAME_PREFIX}${api}_${EMULATOR_ABI//-/_}"
    CREATE_AVD_COMMANDS+=("echo no | $AVDMANAGER_CMD create avd --force --name $avd_name --package $image_package")
  fi
done

if [[ "${#INSTALL_PACKAGES[@]}" -eq 0 && "${#CREATE_AVD_COMMANDS[@]}" -eq 0 ]]; then
  write_report passed ""
  echo "Emulator API matrix environment is already prepared."
  exit 0
fi

if [[ "$APPLY" != "1" ]]; then
  write_report pending apply-required
  echo "Emulator API matrix preparation dry-run. Re-run with APPLY=1 to install/create." >&2
  if [[ "${#INSTALL_PACKAGES[@]}" -gt 0 ]]; then
    printf 'Missing SDK packages:\n' >&2
    printf '  %s\n' "${INSTALL_PACKAGES[@]}" >&2
  fi
  if [[ "${#CREATE_AVD_COMMANDS[@]}" -gt 0 ]]; then
    printf 'Missing AVD commands:\n' >&2
    printf '  %s\n' "${CREATE_AVD_COMMANDS[@]}" >&2
  fi
  exit 0
fi

if [[ "${#INSTALL_PACKAGES[@]}" -gt 0 ]]; then
  set +o pipefail
  yes | "$SDKMANAGER_CMD" --sdk_root="$ANDROID_SDK" "${INSTALL_PACKAGES[@]}"
  sdkmanager_status="${PIPESTATUS[1]}"
  set -o pipefail
  [[ "$sdkmanager_status" -eq 0 ]] ||
    fail sdkmanager sdkmanager-install-failed "sdkmanager failed to install required API matrix packages."
fi

for api in $REQUIRED_APIS; do
  image_package="system-images;android-${api};${EMULATOR_TAG};${EMULATOR_ABI}"
  has_avd_for_api "$api" && continue
  avd_name="${AVD_NAME_PREFIX}${api}_${EMULATOR_ABI//-/_}"
  printf 'no\n' | "$AVDMANAGER_CMD" create avd --force --name "$avd_name" --package "$image_package" ||
    fail avdmanager "avdmanager-create-api-$api-failed" "avdmanager failed to create $avd_name."
done

write_report passed ""
echo "Emulator API matrix environment prepared."
