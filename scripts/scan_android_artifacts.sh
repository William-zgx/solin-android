#!/usr/bin/env bash
set -euo pipefail

REPORT_FILE=""
REQUIRE_SIGNED=0
ALLOW_DEBUG_CERTIFICATE=0
EXPECTED_CERTIFICATE_SHA256=""
ARTIFACTS=()
ORIGINAL_ARGS=("$@")

normalize_sha256() {
  tr '[:upper:]' '[:lower:]' <<<"$1" | tr -d ':'
}

shell_command() {
  local quoted=()
  local arg
  quoted+=("$(printf '%q' "scripts/scan_android_artifacts.sh")")
  if [[ "${#ORIGINAL_ARGS[@]}" -gt 0 ]]; then
    for arg in "${ORIGINAL_ARGS[@]}"; do
      quoted+=("$(printf '%q' "$arg")")
    done
  fi
  local IFS=' '
  printf '%s' "${quoted[*]}"
}

failed_target_for_reason() {
  case "$1" in
    unknown-argument|missing-*-argument)
      printf 'argument-parser'
      ;;
    no-artifact-provided|artifact-*|apk-*|aab-*|forbidden-*|sensitive-*|signing-*|certificate-*|debug-certificate)
      printf 'artifact'
      ;;
    *)
      printf ''
      ;;
  esac
}

write_parse_report() {
  local reason="$1"
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'status=failed\n'
      printf 'target=android-artifact-scan\n'
      printf 'artifactSchema=AndroidArtifactScanReport/v1\n'
      printf 'owner=release-engineering\n'
      printf 'recordedAt=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      printf 'command=%s\n' "$(shell_command)"
      printf 'reproduciblePath=%s\n' "$REPORT_FILE"
      printf 'failedTarget=argument-parser\n'
      printf 'reason=%s\n' "$reason"
      printf 'artifactCount=%s\n' "${#ARTIFACTS[@]}"
      printf 'findingCount=1\n'
      printf 'requireSigned=%s\n' "$REQUIRE_SIGNED"
      printf 'allowDebugCertificate=%s\n' "$ALLOW_DEBUG_CERTIFICATE"
      printf 'expectedCertificateSha256=%s\n' "$(normalize_sha256 "$EXPECTED_CERTIFICATE_SHA256")"
      local index=0
      local artifact
      if [[ "${#ARTIFACTS[@]}" -gt 0 ]]; then
        for artifact in "${ARTIFACTS[@]}"; do
          index=$((index + 1))
          printf 'artifact%sPath=%s\n' "$index" "$artifact"
          if [[ -f "$artifact" ]]; then
            printf 'artifact%sSha256=%s\n' "$index" "$(shasum -a 256 "$artifact" | awk '{print $1}')"
            printf 'artifact%sSizeBytes=%s\n' "$index" "$(wc -c < "$artifact" | tr -d ' ')"
            printf 'artifact%sType=%s\n' "$index" "${artifact##*.}"
          fi
        done
      fi
    } > "$REPORT_FILE"
  fi
}

fail_parse() {
  local reason="$1"
  local message="$2"
  write_parse_report "$reason"
  echo "$message" >&2
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
    --apk|--aab)
      require_value "$1" "${2:-}"
      ARTIFACTS+=("$REQUIRED_ARG_VALUE")
      shift 2
      ;;
    --report)
      require_value "$1" "${2:-}"
      REPORT_FILE="$REQUIRED_ARG_VALUE"
      shift 2
      ;;
    --require-signed)
      REQUIRE_SIGNED=1
      shift
      ;;
    --allow-debug-certificate)
      ALLOW_DEBUG_CERTIFICATE=1
      shift
      ;;
    --expected-certificate-sha256)
      require_value "$1" "${2:-}"
      EXPECTED_CERTIFICATE_SHA256="$REQUIRED_ARG_VALUE"
      shift 2
      ;;
    *)
      fail_parse unknown-argument "Unknown argument: $1"
      ;;
  esac
done

