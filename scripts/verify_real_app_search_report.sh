#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SOURCE_REPORT="${REAL_APP_SEARCH_REPORT_FILE:-build/verification/real-app-search-eval.properties}"
REPORT_FILE="${REAL_APP_SEARCH_EVIDENCE_REPORT_FILE:-}"
EVIDENCE_OWNER="${EVIDENCE_OWNER:-${OWNER:-real-app-control}}"
ORIGINAL_ARGS=("$@")

command_line() {
  local quoted=()
  local arg
  quoted+=("$(printf '%q' "scripts/verify_real_app_search_report.sh")")
  if [[ "${#ORIGINAL_ARGS[@]}" -gt 0 ]]; then
    for arg in "${ORIGINAL_ARGS[@]}"; do
      quoted+=("$(printf '%q' "$arg")")
    done
  fi
  local IFS=' '
  printf '%s' "${quoted[*]}"
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --file)
      SOURCE_REPORT="${2:?missing real app search report path}"
      shift 2
      ;;
    --report)
      REPORT_FILE="${2:?missing verification report path}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

python3 - "$SOURCE_REPORT" "$REPORT_FILE" "$EVIDENCE_OWNER" "$(command_line)" <<'PY'
import hashlib
import json
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

source_report = Path(sys.argv[1])
verification_report = sys.argv[2]
owner = sys.argv[3]
command = sys.argv[4]

EXPECTED_CASES = [
    "taobao",
    "pdd",
    "gaode",
    "jd",
    "chrome",
    "android_browser",
    "quark",
    "uc",
]
EXPECTED_PACKAGES = {
    "taobao": "com.taobao.taobao",
    "pdd": "com.xunmeng.pinduoduo",
    "gaode": "com.autonavi.minimap",
    "jd": "com.jingdong.app.mall",
    "chrome": "com.android.chrome",
    "android_browser": "com.android.browser",
    "quark": "com.quark.browser",
    "uc": "com.UCMobile",
}

HEX64 = re.compile(r"^[0-9a-f]{64}$")


def sha256_file(path):
    path = Path(path)
    if not path.is_file():
        return ""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_properties(path):
    props = {}
    with Path(path).open("r", encoding="utf-8", errors="replace") as handle:
        for raw_line in handle:
            line = raw_line.rstrip("\n\r")
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            props[key] = value
    return props


def as_int(props, key, failures, context):
    value = props.get(key, "")
    try:
        parsed = int(value)
    except ValueError:
        failures.append(f"{context}-not-integer:{key}")
        return 0
    if parsed < 0:
        failures.append(f"{context}-negative:{key}")
    return parsed


def path_value(value):
    if not value:
        return None
    return Path(value)


def validate_sha(props, file_key, sha_key, failures, *, context, reason_prefix, required=False, counted=None):
    value = props.get(file_key, "")
    expected = props.get(sha_key, "")
    if not value:
        if required:
            failures.append(f"{reason_prefix}-missing:{context}:{file_key}")
        return None

    path = path_value(value)
    if path is None or not path.is_file():
        failures.append(f"{reason_prefix}-file-missing:{context}:{file_key}")
        return path

    if not expected:
        failures.append(f"{reason_prefix}-sha-missing:{context}:{sha_key}")
        return path
    if not HEX64.fullmatch(expected):
        failures.append(f"{reason_prefix}-sha-invalid:{context}:{sha_key}")
        return path

    actual = sha256_file(path)
    if actual != expected:
        failures.append(f"{reason_prefix}-sha-mismatch:{context}")
        return path

    if counted is not None:
        counted.add(str(path))
    return path


def validate_ranked_candidates_json(path, failures, *, context):
    if not path or not Path(path).is_file():
        return
    try:
        document = json.loads(Path(path).read_text(encoding="utf-8"))
    except Exception:
        failures.append(f"ranked-candidates-json-invalid:{context}")
        return
    candidates = document.get("candidates")
    if not isinstance(candidates, list):
        failures.append(f"ranked-candidates-json-missing-candidates:{context}")


def failed_target_for(reason):
    first = reason.split(",", 1)[0] if reason else ""
    if not first:
        return ""
    if first.startswith(("missing-real-app-search-report", "report-", "source-")):
        return "real-app-search-report"
    if "diagnostics" in first:
        return "real-app-search-diagnostics"
    if "ranked-candidates" in first or "target-resolution" in first:
        return "real-app-search-target-resolution-evidence"
    if "case" in first or "result-file" in first:
        return "real-app-search-case-artifact"
    return "real-app-search-evidence"


