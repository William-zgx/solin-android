#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

CAPABILITY_MATRIX_FILE="${CAPABILITY_MATRIX_FILE:-docs/capability_matrix.json}"
REPORT_FILE=""
EVIDENCE_OWNER="${EVIDENCE_OWNER:-${OWNER:-trust-privacy}}"
ORIGINAL_ARGS=("$@")

command_line() {
  local quoted=()
  local arg
  quoted+=("$(printf '%q' "scripts/verify_capability_matrix.sh")")
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

report_value() {
  local file="$1"
  local key="$2"
  if [[ -f "$file" ]]; then
    awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; found = 1; exit} END {if (!found) exit 1}' "$file" || true
  fi
}

failed_target_for_reason() {
  local reason="$1"
  local first="${reason%%,*}"
  case "$first" in
    missing-capability-matrix-file|invalid-json|root-*)
      printf 'capability-matrix-file'
      ;;
    next-stage-*|required-boundary-*|sensitive-disclosure-*|product-capability-*|tool-capability-*|capability-*)
      printf 'capability-matrix-entry'
      ;;
    privacy-*|remote-eligible-*|local-evidence-*)
      printf 'capability-boundary'
      ;;
    *)
      printf 'capability-matrix'
      ;;
  esac
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --file|--capability-matrix)
      CAPABILITY_MATRIX_FILE="${2:?missing capability matrix path}"
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
  local reason="${2:-}"
  local metrics_file="${3:-}"
  local failed_target=""
  if [[ "$status" != "passed" ]]; then
    failed_target="$(failed_target_for_reason "$reason")"
  fi
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'artifactSchema=CapabilityMatrixVerification/v1\n'
      printf 'status=%s\n' "$status"
      printf 'target=capability-matrix\n'
      printf 'owner=%s\n' "$EVIDENCE_OWNER"
      printf 'recordedAt=%s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
      printf 'command=%s\n' "$(command_line)"
      printf 'failedTarget=%s\n' "$failed_target"
      printf 'reason=%s\n' "$reason"
      printf 'reproduciblePath=%s\n' "$REPORT_FILE"
      printf 'capabilityMatrixFile=%s\n' "$CAPABILITY_MATRIX_FILE"
      printf 'capabilityMatrixSha256=%s\n' "$(sha256_or_empty "$CAPABILITY_MATRIX_FILE")"
      printf 'matrixVersion=%s\n' "$(report_value "$metrics_file" matrixVersion)"
      printf 'scenarioCount=%s\n' "$(report_value "$metrics_file" scenarioCount)"
      printf 'productCapabilityCount=%s\n' "$(report_value "$metrics_file" productCapabilityCount)"
      printf 'toolCapabilityCount=%s\n' "$(report_value "$metrics_file" toolCapabilityCount)"
      printf 'sensitiveDisclosureCount=%s\n' "$(report_value "$metrics_file" sensitiveDisclosureCount)"
      printf 'requiredBehaviorBoundaryCount=%s\n' "$(report_value "$metrics_file" requiredBehaviorBoundaryCount)"
      printf 'remoteEligibleCapabilityCount=%s\n' "$(report_value "$metrics_file" remoteEligibleCapabilityCount)"
      printf 'localEvidenceRemoteEligibleCount=%s\n' "$(report_value "$metrics_file" localEvidenceRemoteEligibleCount)"
      printf 'publicEvidenceNoConfirmationCount=%s\n' "$(report_value "$metrics_file" publicEvidenceNoConfirmationCount)"
      printf 'requiredSensitiveDisclosureIds=%s\n' "$(report_value "$metrics_file" requiredSensitiveDisclosureIds)"
      printf 'missingSensitiveDisclosureIds=%s\n' "$(report_value "$metrics_file" missingSensitiveDisclosureIds)"
    } > "$REPORT_FILE"
  fi
}

if [[ ! -f "$CAPABILITY_MATRIX_FILE" ]]; then
  write_report failed missing-capability-matrix-file
  echo "Capability matrix file is missing: $CAPABILITY_MATRIX_FILE" >&2
  exit 1
fi

