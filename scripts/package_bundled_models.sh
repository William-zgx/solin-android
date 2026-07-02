#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
BUILD_TOOLS_DIR="${BUILD_TOOLS_DIR:-$({ find "$ANDROID_SDK/build-tools" -maxdepth 1 -type d 2>/dev/null || true; } | sort | tail -n 1)}"
APKSIGNER="${APKSIGNER:-$BUILD_TOOLS_DIR/apksigner}"
ADB_BIN="${ADB_BIN:-$ANDROID_SDK/platform-tools/adb}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"

PACKAGE_NAME="com.bytedance.zgx.solin"
RUN_GRADLE_BUILD="${RUN_GRADLE_BUILD:-1}"
INSTALL_ON_DEVICE="${INSTALL_ON_DEVICE:-0}"
ALLOW_DEBUG_KEYSTORE="${ALLOW_DEBUG_KEYSTORE:-0}"
RELEASE_KEYSTORE_PASSWORD_FILE="${RELEASE_KEYSTORE_PASSWORD_FILE:-}"
RELEASE_KEY_PASSWORD_FILE="${RELEASE_KEY_PASSWORD_FILE:-}"
SIGNED_DIR="${SIGNED_DIR:-app/build/outputs/apk/bundledModels/signed-splits}"
REPORT_FILE="${REPORT_FILE:-build/verification/bundled-models/package.properties}"
INSTALL_OUTPUT_FILE="${INSTALL_OUTPUT_FILE:-${REPORT_FILE%.properties}.install.txt}"
PM_PATH_OUTPUT_FILE="${PM_PATH_OUTPUT_FILE:-${REPORT_FILE%.properties}.pm-path.txt}"
START_OUTPUT_FILE="${START_OUTPUT_FILE:-${REPORT_FILE%.properties}.start.txt}"
STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
ORIGINAL_ARGS=("$@")
SOLIN_SCRIPT_COMMAND="scripts/package_bundled_models.sh"
source "$ROOT_DIR/scripts/lib/report_helpers.sh"
MODEL_LICENSE_REVIEW_FILE="docs/model_license_review.json"
MODEL_LICENSE_GATE_COMMAND="VERIFY_PERF_BASELINE=0 VERIFY_MODEL_LICENSES=1 scripts/verify_release_gate.sh"

RAW_APKS=(
  "app/build/outputs/apk/bundledModels/app-bundledModels-unsigned.apk"
  "modelpackE2b/build/outputs/apk/bundledModels/modelpackE2b-bundledModels.apk"
  "modelpackE2bExtra/build/outputs/apk/bundledModels/modelpackE2bExtra-bundledModels.apk"
  "modelpackE4b/build/outputs/apk/bundledModels/modelpackE4b-bundledModels.apk"
  "modelpackE4bExtra/build/outputs/apk/bundledModels/modelpackE4bExtra-bundledModels.apk"
)
EXPECTED_SPLITS=(
  "base.apk"
  "split_modelpackE2b.apk"
  "split_modelpackE2bExtra.apk"
  "split_modelpackE4b.apk"
  "split_modelpackE4bExtra.apk"
)
SIGNED_APKS=()
SIGNED_APK_COUNT=0
SELECTED_SERIAL=""
FAILED_TARGET=""
FAILURE_REASON=""
TMP_SECRET_DIR=""
KEYSTORE_PASSWORD_FILE_RESOLVED=""
KEY_PASSWORD_FILE_RESOLVED=""

print_compliance_notice() {
  cat >&2 <<EOF
package_bundled_models: compliance note:
  bundledModels APKs include third-party model bytes.
  SOLIN_HF_TOKEN, SHA-256 verification, and APK signing do not approve model
  license, redistribution, attribution, notice, store-policy, or public use.
  Keep artifacts internal until ${MODEL_LICENSE_REVIEW_FILE} is approved and
  '${MODEL_LICENSE_GATE_COMMAND}' passes.
EOF
}

