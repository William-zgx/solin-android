# Release Readiness

PocketMind now has the core storage, trust-boundary, and build gates needed for
internal testing. Broad external distribution still needs the remaining release
items below.

## Completed

- MIT license added in `LICENSE`.
- Recommended model manifest pins upstream revision, byte size, and SHA-256.
- Privacy notice drafted for local chat storage, remote context transfer,
  encrypted API key storage, model downloads, Android intents, device context
  tools, audit traces, and retention controls.
- Manual release checklist added for store metadata, screenshots,
  privacy/license review, signing, test gates, and rollback planning.
- Machine-readable release gates now cover capability matrix drift,
  release record completeness, store policy/data-safety disclosure, privacy
  scanning, rollout monitoring/rollback readiness, release validation matrix
  evidence, privacy notice review records, APK/AAB artifact scanning, model
  license review records, RC perf-baseline verification, artifact SHA matching,
  and optional public release enforcement for signed artifacts plus AAB presence
  and release mapping output.
- Release record, store policy, release validation, privacy review, and model
  license review verifier reports now share a machine-readable evidence schema
  with `artifactSchema`, owner, UTC timestamp, command, failed target,
  reproducible report path, and SHA-256 binding for their input records.
- Release record `verificationReports` now fail closed unless each linked report
  is SHA-bound, `status=passed`, schema-tagged, owner-tagged, UTC timestamped,
  reproducible by command/path, and fresh within the release-record max-age
  window.
- The top-level `release-gate.properties` report is now also a first-class
  evidence artifact with `artifactSchema`, owner, UTC timestamp, command,
  reproducible path, current git head SHA, and path/status/SHA-256 bindings for
  generated child reports such as privacy scan, AI behavior eval, artifact scan,
  perf baseline, signing cert, and release owner records.
- Release operations monitoring and rollback evidence now require passed typed
  properties reports bound to the approved operations record fields, not only
  path/SHA presence.
- Formal manual acceptance and release-flow evidence now require typed
  properties reports with schema, matching owner/date, fresh UTC timestamp,
  command, and reproducible path; SHA-bound status-only files fail closed.
- Share/picker release-flow evidence now covers local vision alongside remote
  vision: verified local image staging, local runtime image send count,
  LocalOnly persistence, prompt metadata redaction, remote runtime idle, and
  unsupported OCR skip counters are machine-checked.
- Recommended downloads are registered only after SHA-256 verification.
- Legacy recommended files are registered as `LegacyUnverified` and verified
  asynchronously before they can become active.
- Custom URL/imported models remain `UnverifiedCustom`, even when their file
  name matches a recommended model.
- Chat sessions, messages, model registry, and download records use Room.
- Non-sensitive settings use DataStore; remote API keys use encrypted storage.
- Remote model transport requires HTTPS, except local HTTP debug hosts.
- Remote streaming uses OkHttp and cancels the underlying call when stopped.
- PR verification is local-only by default; model URL provenance is manual or
  scheduled with `VERIFY_MODEL_URLS=1`.
- Memory is documented as a lightweight local index. Phone actions now use
  local planning, rule fallback, confirmed tools, and low-risk app-control
  continuation.
- Agent capability ownership is now registry-based: `ToolProvider` supplies
  `ToolSpec` contracts, `ToolSpec.tags` drive low-risk GUI continuation,
  device-control sessions, special access, runtime permission descriptors, and
  background-skill eligibility, while `SkillManifest` declares app-control and
  background execution metadata.
- Remote OpenAI-compatible tool calls now go through the local Agent runtime:
  single public read-only evidence calls and all-public evidence batches can
  execute without confirmation, while mixed private/action/side-effect batches
  fail closed before any tool runs.
- Hugging Face model metadata collection now records concrete model-card,
  license, notice, terms, or README source candidates plus a machine-readable
  collector report. The output remains metadata-only and is not legal approval.
- Latest local gate for the current working tree passed
  `scripts/verify_local.sh`, including JVM tests, lint, debug/androidTest APK
  assembly, release assembly, and APK content checks; see
  `docs/validation_report.md` for the dated command log.
