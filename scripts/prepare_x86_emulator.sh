#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

export REQUIRED_APIS="${REQUIRED_APIS:-36}"
export EMULATOR_API_MATRIX_TAG="${EMULATOR_API_MATRIX_TAG:-google_apis}"
export EMULATOR_API_MATRIX_ABI="${EMULATOR_API_MATRIX_ABI:-x86_64}"
export AVD_NAME_PREFIX="${AVD_NAME_PREFIX:-pocketmind_api}"
export REPORT_FILE="${REPORT_FILE:-build/verification/x86-emulator-prepare.properties}"

scripts/prepare_emulator_api_matrix.sh "$@"