write_report() {
  local exit_code="$1"
  local status="failed"
  [[ "$exit_code" -eq 0 ]] && status="passed"
  mkdir -p "$(dirname "$REPORT_FILE")"
  {
    printf 'status=%s\n' "$status"
    printf 'exit_code=%s\n' "$exit_code"
    printf 'target=bundled-models-package\n'
    printf 'artifactSchema=BundledModelsPackageReport/v1\n'
    printf 'owner=release-engineering\n'
    printf 'recordedAt=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'startedAt=%s\n' "$STARTED_AT_UTC"
    printf 'command=%s\n' "$(command_line)"
    printf 'reproduciblePath=%s\n' "$REPORT_FILE"
    printf 'failedTarget=%s\n' "${FAILED_TARGET:-}"
    printf 'reason=%s\n' "${FAILURE_REASON:-}"
    printf 'runGradleBuild=%s\n' "$RUN_GRADLE_BUILD"
    printf 'installOnDevice=%s\n' "$INSTALL_ON_DEVICE"
    printf 'allowDebugKeystore=%s\n' "$ALLOW_DEBUG_KEYSTORE"
    printf 'signedDir=%s\n' "$SIGNED_DIR"
    printf 'signedApkCount=%s\n' "$SIGNED_APK_COUNT"
    printf 'installOutputFile=%s\n' "$INSTALL_OUTPUT_FILE"
    printf 'pmPathOutputFile=%s\n' "$PM_PATH_OUTPUT_FILE"
    printf 'startOutputFile=%s\n' "$START_OUTPUT_FILE"
    printf 'serial=%s\n' "${SELECTED_SERIAL:-}"
    printf 'bundledModelComplianceBoundary=%s\n' "third-party-model-bytes-included"
    printf 'modelLicenseReviewFile=%s\n' "$MODEL_LICENSE_REVIEW_FILE"
    printf 'externalDistributionRequiresModelLicenseApproval=%s\n' "1"
    printf 'modelLicenseGateCommand=%s\n' "$MODEL_LICENSE_GATE_COMMAND"
    local index=0
    local apk
    for apk in "${RAW_APKS[@]}"; do
      index=$((index + 1))
      printf 'rawApk%sPath=%s\n' "$index" "$apk"
      printf 'rawApk%sSha256=%s\n' "$index" "$(sha256_or_empty "$apk")"
      printf 'rawApk%sSizeBytes=%s\n' "$index" "$(file_size_or_empty "$apk")"
    done
    index=0
    if [[ "$SIGNED_APK_COUNT" -gt 0 ]]; then
      for apk in "${SIGNED_APKS[@]}"; do
        index=$((index + 1))
        printf 'signedApk%sPath=%s\n' "$index" "$apk"
        printf 'signedApk%sSha256=%s\n' "$index" "$(sha256_or_empty "$apk")"
        printf 'signedApk%sSizeBytes=%s\n' "$index" "$(file_size_or_empty "$apk")"
      done
    fi
  } > "$REPORT_FILE"
  echo "Bundled models package report: $REPORT_FILE"
}

on_exit() {
  local code="$?"
  trap - EXIT
  if [[ "$code" -ne 0 && -z "$FAILED_TARGET" ]]; then
    FAILED_TARGET="script"
    FAILURE_REASON="script-failed"
  fi
  write_report "$code"
  cleanup_secret_files
  exit "$code"
}
trap on_exit EXIT

fail() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  echo "package_bundled_models: $*" >&2
  exit 1
}

prepare_password_file() {
  local env_name="$1"
  local file_env_name="$2"
  local label="$3"
  local file_value="${!file_env_name:-}"
  local secret_value="${!env_name:-}"
  local out_file

  if [[ -n "$file_value" ]]; then
    [[ -f "$file_value" ]] ||
      fail signing "missing-${label}-file" "Password file is missing: $file_env_name=$file_value"
    out_file="$file_value"
  else
    [[ -n "$secret_value" ]] ||
      fail signing "missing-${label}" "Missing $env_name or $file_env_name."
    if [[ -z "$TMP_SECRET_DIR" ]]; then
      TMP_SECRET_DIR="$(mktemp -d)"
      chmod 700 "$TMP_SECRET_DIR"
    fi
    out_file="$TMP_SECRET_DIR/${label}.txt"
    umask 077
    printf '%s' "$secret_value" > "$out_file"
  fi

  [[ -s "$out_file" ]] ||
    fail signing "empty-${label}-file" "Password file is empty: $file_env_name=$out_file"
  printf '%s' "$out_file"
}

