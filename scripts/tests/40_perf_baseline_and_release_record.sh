# shellcheck shell=bash
# Validation script tests: upgrade fixtures, verify_perf_baseline.sh, verify_release_mapping.sh, verify_release_record.sh.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

UPGRADE_FAKE_BASE_APK="$TMP_DIR/upgrade-base.apk"
UPGRADE_FAKE_CURRENT_APK="$TMP_DIR/upgrade-current.apk"
UPGRADE_FAKE_ANDROID_TEST_APK="$TMP_DIR/upgrade-current-android-test.apk"
printf 'base apk\n' > "$UPGRADE_FAKE_BASE_APK"
printf 'current apk\n' > "$UPGRADE_FAKE_CURRENT_APK"
printf 'current android test apk\n' > "$UPGRADE_FAKE_ANDROID_TEST_APK"
reset_logs
UPGRADE_FAKE_ARTIFACT_DIR="$ARTIFACT_DIR/upgrade-install-fake"
UPGRADE_FAKE_REPORT="$UPGRADE_FAKE_ARTIFACT_DIR/upgrade-install-emulator.properties"
expect_success \
  "upgrade install emulator verifier records explicit APK signing evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  UPGRADE_BASE_APK="$UPGRADE_FAKE_BASE_APK" \
  UPGRADE_CURRENT_APK="$UPGRADE_FAKE_CURRENT_APK" \
  UPGRADE_CURRENT_ANDROID_TEST_APK="$UPGRADE_FAKE_ANDROID_TEST_APK" \
  GRADLE_CMD="$FAKE_GRADLE" \
  ARTIFACT_DIR="$UPGRADE_FAKE_ARTIFACT_DIR" \
  REPORT_FILE="$UPGRADE_FAKE_REPORT" \
  scripts/verify_upgrade_install_emulator.sh
assert_report_contains "$UPGRADE_FAKE_REPORT" "status=passed"
assert_report_contains "$UPGRADE_FAKE_REPORT" "releaseFlowPassed=false"
assert_report_contains "$UPGRADE_FAKE_REPORT" "baseApkMode=explicit"
assert_report_contains "$UPGRADE_FAKE_REPORT" "currentApkMode=explicit"
assert_report_contains "$UPGRADE_FAKE_REPORT" "currentAndroidTestApkMode=explicit"
assert_report_contains "$UPGRADE_FAKE_REPORT" "baseSourceApk=$UPGRADE_FAKE_BASE_APK"
assert_report_contains "$UPGRADE_FAKE_REPORT" "currentSourceApk=$UPGRADE_FAKE_CURRENT_APK"
assert_report_contains "$UPGRADE_FAKE_REPORT" "currentAndroidTestSourceApk=$UPGRADE_FAKE_ANDROID_TEST_APK"
assert_report_contains "$UPGRADE_FAKE_REPORT" "baseVersionCode=1"
assert_report_contains "$UPGRADE_FAKE_REPORT" "baseVersionCodeRaw=1 minSdk=28 targetSdk=36"
assert_report_contains "$UPGRADE_FAKE_REPORT" "currentVersionCode=1"
assert_report_contains "$UPGRADE_FAKE_REPORT" "currentVersionCodeRaw=1 minSdk=28 targetSdk=36"
assert_report_contains "$UPGRADE_FAKE_REPORT" "signerSha256Matches=true"
assert_report_contains "$UPGRADE_FAKE_REPORT" "versionCodeIncreased=false"
assert_report_contains "$UPGRADE_FAKE_REPORT" "baseInstallOutputFile=$UPGRADE_FAKE_ARTIFACT_DIR/install-base.txt"
assert_report_contains "$UPGRADE_FAKE_REPORT" "currentInstallOutputFile=$UPGRADE_FAKE_ARTIFACT_DIR/install-current.txt"
assert_report_contains "$UPGRADE_FAKE_REPORT" "testInstallOutputFile=$UPGRADE_FAKE_ARTIFACT_DIR/install-android-test.txt"
[[ -f "$UPGRADE_FAKE_ARTIFACT_DIR/install-base.txt" ]] ||
  fail "upgrade install fake run must preserve base install output"
[[ -f "$UPGRADE_FAKE_ARTIFACT_DIR/install-current.txt" ]] ||
  fail "upgrade install fake run must preserve current install output"
[[ -f "$UPGRADE_FAKE_ARTIFACT_DIR/install-android-test.txt" ]] ||
  fail "upgrade install fake run must preserve AndroidTest install output"

