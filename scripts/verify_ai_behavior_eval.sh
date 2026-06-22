#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

FIXTURE_DIR="app/src/test/resources/ai_behavior_eval"
REPORT_FILE=""
MIN_CASES_PER_CATEGORY=2
REQUIRE_BOUNDARY_MAP=0
ACTUAL_TRACE_FILE=""
TRACE_DIFF_FILE=""
REQUIRE_ACTUAL_TRACE=0
REQUIRE_RUNTIME_TRACE_SOURCE=0
ACTUAL_TRACE_MAX_AGE_DAYS="${AI_BEHAVIOR_ACTUAL_TRACE_MAX_AGE_DAYS:-30}"
ORIGINAL_ARGS=("$@")

sha256_or_empty() {
  local path="$1"
  if [[ -n "$path" && -f "$path" ]]; then
    shasum -a 256 "$path" | awk '{print $1}'
  fi
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --dir)
      FIXTURE_DIR="${2:?}"
      shift 2
      ;;
    --report)
      REPORT_FILE="${2:?}"
      shift 2
      ;;
    --min-cases)
      MIN_CASES_PER_CATEGORY="${2:?}"
      shift 2
      ;;
    --require-boundary-map)
      REQUIRE_BOUNDARY_MAP=1
      shift
      ;;
    --actual-trace)
      ACTUAL_TRACE_FILE="${2:?}"
      shift 2
      ;;
    --trace-diff)
      TRACE_DIFF_FILE="${2:?}"
      shift 2
      ;;
    --require-actual-trace)
      REQUIRE_ACTUAL_TRACE=1
      shift
      ;;
    --require-runtime-trace-source)
      REQUIRE_RUNTIME_TRACE_SOURCE=1
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

REQUIRED_CATEGORIES=(
  memory_recall
  planner_false_positive
  tool_sequence
  ocr_noise
  runtime_failure
  privacy_boundary
  restart_recovery
)

write_report() {
  local status="$1"
  local reason="${2:-}"
  local metrics_file="${3:-}"
  local metric_keys=(
    categoryCount
    caseCount
    categoryBreakdown
    ownerAgentBreakdown
    expectedBoundaryCount
    mvpScenarioCount
    missingMvpScenarioCount
    requiredMvpScenarios
    missingRequiredMvpScenarios
    unexpectedMvpScenarios
    underCoveredMvpScenarios
    mvpScenarioBreakdown
    casesWithExpectedTools
    expectedToolCount
    confirmationBreakdown
    riskLevelBreakdown
    privacyBreakdown
    localOnlyCaseCount
    remoteEligibleCaseCount
    allowedFailureModeCount
    requiredRealAppSearchFailureModes
    missingRealAppSearchFailureModes
    requiredSafetyFailureModes
    missingSafetyFailureModes
    requiredBoundaryIds
    missingRequiredBoundaryIds
    actualTraceFile
    traceDiffFile
    traceDiffCaseCount
    traceDiffMatchedCount
    traceDiffAllowedFailureCount
    traceDiffMissingActualCount
    traceDiffMismatchCount
    traceDiffExtraActualCount
    traceDiffStatusBreakdown
    actualTraceFailureModeCount
    actualTraceMissingRequiredFailureModeCount
    actualTraceSourceBreakdown
    actualTraceNewestRecordedAt
  )
  local metric_defaults=(0 0 "" "" 0 0 0 "" "" "" "" "" 0 0 "" "" "" 0 0 0 "" "" "" "" "" "" "" "" 0 0 0 0 0 0 0 0 "" "")
  local index metric_key metric_value
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'status=%s\n' "$status"
      printf 'target=ai-behavior-eval\n'
      printf 'reason=%s\n' "$reason"
      printf 'artifactSchema=AgentBehaviorEvalVerification/v1\n'
      printf 'owner=agent-behavior\n'
      printf 'recordedAt=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      printf 'command=%s\n' "scripts/verify_ai_behavior_eval.sh ${ORIGINAL_ARGS[*]}"
      printf 'reproduciblePath=%s\n' "$REPORT_FILE"
      printf 'actualTraceSha256=%s\n' "$(sha256_or_empty "$ACTUAL_TRACE_FILE")"
      printf 'traceDiffSha256=%s\n' "$(sha256_or_empty "$TRACE_DIFF_FILE")"
      printf 'fixtureDir=%s\n' "$FIXTURE_DIR"
      printf 'minCasesPerCategory=%s\n' "$MIN_CASES_PER_CATEGORY"
      printf 'requireBoundaryMap=%s\n' "$REQUIRE_BOUNDARY_MAP"
      printf 'requireActualTrace=%s\n' "$REQUIRE_ACTUAL_TRACE"
      printf 'requireRuntimeTraceSource=%s\n' "$REQUIRE_RUNTIME_TRACE_SOURCE"
      printf 'actualTraceMaxAgeDays=%s\n' "$ACTUAL_TRACE_MAX_AGE_DAYS"
      printf 'requiredCategories=%s\n' "$(IFS=,; echo "${REQUIRED_CATEGORIES[*]}")"
      for index in "${!metric_keys[@]}"; do
        metric_key="${metric_keys[$index]}"
        metric_value="${metric_defaults[$index]-}"
        if [[ -n "$metrics_file" && -f "$metrics_file" ]]; then
          metric_value="$(awk -F= -v key="$metric_key" '$1 == key {sub(/^[^=]*=/, ""); print; found = 1; exit} END {if (!found) exit 1}' "$metrics_file" || true)"
          [[ -n "$metric_value" ]] || metric_value="${metric_defaults[$index]-}"
        fi
        printf '%s=%s\n' "$metric_key" "$metric_value"
      done
    } > "$REPORT_FILE"
  fi
}

