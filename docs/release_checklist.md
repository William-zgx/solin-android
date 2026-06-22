# Production Release Checklist

Use this checklist for each release candidate. It is intentionally operational:
every checked item should have a named owner, date, and artifact link in the
release ticket or PR. Code gates can prove artifact shape, records, and script
outputs; they cannot substitute for manual/legal approval, production signing
secrets, Play Console/Android Vitals evidence, or measurements on real physical
hardware. Keep the ownership/status table in `docs/release_readiness.md` aligned
with each RC before treating this checklist as complete.

## Scope

- [ ] Release version name and version code are final.
- [ ] Release branch, commit SHA, and changelog are recorded.
- [ ] Release owner, reviewer, date, and target channel are recorded.
- [ ] APK/AAB artifact path, SHA-256, signing certificate fingerprint, and
  verification evidence links are recorded.
- [ ] Open blockers are either resolved or explicitly accepted by the release
  owner with a dated risk note, evidence file, and matching SHA-256.
- [ ] Target audience is clear: internal testing, closed testing, or broader
  distribution.
- [ ] Known unsupported capabilities are called out, especially screenshot
  capture, semantic screen understanding, full PDF parsing, legacy Office
  parsing, local image semantic understanding for unverified or non-vision
  local models, and arbitrary media OCR.
- [ ] Agent/tool behavior changes are summarized, including remote
  OpenAI-style `tool_calls`, public evidence batch execution, all-or-nothing
  mixed-batch rejection, and the privacy boundary for LocalOnly tool results.
- [ ] Agent behavior eval reports have no unexpected actual `failureMode`:
  non-empty actual failure modes must be stable slugs and must either be listed
  in the case `allowedFailureModes` or make the trace diff fail as mismatch.
- [ ] Route-sensitive Agent behavior eval cases declare expected routing
  evidence where needed. Plain-chat false positives such as explaining Wi-Fi
  settings APIs must expect `no_action`; actual traces that route to tools must
  fail the planning trace diff instead of passing from an empty final tool list.
- [ ] `docs/release_record.json` is updated for the release candidate and
  `VERIFY_RELEASE_RECORD=1 scripts/verify_release_gate.sh` passes. The gate
  checks the recorded Gradle version, current HEAD Git commit, artifact
  checksum, signing certificate fingerprint, and `verificationReports` whose
  files match the recorded SHA-256, have `status=passed`, include
  `artifactSchema`, owner, UTC `recordedAt`, reproducible command/path, and are
  fresh within `RELEASE_RECORD_VERIFICATION_REPORT_MAX_AGE_DAYS` (default 30).
  It also checks unsupported capabilities, Agent behavior summary, and resolved
  or accepted blockers with matching evidence SHA-256. For `PUBLIC_RELEASE=1`,
  the record must use a public distribution channel and match the final AAB path,
  artifact SHA-256, and production signing certificate SHA-256 passed to the
  release gate; it also requires a clean Git worktree unless
  `ALLOW_DIRTY_RELEASE=1` is explicitly set for non-production dry-run
  validation.
- [ ] `release-gate.properties` and every generated child report bound by it are
  auditable evidence. Release-gate-owned skipped or preflight-failed child
  reports must include `ReleaseGateChildReport/v1`, owner, UTC `recordedAt`,
  command, reason, and reproducible path instead of only `status` / `target`.

## Versioning And Release Track

- [ ] Current Gradle values are recorded:
  `applicationId=com.bytedance.zgx.pocketmind`, `minSdk=28`, `targetSdk=36`,
  current `versionCode=1`, and current `versionName=0.1.0`.
- [ ] `versionCode` is strictly higher than every artifact ever uploaded to the
  same Play application; never reuse a version code, even for a rejected build.
- [ ] `versionName` follows the user-visible release train, for example
  `MAJOR.MINOR.PATCH` for public releases and `MAJOR.MINOR.PATCH-rc.N` only
  for internal/closed testing when the channel allows it.
- [ ] Release notes map version name, version code, Git SHA, artifact checksum,
  and target track: internal, closed, open, staged production, or full
  production.
- [ ] Upgrade paths from the previous production version and the latest internal
  test version are installed and smoke-tested. Downgrade is either tested or
  explicitly unsupported because Room migrations are forward-only.

