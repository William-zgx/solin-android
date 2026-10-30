# shellcheck shell=bash
# Validation script tests: verify_release_gate.sh and sign_release_artifacts.sh.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

VALID_GATE_PERF="$TMP_DIR/perf-baseline-safe-apk.properties"
SAFE_APK_SHA="$(shasum -a 256 "$SAFE_APK" | awk '{print $1}')"
SAFE_AAB_SHA="$(shasum -a 256 "$SAFE_AAB" | awk '{print $1}')"
cat > "$VALID_GATE_PERF" <<VALID_GATE_PERF_BASELINE
artifactSchema=PerfBaseline/v1
status=passed
target=perf-baseline-record
owner=release-engineering
collectionCommand=scripts/collect_rc_perf_from_device.sh
reproduciblePath=$VALID_GATE_PERF
runner=rc_perf_release_broadcast
preserves_model_data=true
harnessResultSha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
rcPerfCollectorReportFile=$TMP_DIR/rc-perf-collector.properties
deviceSerial=device-a
deviceModel=Pixel Test
androidApi=36
abi=arm64-v8a
appVersion=0.1.0
releaseArtifactSha256=$SAFE_APK_SHA
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
VALID_GATE_PERF_BASELINE
VALID_GATE_AAB_PERF="$TMP_DIR/perf-baseline-safe-aab.properties"
sed \
  -e "s/releaseArtifactSha256=$SAFE_APK_SHA/releaseArtifactSha256=$SAFE_AAB_SHA/" \
  -e "s|reproduciblePath=$VALID_GATE_PERF|reproduciblePath=$VALID_GATE_AAB_PERF|" \
  "$VALID_GATE_PERF" > "$VALID_GATE_AAB_PERF"
PRIVACY_GATE_TARGET="$TMP_DIR/privacy-gate-scan-target"
mkdir -p "$PRIVACY_GATE_TARGET"
PRIVACY_GATE_SECRET="$PRIVACY_GATE_TARGET/privacy-scan-gate-secret.tmp"
printf 'token=sk-%s\n' "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" > "$PRIVACY_GATE_SECRET"
export AI_BEHAVIOR_FIXTURE_DIR="$AI_PLACEMENT_FIXTURE_DIR"
export AI_BEHAVIOR_ACTUAL_TRACE_FILE="$AI_PLACEMENT_ACTUAL_TRACE"
expect_success \
  "release gate passed report has evidence schema" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-gate-passed-schema" \
  RELEASE_APK="$TMP_DIR/missing.apk" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  VERIFY_AI_BEHAVIOR_EVAL=0 \
  VERIFY_PERF_BASELINE=0 \
  scripts/verify_release_gate.sh
assert_release_gate_report_schema "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties"
assert_report_contains "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" "failedTarget="
assert_report_contains "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" "failedReason="
assert_report_contains "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" "reason=approved"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" \
  "privacyScan" \
  "$ARTIFACT_DIR/release-gate-passed-schema/privacy-scan.properties" \
  "passed"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" \
  "aiBehaviorEval" \
  "$ARTIFACT_DIR/release-gate-passed-schema/ai-behavior-eval.properties" \
  "skipped"
assert_release_gate_child_report_schema \
  "$ARTIFACT_DIR/release-gate-passed-schema/contract-tests.properties" \
  "skipped" \
  "contract-tests"
assert_release_gate_child_report_schema \
  "$ARTIFACT_DIR/release-gate-passed-schema/ai-behavior-eval.properties" \
  "skipped" \
  "ai-behavior-eval"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" \
  "androidArtifactScan" \
  "$ARTIFACT_DIR/release-gate-passed-schema/android-artifact-scan.properties" \
  "skipped"
assert_release_gate_child_report_schema \
  "$ARTIFACT_DIR/release-gate-passed-schema/android-artifact-scan.properties" \
  "skipped" \
  "android-artifact-scan"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/release-gate-passed-schema/release-gate.properties" \
  "perfBaseline" \
  "$ARTIFACT_DIR/release-gate-passed-schema/perf-baseline-verification.properties" \
  "skipped"
