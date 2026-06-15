#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

VALIDATION_RECORD_FILE="${VALIDATION_RECORD_FILE:-docs/release_validation_record.json}"
ANDROID_TEST_SOURCE_DIR="${ANDROID_TEST_SOURCE_DIR:-app/src/androidTest}"
EXPECTED_RELEASE_ARTIFACT_SHA256="${EXPECTED_RELEASE_ARTIFACT_SHA256:-}"
REPORT_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file)
      VALIDATION_RECORD_FILE="${2:?missing validation record file}"
      shift 2
      ;;
    --report)
      REPORT_FILE="${2:?missing report path}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

write_report() {
  local status="$1"
  local reason="$2"
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'status=%s\n' "$status"
      printf 'target=release-validation-record\n'
      printf 'validationRecordFile=%s\n' "$VALIDATION_RECORD_FILE"
      printf 'androidTestSourceDir=%s\n' "$ANDROID_TEST_SOURCE_DIR"
      printf 'expectedReleaseArtifactSha256=%s\n' "$EXPECTED_RELEASE_ARTIFACT_SHA256"
      printf 'reason=%s\n' "$reason"
    } > "$REPORT_FILE"
  fi
}

if [[ ! -f "$VALIDATION_RECORD_FILE" ]]; then
  write_report failed missing-validation-record-file
  echo "Release validation record file is missing: $VALIDATION_RECORD_FILE" >&2
  exit 1
fi

TMP_FAILURES="$(mktemp)"
trap 'rm -f "$TMP_FAILURES"' EXIT

set +e
python3 - "$VALIDATION_RECORD_FILE" "$ANDROID_TEST_SOURCE_DIR" "$EXPECTED_RELEASE_ARTIFACT_SHA256" > "$TMP_FAILURES" <<'PY'
import json
import hashlib
import re
import sys
from datetime import date, datetime, timezone
from pathlib import Path

record_path = Path(sys.argv[1])
android_test_source_dir = Path(sys.argv[2])
expected_release_artifact_sha = sys.argv[3]

try:
    record = json.loads(record_path.read_text())
except Exception:
    print("json-parse-error")
    sys.exit(1)

def non_empty_string(value):
    return isinstance(value, str) and bool(value.strip())

def properties_for(path):
    values = {}
    with Path(path).open() as handle:
        for raw_line in handle:
            line = raw_line.rstrip("\n")
            if "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key] = value
    return values

def validate_date_field(value, prefix):
    if not non_empty_string(value):
        failures.append(f"{prefix}-date-missing")
        return
    date_pattern = re.compile(r"^\d{4}-\d{2}-\d{2}$")
    if not date_pattern.match(value):
        failures.append(f"{prefix}-date-invalid")
        return
    try:
        parsed_date = date.fromisoformat(value)
    except ValueError:
        failures.append(f"{prefix}-date-invalid")
    else:
        if parsed_date > date.today():
            failures.append(f"{prefix}-date-in-future")

def validate_utc_timestamp_fresh(value, max_age_days, prefix):
    if not non_empty_string(value):
        failures.append(f"{prefix}-recorded-at-missing")
        return
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError:
        failures.append(f"{prefix}-recorded-at-invalid")
        return
    now = datetime.now(timezone.utc)
    if parsed > now:
        failures.append(f"{prefix}-recorded-at-in-future")
    elif (now - parsed).days > max_age_days:
        failures.append(f"{prefix}-recorded-at-stale")

def validate_file_sha(prefix, path, expected_sha):
    if not non_empty_string(expected_sha):
        failures.append(f"{prefix}-sha-missing")
        return
    try:
        actual_sha = hashlib.sha256(Path(path).read_bytes()).hexdigest()
    except OSError:
        failures.append(f"{prefix}-sha-read-failed")
        return
    if actual_sha != expected_sha:
        failures.append(f"{prefix}-sha-mismatch")

def validate_release_artifact_binding(prefix, props):
    if not expected_release_artifact_sha:
        return
    actual_sha = props.get("releaseArtifactSha256", "")
    if not non_empty_string(actual_sha):
        failures.append(f"{prefix}-release-artifact-sha-missing")
    elif not re.match(r"^[0-9a-fA-F]{64}$", actual_sha):
        failures.append(f"{prefix}-release-artifact-sha-invalid")
    elif actual_sha.lower() != expected_release_artifact_sha.lower():
        failures.append(f"{prefix}-release-artifact-sha-mismatch")

def validate_evidence_record(section, key, value):
    prefix = f"{section}-{key}"
    if isinstance(value, str):
        if value == "passed":
            failures.append(f"{prefix}-evidence-record-invalid")
        else:
            failures.append(f"{prefix}-not-passed")
        return
    if not isinstance(value, dict):
        failures.append(f"{prefix}-evidence-record-invalid")
        return
    if value.get("status") != "passed":
        failures.append(f"{prefix}-not-passed")
    if not non_empty_string(value.get("evidence")):
        failures.append(f"{prefix}-evidence-missing")
    evidence_path = value.get("evidencePath", "")
    if not non_empty_string(evidence_path):
        failures.append(f"{prefix}-evidence-path-missing")
    elif not Path(evidence_path).is_file():
        failures.append(f"{prefix}-evidence-file-missing")
    else:
        validate_file_sha(f"{prefix}-evidence", evidence_path, value.get("evidenceSha256", ""))
        if section == "manual":
            validate_manual_evidence(key, evidence_path)
        if section == "flow":
            validate_flow_evidence(key, evidence_path)
    if not non_empty_string(value.get("owner")):
        failures.append(f"{prefix}-owner-missing")
    validate_date_field(value.get("date", ""), prefix)

