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
# `bash -n a.sh b.sh` only parses a.sh (the rest become positional args), so check each
# script on its own. The glob keeps new scripts and libraries covered automatically.
for script_path in scripts/*.sh scripts/lib/*.sh; do
  bash -n "$script_path"
done
scripts/test_validation_scripts.sh

AAPT="$(find "$ANDROID_SDK/build-tools" -name aapt -type f 2>/dev/null | sort | tail -n 1)"
if [[ -z "${AAPT:-}" || ! -x "$AAPT" ]]; then
  echo "Android SDK build-tools/aapt not found. Set ANDROID_SDK_ROOT or ANDROID_HOME." >&2
  exit 1
fi

# lintDebug's lint models can reference Room/KSP release generated sources.
# Generate them first so lint does not race assembleRelease for the same files.
"$GRADLE_CMD" :app:kspReleaseKotlin
"$GRADLE_CMD" :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease :releaseSmoke:assembleRelease

DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
RELEASE_AAB="app/build/outputs/bundle/release/app-release.aab"
RELEASE_SMOKE_APK="releaseSmoke/build/outputs/apk/release/releaseSmoke-release.apk"
[[ -f "$DEBUG_APK" ]]
[[ -f "$RELEASE_APK" ]]
[[ -f "$RELEASE_AAB" ]]
[[ -f "$RELEASE_SMOKE_APK" ]]

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
grep -q "package: name='com.bytedance.zgx.solin'" <<<"$BADGING"
grep -q "uses-permission: name='android.permission.INTERNET'" <<<"$BADGING"
grep -q "native-code: 'arm64-v8a'" <<<"$BADGING"

RELEASE_BADGING="$("$AAPT" dump badging "$RELEASE_APK")"
grep -q "native-code: 'arm64-v8a'" <<<"$RELEASE_BADGING"

RELEASE_SMOKE_BADGING="$("$AAPT" dump badging "$RELEASE_SMOKE_APK")"
grep -q "package: name='com.bytedance.zgx.solin.releasesmoke'" <<<"$RELEASE_SMOKE_BADGING"
RELEASE_SMOKE_MANIFEST="$("$AAPT" dump xmltree "$RELEASE_SMOKE_APK" AndroidManifest.xml)"
grep -q 'com.bytedance.zgx.solin.releasesmoke.ReleaseSmokeInstrumentation' <<<"$RELEASE_SMOKE_MANIFEST"
grep -q 'com.bytedance.zgx.solin' <<<"$RELEASE_SMOKE_MANIFEST"

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

# Search the whole source tree (and the shared Gradle model catalog) so the gate keeps
# working if ModelCatalog.kt moves between packages.
if grep -R "/resolve/main/" app/src/main/java gradle/bundled-models.json >/dev/null; then
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

  # Model metadata comes from the shared catalog (also consumed by the Gradle build).
  BUNDLED_MODELS_CATALOG="gradle/bundled-models.json"
  HF_MODEL_DOWNLOAD_TOKEN="${HUGGING_FACE_TOKEN:-${HF_TOKEN:-}}"
  while IFS=$'\t' read -r model_label model_url model_bytes model_sha256 model_gated; do
    if [[ "$model_gated" == "true" ]]; then
      if [[ -z "$HF_MODEL_DOWNLOAD_TOKEN" ]]; then
        echo "Skipping gated $model_label URL verification; set HF_TOKEN or HUGGING_FACE_TOKEN to verify it." >&2
        continue
      fi
      check_model_header "$model_label" "$model_url" "$model_bytes" "$model_sha256" "$HF_MODEL_DOWNLOAD_TOKEN"
    else
      check_model_header "$model_label" "$model_url" "$model_bytes" "$model_sha256"
    fi
  done < <(python3 - "$BUNDLED_MODELS_CATALOG" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    catalog = json.load(handle)
for asset in catalog["assets"]:
    print("\t".join([
        asset["label"],
        asset["downloadUrl"],
        str(asset["byteSize"]),
        asset["sha256Hex"],
        "true" if asset["requiresHuggingFaceAuthorization"] else "false",
    ]))
PY
)
fi

if grep -R -E 'PREF_REMOTE_API_KEY.*putString|putString\(PREF_REMOTE_API_KEY' app/src/main/java >/dev/null; then
  echo "Remote API keys must not be stored as plaintext SharedPreferences strings." >&2
  exit 1
fi

echo "Local verification passed."
