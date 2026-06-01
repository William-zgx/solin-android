#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

fail() {
  echo "validation-script-test: $*" >&2
  exit 1
}

LAST_OUTPUT=""

expect_success() {
  local name="$1"
  shift
  if ! output="$("$@" 2>&1)"; then
    printf '%s\n' "$output" >&2
    fail "$name unexpectedly failed"
  fi
  LAST_OUTPUT="$output"
}

expect_failure() {
  local name="$1"
  shift
  if output="$("$@" 2>&1)"; then
    printf '%s\n' "$output" >&2
    fail "$name unexpectedly succeeded"
  fi
  LAST_OUTPUT="$output"
}

create_base_sdk() {
  local sdk="$1"
  mkdir -p "$sdk/platforms/android-36" "$sdk/build-tools/36.0.0"
  cat > "$sdk/build-tools/36.0.0/aapt" <<'FAKE_AAPT'
#!/usr/bin/env bash
exit 0
FAKE_AAPT
  chmod +x "$sdk/build-tools/36.0.0/aapt"
}

create_fake_adb() {
  local sdk="$1"
  mkdir -p "$sdk/platform-tools"
  cat > "$sdk/platform-tools/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$*" >> "${FAKE_ADB_LOG:?}"

if [[ "${1:-}" == "devices" ]]; then
  echo "List of devices attached"
  if [[ -n "${FAKE_ADB_DEVICES:-}" ]]; then
    printf '%s\n' "$FAKE_ADB_DEVICES"
  fi
  exit 0
fi

if [[ "${1:-}" == "-s" ]]; then
  shift 2
fi

case "${1:-}" in
  shell)
    shift
    case "$*" in
      "getprop ro.product.cpu.abilist64")
        echo "${FAKE_ABI_LIST:-arm64-v8a,armeabi-v7a}"
        ;;
      "getprop ro.build.version.sdk")
        echo "36"
        ;;
      "getprop sys.boot_completed")
        echo "1"
        ;;
      "df -k /data")
        printf 'Filesystem 1K-blocks Used Available Use%% Mounted on\n'
        printf '/dev/block 5000000 1000 %s 1%% /data\n' "${FAKE_DATA_FREE_KB:-4000000}"
        ;;
      am\ instrument\ -w\ -r*)
        if [[ -n "${FAKE_INSTRUMENTATION_OUTPUT:-}" ]]; then
          printf '%s\n' "$FAKE_INSTRUMENTATION_OUTPUT"
        else
          echo "OK (instrumentation tests)"
        fi
        ;;
      am\ start\ -W\ -n*)
        echo "Status: ok"
        ;;
      *)
        echo "unexpected shell command: $*" >&2
        exit 2
        ;;
    esac
    ;;
  emu)
    shift
    case "$*" in
      "avd name")
        echo "test-avd"
        ;;
      *)
        echo "unexpected emulator command: $*" >&2
        exit 2
        ;;
    esac
    ;;
  install|uninstall)
    echo "Success"
    ;;
  *)
    echo "unexpected adb command: $*" >&2
    exit 2
    ;;
esac
FAKE_ADB
  chmod +x "$sdk/platform-tools/adb"
}

create_fake_emulator() {
  local sdk="$1"
  mkdir -p "$sdk/emulator"
  cat > "$sdk/emulator/emulator" <<'FAKE_EMULATOR'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-list-avds" ]]; then
  if [[ -n "${FAKE_EMULATOR_AVDS+x}" ]]; then
    printf '%s\n' "$FAKE_EMULATOR_AVDS"
  else
    echo "test-avd"
  fi
  exit 0
fi
printf '%s\n' "$*" >> "${FAKE_EMULATOR_LOG:?}"
FAKE_EMULATOR
  chmod +x "$sdk/emulator/emulator"
}

create_fake_gradle() {
  local path="$1"
  cat > "$path" <<'FAKE_GRADLE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_GRADLE_LOG:?}"
FAKE_GRADLE
  chmod +x "$path"
}

assert_no_gradle_call() {
  if [[ -s "$FAKE_GRADLE_LOG" ]]; then
    cat "$FAKE_GRADLE_LOG" >&2
    fail "Gradle should not be called before device preflight succeeds"
  fi
}

assert_gradle_called() {
  grep -q "assembleDebug assembleDebugAndroidTest" "$FAKE_GRADLE_LOG" ||
    fail "Expected install helper to assemble debug and androidTest APKs"
}

assert_report_contains() {
  local file="$1"
  local expected="$2"
  [[ -f "$file" ]] || fail "Expected verification report at $file"
  grep -qxF "$expected" "$file" ||
    fail "Expected $file to contain: $expected"
}

reset_logs() {
  : > "$FAKE_ADB_LOG"
  : > "$FAKE_EMULATOR_LOG"
  : > "$FAKE_GRADLE_LOG"
  rm -rf "$ARTIFACT_DIR"
  mkdir -p "$ARTIFACT_DIR"
}

NO_ADB_SDK="$TMP_DIR/no-adb-sdk"
NO_EMULATOR_SDK="$TMP_DIR/no-emulator-sdk"
FAKE_SDK="$TMP_DIR/fake-sdk"
FAKE_GRADLE="$TMP_DIR/fake-gradle"
export FAKE_ADB_LOG="$TMP_DIR/fake-adb.log"
export FAKE_EMULATOR_LOG="$TMP_DIR/fake-emulator.log"
export FAKE_GRADLE_LOG="$TMP_DIR/fake-gradle.log"
export ARTIFACT_DIR="$TMP_DIR/verification"