def validate_api_matrix_evidence(api_level, evidence_path):
    prefix = f"api-{api_level}-evidence"
    props = properties_for(evidence_path)
    validate_release_artifact_binding(prefix, props)
    if props.get("status") != "passed":
        failures.append(f"{prefix}-status-not-passed")
    if props.get("exit_code") != "0":
        failures.append(f"{prefix}-exit-code-invalid")
    if props.get("target") != "regression-emulator":
        failures.append(f"{prefix}-target-invalid")
    if props.get("failedTarget", ""):
        failures.append(f"{prefix}-failed-target-not-empty")
    if props.get("reason", ""):
        failures.append(f"{prefix}-reason-not-empty")
    if not non_empty_string(props.get("started_at_utc", "")):
        failures.append(f"{prefix}-started-at-missing")
    if not non_empty_string(props.get("finished_at_utc", "")):
        failures.append(f"{prefix}-finished-at-missing")
    if props.get("clean_device") != "1":
        failures.append(f"{prefix}-clean-device-not-true")
    try:
        source_count = int(props.get("source_android_test_count", ""))
    except ValueError:
        failures.append(f"{prefix}-source-test-count-invalid")
    else:
        if source_count != source_android_test_count:
            failures.append(f"{prefix}-source-test-count-mismatch")
    try:
        expected_count = int(props.get("expected_android_test_count", ""))
    except ValueError:
        failures.append(f"{prefix}-expected-test-count-invalid")
    else:
        if expected_count < source_android_test_count:
            failures.append(f"{prefix}-expected-test-count-too-low")
    if props.get("api_level") != str(api_level):
        failures.append(f"{prefix}-api-mismatch")
    report_abi = props.get("abi", "")
    report_abis = {item.strip() for item in report_abi.split(",") if item.strip()}
    if "arm64-v8a" not in report_abis:
        failures.append(f"{prefix}-abi-mismatch")
    report_serial = props.get("serial", "")
    if not report_serial.startswith("emulator-"):
        failures.append(f"{prefix}-serial-not-emulator")
    if not non_empty_string(props.get("avd", "")):
        failures.append(f"{prefix}-avd-missing")
    instrumentation_output_count = validate_instrumentation_output(prefix, props.get("instrumentation_output_file", ""))
    try:
        actual_count = int(props.get("actual_android_test_count", ""))
    except ValueError:
        failures.append(f"{prefix}-test-count-invalid")
    else:
        if actual_count < source_android_test_count:
            failures.append(f"{prefix}-test-count-too-low")
        if instrumentation_output_count is not None and instrumentation_output_count != actual_count:
            failures.append(f"{prefix}-instrumentation-output-count-mismatch")
    validate_api_nested_emulator_report(api_level, props)
    validate_api_nested_device_report(api_level, props)

def validate_logcat_artifact(prefix, logcat_file):
    if not non_empty_string(logcat_file):
        failures.append(f"{prefix}-logcat-file-missing")
        return
    path = Path(logcat_file)
    if not path.is_file():
        failures.append(f"{prefix}-logcat-file-missing")
    elif path.stat().st_size <= 0:
        failures.append(f"{prefix}-logcat-file-empty")

def validate_crash_anr_smoke_report(prefix, smoke_report, expected_device_report="", expected_instrumentation_output="", expected_logcat_file=""):
    smoke_prefix = f"{prefix}-crash-anr-smoke"
    if not non_empty_string(smoke_report):
        failures.append(f"{smoke_prefix}-path-missing")
        return
    if not Path(smoke_report).is_file():
        failures.append(f"{smoke_prefix}-missing")
        return
    props = properties_for(smoke_report)
    if props.get("status") != "passed":
        failures.append(f"{smoke_prefix}-status-not-passed")
    if props.get("target") != "crash-anr-smoke-evidence":
        failures.append(f"{smoke_prefix}-target-invalid")
    if props.get("operationsRecordField") != "crashAnrSmoke.evidence":
        failures.append(f"{smoke_prefix}-operations-field-invalid")
    if props.get("logcatAnalyzed") != "true":
        failures.append(f"{smoke_prefix}-logcat-not-analyzed")
    if props.get("deviceStatus") != "passed":
        failures.append(f"{smoke_prefix}-device-status-not-passed")
    if props.get("instrumentationStatus") != "passed":
        failures.append(f"{smoke_prefix}-instrumentation-status-not-passed")
    for field in (
        "noLaunchCrash",
        "noInstallCrash",
        "noCrashLoop",
        "noFatalNativeLiteRtLmFailure",
        "noReproducibleAnr",
    ):
        if props.get(field) != "true":
            failures.append(f"{smoke_prefix}-{field}-not-true")
    for field in (
        "instrumentationCrashSignalCount",
        "instrumentationFailureSignalCount",
        "crashSignalCount",
        "installCrashSignalCount",
        "anrSignalCount",
        "fatalLiteRtLmSignalCount",
    ):
        if props.get(field) != "0":
            failures.append(f"{smoke_prefix}-{field}-nonzero")
    if non_empty_string(expected_device_report) and props.get("deviceReportFile") != expected_device_report:
        failures.append(f"{smoke_prefix}-device-report-mismatch")
    if non_empty_string(expected_instrumentation_output) and props.get("instrumentationOutputFile") != expected_instrumentation_output:
        failures.append(f"{smoke_prefix}-instrumentation-output-mismatch")
    if non_empty_string(expected_logcat_file) and props.get("logcatFile") != expected_logcat_file:
        failures.append(f"{smoke_prefix}-logcat-file-mismatch")

    device_report = props.get("deviceReportFile", "")
    if non_empty_string(device_report):
        if not Path(device_report).is_file():
            failures.append(f"{smoke_prefix}-device-report-missing")
        elif not non_empty_string(props.get("deviceReportSha256", "")):
            failures.append(f"{smoke_prefix}-device-report-sha-missing")
        else:
            validate_file_sha(f"{smoke_prefix}-device-report", device_report, props.get("deviceReportSha256", ""))
    instrumentation_output = props.get("instrumentationOutputFile", "")
    if non_empty_string(instrumentation_output):
        if not Path(instrumentation_output).is_file():
            failures.append(f"{smoke_prefix}-instrumentation-output-missing")
        elif not non_empty_string(props.get("instrumentationOutputSha256", "")):
            failures.append(f"{smoke_prefix}-instrumentation-output-sha-missing")
        else:
            validate_file_sha(f"{smoke_prefix}-instrumentation-output", instrumentation_output, props.get("instrumentationOutputSha256", ""))
    logcat_file = props.get("logcatFile", "")
    validate_logcat_artifact(smoke_prefix, logcat_file)
    if non_empty_string(logcat_file) and Path(logcat_file).is_file():
        if not non_empty_string(props.get("logcatSha256", "")):
            failures.append(f"{smoke_prefix}-logcat-sha-missing")
        else:
            validate_file_sha(f"{smoke_prefix}-logcat", logcat_file, props.get("logcatSha256", ""))