- Latest 2026-06-17 physical-device debug control eval passed on `fb6272c`:
  `build/verification/device-control-debug-eval-20260617-001110/device-control-debug-eval.properties`
  records `status=passed` and `command_count=39`. The paired real-app search
  eval is not release-passing:
  `build/verification/real-app-search-eval-20260617-000937/real-app-search-eval.properties`
  records `status=failed`, Pinduoduo passed, Taobao failed
  `search_entry_not_found`, Amap/Gaode failed `editable_not_found`, and Chrome
  was skipped because it was not installed.
- Latest local signed release APK was installed back on `fb6272c` with
  `adb install -r` after debug eval, preserving app data. This is not a
  replacement for production signing or the final release-candidate matrix.
- Current local real-app search resolver replay coverage includes UIAutomator
  XML fixtures for Taobao, Pinduoduo, Amap/Gaode, JD, Chrome, Android Browser,
  Quark, and UC browser search-entry ranking, Taobao, Pinduoduo, Amap/Gaode, JD,
  and Quark browser input-field / submit-button / result-page verification replay,
  plus a JD unchanged-home negative replay that prevents feed result hints from
  being accepted as a completed search,
  plus a local evidence gate for ranked candidates JSON,
  target-resolution SHA-256 files, expected package/app fields, failure kind,
  window-dump SHA, submit failure, result verification failure, required hint
  failure, no-target-app fail-closed behavior, and an all-fake-apps success
  path; see `docs/validation_report.md`. This improves the resolver and eval
  regression harness, but it is not a replacement for a passing physical
  real-app-search eval.
- Current release-candidate emulator regression passed with
  `scripts/regression_emulator.sh` on `pocketmind_api36_arm64` /
  `emulator-5554` (API 36, `arm64-v8a`):
  `build/verification/goal-emulator-trust-center-final-rerun/regression-emulator.properties`
  records `status=passed`, nested emulator/device reports passed,
  `actual_android_test_count=56` matching the 56 AndroidTest source count,
  and exercises the headless clean-device AVD regression path.
- Emulator API matrix readiness is now machine-readable. The current local
  environment reports API 36 ready and API 28/32/33/34 missing system images
  and AVDs in
  `build/verification/emulator-api-matrix-readiness.properties`.
- API matrix environment preparation is now machine-readable. Run
  `scripts/prepare_emulator_api_matrix.sh` to produce a dry-run report with the
  exact SDK packages and AVD creation commands; rerun with `APPLY=1` only after
  approving those installs.
- Release validation now rejects `x86` / `x86_64` emulator evidence at the
  approved-record verifier. Arm64 emulator reports can support release
  evidence; x86 emulator reports are limited to developer smoke and cannot be
  mixed into API matrix ABI lists.
- API 36 has also passed through the new matrix runner:
  `build/verification/regression-emulator-api36-no-implicit-image-ocr/regression-emulator-api-matrix.properties`
  records `status=passed`, `passedApis=36`, and links the nested API 36
  `regression-emulator.properties` with 28 AndroidTest(s).
- The app now has a Trust/Capability Center surface backed by
  `CapabilityMatrix`: it names the next-stage MVP scenarios, LocalOnly/remote
  boundaries, confirmation policies, and fail-closed behavior without exposing
  raw prompts, tool parameters, screenshots, clipboard content, or API keys.
- `scripts/verify_ai_behavior_eval.sh` now emits machine-readable behavior
  coverage metrics and the release gate requires `mvpScenario` boundary mapping
  for the fixture suite. Public release gate runs also require
  `AI_BEHAVIOR_ACTUAL_TRACE_FILE` and bind the actual trace / planning diff
  evidence by SHA-256. Strict runs require per-row machine provenance
  (`traceSource` plus UTC `traceRecordedAt`), so final Agent behavior evidence
  cannot be a fixture-only dry run. `scripts/collect_ai_behavior_actual_trace.sh`
  now produces the local `agent_loop_runtime` actual trace collection report; the
  latest local collector report
  `build/verification/ai-behavior-actual-trace-remote-send-gate/ai-behavior-actual-trace-collection.properties`
  records 31 runtime-sourced rows, `traceDiffMatchedCount=21`,
  `traceDiffAllowedFailureCount=10`, `traceDiffMissingActualCount=0`,
  `traceDiffMismatchCount=0`, and `traceDiffExtraActualCount=0`. It includes
  app search then back checkpoint evidence, low-risk app-control checkpoint budget
  evidence, mixed public/private remote batch fail-closed evidence, restart
  external-outcome fail-closed evidence, metadata-only OCR truncation evidence,
  restart-restored confirmation evidence, explicit local memory forget evidence
  through `MemoryRepository`, and remote-send confirmation coverage. The collector
  now invokes the eval verifier with `--require-actual-trace`, so mismatched
  actual runtime traces fail the collector evidence itself instead of relying on a
  later public release gate. Real-app search failure modes are now required in
  the eval suite: `search_entry_not_found`, `editable_not_found`,
  `submit_not_found`, `result_not_verified`, and `required_hint_missing`.
  The evidence is now mismatch-free, while allowed failures remain release-owner
  review items before public release.