validation_output="$(mktemp)"
trap 'rm -f "$validation_output"' EXIT

if ! python3 - "$FIXTURE_DIR" "$MIN_CASES_PER_CATEGORY" "$REQUIRE_BOUNDARY_MAP" \
  "$ACTUAL_TRACE_FILE" "$TRACE_DIFF_FILE" "$REQUIRE_ACTUAL_TRACE" "$REQUIRE_RUNTIME_TRACE_SOURCE" "$ACTUAL_TRACE_MAX_AGE_DAYS" \
  "${REQUIRED_CATEGORIES[@]}" > "$validation_output" <<'PY'
import collections
import datetime
import json
import pathlib
import re
import sys

fixture_dir = pathlib.Path(sys.argv[1])
min_cases = int(sys.argv[2])
require_boundary_map = sys.argv[3] == "1"
actual_trace_arg = sys.argv[4]
trace_diff_arg = sys.argv[5]
require_actual_trace = sys.argv[6] == "1"
require_runtime_trace_source = sys.argv[7] == "1"
try:
    actual_trace_max_age_days = int(sys.argv[8])
except ValueError:
    print("reason=invalid-actual-trace-max-age-days")
    sys.exit(1)
if actual_trace_max_age_days <= 0:
    print("reason=invalid-actual-trace-max-age-days")
    sys.exit(1)
required = sys.argv[9:]
capability_matrix_path = pathlib.Path("docs/capability_matrix.json")
action_models_path = pathlib.Path("app/src/main/java/com/bytedance/zgx/pocketmind/action/ActionModels.kt")

if not capability_matrix_path.is_file():
    print("reason=capability-matrix-missing")
    sys.exit(1)
if not action_models_path.is_file():
    print("reason=action-models-missing")
    sys.exit(1)
try:
    capability_matrix = json.loads(capability_matrix_path.read_text(encoding="utf-8"))
except json.JSONDecodeError as exc:
    print(f"reason=invalid-capability-matrix-json:{exc.msg}")
    sys.exit(1)
scenario_entries = capability_matrix.get("nextStageMvpScenarios", [])
if not isinstance(scenario_entries, list) or any(not isinstance(scenario, dict) for scenario in scenario_entries):
    print("reason=invalid-required-mvp-scenarios")
    sys.exit(1)
required_mvp_scenarios = [
    str(scenario.get("capabilityId", "")).strip()
    for scenario in scenario_entries
]
if (
    not required_mvp_scenarios or
    any(not scenario for scenario in required_mvp_scenarios) or
    len(set(required_mvp_scenarios)) != len(required_mvp_scenarios)
):
    print("reason=invalid-required-mvp-scenarios")
    sys.exit(1)

action_models = action_models_path.read_text(encoding="utf-8")
tool_constants = dict(re.findall(r'const val\s+([A-Z0-9_]+)\s*=\s*"([^"]+)"', action_models))
supported_match = re.search(r"val supported:\s*Set<String>\s*=\s*setOf\((.*?)\)", action_models, re.S)
if not tool_constants or not supported_match:
    print("reason=supported-tools-unreadable")
    sys.exit(1)
supported_tool_names = set()
for token in re.findall(r"\b[A-Z][A-Z0-9_]+\b", supported_match.group(1)):
    if token in tool_constants:
        supported_tool_names.add(tool_constants[token])
if not supported_tool_names:
    print("reason=supported-tools-empty")
    sys.exit(1)

if not fixture_dir.is_dir():
    print("reason=fixture-dir-missing")
    sys.exit(1)

