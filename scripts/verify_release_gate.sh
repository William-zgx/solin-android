#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ORIGINAL_ARGS=("$@")
SOLIN_SCRIPT_COMMAND="scripts/verify_release_gate.sh"
source "$ROOT_DIR/scripts/lib/report_helpers.sh"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/release-gate}"
RELEASE_GATE_OWNER="${RELEASE_GATE_OWNER:-release-engineering}"
PERF_BASELINE_FILE="${PERF_BASELINE_FILE:-}"
DEFAULT_RELEASE_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
DEFAULT_RELEASE_AAB="app/build/outputs/bundle/release/app-release.aab"
DEFAULT_SIGNED_RELEASE_AAB="app/build/outputs/bundle/release/app-release-signed.aab"
RELEASE_APK_WAS_SET=0
if [[ -n "${RELEASE_APK+x}" ]]; then
  RELEASE_APK_WAS_SET=1
fi
RELEASE_AAB_WAS_SET=0
if [[ -n "${RELEASE_AAB+x}" ]]; then
  RELEASE_AAB_WAS_SET=1
fi
RELEASE_APK="${RELEASE_APK:-$DEFAULT_RELEASE_APK}"
RELEASE_AAB="${RELEASE_AAB:-$DEFAULT_RELEASE_AAB}"
PUBLIC_RELEASE="${PUBLIC_RELEASE:-0}"
VERIFY_MODEL_LICENSES="${VERIFY_MODEL_LICENSES:-0}"
VERIFY_PRIVACY_REVIEW="${VERIFY_PRIVACY_REVIEW:-0}"
VERIFY_RELEASE_RECORD="${VERIFY_RELEASE_RECORD:-0}"
RELEASE_RECORD_FILE="${RELEASE_RECORD_FILE:-docs/release_record.json}"
VERIFY_STORE_POLICY="${VERIFY_STORE_POLICY:-0}"
STORE_POLICY_FILE="${STORE_POLICY_FILE:-docs/store_policy_record.json}"
VERIFY_RELEASE_OPERATIONS="${VERIFY_RELEASE_OPERATIONS:-0}"
OPERATIONS_RECORD_FILE="${OPERATIONS_RECORD_FILE:-docs/release_operations_record.json}"
VERIFY_RELEASE_VALIDATION="${VERIFY_RELEASE_VALIDATION:-0}"
VALIDATION_RECORD_FILE="${VALIDATION_RECORD_FILE:-docs/release_validation_record.json}"
REQUIRE_AAB="${REQUIRE_AAB:-0}"
REQUIRE_SIGNED_ARTIFACT="${REQUIRE_SIGNED_ARTIFACT:-0}"
VERIFY_RELEASE_MAPPING="${VERIFY_RELEASE_MAPPING:-0}"
RELEASE_MAPPING_FILE="${RELEASE_MAPPING_FILE:-app/build/outputs/mapping/release/mapping.txt}"
VERIFY_CONTRACT_TESTS="${VERIFY_CONTRACT_TESTS:-1}"
VERIFY_AI_BEHAVIOR_EVAL="${VERIFY_AI_BEHAVIOR_EVAL:-1}"
VERIFY_PERF_BASELINE="${VERIFY_PERF_BASELINE:-1}"
AI_BEHAVIOR_FIXTURE_DIR="${AI_BEHAVIOR_FIXTURE_DIR:-app/src/test/resources/ai_behavior_eval}"
AI_BEHAVIOR_ACTUAL_TRACE_FILE="${AI_BEHAVIOR_ACTUAL_TRACE_FILE:-}"
REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE="${REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE:-1}"
REQUIRE_AI_BEHAVIOR_RUNTIME_TRACE_SOURCE="${REQUIRE_AI_BEHAVIOR_RUNTIME_TRACE_SOURCE:-0}"
REQUIRE_AI_BEHAVIOR_AGENT_LOOP_RUNTIME_TRACE_SOURCE="${REQUIRE_AI_BEHAVIOR_AGENT_LOOP_RUNTIME_TRACE_SOURCE:-0}"
REQUIRE_AI_BEHAVIOR_NO_ALLOWED_FAILURES="${REQUIRE_AI_BEHAVIOR_NO_ALLOWED_FAILURES:-0}"
REQUIRE_AI_BEHAVIOR_PLACEMENT_RECONCILIATION="${REQUIRE_AI_BEHAVIOR_PLACEMENT_RECONCILIATION:-1}"
EXTRA_PRIVACY_SCAN_TARGETS="${EXTRA_PRIVACY_SCAN_TARGETS:-}"
EXPECTED_SIGNING_CERT_SHA256="${EXPECTED_SIGNING_CERT_SHA256:-}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
GRADLE_FILE="${GRADLE_FILE:-app/build.gradle.kts}"