VALID_PERF="$TMP_DIR/perf-baseline.properties"
VALID_PERF_SHA="1111111111111111111111111111111111111111111111111111111111111111"
PERF_RECORDED_AT="$(date -u +%Y-%m-%dT00:00:00Z)"
cat > "$VALID_PERF" <<VALID_PERF_BASELINE
artifactSchema=PerfBaseline/v1
status=passed
target=perf-baseline-record
owner=release-engineering
collectionCommand=scripts/collect_perf_baseline.sh
reproduciblePath=$VALID_PERF
deviceSerial=device-a
deviceModel=Pixel Test
androidApi=36
abi=arm64-v8a
appVersion=0.1.0
releaseArtifactSha256=$VALID_PERF_SHA
modelId=chat-e2b
backend=GPU
firstLaunchInteractiveMs=1200
modelLoadMs=3500
firstTokenMs=900
tokensPerSecond=12.5
stopGenerationRecoveryMs=200
gpuFallbackStatus=not-needed
visionInputMs=500
memorySearch5kMs=25
zvecMemoryIndex50kMs=1500
zvecMemorySearch50kMs=35
memoryPeakMb=512
oomOrAnrObserved=false
recordedAt=$PERF_RECORDED_AT
VALID_PERF_BASELINE
expect_success \
  "perf baseline verifier accepts complete record" \
  scripts/verify_perf_baseline.sh --file "$VALID_PERF" --app-version 0.1.0 --report "$ARTIFACT_DIR/perf.properties"
assert_report_contains "$ARTIFACT_DIR/perf.properties" "artifactSchema=PerfBaselineVerification/v1"
assert_report_contains "$ARTIFACT_DIR/perf.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/perf.properties" "owner=release-engineering"
assert_report_contains "$ARTIFACT_DIR/perf.properties" "failedTarget="
grep -q '^recordedAt=' "$ARTIFACT_DIR/perf.properties" ||
  fail "perf verifier report must include recordedAt"
grep -q '^command=.*scripts/verify_perf_baseline.sh' "$ARTIFACT_DIR/perf.properties" ||
  fail "perf verifier report must include reproducible command"
assert_report_contains "$ARTIFACT_DIR/perf.properties" "reproduciblePath=$VALID_PERF"
assert_report_contains "$ARTIFACT_DIR/perf.properties" "baselineSha256=$(shasum -a 256 "$VALID_PERF" | awk '{print $1}')"
expect_success \
  "perf baseline verifier accepts matching artifact sha" \
  scripts/verify_perf_baseline.sh --file "$VALID_PERF" --artifact-sha256 "$VALID_PERF_SHA" --report "$ARTIFACT_DIR/perf-sha.properties"
assert_report_contains "$ARTIFACT_DIR/perf-sha.properties" "expectedArtifactSha256=$VALID_PERF_SHA"
expect_success \
  "perf baseline verifier records performance key" \
  scripts/verify_perf_baseline.sh \
    --file "$VALID_PERF" \
    --artifact-sha256 "$VALID_PERF_SHA" \
    --app-version 0.1.0 \
    --performance-key firstLaunch \
    --report "$ARTIFACT_DIR/perf-first-launch.properties"
assert_report_contains "$ARTIFACT_DIR/perf-first-launch.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/perf-first-launch.properties" "performanceKey=firstLaunch"
assert_report_contains "$ARTIFACT_DIR/perf-first-launch.properties" "expectedArtifactSha256=$VALID_PERF_SHA"
assert_report_contains "$ARTIFACT_DIR/perf-first-launch.properties" "expectedAppVersion=0.1.0"
expect_failure \
  "perf baseline verifier rejects unknown performance key" \
  scripts/verify_perf_baseline.sh \
    --file "$VALID_PERF" \
    --performance-key typoMemoryPressure \
    --report "$ARTIFACT_DIR/perf-unknown-key.properties"
assert_report_contains "$ARTIFACT_DIR/perf-unknown-key.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/perf-unknown-key.properties" "failedTarget=performance-key"
assert_report_contains "$ARTIFACT_DIR/perf-unknown-key.properties" "reason=performance-key-invalid"
assert_report_contains "$ARTIFACT_DIR/perf-unknown-key.properties" "performanceKey=typoMemoryPressure"
PERF_MISSING_PROVENANCE="$TMP_DIR/perf-baseline-missing-provenance.properties"
sed \
  -e '/^collectionCommand=/d' \
  -e "s|reproduciblePath=$VALID_PERF|reproduciblePath=$PERF_MISSING_PROVENANCE|" \
  "$VALID_PERF" > "$PERF_MISSING_PROVENANCE"
expect_failure \
  "perf baseline verifier rejects missing collection command provenance" \
  scripts/verify_perf_baseline.sh --file "$PERF_MISSING_PROVENANCE" --report "$ARTIFACT_DIR/perf-missing-provenance.properties"
assert_report_contains "$ARTIFACT_DIR/perf-missing-provenance.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/perf-missing-provenance.properties" "failedTarget=baseline-fields"
assert_report_contains_text "$ARTIFACT_DIR/perf-missing-provenance.properties" "collectionCommand-missing"

INVALID_PERF="$TMP_DIR/perf-baseline-invalid.properties"
printf 'status=failed\n' > "$INVALID_PERF"
expect_failure \
  "perf baseline verifier rejects incomplete record" \
  scripts/verify_perf_baseline.sh --file "$INVALID_PERF" --report "$ARTIFACT_DIR/perf-invalid.properties"
