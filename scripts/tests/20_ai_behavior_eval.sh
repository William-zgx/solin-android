# shellcheck shell=bash
# Validation script tests: verify_ai_behavior_eval.sh and collect_ai_behavior_actual_trace.sh.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

ai_behavior_eval_contract=(
  docs/capability_matrix.json
  nextStageMvpScenarios
)
for expected in "${ai_behavior_eval_contract[@]}"; do
  assert_file_contains_text scripts/verify_ai_behavior_eval.sh "$expected"
done
if grep -q 'REQUIRED_MVP_SCENARIOS=' scripts/verify_ai_behavior_eval.sh; then
  fail "AI behavior eval gate must not keep a hard-coded MVP scenario array"
fi
python3 - <<'PY' || fail "Capability matrix JSON MVP scenarios must match CapabilityMatrix"
import json
import pathlib
import re
import sys

kotlin = pathlib.Path("app/src/main/java/com/bytedance/zgx/solin/capability/CapabilityMatrix.kt").read_text()
capability_matrix = json.loads(pathlib.Path("docs/capability_matrix.json").read_text())

def extract_quoted(pattern, source, label):
    match = re.search(pattern, source, re.S)
    if not match:
        print(f"missing {label}", file=sys.stderr)
        sys.exit(1)
    return re.findall(r'"([^"]+)"', match.group(1))

kotlin_ids = extract_quoted(
    r"nextStageMvpScenarioIds:\s*List<String>\s*=\s*listOf\((.*?)\)",
    kotlin,
    "CapabilityMatrix.nextStageMvpScenarioIds",
)
json_ids = [scenario.get("capabilityId", "") for scenario in capability_matrix.get("nextStageMvpScenarios", [])]
json_titles = [scenario.get("title", "") for scenario in capability_matrix.get("nextStageMvpScenarios", [])]
if kotlin_ids != json_ids:
    print(f"CapabilityMatrix MVP scenarios {kotlin_ids} != docs/capability_matrix.json {json_ids}", file=sys.stderr)
    sys.exit(1)
if len(set(json_ids)) != len(json_ids) or any(not title.strip() for title in json_titles):
    print("docs/capability_matrix.json nextStageMvpScenarios must have unique IDs and non-blank titles", file=sys.stderr)
    sys.exit(1)
PY
assert_file_contains_text docs/capability_matrix.json requiredBehaviorEvalBoundaries
assert_file_contains_text \
  app/src/main/java/com/bytedance/zgx/solin/capability/CapabilityMatrix.kt \
  requiredBehaviorEvalBoundaries
assert_file_contains_text \
  app/src/main/java/com/bytedance/zgx/solin/action/ActionModels.kt \
  'val supported: Set<String>'

ai_behavior_eval_contract=(
  --capability-matrix
  underCoveredMvpScenarios=
  missingRequiredMvpScenarios=
  expectedTools
  expectedConfirmation
  expectedRiskLevel
  privacy-mismatch
  supported_tool_names
  unknown-tool
  casesWithExpectedTools=
  confirmationBreakdown=
  riskLevelBreakdown=
  privacyBreakdown=
  missing-confirmation-coverage
  remote_send_confirmation
  missing-risk-coverage
  traceDiffMissingActualCount=
  actualTools
  routingPath
  actualRoutingPath
  actualTraceSource
  actualTraceRecordedAt
  AI_BEHAVIOR_ACTUAL_TRACE_MAX_AGE_DAYS
  actual-trace-recordedAt-stale
  actual-trace-unknown-case-id
  trace-diff-missing-required-failure-mode
)
for expected in "${ai_behavior_eval_contract[@]}"; do
  assert_file_contains_text scripts/verify_ai_behavior_eval.sh "$expected"
done
AI_BEHAVIOR_FIXTURE_DIR_SHA="$(fixture_dir_sha256 app/src/test/resources/ai_behavior_eval)"
AI_BEHAVIOR_ACTION_MODELS_FILE="app/src/main/java/com/bytedance/zgx/solin/action/ActionModels.kt"
AI_BEHAVIOR_ACTION_MODELS_SHA="$(shasum -a 256 "$AI_BEHAVIOR_ACTION_MODELS_FILE" | awk '{print $1}')"
AI_BEHAVIOR_CAPABILITY_MATRIX_FILE="docs/capability_matrix.json"
AI_BEHAVIOR_CAPABILITY_MATRIX_SHA="$(shasum -a 256 "$AI_BEHAVIOR_CAPABILITY_MATRIX_FILE" | awk '{print $1}')"
AI_BEHAVIOR_MISSING_ID_DIR="$TMP_DIR/ai-behavior-missing-id"
mkdir -p "$AI_BEHAVIOR_MISSING_ID_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_MISSING_ID_DIR/"
sed '1s/"id":"[^"]*",//' "$AI_BEHAVIOR_MISSING_ID_DIR/memory_recall.jsonl" > "$AI_BEHAVIOR_MISSING_ID_DIR/memory_recall.tmp"
mv "$AI_BEHAVIOR_MISSING_ID_DIR/memory_recall.tmp" "$AI_BEHAVIOR_MISSING_ID_DIR/memory_recall.jsonl"
expect_failure \
  "AI behavior eval rejects missing stable case id" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_MISSING_ID_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-missing-id.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-id.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-id.properties" "reason=missing-field:memory_recall:1:id"
AI_BEHAVIOR_DUPLICATE_ID_DIR="$TMP_DIR/ai-behavior-duplicate-id"
mkdir -p "$AI_BEHAVIOR_DUPLICATE_ID_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_DUPLICATE_ID_DIR/"
sed '2s/"id":"[^"]*"/"id":"memory_style_concise"/' "$AI_BEHAVIOR_DUPLICATE_ID_DIR/memory_recall.jsonl" > "$AI_BEHAVIOR_DUPLICATE_ID_DIR/memory_recall.tmp"
mv "$AI_BEHAVIOR_DUPLICATE_ID_DIR/memory_recall.tmp" "$AI_BEHAVIOR_DUPLICATE_ID_DIR/memory_recall.jsonl"
expect_failure \
  "AI behavior eval rejects duplicate stable case id" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_DUPLICATE_ID_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-duplicate-id.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-duplicate-id.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-duplicate-id.properties" "reason=duplicate-trace-case-id:memory_style_concise"
AI_BEHAVIOR_MISSING_REMOTE_CONFIRMATION_DIR="$TMP_DIR/ai-behavior-missing-remote-confirmation"
mkdir -p "$AI_BEHAVIOR_MISSING_REMOTE_CONFIRMATION_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_MISSING_REMOTE_CONFIRMATION_DIR/"
python3 - "$AI_BEHAVIOR_MISSING_REMOTE_CONFIRMATION_DIR/privacy_boundary.jsonl" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row["id"] == "privacy_remote_image_preview":
        row["expectedConfirmation"] = "none"
path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval requires remote send confirmation coverage" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_MISSING_REMOTE_CONFIRMATION_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-missing-remote-confirmation.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-remote-confirmation.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-remote-confirmation.properties" "reason=missing-confirmation-coverage:remote_send_confirmation"
AI_BEHAVIOR_REMOTE_CONFIRMATION_LOCALONLY_DIR="$TMP_DIR/ai-behavior-remote-confirmation-localonly"
mkdir -p "$AI_BEHAVIOR_REMOTE_CONFIRMATION_LOCALONLY_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_REMOTE_CONFIRMATION_LOCALONLY_DIR/"
python3 - "$AI_BEHAVIOR_REMOTE_CONFIRMATION_LOCALONLY_DIR/privacy_boundary.jsonl" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row["id"] == "privacy_remote_image_preview":
        row["privacy"] = "LocalOnly"
        row["localOnly"] = True
        row["remoteEligible"] = False
