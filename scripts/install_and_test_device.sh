#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
ADB_BIN="${ANDROID_SDK}/platform-tools/adb"
CLEAN_DEVICE="${CLEAN_DEVICE:-0}"

scripts/doctor.sh --device

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  SELECTED_SERIAL="$ANDROID_SERIAL"
  SELECTED_STATE="$("$ADB_BIN" devices | awk -v serial="$SELECTED_SERIAL" '$1 == serial {print $2; found = 1} END {if (!found) print ""}')"
  if [[ "$SELECTED_STATE" != "device" ]]; then
    "$ADB_BIN" devices
    echo "ANDROID_SERIAL=$SELECTED_SERIAL is not an authorized Android device; state is ${SELECTED_STATE:-missing}." >&2
    exit 1
  fi
else
  DEVICE_COUNT="$("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" {count += 1} END{print count + 0}')"
  if [[ "$DEVICE_COUNT" != "1" ]]; then
    "$ADB_BIN" devices
    echo "Connect exactly one authorized Android device, or set ANDROID_SERIAL to select one." >&2
    exit 1
  fi
  SELECTED_SERIAL="$("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" {print $1; exit}')"
fi

ADB=("$ADB_BIN" -s "$SELECTED_SERIAL")
echo "Using Android device: $SELECTED_SERIAL"

PACKAGE_NAME="com.bytedance.zgx.pocketmind"
TEST_PACKAGE_NAME="${PACKAGE_NAME}.test"
MAIN_ACTIVITY="${PACKAGE_NAME}/.MainActivity"
TEST_RUNNER="${TEST_PACKAGE_NAME}/androidx.test.runner.AndroidJUnitRunner"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
ANDROID_TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
REQUIRED_FREE_KB=$((3 * 1024 * 1024))

ABI_LIST="$("${ADB[@]}" shell getprop ro.product.cpu.abilist64 | tr -d '\r')"
if [[ "$ABI_LIST" != *"arm64-v8a"* ]]; then
  echo "Connected device is not arm64-v8a compatible: ${ABI_LIST:-unknown}" >&2
  exit 1
fi

DATA_FREE_KB="$("${ADB[@]}" shell df -k /data | awk 'NR == 2 {print $4}' | tr -d '\r')"
if [[ "$DATA_FREE_KB" =~ ^[0-9]+$ && "$DATA_FREE_KB" -lt "$REQUIRED_FREE_KB" ]]; then
  echo "Connected device has less than 3 GB free on /data; model download/import may fail." >&2
  exit 1
fi

if [[ "$CLEAN_DEVICE" == "1" ]]; then
  "${ADB[@]}" uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true
fi
"$GRADLE_CMD" assembleDebug assembleDebugAndroidTest

run_device_tests() {
  "${ADB[@]}" install -r "$DEBUG_APK" &&
    "${ADB[@]}" install -r -t "$ANDROID_TEST_APK" &&
    "${ADB[@]}" shell am instrument -w -r "$TEST_RUNNER"
}

instrumentation_output_failed() {
  grep -qE '^(FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2|INSTRUMENTATION_RESULT: shortMsg=|INSTRUMENTATION_STATUS: stack=|Error in )' <<<"$1"
}

set +e
TEST_OUTPUT="$(run_device_tests 2>&1)"
TEST_STATUS=$?
set -e
printf '%s\n' "$TEST_OUTPUT"
if [[ "$TEST_STATUS" -eq 0 ]] && instrumentation_output_failed "$TEST_OUTPUT"; then
  TEST_STATUS=1
fi
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

"${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" >/dev/null

echo "Device install and smoke test passed. App remains installed."
