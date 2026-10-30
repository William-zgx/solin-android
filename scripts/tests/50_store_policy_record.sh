# shellcheck shell=bash
# Validation script tests: verify_store_policy_record.sh.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

STORE_POLICY_NOTICE="$TMP_DIR/store-privacy-notice.md"
STORE_POLICY_MANIFEST="$TMP_DIR/AndroidManifest.xml"
STORE_POLICY_PENDING="$TMP_DIR/store-policy-pending.json"
STORE_POLICY_APPROVED="$TMP_DIR/store-policy-approved.json"
STORE_POLICY_REVIEW_EVIDENCE="$TMP_DIR/store-policy-review.properties"
cat > "$STORE_POLICY_NOTICE" <<'STORE_POLICY_NOTICE_MD'
# Solin store privacy notice

Solin stores chat sessions, user-entered chat text, model records, memory
records, and audit metadata in local Android app storage. Users can delete chat
sessions, use forgetting individual records, and use clearing explicit memory
records.

Remote model mode sends requests only to a user-configured OpenAI-compatible
chat endpoint. Remote transport requires HTTPS. The configured endpoint may
receive remote prompts and responses. Android external intents may share data
with a destination app or Android system component after confirmation.

The app does not contain a first-party analytics upload path. Model downloads
contact model hosts; network operators and model hosts may receive normal
download metadata. Android runtime permissions are disclosed for system speech
recognition, contacts, calendar, media, Usage Access, Accessibility, and
MediaProjection.
STORE_POLICY_NOTICE_MD
STORE_POLICY_NOTICE_SHA="$(shasum -a 256 "$STORE_POLICY_NOTICE" | awk '{print $1}')"
cat > "$STORE_POLICY_MANIFEST" <<'STORE_POLICY_MANIFEST_XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
</manifest>
STORE_POLICY_MANIFEST_XML
STORE_POLICY_MANIFEST_SHA="$(shasum -a 256 "$STORE_POLICY_MANIFEST" | awk '{print $1}')"
STORE_POLICY_MODEL_CAPABILITIES_SHA="$(shasum -a 256 docs/model_capability_profiles.json | awk '{print $1}')"
STORE_POLICY_MODEL_MANIFEST_SHA="$(shasum -a 256 docs/model_manifest.md | awk '{print $1}')"
cat > "$STORE_POLICY_REVIEW_EVIDENCE" <<STORE_POLICY_REVIEW_EVIDENCE_PROPERTIES
status=approved
target=store-policy-review-approved-evidence
storePolicyRecordFile=$STORE_POLICY_APPROVED
privacyNoticePath=$STORE_POLICY_NOTICE
privacyNoticeSha256=$STORE_POLICY_NOTICE_SHA
manifestPath=$STORE_POLICY_MANIFEST
manifestSha256=$STORE_POLICY_MANIFEST_SHA
modelCapabilityProfilesPath=docs/model_capability_profiles.json
modelCapabilityProfilesSha256=$STORE_POLICY_MODEL_CAPABILITIES_SHA
modelManifestPath=docs/model_manifest.md
modelManifestSha256=$STORE_POLICY_MODEL_MANIFEST_SHA
scope=store-listing-data-safety-permissions-special-access
requiredDecision=approved
approvalStatus=approved
reviewer=Store Reviewer
STORE_POLICY_REVIEW_EVIDENCE_PROPERTIES
STORE_POLICY_REVIEW_EVIDENCE_SHA="$(shasum -a 256 "$STORE_POLICY_REVIEW_EVIDENCE" | awk '{print $1}')"
cat > "$STORE_POLICY_PENDING" <<'STORE_POLICY_PENDING_JSON'
{
  "version": 1,
  "status": "pending_policy_review"
}
STORE_POLICY_PENDING_JSON
STORE_POLICY_PENDING_SHA="$(shasum -a 256 "$STORE_POLICY_PENDING" | awk '{print $1}')"
expect_failure \
  "store policy verifier rejects pending records" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_PENDING" --report "$ARTIFACT_DIR/store-policy-pending.properties"
