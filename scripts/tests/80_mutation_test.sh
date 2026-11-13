# shellcheck shell=bash
# Lightweight mutation-runner contract tests. The full campaign is explicitly opt-in.
python3 -I scripts/mutation_test.py --self-test || fail "mutation runner self-tests failed"
assert_file_contains_text scripts/mutation_test.py "copy_repository"
assert_file_contains_text scripts/mutation_test.py "Baseline test run failed"
assert_file_contains_text scripts/mutation_test.py "Surviving mutants"
assert_file_contains_text scripts/mutation_test.py "clean Git worktree"
assert_file_contains_text scripts/mutation_test.py "A Gradle process is already running"
