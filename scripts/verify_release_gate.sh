#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/release-gate}"
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
EXTRA_PRIVACY_SCAN_TARGETS="${EXTRA_PRIVACY_SCAN_TARGETS:-}"
EXPECTED_SIGNING_CERT_SHA256="${EXPECTED_SIGNING_CERT_SHA256:-}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
GRADLE_FILE="${GRADLE_FILE:-app/build.gradle.kts}"

mkdir -p "$ARTIFACT_DIR"

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
fi

if [[ "$REQUIRE_AAB" == "1" && "$REQUIRE_SIGNED_ARTIFACT" == "1" && "$RELEASE_AAB_WAS_SET" == "0" ]]; then
  RELEASE_AAB="$DEFAULT_SIGNED_RELEASE_AAB"
fi

write_gate_report() {
  local status="$1"
  local failed_target="${2:-}"
  local failed_reason="${3:-}"
  {
    printf 'status=%s\n' "$status"
    printf 'target=release-gate\n'
    printf 'failedTarget=%s\n' "$failed_target"
    printf 'failedReason=%s\n' "$failed_reason"
    printf 'artifactDir=%s\n' "$ARTIFACT_DIR"
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
    printf 'extraPrivacyScanTargets=%s\n' "$EXTRA_PRIVACY_SCAN_TARGETS"
    printf 'expectedSigningCertSha256=%s\n' "$EXPECTED_SIGNING_CERT_SHA256"
    printf 'releaseApk=%s\n' "$RELEASE_APK"
    printf 'releaseAab=%s\n' "$RELEASE_AAB"
  } > "$ARTIFACT_DIR/release-gate.properties"
}

report_reason_for() {
  local report_file="$1"
  if [[ -f "$report_file" ]]; then
    awk -F= '$1 == "reason" {print $2; exit}' "$report_file"
  fi
}

fail_gate() {
  local failed_target="$1"
  local report_file="${2:-}"
  local failed_reason="${3:-}"
  if [[ -z "$failed_reason" && -n "$report_file" ]]; then
    failed_reason="$(report_reason_for "$report_file")"
  fi
  write_gate_report failed "$failed_target" "$failed_reason"
  exit 1
}

if [[ "$PUBLIC_RELEASE" == "1" && -z "$EXPECTED_SIGNING_CERT_SHA256" ]]; then
  {
    printf 'status=failed\n'
    printf 'target=signing-cert\n'
    printf 'reason=PUBLIC_RELEASE-EXPECTED_SIGNING_CERT_SHA256-not-set\n'
  } > "$ARTIFACT_DIR/signing-cert.properties"
  echo "PUBLIC_RELEASE=1 requires EXPECTED_SIGNING_CERT_SHA256." >&2
  fail_gate signing-cert "$ARTIFACT_DIR/signing-cert.properties" "PUBLIC_RELEASE-EXPECTED_SIGNING_CERT_SHA256-not-set"
fi

privacy_scan_targets=(app/src/main docs scripts)
if [[ -n "$EXTRA_PRIVACY_SCAN_TARGETS" ]]; then
  IFS=':' read -r -a extra_privacy_scan_targets <<< "$EXTRA_PRIVACY_SCAN_TARGETS"
  for extra_privacy_scan_target in "${extra_privacy_scan_targets[@]}"; do
    if [[ "$extra_privacy_scan_target" == -* ]]; then
      {
        printf 'status=failed\n'
        printf 'target=privacy-scan\n'
        printf 'reason=invalid-extra-privacy-scan-target\n'
        printf 'extraPrivacyScanTarget=%s\n' "$extra_privacy_scan_target"
      } > "$ARTIFACT_DIR/privacy-scan.properties"
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
    --tests com.bytedance.zgx.pocketmind.docs.CapabilityMatrixDocumentationTest \
    --tests com.bytedance.zgx.pocketmind.docs.ModelManifestDocumentationTest \
    --tests com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest; then
    {
      printf 'status=failed\n'
      printf 'target=contract-tests\n'
      printf 'reason=contract-tests-failed\n'
    } > "$ARTIFACT_DIR/contract-tests.properties"
    fail_gate contract-tests "$ARTIFACT_DIR/contract-tests.properties" "contract-tests-failed"
  fi
  {
    printf 'status=passed\n'
    printf 'target=contract-tests\n'
  } > "$ARTIFACT_DIR/contract-tests.properties"