cleanup_secret_files() {
  if [[ -n "${TMP_SECRET_DIR:-}" ]]; then
    rm -rf "$TMP_SECRET_DIR"
  fi
}

usage() {
  cat >&2 <<'EOF'
Usage: scripts/package_bundled_models.sh

Build, sign, and optionally install the internal bundledModels split APK set.

Compliance note:
  The generated split APKs include third-party model bytes. Download tokens,
  SHA-256 verification, and APK signing do not approve model license,
  redistribution, attribution, notice, store-policy, or public use. Keep
  artifacts internal until docs/model_license_review.json is approved and
  VERIFY_PERF_BASELINE=0 VERIFY_MODEL_LICENSES=1 scripts/verify_release_gate.sh passes.

Common environment:
  SOLIN_BUNDLED_MODELS_DIR  Directory with already verified model files.
  SOLIN_HF_TOKEN            Hugging Face token for this build invocation only.
  RUN_GRADLE_BUILD=0             Reuse existing raw APK outputs.
  ALLOW_DEBUG_KEYSTORE=1         Use ~/.android/debug.keystore if release env is absent.
  INSTALL_ON_DEVICE=1            adb install-multiple --no-incremental -r after signing.
  ANDROID_SERIAL=<serial>        Select the target device when installing.

Production/team signing environment:
  RELEASE_KEYSTORE
  RELEASE_KEY_ALIAS
  RELEASE_KEYSTORE_PASSWORD or RELEASE_KEYSTORE_PASSWORD_FILE
  RELEASE_KEY_PASSWORD or RELEASE_KEY_PASSWORD_FILE
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help|help)
      trap - EXIT
      usage
      exit 0
      ;;
    *)
      fail argument-parser unknown-argument "Unknown argument: $1"
      ;;
  esac
done

require_tool() {
  local path="$1"
  local name="$2"
  [[ -n "$path" && -x "$path" ]] || fail tool "$name-not-found" "$name not found or not executable: $path"
}

configure_signing() {
  if [[ "$ALLOW_DEBUG_KEYSTORE" == "1" && -z "${RELEASE_KEYSTORE:-}" ]]; then
    RELEASE_KEYSTORE="$HOME/.android/debug.keystore"
    RELEASE_KEY_ALIAS="androiddebugkey"
    RELEASE_KEYSTORE_PASSWORD="android"
    RELEASE_KEY_PASSWORD="android"
  fi

  [[ -n "${RELEASE_KEYSTORE:-}" ]] || fail signing missing-release-keystore "Missing RELEASE_KEYSTORE. Set ALLOW_DEBUG_KEYSTORE=1 only for local smoke signing."
  [[ -n "${RELEASE_KEY_ALIAS:-}" ]] || fail signing missing-release-key-alias "Missing RELEASE_KEY_ALIAS."
  KEYSTORE_PASSWORD_FILE_RESOLVED="$(prepare_password_file RELEASE_KEYSTORE_PASSWORD RELEASE_KEYSTORE_PASSWORD_FILE release-keystore-password)"
  KEY_PASSWORD_FILE_RESOLVED="$(prepare_password_file RELEASE_KEY_PASSWORD RELEASE_KEY_PASSWORD_FILE release-key-password)"
  [[ -f "$RELEASE_KEYSTORE" ]] || fail signing release-keystore-missing "Keystore not found: $RELEASE_KEYSTORE"

  if [[ "$ALLOW_DEBUG_KEYSTORE" != "1" ]]; then
    local keystore_name
    keystore_name="$(basename "$RELEASE_KEYSTORE" | tr '[:upper:]' '[:lower:]')"
    [[ "$keystore_name" != "debug.keystore" ]] ||
      fail signing debug-keystore-not-allowed "Refusing debug.keystore unless ALLOW_DEBUG_KEYSTORE=1."
  fi
}

