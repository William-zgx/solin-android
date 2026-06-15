#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

FIXTURE_DIR="app/src/test/resources/ai_behavior_eval"
REPORT_FILE=""
MIN_CASES_PER_CATEGORY=2

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
  local category_count="${3:-0}"
  local case_count="${4:-0}"
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'status=%s\n' "$status"
      printf 'target=ai-behavior-eval\n'
      printf 'reason=%s\n' "$reason"
      printf 'fixtureDir=%s\n' "$FIXTURE_DIR"
      printf 'categoryCount=%s\n' "$category_count"
      printf 'caseCount=%s\n' "$case_count"
      printf 'minCasesPerCategory=%s\n' "$MIN_CASES_PER_CATEGORY"
      printf 'requiredCategories=%s\n' "$(IFS=,; echo "${REQUIRED_CATEGORIES[*]}")"
    } > "$REPORT_FILE"
  fi
}

failure_report="$(mktemp)"
trap 'rm -f "$failure_report"' EXIT

if ! python3 - "$FIXTURE_DIR" "$MIN_CASES_PER_CATEGORY" "${REQUIRED_CATEGORIES[@]}" > "$failure_report" <<'PY'
import json
import pathlib
import sys

fixture_dir = pathlib.Path(sys.argv[1])
min_cases = int(sys.argv[2])
required = sys.argv[3:]

if not fixture_dir.is_dir():
    print("fixture-dir-missing")
    sys.exit(1)

total_cases = 0
for category in required:
    path = fixture_dir / f"{category}.jsonl"
    if not path.is_file():
        print(f"missing-category:{category}")
        sys.exit(1)
    rows = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            print(f"invalid-json:{category}:{line_number}:{exc.msg}")
            sys.exit(1)
        for key in ("category", "input", "expectedBoundary", "ownerAgent"):
            if not str(row.get(key, "")).strip():
                print(f"missing-field:{category}:{line_number}:{key}")
                sys.exit(1)
        if row["category"] != category:
            print(f"category-mismatch:{category}:{line_number}:{row['category']}")
            sys.exit(1)
        rows.append(row)
    if len(rows) < min_cases:
        print(f"too-few-cases:{category}:{len(rows)}")
        sys.exit(1)
    total_cases += len(rows)

print(f"categoryCount={len(required)}")
print(f"caseCount={total_cases}")
PY
then
  reason="$(cat "$failure_report" | head -n 1)"
  write_report failed "$reason"
  echo "AI behavior eval fixtures failed validation: $reason" >&2
  exit 1
fi

category_count="$(awk -F= '$1 == "categoryCount" {print $2}' "$failure_report")"
case_count="$(awk -F= '$1 == "caseCount" {print $2}' "$failure_report")"
write_report passed "" "$category_count" "$case_count"
echo "AI behavior eval fixtures passed: ${case_count} cases across ${category_count} categories."