else
  {
    printf 'status=skipped\n'
    printf 'target=contract-tests\n'
    printf 'reason=VERIFY_CONTRACT_TESTS-not-enabled\n'
  } > "$ARTIFACT_DIR/contract-tests.properties"
fi

if [[ "$VERIFY_AI_BEHAVIOR_EVAL" == "1" ]]; then
  if ! scripts/verify_ai_behavior_eval.sh --require-boundary-map --report "$ARTIFACT_DIR/ai-behavior-eval.properties"; then
    fail_gate ai-behavior-eval "$ARTIFACT_DIR/ai-behavior-eval.properties"
  fi
else
  {
    printf 'status=skipped\n'
    printf 'target=ai-behavior-eval\n'
    printf 'reason=VERIFY_AI_BEHAVIOR_EVAL-not-enabled\n'
  } > "$ARTIFACT_DIR/ai-behavior-eval.properties"
fi

artifact_args=()
if [[ -f "$RELEASE_APK" && ! ("$REQUIRE_AAB" == "1" && "$REQUIRE_SIGNED_ARTIFACT" == "1" && "$RELEASE_APK_WAS_SET" == "0") ]]; then
  artifact_args+=(--apk "$RELEASE_APK")
fi
if [[ -f "$RELEASE_AAB" ]]; then
  artifact_args+=(--aab "$RELEASE_AAB")
fi
if [[ "$REQUIRE_AAB" == "1" && ! -f "$RELEASE_AAB" ]]; then
  {
    printf 'status=failed\n'
    printf 'target=android-artifact-scan\n'
    printf 'reason=REQUIRE_AAB-but-release-aab-missing\n'
    printf 'releaseAab=%s\n' "$RELEASE_AAB"
  } > "$ARTIFACT_DIR/android-artifact-scan.properties"
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
  {
    printf 'status=skipped\n'
    printf 'target=android-artifact-scan\n'
    printf 'reason=no-release-apk-or-aab\n'
  } > "$ARTIFACT_DIR/android-artifact-scan.properties"
fi

release_artifact_path=""
release_artifact_type=""
release_artifact_sha256=""
if [[ -f "$RELEASE_AAB" ]]; then
  release_artifact_path="$RELEASE_AAB"
  release_artifact_type="aab"
  release_artifact_sha256="$(shasum -a 256 "$RELEASE_AAB" | awk '{print $1}')"
elif [[ -f "$RELEASE_APK" ]]; then
  release_artifact_path="$RELEASE_APK"
  release_artifact_type="apk"
  release_artifact_sha256="$(shasum -a 256 "$RELEASE_APK" | awk '{print $1}')"
fi
release_mapping_sha256=""
if [[ -f "$RELEASE_MAPPING_FILE" ]]; then
  release_mapping_sha256="$(shasum -a 256 "$RELEASE_MAPPING_FILE" | awk '{print $1}')"
fi
head_commit_sha="$(git rev-parse HEAD 2>/dev/null || true)"

if [[ -n "$PERF_BASELINE_FILE" ]]; then
  expected_app_version="$(awk -F\" '/versionName[[:space:]]*=/ {print $2; exit}' "$GRADLE_FILE")"
  perf_args=(
    --file "$PERF_BASELINE_FILE" \
    --report "$ARTIFACT_DIR/perf-baseline-verification.properties"
  )
  if [[ -n "$expected_app_version" ]]; then
    perf_args+=(--app-version "$expected_app_version")
  fi
  if [[ -f "$RELEASE_AAB" ]]; then
    perf_args+=(--artifact-sha256 "$(shasum -a 256 "$RELEASE_AAB" | awk '{print $1}')")
  elif [[ -f "$RELEASE_APK" ]]; then
    perf_args+=(--artifact-sha256 "$(shasum -a 256 "$RELEASE_APK" | awk '{print $1}')")
  fi
  if ! scripts/verify_perf_baseline.sh "${perf_args[@]}"; then
    fail_gate perf-baseline "$ARTIFACT_DIR/perf-baseline-verification.properties"
  fi
