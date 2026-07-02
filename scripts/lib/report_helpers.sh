#!/usr/bin/env bash

command_line() {
  if [[ -n "${SOLIN_REPORT_COMMAND_OVERRIDE:-}" ]]; then
    printf '%s' "$SOLIN_REPORT_COMMAND_OVERRIDE"
    return
  fi
  if [[ -n "${COLLECTION_COMMAND_OVERRIDE:-}" ]]; then
    printf '%s' "$COLLECTION_COMMAND_OVERRIDE"
    return
  fi
  local command_name="${SOLIN_SCRIPT_COMMAND:-$0}"
  local quoted=()
  local arg
  quoted+=("$(printf '%q' "$command_name")")
  if [[ "${#ORIGINAL_ARGS[@]}" -gt 0 ]]; then
    for arg in "${ORIGINAL_ARGS[@]}"; do
      quoted+=("$(printf '%q' "$arg")")
    done
  fi
  local IFS=' '
  printf '%s' "${quoted[*]}"
}

sha256_or_empty() {
  local path="$1"
  if [[ -n "$path" && -f "$path" ]]; then
    shasum -a 256 "$path" | awk '{print $1}'
  fi
}

file_size_or_empty() {
  local path="$1"
  if [[ -n "$path" && -f "$path" ]]; then
    wc -c < "$path" | tr -d ' '
  fi
}

report_value() {
  local file="$1"
  local key="$2"
  if [[ -f "$file" ]]; then
    awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; found = 1; exit} END {if (!found) exit 1}' "$file" || true
  fi
}
