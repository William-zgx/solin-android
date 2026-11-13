# Mutation testing pilot

`scripts/mutation_test.py` provides a small, deterministic mutation-testing pilot for the pure-JVM
`persistedEnumOrNull` and `StrictJsonScan` paths. It does not change production behavior or run against
an online model.

## Safety and execution contract

- The Git worktree must be clean and no other Gradle process may be active.
- Tests run against a disposable copy under `/tmp`; primary sources are never mutated in place.
- The unmodified baseline must pass before any mutant counts. A baseline failure is not a killed mutant.
- Each mutant replaces exactly one semantic expression. Missing or duplicate patterns fail closed.
- Every surviving mutant is a failure. The JSON report records baseline, killed, survived and each result.
- The campaign requires JDK 21 through `JAVA_HOME` and Android SDK through `ANDROID_SDK_ROOT` or
  `ANDROID_HOME`. It never runs concurrently with another Gradle process.

Run the lightweight runner tests with `python3 -I scripts/mutation_test.py --self-test`. These also run
inside `scripts/test_validation_scripts.sh` and the normal local verification gate.

Run the actual campaign explicitly:

```bash
JAVA_HOME=/path/to/jdk-21 \
ANDROID_SDK_ROOT=/path/to/android-sdk \
ANDROID_HOME=/path/to/android-sdk \
python3 -I scripts/mutation_test.py --run
```

The report is written to `build/verification/mutation/mutation-report.json`. This build output is not a
release artifact or device evidence. The campaign is currently opt-in because each mutant needs a separate
focused Gradle run; it is not part of `verify_local.sh`. Successful mutation evidence does not replace
paired device benchmarks, migration tests, privacy review or release approval.

## Current targets

- Null vs thrown/unknown enum values, case sensitivity, empty enum values.
- Strict JSON trailing content, object guards and depth-boundary rejection.

The first campaign proves the existing focused tests can kill these mutants. On 2026-11-13 the actual
run killed **6 mutants with 0 survivors**; see `build/verification/mutation/mutation-report.json`.
Expanding the pilot to trace
receipt/placement recovery and egress decisions requires additional deterministic fixtures; it should not
be used as an excuse to weaken fail-closed or confirmation rules merely to make mutants pass.