path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects remote confirmation on LocalOnly fixture boundary" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_REMOTE_CONFIRMATION_LOCALONLY_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-remote-confirmation-localonly.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-remote-confirmation-localonly.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-remote-confirmation-localonly.properties" "reason=remote-confirmation-privacy-mismatch:privacy_boundary:6"
AI_BEHAVIOR_MISSING_REAL_APP_FAILURE_DIR="$TMP_DIR/ai-behavior-missing-real-app-failure"
mkdir -p "$AI_BEHAVIOR_MISSING_REAL_APP_FAILURE_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_MISSING_REAL_APP_FAILURE_DIR/"
python3 - "$AI_BEHAVIOR_MISSING_REAL_APP_FAILURE_DIR/runtime_failure.jsonl" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row["id"] == "runtime_app_search_submit_not_found":
        row["allowedFailureModes"] = ["submit_failure_unclassified"]
path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval requires real app search failure mode coverage" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_MISSING_REAL_APP_FAILURE_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-missing-real-app-failure.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-real-app-failure.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-real-app-failure.properties" "reason=missing-real-app-search-failure-mode-coverage:submit_not_found"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-real-app-failure.properties" "missingRealAppSearchFailureModes=submit_not_found"
AI_BEHAVIOR_MISSING_PAGE_NOT_CHANGED_DIR="$TMP_DIR/ai-behavior-missing-page-not-changed"
mkdir -p "$AI_BEHAVIOR_MISSING_PAGE_NOT_CHANGED_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_MISSING_PAGE_NOT_CHANGED_DIR/"
python3 - "$AI_BEHAVIOR_MISSING_PAGE_NOT_CHANGED_DIR/runtime_failure.jsonl" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row["id"] == "runtime_app_search_page_not_changed":
        row["allowedFailureModes"] = ["unchanged_page_unclassified"]
path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval requires unchanged real app page coverage" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_MISSING_PAGE_NOT_CHANGED_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-missing-page-not-changed.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-page-not-changed.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-page-not-changed.properties" "reason=missing-real-app-search-failure-mode-coverage:page_not_changed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-page-not-changed.properties" "missingRealAppSearchFailureModes=page_not_changed"
AI_BEHAVIOR_MISSING_PERMISSION_DENIED_DIR="$TMP_DIR/ai-behavior-missing-permission-denied"
mkdir -p "$AI_BEHAVIOR_MISSING_PERMISSION_DENIED_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_MISSING_PERMISSION_DENIED_DIR/"
python3 - "$AI_BEHAVIOR_MISSING_PERMISSION_DENIED_DIR/runtime_failure.jsonl" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
rows = [
    row for row in rows
    if "permissiondenied" not in row.get("allowedFailureModes", [])
]
path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval requires runtime permission denial coverage" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_MISSING_PERMISSION_DENIED_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-missing-permission-denied.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-permission-denied.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-permission-denied.properties" "reason=missing-safety-failure-mode-coverage:permissiondenied"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-permission-denied.properties" "missingSafetyFailureModes=permissiondenied"
AI_BEHAVIOR_BAD_FAILURE_MODE_DIR="$TMP_DIR/ai-behavior-bad-failure-mode"
mkdir -p "$AI_BEHAVIOR_BAD_FAILURE_MODE_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_BAD_FAILURE_MODE_DIR/"
python3 - "$AI_BEHAVIOR_BAD_FAILURE_MODE_DIR/runtime_failure.jsonl" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0]["allowedFailureModes"] = ["bad mode with spaces"]
path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects invalid allowed failure mode taxonomy" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_BAD_FAILURE_MODE_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-bad-failure-mode.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-bad-failure-mode.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-bad-failure-mode.properties" "reason=invalid-field:runtime_failure:"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-bad-failure-mode.properties" ":allowedFailureModes"
AI_BEHAVIOR_BAD_EXPECTED_ROUTING_DIR="$TMP_DIR/ai-behavior-bad-expected-routing"
mkdir -p "$AI_BEHAVIOR_BAD_EXPECTED_ROUTING_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_BAD_EXPECTED_ROUTING_DIR/"
sed 's/"expectedRoutingPath":"no_action"/"expectedRoutingPath":"model guessed path"/' \
  "$AI_BEHAVIOR_BAD_EXPECTED_ROUTING_DIR/planner_false_positive.jsonl" > \
  "$AI_BEHAVIOR_BAD_EXPECTED_ROUTING_DIR/planner_false_positive.tmp"
mv "$AI_BEHAVIOR_BAD_EXPECTED_ROUTING_DIR/planner_false_positive.tmp" \
  "$AI_BEHAVIOR_BAD_EXPECTED_ROUTING_DIR/planner_false_positive.jsonl"
expect_failure \
  "AI behavior eval rejects invalid expected routing path taxonomy" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_BAD_EXPECTED_ROUTING_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-bad-expected-routing.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-bad-expected-routing.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-bad-expected-routing.properties" "reason=invalid-field:planner_false_positive:"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-bad-expected-routing.properties" ":expectedRoutingPath"
AI_BEHAVIOR_MISSING_HIGH_RISK_DIR="$TMP_DIR/ai-behavior-missing-high-risk"
mkdir -p "$AI_BEHAVIOR_MISSING_HIGH_RISK_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_MISSING_HIGH_RISK_DIR/"
python3 - "$AI_BEHAVIOR_MISSING_HIGH_RISK_DIR/tool_sequence.jsonl" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row["id"] == "sequence_public_summary_high_external_share":
        row["expectedRiskLevel"] = "medium"
path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval requires high-risk external-send coverage" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_MISSING_HIGH_RISK_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-missing-high-risk.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-high-risk.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-high-risk.properties" "reason=missing-risk-coverage:high"
AI_BEHAVIOR_MISSING_BOUNDARY_MATRIX="$TMP_DIR/capability-matrix-missing-behavior-boundaries.json"
python3 - "$AI_BEHAVIOR_MISSING_BOUNDARY_MATRIX" <<'PY'
import json
import pathlib
import sys

