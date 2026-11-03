#!/usr/bin/env bash
# Explicit development-only provisioning. Requires an already authorized ADB device.
# Never enables debugging, grants settings permissions, unlocks a phone or injects input.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ANDROID_SERIAL=<serial> scripts/start_background_phone_helper.sh [package]
Default package: com.bytedance.zgx.solin
Install a debuggable APK containing BackgroundPhoneHelper first. Keep this terminal
open, then explicitly enable experimental background control in Solin. Authorization
expires after 15 minutes; stopping the session destroys its display. API 31+ required;
launching apps on an independently focused background display requires API 34+.
USAGE
}
if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then usage; exit 0; fi
if [[ $# -gt 1 ]]; then usage >&2; exit 2; fi
package="${1:-com.bytedance.zgx.solin}"
if [[ ! "$package" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]]; then
  echo 'Invalid package name' >&2; exit 2
fi
adb_bin="${ADB:-adb}"
command -v "$adb_bin" >/dev/null
command -v openssl >/dev/null
command -v python3 >/dev/null
adb_call() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    "$adb_bin" -s "$ANDROID_SERIAL" "$@"
  else
    "$adb_bin" "$@"
  fi
}
# get-state fails for multiple attached devices rather than selecting one implicitly.
[[ "$(adb_call get-state)" == device ]]
api="$(adb_call shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$api" =~ ^[0-9]+$ && "$api" -ge 31 ]] || { echo 'Android 12+ required' >&2; exit 1; }
uid="$(adb_call shell run-as "$package" id -u | tr -d '\r')"
[[ "$uid" =~ ^[0-9]+$ && "$uid" -ge 10000 ]] || { echo 'Install a debuggable Solin APK first' >&2; exit 1; }
apk="$(adb_call shell pm path "$package" | tr -d '\r' | sed -n 's/^package:\(.*\/base\.apk\)$/\1/p')"
# Only an installed APK path can reach CLASSPATH; reject shell metacharacters and split results.
[[ "$apk" =~ ^/data/app/[A-Za-z0-9_./=+~-]+/base\.apk$ ]] || { echo 'Could not resolve installed base APK' >&2; exit 1; }
token="$(openssl rand -hex 32)"
device_seconds="$(adb_call shell date +%s | tr -d '\r')"
[[ "$device_seconds" =~ ^[0-9]+$ ]] || { echo 'Could not read device time' >&2; exit 1; }
expires="$((device_seconds * 1000 + 15 * 60 * 1000))"
auth_path='no_backup/background-phone-session.json'
cleanup() { adb_call shell "run-as $package rm -f $auth_path" >/dev/null 2>&1 || true; }
trap cleanup EXIT
echo 'Experimental helper starting; waiting for its loopback listener.'
# The helper chooses an unused loopback port. Only a port is printed; the key stays on stdin.
printf '%s\n%s\n' "$token" "$expires" |
  adb_call shell "CLASSPATH=$apk app_process /system/bin com.bytedance.zgx.solin.device.background.BackgroundPhoneHelper $uid" |
  {
    provisioned=0
    while IFS= read -r line; do
      line="${line%$'\r'}"
      if [[ "$line" =~ ^PORT=([0-9]+)$ && "$provisioned" == 0 ]]; then
        port="${BASH_REMATCH[1]}"
        [[ "$port" -ge 1024 && "$port" -le 65535 ]] || exit 1
        printf '{"port":%s,"uid":%s,"token":"%s","expiresAtMs":%s}\n' "$port" "$uid" "$token" "$expires" |
          adb_call shell "run-as $package sh -c 'umask 077; mkdir -p no_backup; cat > $auth_path'"
        provisioned=1
        echo 'Helper ready. Explicitly enable background control in the app; authorization expires in 15 minutes.'
      fi
    done
    [[ "$provisioned" == 1 ]] || { echo 'Helper did not report a ready loopback listener' >&2; exit 1; }
  }
