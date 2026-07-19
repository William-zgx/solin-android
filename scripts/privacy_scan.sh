#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_FILE=""
SCAN_TARGETS=()
ORIGINAL_ARGS=("$@")
SOLIN_SCRIPT_COMMAND="scripts/privacy_scan.sh"
source "$ROOT_DIR/scripts/lib/report_helpers.sh"

failed_target_for_reason() {
  case "$1" in
    unknown-argument|missing-*-argument)
      printf 'argument-parser'
      ;;
    secret-pattern-detected)
      printf 'scan-target'
      ;;
    placement-trace-sensitive-field)
      printf 'placement-invocation-trace'
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
      printf 'command=%s\n' "$(command_line)"
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
    --exclude-dir=.trae \
    --exclude-dir=test \
    --exclude-dir=androidTest \
    --exclude='*.png' \
    --exclude='*.jpg' \
    --exclude='*.jpeg' \
    --exclude='*.webp' \
    --exclude='privacy_scan.sh' \
    --exclude='scan_android_artifacts.sh' \
    --exclude='verify_release_gate.sh' \
    || true)
done

python3 - "${SCAN_TARGETS[@]}" >> "$TMP_FINDINGS" <<'PY'
import json
import os
import re
import sys
from pathlib import Path

trace_allowed_fields = {
    "PlacementSelected": {
        "type", "stepType", "eventType", "schemaVersion", "runId", "policyVersion",
        "preference", "placement", "primaryReason", "complexity", "resourceBand",
        "localState", "remoteState", "remoteProfileRevision",
    },
    "ModelRuntimeInvocationStarted": {
        "type", "stepType", "eventType", "schemaVersion", "runId", "placement",
        "attempt", "remoteProfileRevision",
    },
    "ShadowPlacementEvaluated": {
        "type", "stepType", "eventType", "schemaVersion", "runId", "policyVersion",
        "preference", "placement", "primaryReason", "complexity", "resourceBand",
        "localState", "remoteState",
    },
    "RunDataReceiptRecorded": {
        "type", "stepType", "eventType", "destination", "currentPromptPrivacy",
        "remoteHistoryCount", "localOnlyHistoryFilteredCount", "memoryHitCount",
        "semanticMemoryHitCount", "lexicalMemoryHitCount", "memoryContextIncluded",
        "deviceContextIncluded", "imageAttachmentCount", "protectedSourceCount",
        "evidenceCardCount", "localOnlyEvidenceCardCount", "truncatedEvidenceCardCount",
        "lowQualityEvidenceCardCount", "evidenceSourceTypes", "rawContentPersisted",
        "protectedContentTypes", "deletableRecordTypes", "outputQualityGuardTriggered",
        "outputQualityIssue", "outputQualityRule", "outputQualityAction",
        "outputQualityStopped", "outputQualityKeptPrefix",
    },
}
trace_types = set(trace_allowed_fields)
excluded_dir_names = {".git", ".gradle", "build", ".idea", ".trae", "test", "androidTest"}
forbidden_exact_keys = {
    "prompt",
    "rawprompt",
    "endpoint",
    "baseurl",
    "url",
    "host",
    "ip",
    "ipaddress",
    "token",
    "accesstoken",
    "authtoken",
    "bearertoken",
    "apikey",
    "authorization",
    "secret",
    "input",
    "rawinput",
    "content",
    "rawcontent",
    "toolargs",
    "requestbody",
    "responsebody",
}
forbidden_key_fragments = {
    "prompttext",
    "endpointurl",
    "apikey",
    "authorization",
    "toolarguments",
    "rawpayload",
}
sensitive_value_patterns = [
    re.compile(r"https?://", re.I),
    re.compile(r"(?<![0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])"),
    re.compile(r"\bBearer\s+[A-Za-z0-9._~+/=-]+", re.I),
    re.compile(r"\bsk-[A-Za-z0-9_-]{8,}"),
    re.compile(r"\bhf_[A-Za-z0-9]{8,}"),
]
stable_metadata_string = re.compile(r"^[A-Za-z0-9_.:-]{1,160}$")


def normalized_key(value):
    return re.sub(r"[^a-z0-9]", "", str(value).lower())


def sensitive_key(value):
    normalized = normalized_key(value)
    return normalized in forbidden_exact_keys or any(
        fragment in normalized for fragment in forbidden_key_fragments
    )