mkdir -p "$ARTIFACT_DIR"
RELEASE_GATE_REPORT="$ARTIFACT_DIR/release-gate.properties"
release_gate_command="$(command_line)"
head_commit_sha="$(git rev-parse HEAD 2>/dev/null || true)"
release_artifact_path=""
release_artifact_type=""
release_artifact_sha256=""

if [[ "$PUBLIC_RELEASE" == "1" ]]; then
  VERIFY_RELEASE_RECORD=1
  VERIFY_STORE_POLICY=1
  VERIFY_RELEASE_OPERATIONS=1
  VERIFY_RELEASE_VALIDATION=1
  VERIFY_MODEL_LICENSES=1
  VERIFY_PRIVACY_REVIEW=1
  REQUIRE_AAB=1
  REQUIRE_SIGNED_ARTIFACT=1
  VERIFY_RELEASE_MAPPING=1
  VERIFY_PERF_BASELINE=1
  VERIFY_AI_BEHAVIOR_EVAL=1
  REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE=1
  REQUIRE_AI_BEHAVIOR_RUNTIME_TRACE_SOURCE=1
  REQUIRE_AI_BEHAVIOR_AGENT_LOOP_RUNTIME_TRACE_SOURCE=1
  REQUIRE_AI_BEHAVIOR_NO_ALLOWED_FAILURES=1
  REQUIRE_AI_BEHAVIOR_PLACEMENT_RECONCILIATION=1
fi

if [[ "$REQUIRE_AI_BEHAVIOR_AGENT_LOOP_RUNTIME_TRACE_SOURCE" == "1" ]]; then
  REQUIRE_AI_BEHAVIOR_RUNTIME_TRACE_SOURCE=1
fi

if [[ "$REQUIRE_AAB" == "1" && "$REQUIRE_SIGNED_ARTIFACT" == "1" && "$RELEASE_AAB_WAS_SET" == "0" ]]; then
  RELEASE_AAB="$DEFAULT_SIGNED_RELEASE_AAB"
fi

write_child_report_binding() {
  local child_key="$1"
  local report_file="$2"
  printf '%sReportPath=%s\n' "$child_key" "$report_file"
  if [[ -f "$report_file" ]]; then
    printf '%sReportStatus=%s\n' "$child_key" "$(report_value "$report_file" status)"
    printf '%sReportSha256=%s\n' "$child_key" "$(sha256_or_empty "$report_file")"
  else
    printf '%sReportStatus=not-produced\n' "$child_key"
    printf '%sReportSha256=\n' "$child_key"
  fi
}

write_simple_child_report() {
  local report_file="$1"
  local status="$2"
  local target="$3"
  local reason="${4:-}"
  shift 4
  {
    printf 'artifactSchema=ReleaseGateChildReport/v1\n'
    printf 'status=%s\n' "$status"
    printf 'target=%s\n' "$target"
    printf 'owner=%s\n' "$RELEASE_GATE_OWNER"
    printf 'recordedAt=%s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    printf 'command=%s\n' "$release_gate_command"
    printf 'reproduciblePath=%s\n' "$report_file"
    printf 'reason=%s\n' "$reason"
    printf 'releaseGateReport=%s\n' "$RELEASE_GATE_REPORT"
    printf 'releaseGateHeadCommitSha=%s\n' "$head_commit_sha"
    printf 'releaseRecordFile=%s\n' "$RELEASE_RECORD_FILE"
    printf 'releaseArtifactPath=%s\n' "$release_artifact_path"
    printf 'releaseArtifactType=%s\n' "$release_artifact_type"
    printf 'releaseArtifactSha256=%s\n' "$release_artifact_sha256"
    local entry
    for entry in "$@"; do
      printf '%s\n' "$entry"
    done
  } > "$report_file"
}