write_report() {
  local status="$1"
  local finding_count="$2"
  local reason="${3:-}"
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'status=%s\n' "$status"
      printf 'target=android-artifact-scan\n'
      printf 'artifactSchema=AndroidArtifactScanReport/v1\n'
      printf 'owner=release-engineering\n'
      printf 'recordedAt=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      printf 'command=%s\n' "$(shell_command)"
      printf 'reproduciblePath=%s\n' "$REPORT_FILE"
      printf 'failedTarget=%s\n' "$(failed_target_for_reason "$reason")"
      printf 'reason=%s\n' "$reason"
      printf 'artifactCount=%s\n' "${#ARTIFACTS[@]}"
      printf 'findingCount=%s\n' "$finding_count"
      printf 'requireSigned=%s\n' "$REQUIRE_SIGNED"
      printf 'allowDebugCertificate=%s\n' "$ALLOW_DEBUG_CERTIFICATE"
      printf 'expectedCertificateSha256=%s\n' "$(normalize_sha256 "$EXPECTED_CERTIFICATE_SHA256")"
      local index=0
      if [[ "${#ARTIFACTS[@]}" -gt 0 ]]; then
        for artifact in "${ARTIFACTS[@]}"; do
          index=$((index + 1))
          printf 'artifact%sPath=%s\n' "$index" "$artifact"
          if [[ -f "$artifact" ]]; then
            printf 'artifact%sSha256=%s\n' "$index" "$(shasum -a 256 "$artifact" | awk '{print $1}')"
            printf 'artifact%sSizeBytes=%s\n' "$index" "$(wc -c < "$artifact" | tr -d ' ')"
            printf 'artifact%sType=%s\n' "$index" "${artifact##*.}"
            printf 'artifact%sSigningStatus=%s\n' "$index" "$(artifact_signing_status "$artifact")"
            printf 'artifact%sCertificateSha256=%s\n' "$index" "$(artifact_certificate_sha256 "$artifact")"
            printf 'artifact%sCertificateSubject=%s\n' "$index" "$(artifact_certificate_subject "$artifact")"
          fi
        done
      fi
    } > "$REPORT_FILE"
  fi
}

if [[ "${#ARTIFACTS[@]}" -eq 0 ]]; then
  write_report failed 1 no-artifact-provided
  echo "No APK or AAB artifact was provided." >&2
  exit 1
fi

TMP_FINDINGS="$(mktemp)"
TMP_REASONS="$(mktemp)"
trap 'rm -f "$TMP_FINDINGS" "$TMP_REASONS"' EXIT

add_finding() {
  local reason="$1"
  local message="$2"
  printf '%s\n' "$reason" >> "$TMP_REASONS"
  printf '%s\n' "$message" >> "$TMP_FINDINGS"
}

finding_reason() {
  awk '!seen[$0]++ {
    if (out) {
      out = out "," $0
    } else {
      out = $0
    }
  } END { print out }' "$TMP_REASONS"
}

find_apksigner() {
  if command -v apksigner >/dev/null 2>&1; then
    command -v apksigner
    return
  fi
  local sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
  find "$sdk/build-tools" -name apksigner -type f 2>/dev/null | sort | tail -n 1
}

artifact_signing_status() {
  local artifact="$1"
  case "$artifact" in
    *.apk)
      local apksigner_bin
      apksigner_bin="$(find_apksigner)"
      if [[ -z "$apksigner_bin" || ! -x "$apksigner_bin" ]]; then
        echo "tool-missing"
        return
      fi
      if "$apksigner_bin" verify "$artifact" >/dev/null 2>&1; then
        echo "verified"
      else
        echo "failed"
      fi
      ;;
    *.aab)
      if ! command -v jarsigner >/dev/null 2>&1; then
        echo "tool-missing"
        return
      fi
      local output
      output="$(jarsigner -verify "$artifact" 2>&1 || true)"
      if grep -q 'jar verified[.]' <<<"$output" && ! grep -qi 'jar is unsigned' <<<"$output"; then
        echo "verified"
      else
        echo "failed"
      fi
      ;;
    *)
      echo "unknown"
      ;;
  esac
}

artifact_certificate_sha256() {
  local artifact="$1"
  case "$artifact" in
    *.apk)
      local apksigner_bin
      apksigner_bin="$(find_apksigner)"
      if [[ -z "$apksigner_bin" || ! -x "$apksigner_bin" ]]; then
        echo ""
        return
      fi
      ("$apksigner_bin" verify --print-certs "$artifact" 2>/dev/null || true) |
        awk -F': ' '/certificate SHA-256 digest/ {print $2; exit}'
      ;;
    *.aab)
      if ! command -v keytool >/dev/null 2>&1; then
        echo ""
        return
      fi
      (keytool -printcert -jarfile "$artifact" 2>/dev/null || true) |
        awk -F': ' '/SHA256:/ {gsub(":", "", $2); print tolower($2); exit}'
      ;;
    *)
      echo ""
      ;;
  esac
}

artifact_certificate_subject() {
  local artifact="$1"
  case "$artifact" in
    *.apk)
      local apksigner_bin
      apksigner_bin="$(find_apksigner)"
      if [[ -z "$apksigner_bin" || ! -x "$apksigner_bin" ]]; then
        echo ""
        return
      fi
      ("$apksigner_bin" verify --print-certs "$artifact" 2>/dev/null || true) |
        awk -F': ' '/certificate DN/ {print $2; exit}'
      ;;
    *.aab)
      if ! command -v keytool >/dev/null 2>&1; then
        echo ""
        return
      fi
      (keytool -printcert -jarfile "$artifact" 2>/dev/null || true) |
        awk -F': ' '/Owner:/ {print $2; exit}'
      ;;
    *)
      echo ""
      ;;
  esac
}