total_cases = 0
category_counts = collections.Counter()
owner_counts = collections.Counter()
boundaries = set()
mvp_counts = collections.Counter()
missing_mvp_scenario = 0
cases_with_expected_tools = 0
expected_tool_count = 0
confirmation_counts = collections.Counter()
risk_counts = collections.Counter()
privacy_counts = collections.Counter()
local_only_case_count = 0
remote_eligible_case_count = 0
allowed_failure_mode_count = 0
observed_failure_modes = set()
eval_cases = []

allowed_confirmations = {
    "none",
    "tool_confirmation",
    "remote_send_confirmation",
    "second_confirmation",
    "fail_closed",
}
allowed_risks = {
    "public_evidence",
    "low",
    "medium",
    "high",
    "sensitive",
}
allowed_privacy = {"LocalOnly", "RemoteEligible"}
allowed_runtime_trace_sources = {
    "agent_loop_runtime",
    "android_instrumentation",
    "device_debug_eval",
}
required_real_app_search_failure_modes = {
    "search_entry_not_found",
    "editable_not_found",
    "submit_not_found",
    "result_not_verified",
    "required_hint_missing",
    "page_not_changed",
}
required_safety_failure_modes = {
    "permissiondenied",
}
required_boundary_ids = {
    "public_evidence_multi_search_batch_allowed",
}
allowed_routing_paths = {
    "",
    "skill_first",
    "action_planner",
    "remote_tool_planning",
    "model_tool_call",
    "no_action",
}

for category in required:
    path = fixture_dir / f"{category}.jsonl"
    if not path.is_file():
        print(f"reason=missing-category:{category}")
        sys.exit(1)
    rows = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            print(f"reason=invalid-json:{category}:{line_number}:{exc.msg}")
            sys.exit(1)
        for key in ("id", "category", "input", "expectedBoundary", "ownerAgent"):
            if not str(row.get(key, "")).strip():
                print(f"reason=missing-field:{category}:{line_number}:{key}")
                sys.exit(1)
        case_id = str(row["id"]).strip()
        if not re.match(r"^[a-z0-9][a-z0-9_.-]*$", case_id):
            print(f"reason=invalid-field:{category}:{line_number}:id")
            sys.exit(1)
        for key in (
            "expectedTools",
            "expectedConfirmation",
            "expectedRiskLevel",
            "privacy",
            "localOnly",
            "remoteEligible",
            "allowedFailureModes",
        ):
            if key not in row:
                print(f"reason=missing-field:{category}:{line_number}:{key}")
                sys.exit(1)
        expected_tools = row["expectedTools"]
        if (
            not isinstance(expected_tools, list) or
            any(not isinstance(tool, str) or not tool.strip() for tool in expected_tools)
        ):
            print(f"reason=invalid-field:{category}:{line_number}:expectedTools")
            sys.exit(1)
        unknown_tools = sorted(set(expected_tools) - supported_tool_names)
        if unknown_tools:
            print(f"reason=unknown-tool:{category}:{line_number}:{','.join(unknown_tools)}")
            sys.exit(1)
        expected_confirmation = str(row["expectedConfirmation"]).strip()
        if expected_confirmation not in allowed_confirmations:
            print(f"reason=invalid-field:{category}:{line_number}:expectedConfirmation")
            sys.exit(1)
        expected_risk = str(row["expectedRiskLevel"]).strip()
        if expected_risk not in allowed_risks:
            print(f"reason=invalid-field:{category}:{line_number}:expectedRiskLevel")
            sys.exit(1)
        privacy = str(row["privacy"]).strip()
        if privacy not in allowed_privacy:
            print(f"reason=invalid-field:{category}:{line_number}:privacy")
            sys.exit(1)
        local_only = row["localOnly"]
        remote_eligible = row["remoteEligible"]
        if type(local_only) is not bool:
            print(f"reason=invalid-field:{category}:{line_number}:localOnly")
            sys.exit(1)
        if type(remote_eligible) is not bool:
            print(f"reason=invalid-field:{category}:{line_number}:remoteEligible")
            sys.exit(1)
        if privacy == "LocalOnly" and (not local_only or remote_eligible):
            print(f"reason=privacy-mismatch:{category}:{line_number}:LocalOnly")
            sys.exit(1)
        if privacy == "RemoteEligible" and (local_only or not remote_eligible):
            print(f"reason=privacy-mismatch:{category}:{line_number}:RemoteEligible")
            sys.exit(1)
        if expected_confirmation == "remote_send_confirmation" and (
            privacy != "RemoteEligible" or local_only or not remote_eligible
        ):
            print(f"reason=remote-confirmation-privacy-mismatch:{category}:{line_number}")
            sys.exit(1)
        allowed_failure_modes = row["allowedFailureModes"]
        if (
            not isinstance(allowed_failure_modes, list) or
            any(not isinstance(mode, str) or not mode.strip() for mode in allowed_failure_modes)
        ):
            print(f"reason=invalid-field:{category}:{line_number}:allowedFailureModes")
            sys.exit(1)
        if expected_confirmation == "fail_closed" and not allowed_failure_modes:
            print(f"reason=missing-fail-closed-mode:{category}:{line_number}")
            sys.exit(1)
        if row["category"] != category:
            print(f"reason=category-mismatch:{category}:{line_number}:{row['category']}")
            sys.exit(1)
        mvp_scenario = str(row.get("mvpScenario", "")).strip()
        if not mvp_scenario:
            missing_mvp_scenario += 1
            if require_boundary_map:
                print(f"reason=missing-field:{category}:{line_number}:mvpScenario")
                sys.exit(1)
        else:
            mvp_counts[mvp_scenario] += 1
        owner_counts[str(row["ownerAgent"]).strip()] += 1
        boundaries.add(str(row["expectedBoundary"]).strip())
        if expected_tools:
            cases_with_expected_tools += 1
        expected_tool_count += len(expected_tools)
        confirmation_counts[expected_confirmation] += 1
        risk_counts[expected_risk] += 1
        privacy_counts[privacy] += 1
        if local_only:
            local_only_case_count += 1
        if remote_eligible:
            remote_eligible_case_count += 1
        allowed_failure_mode_count += len(allowed_failure_modes)
        observed_failure_modes.update(allowed_failure_modes)
        eval_cases.append(
            {
                "caseId": case_id,
                "category": category,
                "lineNumber": line_number,
                "input": str(row["input"]),
                "expectedBoundary": str(row["expectedBoundary"]),
                "expectedTools": expected_tools,
                "expectedConfirmation": expected_confirmation,
                "expectedRiskLevel": expected_risk,
                "privacy": privacy,
                "localOnly": local_only,
                "remoteEligible": remote_eligible,
                "allowedFailureModes": allowed_failure_modes,
            }
        )
        rows.append(row)
    if len(rows) < min_cases:
        print(f"reason=too-few-cases:{category}:{len(rows)}")
        sys.exit(1)
    category_counts[category] = len(rows)
    total_cases += len(rows)