write_gate_report() {
  local status="$1"
  local failed_target="${2:-}"
  local failed_reason="${3:-}"
  local reason="$failed_reason"
  if [[ -z "$reason" ]]; then
    if [[ "$status" == "passed" ]]; then
      reason="approved"
    elif [[ -n "$failed_target" ]]; then
      reason="${failed_target}-failed"
    else
      reason="failed"
    fi
  fi
  {
    printf 'artifactSchema=ReleaseGateVerification/v1\n'
    printf 'status=%s\n' "$status"
    printf 'target=release-gate\n'
    printf 'owner=%s\n' "$RELEASE_GATE_OWNER"
    printf 'recordedAt=%s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    printf 'command=%s\n' "$release_gate_command"
    printf 'failedTarget=%s\n' "$failed_target"
    printf 'failedReason=%s\n' "$failed_reason"
    printf 'reason=%s\n' "$reason"
    printf 'reproduciblePath=%s\n' "$RELEASE_GATE_REPORT"
    printf 'headCommitSha=%s\n' "$head_commit_sha"
    printf 'artifactDir=%s\n' "$ARTIFACT_DIR"
    printf 'releaseArtifactPath=%s\n' "$release_artifact_path"
    printf 'releaseArtifactType=%s\n' "$release_artifact_type"
    printf 'releaseArtifactSha256=%s\n' "$release_artifact_sha256"
    printf 'publicRelease=%s\n' "$PUBLIC_RELEASE"
    printf 'verifyReleaseRecord=%s\n' "$VERIFY_RELEASE_RECORD"
    printf 'releaseRecordFile=%s\n' "$RELEASE_RECORD_FILE"
    printf 'verifyStorePolicy=%s\n' "$VERIFY_STORE_POLICY"
    printf 'storePolicyFile=%s\n' "$STORE_POLICY_FILE"
    printf 'verifyReleaseOperations=%s\n' "$VERIFY_RELEASE_OPERATIONS"
    printf 'operationsRecordFile=%s\n' "$OPERATIONS_RECORD_FILE"
    printf 'verifyReleaseValidation=%s\n' "$VERIFY_RELEASE_VALIDATION"
    printf 'validationRecordFile=%s\n' "$VALIDATION_RECORD_FILE"
    printf 'verifyModelLicenses=%s\n' "$VERIFY_MODEL_LICENSES"
    printf 'verifyPrivacyReview=%s\n' "$VERIFY_PRIVACY_REVIEW"
    printf 'requireAab=%s\n' "$REQUIRE_AAB"
    printf 'requireSignedArtifact=%s\n' "$REQUIRE_SIGNED_ARTIFACT"
    printf 'verifyReleaseMapping=%s\n' "$VERIFY_RELEASE_MAPPING"
    printf 'releaseMappingFile=%s\n' "$RELEASE_MAPPING_FILE"
    printf 'verifyContractTests=%s\n' "$VERIFY_CONTRACT_TESTS"
    printf 'verifyAiBehaviorEval=%s\n' "$VERIFY_AI_BEHAVIOR_EVAL"
    printf 'verifyPerfBaseline=%s\n' "$VERIFY_PERF_BASELINE"
    printf 'aiBehaviorFixtureDir=%s\n' "$AI_BEHAVIOR_FIXTURE_DIR"
    printf 'aiBehaviorActualTraceFile=%s\n' "$AI_BEHAVIOR_ACTUAL_TRACE_FILE"
    printf 'requireAiBehaviorActualTrace=%s\n' "$REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE"
    printf 'requireAiBehaviorPlacementReconciliation=%s\n' "$REQUIRE_AI_BEHAVIOR_PLACEMENT_RECONCILIATION"
    printf 'requireAiBehaviorRuntimeTraceSource=%s\n' "$REQUIRE_AI_BEHAVIOR_RUNTIME_TRACE_SOURCE"
    printf 'requireAiBehaviorAgentLoopRuntimeTraceSource=%s\n' "$REQUIRE_AI_BEHAVIOR_AGENT_LOOP_RUNTIME_TRACE_SOURCE"
    printf 'requireAiBehaviorNoAllowedFailures=%s\n' "$REQUIRE_AI_BEHAVIOR_NO_ALLOWED_FAILURES"
    printf 'extraPrivacyScanTargets=%s\n' "$EXTRA_PRIVACY_SCAN_TARGETS"
    printf 'expectedSigningCertSha256=%s\n' "$EXPECTED_SIGNING_CERT_SHA256"
    printf 'releaseApk=%s\n' "$RELEASE_APK"
    printf 'releaseAab=%s\n' "$RELEASE_AAB"
    write_child_report_binding signingCert "$ARTIFACT_DIR/signing-cert.properties"
    write_child_report_binding privacyScan "$ARTIFACT_DIR/privacy-scan.properties"
    write_child_report_binding contractTests "$ARTIFACT_DIR/contract-tests.properties"
    write_child_report_binding aiBehaviorEval "$ARTIFACT_DIR/ai-behavior-eval.properties"
    write_child_report_binding androidArtifactScan "$ARTIFACT_DIR/android-artifact-scan.properties"
    write_child_report_binding perfBaseline "$ARTIFACT_DIR/perf-baseline-verification.properties"
    write_child_report_binding releaseMapping "$ARTIFACT_DIR/release-mapping.properties"
    write_child_report_binding releaseRecord "$ARTIFACT_DIR/release-record.properties"
    write_child_report_binding storePolicyRecord "$ARTIFACT_DIR/store-policy-record.properties"
    write_child_report_binding releaseOperationsRecord "$ARTIFACT_DIR/release-operations-record.properties"
    write_child_report_binding releaseValidationRecord "$ARTIFACT_DIR/release-validation-record.properties"
    write_child_report_binding modelLicenseReview "$ARTIFACT_DIR/model-license-review.properties"
    write_child_report_binding privacyReview "$ARTIFACT_DIR/privacy-review.properties"
  } > "$RELEASE_GATE_REPORT"
}