assert_release_gate_child_report_schema \
  "$ARTIFACT_DIR/release-gate-passed-schema/perf-baseline-verification.properties" \
  "skipped" \
  "perf-baseline"
assert_release_gate_child_report_schema \
  "$ARTIFACT_DIR/release-gate-passed-schema/release-record.properties" \
  "skipped" \
  "release-record"
assert_release_gate_child_report_schema \
  "$ARTIFACT_DIR/release-gate-passed-schema/release-validation-record.properties" \
  "skipped" \
  "release-validation-record"
expect_failure \
  "release gate reports privacy scan child reason" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-privacy-scan-failed" \
  EXTRA_PRIVACY_SCAN_TARGETS="$PRIVACY_GATE_TARGET" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-failed/privacy-scan.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-failed/privacy-scan.properties" "reason=secret-pattern-detected"
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-failed/release-gate.properties" "failedTarget=privacy-scan"
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-failed/release-gate.properties" "failedReason=secret-pattern-detected"
assert_release_gate_report_schema "$ARTIFACT_DIR/release-privacy-scan-failed/release-gate.properties"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/release-privacy-scan-failed/release-gate.properties" \
  "privacyScan" \
  "$ARTIFACT_DIR/release-privacy-scan-failed/privacy-scan.properties" \
  "failed"
expect_failure \
  "release gate rejects option-like extra privacy scan target" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-privacy-scan-invalid-extra-target" \
  EXTRA_PRIVACY_SCAN_TARGETS="--report:$TMP_DIR/redirected-privacy-scan.properties" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-invalid-extra-target/privacy-scan.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-invalid-extra-target/privacy-scan.properties" "reason=invalid-extra-privacy-scan-target"
assert_release_gate_child_report_schema \
  "$ARTIFACT_DIR/release-privacy-scan-invalid-extra-target/privacy-scan.properties" \
  "failed" \
  "privacy-scan"
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-invalid-extra-target/release-gate.properties" "failedTarget=privacy-scan"
assert_report_contains "$ARTIFACT_DIR/release-privacy-scan-invalid-extra-target/release-gate.properties" "failedReason=invalid-extra-privacy-scan-target"
expect_failure \
  "release gate reports missing perf baseline in gate summary" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-missing-perf" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-missing-perf/release-gate.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-missing-perf/release-gate.properties" "verifyPerfBaseline=1"
assert_report_contains "$ARTIFACT_DIR/release-missing-perf/release-gate.properties" "requireAiBehaviorActualTrace=1"
assert_report_contains "$ARTIFACT_DIR/release-missing-perf/release-gate.properties" "requireAiBehaviorPlacementReconciliation=1"
assert_report_contains "$ARTIFACT_DIR/release-missing-perf/release-gate.properties" "failedTarget=perf-baseline"
assert_report_contains "$ARTIFACT_DIR/release-missing-perf/release-gate.properties" "failedReason=PERF_BASELINE_FILE-not-set"
assert_release_gate_report_schema "$ARTIFACT_DIR/release-missing-perf/release-gate.properties"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/release-missing-perf/release-gate.properties" \
  "perfBaseline" \
  "$ARTIFACT_DIR/release-missing-perf/perf-baseline-verification.properties" \
  "failed"
assert_release_gate_child_report_schema \
  "$ARTIFACT_DIR/release-missing-perf/perf-baseline-verification.properties" \
  "failed" \
  "perf-baseline"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/release-missing-perf/release-gate.properties" \
  "aiBehaviorEval" \
  "$ARTIFACT_DIR/release-missing-perf/ai-behavior-eval.properties" \
  "passed"
assert_report_contains "$ARTIFACT_DIR/release-missing-perf/ai-behavior-eval.properties" \
  "traceDiffFile=$ARTIFACT_DIR/release-missing-perf/ai-behavior-planning-trace-diff.jsonl"
assert_report_contains "$ARTIFACT_DIR/release-missing-perf/ai-behavior-eval.properties" \
  "requirePlacementReconciliation=1"
[[ -s "$ARTIFACT_DIR/release-missing-perf/ai-behavior-planning-trace-diff.jsonl" ]] ||
  fail "release gate must preserve AI behavior planning trace diff"
