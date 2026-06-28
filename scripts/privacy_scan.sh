#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_FILE=""
SCAN_TARGETS=()
ORIGINAL_ARGS=("$@")

sha256_or_empty() {
  local path="$1"
  if [[ -n "$path" && -f "$path" ]]; then
    shasum -a 256 "$path" | awk '{print $1}'
  fi
}

shell_command() {
  local quoted=()
  local arg
  quoted+=("$(printf '%q' "scripts/privacy_scan.sh")")
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
    secret-pattern-detected)
      printf 'scan-target'
      ;;
    *)
      printf ''
      ;;
  esac
}

write_report() {
  local status="$1"
  local finding_count="$2"
  local reason="${3:-}"
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'status=%s\n' "$status"
      printf 'target=privacy-scan\n'
      printf 'artifactSchema=PrivacyScanReport/v1\n'
      printf 'owner=privacy-security\n'
      printf 'recordedAt=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      printf 'command=%s\n' "$(shell_command)"
      printf 'reproduciblePath=%s\n' "$REPORT_FILE"
      printf 'failedTarget=%s\n' "$(failed_target_for_reason "$reason")"
      printf 'reason=%s\n' "$reason"
      printf 'scanTargetCount=%s\n' "${#SCAN_TARGETS[@]}"
      printf 'findingCount=%s\n' "$finding_count"
      local index=0
      local target
      if [[ "${#SCAN_TARGETS[@]}" -gt 0 ]]; then
        for target in "${SCAN_TARGETS[@]}"; do
          index=$((index + 1))
          printf 'scanTarget%sPath=%s\n' "$index" "$target"
          printf 'scanTarget%sSha256=%s\n' "$index" "$(sha256_or_empty "$target")"
        done
      fi
    } > "$REPORT_FILE"
  fi
}

fail_parse() {
  local reason="$1"
  local message="$2"
  write_report failed 1 "$reason"
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
    --report)
      require_value "$1" "${2:-}"
      REPORT_FILE="$REQUIRED_ARG_VALUE"
      shift 2
      ;;
    *)
      if [[ "$1" == --* ]]; then
        fail_parse unknown-argument "Unknown argument: $1"
      fi
      SCAN_TARGETS+=("$1")
      shift
      ;;
  esac
done

if [[ "${#SCAN_TARGETS[@]}" -eq 0 ]]; then
  SCAN_TARGETS=("$ROOT_DIR")
fi

TMP_FINDINGS="$(mktemp)"
trap 'rm -f "$TMP_FINDINGS"' EXIT

PATTERN='(-----BEGIN [A-Z ]*PRIVATE KEY-----|AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|xox[abprs]-[0-9A-Za-z-]{16,}|sk-[A-Za-z0-9_-]{24,}|hf_[A-Za-z0-9]{20,}|[Bb]earer[[:space:]]+[A-Za-z0-9._~+/=-]{20,}|(^|[^A-Za-z0-9_])([Aa][Pp][Ii][_-]?[Kk][Ee][Yy]|[Pp][Aa][Ss][Ss][Ww][Oo][Rr][Dd]|[Tt][Oo][Kk][Ee][Nn])[[:space:]]*[:=][[:space:]]*["'\''"]?[A-Za-z0-9._~+/=-]{20,})'

for target in "${SCAN_TARGETS[@]}"; do
  [[ -e "$target" ]] || continue
  while IFS= read -r finding; do
    finding_path="${finding%%:*}"
    finding_rest="${finding#*:}"
    finding_line="${finding_rest%%:*}"
    printf '%s:%s:secret-pattern-detected\n' "$finding_path" "$finding_line" >> "$TMP_FINDINGS"
  done < <(grep -R -n -I -E "$PATTERN" "$target" \
    --exclude-dir=.git \
    --exclude-dir=.gradle \
    --exclude-dir=build \
    --exclude-dir=.idea \
    --exclude-dir=app/build \
    --exclude-dir=app/src/test \
    --exclude-dir=app/src/androidTest \
    --exclude='*.png' \
    --exclude='*.jpg' \
    --exclude='*.jpeg' \
    --exclude='*.webp' \
    --exclude='privacy_scan.sh' \
    --exclude='scan_android_artifacts.sh' \
    --exclude='verify_release_gate.sh' \
    || true)
done

FINDING_COUNT="$(grep -c . "$TMP_FINDINGS" || true)"
if [[ "$FINDING_COUNT" -gt 0 ]]; then
  write_report failed "$FINDING_COUNT" secret-pattern-detected
  cat "$TMP_FINDINGS" >&2
  echo "Privacy scan found high-confidence secret patterns; matched values are redacted." >&2
  exit 1
fi

write_report passed 0 ""
echo "Privacy scan passed."
