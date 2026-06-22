#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REVIEW_FILE="${MODEL_LICENSE_REVIEW_FILE:-docs/model_license_review.json}"
METADATA_FILE="${MODEL_LICENSE_METADATA_FILE:-docs/model_license_metadata.json}"
MANIFEST_FILE="${MODEL_MANIFEST_FILE:-docs/model_manifest.md}"
METADATA_MAX_AGE_DAYS="${MODEL_LICENSE_METADATA_MAX_AGE_DAYS:-30}"
EVIDENCE_OWNER="${EVIDENCE_OWNER:-${OWNER:-model-license-review}}"
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
    missing-review-file|review-version-invalid|review-models-missing|review-entry-invalid|review-model-*)
      printf 'model-license-review-file'
      ;;
    missing-metadata-file|metadata-*)
      printf 'model-license-metadata'
      ;;
    missing-manifest-file|manifest-*|*-manifest-*)
      printf 'model-manifest'
      ;;
    *-review-evidence-*)
      printf 'model-license-review-evidence'
      ;;
    *-license-source-*|*-license-source-*)
      printf 'model-license-source'
      ;;
    *-license-*|*-redistribution-*|*-status-*|*-repository-*|*-upstream-revision-*|*-attribution-*|*-reviewer-*|*-review-date-*)
      printf 'model-license-review-record'
      ;;
    *)
      printf 'model-license-review'
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
      printf 'artifactSchema=ModelLicenseReviewVerification/v1\n'
      printf 'status=%s\n' "$status"
      printf 'target=model-license-review\n'
      printf 'owner=%s\n' "$EVIDENCE_OWNER"
      printf 'recordedAt=%s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
      printf 'command=%s\n' "$(command_line)"
      printf 'failedTarget=%s\n' "$failed_target"
      printf 'reason=%s\n' "$reason"
      printf 'reproduciblePath=%s\n' "$REPORT_FILE"
      printf 'reviewFile=%s\n' "$REVIEW_FILE"
      printf 'reviewSha256=%s\n' "$(sha256_or_empty "$REVIEW_FILE")"
      printf 'metadataFile=%s\n' "$METADATA_FILE"
      printf 'metadataSha256=%s\n' "$(sha256_or_empty "$METADATA_FILE")"
      printf 'manifestFile=%s\n' "$MANIFEST_FILE"
      printf 'manifestSha256=%s\n' "$(sha256_or_empty "$MANIFEST_FILE")"
      printf 'metadataMaxAgeDays=%s\n' "$METADATA_MAX_AGE_DAYS"
    } > "$REPORT_FILE"
  fi
}

if [[ ! -f "$REVIEW_FILE" ]]; then
  write_report failed missing-review-file
  echo "Model license review file is missing: $REVIEW_FILE" >&2
  exit 1
fi

if [[ ! -f "$METADATA_FILE" ]]; then
  write_report failed missing-metadata-file
  echo "Model license metadata file is missing: $METADATA_FILE" >&2
  exit 1
fi

if [[ ! -f "$MANIFEST_FILE" ]]; then
  write_report failed missing-manifest-file
  echo "Model manifest file is missing: $MANIFEST_FILE" >&2
  exit 1
fi

TMP_FAILURES="$(mktemp)"
trap 'rm -f "$TMP_FAILURES"' EXIT

set +e
python3 - "$REVIEW_FILE" "$METADATA_FILE" "$MANIFEST_FILE" "$METADATA_MAX_AGE_DAYS" <<'PY' > "$TMP_FAILURES"
import hashlib
import json
import re
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlparse

review_path = Path(sys.argv[1])
metadata_path = Path(sys.argv[2])
manifest_path = Path(sys.argv[3])
max_age_days_raw = sys.argv[4]

try:
    review = json.loads(review_path.read_text())
    metadata = json.loads(metadata_path.read_text())
except Exception:
    print("json-parse-error")
    sys.exit(1)

failures = []

try:
    metadata_max_age_days = int(max_age_days_raw)
    if metadata_max_age_days < 0:
        raise ValueError
except ValueError:
    metadata_max_age_days = None
    failures.append("metadata-max-age-days-invalid")

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

def validate_utc_timestamp_not_future(prefix, value):
    if not non_empty_string(value):
        failures.append(f"{prefix}-recorded-at-missing")
        return
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError:
        failures.append(f"{prefix}-recorded-at-invalid")
        return
    if parsed > datetime.now(timezone.utc):
        failures.append(f"{prefix}-recorded-at-in-future")

