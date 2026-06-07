#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REVIEW_FILE="${PRIVACY_REVIEW_FILE:-docs/privacy_review.json}"
NOTICE_FILE="${PRIVACY_NOTICE_FILE:-docs/privacy_notice.md}"
REPORT_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
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
      printf 'target=privacy-review\n'
      printf 'reviewFile=%s\n' "$REVIEW_FILE"
      printf 'noticeFile=%s\n' "$NOTICE_FILE"
      printf 'reason=%s\n' "$reason"
    } > "$REPORT_FILE"
  fi
}

if [[ ! -f "$REVIEW_FILE" ]]; then
  write_report failed missing-review-file
  echo "Privacy review file is missing: $REVIEW_FILE" >&2
  exit 1
fi

if [[ ! -f "$NOTICE_FILE" ]]; then
  write_report failed missing-notice-file
  echo "Privacy notice file is missing: $NOTICE_FILE" >&2
  exit 1
fi

TMP_FAILURES="$(mktemp)"
trap 'rm -f "$TMP_FAILURES"' EXIT

set +e
python3 - "$REVIEW_FILE" "$NOTICE_FILE" > "$TMP_FAILURES" <<'PY'
import hashlib
import json
import re
import sys
from datetime import date
from pathlib import Path

review_path = Path(sys.argv[1])
notice_path = Path(sys.argv[2])
try:
    review = json.loads(review_path.read_text())
except Exception:
    print("json-parse-error")
    sys.exit(1)
notice_sha = hashlib.sha256(notice_path.read_bytes()).hexdigest()

failures = []
if review.get("version") != 1:
    failures.append("version-invalid")
if review.get("status") != "approved":
    failures.append("status-not-approved")
if review.get("noticePath") != str(notice_path):
    failures.append("notice-path-mismatch")
if review.get("noticeSha256") != notice_sha:
    failures.append("notice-sha-mismatch")

def non_empty_string(value):
    return isinstance(value, str) and bool(value.strip())

def validate_file_sha(prefix, path, expected_sha):
    if not non_empty_string(expected_sha):
        failures.append(f"{prefix}-sha-missing")
        return
    try:
        actual_sha = hashlib.sha256(Path(path).read_bytes()).hexdigest()
    except OSError:
        failures.append(f"{prefix}-sha-read-failed")
        return
    if actual_sha != expected_sha:
        failures.append(f"{prefix}-sha-mismatch")

def properties_for(path):
    props = {}
    try:
        with Path(path).open() as handle:
            for raw_line in handle:
                line = raw_line.rstrip("\n")
                if "=" not in line:
                    continue
                key, value = line.split("=", 1)
                props[key] = value
    except OSError:
        pass
    return props

def validate_review_evidence(role, path):
    prefix = role or "unknown"
    props = properties_for(path)
    if props.get("status") != "approved":
        failures.append(f"{prefix}-evidence-status-not-approved")
    if props.get("approvalStatus") != "approved":
        failures.append(f"{prefix}-evidence-approval-status-not-approved")
    if props.get("target") != "privacy-review-approved-evidence":
        failures.append(f"{prefix}-evidence-target-invalid")
    if props.get("role") != role:
        failures.append(f"{prefix}-evidence-role-mismatch")
    if props.get("noticePath") != str(notice_path):
        failures.append(f"{prefix}-evidence-notice-path-mismatch")
    if props.get("noticeSha256") != notice_sha:
        failures.append(f"{prefix}-evidence-notice-sha-mismatch")
    if not non_empty_string(props.get("scope")):
        failures.append(f"{prefix}-evidence-scope-missing")
    if props.get("requiredDecision") != "approved":
        failures.append(f"{prefix}-evidence-required-decision-invalid")

reviews = review.get("reviews")
if not isinstance(reviews, list):
    failures.append("reviews-missing")
    reviews = []

required_roles = {"release", "security", "legal"}
date_pattern = re.compile(r"^\d{4}-\d{2}-\d{2}$")
today = date.today()
seen_roles = set()
for entry in reviews:
    if not isinstance(entry, dict):
        failures.append("review-entry-invalid")
        continue
    role = entry.get("role", "")
    seen_roles.add(role)
    if entry.get("decision") != "approved":
        failures.append(f"{role or 'unknown'}-decision-not-approved")
    if not entry.get("reviewer"):
        failures.append(f"{role or 'unknown'}-reviewer-missing")
    evidence_path = entry.get("evidencePath", "")
    if not non_empty_string(evidence_path):
        failures.append(f"{role or 'unknown'}-evidence-path-missing")
    elif not Path(evidence_path).is_file():
        failures.append(f"{role or 'unknown'}-evidence-file-missing")
    else:
        validate_file_sha(
            f"{role or 'unknown'}-evidence",
            evidence_path,
            entry.get("evidenceSha256", ""),
        )
        validate_review_evidence(role, evidence_path)
    review_date = entry.get("reviewDate", "")
    if not review_date:
        failures.append(f"{role or 'unknown'}-review-date-missing")
    elif not date_pattern.match(review_date):
        failures.append(f"{role or 'unknown'}-review-date-invalid")
    else:
        try:
            parsed_date = date.fromisoformat(review_date)
        except ValueError:
            failures.append(f"{role or 'unknown'}-review-date-invalid")
        else:
            if parsed_date > today:
                failures.append(f"{role or 'unknown'}-review-date-in-future")

for role in sorted(required_roles - seen_roles):
    failures.append(f"{role}-review-missing")

if failures:
    print(",".join(failures))
    sys.exit(1)
print("approved")
PY
status=$?
set -e

if [[ "$status" -ne 0 ]]; then
  reason="$(cat "$TMP_FAILURES")"
  write_report failed "${reason:-incomplete-review}"
  echo "Privacy review is incomplete." >&2
  exit 1
fi

write_report passed approved
echo "Privacy review verification passed."
