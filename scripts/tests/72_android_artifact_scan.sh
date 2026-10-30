# shellcheck shell=bash
# Validation script tests: scan_android_artifacts.sh.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

SAFE_APK="$TMP_DIR/safe.apk"
SAFE_AAB="$TMP_DIR/safe.aab"
BAD_AAB="$TMP_DIR/bad.aab"
UNSAFE_APK="$TMP_DIR/unsafe.apk"
mkdir -p "$TMP_DIR/safe-apk/assets" "$TMP_DIR/safe-aab/base/manifest" "$TMP_DIR/unsafe-zip/assets"
printf '<manifest />\n' > "$TMP_DIR/safe-apk/AndroidManifest.xml"
printf 'bundle-config\n' > "$TMP_DIR/safe-aab/BundleConfig.pb"
printf '<manifest />\n' > "$TMP_DIR/safe-aab/base/manifest/AndroidManifest.xml"
printf 'ok\n' > "$TMP_DIR/safe-apk/assets/readme.txt"
printf 'ok\n' > "$TMP_DIR/safe-aab/base/readme.txt"
printf 'model\n' > "$TMP_DIR/unsafe-zip/assets/model.litertlm"
printf 'model part\n' > "$TMP_DIR/unsafe-zip/assets/model.litertlm.part000"
printf 'embedding\n' > "$TMP_DIR/unsafe-zip/assets/embed.tflite"
printf 'sentencepiece\n' > "$TMP_DIR/unsafe-zip/assets/sentencepiece.model"
ARTIFACT_SCAN_SECRET_BODY="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
ARTIFACT_SCAN_SECRET="sk-${ARTIFACT_SCAN_SECRET_BODY}"
ARTIFACT_SCAN_HF_TOKEN="hf_${ARTIFACT_SCAN_SECRET_BODY}bb"
ARTIFACT_SCAN_BEARER="Bearer ${ARTIFACT_SCAN_SECRET_BODY}"
ARTIFACT_SCAN_API_KEY="api_key=${ARTIFACT_SCAN_SECRET_BODY}"
{
  printf 'token=%s\n' "$ARTIFACT_SCAN_SECRET"
  printf '%s\n' "$ARTIFACT_SCAN_HF_TOKEN"
  printf 'Authorization: %s\n' "$ARTIFACT_SCAN_BEARER"
  printf '%s\n' "$ARTIFACT_SCAN_API_KEY"
} > "$TMP_DIR/unsafe-zip/assets/secret.txt"
(cd "$TMP_DIR/safe-apk" && zip -qr "$SAFE_APK" .)
(cd "$TMP_DIR/safe-aab" && zip -qr "$SAFE_AAB" .)
(cd "$TMP_DIR/unsafe-zip" && zip -qr "$UNSAFE_APK" .)
printf 'not a bundle\n' > "$BAD_AAB"
expect_success \
  "artifact scan accepts safe zip" \
  scripts/scan_android_artifacts.sh --apk "$SAFE_APK" --report "$ARTIFACT_DIR/artifact.properties"
assert_report_contains "$ARTIFACT_DIR/artifact.properties" "status=passed"
assert_release_verifier_report_schema "$ARTIFACT_DIR/artifact.properties" "AndroidArtifactScanReport/v1"
SAFE_APK_SHA="$(shasum -a 256 "$SAFE_APK" | awk '{print $1}')"
SAFE_APK_SIZE="$(wc -c < "$SAFE_APK" | tr -d ' ')"
assert_report_contains "$ARTIFACT_DIR/artifact.properties" "artifact1Path=$SAFE_APK"
assert_report_contains "$ARTIFACT_DIR/artifact.properties" "artifact1Sha256=$SAFE_APK_SHA"
assert_report_contains "$ARTIFACT_DIR/artifact.properties" "artifact1SizeBytes=$SAFE_APK_SIZE"
SPACED_APK="$TMP_DIR/safe artifact spaced.apk"
SPACED_ARTIFACT_REPORT="$ARTIFACT_DIR/artifact spaced command.properties"
cp "$SAFE_APK" "$SPACED_APK"
expect_success \
  "artifact scan report command preserves spaced argv" \
  scripts/scan_android_artifacts.sh --apk "$SPACED_APK" --report "$SPACED_ARTIFACT_REPORT"
assert_report_command_argv \
  "$SPACED_ARTIFACT_REPORT" \
  scripts/scan_android_artifacts.sh \
  --apk "$SPACED_APK" \
  --report "$SPACED_ARTIFACT_REPORT"
