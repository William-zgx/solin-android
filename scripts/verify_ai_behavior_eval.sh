#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

FIXTURE_DIR="app/src/test/resources/ai_behavior_eval"
REPORT_FILE=""
MIN_CASES_PER_CATEGORY=2
REQUIRE_BOUNDARY_MAP=0

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
  )
  local metric_defaults=(0 0 "" "" 0 0 0 "" "" "" "" "")
  local index metric_key metric_value
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'status=%s\n' "$status"
      printf 'target=ai-behavior-eval\n'
      printf 'reason=%s\n' "$reason"
      printf 'fixtureDir=%s\n' "$FIXTURE_DIR"
      printf 'minCasesPerCategory=%s\n' "$MIN_CASES_PER_CATEGORY"
      printf 'requireBoundaryMap=%s\n' "$REQUIRE_BOUNDARY_MAP"
      printf 'requiredCategories=%s\n' "$(IFS=,; echo "${REQUIRED_CATEGORIES[*]}")"
      for index in "${!metric_keys[@]}"; do
        metric_key="${metric_keys[$index]}"
        metric_value="${metric_defaults[$index]}"
        if [[ -n "$metrics_file" && -f "$metrics_file" ]]; then
          metric_value="$(awk -F= -v key="$metric_key" '$1 == key {sub(/^[^=]*=/, ""); print; found = 1; exit} END {if (!found) exit 1}' "$metrics_file" || true)"
          [[ -n "$metric_value" ]] || metric_value="${metric_defaults[$index]}"
        fi
        printf '%s=%s\n' "$metric_key" "$metric_value"
      done
    } > "$REPORT_FILE"
  fi
}

validation_output="$(mktemp)"
trap 'rm -f "$validation_output"' EXIT

if ! python3 - "$FIXTURE_DIR" "$MIN_CASES_PER_CATEGORY" "$REQUIRE_BOUNDARY_MAP" \
  "${REQUIRED_CATEGORIES[@]}" > "$validation_output" <<'PY'
import collections
import json
import pathlib
import sys

fixture_dir = pathlib.Path(sys.argv[1])
min_cases = int(sys.argv[2])
require_boundary_map = sys.argv[3] == "1"
required = sys.argv[4:]
capability_matrix_path = pathlib.Path("docs/capability_matrix.json")

if not capability_matrix_path.is_file():
    print("reason=capability-matrix-missing")
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

if not fixture_dir.is_dir():
    print("reason=fixture-dir-missing")
    sys.exit(1)

total_cases = 0
category_counts = collections.Counter()
owner_counts = collections.Counter()
boundaries = set()
mvp_counts = collections.Counter()
missing_mvp_scenario = 0

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
        for key in ("category", "input", "expectedBoundary", "ownerAgent"):
            if not str(row.get(key, "")).strip():
                print(f"reason=missing-field:{category}:{line_number}:{key}")
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
