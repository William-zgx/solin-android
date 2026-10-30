# shellcheck shell=bash
# Validation script tests: verify_release_operations_record.sh.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

OPERATIONS_PENDING="$TMP_DIR/release-operations-pending.json"
OPERATIONS_APPROVED="$TMP_DIR/release-operations-approved.json"
OPERATIONS_DATE="$(date +%F)"
OPERATIONS_RECORDED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
OPERATIONS_MONITORING_EVIDENCE="$TMP_DIR/release-operations-monitoring.properties"
OPERATIONS_MONITORING_PENDING_EVIDENCE="$TMP_DIR/release-operations-monitoring-pending.properties"
OPERATIONS_MONITORING_PENDING_RECORD="$TMP_DIR/release-operations-monitoring-pending.json"
OPERATIONS_SMOKE_EVIDENCE="$TMP_DIR/release-operations-smoke.properties"
OPERATIONS_SMOKE_FAILED_EVIDENCE="$TMP_DIR/release-operations-smoke-failed.properties"
OPERATIONS_SMOKE_FAILED_RECORD="$TMP_DIR/release-operations-smoke-failed.json"
OPERATIONS_SMOKE_BAD_LOGCAT_EVIDENCE="$TMP_DIR/release-operations-smoke-bad-logcat.properties"
OPERATIONS_SMOKE_BAD_LOGCAT_RECORD="$TMP_DIR/release-operations-smoke-bad-logcat.json"
OPERATIONS_SMOKE_DEVICE_REPORT="$TMP_DIR/release-operations-smoke-device.properties"
OPERATIONS_SMOKE_INSTRUMENTATION="$TMP_DIR/release-operations-smoke-instrumentation.txt"
OPERATIONS_SMOKE_LOGCAT="$TMP_DIR/release-operations-smoke-logcat.txt"
OPERATIONS_SMOKE_ANR_LOGCAT="$TMP_DIR/release-operations-smoke-anr-logcat.txt"
OPERATIONS_SMOKE_ANR_REPORT="$TMP_DIR/release-operations-smoke-anr.properties"
OPERATIONS_SMOKE_JAVA_CRASH_LOGCAT="$TMP_DIR/release-operations-smoke-java-crash-logcat.txt"
OPERATIONS_SMOKE_JAVA_CRASH_REPORT="$TMP_DIR/release-operations-smoke-java-crash.properties"
OPERATIONS_SMOKE_INSTRUMENTATION_CRASH="$TMP_DIR/release-operations-smoke-instrumentation-crash.txt"
OPERATIONS_SMOKE_INSTRUMENTATION_CRASH_REPORT="$TMP_DIR/release-operations-smoke-instrumentation-crash.properties"
OPERATIONS_ROLLBACK_EVIDENCE="$TMP_DIR/release-operations-rollback.properties"
OPERATIONS_ROLLBACK_PENDING_EVIDENCE="$TMP_DIR/release-operations-rollback-pending.properties"
OPERATIONS_ROLLBACK_PENDING_RECORD="$TMP_DIR/release-operations-rollback-pending.json"
OPERATIONS_CI_LOCAL_EVIDENCE="$TMP_DIR/release-operations-ci-local.properties"
OPERATIONS_CI_CONNECTED_EVIDENCE="$TMP_DIR/release-operations-ci-connected.properties"
OPERATIONS_CI_API_MATRIX_EVIDENCE="$TMP_DIR/release-operations-ci-api-matrix.properties"
OPERATIONS_CI_API_MATRIX_WEAK_EVIDENCE="$TMP_DIR/release-operations-ci-api-matrix-weak.properties"
OPERATIONS_CI_ARTIFACT_EVIDENCE="$TMP_DIR/release-operations-ci-artifact-archive.properties"
OPERATIONS_CI_SIGNING_EVIDENCE="$TMP_DIR/release-operations-ci-signing.properties"
OPERATIONS_CI_SIGNING_WEAK_EVIDENCE="$TMP_DIR/release-operations-ci-signing-weak.properties"
OPERATIONS_CI_CONNECTED_WEAK_EVIDENCE="$TMP_DIR/release-operations-ci-connected-weak.properties"
OPERATIONS_COMMIT_SHA="$(git rev-parse HEAD)"
OPERATIONS_FAKE_SHA_1="1111111111111111111111111111111111111111111111111111111111111111"
OPERATIONS_FAKE_SHA_2="2222222222222222222222222222222222222222222222222222222222222222"
OPERATIONS_FAKE_SHA_3="3333333333333333333333333333333333333333333333333333333333333333"
OPERATIONS_FAKE_SHA_4="4444444444444444444444444444444444444444444444444444444444444444"
cat > "$OPERATIONS_MONITORING_EVIDENCE" <<OPERATIONS_MONITORING_EVIDENCE_PROPERTIES
artifactSchema=ReleaseMonitoringEvidence/v1
status=passed
target=release-monitoring-evidence
owner=Release Owner
recordedAt=$OPERATIONS_RECORDED_AT
command=manual-release-monitoring-review
reproduciblePath=$OPERATIONS_MONITORING_EVIDENCE
operationsRecordField=monitoring.evidence
operationsRecordFile=$OPERATIONS_APPROVED
signalSources=Android Vitals,Internal dogfood feedback
first24HoursWatcher=Launch Watcher
crashFreeRateThresholdPercent=99.5
anrRateThresholdPercent=1.0
privacyReviewedForCrashSdk=true
OPERATIONS_MONITORING_EVIDENCE_PROPERTIES
cat > "$OPERATIONS_CI_LOCAL_EVIDENCE" <<OPERATIONS_CI_LOCAL_EVIDENCE_PROPERTIES
status=passed
target=ci-local-verification
workflow=Android Verification
job=verify
runId=123456
commitSha=$OPERATIONS_COMMIT_SHA
command=scripts/verify_local.sh
OPERATIONS_CI_LOCAL_EVIDENCE_PROPERTIES
cat > "$OPERATIONS_CI_CONNECTED_EVIDENCE" <<OPERATIONS_CI_CONNECTED_EVIDENCE_PROPERTIES
status=passed
exit_code=0
target=regression-emulator
failedTarget=
reason=
workflow=Android Verification
job=emulator-regression
runId=123456
commitSha=$OPERATIONS_COMMIT_SHA
clean_device=1
source_android_test_count=20
expected_android_test_count=20
actual_android_test_count=20
serial=emulator-5554
api_level=36
abi=arm64-v8a
avd=solin_ci_api36_arm64
instrumentation_output_file=$TMP_DIR/ci-instrumentation.txt
device_report_file=$TMP_DIR/ci-device-verification.properties
OPERATIONS_CI_CONNECTED_EVIDENCE_PROPERTIES
OPERATIONS_CI_API_MATRIX_READINESS="$TMP_DIR/ci-api-matrix-readiness.properties"
cat > "$OPERATIONS_CI_API_MATRIX_READINESS" <<OPERATIONS_CI_API_MATRIX_READINESS_PROPERTIES
status=passed
target=emulator-api-matrix-readiness
requiredApis=28,32,33,34,36
installedSystemImageApis=28,32,33,34,36
availableAvdApis=28,32,33,34,36
missingSystemImageApis=
missingAvdApis=
tag=google_apis
abi=arm64-v8a
OPERATIONS_CI_API_MATRIX_READINESS_PROPERTIES
OPERATIONS_CI_API_MATRIX_READINESS_SHA="$(shasum -a 256 "$OPERATIONS_CI_API_MATRIX_READINESS" | awk '{print $1}')"
OPERATIONS_CI_API_MATRIX_LINES=()
for api_level in 28 32 33 34 36; do
  api_report="$TMP_DIR/ci-api-${api_level}-regression.properties"
  cat > "$api_report" <<OPERATIONS_CI_API_REGRESSION_PROPERTIES
