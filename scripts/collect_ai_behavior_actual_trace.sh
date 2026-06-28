#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/ai-behavior-actual-trace}"
ACTUAL_TRACE_FILE="${AI_BEHAVIOR_ACTUAL_TRACE_FILE:-${ARTIFACT_DIR}/ai-behavior-actual-trace.jsonl}"
TRACE_DIFF_FILE="${TRACE_DIFF_FILE:-${ARTIFACT_DIR}/ai-behavior-planning-trace-diff.jsonl}"
EVAL_REPORT_FILE="${EVAL_REPORT_FILE:-${ARTIFACT_DIR}/ai-behavior-eval.properties}"
REPORT_FILE="${REPORT_FILE:-${ARTIFACT_DIR}/ai-behavior-actual-trace-collection.properties}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
PUBLIC_RELEASE_CONTEXT="${PUBLIC_RELEASE_CONTEXT:-${PUBLIC_RELEASE:-0}}"
REJECT_ALLOWED_FAILURES="${AI_BEHAVIOR_REJECT_ALLOWED_FAILURES:-$PUBLIC_RELEASE_CONTEXT}"
ORIGINAL_ARGS=("$@")

command_line() {
  local quoted=()
  local arg
  quoted+=("$(printf '%q' "$0")")
  if [[ "${#ORIGINAL_ARGS[@]}" -gt 0 ]]; then
    for arg in "${ORIGINAL_ARGS[@]}"; do
      quoted+=("$(printf '%q' "$arg")")
    done
  fi
  local IFS=' '
  printf '%s' "${quoted[*]}"
}

mkdir -p "$ARTIFACT_DIR" "$(dirname "$ACTUAL_TRACE_FILE")" "$(dirname "$TRACE_DIFF_FILE")" "$(dirname "$EVAL_REPORT_FILE")"

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
  {
    printf 'status=%s\n' "$status"
    printf 'target=ai-behavior-actual-trace-collector\n'
    printf 'reason=%s\n' "$reason"
    printf 'artifactSchema=AgentBehaviorActualTraceCollection/v1\n'
    printf 'owner=agent-behavior\n'
    printf 'recordedAt=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'command=%s\n' "$(command_line)"
    printf 'reproduciblePath=%s\n' "$REPORT_FILE"
    printf 'artifactDir=%s\n' "$ARTIFACT_DIR"
    printf 'actualTraceFile=%s\n' "$ACTUAL_TRACE_FILE"
    printf 'actualTraceSha256=%s\n' "$(sha256_or_empty "$ACTUAL_TRACE_FILE")"
    printf 'traceDiffFile=%s\n' "$TRACE_DIFF_FILE"
    printf 'traceDiffSha256=%s\n' "$(sha256_or_empty "$TRACE_DIFF_FILE")"
    printf 'evalReportFile=%s\n' "$EVAL_REPORT_FILE"
    printf 'evalReportSha256=%s\n' "$(sha256_or_empty "$EVAL_REPORT_FILE")"
    printf 'evalFixtureDir=%s\n' "$(report_value "$EVAL_REPORT_FILE" fixtureDir)"
    printf 'evalFixtureDirSha256=%s\n' "$(report_value "$EVAL_REPORT_FILE" fixtureDirSha256)"
    printf 'evalCapabilityMatrixFile=%s\n' "$(report_value "$EVAL_REPORT_FILE" capabilityMatrixFile)"
    printf 'evalCapabilityMatrixSha256=%s\n' "$(report_value "$EVAL_REPORT_FILE" capabilityMatrixSha256)"
    printf 'evalActionModelsFile=%s\n' "$(report_value "$EVAL_REPORT_FILE" actionModelsFile)"
    printf 'evalActionModelsSha256=%s\n' "$(report_value "$EVAL_REPORT_FILE" actionModelsSha256)"
    printf 'evalRequireActualTrace=%s\n' "$(report_value "$EVAL_REPORT_FILE" requireActualTrace)"
    printf 'evalRequireRuntimeTraceSource=%s\n' "$(report_value "$EVAL_REPORT_FILE" requireRuntimeTraceSource)"
    printf 'evalRequireAgentLoopRuntimeTraceSource=%s\n' "$(report_value "$EVAL_REPORT_FILE" requireAgentLoopRuntimeTraceSource)"
    printf 'evalRejectAllowedFailures=%s\n' "$(report_value "$EVAL_REPORT_FILE" rejectAllowedFailures)"
    printf 'publicReleaseContext=%s\n' "$PUBLIC_RELEASE_CONTEXT"
    printf 'rejectAllowedFailures=%s\n' "$REJECT_ALLOWED_FAILURES"
    printf 'evalActualTraceMaxAgeDays=%s\n' "$(report_value "$EVAL_REPORT_FILE" actualTraceMaxAgeDays)"
    printf 'caseCount=%s\n' "$(report_value "$EVAL_REPORT_FILE" caseCount)"
    printf 'traceDiffMatchedCount=%s\n' "$(report_value "$EVAL_REPORT_FILE" traceDiffMatchedCount)"
    printf 'traceDiffAllowedFailureCount=%s\n' "$(report_value "$EVAL_REPORT_FILE" traceDiffAllowedFailureCount)"
    printf 'traceDiffMissingActualCount=%s\n' "$(report_value "$EVAL_REPORT_FILE" traceDiffMissingActualCount)"
    printf 'traceDiffMismatchCount=%s\n' "$(report_value "$EVAL_REPORT_FILE" traceDiffMismatchCount)"
    printf 'traceDiffExtraActualCount=%s\n' "$(report_value "$EVAL_REPORT_FILE" traceDiffExtraActualCount)"
    printf 'actualTraceSourceBreakdown=%s\n' "$(report_value "$EVAL_REPORT_FILE" actualTraceSourceBreakdown)"
  } > "$REPORT_FILE"
}

