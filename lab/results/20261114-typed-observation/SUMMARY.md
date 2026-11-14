# Typed observation saved-output replay — 2026-11-14

## Evidence boundary

This is a **replanner-level paired replay** of the checked-in `20261108` observation-step outputs. The new
`TypedObservationCandidateReplanner` runs on the same 46 fixtures and replaces only its high-confidence
answers; every other saved model answer is unchanged. No new model, network, emulator, or device run was
performed. The production coordinator already handles verified-search completion separately, so this is
not a complete production-path or phone acceptance result.

The machine-readable report is [`replay-summary.json`](replay-summary.json).

## Results

| model | baseline correct | replay correct | fixed | broken | handled cases | model calls avoided |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| E2B | 17/46 | 30/46 | 13 | 0 | 16 | 21 |
| E4B | 23/46 | 30/46 | 7 | 0 | 16 | 23 |

All 16 typed emissions also matched the labeled gold answers in the independent gold sweep. The replay
reports every fixed case ID and the empty broken-case list. Model-call savings count the saved model calls
that would be skipped when the typed candidate runs before the model replanner; they are not wall-clock or
energy measurements.

## Reproduce

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.bytedance.zgx.solin.lab.TypedObservationCandidateSuiteTest" \
  --tests "com.bytedance.zgx.solin.lab.TypedObservationReplayTest"
```

The test also writes `build/verification/typed-observation/replay-summary.json`; it never rewrites the
historical `20261108` outputs.