assert_release_verifier_failed_report "$ARTIFACT_DIR/store-policy-pending.properties" "StorePolicyRecordVerification/v1"
assert_report_contains "$ARTIFACT_DIR/store-policy-pending.properties" "failedTarget=store-policy-record"
assert_report_contains "$ARTIFACT_DIR/store-policy-pending.properties" "storePolicySha256=$STORE_POLICY_PENDING_SHA"
cat > "$STORE_POLICY_APPROVED" <<STORE_POLICY_APPROVED_JSON
{
  "version": 1,
  "status": "approved",
  "privacyNoticePath": "$STORE_POLICY_NOTICE",
  "privacyNoticeSha256": "$STORE_POLICY_NOTICE_SHA",
  "appListing": {
    "appName": "Solin",
    "shortDescription": "Privacy-first Solin AI: local, optional remote, confirmed actions.",
    "fullDescription": "Solin is a privacy-first phone-side AI assistant for Android: it is locally usable with downloaded or imported models, can optionally use user-configured remote multimodal models for text and image requests, and only executes device actions after explicit confirmation. It stores user sessions locally, protects private context with confirmation, and clearly separates optional remote model calls from local-only data.",
    "category": "Productivity",
    "contactEmail": "release@solin.app",
    "privacyPolicyUrl": "https://solin.app/privacy"
  },
  "dataSafety": {
    "userDataCollected": true,
    "userDataShared": true,
    "encryptedInTransit": true,
    "userDeletable": true,
    "optionalRemoteModelEndpoints": true,
    "externalRecipients": [
      "User-configured remote model endpoints",
      "Recommended and custom model download hosts",
      "Android system or destination apps opened by confirmed external intents"
    ],
    "noFirstPartyAnalyticsUpload": true,
    "localStorageDisclosed": true,
    "remoteModelCallsDisclosed": true,
    "modelDownloadsDisclosed": true,
    "androidPermissionsDisclosed": true
  },
  "modelDownloads": {
    "describedAsLargeOptionalAssets": true,
    "declaresNotBundledInApk": true,
    "primaryChatModelProfileId": "chat-e2b",
    "primaryChatModelBytes": 2588147712,
    "primaryChatModelSha256Hex": "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
    "primaryChatModelSourceRevision": "a4a831c060880f3733135ad22f10e0e9f758f45d",
    "remoteAlternativeDisclosed": true,
    "officialLightweightChatAlternative": false
  },
  "permissions": [
    {
      "name": "android.permission.INTERNET",
      "purpose": "Connects only to user-configured remote model endpoints and model download hosts."
    },
    {
      "name": "android.permission.RECORD_AUDIO",
      "purpose": "Lets the user dictate text through explicit voice input before sending."
    }
  ],
  "specialAccessDisclosures": [
    {
      "name": "UsageAccess",
      "purpose": "Used only after confirmation to summarize the current foreground app."
    },
    {
      "name": "AccessibilityService",
      "purpose": "Used only after confirmation to read current-screen text nodes."
    },
    {
      "name": "MediaProjection",
      "purpose": "Used only after confirmation for one-shot current screenshot OCR."
    }
  ],
  "review": {
    "reviewer": "Store Reviewer",
    "reviewDate": "$(date +%F)",
    "evidencePath": "$STORE_POLICY_REVIEW_EVIDENCE",
    "evidenceSha256": "$STORE_POLICY_REVIEW_EVIDENCE_SHA"
  }
}
STORE_POLICY_APPROVED_JSON
STORE_POLICY_APPROVED_SHA="$(shasum -a 256 "$STORE_POLICY_APPROVED" | awk '{print $1}')"
expect_success \
  "store policy verifier accepts approved manifest-aligned record" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_APPROVED" --report "$ARTIFACT_DIR/store-policy-approved.properties"
