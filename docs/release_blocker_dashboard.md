# Release Blocker Dashboard

Generated from `docs/roadmap_gap_matrix.json` and `docs/release_readiness.md` only. This is a compact local evidence view; it does not resolve device, performance, signing, store, legal, privacy, or release-owner blockers.

- Roadmap updated: `2026-06-29`
- Policy: This ledger tracks roadmap completion evidence. Local verifier evidence never replaces physical device, emulator, production signing, performance, store, security, legal, or release-owner approval evidence.

## Active Blockers

The table preserves the dated roadmap ledger; it does not claim a new review of
every historical phase. Current owner requirements are listed below and in
[release readiness](release_readiness.md). Deferred entries remain in this table.

| Blocker | Status | Owner / phase | Blocking evidence | Next evidence |
| --- | --- | --- | --- | --- |
| Real-app search replay coverage | partial | Agent 2 / Phase 2 | physical real app search eval deferred | Continue adding replay fixtures for changing Taobao, Gaode, JD, and browser search surfaces; verify a fresh physical real-app-search pass rate later. |
| Real-app search physical pass rate | deferred | Agent 2 / Phase 2 | no device test in this phase | Run fresh real-app-search eval on arm64 physical hardware and reach the target pass rate without expanding high-risk automation. |
| Agent behavior actual runtime trace | partial | Agent 4 / Phase 3 | fresh agent loop runtime trace not collected in this phase | Collect a fresh agent_loop_runtime actual trace in public-strict mode and keep traceDiffAllowedFailureCount at zero with runtime provenance. |
| Privacy / store / release approvals | partial | Agent 6 / Phase 4 | release owner evidence required, store policy owner evidence required, privacy security legal approval required, model license approval required | Use the preflight fields in verifier reports to fill real owners, reviewers, public policy URLs, approvals, signing identity, artifact SHA values, and approved evidence files. Ref... |
| Physical validation and arm64 API matrix | deferred | Agent 3 / Phase 4 | no device test in this phase, arm64 api matrix environment required | Run arm64 physical validation, prepare API 28/32/33/34 arm64 AVDs, and collect accepted validation reports. Deferred-mode reports carry deferredReason=no-device-test-in-this-phase... |
| Perf baseline on physical arm64 | deferred | Agent 5 / Phase 4 | no device test in this phase, physical arm64 performance required | Collect RC model load, first token, tokens/s, memory, ANR/OOM, and GPU fallback evidence on physical arm64 hardware. |
| Real model runtime validation | partial | Agent 5 / Phase 5 | real model runtime validation deferred | Optionally run the targeted JVM slice and later collect real model loading/performance evidence on physical arm64 hardware. |
| Public release approvals, signing, validation, perf baseline | blocked | Agent 0 / Phase 6 | human approval required, production signing required, physical validation required, perf baseline required | Complete production signing, approved release records, store/privacy/legal/model-license reviews, validation record, operations record, public AAB, mapping, and final public relea... |

## Human Approval

- **Release approvals**: Final owner, reviewer, channel, changelog, release notes, artifact SHA, signing fingerprint, evidence links, and blocker decisions in `release_record.json`.
- **Store approvals**: Real support contact, public privacy URL, Data safety answers, listing/screenshots, and approval in `store_policy_record.json`.
- **Operations owner**: Crash/ANR monitoring, rollout thresholds, first-day watcher, rollback and CI evidence in `release_operations_record.json`.
- **Validation owner**: Approved artifact-bound emulator, physical, API matrix, manual, release-flow, screenshot, and performance records in `release_validation_record.json`.
- **Privacy approvals**: Review the current privacy notice and capability matrix, including opt-in remote vision and the debug background backend.
- **Model/license approvals**: License source, attribution, notice, and redistribution decisions for every recommended model.
- **Signing owner**: Production signing material outside Git, upload/app-signing fingerprints, final APK/AAB and mapping provenance.
- **CI / emulator owner**: Runnable API 28/32/33/34/36 arm64 infrastructure and complete passed nested reports.
- **AI behavior owner**: Fresh public-strict `agent_loop_runtime` trace for the current fixtures and hashes, with no mismatches or unresolved allowed failures.
- **Phone capability owner**: Valid paired task benchmark, real NumberPicker readback, background critical-control negative fixture and complete model-task acceptance.
- **Release / model owner**: Any external handoff of bundled-model tester APKs needs model redistribution approval and separate split-package/install provenance.

## Physical Hardware

- **Physical validation**: Final signed-RC instrumentation, retained-data upgrade, system UI/manual acceptance, and fresh real-app evidence on arm64 hardware.
- **Perf baseline**: First launch, model load, first token, throughput, memory, ANR/OOM, GPU fallback, and 50k memory metrics on physical arm64 against the signed RC.
- **Real-app search**: Run fresh real-app-search eval on arm64 physical hardware and reach the target pass rate without expanding high-risk automation.

## Next Commands

Run these preflight checks from the repository root. Pending owner approvals
are expected to fail closed; a documentation refresh does not approve them.

- `VERIFY_AI_BEHAVIOR_EVAL=0 VERIFY_PERF_BASELINE=0 VERIFY_RELEASE_RECORD=1 scripts/verify_release_gate.sh`
- `VERIFY_AI_BEHAVIOR_EVAL=0 VERIFY_PERF_BASELINE=0 VERIFY_STORE_POLICY=1 scripts/verify_release_gate.sh`
- `VERIFY_AI_BEHAVIOR_EVAL=0 VERIFY_PERF_BASELINE=0 VERIFY_PRIVACY_REVIEW=1 scripts/verify_release_gate.sh`
- `VERIFY_AI_BEHAVIOR_EVAL=0 VERIFY_PERF_BASELINE=0 VERIFY_MODEL_LICENSES=1 scripts/verify_release_gate.sh`
- `scripts/check_emulator_api_matrix.sh`

For operations requiring device selection, signing or measured inputs, use the
complete commands in the owner guides rather than copying incomplete templates:

- [Device acceptance](phone_acceptance.md): emulator preparation, installation and real-app evaluation.
- [RC performance collection](phone_acceptance.md#rc-性能基线): `collect_rc_perf_from_device.sh` with a physical device and final RC artifact.
- [Release checklist](release_checklist.md): signing, evidence records and the final public-release gate.