assert_report_contains "$ARTIFACT_DIR/perf-invalid.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/perf-invalid.properties" "failedTarget=baseline-fields"
assert_report_contains_text "$ARTIFACT_DIR/perf-invalid.properties" "status-not-passed"
expect_failure \
  "perf baseline verifier rejects mismatched artifact sha" \
  scripts/verify_perf_baseline.sh --file "$VALID_PERF" --artifact-sha256 different-sha --report "$ARTIFACT_DIR/perf-sha-failed.properties"
assert_report_contains "$ARTIFACT_DIR/perf-sha-failed.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/perf-sha-failed.properties" "failedTarget=release-artifact"
assert_report_contains_text "$ARTIFACT_DIR/perf-sha-failed.properties" "release-artifact-sha-mismatch"
EMULATOR_PERF="$TMP_DIR/perf-baseline-emulator.properties"
sed \
  -e 's/deviceSerial=device-a/deviceSerial=emulator-5554/' \
  -e "s|reproduciblePath=$VALID_PERF|reproduciblePath=$EMULATOR_PERF|" \
  "$VALID_PERF" > "$EMULATOR_PERF"
expect_failure \
  "perf baseline verifier rejects emulator serials" \
  scripts/verify_perf_baseline.sh --file "$EMULATOR_PERF" --report "$ARTIFACT_DIR/perf-emulator.properties"
assert_report_contains "$ARTIFACT_DIR/perf-emulator.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/perf-emulator.properties" "device-serial-is-emulator"
ZERO_PERF="$TMP_DIR/perf-baseline-zero.properties"
sed \
  -e 's/firstTokenMs=900/firstTokenMs=0/' \
  -e "s|reproduciblePath=$VALID_PERF|reproduciblePath=$ZERO_PERF|" \
  "$VALID_PERF" > "$ZERO_PERF"
expect_failure \
  "perf baseline verifier rejects zero critical timings" \
  scripts/verify_perf_baseline.sh --file "$ZERO_PERF" --report "$ARTIFACT_DIR/perf-zero.properties"
assert_report_contains "$ARTIFACT_DIR/perf-zero.properties" "status=failed"
BACKEND_INVALID_PERF="$TMP_DIR/perf-baseline-backend-invalid.properties"
sed \
  -e 's/backend=GPU/backend=TPU/' \
  -e "s|reproduciblePath=$VALID_PERF|reproduciblePath=$BACKEND_INVALID_PERF|" \
  "$VALID_PERF" > "$BACKEND_INVALID_PERF"
expect_failure \
  "perf baseline verifier rejects unsupported backend" \
  scripts/verify_perf_baseline.sh --file "$BACKEND_INVALID_PERF" --report "$ARTIFACT_DIR/perf-backend-invalid.properties"
assert_report_contains "$ARTIFACT_DIR/perf-backend-invalid.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/perf-backend-invalid.properties" "failedTarget=runtime-backend"
assert_report_contains_text "$ARTIFACT_DIR/perf-backend-invalid.properties" "backend-invalid"
GPU_FALLBACK_INVALID_PERF="$TMP_DIR/perf-baseline-gpu-fallback-invalid.properties"
sed \
  -e 's/gpuFallbackStatus=not-needed/gpuFallbackStatus=unknown/' \
  -e "s|reproduciblePath=$VALID_PERF|reproduciblePath=$GPU_FALLBACK_INVALID_PERF|" \
  "$VALID_PERF" > "$GPU_FALLBACK_INVALID_PERF"
expect_failure \
  "perf baseline verifier rejects unsupported gpu fallback status" \
  scripts/verify_perf_baseline.sh --file "$GPU_FALLBACK_INVALID_PERF" --report "$ARTIFACT_DIR/perf-gpu-fallback-invalid.properties"
assert_report_contains "$ARTIFACT_DIR/perf-gpu-fallback-invalid.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/perf-gpu-fallback-invalid.properties" "failedTarget=runtime-backend"
assert_report_contains_text "$ARTIFACT_DIR/perf-gpu-fallback-invalid.properties" "gpu-fallback-status-invalid"
MODEL_INVALID_PERF="$TMP_DIR/perf-baseline-model-invalid.properties"
sed \
  -e 's/modelId=chat-e2b/modelId=memory-embedding-gemma-300m/' \
  -e "s|reproduciblePath=$VALID_PERF|reproduciblePath=$MODEL_INVALID_PERF|" \
  "$VALID_PERF" > "$MODEL_INVALID_PERF"
expect_failure \
  "perf baseline verifier rejects non-chat model profile" \
  scripts/verify_perf_baseline.sh --file "$MODEL_INVALID_PERF" --report "$ARTIFACT_DIR/perf-model-invalid.properties"
assert_report_contains "$ARTIFACT_DIR/perf-model-invalid.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/perf-model-invalid.properties" "failedTarget=model-profile"
assert_report_contains_text "$ARTIFACT_DIR/perf-model-invalid.properties" "model-id-invalid"
FUTURE_PERF="$TMP_DIR/perf-baseline-future.properties"
sed \
  -e 's/recordedAt=.*/recordedAt=2999-01-01T00:00:00Z/' \
  -e "s|reproduciblePath=$VALID_PERF|reproduciblePath=$FUTURE_PERF|" \
  "$VALID_PERF" > "$FUTURE_PERF"
