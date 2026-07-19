#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

CAPABILITY_MATRIX_FILE="${CAPABILITY_MATRIX_FILE:-docs/capability_matrix.json}"
GRADLE_FILE="${ADAPTIVE_INFERENCE_GRADLE_FILE:-app/build.gradle.kts}"
SERVING_SOURCE_DIR="${ADAPTIVE_SERVING_SOURCE_DIR:-app/src/main/java/com/bytedance/zgx/solin}"
REPORT_FILE=""
EVIDENCE_OWNER="${EVIDENCE_OWNER:-${OWNER:-trust-privacy}}"
ORIGINAL_ARGS=("$@")
SOLIN_SCRIPT_COMMAND="scripts/verify_capability_matrix.sh"
source "$ROOT_DIR/scripts/lib/report_helpers.sh"

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
    adaptive-rollout-*|release-like-rollout-*)
      printf 'adaptive-inference-rollout'
      ;;
    serving-*)
      printf 'adaptive-serving-source'
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
    --gradle-file)
      GRADLE_FILE="${2:?missing Gradle file path}"
      shift 2
      ;;
    --serving-source-dir)
      SERVING_SOURCE_DIR="${2:?missing serving source directory}"
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
      printf 'adaptiveInferenceGradleFile=%s\n' "$GRADLE_FILE"
      printf 'adaptiveInferenceGradleSha256=%s\n' "$(sha256_or_empty "$GRADLE_FILE")"
      printf 'servingSourceDir=%s\n' "$SERVING_SOURCE_DIR"
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
      printf 'adaptiveInferenceRolloutDefaultStage=%s\n' "$(report_value "$metrics_file" adaptiveInferenceRolloutDefaultStage)"
      printf 'releaseLikeRolloutOff=%s\n' "$(report_value "$metrics_file" releaseLikeRolloutOff)"
      printf 'releaseLikeRolloutSelectable=%s\n' "$(report_value "$metrics_file" releaseLikeRolloutSelectable)"
      printf 'servingSourceContract=%s\n' "$(report_value "$metrics_file" servingSourceContract)"
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

if ! python3 - "$CAPABILITY_MATRIX_FILE" "$GRADLE_FILE" "$SERVING_SOURCE_DIR" > "$validation_output" <<'PY'
import json
import re
import sys
from pathlib import Path

matrix_path = Path(sys.argv[1])
gradle_path = Path(sys.argv[2])
serving_source_arg = sys.argv[3]

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

rollout_default_stage = ""
release_like_rollout_off = False
release_like_rollout_selectable = False
serving_source_contract = "not-run"


def block_spans(source, pattern):
    spans = []
    for match in re.finditer(pattern, source):
        brace = source.find("{", match.start(), match.end() + 1)
        if brace < 0:
            continue
        depth = 0
        for index in range(brace, len(source)):
            if source[index] == "{":
                depth += 1
            elif source[index] == "}":
                depth -= 1
                if depth == 0:
                    spans.append((match, brace, index + 1))
                    break
    return spans


if not gradle_path.is_file():
    failures.append("adaptive-rollout-gradle-file-missing")
else:
    gradle_source = gradle_path.read_text(encoding="utf-8")
    rollout_pattern = re.compile(
        r'buildConfigField\(\s*"String"\s*,\s*"ADAPTIVE_INFERENCE_ROLLOUT_STAGE"\s*,\s*"\\"([^"\\]+)\\""\s*\)'
    )
    rollout_call_pattern = re.compile(
        r'buildConfigField\(\s*"String"\s*,\s*"ADAPTIVE_INFERENCE_ROLLOUT_STAGE"\s*,[\s\S]*?\)'
    )
    rollout_matches = list(rollout_pattern.finditer(gradle_source))
    rollout_calls = list(rollout_call_pattern.finditer(gradle_source))
    allowed_rollout_stages = {"off", "shadow", "opt_in", "visible"}
    default_spans = block_spans(gradle_source, r"\bdefaultConfig\s*\{")
    debug_spans = block_spans(
        gradle_source,
        r'(?:\bdebug|getByName\(\s*"debug"\s*\))\s*\{',
    )
    release_spans = block_spans(gradle_source, r"\brelease\s*\{")
    created_spans = block_spans(gradle_source, r'\bcreate\(\s*"(?P<name>[^"]+)"\s*\)\s*\{')

    def within(index, spans):
        return next((span for span in spans if span[1] <= index < span[2]), None)

    def span_matches(span):
        return [match for match in rollout_matches if span[1] <= match.start() < span[2]]

    def inherits(body, build_type):
        return re.search(
            rf'initWith\(\s*(?:(?:buildTypes\.)?getByName\(\s*"{re.escape(build_type)}"\s*\)|{re.escape(build_type)})\s*\)',
            body,
        ) is not None

    for call in rollout_calls:
        if rollout_pattern.fullmatch(call.group(0)) is None:
            failures.append("adaptive-rollout-stage-unreadable")
    if gradle_source.count('"ADAPTIVE_INFERENCE_ROLLOUT_STAGE"') != len(rollout_calls):
        failures.append("adaptive-rollout-stage-unreadable")

    default_matches = [match for match in rollout_matches if within(match.start(), default_spans)]
    if len(default_matches) != 1:
        failures.append("adaptive-rollout-default-stage-missing")
    else:
        rollout_default_stage = default_matches[0].group(1)

    for match in rollout_matches:
        stage = match.group(1)
        if stage not in allowed_rollout_stages:
            failures.append(f"adaptive-rollout-stage-invalid:{stage}")

    release_like_spans = [("release", span) for span in release_spans]
    for span in created_spans:
        name = span[0].group("name")
        body = gradle_source[span[1]:span[2]]
        release_like = "release" in name.lower() or inherits(body, "release")
        if release_like:
            release_like_spans.append((name, span))

    debug_matches = [match.group(1) for span in debug_spans for match in span_matches(span)]
    release_matches = [match.group(1) for span in release_spans for match in span_matches(span)]

    def effective_stage(name, span):
        direct = [match.group(1) for match in span_matches(span)]
        if direct:
            return direct[-1]
        body = gradle_source[span[1]:span[2]]
        if name != "release" and inherits(body, "release") and release_matches:
            return release_matches[-1]
        if inherits(body, "debug") and debug_matches:
            return debug_matches[-1]
        return rollout_default_stage

    release_like_stages = [effective_stage(name, span) for name, span in release_like_spans]
    release_like_rollout_off = bool(release_like_stages) and all(
        stage == "off" for stage in release_like_stages
    )
    release_like_rollout_selectable = bool(release_like_stages) and all(
        stage in {"opt_in", "visible"} for stage in release_like_stages
    )
    for (name, _), stage in zip(release_like_spans, release_like_stages):
        if stage not in {"opt_in", "visible"}:
            failures.append(f"release-like-rollout-not-selectable:{name}:{stage}")

