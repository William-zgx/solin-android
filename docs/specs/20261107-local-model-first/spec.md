# Local model first initial planning

Date: 2026-11-07

Status: debug-only experiment. Default and release behavior are unchanged (RuleFirst).

Code base: `simply/zouguoxue-simply@20361499`

## 1. Behavior

Local initial planning (the first tool of a local run, before anything is observed) is rules-first:
skills, then the rule intent classifier (`InitialToolPlanner.planToolIfSupported`). The retired 270M action
model no longer resolves, so the default path behaves exactly as "no verified action model". A verified
on-device Chat model may instead propose that first tool behind a developer setting.

`InitialPlanningMode.LocalModelFirst` is selected only for runs that are not remote, and only when the
setting holds a strategy other than RuleFirst:

| Strategy | Who proposes the first tool |
| --- | --- |
| `RuleFirst` | Rules only, exactly as before. Default; release builds always use it. |
| `ModelFirstWithRuleFastPath` | Rules first; the model is asked only when the rules plan nothing. The original non-default rule-gap fallback. |
| `RuleFirstGatedModel` | Rules first; a negated request stops; a local high-confidence action-intent gate must open before the model may fill a rule gap. Every gate failure means no tool. |
| `ModelFirst` | The model is asked first on every turn, and its choice (including "no tool") stands; the rules run only when its proposal fails. Ablation only, to measure the model on every request. |

A model proposal goes through these steps (`InitialToolPlanner.planLocalModelFirst`). The two older strategies
may ask the model even when RuleFirst's positive intent-classifier precondition did not fire (the fast
path reaches it only after the rules planned nothing). `RuleFirstGatedModel` also reaches the model only
after a rule miss, but adds the gate below. That dropped precondition is why the default critical-only
mode is not enough: every accepted model proposal asks through the confirmation reason below, while a
rule proposal keeps its previous behavior.

0. A request that starts with a negation (`startsWithActionNegation()`, for example 别启动微信 or 不要分享…)
   returns the RuleFirst result before any model call under the older strategies. `RuleFirstGatedModel`
   first preserves any rule plan, then returns no tool for a negated rule-gap request. A model that matched
   the verb must not act on a negated request.
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
   hands it to `requestToolConfirmation`. A rule plan runs without a prompt in the default critical-only
   mode unless a critical reason applies. A model proposal always asks: `ConfirmationBypassPolicy` returns
   `CriticalApprovalReason.LocalModelProposedStep` (user text 这一步由端侧模型提出，请确认) in both confirmation
   modes, and a critical reason from another rule keeps its own name. The proposal's provenance is read from
   the run trace (`AgentTraceQueries.localPlanningModelProposed`), so a request cannot claim it. Rule plans get
   exactly the reasons they had before. The plan carries `plannedByModel = true` and routes as
   `IntentRoutingPath.ModelToolCall`.

   Threat this defends (T-LM, see `docs/architecture_depth.md`): the models called a tool on 34 of 51
   no-tool requests in the desktop lab. Under critical-only mode, an unprompted model-chosen step such as
   `open_app_by_name` for 别启动微信, or a model-written `web_search` query, would run without the user.

Each attempt appends one `IntentRouted` (ModelToolCall) trace step, and that step is the run's only routing
decision for the proposal (`ToolObservationCoordinator` adds none for it). Its reasons are short tags: the
outcome, `strategy=`, `retrieved_count=`, the output shape, and `retrieved=<tool>`. An accepted proposal records
the argument keys of the call as `call:name{keys}`, at most six keys; a failure records only `text(length)` of the
output, never its content. Argument values are never recorded. The trace store cuts persisted reasons at 80
characters, and the provenance check matches the cut form too, so a long tag does not lose its provenance.

### `RuleFirstGatedModel` action-intent gate

This strategy is available only through the same debug-only setting and keeps RuleFirst as the default and
the only release strategy. After the rules return no plan and the negation guard passes:

1. `HybridActionPlanningRuntime.gateActionIntent` makes a local one-shot on the selected planning model
   through the existing shared `LocalEngine` / dedicated-engine path. It uses a fresh empty-history
   conversation, the existing cancellation and timeout handling, and a 64-token output cap. Busy and other
   unavailable results do not load a second copy of a model the shared engine already holds.
2. The system prompt is the lab suite E `json-prompt` verbatim. The user message is a JSON object with one
   `request` field. The parser accepts exactly one JSON object with exactly `action` (Boolean) and
   `confidence` (number in 0..1); trailing prose, Markdown, missing or extra fields, and wrong types fail.