observed_mvp_scenarios = set(mvp_counts)
required_mvp_set = set(required_mvp_scenarios)
missing_required_mvp_scenarios = sorted(required_mvp_set - observed_mvp_scenarios)
unexpected_mvp_scenarios = sorted(observed_mvp_scenarios - required_mvp_set)
under_covered_mvp_scenarios = sorted(
    f"{scenario}:{mvp_counts[scenario]}"
    for scenario in required_mvp_scenarios
    if mvp_counts[scenario] < 2
)
missing_real_app_search_failure_modes = sorted(required_real_app_search_failure_modes - observed_failure_modes)
missing_safety_failure_modes = sorted(required_safety_failure_modes - observed_failure_modes)
missing_required_boundary_ids = sorted(required_boundary_ids - boundaries)

case_ids = [case["caseId"] for case in eval_cases]
case_id_set = set(case_ids)
duplicate_case_ids = sorted(case_id for case_id, count in collections.Counter(case_ids).items() if count > 1)
if duplicate_case_ids:
    print(f"reason=duplicate-trace-case-id:{','.join(duplicate_case_ids)}")
    sys.exit(1)

now_utc = datetime.datetime.now(datetime.timezone.utc)

def validate_trace_recorded_at(value, line_number):
    if not isinstance(value, str) or not re.match(r"^20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$", value):
        print(f"reason=invalid-actual-trace:{line_number}:traceRecordedAt")
        sys.exit(1)
    try:
        parsed = datetime.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)
    except ValueError:
        print(f"reason=invalid-actual-trace:{line_number}:traceRecordedAt")
        sys.exit(1)
    if parsed > now_utc + datetime.timedelta(minutes=5):
        print(f"reason=actual-trace-recordedAt-future:{line_number}")
        sys.exit(1)
    if now_utc - parsed > datetime.timedelta(days=actual_trace_max_age_days):
        print(f"reason=actual-trace-recordedAt-stale:{line_number}")
        sys.exit(1)
    return parsed

actual_trace_source_counts = collections.Counter()
actual_trace_recorded_at_values = []

