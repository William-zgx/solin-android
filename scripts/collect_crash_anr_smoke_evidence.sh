#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/crash-anr-smoke-$(date +%Y%m%d-%H%M%S)}"
REPORT_FILE="${REPORT_FILE:-${ARTIFACT_DIR}/crash-anr-smoke.properties}"
DEVICE_REPORT_FILE="${DEVICE_REPORT_FILE:-}"
INSTRUMENTATION_OUTPUT_FILE="${INSTRUMENTATION_OUTPUT_FILE:-}"
LOGCAT_FILE="${LOGCAT_FILE:-}"
WINDOW="${WINDOW:-post-install instrumentation smoke}"
TRACK="${TRACK:-local-emulator}"
PACKAGE_NAME="${PACKAGE_NAME:-com.bytedance.zgx.solin}"
FAILURE_EVIDENCE_POLICY="${FAILURE_EVIDENCE_POLICY:-Attach logcat, tombstones, and ANR traces for any failure; state no crash or ANR when none were observed.}"
EVIDENCE_OWNER="${EVIDENCE_OWNER:-${OWNER:-release-engineering}}"
OPERATIONS_RECORD_FILE="${OPERATIONS_RECORD_FILE:-docs/release_operations_record.json}"
ORIGINAL_ARGS=("$@")
SOLIN_SCRIPT_COMMAND="scripts/collect_crash_anr_smoke_evidence.sh"
source "$ROOT_DIR/scripts/lib/report_helpers.sh"
EXPLICIT_INSTRUMENTATION_OUTPUT_FILE=0
EXPLICIT_LOGCAT_FILE=0

usage() {
  cat <<'USAGE'
Usage: scripts/collect_crash_anr_smoke_evidence.sh \
  --device-report build/verification/.../device-verification.properties \
  --logcat build/verification/.../logcat.txt \
  [--instrumentation-output build/verification/.../instrumentation.txt] \
  [--report build/verification/.../crash-anr-smoke.properties] \
  [--window "2026-06-06 internal smoke"] \
  [--track internal_testing]

The device report must be produced by install_and_test_device.sh or a wrapper
that preserves status, instrumentation, instrumentation_test_count, serial,
api_level, abi, and instrumentation_output_file.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device-report)
      DEVICE_REPORT_FILE="${2:?missing device report file}"
      shift 2
      ;;
    --instrumentation-output)
      INSTRUMENTATION_OUTPUT_FILE="${2:?missing instrumentation output file}"
      EXPLICIT_INSTRUMENTATION_OUTPUT_FILE=1
      shift 2
      ;;
    --logcat)
      LOGCAT_FILE="${2:?missing logcat file}"
      EXPLICIT_LOGCAT_FILE=1
      shift 2
      ;;
    --report)
      REPORT_FILE="${2:?missing report file}"
      shift 2
      ;;
    --window)
      WINDOW="${2:?missing smoke window}"
      shift 2
      ;;
    --track)
      TRACK="${2:?missing release track}"
      shift 2
      ;;
    --package)
      PACKAGE_NAME="${2:?missing package name}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

sha256_file() {
  local file="$1"
  [[ -f "$file" ]] || return 0
  shasum -a 256 "$file" | awk '{print $1}'
}

size_bytes() {
  local file="$1"
  [[ -f "$file" ]] || return 0
  wc -c < "$file" | tr -d ' '
}

count_instrumentation_crash_signals() {
  local file="$1"
  awk '
    /INSTRUMENTATION_RESULT: shortMsg=Process crashed/ { count += 1; next }
    /Process crashed/ { count += 1; next }
    /FATAL EXCEPTION/ { count += 1; next }
    /Fatal signal/ { count += 1; next }
    END { print count + 0 }
  ' "$file"
}

count_instrumentation_failure_signals() {
  local file="$1"
  awk '
    /FAILURES!!!/ { count += 1; next }
    /androidx[.]compose[.]ui[.]test[.]ComposeTimeoutException/ { count += 1; next }
    /java[.]lang[.]AssertionError/ { count += 1; next }
    END { print count + 0 }
  ' "$file"
}

count_logcat_crash_signals() {
  local file="$1"
  awk -v package_name="$PACKAGE_NAME" '
    BEGIN {
      package_lower = tolower(package_name)
      pending_fatal_window = 0
    }
    {
      line = $0
      lower = tolower(line)
      if (pending_fatal_window > 0) {
        if (index(lower, package_lower) > 0) {
          count += 1
          pending_fatal_window = 0
          next
        }
        pending_fatal_window -= 1
      }
      if (index(lower, package_lower) > 0 && lower ~ /(fatal exception|fatal signal|crash|crashed|force finishing)/) {
        count += 1
        next
      }
      if (lower ~ /(fatal exception|fatal signal)/) {
        pending_fatal_window = 6
        next
      }
    }
    END { print count + 0 }
  ' "$file"
}