assert_release_verifier_passed_report "$ARTIFACT_DIR/store-policy-approved.properties" "StorePolicyRecordVerification/v1"
assert_report_contains "$ARTIFACT_DIR/store-policy-approved.properties" "storePolicySha256=$STORE_POLICY_APPROVED_SHA"
assert_report_contains "$ARTIFACT_DIR/store-policy-approved.properties" "privacyNoticeSha256=$STORE_POLICY_NOTICE_SHA"
assert_report_contains "$ARTIFACT_DIR/store-policy-approved.properties" "manifestSha256=$STORE_POLICY_MANIFEST_SHA"
assert_report_contains "$ARTIFACT_DIR/store-policy-approved.properties" "modelCapabilityProfilesSha256=$STORE_POLICY_MODEL_CAPABILITIES_SHA"
assert_report_contains "$ARTIFACT_DIR/store-policy-approved.properties" "modelManifestSha256=$STORE_POLICY_MODEL_MANIFEST_SHA"
STORE_POLICY_MISSING_RECORD_FILE_EVIDENCE="$TMP_DIR/store-policy-missing-record-file-review.properties"
grep -Ev '^storePolicyRecordFile=' "$STORE_POLICY_REVIEW_EVIDENCE" > "$STORE_POLICY_MISSING_RECORD_FILE_EVIDENCE"
STORE_POLICY_MISSING_RECORD_FILE_EVIDENCE_SHA="$(shasum -a 256 "$STORE_POLICY_MISSING_RECORD_FILE_EVIDENCE" | awk '{print $1}')"
STORE_POLICY_MISSING_RECORD_FILE_RECORD="$TMP_DIR/store-policy-missing-record-file-review.json"
python3 - "$STORE_POLICY_APPROVED" "$STORE_POLICY_MISSING_RECORD_FILE_RECORD" "$STORE_POLICY_MISSING_RECORD_FILE_EVIDENCE" "$STORE_POLICY_MISSING_RECORD_FILE_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["review"]["evidencePath"] = sys.argv[3]
record["review"]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "store policy verifier rejects review evidence missing store policy record binding" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_MISSING_RECORD_FILE_RECORD" --report "$ARTIFACT_DIR/store-policy-missing-record-file-review.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-missing-record-file-review.properties" "review-evidence-store-policy-record-file-missing"
STORE_POLICY_MISMATCH_RECORD_FILE_EVIDENCE="$TMP_DIR/store-policy-mismatch-record-file-review.properties"
sed 's#^storePolicyRecordFile=.*#storePolicyRecordFile=/tmp/other-store-policy-record.json#' \
  "$STORE_POLICY_REVIEW_EVIDENCE" > "$STORE_POLICY_MISMATCH_RECORD_FILE_EVIDENCE"
STORE_POLICY_MISMATCH_RECORD_FILE_EVIDENCE_SHA="$(shasum -a 256 "$STORE_POLICY_MISMATCH_RECORD_FILE_EVIDENCE" | awk '{print $1}')"
STORE_POLICY_MISMATCH_RECORD_FILE_RECORD="$TMP_DIR/store-policy-mismatch-record-file-review.json"
python3 - "$STORE_POLICY_APPROVED" "$STORE_POLICY_MISMATCH_RECORD_FILE_RECORD" "$STORE_POLICY_MISMATCH_RECORD_FILE_EVIDENCE" "$STORE_POLICY_MISMATCH_RECORD_FILE_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["review"]["evidencePath"] = sys.argv[3]
record["review"]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "store policy verifier rejects review evidence bound to another store policy record" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_MISMATCH_RECORD_FILE_RECORD" --report "$ARTIFACT_DIR/store-policy-mismatch-record-file-review.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-mismatch-record-file-review.properties" "review-evidence-store-policy-record-file-mismatch"
STORE_POLICY_BAD_REVIEW_MANIFEST_EVIDENCE="$TMP_DIR/store-policy-bad-review-manifest.properties"
sed 's#^manifestSha256=.*#manifestSha256=0000000000000000000000000000000000000000000000000000000000000000#' \
  "$STORE_POLICY_REVIEW_EVIDENCE" > "$STORE_POLICY_BAD_REVIEW_MANIFEST_EVIDENCE"
