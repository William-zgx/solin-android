# shellcheck shell=bash
# Validation script tests: emulator API matrix and x86 host checks.
# Sourced in order by scripts/test_validation_scripts.sh; shares its shell state
# (helpers, fakes, TMP_DIR, ARTIFACT_DIR). Not runnable on its own.

FAKE_SDKMANAGER="$TMP_DIR/fake-sdkmanager"
FAKE_AVDMANAGER="$TMP_DIR/fake-avdmanager"
FAKE_MATRIX_REGRESSION="$TMP_DIR/fake-matrix-regression"
FAKE_AVD_ROOT="$TMP_DIR/fake-avd-root"
FAKE_SDKMANAGER_LOG="$TMP_DIR/fake-sdkmanager.log"
FAKE_AVDMANAGER_LOG="$TMP_DIR/fake-avdmanager.log"
create_fake_sdkmanager "$FAKE_SDKMANAGER"
create_fake_avdmanager "$FAKE_AVDMANAGER"
create_fake_matrix_regression "$FAKE_MATRIX_REGRESSION"
mkdir -p "$FAKE_AVD_ROOT/api28.avd" "$FAKE_AVD_ROOT/api36.avd"
cat > "$FAKE_AVD_ROOT/api28.avd/config.ini" <<'API28_AVD_CONFIG'
abi.type=arm64-v8a
image.sysdir.1=system-images/android-28/google_apis/arm64-v8a/
tag.id=google_apis
target=android-28
API28_AVD_CONFIG
cat > "$FAKE_AVD_ROOT/api36.avd/config.ini" <<'API36_AVD_CONFIG'
abi.type=arm64-v8a
image.sysdir.1=system-images/android-36/google_apis/arm64-v8a/
tag.id=google_apis
target=android-36
API36_AVD_CONFIG
FAKE_SDKMANAGER_INSTALLED=$'system-images;android-28;google_apis;arm64-v8a | 1 | Fake\nsystem-images;android-36;google_apis;arm64-v8a | 1 | Fake'
expect_success \
  "emulator api matrix readiness accepts installed images and avds" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED" \
  scripts/check_emulator_api_matrix.sh \
    --sdkmanager "$FAKE_SDKMANAGER" \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "28 36" \
    --report "$ARTIFACT_DIR/emulator-api-matrix-readiness.properties"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness.properties" "target=emulator-api-matrix-readiness"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness.properties" "requiredApis=28,36"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness.properties" "tag=google_apis"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness.properties" "abi=arm64-v8a"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness.properties" "installedSystemImageApis=28,36"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness.properties" "availableAvdApis=28,36"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness.properties" "missingSystemImageApis="
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness.properties" "missingAvdApis="
rm -rf "$FAKE_AVD_ROOT/api32.avd"
FAKE_SDKMANAGER_INSTALLED_PREPARE=$'system-images;android-36;google_apis;arm64-v8a | 1 | Fake\nplatforms;android-36 | 1 | Fake'
: > "$FAKE_SDKMANAGER_LOG"
: > "$FAKE_AVDMANAGER_LOG"
expect_success \
  "emulator api matrix prepare dry-run reports missing work without applying" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_SDKMANAGER_LOG="$FAKE_SDKMANAGER_LOG" \
  FAKE_AVDMANAGER_LOG="$FAKE_AVDMANAGER_LOG" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED_PREPARE" \
  scripts/prepare_emulator_api_matrix.sh \
    --sdkmanager "$FAKE_SDKMANAGER" \
    --avdmanager "$FAKE_AVDMANAGER" \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "32 36" \
    --report "$ARTIFACT_DIR/emulator-api-matrix-prepare-dry-run.properties"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-prepare-dry-run.properties" "status=pending"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-prepare-dry-run.properties" "reason=apply-required"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-prepare-dry-run.properties" "missingSystemImageApis=32"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-prepare-dry-run.properties" "missingAvdApis=32"
assert_report_contains_text "$ARTIFACT_DIR/emulator-api-matrix-prepare-dry-run.properties" "platforms;android-32"
assert_report_contains_text "$ARTIFACT_DIR/emulator-api-matrix-prepare-dry-run.properties" "system-images;android-32;google_apis;arm64-v8a"
if grep -q 'system-images;android-32;google_apis;arm64-v8a' "$FAKE_SDKMANAGER_LOG"; then
  fail "prepare dry-run must not install missing SDK packages"
fi
if [[ -s "$FAKE_AVDMANAGER_LOG" ]]; then
  fail "prepare dry-run must not create AVDs"