expect_failure \
  "release gate fails closed when required AI behavior actual trace is missing" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-ai-behavior-actual-required" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  AI_BEHAVIOR_ACTUAL_TRACE_FILE= \
  REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=1 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-actual-required/ai-behavior-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-actual-required/ai-behavior-eval.properties" "reason=actual-trace-file-missing"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-actual-required/release-gate.properties" "failedTarget=ai-behavior-eval"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-actual-required/release-gate.properties" "failedReason=actual-trace-file-missing"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-actual-required/release-gate.properties" "requireAiBehaviorActualTrace=1"
expect_failure \
  "release gate fails closed when required AI behavior runtime trace source is missing" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-ai-behavior-runtime-source-required" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  AI_BEHAVIOR_FIXTURE_DIR=app/src/test/resources/ai_behavior_eval \
  AI_BEHAVIOR_ACTUAL_TRACE_FILE="$AI_ACTUAL_TRACE_MISSING_SOURCE" \
  REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=1 \
  REQUIRE_AI_BEHAVIOR_RUNTIME_TRACE_SOURCE=1 \
  REQUIRE_AI_BEHAVIOR_PLACEMENT_RECONCILIATION=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-runtime-source-required/ai-behavior-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-runtime-source-required/ai-behavior-eval.properties" "reason=invalid-actual-trace:1:traceSource"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-runtime-source-required/release-gate.properties" "failedTarget=ai-behavior-eval"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-runtime-source-required/release-gate.properties" "requireAiBehaviorRuntimeTraceSource=1"
expect_failure \
  "release gate fails closed when public AI behavior source is not agent_loop_runtime" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-ai-behavior-agent-loop-source-required" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  AI_BEHAVIOR_ACTUAL_TRACE_FILE="$AI_MIXED_SOURCE_TRACE" \
  REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=1 \
  REQUIRE_AI_BEHAVIOR_AGENT_LOOP_RUNTIME_TRACE_SOURCE=1 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-agent-loop-source-required/ai-behavior-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-agent-loop-source-required/ai-behavior-eval.properties" "reason=actual-trace-non-agent-loop-runtime-source"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-agent-loop-source-required/ai-behavior-eval.properties" "requireAgentLoopRuntimeTraceSource=1"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-agent-loop-source-required/ai-behavior-eval.properties" "actualTraceNonAgentLoopRuntimeSourceCount=1"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-agent-loop-source-required/release-gate.properties" "failedTarget=ai-behavior-eval"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-agent-loop-source-required/release-gate.properties" "failedReason=actual-trace-non-agent-loop-runtime-source"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-agent-loop-source-required/release-gate.properties" "requireAiBehaviorAgentLoopRuntimeTraceSource=1"
expect_failure \
  "release gate fails closed when strict AI behavior allowed failures are present" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-ai-behavior-allowed-failure-strict" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  AI_BEHAVIOR_FIXTURE_DIR="$AI_BEHAVIOR_ALLOWED_FAILURE_TOOL_DIR" \
  AI_BEHAVIOR_ACTUAL_TRACE_FILE="$AI_ALLOWED_FAILURE_TOOL_TRACE" \
  REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=1 \
  REQUIRE_AI_BEHAVIOR_RUNTIME_TRACE_SOURCE=1 \
  REQUIRE_AI_BEHAVIOR_NO_ALLOWED_FAILURES=1 \
  REQUIRE_AI_BEHAVIOR_PLACEMENT_RECONCILIATION=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-allowed-failure-strict/ai-behavior-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-allowed-failure-strict/ai-behavior-eval.properties" "reason=trace-diff-allowed-failure"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-allowed-failure-strict/ai-behavior-eval.properties" "rejectAllowedFailures=1"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-allowed-failure-strict/release-gate.properties" "aiBehaviorFixtureDir=$AI_BEHAVIOR_ALLOWED_FAILURE_TOOL_DIR"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-allowed-failure-strict/release-gate.properties" "failedTarget=ai-behavior-eval"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-allowed-failure-strict/release-gate.properties" "failedReason=trace-diff-allowed-failure"
