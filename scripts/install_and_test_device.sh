#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
ADB="${ANDROID_SDK}/platform-tools/adb"
CLEAN_DEVICE="${CLEAN_DEVICE:-0}"

scripts/doctor.sh --device

DEVICE_COUNT="$("$ADB" devices | awk 'NR > 1 && $2 == "device" {count += 1} END{print count + 0}')"
if [[ "$DEVICE_COUNT" != "1" ]]; then
  "$ADB" devices
  echo "Connect exactly one authorized Android device before running this script." >&2
  exit 1
fi

PACKAGE_NAME="com.bytedance.zgx.pocketmind"
TEST_PACKAGE_NAME="${PACKAGE_NAME}.test"
MAIN_ACTIVITY="${PACKAGE_NAME}/.MainActivity"
TEST_RUNNER="${TEST_PACKAGE_NAME}/androidx.test.runner.AndroidJUnitRunner"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
ANDROID_TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
REQUIRED_FREE_KB=$((3 * 1024 * 1024))

ABI_LIST="$("$ADB" shell getprop ro.product.cpu.abilist64 | tr -d '\r')"
if [[ "$ABI_LIST" != *"arm64-v8a"* ]]; then
  echo "Connected device is not arm64-v8a compatible: ${ABI_LIST:-unknown}" >&2
  exit 1
fi

DATA_FREE_KB="$("$ADB" shell df -k /data | awk 'NR == 2 {print $4}' | tr -d '\r')"
if [[ "$DATA_FREE_KB" =~ ^[0-9]+$ && "$DATA_FREE_KB" -lt "$REQUIRED_FREE_KB" ]]; then
  echo "Connected device has less than 3 GB free on /data; model download/import may fail." >&2
  exit 1
fi

if [[ "$CLEAN_DEVICE" == "1" ]]; then
  "$ADB" uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true
fi
"$GRADLE_CMD" assembleDebug assembleDebugAndroidTest

run_device_tests() {
  "$ADB" install -r "$DEBUG_APK" &&
    "$ADB" install -r -t "$ANDROID_TEST_APK" &&
    "$ADB" shell am instrument -w -r "$TEST_RUNNER"
}

set +e
TEST_OUTPUT="$(run_device_tests 2>&1)"
TEST_STATUS=$?
set -e
printf '%s\n' "$TEST_OUTPUT"
if [[ "$TEST_STATUS" -ne 0 ]]; then
  if grep -q "INSTALL_FAILED_USER_RESTRICTED" <<<"$TEST_OUTPUT"; then
    cat >&2 <<'EOF'

Device refused ADB installation.
On Xiaomi/HyperOS/MIUI devices, enable Developer options -> USB debugging,
USB install / Install via USB, and accept any install confirmation shown on the phone.
Then rerun scripts/install_and_test_device.sh.
EOF
  fi
  exit "$TEST_STATUS"
fi

"$ADB" shell am start -W -n "$MAIN_ACTIVITY" >/dev/null

echo "Device install and smoke test passed. App remains installed."
