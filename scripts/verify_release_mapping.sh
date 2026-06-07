#!/usr/bin/env bash
set -euo pipefail

REPORT_FILE=""
RELEASE_MAPPING_FILE="${RELEASE_MAPPING_FILE:-app/build/outputs/mapping/release/mapping.txt}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file)
      RELEASE_MAPPING_FILE="${2:?missing mapping file}"
      shift 2
      ;;
    --report)
      REPORT_FILE="${2:?missing report path}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

write_report() {
  local status="$1"
  local reason="$2"
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'status=%s\n' "$status"
      printf 'target=release-mapping\n'
      printf 'mappingFile=%s\n' "$RELEASE_MAPPING_FILE"
      printf 'reason=%s\n' "$reason"
      if [[ -f "$RELEASE_MAPPING_FILE" ]]; then
        printf 'mappingSha256=%s\n' "$(shasum -a 256 "$RELEASE_MAPPING_FILE" | awk '{print $1}')"
        printf 'mappingSizeBytes=%s\n' "$(wc -c < "$RELEASE_MAPPING_FILE" | tr -d ' ')"
      else
        printf 'mappingSha256=\n'
        printf 'mappingSizeBytes=0\n'
      fi
    } > "$REPORT_FILE"
  fi
}

if [[ ! -f "$RELEASE_MAPPING_FILE" ]]; then
  write_report failed "mapping-file-missing"
  echo "Release mapping file is missing: $RELEASE_MAPPING_FILE" >&2
  exit 1
fi

if [[ ! -s "$RELEASE_MAPPING_FILE" ]]; then
  write_report failed "mapping-file-empty"
  echo "Release mapping file is empty: $RELEASE_MAPPING_FILE" >&2
  exit 1
fi

write_report passed "ok"
echo "Release mapping verification passed."
