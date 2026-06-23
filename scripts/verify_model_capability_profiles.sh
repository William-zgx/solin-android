#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PROFILE_FILE="${MODEL_CAPABILITY_PROFILES_FILE:-docs/model_capability_profiles.json}"
REPORT_FILE=""
EVIDENCE_OWNER="${EVIDENCE_OWNER:-${OWNER:-model-capability}}"
ORIGINAL_ARGS=("$@")

command_line() {
  local quoted=()
  local arg
  quoted+=("$(printf '%q' "scripts/verify_model_capability_profiles.sh")")
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
    missing-profile-file|invalid-json|root-*)
      printf 'model-capability-profiles-file'
      ;;
    profile-*|customLocalTemplates-*|remoteOpenAiCompatibleTemplates-*)
      printf 'model-capability-profile-entry'
      ;;
    *)
      printf 'model-capability-profiles'
      ;;
  esac
}

while [[ "$#" -gt 0 ]]; do
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
  local reason="${2:-}"
  local metrics_file="${3:-}"
  local failed_target=""
  if [[ "$status" != "passed" ]]; then
    failed_target="$(failed_target_for_reason "$reason")"
  fi
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'artifactSchema=ModelCapabilityProfilesVerification/v1\n'
      printf 'status=%s\n' "$status"
      printf 'target=model-capability-profiles\n'
      printf 'owner=%s\n' "$EVIDENCE_OWNER"
      printf 'recordedAt=%s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
      printf 'command=%s\n' "$(command_line)"
      printf 'failedTarget=%s\n' "$failed_target"
      printf 'reason=%s\n' "$reason"
      printf 'reproduciblePath=%s\n' "$REPORT_FILE"
      printf 'modelCapabilityProfilesFile=%s\n' "$PROFILE_FILE"
      printf 'modelCapabilityProfilesSha256=%s\n' "$(sha256_or_empty "$PROFILE_FILE")"
      printf 'profileFile=%s\n' "$PROFILE_FILE"
      printf 'profileSha256=%s\n' "$(sha256_or_empty "$PROFILE_FILE")"
      printf 'profileVersion=%s\n' "$(report_value "$metrics_file" profileVersion)"
      printf 'contractTest=%s\n' "$(report_value "$metrics_file" contractTest)"
      printf 'privacyBoundaryContainsLocalOnly=%s\n' "$(report_value "$metrics_file" privacyBoundaryContainsLocalOnly)"
      printf 'profileCount=%s\n' "$(report_value "$metrics_file" profileCount)"
      printf 'customLocalTemplateCount=%s\n' "$(report_value "$metrics_file" customLocalTemplateCount)"
      printf 'remoteTemplateCount=%s\n' "$(report_value "$metrics_file" remoteTemplateCount)"
      printf 'localOnlyProfileCount=%s\n' "$(report_value "$metrics_file" localOnlyProfileCount)"
      printf 'remoteEligibleProfileCount=%s\n' "$(report_value "$metrics_file" remoteEligibleProfileCount)"
      printf 'visionProfileCount=%s\n' "$(report_value "$metrics_file" visionProfileCount)"
      printf 'memoryEmbeddingProfileCount=%s\n' "$(report_value "$metrics_file" memoryEmbeddingProfileCount)"
      printf 'mobileActionProfileCount=%s\n' "$(report_value "$metrics_file" mobileActionProfileCount)"
      printf 'stableLocalChatProfileCount=%s\n' "$(report_value "$metrics_file" stableLocalChatProfileCount)"
    } > "$REPORT_FILE"
  fi
}

if [[ ! -f "$PROFILE_FILE" ]]; then
  write_report failed missing-profile-file
  echo "Model capability profiles file is missing: $PROFILE_FILE" >&2
  exit 1
fi

validation_output="$(mktemp)"
trap 'rm -f "$validation_output"' EXIT

if ! python3 - "$PROFILE_FILE" > "$validation_output" <<'PY'
import json
import re
import sys
from pathlib import Path

profile_path = Path(sys.argv[1])

try:
    document = json.loads(profile_path.read_text(encoding="utf-8"))
except Exception:
    print("reason=invalid-json")
    sys.exit(1)

failures = []

allowed_backend_kinds = {"LocalLiteRt", "RemoteOpenAiCompatible"}
allowed_capabilities = {"Chat", "MemoryEmbedding", "MobileAction"}
allowed_modalities = {"Text", "Vision"}
allowed_features = {"TextGeneration", "VisionInput", "MemoryEmbedding", "MobileActionPlanning"}
allowed_local_backends = {"CPU", "GPU"}

def non_empty_string(value):
    return isinstance(value, str) and bool(value.strip())

def string_list(value):
    if not isinstance(value, list):
        return None
    result = []
    for item in value:
        if not non_empty_string(item):
            return None
        result.append(item)
    return result

def is_bool(value):
    return isinstance(value, bool)