count_logcat_install_crash_signals() {
  local file="$1"
  awk -v package_name="$PACKAGE_NAME" '
    BEGIN { package_lower = tolower(package_name) }
    {
      lower = tolower($0)
      if (index(lower, package_lower) > 0 && lower ~ /(install|packageinstaller|installd)/ && lower ~ /(crash|crashed|fatal|failed|failure)/) {
        count += 1
      }
    }
    END { print count + 0 }
  ' "$file"
}

count_logcat_anr_signals() {
  local file="$1"
  awk -v package_name="$PACKAGE_NAME" '
    BEGIN { package_lower = tolower(package_name) }
    {
      lower = tolower($0)
      if (index(lower, package_lower) == 0) {
        next
      }
      if (lower ~ /(anr in|application not responding|input dispatching timed out)/) {
        count += 1
      }
    }
    END { print count + 0 }
  ' "$file"
}

count_logcat_litert_fatal_signals() {
  local file="$1"
  awk '
    {
      lower = tolower($0)
      if (lower ~ /no implementation found/ && lower ~ /nativecheckloaded/) {
        next
      }
      if (lower ~ /(litert|litertlm|liblitert)/ && lower ~ /(fatal|signal|abort|exception|unsatisfiedlinkerror|linker)/) {
        count += 1
      }
    }
    END { print count + 0 }
  ' "$file"
}

bool_for_zero() {
  local value="$1"
  if [[ "$value" == "0" ]]; then
    printf 'true'
  else
    printf 'false'
  fi
}

FAILURES=()
add_failure() {
  FAILURES+=("$1")
}

DEVICE_STATUS=""
INSTRUMENTATION_STATUS=""
INSTRUMENTATION_TEST_COUNT=""
SERIAL=""
API_LEVEL=""
ABI=""
DEVICE_REPORT_SHA=""
DEVICE_REPORT_SIZE=""
DEVICE_REPORT_INSTRUMENTATION_OUTPUT_FILE=""
DEVICE_REPORT_LOGCAT_FILE=""
INSTRUMENTATION_OUTPUT_SHA=""
INSTRUMENTATION_OUTPUT_SIZE=""
LOGCAT_SHA=""
LOGCAT_SIZE=""
INSTRUMENTATION_CRASH_SIGNAL_COUNT=0
INSTRUMENTATION_FAILURE_SIGNAL_COUNT=0
CRASH_SIGNAL_COUNT=0
INSTALL_CRASH_SIGNAL_COUNT=0
ANR_SIGNAL_COUNT=0
FATAL_LITERT_SIGNAL_COUNT=0

if [[ -z "$DEVICE_REPORT_FILE" ]]; then
  add_failure "device-report-file-missing"
elif [[ ! -f "$DEVICE_REPORT_FILE" ]]; then
  add_failure "device-report-file-missing"
else
  DEVICE_STATUS="$(report_value "$DEVICE_REPORT_FILE" status)"
  INSTRUMENTATION_STATUS="$(report_value "$DEVICE_REPORT_FILE" instrumentation)"
  INSTRUMENTATION_TEST_COUNT="$(report_value "$DEVICE_REPORT_FILE" instrumentation_test_count)"
  SERIAL="$(report_value "$DEVICE_REPORT_FILE" serial)"
  API_LEVEL="$(report_value "$DEVICE_REPORT_FILE" api_level)"
  ABI="$(report_value "$DEVICE_REPORT_FILE" abi)"
  DEVICE_REPORT_SHA="$(sha256_file "$DEVICE_REPORT_FILE")"
  DEVICE_REPORT_SIZE="$(size_bytes "$DEVICE_REPORT_FILE")"
  DEVICE_REPORT_INSTRUMENTATION_OUTPUT_FILE="$(report_value "$DEVICE_REPORT_FILE" instrumentation_output_file)"
  DEVICE_REPORT_LOGCAT_FILE="$(report_value "$DEVICE_REPORT_FILE" logcat_file)"
  if [[ "$EXPLICIT_INSTRUMENTATION_OUTPUT_FILE" == "0" && -n "$DEVICE_REPORT_INSTRUMENTATION_OUTPUT_FILE" ]]; then
    INSTRUMENTATION_OUTPUT_FILE="$DEVICE_REPORT_INSTRUMENTATION_OUTPUT_FILE"
  fi
  if [[ "$EXPLICIT_LOGCAT_FILE" == "0" && -n "$DEVICE_REPORT_LOGCAT_FILE" ]]; then
    LOGCAT_FILE="$DEVICE_REPORT_LOGCAT_FILE"
  fi
  [[ "$DEVICE_STATUS" == "passed" ]] || add_failure "device-status-not-passed"
  [[ "$INSTRUMENTATION_STATUS" == "passed" ]] || add_failure "instrumentation-status-not-passed"