## Signing, AAB, And Play App Signing

- [ ] Local debug builds are clearly separated from release artifacts:
  `./gradlew :app:assembleDebug` and the Android debug keystore are only for
  developer/device checks and are never uploaded, distributed, or used as
  production evidence.
- [ ] Release signing material is provided outside source control. The release
  record names the signing owner, keystore custody location, upload key alias,
  certificate SHA-256 fingerprint, and recovery contact.
- [ ] `scripts/sign_release_artifacts.sh` is run from the private signing
  environment with `RELEASE_KEYSTORE`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_PASSWORD`, and
  `EXPECTED_SIGNING_CERT_SHA256`; attach
  `build/verification/signing/signing.properties` and the certificate reports.
  Failed signing attempts must record `failedTarget` and `reason` without
  persisting keystore passwords or private key material. `ALLOW_DEBUG_KEYSTORE`
  must be unset for production signing.
- [ ] For Google Play, Play App Signing is enabled or its status is explicitly
  recorded. The app signing certificate fingerprint and upload certificate
  fingerprint are both captured because they are different trust anchors.
- [ ] The Play candidate is an Android App Bundle built with release settings:
  `./gradlew :app:bundleRelease`. Record
  `app/build/outputs/bundle/release/app-release.aab` before signing and
  `app/build/outputs/bundle/release/app-release-signed.aab` after external
  signing, including SHA-256, file size, and signing certificate fingerprint.
- [ ] If an APK is used for internal ad hoc validation, it is separately signed
  outside source control, labeled as non-Play evidence, and not confused with
  the Play AAB.
- [ ] APK/AAB inspection confirms no `.litertlm` model binaries, API keys,
  bearer tokens, private hostnames, or release keystore files are bundled, and
  the artifact is a readable APK/AAB with the expected manifest structure.
- [ ] `scripts/scan_android_artifacts.sh` is run against the final APK/AAB and
  `android-artifact-scan.properties` is attached to the release record.
- [ ] `android-artifact-scan.properties` records a machine-readable `reason`
  list whenever artifact scanning fails.
- [ ] Release artifact size is within the documented budget, and model files are
  described as optional/recommended downloads rather than packaged assets.

## Store Metadata And Policy

- [ ] App name, short description, full description, category, and contact
  email are reviewed.
- [ ] Privacy policy or privacy notice URL points to the approved external
  version of `docs/privacy_notice.md`.
- [ ] Google Play Data safety answers match the implemented behavior for local
  Room/DataStore storage, encrypted remote API keys, user-configured remote
  model calls, recommended/custom model downloads, Android permissions,
  external intents, and the absence of first-party analytics upload in this
  codebase.
- [ ] The Data safety form records whether data is collected, shared, encrypted
  in transit, user-deletable, optional, and purpose-limited. Treat user-chosen
  remote model endpoints and model hosts as external recipients where their own
  policies apply.
- [ ] Model downloads are described as large optional/recommended assets and
  not as APK-bundled files. The store policy record's primary chat model id,
  byte size, SHA-256, and upstream revision must match
  `docs/model_capability_profiles.json` and `docs/model_manifest.md`, and the
  verifier must derive the same lightweight local chat alternative and remote
  alternative disclosure values as the record.
- [ ] Required Android permissions and special-access flows are explained in
  user-facing language.
- [ ] Sensitive permission disclosures are complete for `RECORD_AUDIO`,
  `READ_CALENDAR`, `READ_CONTACTS`, legacy `READ_EXTERNAL_STORAGE`,
  `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`,
  `READ_MEDIA_VISUAL_USER_SELECTED`, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
  `PACKAGE_USAGE_STATS`, the Accessibility service, Accessibility gestures
  for phone control, and one-shot MediaProjection consent for current-screen
  OCR.
- [ ] Play declarations or review notes explain why Usage Access,
  Accessibility observation/gestures, phone-control foreground service,
  notifications, calendar/contact reads, media reads, voice input,
  document/share input, external navigation, and reminders are requested only
  for user-confirmed or low-risk user-initiated flows.
- [ ] `docs/store_policy_record.json` is updated and approved, then
  `VERIFY_STORE_POLICY=1 scripts/verify_release_gate.sh` passes. The gate
  checks store listing fields, privacy policy URL, Data safety answers, model
  download disclosure, model capability/profile manifest bindings, manifest
  permission purposes, special-access disclosures, the current privacy notice
  SHA, and a review evidence file with matching SHA-256.

## Screenshots

- [ ] Chat home screen screenshot uses non-sensitive sample text.
- [ ] Model manager screenshot shows local/remote model controls without API
  keys or private endpoints.
- [ ] Confirmation sheet screenshot shows an example low-risk tool request.
- [ ] Background tasks or audit screenshot uses synthetic task names and
  redacted metadata.
- [ ] Screenshots do not include real contacts, notifications, clipboard text,
  current-screen text, API keys, emails, phone numbers, or internal hostnames.
- [ ] Every screenshot attached to release validation is listed in
  `docs/release_validation_record.json` with its sanitized flag, matching
  screenshot SHA-256, and a matching `release-screenshots.properties` report
  path/SHA-256. Each linked screenshot file must be PNG evidence captured by
  `scripts/capture_release_screenshots.sh`; text or placeholder files are not
  accepted.
- [ ] Every screenshot report includes the matching UI dump path/SHA-256,
  `visualRegression=passed`, and the expected `requiredText` contract for that
  release screen. The release validation verifier must reject screenshots whose
  UI dump does not contain the expected visible text for chat home, model
  manager, confirmation, and background/audit screens.

## Privacy And License

- [ ] `docs/privacy_notice.md` is reviewed by release, security, and legal
  owners before publication. Record the approved current notice SHA in
  `docs/privacy_review.json`; every role approval must include an evidence file
  path and matching SHA-256. Then run `VERIFY_PRIVACY_REVIEW=1
  scripts/verify_release_gate.sh`.
- [ ] All four recommended model downloads in `docs/model_manifest.md` have
  manually verified manifest repository, pinned upstream revision, license
  name, license source URL or file path, redistribution decision, attribution
  or notice requirements, reviewer, review date, review evidence file path, and
  matching review evidence SHA-256. For Hugging Face-hosted models, the license
  source must point to a concrete license, notice, terms, or model-card file
  such as a `blob`, `raw`, or `resolve` URL; the repository homepage is not
  acceptable as a license source.
- [ ] `scripts/collect_model_license_metadata.sh` is run with `REPORT_FILE`
  before manual model review. Attach the collector report and confirm
  `docs/model_license_metadata.json` includes `licenseSourceCandidates` for
  Hugging Face-hosted models, a non-future UTC `recordedAt` within
  `MODEL_LICENSE_METADATA_MAX_AGE_DAYS` (default 30 days), and concrete
  upstream `modelSha` values. These candidates are reviewer input only; they do
  not approve redistribution and do not replace the concrete license source
  recorded in `docs/model_license_review.json`.
- [ ] `docs/model_license_review.json` is updated from pending to approved
  records before broad distribution, and `VERIFY_MODEL_LICENSES=1
  scripts/verify_release_gate.sh` passes for the release candidate. The gate
  runs `scripts/verify_model_license_review.sh`, requiring all model IDs,
  repositories, and pinned upstream revisions to match `docs/model_manifest.md`,
  metadata to match that manifest and be freshly collected, and approvals to
  include aligned license names, approved redistribution, attribution or notice,
  reviewer, and a valid concrete license source plus a non-future review date
  not older than the metadata collection date, with review evidence bound by
  SHA-256. Each approved model review evidence file must also declare
  `artifactSchema=ModelLicenseReviewApprovedEvidence/v1`, owner, UTC
  `recordedAt`, reproducible command/path, approved target/status, matching
  model ID, reviewer, license name, scope, and redistribution decision.
- [ ] README License wording distinguishes app code from third-party model
  artifacts.
- [ ] No API keys, bearer tokens, private model endpoints, raw prompts, or
  private device-context payloads are present in docs, screenshots, logs, or
  release notes.
- [ ] `scripts/privacy_scan.sh` passes and its `privacy-scan.properties`
  artifact is attached.
- [ ] `privacy-scan.properties` records a machine-readable `reason` whenever
  high-confidence secret patterns are found.

## Build Verification

- [ ] `scripts/verify_local.sh` passes on a clean checkout.
- [ ] CI release operations evidence is complete: the `verify` job records
  `ci-local-verification`, the `emulator-regression` job records connected
  Android test evidence, `release-artifact-archive` uploads APK/AAB/mapping
  artifacts with SHA-256 values, and `protected-signing` runs in the protected
  signing environment with production signing evidence. All four evidence files
  are referenced from `docs/release_operations_record.json` with matching
  SHA-256 values.
- [ ] The GitHub Actions `emulator-regression` job has passed for the release
  candidate SHA, or the release ticket records why CI emulator execution was
  unavailable and links an equivalent local `scripts/regression_emulator.sh`
  report. The CI job must upload `android-emulator-regression-evidence` with
  `regression-emulator.properties`, nested emulator/device reports,
  instrumentation output, screenshot/UI/logcat artifacts, and the emulator log.
- [ ] `VERIFY_MODEL_URLS=1 scripts/verify_local.sh` is run when model URL
  availability/provenance needs fresh evidence. License readiness is still
  reviewed manually.
- [ ] `PERF_BASELINE_FILE=<rc perf-baseline.properties>
  scripts/verify_release_gate.sh` passes before release sign-off. Set
  `PUBLIC_RELEASE=1 EXPECTED_SIGNING_CERT_SHA256=<production upload cert>` when
  checking the public-distribution gate. The perf baseline must be from a
  non-emulator `arm64-v8a` device, match the current Gradle `versionName`, bind
  to the release artifact SHA-256, record non-zero measured timings, and use a
  non-future UTC `recordedAt` within the accepted freshness window. If
  `scripts/collect_perf_baseline.sh` fails, attach its `status=failed`
  collector report with `failedTarget`, `reason`, and verifier report linkage.
- [ ] `release-gate.properties` records `failedTarget` and `failedReason` for
  any failed gate run, so the release ticket can link the exact failing child
  report without guessing.
- [ ] `perf-baseline-verification.properties` records a machine-readable
  `reason` list whenever the perf baseline gate fails.
- [ ] Every `performanceSanity` item in `docs/release_validation_record.json`
  references a passed `perf-baseline-verification.properties` report generated
  by `scripts/verify_perf_baseline.sh`, with `target=perf-baseline`, a
  `performanceKey` that matches the record key, `missingFieldCount=0`, and a
  readable `baselineFile` plus matching `baselineSha256`. The linked baseline
  itself must declare `artifactSchema=PerfBaseline/v1`,
  `target=perf-baseline-record`, non-empty `owner`, non-empty
  `collectionCommand`, and a `reproduciblePath` that equals the verified
  baseline path. The verifier report must include non-empty
  `expectedArtifactSha256` and `expectedAppVersion` values, and the linked
  baseline must match those values, come from a non-emulator `arm64-v8a`
  device, record `oomOrAnrObserved=false`, and have a fresh UTC `recordedAt`;
  lightweight status-only, provenance-free, cross-key, or baseline-file swapped
  performance evidence files are not accepted.
- [ ] Release assembly and bundle tasks pass with release minification/resource
  shrinking enabled.
- [ ] ProGuard/R8 mapping files for the release candidate are archived with the
  artifact so crash stacks can be decoded. Attach
  `release-mapping.properties` from `scripts/verify_release_mapping.sh`; the
  `PUBLIC_RELEASE=1` gate requires this mapping check.
- [ ] Public-release AI behavior eval is run with a real planner actual trace
  file. Attach `ai-behavior-eval.properties` and
  `ai-behavior-planning-trace-diff.jsonl`; the report must include
  `artifactSchema=AgentBehaviorEvalVerification/v1`, owner, UTC `recordedAt`,
  reproducible command, `actualTraceSha256`, `traceDiffSha256`, and
  `capabilityMatrixSha256`, plus `requireActualTrace=1` /
  `requireRuntimeTraceSource=1`. Risk coverage must include public-evidence,
  low, medium, high, and sensitive cases; the high-risk external-send case must
  remain second-confirmation gated. Required boundary coverage must come from
  `docs/capability_matrix.json` /
  `CapabilityMatrix.requiredBehaviorEvalBoundaries`, not an untracked shell-only
  list. Each actual trace row must include a machine source such as
  `agent_loop_runtime`,
  `android_instrumentation`, or `device_debug_eval`, plus a UTC
  `traceRecordedAt`. `PUBLIC_RELEASE=1` requires
  `AI_BEHAVIOR_ACTUAL_TRACE_FILE=<actual-trace.jsonl>` and fails closed if the
  file is missing, lacks runtime provenance, is stale, mismatched, or has extra
  rows. Stale means older than 30 days by default; override only with
  `AI_BEHAVIOR_ACTUAL_TRACE_MAX_AGE_DAYS=<days>` when release policy explicitly
  accepts a different window. Use `scripts/collect_ai_behavior_actual_trace.sh`
  for the deterministic local `agent_loop_runtime` trace collection step; do not
  replace it with a hand-copied fixture file.

## Test Matrix

- [ ] `scripts/regression_emulator.sh` passes on a prepared arm64 AVD, or the
  release record explains why emulator validation was not applicable.
- [ ] `scripts/check_emulator_api_matrix.sh` is run before API matrix
  regression. Attach `emulator-api-matrix-readiness.properties`; if it fails,
  the report must list missing system-image APIs and missing AVD APIs instead
  of silently skipping matrix coverage.
- [ ] If API matrix readiness fails, run
  `scripts/prepare_emulator_api_matrix.sh` first in dry-run mode and attach its
  report. Only run `APPLY=1 scripts/prepare_emulator_api_matrix.sh` after
  explicitly approving the SDK package install and AVD creation list.
- [ ] Once API matrix AVDs are prepared,
  `scripts/regression_emulator_api_matrix.sh` passes for API 28/32/33/34/36.
  Attach the top-level `regression-emulator-api-matrix.properties` plus each
  nested API `regression-emulator.properties` report and matching SHA-256.
  Each nested report must have `status=passed`, `target=regression-emulator`,
  `exit_code=0`, empty `failedTarget`/`reason`, UTC start/finish fields,
  `clean_device=1`, matching API level, an ABI list containing `arm64-v8a`
  with no `x86` or `x86_64` token, a non-empty emulator serial and AVD name,
  source/expected/actual AndroidTest counts, and a readable instrumentation
  output whose final `OK` count matches `actual_android_test_count`.
  Each nested report must also link matching
  `emulator-verification.properties` and `device-verification.properties`
  reports from the same API run.
- [ ] `scripts/install_and_test_device.sh` passes on at least one physical
  arm64 device before a broad release candidate.
- [ ] Validation record includes device serial or AVD name, API level, ABI,
  `CLEAN_DEVICE` value, executed command, instrumentation result, and
  `instrumentation_test_count` from the verification report. Physical device
  reports must also include `exit_code=0`, empty `failedTarget`/`reason`, UTC
  start/finish fields, sufficient `data_free_kb`, and a readable
  `instrumentation_output_file` containing the final `OK` marker. The report
  must also bind `instrumentation_output_sha256`, `logcat_file`, and
  `logcat_sha256`; physical and API-matrix nested device reports without
  matching instrumentation/logcat SHA are not acceptable release evidence. The
  `OK` count must match `instrumentation_test_count`, `test_count` should mirror
  that value, and `debug_apk` / `android_test_apk` must match the
  project-approved debug APK paths. Emulator release records should link
  `regression-emulator.properties` plus the nested emulator/device reports, and
  each linked report must include a matching SHA-256 in
  `docs/release_validation_record.json`.
- [ ] Failed device, emulator, or regression reports include machine-readable
  `failedTarget` and `reason` fields plus any generated screenshot, window dump,
  logcat, or instrumentation evidence paths.
- [ ] Debug device-control and real-app search evidence, when sampled for
  App-control readiness, is attached separately from release physical evidence.
  `run_device_control_debug_eval.sh` fatal reports must keep
  `failedTarget` as the machine target category and `reason` as the specific
  failure, for example `failedTarget=device-selection` plus
  `reason=selected-device-unavailable` when an explicit serial is missing or not
  authorized. Each failed `run_real_app_search_eval.sh` case must include a
  `RealAppSearchCaseArtifact/v1` case report with `failed_step`,
  `result_file_sha256`, `target_resolution_failure_kind`,
  `target_resolution_candidates_json`, and screenshot/UIAutomator/window/logcat
  files with SHA-256. Fatal real-app eval failures before a case can run, such
  as no target apps installed, must put `failedTarget`, `reason`, serial/API/ABI,
  and screenshot/UIAutomator/window/logcat paths plus SHA-256 values in the
  top-level `real-app-search-eval.properties` report.
- [ ] Manual acceptance in `docs/phone_acceptance.md` is sampled for model
  setup, remote-mode privacy, tool confirmation, permissions, background
  reminders, sharing, and multimodal entry points.
- [ ] Manual acceptance records voice input, the Android system document picker,
  and MediaProjection consent separately from scripted regression; these
  system-mediated flows are not marked passed solely from scripts, mocked
  intents, or direct reader/ViewModel calls.
- [ ] Every manual acceptance, flow matrix, and performance sanity item in
  `docs/release_validation_record.json` records a structured evidence object
  with `status=passed`, a summary, an evidence file path that exists in the RC
  artifact bundle, matching `evidenceSha256`, owner, and non-future date; a
  bare string `passed` is not acceptable evidence.
- [ ] Every `manualAcceptance` evidence file is a formal manual acceptance
  report with `status=passed`, `target=manual-acceptance`, a `manualKey` that
  matches the record key, `manualAcceptance=true`,
  `artifactSchema=ManualAcceptanceEvidence/v1`, matching owner/date, non-future
  fresh UTC `recordedAt`, a non-empty command, and `reproduciblePath` equal to
  the evidence file path; lightweight status-only manual evidence files are not
  accepted.
- [ ] Every `manualAcceptance` evidence file also satisfies its key-specific
  contract: remote-mode privacy records no automatic local-memory/raw private
  context send, tool confirmation records visible confirmation plus cancel
  blocking execution, permissions record system prompt and denied recovery,
  voice/file/MediaProjection evidence records system-mediated consent/cancel
  boundaries, public evidence remote calls bind request counts, and mixed
  private/action batch evidence records rejection with no partial action
  execution.
- [ ] Manual acceptance evidence is generated from explicit owner sign-off, for
  example `OWNER="<reviewer>" MANUAL_ACCEPTANCE_ALL=1
  scripts/record_manual_acceptance_evidence.sh`; partial runs must remain
  failed until every required manual key is accepted.
- [ ] Every `flowMatrix` evidence file is a formal release flow report with
  `status=passed`, `target=release-flow`, a `flowKey` that matches the record
  key, `releaseFlowPassed=true`, `artifactSchema=ReleaseFlowEvidence/v1`,
  matching owner/date, non-future fresh UTC `recordedAt`, a non-empty command,
  and `reproduciblePath` equal to the evidence file path; candidate-only or
  lightweight status-only flow evidence files are not accepted.
- [ ] `localModelDownloadVerification` release flow evidence must explicitly
  record local download verification, SHA-256 verification, storage preflight,
  failure recovery, unavailable download directory handling, SHA failure cleanup,
  insufficient-storage download failure, missing pending-download task recovery,
  remote fallback explanation, and lightweight alternative explanation.
  `customModelImportOrUrlRejection` evidence must explicitly record `.litertlm`
  import coverage, local non-`.litertlm` import rejection, import storage
  preflight, empty-file rejection, temp cleanup on copy failure, HTTPS-only
  custom download policy, non-`.litertlm` URL rejection, invalid/credentialed URL
  rejection, and successful local import registration as `UnverifiedCustom`.
- [ ] `shareAndPickerInput` release flow evidence must explicitly record
  ACTION_SEND text staging, remote-mode text-share protection, remote vision
  image attachment staging through an endpoint/model that supports
  OpenAI-compatible `image_url` content, unsupported-vision protection, no
  implicit image OCR, per-send remote vision preview confirmation, cancel path
  that keeps the remote runtime idle, verified local-vision model image
  attachment staging, local runtime image-attachment sending, LocalOnly
  persistence, prompt metadata redaction, remote runtime idle protection for
  local vision, unsupported local-vision OCR skip behavior, bounded document
  excerpts, and picker attachment prompting. The local-vision runtime send count
  must be at least one, while remote runtime calls, unsupported local runtime
  image sends, and unsupported image OCR invocations must be zero.
- [ ] `privacyAndDataControls` release flow evidence must explicitly record
  the App privacy notice entry, long-term memory clear and forget controls,
  current-session deletion, remote configuration clearing, and deletion/control
  copy visible to the user.
- [ ] `upgradeInstall` release flow evidence must explicitly record `adb install -r`
  upgrade execution, first-install timestamp preservation, last-update timestamp
  refresh, versionCode increase, and instrumentation coverage. `encryptedApiKeyClear`
  must prove blank-key save clears the encrypted secret and legacy plaintext
  preference is not populated. `sessionPersistence`, `memoryControls`, and
  `remindersAfterReboot` must each expose their create/restore/delete, memory
  create/forget/clear, boot/package-replaced reschedule, catch-up, stale-running
  recovery, and metadata-only audit checks as machine-readable fields.
- [ ] `voiceInput` release flow evidence must explicitly record the near-field
  voice disclosure, one-shot transcript draft with no auto-send, microphone
  permission failure/recovery behavior, and voice-capture cancellation.
  PocketMind behavior after microphone denial/grant recovery must be verified;
  Android system permission dialog copy and button interaction are manual/device
  acceptance evidence, not a required Compose AndroidTest gate.
- [ ] `adaptiveUi` release flow evidence must explicitly record large-font
  reachability, landscape reachability, and accessible labels/actions for core
  controls.
- [ ] `accessibilityText` and `recentMediaOcr` release flow evidence must
  explicitly record confirmation/cancel paths, LocalOnly metadata, trace
  recording, screenshot/image OCR routing, one-item recent screenshot limits,
  and remote-leakage fail-closed coverage.
- [ ] Release flow evidence is generated from explicit owner sign-off, for
  example `OWNER="<reviewer>" RELEASE_FLOW_ALL=1
  scripts/record_release_flow_evidence.sh`; partial runs must remain failed
  until every required release flow is accepted.
- [ ] Remote model manual acceptance samples both a single public evidence
  tool request and a multi-evidence question such as two-location comparison;
  mixed private/action tool batches must fail closed before execution.
- [ ] `scripts/live_remote_emulator.sh` defaults to emulator validation and only
  accepts a physical serial when `POCKETMIND_LIVE_REMOTE_TARGET=device` is set.
  It reports `failedTarget` and `reason` for missing configuration,
  emulator/device selection, install/configuration, remote request, and
  expected-response failures, and links screenshot, UI dump, and logcat
  evidence paths without persisting API keys.
- [ ] Matrix covers at least: API 28 minimum behavior, API 32 legacy storage
  permission behavior, API 33 media/notification permissions, API 34 selected
  visual media access, API 36 target behavior, and one physical arm64 device
  with realistic LiteRT-LM CPU/GPU fallback. Each API matrix row must include
  a nested per-API regression evidence file path that exists in the RC
  validation artifact bundle and a matching `evidenceSha256`; lightweight
  status-only evidence files are not accepted.
- [ ] Matrix covers first install, upgrade install, local model download and
  verification, custom model import or custom URL rejection path, remote model
  HTTPS configuration, encrypted API key clear, session persistence, memory
  controls, privacy/data controls, reminders after reboot, share/picker input,
  voice input, Accessibility text, recent media OCR, and MediaProjection
  cancellation, including one-shot MediaProjection consent and LocalOnly
  current-screen screenshot OCR remote-continuation blocking.
  Candidate evidence files marked `candidateOnly=true`,
  `releaseFlowPassed=false`, or `target=release-flow-matrix-candidate-evidence`
  are reviewer input only and must not be referenced as passed flow evidence in
  `docs/release_validation_record.json`.
- [ ] Performance sanity is recorded for first launch, model load, first token,
  streaming stop/cancel, background reminder delivery, and memory pressure on
  the largest recommended model expected for the channel. Each record must point
  to the perf baseline verifier report, not a hand-written summary.
- [ ] `docs/release_validation_record.json` is updated and approved, then
  `VERIFY_RELEASE_VALIDATION=1 scripts/verify_release_gate.sh` passes. The gate
  checks emulator regression, rejects `emulator-*` serials as physical-device
  instrumentation evidence, verifies API matrix coverage, manual acceptance,
  system-mediated flows, remote public evidence samples, mixed-batch rejection,
  sanitized screenshots with matching SHA-256, flow coverage, and performance
  sanity evidence.

## Crash, ANR, And Monitoring

- [ ] Monitoring owner and signal source are named. For Play-distributed builds,
  Android Vitals in Play Console is the minimum source; if a crash SDK is added
  later, its privacy disclosure and opt-in/retention behavior must be reviewed
  before release.
- [ ] Release candidate has a crash/ANR smoke window on internal or closed
  testing with no unresolved launch crash, install crash, crash loop, fatal
  native LiteRT-LM failure, or reproducible ANR. The smoke window evidence file
  path and SHA-256 are recorded in `docs/release_operations_record.json`. Use
  `scripts/collect_crash_anr_smoke_evidence.sh` with the device verification
  report, instrumentation output, and captured `adb logcat` to generate this
  evidence. The operations gate parses the smoke report and requires
  `status=passed`, analyzed logcat, all crash/ANR/LiteRT counters at 0, all
  five `no*` booleans true, and matching SHA-256/size values for the nested
  device report, instrumentation output, and logcat files.
- [ ] Manual validation captures `adb logcat`, tombstone/native crash evidence,
  and ANR traces for any failure; release notes link the issue or state that no
  crash/ANR was observed in the RC window.
- [ ] Crash-free and ANR thresholds for staged rollout are written in the
  release record, with a named person watching the first 24 hours after each
  rollout step. Monitoring setup and rollback plan evidence files are recorded
  with SHA-256 values and typed properties content. Monitoring evidence must
  declare `status=passed`, `target=release-monitoring-evidence`,
  `operationsRecordField=monitoring.evidence`, owner, signal sources, watcher,
  thresholds, and crash SDK privacy review; rollback evidence must declare
  `status=passed`, `target=release-rollback-evidence`,
  `operationsRecordField=rollback.evidence`, owner, rollback criteria, decision
  channel, Play version-code policy, model-manifest rollback path, data
  compatibility, and previous-known-good or initial-release binding.
- [ ] `docs/release_operations_record.json` is updated and approved, then
  `VERIFY_RELEASE_OPERATIONS=1 scripts/verify_release_gate.sh` passes. The gate
  checks CI local verification, connected Android tests, release artifact
  archive evidence, protected production signing evidence, Android Vitals
  coverage, crash/ANR smoke status, rollout watcher, rollout thresholds,
  monitoring evidence, crash/ANR smoke evidence, rollback evidence, previous
  known-good metadata or initial-release exemption, rollback criteria, Play
  version-code policy, model manifest rollback path, and data compatibility
  notes.

## Rollback

- [ ] Previous known-good APK/AAB, version code, commit SHA, and release notes
  are available.
- [ ] Rollback owner and decision channel are named.
- [ ] Rollback criteria are defined, including install failure, crash loop,
  model download verification failure, privacy boundary failure, or critical
  tool execution regression.
- [ ] For staged Play rollout, the immediate first action is documented: halt
  rollout, keep collecting Android Vitals/user reports, and decide whether to
  resume, replace, or ship a fixed build.
- [ ] If production has already advanced past the prior artifact, the rollback
  plan uses a new artifact with a higher `versionCode`; Play cannot roll users
  back to a lower version code as an ordinary update.
- [ ] Model download manifest changes can be reverted without requiring an APK
  code change when the release process supports remote metadata updates; if not,
  the required APK rollback path is documented.
- [ ] User data compatibility is reviewed: Room migrations are forward-only, so
  downgrades must be tested or explicitly unsupported.

## Final Gate

- [ ] Release readiness remaining items are either complete or accepted by the
  release owner with a dated risk note.
- [ ] The release artifact, checksums, test logs, privacy/license review, and
  screenshots are attached to the release record.
- [ ] The release owner signs off.