source = json.loads(pathlib.Path("docs/capability_matrix.json").read_text(encoding="utf-8"))
source.pop("requiredBehaviorEvalBoundaries", None)
pathlib.Path(sys.argv[1]).write_text(
    json.dumps(source, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects capability matrix without required boundary declarations" \
  scripts/verify_ai_behavior_eval.sh \
    --capability-matrix "$AI_BEHAVIOR_MISSING_BOUNDARY_MATRIX" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-missing-boundary-matrix.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-boundary-matrix.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-boundary-matrix.properties" "reason=invalid-required-behavior-eval-boundaries"
AI_BEHAVIOR_MISSING_PUBLIC_BATCH_DIR="$TMP_DIR/ai-behavior-missing-public-batch"
mkdir -p "$AI_BEHAVIOR_MISSING_PUBLIC_BATCH_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_MISSING_PUBLIC_BATCH_DIR/"
python3 - "$AI_BEHAVIOR_MISSING_PUBLIC_BATCH_DIR/privacy_boundary.jsonl" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row["id"] == "privacy_public_weather_batch_allowed":
        row["expectedBoundary"] = "public_evidence_single_search_allowed"
path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval requires public evidence multi-search batch coverage" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_MISSING_PUBLIC_BATCH_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-missing-public-batch.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-public-batch.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-missing-public-batch.properties" "reason=missing-required-boundary-coverage:public_evidence_multi_search_batch_allowed"
AI_BEHAVIOR_WEAK_PUBLIC_BATCH_DIR="$TMP_DIR/ai-behavior-weak-public-batch"
mkdir -p "$AI_BEHAVIOR_WEAK_PUBLIC_BATCH_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_WEAK_PUBLIC_BATCH_DIR/"
python3 - "$AI_BEHAVIOR_WEAK_PUBLIC_BATCH_DIR/privacy_boundary.jsonl" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row["id"] == "privacy_public_weather_batch_allowed":
        row["expectedConfirmation"] = "tool_confirmation"
path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects weak public evidence required boundary shape" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_WEAK_PUBLIC_BATCH_DIR" \
    --require-boundary-map \
    --report "$ARTIFACT_DIR/ai-behavior-weak-public-batch.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-weak-public-batch.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-weak-public-batch.properties" "reason=required-public-evidence-boundary-shape:public_evidence_multi_search_batch_allowed:privacy_boundary:4"
AI_TRACE_DIFF_MISSING="$ARTIFACT_DIR/ai-behavior-trace-diff-missing.jsonl"
expect_success \
  "AI behavior eval writes planning trace diff without actual trace" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --trace-diff "$AI_TRACE_DIFF_MISSING" \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "artifactSchema=AgentBehaviorEvalVerification/v1"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "owner=agent-behavior"
grep -Eq '^recordedAt=20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" ||
  fail "AI behavior eval report must include UTC recordedAt"
grep -q '^command=.*scripts/verify_ai_behavior_eval.sh.*--trace-diff ' "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" ||
  fail "AI behavior eval report must include reproducible command"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" \
  "reproduciblePath=$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "fixtureDir=app/src/test/resources/ai_behavior_eval"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "fixtureDirSha256=$AI_BEHAVIOR_FIXTURE_DIR_SHA"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "capabilityMatrixFile=$AI_BEHAVIOR_CAPABILITY_MATRIX_FILE"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "capabilityMatrixSha256=$AI_BEHAVIOR_CAPABILITY_MATRIX_SHA"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "actionModelsFile=$AI_BEHAVIOR_ACTION_MODELS_FILE"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "actionModelsSha256=$AI_BEHAVIOR_ACTION_MODELS_SHA"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "traceDiffFile=$AI_TRACE_DIFF_MISSING"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "traceDiffSha256=$(shasum -a 256 "$AI_TRACE_DIFF_MISSING" | awk '{print $1}')"
ai_trace_missing_count="$(awk -F= '$1 == "caseCount" {print $2; exit}' "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties")"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing.properties" "traceDiffMissingActualCount=$ai_trace_missing_count"
assert_report_contains_text "$AI_TRACE_DIFF_MISSING" '"actualTools": []'
assert_report_contains_text "$AI_TRACE_DIFF_MISSING" '"status": "missing_actual"'

AI_ACTUAL_TRACE="$TMP_DIR/ai-behavior-actual-trace.jsonl"
AI_TRACE_FRESH_RECORDED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
AI_TRACE_STALE_RECORDED_AT="2000-01-01T00:00:00Z"
AI_TRACE_FUTURE_RECORDED_AT="2099-01-01T00:00:00Z"
python3 - "$AI_ACTUAL_TRACE" "$AI_TRACE_FRESH_RECORDED_AT" "$AI_BEHAVIOR_FIXTURE_DIR_SHA" <<'PY'
import json
import pathlib
import sys

fixture_dir = pathlib.Path("app/src/test/resources/ai_behavior_eval")
categories = [
    "memory_recall",
    "planner_false_positive",
    "tool_sequence",
    "ocr_noise",
    "runtime_failure",
    "privacy_boundary",
    "restart_recovery",
]
out = pathlib.Path(sys.argv[1])
trace_recorded_at = sys.argv[2]
fixture_dir_sha256 = sys.argv[3]
with out.open("w", encoding="utf-8") as handle:
    for category in categories:
        path = fixture_dir / f"{category}.jsonl"
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            trace = {
                "caseId": row["id"],
                "category": category,
                "input": row["input"],
                "actualTools": row["expectedTools"],
                "actualConfirmation": row["expectedConfirmation"],
                "actualRiskLevel": row["expectedRiskLevel"],
                "privacy": row["privacy"],
                "localOnly": row["localOnly"],
                "remoteEligible": row["remoteEligible"],
                "traceRecordedAt": trace_recorded_at,
                "traceSource": "agent_loop_runtime",
                "fixtureDirSha256": fixture_dir_sha256,
            }
            if row.get("expectedPlacement"):
                trace["placementSelected"] = row["expectedPlacement"]
                trace["actualPlacementReason"] = row["expectedPlacementReason"]
                if row["expectedPlacement"] != "Blocked":
                    trace["invocationPlacement"] = row["expectedPlacement"]
                    trace["receiptDestination"] = row["expectedPlacement"]
            if row.get("expectedRoutingPath"):
                trace["routingPath"] = row["expectedRoutingPath"]
                if row.get("expectedRoutingToolName"):
                    trace["routingToolName"] = row["expectedRoutingToolName"]
                if row.get("expectedRoutingSkillId"):
                    trace["routingSkillId"] = row["expectedRoutingSkillId"]
                if row.get("expectedRoutingRejectionReason"):
                    trace["routingRejectionReason"] = row["expectedRoutingRejectionReason"]
            elif row["expectedTools"]:
                trace["routingPath"] = "action_planner"
                trace["routingToolName"] = row["expectedTools"][0]
            else:
                trace["routingPath"] = "no_action"
                trace["routingRejectionReason"] = "no_action_intent_detected"
            if row["expectedConfirmation"] == "fail_closed":
                trace["failureMode"] = row["allowedFailureModes"][0]
            handle.write(json.dumps(trace, ensure_ascii=False, sort_keys=True) + "\n")
PY
AI_ACTUAL_TRACE_SHA="$(shasum -a 256 "$AI_ACTUAL_TRACE" | awk '{print $1}')"

AI_PLACEMENT_FIXTURE_DIR="$TMP_DIR/ai-behavior-placement-fixtures"
mkdir -p "$AI_PLACEMENT_FIXTURE_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_PLACEMENT_FIXTURE_DIR/"
python3 - "$AI_PLACEMENT_FIXTURE_DIR/privacy_boundary.jsonl" <<'PY'
import json
from pathlib import Path
import sys

path = Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0]["expectedPlacement"] = "Local"
rows[0]["expectedPlacementReason"] = "LOCAL_ONLY"
rows[2]["expectedPlacement"] = "Local"
rows[2]["expectedPlacementReason"] = "MANUAL_LOCAL"
path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
AI_PLACEMENT_FIXTURE_SHA="$(fixture_dir_sha256 "$AI_PLACEMENT_FIXTURE_DIR")"
AI_PLACEMENT_EXPECTATION_COUNT="$(python3 - "$AI_PLACEMENT_FIXTURE_DIR" <<'PY'
import json
from pathlib import Path
import sys

count = 0
for fixture_path in Path(sys.argv[1]).glob("*.jsonl"):
    count += sum(
        1
        for line in fixture_path.read_text(encoding="utf-8").splitlines()
        if line.strip() and json.loads(line).get("expectedPlacement")
    )
print(count)
PY
)"
expect_failure \
  "AI behavior placement reconciliation requires an actual trace" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_PLACEMENT_FIXTURE_DIR" \
    --require-boundary-map \
    --require-placement-reconciliation \
    --report "$ARTIFACT_DIR/ai-behavior-placement-pending.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-placement-pending.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-placement-pending.properties" "reason=placement-reconciliation-requires-actual-trace"

AI_PLACEMENT_ACTUAL_TRACE="$TMP_DIR/ai-behavior-placement-actual-trace.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_PLACEMENT_ACTUAL_TRACE" "$AI_PLACEMENT_FIXTURE_SHA" "$AI_PLACEMENT_FIXTURE_DIR" <<'PY'
import json
from pathlib import Path
import sys

source = Path(sys.argv[1])
target = Path(sys.argv[2])
fixture_sha = sys.argv[3]
fixture_dir = Path(sys.argv[4])
placement_expectations = {}
for fixture_path in fixture_dir.glob("*.jsonl"):
    for line in fixture_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        fixture = json.loads(line)
        if fixture.get("expectedPlacement"):
            placement_expectations[fixture["id"]] = (
                fixture["expectedPlacement"],
                fixture["expectedPlacementReason"],
            )
rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    row["fixtureDirSha256"] = fixture_sha
    expectation = placement_expectations.get(row["caseId"])
    if expectation:
        placement, reason = expectation
        row.update(
            placementSelected=placement,
            actualPlacementReason=reason,
        )
        if placement != "Blocked":
            row.update(invocationPlacement=placement, receiptDestination=placement)
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_success \
  "AI behavior eval reconciles placement selection invocation and receipt" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_PLACEMENT_FIXTURE_DIR" \
    --require-boundary-map \
    --actual-trace "$AI_PLACEMENT_ACTUAL_TRACE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-placement-matched.jsonl" \
    --require-actual-trace \
    --require-placement-reconciliation \
    --report "$ARTIFACT_DIR/ai-behavior-placement-matched.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-placement-matched.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-placement-matched.properties" "requirePlacementReconciliation=1"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-placement-matched.properties" "placementExpectationCount=$AI_PLACEMENT_EXPECTATION_COUNT"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-placement-matched.properties" "placementReconciledCount=$AI_PLACEMENT_EXPECTATION_COUNT"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-placement-matched.jsonl" '"placementReconciliationMatches": true'