fi
: > "$FAKE_SDKMANAGER_LOG"
: > "$FAKE_AVDMANAGER_LOG"
expect_success \
  "emulator api matrix prepare applies missing packages and avds" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_SDKMANAGER_LOG="$FAKE_SDKMANAGER_LOG" \
  FAKE_AVDMANAGER_LOG="$FAKE_AVDMANAGER_LOG" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED_PREPARE" \
  APPLY=1 scripts/prepare_emulator_api_matrix.sh \
    --sdkmanager "$FAKE_SDKMANAGER" \
    --avdmanager "$FAKE_AVDMANAGER" \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "32 36" \
    --report "$ARTIFACT_DIR/emulator-api-matrix-prepare-apply.properties"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-prepare-apply.properties" "status=passed"
grep -q -- '--sdk_root=.*platforms;android-32.*system-images;android-32;google_apis;arm64-v8a' "$FAKE_SDKMANAGER_LOG" ||
  fail "prepare apply must install missing API 32 platform and system image"
grep -q -- 'create avd --force --name solin_api32_arm64_v8a --package system-images;android-32;google_apis;arm64-v8a' "$FAKE_AVDMANAGER_LOG" ||
  fail "prepare apply must create the missing API 32 arm64 AVD"

X86_CPUINFO="$TMP_DIR/x86-cpuinfo"
X86_KVM_DEVICE="$TMP_DIR/kvm"
cat > "$X86_CPUINFO" <<'X86_CPUINFO_FIXTURE'
processor	: 0
flags		: fpu vme de pse tsc msr pae mce cx8 apic sep mtrr vmx
X86_CPUINFO_FIXTURE
: > "$X86_KVM_DEVICE"
expect_success \
  "x86 emulator host check passes with x86 cpu virtualization and kvm" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  HOST_MACHINE="x86_64" \
  CPUINFO_FILE="$X86_CPUINFO" \
  KVM_DEVICE="$X86_KVM_DEVICE" \
  GLIBC_VERSION="2.31" \
  REPORT_FILE="$ARTIFACT_DIR/x86-emulator-host.properties" \
  scripts/check_x86_emulator_host.sh --gui-required
assert_report_contains "$ARTIFACT_DIR/x86-emulator-host.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/x86-emulator-host.properties" "target=x86-emulator-host"
assert_report_contains "$ARTIFACT_DIR/x86-emulator-host.properties" "hostIsX86_64=true"
assert_report_contains "$ARTIFACT_DIR/x86-emulator-host.properties" "cpuVirtualizationFlagPresent=true"
assert_report_contains "$ARTIFACT_DIR/x86-emulator-host.properties" "kvmDeviceAccessible=true"
assert_report_contains "$ARTIFACT_DIR/x86-emulator-host.properties" "glibcGuiReady=true"

expect_failure \
  "x86 emulator host check fails without virtualization and kvm" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  HOST_MACHINE="x86_64" \
  CPUINFO_FILE="$TMP_DIR/missing-cpuinfo" \
  KVM_DEVICE="$TMP_DIR/missing-kvm" \
  GLIBC_VERSION="2.28" \
  REPORT_FILE="$ARTIFACT_DIR/x86-emulator-host-missing.properties" \
  scripts/check_x86_emulator_host.sh --gui-required
assert_report_contains "$ARTIFACT_DIR/x86-emulator-host-missing.properties" "status=failed"
assert_report_contains_text "$ARTIFACT_DIR/x86-emulator-host-missing.properties" "cpu-virtualization-flag-missing"
assert_report_contains_text "$ARTIFACT_DIR/x86-emulator-host-missing.properties" "kvm-device-missing"
assert_report_contains_text "$ARTIFACT_DIR/x86-emulator-host-missing.properties" "gui-glibc-too-old"

: > "$FAKE_SDKMANAGER_LOG"
: > "$FAKE_AVDMANAGER_LOG"
FAKE_SDKMANAGER_INSTALLED=""
expect_success \
  "x86 emulator prepare wraps matrix prepare defaults" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_SDKMANAGER_LOG="$FAKE_SDKMANAGER_LOG" \
  FAKE_AVDMANAGER_LOG="$FAKE_AVDMANAGER_LOG" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED" \
  APPLY=1 scripts/prepare_x86_emulator.sh \
    --sdkmanager "$FAKE_SDKMANAGER" \
    --avdmanager "$FAKE_AVDMANAGER" \
    --avd-root "$FAKE_AVD_ROOT" \
    --report "$ARTIFACT_DIR/x86-emulator-prepare.properties"
assert_report_contains "$ARTIFACT_DIR/x86-emulator-prepare.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/x86-emulator-prepare.properties" "requiredApis=36"
assert_report_contains "$ARTIFACT_DIR/x86-emulator-prepare.properties" "abi=x86_64"
grep -q -- '--sdk_root=.*platforms;android-36.*system-images;android-36;google_apis;x86_64' "$FAKE_SDKMANAGER_LOG" ||
  fail "x86 prepare must install the API 36 x86_64 system image"