def validate_nested_emulator_report(prefix, regression_props, expected_api_level, expected_avd, expected_abi):
    emulator_report = regression_props.get("emulator_report_file", "")
    if not non_empty_string(emulator_report):
        failures.append(f"{prefix}-emulator-report-path-missing")
        return
    if not Path(emulator_report).is_file():
        failures.append(f"{prefix}-emulator-report-missing")
        return
    props = properties_for(emulator_report)
    if props.get("status") != "passed":
        failures.append(f"{prefix}-emulator-report-status-not-passed")
    if props.get("exit_code") != "0":
        failures.append(f"{prefix}-emulator-report-exit-code-invalid")
    if props.get("target") != "emulator":
        failures.append(f"{prefix}-emulator-report-target-invalid")
    if props.get("failedTarget", ""):
        failures.append(f"{prefix}-emulator-report-failed-target-not-empty")
    if props.get("reason", ""):
        failures.append(f"{prefix}-emulator-report-reason-not-empty")
    if props.get("clean_device") != "1":
        failures.append(f"{prefix}-emulator-report-clean-device-not-true")
    if props.get("serial") != regression_props.get("serial", ""):
        failures.append(f"{prefix}-emulator-report-serial-mismatch")
    if props.get("api_level") != str(expected_api_level):
        failures.append(f"{prefix}-emulator-report-api-mismatch")
    report_abis = {item.strip() for item in props.get("abi", "").split(",") if item.strip()}
    if expected_abi not in report_abis:
        failures.append(f"{prefix}-emulator-report-abi-mismatch")
    if props.get("avd") != expected_avd:
        failures.append(f"{prefix}-emulator-report-avd-mismatch")
    if props.get("device_report_file") != regression_props.get("device_report_file", ""):
        failures.append(f"{prefix}-emulator-report-device-report-mismatch")
    if not non_empty_string(props.get("started_at_utc", "")):
        failures.append(f"{prefix}-emulator-report-started-at-missing")
    if not non_empty_string(props.get("finished_at_utc", "")):
        failures.append(f"{prefix}-emulator-report-finished-at-missing")
    validate_logcat_artifact(f"{prefix}-emulator-report", props.get("logcat_file", ""))
    validate_crash_anr_smoke_report(
        f"{prefix}-emulator-report",
        props.get("crash_anr_smoke_report_file", ""),
        expected_device_report=props.get("device_report_file", ""),
        expected_instrumentation_output=regression_props.get("instrumentation_output_file", ""),
        expected_logcat_file=props.get("logcat_file", ""),
    )

def validate_api_nested_emulator_report(api_level, regression_props):
    prefix = f"api-{api_level}-emulator-report"
    emulator_report = regression_props.get("emulator_report_file", "")
    if not non_empty_string(emulator_report):
        failures.append(f"{prefix}-path-missing")
        return
    if not Path(emulator_report).is_file():
        failures.append(f"{prefix}-missing")
        return
    props = properties_for(emulator_report)
    if props.get("status") != "passed":
        failures.append(f"{prefix}-status-not-passed")
    if props.get("exit_code") != "0":
        failures.append(f"{prefix}-exit-code-invalid")
    if props.get("target") != "emulator":
        failures.append(f"{prefix}-target-invalid")
    if props.get("failedTarget", ""):
        failures.append(f"{prefix}-failed-target-not-empty")
    if props.get("reason", ""):
        failures.append(f"{prefix}-reason-not-empty")
    if props.get("clean_device") != "1":
        failures.append(f"{prefix}-clean-device-not-true")
    if props.get("serial") != regression_props.get("serial", ""):
        failures.append(f"{prefix}-serial-mismatch")
    if props.get("api_level") != str(api_level):
        failures.append(f"{prefix}-api-mismatch")
    report_abis = {item.strip() for item in props.get("abi", "").split(",") if item.strip()}
    if "arm64-v8a" not in report_abis:
        failures.append(f"{prefix}-abi-mismatch")
    if props.get("avd") != regression_props.get("avd", ""):
        failures.append(f"{prefix}-avd-mismatch")
    if props.get("device_report_file") != regression_props.get("device_report_file", ""):
        failures.append(f"{prefix}-device-report-mismatch")
    if not non_empty_string(props.get("started_at_utc", "")):
        failures.append(f"{prefix}-started-at-missing")
    if not non_empty_string(props.get("finished_at_utc", "")):
        failures.append(f"{prefix}-finished-at-missing")
    validate_logcat_artifact(prefix, props.get("logcat_file", ""))
    validate_crash_anr_smoke_report(
        prefix,
        props.get("crash_anr_smoke_report_file", ""),
        expected_device_report=props.get("device_report_file", ""),
        expected_instrumentation_output=regression_props.get("instrumentation_output_file", ""),
        expected_logcat_file=props.get("logcat_file", ""),
    )

