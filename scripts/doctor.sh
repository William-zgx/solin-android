#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
REQUIRED_SDK="android-36"

fail() {
  echo "doctor: $*" >&2
  exit 1
}

command -v java >/dev/null 2>&1 || fail "java not found; install JDK 17 or newer."
JAVA_VERSION="$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
JAVA_MAJOR="$(awk -F. '{print ($1 == "1") ? $2 : $1}' <<<"$JAVA_VERSION")"
if [[ ! "$JAVA_MAJOR" =~ ^[0-9]+$ || "$JAVA_MAJOR" -lt 17 ]]; then
  fail "JDK 17 or newer is required; found ${JAVA_VERSION:-unknown}."
fi

[[ -d "$ANDROID_SDK" ]] || fail "Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME."
[[ -d "$ANDROID_SDK/platforms/$REQUIRED_SDK" ]] || fail "Android SDK platform $REQUIRED_SDK not found in $ANDROID_SDK."

AAPT="$(find "$ANDROID_SDK/build-tools" -name aapt -type f 2>/dev/null | sort | tail -n 1)"
[[ -n "${AAPT:-}" && -x "$AAPT" ]] || fail "Android SDK build-tools/aapt not found."

ADB="$ANDROID_SDK/platform-tools/adb"
[[ -x "$ADB" ]] || fail "adb not found at $ADB."

[[ -x ./gradlew ]] || fail "Gradle wrapper is missing or not executable."

echo "PocketMind Android environment OK"
echo "JDK: $JAVA_VERSION"
echo "Android SDK: $ANDROID_SDK"
echo "aapt: $AAPT"
echo "adb: $ADB"
