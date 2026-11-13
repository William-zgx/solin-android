#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Fake-SDK contract tests for the validation scripts. The cases live in scripts/tests/ and are
# sourced in this order into one shell: later parts rely on helpers, fakes and fixtures set up
# by earlier parts (00_harness.sh first). Keep the order when adding or moving cases.
VALIDATION_TEST_PARTS=(
  00_harness.sh
  10_verify_local_contracts.sh
  20_ai_behavior_eval.sh
  30_release_preflight_and_capabilities.sh
  40_perf_baseline_and_release_record.sh
  50_store_policy_record.sh
  51_release_operations_record.sh
  52_release_validation_record.sh
  60_release_flow_evidence.sh
  61_emulator_api_matrix.sh
  70_privacy.sh
  71_model_license.sh
  72_android_artifact_scan.sh
  73_release_gate_and_signing.sh
  80_mutation_test.sh
  80_perf_collection.sh
  81_app_search_eval.sh
  90_install_and_test_device.sh
  91_emulator_verifiers.sh
  92_regression_emulator.sh
)
for validation_test_part in "${VALIDATION_TEST_PARTS[@]}"; do
  # shellcheck source=/dev/null
  source "$ROOT_DIR/scripts/tests/$validation_test_part"
done

bash "$ROOT_DIR/scripts/tests/test_background_phone_helper.sh"

echo "Validation script tests passed."
