#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
BUILD_TOOLS_DIR="${BUILD_TOOLS_DIR:-$({ find "$ANDROID_SDK/build-tools" -maxdepth 1 -type d 2>/dev/null || true; } | sort | tail -n 1)}"
ZIPALIGN="${ZIPALIGN:-$BUILD_TOOLS_DIR/zipalign}"
APKSIGNER="${APKSIGNER:-$BUILD_TOOLS_DIR/apksigner}"

UNSIGNED_APK="${UNSIGNED_APK:-app/build/outputs/apk/release/app-release-unsigned.apk}"
UNSIGNED_AAB="${UNSIGNED_AAB:-app/build/outputs/bundle/release/app-release.aab}"
SIGNED_APK="${SIGNED_APK:-app/build/outputs/apk/release/app-release-signed.apk}"
SIGNED_AAB="${SIGNED_AAB:-app/build/outputs/bundle/release/app-release-signed.aab}"
ALIGNED_APK="${ALIGNED_APK:-${SIGNED_APK%.apk}-aligned.apk}"
REPORT_FILE="${REPORT_FILE:-build/verification/signing/signing.properties}"
ARTIFACT_SCAN_REPORT_FILE="${REPORT_FILE}.artifact-scan.properties"
ALLOW_DEBUG_KEYSTORE="${ALLOW_DEBUG_KEYSTORE:-0}"
EXPECTED_SIGNING_CERT_SHA256="${EXPECTED_SIGNING_CERT_SHA256:-}"
REQUIRE_AAB="${REQUIRE_AAB:-1}"
RELEASE_KEYSTORE_PASSWORD_FILE="${RELEASE_KEYSTORE_PASSWORD_FILE:-}"
RELEASE_KEY_PASSWORD_FILE="${RELEASE_KEY_PASSWORD_FILE:-}"
FAILED_TARGET=""
FAILURE_REASON=""
ORIGINAL_ARGS=("$@")
TMP_SECRET_DIR=""
KEYSTORE_PASSWORD_FILE_RESOLVED=""
KEY_PASSWORD_FILE_RESOLVED=""

sha256_or_empty() {
  local path="$1"
  if [[ -n "$path" && -f "$path" ]]; then
    shasum -a 256 "$path" | awk '{print $1}'
  fi
}

shell_command() {
  local quoted=()
  local arg
  quoted+=("$(printf '%q' "scripts/sign_release_artifacts.sh")")
  if [[ "${#ORIGINAL_ARGS[@]}" -gt 0 ]]; then
    for arg in "${ORIGINAL_ARGS[@]}"; do
      quoted+=("$(printf '%q' "$arg")")
    done
  fi
  local IFS=' '
  printf '%s' "${quoted[*]}"
}

signing_mode() {
  if [[ "$ALLOW_DEBUG_KEYSTORE" == "1" ]]; then
    echo "debug-smoke"
  else
    echo "production"
  fi
}

report_value() {
  local file="$1"
  local key="$2"
  [[ -f "$file" ]] || return 0
  awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$file"
}

read_secret_file() {
  local path="$1"
  [[ -f "$path" ]] || return 1
  tr -d '\r\n' < "$path"
}

prepare_password_file() {
  local env_name="$1"
  local file_env_name="$2"
  local label="$3"
  local file_value="${!file_env_name:-}"
  local secret_value="${!env_name:-}"
  local out_file

  if [[ -n "$file_value" ]]; then
    [[ -f "$file_value" ]] ||
      fail environment "missing-${label}-file" "Password file is missing: $file_env_name=$file_value"
    out_file="$file_value"
  else
    if [[ -z "$secret_value" ]]; then
      fail environment "missing-${label}" "Missing required environment variable or file: $env_name or $file_env_name"
    fi
    if [[ -z "$TMP_SECRET_DIR" ]]; then
      TMP_SECRET_DIR="$(mktemp -d)"
      chmod 700 "$TMP_SECRET_DIR"
    fi
    out_file="$TMP_SECRET_DIR/${label}.txt"
    umask 077
    printf '%s' "$secret_value" > "$out_file"
  fi

  if [[ ! -s "$out_file" ]]; then
    fail environment "empty-${label}-file" "Password file is empty: $file_env_name=$out_file"
  fi
  printf '%s' "$out_file"
}

cleanup_secret_files() {
  if [[ -n "${TMP_SECRET_DIR:-}" ]]; then
    rm -rf "$TMP_SECRET_DIR"
  fi
}

