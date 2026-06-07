#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

RELEASE_RECORD_FILE="${RELEASE_RECORD_FILE:-docs/release_record.json}"
GRADLE_FILE="${GRADLE_FILE:-app/build.gradle.kts}"
PUBLIC_RELEASE_CONTEXT="${PUBLIC_RELEASE_CONTEXT:-0}"
ALLOW_DIRTY_RELEASE="${ALLOW_DIRTY_RELEASE:-0}"
EXPECTED_RELEASE_ARTIFACT_PATH="${EXPECTED_RELEASE_ARTIFACT_PATH:-}"
EXPECTED_RELEASE_ARTIFACT_TYPE="${EXPECTED_RELEASE_ARTIFACT_TYPE:-}"
EXPECTED_RELEASE_ARTIFACT_SHA256="${EXPECTED_RELEASE_ARTIFACT_SHA256:-}"
EXPECTED_SIGNING_CERT_SHA256="${EXPECTED_SIGNING_CERT_SHA256:-}"
REPORT_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file)
      RELEASE_RECORD_FILE="${2:?missing release record file}"
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
      printf 'target=release-record\n'
      printf 'recordFile=%s\n' "$RELEASE_RECORD_FILE"
      printf 'gradleFile=%s\n' "$GRADLE_FILE"
      printf 'publicReleaseContext=%s\n' "$PUBLIC_RELEASE_CONTEXT"
      printf 'allowDirtyRelease=%s\n' "$ALLOW_DIRTY_RELEASE"
      printf 'expectedReleaseArtifactPath=%s\n' "$EXPECTED_RELEASE_ARTIFACT_PATH"
      printf 'expectedReleaseArtifactType=%s\n' "$EXPECTED_RELEASE_ARTIFACT_TYPE"
      printf 'expectedReleaseArtifactSha256=%s\n' "$EXPECTED_RELEASE_ARTIFACT_SHA256"
      printf 'expectedSigningCertSha256=%s\n' "$EXPECTED_SIGNING_CERT_SHA256"
      printf 'reason=%s\n' "$reason"
    } > "$REPORT_FILE"
  fi
}

if [[ ! -f "$RELEASE_RECORD_FILE" ]]; then
  write_report failed missing-release-record-file
  echo "Release record file is missing: $RELEASE_RECORD_FILE" >&2
  exit 1
fi

if [[ ! -f "$GRADLE_FILE" ]]; then
  write_report failed missing-gradle-file
  echo "Gradle file is missing: $GRADLE_FILE" >&2
  exit 1
fi

TMP_FAILURES="$(mktemp)"
trap 'rm -f "$TMP_FAILURES"' EXIT

set +e
python3 - \
  "$RELEASE_RECORD_FILE" \
  "$GRADLE_FILE" \
  "$PUBLIC_RELEASE_CONTEXT" \
  "$ALLOW_DIRTY_RELEASE" \
  "$EXPECTED_RELEASE_ARTIFACT_PATH" \
  "$EXPECTED_RELEASE_ARTIFACT_TYPE" \
  "$EXPECTED_RELEASE_ARTIFACT_SHA256" \
  "$EXPECTED_SIGNING_CERT_SHA256" > "$TMP_FAILURES" <<'PY'
import hashlib
import json
import re
import subprocess
import sys
from datetime import date
from pathlib import Path

record_path = Path(sys.argv[1])
gradle_path = Path(sys.argv[2])
public_release_context = sys.argv[3] == "1"
allow_dirty_release = sys.argv[4] == "1"
expected_artifact_path = sys.argv[5]
expected_artifact_type = sys.argv[6]
expected_artifact_sha256 = sys.argv[7].lower()
expected_signing_cert_sha256 = sys.argv[8].lower()

try:
    record = json.loads(record_path.read_text())
except Exception:
    print("json-parse-error")
    sys.exit(1)

gradle_text = gradle_path.read_text()

def gradle_string(name):
    match = re.search(rf"\b{name}\s*=\s*\"([^\"]+)\"", gradle_text)
    return match.group(1) if match else ""

def gradle_int(name):
    match = re.search(rf"\b{name}\s*=\s*([0-9]+)", gradle_text)
    return int(match.group(1)) if match else None

def git_value(*args):
    try:
        return subprocess.check_output(["git", *args], text=True).strip()
    except Exception:
        return ""

