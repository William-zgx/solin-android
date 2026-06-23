#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REVIEW_FILE="${PRIVACY_REVIEW_FILE:-docs/privacy_review.json}"
NOTICE_FILE="${PRIVACY_NOTICE_FILE:-docs/privacy_notice.md}"
CAPABILITY_MATRIX_FILE="${CAPABILITY_MATRIX_FILE:-docs/capability_matrix.json}"
EVIDENCE_OWNER="${EVIDENCE_OWNER:-${OWNER:-privacy-security}}"
REPORT_FILE=""
ORIGINAL_ARGS=("$@")

command_line() {
  local quoted=()
  local arg
  quoted+=("$(printf '%q' "$0")")
  for arg in "${ORIGINAL_ARGS[@]}"; do
    quoted+=("$(printf '%q' "$arg")")
  done
  local IFS=' '
  printf '%s' "${quoted[*]}"
}

sha256_or_empty() {
  local path="$1"
  if [[ -n "$path" && -f "$path" ]]; then
    shasum -a 256 "$path" | awk '{print $1}'
  fi
}

failed_target_for_reason() {
  local reason="$1"
  local first="${reason%%,*}"
  case "$first" in
    "")
      printf ''
      ;;
    missing-review-file)
      printf 'privacy-review-file'
      ;;
    missing-notice-file|notice-*)
      printf 'privacy-notice'
      ;;
    missing-capability-matrix-file|capability-matrix-*|*-evidence-capability-matrix-*)
      printf 'privacy-capability-matrix'
      ;;
    release-evidence-*|security-evidence-*|legal-evidence-*|*-evidence-*)
      printf 'privacy-review-evidence'
      ;;
    release-review-*|security-review-*|legal-review-*|*-decision-*|*-review-date-*|*-reviewer-*|*-review-role-*)
      printf 'privacy-review-approval'
      ;;
    *)
      printf 'privacy-review-record'
      ;;
  esac
}

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
  local failed_target=""
  if [[ "$status" != "passed" ]]; then
    failed_target="$(failed_target_for_reason "$reason")"
  fi
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'artifactSchema=PrivacyReviewVerification/v1\n'
      printf 'status=%s\n' "$status"
      printf 'target=privacy-review\n'
      printf 'owner=%s\n' "$EVIDENCE_OWNER"
      printf 'recordedAt=%s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
      printf 'command=%s\n' "$(command_line)"
      printf 'failedTarget=%s\n' "$failed_target"
      printf 'reason=%s\n' "$reason"
      printf 'reproduciblePath=%s\n' "$REPORT_FILE"
      printf 'reviewFile=%s\n' "$REVIEW_FILE"
      printf 'reviewSha256=%s\n' "$(sha256_or_empty "$REVIEW_FILE")"
      printf 'noticeFile=%s\n' "$NOTICE_FILE"
      printf 'noticeSha256=%s\n' "$(sha256_or_empty "$NOTICE_FILE")"
      printf 'capabilityMatrixFile=%s\n' "$CAPABILITY_MATRIX_FILE"
      printf 'capabilityMatrixSha256=%s\n' "$(sha256_or_empty "$CAPABILITY_MATRIX_FILE")"
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

if [[ ! -f "$CAPABILITY_MATRIX_FILE" ]]; then
  write_report failed missing-capability-matrix-file
  echo "Capability matrix file is missing: $CAPABILITY_MATRIX_FILE" >&2
  exit 1
fi

TMP_FAILURES="$(mktemp)"
trap 'rm -f "$TMP_FAILURES"' EXIT

set +e
python3 - "$REVIEW_FILE" "$NOTICE_FILE" "$CAPABILITY_MATRIX_FILE" > "$TMP_FAILURES" <<'PY'
import hashlib
import json
import re
import sys
from datetime import date
from pathlib import Path

review_path = Path(sys.argv[1])
notice_path = Path(sys.argv[2])
capability_matrix_path = Path(sys.argv[3])
try:
    review = json.loads(review_path.read_text())
except Exception:
    print("json-parse-error")
    sys.exit(1)
try:
    capability_matrix = json.loads(capability_matrix_path.read_text())
except Exception:
    print("capability-matrix-json-parse-error")
    sys.exit(1)
notice_sha = hashlib.sha256(notice_path.read_bytes()).hexdigest()
capability_matrix_sha = hashlib.sha256(capability_matrix_path.read_bytes()).hexdigest()

failures = []

def non_empty_string(value):
    return isinstance(value, str) and bool(value.strip())

if review.get("version") != 1:
    failures.append("version-invalid")
if review.get("status") != "approved":
    failures.append("status-not-approved")
if review.get("noticePath") != str(notice_path):
    failures.append("notice-path-mismatch")
if review.get("noticeSha256") != notice_sha:
    failures.append("notice-sha-mismatch")
if review.get("capabilityMatrixPath") != str(capability_matrix_path):
    failures.append("capability-matrix-path-mismatch")
if review.get("capabilityMatrixSha256") != capability_matrix_sha:
    failures.append("capability-matrix-sha-mismatch")

if capability_matrix.get("version") != 1:
    failures.append("capability-matrix-version-invalid")
sensitive_disclosures = capability_matrix.get("sensitiveCapabilityDisclosures")
if not isinstance(sensitive_disclosures, list) or not sensitive_disclosures:
    failures.append("capability-matrix-sensitive-disclosures-missing")
    sensitive_disclosures = []
for index, disclosure in enumerate(sensitive_disclosures):
    if not isinstance(disclosure, dict):
        failures.append(f"capability-matrix-disclosure-{index}-invalid")
        continue
    for field in (
        "capabilityId",
        "displayName",
        "dataAccessed",
        "consentBoundary",
        "remoteBoundary",
        "revokeOrClearControl",
    ):
        if not non_empty_string(disclosure.get(field)):
            failures.append(f"capability-matrix-disclosure-{index}-{field}-missing")

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
    privacy_review_file = props.get("privacyReviewFile", "")
    if not non_empty_string(privacy_review_file):
        failures.append(f"{prefix}-evidence-privacy-review-file-missing")
    elif Path(privacy_review_file).resolve() != review_path.resolve():
        failures.append(f"{prefix}-evidence-privacy-review-file-mismatch")
    if props.get("noticePath") != str(notice_path):
        failures.append(f"{prefix}-evidence-notice-path-mismatch")
    if props.get("noticeSha256") != notice_sha:
        failures.append(f"{prefix}-evidence-notice-sha-mismatch")
    if props.get("capabilityMatrixPath") != str(capability_matrix_path):
        failures.append(f"{prefix}-evidence-capability-matrix-path-mismatch")
    if props.get("capabilityMatrixSha256") != capability_matrix_sha:
        failures.append(f"{prefix}-evidence-capability-matrix-sha-mismatch")
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
    role_label = role or "unknown"
    if role not in required_roles:
        failures.append(f"{role_label}-review-role-unknown")
    elif role in seen_roles:
        failures.append(f"{role}-review-role-duplicate")
    else:
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