def load_actual_traces():
    if not actual_trace_arg:
        if require_actual_trace:
            print("reason=actual-trace-file-missing")
            sys.exit(1)
        return []
    actual_trace_path = pathlib.Path(actual_trace_arg)
    if not actual_trace_path.is_file():
        print("reason=actual-trace-file-missing")
        sys.exit(1)
    traces = []
    seen_actual_case_ids = set()
    for line_number, line in enumerate(actual_trace_path.read_text(encoding="utf-8").splitlines(), start=1):
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            print(f"reason=invalid-actual-trace-json:{line_number}:{exc.msg}")
            sys.exit(1)
        case_id = str(row.get("caseId", "")).strip()
        category = str(row.get("category", "")).strip()
        input_text = str(row.get("input", "")).strip()
        if require_actual_trace:
            if not case_id:
                print(f"reason=invalid-actual-trace:{line_number}:caseId")
                sys.exit(1)
            if case_id not in case_id_set:
                print(f"reason=actual-trace-unknown-case-id:{line_number}:{case_id}")
                sys.exit(1)
            if case_id in seen_actual_case_ids:
                print(f"reason=actual-trace-duplicate-case-id:{case_id}")
                sys.exit(1)
            seen_actual_case_ids.add(case_id)
        else:
            if not case_id and (not category or not input_text):
                print(f"reason=invalid-actual-trace:{line_number}:caseId-or-category-input")
                sys.exit(1)
        actual_tools = row.get("actualTools", [])
        if (
            not isinstance(actual_tools, list) or
            any(not isinstance(tool, str) or not tool.strip() for tool in actual_tools)
        ):
            print(f"reason=invalid-actual-trace:{line_number}:actualTools")
            sys.exit(1)
        unknown_tools = sorted(set(actual_tools) - supported_tool_names)
        if unknown_tools:
            print(f"reason=unknown-actual-tool:{line_number}:{','.join(unknown_tools)}")
            sys.exit(1)
        actual_confirmation = str(row.get("actualConfirmation", "")).strip()
        if actual_confirmation not in allowed_confirmations:
            print(f"reason=invalid-actual-trace:{line_number}:actualConfirmation")
            sys.exit(1)
        actual_risk = str(row.get("actualRiskLevel", "")).strip()
        if actual_risk not in allowed_risks:
            print(f"reason=invalid-actual-trace:{line_number}:actualRiskLevel")
            sys.exit(1)
        privacy = str(row.get("privacy", "")).strip()
        if privacy not in allowed_privacy:
            print(f"reason=invalid-actual-trace:{line_number}:privacy")
            sys.exit(1)
        local_only = row.get("localOnly")
        remote_eligible = row.get("remoteEligible")
        if type(local_only) is not bool:
            print(f"reason=invalid-actual-trace:{line_number}:localOnly")
            sys.exit(1)
        if type(remote_eligible) is not bool:
            print(f"reason=invalid-actual-trace:{line_number}:remoteEligible")
            sys.exit(1)
        if privacy == "LocalOnly" and (not local_only or remote_eligible):
            print(f"reason=actual-trace-privacy-mismatch:{line_number}:LocalOnly")
            sys.exit(1)
        if privacy == "RemoteEligible" and (local_only or not remote_eligible):
            print(f"reason=actual-trace-privacy-mismatch:{line_number}:RemoteEligible")
            sys.exit(1)
        if actual_confirmation == "remote_send_confirmation" and (
            privacy != "RemoteEligible" or local_only or not remote_eligible
        ):
            print(f"reason=actual-trace-remote-confirmation-privacy-mismatch:{line_number}")
            sys.exit(1)
        failure_mode = row.get("failureMode", "")
        if failure_mode is None:
            failure_mode = ""
        if not isinstance(failure_mode, str):
            print(f"reason=invalid-actual-trace:{line_number}:failureMode")
            sys.exit(1)
        routing_path = str(row.get("routingPath", "")).strip()
        if routing_path not in allowed_routing_paths:
            print(f"reason=invalid-actual-trace:{line_number}:routingPath")
            sys.exit(1)
        routing_tool_name = str(row.get("routingToolName", "")).strip()
        if routing_tool_name and routing_tool_name not in supported_tool_names and routing_tool_name != "tool_batch":
            print(f"reason=invalid-actual-trace:{line_number}:routingToolName")
            sys.exit(1)
        routing_skill_id = str(row.get("routingSkillId", "")).strip()
        if routing_skill_id and not re.match(r"^[A-Za-z0-9_.-]+$", routing_skill_id):
            print(f"reason=invalid-actual-trace:{line_number}:routingSkillId")
            sys.exit(1)
        routing_rejection_reason = str(row.get("routingRejectionReason", "")).strip()
        if routing_rejection_reason and not re.match(r"^[a-z0-9][a-z0-9_.-]*$", routing_rejection_reason):
            print(f"reason=invalid-actual-trace:{line_number}:routingRejectionReason")
            sys.exit(1)
        if routing_path == "no_action" and (actual_tools or routing_tool_name or routing_skill_id):
            print(f"reason=actual-trace-routing-conflict:{line_number}:no_action")
            sys.exit(1)
        trace_source = str(row.get("traceSource", "")).strip()
        trace_recorded_at = row.get("traceRecordedAt", "")
        if require_runtime_trace_source:
            if trace_source not in allowed_runtime_trace_sources:
                print(f"reason=invalid-actual-trace:{line_number}:traceSource")
                sys.exit(1)
        if require_actual_trace or require_runtime_trace_source:
            parsed_recorded_at = validate_trace_recorded_at(trace_recorded_at, line_number)
            actual_trace_recorded_at_values.append((parsed_recorded_at, trace_recorded_at))
        if trace_source:
            actual_trace_source_counts[trace_source] += 1
        traces.append(
            {
                "lineNumber": line_number,
                "caseId": case_id,
                "category": category,
                "input": input_text,
                "actualTools": actual_tools,
                "actualConfirmation": actual_confirmation,
                "actualRiskLevel": actual_risk,
                "privacy": privacy,
                "localOnly": local_only,
                "remoteEligible": remote_eligible,
                "failureMode": failure_mode.strip(),
                "routingPath": routing_path,
                "routingToolName": routing_tool_name,
                "routingSkillId": routing_skill_id,
                "routingRejectionReason": routing_rejection_reason,
                "traceSource": trace_source,
                "traceRecordedAt": trace_recorded_at if isinstance(trace_recorded_at, str) else "",
                "matched": False,
            }
        )
    return traces

