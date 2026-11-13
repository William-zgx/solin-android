# Mutation testing pilot

`scripts/mutation_test.py` provides a small, deterministic mutation-testing pilot for the pure-JVM
`persistedEnumOrNull` and `StrictJsonScan` paths. It does not change production behavior or run against
an online model.

## Safety and execution contract

- The Git worktree must be clean and no other Gradle process may be active. A per-repository lock
  covers the complete copy/test/report campaign.
- Tests run against a disposable copy under `/tmp`; primary sources are never mutated in place.
  Top-level generated `build` outputs are excluded, while nested source packages named `build` remain.
- The unmodified baseline must pass before any mutant counts. A baseline failure is not a killed mutant.
- Each mutant replaces exactly one semantic expression. Missing or duplicate patterns fail closed.
- Every surviving mutant is a failure. The JSON report records status, UTC time, Git HEAD, baseline,
  killed, survived and each result; infrastructure failures are not kills.
- Real and fake test launches share one process lifecycle. Timeout cleanup uses bounded SIGTERM/SIGKILL
  grace periods rather than waiting indefinitely.
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

The current campaign proves the focused tests kill these mutants without deriving the JSON boundary
fixture from the mutable production constant. On 2026-11-13 the hardened runner at
`b435164f02ae042fe1945016385af2bb145c28c4` completed with **baseline passed, 6 killed and 0 survivors**;
see `build/verification/mutation/mutation-report.json` (`SolinMutationReport/v2`, recorded at
`2026-11-13T13:21:38.304335+00:00`). Six lightweight runner self-tests also passed.
Expanding the pilot to trace
receipt/placement recovery and egress decisions requires additional deterministic fixtures; it should not
be used as an excuse to weaken fail-closed or confirmation rules merely to make mutants pass.
