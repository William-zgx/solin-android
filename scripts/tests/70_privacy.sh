# shellcheck shell=bash
# Validation script tests: privacy_scan.sh and verify_privacy_review.sh.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

SAFE_PRIVACY_DIR="$TMP_DIR/privacy-safe"
mkdir -p "$SAFE_PRIVACY_DIR"
SAFE_PRIVACY_FILE="$SAFE_PRIVACY_DIR/readme.txt"
cat > "$SAFE_PRIVACY_FILE" <<'SAFE_PRIVACY_PLACEHOLDERS'
hello solin
api_key=<api-key>
Authorization: Bearer <token>
hf_token_placeholder=hf_xxx
password=<store-password>
SAFE_PRIVACY_PLACEHOLDERS
SAFE_PRIVACY_FILE_SHA="$(shasum -a 256 "$SAFE_PRIVACY_FILE" | awk '{print $1}')"
expect_success \
  "privacy scan accepts safe directory" \
  scripts/privacy_scan.sh --report "$ARTIFACT_DIR/privacy.properties" "$SAFE_PRIVACY_DIR"
assert_report_contains "$ARTIFACT_DIR/privacy.properties" "status=passed"
assert_release_verifier_report_schema "$ARTIFACT_DIR/privacy.properties" "PrivacyScanReport/v1" "privacy-security"
assert_report_contains "$ARTIFACT_DIR/privacy.properties" "scanTarget1Path=$SAFE_PRIVACY_DIR"

SAFE_PLACEMENT_TRACE_DIR="$TMP_DIR/privacy-placement-trace-safe"
mkdir -p "$SAFE_PLACEMENT_TRACE_DIR"
cat > "$SAFE_PLACEMENT_TRACE_DIR/placement-trace.jsonl" <<'SAFE_PLACEMENT_TRACE'
{"type":"PlacementSelected","schemaVersion":1,"runId":"run-1","placement":"Local","primaryReason":"MANUAL_LOCAL"}
{"type":"ModelRuntimeInvocationStarted","schemaVersion":1,"runId":"run-1","placement":"Local","attempt":1}
{"type":"RunDataReceiptRecorded","destination":"Local","currentPromptPrivacy":"LocalOnly","remoteHistoryCount":0,"localOnlyHistoryFilteredCount":1,"rawContentPersisted":false}
SAFE_PLACEMENT_TRACE
expect_success \
  "privacy scan accepts metadata-only placement and invocation trace" \
  scripts/privacy_scan.sh \
    --report "$ARTIFACT_DIR/privacy-placement-trace-safe.properties" \
    "$SAFE_PLACEMENT_TRACE_DIR"
assert_report_contains "$ARTIFACT_DIR/privacy-placement-trace-safe.properties" "status=passed"

for trace_sensitive_field in endpoint token prompt note; do
  UNSAFE_PLACEMENT_TRACE_DIR="$TMP_DIR/privacy-placement-trace-$trace_sensitive_field"
  mkdir -p "$UNSAFE_PLACEMENT_TRACE_DIR"
  cat > "$UNSAFE_PLACEMENT_TRACE_DIR/placement-trace.jsonl" <<UNSAFE_PLACEMENT_TRACE
{"type":"ModelRuntimeInvocationStarted","schemaVersion":1,"runId":"run-1","placement":"Remote","$trace_sensitive_field":"must-not-be-recorded"}
UNSAFE_PLACEMENT_TRACE
  expect_failure \
    "privacy scan rejects $trace_sensitive_field in placement invocation trace" \
    scripts/privacy_scan.sh \
      --report "$ARTIFACT_DIR/privacy-placement-trace-$trace_sensitive_field.properties" \
      "$UNSAFE_PLACEMENT_TRACE_DIR"
  assert_report_contains "$ARTIFACT_DIR/privacy-placement-trace-$trace_sensitive_field.properties" "status=failed"
  assert_report_contains "$ARTIFACT_DIR/privacy-placement-trace-$trace_sensitive_field.properties" "reason=placement-trace-sensitive-field"
  if grep -q 'must-not-be-recorded' <<<"$LAST_OUTPUT" ||
    grep -q 'must-not-be-recorded' "$ARTIFACT_DIR/privacy-placement-trace-$trace_sensitive_field.properties"; then
    fail "privacy scan placement trace finding must redact $trace_sensitive_field value"
  fi