- GitHub Actions `workflow_dispatch` final release gate now requires an
  `ai_behavior_actual_trace_file` input and passes it to
  `AI_BEHAVIOR_ACTUAL_TRACE_FILE`, so the CI public-release path can satisfy the
  strict Agent behavior actual trace gate instead of failing from a missing
  environment value.
- Store policy evidence has been normalized for machine-checkable drift: the
  privacy notice SHA, pending review evidence SHA, manifest permission list,
  and confirmed-device-actions listing wording match the current tree. The store
  policy record intentionally remains pending until a real reviewer, contact
  email, public privacy policy URL, review date, and approved evidence are
  supplied.
- Model capability profile evidence is now machine-readable in
  `docs/model_capability_profiles.json` and bound to
  `ModelCatalog.recommendedProfiles()` plus remote OpenAI-compatible text/vision
  templates by `ModelCapabilityProfilesDocumentationTest`. The release gate
  contract-test set includes this test, so chat/vision/embedding/action/context
  window/backend drift fails before release evidence is accepted.
- Custom imported local models now resolve to an explicit `custom-local-chat`
  text-only capability profile instead of inheriting the selected recommended
  chat/vision profile in health/runtime evidence. Unknown or stale recommended
  model ids remain unverified and vision fail-closed.
- Agent behavior allowed failures are now safety-bounded. JVM and shell
  verifiers require allowed-failure traces to preserve risk, privacy,
  LocalOnly/remote eligibility, and FailClosed invariants; script self-tests add
  negative cases for safety-boundary drift and FailClosed weakening.
- Roadmap status as of 2026-06-22: local Phase 1 gates remain green, Phase 2
  real-app replay/evidence coverage now includes Chrome, Android Browser, Quark,
  and UC alongside Taobao, PDD, Gaode, and JD, and Phase 3/4 release/privacy/store
  gate contracts continue to harden. This is not yet release-ready: physical
  arm64 validation, arm64 emulator matrix, real-app device loops, perf baseline,
  screenshots, manual approvals, and production signing remain blocking evidence.
- The latest local hardening also rejects contradictory instrumentation outputs
  that contain both failure markers and final OK, requires audit headers on
  monitoring / crash-smoke / rollback operations evidence, binds strict Agent
  actual traces to fixture category and input, and adds a direct UI-state contract
  for verified local vision model capability. These are local gates only; they do
  not replace physical validation or release-owner evidence.
- The latest follow-up hardening records `permissiondenied` through the real
  Agent loop runtime path, requires the actual-trace collector to be entirely
  `agent_loop_runtime`, emits CI identity from emulator regression producers,
  binds API matrix readiness evidence by SHA-256, and asserts local-only
  memory/action model capabilities cannot become remote eligible. These remain
  local gate improvements; physical arm64 validation, emulator API matrix runs,
  perf baseline, screenshots, approvals, and production signing are still
  blocking evidence.

## Remaining release blockers by ownership