fail_gate() {
  local failed_target="$1"
  local report_file="${2:-}"
  local failed_reason="${3:-}"
  if [[ -z "$failed_reason" && -n "$report_file" ]]; then
    failed_reason="$(report_value "$report_file" reason)"
  fi
  write_gate_report failed "$failed_target" "$failed_reason"
  exit 1
}

if [[ "$PUBLIC_RELEASE" == "1" && -z "$EXPECTED_SIGNING_CERT_SHA256" ]]; then
  write_simple_child_report \
    "$ARTIFACT_DIR/signing-cert.properties" \
    failed \
    signing-cert \
    PUBLIC_RELEASE-EXPECTED_SIGNING_CERT_SHA256-not-set
  echo "PUBLIC_RELEASE=1 requires EXPECTED_SIGNING_CERT_SHA256." >&2
  fail_gate signing-cert "$ARTIFACT_DIR/signing-cert.properties" "PUBLIC_RELEASE-EXPECTED_SIGNING_CERT_SHA256-not-set"
fi

privacy_scan_targets=(app/src/main docs scripts)
if [[ -n "$EXTRA_PRIVACY_SCAN_TARGETS" ]]; then
  IFS=':' read -r -a extra_privacy_scan_targets <<< "$EXTRA_PRIVACY_SCAN_TARGETS"
  for extra_privacy_scan_target in "${extra_privacy_scan_targets[@]}"; do
    if [[ "$extra_privacy_scan_target" == -* ]]; then
      write_simple_child_report \
        "$ARTIFACT_DIR/privacy-scan.properties" \
        failed \
        privacy-scan \
        invalid-extra-privacy-scan-target \
        "extraPrivacyScanTarget=$extra_privacy_scan_target"
      echo "EXTRA_PRIVACY_SCAN_TARGETS entries must be paths, not options: $extra_privacy_scan_target" >&2
      fail_gate privacy-scan "$ARTIFACT_DIR/privacy-scan.properties" "invalid-extra-privacy-scan-target"
    fi
    privacy_scan_targets+=("$extra_privacy_scan_target")
  done
fi
if ! scripts/privacy_scan.sh --report "$ARTIFACT_DIR/privacy-scan.properties" "${privacy_scan_targets[@]}"; then
  fail_gate privacy-scan "$ARTIFACT_DIR/privacy-scan.properties"
fi