3. The gate opens only for `action == true && confidence >= 0.9`. Invalid output, low confidence,
   `action=false`, an unavailable engine, Busy, an error, cancellation, or a timeout closes it and returns
   no tool, so the turn continues as an ordinary chat answer.
4. Only an open gate runs `proposeLocalModelTool`: retrieved top-k candidates plus `answer`, the corrected
   `call:` prompt, and the existing strict parser. Accepted proposals take the normal plan path; NoTool and
   Failed both mean no tool. This is a planner candidate gate, never an execution authorization.

In this strategy only, `web_search` is dropped from the retrieved candidates unless the first request
segment contains an explicit search verb: 搜索, 搜一下, 搜搜, 查一下, 查查, 百度, 谷歌, google, search, or look up.
The filter can only reduce model-proposed actions; it is not a safety rule. The trace adds a stable gate
reason such as `local_model_action_gate_open` or `local_model_action_gate_busy`. It never records the gate
prompt, raw model output, confidence text, request arguments, or engine error detail. Accepted proposals
keep the same `proposed_request=` provenance and always ask through `LocalModelProposedStep`.

### C1 Stop cancellation design

The initial route uses option (i): `ChatController` runs the blocking route in `runInterruptible`,
rethrows route `CancellationException`, and calls `ensureActive` before applying any route, action, or chat
outcome. LiteRT one-shots already turn interruption into `Cancelled`, cancel native decode, and close their
conversation in `finally`; the gate maps that result to closed, so no tool-proposal call follows. The same
route-level interruption covers the older LocalModelFirst proposal one-shot. A one-shot timeout remains
`Failed` and also closes the gate.

This avoids threading a new cancellation token through `AgentRunOptions`, `AssistantRouter`, the planner,
and every fake. The wrapped route and its argument resolution use Room/SQLite, DataStore or
SharedPreferences, ordinary `File.inputStream` hashing, and in-memory projections; there is no route-time
NIO `InterruptibleChannel`. Those writes finish their normal `use` / transaction boundaries rather than
having a channel closed by thread interruption. The only interruption-driven native cleanup is the
one-shot's existing audited path.

Regression coverage: `SolinViewModelTest.stopGenerationDuringGatedInitialRouteInterruptsEngineAndAppliesNoOutcome`
uses the real controller/orchestrator/gate with a blocked fake shared engine and proves interruption, no
proposal call, and no post-Stop outcome. `ActionPlannerEngineRoutingTest.actionIntentGateCancelledAndTimeoutFailClosed`
covers direct `Cancelled` and timeout-as-`Failed` closure.

## 2. Fail-closed behavior

Every failure yields the RuleFirst result. Under the fast path the rules have already run and planned
nothing, so the result is "no tool". Under ModelFirst the rule planner runs after the failure.

| Condition | Result |
| --- | --- |
| Setting unset, unknown value, `RuleFirst`, or a release build | RuleFirst; model not called |
| No verified planning model path | RuleFirst; model not called |
| No installed profile with MobileActionPlanning | RuleFirst; model not called |
| Fast path or gated strategy and the rules planned a tool | That rule plan; gate/model not called |
| Gated action-intent gate closed, invalid, unavailable, Busy, cancelled, timed out, or threw | No tool; tool planner not called |
| Retriever returns no tool | RuleFirst; model not called |
| Model throws, or the runtime reports an exception as its failure reason | RuleFirst; trace tag `local_model_planning_error` |
| Request starts with a negation (「别…」「不要…」) | RuleFirst; model not called |
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
  verified planning model, otherwise the default or high-quality Gemma model. The retired
  `mobile-action-270m` is excluded there and from `verifiedActionModelPath()` by the shared verified-model
  resolver, so the default path treats it exactly as "no verified action model". Its catalog entry is
  `RetiredInstalledOnly`: setup and optional downloads no longer offer it, while an installed copy remains
  visible and deletable. That retired row contributes no installed capability or capability profile, so a
  state containing only the 270M takes the same MissingModel and open-then-stop paths as an empty state.
- `ChatController` builds the options lazily, so the settings and Room reads first run inside the
  generation block on the IO dispatcher.
- No UI. The debug-build entry point is an adb broadcast to `DebugRemoteConfigReceiver`, the same receiver
  as the remote-config setup. It validates the extra with `LocalPlanningStrategy.parse` and ignores an unknown
  value (result `RESULT_CANCELED`, nothing written):

  `adb shell run-as com.bytedance.zgx.solin am broadcast --user 0 -n com.bytedance.zgx.solin/.debug.DebugRemoteConfigReceiver --es localPlanningStrategy ModelFirstWithRuleFastPath`

  Release builds ignore the setting regardless (section 3).