def is_sha256(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None

def validate_profile(profile, section, seen_ids, metrics):
    if not isinstance(profile, dict):
        failures.append(f"{section}-entry-invalid")
        return

    profile_id = profile.get("id")
    if not non_empty_string(profile_id):
        failures.append(f"{section}-id-missing")
        profile_id = section
    elif profile_id in seen_ids:
        failures.append(f"profile-duplicate-id:{profile_id}")
    else:
        seen_ids.add(profile_id)

    if not non_empty_string(profile.get("displayName")):
        failures.append(f"profile-display-name-missing:{profile_id}")

    backend_kind = profile.get("backendKind")
    capability = profile.get("capability")
    if backend_kind not in allowed_backend_kinds:
        failures.append(f"profile-backend-kind-invalid:{profile_id}")
    if capability not in allowed_capabilities:
        failures.append(f"profile-capability-invalid:{profile_id}")

    capabilities = profile.get("capabilities")
    if not isinstance(capabilities, dict):
        failures.append(f"profile-capabilities-missing:{profile_id}")
        capabilities = {}
    for flag in ("chat", "vision", "memoryEmbedding", "mobileAction"):
        if not is_bool(capabilities.get(flag)):
            failures.append(f"profile-capability-flag-invalid:{profile_id}:{flag}")

    modalities = string_list(profile.get("inputModalities"))
    if modalities is None:
        failures.append(f"profile-input-modalities-invalid:{profile_id}")
        modalities = []
    if set(modalities) - allowed_modalities:
        failures.append(f"profile-input-modality-unknown:{profile_id}")
    if "Text" not in modalities:
        failures.append(f"profile-text-input-missing:{profile_id}")

    features = string_list(profile.get("features"))
    if features is None:
        failures.append(f"profile-features-invalid:{profile_id}")
        features = []
    if set(features) - allowed_features:
        failures.append(f"profile-feature-unknown:{profile_id}")

    preferred_local_backends = string_list(profile.get("preferredLocalBackends"))
    if preferred_local_backends is None:
        failures.append(f"profile-preferred-local-backends-invalid:{profile_id}")
        preferred_local_backends = []
    if set(preferred_local_backends) - allowed_local_backends:
        failures.append(f"profile-preferred-local-backend-unknown:{profile_id}")

    remote_eligible = profile.get("remoteEligible")
    requires_confirmation = profile.get("requiresRemoteSendConfirmation")
    if not is_bool(remote_eligible):
        failures.append(f"profile-remote-eligible-invalid:{profile_id}")
        remote_eligible = False
    if not is_bool(requires_confirmation):
        failures.append(f"profile-remote-confirmation-invalid:{profile_id}")
        requires_confirmation = False

    supports_vision = "Vision" in modalities and "VisionInput" in features
    expected_flags = {
        "chat": capability == "Chat",
        "vision": supports_vision,
        "memoryEmbedding": capability == "MemoryEmbedding" and "MemoryEmbedding" in features,
        "mobileAction": capability == "MobileAction" and "MobileActionPlanning" in features,
    }
    for flag, expected in expected_flags.items():
        if capabilities.get(flag) is not expected:
            failures.append(f"profile-{flag}-flag-mismatch:{profile_id}")

    if "Vision" in modalities and capability != "Chat":
        failures.append(f"profile-vision-non-chat:{profile_id}")
    if "VisionInput" in features and "Vision" not in modalities:
        failures.append(f"profile-vision-feature-without-modality:{profile_id}")
    if "VisionInput" in features and capability != "Chat":
        failures.append(f"profile-vision-feature-non-chat:{profile_id}")
    if "TextGeneration" in features and capability != "Chat":
        failures.append(f"profile-text-generation-non-chat:{profile_id}")
    if "MemoryEmbedding" in features and capability != "MemoryEmbedding":
        failures.append(f"profile-memory-feature-mismatch:{profile_id}")
    if "MobileActionPlanning" in features and capability != "MobileAction":
        failures.append(f"profile-mobile-action-feature-mismatch:{profile_id}")

    context_window = profile.get("contextWindowTokens")
    if context_window is not None:
        if not isinstance(context_window, int) or context_window <= 0:
            failures.append(f"profile-context-window-invalid:{profile_id}")
        if capability != "Chat":
            failures.append(f"profile-context-window-non-chat:{profile_id}")

    if backend_kind == "RemoteOpenAiCompatible":
        if capability != "Chat":
            failures.append(f"profile-remote-non-chat:{profile_id}")
        if remote_eligible is not True:
            failures.append(f"profile-remote-eligible-mismatch:{profile_id}")
        if requires_confirmation is not True:
            failures.append(f"profile-remote-confirmation-required:{profile_id}")
        if preferred_local_backends:
            failures.append(f"profile-remote-local-backend-present:{profile_id}")
        if "MemoryEmbedding" in features or "MobileActionPlanning" in features:
            failures.append(f"profile-remote-local-only-feature:{profile_id}")
    else:
        if remote_eligible is not False:
            failures.append(f"profile-local-remote-eligible:{profile_id}")
        if requires_confirmation is not False:
            failures.append(f"profile-local-remote-confirmation:{profile_id}")
        if preferred_local_backends and backend_kind != "LocalLiteRt":
            failures.append(f"profile-local-backend-non-local:{profile_id}")

    if capability in {"MemoryEmbedding", "MobileAction"}:
        if backend_kind != "LocalLiteRt":
            failures.append(f"profile-local-only-backend-mismatch:{profile_id}")
        if remote_eligible or requires_confirmation:
            failures.append(f"profile-local-only-remote-boundary:{profile_id}")

    if section == "profiles":
        byte_size = profile.get("byteSize")
        if not isinstance(byte_size, int) or byte_size <= 0:
            failures.append(f"profile-byte-size-invalid:{profile_id}")
        if not is_sha256(profile.get("sha256Hex")):
            failures.append(f"profile-sha256-invalid:{profile_id}")
        if not non_empty_string(profile.get("sourceRevision")):
            failures.append(f"profile-source-revision-missing:{profile_id}")

    if section == "customLocalTemplates":
        if backend_kind != "LocalLiteRt" or capability != "Chat":
            failures.append(f"profile-custom-local-shape-invalid:{profile_id}")
        if "Vision" in modalities or "VisionInput" in features:
            failures.append(f"profile-custom-local-vision-unproven:{profile_id}")
        if preferred_local_backends:
            failures.append(f"profile-custom-local-backends-unproven:{profile_id}")

    metrics["remoteEligibleProfileCount"] += 1 if remote_eligible else 0
    metrics["localOnlyProfileCount"] += 1 if not remote_eligible else 0
    metrics["visionProfileCount"] += 1 if supports_vision else 0
    metrics["memoryEmbeddingProfileCount"] += 1 if capability == "MemoryEmbedding" else 0
    metrics["mobileActionProfileCount"] += 1 if capability == "MobileAction" else 0
    metrics["stableLocalChatProfileCount"] += (
        1
        if (
            section == "profiles" and
            backend_kind == "LocalLiteRt" and
            capability == "Chat" and
            profile.get("experimental", False) is False
        )
        else 0
    )

if document.get("version") != 1:
    failures.append("root-version-invalid")
source = document.get("source")
contract_test = document.get("contractTest")
if not non_empty_string(source):
    failures.append("root-source-missing")
if contract_test != "ModelCapabilityProfilesDocumentationTest":
    failures.append("root-contract-test-missing")
privacy_boundary = document.get("privacyBoundary")
privacy_boundary_contains_local_only = non_empty_string(privacy_boundary) and "LocalOnly" in privacy_boundary
if not privacy_boundary_contains_local_only:
    failures.append("root-privacy-boundary-missing-localonly")

profiles = document.get("profiles")
custom_templates = document.get("customLocalTemplates")
remote_templates = document.get("remoteOpenAiCompatibleTemplates")
if not isinstance(profiles, list):
    failures.append("root-profiles-list-missing")
    profiles = []
if not isinstance(custom_templates, list):
    failures.append("root-custom-local-templates-list-missing")
    custom_templates = []
if not isinstance(remote_templates, list):
    failures.append("root-remote-templates-list-missing")
    remote_templates = []

metrics = {
    "profileVersion": document.get("version", ""),
    "contractTest": contract_test if isinstance(contract_test, str) else "",
    "privacyBoundaryContainsLocalOnly": str(privacy_boundary_contains_local_only).lower(),
    "profileCount": len(profiles),
    "customLocalTemplateCount": len(custom_templates),
    "remoteTemplateCount": len(remote_templates),
    "localOnlyProfileCount": 0,
    "remoteEligibleProfileCount": 0,
    "visionProfileCount": 0,
    "memoryEmbeddingProfileCount": 0,
    "mobileActionProfileCount": 0,
    "stableLocalChatProfileCount": 0,
}

seen_ids = set()
for section, items in (
    ("profiles", profiles),
    ("customLocalTemplates", custom_templates),
    ("remoteOpenAiCompatibleTemplates", remote_templates),
):
    for item in items:
        validate_profile(item, section, seen_ids, metrics)

if metrics["profileCount"] == 0:
    failures.append("profile-count-zero")
if metrics["remoteTemplateCount"] == 0:
    failures.append("remote-template-count-zero")
if metrics["memoryEmbeddingProfileCount"] == 0:
    failures.append("memory-embedding-profile-missing")
if metrics["mobileActionProfileCount"] == 0:
    failures.append("mobile-action-profile-missing")
if metrics["stableLocalChatProfileCount"] == 0:
    failures.append("stable-local-chat-profile-missing")

for key, value in metrics.items():
    print(f"{key}={value}")

if failures:
    print(f"reason={','.join(failures)}")
    sys.exit(1)

print("reason=")
PY
then
  reason="$(report_value "$validation_output" reason)"
  write_report failed "${reason:-validation-failed}" "$validation_output"
  echo "Model capability profiles failed validation: ${reason:-validation-failed}" >&2
  exit 1
fi

write_report passed "" "$validation_output"
echo "Model capability profiles verification passed."