def validate_target_resolution_evidence(path, failures, *, case_name, expected_schema, ranked_files):
    if not path or not Path(path).is_file():
        return
    props = read_properties(path)
    schema = props.get("artifact_schema", "")
    if schema != expected_schema:
        failures.append(f"target-resolution-evidence-schema-mismatch:{case_name}")
    if props.get("case", "") != case_name:
        failures.append(f"target-resolution-evidence-case-mismatch:{case_name}")
    ranked_required = props.get("target_resolution_available", "false") == "true"
    validate_sha(
        props,
        "result_file",
        "result_file_sha256",
        failures,
        context=case_name,
        reason_prefix="result-file",
        required=False,
    )
    ranked_path = validate_sha(
        props,
        "ranked_candidates_file",
        "ranked_candidates_sha256",
        failures,
        context=case_name,
        reason_prefix="ranked-candidates",
        required=ranked_required,
        counted=ranked_files,
    )
    validate_ranked_candidates_json(ranked_path, failures, context=case_name)


def validate_case(case_report, case_name, failures, metrics):
    props = read_properties(case_report)
    if props.get("artifact_schema", "") != "RealAppSearchCaseArtifact/v1":
        failures.append(f"case-schema-mismatch:{case_name}")
    if props.get("case", "") != case_name:
        failures.append(f"case-name-mismatch:{case_name}")
    expected_package = EXPECTED_PACKAGES.get(case_name, "")
    if expected_package and props.get("expected_package_name", "") not in {"", expected_package}:
        failures.append(f"case-expected-package-mismatch:{case_name}")

    status = props.get("status", "")
    if status not in {"passed", "failed", "skipped"}:
        failures.append(f"case-status-invalid:{case_name}")
    else:
        metrics["case_statuses"][status] += 1

    if status in {"failed", "skipped"} and not props.get("reason", ""):
        failures.append(f"case-reason-missing:{case_name}")
    if status == "failed":
        if not props.get("failure_kind", ""):
            failures.append(f"case-failure-kind-missing:{case_name}")
        if not props.get("failed_step", ""):
            failures.append(f"case-failed-step-missing:{case_name}")
        metrics["failure_kinds"][props.get("failure_kind", "") or "unknown"] += 1

    validate_sha(
        props,
        "result_file",
        "result_file_sha256",
        failures,
        context=case_name,
        reason_prefix="result-file",
        required=False,
    )

    if props.get("target_resolution_available", "false") == "true":
        evidence_path = validate_sha(
            props,
            "target_resolution_evidence_file",
            "target_resolution_evidence_sha256",
            failures,
            context=case_name,
            reason_prefix="target-resolution-evidence",
            required=True,
            counted=metrics["target_resolution_evidence_files"],
        )
        validate_target_resolution_evidence(
            evidence_path,
            failures,
            case_name=case_name,
            expected_schema="UiTargetResolutionEvidenceArtifact/v1",
            ranked_files=metrics["ranked_candidate_files"],
        )

    ranked_path = validate_sha(
        props,
        "ranked_candidates_file",
        "ranked_candidates_sha256",
        failures,
        context=case_name,
        reason_prefix="ranked-candidates",
        required=props.get("target_resolution_available", "false") == "true",
        counted=metrics["ranked_candidate_files"],
    )
    validate_ranked_candidates_json(ranked_path, failures, context=case_name)

    step_evidence_keys = [
        key
        for key in props
        if key.startswith("step_") and key.endswith("_target_resolution_evidence_file")
    ]
    for key in sorted(step_evidence_keys):
        step_context = f"{case_name}:{key[len('step_'):-len('_target_resolution_evidence_file')]}"
        evidence_path = validate_sha(
            props,
            key,
            key.replace("_file", "_sha256"),
            failures,
            context=step_context,
            reason_prefix="target-resolution-evidence",
            required=False,
            counted=metrics["target_resolution_evidence_files"],
        )
        validate_target_resolution_evidence(
            evidence_path,
            failures,
            case_name=case_name,
            expected_schema="UiTargetResolutionStepEvidenceArtifact/v1",
            ranked_files=metrics["ranked_candidate_files"],
        )

    step_ranked_keys = [
        key
        for key in props
        if key.startswith("step_") and key.endswith("_ranked_candidates_file")
    ]
    for key in sorted(step_ranked_keys):
        step_context = f"{case_name}:{key[len('step_'):-len('_ranked_candidates_file')]}"
        step_prefix = key[: -len("_ranked_candidates_file")]
        ranked_required = props.get(f"{step_prefix}_target_resolution_available", "false") == "true"
        ranked_path = validate_sha(
            props,
            key,
            key.replace("_file", "_sha256"),
            failures,
            context=step_context,
            reason_prefix="ranked-candidates",
            required=ranked_required,
            counted=metrics["ranked_candidate_files"],
        )
        validate_ranked_candidates_json(ranked_path, failures, context=step_context)

    if status == "failed":
        diagnostics_dir = props.get("diagnostics_dir", "")
        if not diagnostics_dir:
            failures.append(f"diagnostics-dir-missing:{case_name}")
        elif not Path(diagnostics_dir).is_dir():
            failures.append(f"diagnostics-dir-file-missing:{case_name}")
        diagnostics_mappings = [
            ("screenshot_file", "screenshot_sha256"),
            ("uiautomator_dump_file", "uiautomator_dump_sha256"),
            ("focused_window_file", "focused_window_sha256"),
            ("window_dump_file", "window_dump_sha256"),
            ("case_logcat_file", "case_logcat_sha256"),
        ]
        for file_key, sha_key in diagnostics_mappings:
            validate_sha(
                props,
                file_key,
                sha_key,
                failures,
                context=f"{case_name}:{file_key}",
                reason_prefix="diagnostics",
                required=True,
                counted=metrics["diagnostics_files"],
            )


