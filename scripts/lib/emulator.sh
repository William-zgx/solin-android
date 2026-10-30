#!/usr/bin/env bash
# Shared adb/emulator helpers for the emulator and device verification scripts.
# Source after ROOT_DIR is set:  source "$ROOT_DIR/scripts/lib/emulator.sh"
#
# Callers provide these globals (read at call time, not at source time):
#   ADB_BIN                      path to adb
#   ADB                          array, e.g. ("$ADB_BIN" -s "$SELECTED_SERIAL")
#   SELECTED_SERIAL              serial chosen by the caller's select_emulator_once
#   BOOT_TIMEOUT_SECONDS         wait_for_boot_completed deadline
#   SOLIN_ACCESSIBILITY_SERVICE  component checked by solin_accessibility_enabled
#   fail <target> <reason> <message...>   caller-defined failure reporter
#
# Scripts that need different behaviour define their own function after sourcing
# this file; the later definition wins.

device_state_for() {
  local serial="$1"
  "$ADB_BIN" devices | awk -v serial="$serial" '$1 == serial {print $2; found = 1} END {if (!found) print ""}'
}

authorized_emulators() {
  "$ADB_BIN" devices | awk 'NR > 1 && $1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}'
}

emulator_avd_name() {
  "${ADB[@]}" emu avd name 2>/dev/null |
    tr -d '\r' |
    awk 'NF && $0 != "OK" {value = $0} END {print value}'
}

wait_for_boot_completed() {
  local deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
  while true; do
    local state boot_completed
    state="$(device_state_for "$SELECTED_SERIAL")"
    boot_completed="$("$ADB_BIN" -s "$SELECTED_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    if [[ "$state" == "device" && "$boot_completed" == "1" ]]; then
      return
    fi
    [[ "$SECONDS" -lt "$deadline" ]] ||
      fail emulator-boot emulator-boot-timeout "Timed out waiting for $SELECTED_SERIAL to finish booting."
    sleep 2
  done
}

solin_accessibility_enabled() {
  local dump enabled_section crashed_section
  dump="$("${ADB[@]}" shell dumpsys accessibility 2>/dev/null | tr -d '\r')"
  enabled_section="$(awk '
    /Enabled services:/ {printing = 1}
    printing {print}
    /Binding services:/ {printing = 0}
    /Crashed services:/ {printing = 0}
  ' <<<"$dump")"
  crashed_section="$(awk '
    /Crashed services:/ {printing = 1}
    printing {print}
    /Client list info:/ {printing = 0}
  ' <<<"$dump")"
  grep -Fq "$SOLIN_ACCESSIBILITY_SERVICE" <<<"$enabled_section" &&
    ! grep -Fq "$SOLIN_ACCESSIBILITY_SERVICE" <<<"$crashed_section"
}
