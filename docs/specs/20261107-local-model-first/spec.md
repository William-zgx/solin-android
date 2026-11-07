# Local model first initial planning

Date: 2026-11-07

Status: debug-only experiment. Default and release behavior are unchanged (RuleFirst).

Code base: `simply/zouguoxue-simply@20361499`

## 1. Behavior

Local initial planning (the first tool of a local run, before anything is observed) has been rules-only:
skills, then the rule intent classifier, then the action model (`InitialToolPlanner.planToolIfSupported`).
This change lets a verified on-device model propose that first tool, behind a developer setting.

`InitialPlanningMode.LocalModelFirst` is selected only for runs that are not remote, and only when the
setting holds a strategy other than RuleFirst:

| Strategy | Who proposes the first tool |
| --- | --- |
| `RuleFirst` | Rules only, exactly as before. Default; release builds always use it. |
| `ModelFirstWithRuleFastPath` | Rules first; the model is asked only when the rules plan nothing. The recommended non-default strategy. |
| `ModelFirst` | The model is asked first on every turn, and its choice (including "no tool") stands; the rules run only when its proposal fails. Ablation only, to measure the model on every request. |

A model proposal goes through these steps (`InitialToolPlanner.planLocalModelFirst`):

1. Only the first segment of a sequential request ("X然后Y") is planned, matching the rule planner.
2. Candidates are registry tools that pass `isFirstStepPlanningCandidate()`: no in-app gestures, device
   control, orchestration or extension tools, and no Planning, UxInteraction or McpTool tags.
3. `ToolRetriever.retrieve(request, candidates)` returns at most 12 ranked tools. If it returns none, the
   model is not called.
4. The model is offered the shortlist plus an `answer` choice for "no tool". `answer` is never registered,
   so nothing can execute it.
5. The model runs through `ActionPlanningRuntime.plan(input, modelPath, toolSpecs)`, unchanged and owned
   by the runtime slice.
6. A proposal is accepted only if the model itself drafted it (`usedModel` and a Draft plan), the tool is in
   the shortlist, and the sequential-tail guards pass. It is then built by the same `buildInitialToolPlan`
   as a rule draft: registry validation, then `SafetyPolicy.evaluate`, then `UseTool`. `AgentLoopRuntime`
   hands it to `requestToolConfirmation`, so confirmation and `ConfirmationBypassPolicy` apply exactly as
   for rule plans. The plan carries `plannedByModel = true` and routes as `IntentRoutingPath.ModelToolCall`.

Each attempt appends one `IntentRouted` (ModelToolCall) trace step. Its reasons are short tags: the outcome,
`strategy=`, `retrieved_count=`, the output shape `call:name{argument keys}`, and `retrieved=<tool>`.
Argument values are never recorded, and every tag fits the 80-character persisted limit.

## 2. Fail-closed behavior

Every failure yields the RuleFirst result. Under the fast path the rules have already run and planned
nothing, so the result is "no tool". Under ModelFirst the rule planner runs after the failure.

| Condition | Result |
| --- | --- |
| Setting unset, unknown value, `RuleFirst`, or a release build | RuleFirst; model not called |
| No verified planning model path | RuleFirst; model not called |
| No installed profile with MobileActionPlanning | RuleFirst; model not called |
| Fast path and the rules planned a tool | That rule plan; model not called |
| Retriever returns no tool | RuleFirst; model not called |
| Model throws | RuleFirst |
| Blank output, prose, unparseable output, or more than one call | RuleFirst |
| Unregistered tool | RuleFirst |
| Registered tool that was not in the shortlist | RuleFirst |
| Invalid arguments (registry validation) | RuleFirst |
| SafetyPolicy rejects | RuleFirst |
| Tool needs a local model before a sequential tail | RuleFirst |
| Model chooses `answer` | No tool; plain answer. Under ModelFirst this stands even where rules would plan |
| Accepted tool with confirmation policy Required | Parks in `AwaitingUserConfirmation`, same as a rule plan |

## 3. Flag

- Key: `local_planning_strategy` in the `solin_settings` DataStore. Values are exact strategy names; any
  other value reads as RuleFirst (`LocalPlanningStrategy.parse`).
- `ModelRepository.localPlanningStrategy()` returns RuleFirst unless `BuildConfig.DEBUG`.
- `initialAgentRunOptions` (ChatControllerHelpers) builds `LocalModelFirst` options only for non-remote
  runs. When the strategy is RuleFirst it builds exactly the previous options and never reads the model path.
- The planning model path is `verifiedObservationActionModelPath()`: the active Gemma chat model when it is a
  verified planning model, otherwise the default or high-quality Gemma model, then `mobile-action-270m`.