validation_output="$(mktemp)"
trap 'rm -f "$validation_output"' EXIT

if ! python3 - "$CAPABILITY_MATRIX_FILE" > "$validation_output" <<'PY'
import json
import re
import sys
from pathlib import Path

matrix_path = Path(sys.argv[1])

try:
    matrix = json.loads(matrix_path.read_text(encoding="utf-8"))
except Exception:
    print("reason=invalid-json")
    sys.exit(1)

failures = []
slug = re.compile(r"^[a-z0-9][a-z0-9_:-]*$")
required_disclosure_ids = [
    "remote_model_send",
    "voice_transcript_input",
    "share_and_file_picker_input",
    "confirmed_device_actions",
    "contacts_calendar_reads",
    "media_and_recent_ocr",
    "usage_stats_foreground_app",
    "accessibility_current_screen_text",
    "media_projection_screenshot_ocr",
]
allowed_privacy_levels = {
    "UserProvided",
    "PublicEvidence",
    "LocalEvidence",
    "ExternalAction",
    "BackgroundTask",
}
allowed_confirmations = {
    "NotRequired",
    "Required",
    "SecondConfirmation",
}


def is_non_empty_string(value):
    return isinstance(value, str) and bool(value.strip())


def require_list(name):
    value = matrix.get(name)
    if not isinstance(value, list):
        failures.append(f"root-list-missing:{name}")
        return []
    return value


if matrix.get("version") != 1:
    failures.append("root-version-invalid")
if not is_non_empty_string(matrix.get("productPositioning")):
    failures.append("root-product-positioning-missing")
if not is_non_empty_string(matrix.get("targetUserJob")):
    failures.append("root-target-user-job-missing")

scenarios = require_list("nextStageMvpScenarios")
scenario_ids = []
for index, scenario in enumerate(scenarios, start=1):
    if not isinstance(scenario, dict):
        failures.append(f"next-stage-scenario-invalid:{index}")
        continue
    capability_id = scenario.get("capabilityId")
    title = scenario.get("title")
    if not is_non_empty_string(capability_id) or not slug.match(capability_id):
        failures.append(f"next-stage-scenario-id-invalid:{index}")
    else:
        scenario_ids.append(capability_id)
    if not is_non_empty_string(title):
        failures.append(f"next-stage-scenario-title-missing:{capability_id or index}")
if not scenario_ids:
    failures.append("next-stage-scenarios-empty")
if len(set(scenario_ids)) != len(scenario_ids):
    failures.append("next-stage-scenarios-duplicate")

required_boundaries = require_list("requiredBehaviorEvalBoundaries")
boundary_ids = []
for index, boundary in enumerate(required_boundaries, start=1):
    if not is_non_empty_string(boundary) or not slug.match(boundary):
        failures.append(f"required-boundary-invalid:{index}")
    else:
        boundary_ids.append(boundary)
if not boundary_ids:
    failures.append("required-boundaries-empty")
if len(set(boundary_ids)) != len(boundary_ids):
    failures.append("required-boundaries-duplicate")

sensitive_disclosures = require_list("sensitiveCapabilityDisclosures")
disclosure_ids = []
for index, disclosure in enumerate(sensitive_disclosures, start=1):
    if not isinstance(disclosure, dict):
        failures.append(f"sensitive-disclosure-invalid:{index}")
        continue
    capability_id = disclosure.get("capabilityId")
    if not is_non_empty_string(capability_id) or not slug.match(capability_id):
        failures.append(f"sensitive-disclosure-id-invalid:{index}")
    else:
        disclosure_ids.append(capability_id)
    for field in (
        "displayName",
        "dataAccessed",
        "consentBoundary",
        "remoteBoundary",
        "revokeOrClearControl",
    ):
        if not is_non_empty_string(disclosure.get(field)):
            failures.append(f"sensitive-disclosure-field-missing:{capability_id or index}:{field}")
    tests = disclosure.get("requiredTests")
    if not isinstance(tests, list) or not tests or any(not is_non_empty_string(test) for test in tests):
        failures.append(f"sensitive-disclosure-required-tests-invalid:{capability_id or index}")
