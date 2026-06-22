#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

STORE_POLICY_FILE="${STORE_POLICY_FILE:-docs/store_policy_record.json}"
PRIVACY_NOTICE_FILE="${PRIVACY_NOTICE_FILE:-docs/privacy_notice.md}"
MANIFEST_FILE="${MANIFEST_FILE:-app/src/main/AndroidManifest.xml}"
MODEL_CAPABILITY_PROFILES_FILE="${MODEL_CAPABILITY_PROFILES_FILE:-docs/model_capability_profiles.json}"
MODEL_MANIFEST_FILE="${MODEL_MANIFEST_FILE:-docs/model_manifest.md}"
EVIDENCE_OWNER="${EVIDENCE_OWNER:-${OWNER:-release-engineering}}"
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
    missing-store-policy-file|store-policy-sha-*)
      printf 'store-policy-record-file'
      ;;
    missing-privacy-notice-file|privacy-notice-*)
      printf 'privacy-notice'
      ;;
    missing-manifest-file|manifest-*|android.permission.*)
      printf 'android-manifest'
      ;;
    app-listing-*|contact-email-*|privacy-policy-url-*|short-description-*|full-description-*)
      printf 'store-listing'
      ;;
    data-safety-*|userData*|encryptedInTransit-*|external-recipients-*)
      printf 'data-safety'
      ;;
    missing-model-capability-profiles-file|model-capability-profiles-*)
      printf 'model-capability-profiles'
      ;;
    missing-model-manifest-file|model-manifest-*)
      printf 'model-manifest'
      ;;
    model-downloads-*)
      printf 'store-model-downloads'
      ;;
    review-*|reviewer-*|approvalStatus-*|approval-status-*)
      printf 'policy-review-evidence'
      ;;
    *special-access*)
      printf 'special-access-disclosure'
      ;;
    *)
      printf 'store-policy-record'
      ;;
  esac
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file)
      STORE_POLICY_FILE="${2:?missing store policy file}"
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
  local failed_target=""
  if [[ "$status" != "passed" ]]; then
    failed_target="$(failed_target_for_reason "$reason")"
  fi
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'artifactSchema=StorePolicyRecordVerification/v1\n'
      printf 'status=%s\n' "$status"
      printf 'target=store-policy-record\n'
      printf 'owner=%s\n' "$EVIDENCE_OWNER"
      printf 'recordedAt=%s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
      printf 'command=%s\n' "$(command_line)"
      printf 'failedTarget=%s\n' "$failed_target"
      printf 'reason=%s\n' "$reason"
      printf 'reproduciblePath=%s\n' "$REPORT_FILE"
      printf 'storePolicyFile=%s\n' "$STORE_POLICY_FILE"
      printf 'storePolicySha256=%s\n' "$(sha256_or_empty "$STORE_POLICY_FILE")"
      printf 'privacyNoticeFile=%s\n' "$PRIVACY_NOTICE_FILE"
      printf 'privacyNoticeSha256=%s\n' "$(sha256_or_empty "$PRIVACY_NOTICE_FILE")"
      printf 'manifestFile=%s\n' "$MANIFEST_FILE"
      printf 'manifestSha256=%s\n' "$(sha256_or_empty "$MANIFEST_FILE")"
      printf 'modelCapabilityProfilesFile=%s\n' "$MODEL_CAPABILITY_PROFILES_FILE"
      printf 'modelCapabilityProfilesSha256=%s\n' "$(sha256_or_empty "$MODEL_CAPABILITY_PROFILES_FILE")"
      printf 'modelManifestFile=%s\n' "$MODEL_MANIFEST_FILE"
      printf 'modelManifestSha256=%s\n' "$(sha256_or_empty "$MODEL_MANIFEST_FILE")"
    } > "$REPORT_FILE"
  fi
}

if [[ ! -f "$STORE_POLICY_FILE" ]]; then
  write_report failed missing-store-policy-file
  echo "Store policy file is missing: $STORE_POLICY_FILE" >&2
  exit 1
fi

if [[ ! -f "$PRIVACY_NOTICE_FILE" ]]; then
  write_report failed missing-privacy-notice-file
  echo "Privacy notice file is missing: $PRIVACY_NOTICE_FILE" >&2
  exit 1
fi

if [[ ! -f "$MANIFEST_FILE" ]]; then
  write_report failed missing-manifest-file
  echo "Android manifest file is missing: $MANIFEST_FILE" >&2
  exit 1
fi

