# shellcheck shell=bash
# Validation script tests: collect_model_license_metadata.sh and verify_model_license_review.sh.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

MODEL_LICENSE_METADATA="$TMP_DIR/model-license-metadata.json"
MODEL_LICENSE_MANIFEST="$TMP_DIR/model-manifest.md"
MODEL_LICENSE_PENDING="$TMP_DIR/model-license-pending.json"
MODEL_LICENSE_APPROVED="$TMP_DIR/model-license-approved.json"
MODEL_LICENSE_CHAT_EVIDENCE="$TMP_DIR/model-license-chat-e2b-review.properties"
MODEL_LICENSE_MEMORY_EVIDENCE="$TMP_DIR/model-license-memory-embedding-300m-review.properties"
MODEL_LICENSE_REVIEW_RECORDED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
cat > "$MODEL_LICENSE_CHAT_EVIDENCE" <<MODEL_LICENSE_CHAT_EVIDENCE_PROPERTIES
artifactSchema=ModelLicenseReviewApprovedEvidence/v1
status=approved
target=model-license-review-approved-evidence
owner=model-license-reviewer
recordedAt=$MODEL_LICENSE_REVIEW_RECORDED_AT
command=manual-model-license-review chat-e2b
reproduciblePath=$MODEL_LICENSE_CHAT_EVIDENCE
modelLicenseReviewFile=$MODEL_LICENSE_APPROVED
model=chat-e2b
scope=license-redistribution-attribution
redistributionDecision=approved
licenseName=Apache-2.0
reviewer=Model Reviewer
MODEL_LICENSE_CHAT_EVIDENCE_PROPERTIES
cat > "$MODEL_LICENSE_MEMORY_EVIDENCE" <<MODEL_LICENSE_MEMORY_EVIDENCE_PROPERTIES
artifactSchema=ModelLicenseReviewApprovedEvidence/v1
status=approved
target=model-license-review-approved-evidence
owner=model-license-reviewer
recordedAt=$MODEL_LICENSE_REVIEW_RECORDED_AT
command=manual-model-license-review memory-embedding-300m
reproduciblePath=$MODEL_LICENSE_MEMORY_EVIDENCE
modelLicenseReviewFile=$MODEL_LICENSE_APPROVED
model=memory-embedding-300m
scope=license-redistribution-attribution
redistributionDecision=approved
licenseName=Apache-2.0
reviewer=Model Reviewer
MODEL_LICENSE_MEMORY_EVIDENCE_PROPERTIES
MODEL_LICENSE_CHAT_EVIDENCE_SHA="$(shasum -a 256 "$MODEL_LICENSE_CHAT_EVIDENCE" | awk '{print $1}')"
MODEL_LICENSE_MEMORY_EVIDENCE_SHA="$(shasum -a 256 "$MODEL_LICENSE_MEMORY_EVIDENCE" | awk '{print $1}')"
cat > "$MODEL_LICENSE_MANIFEST" <<'MODEL_LICENSE_MANIFEST_MD'
| ID | File | Repository | Upstream revision | Bytes | SHA-256 | License status |
| --- | --- | --- | --- | ---: | --- | --- |
| `chat-e2b` | `chat.litertlm` | `https://huggingface.co/example/chat-e2b` | `chat-revision-a` | `1` | `abc` | Pending. |
| `memory-embedding-300m` | `memory.litertlm` | `https://huggingface.co/example/memory-embedding-300m` | `memory-revision-a` | `1` | `def` | Pending. |
MODEL_LICENSE_MANIFEST_MD
cat > "$MODEL_LICENSE_METADATA" <<'MODEL_LICENSE_METADATA_JSON'
{
  "version": 1,
  "recordedAt": "2026-06-05T00:00:00Z",
  "models": [
    {
      "id": "chat-e2b",
      "repository": "example/chat-e2b",
      "manifestRevision": "chat-revision-a",
      "apiUrl": "https://huggingface.co/api/models/example/chat-e2b",
      "modelSha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "gated": false,
      "requiresUserAuthorization": false,
      "licenseTags": ["apache-2.0"],
      "cardLicense": "apache-2.0",
      "metadataOnly": true
    },
    {
      "id": "memory-embedding-300m",
      "repository": "example/memory-embedding-300m",
      "manifestRevision": "memory-revision-a",
      "apiUrl": "https://huggingface.co/api/models/example/memory-embedding-300m",
      "modelSha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      "gated": false,
      "requiresUserAuthorization": false,
      "licenseTags": ["apache-2.0"],
      "cardLicense": "apache-2.0",
      "metadataOnly": true
    }
  ]
}
MODEL_LICENSE_METADATA_JSON
cat > "$MODEL_LICENSE_PENDING" <<'MODEL_LICENSE_PENDING_JSON'
{
  "version": 1,
  "models": [
    {
      "id": "chat-e2b",
      "status": "pending_manual_review",
      "licenseName": "",
      "licenseUrl": "https://example.com/model",
      "redistributionDecision": "not_approved",
      "attributionNotice": "",
      "reviewer": "",
      "reviewDate": "",
      "reviewEvidencePath": "",
      "reviewEvidenceSha256": ""
    }
  ]
}
MODEL_LICENSE_PENDING_JSON
MODEL_LICENSE_FAKE_API_ROOT="$TMP_DIR/model-license-fake-api"
mkdir -p "$MODEL_LICENSE_FAKE_API_ROOT/example"
cat > "$MODEL_LICENSE_FAKE_API_ROOT/example/chat-e2b" <<'MODEL_LICENSE_CHAT_API_JSON'
{
  "sha": "chat-current-api-sha",
  "lastModified": "2026-06-05T00:00:00.000Z",
  "gated": false,
  "tags": ["license:apache-2.0"],
  "cardData": {"license": "apache-2.0"},
  "siblings": [
    {"rfilename": "README.md"},
    {"rfilename": "LICENSE"},
    {"rfilename": "model.litertlm"}
  ]
}
MODEL_LICENSE_CHAT_API_JSON
cat > "$MODEL_LICENSE_FAKE_API_ROOT/example/memory-embedding-300m" <<'MODEL_LICENSE_MEMORY_API_JSON'
{
  "sha": "memory-current-api-sha",
  "lastModified": "2026-06-05T00:00:00.000Z",
  "gated": false,
  "tags": ["license:apache-2.0"],
  "cardData": {"license": "apache-2.0"},
  "siblings": [
    {"rfilename": "README.md"},
    {"rfilename": "NOTICE.txt"},
    {"rfilename": "memory.litertlm"}
  ]
}
MODEL_LICENSE_MEMORY_API_JSON
MODEL_LICENSE_COLLECTED="$TMP_DIR/model-license-collected.json"
expect_success \
  "model license metadata collector records source candidates" \
  env REVIEW_FILE="$MODEL_LICENSE_PENDING" \
  MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  OUT_FILE="$MODEL_LICENSE_COLLECTED" \
  REPORT_FILE="$ARTIFACT_DIR/model-license-collector.properties" \
  MODEL_LICENSE_API_BASE_URL="file://$MODEL_LICENSE_FAKE_API_ROOT" \
  scripts/collect_model_license_metadata.sh
