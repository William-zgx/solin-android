# shellcheck shell=bash
# LiteRT shim output and cache-lifecycle regressions; stdlib only, no model or network.
python3 -I "$ROOT_DIR/lab/scripts/test_litert_shim.py" || fail "litert shim self-tests failed"