expect_failure \
  "perf baseline verifier rejects future recordedAt" \
  scripts/verify_perf_baseline.sh --file "$FUTURE_PERF" --report "$ARTIFACT_DIR/perf-future.properties"
assert_report_contains "$ARTIFACT_DIR/perf-future.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/perf-future.properties" "failedTarget=baseline-timestamp"
assert_report_contains_text "$ARTIFACT_DIR/perf-future.properties" "recorded-at-invalid-or-stale"
STALE_PERF="$TMP_DIR/perf-baseline-stale.properties"
sed \
  -e 's/recordedAt=.*/recordedAt=2000-01-01T00:00:00Z/' \
  -e "s|reproduciblePath=$VALID_PERF|reproduciblePath=$STALE_PERF|" \
  "$VALID_PERF" > "$STALE_PERF"
expect_failure \
  "perf baseline verifier rejects stale recordedAt" \
  scripts/verify_perf_baseline.sh --file "$STALE_PERF" --report "$ARTIFACT_DIR/perf-stale.properties"
assert_report_contains "$ARTIFACT_DIR/perf-stale.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/perf-stale.properties" "failedTarget=baseline-timestamp"
assert_report_contains_text "$ARTIFACT_DIR/perf-stale.properties" "recorded-at-invalid-or-stale"

RELEASE_MAPPING="$TMP_DIR/mapping.txt"
printf 'com.bytedance.zgx.solin.Sample -> a:\n' > "$RELEASE_MAPPING"
expect_success \
  "release mapping verifier accepts non-empty mapping file" \
  scripts/verify_release_mapping.sh --file "$RELEASE_MAPPING" --report "$ARTIFACT_DIR/release-mapping.properties"
assert_report_contains "$ARTIFACT_DIR/release-mapping.properties" "status=passed"
grep -q '^mappingSha256=' "$ARTIFACT_DIR/release-mapping.properties" ||
  fail "release mapping report must include mapping sha"
grep -q '^mappingSizeBytes=' "$ARTIFACT_DIR/release-mapping.properties" ||
  fail "release mapping report must include mapping size"
expect_failure \
  "release mapping verifier rejects missing mapping file" \
  scripts/verify_release_mapping.sh --file "$TMP_DIR/missing-mapping.txt" --report "$ARTIFACT_DIR/release-mapping-missing.properties"
assert_report_contains "$ARTIFACT_DIR/release-mapping-missing.properties" "status=failed"
EMPTY_RELEASE_MAPPING="$TMP_DIR/empty-mapping.txt"
: > "$EMPTY_RELEASE_MAPPING"
expect_failure \
  "release mapping verifier rejects empty mapping file" \
  scripts/verify_release_mapping.sh --file "$EMPTY_RELEASE_MAPPING" --report "$ARTIFACT_DIR/release-mapping-empty.properties"
assert_report_contains "$ARTIFACT_DIR/release-mapping-empty.properties" "status=failed"

