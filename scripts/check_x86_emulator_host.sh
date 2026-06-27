#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
EMULATOR_BIN="${ANDROID_EMULATOR:-${ANDROID_SDK}/emulator/emulator}"
REPORT_FILE="${REPORT_FILE:-build/verification/x86-emulator-host.properties}"
HOST_MACHINE="${HOST_MACHINE:-$(uname -m)}"
CPUINFO_FILE="${CPUINFO_FILE:-/proc/cpuinfo}"
KVM_DEVICE="${KVM_DEVICE:-/dev/kvm}"
EMULATOR_GUI_REQUIRED="${EMULATOR_GUI_REQUIRED:-0}"
MIN_EMULATOR_QT_GLIBC="${MIN_EMULATOR_QT_GLIBC:-2.30}"
ALLOW_X86_EMULATOR_INFRA_UNAVAILABLE="${ALLOW_X86_EMULATOR_INFRA_UNAVAILABLE:-0}"

usage() {
  cat >&2 <<'EOF'
Usage: scripts/check_x86_emulator_host.sh [options]

Options:
  --report <path>       Write the host readiness report to this path.
  --gui-required        Also require a glibc version suitable for emulator Qt UI.
  --no-gui-required     Check the headless x86_64 emulator path only.
  -h, --help            Show this help text.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --report)
      REPORT_FILE="${2:?missing report path}"
      shift 2
      ;;
    --gui-required)
      EMULATOR_GUI_REQUIRED=1
      shift
      ;;
    --no-gui-required)
      EMULATOR_GUI_REQUIRED=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 2
      ;;
  esac
done

detect_glibc_version() {
  if command -v getconf >/dev/null 2>&1; then
    getconf GNU_LIBC_VERSION 2>/dev/null | awk '{print $NF; exit}'
    return
  fi
  ldd --version 2>&1 | awk 'NR == 1 {print $NF; exit}' || true
}

version_ge() {
  awk -v have="$1" -v need="$2" '
    BEGIN {
      split(have, h, ".")
      split(need, n, ".")
      for (i = 1; i <= 3; i++) {
        hv = h[i] + 0
        nv = n[i] + 0
        if (hv > nv) exit 0
        if (hv < nv) exit 1
      }
      exit 0
    }
  '
}

join_csv() {
  local IFS=,
  printf '%s' "$*"
}

GLIBC_VERSION="${GLIBC_VERSION:-$(detect_glibc_version)}"
HOST_IS_X86_64=false
CPU_VIRTUALIZATION_FLAG_PRESENT=false
KVM_DEVICE_EXISTS=false
KVM_DEVICE_ACCESSIBLE=false
EMULATOR_EXISTS=false
GLIBC_GUI_READY=false
REASONS=()

case "$HOST_MACHINE" in
  x86_64|amd64)
    HOST_IS_X86_64=true
    ;;
  *)
    REASONS+=("host-not-x86_64")
    ;;
esac

if [[ -r "$CPUINFO_FILE" ]] && grep -Eq '(^|[[:space:]])(vmx|svm)([[:space:]]|$)' "$CPUINFO_FILE"; then
  CPU_VIRTUALIZATION_FLAG_PRESENT=true
else
  REASONS+=("cpu-virtualization-flag-missing")
fi

if [[ -e "$KVM_DEVICE" ]]; then
  KVM_DEVICE_EXISTS=true
  if [[ -r "$KVM_DEVICE" && -w "$KVM_DEVICE" ]]; then
    KVM_DEVICE_ACCESSIBLE=true
  else
    REASONS+=("kvm-device-not-accessible")
  fi
else
  REASONS+=("kvm-device-missing")
fi

if [[ -x "$EMULATOR_BIN" ]]; then
  EMULATOR_EXISTS=true
else
  REASONS+=("emulator-binary-missing")
fi

if [[ -n "$GLIBC_VERSION" ]] && version_ge "$GLIBC_VERSION" "$MIN_EMULATOR_QT_GLIBC"; then
  GLIBC_GUI_READY=true
fi

if [[ "$EMULATOR_GUI_REQUIRED" == "1" && "$GLIBC_GUI_READY" != "true" ]]; then
  REASONS+=("gui-glibc-too-old")
fi

status="passed"
exit_code=0
if [[ "${#REASONS[@]}" -gt 0 ]]; then
  status="failed"
  exit_code=1
  if [[ "$ALLOW_X86_EMULATOR_INFRA_UNAVAILABLE" == "1" ]]; then
    status="skipped"
    exit_code=0
  fi
fi

mkdir -p "$(dirname "$REPORT_FILE")"
{
  printf 'status=%s\n' "$status"
  printf 'target=x86-emulator-host\n'
  printf 'reason=%s\n' "$(join_csv "${REASONS[@]:-}")"
  printf 'androidSdk=%s\n' "$ANDROID_SDK"
  printf 'emulator=%s\n' "$EMULATOR_BIN"
  printf 'hostMachine=%s\n' "$HOST_MACHINE"
  printf 'hostIsX86_64=%s\n' "$HOST_IS_X86_64"
  printf 'cpuinfoFile=%s\n' "$CPUINFO_FILE"
  printf 'cpuVirtualizationFlagPresent=%s\n' "$CPU_VIRTUALIZATION_FLAG_PRESENT"
  printf 'kvmDevice=%s\n' "$KVM_DEVICE"
  printf 'kvmDeviceExists=%s\n' "$KVM_DEVICE_EXISTS"
  printf 'kvmDeviceAccessible=%s\n' "$KVM_DEVICE_ACCESSIBLE"
  printf 'glibcVersion=%s\n' "$GLIBC_VERSION"
  printf 'emulatorGuiRequired=%s\n' "$EMULATOR_GUI_REQUIRED"
  printf 'minEmulatorQtGlibc=%s\n' "$MIN_EMULATOR_QT_GLIBC"
  printf 'glibcGuiReady=%s\n' "$GLIBC_GUI_READY"
  printf 'allowInfraUnavailable=%s\n' "$ALLOW_X86_EMULATOR_INFRA_UNAVAILABLE"
} > "$REPORT_FILE"

if [[ "$status" == "passed" ]]; then
  echo "x86 emulator host readiness passed."
elif [[ "$status" == "skipped" ]]; then
  echo "x86 emulator host readiness skipped: $(join_csv "${REASONS[@]}")" >&2
else
  echo "x86 emulator host readiness failed: $(join_csv "${REASONS[@]}")" >&2
fi
echo "x86 emulator host report: $REPORT_FILE"
exit "$exit_code"