missing_disclosures = [
    capability_id for capability_id in required_disclosure_ids
    if capability_id not in set(disclosure_ids)
]
for capability_id in missing_disclosures:
    failures.append(f"sensitive-disclosure-required-missing:{capability_id}")
if len(set(disclosure_ids)) != len(disclosure_ids):
    failures.append("sensitive-disclosures-duplicate")

product_capabilities = require_list("productCapabilities")
tool_capabilities = require_list("toolCapabilities")
all_capability_ids = []
remote_eligible_count = 0
local_evidence_remote_count = 0
public_no_confirmation_count = 0


def validate_capability(kind, item, index):
    global remote_eligible_count, local_evidence_remote_count, public_no_confirmation_count
    if not isinstance(item, dict):
        failures.append(f"{kind}-capability-invalid:{index}")
        return
    capability_id = item.get("capabilityId")
    if not is_non_empty_string(capability_id) or not slug.match(capability_id):
        failures.append(f"{kind}-capability-id-invalid:{index}")
    else:
        all_capability_ids.append(capability_id)

    privacy_level = item.get("privacyLevel")
    confirmation = item.get("confirmationPolicy")
    remote_eligible = item.get("remoteEligible")
    required_tests = item.get("requiredTests")

    if privacy_level not in allowed_privacy_levels:
        failures.append(f"privacy-level-invalid:{capability_id or kind}:{privacy_level}")
    if confirmation not in allowed_confirmations:
        failures.append(f"confirmation-policy-invalid:{capability_id or kind}:{confirmation}")
    if not isinstance(remote_eligible, bool):
        failures.append(f"remote-eligible-not-boolean:{capability_id or kind}")
        remote_eligible = False
    if not isinstance(required_tests, list) or not required_tests or any(not is_non_empty_string(test) for test in required_tests):
        failures.append(f"{kind}-capability-required-tests-invalid:{capability_id or index}")

    if privacy_level == "LocalEvidence" and remote_eligible:
        failures.append(f"local-evidence-remote-eligible:{capability_id or kind}")
        local_evidence_remote_count += 1
    if remote_eligible:
        remote_eligible_count += 1
        if privacy_level not in {"PublicEvidence", "UserProvided"} and confirmation == "NotRequired":
            failures.append(f"remote-eligible-confirmation-missing:{capability_id or kind}")
    if privacy_level == "PublicEvidence" and confirmation == "NotRequired":
        public_no_confirmation_count += 1


for index, item in enumerate(product_capabilities, start=1):
    validate_capability("product", item, index)
for index, item in enumerate(tool_capabilities, start=1):
    validate_capability("tool", item, index)

if len(set(all_capability_ids)) != len(all_capability_ids):
    failures.append("capability-id-duplicate")
if not product_capabilities:
    failures.append("product-capabilities-empty")
if not tool_capabilities:
    failures.append("tool-capabilities-empty")

print(f"matrixVersion={matrix.get('version', '')}")
print(f"scenarioCount={len(scenario_ids)}")
print(f"productCapabilityCount={len(product_capabilities)}")
print(f"toolCapabilityCount={len(tool_capabilities)}")
print(f"sensitiveDisclosureCount={len(disclosure_ids)}")
print(f"requiredBehaviorBoundaryCount={len(boundary_ids)}")
print(f"remoteEligibleCapabilityCount={remote_eligible_count}")
print(f"localEvidenceRemoteEligibleCount={local_evidence_remote_count}")
print(f"publicEvidenceNoConfirmationCount={public_no_confirmation_count}")
print(f"requiredSensitiveDisclosureIds={','.join(required_disclosure_ids)}")
print(f"missingSensitiveDisclosureIds={','.join(missing_disclosures)}")

if failures:
    print("reason=" + ",".join(failures))
    sys.exit(1)
PY
then
  reason="$(report_value "$validation_output" reason)"
  reason="${reason:-capability-matrix-validation-failed}"
  write_report failed "$reason" "$validation_output"
  echo "Capability matrix verification failed: $reason" >&2
  exit 1
fi

write_report passed "" "$validation_output"
echo "Capability matrix verification passed: $CAPABILITY_MATRIX_FILE"