if ! env AI_BEHAVIOR_ACTUAL_TRACE_FILE="$ACTUAL_TRACE_FILE" "$GRADLE_CMD" \
  "-PaiBehaviorActualTraceFile=$ACTUAL_TRACE_FILE" \
  :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.solin.eval.AiBehaviorActualTraceGeneratorTest' \
  --rerun-tasks; then
  write_report failed "actual-trace-generator-failed"
  echo "AI behavior actual trace generator failed." >&2
  exit 1
fi

if [[ ! -s "$ACTUAL_TRACE_FILE" ]]; then
  write_report failed "actual-trace-file-empty"
  echo "AI behavior actual trace file was not created: $ACTUAL_TRACE_FILE" >&2
  exit 1
fi

VERIFY_ARGS=(
  --require-boundary-map
  --actual-trace "$ACTUAL_TRACE_FILE"
  --trace-diff "$TRACE_DIFF_FILE"
  --require-actual-trace
  --require-runtime-trace-source
  --require-agent-loop-runtime-trace-source
  --report "$EVAL_REPORT_FILE"
)
if [[ "$REJECT_ALLOWED_FAILURES" == "1" ]]; then
  VERIFY_ARGS+=(--reject-allowed-failures)
fi

if ! scripts/verify_ai_behavior_eval.sh "${VERIFY_ARGS[@]}"; then
  reason="$(report_value "$EVAL_REPORT_FILE" reason)"
  write_report failed "${reason:-ai-behavior-eval-failed}"
  echo "AI behavior actual trace failed verifier: ${reason:-ai-behavior-eval-failed}" >&2
  exit 1
fi

missing_actual_count="$(report_value "$EVAL_REPORT_FILE" traceDiffMissingActualCount)"
mismatch_count="$(report_value "$EVAL_REPORT_FILE" traceDiffMismatchCount)"
extra_actual_count="$(report_value "$EVAL_REPORT_FILE" traceDiffExtraActualCount)"
case_count="$(report_value "$EVAL_REPORT_FILE" caseCount)"
source_breakdown="$(report_value "$EVAL_REPORT_FILE" actualTraceSourceBreakdown)"

if [[ "$missing_actual_count" != "0" ]]; then
  write_report failed "actual-trace-missing-cases"
  echo "AI behavior actual trace is missing fixture cases." >&2
  exit 1
fi
if [[ "$mismatch_count" != "0" ]]; then
  write_report failed "trace-diff-mismatch"
  echo "AI behavior actual trace does not match fixture expectations." >&2
  exit 1
fi
if [[ "$extra_actual_count" != "0" ]]; then
  write_report failed "actual-trace-extra-cases"
  echo "AI behavior actual trace contains extra cases." >&2
  exit 1
fi
if [[ -z "$case_count" || "$source_breakdown" != "agent_loop_runtime:${case_count}" ]]; then
  write_report failed "actual-trace-non-runtime-source"
  echo "AI behavior actual trace must contain only agent_loop_runtime provenance." >&2
  exit 1
fi

write_report passed
echo "AI behavior actual trace collected: $REPORT_FILE"
