#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_FILE=""
SCAN_TARGETS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --report)
      REPORT_FILE="${2:?missing report path}"
      shift 2
      ;;
    *)
      SCAN_TARGETS+=("$1")
      shift
      ;;
  esac
done

if [[ "${#SCAN_TARGETS[@]}" -eq 0 ]]; then
  SCAN_TARGETS=("$ROOT_DIR")
fi

write_report() {
  local status="$1"
  local finding_count="$2"
  local reason="${3:-}"
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'status=%s\n' "$status"
      printf 'target=privacy-scan\n'
      printf 'reason=%s\n' "$reason"
      printf 'scanTargetCount=%s\n' "${#SCAN_TARGETS[@]}"
      printf 'findingCount=%s\n' "$finding_count"
    } > "$REPORT_FILE"
  fi
}

TMP_FINDINGS="$(mktemp)"
trap 'rm -f "$TMP_FINDINGS"' EXIT

PATTERN='(-----BEGIN [A-Z ]*PRIVATE KEY-----|AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|xox[abprs]-[0-9A-Za-z-]{16,}|sk-[A-Za-z0-9_-]{24,})'

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