assert_report_contains "$ARTIFACT_DIR/release-ai-behavior-allowed-failure-strict/release-gate.properties" "requireAiBehaviorNoAllowedFailures=1"
expect_failure \
  "public release gate forces AI behavior eval even when disabled by environment" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-public-ai-behavior-forced" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  PUBLIC_RELEASE=1 \
  VERIFY_AI_BEHAVIOR_EVAL=0 \
  AI_BEHAVIOR_ACTUAL_TRACE_FILE= \
  EXPECTED_SIGNING_CERT_SHA256="$DEBUG_SIGNED_AAB_CERT_SHA" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-public-ai-behavior-forced/ai-behavior-eval.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-public-ai-behavior-forced/ai-behavior-eval.properties" "reason=actual-trace-file-missing"
assert_report_contains "$ARTIFACT_DIR/release-public-ai-behavior-forced/release-gate.properties" "verifyAiBehaviorEval=1"
assert_report_contains "$ARTIFACT_DIR/release-public-ai-behavior-forced/release-gate.properties" "requireAiBehaviorActualTrace=1"
assert_report_contains "$ARTIFACT_DIR/release-public-ai-behavior-forced/release-gate.properties" "requireAiBehaviorPlacementReconciliation=1"
assert_report_contains "$ARTIFACT_DIR/release-public-ai-behavior-forced/release-gate.properties" "requireAiBehaviorAgentLoopRuntimeTraceSource=1"
assert_report_contains "$ARTIFACT_DIR/release-public-ai-behavior-forced/release-gate.properties" "requireAiBehaviorNoAllowedFailures=1"
assert_report_contains "$ARTIFACT_DIR/release-public-ai-behavior-forced/release-gate.properties" "failedTarget=ai-behavior-eval"
expect_failure \
  "release gate can skip perf baseline for non-public owner evidence checks" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-store-policy-without-perf" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  VERIFY_PERF_BASELINE=0 \
  VERIFY_STORE_POLICY=1 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-store-policy-without-perf/perf-baseline-verification.properties" "status=skipped"
assert_report_contains "$ARTIFACT_DIR/release-store-policy-without-perf/perf-baseline-verification.properties" "reason=VERIFY_PERF_BASELINE-not-enabled"
assert_report_contains "$ARTIFACT_DIR/release-store-policy-without-perf/release-gate.properties" "verifyPerfBaseline=0"
assert_report_contains "$ARTIFACT_DIR/release-store-policy-without-perf/release-gate.properties" "failedTarget=store-policy-record"
VALID_GATE_BAD_SHA_PERF="$TMP_DIR/perf-baseline-safe-apk-bad-sha.properties"
sed \
  -e 's/releaseArtifactSha256='"$SAFE_APK_SHA"'/releaseArtifactSha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  -e "s|reproduciblePath=$VALID_GATE_PERF|reproduciblePath=$VALID_GATE_BAD_SHA_PERF|" \
  "$VALID_GATE_PERF" > "$VALID_GATE_BAD_SHA_PERF"
expect_failure \
  "release gate reports perf baseline child reason" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-perf-bad-sha" \
  PERF_BASELINE_FILE="$VALID_GATE_BAD_SHA_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-perf-bad-sha/perf-baseline-verification.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/release-perf-bad-sha/perf-baseline-verification.properties" "release-artifact-sha-mismatch"
assert_report_contains "$ARTIFACT_DIR/release-perf-bad-sha/release-gate.properties" "failedTarget=perf-baseline"
assert_report_contains_text "$ARTIFACT_DIR/release-perf-bad-sha/release-gate.properties" "failedReason=release-artifact-sha-mismatch"
expect_failure \
  "release gate requires approved privacy review when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-privacy-review" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_PRIVACY_REVIEW=1 \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-privacy-review/privacy-review.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-privacy-review/release-gate.properties" "failedTarget=privacy-review"
expect_failure \
  "release gate requires approved model license review when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-model-license" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_MODEL_LICENSES=1 \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-model-license/model-license-review.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-model-license/release-gate.properties" "failedTarget=model-license-review"