AI_PLACEMENT_EXPECTED_MISMATCH="$TMP_DIR/ai-behavior-placement-expected-mismatch.jsonl"
python3 - "$AI_PLACEMENT_ACTUAL_TRACE" "$AI_PLACEMENT_EXPECTED_MISMATCH" <<'PY'
import json
from pathlib import Path
import sys

rows = [json.loads(line) for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row["caseId"] == "privacy_public_weather_allowed":
        row["placementSelected"] = "Remote"
        row["invocationPlacement"] = "Remote"
        row["receiptDestination"] = "Remote"
Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects expected placement and invocation mismatch" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_PLACEMENT_FIXTURE_DIR" \
    --require-boundary-map \
    --actual-trace "$AI_PLACEMENT_EXPECTED_MISMATCH" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-placement-expected-mismatch.jsonl" \
    --require-actual-trace \
    --require-placement-reconciliation \
    --report "$ARTIFACT_DIR/ai-behavior-placement-expected-mismatch.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-placement-expected-mismatch.properties" "reason=trace-diff-mismatch"

AI_PLACEMENT_RECEIPT_MISMATCH="$TMP_DIR/ai-behavior-placement-receipt-mismatch.jsonl"
python3 - "$AI_PLACEMENT_ACTUAL_TRACE" "$AI_PLACEMENT_RECEIPT_MISMATCH" <<'PY'
import json
from pathlib import Path
import sys

rows = [json.loads(line) for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row["caseId"] == "privacy_screen_text_remote_block":
        row["receiptDestination"] = "Remote"
Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects invocation and receipt mismatch" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_PLACEMENT_FIXTURE_DIR" \
    --require-boundary-map \
    --actual-trace "$AI_PLACEMENT_RECEIPT_MISMATCH" \
    --require-actual-trace \
    --require-placement-reconciliation \
    --report "$ARTIFACT_DIR/ai-behavior-placement-receipt-mismatch.properties"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-placement-receipt-mismatch.properties" "reason=actual-trace-invocation-receipt-mismatch:"

AI_PLACEMENT_LOCALONLY_REMOTE="$TMP_DIR/ai-behavior-placement-localonly-remote.jsonl"
python3 - "$AI_PLACEMENT_ACTUAL_TRACE" "$AI_PLACEMENT_LOCALONLY_REMOTE" <<'PY'
import json
from pathlib import Path
import sys

rows = [json.loads(line) for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row["caseId"] == "privacy_screen_text_remote_block":
        row["placementSelected"] = "Remote"
        row["invocationPlacement"] = "Remote"
        row["receiptDestination"] = "Remote"
Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects LocalOnly remote invocation" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_PLACEMENT_FIXTURE_DIR" \
    --require-boundary-map \
    --actual-trace "$AI_PLACEMENT_LOCALONLY_REMOTE" \
    --require-actual-trace \
    --require-placement-reconciliation \
    --report "$ARTIFACT_DIR/ai-behavior-placement-localonly-remote.properties"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-placement-localonly-remote.properties" "reason=actual-trace-localonly-remote-invocation:"
expect_failure \
  "AI behavior eval rejects LocalOnly remote serving fields even without placement reconciliation mode" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_PLACEMENT_FIXTURE_DIR" \
    --require-boundary-map \
    --actual-trace "$AI_PLACEMENT_LOCALONLY_REMOTE" \
    --require-actual-trace \
    --report "$ARTIFACT_DIR/ai-behavior-placement-localonly-remote-unflagged.properties"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-placement-localonly-remote-unflagged.properties" "reason=actual-trace-localonly-remote-invocation:"

AI_ACTUAL_TRACE_BAD_FIXTURE_SHA="$TMP_DIR/ai-behavior-actual-trace-bad-fixture-sha.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_ACTUAL_TRACE_BAD_FIXTURE_SHA" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0]["fixtureDirSha256"] = "0" * 64
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects actual trace bound to a different fixture set" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE_BAD_FIXTURE_SHA" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-bad-fixture-sha.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-bad-fixture-sha.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-bad-fixture-sha.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-bad-fixture-sha.properties" "reason=actual-trace-fixture-sha-mismatch:1:memory_style_concise"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-bad-fixture-sha.properties" "actualTraceFile=$AI_ACTUAL_TRACE_BAD_FIXTURE_SHA"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-bad-fixture-sha.properties" "fixtureDirSha256=$AI_BEHAVIOR_FIXTURE_DIR_SHA"
AI_ACTUAL_TRACE_MISSING_SOURCE="$TMP_DIR/ai-behavior-actual-trace-missing-source.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_ACTUAL_TRACE_MISSING_SOURCE" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0].pop("traceSource", None)
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects missing runtime trace source in strict provenance mode" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE_MISSING_SOURCE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-source.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-source.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-source.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-source.properties" "reason=invalid-actual-trace:1:traceSource"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-source.properties" "requireRuntimeTraceSource=1"
AI_ACTUAL_TRACE_MIXED_SOURCE="$TMP_DIR/ai-behavior-actual-trace-mixed-source.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_ACTUAL_TRACE_MIXED_SOURCE" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0]["traceSource"] = "device_debug_eval"
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects non-agent-loop actual trace source in public provenance mode" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE_MIXED_SOURCE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-mixed-source-strict.jsonl" \
    --require-actual-trace \
    --require-agent-loop-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-mixed-source-strict.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-mixed-source-strict.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-mixed-source-strict.properties" "reason=actual-trace-non-agent-loop-runtime-source"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-mixed-source-strict.properties" "requireRuntimeTraceSource=1"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-mixed-source-strict.properties" "requireAgentLoopRuntimeTraceSource=1"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-mixed-source-strict.properties" "actualTraceNonAgentLoopRuntimeSourceCount=1"

AI_ACTUAL_TRACE_STALE="$TMP_DIR/ai-behavior-actual-trace-stale.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_ACTUAL_TRACE_STALE" "$AI_TRACE_STALE_RECORDED_AT" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    row["traceRecordedAt"] = sys.argv[3]
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects stale actual trace in strict provenance mode" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE_STALE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-stale.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-stale.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-stale.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-stale.properties" "reason=actual-trace-recordedAt-stale:1"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-stale.properties" "actualTraceMaxAgeDays=30"

AI_ACTUAL_TRACE_FUTURE="$TMP_DIR/ai-behavior-actual-trace-future.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_ACTUAL_TRACE_FUTURE" "$AI_TRACE_FUTURE_RECORDED_AT" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0]["traceRecordedAt"] = sys.argv[3]
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval preserves future actual trace rejection" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE_FUTURE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-future.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-future.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-future.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-future.properties" "reason=actual-trace-recordedAt-future:1"

AI_ACTUAL_TRACE_MISSING_CASE_ID="$TMP_DIR/ai-behavior-actual-trace-missing-case-id.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_ACTUAL_TRACE_MISSING_CASE_ID" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0].pop("caseId", None)
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects missing actual trace case id in strict mode" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE_MISSING_CASE_ID" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-case-id.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-case-id.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-case-id.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-case-id.properties" "reason=invalid-actual-trace:1:caseId"

AI_ACTUAL_TRACE_UNKNOWN_CASE_ID="$TMP_DIR/ai-behavior-actual-trace-unknown-case-id.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_ACTUAL_TRACE_UNKNOWN_CASE_ID" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0]["caseId"] = "unknown_case_from_runtime"
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects unknown actual trace case id in strict mode" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE_UNKNOWN_CASE_ID" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-unknown-case-id.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-unknown-case-id.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-unknown-case-id.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-unknown-case-id.properties" "reason=actual-trace-unknown-case-id:1:unknown_case_from_runtime"