write_report() {
  local exit_code="$1"
  local status="failed"
  [[ "$exit_code" -eq 0 ]] && status="passed"

  mkdir -p "$(dirname "$REPORT_FILE")"
  {
    printf 'status=%s\n' "$status"
    printf 'exit_code=%s\n' "$exit_code"
    printf 'target=release-signing\n'
    printf 'artifactSchema=ReleaseSigningReport/v1\n'
    printf 'owner=release-engineering\n'
    printf 'recordedAt=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'command=%s\n' "$(shell_command)"
    printf 'reproduciblePath=%s\n' "$REPORT_FILE"
    printf 'failedTarget=%s\n' "${FAILED_TARGET:-}"
    printf 'reason=%s\n' "${FAILURE_REASON:-}"
    printf 'signingMode=%s\n' "$(signing_mode)"
    printf 'allowDebugKeystore=%s\n' "$ALLOW_DEBUG_KEYSTORE"
    printf 'requireAab=%s\n' "$REQUIRE_AAB"
    printf 'expectedSigningCertSha256=%s\n' "$EXPECTED_SIGNING_CERT_SHA256"
    printf 'unsignedApk=%s\n' "$UNSIGNED_APK"
    printf 'unsignedApkSha256=%s\n' "$(sha256_or_empty "$UNSIGNED_APK")"
    printf 'unsignedAab=%s\n' "$UNSIGNED_AAB"
    printf 'unsignedAabSha256=%s\n' "$(sha256_or_empty "$UNSIGNED_AAB")"
    printf 'signedApk=%s\n' "$SIGNED_APK"
    if [[ -f "$SIGNED_APK" ]]; then
      printf 'signedApkSha256=%s\n' "$(shasum -a 256 "$SIGNED_APK" | awk '{print $1}')"
    fi
    printf 'signedAab=%s\n' "$SIGNED_AAB"
    if [[ -f "$SIGNED_AAB" ]]; then
      printf 'signedAabSha256=%s\n' "$(shasum -a 256 "$SIGNED_AAB" | awk '{print $1}')"
    fi
    printf 'artifactScanReport=%s\n' "$ARTIFACT_SCAN_REPORT_FILE"
    printf 'artifactScanReportSha256=%s\n' "$(sha256_or_empty "$ARTIFACT_SCAN_REPORT_FILE")"
    printf 'artifactScanStatus=%s\n' "$(report_value "$ARTIFACT_SCAN_REPORT_FILE" status)"
    printf 'artifactScanReason=%s\n' "$(report_value "$ARTIFACT_SCAN_REPORT_FILE" reason)"
  } > "$REPORT_FILE"
  echo "Release signing report: $REPORT_FILE"
}

fail_parse() {
  FAILED_TARGET="argument-parser"
  FAILURE_REASON="$1"
  write_report 2
  echo "$2" >&2
  exit 2
}

REQUIRED_ARG_VALUE=""
require_value() {
  local option="$1"
  local value="${2:-}"
  if [[ -z "$value" || "$value" == --* ]]; then
    fail_parse "missing-${option#--}-argument" "Missing value for $option"
  fi
  REQUIRED_ARG_VALUE="$value"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --report)
      require_value "$1" "${2:-}"
      REPORT_FILE="$REQUIRED_ARG_VALUE"
      ARTIFACT_SCAN_REPORT_FILE="${REPORT_FILE}.artifact-scan.properties"
      shift 2
      ;;
    *)
      fail_parse unknown-argument "Unknown argument: $1"
      ;;
  esac
done

trap 'status=$?; write_report "$status"; cleanup_secret_files; exit "$status"' EXIT

fail() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  echo "$*" >&2
  exit 1
}

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    local reason
    reason="$(printf '%s' "$name" | tr '[:upper:]_' '[:lower:]-')"
    fail environment "missing-$reason" "Missing required environment variable: $name"
  fi
}

require_tool() {
  local path="$1"
  local name="$2"
  if [[ -z "$path" || ! -x "$path" ]]; then
    fail tool "$name-not-found" "$name not found or not executable: $path"
  fi
}

require_env RELEASE_KEYSTORE
require_env RELEASE_KEY_ALIAS
KEYSTORE_PASSWORD_FILE_RESOLVED="$(prepare_password_file RELEASE_KEYSTORE_PASSWORD RELEASE_KEYSTORE_PASSWORD_FILE release-keystore-password)"
KEY_PASSWORD_FILE_RESOLVED="$(prepare_password_file RELEASE_KEY_PASSWORD RELEASE_KEY_PASSWORD_FILE release-key-password)"

if [[ ! -f "$RELEASE_KEYSTORE" ]]; then
  fail keystore release-keystore-missing "Release keystore not found: $RELEASE_KEYSTORE"
fi

if [[ "$ALLOW_DEBUG_KEYSTORE" != "1" ]]; then
  keystore_name="$(basename "$RELEASE_KEYSTORE" | tr '[:upper:]' '[:lower:]')"
  if [[ "$keystore_name" == "debug.keystore" ]]; then
    fail keystore debug-keystore-not-allowed "Refusing Android debug keystore for release signing. Set ALLOW_DEBUG_KEYSTORE=1 only for local smoke validation."
  fi
  if command -v keytool >/dev/null 2>&1; then
    keytool_output="$(
      keytool -list -v \
        -keystore "$RELEASE_KEYSTORE" \
        -alias "$RELEASE_KEY_ALIAS" \
        -storepass:file "$KEYSTORE_PASSWORD_FILE_RESOLVED" 2>/dev/null || true
    )"
    if grep -qi 'CN=Android Debug' <<<"$keytool_output"; then
      fail keystore debug-certificate-not-allowed "Refusing Android debug certificate for release signing. Set ALLOW_DEBUG_KEYSTORE=1 only for local smoke validation."
    fi
  fi
