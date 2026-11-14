# shellcheck shell=bash
# Synthetic privacy-utility contracts; no network, model, or device execution.
python3 -I lab/scripts/test_privacy_utility.py || fail "privacy utility self-tests failed"
python3 -I lab/scripts/privacy_utility.py --check \
  --output "$ROOT_DIR/build/verification/privacy-utility/summary.json" >/dev/null || fail "privacy utility check failed"
assert_file_contains_text "$ROOT_DIR/build/verification/privacy-utility/summary.json" '"modelEvidence": false'
assert_file_contains_text "$ROOT_DIR/build/verification/privacy-utility/summary.json" '"branchSteeringFailures": 0'
