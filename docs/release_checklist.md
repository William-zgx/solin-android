# Release Checklist

Use this checklist for each release candidate. It is intentionally operational:
every checked item should have a named owner, date, and artifact link in the
release ticket or PR.

## Scope

- [ ] Release version name and version code are final.
- [ ] Release branch, commit SHA, and changelog are recorded.
- [ ] Release owner, reviewer, date, and target channel are recorded.
- [ ] APK/AAB artifact path, SHA-256, signing certificate fingerprint, and
  verification evidence links are recorded.
- [ ] Open blockers are either resolved or explicitly accepted by the release
  owner with a dated risk note.
- [ ] Target audience is clear: internal testing, closed testing, or broader
  distribution.
- [ ] Known unsupported capabilities are called out, especially screenshot
  capture, semantic screen understanding, full PDF parsing, legacy Office
  parsing, image semantic understanding, and arbitrary media OCR.

## Store Metadata

- [ ] App name, short description, full description, category, and contact
  email are reviewed.
- [ ] Privacy policy or privacy notice URL points to the approved external
  version of `docs/privacy_notice.md`.
- [ ] Data safety disclosures match the implemented behavior for local storage,
  remote model calls, model downloads, Android permissions, and external
  intents.
- [ ] Model downloads are described as large optional/recommended assets and
  not as APK-bundled files.
- [ ] Required Android permissions and special-access flows are explained in
  user-facing language.

## Screenshots

- [ ] Chat home screen screenshot uses non-sensitive sample text.
- [ ] Model manager screenshot shows local/remote model controls without API
  keys or private endpoints.
- [ ] Confirmation sheet screenshot shows an example low-risk tool request.
- [ ] Background tasks or audit screenshot uses synthetic task names and
  redacted metadata.
- [ ] Screenshots do not include real contacts, notifications, clipboard text,
  current-screen text, API keys, emails, phone numbers, or internal hostnames.

## Privacy And License

- [ ] `docs/privacy_notice.md` is reviewed by release, security, and legal
  owners before publication.
- [ ] All four recommended model downloads in `docs/model_manifest.md` have
  manually verified license name, license source URL or file path,
  redistribution decision, attribution or notice requirements, reviewer, and
  review date.
- [ ] README License wording distinguishes app code from third-party model
  artifacts.
- [ ] No API keys, bearer tokens, private model endpoints, raw prompts, or
  private device-context payloads are present in docs, screenshots, logs, or
  release notes.

## Signing And Build

- [ ] Release signing configuration is provided outside source control.
- [ ] Signing keystore, key alias, and credential access are owned by the
  release owner, not committed to the repository.
- [ ] `scripts/verify_local.sh` passes on a clean checkout.
- [ ] Release APK size is within the documented budget.
- [ ] APK inspection confirms no `.litertlm` model binaries are bundled.

## Device And Emulator Validation

- [ ] `scripts/regression_emulator.sh` passes on a prepared arm64 AVD, or the
  release record explains why emulator validation was not applicable.
- [ ] `scripts/install_and_test_device.sh` passes on at least one physical
  arm64 device before a broad release candidate.
- [ ] Validation record includes device serial or AVD name, API level, ABI,
  `CLEAN_DEVICE` value, executed command, instrumentation result, and
  `instrumentation_test_count` from the verification report. Emulator release
  records should link `regression-emulator.properties` plus the nested
  emulator/device reports.
- [ ] Manual acceptance in `docs/phone_acceptance.md` is sampled for model
  setup, remote-mode privacy, tool confirmation, permissions, background
  reminders, sharing, and multimodal entry points.

## Rollback

- [ ] Previous known-good APK/AAB, version code, commit SHA, and release notes
  are available.
- [ ] Rollback owner and decision channel are named.
- [ ] Rollback criteria are defined, including install failure, crash loop,
  model download verification failure, privacy boundary failure, or critical
  tool execution regression.
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