fi

if [[ -z "$INSTRUMENTATION_OUTPUT_FILE" ]]; then
  add_failure "instrumentation-output-file-missing"
elif [[ ! -f "$INSTRUMENTATION_OUTPUT_FILE" ]]; then
  add_failure "instrumentation-output-file-missing"
elif [[ ! -s "$INSTRUMENTATION_OUTPUT_FILE" ]]; then
  add_failure "instrumentation-output-file-empty"
else
  INSTRUMENTATION_OUTPUT_SHA="$(sha256_file "$INSTRUMENTATION_OUTPUT_FILE")"
  INSTRUMENTATION_OUTPUT_SIZE="$(size_bytes "$INSTRUMENTATION_OUTPUT_FILE")"
  INSTRUMENTATION_CRASH_SIGNAL_COUNT="$(count_instrumentation_crash_signals "$INSTRUMENTATION_OUTPUT_FILE")"
  INSTRUMENTATION_FAILURE_SIGNAL_COUNT="$(count_instrumentation_failure_signals "$INSTRUMENTATION_OUTPUT_FILE")"
  [[ "$INSTRUMENTATION_CRASH_SIGNAL_COUNT" == "0" ]] || add_failure "instrumentation-crash-signal-detected"
  [[ "$INSTRUMENTATION_FAILURE_SIGNAL_COUNT" == "0" ]] || add_failure "instrumentation-failure-signal-detected"
fi

if [[ -z "$LOGCAT_FILE" ]]; then
  add_failure "logcat-file-missing"
elif [[ ! -f "$LOGCAT_FILE" ]]; then
  add_failure "logcat-file-missing"
elif [[ ! -s "$LOGCAT_FILE" ]]; then
  add_failure "logcat-file-empty"
else
  LOGCAT_SHA="$(sha256_file "$LOGCAT_FILE")"
  LOGCAT_SIZE="$(size_bytes "$LOGCAT_FILE")"
  CRASH_SIGNAL_COUNT="$(count_logcat_crash_signals "$LOGCAT_FILE")"
  INSTALL_CRASH_SIGNAL_COUNT="$(count_logcat_install_crash_signals "$LOGCAT_FILE")"
  ANR_SIGNAL_COUNT="$(count_logcat_anr_signals "$LOGCAT_FILE")"
  FATAL_LITERT_SIGNAL_COUNT="$(count_logcat_litert_fatal_signals "$LOGCAT_FILE")"
  [[ "$CRASH_SIGNAL_COUNT" == "0" ]] || add_failure "crash-signal-detected"
  [[ "$INSTALL_CRASH_SIGNAL_COUNT" == "0" ]] || add_failure "install-crash-signal-detected"
  [[ "$ANR_SIGNAL_COUNT" == "0" ]] || add_failure "anr-signal-detected"
  [[ "$FATAL_LITERT_SIGNAL_COUNT" == "0" ]] || add_failure "fatal-litert-lm-signal-detected"
  if [[ "$CRASH_SIGNAL_COUNT" =~ ^[0-9]+$ && "$CRASH_SIGNAL_COUNT" -ge 2 ]]; then
    add_failure "crash-loop-signal-detected"
  fi
fi

NO_LAUNCH_CRASH="false"
NO_INSTALL_CRASH="false"
NO_CRASH_LOOP="false"
NO_FATAL_LITERT="false"
NO_REPRODUCIBLE_ANR="false"

if [[ "$INSTRUMENTATION_CRASH_SIGNAL_COUNT" == "0" && "$CRASH_SIGNAL_COUNT" == "0" ]]; then
  NO_LAUNCH_CRASH="true"
fi
if [[ "$INSTALL_CRASH_SIGNAL_COUNT" == "0" && "$DEVICE_STATUS" == "passed" ]]; then
  NO_INSTALL_CRASH="true"