else
  {
    printf 'status=failed\n'
    printf 'target=perf-baseline\n'
    printf 'reason=PERF_BASELINE_FILE-not-set\n'
  } > "$ARTIFACT_DIR/perf-baseline-verification.properties"
  echo "PERF_BASELINE_FILE must point at the RC perf-baseline.properties file." >&2
  fail_gate perf-baseline "$ARTIFACT_DIR/perf-baseline-verification.properties" "PERF_BASELINE_FILE-not-set"
fi

if [[ "$VERIFY_RELEASE_MAPPING" == "1" ]]; then
  if ! scripts/verify_release_mapping.sh --file "$RELEASE_MAPPING_FILE" --report "$ARTIFACT_DIR/release-mapping.properties"; then
    fail_gate release-mapping "$ARTIFACT_DIR/release-mapping.properties"
  fi
else
  {
    printf 'status=skipped\n'
    printf 'target=release-mapping\n'
    printf 'reason=VERIFY_RELEASE_MAPPING-not-enabled\n'
    printf 'mappingFile=%s\n' "$RELEASE_MAPPING_FILE"
  } > "$ARTIFACT_DIR/release-mapping.properties"
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
  {
    printf 'status=skipped\n'
    printf 'target=release-record\n'
    printf 'reason=VERIFY_RELEASE_RECORD-not-enabled\n'
    printf 'recordFile=%s\n' "$RELEASE_RECORD_FILE"
  } > "$ARTIFACT_DIR/release-record.properties"
fi

if [[ "$VERIFY_STORE_POLICY" == "1" ]]; then
  if ! scripts/verify_store_policy_record.sh --file "$STORE_POLICY_FILE" --report "$ARTIFACT_DIR/store-policy-record.properties"; then
    fail_gate store-policy-record "$ARTIFACT_DIR/store-policy-record.properties"
  fi
else
  {
    printf 'status=skipped\n'
    printf 'target=store-policy-record\n'
    printf 'reason=VERIFY_STORE_POLICY-not-enabled\n'
    printf 'storePolicyFile=%s\n' "$STORE_POLICY_FILE"
  } > "$ARTIFACT_DIR/store-policy-record.properties"
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
  {
    printf 'status=skipped\n'
    printf 'target=release-operations-record\n'
    printf 'reason=VERIFY_RELEASE_OPERATIONS-not-enabled\n'
    printf 'operationsRecordFile=%s\n' "$OPERATIONS_RECORD_FILE"
  } > "$ARTIFACT_DIR/release-operations-record.properties"
fi

if [[ "$VERIFY_RELEASE_VALIDATION" == "1" ]]; then
  if ! env "EXPECTED_RELEASE_ARTIFACT_SHA256=$release_artifact_sha256" scripts/verify_release_validation_record.sh --file "$VALIDATION_RECORD_FILE" --report "$ARTIFACT_DIR/release-validation-record.properties"; then
    fail_gate release-validation-record "$ARTIFACT_DIR/release-validation-record.properties"
  fi
else
  {
    printf 'status=skipped\n'
    printf 'target=release-validation-record\n'
    printf 'reason=VERIFY_RELEASE_VALIDATION-not-enabled\n'
    printf 'validationRecordFile=%s\n' "$VALIDATION_RECORD_FILE"
  } > "$ARTIFACT_DIR/release-validation-record.properties"
fi

if [[ "$VERIFY_MODEL_LICENSES" == "1" ]]; then
  if ! scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-review.properties"; then
    fail_gate model-license-review "$ARTIFACT_DIR/model-license-review.properties"
  fi
else
  {
    printf 'status=skipped\n'
    printf 'target=model-license-review\n'
    printf 'reason=VERIFY_MODEL_LICENSES-not-enabled\n'
  } > "$ARTIFACT_DIR/model-license-review.properties"
fi

if [[ "$VERIFY_PRIVACY_REVIEW" == "1" ]]; then
  if ! scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review.properties"; then
    fail_gate privacy-review "$ARTIFACT_DIR/privacy-review.properties"
  fi
else
  {
    printf 'status=skipped\n'
    printf 'target=privacy-review\n'
    printf 'reason=VERIFY_PRIVACY_REVIEW-not-enabled\n'
  } > "$ARTIFACT_DIR/privacy-review.properties"
fi

write_gate_report passed
echo "Release gate passed."