AI_ACTUAL_TRACE_WRONG_CATEGORY="$TMP_DIR/ai-behavior-actual-trace-wrong-category.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_ACTUAL_TRACE_WRONG_CATEGORY" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0]["category"] = "runtime_failure"
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects actual trace category drift for known case id" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE_WRONG_CATEGORY" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-wrong-category.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-wrong-category.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-wrong-category.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-wrong-category.properties" "reason=actual-trace-category-mismatch:1:memory_style_concise"

AI_ACTUAL_TRACE_WRONG_INPUT="$TMP_DIR/ai-behavior-actual-trace-wrong-input.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_ACTUAL_TRACE_WRONG_INPUT" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0]["input"] = "wrong input for the same case id"
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects actual trace input drift for known case id" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE_WRONG_INPUT" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-wrong-input.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-wrong-input.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-wrong-input.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-wrong-input.properties" "reason=actual-trace-input-mismatch:1:memory_style_concise"

AI_ACTUAL_TRACE_MISSING_FAILURE_MODE="$TMP_DIR/ai-behavior-actual-trace-missing-failure-mode.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_ACTUAL_TRACE_MISSING_FAILURE_MODE" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row.get("actualConfirmation") == "fail_closed":
        row.pop("failureMode", None)
        break
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects fail-closed actual trace without failure mode" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE_MISSING_FAILURE_MODE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-failure-mode.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-failure-mode.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-failure-mode.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-failure-mode.properties" "reason=trace-diff-missing-required-failure-mode"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-missing-failure-mode.properties" "actualTraceMissingRequiredFailureModeCount=1"

AI_ACTUAL_TRACE_UNEXPECTED_FAILURE_MODE="$TMP_DIR/ai-behavior-actual-trace-unexpected-failure-mode.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_ACTUAL_TRACE_UNEXPECTED_FAILURE_MODE" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0]["failureMode"] = "unexpected_silent_failure"
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects unexpected actual failure mode on otherwise matched trace" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE_UNEXPECTED_FAILURE_MODE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-unexpected-failure-mode.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-unexpected-failure-mode.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-unexpected-failure-mode.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-unexpected-failure-mode.properties" "reason=trace-diff-mismatch"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-unexpected-failure-mode.properties" "traceDiffMismatchCount=1"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-trace-diff-unexpected-failure-mode.jsonl" '"actualFailureModeAccepted": false'

AI_TRACE_DIFF_MATCHED="$ARTIFACT_DIR/ai-behavior-trace-diff-matched.jsonl"
expect_success \
  "AI behavior eval accepts matching planning trace diff" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE" \
    --trace-diff "$AI_TRACE_DIFF_MATCHED" \
    --require-actual-trace \
    --require-agent-loop-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "actualTraceFile=$AI_ACTUAL_TRACE"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "actualTraceSha256=$AI_ACTUAL_TRACE_SHA"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "requireRuntimeTraceSource=1"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "requireAgentLoopRuntimeTraceSource=1"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "actualTraceMaxAgeDays=30"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "actualTraceSourceBreakdown=agent_loop_runtime:$ai_trace_missing_count"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "actualTraceNonAgentLoopRuntimeSourceCount=0"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "traceDiffSha256=$(shasum -a 256 "$AI_TRACE_DIFF_MATCHED" | awk '{print $1}')"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "traceDiffMissingActualCount=0"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties" "traceDiffMismatchCount=0"
assert_report_contains_text "$AI_TRACE_DIFF_MATCHED" '"caseId": "memory_style_concise"'
assert_report_contains_text "$AI_TRACE_DIFF_MATCHED" '"status": "matched"'
assert_report_contains_text "$AI_TRACE_DIFF_MATCHED" '"actualRoutingPath": "no_action"'
assert_report_contains_text "$AI_TRACE_DIFF_MATCHED" '"actualRoutingPath": "action_planner"'
assert_report_contains_text "$AI_TRACE_DIFF_MATCHED" '"actualTraceSource": "agent_loop_runtime"'
assert_report_contains_text "$AI_TRACE_DIFF_MATCHED" "\"actualTraceRecordedAt\": \"$AI_TRACE_FRESH_RECORDED_AT\""
ai_trace_allowed_count="$(awk -F= '$1 == "traceDiffAllowedFailureCount" {print $2; exit}' "$ARTIFACT_DIR/ai-behavior-trace-diff-matched.properties")"
if [[ "${ai_trace_allowed_count:-0}" != "0" ]]; then
  fail "AI behavior fixture trace should classify expected fail-closed outcomes as matched, not allowed_failure"
fi
expect_success \
  "AI behavior eval accepts matched fail-closed outcomes when strict release mode rejects only unresolved allowed failures" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_ACTUAL_TRACE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-strict.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --reject-allowed-failures \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-strict.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-strict.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-strict.properties" "reason="
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-strict.properties" "traceDiffAllowedFailureCount=$ai_trace_allowed_count"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-strict.properties" "rejectAllowedFailures=1"

AI_HIGH_RISK_DRIFT_TRACE="$TMP_DIR/ai-behavior-actual-trace-high-risk-drift.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_HIGH_RISK_DRIFT_TRACE" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row.get("caseId") == "sequence_public_summary_high_external_share":
        row["actualRiskLevel"] = "medium"
        break
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects high-risk actual trace downgraded to medium" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_HIGH_RISK_DRIFT_TRACE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-high-risk-drift.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-high-risk-drift.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-high-risk-drift.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-high-risk-drift.properties" "reason=trace-diff-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-trace-diff-high-risk-drift.jsonl" '"caseId": "sequence_public_summary_high_external_share"'
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-trace-diff-high-risk-drift.jsonl" '"expectedRiskLevel": "high"'
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-trace-diff-high-risk-drift.jsonl" '"actualRiskLevel": "medium"'
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-trace-diff-high-risk-drift.jsonl" '"riskMatches": false'

AI_UNEXPECTED_ROUTING_TRACE="$TMP_DIR/ai-behavior-actual-trace-unexpected-routing.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_UNEXPECTED_ROUTING_TRACE" <<'PY'
import json
import pathlib
import sys

rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row.get("caseId") == "planner_explain_wifi_api":
        row["routingPath"] = "action_planner"
        row["routingToolName"] = "open_wifi_settings"
        row.pop("routingRejectionReason", None)
        break
pathlib.Path(sys.argv[2]).write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects route-sensitive false positive actual trace" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_UNEXPECTED_ROUTING_TRACE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-unexpected-routing.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-unexpected-routing.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-unexpected-routing.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-unexpected-routing.properties" "reason=trace-diff-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-trace-diff-unexpected-routing.jsonl" '"caseId": "planner_explain_wifi_api"'
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-trace-diff-unexpected-routing.jsonl" '"expectedRoutingPath": "no_action"'
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-trace-diff-unexpected-routing.jsonl" '"actualRoutingPath": "action_planner"'
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-trace-diff-unexpected-routing.jsonl" '"routingExpectationMatches": false'

AI_BAD_ROUTING_TRACE="$TMP_DIR/ai-behavior-actual-trace-bad-routing.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_BAD_ROUTING_TRACE" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0]["routingPath"] = "model guessed path"
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects invalid routing evidence" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_BAD_ROUTING_TRACE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-bad-routing.jsonl" \
    --require-actual-trace \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-bad-routing.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-bad-routing.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-bad-routing.properties" "reason=invalid-actual-trace:1:routingPath"
AI_NO_ACTION_WITH_TOOL_TRACE="$TMP_DIR/ai-behavior-actual-trace-no-action-with-tool.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_NO_ACTION_WITH_TOOL_TRACE" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row.get("actualTools"):
        row["routingPath"] = "no_action"
        row["routingRejectionReason"] = "no_action_intent_detected"
        break
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects no-action routing with actual tool evidence" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_NO_ACTION_WITH_TOOL_TRACE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-no-action-with-tool.jsonl" \
    --require-actual-trace \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-no-action-with-tool.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-no-action-with-tool.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-trace-diff-no-action-with-tool.properties" "reason=actual-trace-routing-conflict:"