STORE_POLICY_BAD_REVIEW_MANIFEST_EVIDENCE_SHA="$(shasum -a 256 "$STORE_POLICY_BAD_REVIEW_MANIFEST_EVIDENCE" | awk '{print $1}')"
STORE_POLICY_BAD_REVIEW_MANIFEST_RECORD="$TMP_DIR/store-policy-bad-review-manifest.json"
python3 - "$STORE_POLICY_APPROVED" "$STORE_POLICY_BAD_REVIEW_MANIFEST_RECORD" "$STORE_POLICY_BAD_REVIEW_MANIFEST_EVIDENCE" "$STORE_POLICY_BAD_REVIEW_MANIFEST_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["review"]["evidencePath"] = sys.argv[3]
record["review"]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "store policy verifier rejects review evidence manifest sha drift" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_BAD_REVIEW_MANIFEST_RECORD" --report "$ARTIFACT_DIR/store-policy-bad-review-manifest.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-review-manifest.properties" "review-evidence-manifest-sha-mismatch"
STORE_POLICY_BAD_MODEL_BYTES="$TMP_DIR/store-policy-bad-model-bytes.json"
sed 's/"primaryChatModelBytes": 2588147712/"primaryChatModelBytes": 1/' "$STORE_POLICY_APPROVED" > "$STORE_POLICY_BAD_MODEL_BYTES"
expect_failure \
  "store policy verifier rejects primary chat model byte drift" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_BAD_MODEL_BYTES" --report "$ARTIFACT_DIR/store-policy-bad-model-bytes.properties"
assert_report_contains "$ARTIFACT_DIR/store-policy-bad-model-bytes.properties" "failedTarget=store-model-downloads"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-model-bytes.properties" "model-downloads-primary-chat-model-bytes-mismatch"
STORE_POLICY_BAD_MODEL_SHA="$TMP_DIR/store-policy-bad-model-sha.json"
sed 's/"primaryChatModelSha256Hex": "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"/"primaryChatModelSha256Hex": "0000000000000000000000000000000000000000000000000000000000000000"/' "$STORE_POLICY_APPROVED" > "$STORE_POLICY_BAD_MODEL_SHA"
expect_failure \
  "store policy verifier rejects primary chat model sha drift" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_BAD_MODEL_SHA" --report "$ARTIFACT_DIR/store-policy-bad-model-sha.properties"
assert_report_contains "$ARTIFACT_DIR/store-policy-bad-model-sha.properties" "failedTarget=store-model-downloads"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-model-sha.properties" "model-downloads-primary-chat-model-sha-mismatch"
STORE_POLICY_BAD_MODEL_REVISION="$TMP_DIR/store-policy-bad-model-revision.json"
sed 's/"primaryChatModelSourceRevision": "a4a831c060880f3733135ad22f10e0e9f758f45d"/"primaryChatModelSourceRevision": "stale-revision"/' "$STORE_POLICY_APPROVED" > "$STORE_POLICY_BAD_MODEL_REVISION"
expect_failure \
  "store policy verifier rejects primary chat model revision drift" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_BAD_MODEL_REVISION" --report "$ARTIFACT_DIR/store-policy-bad-model-revision.properties"
assert_report_contains "$ARTIFACT_DIR/store-policy-bad-model-revision.properties" "failedTarget=store-model-downloads"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-model-revision.properties" "model-downloads-primary-chat-model-revision-mismatch"
STORE_POLICY_BAD_LIGHTWEIGHT="$TMP_DIR/store-policy-bad-lightweight.json"
sed 's/"officialLightweightChatAlternative": false/"officialLightweightChatAlternative": true/' "$STORE_POLICY_APPROVED" > "$STORE_POLICY_BAD_LIGHTWEIGHT"
expect_failure \
  "store policy verifier rejects lightweight chat alternative drift" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_BAD_LIGHTWEIGHT" --report "$ARTIFACT_DIR/store-policy-bad-lightweight.properties"