create_base_sdk "$NO_ADB_SDK"
create_base_sdk "$NO_EMULATOR_SDK"
create_fake_adb "$NO_EMULATOR_SDK"
create_base_sdk "$FAKE_SDK"
create_fake_adb "$FAKE_SDK"
create_fake_emulator "$FAKE_SDK"
create_fake_gradle "$FAKE_GRADLE"
reset_logs

ksp_line="$(grep -n 'GRADLE_CMD.*:app:kspReleaseKotlin' scripts/verify_local.sh | cut -d: -f1 | head -n 1)"
verify_line="$(grep -n 'GRADLE_CMD.*testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease' scripts/verify_local.sh | cut -d: -f1 | head -n 1)"
if [[ -z "$ksp_line" || -z "$verify_line" || "$ksp_line" -ge "$verify_line" ]]; then
  fail "verify_local.sh must generate release KSP sources before lintDebug"
fi

expect_success \
  "doctor local without adb" \
  env ANDROID_SDK_ROOT="$NO_ADB_SDK" ANDROID_HOME="$NO_ADB_SDK" \
  scripts/doctor.sh --local

expect_failure \
  "doctor device without adb" \
  env ANDROID_SDK_ROOT="$NO_ADB_SDK" ANDROID_HOME="$NO_ADB_SDK" \
  scripts/doctor.sh --device

reset_logs
expect_failure \
  "install helper without devices" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES="" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "target=device"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=not-run"

reset_logs
expect_failure \
  "install helper with unauthorized device" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tunauthorized' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper with multiple devices and no serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice\ndevice-b\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper with offline selected serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\toffline' ANDROID_SERIAL="device-a" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper with missing selected serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-b" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper rejects non arm64 device before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' FAKE_ABI_LIST="armeabi-v7a,x86" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_no_gradle_call
grep -q "not arm64-v8a compatible" <<<"$LAST_OUTPUT" ||
  fail "Expected install helper to reject non arm64-v8a devices"
grep -q -- "-s device-a shell getprop ro.product.cpu.abilist64" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to check selected device ABI before Gradle"

reset_logs
expect_failure \
  "install helper rejects low data partition space before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' FAKE_ABI_LIST="arm64-v8a,armeabi-v7a" \
  FAKE_DATA_FREE_KB="3145727" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_no_gradle_call
grep -q "less than 3 GB free on /data" <<<"$LAST_OUTPUT" ||
  fail "Expected install helper to reject devices with low /data free space"
grep -q -- "-s device-a shell df -k /data" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to check /data free space before Gradle"

reset_logs
expect_success \
  "install helper selects the only authorized device" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "api_level=36"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "abi=arm64-v8a,armeabi-v7a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=passed"
grep -q -- "-s device-a shell getprop ro.product.cpu.abilist64" "$FAKE_ADB_LOG" ||
  fail "Expected adb device commands to target the only authorized device"

reset_logs
expect_success \
  "install helper selects requested serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice\ndevice-b\tdevice' ANDROID_SERIAL="device-b" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
grep -q -- "-s device-b shell getprop ro.product.cpu.abilist64" "$FAKE_ADB_LOG" ||
  fail "Expected adb device commands to target ANDROID_SERIAL"
grep -q -- "-s device-b install -r app/build/outputs/apk/debug/app-debug.apk" "$FAKE_ADB_LOG" ||
  fail "Expected debug APK install to target ANDROID_SERIAL"

reset_logs
expect_failure \
  "install helper fails failed instrumentation output even when adb exits zero" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT=$'FAILURES!!!\nINSTRUMENTATION_CODE: -1' \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=failed"

reset_logs
expect_failure \
  "emulator helper rejects physical serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  EMULATOR_SELECT_TIMEOUT_SECONDS=0 GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_emulator.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "emulator helper rejects physical-only devices" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' EMULATOR_SELECT_TIMEOUT_SECONDS=0 \
  GRADLE_CMD="$FAKE_GRADLE" scripts/verify_emulator.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "emulator helper without emulator binary" \
  env ANDROID_SDK_ROOT="$NO_EMULATOR_SDK" ANDROID_HOME="$NO_EMULATOR_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_emulator.sh
assert_no_gradle_call
grep -q "Android emulator binary not found" <<<"$LAST_OUTPUT" ||
  fail "Expected emulator helper to report missing emulator binary"

reset_logs
expect_failure \
  "emulator helper rejects unknown requested AVD" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES="" FAKE_EMULATOR_AVDS="other-avd" AVD_NAME="test-avd" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/verify_emulator.sh
assert_no_gradle_call
grep -q "AVD_NAME=test-avd not found" <<<"$LAST_OUTPUT" ||
  fail "Expected emulator helper to report unknown AVD"

reset_logs
expect_success \
  "emulator helper selects the only authorized emulator" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "target=emulator"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "serial=emulator-5554"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "api_level=36"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "avd=test-avd"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "device_report_file=$ARTIFACT_DIR/device-verification.properties"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=emulator-5554"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=passed"
grep -q -- "-s emulator-5554 shell getprop sys.boot_completed" "$FAKE_ADB_LOG" ||
  fail "Expected emulator helper to wait for emulator boot completion"
grep -q -- "-s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk" "$FAKE_ADB_LOG" ||
  fail "Expected emulator helper to install debug APK on selected emulator"

reset_logs
expect_success \
  "emulator helper starts requested AVD" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5556\tdevice' AVD_NAME="test-avd" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/verify_emulator.sh
assert_gradle_called
grep -q "Starting emulator AVD: test-avd" <<<"$LAST_OUTPUT" ||
  fail "Expected emulator helper to enter AVD startup path"

echo "Validation script tests passed."
