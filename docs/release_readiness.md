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
- Memory is documented as a lightweight local index. Action planning is
  documented as experimental model planning with rule fallback.
- Historical prepared-emulator regression has passed on
  `focus_agent_api36_arm64` / `emulator-5554` (API 36, `arm64-v8a`) with
  `scripts/verify_emulator.sh`. Current release-candidate validation should
  use `scripts/regression_emulator.sh` and record the runner-reported
  `instrumentation_test_count`; current `app/src/androidTest` source contains
  23 `@Test` methods across 9 instrumentation test classes.

## Remaining

- Review `docs/privacy_notice.md` with release, security, and legal owners
  before publishing it as an external policy.
- For all four recommended model downloads, manually verify the upstream model
  license name, license URL or file path, redistribution rights, attribution or
  notice requirements, reviewer, and review date. Record the result in
  `docs/model_manifest.md` or the release checklist. `VERIFY_MODEL_URLS=1`
  checks URL/content metadata only; it does not establish license readiness.
- Configure release signing outside source control.
- Run a final release-candidate validation pass on target physical hardware
  before broad distribution; emulator validation does not cover all LiteRT-LM
  GPU/performance behavior.