assert_report_contains "$ARTIFACT_DIR/store-policy-bad-lightweight.properties" "failedTarget=store-model-downloads"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-lightweight.properties" "model-downloads-official-lightweight-chat-alternative-mismatch"
STORE_POLICY_BAD_REMOTE_CONFIRMATION_PROFILES="$TMP_DIR/model-capabilities-bad-remote-confirmation.json"
python3 - docs/model_capability_profiles.json "$STORE_POLICY_BAD_REMOTE_CONFIRMATION_PROFILES" <<'PY'
import json
import sys
from pathlib import Path

profiles = json.loads(Path(sys.argv[1]).read_text())
profiles["remoteOpenAiCompatibleTemplates"][0]["requiresRemoteSendConfirmation"] = False
Path(sys.argv[2]).write_text(json.dumps(profiles, indent=2))
PY
expect_failure \
  "store policy verifier rejects remote model template without send confirmation" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
    MODEL_CAPABILITY_PROFILES_FILE="$STORE_POLICY_BAD_REMOTE_CONFIRMATION_PROFILES" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_APPROVED" --report "$ARTIFACT_DIR/store-policy-bad-remote-confirmation.properties"
assert_report_contains "$ARTIFACT_DIR/store-policy-bad-remote-confirmation.properties" "failedTarget=model-capability-profiles"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-remote-confirmation.properties" "model-capability-profiles-remote-confirmation-missing"
STORE_POLICY_BAD_LOCAL_REMOTE_PROFILES="$TMP_DIR/model-capabilities-bad-local-remote.json"
python3 - docs/model_capability_profiles.json "$STORE_POLICY_BAD_LOCAL_REMOTE_PROFILES" <<'PY'
import json
import sys
from pathlib import Path

profiles = json.loads(Path(sys.argv[1]).read_text())
profiles["profiles"][1]["remoteEligible"] = True
profiles["profiles"][1]["requiresRemoteSendConfirmation"] = True
Path(sys.argv[2]).write_text(json.dumps(profiles, indent=2))
PY
expect_failure \
  "store policy verifier rejects local-only model profile marked remote eligible" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
    MODEL_CAPABILITY_PROFILES_FILE="$STORE_POLICY_BAD_LOCAL_REMOTE_PROFILES" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_APPROVED" --report "$ARTIFACT_DIR/store-policy-bad-local-remote.properties"
assert_report_contains "$ARTIFACT_DIR/store-policy-bad-local-remote.properties" "failedTarget=model-capability-profiles"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-local-remote.properties" "model-capability-profiles-local-only-remote-eligible"
STORE_POLICY_BAD_VISION_PROFILES="$TMP_DIR/model-capabilities-bad-vision.json"
python3 - docs/model_capability_profiles.json "$STORE_POLICY_BAD_VISION_PROFILES" <<'PY'
import json
import sys
from pathlib import Path

profiles = json.loads(Path(sys.argv[1]).read_text())
profiles["remoteOpenAiCompatibleTemplates"][0]["capabilities"]["vision"] = True
Path(sys.argv[2]).write_text(json.dumps(profiles, indent=2))
PY
expect_failure \
  "store policy verifier rejects vision capability drift from modalities and features" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
    MODEL_CAPABILITY_PROFILES_FILE="$STORE_POLICY_BAD_VISION_PROFILES" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_APPROVED" --report "$ARTIFACT_DIR/store-policy-bad-vision.properties"
assert_report_contains "$ARTIFACT_DIR/store-policy-bad-vision.properties" "failedTarget=model-capability-profiles"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-vision.properties" "model-capability-profiles-vision-flag-mismatch"
STORE_POLICY_INCOMPLETE_NOTICE="$TMP_DIR/store-policy-incomplete-notice.md"
printf 'Solin stores user-entered chat text locally.\n' > "$STORE_POLICY_INCOMPLETE_NOTICE"
expect_failure \
  "store policy verifier rejects data safety privacy notice mismatch" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_INCOMPLETE_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_APPROVED" --report "$ARTIFACT_DIR/store-policy-notice-mismatch.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-notice-mismatch.properties" "privacy-notice-mismatch"