assert_report_contains "$ARTIFACT_DIR/model-license-collector.properties" "status=passed"
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/model-license-collector.properties" \
  "ModelLicenseMetadataCollection/v1" \
  "model-license-review"
assert_report_contains "$ARTIFACT_DIR/model-license-collector.properties" "target=model-license-metadata-collector"
assert_report_contains "$ARTIFACT_DIR/model-license-collector.properties" "outFile=$MODEL_LICENSE_COLLECTED"
assert_report_contains "$ARTIFACT_DIR/model-license-collector.properties" "outFileSha256=$(shasum -a 256 "$MODEL_LICENSE_COLLECTED" | awk '{print $1}')"
assert_report_contains "$ARTIFACT_DIR/model-license-collector.properties" "reviewFile=$MODEL_LICENSE_PENDING"
assert_report_contains "$ARTIFACT_DIR/model-license-collector.properties" "reviewSha256=$(shasum -a 256 "$MODEL_LICENSE_PENDING" | awk '{print $1}')"
assert_report_contains "$ARTIFACT_DIR/model-license-collector.properties" "manifestFile=$MODEL_LICENSE_MANIFEST"
assert_report_contains "$ARTIFACT_DIR/model-license-collector.properties" "manifestSha256=$(shasum -a 256 "$MODEL_LICENSE_MANIFEST" | awk '{print $1}')"
assert_report_contains "$ARTIFACT_DIR/model-license-collector.properties" "modelCount=2"
grep -q '"licenseSourceCandidates"' "$MODEL_LICENSE_COLLECTED" ||
  fail "Expected collected model license metadata to include source candidates"