done

UNSAFE_RECEIPT_TRACE_DIR="$TMP_DIR/privacy-receipt-trace-unknown-field"
mkdir -p "$UNSAFE_RECEIPT_TRACE_DIR"
cat > "$UNSAFE_RECEIPT_TRACE_DIR/receipt-trace.jsonl" <<'UNSAFE_RECEIPT_TRACE'
{"type":"RunDataReceiptRecorded","destination":"Remote","currentPromptPrivacy":"PublicEvidence","rawContentPersisted":false,"note":"stable-looking-private-metadata"}
UNSAFE_RECEIPT_TRACE
expect_failure \
  "privacy scan rejects non-whitelisted receipt metadata" \
  scripts/privacy_scan.sh \
    --report "$ARTIFACT_DIR/privacy-receipt-trace-unknown-field.properties" \
    "$UNSAFE_RECEIPT_TRACE_DIR"
assert_report_contains "$ARTIFACT_DIR/privacy-receipt-trace-unknown-field.properties" "reason=placement-trace-sensitive-field"

UNSAFE_PLACEMENT_KOTLIN_DIR="$TMP_DIR/privacy-placement-kotlin-unknown-field"
mkdir -p "$UNSAFE_PLACEMENT_KOTLIN_DIR"
cat > "$UNSAFE_PLACEMENT_KOTLIN_DIR/TraceWriter.kt" <<'UNSAFE_PLACEMENT_KOTLIN'
fun traceJson(step: AgentStep, json: JSONObject, dynamicField: String): JSONObject =
    when (step) {
        is AgentStep.ModelRuntimeInvocationStarted -> json
            .put("schemaVersion", 1)
            .put("runId", "run-1")
            .put("placement", "Remote")
            .put("attempt", 1)
            .put("note", "must-not-be-recorded")
            .put(dynamicField, "must-not-be-recorded")
    }
UNSAFE_PLACEMENT_KOTLIN
expect_failure \
  "privacy scan rejects non-whitelisted and dynamic Kotlin trace fields" \
  scripts/privacy_scan.sh \
    --report "$ARTIFACT_DIR/privacy-placement-kotlin-unknown-field.properties" \
    "$UNSAFE_PLACEMENT_KOTLIN_DIR"
assert_report_contains "$ARTIFACT_DIR/privacy-placement-kotlin-unknown-field.properties" "reason=placement-trace-sensitive-field"
if grep -q 'must-not-be-recorded' <<<"$LAST_OUTPUT" ||
  grep -q 'must-not-be-recorded' "$ARTIFACT_DIR/privacy-placement-kotlin-unknown-field.properties"; then
  fail "privacy scan Kotlin trace findings must redact field values"
fi

expect_success \
  "privacy scan binds file target sha" \
  scripts/privacy_scan.sh --report "$ARTIFACT_DIR/privacy-file.properties" "$SAFE_PRIVACY_FILE"
assert_release_verifier_report_schema "$ARTIFACT_DIR/privacy-file.properties" "PrivacyScanReport/v1" "privacy-security"
assert_report_contains "$ARTIFACT_DIR/privacy-file.properties" "scanTarget1Path=$SAFE_PRIVACY_FILE"
assert_report_contains "$ARTIFACT_DIR/privacy-file.properties" "scanTarget1Sha256=$SAFE_PRIVACY_FILE_SHA"
SPACED_PRIVACY_DIR="$TMP_DIR/privacy spaced command"
SPACED_PRIVACY_FILE="$SPACED_PRIVACY_DIR/safe target.txt"
SPACED_PRIVACY_REPORT="$ARTIFACT_DIR/privacy spaced command.properties"
mkdir -p "$SPACED_PRIVACY_DIR"
printf 'safe\n' > "$SPACED_PRIVACY_FILE"
expect_success \
  "privacy scan report command preserves spaced argv" \
  scripts/privacy_scan.sh --report "$SPACED_PRIVACY_REPORT" "$SPACED_PRIVACY_FILE"
