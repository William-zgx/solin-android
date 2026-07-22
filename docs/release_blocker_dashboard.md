# Release Blocker Dashboard

Compact blocker view for the current RC. It summarizes roadmap/readiness
evidence; it does not close device, performance, signing, store, legal,
privacy, or release-owner blockers by itself.

- Roadmap source date: `2026-06-29`
- Dashboard refreshed: `2026-07-22`
- Close rule: a blocker is closed only by linked evidence with owner/date/SHA-256, or by explicit release-owner risk acceptance recorded in the release record.
- Final authority: `PUBLIC_RELEASE=1 ... scripts/verify_release_gate.sh`

## Active Blockers

| Area | Current state | Blocking reason | Required next evidence |
| --- | --- | --- | --- |
| Physical validation and arm64 API matrix | partial | Debug/connected evidence exists, but final signed RC physical validation and API matrix are still incomplete. | Run release validation on final signed RC artifact, prepare missing API 28/32/33/34 arm64 AVDs, refresh API 36 only if evidence is stale, and attach passed nested reports with SHA-256. |
| Perf baseline on physical arm64 | partial | Standalone perf samples do not close the final production-signed RC perf gate. | Re-collect RC first launch, model load, first token, tokens/s, memory peak, ANR/OOM, GPU fallback, and 50k memory metrics against the final signed RC artifact; verify it with `scripts/verify_perf_baseline.sh`. |
| Real-app search physical pass rate | partial | Debug real-app-search evidence exists, but it is not release validation or a 50 task benchmark. | Run the 50 task physical benchmark and final release validation on arm64 physical hardware; attach `RealAppSearchEvidenceVerification/v1` plus case artifacts and release record links. |
| Real-app search replay coverage | partial | Replay fixtures exist but changing Taobao, Gaode, JD, and browser surfaces still need coverage. | Continue adding replay fixtures and compare them against the next physical real-app-search run. |
| Agent behavior actual runtime trace | partial | Fresh public-strict `agent_loop_runtime` trace was not collected. | Run `scripts/collect_ai_behavior_actual_trace.sh` with strict flags and keep trace diff mismatch and allowed-failure counts at zero. |
| Privacy, store, model license, and release approvals | partial | Human approvals and final public metadata are still required. | Fill real owners/reviewers, public privacy URL, store evidence, privacy/security/legal approvals, model license approvals, signing identity, artifact SHA-256, and approved evidence files. |
| Production signing and public artifact | blocked | Production signing material and final public AAB evidence are not complete. | Run protected signing, record upload/app-signing certificate fingerprints, archive AAB/APK/mapping artifacts, and pass artifact scan. |
| Release operations | partial | Monitoring, crash/ANR smoke, rollout watcher, and rollback evidence need final owner-approved records. | Complete `docs/release_operations_record.json`, crash/ANR smoke, monitoring evidence, rollback evidence, and `VERIFY_RELEASE_OPERATIONS=1 scripts/verify_release_gate.sh`. |

## Recently Completed

Resolved in commit `4ad758e`. All items below are DONE / GREEN.

| Area | Item | Status |
| --- | --- | --- |
| Logging | Structured logging facade (SolinLog) | DONE |
| Constants | Centralized constants (SolinConstants) | DONE |
| Concurrency | AgentLoopRuntime concurrency safety (ConcurrentHashMap) | DONE |
| Evidence | Evidence encryption at rest | DONE |
| Network | Network security config (HTTPS-by-default) | DONE |
| Architecture | ModelRepository dependency inversion | DONE |
| Architecture | SolinViewModel code deduplication | DONE |

## Future Work

Items tracked under `docs/plans/`:

| Area | Item | Status |
| --- | --- | --- |
| Architecture | AgentLoopRuntime split into multi-component + typed event bus | PLANNED (no plan file yet; incremental extraction via `orchestration/` collaborators) |
| Data layer | Data layer suspend migration (9 interfaces, 18 methods) | NOT STARTED — `docs/plans/data-layer-suspend-migration.md` |
| ViewModel | ViewModel split into 19 files (8 named controllers) | COMPLETED — `docs/plans/viewmodel-split.md` |
| UiState | UiState split into 10 sub-states | NOT DOING — `docs/plans/uistate-split.md` |
| UI | SolinScreen composable split (~6,300 → ~1,600 lines, 17 leaf components) | COMPLETED — `docs/plans/solin-screen-split.md` |

## Evidence That Does Not Close Release Blockers

- Emulator regression does not replace physical `arm64-v8a` device evidence.
- Debug device-control and real-app-search eval do not replace release validation.
- Unsigned release APK perf samples do not close the final signed RC perf gate.
- Manual observation without artifact path and SHA-256 is reviewer input only.
- `bundledModels` internal experience package evidence does not approve Play/public release packaging.
- Incremental install `Success` is not accepted for the large `bundledModels`
  split package. Expect roughly 5.8 GB / 5.4 GiB of signed APKs; exact sizes
  come from `build/verification/bundled-models/package.properties`. Use
  `adb install-multiple --no-incremental -r`.
- Risk acceptance must name the blocker, owner, date, decision, current release Git commit, release artifact SHA-256, evidence path, and evidence SHA-256.

## Next Commands

```bash
VERIFY_PERF_BASELINE=0 VERIFY_RELEASE_RECORD=1 scripts/verify_release_gate.sh
VERIFY_PERF_BASELINE=0 VERIFY_STORE_POLICY=1 scripts/verify_release_gate.sh
VERIFY_PERF_BASELINE=0 VERIFY_PRIVACY_REVIEW=1 scripts/verify_release_gate.sh
VERIFY_PERF_BASELINE=0 VERIFY_MODEL_LICENSES=1 scripts/verify_release_gate.sh
VERIFY_PERF_BASELINE=0 VERIFY_RELEASE_VALIDATION=1 scripts/verify_release_gate.sh
VERIFY_PERF_BASELINE=0 VERIFY_RELEASE_OPERATIONS=1 scripts/verify_release_gate.sh
scripts/check_emulator_api_matrix.sh
scripts/prepare_emulator_api_matrix.sh
scripts/regression_emulator_api_matrix.sh
APP_APK_MODE=release APP_APK=<signed-release.apk> RELEASE_ARTIFACT_TYPE=apk RELEASE_ARTIFACT_SHA256=<apk-sha> RESET_APP_DATA_AFTER_TESTS=0 ANDROID_SERIAL=<physical-device-serial> scripts/install_and_test_device.sh
ANDROID_SERIAL=<physical-device-serial> scripts/run_real_app_search_eval.sh
RELEASE_ARTIFACT=<signed-rc-artifact> APP_VERSION=<version> ANDROID_SERIAL=<physical-device-serial> scripts/collect_rc_perf_from_device.sh
scripts/collect_ai_behavior_actual_trace.sh
PUBLIC_RELEASE=1 EXPECTED_SIGNING_CERT_SHA256=<production-upload-cert-sha256> PERF_BASELINE_FILE=<rc-perf-baseline.properties> AI_BEHAVIOR_ACTUAL_TRACE_FILE=<actual-trace.jsonl> scripts/verify_release_gate.sh
```