grep -q 'https://huggingface.co/example/chat-e2b/blob/chat-revision-a/README.md' "$MODEL_LICENSE_COLLECTED" ||
  fail "Expected collected metadata to include chat README license source candidate"
grep -q 'https://huggingface.co/example/memory-embedding-300m/blob/memory-revision-a/NOTICE.txt' "$MODEL_LICENSE_COLLECTED" ||
  fail "Expected collected metadata to include memory NOTICE license source candidate"
expect_failure \
  "model license metadata collector reports missing review file" \
  env REVIEW_FILE="$TMP_DIR/missing-model-license-review.json" \
  MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  OUT_FILE="$TMP_DIR/model-license-missing-review-collected.json" \
  REPORT_FILE="$ARTIFACT_DIR/model-license-collector-missing-review.properties" \
  MODEL_LICENSE_API_BASE_URL="file://$MODEL_LICENSE_FAKE_API_ROOT" \
  scripts/collect_model_license_metadata.sh
assert_report_contains "$ARTIFACT_DIR/model-license-collector-missing-review.properties" "status=failed"
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/model-license-collector-missing-review.properties" \
  "ModelLicenseMetadataCollection/v1" \
  "model-license-review"
assert_report_contains "$ARTIFACT_DIR/model-license-collector-missing-review.properties" "failedTarget=input-file"
assert_report_contains "$ARTIFACT_DIR/model-license-collector-missing-review.properties" "reason=missing-review-file"
assert_report_contains "$ARTIFACT_DIR/model-license-collector-missing-review.properties" "reviewFile=$TMP_DIR/missing-model-license-review.json"
assert_report_contains "$ARTIFACT_DIR/model-license-collector-missing-review.properties" "reviewSha256="
assert_report_contains "$ARTIFACT_DIR/model-license-collector-missing-review.properties" "manifestSha256=$(shasum -a 256 "$MODEL_LICENSE_MANIFEST" | awk '{print $1}')"
expect_failure \
  "model license verifier rejects incomplete review records" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_PENDING" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-pending.properties"
assert_release_verifier_failed_report \
  "$ARTIFACT_DIR/model-license-pending.properties" \
  "ModelLicenseReviewVerification/v1" \
  "model-license-review"
assert_report_contains "$ARTIFACT_DIR/model-license-pending.properties" "metadataMaxAgeDays=36500"
assert_report_contains "$ARTIFACT_DIR/model-license-pending.properties" "failedTarget=model-license-review-record"
cat > "$MODEL_LICENSE_APPROVED" <<MODEL_LICENSE_APPROVED_JSON
{
  "version": 1,
  "models": [
    {
      "id": "chat-e2b",
      "repository": "example/chat-e2b",
      "upstreamRevision": "chat-revision-a",
      "status": "approved",
      "licenseName": "Apache-2.0",
      "licenseUrl": "https://huggingface.co/example/chat-e2b/blob/chat-revision-a/README.md",
      "redistributionDecision": "approved",
      "attributionNotice": "Include Apache-2.0 notice.",
      "reviewer": "Model Reviewer",
      "reviewDate": "2026-06-06",
      "reviewEvidencePath": "$MODEL_LICENSE_CHAT_EVIDENCE",
      "reviewEvidenceSha256": "$MODEL_LICENSE_CHAT_EVIDENCE_SHA"
    },
    {
      "id": "memory-embedding-300m",
      "repository": "example/memory-embedding-300m",
      "upstreamRevision": "memory-revision-a",
      "status": "approved",
      "licenseName": "Apache-2.0",
      "licenseUrl": "https://huggingface.co/example/memory-embedding-300m/blob/memory-revision-a/README.md",
      "redistributionDecision": "approved",
      "attributionNotice": "Include Apache-2.0 notice.",
      "reviewer": "Model Reviewer",
      "reviewDate": "2026-06-06",
      "reviewEvidencePath": "$MODEL_LICENSE_MEMORY_EVIDENCE",
      "reviewEvidenceSha256": "$MODEL_LICENSE_MEMORY_EVIDENCE_SHA"
    }
  ]
}
MODEL_LICENSE_APPROVED_JSON
expect_success \
  "model license verifier accepts approved metadata-aligned records" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_APPROVED" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-approved.properties"