assert_report_command_argv \
  "$SPACED_PRIVACY_REPORT" \
  scripts/privacy_scan.sh \
  --report "$SPACED_PRIVACY_REPORT" \
  "$SPACED_PRIVACY_FILE"
PRIVACY_MISSING_VALUE_REPORT="$ARTIFACT_DIR/privacy-missing-report-value.properties"
expect_failure \
  "privacy scan reports malformed arguments when report path is known" \
  scripts/privacy_scan.sh --report "$PRIVACY_MISSING_VALUE_REPORT" --report
assert_release_verifier_report_schema "$PRIVACY_MISSING_VALUE_REPORT" "PrivacyScanReport/v1" "privacy-security"
assert_report_contains "$PRIVACY_MISSING_VALUE_REPORT" "status=failed"
assert_report_contains "$PRIVACY_MISSING_VALUE_REPORT" "reason=missing-report-argument"
assert_report_contains "$PRIVACY_MISSING_VALUE_REPORT" "failedTarget=argument-parser"
PRIVACY_UNKNOWN_OPTION_REPORT="$ARTIFACT_DIR/privacy-unknown-option.properties"
expect_failure \
  "privacy scan rejects option-like unknown arguments instead of scanning them" \
  scripts/privacy_scan.sh --report "$PRIVACY_UNKNOWN_OPTION_REPORT" --bad-arg
assert_release_verifier_report_schema "$PRIVACY_UNKNOWN_OPTION_REPORT" "PrivacyScanReport/v1" "privacy-security"
assert_report_contains "$PRIVACY_UNKNOWN_OPTION_REPORT" "status=failed"
assert_report_contains "$PRIVACY_UNKNOWN_OPTION_REPORT" "reason=unknown-argument"
assert_report_contains "$PRIVACY_UNKNOWN_OPTION_REPORT" "failedTarget=argument-parser"

UNSAFE_PRIVACY_DIR="$TMP_DIR/privacy-unsafe"
mkdir -p "$UNSAFE_PRIVACY_DIR"
PRIVACY_SCAN_SECRET_BODY="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
PRIVACY_SCAN_SECRET="sk-${PRIVACY_SCAN_SECRET_BODY}"
PRIVACY_SCAN_HF_TOKEN="hf_${PRIVACY_SCAN_SECRET_BODY}aa"
PRIVACY_SCAN_BEARER="Bearer ${PRIVACY_SCAN_SECRET_BODY}"
PRIVACY_SCAN_API_KEY="api_key=${PRIVACY_SCAN_SECRET_BODY}"
PRIVACY_SCAN_PASSWORD="password=${PRIVACY_SCAN_SECRET_BODY}"
PRIVACY_SCAN_GENERIC_TOKEN="token=${PRIVACY_SCAN_SECRET_BODY}"
{
  printf 'token=%s\n' "$PRIVACY_SCAN_SECRET"
  printf '%s\n' "$PRIVACY_SCAN_HF_TOKEN"
  printf 'Authorization: %s\n' "$PRIVACY_SCAN_BEARER"
  printf '%s\n' "$PRIVACY_SCAN_API_KEY"
  printf '%s\n' "$PRIVACY_SCAN_PASSWORD"
  printf '%s\n' "$PRIVACY_SCAN_GENERIC_TOKEN"
} > "$UNSAFE_PRIVACY_DIR/secret.txt"
expect_failure \
  "privacy scan rejects high-confidence token" \
  scripts/privacy_scan.sh --report "$ARTIFACT_DIR/privacy-failed.properties" "$UNSAFE_PRIVACY_DIR"