STORE_POLICY_BAD_POSITIONING="$TMP_DIR/store-policy-bad-positioning.json"
sed \
  -e 's#Privacy-first Solin AI: local, optional remote, confirmed actions.#Local-first AI assistant.#' \
  -e 's#Solin is a privacy-first phone-side AI assistant for Android: it is locally usable with downloaded or imported models, can optionally use user-configured remote multimodal models for text and image requests, and only executes device actions after explicit confirmation. ##' \
  "$STORE_POLICY_APPROVED" > "$STORE_POLICY_BAD_POSITIONING"
expect_failure \
  "store policy verifier rejects app listing without product positioning" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_BAD_POSITIONING" --report "$ARTIFACT_DIR/store-policy-bad-positioning.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-positioning.properties" "app-listing-privacy-first-missing"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-positioning.properties" "app-listing-remote-multimodal-missing"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-positioning.properties" "app-listing-confirmed-actions-missing"
STORE_POLICY_BAD_SHA="$TMP_DIR/store-policy-bad-sha.json"
sed 's/"privacyNoticeSha256": "'"$STORE_POLICY_NOTICE_SHA"'"/"privacyNoticeSha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$STORE_POLICY_APPROVED" > "$STORE_POLICY_BAD_SHA"
expect_failure \
  "store policy verifier rejects privacy notice sha mismatch" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_BAD_SHA" --report "$ARTIFACT_DIR/store-policy-bad-sha.properties"
assert_report_contains "$ARTIFACT_DIR/store-policy-bad-sha.properties" "status=failed"
STORE_POLICY_BAD_REVIEW_EVIDENCE_SHA="$TMP_DIR/store-policy-bad-review-evidence-sha.json"
sed 's/"evidenceSha256": "'"$STORE_POLICY_REVIEW_EVIDENCE_SHA"'"/"evidenceSha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$STORE_POLICY_APPROVED" > "$STORE_POLICY_BAD_REVIEW_EVIDENCE_SHA"
expect_failure \
  "store policy verifier rejects review evidence sha mismatch" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_BAD_REVIEW_EVIDENCE_SHA" --report "$ARTIFACT_DIR/store-policy-bad-review-evidence-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-review-evidence-sha.properties" "review-evidence-sha-mismatch"
STORE_POLICY_PENDING_REVIEW_EVIDENCE="$TMP_DIR/store-policy-pending-review.properties"
cat > "$STORE_POLICY_PENDING_REVIEW_EVIDENCE" <<STORE_POLICY_PENDING_REVIEW_EVIDENCE_PROPERTIES
status=pending
target=store-policy-review-candidate-evidence
privacyNoticePath=$STORE_POLICY_NOTICE
privacyNoticeSha256=$STORE_POLICY_NOTICE_SHA
scope=store-listing-data-safety-permissions-special-access
requiredDecision=approved
approvalStatus=not-approved
STORE_POLICY_PENDING_REVIEW_EVIDENCE_PROPERTIES
STORE_POLICY_PENDING_REVIEW_EVIDENCE_SHA="$(shasum -a 256 "$STORE_POLICY_PENDING_REVIEW_EVIDENCE" | awk '{print $1}')"
STORE_POLICY_PENDING_REVIEW_RECORD="$TMP_DIR/store-policy-pending-review-evidence.json"
python3 - "$STORE_POLICY_APPROVED" "$STORE_POLICY_PENDING_REVIEW_RECORD" "$STORE_POLICY_PENDING_REVIEW_EVIDENCE" "$STORE_POLICY_PENDING_REVIEW_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["review"]["evidencePath"] = sys.argv[3]
record["review"]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "store policy verifier rejects pending review evidence content" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_PENDING_REVIEW_RECORD" --report "$ARTIFACT_DIR/store-policy-pending-review-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-pending-review-evidence.properties" "review-evidence-status-not-approved"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-pending-review-evidence.properties" "review-evidence-approval-status-not-approved"
STORE_POLICY_BAD_REVIEW_NOTICE_EVIDENCE="$TMP_DIR/store-policy-bad-review-notice.properties"
sed "s/privacyNoticeSha256=$STORE_POLICY_NOTICE_SHA/privacyNoticeSha256=0000000000000000000000000000000000000000000000000000000000000000/" \
  "$STORE_POLICY_REVIEW_EVIDENCE" > "$STORE_POLICY_BAD_REVIEW_NOTICE_EVIDENCE"