if [[ ! -f "$MODEL_CAPABILITY_PROFILES_FILE" ]]; then
  write_report failed missing-model-capability-profiles-file
  echo "Model capability profiles file is missing: $MODEL_CAPABILITY_PROFILES_FILE" >&2
  exit 1
fi

if [[ ! -f "$MODEL_MANIFEST_FILE" ]]; then
  write_report failed missing-model-manifest-file
  echo "Model manifest file is missing: $MODEL_MANIFEST_FILE" >&2
  exit 1
fi

TMP_FAILURES="$(mktemp)"
trap 'rm -f "$TMP_FAILURES"' EXIT

set +e
python3 - "$STORE_POLICY_FILE" "$PRIVACY_NOTICE_FILE" "$MANIFEST_FILE" "$MODEL_CAPABILITY_PROFILES_FILE" "$MODEL_MANIFEST_FILE" > "$TMP_FAILURES" <<'PY'
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from datetime import date
from pathlib import Path
from urllib.parse import urlparse

record_path = Path(sys.argv[1])
notice_path = Path(sys.argv[2])
manifest_path = Path(sys.argv[3])
model_capability_profiles_path = Path(sys.argv[4])
model_manifest_path = Path(sys.argv[5])

try:
    record = json.loads(record_path.read_text())
except Exception:
    print("json-parse-error")
    sys.exit(1)
try:
    model_capability_profiles = json.loads(model_capability_profiles_path.read_text())
except Exception:
    print("model-capability-profiles-json-parse-error")
    sys.exit(1)
notice_text = notice_path.read_text()
notice_text_lower = notice_text.lower()
normalized_notice_text = re.sub(r"\s+", " ", notice_text_lower)

failures = []
if record.get("version") != 1:
    failures.append("version-invalid")
if record.get("status") != "approved":
    failures.append("status-not-approved")

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

def normalize_markdown_cell(value):
    value = value.strip()
    if value.startswith("`") and value.endswith("`") and len(value) >= 2:
        value = value[1:-1]
    return value.strip()

def parse_model_manifest(path):
    manifest = {}
    try:
        lines = path.read_text().splitlines()
    except OSError:
        return manifest
    for line in lines:
        stripped = line.strip()
        if not stripped.startswith("| `"):
            continue
        columns = [normalize_markdown_cell(column) for column in stripped.strip("|").split("|")]
        if len(columns) < 7:
            continue
        model_id = columns[0]
        if model_id:
            manifest[model_id] = {
                "revision": columns[3],
                "bytes": columns[4],
                "sha256": columns[5],
            }
    return manifest

def validate_store_review_evidence(path, expected_reviewer):
    props = properties_for(path)
    if props.get("status") != "approved":
        failures.append("review-evidence-status-not-approved")
    if props.get("approvalStatus") != "approved":
        failures.append("review-evidence-approval-status-not-approved")
    if props.get("target") != "store-policy-review-approved-evidence":
        failures.append("review-evidence-target-invalid")
    if props.get("privacyNoticePath") != str(notice_path):
        failures.append("review-evidence-privacy-notice-path-mismatch")
    if props.get("privacyNoticeSha256") != notice_sha:
        failures.append("review-evidence-privacy-notice-sha-mismatch")
    if not non_empty_string(props.get("scope")):
        failures.append("review-evidence-scope-missing")
    if props.get("requiredDecision") != "approved":
        failures.append("review-evidence-required-decision-invalid")
    evidence_reviewer = props.get("reviewer", "")
    if non_empty_string(evidence_reviewer) and evidence_reviewer != expected_reviewer:
        failures.append("review-evidence-reviewer-mismatch")

notice_sha = hashlib.sha256(notice_path.read_bytes()).hexdigest()
if record.get("privacyNoticePath") != str(notice_path):
    failures.append("privacy-notice-path-mismatch")
if record.get("privacyNoticeSha256") != notice_sha:
    failures.append("privacy-notice-sha-mismatch")

listing = record.get("appListing")
if not isinstance(listing, dict):
    failures.append("app-listing-missing")
    listing = {}
required_listing_fields = ("appName", "shortDescription", "fullDescription", "category", "contactEmail", "privacyPolicyUrl")
for field in required_listing_fields:
    if not listing.get(field):
        failures.append(f"{field}-missing")
if listing.get("contactEmail") and not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", listing["contactEmail"]):
    failures.append("contact-email-invalid")
elif listing.get("contactEmail"):
    email_domain = listing["contactEmail"].rsplit("@", 1)[-1].lower()
    if (
        email_domain in {"example.com", "example.org", "example.net", "localhost"} or
        email_domain.endswith(".example") or
        email_domain.endswith(".invalid")
    ):
        failures.append("contact-email-placeholder")