expect_failure \
  "public release profile requires expected signing certificate" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/public-release-missing-cert" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  PUBLIC_RELEASE=1 \
  VERIFY_PERF_BASELINE=0 \
  REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=0 \
  REQUIRE_AI_BEHAVIOR_RUNTIME_TRACE_SOURCE=0 \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "publicRelease=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyReleaseRecord=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyStorePolicy=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyReleaseOperations=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyReleaseValidation=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyPrivacyReview=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyModelLicenses=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyPerfBaseline=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "requireAiBehaviorActualTrace=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "requireAiBehaviorRuntimeTraceSource=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "requireAab=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "requireSignedArtifact=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyReleaseMapping=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "failedTarget=signing-cert"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "failedReason=PUBLIC_RELEASE-EXPECTED_SIGNING_CERT_SHA256-not-set"
assert_release_gate_report_schema "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties"
assert_release_gate_child_report_bound \
  "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" \
  "signingCert" \
  "$ARTIFACT_DIR/public-release-missing-cert/signing-cert.properties" \
  "failed"
assert_release_gate_child_report_schema \
  "$ARTIFACT_DIR/public-release-missing-cert/signing-cert.properties" \
  "failed" \
  "signing-cert"
assert_release_gate_children_not_produced \
  "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" \
  "$ARTIFACT_DIR/public-release-missing-cert" \
  "privacyScan:privacy-scan.properties" \
  "contractTests:contract-tests.properties" \
  "aiBehaviorEval:ai-behavior-eval.properties" \
  "androidArtifactScan:android-artifact-scan.properties" \
  "perfBaseline:perf-baseline-verification.properties" \
  "releaseMapping:release-mapping.properties" \
  "releaseRecord:release-record.properties" \
  "storePolicyRecord:store-policy-record.properties" \
  "releaseOperationsRecord:release-operations-record.properties" \
  "releaseValidationRecord:release-validation-record.properties" \
  "modelLicenseReview:model-license-review.properties" \
  "privacyReview:privacy-review.properties"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/signing-cert.properties" "status=failed"

expect_failure \
  "release gate requires aab when public gate requests it" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-require-aab" \
  PERF_BASELINE_FILE="$VALID_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  REQUIRE_AAB=1 \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-require-aab/android-artifact-scan.properties" "status=failed"
assert_release_gate_child_report_schema \
  "$ARTIFACT_DIR/release-require-aab/android-artifact-scan.properties" \
  "failed" \
  "android-artifact-scan"
assert_report_contains "$ARTIFACT_DIR/release-require-aab/release-gate.properties" "failedTarget=android-artifact-scan"
rm -f app/build/outputs/bundle/release/app-release-signed.aab
expect_failure \
  "release gate defaults signed aab path when signed aab is required" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-signed-default-aab" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  REQUIRE_AAB=1 \
  REQUIRE_SIGNED_ARTIFACT=1 \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-signed-default-aab/android-artifact-scan.properties" "status=failed"
assert_release_gate_child_report_schema \
  "$ARTIFACT_DIR/release-signed-default-aab/android-artifact-scan.properties" \
  "failed" \
  "android-artifact-scan"
assert_report_contains "$ARTIFACT_DIR/release-signed-default-aab/android-artifact-scan.properties" "releaseAab=app/build/outputs/bundle/release/app-release-signed.aab"
assert_report_contains "$ARTIFACT_DIR/release-signed-default-aab/release-gate.properties" "releaseAab=app/build/outputs/bundle/release/app-release-signed.aab"
assert_report_contains "$ARTIFACT_DIR/release-signed-default-aab/release-gate.properties" "failedTarget=android-artifact-scan"
assert_report_contains "$ARTIFACT_DIR/release-signed-default-aab/release-gate.properties" "failedReason=REQUIRE_AAB-but-release-aab-missing"
expect_failure \
  "release gate binds release record to scanned artifact in non-public mode" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-record-artifact-mismatch" \
  PERF_BASELINE_FILE="$VALID_GATE_AAB_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$SAFE_AAB" \
  VERIFY_RELEASE_RECORD=1 \
  RELEASE_RECORD_FILE="$RELEASE_RECORD_APPROVED" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-record-artifact-mismatch/release-record.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-record-artifact-mismatch/release-record.properties" "expectedReleaseArtifactPath=$SAFE_AAB"
