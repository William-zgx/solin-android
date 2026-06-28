#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

MODEL_CAPABILITY_PROFILES_FILE="${MODEL_CAPABILITY_PROFILES_FILE:-docs/model_capability_profiles.json}"
CAPABILITY_MATRIX_FILE="${CAPABILITY_MATRIX_FILE:-docs/capability_matrix.json}"
PHONE_ACCEPTANCE_FILE="${PHONE_ACCEPTANCE_FILE:-docs/phone_acceptance.md}"
MODEL_PROFILE_REPORT_FILE="${MODEL_PROFILE_REPORT_FILE:-build/verification/model-memory-multimodal/model-capability-profiles.properties}"
CAPABILITY_MATRIX_REPORT_FILE="${CAPABILITY_MATRIX_REPORT_FILE:-build/verification/model-memory-multimodal/capability-matrix.properties}"
REPORT_FILE=""
EVIDENCE_OWNER="${EVIDENCE_OWNER:-${OWNER:-model-capability}}"
RUN_TARGETED_JVM="${RUN_TARGETED_JVM:-0}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
ORIGINAL_ARGS=("$@")

JVM_TEST_TARGETS=(
  "com.bytedance.zgx.solin.docs.ModelCapabilityProfilesDocumentationTest"
  "com.bytedance.zgx.solin.docs.CapabilityMatrixDocumentationTest"
  "com.bytedance.zgx.solin.tool.ToolRegistryTest"
  "com.bytedance.zgx.solin.tool.RoutingAndValidatingToolExecutorTest"
  "com.bytedance.zgx.solin.tool.DeviceContextToolExecutorTest"
)