def validate_api_nested_device_report(api_level, regression_props):
    prefix = f"api-{api_level}-device-report"
    device_report = regression_props.get("device_report_file", "")
    if not non_empty_string(device_report):
        failures.append(f"{prefix}-path-missing")
        return
    if not Path(device_report).is_file():
        failures.append(f"{prefix}-missing")
        return
    props = properties_for(device_report)
    if props.get("status") != "passed":
        failures.append(f"{prefix}-status-not-passed")
    if props.get("exit_code") != "0":
        failures.append(f"{prefix}-exit-code-invalid")
    if props.get("target") != "device":
        failures.append(f"{prefix}-target-invalid")
    if props.get("failedTarget", ""):
        failures.append(f"{prefix}-failed-target-not-empty")
    if props.get("reason", ""):
        failures.append(f"{prefix}-reason-not-empty")
    if props.get("serial") != regression_props.get("serial", ""):
        failures.append(f"{prefix}-serial-mismatch")
    if props.get("api_level") != str(api_level):
        failures.append(f"{prefix}-api-mismatch")
    report_abis = {item.strip() for item in props.get("abi", "").split(",") if item.strip()}
    if "arm64-v8a" not in report_abis:
        failures.append(f"{prefix}-abi-mismatch")
    if props.get("clean_device") != "1":
        failures.append(f"{prefix}-clean-device-not-true")
    if props.get("instrumentation") != "passed":
        failures.append(f"{prefix}-instrumentation-not-passed")
    if props.get("instrumentation_output_file") != regression_props.get("instrumentation_output_file", ""):
        failures.append(f"{prefix}-instrumentation-output-mismatch")
    instrumentation_output_count = validate_instrumentation_output(prefix, props.get("instrumentation_output_file", ""))
    try:
        device_count = int(props.get("instrumentation_test_count", ""))
    except ValueError:
        failures.append(f"{prefix}-test-count-invalid")
    else:
        try:
            regression_count = int(regression_props.get("actual_android_test_count", ""))
        except ValueError:
            regression_count = None
        if device_count < source_android_test_count:
            failures.append(f"{prefix}-test-count-too-low")
        if regression_count is not None and device_count != regression_count:
            failures.append(f"{prefix}-test-count-mismatch")
        if instrumentation_output_count is not None and instrumentation_output_count != device_count:
            failures.append(f"{prefix}-instrumentation-output-count-mismatch")
    if props.get("debug_apk") != "app/build/outputs/apk/debug/app-debug.apk":
        failures.append(f"{prefix}-debug-apk-invalid")
    if props.get("android_test_apk") != "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk":
        failures.append(f"{prefix}-android-test-apk-invalid")
    if not non_empty_string(props.get("started_at_utc", "")):
        failures.append(f"{prefix}-started-at-missing")
    if not non_empty_string(props.get("finished_at_utc", "")):
        failures.append(f"{prefix}-finished-at-missing")

def validate_performance_evidence(key, evidence_path):
    prefix = f"performance-{key}-evidence"
    props = properties_for(evidence_path)
    if props.get("status") != "passed":
        failures.append(f"{prefix}-status-not-passed")
    if props.get("target") != "perf-baseline":
        failures.append(f"{prefix}-target-invalid")
    if props.get("performanceKey") != key:
        failures.append(f"{prefix}-key-mismatch")
    if props.get("missingFieldCount") != "0":
        failures.append(f"{prefix}-missing-fields")
    expected_sha = props.get("expectedArtifactSha256", "")
    if not re.match(r"^[0-9a-fA-F]{64}$", expected_sha):
        failures.append(f"{prefix}-expected-artifact-sha-invalid")
    elif expected_release_artifact_sha and expected_sha.lower() != expected_release_artifact_sha.lower():
        failures.append(f"{prefix}-expected-artifact-sha-mismatch")
    expected_app_version = props.get("expectedAppVersion", "")
    if not non_empty_string(expected_app_version):
        failures.append(f"{prefix}-expected-app-version-missing")
    try:
        max_record_age_days = int(props.get("maxRecordAgeDays", ""))
    except ValueError:
        max_record_age_days = None
        failures.append(f"{prefix}-max-record-age-invalid")
    else:
        if max_record_age_days <= 0:
            failures.append(f"{prefix}-max-record-age-invalid")
    baseline_file = props.get("baselineFile", "")
    if not non_empty_string(baseline_file):
        failures.append(f"{prefix}-baseline-file-missing")
    elif not Path(baseline_file).is_file():
        failures.append(f"{prefix}-baseline-file-read-failed")
    else:
        expected_baseline_sha = props.get("baselineSha256", "")
        if not re.match(r"^[0-9a-fA-F]{64}$", expected_baseline_sha):
            failures.append(f"{prefix}-baseline-sha-invalid")
        else:
            validate_file_sha(f"{prefix}-baseline", baseline_file, expected_baseline_sha)
        baseline_props = properties_for(baseline_file)
        if baseline_props.get("status") != "passed":
            failures.append(f"{prefix}-baseline-status-not-passed")
        if baseline_props.get("releaseArtifactSha256") != expected_sha:
            failures.append(f"{prefix}-baseline-artifact-sha-mismatch")
        if baseline_props.get("appVersion") != expected_app_version:
            failures.append(f"{prefix}-baseline-app-version-mismatch")
        if baseline_props.get("deviceSerial", "").startswith("emulator-") or not non_empty_string(baseline_props.get("deviceSerial", "")):
            failures.append(f"{prefix}-baseline-device-serial-invalid")
        if baseline_props.get("abi") != "arm64-v8a":
            failures.append(f"{prefix}-baseline-abi-invalid")
        if baseline_props.get("oomOrAnrObserved") != "false":
            failures.append(f"{prefix}-baseline-oom-or-anr-observed")
        if max_record_age_days is not None and max_record_age_days > 0:
            validate_utc_timestamp_fresh(baseline_props.get("recordedAt", ""), max_record_age_days, prefix)