privacy_url = listing.get("privacyPolicyUrl", "")
if privacy_url and not privacy_url.startswith("https://"):
    failures.append("privacy-policy-url-invalid")
elif privacy_url:
    privacy_host = urlparse(privacy_url).hostname or ""
    privacy_host = privacy_host.lower()
    if (
        privacy_host in {"example.com", "example.org", "example.net", "localhost"} or
        privacy_host.endswith(".example") or
        privacy_host.endswith(".invalid")
    ):
        failures.append("privacy-policy-url-placeholder")
if isinstance(listing.get("shortDescription"), str) and len(listing["shortDescription"]) > 80:
    failures.append("short-description-too-long")
if isinstance(listing.get("fullDescription"), str) and len(listing["fullDescription"].strip()) < 120:
    failures.append("full-description-too-short")
listing_text = " ".join(
    value for value in (
        listing.get("shortDescription", ""),
        listing.get("fullDescription", ""),
    ) if isinstance(value, str)
).lower()
if "privacy-first" not in listing_text and "privacy first" not in listing_text:
    failures.append("app-listing-privacy-first-missing")
if "local" not in listing_text and "on-device" not in listing_text:
    failures.append("app-listing-local-use-missing")
if "remote multimodal" not in listing_text and not ("remote" in listing_text and "multimodal" in listing_text):
    failures.append("app-listing-remote-multimodal-missing")
if not (
    ("device action" in listing_text or "actions" in listing_text) and
    ("confirmation" in listing_text or "confirmed" in listing_text)
):
    failures.append("app-listing-confirmed-actions-missing")

data_safety = record.get("dataSafety")
if not isinstance(data_safety, dict):
    failures.append("data-safety-missing")
    data_safety = {}
required_true_data_safety = (
    "userDataCollected",
    "userDataShared",
    "encryptedInTransit",
    "userDeletable",
    "optionalRemoteModelEndpoints",
    "noFirstPartyAnalyticsUpload",
    "localStorageDisclosed",
    "remoteModelCallsDisclosed",
    "modelDownloadsDisclosed",
    "androidPermissionsDisclosed",
)
for field in required_true_data_safety:
    if data_safety.get(field) is not True:
        failures.append(f"{field}-not-true")
recipients = data_safety.get("externalRecipients")
if not isinstance(recipients, list) or len([item for item in recipients if isinstance(item, str) and item.strip()]) < 3:
    failures.append("external-recipients-incomplete")

def notice_contains_all(required_phrases):
    return all(phrase.lower() in normalized_notice_text for phrase in required_phrases)

privacy_notice_requirements = {
    "userDataCollected": (
        "local android app storage",
        "user-entered chat text",
    ),
    "userDataShared": (
        "remote model mode sends requests",
        "destination app or android system component",
    ),
    "encryptedInTransit": (
        "remote transport requires https",
    ),
    "userDeletable": (
        "delete chat sessions",
        "forgetting individual records",
        "clearing explicit memory records",
    ),
    "optionalRemoteModelEndpoints": (
        "user-configured openai-compatible chat endpoint",
    ),
    "noFirstPartyAnalyticsUpload": (
        "does not contain a first-party analytics upload path",
    ),
    "localStorageDisclosed": (
        "local android app storage",
    ),
    "remoteModelCallsDisclosed": (
        "remote model mode sends requests",
    ),
    "modelDownloadsDisclosed": (
        "model downloads",
        "network operators and model hosts",
    ),
    "androidPermissionsDisclosed": (
        "android runtime permissions",
        "system speech recognition",
        "usage access",
        "accessibility",
        "mediaprojection",
    ),
}
for field, required_phrases in privacy_notice_requirements.items():
    if data_safety.get(field) is True and not notice_contains_all(required_phrases):
        failures.append(f"{field}-privacy-notice-mismatch")

recipient_notice_requirements = {
    "User-configured remote model endpoints": ("configured endpoint",),
    "Recommended and custom model download hosts": ("model hosts",),
    "Android system or destination apps opened by confirmed external intents": (
        "destination app or android system component",
    ),
}
if isinstance(recipients, list):
    for recipient, required_phrases in recipient_notice_requirements.items():
        if recipient in recipients and not notice_contains_all(required_phrases):
            failures.append(f"{recipient.lower().replace(' ', '-')}-privacy-notice-mismatch")

model_downloads = record.get("modelDownloads")
if not isinstance(model_downloads, dict):
    failures.append("model-downloads-missing")
    model_downloads = {}
