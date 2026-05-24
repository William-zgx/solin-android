#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
AAPT="$(find "$ANDROID_SDK/build-tools" -name aapt -type f 2>/dev/null | sort | tail -n 1)"
MAX_RELEASE_APK_BYTES=$((75 * 1024 * 1024))

if [[ -z "${AAPT:-}" || ! -x "$AAPT" ]]; then
  echo "Android SDK build-tools/aapt not found. Set ANDROID_SDK_ROOT or ANDROID_HOME." >&2
  exit 1
fi

scripts/doctor.sh
"$GRADLE_CMD" testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease

DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -f "$DEBUG_APK" ]]
[[ -f "$RELEASE_APK" ]]

if unzip -Z1 "$DEBUG_APK" | grep -E '(^|/)[^/]+\.litertlm$' >/dev/null; then
  echo "APK unexpectedly contains a model artifact." >&2
  exit 1
fi
if unzip -Z1 "$RELEASE_APK" | grep -E '(^|/)[^/]+\.litertlm$' >/dev/null; then
  echo "Release APK unexpectedly contains a model artifact." >&2
  exit 1
fi

BADGING="$("$AAPT" dump badging "$DEBUG_APK")"
grep -q "package: name='com.bytedance.zgx.pocketmind'" <<<"$BADGING"
grep -q "uses-permission: name='android.permission.INTERNET'" <<<"$BADGING"
grep -q "native-code: 'arm64-v8a'" <<<"$BADGING"

RELEASE_BADGING="$("$AAPT" dump badging "$RELEASE_APK")"
grep -q "native-code: 'arm64-v8a'" <<<"$RELEASE_BADGING"

RELEASE_BYTES="$(wc -c < "$RELEASE_APK" | tr -d ' ')"
if [[ "$RELEASE_BYTES" -gt "$MAX_RELEASE_APK_BYTES" ]]; then
  echo "Release APK is too large: $RELEASE_BYTES bytes (budget: $MAX_RELEASE_APK_BYTES)." >&2
  exit 1
fi

HEADER_FILE="$(mktemp)"
trap 'rm -f "$HEADER_FILE"' EXIT
curl -fsSIL \
  "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true" \
  -o "$HEADER_FILE"

CONTENT_LENGTH="$(awk 'tolower($1) == "content-length:" {gsub("\r","",$2); value=$2} END{print value}' "$HEADER_FILE")"
if [[ "$CONTENT_LENGTH" != "2588147712" ]]; then
  echo "Unexpected model content-length: ${CONTENT_LENGTH:-missing}" >&2
  exit 1
fi

echo "Local verification passed."
