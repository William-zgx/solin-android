# Release Blocker Dashboard

Compact blocker view for the current RC. It summarizes roadmap/readiness
evidence; it does not close device, performance, signing, store, legal,
privacy, or release-owner blockers by itself.

- Roadmap source date: `2026-06-23`
- Dashboard refreshed: `2026-06-26`
- Close rule: a blocker is closed only by linked evidence with owner/date/SHA-256, or by explicit release-owner risk acceptance recorded in the release record.
- Final authority: `PUBLIC_RELEASE=1 ... scripts/verify_release_gate.sh`

## Active Blockers

| Area | Current state | Blocking reason | Required next evidence |
| --- | --- | --- | --- |
| Physical validation and arm64 API matrix | deferred | No fresh physical-device release validation in this phase; API matrix environment still required. | Resolve the physical instrumentation issue, run `scripts/install_and_test_device.sh` on arm64 hardware, prepare missing API 28/32/33/34 arm64 AVDs, refresh API 36 only if evidence is stale, and attach passed nested reports with SHA-256. |
| Perf baseline on physical arm64 | deferred | Release performance cannot be inferred from emulator or JVM results. | Collect RC first launch, model load, first token, tokens/s, memory peak, ANR/OOM, and GPU fallback in `perf-baseline.properties`; verify it with `scripts/verify_perf_baseline.sh`. |
| Real-app search physical pass rate | deferred | Debug real-app search physical pass rate was not refreshed. | Run `scripts/run_real_app_search_eval.sh` on arm64 physical hardware and attach `RealAppSearchEvidenceVerification/v1` plus case artifacts. This remains debug readiness evidence, not release physical validation. |
| Real-app search replay coverage | partial | Replay fixtures exist but changing Taobao, Gaode, JD, and browser surfaces still need coverage. | Continue adding replay fixtures and compare them against the next physical real-app-search run. |
| Agent behavior actual runtime trace | partial | Fresh public-strict `agent_loop_runtime` trace was not collected. | Run `scripts/collect_ai_behavior_actual_trace.sh` with strict flags and keep trace diff mismatch and allowed-failure counts at zero. |
| Privacy, store, model license, and release approvals | partial | Human approvals and final public metadata are still required. | Fill real owners/reviewers, public privacy URL, store evidence, privacy/security/legal approvals, model license approvals, signing identity, artifact SHA-256, and approved evidence files. |
| Production signing and public artifact | blocked | Production signing material and final public AAB evidence are not complete. | Run protected signing, record upload/app-signing certificate fingerprints, archive AAB/APK/mapping artifacts, and pass artifact scan. |
| Release operations | partial | Monitoring, crash/ANR smoke, rollout watcher, and rollback evidence need final owner-approved records. | Complete `docs/release_operations_record.json`, crash/ANR smoke, monitoring evidence, rollback evidence, and `VERIFY_RELEASE_OPERATIONS=1 scripts/verify_release_gate.sh`. |

## Evidence That Does Not Close Release Blockers

- Emulator regression does not replace physical `arm64-v8a` device evidence.
- Debug device-control and real-app-search eval do not replace release validation.
- Manual observation without artifact path and SHA-256 is reviewer input only.
- `bundledModels` internal experience package evidence does not approve Play/public release packaging.
- Incremental install `Success` is not accepted for the large `bundledModels`
  split package. Expect roughly 5.8 GB / 5.4 GiB of signed APKs; exact sizes
  come from `build/verification/bundled-models/package.properties`. Use
  `adb install-multiple --no-incremental -r`.
- Risk acceptance must name the blocker, owner, date, decision, current release Git commit, release artifact SHA-256, evidence path, and evidence SHA-256.

## Next Commands

```bash
VERIFY_RELEASE_RECORD=1 scripts/verify_release_gate.sh
VERIFY_STORE_POLICY=1 scripts/verify_release_gate.sh
VERIFY_PRIVACY_REVIEW=1 scripts/verify_release_gate.sh
VERIFY_MODEL_LICENSES=1 scripts/verify_release_gate.sh
VERIFY_RELEASE_VALIDATION=1 scripts/verify_release_gate.sh
VERIFY_RELEASE_OPERATIONS=1 scripts/verify_release_gate.sh
scripts/check_emulator_api_matrix.sh
scripts/prepare_emulator_api_matrix.sh
ANDROID_SERIAL=<physical-device-serial> scripts/install_and_test_device.sh
ANDROID_SERIAL=<physical-device-serial> scripts/run_real_app_search_eval.sh
ANDROID_SERIAL=<physical-device-serial> scripts/collect_perf_baseline.sh
PUBLIC_RELEASE=1 EXPECTED_SIGNING_CERT_SHA256=<production-upload-cert-sha256> PERF_BASELINE_FILE=<rc-perf-baseline.properties> AI_BEHAVIOR_ACTUAL_TRACE_FILE=<actual-trace.jsonl> scripts/verify_release_gate.sh
```