actual_traces = load_actual_traces()
actual_trace_newest_recorded_at = ""
if actual_trace_recorded_at_values:
    actual_trace_newest_recorded_at = max(actual_trace_recorded_at_values, key=lambda item: item[0])[1]
actual_trace_failure_mode_count = sum(1 for trace in actual_traces if trace["failureMode"])
actual_by_case_id = {
    trace["caseId"]: trace
    for trace in actual_traces
    if trace["caseId"]
}
actual_by_category_input = collections.defaultdict(list)
for trace in actual_traces:
    if trace["category"] and trace["input"]:
        actual_by_category_input[(trace["category"], trace["input"])].append(trace)

trace_diff_rows = []
trace_diff_counts = collections.Counter()
actual_trace_missing_required_failure_mode_count = 0
for case in eval_cases:
    actual = actual_by_case_id.get(case["caseId"])
    if actual is None and not require_actual_trace:
        candidates = actual_by_category_input.get((case["category"], case["input"]), [])
        actual = next((candidate for candidate in candidates if not candidate["matched"]), None)
    if actual is not None:
        actual["matched"] = True
    actual_tools = actual["actualTools"] if actual is not None else []
    actual_confirmation = actual["actualConfirmation"] if actual is not None else ""
    actual_risk = actual["actualRiskLevel"] if actual is not None else ""
    actual_privacy = actual["privacy"] if actual is not None else ""
    actual_local_only = actual["localOnly"] if actual is not None else None
    actual_remote_eligible = actual["remoteEligible"] if actual is not None else None
    actual_failure_mode = actual["failureMode"] if actual is not None else ""
    actual_routing_path = actual["routingPath"] if actual is not None else ""
    actual_routing_tool_name = actual["routingToolName"] if actual is not None else ""
    actual_routing_skill_id = actual["routingSkillId"] if actual is not None else ""
    actual_routing_rejection_reason = actual["routingRejectionReason"] if actual is not None else ""
    actual_trace_source = actual["traceSource"] if actual is not None else ""
    actual_trace_recorded_at = actual["traceRecordedAt"] if actual is not None else ""
    tools_match = case["expectedTools"] == actual_tools
    confirmation_match = case["expectedConfirmation"] == actual_confirmation
    risk_match = case["expectedRiskLevel"] == actual_risk
    privacy_match = case["privacy"] == actual_privacy
    local_only_match = case["localOnly"] == actual_local_only
    remote_eligible_match = case["remoteEligible"] == actual_remote_eligible
    safety_boundary_match = (
        risk_match and
        privacy_match and
        local_only_match and
        remote_eligible_match
    )
    fail_closed_invariant_match = (
        case["expectedConfirmation"] != "fail_closed" or
        confirmation_match
    )
    allowed_failure_mode_match = (
        actual_failure_mode and
        actual_failure_mode in case["allowedFailureModes"]
    )
    required_failure_mode_match = (
        not require_actual_trace or
        case["expectedConfirmation"] != "fail_closed" or
        allowed_failure_mode_match
    )
    if (
        require_actual_trace and
        actual is not None and
        case["expectedConfirmation"] == "fail_closed" and
        not allowed_failure_mode_match
    ):
        actual_trace_missing_required_failure_mode_count += 1
    allowed_failure_match = (
        allowed_failure_mode_match and
        safety_boundary_match and
        fail_closed_invariant_match
    )
    if actual is None:
        status = "missing_actual"
    elif allowed_failure_match:
        status = "allowed_failure"
    elif (
        tools_match and
        confirmation_match and
        safety_boundary_match and
        required_failure_mode_match
    ):
        status = "matched"
    else:
        status = "mismatch"
    trace_diff_counts[status] += 1
    trace_diff_rows.append(
        {
            "caseId": case["caseId"],
            "category": case["category"],
            "input": case["input"],
            "expectedBoundary": case["expectedBoundary"],
            "expectedTools": case["expectedTools"],
            "actualTools": actual_tools,
            "expectedConfirmation": case["expectedConfirmation"],
            "actualConfirmation": actual_confirmation,
            "expectedRiskLevel": case["expectedRiskLevel"],
            "actualRiskLevel": actual_risk,
            "expectedPrivacy": case["privacy"],
            "actualPrivacy": actual_privacy,
            "expectedLocalOnly": case["localOnly"],
            "actualLocalOnly": actual_local_only,
            "expectedRemoteEligible": case["remoteEligible"],
            "actualRemoteEligible": actual_remote_eligible,
            "allowedFailureModes": case["allowedFailureModes"],
            "actualFailureMode": actual_failure_mode,
            "actualRoutingPath": actual_routing_path,
            "actualRoutingToolName": actual_routing_tool_name,
            "actualRoutingSkillId": actual_routing_skill_id,
            "actualRoutingRejectionReason": actual_routing_rejection_reason,
            "actualTraceSource": actual_trace_source,
            "actualTraceRecordedAt": actual_trace_recorded_at,
            "toolsMatch": tools_match,
            "confirmationMatches": confirmation_match,
            "riskMatches": risk_match,
            "privacyMatches": privacy_match,
            "localOnlyMatches": local_only_match,
            "remoteEligibleMatches": remote_eligible_match,
            "allowedFailureModeMatches": bool(allowed_failure_mode_match),
            "requiredFailureModeMatches": bool(required_failure_mode_match),
            "allowedFailureSafetyMatches": bool(allowed_failure_match),
            "safetyBoundaryMatches": bool(safety_boundary_match),
            "failClosedInvariantMatches": bool(fail_closed_invariant_match),
            "status": status,
        }
    )