select_device() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    local selected_state
    selected_state="$("$ADB_BIN" devices | awk -v serial="$ANDROID_SERIAL" '$1 == serial {print $2; found=1} END {if (!found) print ""}')"
    [[ "$selected_state" == "device" ]] ||
      fail device-selection selected-device-unavailable "ANDROID_SERIAL=$ANDROID_SERIAL is not authorized; state is ${selected_state:-missing}."
    SELECTED_SERIAL="$ANDROID_SERIAL"
    return
  fi

  local serials=()
  while IFS= read -r serial; do
    [[ -n "$serial" ]] && serials+=("$serial")
  done < <("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" {print $1}')
  [[ "${#serials[@]}" -eq 1 ]] ||
    fail device-selection no-single-authorized-device "Connect exactly one authorized device or set ANDROID_SERIAL."
  SELECTED_SERIAL="${serials[0]}"
}

require_tool "$APKSIGNER" apksigner
configure_signing
print_compliance_notice

if [[ "$RUN_GRADLE_BUILD" == "1" ]]; then
  "$GRADLE_CMD" checkBundledModelsPackageOutputs
else
  for apk in "${RAW_APKS[@]}"; do
    [[ -f "$apk" ]] || fail input raw-apk-missing "Raw bundledModels APK missing: $apk"
  done
fi

mkdir -p "$SIGNED_DIR"
SIGNED_APKS=()
for apk in "${RAW_APKS[@]}"; do
  [[ -f "$apk" ]] || fail input raw-apk-missing "Raw bundledModels APK missing: $apk"
  out="$SIGNED_DIR/$(basename "$apk" .apk)-signed.apk"
  cp -f "$apk" "$out"
  "$APKSIGNER" sign \
    --ks "$RELEASE_KEYSTORE" \
    --ks-key-alias "$RELEASE_KEY_ALIAS" \
    --ks-pass "file:$KEYSTORE_PASSWORD_FILE_RESOLVED" \
    --key-pass "file:$KEY_PASSWORD_FILE_RESOLVED" \
    "$out"
  "$APKSIGNER" verify --verbose "$out" > "$out.verify.txt"
  SIGNED_APKS+=("$out")
  SIGNED_APK_COUNT=$((SIGNED_APK_COUNT + 1))
done

if [[ "$INSTALL_ON_DEVICE" == "1" ]]; then
  require_tool "$ADB_BIN" adb
  select_device
  ADB=("$ADB_BIN" -s "$SELECTED_SERIAL")
  mkdir -p "$(dirname "$INSTALL_OUTPUT_FILE")"
  if ! "${ADB[@]}" install-multiple --no-incremental -r "${SIGNED_APKS[@]}" >"$INSTALL_OUTPUT_FILE" 2>&1; then
    cat "$INSTALL_OUTPUT_FILE" >&2
    fail install install-multiple-failed "adb install-multiple --no-incremental -r failed."
  fi
  "${ADB[@]}" shell pm path "$PACKAGE_NAME" >"$PM_PATH_OUTPUT_FILE" 2>&1 ||
    fail install package-path-missing "Package is not visible after install."
  for split in "${EXPECTED_SPLITS[@]}"; do
    grep -Fq "$split" "$PM_PATH_OUTPUT_FILE" ||
      fail install missing-installed-split "Installed package is missing $split."
  done
  "${ADB[@]}" shell monkey -p "$PACKAGE_NAME" 1 >"$START_OUTPUT_FILE" 2>&1 ||
    fail launch app-launch-failed "Unable to launch $PACKAGE_NAME after install."
fi

echo "Bundled models split APKs are signed in $SIGNED_DIR"
if [[ "$INSTALL_ON_DEVICE" == "1" ]]; then
  echo "Installed on $SELECTED_SERIAL with --no-incremental -r"
fi