status=passed
target=regression-emulator
clean_device=1
source_android_test_count=20
expected_android_test_count=20
actual_android_test_count=20
serial=emulator-${api_level}
api_level=${api_level}
abi=arm64-v8a
avd=solin_ci_api${api_level}_arm64_v8a
instrumentation_output_file=$TMP_DIR/ci-api-${api_level}-instrumentation.txt
device_report_file=$TMP_DIR/ci-api-${api_level}-device-verification.properties
OPERATIONS_CI_API_REGRESSION_PROPERTIES
  api_report_sha="$(shasum -a 256 "$api_report" | awk '{print $1}')"
  OPERATIONS_CI_API_MATRIX_LINES+=("api${api_level}Status=passed")
  OPERATIONS_CI_API_MATRIX_LINES+=("api${api_level}Avd=solin_ci_api${api_level}_arm64_v8a")
  OPERATIONS_CI_API_MATRIX_LINES+=("api${api_level}ReportFile=$api_report")
  OPERATIONS_CI_API_MATRIX_LINES+=("api${api_level}ReportSha256=$api_report_sha")
done
{
  printf 'status=passed\n'
  printf 'target=regression-emulator-api-matrix\n'
  printf 'failedTarget=\n'
  printf 'reason=\n'
  printf 'workflow=Android Verification\n'
  printf 'job=emulator-api-matrix\n'
  printf 'runId=123456\n'
  printf 'commitSha=%s\n' "$OPERATIONS_COMMIT_SHA"
  printf 'artifactDir=%s\n' "$TMP_DIR/ci-api-matrix"
  printf 'requiredApis=28,32,33,34,36\n'
  printf 'tag=google_apis\n'
  printf 'abi=arm64-v8a\n'
  printf 'readinessReportFile=%s\n' "$OPERATIONS_CI_API_MATRIX_READINESS"
  printf 'readinessReportSha256=%s\n' "$OPERATIONS_CI_API_MATRIX_READINESS_SHA"
  printf 'passedApis=28,32,33,34,36\n'
  printf 'failedApis=\n'
  printf '%s\n' "${OPERATIONS_CI_API_MATRIX_LINES[@]}"
} > "$OPERATIONS_CI_API_MATRIX_EVIDENCE"
cat > "$OPERATIONS_CI_API_MATRIX_WEAK_EVIDENCE" <<OPERATIONS_CI_API_MATRIX_WEAK_EVIDENCE_PROPERTIES
status=passed
target=regression-emulator-api-matrix
workflow=Android Verification
job=emulator-api-matrix
runId=123456
commitSha=$OPERATIONS_COMMIT_SHA
requiredApis=28,32
passedApis=28
failedApis=32
readinessReportFile=$OPERATIONS_CI_API_MATRIX_READINESS
readinessReportSha256=$OPERATIONS_CI_API_MATRIX_READINESS_SHA
OPERATIONS_CI_API_MATRIX_WEAK_EVIDENCE_PROPERTIES
OPERATIONS_CI_ARTIFACT_SCAN_REPORT="$TMP_DIR/release-operations-ci-artifact-scan.properties"
cat > "$OPERATIONS_CI_ARTIFACT_SCAN_REPORT" <<OPERATIONS_CI_ARTIFACT_SCAN_PROPERTIES
status=passed
target=android-artifact-scan
artifactSchema=AndroidArtifactScanReport/v1
owner=release-engineering
recordedAt=2026-06-06T00:00:00Z
command=scripts/scan_android_artifacts.sh --aab app/build/outputs/bundle/release/app-release.aab --apk app/build/outputs/apk/release/app-release-unsigned.apk --report $OPERATIONS_CI_ARTIFACT_SCAN_REPORT
reproduciblePath=$OPERATIONS_CI_ARTIFACT_SCAN_REPORT
reason=approved
artifactCount=2
findingCount=0
artifact1Path=app/build/outputs/bundle/release/app-release.aab
artifact1Sha256=$OPERATIONS_FAKE_SHA_1
artifact1SizeBytes=123
artifact2Path=app/build/outputs/apk/release/app-release-unsigned.apk
artifact2Sha256=$OPERATIONS_FAKE_SHA_2
artifact2SizeBytes=456
OPERATIONS_CI_ARTIFACT_SCAN_PROPERTIES
OPERATIONS_CI_ARTIFACT_SCAN_SHA="$(shasum -a 256 "$OPERATIONS_CI_ARTIFACT_SCAN_REPORT" | awk '{print $1}')"
cat > "$OPERATIONS_CI_ARTIFACT_EVIDENCE" <<OPERATIONS_CI_ARTIFACT_EVIDENCE_PROPERTIES
status=passed
target=ci-release-artifact-archive
artifactSchema=ReleaseArtifactArchiveEvidence/v1
owner=release-engineering
recordedAt=2026-06-06T00:00:00Z
command=scripts/archive_release_artifacts.sh
reproduciblePath=$OPERATIONS_CI_ARTIFACT_EVIDENCE
workflow=Android Verification
job=release-artifact-archive
runId=123456
commitSha=$OPERATIONS_COMMIT_SHA
artifactUploadName=android-release-artifacts
aabPath=app/build/outputs/bundle/release/app-release.aab
aabSha256=$OPERATIONS_FAKE_SHA_1
apkPath=app/build/outputs/apk/release/app-release-unsigned.apk
apkSha256=$OPERATIONS_FAKE_SHA_2
mappingPath=app/build/outputs/mapping/release/mapping.txt
mappingSha256=$OPERATIONS_FAKE_SHA_3
artifactScanReport=$OPERATIONS_CI_ARTIFACT_SCAN_REPORT
artifactScanReportSha256=$OPERATIONS_CI_ARTIFACT_SCAN_SHA
artifactScanStatus=passed
OPERATIONS_CI_ARTIFACT_EVIDENCE_PROPERTIES
OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_REPORT="$TMP_DIR/release-operations-ci-signing-artifact-scan.properties"
cat > "$OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_REPORT" <<OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_PROPERTIES
status=passed
target=android-artifact-scan
artifactSchema=AndroidArtifactScanReport/v1
owner=release-engineering
recordedAt=2026-06-06T00:00:00Z
command=scripts/scan_android_artifacts.sh --require-signed --aab app/build/outputs/bundle/release/app-release-signed.aab --report $OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_REPORT
reproduciblePath=$OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_REPORT
reason=approved
artifactCount=1
findingCount=0
artifact1Path=app/build/outputs/bundle/release/app-release-signed.aab
artifact1Sha256=$OPERATIONS_FAKE_SHA_1
artifact1SizeBytes=123
OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_PROPERTIES
OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_SHA="$(shasum -a 256 "$OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_REPORT" | awk '{print $1}')"
cat > "$OPERATIONS_CI_SIGNING_EVIDENCE" <<OPERATIONS_CI_SIGNING_EVIDENCE_PROPERTIES
status=passed
exit_code=0
target=release-signing
artifactSchema=ReleaseSigningReport/v1
owner=release-engineering
recordedAt=2026-06-06T00:00:00Z
command=scripts/sign_release_artifacts.sh
reproduciblePath=$OPERATIONS_CI_SIGNING_EVIDENCE
workflow=Android Verification
job=protected-signing
runId=123456
commitSha=$OPERATIONS_COMMIT_SHA
signingMode=production
allowDebugKeystore=0
requireAab=1
expectedSigningCertSha256=$OPERATIONS_FAKE_SHA_4
signedApk=app/build/outputs/apk/release/app-release-signed.apk
signedApkSha256=$OPERATIONS_FAKE_SHA_2
signedAab=app/build/outputs/bundle/release/app-release-signed.aab
signedAabSha256=$OPERATIONS_FAKE_SHA_1
signedAndroidTestApk=app/build/outputs/apk/androidTest/release/app-release-androidTest-signed.apk
signedAndroidTestApkSha256=$OPERATIONS_FAKE_SHA_2
appSignerSha256=$OPERATIONS_FAKE_SHA_4
androidTestSignerSha256=$OPERATIONS_FAKE_SHA_4
aabSignerSha256=$OPERATIONS_FAKE_SHA_4
appAndAndroidTestSignersMatch=true
artifactScanReport=$OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_REPORT
artifactScanReportSha256=$OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_SHA
artifactScanStatus=passed
OPERATIONS_CI_SIGNING_EVIDENCE_PROPERTIES
cat > "$OPERATIONS_CI_SIGNING_WEAK_EVIDENCE" <<OPERATIONS_CI_SIGNING_WEAK_PROPERTIES
status=passed
exit_code=0
target=release-signing
workflow=Android Verification
job=protected-signing
runId=123456
commitSha=$OPERATIONS_COMMIT_SHA
signingMode=production
allowDebugKeystore=0
requireAab=1
expectedSigningCertSha256=$OPERATIONS_FAKE_SHA_4
signedAab=app/build/outputs/bundle/release/app-release-signed.aab
signedAabSha256=$OPERATIONS_FAKE_SHA_1
artifactScanReport=$OPERATIONS_CI_SIGNING_ARTIFACT_SCAN_REPORT
artifactScanStatus=passed
OPERATIONS_CI_SIGNING_WEAK_PROPERTIES
cat > "$OPERATIONS_CI_CONNECTED_WEAK_EVIDENCE" <<OPERATIONS_CI_CONNECTED_WEAK_EVIDENCE_PROPERTIES
status=passed
target=ci-local-verification
command=scripts/verify_local.sh
OPERATIONS_CI_CONNECTED_WEAK_EVIDENCE_PROPERTIES
cat > "$OPERATIONS_SMOKE_INSTRUMENTATION" <<'OPERATIONS_SMOKE_INSTRUMENTATION_TXT'
INSTRUMENTATION_STATUS: numtests=3
OK (3 tests)
INSTRUMENTATION_CODE: -1
OPERATIONS_SMOKE_INSTRUMENTATION_TXT
cat > "$OPERATIONS_SMOKE_DEVICE_REPORT" <<OPERATIONS_SMOKE_DEVICE_REPORT_TXT
status=passed
target=device
serial=emulator-5554
api_level=36
abi=arm64-v8a
instrumentation=passed
instrumentation_test_count=3
instrumentation_output_file=$OPERATIONS_SMOKE_INSTRUMENTATION
logcat_file=$OPERATIONS_SMOKE_LOGCAT
OPERATIONS_SMOKE_DEVICE_REPORT_TXT
cat > "$OPERATIONS_SMOKE_LOGCAT" <<'OPERATIONS_SMOKE_LOGCAT_TXT'
06-06 20:00:00.000  1000  1000 I ActivityTaskManager: Displayed com.bytedance.zgx.solin/.MainActivity
06-06 20:00:01.000  1000  1000 I LiteRT: nativeCheckLoaded returned true
OPERATIONS_SMOKE_LOGCAT_TXT
expect_success \
  "crash/ANR smoke collector accepts clean instrumentation and logcat" \
  env OPERATIONS_RECORD_FILE="$OPERATIONS_APPROVED" scripts/collect_crash_anr_smoke_evidence.sh \
    --device-report "$OPERATIONS_SMOKE_DEVICE_REPORT" \
    --instrumentation-output "$OPERATIONS_SMOKE_INSTRUMENTATION" \
    --report "$OPERATIONS_SMOKE_EVIDENCE" \
    --window "2026-06-06 internal smoke" \
    --track internal_testing
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "status=passed"
assert_release_verifier_report_schema \
  "$OPERATIONS_SMOKE_EVIDENCE" \
  "CrashAnrSmokeEvidence/v1"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "operationsRecordField=crashAnrSmoke.evidence"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "operationsRecordFile=$OPERATIONS_APPROVED"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "logcatAnalyzed=true"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "instrumentationFailureSignalCount=0"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "noLaunchCrash=true"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "noInstallCrash=true"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "noCrashLoop=true"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "noFatalNativeLiteRtLmFailure=true"
