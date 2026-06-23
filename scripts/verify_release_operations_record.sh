#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

OPERATIONS_RECORD_FILE="${OPERATIONS_RECORD_FILE:-docs/release_operations_record.json}"
EXPECTED_COMMIT_SHA="${EXPECTED_COMMIT_SHA:-}"
EXPECTED_RELEASE_ARTIFACT_TYPE="${EXPECTED_RELEASE_ARTIFACT_TYPE:-}"
EXPECTED_RELEASE_ARTIFACT_SHA256="${EXPECTED_RELEASE_ARTIFACT_SHA256:-}"
EXPECTED_RELEASE_MAPPING_SHA256="${EXPECTED_RELEASE_MAPPING_SHA256:-}"
EXPECTED_SIGNING_CERT_SHA256="${EXPECTED_SIGNING_CERT_SHA256:-}"
REPORT_FILE=""
ORIGINAL_ARGS=("$@")

sha256_or_empty() {
  local path="$1"
  if [[ -n "$path" && -f "$path" ]]; then
    shasum -a 256 "$path" | awk '{print $1}'
  fi
}

shell_command() {
  local quoted=()
  local arg
  quoted+=("$(printf '%q' "scripts/verify_release_operations_record.sh")")
  for arg in "${ORIGINAL_ARGS[@]}"; do
    quoted+=("$(printf '%q' "$arg")")
  done
  local IFS=' '
  printf '%s' "${quoted[*]}"
}

failed_target_for_reason() {
  case "$1" in
    unknown-argument|missing-*-argument)
      printf 'argument-parser'
      ;;
    missing-operations-record-file|json-parse-error)
      printf 'release-operations-record'
      ;;
    *)
      printf ''
      ;;
  esac
}

write_report() {
  local status="$1"
  local reason="$2"
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'status=%s\n' "$status"
      printf 'target=release-operations-record\n'
      printf 'artifactSchema=ReleaseOperationsVerification/v1\n'
      printf 'owner=release-engineering\n'
      printf 'recordedAt=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      printf 'command=%s\n' "$(shell_command)"
      printf 'reproduciblePath=%s\n' "$REPORT_FILE"
      printf 'failedTarget=%s\n' "$(failed_target_for_reason "$reason")"
      printf 'operationsRecordFile=%s\n' "$OPERATIONS_RECORD_FILE"
      printf 'operationsRecordSha256=%s\n' "$(sha256_or_empty "$OPERATIONS_RECORD_FILE")"
      printf 'expectedCommitSha=%s\n' "$EXPECTED_COMMIT_SHA"
      printf 'expectedReleaseArtifactType=%s\n' "$EXPECTED_RELEASE_ARTIFACT_TYPE"
      printf 'expectedReleaseArtifactSha256=%s\n' "$EXPECTED_RELEASE_ARTIFACT_SHA256"
      printf 'expectedReleaseMappingSha256=%s\n' "$EXPECTED_RELEASE_MAPPING_SHA256"
      printf 'expectedSigningCertSha256=%s\n' "$EXPECTED_SIGNING_CERT_SHA256"
      printf 'reason=%s\n' "$reason"
    } > "$REPORT_FILE"
  fi
}

fail_parse() {
  local reason="$1"
  local message="$2"
  write_report failed "$reason"
  echo "$message" >&2
  exit 2
}

REQUIRED_ARG_VALUE=""
require_value() {
  local option="$1"
  local value="${2:-}"
  if [[ -z "$value" || "$value" == --* ]]; then
    fail_parse "missing-${option#--}-argument" "Missing value for $option"
  fi
  REQUIRED_ARG_VALUE="$value"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file)
      require_value "$1" "${2:-}"
      OPERATIONS_RECORD_FILE="$REQUIRED_ARG_VALUE"
      shift 2
      ;;
    --report)
      require_value "$1" "${2:-}"
      REPORT_FILE="$REQUIRED_ARG_VALUE"
      shift 2
      ;;
    *)
      fail_parse unknown-argument "Unknown argument: $1"
      ;;
  esac
done

if [[ ! -f "$OPERATIONS_RECORD_FILE" ]]; then
  write_report failed missing-operations-record-file
  echo "Release operations record file is missing: $OPERATIONS_RECORD_FILE" >&2
  exit 1
fi

TMP_FAILURES="$(mktemp)"
trap 'rm -f "$TMP_FAILURES"' EXIT

set +e
python3 - "$OPERATIONS_RECORD_FILE" \
  "$EXPECTED_COMMIT_SHA" \
  "$EXPECTED_RELEASE_ARTIFACT_TYPE" \
  "$EXPECTED_RELEASE_ARTIFACT_SHA256" \
  "$EXPECTED_RELEASE_MAPPING_SHA256" \
  "$EXPECTED_SIGNING_CERT_SHA256" > "$TMP_FAILURES" <<'PY'