assert_report_contains "$ARTIFACT_DIR/privacy-failed.properties" "status=failed"
assert_release_verifier_report_schema "$ARTIFACT_DIR/privacy-failed.properties" "PrivacyScanReport/v1" "privacy-security"
assert_report_contains "$ARTIFACT_DIR/privacy-failed.properties" "scanTarget1Path=$UNSAFE_PRIVACY_DIR"
assert_report_contains "$ARTIFACT_DIR/privacy-failed.properties" "reason=secret-pattern-detected"
if grep -q "$PRIVACY_SCAN_SECRET" <<<"$LAST_OUTPUT"; then
  fail "privacy scan stderr must redact raw secret values"
fi
if grep -q "$PRIVACY_SCAN_SECRET" "$ARTIFACT_DIR/privacy-failed.properties"; then
  fail "privacy scan report must not contain raw secret values"
fi
for privacy_secret_value in "$PRIVACY_SCAN_HF_TOKEN" "$PRIVACY_SCAN_BEARER" "$PRIVACY_SCAN_API_KEY" "$PRIVACY_SCAN_PASSWORD" "$PRIVACY_SCAN_GENERIC_TOKEN"; do
  if grep -q "$privacy_secret_value" <<<"$LAST_OUTPUT" ||
    grep -q "$privacy_secret_value" "$ARTIFACT_DIR/privacy-failed.properties"; then
    fail "privacy scan outputs must redact raw expanded secret patterns"
  fi
done

PRIVACY_NOTICE="$TMP_DIR/privacy-notice.md"
PRIVACY_CAPABILITY_MATRIX="$TMP_DIR/privacy-capability-matrix.json"
PRIVACY_REVIEW_PENDING="$TMP_DIR/privacy-review-pending.json"
PRIVACY_REVIEW_APPROVED="$TMP_DIR/privacy-review-approved.json"
PRIVACY_REVIEW_RELEASE_EVIDENCE="$TMP_DIR/privacy-review-release.properties"
PRIVACY_REVIEW_SECURITY_EVIDENCE="$TMP_DIR/privacy-review-security.properties"
PRIVACY_REVIEW_LEGAL_EVIDENCE="$TMP_DIR/privacy-review-legal.properties"
printf 'Solin privacy notice\n' > "$PRIVACY_NOTICE"
cat > "$PRIVACY_CAPABILITY_MATRIX" <<'PRIVACY_CAPABILITY_MATRIX_JSON'
{
  "version": 1,
  "sensitiveCapabilityDisclosures": [
    {
      "capabilityId": "remote_model_send",
      "displayName": "Remote model send",
      "dataAccessed": "Remote-eligible prompt and image input.",
      "consentBoundary": "User confirms before sending.",
      "remoteBoundary": "Only configured HTTPS endpoints receive data.",
      "revokeOrClearControl": "User can clear the endpoint and API key."
    }
  ]
}
PRIVACY_CAPABILITY_MATRIX_JSON
PRIVACY_NOTICE_SHA="$(shasum -a 256 "$PRIVACY_NOTICE" | awk '{print $1}')"
PRIVACY_CAPABILITY_MATRIX_SHA="$(shasum -a 256 "$PRIVACY_CAPABILITY_MATRIX" | awk '{print $1}')"
for privacy_review_role in release security legal; do
  case "$privacy_review_role" in
    release) privacy_review_evidence="$PRIVACY_REVIEW_RELEASE_EVIDENCE" ;;
    security) privacy_review_evidence="$PRIVACY_REVIEW_SECURITY_EVIDENCE" ;;
    legal) privacy_review_evidence="$PRIVACY_REVIEW_LEGAL_EVIDENCE" ;;
  esac
  cat > "$privacy_review_evidence" <<PRIVACY_REVIEW_EVIDENCE_PROPERTIES