grep -q -- 'create avd --force --name solin_api36_x86_64 --package system-images;android-36;google_apis;x86_64' "$FAKE_AVDMANAGER_LOG" ||
  fail "x86 prepare must create the default API 36 x86_64 AVD"

mkdir -p "$FAKE_AVD_ROOT/api36-x86-spaced.avd"
cat > "$FAKE_AVD_ROOT/api36-x86-spaced.avd/config.ini" <<'API36_X86_SPACED_AVD_CONFIG'
abi.type = x86_64
image.sysdir.1 = system-images/android-36/google_apis/x86_64/
tag.id = google_apis
target = android-36
API36_X86_SPACED_AVD_CONFIG
FAKE_SDKMANAGER_INSTALLED=$'system-images;android-36;google_apis;x86_64 | 7 | Fake'
expect_success \
  "emulator api matrix readiness accepts avdmanager config spacing" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED" \
  scripts/check_emulator_api_matrix.sh \
    --sdkmanager "$FAKE_SDKMANAGER" \
    --avd-root "$FAKE_AVD_ROOT" \
    --abi x86_64 \
    --required-apis "36" \
    --report "$ARTIFACT_DIR/emulator-api-matrix-x86-spaced.properties"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-x86-spaced.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-x86-spaced.properties" "abi=x86_64"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-x86-spaced.properties" "availableAvdApis=36"

rm -rf "$FAKE_AVD_ROOT/api28.avd"
FAKE_SDKMANAGER_INSTALLED=$'system-images;android-36;google_apis;arm64-v8a | 1 | Fake'
expect_failure \
  "emulator api matrix readiness reports missing image and avd" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED" \
  scripts/check_emulator_api_matrix.sh \
    --sdkmanager "$FAKE_SDKMANAGER" \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "28 36" \
    --report "$ARTIFACT_DIR/emulator-api-matrix-readiness-missing.properties"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness-missing.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness-missing.properties" "failedTarget=api-matrix-readiness"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness-missing.properties" "reason=missing-system-image-api-28,missing-avd-api-28"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness-missing.properties" "missingSystemImageApis=28"
assert_report_contains "$ARTIFACT_DIR/emulator-api-matrix-readiness-missing.properties" "missingAvdApis=28"
mkdir -p "$FAKE_AVD_ROOT/api28.avd"
cat > "$FAKE_AVD_ROOT/api28.avd/config.ini" <<'API28_AVD_CONFIG'
abi.type=arm64-v8a
image.sysdir.1=system-images/android-28/google_apis/arm64-v8a/
tag.id=google_apis
target=android-28
API28_AVD_CONFIG
FAKE_SDKMANAGER_INSTALLED=$'system-images;android-28;google_apis;arm64-v8a | 1 | Fake\nsystem-images;android-36;google_apis;arm64-v8a | 1 | Fake'
MATRIX_ARTIFACT_DIR="$ARTIFACT_DIR/emulator-api-matrix"
MATRIX_REPORT="$ARTIFACT_DIR/regression-emulator-api-matrix.properties"
MATRIX_CI_SHA="1111111111111111111111111111111111111111"
expect_success \
  "emulator api matrix regression accepts all api reports" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" SDKMANAGER_CMD="$FAKE_SDKMANAGER" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED" \
  GITHUB_WORKFLOW="Android Verification" GITHUB_JOB="emulator-api-matrix" \
  GITHUB_RUN_ID=123456 GITHUB_SHA="$MATRIX_CI_SHA" \
  scripts/regression_emulator_api_matrix.sh \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "28 36" \
    --check-script scripts/check_emulator_api_matrix.sh \
    --regression-script "$FAKE_MATRIX_REGRESSION" \
    --keep-emulators \
    --artifact-dir "$MATRIX_ARTIFACT_DIR" \
    --report "$MATRIX_REPORT"