if [[ "$VERIFY_CONTRACT_TESTS" == "1" ]]; then
  if ! "$GRADLE_CMD" :app:testDebugUnitTest \
    --tests com.bytedance.zgx.solin.docs.CapabilityMatrixDocumentationTest \
    --tests com.bytedance.zgx.solin.docs.ModelManifestDocumentationTest \
    --tests com.bytedance.zgx.solin.docs.ModelCapabilityProfilesDocumentationTest \
    --tests com.bytedance.zgx.solin.docs.AgentCoreDocumentationTest; then
    write_simple_child_report \
      "$ARTIFACT_DIR/contract-tests.properties" \
      failed \
      contract-tests \
      contract-tests-failed
    fail_gate contract-tests "$ARTIFACT_DIR/contract-tests.properties" "contract-tests-failed"
  fi
  write_simple_child_report "$ARTIFACT_DIR/contract-tests.properties" passed contract-tests ""
else
  write_simple_child_report \
    "$ARTIFACT_DIR/contract-tests.properties" \
    skipped \
    contract-tests \
    VERIFY_CONTRACT_TESTS-not-enabled
fi

if [[ "$VERIFY_AI_BEHAVIOR_EVAL" == "1" ]]; then
  ai_behavior_args=(
    --dir "$AI_BEHAVIOR_FIXTURE_DIR"
    --require-boundary-map
    --trace-diff "$ARTIFACT_DIR/ai-behavior-planning-trace-diff.jsonl"
    --report "$ARTIFACT_DIR/ai-behavior-eval.properties"
  )
  if [[ -n "$AI_BEHAVIOR_ACTUAL_TRACE_FILE" ]]; then
    ai_behavior_args+=(--actual-trace "$AI_BEHAVIOR_ACTUAL_TRACE_FILE")
  fi
  if [[ "$REQUIRE_AI_BEHAVIOR_ACTUAL_TRACE" == "1" ]]; then
    ai_behavior_args+=(--require-actual-trace)
  fi
  if [[ "$REQUIRE_AI_BEHAVIOR_RUNTIME_TRACE_SOURCE" == "1" ]]; then
    ai_behavior_args+=(--require-runtime-trace-source)
  fi
  if [[ "$REQUIRE_AI_BEHAVIOR_AGENT_LOOP_RUNTIME_TRACE_SOURCE" == "1" ]]; then
    ai_behavior_args+=(--require-agent-loop-runtime-trace-source)
  fi
  if [[ "$REQUIRE_AI_BEHAVIOR_NO_ALLOWED_FAILURES" == "1" ]]; then
    ai_behavior_args+=(--reject-allowed-failures)
  fi
  if [[ "$REQUIRE_AI_BEHAVIOR_PLACEMENT_RECONCILIATION" == "1" ]]; then
    ai_behavior_args+=(--require-placement-reconciliation)
  fi
  if ! scripts/verify_ai_behavior_eval.sh "${ai_behavior_args[@]}"; then
    fail_gate ai-behavior-eval "$ARTIFACT_DIR/ai-behavior-eval.properties"
  fi
else
  write_simple_child_report \
    "$ARTIFACT_DIR/ai-behavior-eval.properties" \
    skipped \
    ai-behavior-eval \
    VERIFY_AI_BEHAVIOR_EVAL-not-enabled
fi

artifact_args=()
if [[ -f "$RELEASE_APK" && ! ("$REQUIRE_AAB" == "1" && "$REQUIRE_SIGNED_ARTIFACT" == "1" && "$RELEASE_APK_WAS_SET" == "0") ]]; then
  artifact_args+=(--apk "$RELEASE_APK")
fi
if [[ -f "$RELEASE_AAB" ]]; then
  artifact_args+=(--aab "$RELEASE_AAB")
fi
if [[ "$REQUIRE_AAB" == "1" && ! -f "$RELEASE_AAB" ]]; then
  write_simple_child_report \
    "$ARTIFACT_DIR/android-artifact-scan.properties" \
    failed \
    android-artifact-scan \
    REQUIRE_AAB-but-release-aab-missing \
    "releaseAab=$RELEASE_AAB"
  echo "REQUIRE_AAB=1 but release AAB is missing: $RELEASE_AAB" >&2
  fail_gate android-artifact-scan "$ARTIFACT_DIR/android-artifact-scan.properties" "REQUIRE_AAB-but-release-aab-missing"
fi
if [[ "${#artifact_args[@]}" -gt 0 ]]; then
  scan_args=("${artifact_args[@]}" --report "$ARTIFACT_DIR/android-artifact-scan.properties")
  if [[ "$REQUIRE_SIGNED_ARTIFACT" == "1" ]]; then
    scan_args+=(--require-signed)
  fi
  if [[ -n "$EXPECTED_SIGNING_CERT_SHA256" ]]; then
    scan_args+=(--expected-certificate-sha256 "$EXPECTED_SIGNING_CERT_SHA256")
  fi
  if ! scripts/scan_android_artifacts.sh "${scan_args[@]}"; then
    fail_gate android-artifact-scan "$ARTIFACT_DIR/android-artifact-scan.properties"
  fi
