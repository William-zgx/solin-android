# Release Readiness

This page is the current release-status summary. It is not the release
checklist and not the evidence log.

- Use `docs/release_checklist.md` to execute a release candidate.
- Use `docs/validation_report.md` for dated command output and artifacts.
- Use JSON records in `docs/` for machine-readable release owner fields.

## Status

Solin is suitable for internal development, lab validation, and targeted
tester builds. It is not ready for broad public distribution.
The canonical release-gate flow is in `docs/release_checklist.md`; this page
only summarizes current readiness and blockers.

## Complete Foundations

- MIT license is present.
- Recommended model manifest pins repository, revision, bytes, and SHA-256.
- Local model registration verifies recommended downloads before activation.
- Chat sessions, messages, model registry, download records, memory records,
  tool audit, and Agent trace state use local app storage.
- Remote API keys use Android Keystore-backed encrypted preferences.
- Remote transport requires HTTPS except local debug hosts.
- Tool execution is registry-driven: `ToolSpec` owns schemas, risk,
  permissions, continuation policy, private outputs, and low-risk tags.
- Remote OpenAI-compatible `tool_calls` are revalidated locally. Public
  read-only evidence may continue remotely; mixed private/action batches fail
  closed before execution.
- Privacy notice, release checklist, release records, store policy records,
  validation records, operations records, privacy review records, and model
  license review records exist and are wired into verifier scripts.
- Internal `bundledModels` packaging is a first-class developer path:
  `assembleBundledModelsPackage`, `bundleBundledModelsPackage`,
  `checkBundledModelsPackageOutputs`, and
  `scripts/package_bundled_models.sh`.

## Current Evidence

| Area | Current evidence | Caveat |
| --- | --- | --- |
| Local build gate | Historical local gates have passed `scripts/verify_local.sh`; see `docs/validation_report.md`. | Must be rerun for the current RC or working tree. |
| Emulator regression | API 36 arm64 emulator regression has passed in prior evidence. | API 28/32/33/34 arm64 images and AVDs are still missing locally. |
| Physical device | `fb6272c` has recent manual/debug evidence for resource UI, release APK overwrite install, and bundled split install. | Full physical instrumentation is not release-passing yet. |
| Real-app search | Replay and debug evidence exist for supported shopping/map/browser surfaces. | Fresh physical real-app pass rate is still required. |
| Bundled model package | On 2026-06-26, `fb6272c` installed base plus four modelpack splits; Model Manager showed E2B/E4B/memory/action assets as `SHA-256 已校验`; E2B loaded with `backend=GPU`. | Internal quick-experience evidence only. It is not Play/public release evidence, and external handoff still requires approved model license and redistribution review. |
| Store/privacy/license | Records and verifiers exist. | Real owners, reviewers, public URLs, approvals, and model redistribution decisions remain pending. |
| Perf | Perf baseline schema and verifier exist. | RC metrics must be measured on physical arm64 hardware. |

## Release Blockers

| Owner | Blocker | Evidence needed |
| --- | --- | --- |
| Release owner | Fill `docs/release_record.json` with final owner, reviewer, target channel, changelog, release notes, artifact checksum, signing certificate fingerprint, verification reports, and blocker decisions. | `VERIFY_RELEASE_RECORD=1 scripts/verify_release_gate.sh`; public release also needs `PUBLIC_RELEASE=1` and a bound final gate report. |
| Store / policy owner | Fill and approve `docs/store_policy_record.json` with real support contact, public privacy-policy URL, reviewer, review date, Data safety answers, and evidence. | `VERIFY_STORE_POLICY=1 scripts/verify_release_gate.sh`. |
| Operations owner | Fill `docs/release_operations_record.json` with crash/ANR monitoring owner, rollout thresholds, first-24-hour watcher, and rollback plan. | `VERIFY_RELEASE_OPERATIONS=1 scripts/verify_release_gate.sh`. |
| Validation owner | Fill `docs/release_validation_record.json` with approved emulator regression, physical instrumentation, API matrix, manual acceptance, release-flow, screenshots, and perf sanity evidence. | `VERIFY_RELEASE_VALIDATION=1 scripts/verify_release_gate.sh`. |
| Release, security, legal | Approve the current privacy notice and capability matrix. | `VERIFY_PRIVACY_REVIEW=1 scripts/verify_release_gate.sh`. |
| Model/license reviewer | Verify license, attribution, notice, and redistribution rights for all recommended models. | `VERIFY_MODEL_LICENSES=1 scripts/verify_release_gate.sh`. |
| Signing owner | Configure production signing outside source control. | `PUBLIC_RELEASE=1 EXPECTED_SIGNING_CERT_SHA256=<production upload cert> scripts/verify_release_gate.sh`. |
| Device validation owner | Resolve physical-device instrumentation instability and rerun on target arm64 hardware without resetting app data after tests. | Passing `RESET_APP_DATA_AFTER_TESTS=0 scripts/install_and_test_device.sh` report with instrumentation/logcat SHA bindings. |
| CI / emulator owner | Prepare API 28/32/33/34 arm64 emulator images and AVDs. | `scripts/check_emulator_api_matrix.sh`, then `scripts/prepare_emulator_api_matrix.sh`, then matrix regression evidence. |
| Performance owner | Collect RC load, first-token, throughput, memory, ANR/OOM, and GPU fallback measurements on physical arm64 hardware. | `scripts/collect_rc_perf_from_device.sh` report and `PERF_BASELINE_FILE=... scripts/verify_release_gate.sh`. |
| Release / model owner | If sharing the large bundled-model tester package, bind split APKs, signing certificate, model hashes, model license approval, and install smoke separately from Play artifacts. | `VERIFY_MODEL_LICENSES=1 scripts/verify_release_gate.sh`, then `scripts/package_bundled_models.sh` report plus device smoke showing base plus four modelpack splits and verified models. |

## Next Commands

```bash
scripts/verify_local.sh
scripts/check_emulator_api_matrix.sh
scripts/prepare_emulator_api_matrix.sh
RESET_APP_DATA_AFTER_TESTS=0 ANDROID_SERIAL=<physical-device-serial> scripts/install_and_test_device.sh
ANDROID_SERIAL=<physical-device-serial> scripts/run_real_app_search_eval.sh
ANDROID_SERIAL=<physical-device-serial> scripts/collect_rc_perf_from_device.sh
VERIFY_RELEASE_RECORD=1 scripts/verify_release_gate.sh
VERIFY_STORE_POLICY=1 scripts/verify_release_gate.sh
VERIFY_PRIVACY_REVIEW=1 scripts/verify_release_gate.sh
VERIFY_MODEL_LICENSES=1 scripts/verify_release_gate.sh
PUBLIC_RELEASE=1 \
EXPECTED_SIGNING_CERT_SHA256=<production upload cert> \
PERF_BASELINE_FILE=<rc perf baseline> \
AI_BEHAVIOR_ACTUAL_TRACE_FILE=<actual-trace.jsonl> \
scripts/verify_release_gate.sh
```

Run the final public release gate only after production signing, owner records,
approvals, validation, screenshots, model-license evidence, operations evidence,
and physical perf baseline are complete.