command_line() {
  local quoted=()
  local arg
  quoted+=("$(printf '%q' "scripts/verify_model_memory_multimodal_local_gates.sh")")
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

write_report() {
  local status="$1"
  local reason="${2:-}"
  local metrics_file="${3:-}"
  local failed_target=""
  if [[ "$status" != "passed" ]]; then
    failed_target="${reason%%:*}"
    failed_target="${failed_target%%,*}"
  fi
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'artifactSchema=ModelMemoryMultimodalLocalGates/v1\n'
      printf 'status=%s\n' "$status"
      printf 'target=model-memory-multimodal-local-gates\n'
      printf 'owner=%s\n' "$EVIDENCE_OWNER"
      printf 'recordedAt=%s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
      printf 'command=%s\n' "$(command_line)"
      printf 'failedTarget=%s\n' "$failed_target"
      printf 'reason=%s\n' "$reason"
      printf 'reproduciblePath=%s\n' "$REPORT_FILE"
      printf 'modelCapabilityProfilesFile=%s\n' "$MODEL_CAPABILITY_PROFILES_FILE"
      printf 'modelCapabilityProfilesSha256=%s\n' "$(sha256_or_empty "$MODEL_CAPABILITY_PROFILES_FILE")"
      printf 'modelCapabilityProfilesReportFile=%s\n' "$MODEL_PROFILE_REPORT_FILE"
      printf 'modelCapabilityProfilesReportSha256=%s\n' "$(sha256_or_empty "$MODEL_PROFILE_REPORT_FILE")"
      printf 'modelCapabilityProfilesReportStatus=%s\n' "$(report_value "$MODEL_PROFILE_REPORT_FILE" status)"
      printf 'capabilityMatrixFile=%s\n' "$CAPABILITY_MATRIX_FILE"
      printf 'capabilityMatrixSha256=%s\n' "$(sha256_or_empty "$CAPABILITY_MATRIX_FILE")"
      printf 'capabilityMatrixReportFile=%s\n' "$CAPABILITY_MATRIX_REPORT_FILE"
      printf 'capabilityMatrixReportSha256=%s\n' "$(sha256_or_empty "$CAPABILITY_MATRIX_REPORT_FILE")"
      printf 'capabilityMatrixReportStatus=%s\n' "$(report_value "$CAPABILITY_MATRIX_REPORT_FILE" status)"
      printf 'phoneAcceptanceFile=%s\n' "$PHONE_ACCEPTANCE_FILE"
      printf 'phoneAcceptanceSha256=%s\n' "$(sha256_or_empty "$PHONE_ACCEPTANCE_FILE")"
      printf 'localVisionProfileCount=%s\n' "$(report_value "$metrics_file" localVisionProfileCount)"
      printf 'remoteVisionTemplateConfirmationCount=%s\n' "$(report_value "$metrics_file" remoteVisionTemplateConfirmationCount)"
      printf 'memoryEmbeddingLocalOnlyCount=%s\n' "$(report_value "$metrics_file" memoryEmbeddingLocalOnlyCount)"
      printf 'mobileActionLocalOnlyCount=%s\n' "$(report_value "$metrics_file" mobileActionLocalOnlyCount)"
      printf 'ocrLocalOnlyDisclosureCount=%s\n' "$(report_value "$metrics_file" ocrLocalOnlyDisclosureCount)"
      printf 'remoteSendDisclosureCount=%s\n' "$(report_value "$metrics_file" remoteSendDisclosureCount)"
      printf 'memoryPrivacyDocEvidence=%s\n' "$(report_value "$metrics_file" memoryPrivacyDocEvidence)"
      printf 'ocrPrivacyDocEvidence=%s\n' "$(report_value "$metrics_file" ocrPrivacyDocEvidence)"
      printf 'runtimeUiEvidenceMode=static-contract\n'
      printf 'targetedJvmTestsRun=%s\n' "$RUN_TARGETED_JVM"
      printf 'targetedJvmTests=%s\n' "$(IFS=,; printf '%s' "${JVM_TEST_TARGETS[*]}")"
      if [[ "$RUN_TARGETED_JVM" != "1" ]]; then
        printf 'targetedJvmDeferredReason=targeted-jvm-not-run-in-this-phase\n'
      else
        printf 'targetedJvmDeferredReason=\n'
      fi
    } > "$REPORT_FILE"
  fi
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

if [[ -z "$REPORT_FILE" ]]; then
  REPORT_FILE="build/verification/model-memory-multimodal/model-memory-multimodal-local-gates.properties"
fi

mkdir -p "$(dirname "$MODEL_PROFILE_REPORT_FILE")" "$(dirname "$CAPABILITY_MATRIX_REPORT_FILE")"

if ! scripts/verify_model_capability_profiles.sh --report "$MODEL_PROFILE_REPORT_FILE" >/dev/null; then
  reason="$(report_value "$MODEL_PROFILE_REPORT_FILE" reason)"
  write_report failed "model-capability-profiles:${reason:-failed}"
  echo "Model capability profiles verifier failed." >&2
  exit 1
fi

if ! scripts/verify_capability_matrix.sh --report "$CAPABILITY_MATRIX_REPORT_FILE" >/dev/null; then
  reason="$(report_value "$CAPABILITY_MATRIX_REPORT_FILE" reason)"
  write_report failed "capability-matrix:${reason:-failed}"
  echo "Capability matrix verifier failed." >&2
  exit 1
fi

metrics_file="$(mktemp)"
trap 'rm -f "$metrics_file"' EXIT

if ! python3 - "$MODEL_CAPABILITY_PROFILES_FILE" "$CAPABILITY_MATRIX_FILE" "$PHONE_ACCEPTANCE_FILE" > "$metrics_file" <<'PY'
import json
import pathlib
import sys

profiles_path = pathlib.Path(sys.argv[1])
matrix_path = pathlib.Path(sys.argv[2])
phone_acceptance_path = pathlib.Path(sys.argv[3])

profiles = json.loads(profiles_path.read_text(encoding="utf-8"))
matrix = json.loads(matrix_path.read_text(encoding="utf-8"))
phone_acceptance = phone_acceptance_path.read_text(encoding="utf-8")

all_profiles = (
    profiles.get("profiles", []) +
    profiles.get("customLocalTemplates", []) +
    profiles.get("remoteOpenAiCompatibleTemplates", [])
)

def supports(profile, key):
    capabilities = profile.get("capabilities", {})
    return bool(capabilities.get(key))

local_vision = [
    p for p in all_profiles
    if p.get("backendKind") == "LocalLiteRt"
    and supports(p, "chat")
    and supports(p, "vision")
    and p.get("remoteEligible") is False
]
remote_vision_confirmed = [
    p for p in all_profiles
    if p.get("backendKind") == "RemoteOpenAiCompatible"
    and supports(p, "vision")
    and p.get("remoteEligible") is True
    and p.get("requiresRemoteSendConfirmation") is True
]
memory_local_only = [
    p for p in all_profiles
    if supports(p, "memoryEmbedding") and p.get("remoteEligible") is False
]
action_local_only = [
    p for p in all_profiles
    if supports(p, "mobileAction") and p.get("remoteEligible") is False
]

disclosure_ids = {
    item.get("capabilityId")
    for item in matrix.get("sensitiveCapabilityDisclosures", [])
    if isinstance(item, dict)
}
ocr_disclosure_count = sum(
    1 for item in ("media_and_recent_ocr", "media_projection_screenshot_ocr")
    if item in disclosure_ids
)
remote_send_disclosure_count = 1 if "remote_model_send" in disclosure_ids else 0

failures = []
if not local_vision:
    failures.append("local-vision-profile-missing")
if not remote_vision_confirmed:
    failures.append("remote-vision-confirmation-missing")
if not memory_local_only:
    failures.append("memory-embedding-local-only-missing")
if not action_local_only:
    failures.append("mobile-action-local-only-missing")
if ocr_disclosure_count != 2:
    failures.append("ocr-local-only-disclosures-missing")
if remote_send_disclosure_count != 1:
    failures.append("remote-send-disclosure-missing")
if "远程模型模式下，`记住：...`" not in phone_acceptance:
    failures.append("memory-remote-privacy-doc-missing")
if "OCR 摘录" not in phone_acceptance or "LocalOnly" not in phone_acceptance:
    failures.append("ocr-local-only-doc-missing")

print(f"localVisionProfileCount={len(local_vision)}")
print(f"remoteVisionTemplateConfirmationCount={len(remote_vision_confirmed)}")
print(f"memoryEmbeddingLocalOnlyCount={len(memory_local_only)}")
print(f"mobileActionLocalOnlyCount={len(action_local_only)}")
print(f"ocrLocalOnlyDisclosureCount={ocr_disclosure_count}")
print(f"remoteSendDisclosureCount={remote_send_disclosure_count}")
print("memoryPrivacyDocEvidence=phone_acceptance:remote-memory-local-only")
print("ocrPrivacyDocEvidence=phone_acceptance:ocr-local-only")

if failures:
    print("reason=" + ",".join(failures))
    sys.exit(1)
PY
then
  reason="$(report_value "$metrics_file" reason)"
  write_report failed "local-contract:${reason:-failed}" "$metrics_file"
  echo "Model/memory/multimodal local contract check failed." >&2
  exit 1
fi

if [[ "$RUN_TARGETED_JVM" == "1" ]]; then
  gradle_args=(:app:testDebugUnitTest)
  for test_target in "${JVM_TEST_TARGETS[@]}"; do
    gradle_args+=(--tests "$test_target")
  done
  if ! "$GRADLE_CMD" "${gradle_args[@]}"; then
    write_report failed "targeted-jvm-tests-failed" "$metrics_file"
    echo "Targeted JVM model/memory/multimodal tests failed." >&2
    exit 1
  fi
fi

write_report passed "" "$metrics_file"
echo "Model/memory/multimodal local gates passed: $REPORT_FILE"