assert_report_contains "$OPERATIONS_SMOKE_EVIDENCE" "noReproducibleAnr=true"
cat > "$OPERATIONS_SMOKE_ANR_LOGCAT" <<'OPERATIONS_SMOKE_ANR_LOGCAT_TXT'
06-06 20:00:02.000  1000  1000 E ActivityManager: ANR in com.bytedance.zgx.solin
OPERATIONS_SMOKE_ANR_LOGCAT_TXT
expect_failure \
  "crash/ANR smoke collector rejects ANR logcat signal" \
  scripts/collect_crash_anr_smoke_evidence.sh \
    --device-report "$OPERATIONS_SMOKE_DEVICE_REPORT" \
    --instrumentation-output "$OPERATIONS_SMOKE_INSTRUMENTATION" \
    --logcat "$OPERATIONS_SMOKE_ANR_LOGCAT" \
    --report "$OPERATIONS_SMOKE_ANR_REPORT"
assert_report_contains "$OPERATIONS_SMOKE_ANR_REPORT" "status=failed"
assert_report_contains "$OPERATIONS_SMOKE_ANR_REPORT" "noReproducibleAnr=false"
assert_report_contains_text "$OPERATIONS_SMOKE_ANR_REPORT" "anr-signal-detected"
cat > "$OPERATIONS_SMOKE_JAVA_CRASH_LOGCAT" <<'OPERATIONS_SMOKE_JAVA_CRASH_LOGCAT_TXT'
06-06 20:00:03.000  1000  1000 E AndroidRuntime: FATAL EXCEPTION: main
06-06 20:00:03.001  1000  1000 E AndroidRuntime: Process: com.bytedance.zgx.solin, PID: 12345
OPERATIONS_SMOKE_JAVA_CRASH_LOGCAT_TXT
expect_failure \
  "crash/ANR smoke collector treats one Java crash as one launch crash" \
  scripts/collect_crash_anr_smoke_evidence.sh \
    --device-report "$OPERATIONS_SMOKE_DEVICE_REPORT" \
    --instrumentation-output "$OPERATIONS_SMOKE_INSTRUMENTATION" \
    --logcat "$OPERATIONS_SMOKE_JAVA_CRASH_LOGCAT" \
    --report "$OPERATIONS_SMOKE_JAVA_CRASH_REPORT"
assert_report_contains "$OPERATIONS_SMOKE_JAVA_CRASH_REPORT" "status=failed"
assert_report_contains "$OPERATIONS_SMOKE_JAVA_CRASH_REPORT" "crashSignalCount=1"
assert_report_contains "$OPERATIONS_SMOKE_JAVA_CRASH_REPORT" "noLaunchCrash=false"
assert_report_contains "$OPERATIONS_SMOKE_JAVA_CRASH_REPORT" "noCrashLoop=true"
assert_report_contains_text "$OPERATIONS_SMOKE_JAVA_CRASH_REPORT" "crash-signal-detected"
cat > "$OPERATIONS_SMOKE_INSTRUMENTATION_CRASH" <<'OPERATIONS_SMOKE_INSTRUMENTATION_CRASH_TXT'
INSTRUMENTATION_RESULT: shortMsg=Process crashed.
INSTRUMENTATION_CODE: 0
OPERATIONS_SMOKE_INSTRUMENTATION_CRASH_TXT
expect_failure \
  "crash/ANR smoke collector rejects instrumentation process crash signal" \
  scripts/collect_crash_anr_smoke_evidence.sh \
    --device-report "$OPERATIONS_SMOKE_DEVICE_REPORT" \
    --instrumentation-output "$OPERATIONS_SMOKE_INSTRUMENTATION_CRASH" \
    --logcat "$OPERATIONS_SMOKE_LOGCAT" \
    --report "$OPERATIONS_SMOKE_INSTRUMENTATION_CRASH_REPORT"