AI_REMOTE_CONFIRMATION_LOCALONLY_TRACE="$TMP_DIR/ai-behavior-actual-trace-remote-confirmation-localonly.jsonl"
python3 - "$AI_ACTUAL_TRACE" "$AI_REMOTE_CONFIRMATION_LOCALONLY_TRACE" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    if row.get("actualConfirmation") == "remote_send_confirmation":
        row["privacy"] = "LocalOnly"
        row["localOnly"] = True
        row["remoteEligible"] = False
        break
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects actual remote confirmation on LocalOnly boundary" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_REMOTE_CONFIRMATION_LOCALONLY_TRACE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-remote-confirmation-localonly.jsonl" \
    --require-actual-trace \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-remote-confirmation-localonly.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-remote-confirmation-localonly.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-trace-diff-remote-confirmation-localonly.properties" "reason=actual-trace-remote-confirmation-privacy-mismatch:"

AI_BEHAVIOR_ALLOWED_FAILURE_TOOL_DIR="$TMP_DIR/ai-behavior-allowed-failure-tool"
AI_ALLOWED_FAILURE_TOOL_TRACE="$TMP_DIR/ai-behavior-actual-trace-allowed-failure-tool.jsonl"
AI_ALLOWED_FAILURE_TOOL_DIFF="$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-tool.jsonl"
AI_ALLOWED_FAILURE_TOOL_MODE="allowed_script_tool_drift"
mkdir -p "$AI_BEHAVIOR_ALLOWED_FAILURE_TOOL_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_ALLOWED_FAILURE_TOOL_DIR/"
python3 - "$AI_BEHAVIOR_ALLOWED_FAILURE_TOOL_DIR" "$AI_ACTUAL_TRACE" "$AI_ALLOWED_FAILURE_TOOL_TRACE" "$AI_ALLOWED_FAILURE_TOOL_MODE" <<'PY'
import hashlib
import json
import pathlib
import sys

fixture_dir = pathlib.Path(sys.argv[1])
source = pathlib.Path(sys.argv[2])
target = pathlib.Path(sys.argv[3])
mode = sys.argv[4]

fixture_path = fixture_dir / "memory_recall.jsonl"
fixture_rows = [json.loads(line) for line in fixture_path.read_text(encoding="utf-8").splitlines() if line.strip()]
fixture_rows[0]["allowedFailureModes"] = [mode]
fixture_path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in fixture_rows),
    encoding="utf-8",
)
fixture_hash = hashlib.sha256()
for path in sorted(fixture_dir.glob("*.jsonl"), key=lambda item: item.name):
    fixture_hash.update(f"{hashlib.sha256(path.read_bytes()).hexdigest()} {path.name}\n".encode("utf-8"))
fixture_dir_sha256 = fixture_hash.hexdigest()

trace_rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in trace_rows:
    row["fixtureDirSha256"] = fixture_dir_sha256
trace_rows[0]["actualTools"] = ["web_search"]
trace_rows[0]["routingPath"] = "action_planner"
trace_rows[0]["routingToolName"] = "web_search"
trace_rows[0].pop("routingRejectionReason", None)
trace_rows[0]["failureMode"] = mode
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in trace_rows),
    encoding="utf-8",
)
PY
expect_success \
  "AI behavior eval records non-fail-closed allowed failure as a local/debug downgrade" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_ALLOWED_FAILURE_TOOL_DIR" \
    --require-boundary-map \
    --actual-trace "$AI_ALLOWED_FAILURE_TOOL_TRACE" \
    --trace-diff "$AI_ALLOWED_FAILURE_TOOL_DIFF" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-tool.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-tool.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-tool.properties" "traceDiffAllowedFailureCount=1"
assert_report_contains_text "$AI_ALLOWED_FAILURE_TOOL_DIFF" '"status": "allowed_failure"'
expect_failure \
  "AI behavior eval rejects unresolved allowed failures in public strict mode" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_ALLOWED_FAILURE_TOOL_DIR" \
    --require-boundary-map \
    --actual-trace "$AI_ALLOWED_FAILURE_TOOL_TRACE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-tool-strict.jsonl" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --reject-allowed-failures \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-tool-strict.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-tool-strict.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-tool-strict.properties" "reason=trace-diff-allowed-failure"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-tool-strict.properties" "traceDiffAllowedFailureCount=1"

AI_BEHAVIOR_ALLOWED_FAILURE_SAFETY_DIR="$TMP_DIR/ai-behavior-allowed-failure-safety"
AI_ALLOWED_FAILURE_SAFETY_TRACE="$TMP_DIR/ai-behavior-actual-trace-allowed-failure-safety.jsonl"
AI_ALLOWED_FAILURE_SAFETY_DIFF="$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-safety.jsonl"
AI_ALLOWED_FAILURE_SAFETY_MODE="allowed_script_safety_drift"
mkdir -p "$AI_BEHAVIOR_ALLOWED_FAILURE_SAFETY_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_ALLOWED_FAILURE_SAFETY_DIR/"
python3 - "$AI_BEHAVIOR_ALLOWED_FAILURE_SAFETY_DIR" "$AI_ACTUAL_TRACE" "$AI_ALLOWED_FAILURE_SAFETY_TRACE" "$AI_ALLOWED_FAILURE_SAFETY_MODE" <<'PY'
import hashlib
import json
import pathlib
import sys

fixture_dir = pathlib.Path(sys.argv[1])
source = pathlib.Path(sys.argv[2])
target = pathlib.Path(sys.argv[3])
mode = sys.argv[4]

fixture_path = fixture_dir / "memory_recall.jsonl"
fixture_rows = [json.loads(line) for line in fixture_path.read_text(encoding="utf-8").splitlines() if line.strip()]
fixture_rows[0]["allowedFailureModes"] = [mode]
fixture_path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in fixture_rows),
    encoding="utf-8",
)
fixture_hash = hashlib.sha256()
for path in sorted(fixture_dir.glob("*.jsonl"), key=lambda item: item.name):
    fixture_hash.update(f"{hashlib.sha256(path.read_bytes()).hexdigest()} {path.name}\n".encode("utf-8"))
fixture_dir_sha256 = fixture_hash.hexdigest()

trace_rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in trace_rows:
    row["fixtureDirSha256"] = fixture_dir_sha256
trace_rows[0]["failureMode"] = mode
trace_rows[0]["actualRiskLevel"] = "sensitive" if trace_rows[0]["actualRiskLevel"] != "sensitive" else "low"
if trace_rows[0]["privacy"] == "LocalOnly":
    trace_rows[0]["privacy"] = "RemoteEligible"
    trace_rows[0]["localOnly"] = False
    trace_rows[0]["remoteEligible"] = True
else:
    trace_rows[0]["privacy"] = "LocalOnly"
    trace_rows[0]["localOnly"] = True
    trace_rows[0]["remoteEligible"] = False
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in trace_rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects allowed failure with safety boundary drift" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_ALLOWED_FAILURE_SAFETY_DIR" \
    --require-boundary-map \
    --actual-trace "$AI_ALLOWED_FAILURE_SAFETY_TRACE" \
    --trace-diff "$AI_ALLOWED_FAILURE_SAFETY_DIFF" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-safety.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-safety.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-safety.properties" "reason=trace-diff-mismatch"
assert_report_contains_text "$AI_ALLOWED_FAILURE_SAFETY_DIFF" '"actualFailureMode": "allowed_script_safety_drift"'
assert_report_contains_text "$AI_ALLOWED_FAILURE_SAFETY_DIFF" '"status": "mismatch"'