def validate_manual_evidence(key, evidence_path):
    prefix = f"manual-{key}-evidence"
    props = properties_for(evidence_path)
    validate_release_artifact_binding(prefix, props)
    if props.get("status") != "passed":
        failures.append(f"{prefix}-status-not-passed")
    if props.get("target") != "manual-acceptance":
        failures.append(f"{prefix}-target-invalid")
    if props.get("manualKey") != key:
        failures.append(f"{prefix}-key-mismatch")
    if props.get("manualAcceptance", "").lower() not in {"true", "1", "yes"}:
        failures.append(f"{prefix}-manual-acceptance-not-true")

def validate_flow_evidence(key, evidence_path):
    prefix = f"flow-{key}"
    evidence_prefix = f"{prefix}-evidence"
    props = properties_for(evidence_path)
    validate_release_artifact_binding(evidence_prefix, props)
    if props.get("status") != "passed":
        failures.append(f"{evidence_prefix}-status-not-passed")
    if props.get("target") != "release-flow":
        failures.append(f"{evidence_prefix}-target-invalid")
    if props.get("flowKey") != key:
        failures.append(f"{evidence_prefix}-key-mismatch")
    if props.get("candidateOnly", "").lower() in {"true", "1", "yes"}:
        failures.append(f"{prefix}-candidate-evidence-not-approved")
    if props.get("releaseFlowPassed", "").lower() not in {"true", "1", "yes"}:
        failures.append(f"{prefix}-release-flow-not-passed")
    required_true_fields = {
        "firstInstall": {
            "firstRunSetupVisibleCovered": "first-run-setup-visibility-missing",
            "firstRunDefaultChatModelSelected": "first-run-default-chat-model-missing",
            "firstRunSkipReachesMainShell": "first-run-skip-main-shell-missing",
        },
        "localModelDownloadVerification": {
            "localModelDownloadVerified": "model-download-verification-missing",
            "modelSha256VerificationCovered": "model-sha-verification-missing",
            "storagePreflightCovered": "storage-preflight-missing",
            "downloadFailureRecoveryCovered": "download-failure-recovery-missing",
            "downloadDirectoryUnavailableCovered": "download-directory-unavailable-missing",
            "downloadShaFailureCleanupCovered": "download-sha-failure-cleanup-missing",
            "downloadInsufficientStorageFailureCovered": "download-insufficient-storage-failure-missing",
            "pendingDownloadMissingTaskRecoveryCovered": "pending-download-missing-task-recovery-missing",
            "remoteFallbackExplained": "remote-fallback-explanation-missing",
            "lightweightAlternativeExplained": "lightweight-alternative-explanation-missing",
        },
        "customModelImportOrUrlRejection": {
            "customLitertlmImportCovered": "custom-import-missing",
            "customLocalNonLitertlmImportRejected": "custom-local-non-litertlm-import-rejection-missing",
            "customImportStoragePreflightCovered": "custom-import-storage-preflight-missing",
            "customImportEmptyFileRejected": "custom-import-empty-file-rejection-missing",
            "customImportTempCleanupOnCopyFailureCovered": "custom-import-temp-cleanup-on-copy-failure-missing",
            "customDownloadHttpsOnly": "custom-https-only-missing",
            "customNonLitertlmDownloadRejected": "custom-non-litertlm-rejection-missing",
            "customInvalidUrlRejected": "invalid-url-rejection-missing",
            "customCredentialedUrlRejected": "credentialed-url-rejection-missing",
            "customUnverifiedModelMarked": "custom-unverified-marker-missing",
        },
        "remoteHttpsConfiguration": {
            "remoteNetworkFailureRecoveryCovered": "remote-network-failure-recovery-missing",
            "remoteUnconfiguredModelFailureCovered": "remote-unconfigured-model-failure-missing",
            "remoteLocalMemoryNotAutoIncluded": "remote-local-memory-boundary-missing",
        },
        "shareAndPickerInput": {
            "actionSendTextStaged": "action-send-text-staging-missing",
            "remoteTextShareProtected": "remote-text-share-protection-missing",
            "remoteVisionImageAttachmentStaged": "remote-vision-image-staging-missing",
            "remoteVisionUnsupportedProtected": "remote-vision-unsupported-protection-missing",
            "noImplicitImageOcr": "no-implicit-image-ocr-missing",
            "remoteNonImageAttachmentNotAutoIncluded": "remote-non-image-attachment-protection-missing",
            "remoteVisionSupportedOpenStreamCountCovered": "remote-vision-supported-open-stream-count-missing",
            "remoteVisionSupportedOcrSkipped": "remote-vision-supported-ocr-skip-missing",
            "remoteVisionUnsupportedOpenStreamCountCovered": "remote-vision-unsupported-open-stream-count-missing",
            "remoteVisionUnsupportedOcrSkipped": "remote-vision-unsupported-ocr-skip-missing",
            "remoteVisionMixedShareNonImageProtected": "remote-vision-mixed-share-non-image-protection-missing",
            "remoteVisionHttpFixtureStreamRequested": "remote-vision-http-fixture-stream-request-missing",
            "documentExcerptBounded": "document-excerpt-boundary-missing",
            "pickerAttachmentPromptCovered": "picker-attachment-prompt-missing",
        },
        "voiceInput": {
            "voiceEntryDisclosureVisible": "entry-disclosure-missing",
            "voiceDraftNoAutoSendCovered": "draft-no-auto-send-missing",
            "voicePermissionFailureRecoveryCovered": "permission-failure-recovery-missing",
            "voiceCancelCovered": "cancel-path-missing",
        },
        "privacyAndDataControls": {
            "privacyNoticeEntryVisible": "privacy-notice-entry-missing",
            "memoryClearControlCovered": "memory-clear-control-missing",
            "memoryForgetControlCovered": "memory-forget-control-missing",
            "sessionDeleteControlCovered": "session-delete-control-missing",
            "remoteConfigClearCovered": "remote-config-clear-control-missing",
            "dataDeletionCopyCovered": "data-deletion-copy-missing",
        },
        "adaptiveUi": {
            "largeFontReachabilityCovered": "large-font-reachability-missing",
            "landscapeReachabilityCovered": "landscape-reachability-missing",
            "accessibleLabelsCovered": "accessible-labels-missing",
        },
    }.get(key, {})
    for field, reason in required_true_fields.items():
        if props.get(field, "").lower() not in {"true", "1", "yes"}:
            failures.append(f"{prefix}-{reason}")
    required_exact_fields = {
        "shareAndPickerInput": {
            "remoteVisionHttpFixtureImagePartCount": ("1", "remote-vision-http-fixture-image-part-count-mismatch"),
            "remoteVisionSupportedImageStreamOpenCount": ("1", "remote-vision-supported-image-stream-count-mismatch"),
            "remoteVisionSupportedImageOcrInvocationCount": ("0", "remote-vision-supported-image-ocr-count-mismatch"),
            "remoteVisionUnsupportedImageStreamOpenCount": ("0", "remote-vision-unsupported-image-stream-count-mismatch"),
            "remoteVisionUnsupportedImageOcrInvocationCount": ("0", "remote-vision-unsupported-image-ocr-count-mismatch"),
            "remoteVisionMixedProtectedNonImageCount": ("1", "remote-vision-mixed-protected-non-image-count-mismatch"),
        },
    }.get(key, {})
    for field, (expected, reason) in required_exact_fields.items():
        if props.get(field) != expected:
            failures.append(f"{prefix}-{reason}")