## 4. Auto placement

Initial planning runs before Auto placement, with local run options: the first pass of an Auto send is
never remote. A chat route then goes through `prepareInitialChatRun`, whose placement inputs
(`chatPlacementInputs`) never read `AgentRunOptions` (`requiresTools` and `requiresToolLoop` stay false).
So the planning mode cannot route a turn remote. The tests `ChatPlacementInputsTest.localModelFirstLeavesAutoPlacementOfRuleClassifiedTurnsUnchanged`
and `SolinViewModelTest.localPlanningStrategyFromTheModelRepositoryShapesOnlyLocalRouteOptions` cover this.

Under ModelFirst, if the model chooses `answer` where the rules would have planned a tool, the turn becomes a
chat turn. Auto may place that chat turn remote through the normal privacy checks, the same as any chat turn.
It is not forced local.

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

The 20261108 run on branch `edge-lab/lab-lmf` (`lab/results/20261108/SUMMARY.md`) adds production-artifact
and ablation evidence, still not phone evidence:

- On the shipped `gemma-4-E2B-it.litertlm` and `gemma-4-E4B-it.litertlm`, all 100 tool cases per model failed
  with `Unknown tool: function`: the model copied `call:function {"arg":"value"}` from the production
  prompt. The lab `-fix` ablation replaces that shape in **both** the system instruction and user prompt
  with `call:函数名 {"参数名":"值"}`; the user prompt also says to substitute a function from the advertised
  list. `M-topk-fix` / `M-topk-fix-lmf` recovered 77–79/100. Production now ports that same change without
  changing the parser. The observation replanner shares the prompt, so model-proposed replanner steps are
  now possible through its existing gates; **Suite A evidence on production files is pending (D-lab2)**.
- The default 270M path sends about 2630 text tokens, exceeding the shipped
  `mobile-actions_q8_ekv1024.litertlm` 1024-token context, so every call fails with
  `INVALID_ARGUMENT: Input token ids are too long` and falls back to rules. A top-k prompt fits but scores
  0/100 even with the placeholder fix. This is why the 270M is retired from downloads and both default and
  observation action-model resolution rather than kept as a fallback.
- A coordinator offline composite measured rules → negation guard → action-intent gate → model at
  **131–133/151**, with **2/51** no-tool false positives, versus rules at **127/151** with **0** false
  positives. Without the gate it scored **120–125/151**. These are pre-implementation composite numbers;
  end-to-end lab evidence on the final production files is pending (D-lab2).

Reading:

- With the text format, the model alone is far below the rules.
- Rules then model neither gained nor lost anything in the proxy.
- Native tool calling is much stronger but still below the rules.

That is why the model strategies remain non-default and ModelFirst is an ablation. The newer gated strategy
also stays debug-only until its final production-file composite, latency, and device evidence exist.

## 7. Pending device evidence and follow-ups

- Final production-file Suite A observation evidence and the end-to-end gated composite (D-lab2); the
  desktop ablations above do not establish on-device accuracy.
- Per-turn latency. The fast path adds a model call only on rule misses; the gated strategy adds the gate
  and, only when it opens, the tool-proposal call; ModelFirst adds one call every turn.
- Memory and engine swaps. Chat and both planners now try the one loaded `LocalEngine` first. A
  different planning model uses the shared dedicated CPU fallback; a busy or failed chat engine
  fails closed to rules rather than mapping the same model twice. Device memory, latency and model
  alternation still need measurement.
- Native tool calling remains a device spike, not a completed migration. The runtime can return
  declared-only native calls, but this planner still uses the `call:` text format and passes a plain
  `ToolSpec` list.
- Recall on a held-out request set.

## 8. Code map

| Area | File |
| --- | --- |
| Modes, strategies, options | `orchestration/AgentRunOptions.kt` |
| LocalModelFirst planning and gate flow | `orchestration/InitialToolPlanner.kt` |
| Corrected `call:` prompt and local gate one-shot / strict JSON parser | `action/ActionPlanningRuntime.kt` |
| Planning switch (one branch) | `orchestration/AgentLoopRuntime.kt` |
| Shortlist and first-step scope | `tool/ToolRetriever.kt` |
| Setting | `data/Stores.kt`, `data/PreferenceSettingsStore.kt`, `data/ModelRepository.kt` |
| Run options | `presentation/ChatControllerHelpers.kt`, `presentation/ChatController.kt` |
| Tests | `LocalModelFirstPlanningTest`, `AgentRunOptionsTest`, `ToolRetrieverTest`, `ChatPlacementInputsTest`, `PreferenceSettingsStoreTest`, `SolinViewModelTest` |