trace_diff_extra_actual_count = sum(1 for trace in actual_traces if not trace["matched"])
if trace_diff_arg:
    trace_diff_path = pathlib.Path(trace_diff_arg)
    trace_diff_path.parent.mkdir(parents=True, exist_ok=True)
    trace_diff_path.write_text(
        "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in trace_diff_rows),
        encoding="utf-8",
    )

def encode_counter(counter):
    return ",".join(f"{key}:{counter[key]}" for key in sorted(counter))

def encode_list(values):
    return ",".join(values)

def emit_metrics(reason=""):
    print(f"reason={reason}")
    print(f"categoryCount={len(required)}")
    print(f"caseCount={total_cases}")
    print(f"categoryBreakdown={encode_counter(category_counts)}")
    print(f"ownerAgentBreakdown={encode_counter(owner_counts)}")
    print(f"expectedBoundaryCount={len(boundaries)}")
    print(f"mvpScenarioCount={len(mvp_counts)}")
    print(f"missingMvpScenarioCount={missing_mvp_scenario}")
    print(f"requiredMvpScenarios={encode_list(required_mvp_scenarios)}")
    print(f"missingRequiredMvpScenarios={encode_list(missing_required_mvp_scenarios)}")
    print(f"unexpectedMvpScenarios={encode_list(unexpected_mvp_scenarios)}")
    print(f"underCoveredMvpScenarios={encode_list(under_covered_mvp_scenarios)}")
    print(f"mvpScenarioBreakdown={encode_counter(mvp_counts)}")
    print(f"casesWithExpectedTools={cases_with_expected_tools}")
    print(f"expectedToolCount={expected_tool_count}")
    print(f"confirmationBreakdown={encode_counter(confirmation_counts)}")
    print(f"riskLevelBreakdown={encode_counter(risk_counts)}")
    print(f"privacyBreakdown={encode_counter(privacy_counts)}")
    print(f"localOnlyCaseCount={local_only_case_count}")
    print(f"remoteEligibleCaseCount={remote_eligible_case_count}")
    print(f"allowedFailureModeCount={allowed_failure_mode_count}")
    print(f"requiredRealAppSearchFailureModes={encode_list(sorted(required_real_app_search_failure_modes))}")
    print(f"missingRealAppSearchFailureModes={encode_list(missing_real_app_search_failure_modes)}")
    print(f"requiredSafetyFailureModes={encode_list(sorted(required_safety_failure_modes))}")
    print(f"missingSafetyFailureModes={encode_list(missing_safety_failure_modes)}")
    print(f"requiredBoundaryIds={encode_list(sorted(required_boundary_ids))}")
    print(f"missingRequiredBoundaryIds={encode_list(missing_required_boundary_ids)}")
    print(f"actualTraceFile={actual_trace_arg}")
    print(f"traceDiffFile={trace_diff_arg}")
    print(f"requireActualTrace={int(require_actual_trace)}")
    print(f"requireRuntimeTraceSource={int(require_runtime_trace_source)}")
    print(f"actualTraceMaxAgeDays={actual_trace_max_age_days}")
    print(f"traceDiffCaseCount={len(eval_cases)}")
    print(f"traceDiffMatchedCount={trace_diff_counts['matched']}")
    print(f"traceDiffAllowedFailureCount={trace_diff_counts['allowed_failure']}")
    print(f"traceDiffMissingActualCount={trace_diff_counts['missing_actual']}")
    print(f"traceDiffMismatchCount={trace_diff_counts['mismatch']}")
    print(f"traceDiffExtraActualCount={trace_diff_extra_actual_count}")
    print(f"traceDiffStatusBreakdown={encode_counter(trace_diff_counts)}")
    print(f"actualTraceFailureModeCount={actual_trace_failure_mode_count}")
    print(f"actualTraceMissingRequiredFailureModeCount={actual_trace_missing_required_failure_mode_count}")
    print(f"actualTraceSourceBreakdown={encode_counter(actual_trace_source_counts)}")
    print(f"actualTraceNewestRecordedAt={actual_trace_newest_recorded_at}")