def parse_properties(path):
    values = {}
    for raw_line in Path(path).read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values

def normalize_license(value):
    normalized = re.sub(r"[^a-z0-9]+", "", value.lower())
    return normalized.replace("license", "")

def repo_from_huggingface_url(url):
    prefix = "https://huggingface.co/"
    if not isinstance(url, str) or not url.startswith(prefix):
        return ""
    suffix = url[len(prefix):].strip("/")
    if not suffix:
        return ""
    if suffix.startswith("api/models/"):
        return suffix[len("api/models/"):].strip("/")
    parts = suffix.split("/")
    if len(parts) < 2:
        return ""
    return "/".join(parts[:2])

def is_concrete_huggingface_license_source(url):
    if not isinstance(url, str) or not url.startswith("https://huggingface.co/"):
        return False
    parsed = urlparse(url)
    path_parts = [part for part in parsed.path.strip("/").split("/") if part]
    if path_parts[:2] == ["api", "models"]:
        path_parts = path_parts[2:]
    if len(path_parts) <= 2:
        return False
    file_parts = path_parts[2:]
    if file_parts[0] not in {"blob", "resolve", "raw"}:
        return False
    if len(file_parts) < 3:
        return False
    filename = file_parts[-1].lower()
    source_path = "/".join(file_parts[2:]).lower()
    license_markers = ("license", "licence", "copying", "notice", "readme", "model_card", "model-card", "terms")
    return any(marker in filename or marker in source_path for marker in license_markers)

def huggingface_source_revision(url):
    if not isinstance(url, str) or not url.startswith("https://huggingface.co/"):
        return ""
    parsed = urlparse(url)
    path_parts = [part for part in parsed.path.strip("/").split("/") if part]
    if path_parts[:2] == ["api", "models"]:
        path_parts = path_parts[2:]
    if len(path_parts) < 5:
        return ""
    file_parts = path_parts[2:]
    if file_parts[0] not in {"blob", "resolve", "raw"} or len(file_parts) < 3:
        return ""
    return file_parts[1]

def parse_manifest(path):
    models = []
    for raw_line in path.read_text().splitlines():
        line = raw_line.strip()
        if not line.startswith("| `"):
            continue
        columns = [part.strip() for part in line.strip("|").split("|")]
        if len(columns) < 6:
            continue
        model_id = columns[0].strip("`")
        repository_url = columns[2].strip("`")
        revision = columns[3].strip("`")
        if not model_id or model_id == "ID":
            continue
        repository = repo_from_huggingface_url(repository_url)
        if not repository:
            failures.append(f"{model_id}-manifest-repository-invalid")
        models.append({
            "id": model_id,
            "repository": repository,
            "upstreamRevision": revision,
        })
    return models

def validate_review_evidence(model_id, evidence_path, entry):
    try:
        properties = parse_properties(evidence_path)
    except OSError:
        failures.append(f"{model_id or 'unknown'}-review-evidence-read-failed")
        return
    prefix = model_id or "unknown"
    expected = {
        "artifactSchema": "ModelLicenseReviewApprovedEvidence/v1",
        "status": "approved",
        "target": "model-license-review-approved-evidence",
        "model": model_id,
        "scope": "license-redistribution-attribution",
        "redistributionDecision": "approved",
        "reviewer": entry.get("reviewer", ""),
        "licenseName": entry.get("licenseName", ""),
    }
    for key, expected_value in expected.items():
        actual = properties.get(key, "")
        if actual != expected_value:
            failures.append(f"{prefix}-review-evidence-{key}-mismatch")
    if not non_empty_string(properties.get("owner", "")):
        failures.append(f"{prefix}-review-evidence-owner-missing")
    validate_utc_timestamp_not_future(
        f"{prefix}-review-evidence",
        properties.get("recordedAt", ""),
    )
    if not non_empty_string(properties.get("command", "")):
        failures.append(f"{prefix}-review-evidence-command-missing")
    reproducible_path = properties.get("reproduciblePath", "")
    if not non_empty_string(reproducible_path):
        failures.append(f"{prefix}-review-evidence-reproducible-path-missing")
    elif reproducible_path != str(evidence_path):
        failures.append(f"{prefix}-review-evidence-reproducible-path-mismatch")

if review.get("version") != 1:
    failures.append("review-version-invalid")