def validate_png_file(name, path):
    try:
        signature = Path(path).read_bytes()[:8]
    except OSError:
        failures.append(f"{name}-screenshot-read-failed")
        return
    if signature != b"\x89PNG\r\n\x1a\n":
        failures.append(f"{name}-screenshot-not-png")

def slug(value):
    slugged = re.sub(r"[^0-9A-Za-z]+", "-", value).strip("-").lower()
    return slugged or "non-ascii-text"

def validate_screenshot_report(name, entry, path):
    report_path = entry.get("reportPath", "")
    if not non_empty_string(report_path):
        failures.append(f"{name}-screenshot-report-path-missing")
        return
    if not Path(report_path).is_file():
        failures.append(f"{name}-screenshot-report-missing")
        return
    validate_file_sha(f"{name}-screenshot-report", report_path, entry.get("reportSha256", ""))
    props = properties_for(report_path)
    validate_release_artifact_binding(f"{name}-screenshot-report", props)
    if props.get("status") != "passed":
        failures.append(f"{name}-screenshot-report-status-not-passed")
    if props.get("target") != "release-screenshots":
        failures.append(f"{name}-screenshot-report-target-invalid")
    if props.get("clean_device") != "1":
        failures.append(f"{name}-screenshot-report-clean-device-not-true")
    if props.get(f"screenshot.{name}.path") != path:
        failures.append(f"{name}-screenshot-report-path-mismatch")
    if props.get(f"screenshot.{name}.sha256") != entry.get("sha256", ""):
        failures.append(f"{name}-screenshot-report-sha-mismatch")
    if props.get(f"screenshot.{name}.sanitized", "").lower() not in {"true", "1", "yes"}:
        failures.append(f"{name}-screenshot-report-not-sanitized")
    if props.get(f"screenshot.{name}.visualRegression") != "passed":
        failures.append(f"{name}-screenshot-visual-regression-not-passed")
    expected_text = "|".join(required_screenshot_texts(name))
    if props.get(f"screenshot.{name}.requiredText") != expected_text:
        failures.append(f"{name}-screenshot-required-text-contract-mismatch")
    ui_dump = props.get(f"screenshot.{name}.uiDump", "")
    if not non_empty_string(ui_dump):
        failures.append(f"{name}-screenshot-ui-dump-missing")
    elif not Path(ui_dump).is_file():
        failures.append(f"{name}-screenshot-ui-dump-file-missing")
    else:
        validate_file_sha(f"{name}-screenshot-ui-dump", ui_dump, props.get(f"screenshot.{name}.uiDumpSha256", ""))
        validate_screenshot_ui_contract(name, ui_dump)

def required_screenshot_texts(name):
    return {
        "chat-home": ["PocketMind", "隐私优先的随身 AI 助手", "为什么装它", "模型管理"],
        "model-manager": ["模型管理", "当前模型", "本地可用", "远程多模态可选"],
        "confirmation-sheet": ["即将发送到远程模型", "确认后才会", "取消"],
        "background-tasks-or-audit": ["后台任务", "最近审计日志", "最近 Agent 轨迹", "暂无运行中的后台任务"],
    }.get(name, [])

def validate_screenshot_ui_contract(name, ui_dump):
    expected = required_screenshot_texts(name)
    if not expected:
        failures.append(f"{name}-screenshot-required-text-contract-missing")
        return
    try:
        raw = Path(ui_dump).read_text(errors="ignore")
        start = raw.find("<")
        if start > 0:
            raw = raw[start:]
        import xml.etree.ElementTree as ET
        root = ET.fromstring(raw)
    except Exception:
        failures.append(f"{name}-screenshot-ui-dump-parse-failed")
        return
    surface = "\n".join(
        " ".join(
            value
            for value in (
                node.attrib.get("text", ""),
                node.attrib.get("content-desc", ""),
                node.attrib.get("resource-id", ""),
            )
            if value
        )
        for node in root.iter("node")
    )
    for text in expected:
        if text not in surface:
            failures.append(f"{name}-screenshot-required-text-missing-{slug(text)}")