status=approved
target=privacy-review-approved-evidence
role=$privacy_review_role
privacyReviewFile=$PRIVACY_REVIEW_APPROVED
noticePath=$PRIVACY_NOTICE
noticeSha256=$PRIVACY_NOTICE_SHA
capabilityMatrixPath=$PRIVACY_CAPABILITY_MATRIX
capabilityMatrixSha256=$PRIVACY_CAPABILITY_MATRIX_SHA
scope=privacy-notice
requiredDecision=approved
approvalStatus=approved
PRIVACY_REVIEW_EVIDENCE_PROPERTIES
done
PRIVACY_REVIEW_RELEASE_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_RELEASE_EVIDENCE" | awk '{print $1}')"
PRIVACY_REVIEW_SECURITY_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_SECURITY_EVIDENCE" | awk '{print $1}')"
PRIVACY_REVIEW_LEGAL_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_LEGAL_EVIDENCE" | awk '{print $1}')"
cat > "$PRIVACY_REVIEW_PENDING" <<'PRIVACY_REVIEW_PENDING_JSON'
{
  "version": 1,
  "noticePath": "PLACEHOLDER",
  "noticeSha256": "PLACEHOLDER",
  "capabilityMatrixPath": "PLACEHOLDER",
  "capabilityMatrixSha256": "PLACEHOLDER",
  "status": "pending_manual_review",
  "reviews": []
}
PRIVACY_REVIEW_PENDING_JSON
expect_failure \
  "privacy review verifier rejects pending records" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_PENDING" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" CAPABILITY_MATRIX_FILE="$PRIVACY_CAPABILITY_MATRIX" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-pending.properties"
assert_release_verifier_failed_report \
  "$ARTIFACT_DIR/privacy-review-pending.properties" \
  "PrivacyReviewVerification/v1" \
  "privacy-security"
assert_report_contains "$ARTIFACT_DIR/privacy-review-pending.properties" "failedTarget=privacy-review-record"
assert_report_contains "$ARTIFACT_DIR/privacy-review-pending.properties" "noticeSha256=$PRIVACY_NOTICE_SHA"
assert_report_contains "$ARTIFACT_DIR/privacy-review-pending.properties" "capabilityMatrixSha256=$PRIVACY_CAPABILITY_MATRIX_SHA"
expect_failure \
  "checked-in privacy review candidate has current notice and evidence hashes" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-checked-in-pending.properties"
assert_release_verifier_failed_report \
  "$ARTIFACT_DIR/privacy-review-checked-in-pending.properties" \
  "PrivacyReviewVerification/v1" \
  "privacy-security"
for privacy_review_sha_failure in \
  "notice-sha-mismatch" \
  "release-evidence-sha-mismatch" \
  "security-evidence-sha-mismatch" \
  "legal-evidence-sha-mismatch" \
  "release-evidence-notice-sha-mismatch" \
  "security-evidence-notice-sha-mismatch" \
  "legal-evidence-notice-sha-mismatch" \
  "capability-matrix-sha-mismatch" \
  "release-evidence-capability-matrix-sha-mismatch" \
  "security-evidence-capability-matrix-sha-mismatch" \
  "legal-evidence-capability-matrix-sha-mismatch"; do
  if grep -q "$privacy_review_sha_failure" "$ARTIFACT_DIR/privacy-review-checked-in-pending.properties"; then
    fail "checked-in privacy review candidate must not fail on stale SHA: $privacy_review_sha_failure"
  fi
done
cat > "$PRIVACY_REVIEW_APPROVED" <<PRIVACY_REVIEW_APPROVED_JSON
{
  "version": 1,
  "noticePath": "$PRIVACY_NOTICE",
  "noticeSha256": "$PRIVACY_NOTICE_SHA",
  "capabilityMatrixPath": "$PRIVACY_CAPABILITY_MATRIX",
  "capabilityMatrixSha256": "$PRIVACY_CAPABILITY_MATRIX_SHA",
  "status": "approved",
  "reviews": [
    {
      "role": "release",
      "decision": "approved",
      "reviewer": "Release Reviewer",
      "reviewDate": "2026-06-06",
      "evidencePath": "$PRIVACY_REVIEW_RELEASE_EVIDENCE",
      "evidenceSha256": "$PRIVACY_REVIEW_RELEASE_EVIDENCE_SHA"
    },
    {
      "role": "security",
      "decision": "approved",
      "reviewer": "Security Reviewer",
      "reviewDate": "2026-06-06",
      "evidencePath": "$PRIVACY_REVIEW_SECURITY_EVIDENCE",
      "evidenceSha256": "$PRIVACY_REVIEW_SECURITY_EVIDENCE_SHA"
    },
    {
      "role": "legal",
      "decision": "approved",
      "reviewer": "Legal Reviewer",
      "reviewDate": "2026-06-06",
      "evidencePath": "$PRIVACY_REVIEW_LEGAL_EVIDENCE",
      "evidenceSha256": "$PRIVACY_REVIEW_LEGAL_EVIDENCE_SHA"
    }
  ]
}
PRIVACY_REVIEW_APPROVED_JSON
expect_success \
  "privacy review verifier accepts approved current notice" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_APPROVED" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" CAPABILITY_MATRIX_FILE="$PRIVACY_CAPABILITY_MATRIX" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-approved.properties"