if metadata.get("version") != 1:
    failures.append("metadata-version-invalid")

review_models = review.get("models")
metadata_models = metadata.get("models")
if not isinstance(review_models, list) or not review_models:
    failures.append("review-models-missing")
    review_models = []
if not isinstance(metadata_models, list) or not metadata_models:
    failures.append("metadata-models-missing")
    metadata_models = []

manifest_models = parse_manifest(manifest_path)
if not manifest_models:
    failures.append("manifest-models-missing")

manifest_ids = [entry["id"] for entry in manifest_models]
manifest_by_id = {entry["id"]: entry for entry in manifest_models}

recorded_at = metadata.get("recordedAt", "")
metadata_recorded_at = None
metadata_record_date = None
if not recorded_at:
    failures.append("metadata-recorded-at-missing")
elif not re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$", recorded_at):
    failures.append("metadata-recorded-at-not-utc")
else:
    try:
        metadata_recorded_at = datetime.strptime(recorded_at, "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc,
        )
        metadata_record_date = metadata_recorded_at.date()
    except ValueError:
        failures.append("metadata-recorded-at-invalid")
    else:
        now_utc = datetime.now(timezone.utc)
        if metadata_recorded_at > now_utc:
            failures.append("metadata-recorded-at-in-future")
        elif (
            metadata_max_age_days is not None and
            now_utc - metadata_recorded_at > timedelta(days=metadata_max_age_days)
        ):
            failures.append("metadata-recorded-at-stale")

metadata_ids = []
metadata_by_id = {}
for entry in metadata_models:
    if not isinstance(entry, dict):
        failures.append("metadata-entry-invalid")
        continue
    model_id = entry.get("id", "")
    if not model_id:
        failures.append("metadata-id-missing")
    metadata_ids.append(model_id)
    if model_id:
        metadata_by_id[model_id] = entry
    if entry.get("metadataOnly") is not True:
        failures.append(f"{model_id or 'unknown'}-metadata-only-not-true")
    manifest_entry = manifest_by_id.get(model_id)
    if manifest_entry:
        if entry.get("repository") != manifest_entry["repository"]:
            failures.append(f"{model_id}-metadata-repository-mismatch")
        if entry.get("manifestRevision") != manifest_entry["upstreamRevision"]:
            failures.append(f"{model_id}-metadata-manifest-revision-mismatch")
        api_url = entry.get("apiUrl", "")
        expected_api_url = f"https://huggingface.co/api/models/{manifest_entry['repository']}"
        if api_url != expected_api_url:
            failures.append(f"{model_id}-metadata-api-url-mismatch")
    model_sha = entry.get("modelSha")
    if not non_empty_string(model_sha):
        failures.append(f"{model_id or 'unknown'}-metadata-model-sha-missing")
    elif not re.match(r"^[0-9a-fA-F]{40,64}$", model_sha):
        failures.append(f"{model_id or 'unknown'}-metadata-model-sha-invalid")
    gated = entry.get("gated")
    if not isinstance(gated, bool):
        failures.append(f"{model_id or 'unknown'}-metadata-gated-invalid")
    elif gated and entry.get("requiresUserAuthorization") is not True:
        failures.append(f"{model_id or 'unknown'}-metadata-gated-without-user-authorization")
    license_values = []
    if non_empty_string(entry.get("cardLicense")):
        license_values.append(entry["cardLicense"])
    license_tags = entry.get("licenseTags")
    if isinstance(license_tags, list):
        license_values.extend(tag for tag in license_tags if non_empty_string(tag))
    if not license_values:
        failures.append(f"{model_id or 'unknown'}-metadata-license-missing")