RELEASE_RECORD_ARTIFACT="$TMP_DIR/release-record.aab"
RELEASE_RECORD_REPORT="$TMP_DIR/release-record-report.properties"
RELEASE_RECORD_FAILED_REPORT="$TMP_DIR/release-record-failed-report.properties"
RELEASE_RECORD_WEAK_REPORT="$TMP_DIR/release-record-weak-report.properties"
RELEASE_RECORD_STALE_REPORT="$TMP_DIR/release-record-stale-report.properties"
RELEASE_RECORD_GATE_REPORT="$TMP_DIR/release-record-gate-report.properties"
RELEASE_RECORD_PENDING="$TMP_DIR/release-record-pending.json"
RELEASE_RECORD_APPROVED="$TMP_DIR/release-record-approved.json"
RELEASE_RECORD_BLOCKER_EVIDENCE="$TMP_DIR/release-record-privacy-review-blocker.properties"
printf 'release artifact\n' > "$RELEASE_RECORD_ARTIFACT"
RELEASE_RECORD_RECORDED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
cat > "$RELEASE_RECORD_REPORT" <<RELEASE_RECORD_REPORT_PROPERTIES
artifactSchema=LocalReleaseVerification/v1
status=passed
target=local-verification
owner=release-engineering
recordedAt=$RELEASE_RECORD_RECORDED_AT
command=scripts/verify_local.sh
reproduciblePath=$RELEASE_RECORD_REPORT
RELEASE_RECORD_REPORT_PROPERTIES
cat > "$RELEASE_RECORD_FAILED_REPORT" <<RELEASE_RECORD_FAILED_REPORT_PROPERTIES
artifactSchema=LocalReleaseVerification/v1
status=failed
target=local-verification
owner=release-engineering
recordedAt=$RELEASE_RECORD_RECORDED_AT
command=scripts/verify_local.sh
reproduciblePath=$RELEASE_RECORD_FAILED_REPORT
RELEASE_RECORD_FAILED_REPORT_PROPERTIES
printf 'status=passed\ntarget=local-verification\n' > "$RELEASE_RECORD_WEAK_REPORT"
sed 's/^recordedAt=.*/recordedAt=2000-01-01T00:00:00Z/' "$RELEASE_RECORD_REPORT" > "$RELEASE_RECORD_STALE_REPORT"
RELEASE_RECORD_ARTIFACT_SHA="$(shasum -a 256 "$RELEASE_RECORD_ARTIFACT" | awk '{print $1}')"
RELEASE_RECORD_ARTIFACT_SIZE="$(wc -c < "$RELEASE_RECORD_ARTIFACT" | tr -d ' ')"
RELEASE_RECORD_REPORT_SHA="$(shasum -a 256 "$RELEASE_RECORD_REPORT" | awk '{print $1}')"
RELEASE_RECORD_FAILED_REPORT_SHA="$(shasum -a 256 "$RELEASE_RECORD_FAILED_REPORT" | awk '{print $1}')"
RELEASE_RECORD_WEAK_REPORT_SHA="$(shasum -a 256 "$RELEASE_RECORD_WEAK_REPORT" | awk '{print $1}')"
RELEASE_RECORD_STALE_REPORT_SHA="$(shasum -a 256 "$RELEASE_RECORD_STALE_REPORT" | awk '{print $1}')"
RELEASE_RECORD_HEAD="$(git rev-parse HEAD)"
RELEASE_RECORD_NON_HEAD="$(git rev-parse HEAD^)"
RELEASE_RECORD_DATE="$(date +%F)"
cat > "$RELEASE_RECORD_BLOCKER_EVIDENCE" <<RELEASE_RECORD_BLOCKER_EVIDENCE_PROPERTIES
artifactSchema=ReleaseRecordBlockerEvidence/v1
status=accepted
target=release-record-blocker-evidence
blockerId=privacy-review
owner=Release Owner
date=$RELEASE_RECORD_DATE
decision=accepted
releaseGitCommit=$RELEASE_RECORD_HEAD
releaseArtifactSha256=$RELEASE_RECORD_ARTIFACT_SHA
reproduciblePath=$RELEASE_RECORD_BLOCKER_EVIDENCE
scope=internal-testing-risk
RELEASE_RECORD_BLOCKER_EVIDENCE_PROPERTIES
RELEASE_RECORD_BLOCKER_EVIDENCE_SHA="$(shasum -a 256 "$RELEASE_RECORD_BLOCKER_EVIDENCE" | awk '{print $1}')"
cat > "$RELEASE_RECORD_PENDING" <<'RELEASE_RECORD_PENDING_JSON'
{
  "version": 1,
  "status": "pending_release_record",
  "release": {}
}
RELEASE_RECORD_PENDING_JSON
RELEASE_RECORD_PENDING_SHA="$(shasum -a 256 "$RELEASE_RECORD_PENDING" | awk '{print $1}')"
expect_failure \
  "release record verifier rejects pending records" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_PENDING" --report "$ARTIFACT_DIR/release-record-pending.properties"
assert_release_verifier_failed_report "$ARTIFACT_DIR/release-record-pending.properties" "ReleaseRecordVerification/v1"
assert_report_contains "$ARTIFACT_DIR/release-record-pending.properties" "failedTarget=release-record"
assert_report_contains "$ARTIFACT_DIR/release-record-pending.properties" "recordSha256=$RELEASE_RECORD_PENDING_SHA"
cat > "$RELEASE_RECORD_APPROVED" <<RELEASE_RECORD_APPROVED_JSON
{
  "version": 1,
  "status": "approved",
  "release": {
    "applicationId": "com.bytedance.zgx.solin",
    "versionCode": 1,
    "versionName": "0.1.0",
    "gitCommit": "$RELEASE_RECORD_HEAD",
    "gitBranch": "main",
    "targetChannel": "internal_testing",
    "releaseDate": "$RELEASE_RECORD_DATE",
    "owner": "Release Owner",
    "reviewer": "Release Reviewer",
    "changelog": "Initial release candidate.",
    "releaseNotes": "Initial internal release.",
    "agentBehaviorSummary": "Remote OpenAI-style public read-only tool calls execute through the local Agent runtime and mixed private/action batches fail closed before execution.",
    "unsupportedCapabilities": [
      "Full PDF parsing",
      "Local image semantic understanding without a configured vision model"
    ],
    "artifact": {
      "type": "aab",
      "path": "$RELEASE_RECORD_ARTIFACT",
      "sha256": "$RELEASE_RECORD_ARTIFACT_SHA",
      "sizeBytes": $RELEASE_RECORD_ARTIFACT_SIZE,
      "signingCertificateSha256": "1111111111111111111111111111111111111111111111111111111111111111"
    },
    "verificationReports": [
      {
        "name": "local",
        "path": "$RELEASE_RECORD_REPORT",
        "sha256": "$RELEASE_RECORD_REPORT_SHA"
      }
    ],
    "blockers": [
      {
        "id": "privacy-review",
        "status": "accepted",
        "owner": "Release Owner",
        "date": "$RELEASE_RECORD_DATE",
        "evidencePath": "$RELEASE_RECORD_BLOCKER_EVIDENCE",
        "evidenceSha256": "$RELEASE_RECORD_BLOCKER_EVIDENCE_SHA",
        "riskNote": "Accepted for internal testing only."
      }
    ]
  }
}
RELEASE_RECORD_APPROVED_JSON
RELEASE_RECORD_APPROVED_SHA="$(shasum -a 256 "$RELEASE_RECORD_APPROVED" | awk '{print $1}')"
expect_success \
  "release record verifier accepts approved current record" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_APPROVED" --report "$ARTIFACT_DIR/release-record-approved.properties"