assert_report_contains "$ARTIFACT_DIR/release-record-artifact-mismatch/release-gate.properties" "failedTarget=release-record"
expect_failure \
  "release gate requires mapping when mapping gate is enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-mapping-gate" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_RELEASE_MAPPING=1 \
  RELEASE_MAPPING_FILE="$TMP_DIR/missing-mapping.txt" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-mapping-gate/release-mapping.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-mapping-gate/release-gate.properties" "failedTarget=release-mapping"
expect_failure \
  "release gate requires approved release record when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-record-gate" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_RELEASE_RECORD=1 \
  RELEASE_RECORD_FILE="$RELEASE_RECORD_PENDING" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-record-gate/release-record.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-record-gate/release-gate.properties" "failedTarget=release-record"
expect_failure \
  "release gate requires approved store policy when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-store-policy-gate" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_STORE_POLICY=1 \
  STORE_POLICY_FILE="$STORE_POLICY_PENDING" \
  PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" \
  MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-store-policy-gate/store-policy-record.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-store-policy-gate/release-gate.properties" "failedTarget=store-policy-record"
expect_failure \
  "release gate requires approved operations record when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-operations-gate" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_RELEASE_OPERATIONS=1 \
  OPERATIONS_RECORD_FILE="$OPERATIONS_PENDING" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-operations-gate/release-operations-record.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-operations-gate/release-gate.properties" "failedTarget=release-operations-record"
expect_failure \
  "release gate requires approved validation record when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-validation-gate" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_RELEASE_VALIDATION=1 \
  VALIDATION_RECORD_FILE="$VALIDATION_PENDING" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-validation-gate/release-validation-record.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-validation-gate/release-gate.properties" "failedTarget=release-validation-record"
expect_failure \
  "signing helper requires private keystore environment" \
  env REPORT_FILE="$ARTIFACT_DIR/signing-missing-env.properties" \
  scripts/sign_release_artifacts.sh
assert_report_contains "$ARTIFACT_DIR/signing-missing-env.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/signing-missing-env.properties" "target=release-signing"
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/signing-missing-env.properties" \
  "ReleaseSigningReport/v1"
assert_report_contains "$ARTIFACT_DIR/signing-missing-env.properties" "failedTarget=environment"
assert_report_contains "$ARTIFACT_DIR/signing-missing-env.properties" "reason=missing-release-keystore"
expect_failure \
  "signing helper reports malformed arguments when report path is known" \
  env REPORT_FILE="$ARTIFACT_DIR/signing-malformed-argument.properties" \
  scripts/sign_release_artifacts.sh --bad-arg
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/signing-malformed-argument.properties" \
  "ReleaseSigningReport/v1"
assert_report_contains "$ARTIFACT_DIR/signing-malformed-argument.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/signing-malformed-argument.properties" "failedTarget=argument-parser"
assert_report_contains "$ARTIFACT_DIR/signing-malformed-argument.properties" "reason=unknown-argument"
SIGNING_CLI_MALFORMED_REPORT="$ARTIFACT_DIR/signing-cli-malformed-argument.properties"
expect_failure \
  "signing helper reports malformed CLI arguments when report path is known" \
  scripts/sign_release_artifacts.sh --report "$SIGNING_CLI_MALFORMED_REPORT" --bad-arg
assert_release_verifier_report_schema \
  "$SIGNING_CLI_MALFORMED_REPORT" \
  "ReleaseSigningReport/v1"
assert_report_contains "$SIGNING_CLI_MALFORMED_REPORT" "status=failed"
assert_report_contains "$SIGNING_CLI_MALFORMED_REPORT" "failedTarget=argument-parser"
assert_report_contains "$SIGNING_CLI_MALFORMED_REPORT" "reason=unknown-argument"
SIGNING_CLI_MISSING_VALUE_REPORT="$ARTIFACT_DIR/signing-cli-missing-value.properties"
expect_failure \
  "signing helper reports option-like missing CLI report value" \
  scripts/sign_release_artifacts.sh --report "$SIGNING_CLI_MISSING_VALUE_REPORT" --report --bad-arg
assert_release_verifier_report_schema \
  "$SIGNING_CLI_MISSING_VALUE_REPORT" \
  "ReleaseSigningReport/v1"