assert_report_contains "$OPERATIONS_SMOKE_INSTRUMENTATION_CRASH_REPORT" "status=failed"
assert_report_contains "$OPERATIONS_SMOKE_INSTRUMENTATION_CRASH_REPORT" "noLaunchCrash=false"
assert_report_contains_text "$OPERATIONS_SMOKE_INSTRUMENTATION_CRASH_REPORT" "instrumentation-crash-signal-detected"
cat > "$OPERATIONS_ROLLBACK_EVIDENCE" <<OPERATIONS_ROLLBACK_EVIDENCE_PROPERTIES
artifactSchema=ReleaseRollbackEvidence/v1
status=passed
target=release-rollback-evidence
owner=Release Owner
recordedAt=$OPERATIONS_RECORDED_AT
command=manual-release-rollback-review
reproduciblePath=$OPERATIONS_ROLLBACK_EVIDENCE
operationsRecordField=rollback.evidence
operationsRecordFile=$OPERATIONS_APPROVED
decisionChannel=#solin-release
criteria=install failure,crash loop,model download verification failure,privacy boundary failure,critical tool execution regression
firstStagedRolloutAction=Halt rollout, keep collecting Android Vitals and user reports, then decide whether to resume, replace, or ship a fixed build.
playVersionCodePolicy=Any replacement artifact must use a higher versionCode; Play cannot ordinary-update users to a lower versionCode.
modelManifestRollbackPath=Revert model download metadata when supported; otherwise ship a fixed APK with a higher versionCode.
userDataCompatibility=Room migrations are forward-only, so downgrade is unsupported unless explicitly tested.
previousKnownGoodStatus=not_applicable_initial_release
previousKnownGoodReleaseNotes=Initial release has no previous production artifact.
OPERATIONS_ROLLBACK_EVIDENCE_PROPERTIES
OPERATIONS_MONITORING_SHA="$(shasum -a 256 "$OPERATIONS_MONITORING_EVIDENCE" | awk '{print $1}')"
OPERATIONS_SMOKE_SHA="$(shasum -a 256 "$OPERATIONS_SMOKE_EVIDENCE" | awk '{print $1}')"
OPERATIONS_ROLLBACK_SHA="$(shasum -a 256 "$OPERATIONS_ROLLBACK_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_LOCAL_SHA="$(shasum -a 256 "$OPERATIONS_CI_LOCAL_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_CONNECTED_SHA="$(shasum -a 256 "$OPERATIONS_CI_CONNECTED_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_API_MATRIX_SHA="$(shasum -a 256 "$OPERATIONS_CI_API_MATRIX_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_API_MATRIX_WEAK_SHA="$(shasum -a 256 "$OPERATIONS_CI_API_MATRIX_WEAK_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_ARTIFACT_SHA="$(shasum -a 256 "$OPERATIONS_CI_ARTIFACT_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_SIGNING_SHA="$(shasum -a 256 "$OPERATIONS_CI_SIGNING_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_SIGNING_WEAK_SHA="$(shasum -a 256 "$OPERATIONS_CI_SIGNING_WEAK_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_CONNECTED_WEAK_SHA="$(shasum -a 256 "$OPERATIONS_CI_CONNECTED_WEAK_EVIDENCE" | awk '{print $1}')"
cat > "$OPERATIONS_PENDING" <<'OPERATIONS_PENDING_JSON'
{
  "version": 1,
  "status": "pending_operations_review"
}
OPERATIONS_PENDING_JSON
expect_failure \
  "release operations verifier rejects pending records" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_PENDING" --report "$ARTIFACT_DIR/release-operations-pending.properties"
assert_report_contains "$ARTIFACT_DIR/release-operations-pending.properties" "status=failed"
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/release-operations-pending.properties" \
  "ReleaseOperationsVerification/v1"
OPERATIONS_SPACED_PENDING="$TMP_DIR/release operations pending.json"
OPERATIONS_SPACED_REPORT="$ARTIFACT_DIR/release operations spaced command.properties"
cat > "$OPERATIONS_SPACED_PENDING" <<'OPERATIONS_SPACED_PENDING_JSON'
{
  "version": 1,
  "status": "pending_operations_review"
}
OPERATIONS_SPACED_PENDING_JSON
expect_failure \
  "release operations report command preserves spaced argv" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_SPACED_PENDING" --report "$OPERATIONS_SPACED_REPORT"
assert_report_command_argv \
  "$OPERATIONS_SPACED_REPORT" \
  scripts/verify_release_operations_record.sh \
  --file "$OPERATIONS_SPACED_PENDING" \
  --report "$OPERATIONS_SPACED_REPORT"
OPERATIONS_MALFORMED_REPORT="$ARTIFACT_DIR/release-operations-malformed-argument.properties"
expect_failure \
  "release operations verifier reports malformed arguments when report path is known" \
  scripts/verify_release_operations_record.sh --report "$OPERATIONS_MALFORMED_REPORT" --bad-arg
assert_release_verifier_report_schema \
  "$OPERATIONS_MALFORMED_REPORT" \
  "ReleaseOperationsVerification/v1"
assert_report_contains "$OPERATIONS_MALFORMED_REPORT" "status=failed"
assert_report_contains "$OPERATIONS_MALFORMED_REPORT" "reason=unknown-argument"
assert_report_contains "$OPERATIONS_MALFORMED_REPORT" "failedTarget=argument-parser"
OPERATIONS_MISSING_VALUE_REPORT="$ARTIFACT_DIR/release-operations-missing-value.properties"
expect_failure \
  "release operations verifier reports missing argument values when report path is known" \
  scripts/verify_release_operations_record.sh --report "$OPERATIONS_MISSING_VALUE_REPORT" --file
assert_release_verifier_report_schema \
  "$OPERATIONS_MISSING_VALUE_REPORT" \
  "ReleaseOperationsVerification/v1"
assert_report_contains "$OPERATIONS_MISSING_VALUE_REPORT" "status=failed"
assert_report_contains "$OPERATIONS_MISSING_VALUE_REPORT" "reason=missing-file-argument"
assert_report_contains "$OPERATIONS_MISSING_VALUE_REPORT" "failedTarget=argument-parser"
OPERATIONS_OPTION_VALUE_REPORT="$ARTIFACT_DIR/release-operations-option-like-value.properties"
expect_failure \
  "release operations verifier treats option-like file value as missing" \
  scripts/verify_release_operations_record.sh --report "$OPERATIONS_OPTION_VALUE_REPORT" --file --bad-arg
assert_release_verifier_report_schema \
  "$OPERATIONS_OPTION_VALUE_REPORT" \
  "ReleaseOperationsVerification/v1"