assert_release_verifier_passed_report \
  "$ARTIFACT_DIR/model-license-approved.properties" \
  "ModelLicenseReviewVerification/v1" \
  "model-license-review"
assert_report_contains "$ARTIFACT_DIR/model-license-approved.properties" "reviewSha256=$(shasum -a 256 "$MODEL_LICENSE_APPROVED" | awk '{print $1}')"
MODEL_LICENSE_MISSING_REVIEW_FILE_EVIDENCE="$TMP_DIR/model-license-missing-review-file-evidence.properties"
grep -Ev '^modelLicenseReviewFile=' "$MODEL_LICENSE_CHAT_EVIDENCE" > "$MODEL_LICENSE_MISSING_REVIEW_FILE_EVIDENCE"
MODEL_LICENSE_MISSING_REVIEW_FILE_EVIDENCE_SHA="$(shasum -a 256 "$MODEL_LICENSE_MISSING_REVIEW_FILE_EVIDENCE" | awk '{print $1}')"
MODEL_LICENSE_MISSING_REVIEW_FILE_RECORD="$TMP_DIR/model-license-missing-review-file-evidence.json"
python3 - "$MODEL_LICENSE_APPROVED" "$MODEL_LICENSE_MISSING_REVIEW_FILE_RECORD" "$MODEL_LICENSE_MISSING_REVIEW_FILE_EVIDENCE" "$MODEL_LICENSE_MISSING_REVIEW_FILE_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["models"][0]["reviewEvidencePath"] = sys.argv[3]
record["models"][0]["reviewEvidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "model license verifier rejects review evidence missing review file binding" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_MISSING_REVIEW_FILE_RECORD" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-missing-review-file-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-missing-review-file-evidence.properties" "chat-e2b-review-evidence-review-file-missing"
MODEL_LICENSE_MISMATCH_REVIEW_FILE_EVIDENCE="$TMP_DIR/model-license-mismatch-review-file-evidence.properties"
sed 's#^modelLicenseReviewFile=.*#modelLicenseReviewFile=/tmp/other-model-license-review.json#' \
  "$MODEL_LICENSE_CHAT_EVIDENCE" > "$MODEL_LICENSE_MISMATCH_REVIEW_FILE_EVIDENCE"
MODEL_LICENSE_MISMATCH_REVIEW_FILE_EVIDENCE_SHA="$(shasum -a 256 "$MODEL_LICENSE_MISMATCH_REVIEW_FILE_EVIDENCE" | awk '{print $1}')"
MODEL_LICENSE_MISMATCH_REVIEW_FILE_RECORD="$TMP_DIR/model-license-mismatch-review-file-evidence.json"
python3 - "$MODEL_LICENSE_APPROVED" "$MODEL_LICENSE_MISMATCH_REVIEW_FILE_RECORD" "$MODEL_LICENSE_MISMATCH_REVIEW_FILE_EVIDENCE" "$MODEL_LICENSE_MISMATCH_REVIEW_FILE_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["models"][0]["reviewEvidencePath"] = sys.argv[3]
record["models"][0]["reviewEvidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "model license verifier rejects review evidence bound to another review file" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_MISMATCH_REVIEW_FILE_RECORD" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-mismatch-review-file-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-mismatch-review-file-evidence.properties" "chat-e2b-review-evidence-review-file-mismatch"
MODEL_LICENSE_SOURCE_MISMATCH="$TMP_DIR/model-license-source-mismatch.json"
sed 's#https://huggingface.co/example/chat-e2b/blob/chat-revision-a/README.md#https://huggingface.co/example/wrong-model/blob/chat-revision-a/README.md#' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_SOURCE_MISMATCH"
expect_failure \
  "model license verifier rejects Hugging Face license source for a different repository" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_SOURCE_MISMATCH" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-source-mismatch.properties"
assert_report_contains "$ARTIFACT_DIR/model-license-source-mismatch.properties" "status=failed"
MODEL_LICENSE_REPO_ROOT="$TMP_DIR/model-license-repo-root.json"
sed 's#https://huggingface.co/example/chat-e2b/blob/chat-revision-a/README.md#https://huggingface.co/example/chat-e2b#' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_REPO_ROOT"
expect_failure \
  "model license verifier rejects Hugging Face repository root as license source" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_REPO_ROOT" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-repo-root.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-repo-root.properties" "chat-e2b-license-source-not-concrete"
MODEL_LICENSE_SOURCE_REVISION_MISMATCH="$TMP_DIR/model-license-source-revision-mismatch.json"
sed 's#https://huggingface.co/example/chat-e2b/blob/chat-revision-a/README.md#https://huggingface.co/example/chat-e2b/blob/other-revision/README.md#' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_SOURCE_REVISION_MISMATCH"
expect_failure \
  "model license verifier rejects Hugging Face license source for a different revision" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_SOURCE_REVISION_MISMATCH" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-source-revision-mismatch.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-source-revision-mismatch.properties" "chat-e2b-license-source-revision-mismatch"
MODEL_LICENSE_BAD_REVIEW_EVIDENCE_SHA="$TMP_DIR/model-license-bad-review-evidence-sha.json"
sed 's/"reviewEvidenceSha256": "'"$MODEL_LICENSE_CHAT_EVIDENCE_SHA"'"/"reviewEvidenceSha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_BAD_REVIEW_EVIDENCE_SHA"
expect_failure \
  "model license verifier rejects review evidence sha mismatch" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_BAD_REVIEW_EVIDENCE_SHA" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-bad-review-evidence-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-bad-review-evidence-sha.properties" "chat-e2b-review-evidence-sha-mismatch"
MODEL_LICENSE_PENDING_REVIEW_EVIDENCE="$TMP_DIR/model-license-pending-review-evidence.properties"
sed 's/status=approved/status=pending/' "$MODEL_LICENSE_CHAT_EVIDENCE" > "$MODEL_LICENSE_PENDING_REVIEW_EVIDENCE"
MODEL_LICENSE_PENDING_REVIEW_EVIDENCE_SHA="$(shasum -a 256 "$MODEL_LICENSE_PENDING_REVIEW_EVIDENCE" | awk '{print $1}')"
MODEL_LICENSE_BAD_REVIEW_EVIDENCE_CONTENT="$TMP_DIR/model-license-bad-review-evidence-content.json"
python3 - "$MODEL_LICENSE_APPROVED" "$MODEL_LICENSE_BAD_REVIEW_EVIDENCE_CONTENT" "$MODEL_LICENSE_PENDING_REVIEW_EVIDENCE" "$MODEL_LICENSE_PENDING_REVIEW_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["models"][0]["reviewEvidencePath"] = sys.argv[3]
record["models"][0]["reviewEvidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "model license verifier rejects pending review evidence content" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_BAD_REVIEW_EVIDENCE_CONTENT" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-bad-review-evidence-content.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-bad-review-evidence-content.properties" "chat-e2b-review-evidence-status-mismatch"
MODEL_LICENSE_WEAK_REVIEW_EVIDENCE="$TMP_DIR/model-license-weak-review-evidence.properties"
sed '/^artifactSchema=/d' "$MODEL_LICENSE_CHAT_EVIDENCE" > "$MODEL_LICENSE_WEAK_REVIEW_EVIDENCE"
MODEL_LICENSE_WEAK_REVIEW_EVIDENCE_SHA="$(shasum -a 256 "$MODEL_LICENSE_WEAK_REVIEW_EVIDENCE" | awk '{print $1}')"
MODEL_LICENSE_WEAK_REVIEW_EVIDENCE_RECORD="$TMP_DIR/model-license-weak-review-evidence.json"
python3 - "$MODEL_LICENSE_APPROVED" "$MODEL_LICENSE_WEAK_REVIEW_EVIDENCE_RECORD" "$MODEL_LICENSE_WEAK_REVIEW_EVIDENCE" "$MODEL_LICENSE_WEAK_REVIEW_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["models"][0]["reviewEvidencePath"] = sys.argv[3]
record["models"][0]["reviewEvidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "model license verifier rejects review evidence without schema" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_WEAK_REVIEW_EVIDENCE_RECORD" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-weak-review-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-weak-review-evidence.properties" "chat-e2b-review-evidence-artifactSchema-mismatch"
MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE="$TMP_DIR/model-license-future-review-evidence.properties"
sed 's/^recordedAt=.*/recordedAt=2999-01-01T00:00:00Z/' "$MODEL_LICENSE_CHAT_EVIDENCE" > "$MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE"
MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE_SHA="$(shasum -a 256 "$MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE" | awk '{print $1}')"
MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE_RECORD="$TMP_DIR/model-license-future-review-evidence.json"
python3 - "$MODEL_LICENSE_APPROVED" "$MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE_RECORD" "$MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE" "$MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["models"][0]["reviewEvidencePath"] = sys.argv[3]
record["models"][0]["reviewEvidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "model license verifier rejects future review evidence timestamp" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_FUTURE_REVIEW_EVIDENCE_RECORD" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-future-review-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-future-review-evidence.properties" "chat-e2b-review-evidence-recorded-at-in-future"
MODEL_LICENSE_STALE_METADATA="$TMP_DIR/model-license-stale-metadata.json"
sed 's/"recordedAt": "2026-06-05T00:00:00Z"/"recordedAt": "2000-01-01T00:00:00Z"/' "$MODEL_LICENSE_METADATA" > "$MODEL_LICENSE_STALE_METADATA"
expect_failure \
  "model license verifier rejects stale metadata collection" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=7 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_APPROVED" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_STALE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-stale-metadata.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-stale-metadata.properties" "metadata-recorded-at-stale"
MODEL_LICENSE_FUTURE_METADATA="$TMP_DIR/model-license-future-metadata.json"
sed 's/"recordedAt": "2026-06-05T00:00:00Z"/"recordedAt": "2999-01-01T00:00:00Z"/' "$MODEL_LICENSE_METADATA" > "$MODEL_LICENSE_FUTURE_METADATA"
expect_failure \
  "model license verifier rejects future metadata collection time" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_APPROVED" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_FUTURE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-future-metadata.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-future-metadata.properties" "metadata-recorded-at-in-future"
MODEL_LICENSE_NON_UTC_METADATA="$TMP_DIR/model-license-non-utc-metadata.json"
sed 's/"recordedAt": "2026-06-05T00:00:00Z"/"recordedAt": "2026-06-05"/' "$MODEL_LICENSE_METADATA" > "$MODEL_LICENSE_NON_UTC_METADATA"
expect_failure \
  "model license verifier rejects non-UTC metadata collection time" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_APPROVED" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_NON_UTC_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-non-utc-metadata.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-non-utc-metadata.properties" "metadata-recorded-at-not-utc"
MODEL_LICENSE_BAD_MODEL_SHA="$TMP_DIR/model-license-bad-model-sha.json"
sed 's/"modelSha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"/"modelSha": "chat-current-api-sha"/' "$MODEL_LICENSE_METADATA" > "$MODEL_LICENSE_BAD_MODEL_SHA"
expect_failure \
  "model license verifier rejects invalid metadata model sha" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_APPROVED" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_BAD_MODEL_SHA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-bad-model-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-bad-model-sha.properties" "chat-e2b-metadata-model-sha-invalid"
MODEL_LICENSE_STALE_REVIEW="$TMP_DIR/model-license-stale-review.json"
sed 's/2026-06-06/2026-06-04/g' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_STALE_REVIEW"
expect_failure \
  "model license verifier rejects review dates before metadata collection" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_STALE_REVIEW" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-stale-review.properties"
assert_report_contains "$ARTIFACT_DIR/model-license-stale-review.properties" "status=failed"
MODEL_LICENSE_FUTURE="$TMP_DIR/model-license-future.json"
sed 's/2026-06-06/2999-01-01/g' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_FUTURE"
expect_failure \
  "model license verifier rejects future review dates" \
  env MODEL_LICENSE_METADATA_MAX_AGE_DAYS=36500 MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_FUTURE" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-future.properties"
assert_report_contains "$ARTIFACT_DIR/model-license-future.properties" "status=failed"