| Status | Owner / environment | Item | Gate or evidence |
| --- | --- | --- | --- |
| Owner evidence required | Release owner | Fill `docs/release_record.json` with final owner, reviewer, target channel, changelog, release notes, artifact checksum, signing certificate fingerprint, fresh schema/owner-tagged verification reports, and resolved/accepted blockers. | `VERIFY_RELEASE_RECORD=1 scripts/verify_release_gate.sh`; `PUBLIC_RELEASE=1` additionally binds the record to the final public AAB, artifact SHA-256, and production signing certificate SHA-256. |
| Owner evidence required | Store / policy owner | Fill `docs/store_policy_record.json` with an approved status, real support contact, public privacy-policy URL, reviewer, review date, and approved store-policy evidence. Current machine-checkable SHA, permission, and confirmed-actions wording drift has been normalized, but the record remains intentionally pending. | `VERIFY_STORE_POLICY=1 scripts/verify_release_gate.sh`; verifier checks the current privacy notice SHA and Android manifest. |
| Owner evidence required | Release operations owner | Fill `docs/release_operations_record.json` with crash/ANR monitoring owner, signal source, first-24-hour watcher, staged rollout thresholds, crash/ANR smoke result, and rollback plan. | `VERIFY_RELEASE_OPERATIONS=1 scripts/verify_release_gate.sh`; smoke evidence should come from `scripts/collect_crash_anr_smoke_evidence.sh` plus device verification, instrumentation output, and logcat. |
| Owner evidence required | Validation owner | Fill `docs/release_validation_record.json` with approved emulator regression, physical-device instrumentation, API matrix, manual acceptance, flow matrix, sanitized screenshots, and performance sanity evidence. | `VERIFY_RELEASE_VALIDATION=1 scripts/verify_release_gate.sh`; verifier rejects emulator serials as physical-device evidence and checks AndroidTest counts, required APIs, manual/system-mediated flows, screenshots, and review date. |
| Manual approval required | Release, security, legal | Review `docs/privacy_notice.md` before publishing it as the external policy and record role approvals in `docs/privacy_review.json`. | `VERIFY_PRIVACY_REVIEW=1 scripts/verify_release_gate.sh`. App code cannot replace this approval. |
| Manual/legal approval required | Model/license reviewer | For all four recommended model downloads, verify upstream license name, concrete license/notice URL or file path, redistribution rights, attribution/notice requirements, reviewer, and date. | Record in `docs/model_license_review.json`; `VERIFY_MODEL_LICENSES=1` verifies alignment with `docs/model_manifest.md` and metadata. `VERIFY_MODEL_URLS=1` checks URL/content metadata only and is not license approval. |
| Private environment required | Signing owner | Configure production release signing outside source control and run `scripts/sign_release_artifacts.sh` with production keystore material and `EXPECTED_SIGNING_CERT_SHA256`. | `PUBLIC_RELEASE=1 EXPECTED_SIGNING_CERT_SHA256=<production upload cert> scripts/verify_release_gate.sh`; debug keystores are rejected for production. |
| Physical hardware required | Device validation owner | Investigate the current full physical-device instrumentation crash before binding physical-device release evidence. On 2026-06-17, `fb6272c` (`Xiaomi 23127PN0CC`, API 36, `arm64-v8a`) crashed in `MainActivityAdaptiveUiTest.largeFontChatShellAndModelManagerRemainReachable`; see `build/verification/device-20260617-000355/device-verification.properties` (`failedTarget=instrumentation`, `reason=instrumentation-failed`) and `instrumentation.txt` (`shortMsg=Process crashed.`). | A passing physical `scripts/install_and_test_device.sh` report, plus failure evidence for any remaining crash or timeout. |
| SDK/AVD environment required | CI / emulator owner | Prepare API 28/32/33/34 arm64 emulator system images and AVDs before claiming API matrix coverage. | `scripts/check_emulator_api_matrix.sh` records missing packages/AVDs; `scripts/prepare_emulator_api_matrix.sh` produces dry-run/apply commands; `scripts/regression_emulator_api_matrix.sh` generates matrix evidence. |
| Physical hardware required | Performance owner | Run final release-candidate validation and performance SLO collection on target physical arm64 hardware. Emulator validation does not cover LiteRT-LM GPU/performance behavior. | `scripts/collect_perf_baseline.sh` or equivalent `perf-baseline.properties`, then `PERF_BASELINE_FILE=... scripts/verify_release_gate.sh`; verifier rejects emulator serials, stale/future timestamps, wrong ABI/version/artifact SHA, OOM/ANR, and zero timing/memory values. |
| Public-release final gate | Release owner | After production signing, AAB generation, approvals, validation, perf evidence, and Agent behavior actual trace evidence are complete, run the public release gate. | `PUBLIC_RELEASE=1 EXPECTED_SIGNING_CERT_SHA256=<production upload cert> PERF_BASELINE_FILE=<rc perf baseline> AI_BEHAVIOR_ACTUAL_TRACE_FILE=<actual-trace.jsonl> scripts/verify_release_gate.sh`; this enables release record, store policy, operations, validation, privacy review, model license, signed artifact/AAB, cert fingerprint, mapping, perf, and AI behavior actual trace checks. |