assert_release_verifier_passed_report "$ARTIFACT_DIR/release-record-approved.properties" "ReleaseRecordVerification/v1"
assert_report_contains "$ARTIFACT_DIR/release-record-approved.properties" "recordSha256=$RELEASE_RECORD_APPROVED_SHA"
grep -Eq '^gradleSha256=[0-9a-f]{64}$' "$ARTIFACT_DIR/release-record-approved.properties" ||
  fail "release record verifier report must include gradleSha256"
RELEASE_RECORD_WEAK_REPORT_JSON="$TMP_DIR/release-record-weak-report.json"
python3 - "$RELEASE_RECORD_APPROVED" "$RELEASE_RECORD_WEAK_REPORT_JSON" "$RELEASE_RECORD_WEAK_REPORT" "$RELEASE_RECORD_WEAK_REPORT_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["release"]["verificationReports"][0]["path"] = sys.argv[3]
record["release"]["verificationReports"][0]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release record verifier rejects verification report without evidence schema" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_WEAK_REPORT_JSON" --report "$ARTIFACT_DIR/release-record-weak-report.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-record-weak-report.properties" "verification-report-0-artifact-schema-missing"
RELEASE_RECORD_STALE_REPORT_JSON="$TMP_DIR/release-record-stale-report.json"
python3 - "$RELEASE_RECORD_APPROVED" "$RELEASE_RECORD_STALE_REPORT_JSON" "$RELEASE_RECORD_STALE_REPORT" "$RELEASE_RECORD_STALE_REPORT_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["release"]["verificationReports"][0]["path"] = sys.argv[3]
record["release"]["verificationReports"][0]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release record verifier rejects stale verification report evidence" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_STALE_REPORT_JSON" --report "$ARTIFACT_DIR/release-record-stale-report.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-record-stale-report.properties" "verification-report-0-recorded-at-stale"
expect_failure \
  "release record verifier rejects internal channel in public context" \
  env PUBLIC_RELEASE_CONTEXT=1 \
  ALLOW_DIRTY_RELEASE=1 \
  EXPECTED_RELEASE_ARTIFACT_PATH="$RELEASE_RECORD_ARTIFACT" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$RELEASE_RECORD_ARTIFACT_SHA" \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_APPROVED" --report "$ARTIFACT_DIR/release-record-public-internal-channel.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-public-internal-channel.properties" "status=failed"
RELEASE_RECORD_PUBLIC="$TMP_DIR/release-record-public.json"
RELEASE_RECORD_PUBLIC_NO_GATE="$TMP_DIR/release-record-public-no-gate.json"
sed 's/"targetChannel": "internal_testing"/"targetChannel": "open_testing"/' "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_PUBLIC_NO_GATE"
expect_failure \
  "release record verifier rejects public record without release gate report" \
  env PUBLIC_RELEASE_CONTEXT=1 \
  ALLOW_DIRTY_RELEASE=1 \
  EXPECTED_RELEASE_ARTIFACT_PATH="$RELEASE_RECORD_ARTIFACT" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$RELEASE_RECORD_ARTIFACT_SHA" \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_PUBLIC_NO_GATE" --report "$ARTIFACT_DIR/release-record-public-missing-gate.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-record-public-missing-gate.properties" "verification-report-release-gate-missing"
cat > "$RELEASE_RECORD_GATE_REPORT" <<RELEASE_RECORD_GATE_REPORT_PROPERTIES
artifactSchema=ReleaseGateVerification/v1
status=passed
target=release-gate
owner=release-engineering
recordedAt=$RELEASE_RECORD_RECORDED_AT
command=PUBLIC_RELEASE=1 scripts/verify_release_gate.sh
reproduciblePath=$RELEASE_RECORD_GATE_REPORT
reason=approved
headCommitSha=$RELEASE_RECORD_HEAD
releaseArtifactPath=$RELEASE_RECORD_ARTIFACT
releaseArtifactType=aab
releaseArtifactSha256=$RELEASE_RECORD_ARTIFACT_SHA
publicRelease=1
verifyReleaseRecord=1
releaseRecordFile=$RELEASE_RECORD_PUBLIC
releaseRecordReportPath=$ARTIFACT_DIR/release-record.properties
releaseRecordReportStatus=passed
releaseRecordReportSha256=1111111111111111111111111111111111111111111111111111111111111111
RELEASE_RECORD_GATE_REPORT_PROPERTIES
RELEASE_RECORD_GATE_REPORT_SHA="$(shasum -a 256 "$RELEASE_RECORD_GATE_REPORT" | awk '{print $1}')"
python3 - "$RELEASE_RECORD_PUBLIC_NO_GATE" "$RELEASE_RECORD_PUBLIC" "$RELEASE_RECORD_GATE_REPORT" "$RELEASE_RECORD_GATE_REPORT_SHA" <<'PY'
import json
import sys
from pathlib import Path

