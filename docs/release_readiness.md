# Release Readiness

Code baseline for this readiness refresh: `b435164f02ae042fe1945016385af2bb145c28c4`
(2026-11-13). The full local gate for that integrated tree passed; the latest recorded hosted full CI
remains the earlier `cb7e4918` run. This page is not the release checklist or evidence log.

- Use [the release checklist](release_checklist.md) to execute a release candidate.
- Use [the current evidence index](validation_report.md#当前证据索引) for the current local gate and
  [the latest recorded full hosted CI evidence](validation_report.md#2026-11-04--main-ci-convergence),
  plus the append-only validation report for dated commands, APK hashes, results, and known gaps.
- Use the JSON records in `docs/` for machine-readable owner decisions and approvals.
- [The blocker dashboard](release_blocker_dashboard.md) is generated from this page
  and `roadmap_gap_matrix.json`; regenerate it with
  `scripts/generate_release_blocker_dashboard.sh > docs/release_blocker_dashboard.md`.

## Status

Solin is suitable for internal development, lab validation, and targeted tester
builds. It is not ready for broad public distribution. Mainline CI success proves
the checks that actually ran; it does not approve production signing, physical
acceptance, model redistribution, or public release.

## Complete Foundations

- The app's MIT license and Mika-derived Apache-2.0 source notices are present;
  [NOTICE](../NOTICE) identifies the adapted phone-operation code. Model licenses
  and redistribution permissions are reviewed separately.
- Recommended model metadata pins upstream revisions, byte sizes, and SHA-256;
  downloads are verified before registration. A verified file is not proof of a
  successful device runtime probe.
- Chat, memory, tool results, and screen evidence remain `LocalOnly` by default;
  remote sends use explicit eligible paths and redacted audit records. API keys
  use Android Keystore-backed encrypted preferences.
- Registry-driven tool execution retains schemas, risk, permissions, continuation
  policy, private-output metadata, and runtime authorization. Critical on-screen
  controls remain user-operated in both confirmation modes.
- Phone operations include input readback, bounded tree-stability waiting,
  `ui_collect`, `ui_find`, `ui_set_wheel`, and session-only verified route reuse.
  Composite children are authorized separately; route reuse carries no old consent.
- Debug builds have an explicitly provisioned experimental background-display
  path. Local loopback HMAC authentication, expiring sessions, and persistent
  background-run markers prevent silent fallback to the main display. Background
  remote vision and main-screen MediaProjection OCR are unavailable.
- Remote calls recheck the stop generation after registration, covering a stop
  during call creation; canceled generations do not retry that request.
- Privacy, release, validation, operations, store-policy, and model-license
  records and verifiers exist. Their presence does not turn pending reviews into
  approvals.
- Internal `bundledModels` packaging supports a base APK plus four modelpack
  splits. Ordinary public APK/AAB artifacts do not bundle model files.

Current implementation ownership and limitations live in
[the core-module reference](agent_core_modules.md),
[the phone capability record](plans/mika-phone-capabilities.md), and
[the privacy notice](privacy_notice.md).

## Current Evidence

| Area | Current evidence | Caveat |
| --- | --- | --- |
| Current local gate | `b435164f` passed `scripts/verify_local.sh`, including script regression, 2981 JVM tests (13 skipped), Lint, debug/release builds and artifact checks; `scripts/privacy_scan.sh` passed. The hardened mutation campaign passed its baseline and killed 6/6 mutants. | Local JVM/offline evidence is not hosted CI, emulator, physical-device, OEM, thermal or release approval evidence. |
| Mainline CI | [Android Verification 41699276903](https://github.com/William-zgx/solin-android/actions/runs/41699276903) passed on `cb7e4918`: `verify` ran `scripts/verify_local.sh`, including script regression, JVM tests, Lint, builds, and artifact checks. | A push run does not execute the protected signing, release archive, or final public-release jobs. |
| JVM and Lint | The matching local fix gate passed 2688 JVM tests with zero failures/errors/skips and Lint with zero errors. The registration-race regression failed on the previous implementation and passed after the fix. | This count is evidence for that commit, not a permanent minimum or device-behavior claim. |
| AI behavior fixtures | CI fixture/boundary validation passed 40 cases across 7 categories and 6 MVP scenarios. | Fixture validation is not a fresh public-strict runtime trace or live-model task benchmark. |
| Hosted emulator | The top-level regression report has `status=skipped`, `failedTarget=emulator-infra`, `reason=emulator-infra-hvf-unsupported`; its nested emulator report failed at `emulator-startup` / `process-exited`. | No hosted device regression passed in that run despite the job's green status. API matrix and model-provenance jobs were not run by this push. |
| Local API 36 increment | Six tests actually executed with zero skips: input readback including Chinese/whitespace/newlines, stable wait, partial collection, find without click, denied scrolling, and background-default refusal. | Debug evidence covers the tested APKs in `validation_report.md`; it is not full emulator regression or final signed-RC physical validation. |
| Background display | API 36 debug checks verified create, Settings launch, observe, guarded Apps tap with page change, capture, about 38 seconds idle, selection surviving return to chat, and explicit stop cleanup. | Background critical-control negative fixtures, physical/OEM focus, lock/expiry behavior, and full model-driven tasks remain pending. |
| Phone task benchmark | Preparation/failure evidence is preserved. | No valid same-model paired baseline was obtained; no task-success or performance improvement is established. |
| Physical device and perf | Collectors and verifiers exist; historical device evidence remains in the log. | Current signed-RC physical instrumentation, real-app 50-task evidence, performance, and complete API 28/32/33/34/36 evidence are still required. |
| Bundled model package | Build/install tooling exists for same-signature split APKs with `adb install-multiple --no-incremental -r`. | Internal lab evidence does not authorize external redistribution or Play release. |
| Store/privacy/license/operations | Machine records remain pending. | Support contact, public policy URL, owners, approvals, production signing, and final artifact-bound records are not supplied by CI. |

## Remaining release blockers by ownership

The four-column table below is also input to the dashboard generator. Approval
states describe missing evidence, not automatic permission to ship.

| Status | Owner | Item | Gate / evidence |
| --- | --- | --- | --- |
| Owner evidence required | Release owner | Final owner, reviewer, channel, changelog, release notes, artifact SHA, signing fingerprint, evidence links, and blocker decisions in `release_record.json`. | `VERIFY_AI_BEHAVIOR_EVAL=0 VERIFY_PERF_BASELINE=0 VERIFY_RELEASE_RECORD=1 scripts/verify_release_gate.sh`; final public release binds the current commit and artifact. |
| Manual approval required | Store / policy owner | Real support contact, public privacy URL, Data safety answers, listing/screenshots, and approval in `store_policy_record.json`. | `VERIFY_AI_BEHAVIOR_EVAL=0 VERIFY_PERF_BASELINE=0 VERIFY_STORE_POLICY=1 scripts/verify_release_gate.sh`. |
| Owner evidence required | Operations owner | Crash/ANR monitoring, rollout thresholds, first-day watcher, rollback and CI evidence in `release_operations_record.json`. | `VERIFY_AI_BEHAVIOR_EVAL=0 VERIFY_PERF_BASELINE=0 VERIFY_RELEASE_OPERATIONS=1 scripts/verify_release_gate.sh`. |
| Owner evidence required | Validation owner | Approved artifact-bound emulator, physical, API matrix, manual, release-flow, screenshot, and performance records in `release_validation_record.json`. | `VERIFY_AI_BEHAVIOR_EVAL=0 VERIFY_PERF_BASELINE=0 VERIFY_RELEASE_VALIDATION=1 scripts/verify_release_gate.sh`; skips cannot fill passed evidence slots. |
| Manual/legal approval required | Release, security, legal | Review the current privacy notice and capability matrix, including opt-in remote vision and the debug background backend. | `VERIFY_AI_BEHAVIOR_EVAL=0 VERIFY_PERF_BASELINE=0 VERIFY_PRIVACY_REVIEW=1 scripts/verify_release_gate.sh`; candidate hash refresh is not approval. |
| Manual/legal approval required | Model/license reviewer | License source, attribution, notice, and redistribution decisions for every recommended model. | `VERIFY_AI_BEHAVIOR_EVAL=0 VERIFY_PERF_BASELINE=0 VERIFY_MODEL_LICENSES=1 scripts/verify_release_gate.sh`. |
| Owner evidence required | Signing owner | Production signing material outside Git, upload/app-signing fingerprints, final APK/AAB and mapping provenance. | Protected signing and `PUBLIC_RELEASE=1 EXPECTED_SIGNING_CERT_SHA256=<production-upload-cert-sha256> scripts/verify_release_gate.sh`. |
| Physical hardware required | Device validation owner | Final signed-RC instrumentation, retained-data upgrade, system UI/manual acceptance, and fresh real-app evidence on arm64 hardware. | `APP_APK_MODE=release APP_APK=<signed-release.apk> RELEASE_ARTIFACT_TYPE=apk RELEASE_ARTIFACT_SHA256=<apk-sha> RESET_APP_DATA_AFTER_TESTS=0 scripts/install_and_test_device.sh`. |
| Owner evidence required | CI / emulator owner | Runnable API 28/32/33/34/36 arm64 infrastructure and complete passed nested reports. | `scripts/check_emulator_api_matrix.sh`, `scripts/prepare_emulator_api_matrix.sh`, then `scripts/regression_emulator_api_matrix.sh`. |
| Physical hardware required | Performance owner | First launch, model load, first token, throughput, memory, ANR/OOM, GPU fallback, and 50k memory metrics on physical arm64 against the signed RC. | `RELEASE_ARTIFACT=<signed-rc> APP_VERSION=<version> scripts/collect_rc_perf_from_device.sh`, then `scripts/verify_perf_baseline.sh`. |
| Owner evidence required | AI behavior owner | Fresh public-strict `agent_loop_runtime` trace for the current fixtures and hashes, with no mismatches or unresolved allowed failures. | `PUBLIC_RELEASE_CONTEXT=1 scripts/collect_ai_behavior_actual_trace.sh`. |
| Owner evidence required | Phone capability owner | Valid paired task benchmark, real NumberPicker readback, background critical-control negative fixture and complete model-task acceptance. | Follow `phone_acceptance.md`; retain failed and skipped cases separately from successful positive-path checks. |
| Manual/legal approval required | Release / model owner | Any external handoff of bundled-model tester APKs needs model redistribution approval and separate split-package/install provenance. | Model-license gate, `scripts/package_bundled_models.sh`, and device evidence for all five APKs and verified models. |

## Architecture And Follow-up Work

Architecture debt and implementation status are owned by
[architecture_depth.md](architecture_depth.md),
[optimization_plan_weaknesses.md](optimization_plan_weaknesses.md), and the
individual [plans](plans/). Those plans are not release approvals. In particular,
the phone capability implementation does not close its outstanding device or
paired-benchmark acceptance requirements.

## Next Commands

Use JDK 21 and SDK 36 to match CI. Prepare authorized devices and the required
records before running commands that install, sign, or collect evidence. The
canonical command sequence is in [the checklist](release_checklist.md#release-gate)
and [phone acceptance](phone_acceptance.md).

```bash
scripts/verify_local.sh
scripts/verify_ai_behavior_eval.sh --require-boundary-map
scripts/check_emulator_api_matrix.sh
scripts/prepare_emulator_api_matrix.sh
scripts/regression_emulator_api_matrix.sh
PUBLIC_RELEASE_CONTEXT=1 scripts/collect_ai_behavior_actual_trace.sh
```

Run the final public release gate only after production signing, owner records,
approvals, device validation, screenshots, model-license evidence, operations
evidence, and the physical performance baseline are complete.