assert_report_contains "$OPERATIONS_OPTION_VALUE_REPORT" "status=failed"
assert_report_contains "$OPERATIONS_OPTION_VALUE_REPORT" "reason=missing-file-argument"
assert_report_contains "$OPERATIONS_OPTION_VALUE_REPORT" "failedTarget=argument-parser"
cat > "$OPERATIONS_APPROVED" <<OPERATIONS_APPROVED_JSON
{
  "version": 1,
  "status": "approved",
  "ci": {
    "owner": "Release Engineering",
    "provider": "GitHub Actions",
    "workflowName": "Android Verification",
    "runId": "123456",
    "commitSha": "$OPERATIONS_COMMIT_SHA",
    "localVerification": {
      "status": "passed",
      "jobName": "verify",
      "evidence": {
        "path": "$OPERATIONS_CI_LOCAL_EVIDENCE",
        "sha256": "$OPERATIONS_CI_LOCAL_SHA"
      }
    },
    "connectedAndroidTests": {
      "status": "passed",
      "jobName": "emulator-regression",
      "evidence": {
        "path": "$OPERATIONS_CI_CONNECTED_EVIDENCE",
        "sha256": "$OPERATIONS_CI_CONNECTED_SHA"
      }
    },
    "apiMatrix": {
      "status": "passed",
      "jobName": "emulator-api-matrix",
      "artifactName": "android-emulator-api-matrix-evidence",
      "evidence": {
        "path": "$OPERATIONS_CI_API_MATRIX_EVIDENCE",
        "sha256": "$OPERATIONS_CI_API_MATRIX_SHA"
      }
    },
    "releaseArtifactArchive": {
      "status": "passed",
      "jobName": "release-artifact-archive",
      "artifactName": "android-release-artifacts",
      "evidence": {
        "path": "$OPERATIONS_CI_ARTIFACT_EVIDENCE",
        "sha256": "$OPERATIONS_CI_ARTIFACT_SHA"
      }
    },
    "protectedSigning": {
      "status": "passed",
      "jobName": "protected-signing",
      "signingEnvironment": "android-production-signing",
      "evidence": {
        "path": "$OPERATIONS_CI_SIGNING_EVIDENCE",
        "sha256": "$OPERATIONS_CI_SIGNING_SHA"
      }
    }
  },
  "monitoring": {
    "owner": "Release Owner",
    "signalSources": ["Android Vitals", "Internal dogfood feedback"],
    "first24HoursWatcher": "Launch Watcher",
    "crashFreeRateThresholdPercent": 99.5,
    "anrRateThresholdPercent": 1.0,
    "privacyReviewedForCrashSdk": true,
    "evidence": {
      "path": "$OPERATIONS_MONITORING_EVIDENCE",
      "sha256": "$OPERATIONS_MONITORING_SHA"
    }
  },
  "crashAnrSmoke": {
    "window": "2026-06-06 internal smoke",
    "track": "internal_testing",
    "noLaunchCrash": true,
    "noInstallCrash": true,
    "noCrashLoop": true,
    "noFatalNativeLiteRtLmFailure": true,
    "noReproducibleAnr": true,
    "failureEvidencePolicy": "Attach logcat, tombstones, and ANR traces for any failure; state no crash or ANR when none were observed.",
    "evidence": {
      "path": "$OPERATIONS_SMOKE_EVIDENCE",
      "sha256": "$OPERATIONS_SMOKE_SHA"
    }
  },
  "rollback": {
    "owner": "Release Owner",
    "decisionChannel": "#solin-release",
    "criteria": [
      "install failure",
      "crash loop",
      "model download verification failure",
      "privacy boundary failure",
      "critical tool execution regression"
    ],
    "firstStagedRolloutAction": "Halt rollout, keep collecting Android Vitals and user reports, then decide whether to resume, replace, or ship a fixed build.",
    "playVersionCodePolicy": "Any replacement artifact must use a higher versionCode; Play cannot ordinary-update users to a lower versionCode.",
    "modelManifestRollbackPath": "Revert model download metadata when supported; otherwise ship a fixed APK with a higher versionCode.",
    "userDataCompatibility": "Room migrations are forward-only, so downgrade is unsupported unless explicitly tested.",
    "evidence": {
      "path": "$OPERATIONS_ROLLBACK_EVIDENCE",
      "sha256": "$OPERATIONS_ROLLBACK_SHA"
    },
    "previousKnownGood": {
      "status": "not_applicable_initial_release",
      "versionCode": 0,
      "versionName": "",
      "gitCommit": "",
      "artifactPath": "",
      "artifactSha256": "",
      "releaseNotes": "Initial release has no previous production artifact."
    }
  },
  "review": {
    "reviewer": "Release Reviewer",
    "reviewDate": "$OPERATIONS_DATE"
  }
}
OPERATIONS_APPROVED_JSON
OPERATIONS_APPROVED_SHA="$(shasum -a 256 "$OPERATIONS_APPROVED" | awk '{print $1}')"
expect_success \
  "release operations verifier accepts approved initial-release record" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_APPROVED" --report "$ARTIFACT_DIR/release-operations-approved.properties"
assert_report_contains "$ARTIFACT_DIR/release-operations-approved.properties" "status=passed"
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/release-operations-approved.properties" \
  "ReleaseOperationsVerification/v1"
assert_report_contains "$ARTIFACT_DIR/release-operations-approved.properties" "operationsRecordSha256=$OPERATIONS_APPROVED_SHA"
OPERATIONS_BINDING_CASE_DIR="$TMP_DIR/release-operations-binding"
mkdir -p "$OPERATIONS_BINDING_CASE_DIR"
python3 - "$OPERATIONS_APPROVED" \
  "$OPERATIONS_BINDING_CASE_DIR" \
  "$OPERATIONS_MONITORING_EVIDENCE" \
  "$OPERATIONS_SMOKE_EVIDENCE" \
  "$OPERATIONS_ROLLBACK_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record_source = Path(sys.argv[1])
case_dir = Path(sys.argv[2])
evidence_sources = {
    "monitoring": Path(sys.argv[3]),
    "crashAnrSmoke": Path(sys.argv[4]),
    "rollback": Path(sys.argv[5]),
}
case_dir.mkdir(parents=True, exist_ok=True)

def read_properties(path):
    pairs = []
    for line in path.read_text().splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            pairs.append((key, value))
    return pairs

def write_bound_evidence(source, target, record_path, mode):
    pairs = [
        (key, value)
        for key, value in read_properties(source)
        if key != "operationsRecordFile"
    ]
    next_pairs = []
    inserted_binding = False
    for key, value in pairs:
        if key == "reproduciblePath":
            value = str(target)
        next_pairs.append((key, value))
        if key == "operationsRecordField" and mode != "missing":
            next_pairs.append((
                "operationsRecordFile",
                "/tmp/other-release-operations-record.json" if mode == "mismatch" else str(record_path),
            ))
            inserted_binding = True
    if not inserted_binding and mode != "missing":
        next_pairs.append((
            "operationsRecordFile",
            "/tmp/other-release-operations-record.json" if mode == "mismatch" else str(record_path),
        ))
    target.write_text("".join(f"{key}={value}\n" for key, value in next_pairs))

cases = [
    ("monitoring-missing", "monitoring", "missing"),
    ("monitoring-mismatch", "monitoring", "mismatch"),
    ("crash-anr-smoke-missing", "crashAnrSmoke", "missing"),
    ("crash-anr-smoke-mismatch", "crashAnrSmoke", "mismatch"),
    ("rollback-missing", "rollback", "missing"),
    ("rollback-mismatch", "rollback", "mismatch"),
]
for case_name, target_section, mode in cases:
    record_path = case_dir / f"{case_name}.json"
    record = json.loads(record_source.read_text())
    for section, source_path in evidence_sources.items():
        evidence_path = case_dir / f"{case_name}-{section}.properties"
        write_bound_evidence(
            source_path,
            evidence_path,
            record_path,
            mode if section == target_section else "bound",
        )
        record[section]["evidence"]["path"] = str(evidence_path)
        record[section]["evidence"]["sha256"] = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
    record_path.write_text(json.dumps(record, indent=2))
PY
while IFS='|' read -r operations_binding_label operations_binding_reason; do
  operations_binding_record="$OPERATIONS_BINDING_CASE_DIR/${operations_binding_label}.json"
  operations_binding_report="$ARTIFACT_DIR/release-operations-binding-${operations_binding_label}.properties"
  expect_failure \
    "release operations verifier rejects ${operations_binding_label} operations record binding" \
    scripts/verify_release_operations_record.sh --file "$operations_binding_record" --report "$operations_binding_report"
  assert_report_contains_text "$operations_binding_report" "$operations_binding_reason"