ARTIFACT_MALFORMED_REPORT="$ARTIFACT_DIR/artifact-malformed-argument.properties"
expect_failure \
  "artifact scan reports malformed arguments when report path is known" \
  scripts/scan_android_artifacts.sh --report "$ARTIFACT_MALFORMED_REPORT" --bad-arg
assert_release_verifier_report_schema "$ARTIFACT_MALFORMED_REPORT" "AndroidArtifactScanReport/v1"
assert_report_contains "$ARTIFACT_MALFORMED_REPORT" "status=failed"
assert_report_contains "$ARTIFACT_MALFORMED_REPORT" "reason=unknown-argument"
assert_report_contains "$ARTIFACT_MALFORMED_REPORT" "failedTarget=argument-parser"
ARTIFACT_MISSING_VALUE_REPORT="$ARTIFACT_DIR/artifact-missing-value.properties"
expect_failure \
  "artifact scan reports missing argument values when report path is known" \
  scripts/scan_android_artifacts.sh --report "$ARTIFACT_MISSING_VALUE_REPORT" --apk
assert_release_verifier_report_schema "$ARTIFACT_MISSING_VALUE_REPORT" "AndroidArtifactScanReport/v1"
assert_report_contains "$ARTIFACT_MISSING_VALUE_REPORT" "status=failed"
assert_report_contains "$ARTIFACT_MISSING_VALUE_REPORT" "reason=missing-apk-argument"
assert_report_contains "$ARTIFACT_MISSING_VALUE_REPORT" "failedTarget=argument-parser"
ARTIFACT_OPTION_VALUE_REPORT="$ARTIFACT_DIR/artifact-option-like-value.properties"
expect_failure \
  "artifact scan treats option-like artifact path as missing" \
  scripts/scan_android_artifacts.sh --report "$ARTIFACT_OPTION_VALUE_REPORT" --apk --bad-arg
assert_release_verifier_report_schema "$ARTIFACT_OPTION_VALUE_REPORT" "AndroidArtifactScanReport/v1"
assert_report_contains "$ARTIFACT_OPTION_VALUE_REPORT" "status=failed"
assert_report_contains "$ARTIFACT_OPTION_VALUE_REPORT" "reason=missing-apk-argument"
assert_report_contains "$ARTIFACT_OPTION_VALUE_REPORT" "failedTarget=argument-parser"
ARTIFACT_EXPECTED_CERT_OPTION_VALUE_REPORT="$ARTIFACT_DIR/artifact-expected-cert-option-like-value.properties"
expect_failure \
  "artifact scan treats option-like expected certificate sha as missing" \
  scripts/scan_android_artifacts.sh --report "$ARTIFACT_EXPECTED_CERT_OPTION_VALUE_REPORT" --apk "$SAFE_APK" --expected-certificate-sha256 --bad-arg
assert_release_verifier_report_schema "$ARTIFACT_EXPECTED_CERT_OPTION_VALUE_REPORT" "AndroidArtifactScanReport/v1"
assert_report_contains "$ARTIFACT_EXPECTED_CERT_OPTION_VALUE_REPORT" "status=failed"
assert_report_contains "$ARTIFACT_EXPECTED_CERT_OPTION_VALUE_REPORT" "reason=missing-expected-certificate-sha256-argument"
assert_report_contains "$ARTIFACT_EXPECTED_CERT_OPTION_VALUE_REPORT" "failedTarget=argument-parser"
grep -q '^artifact1Sha256=' "$ARTIFACT_DIR/artifact.properties" ||
  fail "artifact scan report must include artifact sha"
grep -q '^artifact1SizeBytes=' "$ARTIFACT_DIR/artifact.properties" ||
  fail "artifact scan report must include artifact size"
expect_failure \
  "artifact scan rejects bundled model" \
  scripts/scan_android_artifacts.sh --apk "$UNSAFE_APK" --report "$ARTIFACT_DIR/artifact-failed.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-failed.properties" "status=failed"
assert_release_verifier_report_schema "$ARTIFACT_DIR/artifact-failed.properties" "AndroidArtifactScanReport/v1"
assert_report_contains_text "$ARTIFACT_DIR/artifact-failed.properties" "forbidden-artifact-file"
assert_report_contains_text "$ARTIFACT_DIR/artifact-failed.properties" "sensitive-string"
if grep -q "$ARTIFACT_SCAN_SECRET" <<<"$LAST_OUTPUT"; then
  fail "artifact scan stderr must redact raw secret values"