if model_downloads.get("describedAsLargeOptionalAssets") is not True:
    failures.append("model-downloads-not-described-large-optional")
if model_downloads.get("declaresNotBundledInApk") is not True:
    failures.append("model-downloads-not-declared-unbundled")
if model_capability_profiles.get("version") != 1:
    failures.append("model-capability-profiles-version-invalid")
profiles = model_capability_profiles.get("profiles")
if not isinstance(profiles, list):
    failures.append("model-capability-profiles-list-missing")
    profiles = []
profiles_by_id = {
    profile.get("id"): profile
    for profile in profiles
    if isinstance(profile, dict) and non_empty_string(profile.get("id"))
}
manifest_models = parse_model_manifest(model_manifest_path)
for profile in profiles:
    if not isinstance(profile, dict):
        continue
    profile_id = profile.get("id")
    if not non_empty_string(profile_id):
        continue
    if not all(key in profile for key in ("byteSize", "sha256Hex", "sourceRevision")):
        continue
    manifest_model = manifest_models.get(profile_id)
    if manifest_model is None:
        failures.append(f"model-manifest-profile-missing:{profile_id}")
        continue
    if str(profile.get("byteSize")) != manifest_model.get("bytes"):
        failures.append(f"model-manifest-profile-bytes-mismatch:{profile_id}")
    if profile.get("sha256Hex") != manifest_model.get("sha256"):
        failures.append(f"model-manifest-profile-sha-mismatch:{profile_id}")
    if profile.get("sourceRevision") != manifest_model.get("revision"):
        failures.append(f"model-manifest-profile-revision-mismatch:{profile_id}")

primary_chat_model_id = model_downloads.get("primaryChatModelProfileId")
if not non_empty_string(primary_chat_model_id):
    failures.append("model-downloads-primary-chat-model-profile-id-missing")
    primary_chat_profile = None
else:
    primary_chat_profile = profiles_by_id.get(primary_chat_model_id)
    if not isinstance(primary_chat_profile, dict):
        failures.append("model-downloads-primary-chat-model-missing")
if isinstance(primary_chat_profile, dict):
    primary_capabilities = primary_chat_profile.get("capabilities")
    if not isinstance(primary_capabilities, dict):
        primary_capabilities = {}
    if (
        primary_chat_profile.get("backendKind") != "LocalLiteRt" or
        primary_chat_profile.get("capability") != "Chat" or
        primary_capabilities.get("chat") is not True
    ):
        failures.append("model-downloads-primary-chat-model-not-local-chat")
    if primary_chat_profile.get("experimental") is not False:
        failures.append("model-downloads-primary-chat-model-experimental")
    primary_profile_bytes = primary_chat_profile.get("byteSize")
    primary_record_bytes = model_downloads.get("primaryChatModelBytes")
    if not isinstance(primary_profile_bytes, int) or primary_profile_bytes <= 0:
        failures.append("model-capability-profiles-primary-chat-byte-size-invalid")
    if not isinstance(primary_record_bytes, int) or primary_record_bytes <= 0:
        failures.append("model-downloads-primary-chat-model-bytes-invalid")
    elif isinstance(primary_profile_bytes, int) and primary_record_bytes != primary_profile_bytes:
        failures.append("model-downloads-primary-chat-model-bytes-mismatch")
    primary_record_sha = model_downloads.get("primaryChatModelSha256Hex")
    if not non_empty_string(primary_record_sha):
        failures.append("model-downloads-primary-chat-model-sha-missing")
    elif primary_record_sha != primary_chat_profile.get("sha256Hex"):
        failures.append("model-downloads-primary-chat-model-sha-mismatch")
    primary_record_revision = model_downloads.get("primaryChatModelSourceRevision")
    if not non_empty_string(primary_record_revision):
        failures.append("model-downloads-primary-chat-model-revision-missing")
    elif primary_record_revision != primary_chat_profile.get("sourceRevision"):
        failures.append("model-downloads-primary-chat-model-revision-mismatch")
lightweight_threshold_bytes = 1_000_000_000
has_official_lightweight_chat = False
for profile in profiles:
    if not isinstance(profile, dict):
        continue
    capabilities = profile.get("capabilities")
    if not isinstance(capabilities, dict):
        capabilities = {}
    if (
        profile.get("backendKind") == "LocalLiteRt" and
        profile.get("capability") == "Chat" and
        profile.get("experimental") is False and
        capabilities.get("chat") is True and
        isinstance(profile.get("byteSize"), int) and
        profile.get("byteSize") <= lightweight_threshold_bytes
    ):
        has_official_lightweight_chat = True
        break