assert_release_verifier_passed_report \
  "$ARTIFACT_DIR/privacy-review-approved.properties" \
  "PrivacyReviewVerification/v1" \
  "privacy-security"
assert_report_contains "$ARTIFACT_DIR/privacy-review-approved.properties" "reviewSha256=$(shasum -a 256 "$PRIVACY_REVIEW_APPROVED" | awk '{print $1}')"
PRIVACY_REVIEW_MISSING_REVIEW_FILE_EVIDENCE="$TMP_DIR/privacy-review-release-missing-review-file.properties"
grep -Ev '^privacyReviewFile=' "$PRIVACY_REVIEW_RELEASE_EVIDENCE" > "$PRIVACY_REVIEW_MISSING_REVIEW_FILE_EVIDENCE"
PRIVACY_REVIEW_MISSING_REVIEW_FILE_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_MISSING_REVIEW_FILE_EVIDENCE" | awk '{print $1}')"
PRIVACY_REVIEW_MISSING_REVIEW_FILE_RECORD="$TMP_DIR/privacy-review-missing-review-file-evidence.json"
python3 - "$PRIVACY_REVIEW_APPROVED" "$PRIVACY_REVIEW_MISSING_REVIEW_FILE_RECORD" "$PRIVACY_REVIEW_MISSING_REVIEW_FILE_EVIDENCE" "$PRIVACY_REVIEW_MISSING_REVIEW_FILE_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["reviews"][0]["evidencePath"] = sys.argv[3]
record["reviews"][0]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "privacy review verifier rejects evidence missing review file binding" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_MISSING_REVIEW_FILE_RECORD" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" CAPABILITY_MATRIX_FILE="$PRIVACY_CAPABILITY_MATRIX" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-missing-review-file-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-missing-review-file-evidence.properties" "release-evidence-privacy-review-file-missing"
PRIVACY_REVIEW_MISMATCH_REVIEW_FILE_EVIDENCE="$TMP_DIR/privacy-review-release-mismatch-review-file.properties"
sed 's#^privacyReviewFile=.*#privacyReviewFile=/tmp/other-privacy-review.json#' \
  "$PRIVACY_REVIEW_RELEASE_EVIDENCE" > "$PRIVACY_REVIEW_MISMATCH_REVIEW_FILE_EVIDENCE"
PRIVACY_REVIEW_MISMATCH_REVIEW_FILE_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_MISMATCH_REVIEW_FILE_EVIDENCE" | awk '{print $1}')"
PRIVACY_REVIEW_MISMATCH_REVIEW_FILE_RECORD="$TMP_DIR/privacy-review-mismatch-review-file-evidence.json"
python3 - "$PRIVACY_REVIEW_APPROVED" "$PRIVACY_REVIEW_MISMATCH_REVIEW_FILE_RECORD" "$PRIVACY_REVIEW_MISMATCH_REVIEW_FILE_EVIDENCE" "$PRIVACY_REVIEW_MISMATCH_REVIEW_FILE_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["reviews"][0]["evidencePath"] = sys.argv[3]
record["reviews"][0]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "privacy review verifier rejects evidence bound to another review file" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_MISMATCH_REVIEW_FILE_RECORD" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" CAPABILITY_MATRIX_FILE="$PRIVACY_CAPABILITY_MATRIX" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-mismatch-review-file-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-mismatch-review-file-evidence.properties" "release-evidence-privacy-review-file-mismatch"
PRIVACY_REVIEW_BAD_CAPABILITY_SHA="$TMP_DIR/privacy-review-bad-capability-sha.json"
sed 's/"capabilityMatrixSha256": "'"$PRIVACY_CAPABILITY_MATRIX_SHA"'"/"capabilityMatrixSha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$PRIVACY_REVIEW_APPROVED" > "$PRIVACY_REVIEW_BAD_CAPABILITY_SHA"
expect_failure \
  "privacy review verifier rejects capability matrix sha mismatch" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_BAD_CAPABILITY_SHA" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" CAPABILITY_MATRIX_FILE="$PRIVACY_CAPABILITY_MATRIX" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-bad-capability-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-bad-capability-sha.properties" "capability-matrix-sha-mismatch"
