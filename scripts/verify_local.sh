#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
# The release APK is a universal install artifact and includes arm64 native
# runtimes for LiteRT, embedding/RAG, and OCR. Keep the Play-delivered AAB
# budget tighter while giving the universal APK its own explicit gate.
MAX_RELEASE_APK_BYTES="${MAX_RELEASE_APK_BYTES:-$((125 * 1024 * 1024))}"
MAX_RELEASE_AAB_BYTES="${MAX_RELEASE_AAB_BYTES:-$((75 * 1024 * 1024))}"

scripts/doctor.sh --local
bash -n scripts/doctor.sh scripts/verify_ai_behavior_eval.sh scripts/collect_ai_behavior_actual_trace.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/install_review_device.sh scripts/verify_device_control_acceptance.sh scripts/run_device_control_debug_eval.sh scripts/verify_real_app_search_report.sh scripts/verify_capability_matrix.sh scripts/release_preflight_fields.sh scripts/verify_model_memory_multimodal_local_gates.sh scripts/verify_fresh_start_main_shell_emulator.sh scripts/verify_emulator.sh scripts/regression_emulator.sh scripts/check_emulator_api_matrix.sh scripts/prepare_emulator_api_matrix.sh scripts/regression_emulator_api_matrix.sh scripts/live_remote_emulator.sh scripts/capture_release_screenshots.sh scripts/collect_release_flow_matrix_evidence.sh scripts/collect_crash_anr_smoke_evidence.sh scripts/record_manual_acceptance_evidence.sh scripts/record_release_flow_evidence.sh scripts/verify_upgrade_install_emulator.sh scripts/test_validation_scripts.sh scripts/privacy_scan.sh scripts/scan_android_artifacts.sh scripts/verify_perf_baseline.sh scripts/verify_privacy_review.sh scripts/verify_release_record.sh scripts/verify_store_policy_record.sh scripts/verify_release_operations_record.sh scripts/verify_release_validation_record.sh scripts/verify_model_license_review.sh scripts/verify_model_capability_profiles.sh scripts/verify_release_mapping.sh scripts/verify_release_gate.sh scripts/collect_perf_baseline.sh scripts/collect_rc_perf_from_device.sh scripts/collect_model_license_metadata.sh scripts/sign_release_artifacts.sh
scripts/test_validation_scripts.sh

AAPT="$(find "$ANDROID_SDK/build-tools" -name aapt -type f 2>/dev/null | sort | tail -n 1)"
if [[ -z "${AAPT:-}" || ! -x "$AAPT" ]]; then
  echo "Android SDK build-tools/aapt not found. Set ANDROID_SDK_ROOT or ANDROID_HOME." >&2
  exit 1
fi

# lintDebug's lint models can reference Room/KSP release generated sources.
# Generate them first so lint does not race assembleRelease for the same files.
"$GRADLE_CMD" :app:kspReleaseKotlin
"$GRADLE_CMD" testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease bundleRelease

DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
RELEASE_AAB="app/build/outputs/bundle/release/app-release.aab"
[[ -f "$DEBUG_APK" ]]
[[ -f "$RELEASE_APK" ]]
[[ -f "$RELEASE_AAB" ]]

if unzip -Z1 "$DEBUG_APK" | grep -E '(^|/)[^/]+\.litertlm$' >/dev/null; then
  echo "APK unexpectedly contains a model artifact." >&2
  exit 1
fi
if unzip -Z1 "$RELEASE_APK" | grep -E '(^|/)[^/]+\.litertlm$' >/dev/null; then
  echo "Release APK unexpectedly contains a model artifact." >&2
  exit 1
fi
if unzip -Z1 "$RELEASE_AAB" | grep -E '(^|/)[^/]+\.litertlm$' >/dev/null; then
  echo "Release AAB unexpectedly contains a model artifact." >&2
  exit 1
fi

scripts/scan_android_artifacts.sh \
  --apk "$RELEASE_APK" \
  --aab "$RELEASE_AAB" \
  --report build/verification/local/android-artifact-scan.properties

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
AAB_BYTES="$(wc -c < "$RELEASE_AAB" | tr -d ' ')"
if [[ "$AAB_BYTES" -gt "$MAX_RELEASE_AAB_BYTES" ]]; then
  echo "Release AAB is too large: $AAB_BYTES bytes (budget: $MAX_RELEASE_AAB_BYTES)." >&2
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
    local auth_token="${5:-}"

    : > "$HEADER_FILE"
    if [[ -n "$auth_token" ]]; then
      curl -fsSIL -H "Authorization: Bearer $auth_token" "$url" -o "$HEADER_FILE"
    else
      curl -fsSIL "$url" -o "$HEADER_FILE"
    fi

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

  check_small_model_download() {
    local name="$1"
    local url="$2"
    local expected_bytes="$3"
    local expected_sha256="$4"

    local model_file
    model_file="$(mktemp)"
    curl -fsSL "$url" -o "$model_file"

    local actual_bytes
    actual_bytes="$(wc -c < "$model_file" | tr -d '[:space:]')"
    if [[ "$actual_bytes" != "$expected_bytes" ]]; then
      rm -f "$model_file"
      echo "Unexpected $name byte size: ${actual_bytes:-missing}" >&2
      exit 1
    fi

    local actual_sha256
    actual_sha256="$(shasum -a 256 "$model_file" | awk '{print $1}')"
    rm -f "$model_file"
    if [[ "$actual_sha256" != "$expected_sha256" ]]; then
      echo "Unexpected $name sha256: ${actual_sha256:-missing}" >&2
      exit 1
    fi
  }

  check_model_header \
    "E2B chat model" \
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/a4a831c060880f3733135ad22f10e0e9f758f45d/gemma-4-E2B-it.litertlm?download=true" \
    "2588147712" \
    "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

  HF_MODEL_DOWNLOAD_TOKEN="${HUGGING_FACE_TOKEN:-${HF_TOKEN:-}}"
  if [[ -n "$HF_MODEL_DOWNLOAD_TOKEN" ]]; then
    check_model_header \
      "memory embedding model" \
      "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/870cbe05ef460385363c6b574c851ae5d8989ce3/embeddinggemma-300M_seq256_mixed-precision.tflite?download=true" \
      "179131736" \
      "37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5" \
      "$HF_MODEL_DOWNLOAD_TOKEN"

    check_model_header \
      "memory embedding tokenizer" \
      "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/870cbe05ef460385363c6b574c851ae5d8989ce3/sentencepiece.model?download=true" \
      "4683319" \
      "d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7" \
      "$HF_MODEL_DOWNLOAD_TOKEN"
  else
    echo "Skipping gated memory embedding model URL verification; set HF_TOKEN or HUGGING_FACE_TOKEN to verify it." >&2
  fi

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