fi
if [[ "$CRASH_SIGNAL_COUNT" =~ ^[0-9]+$ && "$CRASH_SIGNAL_COUNT" -lt 2 && "$INSTRUMENTATION_CRASH_SIGNAL_COUNT" == "0" ]]; then
  NO_CRASH_LOOP="true"
fi
NO_FATAL_LITERT="$(bool_for_zero "$FATAL_LITERT_SIGNAL_COUNT")"
NO_REPRODUCIBLE_ANR="$(bool_for_zero "$ANR_SIGNAL_COUNT")"

STATUS="passed"
REASON=""
if [[ "${#FAILURES[@]}" -gt 0 ]]; then
  STATUS="failed"
  REASON="$(IFS=,; printf '%s' "${FAILURES[*]}")"
fi

mkdir -p "$(dirname "$REPORT_FILE")"
{
  printf 'artifactSchema=CrashAnrSmokeEvidence/v1\n'
  printf 'status=%s\n' "$STATUS"
  printf 'target=crash-anr-smoke-evidence\n'
  printf 'owner=%s\n' "$EVIDENCE_OWNER"
  printf 'recordedAt=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'command=%s\n' "$(command_line)"
  printf 'reproduciblePath=%s\n' "$REPORT_FILE"
  printf 'reason=%s\n' "$REASON"
  printf 'operationsRecordField=crashAnrSmoke.evidence\n'
  printf 'operationsRecordFile=%s\n' "$OPERATIONS_RECORD_FILE"
  printf 'window=%s\n' "$WINDOW"
  printf 'track=%s\n' "$TRACK"
  printf 'packageName=%s\n' "$PACKAGE_NAME"
  printf 'deviceReportFile=%s\n' "$DEVICE_REPORT_FILE"
  printf 'deviceReportSha256=%s\n' "$DEVICE_REPORT_SHA"
  printf 'deviceReportSizeBytes=%s\n' "$DEVICE_REPORT_SIZE"
  printf 'deviceStatus=%s\n' "$DEVICE_STATUS"
  printf 'instrumentationStatus=%s\n' "$INSTRUMENTATION_STATUS"
  printf 'instrumentationTestCount=%s\n' "$INSTRUMENTATION_TEST_COUNT"
  printf 'serial=%s\n' "$SERIAL"
  printf 'apiLevel=%s\n' "$API_LEVEL"
  printf 'abi=%s\n' "$ABI"
  printf 'instrumentationOutputFile=%s\n' "$INSTRUMENTATION_OUTPUT_FILE"
  printf 'instrumentationOutputSha256=%s\n' "$INSTRUMENTATION_OUTPUT_SHA"
  printf 'instrumentationOutputSizeBytes=%s\n' "$INSTRUMENTATION_OUTPUT_SIZE"
  printf 'logcatFile=%s\n' "$LOGCAT_FILE"
  printf 'logcatSha256=%s\n' "$LOGCAT_SHA"
  printf 'logcatSizeBytes=%s\n' "$LOGCAT_SIZE"
  printf 'logcatAnalyzed=%s\n' "$([[ -n "$LOGCAT_SHA" ]] && printf true || printf false)"
  printf 'instrumentationCrashSignalCount=%s\n' "$INSTRUMENTATION_CRASH_SIGNAL_COUNT"
  printf 'instrumentationFailureSignalCount=%s\n' "$INSTRUMENTATION_FAILURE_SIGNAL_COUNT"
  printf 'crashSignalCount=%s\n' "$CRASH_SIGNAL_COUNT"
  printf 'installCrashSignalCount=%s\n' "$INSTALL_CRASH_SIGNAL_COUNT"
  printf 'anrSignalCount=%s\n' "$ANR_SIGNAL_COUNT"
  printf 'fatalLiteRtLmSignalCount=%s\n' "$FATAL_LITERT_SIGNAL_COUNT"
  printf 'noLaunchCrash=%s\n' "$NO_LAUNCH_CRASH"
  printf 'noInstallCrash=%s\n' "$NO_INSTALL_CRASH"
  printf 'noCrashLoop=%s\n' "$NO_CRASH_LOOP"
  printf 'noFatalNativeLiteRtLmFailure=%s\n' "$NO_FATAL_LITERT"
  printf 'noReproducibleAnr=%s\n' "$NO_REPRODUCIBLE_ANR"
  printf 'failureEvidencePolicy=%s\n' "$FAILURE_EVIDENCE_POLICY"
} > "$REPORT_FILE"

echo "Crash/ANR smoke evidence report: $REPORT_FILE"
if [[ "$STATUS" != "passed" ]]; then
  echo "Crash/ANR smoke evidence failed: $REASON" >&2
  exit 1
fi