else
  write_simple_child_report \
    "$ARTIFACT_DIR/android-artifact-scan.properties" \
    skipped \
    android-artifact-scan \
    no-release-apk-or-aab
fi

if [[ -f "$RELEASE_AAB" ]]; then
  release_artifact_path="$RELEASE_AAB"
  release_artifact_type="aab"
  release_artifact_sha256="$(sha256_or_empty "$RELEASE_AAB")"
elif [[ -f "$RELEASE_APK" ]]; then
  release_artifact_path="$RELEASE_APK"
  release_artifact_type="apk"
  release_artifact_sha256="$(sha256_or_empty "$RELEASE_APK")"
fi
release_mapping_sha256=""
if [[ -f "$RELEASE_MAPPING_FILE" ]]; then
  release_mapping_sha256="$(sha256_or_empty "$RELEASE_MAPPING_FILE")"
fi

if [[ "$VERIFY_PERF_BASELINE" != "1" ]]; then
  write_simple_child_report \
    "$ARTIFACT_DIR/perf-baseline-verification.properties" \
    skipped \
    perf-baseline \
    VERIFY_PERF_BASELINE-not-enabled \
    "publicRelease=$PUBLIC_RELEASE"
elif [[ -n "$PERF_BASELINE_FILE" ]]; then
  expected_app_version="$(awk -F\" '/versionName[[:space:]]*=/ {print $2; exit}' "$GRADLE_FILE")"
  perf_args=(
    --file "$PERF_BASELINE_FILE" \
    --report "$ARTIFACT_DIR/perf-baseline-verification.properties"
  )
  if [[ -n "$expected_app_version" ]]; then
    perf_args+=(--app-version "$expected_app_version")
  fi
  if [[ -f "$RELEASE_AAB" ]]; then
    perf_args+=(--artifact-sha256 "$(sha256_or_empty "$RELEASE_AAB")")
  elif [[ -f "$RELEASE_APK" ]]; then
    perf_args+=(--artifact-sha256 "$(sha256_or_empty "$RELEASE_APK")")
  fi
  if ! REQUIRE_RC_PERF_PROVENANCE=1 scripts/verify_perf_baseline.sh "${perf_args[@]}"; then
    fail_gate perf-baseline "$ARTIFACT_DIR/perf-baseline-verification.properties"
  fi
else
  write_simple_child_report \
    "$ARTIFACT_DIR/perf-baseline-verification.properties" \
    failed \
    perf-baseline \
    PERF_BASELINE_FILE-not-set
  echo "PERF_BASELINE_FILE must point at the RC perf-baseline.properties file." >&2
  fail_gate perf-baseline "$ARTIFACT_DIR/perf-baseline-verification.properties" "PERF_BASELINE_FILE-not-set"
fi

if [[ "$VERIFY_RELEASE_MAPPING" == "1" ]]; then
  if ! scripts/verify_release_mapping.sh --file "$RELEASE_MAPPING_FILE" --report "$ARTIFACT_DIR/release-mapping.properties"; then
    fail_gate release-mapping "$ARTIFACT_DIR/release-mapping.properties"
  fi
else
  write_simple_child_report \
    "$ARTIFACT_DIR/release-mapping.properties" \
    skipped \
    release-mapping \
    VERIFY_RELEASE_MAPPING-not-enabled \
    "mappingFile=$RELEASE_MAPPING_FILE"
fi

if [[ "$VERIFY_RELEASE_RECORD" == "1" ]]; then
  release_record_env=(
    "PUBLIC_RELEASE_CONTEXT=$PUBLIC_RELEASE"
    "EXPECTED_SIGNING_CERT_SHA256=$EXPECTED_SIGNING_CERT_SHA256"
  )
  if [[ -n "$release_artifact_path" ]]; then
    release_record_env+=(
      "EXPECTED_RELEASE_ARTIFACT_PATH=$release_artifact_path"
      "EXPECTED_RELEASE_ARTIFACT_TYPE=$release_artifact_type"
      "EXPECTED_RELEASE_ARTIFACT_SHA256=$release_artifact_sha256"
    )
  fi
  if ! env "${release_record_env[@]}" scripts/verify_release_record.sh --file "$RELEASE_RECORD_FILE" --report "$ARTIFACT_DIR/release-record.properties"; then
    fail_gate release-record "$ARTIFACT_DIR/release-record.properties"
  fi