if model_downloads.get("officialLightweightChatAlternative") is not has_official_lightweight_chat:
    failures.append("model-downloads-official-lightweight-chat-alternative-mismatch")
remote_templates = model_capability_profiles.get("remoteOpenAiCompatibleTemplates")
if not isinstance(remote_templates, list):
    remote_templates = []
has_confirmed_remote_chat_template = any(
    isinstance(template, dict) and
    template.get("backendKind") == "RemoteOpenAiCompatible" and
    template.get("capability") == "Chat" and
    template.get("remoteEligible") is True and
    template.get("requiresRemoteSendConfirmation") is True
    for template in remote_templates
)
if model_downloads.get("remoteAlternativeDisclosed") is not has_confirmed_remote_chat_template:
    failures.append("model-downloads-remote-alternative-disclosed-mismatch")

android_ns = "{http://schemas.android.com/apk/res/android}"
try:
    manifest_root = ET.parse(manifest_path).getroot()
except Exception:
    print("manifest-parse-error")
    sys.exit(1)
manifest_permissions = sorted(
    permission.get(android_ns + "name", "")
    for permission in manifest_root.findall("uses-permission")
    if permission.get(android_ns + "name", "")
)
record_permissions = record.get("permissions")
if not isinstance(record_permissions, list) or not record_permissions:
    failures.append("permissions-missing")
    record_permissions = []
seen_permissions = {}
for index, entry in enumerate(record_permissions):
    if not isinstance(entry, dict):
        failures.append(f"permission-{index}-invalid")
        continue
    name = entry.get("name", "")
    purpose = entry.get("purpose", "")
    if not name:
        failures.append(f"permission-{index}-name-missing")
    elif name in seen_permissions:
        failures.append(f"{name}-duplicate")
    seen_permissions[name] = True
    if not isinstance(purpose, str) or len(purpose.strip()) < 20:
        failures.append(f"{name or index}-purpose-missing")
record_permission_names = sorted(seen_permissions)
if manifest_permissions != record_permission_names:
    failures.append("manifest-permissions-mismatch")

special_access = record.get("specialAccessDisclosures")
if not isinstance(special_access, list):
    failures.append("special-access-disclosures-missing")
    special_access = []
required_special_access = {"UsageAccess", "AccessibilityService", "MediaProjection"}
seen_special_access = set()
for index, entry in enumerate(special_access):
    if not isinstance(entry, dict):
        failures.append(f"special-access-{index}-invalid")
        continue
    name = entry.get("name", "")
    purpose = entry.get("purpose", "")
    if not name:
        failures.append(f"special-access-{index}-name-missing")
    seen_special_access.add(name)
    if not isinstance(purpose, str) or len(purpose.strip()) < 20:
        failures.append(f"{name or index}-special-access-purpose-missing")
for missing in sorted(required_special_access - seen_special_access):
    failures.append(f"{missing}-special-access-missing")

review = record.get("review")
if not isinstance(review, dict):
    failures.append("review-missing")
    review = {}
if not review.get("reviewer"):
    failures.append("reviewer-missing")
review_evidence_path = review.get("evidencePath", "")
if not non_empty_string(review_evidence_path):
    failures.append("review-evidence-path-missing")
elif not Path(review_evidence_path).is_file():
    failures.append("review-evidence-file-missing")
else:
    validate_file_sha(
        "review-evidence",
        review_evidence_path,
        review.get("evidenceSha256", ""),
    )
    validate_store_review_evidence(review_evidence_path, review.get("reviewer", ""))
review_date = review.get("reviewDate", "")
date_pattern = re.compile(r"^\d{4}-\d{2}-\d{2}$")
if not review_date:
    failures.append("review-date-missing")
elif not date_pattern.match(review_date):
    failures.append("review-date-invalid")
else:
    try:
        parsed_date = date.fromisoformat(review_date)
    except ValueError:
        failures.append("review-date-invalid")
    else:
        if parsed_date > date.today():
            failures.append("review-date-in-future")

if failures:
    print(",".join(failures))
    sys.exit(1)

print("approved")
PY
status=$?
set -e

if [[ "$status" -ne 0 ]]; then
  reason="$(cat "$TMP_FAILURES")"
  write_report failed "${reason:-incomplete-store-policy-record}"
  echo "Store policy record is incomplete." >&2
  exit 1
fi

write_report passed approved
echo "Store policy record verification passed."