done <<OPERATIONS_BINDING_CASES
monitoring-missing|monitoring-evidence-operations-record-file-missing
monitoring-mismatch|monitoring-evidence-operations-record-file-mismatch
crash-anr-smoke-missing|crash-anr-smoke-evidence-operations-record-file-missing
crash-anr-smoke-mismatch|crash-anr-smoke-evidence-operations-record-file-mismatch
rollback-missing|rollback-evidence-operations-record-file-missing
rollback-mismatch|rollback-evidence-operations-record-file-mismatch
OPERATIONS_BINDING_CASES
expect_success \
  "release operations verifier accepts current release artifact context" \
  env EXPECTED_COMMIT_SHA="$OPERATIONS_COMMIT_SHA" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$OPERATIONS_FAKE_SHA_1" \
  EXPECTED_RELEASE_MAPPING_SHA256="$OPERATIONS_FAKE_SHA_3" \
  EXPECTED_SIGNING_CERT_SHA256="$OPERATIONS_FAKE_SHA_4" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_APPROVED" --report "$ARTIFACT_DIR/release-operations-current-context.properties"
assert_report_contains "$ARTIFACT_DIR/release-operations-current-context.properties" "status=passed"
sed 's/^status=passed$/status=pending/' "$OPERATIONS_MONITORING_EVIDENCE" > "$OPERATIONS_MONITORING_PENDING_EVIDENCE"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_MONITORING_PENDING_RECORD" "$OPERATIONS_MONITORING_PENDING_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record_path, target_path, evidence_path = map(Path, sys.argv[1:])
record = json.loads(record_path.read_text())
record["monitoring"]["evidence"]["path"] = str(evidence_path)
record["monitoring"]["evidence"]["sha256"] = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
target_path.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects pending monitoring evidence with matching sha" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_MONITORING_PENDING_RECORD" --report "$ARTIFACT_DIR/release-operations-monitoring-pending.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-monitoring-pending.properties" "monitoring-evidence-status-not-passed"
OPERATIONS_MONITORING_WEAK_EVIDENCE="$TMP_DIR/release-operations-monitoring-weak-audit.properties"
grep -Ev '^(artifactSchema|recordedAt|command|reproduciblePath)=' \
  "$OPERATIONS_MONITORING_EVIDENCE" > "$OPERATIONS_MONITORING_WEAK_EVIDENCE"
OPERATIONS_MONITORING_WEAK_RECORD="$TMP_DIR/release-operations-monitoring-weak-audit.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_MONITORING_WEAK_RECORD" "$OPERATIONS_MONITORING_WEAK_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record_path, target_path, evidence_path = map(Path, sys.argv[1:])
record = json.loads(record_path.read_text())
record["monitoring"]["evidence"]["path"] = str(evidence_path)
record["monitoring"]["evidence"]["sha256"] = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
target_path.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects monitoring evidence without audit header" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_MONITORING_WEAK_RECORD" --report "$ARTIFACT_DIR/release-operations-monitoring-weak-audit.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-monitoring-weak-audit.properties" "monitoring-evidence-artifact-schema-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-monitoring-weak-audit.properties" "monitoring-evidence-command-missing"
sed 's/^status=passed$/status=pending/' "$OPERATIONS_ROLLBACK_EVIDENCE" > "$OPERATIONS_ROLLBACK_PENDING_EVIDENCE"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_ROLLBACK_PENDING_RECORD" "$OPERATIONS_ROLLBACK_PENDING_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record_path, target_path, evidence_path = map(Path, sys.argv[1:])
record = json.loads(record_path.read_text())
record["rollback"]["evidence"]["path"] = str(evidence_path)
record["rollback"]["evidence"]["sha256"] = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
target_path.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects pending rollback evidence with matching sha" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_ROLLBACK_PENDING_RECORD" --report "$ARTIFACT_DIR/release-operations-rollback-pending.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-rollback-pending.properties" "rollback-evidence-status-not-passed"
OPERATIONS_ROLLBACK_WEAK_EVIDENCE="$TMP_DIR/release-operations-rollback-weak-audit.properties"
grep -Ev '^(artifactSchema|recordedAt|command|reproduciblePath)=' \
  "$OPERATIONS_ROLLBACK_EVIDENCE" > "$OPERATIONS_ROLLBACK_WEAK_EVIDENCE"
OPERATIONS_ROLLBACK_WEAK_RECORD="$TMP_DIR/release-operations-rollback-weak-audit.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_ROLLBACK_WEAK_RECORD" "$OPERATIONS_ROLLBACK_WEAK_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record_path, target_path, evidence_path = map(Path, sys.argv[1:])
record = json.loads(record_path.read_text())
record["rollback"]["evidence"]["path"] = str(evidence_path)
record["rollback"]["evidence"]["sha256"] = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
target_path.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects rollback evidence without audit header" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_ROLLBACK_WEAK_RECORD" --report "$ARTIFACT_DIR/release-operations-rollback-weak-audit.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-rollback-weak-audit.properties" "rollback-evidence-artifact-schema-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-rollback-weak-audit.properties" "rollback-evidence-command-missing"
expect_failure \
  "release operations verifier rejects stale release artifact context" \
  env EXPECTED_COMMIT_SHA="$OPERATIONS_COMMIT_SHA" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$OPERATIONS_FAKE_SHA_2" \
  EXPECTED_RELEASE_MAPPING_SHA256="$OPERATIONS_FAKE_SHA_3" \
  EXPECTED_SIGNING_CERT_SHA256="$OPERATIONS_FAKE_SHA_4" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_APPROVED" --report "$ARTIFACT_DIR/release-operations-stale-artifact.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-stale-artifact.properties" "ci-release-artifact-archive-aab-sha-mismatch"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-stale-artifact.properties" "ci-protected-signing-aab-sha-mismatch"
expect_failure \
  "release operations verifier rejects stale commit context" \
  env EXPECTED_COMMIT_SHA=0000000000000000000000000000000000000000 \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$OPERATIONS_FAKE_SHA_1" \
  EXPECTED_RELEASE_MAPPING_SHA256="$OPERATIONS_FAKE_SHA_3" \
  EXPECTED_SIGNING_CERT_SHA256="$OPERATIONS_FAKE_SHA_4" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_APPROVED" --report "$ARTIFACT_DIR/release-operations-stale-commit.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-stale-commit.properties" "ci-commit-sha-mismatch"
expect_failure \
  "release operations verifier rejects stale mapping context" \
  env EXPECTED_COMMIT_SHA="$OPERATIONS_COMMIT_SHA" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$OPERATIONS_FAKE_SHA_1" \
  EXPECTED_RELEASE_MAPPING_SHA256="$OPERATIONS_FAKE_SHA_2" \
  EXPECTED_SIGNING_CERT_SHA256="$OPERATIONS_FAKE_SHA_4" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_APPROVED" --report "$ARTIFACT_DIR/release-operations-stale-mapping.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-stale-mapping.properties" "ci-release-artifact-archive-mapping-sha-mismatch"
expect_failure \
  "release operations verifier rejects stale signing certificate context" \
  env EXPECTED_COMMIT_SHA="$OPERATIONS_COMMIT_SHA" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$OPERATIONS_FAKE_SHA_1" \
  EXPECTED_RELEASE_MAPPING_SHA256="$OPERATIONS_FAKE_SHA_3" \
  EXPECTED_SIGNING_CERT_SHA256="$OPERATIONS_FAKE_SHA_2" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_APPROVED" --report "$ARTIFACT_DIR/release-operations-stale-signing-cert.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-stale-signing-cert.properties" "ci-protected-signing-cert-sha-mismatch"
OPERATIONS_CI_STALE_LOCAL_EVIDENCE="$TMP_DIR/release-operations-ci-local-stale-commit.properties"
sed "s/commitSha=$OPERATIONS_COMMIT_SHA/commitSha=0000000000000000000000000000000000000000/" \
  "$OPERATIONS_CI_LOCAL_EVIDENCE" > "$OPERATIONS_CI_STALE_LOCAL_EVIDENCE"