serving_source_dir = Path(serving_source_arg)
if not serving_source_dir.is_dir():
    failures.append("serving-source-dir-missing")
    serving_source_contract = "failed"
else:
    serving_names = {
        "ChatController.kt",
        "ChatGenerationSupport.kt",
        "ChatToolContinuationSupport.kt",
        "ToolExecutionController.kt",
        "PendingConfirmationSupport.kt",
        "ChatRemoteSendSupport.kt",
        "ModelRuntimeDispatcher.kt",
    }
    kotlin_files = sorted(serving_source_dir.rglob("*.kt"))
    selected_files = [path for path in kotlin_files if path.name in serving_names] or kotlin_files
    if not selected_files:
        failures.append("serving-source-files-missing")
        serving_source_contract = "failed"
    else:
        combined_source = "\n".join(path.read_text(encoding="utf-8") for path in selected_files)
        if not re.search(
            r"\b(?:binding|permit|invocation)(?:\.decision)?\.placement\b|\.placement\s*==\s*RunPlacement\.",
            combined_source,
        ):
            failures.append("serving-bound-placement-read-missing")

        serving_signal = re.compile(
            r"\b(?:remoteChatRuntime|localChatRuntime|remoteRuntime|modelRuntimeDispatcher|chatPlacementRuntime)\b"
            r"|\bRunDataDestination\.(?:Remote|Local)\b"
            r"|\.(?:sendWithTools|send|dispatch|stop)\s*\("
        )
        target_name = re.compile(
            r"^(?:useRemoteModel|actualTarget|receiptDestination|destination|runtimeTarget|selectedRuntime|modelRuntime|runtime)$"
        )
        assignment = re.compile(r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*([^\n;]+)")

        def condition_controls_serving(source, token):
            condition = rf"\b(?:if|when)\s*\([^)]*\b{re.escape(token)}\b[^)]*\)"
            for span in block_spans(source, condition + r"\s*\{"):
                if serving_signal.search(source[span[1]:span[2]]):
                    return True
            inline = re.compile(
                condition + rf"[ \t]*(?:\n[ \t]*)?(?:{serving_signal.pattern})"
            )
            return inline.search(source) is not None

        for path in selected_files:
            source = path.read_text(encoding="utf-8")
            if "inferenceMode" not in source:
                continue
            tainted = {"inferenceMode"}
            assignments = list(assignment.finditer(source))
            changed = True
            while changed:
                changed = False
                for match in assignments:
                    name, expression = match.groups()
                    if name in tainted:
                        continue
                    if any(re.search(rf"\b{re.escape(token)}\b", expression) for token in tainted):
                        tainted.add(name)
                        changed = True

            derived_target = False
            for match in assignments:
                name, expression = match.groups()
                depends_on_preference = any(
                    re.search(rf"\b{re.escape(token)}\b", expression)
                    for token in tainted
                )
                if depends_on_preference and (target_name.fullmatch(name) or serving_signal.search(expression)):
                    derived_target = True
                    break

            if not derived_target:
                for token in tainted:
                    if condition_controls_serving(source, token):
                        derived_target = True
                        break

            if derived_target:
                failures.append(f"serving-target-derived-from-inference-mode:{path.name}")

        serving_source_contract = "failed" if any(
            reason.startswith("serving-") for reason in failures
        ) else "passed"


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
print(f"adaptiveInferenceRolloutDefaultStage={rollout_default_stage}")
print(f"releaseLikeRolloutOff={str(release_like_rollout_off).lower()}")
print(f"releaseLikeRolloutSelectable={str(release_like_rollout_selectable).lower()}")
print(f"servingSourceContract={serving_source_contract}")

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