def git_success(*args):
    try:
        subprocess.check_call(["git", *args], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except Exception:
        return False

def git_worktree_dirty():
    unstaged = not git_success("diff", "--quiet")
    staged = not git_success("diff", "--cached", "--quiet")
    untracked = bool(git_value("ls-files", "--others", "--exclude-standard"))
    return unstaged or staged or untracked

def properties_for(path):
    values = {}
    try:
        for raw_line in Path(path).read_text(errors="ignore").splitlines():
            if "=" not in raw_line:
                continue
            key, value = raw_line.split("=", 1)
            values[key] = value
    except OSError:
        pass
    return values

def non_empty_string(value):
    return isinstance(value, str) and bool(value.strip())

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

failures = []
if record.get("version") != 1:
    failures.append("version-invalid")
if record.get("status") != "approved":
    failures.append("status-not-approved")

release = record.get("release")
if not isinstance(release, dict):
    failures.append("release-missing")
    release = {}

expected_application_id = gradle_string("applicationId")
expected_version_code = gradle_int("versionCode")
expected_version_name = gradle_string("versionName")

if release.get("applicationId") != expected_application_id:
    failures.append("application-id-mismatch")
if release.get("versionCode") != expected_version_code:
    failures.append("version-code-mismatch")
if release.get("versionName") != expected_version_name:
    failures.append("version-name-mismatch")

git_commit = release.get("gitCommit", "")
head_commit = git_value("rev-parse", "HEAD")
if not re.fullmatch(r"[0-9a-f]{40}", git_commit):
    failures.append("git-commit-invalid")
elif not git_success("cat-file", "-e", f"{git_commit}^{{commit}}"):
    failures.append("git-commit-missing")
elif git_commit != head_commit:
    failures.append("git-commit-not-current-head")

if not release.get("gitBranch"):
    failures.append("git-branch-missing")

valid_channels = {
    "internal_testing",
    "closed_testing",
    "open_testing",
    "staged_production",
    "full_production",
}
if release.get("targetChannel") not in valid_channels:
    failures.append("target-channel-invalid")
if public_release_context and release.get("targetChannel") not in {
    "open_testing",
    "staged_production",
    "full_production",
}:
    failures.append("public-release-target-channel-invalid")
if public_release_context and not allow_dirty_release and git_worktree_dirty():
    failures.append("git-worktree-dirty")

today = date.today()
date_pattern = re.compile(r"^\d{4}-\d{2}-\d{2}$")
release_date = release.get("releaseDate", "")
if not release_date:
    failures.append("release-date-missing")
elif not date_pattern.match(release_date):
    failures.append("release-date-invalid")
else:
    try:
        parsed_release_date = date.fromisoformat(release_date)
    except ValueError:
        failures.append("release-date-invalid")
    else:
        if parsed_release_date > today:
            failures.append("release-date-in-future")

for key in ("owner", "reviewer", "changelog", "releaseNotes"):
    if not release.get(key):
        failures.append(f"{key}-missing")

agent_summary = release.get("agentBehaviorSummary", "")
if not isinstance(agent_summary, str) or len(agent_summary.strip()) < 40:
    failures.append("agent-behavior-summary-missing")

unsupported = release.get("unsupportedCapabilities")
if not isinstance(unsupported, list) or not all(isinstance(item, str) and item.strip() for item in unsupported):
    failures.append("unsupported-capabilities-missing")

artifact = release.get("artifact")
if not isinstance(artifact, dict):
    failures.append("artifact-missing")
    artifact = {}

artifact_type = artifact.get("type", "")
artifact_path_value = artifact.get("path", "")
artifact_path = Path(artifact_path_value)
if artifact_type not in {"apk", "aab"}:
    failures.append("artifact-type-invalid")
if expected_artifact_type and artifact_type != expected_artifact_type:
    failures.append("artifact-type-expected-mismatch")
if public_release_context and artifact_type != "aab":
    failures.append("public-release-artifact-type-not-aab")
if not artifact_path_value:
    failures.append("artifact-path-missing")
elif not artifact_path.is_file():
    failures.append("artifact-path-missing")
else:
    artifact_bytes = artifact_path.read_bytes()
    actual_sha = hashlib.sha256(artifact_bytes).hexdigest()
    if artifact.get("sha256") != actual_sha:
        failures.append("artifact-sha-mismatch")
    if artifact.get("sizeBytes") != len(artifact_bytes):
        failures.append("artifact-size-mismatch")
    suffix_type = artifact_path.suffix.removeprefix(".")
    if artifact_type and suffix_type and suffix_type != artifact_type:
        failures.append("artifact-type-path-mismatch")
if expected_artifact_path and artifact_path_value != expected_artifact_path:
    failures.append("artifact-path-expected-mismatch")
if expected_artifact_sha256 and artifact.get("sha256", "").lower() != expected_artifact_sha256:
    failures.append("artifact-sha-expected-mismatch")

signing_sha = artifact.get("signingCertificateSha256", "")
if not re.fullmatch(r"[0-9a-fA-F]{64}", signing_sha):
    failures.append("signing-certificate-sha-invalid")
elif expected_signing_cert_sha256 and signing_sha.lower() != expected_signing_cert_sha256:
    failures.append("signing-certificate-expected-mismatch")

reports = release.get("verificationReports")
if not isinstance(reports, list) or not reports:
    failures.append("verification-reports-missing")
    reports = []
for index, report in enumerate(reports):
    if not isinstance(report, dict):
        failures.append(f"verification-report-{index}-invalid")
        continue
    if not report.get("name"):
        failures.append(f"verification-report-{index}-name-missing")
    report_path = report.get("path", "")
    if not report_path:
        failures.append(f"verification-report-{index}-path-missing")
    elif not Path(report_path).is_file():
        failures.append(f"verification-report-{index}-file-missing")
    report_sha = report.get("sha256", "")
    if report_path and Path(report_path).is_file():
        report_file = Path(report_path)
        actual_report_sha = hashlib.sha256(report_file.read_bytes()).hexdigest()
        if report_sha != actual_report_sha:
            failures.append(f"verification-report-{index}-sha-mismatch")
        report_properties = properties_for(report_file)
        if report_properties.get("status") != "passed":
            failures.append(f"verification-report-{index}-status-not-passed")
        if not report_properties.get("target"):
            failures.append(f"verification-report-{index}-target-missing")
    elif not re.fullmatch(r"[0-9a-fA-F]{64}", report_sha):
        failures.append(f"verification-report-{index}-sha-invalid")

blockers = release.get("blockers")
if not isinstance(blockers, list):
    failures.append("blockers-invalid")
    blockers = []
for index, blocker in enumerate(blockers):
    if not isinstance(blocker, dict):
        failures.append(f"blocker-{index}-invalid")
        continue
    blocker_id = blocker.get("id", f"blocker-{index}")
    status = blocker.get("status")
    if not blocker_id:
        failures.append(f"blocker-{index}-id-missing")
    if status not in {"resolved", "accepted"}:
        failures.append(f"{blocker_id}-not-resolved-or-accepted")
    if status == "accepted" and not blocker.get("riskNote"):
        failures.append(f"{blocker_id}-risk-note-missing")
    if not blocker.get("owner"):
        failures.append(f"{blocker_id}-owner-missing")
    evidence_path = blocker.get("evidencePath", "")
    if not non_empty_string(evidence_path):
        failures.append(f"{blocker_id}-evidence-path-missing")
    elif not Path(evidence_path).is_file():
        failures.append(f"{blocker_id}-evidence-file-missing")
    else:
        validate_file_sha(
            f"{blocker_id}-evidence",
            evidence_path,
            blocker.get("evidenceSha256", ""),
        )
    blocker_date = blocker.get("date", "")
    if not blocker_date:
        failures.append(f"{blocker_id}-date-missing")
    elif not date_pattern.match(blocker_date):
        failures.append(f"{blocker_id}-date-invalid")
    else:
        try:
            parsed_blocker_date = date.fromisoformat(blocker_date)
        except ValueError:
            failures.append(f"{blocker_id}-date-invalid")
        else:
            if parsed_blocker_date > today:
                failures.append(f"{blocker_id}-date-in-future")

if failures:
    print(",".join(failures))
    sys.exit(1)

print("approved")
PY
status=$?
set -e

if [[ "$status" -ne 0 ]]; then
  reason="$(cat "$TMP_FAILURES")"
  write_report failed "${reason:-incomplete-release-record}"
  echo "Release record is incomplete." >&2
  exit 1
fi

write_report passed approved
echo "Release record verification passed."