def validate_instrumentation_output(prefix, output_file):
    if not non_empty_string(output_file):
        failures.append(f"{prefix}-instrumentation-output-file-missing")
        return None
    output_path = Path(output_file)
    if not output_path.is_file():
        failures.append(f"{prefix}-instrumentation-output-file-missing")
        return None
    try:
        output = output_path.read_text(errors="ignore")
    except OSError:
        failures.append(f"{prefix}-instrumentation-output-read-failed")
        return None
    if not output.strip():
        failures.append(f"{prefix}-instrumentation-output-empty")
    ok_matches = re.findall(r"^OK(?: \(([0-9]+) tests?\))?$", output, re.MULTILINE)
    if not ok_matches:
        if re.search(r"^(FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2|INSTRUMENTATION_RESULT: shortMsg=|INSTRUMENTATION_STATUS: stack=|Error in )", output, re.MULTILINE):
            failures.append(f"{prefix}-instrumentation-output-failure-marker")
        failures.append(f"{prefix}-instrumentation-output-success-marker-missing")
        return None
    count = ok_matches[-1]
    if not count:
        failures.append(f"{prefix}-instrumentation-output-count-missing")
        return None
    try:
        return int(count)
    except ValueError:
        failures.append(f"{prefix}-instrumentation-output-count-invalid")
        return None

def count_android_tests():
    count = 0
    if not android_test_source_dir.is_dir():
        return 0
    pattern = re.compile(r"^\s*@(org[.]junit[.])?Test(\s*[(]|[\s]|$)")
    for path in android_test_source_dir.rglob("*"):
        if path.suffix not in {".kt", ".java"}:
            continue
        try:
            for line in path.read_text(errors="ignore").splitlines():
                if pattern.search(line):
                    count += 1
        except OSError:
            pass
    return count

failures = []
if record.get("version") != 1:
    failures.append("version-invalid")
if record.get("status") != "approved":
    failures.append("status-not-approved")

source_android_test_count = count_android_tests()
if source_android_test_count <= 0:
    failures.append("android-test-source-count-invalid")

emulator = record.get("emulatorRegression")
if not isinstance(emulator, dict):
    failures.append("emulator-regression-missing")
    emulator = {}
if emulator.get("status") != "passed":
    failures.append("emulator-regression-not-passed")
emulator_report = emulator.get("reportPath", "")
if not emulator_report:
    failures.append("emulator-report-path-missing")
elif not Path(emulator_report).is_file():
    failures.append("emulator-report-missing")
else:
    validate_file_sha("emulator-report", emulator_report, emulator.get("reportSha256", ""))
    props = properties_for(emulator_report)
    validate_release_artifact_binding("emulator-report", props)
    if props.get("status") != "passed":
        failures.append("emulator-report-status-not-passed")
    if props.get("target") != "regression-emulator":
        failures.append("emulator-report-target-invalid")
    if props.get("clean_device") != "1":
        failures.append("emulator-report-clean-device-not-true")
    if props.get("avd") != emulator.get("avd"):
        failures.append("emulator-report-avd-mismatch")
    if props.get("api_level") != str(emulator.get("apiLevel")):
        failures.append("emulator-report-api-mismatch")
    if props.get("abi") != emulator.get("abi"):
        failures.append("emulator-report-abi-mismatch")
    try:
        actual_count = int(props.get("actual_android_test_count", ""))
    except ValueError:
        failures.append("emulator-report-test-count-invalid")
    else:
        if actual_count < source_android_test_count:
            failures.append("emulator-report-test-count-too-low")
    validate_nested_emulator_report(
        "emulator-regression",
        props,
        emulator.get("apiLevel"),
        emulator.get("avd"),
        emulator.get("abi"),
    )

physical = record.get("physicalDevice")
if not isinstance(physical, dict):
    failures.append("physical-device-missing")
    physical = {}
if physical.get("status") != "passed":
    failures.append("physical-device-not-passed")
physical_serial = physical.get("serial", "")
if not non_empty_string(physical_serial) or physical_serial.startswith("emulator-"):
    failures.append("physical-device-serial-invalid")
device_report = physical.get("reportPath", "")
if not device_report:
    failures.append("physical-device-report-path-missing")
elif not Path(device_report).is_file():
    failures.append("physical-device-report-missing")
else:
    validate_file_sha("physical-device-report", device_report, physical.get("reportSha256", ""))
    props = properties_for(device_report)
    validate_release_artifact_binding("physical-device-report", props)
    report_serial = props.get("serial", "")
    if props.get("status") != "passed":
        failures.append("physical-device-report-status-not-passed")
    if props.get("exit_code") != "0":
        failures.append("physical-device-report-exit-code-invalid")
    if props.get("target") != "device":
        failures.append("physical-device-report-target-invalid")
    if props.get("failedTarget", ""):
        failures.append("physical-device-report-failed-target-not-empty")
    if props.get("reason", ""):
        failures.append("physical-device-report-reason-not-empty")
    if not non_empty_string(props.get("started_at_utc", "")):
        failures.append("physical-device-report-started-at-missing")
    if not non_empty_string(props.get("finished_at_utc", "")):
        failures.append("physical-device-report-finished-at-missing")
    if report_serial.startswith("emulator-"):
        failures.append("physical-device-report-serial-is-emulator")
    if report_serial != physical_serial:
        failures.append("physical-device-report-serial-mismatch")
    if props.get("api_level") != str(physical.get("apiLevel")):
        failures.append("physical-device-report-api-mismatch")
    report_abi = props.get("abi", "")
    expected_abi = physical.get("abi", "")
    report_abis = {item.strip() for item in report_abi.split(",") if item.strip()}
    if "arm64-v8a" not in report_abis:
        failures.append("physical-device-report-abi-not-arm64")
    if non_empty_string(expected_abi) and expected_abi not in report_abis:
        failures.append("physical-device-report-abi-mismatch")
    expected_clean = "1" if physical.get("cleanDevice") is True else "0"
    if props.get("clean_device") != expected_clean:
        failures.append("physical-device-report-clean-device-mismatch")
    try:
        data_free_kb = int(props.get("data_free_kb", ""))
    except ValueError:
        failures.append("physical-device-report-data-free-invalid")
    else:
        if data_free_kb < 3 * 1024 * 1024:
            failures.append("physical-device-report-data-free-too-low")
    if props.get("instrumentation") != "passed":
        failures.append("physical-device-report-instrumentation-not-passed")
    instrumentation_output_count = validate_instrumentation_output("physical-device-report", props.get("instrumentation_output_file", ""))
    try:
        actual_count = int(props.get("instrumentation_test_count", ""))
    except ValueError:
        failures.append("physical-device-report-test-count-invalid")
    else:
        if actual_count < source_android_test_count:
            failures.append("physical-device-report-test-count-too-low")
        if instrumentation_output_count is not None and instrumentation_output_count != actual_count:
            failures.append("physical-device-report-instrumentation-output-count-mismatch")
    if props.get("debug_apk") != "app/build/outputs/apk/debug/app-debug.apk":
        failures.append("physical-device-report-debug-apk-invalid")
    if props.get("android_test_apk") != "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk":
        failures.append("physical-device-report-android-test-apk-invalid")