OPERATIONS_CI_STALE_LOCAL_SHA="$(shasum -a 256 "$OPERATIONS_CI_STALE_LOCAL_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_STALE_LOCAL_RECORD="$TMP_DIR/release-operations-ci-stale-local.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_CI_STALE_LOCAL_RECORD" "$OPERATIONS_CI_STALE_LOCAL_EVIDENCE" "$OPERATIONS_CI_STALE_LOCAL_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["ci"]["localVerification"]["evidence"]["path"] = sys.argv[3]
record["ci"]["localVerification"]["evidence"]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects stale local CI evidence commit" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_CI_STALE_LOCAL_RECORD" --report "$ARTIFACT_DIR/release-operations-ci-stale-local.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-stale-local.properties" "ci-local-verification-evidence-commit-sha-mismatch"
OPERATIONS_CI_STALE_CONNECTED_EVIDENCE="$TMP_DIR/release-operations-ci-connected-stale-commit.properties"
sed "s/commitSha=$OPERATIONS_COMMIT_SHA/commitSha=0000000000000000000000000000000000000000/" \
  "$OPERATIONS_CI_CONNECTED_EVIDENCE" > "$OPERATIONS_CI_STALE_CONNECTED_EVIDENCE"
OPERATIONS_CI_STALE_CONNECTED_SHA="$(shasum -a 256 "$OPERATIONS_CI_STALE_CONNECTED_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_STALE_CONNECTED_RECORD="$TMP_DIR/release-operations-ci-stale-connected.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_CI_STALE_CONNECTED_RECORD" "$OPERATIONS_CI_STALE_CONNECTED_EVIDENCE" "$OPERATIONS_CI_STALE_CONNECTED_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["ci"]["connectedAndroidTests"]["evidence"]["path"] = sys.argv[3]
record["ci"]["connectedAndroidTests"]["evidence"]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects stale connected Android test CI evidence commit" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_CI_STALE_CONNECTED_RECORD" --report "$ARTIFACT_DIR/release-operations-ci-stale-connected.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-stale-connected.properties" "ci-connected-android-tests-evidence-commit-sha-mismatch"
OPERATIONS_CI_STALE_ARTIFACT_EVIDENCE="$TMP_DIR/release-operations-ci-artifact-stale-run.properties"
sed 's/runId=123456/runId=654321/' "$OPERATIONS_CI_ARTIFACT_EVIDENCE" > "$OPERATIONS_CI_STALE_ARTIFACT_EVIDENCE"
OPERATIONS_CI_STALE_ARTIFACT_SHA="$(shasum -a 256 "$OPERATIONS_CI_STALE_ARTIFACT_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_STALE_ARTIFACT_RECORD="$TMP_DIR/release-operations-ci-stale-artifact-run.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_CI_STALE_ARTIFACT_RECORD" "$OPERATIONS_CI_STALE_ARTIFACT_EVIDENCE" "$OPERATIONS_CI_STALE_ARTIFACT_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["ci"]["releaseArtifactArchive"]["evidence"]["path"] = sys.argv[3]
record["ci"]["releaseArtifactArchive"]["evidence"]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects stale artifact CI evidence run" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_CI_STALE_ARTIFACT_RECORD" --report "$ARTIFACT_DIR/release-operations-ci-stale-artifact-run.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-stale-artifact-run.properties" "ci-release-artifact-archive-evidence-run-id-mismatch"
OPERATIONS_CI_STALE_API_MATRIX_EVIDENCE="$TMP_DIR/release-operations-ci-api-matrix-stale-run.properties"
sed 's/runId=123456/runId=654321/' "$OPERATIONS_CI_API_MATRIX_EVIDENCE" > "$OPERATIONS_CI_STALE_API_MATRIX_EVIDENCE"
OPERATIONS_CI_STALE_API_MATRIX_SHA="$(shasum -a 256 "$OPERATIONS_CI_STALE_API_MATRIX_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_STALE_API_MATRIX_RECORD="$TMP_DIR/release-operations-ci-stale-api-matrix-run.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_CI_STALE_API_MATRIX_RECORD" "$OPERATIONS_CI_STALE_API_MATRIX_EVIDENCE" "$OPERATIONS_CI_STALE_API_MATRIX_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["ci"]["apiMatrix"]["evidence"]["path"] = sys.argv[3]
record["ci"]["apiMatrix"]["evidence"]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects stale API matrix CI evidence run" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_CI_STALE_API_MATRIX_RECORD" --report "$ARTIFACT_DIR/release-operations-ci-stale-api-matrix-run.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-stale-api-matrix-run.properties" "ci-api-matrix-evidence-run-id-mismatch"
OPERATIONS_CI_WEAK_CONNECTED="$TMP_DIR/release-operations-ci-weak-connected.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_CI_WEAK_CONNECTED" "$OPERATIONS_CI_CONNECTED_WEAK_EVIDENCE" "$OPERATIONS_CI_CONNECTED_WEAK_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["ci"]["connectedAndroidTests"]["evidence"]["path"] = sys.argv[3]
record["ci"]["connectedAndroidTests"]["evidence"]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects weak connected Android test evidence" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_CI_WEAK_CONNECTED" --report "$ARTIFACT_DIR/release-operations-ci-weak-connected.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-weak-connected.properties" "ci-connected-android-tests-evidence-target-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-weak-connected.properties" "ci-connected-android-tests-count-invalid"
OPERATIONS_CI_WEAK_API_MATRIX="$TMP_DIR/release-operations-ci-weak-api-matrix.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_CI_WEAK_API_MATRIX" "$OPERATIONS_CI_API_MATRIX_WEAK_EVIDENCE" "$OPERATIONS_CI_API_MATRIX_WEAK_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["ci"]["apiMatrix"]["evidence"]["path"] = sys.argv[3]
record["ci"]["apiMatrix"]["evidence"]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects weak API matrix evidence" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_CI_WEAK_API_MATRIX" --report "$ARTIFACT_DIR/release-operations-ci-weak-api-matrix.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-weak-api-matrix.properties" "ci-api-matrix-required-apis-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-weak-api-matrix.properties" "ci-api-matrix-passed-apis-invalid"
OPERATIONS_CI_FAILED_READINESS="$TMP_DIR/release-operations-ci-api-matrix-readiness-failed.properties"
sed \
  -e 's/^status=passed$/status=failed/' \
  -e 's/^availableAvdApis=28,32,33,34,36$/availableAvdApis=32,33,34,36/' \
  -e 's/^missingAvdApis=$/missingAvdApis=28/' \
  "$OPERATIONS_CI_API_MATRIX_READINESS" > "$OPERATIONS_CI_FAILED_READINESS"
OPERATIONS_CI_FAILED_READINESS_SHA="$(shasum -a 256 "$OPERATIONS_CI_FAILED_READINESS" | awk '{print $1}')"
OPERATIONS_CI_FAILED_READINESS_EVIDENCE="$TMP_DIR/release-operations-ci-api-matrix-failed-readiness.properties"
sed \
  -e "s#^readinessReportFile=.*#readinessReportFile=$OPERATIONS_CI_FAILED_READINESS#" \
  -e "s#^readinessReportSha256=.*#readinessReportSha256=$OPERATIONS_CI_FAILED_READINESS_SHA#" \
  "$OPERATIONS_CI_API_MATRIX_EVIDENCE" > "$OPERATIONS_CI_FAILED_READINESS_EVIDENCE"