def write_report(status, reason, metrics, source_props):
    if not verification_report:
        return
    report_path = Path(verification_report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    failed_target = failed_target_for(reason) if status != "passed" else ""
    failure_breakdown = ",".join(
        f"{key}:{count}" for key, count in sorted(metrics["failure_kinds"].items()) if key
    )
    lines = [
        "artifactSchema=RealAppSearchEvidenceVerification/v1",
        f"status={status}",
        "target=real-app-search-evidence",
        f"owner={owner}",
        f"recordedAt={datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}",
        f"command={command}",
        f"failedTarget={failed_target}",
        f"reason={reason}",
        f"reproduciblePath={verification_report}",
        f"realAppSearchReportFile={source_report}",
        f"realAppSearchReportSha256={sha256_file(source_report)}",
        f"sourceArtifactSchema={source_props.get('artifact_schema', '')}",
        f"sourceStatus={source_props.get('status', '')}",
        f"sourceFailedTarget={source_props.get('failedTarget', '')}",
        f"sourceReason={source_props.get('reason', '')}",
        f"caseCount={metrics['case_count']}",
        f"runCount={metrics['run_count']}",
        f"passCount={metrics['pass_count']}",
        f"failCount={metrics['fail_count']}",
        f"skipCount={metrics['skip_count']}",
        f"caseArtifactCount={metrics['case_artifact_count']}",
        f"passedCaseArtifactCount={metrics['case_statuses']['passed']}",
        f"failedCaseArtifactCount={metrics['case_statuses']['failed']}",
        f"skippedCaseArtifactCount={metrics['case_statuses']['skipped']}",
        f"rankedCandidatesArtifactCount={len(metrics['ranked_candidate_files'])}",
        f"targetResolutionEvidenceCount={len(metrics['target_resolution_evidence_files'])}",
        f"diagnosticsArtifactCount={len(metrics['diagnostics_files'])}",
        f"failureKindBreakdown={failure_breakdown}",
    ]
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


failures = []
source_props = {}
metrics = {
    "case_count": 0,
    "run_count": 0,
    "pass_count": 0,
    "fail_count": 0,
    "skip_count": 0,
    "case_artifact_count": 0,
    "case_statuses": Counter(),
    "failure_kinds": Counter(),
    "ranked_candidate_files": set(),
    "target_resolution_evidence_files": set(),
    "diagnostics_files": set(),
}

if not source_report.is_file():
    failures.append("missing-real-app-search-report")
else:
    try:
        source_props = read_properties(source_report)
    except Exception:
        failures.append("report-unreadable")
        source_props = {}

if source_props:
    if source_props.get("artifact_schema", "") != "RealAppSearchEvalArtifact/v1":
        failures.append("report-schema-mismatch")
    if source_props.get("target", "") != "real-app-search-eval":
        failures.append("report-target-mismatch")
    if source_props.get("case_artifact_schema", "") != "RealAppSearchCaseArtifact/v1":
        failures.append("report-case-artifact-schema-mismatch")
    for required_key in ("serial", "api_level", "abi"):
        if not source_props.get(required_key, ""):
            failures.append(f"report-required-field-missing:{required_key}")

    cases = [case for case in source_props.get("cases", "").split(",") if case]
    metrics["case_count"] = len(cases)
    if cases != EXPECTED_CASES:
        failures.append("report-cases-mismatch")
    if len(set(cases)) != len(cases):
        failures.append("report-cases-duplicate")

    metrics["run_count"] = as_int(source_props, "run_count", failures, "report")
    metrics["pass_count"] = as_int(source_props, "pass_count", failures, "report")
    metrics["fail_count"] = as_int(source_props, "fail_count", failures, "report")
    metrics["skip_count"] = as_int(source_props, "skip_count", failures, "report")

    if metrics["run_count"] + metrics["skip_count"] != metrics["case_count"]:
        failures.append("report-run-skip-count-mismatch")
    if metrics["pass_count"] + metrics["fail_count"] != metrics["run_count"]:
        failures.append("report-pass-fail-count-mismatch")

    source_status = source_props.get("status", "")
    if source_status not in {"passed", "failed"}:
        failures.append("report-status-invalid")
    if source_status == "passed" and metrics["fail_count"] != 0:
        failures.append("report-passed-with-failures")
    if source_status == "failed":
        if not source_props.get("failedTarget", ""):
            failures.append("report-failed-target-missing")
        if not source_props.get("reason", ""):
            failures.append("report-reason-missing")

    validate_sha(
        source_props,
        "logcat_file",
        "logcat_sha256",
        failures,
        context="top-level",
        reason_prefix="source-logcat",
        required=False,
    )

    report_dir = source_report.parent
    for case_name in cases:
        case_report = report_dir / f"{case_name}.case.properties"
        if not case_report.is_file():
            failures.append(f"case-report-missing:{case_name}")
            continue
        metrics["case_artifact_count"] += 1
        validate_case(case_report, case_name, failures, metrics)

    if metrics["case_artifact_count"] != metrics["case_count"]:
        failures.append("case-artifact-count-mismatch")
    if metrics["case_statuses"]["passed"] != metrics["pass_count"]:
        failures.append("case-pass-count-mismatch")
    if metrics["case_statuses"]["failed"] != metrics["fail_count"]:
        failures.append("case-fail-count-mismatch")
    if metrics["case_statuses"]["skipped"] != metrics["skip_count"]:
        failures.append("case-skip-count-mismatch")

    fatal_failure = source_props.get("failedTarget", "") == "target-apps" or (
        source_status == "failed" and metrics["run_count"] == 0
    )
    if fatal_failure:
        if not source_props.get("failure_diagnostics_dir", ""):
            failures.append("fatal-diagnostics-dir-missing")
        elif not Path(source_props.get("failure_diagnostics_dir", "")).is_dir():
            failures.append("fatal-diagnostics-dir-file-missing")
        fatal_mappings = [
            ("failure_screenshot_file", "failure_screenshot_sha256"),
            ("failure_uiautomator_dump_file", "failure_uiautomator_dump_sha256"),
            ("failure_focused_window_file", "failure_focused_window_sha256"),
            ("failure_window_dump_file", "failure_window_dump_sha256"),
            ("failure_logcat_file", "failure_logcat_sha256"),
        ]
        for file_key, sha_key in fatal_mappings:
            validate_sha(
                source_props,
                file_key,
                sha_key,
                failures,
                context=f"fatal:{file_key}",
                reason_prefix="fatal-diagnostics",
                required=True,
                counted=metrics["diagnostics_files"],
            )

reason = ",".join(failures)
status = "failed" if failures else "passed"
write_report(status, reason, metrics, source_props)

if failures:
    print(f"Real app search evidence verification failed: {reason}", file=sys.stderr)
    sys.exit(1)

print(f"Real app search evidence verification passed: {source_report}")
PY