PRIVACY_REVIEW_UNKNOWN_ROLE="$TMP_DIR/privacy-review-unknown-role.json"
python3 - "$PRIVACY_REVIEW_APPROVED" "$PRIVACY_REVIEW_UNKNOWN_ROLE" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
unknown_review = dict(record["reviews"][0])
unknown_review["role"] = "growth"
record["reviews"].append(unknown_review)
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "privacy review verifier rejects unknown review roles" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_UNKNOWN_ROLE" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" CAPABILITY_MATRIX_FILE="$PRIVACY_CAPABILITY_MATRIX" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-unknown-role.properties"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-unknown-role.properties" "growth-review-role-unknown"
PRIVACY_REVIEW_DUPLICATE_ROLE="$TMP_DIR/privacy-review-duplicate-role.json"
python3 - "$PRIVACY_REVIEW_APPROVED" "$PRIVACY_REVIEW_DUPLICATE_ROLE" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["reviews"][1]["role"] = "release"
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "privacy review verifier rejects duplicate review roles" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_DUPLICATE_ROLE" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" CAPABILITY_MATRIX_FILE="$PRIVACY_CAPABILITY_MATRIX" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-duplicate-role.properties"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-duplicate-role.properties" "release-review-role-duplicate"
PRIVACY_REVIEW_FUTURE="$TMP_DIR/privacy-review-future.json"
sed 's/2026-06-06/2999-01-01/g' "$PRIVACY_REVIEW_APPROVED" > "$PRIVACY_REVIEW_FUTURE"
expect_failure \
  "privacy review verifier rejects future review dates" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_FUTURE" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" CAPABILITY_MATRIX_FILE="$PRIVACY_CAPABILITY_MATRIX" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-future.properties"
assert_report_contains "$ARTIFACT_DIR/privacy-review-future.properties" "status=failed"
PRIVACY_REVIEW_BAD_EVIDENCE_SHA="$TMP_DIR/privacy-review-bad-evidence-sha.json"
sed 's/"evidenceSha256": "'"$PRIVACY_REVIEW_RELEASE_EVIDENCE_SHA"'"/"evidenceSha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$PRIVACY_REVIEW_APPROVED" > "$PRIVACY_REVIEW_BAD_EVIDENCE_SHA"
expect_failure \
  "privacy review verifier rejects evidence sha mismatch" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_BAD_EVIDENCE_SHA" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" CAPABILITY_MATRIX_FILE="$PRIVACY_CAPABILITY_MATRIX" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-bad-evidence-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-bad-evidence-sha.properties" "release-evidence-sha-mismatch"
