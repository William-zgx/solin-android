# Model-Driven App Control Multi-Agent Plan

This plan covers two layers that must stay separate:

- Development collaboration: how multiple engineering agents split design,
  implementation, verification, and review work.
- Runtime control: how Solin uses a verified local Chat or action-planning model
  to decide the next UI tool call after observing the current app screen.

## Goal

Move app search from a fixed script sequence toward a model-observe-act loop:

1. Parse the user request, such as "open Taobao and search for milk".
2. Bootstrap the target app and collect a local-only screen observation.
3. Ask the verified local Chat/action-planning model for exactly one low-risk UI
   tool call.
4. Execute the tool through the existing accessibility control provider.
5. Feed the tool result and fresh observation back to the replanner.
6. Stop when the search result verifier proves the requested query is visible.

The model does not get permission to perform arbitrary device actions. It can
only choose from the existing app-search tool set: open app, observe, tap, type,
submit search, scroll, wait, and back.

## Recent Infrastructure Improvements

These supporting improvements have been merged and are available for use by the model-driven app control system:

- **SolinConstants** — all timeout, retry, and UI tuning values in one place (`SolinConstants.kt`)
- **SolinLog** — structured logging with standard tags including `TAG_TOOL`, `TAG_DEVICE`, `TAG_UI`
- **Network security config** — HTTPS-by-default with loopback exceptions for local model endpoints

## Existing Base

The app already has the runtime skeleton:

- `BuiltInSkillRuntime` has static app-search skills and model-driven bootstrap
  skills. Model-driven bootstrap opens/waits/observes, then delegates following
  UI steps to `ModelObservationReplanner`.
- `AgentLoopRuntime` observes each `ToolResult` and can ask the local
  Chat/action-planning model for the next UI tool when the latest skill is
  model-driven app search.
- `DeviceControlEvalReceiver` exposes `model_driven_app_search` for device
  acceptance, using `AssistantOrchestrator`, `ModelObservationReplanner`, and
  the normal validated `ToolExecutor`.
- `run_mock_target_app_search_eval.sh` has an optional
  `RUN_MODEL_DRIVEN_APP_SEARCH_EVAL=1` path for deterministic emulator targets.
  The model-driven command must pass `verifySearchQuery`,
  `expectedPackageName`, and `expectedAppName`; otherwise the receiver cannot
  attach search-result verification evidence.
- `run_real_app_search_eval.sh` now has an optional
  `RUN_MODEL_DRIVEN_APP_SEARCH_EVAL=1` path that runs the model-driven command
  against selected real apps, passes the expected query/package/app guard into
  the debug receiver, and asserts that at least one step was planned by the
  model and the final search verification is `verified`.

## Development Agent Split

Use sub-agents only for independent work with disjoint ownership:

| Agent | Ownership | Output |
| --- | --- | --- |
| Main integrator | Goal, scope, code review, final merge, device verification | Final implementation and evidence |
| Runtime explorer | `orchestration/`, `skill/`, `tool/` read-only analysis | Current model-observe-act behavior and gaps |
| Eval explorer | `scripts/`, debug receiver, device tests read-only analysis | Acceptance path and artifact contract |
| Runtime worker | Bounded runtime changes, if needed | Small patch under `orchestration/` or `device/` |
| Eval worker | Bounded test/script changes | Small patch under `scripts/` or android tests |
| Verification agent | Independent command execution and report review | Pass/fail evidence and residual risks |

The main integrator keeps all high-impact actions: real-device runs, final
reporting, changes that alter safety policy, and release evidence updates.

## Runtime Contract

Model-driven app control is allowed only when all conditions hold:

- A verified local Chat/action-planning model is installed. E2B/E4B Chat models
  are preferred for observation-to-action planning; the optional low-resource
  mobile action model is a fallback.
- The run is attached to a model-driven app-search skill.
- The previous result includes local-only screen evidence or a validated UI
  action result.
- The requested tool is in the low-risk app-search tool allowlist.
- Step and replan budgets (now centralized in `SolinConstants.AgentLoop`) are still available.

The model receives redacted local observation evidence, not screenshot pixels.
The executor still validates arguments and output schemas before the run can
observe the result.

## Acceptance

Checks for this feature are scoped by environment:

- Unit coverage: model-driven bootstrap replans a UI chain until verification.
- Device coverage: installed local planning model can produce at least one UI
  step from an observed app-search screen. This is optional lab/device coverage:
  the instrumentation test must skip when no verified planning model is
  installed on the target device, and it is not an ordinary CI prerequisite.
- Mock-app eval: optional `RUN_MODEL_DRIVEN_APP_SEARCH_EVAL=1` path exercises a
  deterministic target app when bundled models are prepared. It must pass
  `verifySearchQuery`, `expectedPackageName`, and `expectedAppName` into
  `model_driven_app_search`, then require `searchVerificationStatus=verified`
  and `modelPlannedStepCount >= 1`.
- Real-app eval: optional `RUN_MODEL_DRIVEN_APP_SEARCH_EVAL=1` path runs
  `model_driven_app_search` on selected installed apps and records
  `modelPlannedStepCount >= 1`, injected `verifySearchQuery`,
  `expectedPackageName`, `expectedAppName` guards, replan trace evidence, and
  `searchVerificationStatus=verified`. The debug receiver must fail
  `model_driven_app_search` if the model loop completes without verified search
  evidence.

The static real-app search eval remains a baseline. It proves the accessibility
and target-resolution primitives still work when the model path is disabled.

## Rollout

1. Keep static app-search as fallback.
2. Enable model-driven bootstrap automatically only when a verified local
   Chat/action-planning model is available.
3. Start real-device model-driven acceptance with one stable app, then expand
   `MODEL_DRIVEN_APP_SEARCH_CASES`.
4. Treat failures as observation/replanning bugs unless diagnostics prove the
   target app is unavailable or blocked by device state.