OPERATIONS_CI_FAILED_READINESS_EVIDENCE_SHA="$(shasum -a 256 "$OPERATIONS_CI_FAILED_READINESS_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_FAILED_READINESS_RECORD="$TMP_DIR/release-operations-ci-api-matrix-failed-readiness.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_CI_FAILED_READINESS_RECORD" "$OPERATIONS_CI_FAILED_READINESS_EVIDENCE" "$OPERATIONS_CI_FAILED_READINESS_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["ci"]["apiMatrix"]["evidence"]["path"] = sys.argv[3]
record["ci"]["apiMatrix"]["evidence"]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects API matrix evidence with failed readiness report" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_CI_FAILED_READINESS_RECORD" --report "$ARTIFACT_DIR/release-operations-ci-api-matrix-failed-readiness.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-api-matrix-failed-readiness.properties" "ci-api-matrix-readiness-status-not-passed"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-api-matrix-failed-readiness.properties" "ci-api-matrix-readiness-available-avds-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-api-matrix-failed-readiness.properties" "ci-api-matrix-readiness-missing-avds-not-empty"
OPERATIONS_CI_BAD_READINESS_SHA_EVIDENCE="$TMP_DIR/release-operations-ci-api-matrix-bad-readiness-sha.properties"
sed 's/^readinessReportSha256=.*/readinessReportSha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$OPERATIONS_CI_API_MATRIX_EVIDENCE" > "$OPERATIONS_CI_BAD_READINESS_SHA_EVIDENCE"
OPERATIONS_CI_BAD_READINESS_SHA_EVIDENCE_SHA="$(shasum -a 256 "$OPERATIONS_CI_BAD_READINESS_SHA_EVIDENCE" | awk '{print $1}')"
OPERATIONS_CI_BAD_READINESS_SHA_RECORD="$TMP_DIR/release-operations-ci-api-matrix-bad-readiness-sha.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_CI_BAD_READINESS_SHA_RECORD" "$OPERATIONS_CI_BAD_READINESS_SHA_EVIDENCE" "$OPERATIONS_CI_BAD_READINESS_SHA_EVIDENCE_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["ci"]["apiMatrix"]["evidence"]["path"] = sys.argv[3]
record["ci"]["apiMatrix"]["evidence"]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects API matrix evidence with bad readiness sha" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_CI_BAD_READINESS_SHA_RECORD" --report "$ARTIFACT_DIR/release-operations-ci-api-matrix-bad-readiness-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-api-matrix-bad-readiness-sha.properties" "ci-api-matrix-readiness-report-sha-mismatch"
OPERATIONS_CI_WEAK_SIGNING="$TMP_DIR/release-operations-ci-weak-signing.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_CI_WEAK_SIGNING" "$OPERATIONS_CI_SIGNING_WEAK_EVIDENCE" "$OPERATIONS_CI_SIGNING_WEAK_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["ci"]["protectedSigning"]["evidence"]["path"] = sys.argv[3]
record["ci"]["protectedSigning"]["evidence"]["sha256"] = sys.argv[4]
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects weak protected signing evidence" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_CI_WEAK_SIGNING" --report "$ARTIFACT_DIR/release-operations-ci-weak-signing.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-weak-signing.properties" "ci-protected-signing-evidence-artifact-schema-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-ci-weak-signing.properties" "ci-protected-signing-artifact-scan-report-sha-invalid"
OPERATIONS_NO_VITALS="$TMP_DIR/release-operations-no-vitals.json"
sed 's/"Android Vitals", //' "$OPERATIONS_APPROVED" > "$OPERATIONS_NO_VITALS"
expect_failure \
  "release operations verifier requires Android Vitals source" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_NO_VITALS" --report "$ARTIFACT_DIR/release-operations-no-vitals.properties"
assert_report_contains "$ARTIFACT_DIR/release-operations-no-vitals.properties" "status=failed"
OPERATIONS_FUTURE="$TMP_DIR/release-operations-future.json"
sed 's/"reviewDate": "'"$OPERATIONS_DATE"'"/"reviewDate": "2999-01-01"/' "$OPERATIONS_APPROVED" > "$OPERATIONS_FUTURE"
expect_failure \
  "release operations verifier rejects future review dates" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_FUTURE" --report "$ARTIFACT_DIR/release-operations-future.properties"
assert_report_contains "$ARTIFACT_DIR/release-operations-future.properties" "status=failed"
OPERATIONS_SMOKE_BAD_SHA="$TMP_DIR/release-operations-smoke-bad-sha.json"
sed 's/"sha256": "'"$OPERATIONS_SMOKE_SHA"'"/"sha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$OPERATIONS_APPROVED" > "$OPERATIONS_SMOKE_BAD_SHA"
expect_failure \
  "release operations verifier rejects crash smoke evidence sha mismatch" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_SMOKE_BAD_SHA" --report "$ARTIFACT_DIR/release-operations-smoke-bad-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-smoke-bad-sha.properties" "crash-anr-smoke-evidence-sha-mismatch"
OPERATIONS_SMOKE_WEAK_EVIDENCE="$TMP_DIR/release-operations-smoke-weak-audit.properties"
grep -Ev '^(artifactSchema|recordedAt|command|reproduciblePath)=' \
  "$OPERATIONS_SMOKE_EVIDENCE" > "$OPERATIONS_SMOKE_WEAK_EVIDENCE"
OPERATIONS_SMOKE_WEAK_RECORD="$TMP_DIR/release-operations-smoke-weak-audit.json"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_SMOKE_WEAK_RECORD" "$OPERATIONS_SMOKE_WEAK_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record_path, target_path, evidence_path = map(Path, sys.argv[1:])
record = json.loads(record_path.read_text())
record["crashAnrSmoke"]["evidence"]["path"] = str(evidence_path)
record["crashAnrSmoke"]["evidence"]["sha256"] = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
target_path.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects crash smoke evidence without audit header" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_SMOKE_WEAK_RECORD" --report "$ARTIFACT_DIR/release-operations-smoke-weak-audit.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-smoke-weak-audit.properties" "crash-anr-smoke-evidence-artifact-schema-invalid"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-smoke-weak-audit.properties" "crash-anr-smoke-evidence-command-missing"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_SMOKE_FAILED_RECORD" "$OPERATIONS_SMOKE_EVIDENCE" "$OPERATIONS_SMOKE_FAILED_EVIDENCE" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record_path, target_path, source_evidence_path, failed_evidence_path = map(Path, sys.argv[1:])
text = source_evidence_path.read_text()
text = text.replace("status=passed\n", "status=failed\n", 1)
text = text.replace("noLaunchCrash=true\n", "noLaunchCrash=false\n", 1)
failed_evidence_path.write_text(text)
record = json.loads(record_path.read_text())
record["crashAnrSmoke"]["evidence"]["path"] = str(failed_evidence_path)
record["crashAnrSmoke"]["evidence"]["sha256"] = hashlib.sha256(failed_evidence_path.read_bytes()).hexdigest()
target_path.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects failed crash smoke evidence with matching sha" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_SMOKE_FAILED_RECORD" --report "$ARTIFACT_DIR/release-operations-smoke-failed.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-smoke-failed.properties" "crash-anr-smoke-evidence-status-not-passed"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-smoke-failed.properties" "crash-anr-smoke-evidence-no-launch-crash-not-true"
python3 - "$OPERATIONS_APPROVED" "$OPERATIONS_SMOKE_BAD_LOGCAT_RECORD" "$OPERATIONS_SMOKE_EVIDENCE" "$OPERATIONS_SMOKE_BAD_LOGCAT_EVIDENCE" <<'PY'
import hashlib
import json
import re
import sys
from pathlib import Path

record_path, target_path, source_evidence_path, bad_logcat_evidence_path = map(Path, sys.argv[1:])
text = source_evidence_path.read_text()
text = re.sub(
    r"^logcatSha256=[0-9a-f]{64}$",
    "logcatSha256=0000000000000000000000000000000000000000000000000000000000000000",
    text,
    count=1,
    flags=re.MULTILINE,
)
bad_logcat_evidence_path.write_text(text)
record = json.loads(record_path.read_text())
record["crashAnrSmoke"]["evidence"]["path"] = str(bad_logcat_evidence_path)
record["crashAnrSmoke"]["evidence"]["sha256"] = hashlib.sha256(bad_logcat_evidence_path.read_bytes()).hexdigest()
target_path.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release operations verifier rejects crash smoke evidence with bad logcat sha" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_SMOKE_BAD_LOGCAT_RECORD" --report "$ARTIFACT_DIR/release-operations-smoke-bad-logcat.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-smoke-bad-logcat.properties" "crash-anr-smoke-logcat-logcat-sha256-mismatch"