AI_BEHAVIOR_ALLOWED_FAILURE_FAIL_CLOSED_DIR="$TMP_DIR/ai-behavior-allowed-failure-fail-closed"
AI_ALLOWED_FAILURE_FAIL_CLOSED_TRACE="$TMP_DIR/ai-behavior-actual-trace-allowed-failure-fail-closed.jsonl"
AI_ALLOWED_FAILURE_FAIL_CLOSED_DIFF="$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-fail-closed.jsonl"
AI_ALLOWED_FAILURE_FAIL_CLOSED_MODE="allowed_script_fail_closed_drift"
mkdir -p "$AI_BEHAVIOR_ALLOWED_FAILURE_FAIL_CLOSED_DIR"
cp app/src/test/resources/ai_behavior_eval/*.jsonl "$AI_BEHAVIOR_ALLOWED_FAILURE_FAIL_CLOSED_DIR/"
python3 - "$AI_BEHAVIOR_ALLOWED_FAILURE_FAIL_CLOSED_DIR" "$AI_ACTUAL_TRACE" "$AI_ALLOWED_FAILURE_FAIL_CLOSED_TRACE" "$AI_ALLOWED_FAILURE_FAIL_CLOSED_MODE" <<'PY'
import hashlib
import json
import pathlib
import sys

fixture_dir = pathlib.Path(sys.argv[1])
source = pathlib.Path(sys.argv[2])
target = pathlib.Path(sys.argv[3])
mode = sys.argv[4]

fixture_path = fixture_dir / "memory_recall.jsonl"
fixture_rows = [json.loads(line) for line in fixture_path.read_text(encoding="utf-8").splitlines() if line.strip()]
fixture_rows[0]["expectedConfirmation"] = "fail_closed"
fixture_rows[0]["allowedFailureModes"] = [mode]
fixture_path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in fixture_rows),
    encoding="utf-8",
)
fixture_hash = hashlib.sha256()
for path in sorted(fixture_dir.glob("*.jsonl"), key=lambda item: item.name):
    fixture_hash.update(f"{hashlib.sha256(path.read_bytes()).hexdigest()} {path.name}\n".encode("utf-8"))
fixture_dir_sha256 = fixture_hash.hexdigest()

trace_rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
for row in trace_rows:
    row["fixtureDirSha256"] = fixture_dir_sha256
trace_rows[0]["actualConfirmation"] = "none"
trace_rows[0]["failureMode"] = mode
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in trace_rows),
    encoding="utf-8",
)
PY
expect_failure \
  "AI behavior eval rejects allowed failure that weakens fail closed" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_BEHAVIOR_ALLOWED_FAILURE_FAIL_CLOSED_DIR" \
    --require-boundary-map \
    --actual-trace "$AI_ALLOWED_FAILURE_FAIL_CLOSED_TRACE" \
    --trace-diff "$AI_ALLOWED_FAILURE_FAIL_CLOSED_DIFF" \
    --require-actual-trace \
    --require-runtime-trace-source \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-fail-closed.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-fail-closed.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-allowed-failure-fail-closed.properties" "reason=trace-diff-mismatch"
assert_report_contains_text "$AI_ALLOWED_FAILURE_FAIL_CLOSED_DIFF" '"actualFailureMode": "allowed_script_fail_closed_drift"'
assert_report_contains_text "$AI_ALLOWED_FAILURE_FAIL_CLOSED_DIFF" '"status": "mismatch"'

reset_logs
AI_COLLECTOR_DIR="$ARTIFACT_DIR/ai-behavior-actual-trace-collector"
expect_success \
  "AI behavior actual trace collector records runtime trace evidence" \
  env ARTIFACT_DIR="$AI_COLLECTOR_DIR" \
  GRADLE_CMD="$FAKE_GRADLE" \
  AI_BEHAVIOR_FIXTURE_DIR="$AI_PLACEMENT_FIXTURE_DIR" \
  FAKE_AI_BEHAVIOR_ACTUAL_TRACE_SOURCE="$AI_PLACEMENT_ACTUAL_TRACE" \
  scripts/collect_ai_behavior_actual_trace.sh
grep -q -- ":app:testDebugUnitTest --tests com.bytedance.zgx.solin.eval.AiBehaviorActualTraceGeneratorTest" "$FAKE_GRADLE_LOG" ||
  fail "AI behavior actual trace collector must run the generator test"
AI_COLLECTOR_REPORT="$AI_COLLECTOR_DIR/ai-behavior-actual-trace-collection.properties"
AI_COLLECTOR_TRACE="$AI_COLLECTOR_DIR/ai-behavior-actual-trace.jsonl"
AI_COLLECTOR_DIFF="$AI_COLLECTOR_DIR/ai-behavior-planning-trace-diff.jsonl"
AI_COLLECTOR_EVAL="$AI_COLLECTOR_DIR/ai-behavior-eval.properties"
assert_report_contains "$AI_COLLECTOR_REPORT" "status=passed"
assert_report_contains "$AI_COLLECTOR_REPORT" "artifactSchema=AgentBehaviorActualTraceCollection/v1"
assert_report_contains "$AI_COLLECTOR_REPORT" "owner=agent-behavior"
assert_report_contains "$AI_COLLECTOR_REPORT" "actualTraceFile=$AI_COLLECTOR_TRACE"
assert_report_contains "$AI_COLLECTOR_REPORT" "actualTraceSha256=$(shasum -a 256 "$AI_COLLECTOR_TRACE" | awk '{print $1}')"
assert_report_contains "$AI_COLLECTOR_REPORT" "traceDiffFile=$AI_COLLECTOR_DIFF"
assert_report_contains "$AI_COLLECTOR_REPORT" "traceDiffSha256=$(shasum -a 256 "$AI_COLLECTOR_DIFF" | awk '{print $1}')"
assert_report_contains "$AI_COLLECTOR_REPORT" "evalReportFile=$AI_COLLECTOR_EVAL"
assert_report_contains "$AI_COLLECTOR_REPORT" "evalReportSha256=$(shasum -a 256 "$AI_COLLECTOR_EVAL" | awk '{print $1}')"
assert_report_contains "$AI_COLLECTOR_REPORT" "evalFixtureDir=$AI_PLACEMENT_FIXTURE_DIR"
assert_report_contains "$AI_COLLECTOR_REPORT" "evalFixtureDirSha256=$AI_PLACEMENT_FIXTURE_SHA"
assert_report_contains "$AI_COLLECTOR_REPORT" "evalCapabilityMatrixFile=$AI_BEHAVIOR_CAPABILITY_MATRIX_FILE"
assert_report_contains "$AI_COLLECTOR_REPORT" "evalCapabilityMatrixSha256=$AI_BEHAVIOR_CAPABILITY_MATRIX_SHA"
assert_report_contains "$AI_COLLECTOR_REPORT" "evalActionModelsFile=$AI_BEHAVIOR_ACTION_MODELS_FILE"
assert_report_contains "$AI_COLLECTOR_REPORT" "evalActionModelsSha256=$AI_BEHAVIOR_ACTION_MODELS_SHA"
assert_report_contains "$AI_COLLECTOR_REPORT" "evalRequireActualTrace=1"
assert_report_contains "$AI_COLLECTOR_REPORT" "evalRequireRuntimeTraceSource=1"
assert_report_contains "$AI_COLLECTOR_REPORT" "evalRequireAgentLoopRuntimeTraceSource=1"
assert_report_contains "$AI_COLLECTOR_REPORT" "evalRequirePlacementReconciliation=1"
assert_report_contains "$AI_COLLECTOR_REPORT" "evalRejectAllowedFailures=0"
assert_report_contains "$AI_COLLECTOR_REPORT" "publicReleaseContext=0"
assert_report_contains "$AI_COLLECTOR_REPORT" "rejectAllowedFailures=0"
assert_report_contains "$AI_COLLECTOR_REPORT" "evalActualTraceMaxAgeDays=30"
assert_report_contains "$AI_COLLECTOR_REPORT" "traceDiffMissingActualCount=0"
assert_report_contains "$AI_COLLECTOR_REPORT" "traceDiffExtraActualCount=0"
assert_report_contains "$AI_COLLECTOR_REPORT" "actualTraceSourceBreakdown=agent_loop_runtime:$ai_trace_missing_count"
assert_report_contains "$AI_COLLECTOR_EVAL" "requireRuntimeTraceSource=1"
assert_report_contains "$AI_COLLECTOR_EVAL" "requireAgentLoopRuntimeTraceSource=1"
assert_report_contains "$AI_COLLECTOR_EVAL" "actualTraceNonAgentLoopRuntimeSourceCount=0"
assert_report_contains_text "$AI_COLLECTOR_DIFF" '"status": "matched"'

reset_logs
AI_STRICT_COLLECTOR_DIR="$ARTIFACT_DIR/ai-behavior-actual-trace-collector-strict"
expect_success \
  "AI behavior actual trace collector supports public strict allowed-failure rejection" \
  env ARTIFACT_DIR="$AI_STRICT_COLLECTOR_DIR" \
  GRADLE_CMD="$FAKE_GRADLE" \
  AI_BEHAVIOR_FIXTURE_DIR="$AI_PLACEMENT_FIXTURE_DIR" \
  FAKE_AI_BEHAVIOR_ACTUAL_TRACE_SOURCE="$AI_PLACEMENT_ACTUAL_TRACE" \
  AI_BEHAVIOR_REJECT_ALLOWED_FAILURES=1 \
  scripts/collect_ai_behavior_actual_trace.sh
assert_report_contains "$AI_STRICT_COLLECTOR_DIR/ai-behavior-actual-trace-collection.properties" "status=passed"
assert_report_contains "$AI_STRICT_COLLECTOR_DIR/ai-behavior-actual-trace-collection.properties" "evalRejectAllowedFailures=1"
assert_report_contains "$AI_STRICT_COLLECTOR_DIR/ai-behavior-actual-trace-collection.properties" "rejectAllowedFailures=1"
assert_report_contains "$AI_STRICT_COLLECTOR_DIR/ai-behavior-eval.properties" "rejectAllowedFailures=1"
assert_report_contains "$AI_STRICT_COLLECTOR_DIR/ai-behavior-eval.properties" "traceDiffAllowedFailureCount=0"

AI_MIXED_SOURCE_TRACE="$TMP_DIR/ai-behavior-actual-trace-mixed-source.jsonl"
python3 - "$AI_PLACEMENT_ACTUAL_TRACE" "$AI_MIXED_SOURCE_TRACE" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0]["traceSource"] = "device_debug_eval"
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
AI_MIXED_COLLECTOR_DIR="$ARTIFACT_DIR/ai-behavior-actual-trace-collector-mixed-source"
expect_failure \
  "AI behavior actual trace collector rejects mixed non-agent-loop trace provenance" \
  env ARTIFACT_DIR="$AI_MIXED_COLLECTOR_DIR" \
  GRADLE_CMD="$FAKE_GRADLE" \
  AI_BEHAVIOR_FIXTURE_DIR="$AI_PLACEMENT_FIXTURE_DIR" \
  FAKE_AI_BEHAVIOR_ACTUAL_TRACE_SOURCE="$AI_MIXED_SOURCE_TRACE" \
  scripts/collect_ai_behavior_actual_trace.sh
assert_report_contains "$AI_MIXED_COLLECTOR_DIR/ai-behavior-actual-trace-collection.properties" "status=failed"
assert_report_contains "$AI_MIXED_COLLECTOR_DIR/ai-behavior-actual-trace-collection.properties" "reason=actual-trace-non-agent-loop-runtime-source"
assert_report_contains "$AI_MIXED_COLLECTOR_DIR/ai-behavior-eval.properties" "requireAgentLoopRuntimeTraceSource=1"
assert_report_contains "$AI_MIXED_COLLECTOR_DIR/ai-behavior-eval.properties" "actualTraceNonAgentLoopRuntimeSourceCount=1"
assert_report_contains_text "$AI_MIXED_COLLECTOR_DIR/ai-behavior-actual-trace-collection.properties" "actualTraceSourceBreakdown="

AI_BAD_ACTUAL_TRACE="$TMP_DIR/ai-behavior-actual-trace-bad.jsonl"
python3 - "$AI_PLACEMENT_ACTUAL_TRACE" "$AI_BAD_ACTUAL_TRACE" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
rows[0]["actualTools"] = ["share_text"]
rows[0]["routingPath"] = "action_planner"
rows[0]["routingToolName"] = "share_text"
rows[0].pop("routingRejectionReason", None)
target.write_text(
    "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows),
    encoding="utf-8",
)
PY
AI_TRACE_DIFF_MISMATCH="$ARTIFACT_DIR/ai-behavior-trace-diff-mismatch.jsonl"
expect_failure \
  "AI behavior eval rejects mismatched required planning trace diff" \
  scripts/verify_ai_behavior_eval.sh \
    --dir "$AI_PLACEMENT_FIXTURE_DIR" \
    --require-boundary-map \
    --actual-trace "$AI_BAD_ACTUAL_TRACE" \
    --trace-diff "$AI_TRACE_DIFF_MISMATCH" \
    --require-actual-trace \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-mismatch.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-mismatch.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-mismatch.properties" "reason=trace-diff-mismatch"
assert_report_contains_text "$AI_TRACE_DIFF_MISMATCH" '"status": "mismatch"'

reset_logs
AI_COLLECTOR_MISMATCH_DIR="$ARTIFACT_DIR/ai-behavior-actual-trace-collector-mismatch"
expect_failure \
  "AI behavior actual trace collector rejects mismatched runtime trace evidence" \
  env ARTIFACT_DIR="$AI_COLLECTOR_MISMATCH_DIR" \
  GRADLE_CMD="$FAKE_GRADLE" \
  AI_BEHAVIOR_FIXTURE_DIR="$AI_PLACEMENT_FIXTURE_DIR" \
  FAKE_AI_BEHAVIOR_ACTUAL_TRACE_SOURCE="$AI_BAD_ACTUAL_TRACE" \
  scripts/collect_ai_behavior_actual_trace.sh
AI_COLLECTOR_MISMATCH_REPORT="$AI_COLLECTOR_MISMATCH_DIR/ai-behavior-actual-trace-collection.properties"
AI_COLLECTOR_MISMATCH_EVAL="$AI_COLLECTOR_MISMATCH_DIR/ai-behavior-eval.properties"
assert_report_contains "$AI_COLLECTOR_MISMATCH_REPORT" "status=failed"
assert_report_contains "$AI_COLLECTOR_MISMATCH_REPORT" "reason=trace-diff-mismatch"
assert_report_contains "$AI_COLLECTOR_MISMATCH_REPORT" "traceDiffMismatchCount=1"
assert_report_contains "$AI_COLLECTOR_MISMATCH_EVAL" "requireActualTrace=1"
assert_report_contains "$AI_COLLECTOR_MISMATCH_EVAL" "traceDiffMismatchCount=1"
AI_EXTRA_ACTUAL_TRACE="$TMP_DIR/ai-behavior-actual-trace-extra.jsonl"
cp "$AI_ACTUAL_TRACE" "$AI_EXTRA_ACTUAL_TRACE"
cat >> "$AI_EXTRA_ACTUAL_TRACE" <<AI_EXTRA_ACTUAL_TRACE_JSONL
{"caseId":"extra-case","category":"memory_recall","input":"extra","actualTools":[],"actualConfirmation":"none","actualRiskLevel":"low","privacy":"RemoteEligible","localOnly":false,"remoteEligible":true,"traceRecordedAt":"$AI_TRACE_FRESH_RECORDED_AT","traceSource":"agent_loop_runtime","fixtureDirSha256":"$AI_BEHAVIOR_FIXTURE_DIR_SHA"}
AI_EXTRA_ACTUAL_TRACE_JSONL
expect_failure \
  "AI behavior eval rejects extra unknown actual trace rows" \
  scripts/verify_ai_behavior_eval.sh \
    --require-boundary-map \
    --actual-trace "$AI_EXTRA_ACTUAL_TRACE" \
    --trace-diff "$ARTIFACT_DIR/ai-behavior-trace-diff-extra.jsonl" \
    --require-actual-trace \
    --report "$ARTIFACT_DIR/ai-behavior-trace-diff-extra.properties"
assert_report_contains "$ARTIFACT_DIR/ai-behavior-trace-diff-extra.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/ai-behavior-trace-diff-extra.properties" "reason=actual-trace-unknown-case-id:"