source, target, report_path = map(Path, sys.argv[1:4])
record = json.loads(source.read_text())
record["release"]["verificationReports"].append({
    "name": "release-gate",
    "path": str(report_path),
    "sha256": sys.argv[4],
})
target.write_text(json.dumps(record, indent=2))
PY
expect_success \
  "release record verifier accepts matching public aab record" \
  env PUBLIC_RELEASE_CONTEXT=1 \
  ALLOW_DIRTY_RELEASE=1 \
  EXPECTED_RELEASE_ARTIFACT_PATH="$RELEASE_RECORD_ARTIFACT" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$RELEASE_RECORD_ARTIFACT_SHA" \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_PUBLIC" --report "$ARTIFACT_DIR/release-record-public.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-public.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/release-record-public.properties" "allowDirtyRelease=1"
RELEASE_RECORD_BAD_GATE_REPORT="$TMP_DIR/release-record-bad-gate-report.properties"
sed 's/^releaseArtifactSha256=.*/releaseArtifactSha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$RELEASE_RECORD_GATE_REPORT" > "$RELEASE_RECORD_BAD_GATE_REPORT"
RELEASE_RECORD_BAD_GATE_REPORT_SHA="$(shasum -a 256 "$RELEASE_RECORD_BAD_GATE_REPORT" | awk '{print $1}')"
RELEASE_RECORD_BAD_GATE_JSON="$TMP_DIR/release-record-bad-gate-report.json"
python3 - "$RELEASE_RECORD_PUBLIC" "$RELEASE_RECORD_BAD_GATE_JSON" "$RELEASE_RECORD_BAD_GATE_REPORT" "$RELEASE_RECORD_BAD_GATE_REPORT_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["release"]["verificationReports"][1]["path"] = sys.argv[3]
record["release"]["verificationReports"][1]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release record verifier rejects release gate report bound to another artifact" \
  env PUBLIC_RELEASE_CONTEXT=1 \
  ALLOW_DIRTY_RELEASE=1 \
  EXPECTED_RELEASE_ARTIFACT_PATH="$RELEASE_RECORD_ARTIFACT" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$RELEASE_RECORD_ARTIFACT_SHA" \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_BAD_GATE_JSON" --report "$ARTIFACT_DIR/release-record-bad-gate-report.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-record-bad-gate-report.properties" "verification-report-1-release-artifact-sha-mismatch"
DIRTY_RELEASE_MARKER="$ROOT_DIR/release-record-dirty-test.tmp"
CLEANUP_PATHS+=("$DIRTY_RELEASE_MARKER")
printf 'dirty release record test\n' > "$DIRTY_RELEASE_MARKER"
expect_failure \
  "release record verifier rejects dirty public source tree" \
  env PUBLIC_RELEASE_CONTEXT=1 \
  EXPECTED_RELEASE_ARTIFACT_PATH="$RELEASE_RECORD_ARTIFACT" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$RELEASE_RECORD_ARTIFACT_SHA" \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_PUBLIC" --report "$ARTIFACT_DIR/release-record-public-dirty.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-public-dirty.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-record-public-dirty.properties" "reason=git-worktree-dirty"
rm -f "$DIRTY_RELEASE_MARKER"
RELEASE_RECORD_OTHER_ARTIFACT="$TMP_DIR/release-record-other.aab"
printf 'other release artifact\n' > "$RELEASE_RECORD_OTHER_ARTIFACT"
expect_failure \
  "release record verifier rejects mismatched public artifact path" \
  env PUBLIC_RELEASE_CONTEXT=1 \
  ALLOW_DIRTY_RELEASE=1 \
  EXPECTED_RELEASE_ARTIFACT_PATH="$RELEASE_RECORD_OTHER_ARTIFACT" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$(shasum -a 256 "$RELEASE_RECORD_OTHER_ARTIFACT" | awk '{print $1}')" \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_PUBLIC" --report "$ARTIFACT_DIR/release-record-public-artifact-mismatch.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-public-artifact-mismatch.properties" "status=failed"
RELEASE_RECORD_FUTURE="$TMP_DIR/release-record-future.json"
sed 's/"releaseDate": "'"$RELEASE_RECORD_DATE"'"/"releaseDate": "2999-01-01"/' "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_FUTURE"
expect_failure \
  "release record verifier rejects future release dates" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_FUTURE" --report "$ARTIFACT_DIR/release-record-future.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-future.properties" "status=failed"
RELEASE_RECORD_OLD_COMMIT="$TMP_DIR/release-record-old-commit.json"
sed 's/"gitCommit": "'"$RELEASE_RECORD_HEAD"'"/"gitCommit": "'"$RELEASE_RECORD_NON_HEAD"'"/' "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_OLD_COMMIT"
expect_failure \
  "release record verifier rejects non-head source commit" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_OLD_COMMIT" --report "$ARTIFACT_DIR/release-record-old-commit.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-old-commit.properties" "status=failed"
