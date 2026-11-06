# SolinViewModel Split — Completed Work

**Status:** the Wave 1–6 extraction is complete. This page is a historical decision
record, not a queue of further controller extractions. Current ownership is in
[Agent core modules](../agent_core_modules.md#presentation-controllers); new
structural work follows [architecture depth](../architecture_depth.md).

## Original goal and outcome

The original proposal addressed a roughly 6,500-line `SolinViewModel`, with chat,
model loading, tool execution, memory, device context, background tasks, audit and
settings logic mixed together. It proposed eight controllers and a separate
`SolinViewModelCoordinator`.

The implementation evolved through [Wave 1–6](../optimization_plan_weaknesses.md):
logic moved into `presentation/` and orchestration collaborators, while
`SolinViewModel` remained the composition root. The proposed coordinator is not a
current production class. Historical method maps, line numbers and migration
snippets are retained in the
[original plan at `cb7e4918`](https://github.com/William-zgx/solin-android/blob/cb7e4918057fb3a6ed71df87ba24ecc8875567c7/docs/plans/viewmodel-split.md).

## Current implementation

Checked on 2026-11-04 against `cb7e4918`: `SolinViewModel.kt` has 852 lines.
This is a measured snapshot, not a design target or a claim that the class may
never change.

| Concern | Current owner |
| --- | --- |
| Chat dispatch, placement and generation | `ChatController` and its focused collaborators |
| Model loading and download state | `ModelLoadController` |
| Tool confirmation and boundary execution | `ToolExecutionController` |
| Session operations and persistence access | `SessionController`, `PresentationSessionHost` |
| Memory, audit, reminders and voice UI | `MemoryUiController`, `AuditUiController`, `BackgroundTaskController`, `VoiceInputController` |
| Startup state and mode authorization | `InitialChatStateFactory`, `StartupStateRestorer`, `InferenceModeController`, `AutoInferenceAuthorizationCoordinator` |
| Wiring and observable chat state | `SolinViewModel` |

Package boundaries and growth watermarks are enforced by
`PackageBoundaryArchitectureTest`. Do not add a forwarding-only controller to
make a line count smaller; a new module must hide substantive logic.

## Contracts retained

- `ChatUiState` remains the UI state contract; this extraction did not implement
  the proposed multi-flow [UiState split](uistate-split.md).
- Tool execution still passes `ToolExecutionController` and `ToolExecutionBoundary`;
  moving code does not transfer prior confirmation or relax LocalOnly semantics.
- Runtime placement, cancellation, pending confirmation recovery and persistence
  remain atomic contracts across the relevant owners.
- Data-layer APIs are still largely synchronous. Their separate
  [suspend migration proposal](data-layer-suspend-migration.md) is not marked done
  merely because callers moved into controllers.

## Validation and future changes

Historical Wave test counts remain historical evidence. Current commands and
results belong in [validation_report.md](../validation_report.md).
For a future change, verify the affected controller, state/persistence boundary,
and architecture tests; device lifecycle acceptance remains separate. Reopen
this structural work only for a specific behavior or ownership problem, not to
repeat the completed extraction plan.