assert_report_contains "$MATRIX_REPORT" "status=passed"
assert_report_contains "$MATRIX_REPORT" "target=regression-emulator-api-matrix"
assert_report_contains "$MATRIX_REPORT" "workflow=Android Verification"
assert_report_contains "$MATRIX_REPORT" "job=emulator-api-matrix"
assert_report_contains "$MATRIX_REPORT" "runId=123456"
assert_report_contains "$MATRIX_REPORT" "commitSha=$MATRIX_CI_SHA"
assert_report_contains "$MATRIX_REPORT" "artifactDir=$MATRIX_ARTIFACT_DIR"
assert_report_contains "$MATRIX_REPORT" "readinessReportFile=$MATRIX_ARTIFACT_DIR/emulator-api-matrix-readiness.properties"
assert_report_contains "$MATRIX_REPORT" "readinessReportSha256=$(shasum -a 256 "$MATRIX_ARTIFACT_DIR/emulator-api-matrix-readiness.properties" | awk '{print $1}')"
assert_report_contains "$MATRIX_REPORT" "passedApis=28,36"
assert_report_contains "$MATRIX_REPORT" "skippedApis="
assert_report_contains "$MATRIX_REPORT" "api28Status=passed"
assert_report_contains "$MATRIX_REPORT" "api36Status=passed"
MATRIX_SKIPPED_ARTIFACT_DIR="$ARTIFACT_DIR/emulator-api-matrix-skipped"
MATRIX_SKIPPED_REPORT="$ARTIFACT_DIR/regression-emulator-api-matrix-skipped.properties"
expect_success \
  "emulator api matrix regression records allowed infra skips without passing them" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" SDKMANAGER_CMD="$FAKE_SDKMANAGER" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED" \
  FAKE_MATRIX_SKIP_API=28 ALLOW_EMULATOR_INFRA_UNAVAILABLE=1 \
  scripts/regression_emulator_api_matrix.sh \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "28 36" \
    --check-script scripts/check_emulator_api_matrix.sh \
    --regression-script "$FAKE_MATRIX_REGRESSION" \
    --keep-emulators \
    --artifact-dir "$MATRIX_SKIPPED_ARTIFACT_DIR" \
    --report "$MATRIX_SKIPPED_REPORT"
assert_report_contains "$MATRIX_SKIPPED_REPORT" "status=skipped"
assert_report_contains "$MATRIX_SKIPPED_REPORT" "failedTarget=emulator-infra"
assert_report_contains "$MATRIX_SKIPPED_REPORT" "reason=emulator-infra-hvf-unsupported"
assert_report_contains "$MATRIX_SKIPPED_REPORT" "passedApis=36"
assert_report_contains "$MATRIX_SKIPPED_REPORT" "skippedApis=28"
assert_report_contains "$MATRIX_SKIPPED_REPORT" "api28Status=skipped"
assert_report_contains "$MATRIX_SKIPPED_REPORT" "api36Status=passed"
MATRIX_EXPLICIT_ENV_REPORT="$ARTIFACT_DIR/regression-emulator-api-matrix-explicit-env.properties"
MATRIX_EXPLICIT_ENV_ARTIFACT_DIR="$ARTIFACT_DIR/emulator-api-matrix-explicit-env"
expect_success \
  "emulator api matrix artifact-dir keeps explicit env report" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" SDKMANAGER_CMD="$FAKE_SDKMANAGER" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED" \
  REPORT_FILE="$MATRIX_EXPLICIT_ENV_REPORT" \
  scripts/regression_emulator_api_matrix.sh \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "28 36" \
    --check-script scripts/check_emulator_api_matrix.sh \
    --regression-script "$FAKE_MATRIX_REGRESSION" \
    --keep-emulators \
    --artifact-dir "$MATRIX_EXPLICIT_ENV_ARTIFACT_DIR"
assert_report_contains "$MATRIX_EXPLICIT_ENV_REPORT" "status=passed"
assert_report_contains "$MATRIX_EXPLICIT_ENV_REPORT" "artifactDir=$MATRIX_EXPLICIT_ENV_ARTIFACT_DIR"
expect_failure \
  "emulator api matrix regression reports api failure" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" SDKMANAGER_CMD="$FAKE_SDKMANAGER" \
  FAKE_SDKMANAGER_INSTALLED="$FAKE_SDKMANAGER_INSTALLED" \
  FAKE_MATRIX_FAIL_API=28 \
  scripts/regression_emulator_api_matrix.sh \
    --avd-root "$FAKE_AVD_ROOT" \
    --required-apis "28 36" \
    --check-script scripts/check_emulator_api_matrix.sh \
    --regression-script "$FAKE_MATRIX_REGRESSION" \
    --keep-emulators \
    --artifact-dir "$ARTIFACT_DIR/emulator-api-matrix-failed" \
    --report "$ARTIFACT_DIR/regression-emulator-api-matrix-failed.properties"
assert_report_contains "$ARTIFACT_DIR/regression-emulator-api-matrix-failed.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator-api-matrix-failed.properties" "failedTarget=api-28-regression"
assert_report_contains "$ARTIFACT_DIR/regression-emulator-api-matrix-failed.properties" "reason=api-28-regression-regression-failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator-api-matrix-failed.properties" "failedApis=28"
assert_report_contains "$ARTIFACT_DIR/regression-emulator-api-matrix-failed.properties" "api28Status=failed"