else
  write_simple_child_report \
    "$ARTIFACT_DIR/release-record.properties" \
    skipped \
    release-record \
    VERIFY_RELEASE_RECORD-not-enabled \
    "recordFile=$RELEASE_RECORD_FILE"
fi

if [[ "$VERIFY_STORE_POLICY" == "1" ]]; then
  if ! scripts/verify_store_policy_record.sh --file "$STORE_POLICY_FILE" --report "$ARTIFACT_DIR/store-policy-record.properties"; then
    fail_gate store-policy-record "$ARTIFACT_DIR/store-policy-record.properties"
  fi
else
  write_simple_child_report \
    "$ARTIFACT_DIR/store-policy-record.properties" \
    skipped \
    store-policy-record \
    VERIFY_STORE_POLICY-not-enabled \
    "storePolicyFile=$STORE_POLICY_FILE"
fi

if [[ "$VERIFY_RELEASE_OPERATIONS" == "1" ]]; then
  operations_env=(
    "EXPECTED_COMMIT_SHA=$head_commit_sha"
    "EXPECTED_RELEASE_ARTIFACT_TYPE=$release_artifact_type"
    "EXPECTED_RELEASE_ARTIFACT_SHA256=$release_artifact_sha256"
    "EXPECTED_RELEASE_MAPPING_SHA256=$release_mapping_sha256"
    "EXPECTED_SIGNING_CERT_SHA256=$EXPECTED_SIGNING_CERT_SHA256"
  )
  if ! env "${operations_env[@]}" scripts/verify_release_operations_record.sh --file "$OPERATIONS_RECORD_FILE" --report "$ARTIFACT_DIR/release-operations-record.properties"; then
    fail_gate release-operations-record "$ARTIFACT_DIR/release-operations-record.properties"
  fi
else
  write_simple_child_report \
    "$ARTIFACT_DIR/release-operations-record.properties" \
    skipped \
    release-operations-record \
    VERIFY_RELEASE_OPERATIONS-not-enabled \
    "operationsRecordFile=$OPERATIONS_RECORD_FILE"
fi

if [[ "$VERIFY_RELEASE_VALIDATION" == "1" ]]; then
  if ! env \
    "EXPECTED_RELEASE_ARTIFACT_TYPE=$release_artifact_type" \
    "EXPECTED_RELEASE_ARTIFACT_SHA256=$release_artifact_sha256" \
    "EXPECTED_SIGNING_CERT_SHA256=$EXPECTED_SIGNING_CERT_SHA256" \
    scripts/verify_release_validation_record.sh --file "$VALIDATION_RECORD_FILE" --report "$ARTIFACT_DIR/release-validation-record.properties"; then
    fail_gate release-validation-record "$ARTIFACT_DIR/release-validation-record.properties"
  fi
else
  write_simple_child_report \
    "$ARTIFACT_DIR/release-validation-record.properties" \
    skipped \
    release-validation-record \
    VERIFY_RELEASE_VALIDATION-not-enabled \
    "validationRecordFile=$VALIDATION_RECORD_FILE"
fi

if [[ "$VERIFY_MODEL_LICENSES" == "1" ]]; then
  if ! scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-review.properties"; then
    fail_gate model-license-review "$ARTIFACT_DIR/model-license-review.properties"
  fi
else
  write_simple_child_report \
    "$ARTIFACT_DIR/model-license-review.properties" \
    skipped \
    model-license-review \
    VERIFY_MODEL_LICENSES-not-enabled
fi

if [[ "$VERIFY_PRIVACY_REVIEW" == "1" ]]; then
  if ! scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review.properties"; then
    fail_gate privacy-review "$ARTIFACT_DIR/privacy-review.properties"
  fi
else
  write_simple_child_report \
    "$ARTIFACT_DIR/privacy-review.properties" \
    skipped \
    privacy-review \
    VERIFY_PRIVACY_REVIEW-not-enabled
fi

write_gate_report passed
echo "Release gate passed."