- `ChatController` builds the options lazily, so the settings and Room reads first run inside the
  generation block on the IO dispatcher.
- There is no UI or other debug entry point yet. See section 7.

## 4. Auto placement

Initial planning runs before Auto placement, with local run options: the first pass of an Auto send is
never remote. A chat route then goes through `prepareInitialChatRun`, whose placement inputs
(`chatPlacementInputs`) never read `AgentRunOptions` (`requiresTools` and `requiresToolLoop` stay false).
So the planning mode cannot route a turn remote. The tests `ChatPlacementInputsTest.autoPlacesALocalModelFirstTurnLikeARuleFirstTurn`
and `SolinViewModelTest.localPlanningStrategyFromTheModelRepositoryShapesOnlyLocalRouteOptions` cover this.

Under ModelFirst, if the model chooses `answer` where the rules would have planned a tool, the turn becomes
a chat turn and is placed like any other chat turn.

## 5. ToolRetriever

`ToolRetriever.retrieve(request, candidates, limit = 12)` is a pure, deterministic function. It returns a
subset of the candidates, most relevant first, with ties kept in candidate order. It reads only a tool's
name, title, description and planning hint, never its argument schema, so the same shortlist serves a text
`call:` prompt and a native tool-calling prompt. A tool scores on curated trigger phrases and request
shapes (in-app search, arithmetic) plus word overlap with its own text. Below a minimum score it is dropped,
so most plain questions retrieve nothing.

Measured on the lab's 173-case `initial_planning.jsonl` (100 cases with a gold tool):

| k | Gold tool retrieved |
| --- | --- |
| 8 | 99/100 |
| 12 | 99/100 |
| 16 | 99/100 |

- The one miss is `observe_current_screen`, which the first-step scope excludes by design.
- The average shortlist is 3.7 tools, so the minimum score, not k, bounds the list.
- 13 of the 73 no-tool cases retrieve nothing and never reach the model; the rest reach it with `answer`.
- The trigger tables were written with this dataset's misses in view, so these numbers are not a held-out
  estimate. The lab's own retriever measured 79/100 at k=8.

## 6. Lab evidence (desktop proxy, not device evidence)

Ollama with `qwen3.5:2b` on a desktop, 151 initial-planning cases:

| Configuration | Correct |
| --- | --- |
| Rules (R) | 127/151 |
| `call:` text format, full registry (M-full) | 63/151 |
| Same, top-8 retrieval (M-topk) | 47/151 |
| Rules then model fallback (R+M) | 127/151 |
| Native tool calling, full registry (N-full) | 110/151 |

Caveats: this is a different model, runtime and hardware than Gemma 4 E2B/E4B on LiteRT-LM 0.12.0. M-topk
used the lab's retriever (recall@8 79/100), not this one.

Reading:

- With the text format, the model alone is far below the rules.
- Rules then model neither gained nor lost anything in the proxy.
- Native tool calling is much stronger but still below the rules.

That is why the fast path (R+M) is the non-default strategy, and ModelFirst is an ablation.

## 7. Pending device evidence and follow-ups

- Gemma 4 E2B/E4B accuracy on device with the `call:` format, for both strategies.
- Per-turn latency. The fast path adds a model call only on rule misses; ModelFirst adds one every turn.
- Memory and engine swaps. The initial `HybridActionPlanningRuntime` holds one engine. Alternating the
  `mobile-action-270m` rule-path model with the Gemma planner reloads it, and Gemma may be loaded twice
  (chat runtime and planner). This needs device measurement and a runtime-slice decision.
- A debug entry point to set `local_planning_strategy` (debug menu or adb intent); none exists yet.
- Native tool calling waits on the runtime slice. The planner already passes a plain `ToolSpec` list, so
  it does not depend on the prompt format.
- Recall on a held-out request set.
- Links from `docs/index.md` and `agent_core_modules.md`, for the integrator.

## 8. Code map

| Area | File |
| --- | --- |
| Modes, strategies, options | `orchestration/AgentRunOptions.kt` |
| LocalModelFirst planning | `orchestration/InitialToolPlanner.kt` |
| Planning switch (one branch) | `orchestration/AgentLoopRuntime.kt` |
| Shortlist and first-step scope | `tool/ToolRetriever.kt` |
| Setting | `data/Stores.kt`, `data/PreferenceSettingsStore.kt`, `data/ModelRepository.kt` |
| Run options | `presentation/ChatControllerHelpers.kt`, `presentation/ChatController.kt` |
| Tests | `LocalModelFirstPlanningTest`, `AgentRunOptionsTest`, `ToolRetrieverTest`, `ChatPlacementInputsTest`, `PreferenceSettingsStoreTest`, `SolinViewModelTest` |