STORE_POLICY_BAD_REVIEW_NOTICE_EVIDENCE_SHA="$(shasum -a 256 "$STORE_POLICY_BAD_REVIEW_NOTICE_EVIDENCE" | awk '{print $1}')"
STORE_POLICY_BAD_REVIEW_NOTICE_RECORD="$TMP_DIR/store-policy-bad-review-notice-evidence.json"
python3 - "$STORE_POLICY_APPROVED" "$STORE_POLICY_BAD_REVIEW_NOTICE_RECORD" "$STORE_POLICY_BAD_REVIEW_NOTICE_EVIDENCE" "$STORE_POLICY_BAD_REVIEW_NOTICE_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["review"]["evidencePath"] = sys.argv[3]
record["review"]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "store policy verifier rejects review evidence notice sha mismatch" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_BAD_REVIEW_NOTICE_RECORD" --report "$ARTIFACT_DIR/store-policy-bad-review-notice-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-review-notice-evidence.properties" "review-evidence-privacy-notice-sha-mismatch"
STORE_POLICY_BAD_REVIEWER_EVIDENCE="$TMP_DIR/store-policy-bad-reviewer.properties"
sed 's/reviewer=Store Reviewer/reviewer=Different Reviewer/' \
  "$STORE_POLICY_REVIEW_EVIDENCE" > "$STORE_POLICY_BAD_REVIEWER_EVIDENCE"
STORE_POLICY_BAD_REVIEWER_EVIDENCE_SHA="$(shasum -a 256 "$STORE_POLICY_BAD_REVIEWER_EVIDENCE" | awk '{print $1}')"
STORE_POLICY_BAD_REVIEWER_RECORD="$TMP_DIR/store-policy-bad-reviewer-evidence.json"
python3 - "$STORE_POLICY_APPROVED" "$STORE_POLICY_BAD_REVIEWER_RECORD" "$STORE_POLICY_BAD_REVIEWER_EVIDENCE" "$STORE_POLICY_BAD_REVIEWER_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["review"]["evidencePath"] = sys.argv[3]
record["review"]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "store policy verifier rejects review evidence reviewer mismatch" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_BAD_REVIEWER_RECORD" --report "$ARTIFACT_DIR/store-policy-bad-reviewer-evidence.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-bad-reviewer-evidence.properties" "review-evidence-reviewer-mismatch"
STORE_POLICY_EXTRA_PERMISSION="$TMP_DIR/store-policy-extra-permission.json"
sed 's/"name": "android.permission.RECORD_AUDIO"/"name": "android.permission.READ_CONTACTS"/' "$STORE_POLICY_APPROVED" > "$STORE_POLICY_EXTRA_PERMISSION"
expect_failure \
  "store policy verifier rejects manifest permission mismatch" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_EXTRA_PERMISSION" --report "$ARTIFACT_DIR/store-policy-permission-mismatch.properties"
assert_report_contains "$ARTIFACT_DIR/store-policy-permission-mismatch.properties" "status=failed"
STORE_POLICY_PLACEHOLDER_CONTACT="$TMP_DIR/store-policy-placeholder-contact.json"
sed \
  -e 's#release@solin.app#release@example.com#' \
  -e 's#https://solin.app/privacy#https://example.com/privacy#' \
  "$STORE_POLICY_APPROVED" > "$STORE_POLICY_PLACEHOLDER_CONTACT"
expect_failure \
  "store policy verifier rejects placeholder contact and privacy URL" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_PLACEHOLDER_CONTACT" --report "$ARTIFACT_DIR/store-policy-placeholder-contact.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-placeholder-contact.properties" "contact-email-placeholder"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-placeholder-contact.properties" "privacy-policy-url-placeholder"
