#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
MAX_RELEASE_APK_BYTES=$((75 * 1024 * 1024))

scripts/doctor.sh --local
bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/verify_emulator.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh

AAPT="$(find "$ANDROID_SDK/build-tools" -name aapt -type f 2>/dev/null | sort | tail -n 1)"
if [[ -z "${AAPT:-}" || ! -x "$AAPT" ]]; then
  echo "Android SDK build-tools/aapt not found. Set ANDROID_SDK_ROOT or ANDROID_HOME." >&2
  exit 1
fi

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

if grep -R "/resolve/main/" app/src/main/java/com/bytedance/zgx/pocketmind/ModelCatalog.kt >/dev/null; then
  echo "Recommended model downloads must be pinned to immutable revisions." >&2
  exit 1
fi

if [[ "${VERIFY_MODEL_URLS:-0}" == "1" ]]; then
  HEADER_FILE="$(mktemp)"
  trap 'rm -f "$HEADER_FILE"' EXIT

  check_model_header() {
    local name="$1"
    local url="$2"
    local expected_bytes="$3"
    local expected_sha256="$4"

    : > "$HEADER_FILE"
    curl -fsSIL "$url" -o "$HEADER_FILE"

    local content_length
    content_length="$(awk 'tolower($1) == "content-length:" {gsub("\r","",$2); value=$2} END{print value}' "$HEADER_FILE")"
    if [[ "$content_length" != "$expected_bytes" ]]; then
      echo "Unexpected $name content-length: ${content_length:-missing}" >&2
      exit 1
    fi

    local linked_etag
    linked_etag="$(awk 'tolower($1) == "x-linked-etag:" {gsub("\r","",$2); gsub("\"","",$2); value=$2} END{print value}' "$HEADER_FILE")"
    if [[ "$linked_etag" != "$expected_sha256" ]]; then
      echo "Unexpected $name sha256 metadata: ${linked_etag:-missing}" >&2
      exit 1
    fi
  }

  check_model_header \
    "E2B chat model" \
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/a4a831c060880f3733135ad22f10e0e9f758f45d/gemma-4-E2B-it.litertlm?download=true" \
    "2588147712" \
    "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

  check_model_header \
    "memory embedding model" \
    "https://huggingface.co/kontextdev/embeddinggemma-300m-litertlm/resolve/96fa469293abd2da72b46aeeafea3bb571468dfe/embeddinggemma-300m.litertlm?download=true" \
    "179159040" \
    "80e9596830fdd083cbc741dad666c0186439b0ba7b30112b552094650960b1cd"

  check_model_header \
    "mobile action model" \
    "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/resolve/82d0f654a6270c518d16c600edce3136221b3347/mobile-actions_q8_ekv1024.litertlm?download=true" \
    "284426240" \
    "92109695f911d1872fa8ae07c1e3ff0ed70f2c3d1690d410ec6db8587c2ab409"

  check_model_header \
    "E4B chat model" \
    "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/65ce5ba80d8790d66ef11d82d7d079a06f3fef97/gemma-4-E4B-it.litertlm?download=true" \
    "3659530240" \
    "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
fi

if grep -R -E 'PREF_REMOTE_API_KEY.*putString|putString\(PREF_REMOTE_API_KEY' app/src/main/java >/dev/null; then
  echo "Remote API keys must not be stored as plaintext SharedPreferences strings." >&2
  exit 1
fi

echo "Local verification passed."