for artifact in "${ARTIFACTS[@]}"; do
  if [[ ! -f "$artifact" ]]; then
    add_finding artifact-missing "$artifact: missing artifact"
    continue
  fi
  entry_list="$(mktemp)"
  if ! unzip -Z1 "$artifact" > "$entry_list" 2>/dev/null; then
    add_finding artifact-not-readable-zip "$artifact: artifact is not a readable zip archive"
    rm -f "$entry_list"
    continue
  fi
  case "$artifact" in
    *.apk)
      if ! grep -qx 'AndroidManifest.xml' "$entry_list"; then
        add_finding apk-manifest-missing "$artifact: APK is missing AndroidManifest.xml"
      fi
      ;;
    *.aab)
      if ! grep -qx 'BundleConfig.pb' "$entry_list"; then
        add_finding aab-bundle-config-missing "$artifact: AAB is missing BundleConfig.pb"
      fi
      if ! grep -qx 'base/manifest/AndroidManifest.xml' "$entry_list"; then
        add_finding aab-manifest-missing "$artifact: AAB is missing base/manifest/AndroidManifest.xml"
      fi
      ;;
  esac
  while IFS= read -r finding; do
    add_finding forbidden-artifact-file "$artifact:$finding"
  done < <(grep -E '(^|/)([^/]+[.]litertlm([.]part[^/]*)?|[^/]+[.]tflite|sentencepiece[.]model|[^/]+[.](jks|keystore|pem|p12))$' "$entry_list" || true)
  rm -f "$entry_list"
  sensitive_index=0
  while IFS= read -r _finding; do
    sensitive_index=$((sensitive_index + 1))
    add_finding sensitive-string "$artifact:string:$sensitive_index:sensitive-string-redacted"
  done < <(unzip -p "$artifact" 2>/dev/null |
    strings |
    grep -E '(-----BEGIN [A-Z ]*PRIVATE KEY-----|AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|xox[abprs]-[0-9A-Za-z-]{16,}|sk-[A-Za-z0-9_-]{24,}|hf_[A-Za-z0-9]{20,}|[Bb]earer[[:space:]]+[A-Za-z0-9._~+/=-]{20,}|(^|[^A-Za-z0-9_])([Aa][Pp][Ii][_-]?[Kk][Ee][Yy]|[Pp][Aa][Ss][Ss][Ww][Oo][Rr][Dd]|[Tt][Oo][Kk][Ee][Nn])[[:space:]]*[:=][[:space:]]*["'\''"]?[A-Za-z0-9._~+/=-]{20,}|code[.]byted[.]org)' \
    || true)
  if [[ "$REQUIRE_SIGNED" == "1" ]]; then
    signing_status="$(artifact_signing_status "$artifact")"
    if [[ "$signing_status" != "verified" ]]; then
      add_finding "signing-status-$signing_status" "$artifact: signing status is $signing_status"
    fi
    certificate_sha256="$(normalize_sha256 "$(artifact_certificate_sha256 "$artifact")")"
    certificate_subject="$(artifact_certificate_subject "$artifact")"
    if [[ "$ALLOW_DEBUG_CERTIFICATE" != "1" ]] && grep -qi 'CN=Android Debug' <<<"$certificate_subject"; then
      add_finding debug-certificate "$artifact: signed with Android debug certificate"
    fi
    if [[ -n "$EXPECTED_CERTIFICATE_SHA256" ]]; then
      expected_sha256="$(normalize_sha256 "$EXPECTED_CERTIFICATE_SHA256")"
      if [[ -z "$certificate_sha256" ]]; then
        add_finding certificate-sha-missing "$artifact: signed certificate SHA-256 is missing"
      elif [[ "$certificate_sha256" != "$expected_sha256" ]]; then
        add_finding certificate-sha-mismatch "$artifact: signed certificate SHA-256 $certificate_sha256 does not match expected $expected_sha256"
      fi
    fi
  fi
done

FINDING_COUNT="$(grep -c . "$TMP_FINDINGS" || true)"
if [[ "$FINDING_COUNT" -gt 0 ]]; then
  write_report failed "$FINDING_COUNT" "$(finding_reason)"
  cat "$TMP_FINDINGS" >&2
  echo "Android artifact scan failed." >&2
  exit 1
fi

write_report passed 0 ""
echo "Android artifact scan passed."
