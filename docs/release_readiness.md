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
- Public identity is unified as `栖知 Solin`; launcher/adaptive/round/
  monochrome icons, docs preview images, and listing copy use the current Solin
  mark and shared background color.
- Privacy notice, release checklist, release records, store policy records,
  validation records, operations records, privacy review records, and model
  license review records exist and are wired into verifier scripts.
- Internal `bundledModels` packaging is a first-class developer path:
  `assembleBundledModelsPackage`, `bundleBundledModelsPackage`,
  `checkBundledModelsPackageOutputs`, and
  `scripts/package_bundled_models.sh`.
- SolinLog structured logging facade (test-safe, no `android.util.Log` crash in unit tests).
- SolinConstants centralized constants (Network/AgentLoop/Ui/Embedding).
- AgentLoopRuntime concurrency safety: 8 maps converted to `ConcurrentHashMap`.
- MemoryController extracted from SolinViewModel (385 lines).
- Evidence encryption at rest: AES/CBC/PKCS5Padding with AndroidKeyStore.
- Network security config: HTTPS-by-default, loopback cleartext only.
- ModelRepository dependency inversion: now takes `SettingsStore` interface.
- SolinViewModel code deduplication: `persistMessagesAndRebuildMemory` (11 sites), `clearedEvidence()` (5 sites).
- Bug fix: `ToolResult.errorCode` → `ToolResult.error` in SolinViewModel.

## Current Evidence

| Area | Current evidence | Caveat |
| --- | --- | --- |
| Local build gate | Historical local gates have passed `scripts/verify_local.sh`; see `docs/validation_report.md`. | Must be rerun for the current commit before an RC handoff. |
| Brand/assets | Current source and docs use `栖知 Solin`, current Solin icon resources, and current docs preview images. | Store screenshots and final listing still need release-owner review and fresh screenshot evidence. |
| Emulator regression | API 36 arm64 emulator/debug evidence exists in historical records. | Current validation record must not reuse stale reports that bind older package/brand state; API 28/32/33/34 arm64 matrix is still incomplete. |
| Physical device | Historical debug/connected evidence exists with app data preserved. | It is partial readiness evidence, not final signed RC physical validation. |
| Real-app search | Replay and debug evidence exist for supported shopping/map/browser surfaces. | Fresh physical real-app evidence and the required 50 task benchmark are still blockers. |
| Bundled model package | Internal split package path exists and can install base plus four modelpack splits with same-signature `adb install-multiple --no-incremental -r`. | Internal quick-experience evidence only. It is not Play/public release evidence, and external handoff still requires approved model license and redistribution review. |
| Store/privacy/license | Records and verifiers exist. | Real support contact, public privacy URL, reviewers, approvals, and model redistribution decisions remain pending. |
| Perf | RC perf collector and verifier exist. | Final metrics must be measured on physical arm64 hardware against the final signed RC artifact with `runner=rc_perf_release_broadcast`. |

## Recent Test Results

- ~1911 unit tests passing, 0 failures.
- `scripts/verify_local.sh` passes (exit code 0).
- Emulator tests: 7 previously-failing tests now passing.

## Release Blockers