fi

if [[ "$ALLOW_DEBUG_KEYSTORE" != "1" && -z "$EXPECTED_SIGNING_CERT_SHA256" ]]; then
  fail signing-policy expected-signing-cert-sha256-missing "Production release signing requires EXPECTED_SIGNING_CERT_SHA256."
fi

if [[ "$ALLOW_DEBUG_KEYSTORE" != "1" && "$REQUIRE_AAB" != "1" ]]; then
  fail signing-policy production-requires-aab "Production release signing requires REQUIRE_AAB=1."
fi

if [[ "$REQUIRE_AAB" == "1" && ! -f "$UNSIGNED_AAB" ]]; then
  fail input-artifact unsigned-aab-missing "Release signing requires unsigned AAB: $UNSIGNED_AAB"
fi

require_tool "$ZIPALIGN" zipalign
require_tool "$APKSIGNER" apksigner
command -v jarsigner >/dev/null 2>&1 || {
  fail tool jarsigner-not-found "jarsigner not found in PATH."
}

mkdir -p "$(dirname "$SIGNED_APK")" "$(dirname "$SIGNED_AAB")" "$(dirname "$REPORT_FILE")"

if [[ -f "$UNSIGNED_APK" ]]; then
  if ! "$ZIPALIGN" -p -f 4 "$UNSIGNED_APK" "$ALIGNED_APK"; then
    fail apk-signing zipalign-failed "APK zipalign failed."
  fi
  if ! "$APKSIGNER" sign \
    --ks "$RELEASE_KEYSTORE" \
    --ks-key-alias "$RELEASE_KEY_ALIAS" \
    --ks-pass "file:$KEYSTORE_PASSWORD_FILE_RESOLVED" \
    --key-pass "file:$KEY_PASSWORD_FILE_RESOLVED" \
    --out "$SIGNED_APK" \
    "$ALIGNED_APK"; then
    fail apk-signing apksigner-sign-failed "APK signing failed."
  fi
  if ! "$APKSIGNER" verify --verbose --print-certs "$SIGNED_APK" > "$REPORT_FILE.apk-certs.txt"; then
    fail apk-signing apksigner-verify-failed "APK signature verification failed."
  fi
fi

if [[ -f "$UNSIGNED_AAB" ]]; then
  cp "$UNSIGNED_AAB" "$SIGNED_AAB"
  if ! jarsigner \
    -keystore "$RELEASE_KEYSTORE" \
    -storepass:file "$KEYSTORE_PASSWORD_FILE_RESOLVED" \
    -keypass:file "$KEY_PASSWORD_FILE_RESOLVED" \
    "$SIGNED_AAB" \
    "$RELEASE_KEY_ALIAS" >/dev/null; then
    fail aab-signing jarsigner-sign-failed "AAB signing failed."
  fi
  if ! jarsigner -verify -certs -verbose "$SIGNED_AAB" > "$REPORT_FILE.aab-certs.txt" 2>&1; then
    fail aab-signing jarsigner-verify-failed "AAB signature verification failed."
  fi
fi

scan_args=()
if [[ -f "$SIGNED_APK" ]]; then
  scan_args+=(--apk "$SIGNED_APK")
fi
if [[ -f "$SIGNED_AAB" ]]; then
  scan_args+=(--aab "$SIGNED_AAB")
fi
if [[ "${#scan_args[@]}" -eq 0 ]]; then
  fail output-artifact no-signed-artifacts-produced "No signed APK or AAB was produced."
fi
scan_extra_args=()
if [[ "$ALLOW_DEBUG_KEYSTORE" == "1" ]]; then
  scan_extra_args+=(--allow-debug-certificate)
fi
if [[ -n "$EXPECTED_SIGNING_CERT_SHA256" ]]; then
  scan_extra_args+=(--expected-certificate-sha256 "$EXPECTED_SIGNING_CERT_SHA256")
fi
if ! scripts/scan_android_artifacts.sh \
  "${scan_args[@]}" \
  --require-signed \
  "${scan_extra_args[@]}" \
  --report "$ARTIFACT_SCAN_REPORT_FILE"; then
  artifact_scan_reason="$(report_value "$ARTIFACT_SCAN_REPORT_FILE" reason)"
  [[ -n "$artifact_scan_reason" ]] || artifact_scan_reason="artifact-scan-failed"
  fail artifact-scan "$artifact_scan_reason" "Signed artifact scan failed."
fi

echo "Release artifacts signed. Report: $REPORT_FILE"