RELEASE_RECORD_BAD_SHA="$TMP_DIR/release-record-bad-sha.json"
sed 's/"sha256": "'"$RELEASE_RECORD_ARTIFACT_SHA"'"/"sha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_BAD_SHA"
expect_failure \
  "release record verifier rejects artifact sha mismatch" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_BAD_SHA" --report "$ARTIFACT_DIR/release-record-bad-sha.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-bad-sha.properties" "status=failed"
RELEASE_RECORD_BAD_BLOCKER_EVIDENCE_SHA="$TMP_DIR/release-record-bad-blocker-evidence-sha.json"
sed 's/"evidenceSha256": "'"$RELEASE_RECORD_BLOCKER_EVIDENCE_SHA"'"/"evidenceSha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_BAD_BLOCKER_EVIDENCE_SHA"
expect_failure \
  "release record verifier rejects blocker evidence sha mismatch" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_BAD_BLOCKER_EVIDENCE_SHA" --report "$ARTIFACT_DIR/release-record-bad-blocker-evidence-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-record-bad-blocker-evidence-sha.properties" "privacy-review-evidence-sha-mismatch"
RELEASE_RECORD_BAD_BLOCKER_SCHEMA_EVIDENCE="$TMP_DIR/release-record-bad-blocker-schema.properties"
grep -Ev '^artifactSchema=' "$RELEASE_RECORD_BLOCKER_EVIDENCE" > "$RELEASE_RECORD_BAD_BLOCKER_SCHEMA_EVIDENCE"
RELEASE_RECORD_BAD_BLOCKER_SCHEMA_EVIDENCE_SHA="$(shasum -a 256 "$RELEASE_RECORD_BAD_BLOCKER_SCHEMA_EVIDENCE" | awk '{print $1}')"
RELEASE_RECORD_BAD_BLOCKER_SCHEMA_JSON="$TMP_DIR/release-record-bad-blocker-schema.json"
python3 - "$RELEASE_RECORD_APPROVED" "$RELEASE_RECORD_BAD_BLOCKER_SCHEMA_JSON" "$RELEASE_RECORD_BAD_BLOCKER_SCHEMA_EVIDENCE" "$RELEASE_RECORD_BAD_BLOCKER_SCHEMA_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["release"]["blockers"][0]["evidencePath"] = sys.argv[3]
record["release"]["blockers"][0]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release record verifier rejects blocker evidence without artifact schema" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_BAD_BLOCKER_SCHEMA_JSON" --report "$ARTIFACT_DIR/release-record-bad-blocker-schema.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-record-bad-blocker-schema.properties" "privacy-review-evidence-artifact-schema-invalid"
RELEASE_RECORD_BAD_BLOCKER_ARTIFACT_EVIDENCE="$TMP_DIR/release-record-bad-blocker-artifact.properties"
sed 's/^releaseArtifactSha256=.*/releaseArtifactSha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$RELEASE_RECORD_BLOCKER_EVIDENCE" > "$RELEASE_RECORD_BAD_BLOCKER_ARTIFACT_EVIDENCE"
RELEASE_RECORD_BAD_BLOCKER_ARTIFACT_EVIDENCE_SHA="$(shasum -a 256 "$RELEASE_RECORD_BAD_BLOCKER_ARTIFACT_EVIDENCE" | awk '{print $1}')"
RELEASE_RECORD_BAD_BLOCKER_ARTIFACT_JSON="$TMP_DIR/release-record-bad-blocker-artifact.json"
python3 - "$RELEASE_RECORD_APPROVED" "$RELEASE_RECORD_BAD_BLOCKER_ARTIFACT_JSON" "$RELEASE_RECORD_BAD_BLOCKER_ARTIFACT_EVIDENCE" "$RELEASE_RECORD_BAD_BLOCKER_ARTIFACT_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["release"]["blockers"][0]["evidencePath"] = sys.argv[3]
record["release"]["blockers"][0]["evidenceSha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release record verifier rejects blocker evidence bound to another artifact" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_BAD_BLOCKER_ARTIFACT_JSON" --report "$ARTIFACT_DIR/release-record-bad-blocker-artifact.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-record-bad-blocker-artifact.properties" "privacy-review-evidence-release-artifact-sha-mismatch"
RELEASE_RECORD_FAILED_REPORT_JSON="$TMP_DIR/release-record-failed-report.json"
sed \
  -e 's#'"$RELEASE_RECORD_REPORT"'#'"$RELEASE_RECORD_FAILED_REPORT"'#' \
  -e 's#'"$RELEASE_RECORD_REPORT_SHA"'#'"$RELEASE_RECORD_FAILED_REPORT_SHA"'#' \
  "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_FAILED_REPORT_JSON"
expect_failure \
  "release record verifier rejects failed verification report" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_FAILED_REPORT_JSON" --report "$ARTIFACT_DIR/release-record-failed-report.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-failed-report.properties" "status=failed"
