#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_API="${ANDROID_API:-36}"
EMULATOR_ABI="${EMULATOR_API_MATRIX_ABI:-x86_64}"
AVD_NAME_PREFIX="${AVD_NAME_PREFIX:-pocketmind_api}"
DEFAULT_AVD_NAME="${AVD_NAME_PREFIX}${ANDROID_API}_${EMULATOR_ABI//-/_}"
DEFAULT_EMULATOR_ARGS="-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot"
USER_SET_EMULATOR_ARGS=0

export AVD_NAME="${AVD_NAME:-$DEFAULT_AVD_NAME}"
if [[ "${EMULATOR_ARGS+x}" ]]; then
  USER_SET_EMULATOR_ARGS=1
else
  export EMULATOR_ARGS="$DEFAULT_EMULATOR_ARGS"
fi
export EMULATOR_SELECT_TIMEOUT_SECONDS="${EMULATOR_SELECT_TIMEOUT_SECONDS:-90}"
export BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-240}"

gui_required=1
if [[ " $EMULATOR_ARGS " == *" -no-window "* ]]; then
  gui_required=0
fi

X86_HOST_REPORT="${X86_HOST_REPORT:-build/verification/x86-emulator-host.properties}"
ALLOW_X86_EMULATOR_INFRA_UNAVAILABLE=1 EMULATOR_GUI_REQUIRED="$gui_required" REPORT_FILE="$X86_HOST_REPORT" scripts/check_x86_emulator_host.sh
if [[ "$USER_SET_EMULATOR_ARGS" == "0" ]] && grep -Eq 'cpu-virtualization-flag-missing|kvm-device-' "$X86_HOST_REPORT"; then
  export EMULATOR_ARGS="$EMULATOR_ARGS -accel off"
  export BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-900}"
fi

scripts/capture_release_screenshots.sh
