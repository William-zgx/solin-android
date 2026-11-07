# SolinScreen Split — Completed Work

**Status:** the Wave 1–6 extraction is complete. Current UI ownership is in
[Agent core modules](../../agent_core_modules.md#ui-layer); this page preserves the
reason for the split and its remaining contracts.

## Original goal and outcome

The original plan inventoried a 6,324-line screen with 85 composables and a large
callback surface. It proposed extracting the assistant surface into component
files and replacing individual callbacks with a `SolinScreenActions` object.
The actual implementation uses the current screen signature and controller
callbacks; the proposed callback object is not the current API.

The detailed historical inventory, proposed code and migration steps remain in
the [original plan at `cb7e4918`](https://github.com/William-zgx/solin-android/blob/cb7e4918057fb3a6ed71df87ba24ecc8875567c7/docs/plans/solin-screen-split.md).
Wave-by-wave results remain in [the optimization history](../../optimization_plan_weaknesses.md).
Do not apply those old line numbers or snippets to the current tree.

## Current implementation

Checked on 2026-11-04 against `cb7e4918`: `ui/SolinScreen.kt` has 497 lines.
It assembles the assistant shell from components under `ui/components/`.

| Area | Current composition |
| --- | --- |
| Conversation shell | Top bar, session drawer, message list, composer and empty state |
| Modal decisions | `SolinSheetHost` and the relevant disclosure, action-draft and outcome sheets |
| App-wide controls | Model management, memory, trust/privacy and background task surfaces |
| Message content | Message bubble/chrome and `MessageMarkdown` |
| Resource state | `ResourcePressureBadge` / `ResourcePressureOverlay` |
| Theme | `ui/theme/Theme.kt` |

The earlier 17-component count and roughly 1,600-line result were intermediate
snapshots, not current inventory. Further changes should improve ownership or
behavior under [the depth rules](../../architecture_depth.md), not add files to meet
an arbitrary smaller line count.

## Contracts retained

- State and decisions belong to the presentation layer; the screen observes
  `ChatUiState` and forwards user actions through callbacks.
- UI code must not inject tool actions or directly depend on `ToolExecutor` /
  `ActionExecutor`; architecture tests enforce this boundary.
- Existing `testTag` values and visible system-mediated flows remain part of the
  device acceptance contract.
- Closing one sheet must not leave a different sheet in a stuck state. Session
  switching, draft confirmation, disclosure and generation cancellation must
  continue to work together.

## Validation

Current evidence belongs in [validation_report.md](../../validation_report.md).
For a UI change, compile the affected surface and use relevant Compose tests such
as `SolinScreenDisplayTest`, `AppShellDisplayTest`, `ComposerMidRunSteeringTest`
and `SolinExternalOutcomeUiTest`. System document picking, voice input and
MediaProjection consent still require [device acceptance](../../phone_acceptance.md);
a completed component extraction is not proof those flows passed.