api_matrix = record.get("apiMatrix")
required_apis = {28, 32, 33, 34, 36}
seen_apis = set()
if not isinstance(api_matrix, list):
    failures.append("api-matrix-missing")
    api_matrix = []
for entry in api_matrix:
    if not isinstance(entry, dict):
        failures.append("api-matrix-entry-invalid")
        continue
    api_level = entry.get("apiLevel")
    seen_apis.add(api_level)
    if entry.get("status") != "passed":
        failures.append(f"api-{api_level}-not-passed")
    if not non_empty_string(entry.get("evidence")):
        failures.append(f"api-{api_level}-evidence-missing")
    evidence_path = entry.get("evidencePath", "")
    if not non_empty_string(evidence_path):
        failures.append(f"api-{api_level}-evidence-path-missing")
    elif not Path(evidence_path).is_file():
        failures.append(f"api-{api_level}-evidence-file-missing")
    else:
        validate_file_sha(f"api-{api_level}-evidence", evidence_path, entry.get("evidenceSha256", ""))
        validate_api_matrix_evidence(api_level, evidence_path)
for missing in sorted(required_apis - seen_apis):
    failures.append(f"api-{missing}-missing")

required_manual = {
    "modelSetup",
    "remoteModePrivacy",
    "toolConfirmation",
    "permissions",
    "backgroundReminders",
    "sharing",
    "multimodalEntryPoints",
    "voiceInput",
    "filePicker",
    "mediaProjection",
    "remoteSinglePublicEvidence",
    "remoteMultiEvidenceComparison",
    "mixedPrivateActionBatchFailClosed",
}
manual = record.get("manualAcceptance")
if not isinstance(manual, dict):
    failures.append("manual-acceptance-missing")
    manual = {}
for key in sorted(required_manual):
    validate_evidence_record("manual", key, manual.get(key))

required_flows = {
    "firstInstall",
    "upgradeInstall",
    "localModelDownloadVerification",
    "customModelImportOrUrlRejection",
    "remoteHttpsConfiguration",
    "encryptedApiKeyClear",
    "sessionPersistence",
    "memoryControls",
    "privacyAndDataControls",
    "remindersAfterReboot",
    "shareAndPickerInput",
    "voiceInput",
    "adaptiveUi",
    "accessibilityText",
    "recentMediaOcr",
    "mediaProjectionCancellation",
}
flows = record.get("flowMatrix")
if not isinstance(flows, dict):
    failures.append("flow-matrix-missing")
    flows = {}
for key in sorted(required_flows):
    validate_evidence_record("flow", key, flows.get(key))

screenshots = record.get("screenshots")
required_screenshots = {"chat-home", "model-manager", "confirmation-sheet", "background-tasks-or-audit"}
seen_screenshots = set()
if not isinstance(screenshots, list):
    failures.append("screenshots-missing")
    screenshots = []
for entry in screenshots:
    if not isinstance(entry, dict):
        failures.append("screenshot-entry-invalid")
        continue
    name = entry.get("name", "")
    seen_screenshots.add(name)
    path = entry.get("path", "")
    if not name:
        failures.append("screenshot-name-missing")
    if not path:
        failures.append(f"{name or 'unknown'}-screenshot-path-missing")
    elif not Path(path).is_file():
        failures.append(f"{name or 'unknown'}-screenshot-missing")
    else:
        validate_file_sha(f"{name or 'unknown'}-screenshot", path, entry.get("sha256", ""))
        validate_png_file(name or "unknown", path)
        validate_screenshot_report(name or "unknown", entry, path)
    if entry.get("sanitized") is not True:
        failures.append(f"{name or 'unknown'}-screenshot-not-sanitized")
for missing in sorted(required_screenshots - seen_screenshots):
    failures.append(f"{missing}-screenshot-missing")

performance = record.get("performanceSanity")
required_performance = {
    "firstLaunch",
    "modelLoad",
    "firstToken",
    "streamingStopCancel",
    "backgroundReminderDelivery",
    "memoryPressure",
}
if not isinstance(performance, dict):
    failures.append("performance-sanity-missing")
    performance = {}
for key in sorted(required_performance):
    value = performance.get(key)
    validate_evidence_record("performance", key, value)
    if isinstance(value, dict):
        evidence_path = value.get("evidencePath", "")
        if non_empty_string(evidence_path) and Path(evidence_path).is_file():
            validate_performance_evidence(key, evidence_path)

review = record.get("review")
if not isinstance(review, dict):
    failures.append("review-missing")
    review = {}
if not non_empty_string(review.get("reviewer")):
    failures.append("reviewer-missing")
review_date = review.get("reviewDate", "")
validate_date_field(review_date, "review")

if failures:
    print(",".join(failures))
    sys.exit(1)

print("approved")
PY
status=$?
set -e

if [[ "$status" -ne 0 ]]; then
  reason="$(cat "$TMP_FAILURES")"
  write_report failed "${reason:-incomplete-release-validation-record}"
  echo "Release validation record is incomplete." >&2
  exit 1
fi

write_report passed approved
echo "Release validation record verification passed."