import hashlib
import json
import re
import subprocess
import sys
from datetime import date
from pathlib import Path

record_path = Path(sys.argv[1])
expected_commit_sha = sys.argv[2]
expected_release_artifact_type = sys.argv[3]
expected_release_artifact_sha = sys.argv[4]
expected_release_mapping_sha = sys.argv[5]
expected_signing_cert_sha = sys.argv[6].lower()

try:
    record = json.loads(record_path.read_text())
except Exception:
    print("json-parse-error")
    sys.exit(1)

def git_success(*args):
    try:
        subprocess.check_call(["git", *args], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except Exception:
        return False

def non_empty_string(value):
    return isinstance(value, str) and bool(value.strip())

def number_between(value, minimum, maximum):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and minimum <= value <= maximum

def validate_evidence_file(section, entry):
    if not isinstance(entry, dict):
        failures.append(f"{section}-evidence-missing")
        return None
    evidence_path = entry.get("path", "")
    expected_sha = entry.get("sha256", "")
    if not non_empty_string(evidence_path):
        failures.append(f"{section}-evidence-path-missing")
        return None
    path = Path(evidence_path)
    if not path.is_file():
        failures.append(f"{section}-evidence-file-missing")
        return None
    actual_sha = hashlib.sha256(path.read_bytes()).hexdigest()
    if not non_empty_string(expected_sha):
        failures.append(f"{section}-evidence-sha-missing")
    elif expected_sha != actual_sha:
        failures.append(f"{section}-evidence-sha-mismatch")
    return path

def kebab(value):
    spaced = re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", value)
    return re.sub(r"[^a-zA-Z0-9]+", "-", spaced).lower().strip("-")

def properties_for(path):
    props = {}
    if path is None:
        return props
    try:
        with Path(path).open() as handle:
            for raw_line in handle:
                line = raw_line.rstrip("\n")
                if "=" not in line:
                    continue
                key, value = line.split("=", 1)
                props[key] = value
    except OSError:
        pass
    return props

def csv_tokens(value):
    if not isinstance(value, str):
        return set()
    return {item.strip() for item in value.split(",") if item.strip()}

def number_property_matches(value, expected):
    if not non_empty_string(value):
        return False
    try:
        return float(value) == float(expected)
    except (TypeError, ValueError):
        return False

def is_sha256(value):
    return isinstance(value, str) and bool(re.fullmatch(r"[0-9a-f]{64}", value))

def is_utc_timestamp(value):
    return isinstance(value, str) and bool(re.fullmatch(r"20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", value))

def positive_int_string(value):
    return isinstance(value, str) and bool(re.fullmatch(r"[1-9][0-9]*", value))

def non_negative_int_string(value):
    return isinstance(value, str) and bool(re.fullmatch(r"0|[1-9][0-9]*", value))

def validate_property_artifact(section, props, path_key, sha_key, size_key):
    artifact_value = props.get(path_key, "")
    artifact_label = kebab(path_key)
    if not non_empty_string(artifact_value):
        failures.append(f"{section}-{artifact_label}-missing")
        return None
    artifact_path = Path(artifact_value)
    if not artifact_path.is_file():
        failures.append(f"{section}-{artifact_label}-file-missing")
        return None

    expected_sha = props.get(sha_key, "")
    if not is_sha256(expected_sha):
        failures.append(f"{section}-{kebab(sha_key)}-invalid")
    else:
        actual_sha = hashlib.sha256(artifact_path.read_bytes()).hexdigest()
        if expected_sha != actual_sha:
            failures.append(f"{section}-{kebab(sha_key)}-mismatch")

    expected_size = props.get(size_key, "")
    if not positive_int_string(expected_size):
        failures.append(f"{section}-{kebab(size_key)}-invalid")
    elif int(expected_size) != artifact_path.stat().st_size:
        failures.append(f"{section}-{kebab(size_key)}-mismatch")

    return artifact_path

def validate_zero_count(section, props, key):
    value = props.get(key, "")
    if not non_negative_int_string(value):
        failures.append(f"{section}-{kebab(key)}-invalid")
    elif value != "0":
        failures.append(f"{section}-{kebab(key)}-not-zero")

def validate_report_schema(section, props, path, expected_schema, expected_owner="release-engineering"):
    if props.get("artifactSchema") != expected_schema:
        failures.append(f"{section}-artifact-schema-invalid")
    if props.get("owner") != expected_owner:
        failures.append(f"{section}-owner-invalid")
    if not is_utc_timestamp(props.get("recordedAt", "")):
        failures.append(f"{section}-recorded-at-invalid")
    if not non_empty_string(props.get("command", "")):
        failures.append(f"{section}-command-missing")
    if path is not None and props.get("reproduciblePath") != str(path):
        failures.append(f"{section}-reproducible-path-invalid")

def validate_operations_record_binding(section, props):
    operations_record_file = props.get("operationsRecordFile", "")
    if not non_empty_string(operations_record_file):
        failures.append(f"{section}-operations-record-file-missing")
        return
    if Path(operations_record_file).resolve() != record_path.resolve():
        failures.append(f"{section}-operations-record-file-mismatch")

def validate_artifact_scan_report_binding(section, props):
    report_file = props.get("artifactScanReport", "")
    if not non_empty_string(report_file):
        failures.append(f"{section}-artifact-scan-report-missing")
        return {}
    report_path = Path(report_file)
    if not report_path.is_file():
        failures.append(f"{section}-artifact-scan-report-file-missing")
        return {}
    expected_sha = props.get("artifactScanReportSha256", "")
    if not is_sha256(expected_sha):
        failures.append(f"{section}-artifact-scan-report-sha-invalid")
    else:
        actual_sha = hashlib.sha256(report_path.read_bytes()).hexdigest()
        if expected_sha != actual_sha:
            failures.append(f"{section}-artifact-scan-report-sha-mismatch")
    scan_props = properties_for(report_path)
    if scan_props.get("status") != "passed":
        failures.append(f"{section}-artifact-scan-report-status-not-passed")
    if scan_props.get("target") != "android-artifact-scan":
        failures.append(f"{section}-artifact-scan-report-target-invalid")
    validate_report_schema(
        f"{section}-artifact-scan-report",
        scan_props,
        report_path,
        "AndroidArtifactScanReport/v1",
    )
    return scan_props

def validate_ci_evidence_record(section, entry, expected_target, expected_schema=""):
    if not isinstance(entry, dict):
        failures.append(f"ci-{section}-missing")
        return {}
    if entry.get("status") != "passed":
        failures.append(f"ci-{section}-not-passed")
    if not non_empty_string(entry.get("jobName")):
        failures.append(f"ci-{section}-job-name-missing")
    path = validate_evidence_file(f"ci-{section}", entry.get("evidence"))
    props = properties_for(path)
    if props.get("status") != "passed":
        failures.append(f"ci-{section}-evidence-status-not-passed")
    if props.get("target") != expected_target:
        failures.append(f"ci-{section}-evidence-target-invalid")
    if expected_schema:
        validate_report_schema(
            f"ci-{section}-evidence",
            props,
            path,
            expected_schema,
        )
    return props

def validate_ci_identity(section, props, expected_job="", required=False):
    checks = (
        ("workflow", ci.get("workflowName", ""), "workflow"),
        ("runId", ci.get("runId", ""), "run-id"),
        ("commitSha", ci_commit, "commit-sha"),
    )
    for prop_key, expected_value, label in checks:
        actual_value = props.get(prop_key, "")
        if required and not non_empty_string(actual_value):
            failures.append(f"ci-{section}-evidence-{label}-missing")
            continue
        if non_empty_string(actual_value) and non_empty_string(expected_value) and actual_value != expected_value:
            failures.append(f"ci-{section}-evidence-{label}-mismatch")
    actual_job = props.get("job", "")
    if required and not non_empty_string(actual_job):
        failures.append(f"ci-{section}-evidence-job-missing")
    elif non_empty_string(actual_job) and non_empty_string(expected_job) and actual_job != expected_job:
        failures.append(f"ci-{section}-evidence-job-mismatch")

def csv_values(value):
    if not isinstance(value, str) or not value:
        return []
    return [item.strip() for item in value.split(",") if item.strip()]

def validate_api_matrix_report(props):
    required_apis = ["28", "32", "33", "34", "36"]
    if csv_values(props.get("requiredApis", "")) != required_apis:
        failures.append("ci-api-matrix-required-apis-invalid")
    if csv_values(props.get("passedApis", "")) != required_apis:
        failures.append("ci-api-matrix-passed-apis-invalid")
    if csv_values(props.get("failedApis", "")):
        failures.append("ci-api-matrix-failed-apis-not-empty")
    readiness_report = props.get("readinessReportFile", "")
    if not non_empty_string(readiness_report):
        failures.append("ci-api-matrix-readiness-report-missing")
    elif not Path(readiness_report).is_file():
        failures.append("ci-api-matrix-readiness-report-file-missing")
    else:
        readiness_path = Path(readiness_report)
        readiness_sha = props.get("readinessReportSha256", "")
        if not is_sha256(readiness_sha):
            failures.append("ci-api-matrix-readiness-report-sha-invalid")
        else:
            actual_sha = hashlib.sha256(readiness_path.read_bytes()).hexdigest()
            if readiness_sha != actual_sha:
                failures.append("ci-api-matrix-readiness-report-sha-mismatch")
        readiness_props = properties_for(readiness_path)
        if readiness_props.get("status") != "passed":
            failures.append("ci-api-matrix-readiness-status-not-passed")
        if readiness_props.get("target") != "emulator-api-matrix-readiness":
            failures.append("ci-api-matrix-readiness-target-invalid")
        if csv_values(readiness_props.get("requiredApis", "")) != required_apis:
            failures.append("ci-api-matrix-readiness-required-apis-invalid")
        if csv_values(readiness_props.get("installedSystemImageApis", "")) != required_apis:
            failures.append("ci-api-matrix-readiness-installed-system-images-invalid")
        if csv_values(readiness_props.get("availableAvdApis", "")) != required_apis:
            failures.append("ci-api-matrix-readiness-available-avds-invalid")
        if csv_values(readiness_props.get("missingSystemImageApis", "")):
            failures.append("ci-api-matrix-readiness-missing-system-images-not-empty")
        if csv_values(readiness_props.get("missingAvdApis", "")):
            failures.append("ci-api-matrix-readiness-missing-avds-not-empty")
        if readiness_props.get("tag") != props.get("tag"):
            failures.append("ci-api-matrix-readiness-tag-mismatch")
        if readiness_props.get("abi") != props.get("abi"):
            failures.append("ci-api-matrix-readiness-abi-mismatch")
    for api in required_apis:
        prefix = f"api{api}"
        if props.get(f"{prefix}Status") != "passed":
            failures.append(f"ci-api-matrix-api-{api}-not-passed")
        report_file = props.get(f"{prefix}ReportFile", "")
        if not non_empty_string(report_file):
            failures.append(f"ci-api-matrix-api-{api}-report-file-missing")
            continue
        path = Path(report_file)
        if not path.is_file():
            failures.append(f"ci-api-matrix-api-{api}-report-file-not-found")
            continue
        expected_sha = props.get(f"{prefix}ReportSha256", "")
        if not is_sha256(expected_sha):
            failures.append(f"ci-api-matrix-api-{api}-report-sha-invalid")
        else:
            actual_sha = hashlib.sha256(path.read_bytes()).hexdigest()
            if expected_sha != actual_sha:
                failures.append(f"ci-api-matrix-api-{api}-report-sha-mismatch")
        child_props = properties_for(path)
        if child_props.get("status") != "passed":
            failures.append(f"ci-api-matrix-api-{api}-child-status-not-passed")
        if child_props.get("target") != "regression-emulator":
            failures.append(f"ci-api-matrix-api-{api}-child-target-invalid")
        if child_props.get("api_level") != api:
            failures.append(f"ci-api-matrix-api-{api}-child-api-mismatch")
        if child_props.get("clean_device") != "1":
            failures.append(f"ci-api-matrix-api-{api}-child-not-clean")
        if not positive_int_string(child_props.get("actual_android_test_count", "")):
            failures.append(f"ci-api-matrix-api-{api}-child-count-invalid")
        if not non_empty_string(child_props.get("device_report_file")):
            failures.append(f"ci-api-matrix-api-{api}-child-device-report-missing")
        if not non_empty_string(child_props.get("instrumentation_output_file")):
            failures.append(f"ci-api-matrix-api-{api}-child-instrumentation-output-missing")

failures = []
if record.get("version") != 1:
    failures.append("version-invalid")
if record.get("status") != "approved":
    failures.append("status-not-approved")

ci = record.get("ci")
if not isinstance(ci, dict):
    failures.append("ci-missing")
    ci = {}
for field in ("owner", "provider", "workflowName", "runId"):
    if not non_empty_string(ci.get(field)):
        failures.append(f"ci-{field}-missing")
ci_commit = ci.get("commitSha", "")
if not re.fullmatch(r"[0-9a-f]{40}", ci_commit):
    failures.append("ci-commit-sha-invalid")
elif not git_success("cat-file", "-e", f"{ci_commit}^{{commit}}"):
    failures.append("ci-commit-sha-missing")
elif expected_commit_sha and ci_commit != expected_commit_sha:
    failures.append("ci-commit-sha-mismatch")

local_ci_props = validate_ci_evidence_record(
    "local-verification",
    ci.get("localVerification"),
    "ci-local-verification",
)
validate_ci_identity("local-verification", local_ci_props, expected_job="verify", required=True)
if local_ci_props.get("command") != "scripts/verify_local.sh":
    failures.append("ci-local-verification-command-invalid")

connected_ci_props = validate_ci_evidence_record(
    "connected-android-tests",
    ci.get("connectedAndroidTests"),
    "regression-emulator",
)
validate_ci_identity("connected-android-tests", connected_ci_props, expected_job="emulator-regression", required=True)
if connected_ci_props.get("clean_device") != "1":
    failures.append("ci-connected-android-tests-not-clean")
if not positive_int_string(connected_ci_props.get("actual_android_test_count", "")):
    failures.append("ci-connected-android-tests-count-invalid")
if not non_empty_string(connected_ci_props.get("device_report_file")):
    failures.append("ci-connected-android-tests-device-report-missing")
if not non_empty_string(connected_ci_props.get("instrumentation_output_file")):
    failures.append("ci-connected-android-tests-instrumentation-output-missing")

api_matrix_ci_props = validate_ci_evidence_record(
    "api-matrix",
    ci.get("apiMatrix"),
    "regression-emulator-api-matrix",
)
validate_ci_identity("api-matrix", api_matrix_ci_props, expected_job="emulator-api-matrix", required=True)
if isinstance(ci.get("apiMatrix"), dict) and ci["apiMatrix"].get("artifactName") != "android-emulator-api-matrix-evidence":
    failures.append("ci-api-matrix-artifact-name-invalid")
validate_api_matrix_report(api_matrix_ci_props)

artifact_entry = ci.get("releaseArtifactArchive")
artifact_ci_props = validate_ci_evidence_record(
    "release-artifact-archive",
    artifact_entry,
    "ci-release-artifact-archive",
    "ReleaseArtifactArchiveEvidence/v1",
)
validate_ci_identity("release-artifact-archive", artifact_ci_props, expected_job="release-artifact-archive", required=True)
if isinstance(artifact_entry, dict) and not non_empty_string(artifact_entry.get("artifactName")):
    failures.append("ci-release-artifact-archive-name-missing")
if not is_sha256(artifact_ci_props.get("aabSha256", "")):
    failures.append("ci-release-artifact-archive-aab-sha-invalid")
elif expected_release_artifact_sha and expected_release_artifact_type != "apk" and artifact_ci_props.get("aabSha256", "") != expected_release_artifact_sha:
    failures.append("ci-release-artifact-archive-aab-sha-mismatch")
if expected_release_artifact_sha and expected_release_artifact_type == "apk" and artifact_ci_props.get("apkSha256", "") != expected_release_artifact_sha:
    failures.append("ci-release-artifact-archive-apk-sha-mismatch")
if not is_sha256(artifact_ci_props.get("mappingSha256", "")):
    failures.append("ci-release-artifact-archive-mapping-sha-invalid")
elif expected_release_mapping_sha and artifact_ci_props.get("mappingSha256", "") != expected_release_mapping_sha:
    failures.append("ci-release-artifact-archive-mapping-sha-mismatch")
if artifact_ci_props.get("artifactScanStatus") != "passed":
    failures.append("ci-release-artifact-archive-scan-not-passed")
validate_artifact_scan_report_binding("ci-release-artifact-archive", artifact_ci_props)
if not non_empty_string(artifact_ci_props.get("artifactUploadName")):
    failures.append("ci-release-artifact-archive-upload-name-missing")

signing_entry = ci.get("protectedSigning")
signing_ci_props = validate_ci_evidence_record(
    "protected-signing",
    signing_entry,
    "release-signing",
    "ReleaseSigningReport/v1",
)
validate_ci_identity("protected-signing", signing_ci_props, expected_job="protected-signing")
if isinstance(signing_entry, dict) and not non_empty_string(signing_entry.get("signingEnvironment")):
    failures.append("ci-protected-signing-environment-missing")
if signing_ci_props.get("signingMode") != "production":
    failures.append("ci-protected-signing-mode-invalid")
if signing_ci_props.get("artifactScanStatus") != "passed":
    failures.append("ci-protected-signing-artifact-scan-not-passed")
validate_artifact_scan_report_binding("ci-protected-signing", signing_ci_props)
if not is_sha256(signing_ci_props.get("expectedSigningCertSha256", "")):
    failures.append("ci-protected-signing-cert-sha-invalid")
elif expected_signing_cert_sha and signing_ci_props.get("expectedSigningCertSha256", "").lower() != expected_signing_cert_sha:
    failures.append("ci-protected-signing-cert-sha-mismatch")
if not is_sha256(signing_ci_props.get("signedAabSha256", "")):
    failures.append("ci-protected-signing-aab-sha-invalid")
elif expected_release_artifact_sha and expected_release_artifact_type != "apk" and signing_ci_props.get("signedAabSha256", "") != expected_release_artifact_sha:
    failures.append("ci-protected-signing-aab-sha-mismatch")

monitoring = record.get("monitoring")
if not isinstance(monitoring, dict):
    failures.append("monitoring-missing")
    monitoring = {}
if not non_empty_string(monitoring.get("owner")):
    failures.append("monitoring-owner-missing")
sources = monitoring.get("signalSources")
if not isinstance(sources, list) or not all(non_empty_string(source) for source in sources):
    failures.append("monitoring-signal-sources-missing")
    sources = []
if "Android Vitals" not in sources:
    failures.append("android-vitals-source-missing")
if not non_empty_string(monitoring.get("first24HoursWatcher")):
    failures.append("first-24-hours-watcher-missing")
if not number_between(monitoring.get("crashFreeRateThresholdPercent"), 90, 100):
    failures.append("crash-free-threshold-invalid")
if not number_between(monitoring.get("anrRateThresholdPercent"), 0, 10):
    failures.append("anr-threshold-invalid")
if monitoring.get("privacyReviewedForCrashSdk") is not True:
    failures.append("crash-sdk-privacy-review-not-confirmed")
monitoring_evidence_path = validate_evidence_file("monitoring", monitoring.get("evidence"))
if monitoring_evidence_path is not None:
    monitoring_props = properties_for(monitoring_evidence_path)
    validate_report_schema(
        "monitoring-evidence",
        monitoring_props,
        monitoring_evidence_path,
        "ReleaseMonitoringEvidence/v1",
        expected_owner=monitoring.get("owner", ""),
    )
    if monitoring_props.get("status") != "passed":
        failures.append("monitoring-evidence-status-not-passed")
    if monitoring_props.get("target") != "release-monitoring-evidence":
        failures.append("monitoring-evidence-target-invalid")
    if monitoring_props.get("operationsRecordField") != "monitoring.evidence":
        failures.append("monitoring-evidence-operations-record-field-invalid")
    validate_operations_record_binding("monitoring-evidence", monitoring_props)
    if non_empty_string(monitoring.get("owner")) and monitoring_props.get("owner") != monitoring.get("owner"):
        failures.append("monitoring-evidence-owner-mismatch")
    if sources and csv_tokens(monitoring_props.get("signalSources", "")) != set(sources):
        failures.append("monitoring-evidence-signal-sources-mismatch")
    if non_empty_string(monitoring.get("first24HoursWatcher")) and monitoring_props.get("first24HoursWatcher") != monitoring.get("first24HoursWatcher"):
        failures.append("monitoring-evidence-first-24-hours-watcher-mismatch")
    if number_between(monitoring.get("crashFreeRateThresholdPercent"), 90, 100) and not number_property_matches(
        monitoring_props.get("crashFreeRateThresholdPercent", ""),
        monitoring.get("crashFreeRateThresholdPercent"),
    ):
        failures.append("monitoring-evidence-crash-free-rate-threshold-mismatch")
    if number_between(monitoring.get("anrRateThresholdPercent"), 0, 10) and not number_property_matches(
        monitoring_props.get("anrRateThresholdPercent", ""),
        monitoring.get("anrRateThresholdPercent"),
    ):
        failures.append("monitoring-evidence-anr-rate-threshold-mismatch")
    if monitoring.get("privacyReviewedForCrashSdk") is True and monitoring_props.get("privacyReviewedForCrashSdk") != "true":
        failures.append("monitoring-evidence-crash-sdk-privacy-review-not-confirmed")

smoke = record.get("crashAnrSmoke")
if not isinstance(smoke, dict):
    failures.append("crash-anr-smoke-missing")
    smoke = {}
for field in ("window", "track", "failureEvidencePolicy"):
    if not non_empty_string(smoke.get(field)):
        failures.append(f"crash-anr-smoke-{field}-missing")
for field in (
    "noLaunchCrash",
    "noInstallCrash",
    "noCrashLoop",
    "noFatalNativeLiteRtLmFailure",
    "noReproducibleAnr",
):
    if smoke.get(field) is not True:
        failures.append(f"{field}-not-true")
smoke_evidence_path = validate_evidence_file("crash-anr-smoke", smoke.get("evidence"))
if smoke_evidence_path is not None:
    smoke_props = properties_for(smoke_evidence_path)
    validate_report_schema(
        "crash-anr-smoke-evidence",
        smoke_props,
        smoke_evidence_path,
        "CrashAnrSmokeEvidence/v1",
    )
    if smoke_props.get("status") != "passed":
        failures.append("crash-anr-smoke-evidence-status-not-passed")
    if smoke_props.get("target") != "crash-anr-smoke-evidence":
        failures.append("crash-anr-smoke-evidence-target-invalid")
    if smoke_props.get("operationsRecordField") != "crashAnrSmoke.evidence":
        failures.append("crash-anr-smoke-evidence-operations-record-field-invalid")
    validate_operations_record_binding("crash-anr-smoke-evidence", smoke_props)
    for field in ("window", "track", "failureEvidencePolicy"):
        if non_empty_string(smoke.get(field)) and smoke_props.get(field) != smoke.get(field):
            failures.append(f"crash-anr-smoke-evidence-{kebab(field)}-mismatch")
    if not non_empty_string(smoke_props.get("packageName")):
        failures.append("crash-anr-smoke-evidence-package-name-missing")
    if smoke_props.get("deviceStatus") != "passed":
        failures.append("crash-anr-smoke-evidence-device-status-not-passed")
    if smoke_props.get("instrumentationStatus") != "passed":
        failures.append("crash-anr-smoke-evidence-instrumentation-status-not-passed")
    if not positive_int_string(smoke_props.get("instrumentationTestCount", "")):
        failures.append("crash-anr-smoke-evidence-instrumentation-test-count-invalid")
    for field in ("serial", "apiLevel", "abi"):
        if not non_empty_string(smoke_props.get(field)):
            failures.append(f"crash-anr-smoke-evidence-{kebab(field)}-missing")
    if smoke_props.get("logcatAnalyzed") != "true":
        failures.append("crash-anr-smoke-evidence-logcat-not-analyzed")
    for field in (
        "noLaunchCrash",
        "noInstallCrash",
        "noCrashLoop",
        "noFatalNativeLiteRtLmFailure",
        "noReproducibleAnr",
    ):
        if smoke_props.get(field) != "true":
            failures.append(f"crash-anr-smoke-evidence-{kebab(field)}-not-true")
    for field in (
        "instrumentationCrashSignalCount",
        "instrumentationFailureSignalCount",
        "crashSignalCount",
        "installCrashSignalCount",
        "anrSignalCount",
        "fatalLiteRtLmSignalCount",
    ):
        validate_zero_count("crash-anr-smoke-evidence", smoke_props, field)

    device_report_path = validate_property_artifact(
        "crash-anr-smoke-device-report",
        smoke_props,
        "deviceReportFile",
        "deviceReportSha256",
        "deviceReportSizeBytes",
    )
    instrumentation_output_path = validate_property_artifact(
        "crash-anr-smoke-instrumentation-output",
        smoke_props,
        "instrumentationOutputFile",
        "instrumentationOutputSha256",
        "instrumentationOutputSizeBytes",
    )
    logcat_path = validate_property_artifact(
        "crash-anr-smoke-logcat",
        smoke_props,
        "logcatFile",
        "logcatSha256",
        "logcatSizeBytes",
    )
    device_report_props = properties_for(device_report_path)
    if device_report_path is not None:
        if device_report_props.get("status") != "passed":
            failures.append("crash-anr-smoke-device-report-status-not-passed")
        if device_report_props.get("instrumentation") != "passed":
            failures.append("crash-anr-smoke-device-report-instrumentation-not-passed")
        if device_report_props.get("instrumentation_test_count") != smoke_props.get("instrumentationTestCount"):
            failures.append("crash-anr-smoke-device-report-test-count-mismatch")
        if device_report_props.get("serial") != smoke_props.get("serial"):
            failures.append("crash-anr-smoke-device-report-serial-mismatch")
        if device_report_props.get("api_level") != smoke_props.get("apiLevel"):
            failures.append("crash-anr-smoke-device-report-api-level-mismatch")
        if device_report_props.get("abi") != smoke_props.get("abi"):
            failures.append("crash-anr-smoke-device-report-abi-mismatch")
        if non_empty_string(device_report_props.get("instrumentation_output_file")) and device_report_props.get("instrumentation_output_file") != str(instrumentation_output_path):
            failures.append("crash-anr-smoke-device-report-instrumentation-output-mismatch")
        if non_empty_string(device_report_props.get("logcat_file")) and device_report_props.get("logcat_file") != str(logcat_path):
            failures.append("crash-anr-smoke-device-report-logcat-mismatch")

rollback = record.get("rollback")
if not isinstance(rollback, dict):
    failures.append("rollback-missing")
    rollback = {}
for field in (
    "owner",
    "decisionChannel",
    "firstStagedRolloutAction",
    "playVersionCodePolicy",
    "modelManifestRollbackPath",
    "userDataCompatibility",
):
    if not non_empty_string(rollback.get(field)):
        failures.append(f"rollback-{field}-missing")

criteria = rollback.get("criteria")
required_criteria = {
    "install failure",
    "crash loop",
    "model download verification failure",
    "privacy boundary failure",
    "critical tool execution regression",
}
if not isinstance(criteria, list):
    failures.append("rollback-criteria-missing")
    criteria = []
criteria_set = {criterion for criterion in criteria if isinstance(criterion, str)}
for criterion in sorted(required_criteria - criteria_set):
    failures.append("rollback-criterion-missing-" + re.sub(r"[^a-z0-9]+", "-", criterion.lower()).strip("-"))
rollback_evidence_path = validate_evidence_file("rollback", rollback.get("evidence"))

previous = rollback.get("previousKnownGood")
if not isinstance(previous, dict):
    failures.append("previous-known-good-missing")
    previous = {}
previous_status = previous.get("status")
if previous_status == "not_applicable_initial_release":
    if not non_empty_string(previous.get("releaseNotes")):
        failures.append("previous-known-good-release-notes-missing")
elif previous_status == "available":
    version_code = previous.get("versionCode")
    if not isinstance(version_code, int) or version_code <= 0:
        failures.append("previous-known-good-version-code-invalid")
    if not non_empty_string(previous.get("versionName")):
        failures.append("previous-known-good-version-name-missing")
    commit = previous.get("gitCommit", "")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        failures.append("previous-known-good-git-commit-invalid")
    elif not git_success("cat-file", "-e", f"{commit}^{{commit}}"):
        failures.append("previous-known-good-git-commit-missing")
    artifact_path = Path(previous.get("artifactPath", ""))
    if not previous.get("artifactPath"):
        failures.append("previous-known-good-artifact-path-missing")
    elif not artifact_path.is_file():
        failures.append("previous-known-good-artifact-missing")
    else:
        actual_sha = hashlib.sha256(artifact_path.read_bytes()).hexdigest()
        if previous.get("artifactSha256") != actual_sha:
            failures.append("previous-known-good-artifact-sha-mismatch")
    if not non_empty_string(previous.get("releaseNotes")):
        failures.append("previous-known-good-release-notes-missing")
else:
    failures.append("previous-known-good-status-invalid")

if rollback_evidence_path is not None:
    rollback_props = properties_for(rollback_evidence_path)
    validate_report_schema(
        "rollback-evidence",
        rollback_props,
        rollback_evidence_path,
        "ReleaseRollbackEvidence/v1",
        expected_owner=rollback.get("owner", ""),
    )
    if rollback_props.get("status") != "passed":
        failures.append("rollback-evidence-status-not-passed")
    if rollback_props.get("target") != "release-rollback-evidence":
        failures.append("rollback-evidence-target-invalid")
    if rollback_props.get("operationsRecordField") != "rollback.evidence":
        failures.append("rollback-evidence-operations-record-field-invalid")
    validate_operations_record_binding("rollback-evidence", rollback_props)
    for field in (
        "owner",
        "decisionChannel",
        "firstStagedRolloutAction",
        "playVersionCodePolicy",
        "modelManifestRollbackPath",
        "userDataCompatibility",
    ):
        if non_empty_string(rollback.get(field)) and rollback_props.get(field) != rollback.get(field):
            failures.append(f"rollback-evidence-{kebab(field)}-mismatch")
    if criteria_set and csv_tokens(rollback_props.get("criteria", "")) != criteria_set:
        failures.append("rollback-evidence-criteria-mismatch")
    if non_empty_string(previous_status) and rollback_props.get("previousKnownGoodStatus") != previous_status:
        failures.append("rollback-evidence-previous-known-good-status-mismatch")
    if previous_status == "not_applicable_initial_release" and non_empty_string(previous.get("releaseNotes")) and rollback_props.get("previousKnownGoodReleaseNotes") != previous.get("releaseNotes"):
        failures.append("rollback-evidence-previous-known-good-release-notes-mismatch")
    if previous_status == "available":
        if isinstance(previous.get("versionCode"), int) and rollback_props.get("previousKnownGoodVersionCode") != str(previous.get("versionCode")):
            failures.append("rollback-evidence-previous-known-good-version-code-mismatch")
        for source_key, prop_key in (
            ("versionName", "previousKnownGoodVersionName"),
            ("gitCommit", "previousKnownGoodGitCommit"),
            ("artifactPath", "previousKnownGoodArtifactPath"),
            ("artifactSha256", "previousKnownGoodArtifactSha256"),
            ("releaseNotes", "previousKnownGoodReleaseNotes"),
        ):
            if non_empty_string(previous.get(source_key)) and rollback_props.get(prop_key) != previous.get(source_key):
                failures.append(f"rollback-evidence-{kebab(prop_key)}-mismatch")

review = record.get("review")
if not isinstance(review, dict):
    failures.append("review-missing")
    review = {}
if not non_empty_string(review.get("reviewer")):
    failures.append("reviewer-missing")
review_date = review.get("reviewDate", "")
date_pattern = re.compile(r"^\d{4}-\d{2}-\d{2}$")
if not review_date:
    failures.append("review-date-missing")
elif not date_pattern.match(review_date):
    failures.append("review-date-invalid")
else:
    try:
        parsed_date = date.fromisoformat(review_date)
    except ValueError:
        failures.append("review-date-invalid")
    else:
        if parsed_date > date.today():
            failures.append("review-date-in-future")

if failures:
    print(",".join(failures))
    sys.exit(1)

print("approved")
PY
status=$?
set -e

if [[ "$status" -ne 0 ]]; then
  reason="$(cat "$TMP_FAILURES")"
  write_report failed "${reason:-incomplete-release-operations-record}"
  echo "Release operations record is incomplete." >&2
  exit 1
fi

write_report passed approved
echo "Release operations record verification passed."