assert_report_contains "$SIGNING_CLI_MISSING_VALUE_REPORT" "status=failed"
assert_report_contains "$SIGNING_CLI_MISSING_VALUE_REPORT" "failedTarget=argument-parser"
assert_report_contains "$SIGNING_CLI_MISSING_VALUE_REPORT" "reason=missing-report-argument"
DEBUG_KEYSTORE="$TMP_DIR/debug.keystore"
printf 'not-a-real-keystore\n' > "$DEBUG_KEYSTORE"
expect_failure \
  "signing helper rejects debug keystore by default" \
  env RELEASE_KEYSTORE="$DEBUG_KEYSTORE" \
  RELEASE_KEY_ALIAS=androiddebugkey \
  RELEASE_KEYSTORE_PASSWORD=android \
  RELEASE_KEY_PASSWORD=android \
  REPORT_FILE="$ARTIFACT_DIR/signing-debug-keystore.properties" \
  scripts/sign_release_artifacts.sh
assert_report_contains "$ARTIFACT_DIR/signing-debug-keystore.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/signing-debug-keystore.properties" "failedTarget=keystore"
assert_report_contains "$ARTIFACT_DIR/signing-debug-keystore.properties" "reason=debug-keystore-not-allowed"
grep -q 'Refusing Android debug keystore' <<<"$LAST_OUTPUT" ||
  fail "Expected signing helper to refuse debug keystore before signing"
PRODUCTION_KEYSTORE="$TMP_DIR/production-upload.keystore"
printf 'not-a-real-keystore\n' > "$PRODUCTION_KEYSTORE"
expect_failure \
  "signing helper requires expected production certificate" \
  env RELEASE_KEYSTORE="$PRODUCTION_KEYSTORE" \
  RELEASE_KEY_ALIAS=upload \
  RELEASE_KEYSTORE_PASSWORD=secret \
  RELEASE_KEY_PASSWORD=secret \
  REPORT_FILE="$ARTIFACT_DIR/signing-missing-cert.properties" \
  scripts/sign_release_artifacts.sh
assert_report_contains "$ARTIFACT_DIR/signing-missing-cert.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/signing-missing-cert.properties" "failedTarget=signing-policy"
assert_report_contains "$ARTIFACT_DIR/signing-missing-cert.properties" "reason=expected-signing-cert-sha256-missing"
grep -q 'Production release signing requires EXPECTED_SIGNING_CERT_SHA256' <<<"$LAST_OUTPUT" ||
  fail "Expected signing helper to require expected production certificate before signing"
expect_failure \
  "signing helper requires unsigned aab for production signing" \
  env RELEASE_KEYSTORE="$PRODUCTION_KEYSTORE" \
  RELEASE_KEY_ALIAS=upload \
  RELEASE_KEYSTORE_PASSWORD=secret \
  RELEASE_KEY_PASSWORD=secret \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  UNSIGNED_APK="$SAFE_APK" \
  UNSIGNED_AAB="$TMP_DIR/missing-release.aab" \
  REPORT_FILE="$ARTIFACT_DIR/signing-missing-aab.properties" \
  scripts/sign_release_artifacts.sh
assert_report_contains "$ARTIFACT_DIR/signing-missing-aab.properties" "status=failed"
assert_release_verifier_report_schema \
  "$ARTIFACT_DIR/signing-missing-aab.properties" \
  "ReleaseSigningReport/v1"
assert_report_contains "$ARTIFACT_DIR/signing-missing-aab.properties" "unsignedApkSha256=$SAFE_APK_SHA"
assert_report_contains "$ARTIFACT_DIR/signing-missing-aab.properties" "unsignedAabSha256="
assert_report_contains "$ARTIFACT_DIR/signing-missing-aab.properties" "artifactScanReportSha256="
assert_report_contains "$ARTIFACT_DIR/signing-missing-aab.properties" "failedTarget=input-artifact"
assert_report_contains "$ARTIFACT_DIR/signing-missing-aab.properties" "reason=unsigned-aab-missing"
grep -q 'Release signing requires unsigned AAB' <<<"$LAST_OUTPUT" ||
  fail "Expected signing helper to require unsigned AAB before production signing"