| Owner | Blocker | Evidence needed |
| --- | --- | --- |
| Release owner | Fill `docs/release_record.json` with final owner, reviewer, target channel, changelog, release notes, artifact checksum, signing certificate fingerprint, verification reports, and blocker decisions. | `VERIFY_RELEASE_RECORD=1 scripts/verify_release_gate.sh`; public release also needs `PUBLIC_RELEASE=1` and a bound final gate report. |
| Store / policy owner | Fill and approve `docs/store_policy_record.json` with real support contact, public privacy-policy URL, reviewer, review date, Data safety answers, and evidence. | `VERIFY_STORE_POLICY=1 scripts/verify_release_gate.sh`. |
| Operations owner | Fill `docs/release_operations_record.json` with crash/ANR monitoring owner, rollout thresholds, first-24-hour watcher, and rollback plan. | `VERIFY_RELEASE_OPERATIONS=1 scripts/verify_release_gate.sh`. |
| Validation owner | Fill `docs/release_validation_record.json` with approved emulator regression, physical instrumentation, API matrix, manual acceptance, release-flow, screenshots, and perf sanity evidence. | `VERIFY_RELEASE_VALIDATION=1 scripts/verify_release_gate.sh`. |
| Release, security, legal | Approve the current privacy notice and capability matrix. | `VERIFY_PRIVACY_REVIEW=1 scripts/verify_release_gate.sh`. |
| Model/license reviewer | Verify license, attribution, notice, and redistribution rights for all recommended models. | `VERIFY_PERF_BASELINE=0 VERIFY_MODEL_LICENSES=1 scripts/verify_release_gate.sh`. |
| Signing owner | Configure production signing outside source control. | `PUBLIC_RELEASE=1 EXPECTED_SIGNING_CERT_SHA256=<production upload cert> scripts/verify_release_gate.sh`. |
| Device validation owner | Rerun on target arm64 hardware against the final signed release APK without resetting app data after tests. | Passing `APP_APK_MODE=release APP_APK=<signed-release.apk> RELEASE_ARTIFACT_TYPE=apk RELEASE_ARTIFACT_SHA256=<apk-sha> RESET_APP_DATA_AFTER_TESTS=0 scripts/install_and_test_device.sh` report with instrumentation/logcat SHA bindings. |
| CI / emulator owner | Prepare API 28/32/33/34 arm64 emulator images and AVDs. | `scripts/check_emulator_api_matrix.sh`, then `scripts/prepare_emulator_api_matrix.sh`, then matrix regression evidence. |
| Performance owner | Collect RC load, first-token, throughput, memory, ANR/OOM, GPU fallback, and 50k memory metrics on physical arm64 hardware. | `RELEASE_ARTIFACT=<signed-rc> APP_VERSION=<version> scripts/collect_rc_perf_from_device.sh` report and `PERF_BASELINE_FILE=... scripts/verify_release_gate.sh`. |
| AI behavior owner | Collect a fresh public-strict `agent_loop_runtime` actual trace for the current fixture set. | `scripts/collect_ai_behavior_actual_trace.sh` and strict `scripts/verify_ai_behavior_eval.sh` report with zero mismatches and zero unresolved allowed failures. |
| Release / model owner | If sharing the large bundled-model tester package, bind split APKs, signing certificate, model hashes, model license approval, and install smoke separately from Play artifacts. | `VERIFY_PERF_BASELINE=0 VERIFY_MODEL_LICENSES=1 scripts/verify_release_gate.sh`, then `scripts/package_bundled_models.sh` report plus device smoke showing base plus four modelpack splits and verified models. |

## Future Work

Detailed implementation plans live in `docs/plans/`. Of the five tracked
plans, two are completed (ViewModel split, SolinScreen split), one is
explicitly not doing (UiState split), one is not started (data layer suspend
migration), and one has no plan file yet (AgentLoopRuntime split). These are
tracked as future work items beyond the current release gate.

## Next Commands

```bash
scripts/verify_local.sh
scripts/check_emulator_api_matrix.sh
scripts/prepare_emulator_api_matrix.sh
scripts/regression_emulator_api_matrix.sh
scripts/sign_release_artifacts.sh
APP_APK_MODE=release \
APP_APK=<signed-release.apk> \
RELEASE_ARTIFACT_TYPE=apk \
RELEASE_ARTIFACT_SHA256=<signed-release-apk-sha256> \
RESET_APP_DATA_AFTER_TESTS=0 \
ANDROID_SERIAL=<physical-device-serial> \
scripts/install_and_test_device.sh
ANDROID_SERIAL=<physical-device-serial> scripts/run_real_app_search_eval.sh
RELEASE_ARTIFACT=<signed-rc-artifact> \
APP_VERSION=<versionName-versionCode> \
ANDROID_SERIAL=<physical-device-serial> \
scripts/collect_rc_perf_from_device.sh
scripts/collect_ai_behavior_actual_trace.sh
VERIFY_PERF_BASELINE=0 VERIFY_RELEASE_RECORD=1 scripts/verify_release_gate.sh
VERIFY_PERF_BASELINE=0 VERIFY_STORE_POLICY=1 scripts/verify_release_gate.sh
VERIFY_PERF_BASELINE=0 VERIFY_PRIVACY_REVIEW=1 scripts/verify_release_gate.sh
VERIFY_PERF_BASELINE=0 VERIFY_MODEL_LICENSES=1 scripts/verify_release_gate.sh
VERIFY_PERF_BASELINE=0 VERIFY_RELEASE_VALIDATION=1 scripts/verify_release_gate.sh
VERIFY_PERF_BASELINE=0 VERIFY_RELEASE_OPERATIONS=1 scripts/verify_release_gate.sh
PUBLIC_RELEASE=1 \
EXPECTED_SIGNING_CERT_SHA256=<production upload cert> \
PERF_BASELINE_FILE=<rc perf baseline> \
AI_BEHAVIOR_ACTUAL_TRACE_FILE=<actual-trace.jsonl> \
scripts/verify_release_gate.sh
```

Run the final public release gate only after production signing, owner records,
approvals, validation, screenshots, model-license evidence, operations evidence,
and physical perf baseline are complete.