def should_skip(path):
    parts = set(path.parts)
    if parts & excluded_dir_names:
        return True
    joined = "/".join(path.parts)
    return "/app/build/" in f"/{joined}/" or "/app/src/test/" in f"/{joined}/" or "/app/src/androidTest/" in f"/{joined}/"


def iter_files(target):
    if target.is_file():
        yield target
        return
    if not target.is_dir():
        return
    for root, dir_names, file_names in os.walk(target):
        root_path = Path(root)
        dir_names[:] = [
            name for name in dir_names
            if not should_skip(root_path / name)
        ]
        for name in file_names:
            path = root_path / name
            if not should_skip(path):
                yield path


def emit(path, line_number, field):
    print(f"{path}:{line_number}:placement-trace-sensitive-field:{field}")


def scan_trace_object(path, line_number, trace_type, trace):
    findings = []
    allowed_fields = trace_allowed_fields[trace_type]
    for key in trace:
        if key not in allowed_fields:
            findings.append(str(key))

    def visit(value, field_path=""):
        if isinstance(value, dict):
            for key, child in value.items():
                next_path = f"{field_path}.{key}" if field_path else str(key)
                if key not in {"type", "stepType", "eventType"} and sensitive_key(key):
                    findings.append(next_path)
                visit(child, next_path)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                visit(child, f"{field_path}[{index}]")
        elif isinstance(value, str):
            if (
                any(pattern.search(value) for pattern in sensitive_value_patterns) or
                not stable_metadata_string.fullmatch(value)
            ):
                findings.append(field_path or "value")

    visit(trace)
    for field in sorted(set(findings)):
        emit(path, line_number, field)


def scan_json_value(path, line_number, value):
    if isinstance(value, dict):
        trace_type = next(
            (
                value.get(key)
                for key in ("type", "stepType", "eventType")
                if value.get(key) in trace_types
            ),
            None,
        )
        if trace_type:
            scan_trace_object(path, line_number, trace_type, value)
            return
        for child in value.values():
            scan_json_value(path, line_number, child)
    elif isinstance(value, list):
        for child in value:
            scan_json_value(path, line_number, child)


def scan_json_file(path, text):
    parsed_line = False
    for line_number, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        parsed_line = True
        scan_json_value(path, line_number, value)
    if parsed_line:
        return
    try:
        value = json.loads(text)
    except json.JSONDecodeError:
        return
    scan_json_value(path, 1, value)


def scan_kotlin_file(path, text):
    block_pattern = re.compile(
        r"is\s+AgentStep\.(PlacementSelected|ModelRuntimeInvocationStarted|ShadowPlacementEvaluated|RunDataReceiptRecorded)\s*->"
        r"(?P<body>.*?)(?=\n\s*is\s+AgentStep\.|\Z)",
        re.S,
    )
    for match in block_pattern.finditer(text):
        line_number = text.count("\n", 0, match.start()) + 1
        trace_type = match.group(1)
        body = match.group("body")
        for key in re.findall(r'\.put\(\s*"([^"]+)"', body):
            if key not in trace_allowed_fields[trace_type] or sensitive_key(key):
                emit(path, line_number, key)
        if re.search(r'\.put\(\s*[^"\s]', body):
            emit(path, line_number, "dynamic-field")


for raw_target in sys.argv[1:]:
    target = Path(raw_target)
    for path in iter_files(target):
        if path.suffix.lower() not in {".json", ".jsonl", ".kt"}:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        if path.suffix.lower() == ".kt":
            scan_kotlin_file(path, text)
        else:
            scan_json_file(path, text)
PY

FINDING_COUNT="$(grep -c . "$TMP_FINDINGS" || true)"
if [[ "$FINDING_COUNT" -gt 0 ]]; then
  finding_reason="secret-pattern-detected"
  if grep -q ':placement-trace-sensitive-field:' "$TMP_FINDINGS"; then
    finding_reason="placement-trace-sensitive-field"
  fi
  write_report failed "$FINDING_COUNT" "$finding_reason"
  cat "$TMP_FINDINGS" >&2
  echo "Privacy scan found sensitive trace fields or high-confidence secret patterns; matched values are redacted." >&2
  exit 1
fi

write_report passed 0 ""
echo "Privacy scan passed."