fi
if grep -q "$ARTIFACT_SCAN_SECRET" "$ARTIFACT_DIR/artifact-failed.properties"; then
  fail "artifact scan report must not contain raw secret values"
fi
for artifact_secret_value in "$ARTIFACT_SCAN_HF_TOKEN" "$ARTIFACT_SCAN_BEARER" "$ARTIFACT_SCAN_API_KEY"; do
  if grep -q "$artifact_secret_value" <<<"$LAST_OUTPUT" ||
    grep -q "$artifact_secret_value" "$ARTIFACT_DIR/artifact-failed.properties"; then
    fail "artifact scan outputs must redact raw expanded secret patterns"
  fi
done
expect_failure \
  "artifact scan rejects unreadable aab" \
  scripts/scan_android_artifacts.sh --aab "$BAD_AAB" --report "$ARTIFACT_DIR/artifact-bad-aab.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-bad-aab.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/artifact-bad-aab.properties" "artifact-not-readable-zip"
expect_failure \
  "artifact scan require-signed rejects unsigned zip" \
  scripts/scan_android_artifacts.sh --apk "$SAFE_APK" --require-signed --report "$ARTIFACT_DIR/artifact-unsigned.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-unsigned.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/artifact-unsigned.properties" "signing-status"
expect_failure \
  "artifact scan require-signed rejects unsigned aab" \
  scripts/scan_android_artifacts.sh --aab "$SAFE_AAB" --require-signed --report "$ARTIFACT_DIR/artifact-unsigned-aab.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-unsigned-aab.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/artifact-unsigned-aab.properties" "signing-status"
DEBUG_SCAN_KEYSTORE="$TMP_DIR/debug-scan.keystore"
DEBUG_SIGNED_AAB="$TMP_DIR/debug-signed.aab"
cp "$SAFE_AAB" "$DEBUG_SIGNED_AAB"
keytool -genkeypair \
  -keystore "$DEBUG_SCAN_KEYSTORE" \
  -storepass android \
  -keypass android \
  -alias androiddebugkey \
  -dname "CN=Android Debug,O=Android,C=US" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 >/dev/null
jarsigner \
  -keystore "$DEBUG_SCAN_KEYSTORE" \
  -storepass android \
  -keypass android \
  "$DEBUG_SIGNED_AAB" \
  androiddebugkey >/dev/null
DEBUG_SIGNED_AAB_CERT_SHA="$(
  keytool -printcert -jarfile "$DEBUG_SIGNED_AAB" 2>/dev/null |
    awk -F': ' '/SHA256:/ {gsub(":", "", $2); print tolower($2); exit}'
)"
expect_failure \
  "artifact scan require-signed rejects debug certificate" \
  scripts/scan_android_artifacts.sh --aab "$DEBUG_SIGNED_AAB" --require-signed --report "$ARTIFACT_DIR/artifact-debug-cert.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-debug-cert.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/artifact-debug-cert.properties" "debug-certificate"
expect_success \
  "artifact scan allows debug certificate only for smoke" \
  scripts/scan_android_artifacts.sh --aab "$DEBUG_SIGNED_AAB" --require-signed --allow-debug-certificate --report "$ARTIFACT_DIR/artifact-debug-cert-smoke.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-debug-cert-smoke.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/artifact-debug-cert-smoke.properties" "allowDebugCertificate=1"
expect_failure \
  "artifact scan rejects unexpected signing certificate" \
  scripts/scan_android_artifacts.sh --aab "$DEBUG_SIGNED_AAB" --require-signed --allow-debug-certificate --expected-certificate-sha256 0000000000000000000000000000000000000000000000000000000000000000 --report "$ARTIFACT_DIR/artifact-cert-mismatch.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-cert-mismatch.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/artifact-cert-mismatch.properties" "certificate-sha-mismatch"
expect_success \
  "artifact scan accepts expected signing certificate" \
  scripts/scan_android_artifacts.sh --aab "$DEBUG_SIGNED_AAB" --require-signed --allow-debug-certificate --expected-certificate-sha256 "$DEBUG_SIGNED_AAB_CERT_SHA" --report "$ARTIFACT_DIR/artifact-cert-match.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-cert-match.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/artifact-cert-match.properties" "expectedCertificateSha256=$DEBUG_SIGNED_AAB_CERT_SHA"