PRIVACY_REVIEW_PENDING_EVIDENCE="$TMP_DIR/privacy-review-release-pending.properties"
cat > "$PRIVACY_REVIEW_PENDING_EVIDENCE" <<PRIVACY_REVIEW_PENDING_EVIDENCE_PROPERTIES
status=pending
target=privacy-review-candidate-evidence
role=release
privacyReviewFile=$PRIVACY_REVIEW_APPROVED
noticePath=$PRIVACY_NOTICE
noticeSha256=$PRIVACY_NOTICE_SHA
capabilityMatrixPath=$PRIVACY_CAPABILITY_MATRIX
capabilityMatrixSha256=$PRIVACY_CAPABILITY_MATRIX_SHA
scope=privacy-notice
requiredDecision=approved
approvalStatus=not-approved
PRIVACY_REVIEW_PENDING_EVIDENCE_PROPERTIES
PRIVACY_REVIEW_PENDING_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_PENDING_EVIDENCE" | awk '{print $1}')"
PRIVACY_REVIEW_PENDING_EVIDENCE_RECORD="$TMP_DIR/privacy-review-pending-evidence.json"
python3 - "$PRIVACY_REVIEW_APPROVED" "$PRIVACY_REVIEW_PENDING_EVIDENCE_RECORD" "$PRIVACY_REVIEW_PENDING_EVIDENCE" "$PRIVACY_REVIEW_PENDING_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["reviews"][0]["evidencePath"] = sys.argv[3]
record["reviews"][0]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "privacy review verifier rejects pending evidence content" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_PENDING_EVIDENCE_RECORD" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" CAPABILITY_MATRIX_FILE="$PRIVACY_CAPABILITY_MATRIX" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-pending-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-pending-evidence.properties" "release-evidence-status-not-approved"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-pending-evidence.properties" "release-evidence-approval-status-not-approved"
PRIVACY_REVIEW_ROLE_MISMATCH_EVIDENCE="$TMP_DIR/privacy-review-release-role-mismatch.properties"
sed 's/role=release/role=security/' "$PRIVACY_REVIEW_RELEASE_EVIDENCE" > "$PRIVACY_REVIEW_ROLE_MISMATCH_EVIDENCE"
PRIVACY_REVIEW_ROLE_MISMATCH_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_ROLE_MISMATCH_EVIDENCE" | awk '{print $1}')"
PRIVACY_REVIEW_ROLE_MISMATCH_RECORD="$TMP_DIR/privacy-review-role-mismatch-evidence.json"
python3 - "$PRIVACY_REVIEW_APPROVED" "$PRIVACY_REVIEW_ROLE_MISMATCH_RECORD" "$PRIVACY_REVIEW_ROLE_MISMATCH_EVIDENCE" "$PRIVACY_REVIEW_ROLE_MISMATCH_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["reviews"][0]["evidencePath"] = sys.argv[3]
record["reviews"][0]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "privacy review verifier rejects evidence role mismatch" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_ROLE_MISMATCH_RECORD" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" CAPABILITY_MATRIX_FILE="$PRIVACY_CAPABILITY_MATRIX" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-role-mismatch-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-role-mismatch-evidence.properties" "release-evidence-role-mismatch"
PRIVACY_REVIEW_NOTICE_MISMATCH_EVIDENCE="$TMP_DIR/privacy-review-release-notice-mismatch.properties"
sed "s/noticeSha256=$PRIVACY_NOTICE_SHA/noticeSha256=0000000000000000000000000000000000000000000000000000000000000000/" \
  "$PRIVACY_REVIEW_RELEASE_EVIDENCE" > "$PRIVACY_REVIEW_NOTICE_MISMATCH_EVIDENCE"
PRIVACY_REVIEW_NOTICE_MISMATCH_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_NOTICE_MISMATCH_EVIDENCE" | awk '{print $1}')"
PRIVACY_REVIEW_NOTICE_MISMATCH_RECORD="$TMP_DIR/privacy-review-notice-mismatch-evidence.json"
python3 - "$PRIVACY_REVIEW_APPROVED" "$PRIVACY_REVIEW_NOTICE_MISMATCH_RECORD" "$PRIVACY_REVIEW_NOTICE_MISMATCH_EVIDENCE" "$PRIVACY_REVIEW_NOTICE_MISMATCH_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["reviews"][0]["evidencePath"] = sys.argv[3]
record["reviews"][0]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "privacy review verifier rejects evidence notice sha mismatch" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_NOTICE_MISMATCH_RECORD" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" CAPABILITY_MATRIX_FILE="$PRIVACY_CAPABILITY_MATRIX" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-notice-mismatch-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-notice-mismatch-evidence.properties" "release-evidence-notice-sha-mismatch"