if require_boundary_map and not mvp_counts:
    emit_metrics("missing-mvp-scenarios")
    sys.exit(1)
if require_boundary_map and missing_required_mvp_scenarios:
    emit_metrics("missing-required-mvp-scenarios")
    sys.exit(1)
if require_boundary_map and unexpected_mvp_scenarios:
    emit_metrics("unexpected-mvp-scenarios")
    sys.exit(1)
if require_boundary_map and under_covered_mvp_scenarios:
    emit_metrics("too-few-mvp-scenario-cases")
    sys.exit(1)

required_confirmations = {"tool_confirmation", "remote_send_confirmation", "second_confirmation", "fail_closed"}
missing_confirmations = sorted(required_confirmations - set(confirmation_counts))
required_risks = {"public_evidence", "low", "medium", "sensitive"}
missing_risks = sorted(required_risks - set(risk_counts))
if cases_with_expected_tools <= 0:
    emit_metrics("missing-expected-tool-coverage")
    sys.exit(1)
if local_only_case_count <= 0 or remote_eligible_case_count <= 0:
    emit_metrics("missing-privacy-boundary-coverage")
    sys.exit(1)
if missing_confirmations:
    emit_metrics(f"missing-confirmation-coverage:{','.join(missing_confirmations)}")
    sys.exit(1)
if missing_risks:
    emit_metrics(f"missing-risk-coverage:{','.join(missing_risks)}")
    sys.exit(1)
if missing_real_app_search_failure_modes:
    emit_metrics(f"missing-real-app-search-failure-mode-coverage:{','.join(missing_real_app_search_failure_modes)}")
    sys.exit(1)
if missing_safety_failure_modes:
    emit_metrics(f"missing-safety-failure-mode-coverage:{','.join(missing_safety_failure_modes)}")
    sys.exit(1)
if missing_required_boundary_ids:
    emit_metrics(f"missing-required-boundary-coverage:{','.join(missing_required_boundary_ids)}")
    sys.exit(1)
if require_actual_trace and trace_diff_counts["missing_actual"] > 0:
    emit_metrics("trace-diff-missing-actual")
    sys.exit(1)
if require_actual_trace and actual_trace_missing_required_failure_mode_count > 0:
    emit_metrics("trace-diff-missing-required-failure-mode")
    sys.exit(1)
if require_actual_trace and trace_diff_counts["mismatch"] > 0:
    emit_metrics("trace-diff-mismatch")
    sys.exit(1)
if require_actual_trace and trace_diff_extra_actual_count > 0:
    emit_metrics("trace-diff-extra-actual")
    sys.exit(1)

emit_metrics()
PY
then
  reason="$(awk -F= '$1 == "reason" {print $2; exit}' "$validation_output")"
  write_report failed "$reason" "$validation_output"
  echo "AI behavior eval fixtures failed validation: $reason" >&2
  exit 1
fi

reason="$(awk -F= '$1 == "reason" {print $2; exit}' "$validation_output")"
write_report passed "$reason" "$validation_output"
case_count="$(awk -F= '$1 == "caseCount" {print $2; exit}' "$validation_output")"
category_count="$(awk -F= '$1 == "categoryCount" {print $2; exit}' "$validation_output")"
mvp_count="$(awk -F= '$1 == "mvpScenarioCount" {print $2; exit}' "$validation_output")"
echo "AI behavior eval fixtures passed: ${case_count} cases across ${category_count} categories and ${mvp_count} MVP scenarios."