review_ids = []
seen_ids = set()
date_pattern = re.compile(r"^\d{4}-\d{2}-\d{2}$")
today = date.today()
for entry in review_models:
    if not isinstance(entry, dict):
        failures.append("review-entry-invalid")
        continue
    model_id = entry.get("id", "")
    if not model_id:
        failures.append("review-id-missing")
    elif model_id in seen_ids:
        failures.append(f"{model_id}-duplicate")
    seen_ids.add(model_id)
    review_ids.append(model_id)
    manifest_entry = manifest_by_id.get(model_id)
    metadata_entry = metadata_by_id.get(model_id)

    if entry.get("status") != "approved":
        failures.append(f"{model_id or 'unknown'}-status-not-approved")
    if entry.get("redistributionDecision") != "approved":
        failures.append(f"{model_id or 'unknown'}-redistribution-not-approved")
    if not entry.get("licenseName"):
        failures.append(f"{model_id or 'unknown'}-license-name-missing")
    elif metadata_entry:
        metadata_license_values = []
        if non_empty_string(metadata_entry.get("cardLicense")):
            metadata_license_values.append(metadata_entry["cardLicense"])
        tags = metadata_entry.get("licenseTags")
        if isinstance(tags, list):
            metadata_license_values.extend(tag for tag in tags if non_empty_string(tag))
        review_license = normalize_license(entry["licenseName"])
        metadata_licenses = [normalize_license(value) for value in metadata_license_values]
        if metadata_licenses and not any(
            license_value and (license_value in review_license or review_license in license_value)
            for license_value in metadata_licenses
        ):
            failures.append(f"{model_id or 'unknown'}-license-name-metadata-mismatch")

    if manifest_entry:
        if entry.get("repository") != manifest_entry["repository"]:
            failures.append(f"{model_id or 'unknown'}-repository-mismatch")
        if entry.get("upstreamRevision") != manifest_entry["upstreamRevision"]:
            failures.append(f"{model_id or 'unknown'}-upstream-revision-mismatch")

    license_source = entry.get("licenseUrl", "")
    if not license_source:
        failures.append(f"{model_id or 'unknown'}-license-source-missing")
    elif not license_source.startswith("https://") and not Path(license_source).is_file():
        failures.append(f"{model_id or 'unknown'}-license-source-invalid")
    elif manifest_entry and license_source.startswith("https://huggingface.co/"):
        source_repo = repo_from_huggingface_url(license_source)
        if source_repo != manifest_entry["repository"]:
            failures.append(f"{model_id or 'unknown'}-license-source-repository-mismatch")
        if not is_concrete_huggingface_license_source(license_source):
            failures.append(f"{model_id or 'unknown'}-license-source-not-concrete")
        source_revision = huggingface_source_revision(license_source)
        if source_revision != manifest_entry["upstreamRevision"]:
            failures.append(f"{model_id or 'unknown'}-license-source-revision-mismatch")

    if not entry.get("attributionNotice"):
        failures.append(f"{model_id or 'unknown'}-attribution-notice-missing")
    if not entry.get("reviewer"):
        failures.append(f"{model_id or 'unknown'}-reviewer-missing")
    review_evidence_path = entry.get("reviewEvidencePath", "")
    if not non_empty_string(review_evidence_path):
        failures.append(f"{model_id or 'unknown'}-review-evidence-path-missing")
    elif not Path(review_evidence_path).is_file():
        failures.append(f"{model_id or 'unknown'}-review-evidence-file-missing")
    else:
        validate_file_sha(
            f"{model_id or 'unknown'}-review-evidence",
            review_evidence_path,
            entry.get("reviewEvidenceSha256", ""),
        )
        validate_review_evidence(model_id, review_evidence_path, entry)
    review_date = entry.get("reviewDate", "")
    if not review_date:
        failures.append(f"{model_id or 'unknown'}-review-date-missing")
    elif not date_pattern.match(review_date):
        failures.append(f"{model_id or 'unknown'}-review-date-invalid")
    else:
        try:
            parsed_date = date.fromisoformat(review_date)
        except ValueError:
            failures.append(f"{model_id or 'unknown'}-review-date-invalid")
        else:
            if parsed_date > today:
                failures.append(f"{model_id or 'unknown'}-review-date-in-future")
            if metadata_record_date and parsed_date < metadata_record_date:
                failures.append(f"{model_id or 'unknown'}-review-date-before-metadata")

if metadata_ids and review_ids != metadata_ids:
    failures.append("review-model-ids-do-not-match-metadata")
if manifest_ids and metadata_ids != manifest_ids:
    failures.append("metadata-model-ids-do-not-match-manifest")
if manifest_ids and review_ids != manifest_ids:
    failures.append("review-model-ids-do-not-match-manifest")

if failures:
    print(",".join(failures))
    sys.exit(1)

print("approved")
PY
status=$?
set -e

if [[ "$status" -ne 0 ]]; then
  reason="$(cat "$TMP_FAILURES")"
  write_report failed "${reason:-incomplete-license-review}"
  echo "Model license review is incomplete." >&2
  exit 1
fi

write_report passed approved
echo "Model license review verification passed."
